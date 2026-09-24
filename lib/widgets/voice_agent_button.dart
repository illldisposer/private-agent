import 'package:flutter/material.dart';
import '../screens/voice_agent_screen.dart';

/// Kleiner Mikrofon-Button, der den Voice-Agent-Screen öffnet.
/// Einbinden in den bestehenden Agent-Screen neben dem Send-Button.
///
/// Beispiel-Integration in deinen AgentScreen:
///   Row(children: [
///     Expanded(child: TextField(...)),
///     VoiceAgentButton(),          // ← hier einfügen
///     IconButton(icon: Icon(Icons.send), ...),
///   ])
class VoiceAgentButton extends StatelessWidget {
  const VoiceAgentButton({super.key});

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: () => _openVoiceAgent(context),
      child: Container(
        margin: const EdgeInsets.only(right: 8),
        width: 44,
        height: 44,
        decoration: BoxDecoration(
          gradient: const LinearGradient(
            colors: [Color(0xFF4B7BF5), Color(0xFF7B5CF5)],
          ),
          borderRadius: BorderRadius.circular(14),
          boxShadow: [
            BoxShadow(
              color: const Color(0xFF4B7BF5).withOpacity(0.35),
              blurRadius: 12,
              offset: const Offset(0, 4),
            ),
          ],
        ),
        child: const Icon(Icons.mic, color: Colors.white, size: 22),
      ),
    );
  }

  void _openVoiceAgent(BuildContext context) {
    Navigator.of(context).push(
      PageRouteBuilder(
        opaque: false,
        barrierDismissible: false,
        pageBuilder: (_, __, ___) => const VoiceAgentScreen(),
        transitionsBuilder: (_, anim, __, child) => SlideTransition(
          position: Tween<Offset>(
            begin: const Offset(0, 1),
            end: Offset.zero,
          ).animate(CurvedAnimation(parent: anim, curve: Curves.easeOutCubic)),
          child: child,
        ),
        transitionDuration: const Duration(milliseconds: 350),
      ),
    );
  }
}
