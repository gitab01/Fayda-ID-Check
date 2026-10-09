// The §1 API surface, in one place.
//
// `VerificationApi` is the interface the controller depends on — that is what
// makes the flow testable with a fake that records calls. `HttpVerificationApi`
// is the real one: it adds `X-Request-Id` to every request, the bearer token, the
// per-attempt key to the one call that starts an attempt, and turns any non-2xx
// response into an [ApiFailure].

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:http/http.dart' as http;

import '../crypto/envelope.dart';
import 'api_exception.dart';
import 'api_models.dart';
import 'wire.dart';

/// Every endpoint the mobile client is allowed to call.
abstract class VerificationApi {
  /// `POST /attempts`.
  ///
  /// [attemptKey] is the per-attempt AES key generated on the device for this attempt. It
  /// leaves the phone exactly once, in the `X-Attempt-Key` header of this call; only its
  /// fingerprint (`keyId`) ever appears inside an envelope (CONTRACT.md §1, §3).
  Future<AttemptStart> startAttempt(
    StartAttemptRequest request, {
    required AttemptKey attemptKey,
  });

  Future<DocumentUploadResult> uploadDocument(int attemptId, Envelope envelope);

  Future<SelfieUploadResult> uploadSelfie(int attemptId, Envelope envelope);

  Future<DecisionRecord> verify(int attemptId);

  Future<AttemptSnapshot> fetchAttempt(int attemptId);

  Future<RetryResult> retryAttempt(int attemptId);

  /// Wipes anything cached that could outlive the attempt. There is nothing to
  /// wipe in the HTTP client today; the method exists so callers can always
  /// call it and the invariant stays auditable.
  void clear();
}

/// Body of `POST /attempts`: exactly the two fields the service's request record declares.
///
/// The per-attempt key is not part of the body — it is delivered once in the
/// `X-Attempt-Key` header (see [VerificationApi.startAttempt]), so a body replay can never
/// carry key material.
class StartAttemptRequest {
  const StartAttemptRequest({
    required this.documentType,
    required this.deviceInfo,
  });

  final DocumentType documentType;
  final String deviceInfo;

  Map<String, dynamic> toJson() => <String, dynamic>{
        'documentType': documentType.wireValue,
        'deviceInfo': deviceInfo,
      };
}

/// Real client over HTTP.
class HttpVerificationApi implements VerificationApi {
  HttpVerificationApi({
    required String baseUrl,
    required http.Client client,
    required FutureOr<String?> Function() accessToken,
    this.timeout = const Duration(seconds: 20),
  })  : _baseUri = Uri.parse(baseUrl),
        _client = client,
        _accessToken = accessToken;

  final Uri _baseUri;
  final http.Client _client;
  final FutureOr<String?> Function() _accessToken;
  final Duration timeout;

  @override
  Future<AttemptStart> startAttempt(
    StartAttemptRequest request, {
    required AttemptKey attemptKey,
  }) async {
    final Map<String, dynamic> json = await _send(
      method: 'POST',
      path: '/attempts',
      body: jsonEncode(request.toJson()),
      expectedStatuses: const <int>{200, 201},
      headers: <String, String>{'X-Attempt-Key': attemptKey.headerValue},
    );
    return AttemptStart.fromJson(json);
  }

  @override
  Future<DocumentUploadResult> uploadDocument(int attemptId, Envelope envelope) async {
    final Map<String, dynamic> json = await _send(
      method: 'POST',
      path: '/attempts/$attemptId/document',
      body: jsonEncode(envelope.toJson()),
      expectedStatuses: const <int>{200, 202},
    );
    return DocumentUploadResult.fromJson(json);
  }

  @override
  Future<SelfieUploadResult> uploadSelfie(int attemptId, Envelope envelope) async {
    final Map<String, dynamic> json = await _send(
      method: 'POST',
      path: '/attempts/$attemptId/selfie',
      body: jsonEncode(envelope.toJson()),
      expectedStatuses: const <int>{200, 202},
    );
    return SelfieUploadResult.fromJson(json);
  }

