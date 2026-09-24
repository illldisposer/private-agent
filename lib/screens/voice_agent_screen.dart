import 'dart:async';
import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:speech_to_text/speech_to_text.dart';
import 'package:flutter_tts/flutter_tts.dart';
import '../services/voice_agent_controller.dart';

/// Vollbild-Voice-Agent-Overlay.
/// Wird über den Agent-Mode-Button geöffnet und verdeckt die gesamte App.
/// Push-to-Talk: Gedrückt halten → Aufnahme, loslassen → Verarbeitung.
class VoiceAgentScreen extends StatefulWidget {
  const VoiceAgentScreen({super.key});

  @override
  State<VoiceAgentScreen> createState() => _VoiceAgentScreenState();
}

class _VoiceAgentScreenState extends State<VoiceAgentScreen>
    with TickerProviderStateMixin {
  // ── Dienste ───────────────────────────────────────────────────────────────
  final SpeechToText _stt     = SpeechToText();
  final FlutterTts   _tts     = FlutterTts();
  final VoiceAgentController _agent = VoiceAgentController();

  // ── Zustand ───────────────────────────────────────────────────────────────
  VoiceState _state       = VoiceState.idle;
  String     _transcript  = '';
  String     _statusText  = 'Halte den Button gedrückt um zu sprechen';
  String     _agentStatus = '';
  bool       _sttReady    = false;

  // Bestätigungs-Optionen vom Agenten
  String?       _confirmQuestion;
  List<String>  _confirmOptions = [];
  Completer<bool>? _confirmCompleter;

  // ── Animationen ───────────────────────────────────────────────────────────
  late AnimationController _pulseCtrl;
  late AnimationController _waveCtrl;
  late Animation<double>   _pulseAnim;

  // ── Wellenform-Simulation ─────────────────────────────────────────────────
  final List<double> _waveHeights = List.filled(32, 0.1);
  Timer? _waveTimer;
  final _rng = math.Random();

  @override
  void initState() {
    super.initState();
    _initAnimations();
    _initStt();
    _initTts();
    _initAgent();
  }

  void _initAnimations() {
    _pulseCtrl = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1200),
    )..repeat(reverse: true);

    _waveCtrl = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 80),
    )..addListener(_updateWave);

    _pulseAnim = Tween<double>(begin: 0.92, end: 1.08).animate(
      CurvedAnimation(parent: _pulseCtrl, curve: Curves.easeInOut),
    );
  }

  void _updateWave() {
    if (_state == VoiceState.listening) {
      setState(() {
        for (int i = 0; i < _waveHeights.length; i++) {
          _waveHeights[i] = 0.1 + _rng.nextDouble() * 0.9;
        }
      });
    }
  }

  Future<void> _initStt() async {
    _sttReady = await _stt.initialize(
      onError: (e) => _setStatus('STT-Fehler: ${e.errorMsg}'),
    );
  }

  Future<void> _initTts() async {
    await _tts.setLanguage('de-DE');
    await _tts.setSpeechRate(0.95);
    await _tts.setPitch(1.0);
    await _tts.setVolume(1.0);
  }

  void _initAgent() {
    _agent.init(
      onStatus: (msg) => setState(() => _agentStatus = msg),
      onConfirm: (question, options, resolve) {
        setState(() {
          _confirmQuestion = question;
          _confirmOptions  = options.isEmpty ? ['Ja', 'Nein'] : options;
          _confirmCompleter = Completer<bool>();
        });
        // TTS-Frage vorlesen
        _tts.speak(question);
        _confirmCompleter!.future.then(resolve);
      },
    );
  }

  // ── Push-to-Talk ──────────────────────────────────────────────────────────

  void _onPttDown() {
    if (!_sttReady) return;
    HapticFeedback.mediumImpact();
    setState(() {
      _state      = VoiceState.listening;
      _transcript = '';
      _statusText = 'Spreche …';
    });
    _waveCtrl.repeat();
    _stt.listen(
      onResult: (r) => setState(() => _transcript = r.recognizedWords),
      listenFor: const Duration(seconds: 60),
      pauseFor:  const Duration(seconds: 4),
      localeId:  'de_DE',
    );
  }

  void _onPttUp() {
    _stt.stop();
    _waveCtrl.stop();
    if (_transcript.trim().isEmpty) {
      setState(() {
        _state      = VoiceState.idle;
        _statusText = 'Nichts erkannt – nochmal versuchen';
      });
      return;
    }
    _processCommand(_transcript.trim());
  }

  Future<void> _processCommand(String command) async {
    setState(() {
      _state      = VoiceState.thinking;
      _statusText = 'Verarbeite: "$command"';
    });

    try {
      await _agent.runCommand(command);
    } catch (e) {
      await _tts.speak('Fehler: $e');
    } finally {
      if (mounted) {
        setState(() {
          _state      = VoiceState.idle;
          _statusText = 'Fertig – halte den Button für neue Anweisung';
        });
      }
    }
  }

  // ── Bestätigung beantworten ───────────────────────────────────────────────

  void _answerConfirm(bool yes) {
    _confirmCompleter?.complete(yes);
    setState(() {
      _confirmQuestion = null;
      _confirmOptions  = [];
      _confirmCompleter = null;
    });
  }

  void _answerOption(int idx) {
    // Option 0 = Ja/Erste, alles andere = Nein
    _answerConfirm(idx == 0);
  }

  // ── Build ─────────────────────────────────────────────────────────────────

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.transparent,
      body: Container(
        decoration: const BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topCenter,
            end:   Alignment.bottomCenter,
            colors: [Color(0xFF0A0E1A), Color(0xFF0D1529), Color(0xFF060B15)],
          ),
        ),
        child: SafeArea(
          child: Column(
            children: [
              _buildTopBar(),
              Expanded(child: _buildCenter()),
              if (_confirmQuestion != null) _buildConfirmCard(),
              _buildStatusBar(),
              _buildPttButton(),
              const SizedBox(height: 32),
            ],
          ),
        ),
      ),
    );
  }

  // ── Top-Bar ───────────────────────────────────────────────────────────────

  Widget _buildTopBar() {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
      child: Row(
        children: [
          GestureDetector(
            onTap: () => Navigator.of(context).pop(),
            child: Container(
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(
                color: Colors.white.withOpacity(0.07),
                borderRadius: BorderRadius.circular(12),
              ),
              child: const Icon(Icons.keyboard_arrow_down,
                  color: Colors.white70, size: 22),
            ),
          ),
          const Spacer(),
          const Text(
            'Voice Agent',
            style: TextStyle(
              color: Colors.white,
              fontSize: 17,
              fontWeight: FontWeight.w600,
              letterSpacing: 0.3,
            ),
          ),
          const Spacer(),
          // Platzhalter für Symmetrie
          const SizedBox(width: 42),
        ],
      ),
    );
  }

  // ── Mitte: Wellenform + Orb ───────────────────────────────────────────────

  Widget _buildCenter() {
    return Column(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        // Statusorb
        AnimatedBuilder(
          animation: _pulseAnim,
          builder: (_, __) => Transform.scale(
            scale: _state == VoiceState.listening ? _pulseAnim.value : 1.0,
            child: Container(
              width: 120,
              height: 120,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                gradient: RadialGradient(
                  colors: _orbColors(),
                ),
                boxShadow: [
                  BoxShadow(
                    color: _orbGlow().withOpacity(0.5),
                    blurRadius: 40,
                    spreadRadius: 8,
                  ),
                ],
              ),
              child: Icon(_orbIcon(), color: Colors.white, size: 46),
            ),
          ),
        ),
        const SizedBox(height: 40),

        // Wellenform
        if (_state == VoiceState.listening)
          _buildWaveform()
        else
          const SizedBox(height: 56),

        const SizedBox(height: 32),

        // Erkannter Text
        if (_transcript.isNotEmpty)
          Container(
            margin: const EdgeInsets.symmetric(horizontal: 28),
            padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 14),
            decoration: BoxDecoration(
              color: Colors.white.withOpacity(0.06),
              borderRadius: BorderRadius.circular(16),
              border: Border.all(color: Colors.white.withOpacity(0.1)),
            ),
            child: Text(
              '"$_transcript"',
              style: const TextStyle(
                color: Colors.white,
                fontSize: 16,
                fontStyle: FontStyle.italic,
                height: 1.5,
              ),
              textAlign: TextAlign.center,
            ),
          ),

        // Agent-Status (laufende Aktion)
        if (_agentStatus.isNotEmpty) ...[
          const SizedBox(height: 16),
          Container(
            margin: const EdgeInsets.symmetric(horizontal: 28),
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
            decoration: BoxDecoration(
              color: const Color(0xFF1A2744),
              borderRadius: BorderRadius.circular(12),
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                const SizedBox(
                  width: 14, height: 14,
                  child: CircularProgressIndicator(
                    strokeWidth: 2,
                    color: Color(0xFF4B7BF5),
                  ),
                ),
                const SizedBox(width: 10),
                Flexible(
                  child: Text(
                    _agentStatus,
                    style: const TextStyle(
                      color: Color(0xFF8FAEF8),
                      fontSize: 13,
                    ),
                  ),
                ),
              ],
            ),
          ),
        ],
      ],
    );
  }

  Widget _buildWaveform() {
    return SizedBox(
      height: 56,
      child: Row(
        mainAxisAlignment: MainAxisAlignment.center,
        crossAxisAlignment: CrossAxisAlignment.center,
        children: List.generate(_waveHeights.length, (i) {
          final h = _waveHeights[i] * 48 + 4;
          return AnimatedContainer(
            duration: const Duration(milliseconds: 60),
            width: 4,
            height: h,
            margin: const EdgeInsets.symmetric(horizontal: 1.5),
            decoration: BoxDecoration(
              color: Color.lerp(
                const Color(0xFF4B7BF5),
                const Color(0xFF7B5CF5),
                _waveHeights[i],
              ),
              borderRadius: BorderRadius.circular(3),
            ),
          );
        }),
      ),
    );
  }

  // ── Bestätigungs-Karte ────────────────────────────────────────────────────

  Widget _buildConfirmCard() {
    return Container(
      margin: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: const Color(0xFF131D38),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: const Color(0xFF2A3D6E), width: 1),
        boxShadow: [
          BoxShadow(
            color: const Color(0xFF4B7BF5).withOpacity(0.15),
            blurRadius: 20,
            spreadRadius: 2,
          ),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Icon(Icons.smart_toy_outlined,
                  color: Color(0xFF4B7BF5), size: 18),
              const SizedBox(width: 8),
              const Text('Agent fragt:',
                  style: TextStyle(color: Color(0xFF8FAEF8), fontSize: 12)),
            ],
          ),
          const SizedBox(height: 8),
          Text(
            _confirmQuestion ?? '',
            style: const TextStyle(
              color: Colors.white,
              fontSize: 15,
              fontWeight: FontWeight.w500,
              height: 1.4,
            ),
          ),
          const SizedBox(height: 14),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: _confirmOptions.asMap().entries.map((e) {
              final isFirst = e.key == 0;
              return GestureDetector(
                onTap: () => _answerOption(e.key),
                child: Container(
                  padding: const EdgeInsets.symmetric(
                      horizontal: 18, vertical: 10),
                  decoration: BoxDecoration(
                    color: isFirst
                        ? const Color(0xFF4B7BF5)
                        : Colors.white.withOpacity(0.08),
                    borderRadius: BorderRadius.circular(12),
                    border: isFirst
                        ? null
                        : Border.all(
                            color: Colors.white.withOpacity(0.15)),
                  ),
                  child: Text(
                    e.value,
                    style: TextStyle(
                      color: isFirst
                          ? Colors.white
                          : Colors.white70,
                      fontWeight: isFirst
                          ? FontWeight.w600
                          : FontWeight.normal,
                      fontSize: 14,
                    ),
                  ),
                ),
              );
            }).toList(),
          ),
        ],
      ),
    );
  }

  // ── Status-Bar ────────────────────────────────────────────────────────────

  Widget _buildStatusBar() {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 28, vertical: 8),
      child: Text(
        _statusText,
        style: const TextStyle(color: Colors.white38, fontSize: 13),
        textAlign: TextAlign.center,
      ),
    );
  }

  // ── PTT-Button ────────────────────────────────────────────────────────────

  Widget _buildPttButton() {
    final isListening = _state == VoiceState.listening;
    return GestureDetector(
      onLongPressStart: (_) => _onPttDown(),
      onLongPressEnd:   (_) => _onPttUp(),
      onTapDown:        (_) => _onPttDown(),
      onTapUp:          (_) => _onPttUp(),
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 150),
        width:  isListening ? 96 : 80,
        height: isListening ? 96 : 80,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          color: isListening
              ? const Color(0xFFEF4444)
              : const Color(0xFF4B7BF5),
          boxShadow: [
            BoxShadow(
              color: (isListening
                      ? const Color(0xFFEF4444)
                      : const Color(0xFF4B7BF5))
                  .withOpacity(0.4),
              blurRadius: isListening ? 30 : 18,
              spreadRadius: isListening ? 6 : 2,
            ),
          ],
        ),
        child: Icon(
          isListening ? Icons.stop : Icons.mic,
          color: Colors.white,
          size: isListening ? 38 : 32,
        ),
      ),
    );
  }

  // ── Hilfsmethoden ─────────────────────────────────────────────────────────

  List<Color> _orbColors() => switch (_state) {
    VoiceState.idle     => [const Color(0xFF1E3A8A), const Color(0xFF0F2044)],
    VoiceState.listening=> [const Color(0xFF1D4ED8), const Color(0xFF3B82F6)],
    VoiceState.thinking => [const Color(0xFF7C3AED), const Color(0xFF4C1D95)],
    VoiceState.speaking => [const Color(0xFF065F46), const Color(0xFF059669)],
  };

  Color _orbGlow() => switch (_state) {
    VoiceState.idle     => const Color(0xFF3B82F6),
    VoiceState.listening=> const Color(0xFF60A5FA),
    VoiceState.thinking => const Color(0xFFA78BFA),
    VoiceState.speaking => const Color(0xFF34D399),
  };

  IconData _orbIcon() => switch (_state) {
    VoiceState.idle     => Icons.smart_toy_outlined,
    VoiceState.listening=> Icons.graphic_eq,
    VoiceState.thinking => Icons.psychology,
    VoiceState.speaking => Icons.volume_up,
  };

  @override
  void dispose() {
    _pulseCtrl.dispose();
    _waveCtrl.dispose();
    _waveTimer?.cancel();
    _stt.cancel();
    _tts.stop();
    _agent.abort();
    super.dispose();
  }
}

enum VoiceState { idle, listening, thinking, speaking }
