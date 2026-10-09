// The attempt flow, in one testable class.
//
// Nothing in here imports a widget or the camera plugin: it takes an
// [VerificationApi], a captured document image, and selfie frames, and it produces a
// [VerificationState]. That is what makes the whole sequence — key generation, the
// `uploadKey.keyId` check, envelope sealing, the ≥ 3 frame rule, retry, wipe — unit
// testable with a fake that records calls.
//
// The invariants this class owns:
//   * the per-attempt key is generated *before* `POST /attempts` and destroyed when the
//     attempt reaches a terminal state;
//   * the server's echoed `keyId` must equal the local one, or the attempt is abandoned
//     before a single image byte is sent;
//   * capture frames live only in this object's memory and are dropped as soon as the
//     envelope has been sent;
//   * no decision text is invented here — the server's `guidance` is what the UI shows.

import 'package:flutter/foundation.dart';

import '../api/api_exception.dart';
import '../api/api_models.dart';
import '../api/verification_api.dart';
import '../capture/quality.dart' show DocumentQualityFacts;
import '../crypto/envelope.dart';

/// Where the user is in the flow.
enum VerificationStage {
  idle,
  starting,
  awaitingDocument,
  documentUploading,
  awaitingLiveness,
  livenessUploading,
  deciding,
  finished,
  blocked,
}

/// Everything the UI renders.
class VerificationState {
  const VerificationState({
    required this.stage,
    this.attemptId,
    this.attemptNo = 1,
    this.attemptsRemaining = 0,
    this.challenge,
    this.documentAccepted,
    this.framesAccepted = 0,
    this.decision,
    this.failure,
    this.busy = false,
  });

  final VerificationStage stage;
  final int? attemptId;
  final int attemptNo;
  final int attemptsRemaining;
  final Challenge? challenge;
  final bool? documentAccepted;
  final int framesAccepted;
  final DecisionRecord? decision;

  /// The reason the flow stopped, when it stopped for a reason the user must act on.
  final FlowOutcome? failure;
  final bool busy;

  bool get canStartOver => stage == VerificationStage.finished || stage == VerificationStage.blocked;

  /// The stages already reached, for the honest progress rail.
  List<AttemptStatus> get reachedStages => <AttemptStatus>[
        if (attemptId != null) AttemptStatus.created,
        if (documentAccepted == true) AttemptStatus.captured,
        if (stage == VerificationStage.deciding) AttemptStatus.inferring,
        if (decision != null) AttemptStatus.decided,
      ];
}

/// A finished document capture, already resized by the camera layer.
class DocumentCapture {
  const DocumentCapture({
    required this.image,
    required this.quality,
    this.ocr,
  });

  final CaptureImage image;
  final DocumentQualityFacts quality;
  final DocumentOcr? ocr;
}

/// Runs one verification attempt at a time.
class VerificationController extends ChangeNotifier {
  VerificationController({
    required VerificationApi api,
    Future<AttemptKey> Function()? keyFactory,
    DateTime Function()? clock,
  })  : _api = api,
        _keyFactory = keyFactory ?? AttemptKey.generate,
        _clock = clock ?? DateTime.now,
        _state = const VerificationState(stage: VerificationStage.idle);

  final VerificationApi _api;
  final Future<AttemptKey> Function() _keyFactory;
  final DateTime Function() _clock;

  VerificationState _state;
  AttemptKey? _key;
  List<SelfieFrame> _frames = <SelfieFrame>[];
  List<String> _executedActions = <String>[];

  VerificationState get state => _state;

  /// Actions still to be prompted for, in the order the server derived them.
  List<String> get pendingActions => <String>[
        for (final String action in _state.challenge?.actions ?? const <String>[])
          if (!_executedActions.contains(action)) action,
      ];

