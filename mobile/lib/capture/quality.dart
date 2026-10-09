// On-device capture quality gate.
//
// This file is deliberately pure Dart: it imports nothing from
// `package:flutter` and nothing from `package:camera`. Everything that reads
// the camera lives in `camera_adapter.dart`, which converts a camera frame
// into a [GrayFrame] and hands it here. Keeping the gate free of platform
// bindings is what makes the blur / glare / exposure / framing rules
// unit-testable against synthetic bitmaps.
//
// The four gates implement the on-device checks described in the case study:
//   * blur      — variance of the Laplacian response of the luma plane
//   * glare     — fraction of pixels blown out towards white
//   * exposure  — mean luma plus the clipped-black fraction
//   * framing   — the document must present all four corners of the outline
//
// The gate reports the *specific* failing reasons, so the UI can tell the user
// what to change instead of showing a generic error.

import 'dart:math' as math;
import 'dart:typed_data';

/// A tightly packed 8-bit single-channel (luma) frame.
///
/// `luma.length` must be exactly `width * height`; producers that read a
/// camera plane with a row stride have to compact it first.
class GrayFrame {
  GrayFrame({required this.width, required this.height, required this.luma})
      : assert(width > 0, 'width must be positive'),
        assert(height > 0, 'height must be positive') {
    if (luma.length != width * height) {
      throw ArgumentError.value(
        luma.length,
        'luma.length',
        'expected width * height (${width * height})',
      );
    }
  }

  final int width;
  final int height;
  final Uint8List luma;

  int lumaAt(int x, int y) => luma[y * width + x];

  bool get isPortrait => height > width;
}

/// The normalized document outline the user is aligning to.
///
/// Coordinates are fractions of the frame (0..1) so the same outline works at
/// any camera resolution.
class DocumentOutline {
  const DocumentOutline({
    this.left = 0.10,
    this.top = 0.26,
    this.right = 0.90,
    this.bottom = 0.74,
  });

  final double left;
  final double top;
  final double right;
  final double bottom;

  double get widthFraction => right - left;
  double get heightFraction => bottom - top;
}

/// The specific reasons a frame can be rejected.
enum QualityIssue {
  blurry('Image is blurred — hold still and tap the shutter when steady'),
  glare('Glare on the document — tilt it away from the light source'),
  tooDark('Too dark — move to a brighter spot, do not use the flash'),
  tooBright('Too bright — step away from direct light'),
  cornersMissing('A corner of the document is outside the outline');

  const QualityIssue(this.advice);

  /// Actionable instruction shown next to the camera preview.
  final String advice;
}

/// Tunables for the gate, overridable per device class.
class QualityThresholds {
  const QualityThresholds({
    this.minBlurScore = 70.0,
    this.maxGlareRatio = 0.06,
    this.minMeanLuma = 45.0,
    this.maxMeanLuma = 215.0,
    this.maxClippedBlackRatio = 0.30,
    this.minOutlineCoverage = 0.65,
    this.minCornerInsideScore = 0.70,
    this.maxCornerOutsideScore = 0.40,
    this.analysisWidth = 320,
  });

  /// Minimum Laplacian variance (on the analysis-scale frame) to be sharp.
  final double minBlurScore;

  /// Maximum fraction of pixels at or above [highlightCutoff] before glare.
  final double maxGlareRatio;

  final double minMeanLuma;
  final double maxMeanLuma;

  /// Fraction of near-black pixels tolerated before "too dark".
  final double maxClippedBlackRatio;

  /// Fraction of the outline interior that must belong to the document.
  final double minOutlineCoverage;

  /// How much of each in-sample window must be document pixels.
  final double minCornerInsideScore;

  /// How much of each out-sample window may be document pixels.
  final double maxCornerOutsideScore;

  /// Frames are resampled to this width before scoring, so thresholds are
  /// resolution independent.
  final int analysisWidth;

