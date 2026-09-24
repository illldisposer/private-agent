import 'dart:async';
import 'dart:convert';
import 'package:flutter/services.dart';
import 'package:flutter_tts/flutter_tts.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

// ── Method-Channels (kommuniziert mit Kotlin Accessibility-Service) ─────────
const _kChannel     = MethodChannel('com.privateagent/accessibility');
const _kScreenCh    = MethodChannel('com.privateagent/screen');

typedef StatusCallback  = void Function(String message);
typedef ConfirmCallback = void Function(
    String question, List<String> options, void Function(bool) resolve);

/// Steuert autonom das Android-Gerät auf Basis von Sprachbefehlen.
/// Schickt Screenshot + UI-Tree ans LLM → LLM plant Aktionen → wir führen aus.
class VoiceAgentController {
  StatusCallback?  _onStatus;
  ConfirmCallback? _onConfirm;
  final FlutterTts _tts   = FlutterTts();
  bool _running = false;

  // Gesprächsverlauf für den LLM-Kontext (max. 20 Nachrichten)
  final List<Map<String, String>> _history = [];

  void init({
    required StatusCallback  onStatus,
    required ConfirmCallback onConfirm,
  }) {
    _onStatus  = onStatus;
    _onConfirm = onConfirm;
  }

  void abort() => _running = false;

  // ── Haupteinstiegspunkt ────────────────────────────────────────────────────

  Future<void> runCommand(String userCommand) async {
    _running = true;
    _status('🧠 Plane Aktionen …');

    // Benutzer-Anweisung in History aufnehmen
    _addHistory('user', userCommand);

    // Schleife: LLM → Aktion → Feedback → LLM → …
    for (int step = 0; step < 50 && _running; step++) {
      // 1. Bildschirm erfassen
      _status('📸 Lese Bildschirm …');
      final screenData = await _captureScreen();

      // 2. LLM befragen
      _status('🤖 LLM denkt …');
      final plan = await _askLlm(screenData, userCommand);

      if (plan == null) {
        await _speak('Ich konnte keinen Plan erstellen.');
        break;
      }

      // 3. Fertig?
      if (plan['done'] == true) {
        final msg = plan['message'] as String? ?? 'Aufgabe abgeschlossen.';
        _status('✅ $msg');
        await _speak(msg);
        break;
      }

      // 4. Bestätigung wenn nötig
      if (plan['confirm'] == true) {
        final question   = plan['question']   as String? ?? 'Weiter?';
        final opts       = (plan['options']   as List?)?.cast<String>() ?? [];
        final confirmed  = await _askUser(question, opts);
        if (!confirmed) {
          _status('↩ Übersprungen');
          continue;
        }
      }

      // 5. Aktion ausführen
      final action = plan['action'] as Map<String, dynamic>?;
      if (action == null) break;

      await _executeAction(action);

      // 6. Auf Bildschirmbereitschaft warten
      await _waitForScreenReady();

      // Ergebnis in History
      _addHistory('assistant', 'Aktion ausgeführt: ${action['type']}');
    }

    _running = false;
  }

  // ── Bildschirm erfassen ────────────────────────────────────────────────────

  Future<Map<String, dynamic>> _captureScreen() async {
    try {
      final result = await _kScreenCh.invokeMapMethod<String, dynamic>(
        'captureScreen',
      );
      return result ?? {};
    } catch (e) {
      return {'error': e.toString()};
    }
  }

  // ── Auf Ladebalken / Dialoge warten ───────────────────────────────────────

  Future<void> _waitForScreenReady() async {
    _status('👁 Warte auf Bildschirm …');
    for (int i = 0; i < 30; i++) {
      await Future.delayed(const Duration(milliseconds: 600));
      try {
        final ready = await _kScreenCh.invokeMethod<bool>('isScreenReady');
        if (ready == true) break;
        _status('⏳ Lade … (${(i + 1) * 600}ms)');
      } catch (_) {
        break;
      }
    }
  }

  // ── LLM aufrufen ──────────────────────────────────────────────────────────

