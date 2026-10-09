// The only file in the app that touches the camera plugin.
//
// Two conversions live here: the preview plane becomes a [GrayFrame] for the quality gate, and an
// accepted frame becomes JPEG bytes for the §3 envelope. `takePicture()` is deliberately never
// called — it writes a photo to shared storage, which the privacy rules in CONTRACT.md forbid.
// Every byte the app uploads is encoded in memory and can be zeroed by the caller ([CaptureFrame.destroy]).
//
// The gate is intentionally off this file: it is pure Dart in `quality.dart` so its thresholds can
// be tested against synthetic bitmaps.

import 'dart:typed_data';

import 'package:camera/camera.dart';
import 'package:image/image.dart' as img;

import 'quality.dart' show GrayFrame;

/// One camera frame, ready for the gate and for upload.
class CaptureFrame {
  CaptureFrame({required this.gray, required this.jpeg, required this.width, required this.height});

  final GrayFrame gray;

  /// JPEG of the upright, downscaled frame: what goes into `image.bytes64`.
  final Uint8List jpeg;

  /// Encoded dimensions, so the payload's `width`/`height` describe the bytes actually sent.
  final int width;
  final int height;

  void destroy() {
    gray.luma.fillRange(0, gray.luma.length, 0);
    jpeg.fillRange(0, jpeg.length, 0);
  }
}

/// Packed luma plane of a camera frame.
///
/// The plugin's plane has `bytesPerRow` padding per line; the quality gate expects exactly
/// `width * height` bytes, so the padding is dropped here rather than corrupting the statistics.
GrayFrame grayPlaneOf(CameraImage image) {
  final Uint8List plane = image.planes.first.bytes;
  final int bytesPerPixel = image.planes.first.bytesPerPixel ?? 1;
  if (bytesPerPixel != 1) {
    throw ArgumentError('expected a single-channel luma plane, got $bytesPerPixel bytes');
  }
  final int width = image.width;
  final int height = image.height;
  final int stride = image.planes.first.bytesPerRow;
  final Uint8List luma = Uint8List(width * height);
  for (int y = 0; y < height; y++) {
    final int row = y * width;
    final int src = y * stride;
    for (int x = 0; x < width; x++) {
      luma[row + x] = plane[src + x];
    }
  }
  return GrayFrame(width: width, height: height, luma: luma);
}

/// Converts a camera frame to an upright, downscaled JPEG plus the luma of exactly those pixels.
///
/// The app locks its UI to portrait, so "upright" means undoing the sensor's own rotation:
/// clockwise by `sensorOrientation` for the rear camera, and counter-clockwise plus a mirror for
/// the front camera, which is delivered horizontally flipped. Downscaling during the colour
/// conversion (instead of converting at full resolution and resizing after) keeps one frame cheap
/// enough to encode on the UI isolate.
///
/// The returned [CaptureFrame.gray] is derived from the encoded pixels, not from the sensor plane,
/// so the blur score in the uploaded `quality` block describes the image the service receives.
CaptureFrame captureFrameOf(
  CameraImage image, {
  required int sensorOrientation,
  required bool front,
  int maxLongEdge = 640,
  int quality = 82,
}) {
  final img.Image upright = _toUprightRgb(image,
      sensorOrientation: sensorOrientation, front: front, maxLongEdge: maxLongEdge);
  final int width = upright.width;
  final int height = upright.height;
  final Uint8List luma = Uint8List(width * height);
  int i = 0;
  for (int y = 0; y < height; y++) {
    for (int x = 0; x < width; x++) {
      final img.Pixel px = upright.getPixel(x, y);
      luma[i++] = _clampByte(0.299 * px.r + 0.587 * px.g + 0.114 * px.b);
    }
  }
  return CaptureFrame(
    gray: GrayFrame(width: width, height: height, luma: luma),
    jpeg: img.encodeJpg(upright, quality: quality),
    width: width,
    height: height,
  );
}

img.Image _toUprightRgb(
  CameraImage image, {
  required int sensorOrientation,
  required bool front,
  required int maxLongEdge,
}) {
  final int srcWidth = image.width;
  final int srcHeight = image.height;
  final double step = _sampleStep(srcWidth, srcHeight, maxLongEdge);
  final int outWidth = (srcWidth / step).round().clamp(1, srcWidth);
  final int outHeight = (srcHeight / step).round().clamp(1, srcHeight);

  final img.Image sampled = img.Image(width: outWidth, height: outHeight, numChannels: 3);
  switch (image.format.group) {
    case ImageFormatGroup.yuv420:
    case ImageFormatGroup.nv21:
      _fillFromYuv(image, sampled, step);
    case ImageFormatGroup.bgra8888:
      _fillFromBgra(image, sampled, step);
    default:
      throw ArgumentError(
          'unsupported camera format ${image.format.group}; the stream needs yuv420 or nv21');
  }

  img.Image upright = front
      ? img.copyRotate(sampled, angle: 360 - sensorOrientation)
      : img.copyRotate(sampled, angle: sensorOrientation);
  if (front) {
    upright = img.copyFlip(upright, direction: img.FlipDirection.horizontal);
  }
  return upright;
}