  static const int highlightCutoff = 245;
  static const int blackCutoff = 24;
}

/// Result of evaluating one frame.
class QualityReport {
  QualityReport({
    required this.blurScore,
    required this.glareRatio,
    required this.meanLuma,
    required this.clippedBlackRatio,
    required this.cornersFound,
    required this.cornerScores,
    required this.issues,
    required this.documentCoverage,
  });

  /// Variance of the Laplacian response — higher is sharper.
  final double blurScore;

  /// Fraction of blown-out pixels.
  final double glareRatio;

  final double meanLuma;
  final double clippedBlackRatio;
  final bool cornersFound;

  /// Per-corner inside/outside document ratios, in
  /// top-left, top-right, bottom-left, bottom-right order.
  final List<CornerScore> cornerScores;

  final List<QualityIssue> issues;
  final double documentCoverage;

  bool get isPassing => issues.isEmpty;

  /// Compact facts for the `quality` block of the uploaded payload.
  DocumentQualityFacts toUploadFacts() => DocumentQualityFacts(
        blurScore: blurScore,
        glareRatio: glareRatio,
        cornersFound: cornersFound,
      );

  @override
  String toString() => 'QualityReport(blur: ${blurScore.toStringAsFixed(1)}, '
      'glare: ${(glareRatio * 100).toStringAsFixed(1)}%, '
      'meanLuma: ${meanLuma.toStringAsFixed(1)}, '
      'corners: $cornersFound, issues: $issues)';
}

/// One corner of the framing check.
class CornerScore {
  const CornerScore({
    required this.insideIsDocument,
    required this.outsideIsDocument,
    required this.found,
  });

  /// Fraction of the sample window inside the outline that is document.
  final double insideIsDocument;

  /// Fraction of the sample window outside the outline that is document.
  ///
  /// A sample that falls outside the frame scores as "all document", because
  /// there is no background margin there: that is a cropped document.
  final double outsideIsDocument;

  final bool found;

  @override
  String toString() =>
      'CornerScore(in: ${insideIsDocument.toStringAsFixed(2)}, '
      'out: ${outsideIsDocument.toStringAsFixed(2)}, found: $found)';
}

/// The subset of quality facts the contract asks the app to upload.
class DocumentQualityFacts {
  const DocumentQualityFacts({
    required this.blurScore,
    required this.glareRatio,
    required this.cornersFound,
  });

  final double blurScore;
  final double glareRatio;
  final bool cornersFound;

  Map<String, dynamic> toJson() => <String, dynamic>{
        'blurScore': blurScore,
        'glareRatio': glareRatio,
        'cornersFound': cornersFound,
      };
}

/// Evaluates a single frame against all four gates.
class QualityGate {
  QualityGate({
    this.thresholds = const QualityThresholds(),
    this.outline = const DocumentOutline(),
  });

  final QualityThresholds thresholds;
  final DocumentOutline outline;

  QualityReport evaluate(GrayFrame frame) {
    final GrayFrame analysis = _resampleToAnalysisWidth(frame);
    final double blur = laplacianVariance(analysis);
    final double glare = highlightRatio(analysis);
    final Exposure exposure = measureExposure(analysis);
    final CornerInspection corners = inspectFraming(analysis, outline, thresholds);

    final List<QualityIssue> issues = <QualityIssue>[];
    if (blur < thresholds.minBlurScore) {
      issues.add(QualityIssue.blurry);
    }
    if (glare > thresholds.maxGlareRatio) {
      issues.add(QualityIssue.glare);
    }
    if (exposure.mean < thresholds.minMeanLuma ||
        exposure.clippedBlackRatio > thresholds.maxClippedBlackRatio) {
      issues.add(QualityIssue.tooDark);
    } else if (exposure.mean > thresholds.maxMeanLuma) {
      issues.add(QualityIssue.tooBright);
    }
    if (!corners.allFound) {
      issues.add(QualityIssue.cornersMissing);
    }

    return QualityReport(
      blurScore: blur,
      glareRatio: glare,
      meanLuma: exposure.mean,
      clippedBlackRatio: exposure.clippedBlackRatio,
      cornersFound: corners.allFound,
      cornerScores: corners.scores,
      issues: issues,
      documentCoverage: corners.coverage,
    );
  }

