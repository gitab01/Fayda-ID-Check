// One camera preview, two uses.
//
// Both capture screens need the same things — open the right lens, show the live frame, hand every
// preview frame to a callback, and close the camera when the screen goes away — so that lives here
// once. The screens differ only in what they do with the frames and what they draw on top.
//
// Frames are delivered on the UI isolate. That is a deliberate, documented limit: the quality gate
// works on a 320-wide resample, so a frame costs well under a preview tick on the devices this app
// targets, and moving it to an isolate would mean copying the plane twice.

import 'dart:async';

import 'package:camera/camera.dart';
import 'package:flutter/material.dart';

import '../capture/camera_adapter.dart';
import 'theme.dart';

class CameraStage extends StatefulWidget {
  const CameraStage({
    required this.front,
    required this.onFrame,
    this.overlay,
    this.onReady,
    super.key,
  });

  final bool front;
  final void Function(CameraImage image) onFrame;
  final void Function(CameraHarness harness)? onReady;

  /// Drawn on top of the preview: the document outline, the face ellipse, the prompt.
  final Widget? overlay;

  @override
  State<CameraStage> createState() => _CameraStageState();
}

class _CameraStageState extends State<CameraStage> {
  CameraHarness? _harness;
  Object? _error;

  @override
  void initState() {
    super.initState();
    _open();
  }

  Future<void> _open() async {
    try {
      final CameraHarness harness = await CameraHarness.open(front: widget.front);
      if (!mounted) {
        await harness.dispose();
        return;
      }
      setState(() => _harness = harness);
      widget.onReady?.call(harness);
      await harness.start(widget.onFrame);
    } catch (error) {
      // CameraAccessException (permission denied), a missing lens, or an unsupported format. The
      // screen must say which, because "camera error" tells the user nothing to change.
      if (mounted) {
        setState(() => _error = error);
      }
    }
  }

  @override
  void dispose() {
    final CameraHarness? harness = _harness;
    _harness = null;
    if (harness != null) {
      unawaited(harness.dispose());
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final Object? error = _error;
    if (error != null) {
      return _CameraUnavailable(error: error);
    }
    final CameraHarness? harness = _harness;
    if (harness == null || !harness.controller.value.isInitialized) {
      return const ColoredBox(
        color: InkTokens.page,
        child: Center(child: CircularProgressIndicator(strokeWidth: 2)),
      );
    }
    return ClipRect(
      child: Stack(
        fit: StackFit.expand,
        children: <Widget>[
          CameraPreview(harness.controller),
          if (widget.overlay != null) widget.overlay!,
        ],
      ),
    );
  }
}

class _CameraUnavailable extends StatelessWidget {
  const _CameraUnavailable({required this.error});

  final Object error;

  @override
  Widget build(BuildContext context) {
    final String text = error is CameraException
        ? 'The camera could not be opened (${(error as CameraException).code}). '
            'Allow camera access in the system settings, then come back to this step.'
        : 'The camera could not be opened on this device.';
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Text(text, textAlign: TextAlign.center, style: Theme.of(context).textTheme.bodyMedium),
      ),
    );
  }
}