  @override
  Future<DecisionRecord> verify(int attemptId) async {
    final Map<String, dynamic> json = await _send(
      method: 'POST',
      path: '/attempts/$attemptId/verify',
      body: jsonEncode(<String, dynamic>{}),
      expectedStatuses: const <int>{200},
    );
    return DecisionRecord.fromJson(json);
  }

  @override
  Future<AttemptSnapshot> fetchAttempt(int attemptId) async {
    final Map<String, dynamic> json = await _send(
      method: 'GET',
      path: '/attempts/$attemptId',
      body: null,
      expectedStatuses: const <int>{200},
    );
    return AttemptSnapshot.fromJson(json);
  }

  @override
  Future<RetryResult> retryAttempt(int attemptId) async {
    final Map<String, dynamic> json = await _send(
      method: 'POST',
      path: '/attempts/$attemptId/retry',
      body: jsonEncode(<String, dynamic>{}),
      expectedStatuses: const <int>{200, 201},
    );
    return RetryResult.fromJson(json);
  }

  @override
  void clear() {
    // The HTTP client holds no capture data: envelopes are built by the caller
    // and discarded after the response. Kept for the invariant.
  }

  Future<Map<String, dynamic>> _send({
    required String method,
    required String path,
    required String? body,
    required Set<int> expectedStatuses,
    Map<String, String> headers = const <String, String>{},
  }) async {
    final Uri uri = _baseUri.replace(
      path: '${(_baseUri.path.endsWith('/') ? _baseUri.path.substring(0, _baseUri.path.length - 1) : _baseUri.path)}/api/v1$path',
    );
    final String requestId = newRequestId();
    final Map<String, String> allHeaders = <String, String>{
      'content-type': 'application/json; charset=utf-8',
      'accept': 'application/json',
      'X-Request-Id': requestId,
      ...headers,
    };
    final String? token = await _accessToken();
    if (token != null && token.isNotEmpty) {
      allHeaders['Authorization'] = 'Bearer $token';
    }

    final http.Response response;
    try {
      final http.Request request = http.Request(method, uri)
        ..headers.addAll(allHeaders);
      if (body != null) {
        request.body = body;
      }
      final http.StreamedResponse streamed =
          await _client.send(request).timeout(timeout);
      response = await http.Response.fromStream(streamed);
    } on TimeoutException {
      throw ApiFailure.transport(message: 'The request timed out.').withRequestId(requestId);
    } on SocketException catch (error) {
      throw ApiFailure.transport(message: error.message).withRequestId(requestId);
    } on http.ClientException catch (error) {
      throw ApiFailure.transport(message: error.message).withRequestId(requestId);
    }

    Map<String, dynamic> json;
    try {
      final Object? decoded = response.body.isEmpty ? <String, dynamic>{} : jsonDecode(response.body);
      json = asJsonObject(decoded);
    } on FormatException catch (error) {
      if (expectedStatuses.contains(response.statusCode)) {
        throw ApiFailure.malformed(message: error.message).withRequestId(requestId);
      }
      json = <String, dynamic>{
        'code': 'HTTP_${response.statusCode}',
        'message': response.body,
      };
    }

    if (expectedStatuses.contains(response.statusCode)) {
      return json;
    }

    final Duration? retryAfter = _parseRetryAfter(response.headers['Retry-After']);
    throw ApiFailure.fromResponse(
      statusCode: response.statusCode,
      body: json,
      retryAfter: retryAfter,
    ).withRequestId(response.headers['x-request-id'] ?? requestId);
  }

  static Duration? _parseRetryAfter(String? value) {
    final int? seconds = int.tryParse(value ?? '');
    return seconds == null ? null : Duration(seconds: seconds);
  }
}

extension on ApiFailure {
  /// Keeps the transport-level request id when the error body did not carry
  /// one, so support can correlate the failure with the service logs.
  ApiFailure withRequestId(String id) => requestId == null || requestId!.isEmpty
      ? ApiFailure(
          kind: kind,
          code: code,
          message: message,
          httpStatus: httpStatus,
          requestId: id,
          retryable: retryable,
          retryAfter: retryAfter,
        )
      : this;
}