  /// Variance of the 3x3 Laplacian response, the classic sharpness proxy.
  static double laplacianVariance(GrayFrame frame) {
    final Uint8List luma = frame.luma;
    final int w = frame.width;
    final int h = frame.height;
    if (w < 3 || h < 3) {
      return 0;
    }
    double sum = 0;
    double sumSquares = 0;
    int count = 0;
    for (int y = 1; y < h - 1; y++) {
      final int row = y * w;
      for (int x = 1; x < w - 1; x++) {
        final int i = row + x;
        // -4*c + up + down + left + right
        final int response =
            -4 * luma[i] + luma[i - w] + luma[i + w] + luma[i - 1] + luma[i + 1];
        sum += response;
        sumSquares += response * response.toDouble();
        count++;
      }
    }
    if (count == 0) {
      return 0;
    }
    final double mean = sum / count;
    final double variance = (sumSquares / count) - (mean * mean);
    return math.max(0, variance);
  }

  /// Fraction of pixels at or near pure white (highlights / specular glare).
  static double highlightRatio(GrayFrame frame) {
    final Uint8List luma = frame.luma;
    int hits = 0;
    for (int i = 0; i < luma.length; i++) {
      if (luma[i] >= QualityThresholds.highlightCutoff) {
        hits++;
      }
    }
    return luma.isEmpty ? 0 : hits / luma.length;
  }

  /// Mean luma plus the clipped-black fraction.
  static Exposure measureExposure(GrayFrame frame) {
    final Uint8List luma = frame.luma;
    if (luma.isEmpty) {
      return const Exposure(mean: 0, clippedBlackRatio: 1);
    }
    int total = 0;
    int clipped = 0;
    for (int i = 0; i < luma.length; i++) {
      final int v = luma[i];
      total += v;
      if (v <= QualityThresholds.blackCutoff) {
        clipped++;
      }
    }
    return Exposure(
      mean: total / luma.length,
      clippedBlackRatio: clipped / luma.length,
    );
  }

