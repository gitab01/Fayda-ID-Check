// Error handling for CONTRACT.md §1.
//
// The contract lists one outcome per status code, and the app must not collapse
// them into a single error screen: a signed-out user, a rate-limited user and a
// service that is briefly down all need different words and different actions.
// [ApiFailure] carries the machine facts; [FlowOutcome] carries the
// user-facing result derived from them.

import 'wire.dart';

/// Coarse classification of a failed call, one value per contract status.
enum ApiErrorKind {
  /// `401` — the JWT is missing or expired.
  unauthenticated,

  /// `403` — authenticated, but the wrong scope for this endpoint.
  forbidden,

  /// `404` — the attempt id does not exist (or belongs to somebody else).
  notFound,

  /// `409` — the attempt is not in the state this call expects.
  stateConflict,

  /// `409` with `CHALLENGE_SEQUENCE_MISMATCH` — the recorded liveness sequence
  /// is not the one the server derived from the seed.
  challengeMismatch,

  /// `422` — the decrypted payload failed validation.
  validation,

  /// `429` — rate limited.
  rateLimited,

  /// `503` — inference unavailable; the attempt goes to `FAILED_RETRYABLE`.
  inferenceUnavailable,

  /// Any other non-2xx status.
  serverError,

  /// The request never completed (offline, DNS, timeout).
  transport,

  /// A 2xx response whose body the client could not read.
  malformedResponse,
}

/// The distinct thing the user is told and can do next.
enum FlowOutcomeKind {
  signedOut,
  wrongPermissions,
  attemptLost,
  alreadySubmitted,
  challengeMismatch,
  captureRejected,
  tooManyRequests,
  serviceUnavailable,
  offline,
  technicalProblem,
}

/// A user-facing failure: title, plain-language body, and the single action
/// offered. Never a bare "Verification failed".
class FlowOutcome {
  const FlowOutcome({
    required this.kind,
    required this.title,
    required this.body,
    required this.actionLabel,
    required this.canRetryAttempt,
  });

  final FlowOutcomeKind kind;
  final String title;
  final String body;
  final String actionLabel;

  /// Whether the *attempt* may be retried here. Distinct from
  /// [ApiFailure.retryable], which only says the HTTP call may be repeated.
  final bool canRetryAttempt;

  @override
  String toString() => 'FlowOutcome(${kind.name}: $title)';
}

/// Thrown by [VerificationApi] for every non-success response.
class ApiFailure implements Exception {
  const ApiFailure({
    required this.kind,
    required this.code,
    required this.message,
    this.httpStatus,
    this.requestId,
    this.retryable = false,
    this.retryAfter,
  });

  final ApiErrorKind kind;

  /// Machine-readable code from the error body, e.g. `ATTEMPT_NOT_CAPTURED`.
  final String code;
  final String message;
  final int? httpStatus;
  final String? requestId;
  final bool retryable;

  /// `Retry-After` hint from a `429`, when the service sent one.
  final Duration? retryAfter;

  /// Builds a failure from a response, following the contract's error shape
  /// `{ code, message, requestId, retryable }`.
  factory ApiFailure.fromResponse({
    required int statusCode,
    required Map<String, dynamic> body,
    Duration? retryAfter,
  }) {
    final String code = optionalString(body, 'code') ?? '';
    final String message = optionalString(body, 'message') ?? '';
    final String? requestId = optionalString(body, 'requestId');
    final bool bodySaysRetryable = optionalBool(body, 'retryable');
    final ApiErrorKind kind = ApiErrorKindClassifier.classify(statusCode, code);
    return ApiFailure(
      kind: kind,
      code: code.isEmpty ? _defaultCode(statusCode) : code,
      message: message.isEmpty ? _fallbackMessage(statusCode) : message,
      httpStatus: statusCode,
      requestId: requestId,
      retryable: bodySaysRetryable || kind == ApiErrorKind.inferenceUnavailable || kind == ApiErrorKind.rateLimited,
      retryAfter: retryAfter,
    );
  }

  factory ApiFailure.transport({
    required String message,
    String code = 'TRANSPORT_ERROR',
  }) =>
      ApiFailure(
        kind: ApiErrorKind.transport,
        code: code,
        message: message,
        retryable: true,
      );

  factory ApiFailure.malformed({required String message}) => ApiFailure(
        kind: ApiErrorKind.malformedResponse,
        code: 'MALFORMED_RESPONSE',
        message: message,
        retryable: true,
      );

