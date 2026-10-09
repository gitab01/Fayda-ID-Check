// The attempt flow, with the network replaced by a recorder.
//
// What is under test here is the set of promises the app makes to the user:
//   * nothing is uploaded when the service echoes a key this device did not send;
//   * a sequence too short to pass the contract is never sent at all;
//   * each contract failure lands on a distinct outcome the UI can act on;
//   * frames are gone from the controller once they have been sealed.

import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';

import 'package:fayda_id_check/api/api_exception.dart';
import 'package:fayda_id_check/api/api_models.dart';
import 'package:fayda_id_check/api/verification_api.dart';
import 'package:fayda_id_check/capture/quality.dart' show DocumentQualityFacts;
import 'package:fayda_id_check/crypto/envelope.dart';
import 'package:fayda_id_check/state/verification_controller.dart';

/// Records every call and returns canned responses; the only boundary the flow needs.
class RecordingApi implements VerificationApi {
  final List<String> calls = <String>[];
  final List<Envelope> envelopes = <Envelope>[];
  final List<AttemptKey> keys = <AttemptKey>[];

  /// What the service echoes back as the upload key fingerprint. Empty means "the same one".
  String echoKeyId = '';
  ApiFailure? failureAt;
  AttemptStatus statusAfterRetry = AttemptStatus.captured;

  @override
  Future<AttemptStart> startAttempt(StartAttemptRequest request,
      {required AttemptKey attemptKey}) async {
    calls.add('start');
    keys.add(attemptKey);
    _maybeFail('start');
    return AttemptStart(
      attemptId: 42,
      status: AttemptStatus.created,
      challenge: const Challenge(seed: 'seed-1', actions: <String>['BLINK', 'NOD', 'SMILE']),
      uploadKey: UploadKeyInfo(keyId: echoKeyId.isEmpty ? attemptKey.keyId : echoKeyId,
          algorithm: 'AES-256-GCM'),
      attemptNo: 1,
      attemptsRemaining: 3,
    );
  }

  @override
  Future<DocumentUploadResult> uploadDocument(int attemptId, Envelope envelope) async {
    calls.add('document');
    envelopes.add(envelope);
    _maybeFail('document');
    return const DocumentUploadResult(
        attemptId: 42, status: AttemptStatus.captured, documentAccepted: true);
  }

  @override
  Future<SelfieUploadResult> uploadSelfie(int attemptId, Envelope envelope) async {
    calls.add('selfie');
    envelopes.add(envelope);
    _maybeFail('selfie');
    return const SelfieUploadResult(
        attemptId: 42, status: AttemptStatus.captured, framesAccepted: 6);
  }

  @override
  Future<DecisionRecord> verify(int attemptId) async {
    calls.add('verify');
    _maybeFail('verify');
    return const DecisionRecord(
      attemptId: 42,
      decision: Decision.pass,
      guidance: 'Your identity was confirmed.',
    );
  }

  @override
  Future<AttemptSnapshot> fetchAttempt(int attemptId) async {
    calls.add('fetch');
    _maybeFail('fetch');
    return const AttemptSnapshot(
        attemptId: 42, status: AttemptStatus.captured, attemptNo: 1, attemptsRemaining: 2);
  }

  @override
  Future<RetryResult> retryAttempt(int attemptId) async {
    calls.add('retry');
    _maybeFail('retry');
    return RetryResult(
        attemptId: 42, status: statusAfterRetry, attemptNo: 2, attemptsRemaining: 2);
  }

  @override
  void clear() => calls.add('clear');

  void _maybeFail(String call) {
    final ApiFailure? failure = failureAt;
    if (failure != null) {
      failureAt = null;
      throw failure;
    }
  }
}

const List<int> _tinyJpeg = <int>[0xFF, 0xD8, 0xFF, 0xD9];

DocumentCapture _capture() => DocumentCapture(
      image: CaptureImage(
        bytes: Uint8List.fromList(_tinyJpeg),
        width: 2,
        height: 2,
        mimeType: 'image/jpeg',
      ),
      quality: const DocumentQualityFacts(blurScore: 180, glareRatio: 0.01, cornersFound: true),
    );