  /// Four-corner framing check.
  ///
  /// The document/background split is derived from the frame itself (Otsu),
  /// then for every outline corner two windows are sampled: one just inside
  /// the corner and one just outside it, towards the background. A corner is
  /// present only when the inside window is document and the outside window is
  /// background — which is exactly what makes a cropped document fail: with no
  /// background margin beyond the outline corner the outside sample is still
  /// document (or falls off the frame entirely).
  static CornerInspection inspectFraming(
    GrayFrame frame,
    DocumentOutline outline,
    QualityThresholds thresholds,
  ) {
    final double threshold = _otsuThreshold(frame.luma);
    final int centreX =
        ((outline.left + outline.right) / 2 * frame.width).round().clamp(0, frame.width - 1);
    final int centreY =
        ((outline.top + outline.bottom) / 2 * frame.height).round().clamp(0, frame.height - 1);
    // Decide which side of the threshold is the document by sampling the
    // middle of the outline: a card can be darker or lighter than the table.
    final bool documentIsBright = frame.lumaAt(centreX, centreY) > threshold;
    bool isDocument(int v) => documentIsBright ? v > threshold : v <= threshold;
    bool isBackground(int v) => !isDocument(v);

    final double midX = (outline.left + outline.right) / 2;
    final double midY = (outline.top + outline.bottom) / 2;
    final double insetX = outline.widthFraction * 0.08;
    final double insetY = outline.heightFraction * 0.14;

    final List<(double, double)> corners = <(double, double)>[
      (outline.left, outline.top),
      (outline.right, outline.top),
      (outline.left, outline.bottom),
      (outline.right, outline.bottom),
    ];

    final List<CornerScore> scores = <CornerScore>[];
    int foundCount = 0;
    for (final (double cornerX, double cornerY) in corners) {
      // Direction towards the outline centre.
      final double towardsX = cornerX < midX ? 1 : -1;
      final double towardsY = cornerY < midY ? 1 : -1;

      final double inside = _sampleWindow(
        frame,
        xFraction: cornerX + towardsX * insetX,
        yFraction: cornerY + towardsY * insetY,
        predicate: isDocument,
      );
      final double outsideBackground = _sampleWindow(
        frame,
        xFraction: cornerX - towardsX * insetX * 1.5,
        yFraction: cornerY - towardsY * insetY * 1.5,
        predicate: isBackground,
      );
      final CornerScore score = CornerScore(
        insideIsDocument: inside,
        outsideIsDocument: 1 - outsideBackground,
        found: inside >= thresholds.minCornerInsideScore &&
            (1 - outsideBackground) <= thresholds.maxCornerOutsideScore,
      );
      scores.add(score);
      if (score.found) {
        foundCount++;
      }
    }

    final double coverage = _regionRatio(
      frame,
      left: outline.left,
      top: outline.top,
      right: outline.right,
      bottom: outline.bottom,
      predicate: isDocument,
    );

    return CornerInspection(
      scores: scores,
      foundCount: foundCount,
      // Four corners present and the document actually filling the outline:
      // an empty frame of a single tone would otherwise trivially "find" them.
      allFound: foundCount == 4 && coverage >= thresholds.minOutlineCoverage,
      coverage: coverage,
    );
  }

  /// Fraction of a small window around a normalized point matching [predicate].
  ///
  /// Points outside the frame score 0, which is what turns a cropped document
  /// into a failed corner.
  static double _sampleWindow(
    GrayFrame frame, {
    required double xFraction,
    required double yFraction,
    required bool Function(int luma) predicate,
  }) {
    if (xFraction < 0 || xFraction > 1 || yFraction < 0 || yFraction > 1) {
      return 0;
    }
    final int cx = (xFraction * (frame.width - 1)).round();
    final int cy = (yFraction * (frame.height - 1)).round();
    final int radius = math.max(2, (frame.width * 0.02).round());
    int hits = 0;
    int total = 0;
    for (int y = cy - radius; y <= cy + radius; y++) {
      if (y < 0 || y >= frame.height) {
        continue;
      }
      for (int x = cx - radius; x <= cx + radius; x++) {
        if (x < 0 || x >= frame.width) {
          continue;
        }
        total++;
        if (predicate(frame.lumaAt(x, y))) {
          hits++;
        }
      }
    }
    return total == 0 ? 0 : hits / total;
  }

  /// Fraction of a normalized rectangle matching [predicate].
  static double _regionRatio(
    GrayFrame frame, {
    required double left,
    required double top,
    required double right,
    required double bottom,
    required bool Function(int luma) predicate,
  }) {
    final int x0 = (left * (frame.width - 1)).round().clamp(0, frame.width - 1);
    final int x1 = (right * (frame.width - 1)).round().clamp(0, frame.width - 1);
    final int y0 = (top * (frame.height - 1)).round().clamp(0, frame.height - 1);
    final int y1 = (bottom * (frame.height - 1)).round().clamp(0, frame.height - 1);
    int hits = 0;
    int total = 0;
    final int stepX = math.max(1, ((x1 - x0) / 64).ceil());
    final int stepY = math.max(1, ((y1 - y0) / 64).ceil());
    for (int y = y0; y <= y1; y += stepY) {
      for (int x = x0; x <= x1; x += stepX) {
        total++;
        if (predicate(frame.lumaAt(x, y))) {
          hits++;
        }
      }
    }
    return total == 0 ? 0 : hits / total;
  }

