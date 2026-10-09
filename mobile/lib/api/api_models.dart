// Typed models for every endpoint in CONTRACT.md §1, plus the JSON shapes of
// the encrypted inner payloads (§1 document/selfie bodies, §3 envelope).
//
// Field names match the contract exactly; parsing is tolerant of missing
// optional fields but never invents a default decision or score.

import 'dart:convert';
import 'dart:typed_data';

import '../capture/quality.dart' show DocumentQualityFacts;
import 'wire.dart';

/// `documentType` values accepted by `POST /attempts`.
enum DocumentType {
  nationalId('NATIONAL_ID', 'National ID'),
  passport('PASSPORT', 'Passport'),
  drivingLicense('DRIVING_LICENSE', 'Driving licence');

  const DocumentType(this.wireValue, this.label);

  final String wireValue;
  final String label;

  static DocumentType fromWire(String? value) => DocumentType.values.firstWhere(
        (DocumentType t) => t.wireValue == value,
        orElse: () => DocumentType.nationalId,
      );
}

/// Attempt lifecycle states from the service state machine
/// (`CREATED → CAPTURED → INFERRING → DECIDED`, plus failure states).
enum AttemptStatus {
  created('CREATED', 'Attempt created'),
  captured('CAPTURED', 'Captures received'),
  inferring('INFERRING', 'Checking the captures'),
  decided('DECIDED', 'Decision made'),
  failed('FAILED', 'Verification failed'),
  failedRetryable('FAILED_RETRYABLE', 'Could not be completed just now'),
  expired('EXPIRED', 'Attempt expired'),
  unknown('', 'Unknown state');

  const AttemptStatus(this.wireValue, this.userLabel);

  final String wireValue;
  final String userLabel;

  static AttemptStatus fromWire(String? value) => AttemptStatus.values.firstWhere(
        (AttemptStatus s) => s.wireValue == (value ?? ''),
        orElse: () => AttemptStatus.unknown,
      );

  /// Whether this stage belongs on the honest progress rail.
  bool get isProgressStage =>
      this == AttemptStatus.created ||
      this == AttemptStatus.captured ||
      this == AttemptStatus.inferring ||
      this == AttemptStatus.decided;
}

/// The decision composite outcome (CONTRACT.md §4).
enum Decision {
  pass('PASS', 'Verified'),
  review('REVIEW', 'Needs a human review'),
  fail('FAIL', 'Not verified');

  const Decision(this.wireValue, this.plainLabel);

  final String wireValue;
  final String plainLabel;

  static Decision fromWire(String? value) => Decision.values.firstWhere(
        (Decision d) => d.wireValue == value,
        orElse: () => Decision.fail,
      );
}

/// `challenge` from `POST /attempts`.
///
/// `actions` stays a list of raw wire codes: the server owns the sequence and
/// may add actions this build does not have a nicer prompt for.
class Challenge {
  const Challenge({required this.seed, required this.actions});

  final String seed;
  final List<String> actions;

  factory Challenge.fromJson(Map<String, dynamic> json) => Challenge(
        seed: optionalString(json, 'seed') ?? '',
        actions: asJsonList(json['actions'] ?? const <dynamic>[])
            .map((Object? e) => '$e')
            .toList(growable: false),
      );

  Map<String, dynamic> toJson() => <String, dynamic>{'seed': seed, 'actions': actions};
}

/// `uploadKey` from `POST /attempts`: the server echo of the per-attempt key
/// fingerprint. The key itself is never sent.
class UploadKeyInfo {
  const UploadKeyInfo({
    required this.keyId,
    required this.algorithm,
    this.expiresAtUtc,
  });

  final String keyId;
  final String algorithm;
  final DateTime? expiresAtUtc;

  factory UploadKeyInfo.fromJson(Map<String, dynamic> json) => UploadKeyInfo(
        keyId: optionalString(json, 'keyId') ?? '',
        algorithm: optionalString(json, 'algorithm') ?? 'AES-256-GCM',
        expiresAtUtc: parseUtcDate(optionalString(json, 'expiresAtUtc')),
      );

  Map<String, dynamic> toJson() => <String, dynamic>{
        'keyId': keyId,
        'algorithm': algorithm,
        if (expiresAtUtc != null) 'expiresAtUtc': formatUtcMillis(expiresAtUtc!),
      };
}

/// Response `201` of `POST /attempts`.
class AttemptStart {
  const AttemptStart({
    required this.attemptId,
    required this.status,
    required this.challenge,
    required this.uploadKey,
    required this.attemptNo,
    required this.attemptsRemaining,
    this.stageDeadlineUtc,
  });

  final int attemptId;
  final AttemptStatus status;
  final Challenge challenge;
  final UploadKeyInfo uploadKey;
  final DateTime? stageDeadlineUtc;
  final int attemptNo;
  final int attemptsRemaining;

