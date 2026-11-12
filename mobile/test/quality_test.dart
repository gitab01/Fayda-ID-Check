// The on-device capture gate, checked against synthetic bitmaps.
//
// `quality.dart` imports nothing from Flutter or the camera plugin, so every rule is testable
// without a device: a known pixel pattern in, a known verdict out. The gate decides whether a
// photo of a real ID is worth uploading, and both directions of error are expensive — too strict
// makes the app unusable on a dark table, too loose ships frames the inference service cannot
// score. So each rule is pinned from both sides, plus the auto-shutter hold window on an injected
// clock.

import 'dart:math' as math;
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';

import 'package:fayda_id_check/capture/quality.dart';

/// Pixels of a card lying on a table, with "printed text" in the middle band.
///
/// The ink band is deliberately kept away from the outline corners so a corner window can never
/// sample a text line, and the card is drawn slightly *outside* the default outline so there is a
/// background margin beyond every corner.
Uint8List syntheticLuma({
  required int width,
  required int height,
  double cardLeft = 0.05,
  double cardRight = 0.95,
  double cardTop = 0.20,
  double cardBottom = 0.80,
  int card = 200,
  int table = 60,
  int ink = 30,
  bool withText = true,
}) {
  final Uint8List luma = Uint8List(width * height)..fillRange(0, width * height, table);

  final int x0 = (cardLeft * width).round().clamp(0, width);
  final int x1 = (cardRight * width).round().clamp(0, width);
  final int y0 = (cardTop * height).round().clamp(0, height);
  final int y1 = (cardBottom * height).round().clamp(0, height);
  for (int y = y0; y < y1; y++) {
    for (int x = x0; x < x1; x++) {
      luma[y * width + x] = card;
    }
  }
  if (!withText) {
    return luma;
  }
  final int inkX0 = (0.30 * width).round();
  final int inkX1 = (0.68 * width).round();
  final int lineHeight = math.max(2, (0.015 * height).round());
  final int bandTop = (0.34 * height).round();
  final int bandStep = math.max(lineHeight + 1, (0.045 * height).round());
  for (int line = 0; line < 6; line++) {
    final int start = bandTop + line * bandStep;
    for (int y = start; y < start + lineHeight && y < y1; y++) {
      for (int x = inkX0; x < inkX1 && x < x1; x++) {
        luma[y * width + x] = ink;
      }
    }
  }
  return luma;
}

GrayFrame syntheticFrame({
  int width = 240,
  int height = 320,
  double cardLeft = 0.05,
  double cardRight = 0.95,
  double cardTop = 0.20,
  double cardBottom = 0.80,
  int card = 200,
  int table = 60,
  int ink = 30,
  bool withText = true,
}) =>
    GrayFrame(
      width: width,
      height: height,
      luma: syntheticLuma(
        width: width,
        height: height,
        cardLeft: cardLeft,
        cardRight: cardRight,
        cardTop: cardTop,
        cardBottom: cardBottom,
        card: card,
        table: table,
        ink: ink,
        withText: withText,
      ),
    );

GrayFrame uniformFrame(int size, int value) => GrayFrame(
      width: size,
      height: size,
      luma: Uint8List(size * size)..fillRange(0, size * size, value),
    );

GrayFrame checkerboard(int size, int low, int high) {
  final Uint8List luma = Uint8List(size * size);
  for (int y = 0; y < size; y++) {
    for (int x = 0; x < size; x++) {
      luma[y * size + x] = (x + y) % 2 == 0 ? low : high;
    }
  }
  return GrayFrame(width: size, height: size, luma: luma);
}

/// Horizontal ramp with an exactly constant slope, so the Laplacian cancels it out.
GrayFrame ramp(int width, int height, int step) {
  final Uint8List luma = Uint8List(width * height);
  for (int y = 0; y < height; y++) {
    for (int x = 0; x < width; x++) {
      luma[y * width + x] = x * step;
    }
  }
  return GrayFrame(width: width, height: height, luma: luma);
}

/// A gate whose report always passes, so shutter timing can be tested apart from the pixels.
class _AlwaysPassingGate extends QualityGate {
  @override
  QualityReport evaluate(GrayFrame frame) => QualityReport(
        blurScore: 900,
        glareRatio: 0,
        meanLuma: 120,
        clippedBlackRatio: 0,
        cornersFound: true,
        cornerScores: const <CornerScore>[],
        issues: const <QualityIssue>[],
        documentCoverage: 1,
      );
}

const DocumentOutline _outline = DocumentOutline();
const QualityThresholds _thresholds = QualityThresholds();