  /// The user-facing result of this failure. Each kind is distinct, so the UI
  /// can show the right words and offer the right button.
  FlowOutcome get outcome {
    switch (kind) {
      case ApiErrorKind.unauthenticated:
        return const FlowOutcome(
          kind: FlowOutcomeKind.signedOut,
          title: 'Your session ended',
          body: 'You are still who you are — you just need to unlock the app '
              'again before the service will accept the attempt.',
          actionLabel: 'Unlock the app',
          canRetryAttempt: true,
        );
      case ApiErrorKind.forbidden:
        return const FlowOutcome(
          kind: FlowOutcomeKind.wrongPermissions,
          title: 'This account cannot run that step',
          body: 'Your account is not allowed to make verification attempts on '
              'this service. Nothing about your capture is wrong, so retrying '
              'will not help — please contact the organisation that gave you '
              'access.',
          actionLabel: 'Back to start',
          canRetryAttempt: false,
        );
      case ApiErrorKind.notFound:
        return const FlowOutcome(
          kind: FlowOutcomeKind.attemptLost,
          title: 'We have lost track of this attempt',
          body: 'The service no longer knows this attempt — it may have '
              'expired while you were capturing. Start a fresh attempt; your '
              'captures are not sent anywhere.',
          actionLabel: 'Start a new attempt',
          canRetryAttempt: false,
        );
      case ApiErrorKind.challengeMismatch:
        return const FlowOutcome(
          kind: FlowOutcomeKind.challengeMismatch,
          title: 'The movement check did not line up',
          body: 'The movements you performed did not match the ones this '
              'attempt asked for, so the recording was rejected. Repeat the '
              'liveness sequence and follow the prompts in the order shown.',
          actionLabel: 'Repeat the movement check',
          canRetryAttempt: true,
        );
      case ApiErrorKind.stateConflict:
        return const FlowOutcome(
          kind: FlowOutcomeKind.alreadySubmitted,
          title: 'That step is already done',
          body: 'The attempt has already moved past this step, so the '
              'submission was ignored. Continue from the current stage.',
          actionLabel: 'Continue',
          canRetryAttempt: false,
        );
      case ApiErrorKind.validation:
        return const FlowOutcome(
          kind: FlowOutcomeKind.captureRejected,
          title: 'The capture could not be used',
          body: 'The service checked your capture and found it unusable. '
              'Capture the document and face again — better light and holding '
              'the phone steady are the two things that help most.',
          actionLabel: 'Capture again',
          canRetryAttempt: true,
        );
      case ApiErrorKind.rateLimited:
        return const FlowOutcome(
          kind: FlowOutcomeKind.tooManyRequests,
          title: 'Slow down for a moment',
          body: 'Too many requests from this device just now. Wait a little '
              'and try again — your attempt is still where you left it.',
          actionLabel: 'Try again in a minute',
          canRetryAttempt: true,
        );
      case ApiErrorKind.inferenceUnavailable:
        return const FlowOutcome(
          kind: FlowOutcomeKind.serviceUnavailable,
          title: 'The checking service is not available',
          body: 'Your captures reached the service, but the component that '
              'scores them is temporarily unavailable. Nothing about you or '
              'your documents is in question — you can retry, and the attempt '
              'has been marked as retryable.',
          actionLabel: 'Retry verification',
          canRetryAttempt: true,
        );
      case ApiErrorKind.serverError:
        return const FlowOutcome(
          kind: FlowOutcomeKind.technicalProblem,
          title: 'Something went wrong on our side',
          body: 'The service returned an unexpected error. Your captures were '
              'not stored on this device — try again, or come back later.',
          actionLabel: 'Try again',
          canRetryAttempt: true,
        );
      case ApiErrorKind.transport:
        return const FlowOutcome(
          kind: FlowOutcomeKind.offline,
          title: 'No connection right now',
          body: 'The app could not reach the verification service. Check the '
              'network and send the captures again — they are still only in '
              'memory on this device.',
          actionLabel: 'Try again',
          canRetryAttempt: true,
        );
      case ApiErrorKind.malformedResponse:
        return const FlowOutcome(
          kind: FlowOutcomeKind.technicalProblem,
          title: 'The service replied in an unexpected way',
          body: 'The app could not read the reply, so no decision was made. '
              'Try the step again.',
          actionLabel: 'Try again',
          canRetryAttempt: true,
        );
    }
  }

  bool get isChallengeMismatch => kind == ApiErrorKind.challengeMismatch;

  /// Stand-in code for an error body that omitted `code`.
  static String _defaultCode(int status) => 'HTTP_$status';

  /// Stand-in message for an error body that omitted `message`.
  static String _fallbackMessage(int status) {
    switch (status) {
      case 401:
        return 'Not authenticated.';
      case 403:
        return 'Not allowed to perform this step.';
      case 404:
        return 'Unknown attempt.';
      case 409:
        return 'The attempt is not in the right state for this step.';
      case 422:
        return 'The payload failed validation.';
      case 429:
        return 'Rate limited.';
      case 503:
        return 'Inference is unavailable.';
      default:
        return 'The service returned status $status.';
    }
  }

  @override
  String toString() =>
      'ApiFailure(${kind.name}, status: $httpStatus, code: $code, '
      'requestId: $requestId, retryable: $retryable)';
}

/// Maps `(status, code)` onto a kind. The status is the contract's primary
/// signal; a specific code can sharpen it within the same status.
class ApiErrorKindClassifier {
  const ApiErrorKindClassifier._();

  static ApiErrorKind classify(int statusCode, String? code) {
    final String upper = (code ?? '').toUpperCase();
    if (upper == 'CHALLENGE_SEQUENCE_MISMATCH') {
      return ApiErrorKind.challengeMismatch;
    }
    switch (statusCode) {
      case 401:
        return ApiErrorKind.unauthenticated;
      case 403:
        return ApiErrorKind.forbidden;
      case 404:
        return ApiErrorKind.notFound;
      case 409:
        return ApiErrorKind.stateConflict;
      case 422:
        return ApiErrorKind.validation;
      case 429:
        return ApiErrorKind.rateLimited;
      case 503:
        return ApiErrorKind.inferenceUnavailable;
      case >= 500:
        return ApiErrorKind.serverError;
      default:
        return ApiErrorKind.serverError;
    }
  }
}