  factory AttemptStart.fromJson(Map<String, dynamic> json) => AttemptStart(
        attemptId: optionalInt(json, 'attemptId') ?? -1,
        status: AttemptStatus.fromWire(optionalString(json, 'status')),
        challenge: Challenge.fromJson(asJsonObject(json['challenge'] ?? const <String, dynamic>{})),
        uploadKey: UploadKeyInfo.fromJson(asJsonObject(json['uploadKey'] ?? const <String, dynamic>{})),
        stageDeadlineUtc: parseUtcDate(optionalString(json, 'stageDeadlineUtc')),
        attemptNo: optionalInt(json, 'attemptNo') ?? 1,
        attemptsRemaining: optionalInt(json, 'attemptsRemaining') ?? 0,
      );
}

/// `POST /attempts/{id}/document` → `202`.
class DocumentUploadResult {
  const DocumentUploadResult({
    required this.attemptId,
    required this.status,
    required this.documentAccepted,
  });

  final int attemptId;
  final AttemptStatus status;
  final bool documentAccepted;

  factory DocumentUploadResult.fromJson(Map<String, dynamic> json) => DocumentUploadResult(
        attemptId: optionalInt(json, 'attemptId') ?? -1,
        status: AttemptStatus.fromWire(optionalString(json, 'status')),
        documentAccepted: optionalBool(json, 'documentAccepted'),
      );
}

/// `POST /attempts/{id}/selfie` response. The contract fixes the request shape
/// (>= 3 frames) and the `CREATED → CAPTURED` transition, not every response
/// field, so unknown fields fall back to what the transition implies.
class SelfieUploadResult {
  const SelfieUploadResult({
    required this.attemptId,
    required this.status,
    required this.framesAccepted,
  });

  final int attemptId;
  final AttemptStatus status;
  final int framesAccepted;

  factory SelfieUploadResult.fromJson(Map<String, dynamic> json) => SelfieUploadResult(
        attemptId: optionalInt(json, 'attemptId') ?? -1,
        status: AttemptStatus.fromWire(optionalString(json, 'status')),
        framesAccepted: optionalInt(json, 'framesAccepted') ?? 0,
      );
}

/// Component scores from the decision composite (CONTRACT.md §4).
class ComponentScores {
  const ComponentScores({
    required this.liveness,
    required this.match,
    required this.document,
  });

  final double liveness;
  final double match;
  final double document;

  factory ComponentScores.fromJson(Map<String, dynamic> json) => ComponentScores(
        liveness: optionalDouble(json, 'liveness') ?? 0,
        match: optionalDouble(json, 'match') ?? 0,
        document: optionalDouble(json, 'document') ?? 0,
      );

  Map<String, dynamic> toJson() => <String, dynamic>{
        'liveness': liveness,
        'match': match,
        'document': document,
      };
}

/// The decided outcome, from `POST /attempts/{id}/verify` and
/// `GET /attempts/{id}`.
class DecisionRecord {
  const DecisionRecord({
    required this.attemptId,
    required this.decision,
    required this.guidance,
    this.compositeScore,
    this.scores,
    this.reasonCode,
    this.thresholdVersion,
    this.decidedAtUtc,
  });

  final int attemptId;
  final Decision decision;

  /// Server-written text. The result screen shows it verbatim — the app must
  /// not rewrite the explanation the institution is accountable for.
  final String guidance;

  final double? compositeScore;
  final ComponentScores? scores;
  final String? reasonCode;
  final String? thresholdVersion;
  final DateTime? decidedAtUtc;

  factory DecisionRecord.fromJson(Map<String, dynamic> json) => DecisionRecord(
        attemptId: optionalInt(json, 'attemptId') ?? -1,
        decision: Decision.fromWire(optionalString(json, 'decision')),
        guidance: optionalString(json, 'guidance') ?? '',
        compositeScore: optionalDouble(json, 'compositeScore'),
        scores: json['scores'] == null
            ? null
            : ComponentScores.fromJson(asJsonObject(json['scores'])),
        reasonCode: optionalString(json, 'reasonCode'),
        thresholdVersion: optionalString(json, 'thresholdVersion'),
        decidedAtUtc: parseUtcDate(optionalString(json, 'decidedAtUtc')),
      );
}

/// `GET /attempts/{id}` — current status plus decision if present.
class AttemptSnapshot {
  const AttemptSnapshot({
    required this.attemptId,
    required this.status,
    required this.attemptNo,
    required this.attemptsRemaining,
    this.decision,
    this.stageDeadlineUtc,
  });

  final int attemptId;
  final AttemptStatus status;
  final int attemptNo;
  final int attemptsRemaining;
  final DecisionRecord? decision;
  final DateTime? stageDeadlineUtc;

  factory AttemptSnapshot.fromJson(Map<String, dynamic> json) => AttemptSnapshot(
        attemptId: optionalInt(json, 'attemptId') ?? -1,
        status: AttemptStatus.fromWire(optionalString(json, 'status')),
        attemptNo: optionalInt(json, 'attemptNo') ?? 1,
        attemptsRemaining: optionalInt(json, 'attemptsRemaining') ?? 0,
        decision: json['decision'] == null
            ? null
            : DecisionRecord.fromJson(asJsonObject(json['decision'])),
        stageDeadlineUtc: parseUtcDate(optionalString(json, 'stageDeadlineUtc')),
      );
}