  /// `POST /attempts`, then stop at the document gate.
  ///
  /// Returns the resulting state; callers render it, they do not guess it.
  Future<VerificationState> start({
    DocumentType documentType = DocumentType.nationalId,
    String deviceInfo = 'Fayda-ID Check',
  }) async {
    _publish(const VerificationState(stage: VerificationStage.starting, busy: true));
    try {
      final AttemptKey key = await _keyFactory();
      final AttemptStart started = await _api.startAttempt(
        StartAttemptRequest(documentType: documentType, deviceInfo: deviceInfo),
        attemptKey: key,
      );
      if (started.uploadKey.keyId.isNotEmpty && started.uploadKey.keyId != key.keyId) {
        // The server holds a different key than this device generated. Sending captures
        // under it could only fail, and it would mean the TLS path is not what we think.
        key.destroy();
        _publish(const VerificationState(
          stage: VerificationStage.blocked,
          failure: FlowOutcome(
            kind: FlowOutcomeKind.technicalProblem,
            title: 'This attempt was started on another device',
            body: 'The service recognised a different session key than the one this device '
                'generated, so nothing was uploaded. Start the attempt again on this phone.',
            actionLabel: 'Start again',
            canRetryAttempt: false,
          ),
        ));
        return _state;
      }
      _key = key;
      _frames = <SelfieFrame>[];
      _executedActions = <String>[];
      _publish(VerificationState(
        stage: VerificationStage.awaitingDocument,
        attemptId: started.attemptId,
        attemptNo: started.attemptNo,
        attemptsRemaining: started.attemptsRemaining,
        challenge: started.challenge,
      ));
    } on ApiFailure catch (failure) {
      _block(failure);
    } catch (error) {
      _publish(const VerificationState(
        stage: VerificationStage.blocked,
        failure: FlowOutcome(
          kind: FlowOutcomeKind.technicalProblem,
          title: 'The attempt could not be started',
          body: 'Something unexpected happened before any capture was sent. Nothing left '
              'this device.',
          actionLabel: 'Try again',
          canRetryAttempt: true,
        ),
      ));
      debugPrint('startAttempt failed: ${error.runtimeType}');
    }
    return _state;
  }

  /// `POST /attempts/{id}/document` — sealed, then the plaintext is dropped.
  Future<VerificationState> uploadDocument(DocumentCapture capture) async {
    final AttemptKey? key = _key;
    final int? attemptId = _state.attemptId;
    if (key == null || attemptId == null) {
      return _refuseBecauseNoAttempt();
    }
    _publish(_state.copyWith(stage: VerificationStage.documentUploading, busy: true));
    try {
      final DocumentPayload payload = DocumentPayload(
        image: capture.image,
        quality: capture.quality,
        ocr: capture.ocr,
      );
      final Envelope envelope = await sealEnvelope(
        plaintextJson: payload.encodeJson(),
        key: key,
        now: _clock(),
      );
      final DocumentUploadResult result = await _api.uploadDocument(attemptId, envelope);
      _publish(_state.copyWith(
        stage: VerificationStage.awaitingLiveness,
        documentAccepted: result.documentAccepted,
        clearFailure: true,
        busy: false,
      ));
    } on ApiFailure catch (failure) {
      _block(failure);
    }
    return _state;
  }

  /// Records one selfie frame against the action the user was just prompted for.
  void addSelfieFrame({
    required Uint8List bytes,
    required String action,
    required int width,
    required int height,
  }) {
    _executedActions.add(action);
    _frames = <SelfieFrame>[
      ..._frames,
      SelfieFrame(
        bytes: bytes,
        capturedAtMs: _clock().toUtc().millisecondsSinceEpoch,
        action: action,
        width: width,
        height: height,
      ),
    ];
    _publish(_state.copyWith(framesAccepted: _frames.length, clearFailure: true));
  }

  /// `POST /attempts/{id}/selfie`, then `POST .../verify`.
  ///
  /// Refuses locally when fewer than three frames were recorded: the contract would reject
  /// it anyway, and a doomed request is one the user has to sit through.
  Future<VerificationState> submitLiveness({required SelfieQuality quality}) async {
    final AttemptKey? key = _key;
    final int? attemptId = _state.attemptId;
    if (key == null || attemptId == null) {
      return _refuseBecauseNoAttempt();
    }
    if (_frames.length < SelfiePayload.minimumFrames) {
      _publish(_state.copyWith(
        failure: const FlowOutcome(
          kind: FlowOutcomeKind.captureRejected,
          title: 'Not enough of the movement check was recorded',
          body: 'At least three frames are needed to check liveness. Repeat the sequence and '
              'hold each position until the prompt changes.',
          actionLabel: 'Repeat the movement check',
          canRetryAttempt: true,
        ),
      ));
      return _state;
    }
    _publish(_state.copyWith(stage: VerificationStage.livenessUploading, busy: true));
    try {
      final SelfiePayload payload = SelfiePayload(
        frames: List<SelfieFrame>.unmodifiable(_frames),
        executedActions: List<String>.unmodifiable(_executedActions),
        challengeSeed: _state.challenge?.seed ?? '',
        quality: quality,
      );
      final Envelope envelope = await sealEnvelope(
        plaintextJson: payload.encodeJson(),
        key: key,
        now: _clock(),
      );
      final SelfieUploadResult uploaded = await _api.uploadSelfie(attemptId, envelope);
      // The plaintext is inside the envelope now; the local copies have no further use.
      _dropFrames();
      _publish(_state.copyWith(
        stage: VerificationStage.deciding,
        framesAccepted: uploaded.framesAccepted,
        clearFailure: true,
        busy: true,
      ));
      final DecisionRecord decision = await _api.verify(attemptId);
      _publish(_state.copyWith(
        stage: VerificationStage.finished,
        decision: decision,
        clearFailure: true,
        busy: false,
      ));
    } on ApiFailure catch (failure) {
      _dropFrames();
      _block(failure);
    }
    return _state;
  }