  /// Otsu's method on a 256-bin histogram: separates document from background
  /// without any calibration.
  static double _otsuThreshold(Uint8List luma) {
    final List<int> histogram = List<int>.filled(256, 0);
    for (int i = 0; i < luma.length; i++) {
      histogram[luma[i]]++;
    }
    final int total = luma.length;
    if (total == 0) {
      return 127;
    }
    double sumAll = 0;
    for (int i = 0; i < 256; i++) {
      sumAll += i * histogram[i];
    }
    double sumBackground = 0;
    int weightBackground = 0;
    double bestVariance = -1;
    int bestThreshold = 127;
    for (int t = 0; t < 256; t++) {
      weightBackground += histogram[t];
      if (weightBackground == 0) {
        continue;
      }
      final int weightForeground = total - weightBackground;
      if (weightForeground == 0) {
        break;
      }
      sumBackground += t * histogram[t];
      final double meanBackground = sumBackground / weightBackground;
      final double meanForeground = (sumAll - sumBackground) / weightForeground;
      final double between =
          weightBackground * weightForeground.toDouble() * math.pow(meanBackground - meanForeground, 2);
      if (between > bestVariance) {
        bestVariance = between;
        bestThreshold = t;
      }
    }
    return bestThreshold.toDouble();
  }

  /// Box-average resample to [QualityThresholds.analysisWidth] so thresholds do
  /// not depend on the sensor resolution.
  GrayFrame _resampleToAnalysisWidth(GrayFrame frame) {
    final int targetWidth = thresholds.analysisWidth;
    if (frame.width <= targetWidth || frame.width < 4) {
      return frame;
    }
    final double scaleX = frame.width / targetWidth;
    final int targetHeight = math.max(2, (frame.height / scaleX).round());
    final double scaleY = frame.height / targetHeight;
    final Uint8List out = Uint8List(targetWidth * targetHeight);
    for (int y = 0; y < targetHeight; y++) {
      final int srcY0 = (y * scaleY).floor();
      final int srcY1 = math.min(frame.height - 1, ((y + 1) * scaleY).floor());
      for (int x = 0; x < targetWidth; x++) {
        final int srcX0 = (x * scaleX).floor();
        final int srcX1 = math.min(frame.width - 1, ((x + 1) * scaleX).floor());
        int sum = 0;
        int n = 0;
        for (int sy = srcY0; sy <= srcY1; sy++) {
          for (int sx = srcX0; sx <= srcX1; sx++) {
            sum += frame.lumaAt(sx, sy);
            n++;
          }
        }
        out[y * targetWidth + x] = n == 0 ? 0 : (sum / n).round().clamp(0, 255);
      }
    }
    return GrayFrame(width: targetWidth, height: targetHeight, luma: out);
  }
}

/// Mean brightness summary of a frame.
class Exposure {
  const Exposure({required this.mean, required this.clippedBlackRatio});

  final double mean;
  final double clippedBlackRatio;
}

/// Outcome of the four-corner framing check.
class CornerInspection {
  const CornerInspection({
    required this.scores,
    required this.foundCount,
    required this.allFound,
    required this.coverage,
  });

  final List<CornerScore> scores;
  final int foundCount;
  final bool allFound;
  final double coverage;
}

/// Mean absolute luma difference between two frames, on a sampled grid.
///
/// Cheap enough to run on every preview frame and the signal used to decide
/// that the phone is being held still.
double meanFrameDifference(GrayFrame a, GrayFrame b) {
  if (a.width != b.width || a.height != b.height) {
    // Different sizes: compare on a coarse normalized grid instead of failing.
    const int grid = 24;
    double sum = 0;
    for (int i = 0; i < grid; i++) {
      for (int j = 0; j < grid; j++) {
        final double xf = i / (grid - 1);
        final double yf = j / (grid - 1);
        final int av = a.lumaAt(
          (xf * (a.width - 1)).round(),
          (yf * (a.height - 1)).round(),
        );
        final int bv = b.lumaAt(
          (xf * (b.width - 1)).round(),
          (yf * (b.height - 1)).round(),
        );
        sum += (av - bv).abs();
      }
    }
    return sum / (grid * grid);
  }
  const int grid = 32;
  double sum = 0;
  for (int i = 0; i < grid; i++) {
    for (int j = 0; j < grid; j++) {
      final int x = ((i / (grid - 1)) * (a.width - 1)).round();
      final int y = ((j / (grid - 1)) * (a.height - 1)).round();
      sum += (a.lumaAt(x, y) - b.lumaAt(x, y)).abs();
    }
  }
  return sum / (grid * grid);
}