void main() {
  group('GrayFrame', () {
    test('refuses a plane that is not width * height long', () {
      expect(
        () => GrayFrame(width: 4, height: 4, luma: Uint8List(15)),
        throwsArgumentError,
      );
    });

    test('indexes row-major', () {
      final GrayFrame frame = syntheticFrame(width: 4, height: 3, withText: false);
      expect(frame.lumaAt(2, 1), frame.luma[1 * 4 + 2]);
      expect(frame.isPortrait, isFalse);
      expect(syntheticFrame(width: 3, height: 4, withText: false).isPortrait, isTrue);
    });
  });

  group('blur', () {
    test('a flat frame scores zero', () {
      expect(QualityGate.laplacianVariance(uniformFrame(32, 120)), 0);
    });

    test('a linear ramp scores zero: the Laplacian cancels a constant slope', () {
      expect(QualityGate.laplacianVariance(ramp(64, 32, 3)), closeTo(0, 1e-9));
    });

    test('a high-contrast checkerboard scores far above the sharpness floor', () {
      final double score = QualityGate.laplacianVariance(checkerboard(64, 0, 255));
      expect(score, greaterThan(100000));
      expect(score, greaterThan(_thresholds.minBlurScore));
    });

    test('a frame narrower than the 3x3 kernel scores zero', () {
      expect(QualityGate.laplacianVariance(syntheticFrame(width: 2, height: 8)), 0);
    });

    test('printed lines add sharpness on top of the card edges', () {
      final double printed = QualityGate.laplacianVariance(syntheticFrame());
      final double blank = QualityGate.laplacianVariance(syntheticFrame(withText: false));

      expect(printed, greaterThan(blank),
          reason: 'detail, not just a silhouette, is what the score is meant to reward');
    });
  });

  group('glare', () {
    test('counts pixels at or above the cutoff and nothing below it', () {
      final Uint8List atCutoff = Uint8List(100)
        ..fillRange(0, 5, QualityThresholds.highlightCutoff);
      final Uint8List justBelow = Uint8List(100)
        ..fillRange(0, 5, QualityThresholds.highlightCutoff - 1);

      expect(
          QualityGate.highlightRatio(GrayFrame(width: 10, height: 10, luma: atCutoff)), 0.05);
      expect(
          QualityGate.highlightRatio(GrayFrame(width: 10, height: 10, luma: justBelow)), 0);
    });

    test('a specular band wider than the tolerance raises the glare issue', () {
      final Uint8List luma = syntheticLuma(width: 240, height: 320);
      for (int y = 100; y < 200; y++) {
        for (int x = 0; x < 240; x++) {
          luma[y * 240 + x] = 252;
        }
      }
      final QualityReport report =
          QualityGate().evaluate(GrayFrame(width: 240, height: 320, luma: luma));

      expect(report.glareRatio, greaterThan(_thresholds.maxGlareRatio));
      expect(report.issues, contains(QualityIssue.glare));
    });

    test('the clean card raises no glare', () {
      expect(QualityGate().evaluate(syntheticFrame()).glareRatio, 0);
    });
  });

  group('exposure', () {
    test('reports the mean and the clipped-black fraction together', () {
      final Uint8List luma = Uint8List(64)
        ..fillRange(0, 32, 0)
        ..fillRange(32, 64, 200);
      final Exposure exposure =
          QualityGate.measureExposure(GrayFrame(width: 8, height: 8, luma: luma));

      expect(exposure.mean, closeTo(100, 1e-9));
      expect(exposure.clippedBlackRatio, closeTo(0.5, 1e-9));
    });

    test('a dark scene raises tooDark, never tooBright', () {
      final QualityReport report = QualityGate()
          .evaluate(syntheticFrame(card: 40, table: 15, ink: 20, withText: false));

      expect(report.meanLuma, lessThan(_thresholds.minMeanLuma));
      expect(report.issues, contains(QualityIssue.tooDark));
      expect(report.issues, isNot(contains(QualityIssue.tooBright)));
    });

    test('a washed-out scene raises tooBright, never tooDark', () {
      final QualityReport report = QualityGate()
          .evaluate(syntheticFrame(card: 240, table: 220, ink: 235, withText: false));

      expect(report.meanLuma, greaterThan(_thresholds.maxMeanLuma));
      expect(report.issues, contains(QualityIssue.tooBright));
      expect(report.issues, isNot(contains(QualityIssue.tooDark)));
    });

    test('a clipped black background is too dark even when the mean looks fine', () {
      final Uint8List luma = syntheticLuma(width: 240, height: 320, card: 160, table: 0);
      final QualityReport report =
          QualityGate().evaluate(GrayFrame(width: 240, height: 320, luma: luma));

      expect(report.meanLuma, greaterThanOrEqualTo(_thresholds.minMeanLuma));
      expect(report.clippedBlackRatio, greaterThan(_thresholds.maxClippedBlackRatio));
      expect(report.issues, contains(QualityIssue.tooDark));
    });
  });

  group('framing', () {
    test('a complete card inside the outline passes with all four corners', () {
      final QualityReport report = QualityGate().evaluate(syntheticFrame());

      expect(report.cornerScores, hasLength(4));
      expect(report.cornerScores.map((CornerScore s) => s.found), everyElement(isTrue));
      expect(report.documentCoverage, greaterThanOrEqualTo(_thresholds.minOutlineCoverage));
      expect(report.isPassing, isTrue,
          reason: 'the clean synthetic card must pass all four gates at once');
    });

    test('the same scene passes at a sensor size that triggers the analysis resample', () {
      final QualityReport large = QualityGate().evaluate(syntheticFrame(width: 480, height: 640));

      expect(large.isPassing, isTrue, reason: 'thresholds are resolution independent');
      expect(large.cornersFound, isTrue);
    });

    test('a corner flush with two frame edges is the one that fails', () {
      final GrayFrame cropped = syntheticFrame(cardLeft: 0.0, cardTop: 0.0);
      final CornerInspection inspection =
          QualityGate.inspectFraming(cropped, _outline, _thresholds);

      expect(inspection.scores[0].insideIsDocument, 1);
      expect(inspection.scores[0].outsideIsDocument, 1,
          reason: 'the out-sample still lands on the card, so there is no corner edge to find');
      expect(inspection.scores[0].found, isFalse);
      expect(inspection.foundCount, 3);
      expect(inspection.coverage, greaterThanOrEqualTo(_thresholds.minOutlineCoverage),
          reason: 'the outline is full of document, so this failure is the corner, not coverage');
      expect(inspection.allFound, isFalse);
      expect(QualityGate().evaluate(cropped).issues, contains(QualityIssue.cornersMissing));
    });

    test('the margin is read diagonally, so a side-only crop with top and bottom room passes', () {
      final CornerInspection inspection = QualityGate.inspectFraming(
        syntheticFrame(cardLeft: 0.0),
        _outline,
        _thresholds,
      );

      expect(inspection.foundCount, 4,
          reason: 'every corner still has background beyond it in the sampled direction');
    });

    test('a single-tone frame finds no corners even though coverage is perfect', () {
      final CornerInspection inspection =
          QualityGate.inspectFraming(uniformFrame(120, 100), _outline, _thresholds);

      expect(inspection.coverage, 1, reason: 'everything looks like document');
      expect(inspection.foundCount, 0);
      expect(inspection.allFound, isFalse,
          reason: 'coverage is checked as well as the corners, or a blank frame would pass');
    });

    test('a dark card on a light table is judged the same as a light card on a dark one', () {
      final CornerInspection inverted = QualityGate.inspectFraming(
        syntheticFrame(card: 60, table: 200, ink: 170),
        _outline,
        _thresholds,
      );

      expect(inverted.foundCount, 4);
      expect(inverted.allFound, isTrue,
          reason: 'which side of the Otsu split is the document is derived from the frame');
    });

    test('a card too small for the outline fails on coverage', () {
      final QualityReport report = QualityGate().evaluate(
          syntheticFrame(cardLeft: 0.30, cardRight: 0.70, cardTop: 0.40, cardBottom: 0.60));

      expect(report.documentCoverage, lessThan(_thresholds.minOutlineCoverage));
      expect(report.issues, contains(QualityIssue.cornersMissing));
    });
  });

  group('upload facts', () {
    test('carry exactly the three contract fields', () {
      final QualityReport report = QualityGate().evaluate(syntheticFrame());
      final Map<String, dynamic> json = report.toUploadFacts().toJson();

      expect(json.keys, unorderedEquals(<String>['blurScore', 'glareRatio', 'cornersFound']));
      expect(json['cornersFound'], isTrue);
      expect(json['blurScore'], report.blurScore);
      expect(json['glareRatio'], report.glareRatio);
    });
  });

  group('meanFrameDifference', () {
    test('a frame equals itself', () {
      final GrayFrame frame = syntheticFrame();
      expect(meanFrameDifference(frame, frame), 0);
    });

    test('measures a constant offset exactly', () {
      expect(meanFrameDifference(uniformFrame(40, 100), uniformFrame(40, 140)),
          closeTo(40, 1e-9));
    });

    test('still compares frames of different sizes', () {
      expect(meanFrameDifference(uniformFrame(20, 100), uniformFrame(50, 20)), closeTo(80, 1e-9));
    });
  });

  group('AutoShutterGate', () {
    final DateTime t0 = DateTime.utc(2026, 5, 1, 12);
    const Duration hold = Duration(milliseconds: 600);

    test('holds until the stability window closes, then fires', () {
      final AutoShutterGate gate = AutoShutterGate(
        qualityGate: _AlwaysPassingGate(),
        hold: hold,
      );
      final GrayFrame still = uniformFrame(40, 120);

      expect(gate.onFrame(still, now: t0).block, ShutterBlock.holding);
      expect(
          gate.onFrame(still, now: t0.add(const Duration(milliseconds: 599))).shouldFire, isFalse);

      final ShutterDecision fired =
          gate.onFrame(still, now: t0.add(const Duration(milliseconds: 600)));
      expect(fired.block, ShutterBlock.ready);
      expect(fired.shouldFire, isTrue);
      expect(fired.heldFor, hold);
      expect(fired.advice, isNull);
      expect(gate.hasFired, isTrue);
    });

    test('a moved phone restarts the hold from the frame after it', () {
      final AutoShutterGate gate = AutoShutterGate(
        qualityGate: _AlwaysPassingGate(),
        hold: hold,
      );
      final GrayFrame still = uniformFrame(40, 120);
      final GrayFrame moved = uniformFrame(40, 220);

      gate.onFrame(still, now: t0);
      gate.onFrame(still, now: t0.add(const Duration(milliseconds: 300)));

      final ShutterDecision unstable =
          gate.onFrame(moved, now: t0.add(const Duration(milliseconds: 400)));
      expect(unstable.block, ShutterBlock.unstableFrame);
      expect(unstable.motion, greaterThan(gate.maxMotion));
      expect(unstable.shouldFire, isFalse);

      final ShutterDecision afterMove =
          gate.onFrame(moved, now: t0.add(const Duration(milliseconds: 900)));
      expect(afterMove.block, ShutterBlock.holding);
      expect(afterMove.heldFor, Duration.zero,
          reason: 'the clock restarts, so 900ms after the first frame is not 600ms of stillness');
      expect(
          gate.onFrame(moved, now: t0.add(const Duration(milliseconds: 1500))).shouldFire, isTrue);
    });

    test('a rejected frame names the gate and carries actionable advice', () {
      final AutoShutterGate gate = AutoShutterGate(hold: hold);
      final ShutterDecision decision = gate.onFrame(uniformFrame(120, 100), now: t0);

      expect(decision.block, ShutterBlock.qualityFailed);
      expect(decision.shouldFire, isFalse);
      expect(decision.issue, QualityIssue.blurry);
      expect(decision.advice, QualityIssue.blurry.advice);
      expect(decision.report.issues, contains(QualityIssue.cornersMissing));
    });

    test('a quality failure discards an almost-complete hold', () {
      final AutoShutterGate gate = AutoShutterGate(hold: hold);
      final GrayFrame good = syntheticFrame();

      gate.onFrame(good, now: t0);
      expect(gate.onFrame(good, now: t0.add(const Duration(milliseconds: 500))).heldFor,
          const Duration(milliseconds: 500));

      final ShutterDecision rejected =
          gate.onFrame(uniformFrame(120, 100), now: t0.add(const Duration(milliseconds: 550)));
      expect(rejected.block, ShutterBlock.qualityFailed);

      // The recovered frame also differs from the rejected one, so it counts as motion; the hold
      // is re-anchored twice over and cannot fire before 600ms of genuinely identical frames.
      expect(gate.onFrame(good, now: t0.add(const Duration(milliseconds: 600))).block,
          ShutterBlock.unstableFrame);
      final ShutterDecision reanchored =
          gate.onFrame(good, now: t0.add(const Duration(milliseconds: 700)));
      expect(reanchored.block, ShutterBlock.holding);
      expect(reanchored.heldFor, Duration.zero);
      expect(
          gate.onFrame(good, now: t0.add(const Duration(milliseconds: 1299))).shouldFire, isFalse);
      expect(
          gate.onFrame(good, now: t0.add(const Duration(milliseconds: 1300))).shouldFire, isTrue);
    });

    test('reset clears the hold and the fired flag', () {
      final AutoShutterGate gate = AutoShutterGate(
        qualityGate: _AlwaysPassingGate(),
        hold: hold,
      );
      final GrayFrame still = uniformFrame(40, 120);
      gate.onFrame(still, now: t0);
      expect(gate.onFrame(still, now: t0.add(hold)).shouldFire, isTrue);

      gate.reset();

      expect(gate.hasFired, isFalse);
      expect(gate.lastReport, isNull);
      expect(gate.onFrame(still, now: t0.add(const Duration(seconds: 5))).heldFor, Duration.zero);
    });
  });
}
