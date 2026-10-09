// Step 3: the movement check.
//
// The order of movements is derived by the service from the challenge seed it issued, so the app
// only ever shows what `POST /attempts` returned, in that order — it cannot invent a sequence, and
// a mismatch comes back as `CHALLENGE_SEQUENCE_MISMATCH`.
//
// Two frames are recorded per prompt, one when the prompt appears and one a moment later, so the
// motion between them is real rather than two views of the same instant. The frames stay in the
// controller's memory until the envelope has been sent.

import 'dart:async';

import 'package:camera/camera.dart';
import 'package:flutter/material.dart';

import '../api/api_models.dart';
import '../capture/camera_adapter.dart';
import '../capture/quality.dart';
import '../state/verification_controller.dart';
import 'camera_stage.dart';

class LivenessScreen extends StatefulWidget {
  const LivenessScreen({required this.controller, super.key});

  final VerificationController controller;

  @override
  State<LivenessScreen> createState() => _LivenessScreenState();
}

class _LivenessScreenState extends State<LivenessScreen> {
  static const Duration _secondSampleDelay = Duration(milliseconds: 1200);
  static const Duration _actionDuration = Duration(milliseconds: 2400);

  final QualityGate _gate = QualityGate();

  CameraHarness? _harness;
  String? _current;
  bool _captureNext = false;
  bool _submitting = false;
  int _blurSamples = 0;
  double _blurTotal = 0;
  Timer? _secondSample;
  Timer? _nextAction;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _advance());
  }

  @override
  void dispose() {
    _secondSample?.cancel();
    _nextAction?.cancel();
    _harness?.stop();
    super.dispose();
  }

  /// Shows the next pending prompt, or submits once they are all done.
  void _advance() {
    _secondSample?.cancel();
    _nextAction?.cancel();
    final List<String> pending = widget.controller.pendingActions;
    if (pending.isEmpty) {
      _submit();
      return;
    }
    setState(() {
      _current = pending.first;
      _captureNext = true;
    });
    _secondSample = Timer(_secondSampleDelay, () {
      if (mounted) {
        setState(() => _captureNext = true);
      }
    });
    _nextAction = Timer(_actionDuration, () {
      if (mounted) {
        _advance();
      }
    });
  }

  void _onFrame(CameraImage image) {
    if (!_captureNext || _submitting || widget.controller.state.busy) {
      return;
    }
    final CameraHarness? harness = _harness;
    final String? action = _current;
    if (harness == null || action == null) {
      return;
    }
    final CaptureFrame frame = captureFrameOf(
      image,
      sensorOrientation: harness.description.sensorOrientation,
      front: true,
      maxLongEdge: 480,
    );
    // Blur is measured on the encoded pixels, the same rule as the document step.
    final double blur = _gate.evaluate(frame.gray).blurScore;
    _blurTotal += blur;
    _blurSamples++;
    widget.controller.addSelfieFrame(
      bytes: frame.jpeg,
      action: action,
      width: frame.width,
      height: frame.height,
    );
    if (mounted) {
      setState(() => _captureNext = false);
    }
  }

  void _submit() {
    if (_submitting) {
      return;
    }
    setState(() {
      _submitting = true;
      _current = null;
    });
    _secondSample?.cancel();
    _nextAction?.cancel();
    widget.controller.submitLiveness(
      quality: SelfieQuality(
        blurScore: _blurSamples == 0 ? 0 : _blurTotal / _blurSamples,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final TextTheme text = Theme.of(context).textTheme;
    final VerificationState state = widget.controller.state;
    final int total = state.challenge?.actions.length ?? 0;
    final int index = total - widget.controller.pendingActions.length;
    final String? action = _current;

    return Scaffold(
      body: SafeArea(
        child: Column(
          children: <Widget>[
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 16, 20, 8),
              child: Row(
                children: <Widget>[
                  Text(total == 0 ? 'Movement check' : 'Movement ${index + 1} of $total',
                      style: text.bodyMedium),
                  const Spacer(),
                  Text('${state.framesAccepted} frames recorded', style: text.bodyMedium),
                ],
              ),
            ),
            Expanded(
              child: CameraStage(
                front: true,
                onFrame: _onFrame,
                onReady: (CameraHarness harness) => _harness = harness,
                overlay: _PromptBanner(prompt: action == null ? 'Finishing…' : _promptFor(action)),
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 12, 20, 16),
              child: Row(
                children: <Widget>[
                  Expanded(
                    child: Text(
                      state.busy
                          ? 'Sending the sequence…'
                          : 'Keep your face inside the oval and follow each prompt as it appears.',
                      style: text.bodyMedium,
                    ),
                  ),
                  const SizedBox(width: 12),
                  OutlinedButton(
                    onPressed: state.busy ? null : widget.controller.reset,
                    child: const Text('Cancel'),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// Plain-language prompts for the seven actions the service can ask for.
///
/// An unknown code still has to be followed in order, so it falls back to a generic prompt rather
/// than being dropped — dropping it would desynchronise the sequence.
String _promptFor(String action) => switch (action) {
      'TURN_LEFT' => 'Turn your head slowly to the left',
      'TURN_RIGHT' => 'Turn your head slowly to the right',
      'BLINK' => 'Blink twice',
      'NOD' => 'Nod once',
      'SMILE' => 'Smile',
      'TILT_LEFT' => 'Tilt your head towards the left shoulder',
      'TILT_RIGHT' => 'Tilt your head towards the right shoulder',
      _ => 'Follow the prompt: $action',
    };

/// The prompt the user must act on now, over the middle of the preview.
class _PromptBanner extends StatelessWidget {
  const _PromptBanner({required this.prompt});

  final String prompt;

  @override
  Widget build(BuildContext context) {
    return Stack(
      fit: StackFit.expand,
      children: <Widget>[
        Center(
          child: SizedBox(
            width: 220,
            height: 290,
            child: DecoratedBox(
              decoration: BoxDecoration(
                border: Border.all(color: Colors.white70, width: 2),
                borderRadius: BorderRadius.circular(110),
              ),
            ),
          ),
        ),
        Positioned(
          left: 20,
          right: 20,
          bottom: 24,
          child: Text(
            prompt,
            textAlign: TextAlign.center,
            style: Theme.of(context)
                .textTheme
                .headlineSmall!
                .copyWith(color: Colors.white, fontWeight: FontWeight.w700),
          ),
        ),
      ],
    );
  }
}
