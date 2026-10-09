// Step 2: photograph the document.
//
// The camera shows the live frame; the four-corner outline is drawn over it so the user can see
// what the gate is measuring. The shutter fires by itself once a frame passes every gate and holds
// steady for the stability window, with a manual button for the case where the auto gate never
// settles (a shiny card under a ceiling light, say).
//
// Only the accepted frame is converted to JPEG, and the quality facts uploaded are recomputed from
// exactly those pixels — not from the sensor frame — so what the service is told matches what it
// receives.

import 'package:camera/camera.dart';
import 'package:flutter/material.dart';

import '../api/api_models.dart';
import '../capture/camera_adapter.dart';
import '../capture/quality.dart';
import '../state/verification_controller.dart';
import 'camera_stage.dart';
import 'theme.dart';

class DocumentCaptureScreen extends StatefulWidget {
  const DocumentCaptureScreen({required this.controller, super.key});

  final VerificationController controller;

  @override
  State<DocumentCaptureScreen> createState() => _DocumentCaptureScreenState();
}

class _DocumentCaptureScreenState extends State<DocumentCaptureScreen> {
  static const DocumentOutline _outline = DocumentOutline();

  final AutoShutterGate _shutter = AutoShutterGate();
  final QualityGate _uploadGate = QualityGate();

  CameraHarness? _harness;
  String _advice = 'Line the card up inside the frame';
  bool _done = false;
  bool _manualRequested = false;

  @override
  void dispose() {
    _harness?.stop();
    super.dispose();
  }

  void _onFrame(CameraImage image) {
    if (_done || widget.controller.state.busy) {
      return;
    }
    final ShutterDecision decision =
        _shutter.onFrame(grayPlaneOf(image), now: DateTime.now());
    if (_manualRequested || decision.shouldFire) {
      _done = true;
      _upload(image, decision.report);
      return;
    }
    final String advice = decision.advice ?? 'Hold still';
    if (advice != _advice && mounted) {
      setState(() => _advice = advice);
    }
  }

  void _upload(CameraImage image, QualityReport sensorReport) {
    final CameraHarness? harness = _harness;
    if (harness == null) {
      return;
    }
    final CaptureFrame frame = captureFrameOf(
      image,
      sensorOrientation: harness.description.sensorOrientation,
      front: false,
      maxLongEdge: 960,
    );
    final QualityReport uploadReport = _uploadGate.evaluate(frame.gray);
    widget.controller.uploadDocument(DocumentCapture(
      image: CaptureImage(
        bytes: frame.jpeg,
        width: frame.width,
        height: frame.height,
        mimeType: 'image/jpeg',
      ),
      // The facts the contract asks for, measured on the bytes actually sent.
      quality: uploadReport.toUploadFacts(),
      ocr: null, // This build does not run OCR on the phone; see CONTRACT.md §2.
    ));
    debugPrint('document capture accepted: sensor blur ${sensorReport.blurScore.toStringAsFixed(1)}');
  }

  @override
  Widget build(BuildContext context) {
    final TextTheme text = Theme.of(context).textTheme;
    final VerificationState state = widget.controller.state;
    return Scaffold(
      body: Stack(
        fit: StackFit.expand,
        children: <Widget>[
          CameraStage(
            front: false,
            onFrame: _onFrame,
            onReady: (CameraHarness harness) => _harness = harness,
            overlay: _OutlineOverlay(outline: _outline),
          ),
          SafeArea(
            child: Column(
              children: <Widget>[
                const Spacer(),
                Padding(
                  padding: const EdgeInsets.fromLTRB(20, 0, 20, 0),
                  child: InkCard(
                    tone: state.busy ? InkTone.neutral : InkTone.attention,
                    padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
                    child: Text(
                      state.busy
                          ? 'Encrypting and sending the capture…'
                          : _advice,
                      textAlign: TextAlign.center,
                      style: text.titleMedium,
                    ),
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(20, 12, 20, 20),
                  child: Row(
                    children: <Widget>[
                      Expanded(
                        child: OutlinedButton.icon(
                          onPressed: state.busy || _done
                              ? null
                              : () => setState(() => _manualRequested = true),
                          icon: const Icon(Icons.camera_alt_outlined),
                          label: const Text('Capture now'),
                        ),
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: OutlinedButton(
                          onPressed: state.busy ? null : widget.controller.reset,
                          child: const Text('Cancel'),
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// The framing target: a card-shaped outline plus corner marks.
class _OutlineOverlay extends StatelessWidget {
  const _OutlineOverlay({required this.outline});

  final DocumentOutline outline;

  @override
  Widget build(BuildContext context) {
    return IgnorePointer(
      child: CustomPaint(painter: _OutlinePainter(outline)),
    );
  }
}

class _OutlinePainter extends CustomPainter {
  const _OutlinePainter(this.outline);

  final DocumentOutline outline;

  @override
  void paint(Canvas canvas, Size size) {
    final Rect box = Rect.fromLTRB(
      outline.left * size.width,
      outline.top * size.height,
      outline.right * size.width,
      outline.bottom * size.height,
    );
    final Paint dim = Paint()..color = const Color(0x66000000);
    final Path outside = Path()
      ..addRect(Offset.zero & size)
      ..addRRect(RRect.fromRectAndRadius(box, const Radius.circular(10)))
      ..fillType = PathFillType.evenOdd;
    canvas.drawPath(outside, dim);

    final Paint edge = Paint()
      ..color = Colors.white
      ..style = PaintingStyle.stroke
      ..strokeWidth = 2;
    canvas.drawRRect(RRect.fromRectAndRadius(box, const Radius.circular(10)), edge);

    final Paint corner = Paint()
      ..color = InkTokens.accent
      ..style = PaintingStyle.stroke
      ..strokeWidth = 5
      ..strokeCap = StrokeCap.round;
    final double arm = box.width * 0.08;
    for (final Offset o in <Offset>[
      box.topLeft,
      box.topRight,
      box.bottomLeft,
      box.bottomRight,
    ]) {
      final double dx = o.dx == box.left ? 1 : -1;
      final double dy = o.dy == box.top ? 1 : -1;
      canvas.drawPath(
        Path()
          ..moveTo(o.dx, o.dy + dy * arm)
          ..lineTo(o.dx, o.dy)
          ..lineTo(o.dx + dx * arm, o.dy),
        corner,
      );
    }
  }

  @override
  bool shouldRepaint(_OutlinePainter oldDelegate) => oldDelegate.outline != outline;
}