void main() {
  late RecordingApi api;
  late VerificationController controller;

  setUp(() {
    api = RecordingApi();
    controller = VerificationController(api: api, clock: () => DateTime.utc(2026, 5, 1, 12));
  });

  tearDown(() => controller.dispose());

  Future<void> startAndCaptureDocument() async {
    await controller.start();
    await controller.uploadDocument(_capture());
  }

  test('a mismatched echoed keyId stops the attempt before any upload', () async {
    api.echoKeyId = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';

    final VerificationState state = await controller.start();

    expect(state.stage, VerificationStage.blocked);
    expect(state.failure!.kind, FlowOutcomeKind.technicalProblem);
    expect(api.calls, <String>['start'], reason: 'the flow must not reach a capture call');
    expect(state.attemptId, isNull);
  });

  test('the document step seals an envelope under the key this device generated', () async {
    await startAndCaptureDocument();

    expect(controller.state.stage, VerificationStage.awaitingLiveness);
    expect(controller.state.documentAccepted, isTrue);
    final Envelope envelope = api.envelopes.single;
    expect(envelope.keyId, api.keys.single.keyId);
    expect(envelope.sentAtUtc, '2026-05-01T12:00:00.000Z');
  });

  test('fewer than the contract minimum of frames is refused locally', () async {
    await startAndCaptureDocument();
    controller.addSelfieFrame(bytes: Uint8List.fromList(_tinyJpeg), action: 'BLINK', width: 2, height: 2);
    controller.addSelfieFrame(bytes: Uint8List.fromList(_tinyJpeg), action: 'NOD', width: 2, height: 2);

    final VerificationState state =
        await controller.submitLiveness(quality: const SelfieQuality(blurScore: 120));

    expect(api.calls, isNot(contains('selfie')),
        reason: 'a doomed request must not be sent');
    expect(state.stage, VerificationStage.awaitingLiveness);
    expect(state.failure!.kind, FlowOutcomeKind.captureRejected);
  });

  test('a full sequence uploads, verifies and finishes with the server guidance', () async {
    await startAndCaptureDocument();
    for (final String action in <String>['BLINK', 'NOD', 'SMILE']) {
      controller.addSelfieFrame(
          bytes: Uint8List.fromList(_tinyJpeg), action: action, width: 2, height: 2);
      controller.addSelfieFrame(
          bytes: Uint8List.fromList(_tinyJpeg), action: action, width: 2, height: 2);
    }
    expect(controller.pendingActions, isEmpty);

    final VerificationState state =
        await controller.submitLiveness(quality: const SelfieQuality(blurScore: 140));

    expect(api.calls, <String>['start', 'document', 'selfie', 'verify']);
    expect(state.stage, VerificationStage.finished);
    expect(state.decision!.guidance, 'Your identity was confirmed.');
    expect(state.reachedStages, contains(AttemptStatus.decided));
  });

  test('inference being down maps to the retryable outcome and recovers through retry', () async {
    await startAndCaptureDocument();
    for (final String action in <String>['BLINK', 'NOD', 'SMILE']) {
      controller.addSelfieFrame(
          bytes: Uint8List.fromList(_tinyJpeg), action: action, width: 2, height: 2);
    }
    api.failureAt = const ApiFailure(
      kind: ApiErrorKind.inferenceUnavailable,
      code: 'INFERENCE_UNAVAILABLE',
      message: 'model worker unavailable',
      httpStatus: 503,
      retryable: true,
    );

    VerificationState state =
        await controller.submitLiveness(quality: const SelfieQuality(blurScore: 140));
    expect(state.stage, VerificationStage.blocked);
    expect(state.failure!.kind, FlowOutcomeKind.serviceUnavailable);
    expect(state.failure!.canRetryAttempt, isTrue);

    state = await controller.retry();
    expect(state.stage, VerificationStage.awaitingLiveness);
    expect(state.attemptsRemaining, 2);
  });

  test('an expired attempt read back from the service lands on the lost-attempt outcome', () async {
    await startAndCaptureDocument();
    api.failureAt = const ApiFailure(
      kind: ApiErrorKind.notFound,
      code: 'ATTEMPT_EXPIRED',
      message: 'attempt expired',
      httpStatus: 404,
    );

    final VerificationState state = await controller.refresh();

    expect(state.failure!.kind, FlowOutcomeKind.attemptLost);
    expect(state.failure!.canRetryAttempt, isFalse);
  });

  test('reset closes the API session and clears the attempt', () async {
    await startAndCaptureDocument();

    controller.reset();

    expect(controller.state.stage, VerificationStage.idle);
    expect(controller.state.attemptId, isNull);
    expect(api.calls, contains('clear'));
  });

  test('a step with no attempt in progress is refused instead of guessing', () async {
    final VerificationState state = await controller.uploadDocument(_capture());

    expect(state.stage, VerificationStage.blocked);
    expect(state.failure!.kind, FlowOutcomeKind.attemptLost);
    expect(api.calls, isEmpty);
  });
}