/// `POST /attempts/{id}/retry` — valid only from `FAILED_RETRYABLE`.
class RetryResult {
  const RetryResult({
    required this.attemptId,
    required this.status,
    required this.attemptNo,
    required this.attemptsRemaining,
  });

  final int attemptId;
  final AttemptStatus status;
  final int attemptNo;
  final int attemptsRemaining;

  factory RetryResult.fromJson(Map<String, dynamic> json) => RetryResult(
        attemptId: optionalInt(json, 'attemptId') ?? -1,
        status: AttemptStatus.fromWire(optionalString(json, 'status')),
        attemptNo: optionalInt(json, 'attemptNo') ?? 1,
        attemptsRemaining: optionalInt(json, 'attemptsRemaining') ?? 0,
      );
}

// ---------------------------------------------------------------------------
// Inner payload shapes (these are what get encrypted into the §3 envelope).
// ---------------------------------------------------------------------------

/// `image` block of the document body.
class CaptureImage {
  const CaptureImage({
    required this.bytes,
    required this.width,
    required this.height,
    required this.mimeType,
  });

  final Uint8List bytes;
  final int width;
  final int height;
  final String mimeType;

  int get byteLength => bytes.length;

  Map<String, dynamic> toJson() => <String, dynamic>{
        'bytes64': encodeBytes64(bytes),
        'width': width,
        'height': height,
        'mimeType': mimeType,
      };
}

/// `ocr` block of the document body.
class DocumentOcr {
  const DocumentOcr({
    this.idNumber,
    this.fullName,
    this.issuingRegion,
    this.expiryDate,
  });

  final String? idNumber;
  final String? fullName;
  final String? issuingRegion;

  /// Plain `yyyy-MM-dd`, as in the contract example.
  final String? expiryDate;

  Map<String, dynamic> toJson() => <String, dynamic>{
        if (idNumber != null) 'idNumber': idNumber,
        if (fullName != null) 'fullName': fullName,
        if (issuingRegion != null) 'issuingRegion': issuingRegion,
        if (expiryDate != null) 'expiryDate': expiryDate,
      };
}

/// The decrypted body of `POST /attempts/{id}/document`.
class DocumentPayload {
  const DocumentPayload({
    required this.image,
    required this.quality,
    this.ocr,
  });

  final CaptureImage image;
  final DocumentQualityFacts quality;
  final DocumentOcr? ocr;

  String encodeJson() => _jsonString(<String, dynamic>{
        'image': image.toJson(),
        'ocr': ocr?.toJson() ?? <String, dynamic>{},
        'quality': quality.toJson(),
      });
}

/// One liveness frame: `frames[]` of the selfie body.
class SelfieFrame {
  const SelfieFrame({
    required this.bytes,
    required this.capturedAtMs,
    this.action,
    this.width = 0,
    this.height = 0,
  });

  final Uint8List bytes;

  /// The challenge action this frame was recorded for, if any.
  final String? action;
  final int capturedAtMs;
  final int width;
  final int height;

  Map<String, dynamic> toJson() => <String, dynamic>{
        'bytes64': encodeBytes64(bytes),
        if (action != null) 'action': action,
        'capturedAtMs': capturedAtMs,
      };
}

/// The decrypted body of `POST /attempts/{id}/selfie`.
///
/// `executedActions` is recorded in the order the user was prompted, and the
/// seed is echoed so the server can re-derive the sequence (a mismatch is
/// `CHALLENGE_SEQUENCE_MISMATCH`).
class SelfiePayload {
  const SelfiePayload({
    required this.frames,
    required this.executedActions,
    required this.challengeSeed,
    required this.quality,
  });

  final List<SelfieFrame> frames;
  final List<String> executedActions;
  final String challengeSeed;
  final SelfieQuality quality;

  /// The contract requires at least three frames; enforced on the client so a
  /// doomed request is never sent.
  static const int minimumFrames = 3;

  bool get hasMinimumFrames => frames.length >= minimumFrames;

  String encodeJson() => _jsonString(<String, dynamic>{
        'frames': frames.map((SelfieFrame f) => f.toJson()).toList(growable: false),
        'executedActions': executedActions,
        'challengeSeed': challengeSeed,
        'quality': quality.toJson(),
      });
}

/// `quality` block of the selfie body: sharpness plus where the face was.
class SelfieQuality {
  const SelfieQuality({required this.blurScore, this.faceBox});

  final double blurScore;

  /// `[x, y, width, height]` of the detected face, or null when the app did
  /// not localise one.
  final List<int>? faceBox;

  Map<String, dynamic> toJson() => <String, dynamic>{
        'blurScore': blurScore,
        if (faceBox != null) 'faceBox': faceBox,
      };
}

String _jsonString(Map<String, dynamic> json) => jsonEncode(json);