/// Fires the shutter only when the frame is both good and held steady.
///
/// `hold` is the ~600 ms stability window from the flow description. The clock
/// is injected so the behaviour is testable without waiting in real time.
class AutoShutterGate {
  AutoShutterGate({
    QualityGate? qualityGate,
    this.hold = const Duration(milliseconds: 600),
    this.maxMotion = 6.0,
  }) : qualityGate = qualityGate ?? QualityGate();

  final QualityGate qualityGate;
  final Duration hold;
  final double maxMotion;

  DateTime? _goodSince;
  GrayFrame? _lastFrame;
  QualityReport? _lastReport;
  bool _motionStable = false;
  bool _fired = false;

  QualityReport? get lastReport => _lastReport;
  bool get hasFired => _fired;

  void reset() {
    _goodSince = null;
    _lastFrame = null;
    _lastReport = null;
    _motionStable = false;
    _fired = false;
  }

  ShutterDecision onFrame(GrayFrame frame, {required DateTime now}) {
    final QualityReport report = qualityGate.evaluate(frame);
    _lastReport = report;

    final GrayFrame? previous = _lastFrame;
    _lastFrame = frame;

    final double motion = previous == null ? 0 : meanFrameDifference(previous, frame);
    // The very first frame has nothing to compare against, so it is treated as
    // stable but cannot complete the hold window on its own.
    _motionStable = motion <= maxMotion;

    if (!report.isPassing) {
      _goodSince = null;
      return ShutterDecision(
        report: report,
        shouldFire: false,
        heldFor: Duration.zero,
        motion: motion,
        block: ShutterBlock.qualityFailed,
        issue: report.issues.first,
      );
    }

    if (!_motionStable) {
      _goodSince = null;
      return ShutterDecision(
        report: report,
        shouldFire: false,
        heldFor: Duration.zero,
        motion: motion,
        block: ShutterBlock.unstableFrame,
      );
    }

    _goodSince ??= now;
    final Duration heldFor = now.difference(_goodSince!);
    if (heldFor >= hold) {
      _fired = true;
      return ShutterDecision(
        report: report,
        shouldFire: true,
        heldFor: heldFor,
        motion: motion,
        block: ShutterBlock.ready,
      );
    }
    return ShutterDecision(
      report: report,
      shouldFire: false,
      heldFor: heldFor,
      motion: motion,
      block: ShutterBlock.holding,
    );
  }
}

/// Why the shutter has not fired yet.
enum ShutterBlock {
  /// A quality gate rejected the frame; see [ShutterDecision.issue].
  qualityFailed,

  /// All gates pass, still inside the stability window.
  holding,

  /// The phone moved, the hold window restarted.
  unstableFrame,

  /// The frame is good and stable long enough.
  ready,
}

class ShutterDecision {
  const ShutterDecision({
    required this.report,
    required this.shouldFire,
    required this.heldFor,
    required this.motion,
    required this.block,
    this.issue,
  });

  final QualityReport report;
  final bool shouldFire;
  final Duration heldFor;
  final double motion;
  final ShutterBlock block;

  /// The specific gate that rejected the frame, when one did.
  final QualityIssue? issue;

  /// Instruction for the camera overlay, or null when nothing is blocking.
  String? get advice => block == ShutterBlock.ready ? null : issue?.advice;
}