  /// `POST /attempts/{id}/retry` — only valid from `FAILED_RETRYABLE`.
  Future<VerificationState> retry() async {
    final int? attemptId = _state.attemptId;
    if (attemptId == null) {
      return _refuseBecauseNoAttempt();
    }
    _publish(_state.copyWith(busy: true));
    try {
      final RetryResult result = await _api.retryAttempt(attemptId);
      _publish(VerificationState(
        stage: VerificationStage.awaitingLiveness,
        attemptId: result.attemptId,
        attemptNo: result.attemptNo,
        attemptsRemaining: result.attemptsRemaining,
        challenge: _state.challenge,
        documentAccepted: true,
      ));
    } on ApiFailure catch (failure) {
      _block(failure);
    }
    return _state;
  }

  /// `GET /attempts/{id}` — what stage the server thinks we are in.
  Future<VerificationState> refresh() async {
    final int? attemptId = _state.attemptId;
    if (attemptId == null) {
      return _refuseBecauseNoAttempt();
    }
    try {
      final AttemptSnapshot snapshot = await _api.fetchAttempt(attemptId);
      _publish(VerificationState(
        stage: snapshot.decision != null ? VerificationStage.finished : _state.stage,
        attemptId: snapshot.attemptId,
        attemptNo: snapshot.attemptNo,
        attemptsRemaining: snapshot.attemptsRemaining,
        challenge: _state.challenge,
        documentAccepted: _state.documentAccepted,
        decision: snapshot.decision,
        failure: snapshot.status == AttemptStatus.expired
            ? const FlowOutcome(
                kind: FlowOutcomeKind.attemptLost,
                title: 'This attempt expired',
                body: 'Too much time passed between the captures, so the service discarded '
                    'them. Nothing was kept: start the attempt again.',
                actionLabel: 'Start a new attempt',
                canRetryAttempt: false,
              )
            : _state.failure,
      ));
    } on ApiFailure catch (failure) {
      _block(failure);
    }
    return _state;
  }

  /// Back to the beginning, with every volatile byte gone.
  void reset() {
    _dropFrames();
    _key?.destroy();
    _key = null;
    _executedActions = <String>[];
    _api.clear();
    _publish(const VerificationState(stage: VerificationStage.idle));
  }

  @override
  void dispose() {
    _dropFrames();
    _key?.destroy();
    _key = null;
    super.dispose();
  }

  void _block(ApiFailure failure) {
    _publish(_state.copyWith(
      stage: VerificationStage.blocked,
      failure: failure.outcome,
      busy: false,
    ));
  }

  VerificationState _refuseBecauseNoAttempt() {
    _publish(const VerificationState(
      stage: VerificationStage.blocked,
      failure: FlowOutcome(
        kind: FlowOutcomeKind.attemptLost,
        title: 'There is no attempt in progress',
        body: 'Start a new attempt; the previous one was already closed and its captures '
            'were discarded.',
        actionLabel: 'Start a new attempt',
        canRetryAttempt: false,
      ),
    ));
    return _state;
  }

  void _dropFrames() {
    for (final SelfieFrame frame in _frames) {
      frame.bytes.fillRange(0, frame.bytes.length, 0);
    }
    _frames = <SelfieFrame>[];
  }

  void _publish(VerificationState next) {
    _state = next;
    notifyListeners();
  }
}

/// Copy-with for a state this large would otherwise need a generated file for.
extension VerificationStateCopy on VerificationState {
  VerificationState copyWith({
    VerificationStage? stage,
    int? attemptId,
    int? attemptNo,
    int? attemptsRemaining,
    Challenge? challenge,
    bool? documentAccepted,
    int? framesAccepted,
    DecisionRecord? decision,
    FlowOutcome? failure,
    bool clearFailure = false,
    bool? busy,
  }) =>
      VerificationState(
        stage: stage ?? this.stage,
        attemptId: attemptId ?? this.attemptId,
        attemptNo: attemptNo ?? this.attemptNo,
        attemptsRemaining: attemptsRemaining ?? this.attemptsRemaining,
        challenge: challenge ?? this.challenge,
        documentAccepted: documentAccepted ?? this.documentAccepted,
        framesAccepted: framesAccepted ?? this.framesAccepted,
        decision: decision ?? this.decision,
        failure: clearFailure ? null : (failure ?? this.failure),
        busy: busy ?? this.busy,
      );
}