/// Sampled BT.601 YUV → RGB.
///
/// `bytesPerPixel` distinguishes planar from semi-planar chroma: when the U plane steps by two
/// bytes its pairs are interleaved with V, and the V plane carries no separate buffer.
void _fillFromYuv(CameraImage image, img.Image out, double step) {
  final Uint8List yPlane = image.planes[0].bytes;
  final int yStride = image.planes[0].bytesPerRow;
  final _Chroma chroma = _Chroma.of(image);
  for (int outY = 0; outY < out.height; outY++) {
    final int sy = (outY * step).round().clamp(0, image.height - 1);
    for (int outX = 0; outX < out.width; outX++) {
      final int sx = (outX * step).round().clamp(0, image.width - 1);
      final int luma = yPlane[sy * yStride + sx];
      final (int u, int v) = chroma.at(sx, sy);
      out.setPixelRgb(
        outX,
        outY,
        _clampByte(luma + 1.402 * (v - 128)),
        _clampByte(luma - 0.344136 * (u - 128) - 0.714136 * (v - 128)),
        _clampByte(luma + 1.772 * (u - 128)),
      );
    }
  }
}

void _fillFromBgra(CameraImage image, img.Image out, double step) {
  final Uint8List plane = image.planes.first.bytes;
  final int stride = image.planes.first.bytesPerRow;
  for (int outY = 0; outY < out.height; outY++) {
    final int sy = (outY * step).round().clamp(0, image.height - 1);
    for (int outX = 0; outX < out.width; outX++) {
      final int sx = (outX * step).round().clamp(0, image.width - 1);
      final int i = sy * stride + sx * 4;
      out.setPixelRgb(outX, outY, plane[i + 2], plane[i + 1], plane[i]);
    }
  }
}

int _clampByte(double value) => value.round().clamp(0, 255);

/// Integer-ish downsample step that fits the longest edge into `maxLongEdge`.
double _sampleStep(int width, int height, int maxLongEdge) {
  final int longest = width > height ? width : height;
  if (longest <= maxLongEdge) {
    return 1;
  }
  return longest / maxLongEdge;
}

/// Chroma lookup that works for planar and semi-planar frames alike.
///
/// The pair order is decided once, from the format the plugin reported, so the per-pixel path is
/// only two index computations.
class _Chroma {
  _Chroma(this._u, this._v, this._uStride, this._vStride, this._uPixelStride, this._vPixelStride,
      {required bool vLeadsPair})
      : _vFirst = vLeadsPair;

  final Uint8List _u;
  final Uint8List _v;
  final int _uStride;
  final int _vStride;
  final int _uPixelStride;
  final int _vPixelStride;
  final bool _vFirst;

  factory _Chroma.of(CameraImage image) {
    if (image.planes.length < 3) {
      throw ArgumentError('yuv420 frames need three planes, got ${image.planes.length}');
    }
    final bool interleaved = image.planes[2].bytes.isEmpty;
    final Uint8List u = image.planes[1].bytes;
    final Uint8List v = interleaved ? u : image.planes[2].bytes;
    return _Chroma(
      u,
      v,
      image.planes[1].bytesPerRow,
      interleaved ? image.planes[1].bytesPerRow : image.planes[2].bytesPerRow,
      image.planes[1].bytesPerPixel ?? 1,
      interleaved ? image.planes[1].bytesPerPixel ?? 1 : image.planes[2].bytesPerPixel ?? 1,
      // NV21 stores the pairs as V,U; NV12 (and Android's usual YUV_420_888) as U,V.
      vLeadsPair: interleaved && image.format.group == ImageFormatGroup.nv21,
    );
  }

  /// Chroma for one luma pixel; 4:2:0 subsamples both axes by two.
  (int u, int v) at(int x, int y) {
    final int chromaY = y ~/ 2;
    final int chromaX = x ~/ 2;
    if (_uPixelStride < 2) {
      return (_u[chromaY * _uStride + chromaX * _uPixelStride],
          _v[chromaY * _vStride + chromaX * _vPixelStride]);
    }
    final int first = _u[chromaY * _uStride + chromaX * _uPixelStride];
    final int second = _u[chromaY * _uStride + chromaX * _uPixelStride + 1];
    return _vFirst ? (second, first) : (first, second);
  }
}

/// Opens one camera, streams frames to a callback, and closes cleanly.
///
/// The gate reads frames through [start]; nothing is buffered, so a screen that goes away stops
/// seeing pixels immediately.
class CameraHarness {
  CameraHarness._(this.controller, this.description);

  final CameraController controller;
  final CameraDescription description;

  bool _streaming = false;

  bool get isStreaming => _streaming;

  static Future<CameraHarness> open({
    required bool front,
    ResolutionPreset preset = ResolutionPreset.veryHigh,
    List<CameraDescription>? cameras,
  }) async {
    final List<CameraDescription> available = cameras ?? await availableCameras();
    if (available.isEmpty) {
      throw StateError('the device reports no camera');
    }
    final CameraDescription wanted = available.firstWhere(
      (CameraDescription c) =>
          c.lensDirection == (front ? CameraLensDirection.front : CameraLensDirection.back),
      orElse: () => available.first,
    );
    final CameraController controller =
        CameraController(wanted, preset, enableAudio: false, imageFormatGroup: ImageFormatGroup.yuv420);
    await controller.initialize();
    return CameraHarness._(controller, wanted);
  }

  /// Luma-only conversion of the frame the plugin last delivered, for the quality gate.
  Future<void> start(void Function(CameraImage image) onFrame) async {
    if (_streaming) {
      return;
    }
    await controller.startImageStream(onFrame);
    _streaming = true;
  }

  Future<void> stop() async {
    if (!_streaming) {
      return;
    }
    await controller.stopImageStream();
    _streaming = false;
  }

  Future<void> dispose() async {
    await stop();
    await controller.dispose();
  }
}