  Future<Map<String, dynamic>?> _askLlm(
    Map<String, dynamic> screenData,
    String goal,
  ) async {
    final prefs   = await SharedPreferences.getInstance();
    final baseUrl = prefs.getString('api_base_url') ?? 'https://api.deepseek.com';
    final apiKey  = prefs.getString('api_key') ?? '';
    final model   = prefs.getString('model') ?? 'deepseek-chat';

    // Systempromt mit Screen-Kontext
    final systemPrompt = '''
Du bist ein autonomer Android-Steuerungsagent.
Dein Ziel: "${goal}"

Aktueller Bildschirm:
${jsonEncode(screenData)}

Antworte NUR als JSON in diesem Format:
{
  "done": false,
  "confirm": false,
  "question": "Frage an Nutzer (falls confirm=true)",
  "options": ["Option A", "Option B"],
  "action": {
    "type": "tap|swipe|type|openApp|pressKey|runTermuxCode|wait",
    "x": 0, "y": 0,
    "x2": 0, "y2": 0,
    "text": "",
    "packageName": "",
    "keyCode": 0,
    "code": "",
    "ms": 0,
    "description": "Was diese Aktion macht"
  },
  "message": "Abschlussnachricht (falls done=true)"
}

Regeln:
- Analysiere den UI-Tree und berechne exakte Koordinaten für Taps
- Warte immer auf Ladebalken bevor du weitermachst
- Bei Unsicherheit setze confirm=true und stelle eine Frage
- Bei Termux-Code: schreibe korrekten bash-Code in "code"
- Typ "pressKey": 3=HOME, 4=BACK, 187=RECENT_APPS
''';

    final messages = [
      {'role': 'system', 'content': systemPrompt},
      ..._history,
    ];

    try {
      final resp = await http.post(
        Uri.parse('$baseUrl/v1/chat/completions'),
        headers: {
          'Authorization': 'Bearer $apiKey',
          'Content-Type':  'application/json',
        },
        body: jsonEncode({
          'model':    model,
          'messages': messages,
          'response_format': {'type': 'json_object'},
          'temperature': 0.2,
          'max_tokens': 512,
        }),
      ).timeout(const Duration(seconds: 30));

      if (resp.statusCode != 200) {
        _status('❌ API-Fehler ${resp.statusCode}');
        return null;
      }

      final body    = jsonDecode(resp.body) as Map<String, dynamic>;
      final content = body['choices'][0]['message']['content'] as String;
      return jsonDecode(content) as Map<String, dynamic>;
    } catch (e) {
      _status('❌ LLM-Fehler: $e');
      return null;
    }
  }

  // ── Aktion ausführen ──────────────────────────────────────────────────────

  Future<void> _executeAction(Map<String, dynamic> a) async {
    final type = a['type'] as String? ?? '';
    final desc = a['description'] as String? ?? type;
    _status('▶ $desc');

    switch (type) {
      case 'tap':
        await _kChannel.invokeMethod('tap', {
          'x': (a['x'] as num).toDouble(),
          'y': (a['y'] as num).toDouble(),
        });

      case 'swipe':
        await _kChannel.invokeMethod('swipe', {
          'x1': (a['x']  as num).toDouble(),
          'y1': (a['y']  as num).toDouble(),
          'x2': (a['x2'] as num).toDouble(),
          'y2': (a['y2'] as num).toDouble(),
          'duration': (a['duration'] as num?)?.toInt() ?? 300,
        });

      case 'type':
        await _kChannel.invokeMethod('typeText', {'text': a['text'] ?? ''});

      case 'openApp':
        await _kChannel.invokeMethod('openApp', {'package': a['packageName']});

      case 'pressKey':
        await _kChannel.invokeMethod('pressKey', {'keyCode': a['keyCode'] ?? 4});

      case 'runTermuxCode':
        _status('⚙️ Termux: ${a['code']}');
        final result = await _kChannel.invokeMethod<String>(
          'runTermuxCode',
          {'code': a['code'] ?? ''},
        );
        _addHistory('user', 'Termux-Ausgabe: $result');
        await _speak('Termux abgeschlossen: ${result ?? 'kein Output'}');

      case 'wait':
        await Future.delayed(Duration(milliseconds: (a['ms'] as num?)?.toInt() ?? 1000));

      default:
        _status('⚠️ Unbekannte Aktion: $type');
    }
  }

  // ── Nutzer fragen (TTS + Warten auf UI-Antwort) ───────────────────────────

  Future<bool> _askUser(String question, List<String> options) {
    final comp = Completer<bool>();
    _onConfirm?.call(question, options, comp.complete);
    return comp.future;
  }

  // ── Hilfsmethoden ─────────────────────────────────────────────────────────

  void _status(String msg) => _onStatus?.call(msg);

  Future<void> _speak(String text) async {
    _onStatus?.call('🔊 $text');
    await _tts.speak(text);
    await Future.delayed(const Duration(milliseconds: 200));
  }

  void _addHistory(String role, String content) {
    _history.add({'role': role, 'content': content});
    // Max 20 Nachrichten im Kontext behalten
    if (_history.length > 20) _history.removeAt(0);
  }
}
