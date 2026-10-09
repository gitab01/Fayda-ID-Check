// The one rule the client and the server had disagreed about: how the per-attempt key travels.
//
// CONTRACT.md §1 says the key leaves the phone once, in the `X-Attempt-Key` header of
// `POST /attempts`, and the body must not contain key material at all. These tests pin that down at
// the HTTP boundary rather than trusting the controller.

import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;

import 'package:fayda_id_check/api/api_exception.dart';
import 'package:fayda_id_check/api/api_models.dart';
import 'package:fayda_id_check/api/verification_api.dart';
import 'package:fayda_id_check/crypto/envelope.dart';

class _CapturingClient extends http.BaseClient {
  http.BaseRequest? request;
  String responseBody = '{}';
  int statusCode = 200;
  Map<String, String> responseHeaders = const <String, String>{};

  @override
  Future<http.StreamedResponse> send(http.BaseRequest request) async {
    this.request = request;
    return http.StreamedResponse(
      Stream<List<int>>.value(utf8.encode(responseBody)),
      statusCode,
      headers: Map<String, String>.from(responseHeaders),
    );
  }
}

const String _startResponse = '''
{
  "attemptId": 7,
  "status": "CREATED",
  "challenge": {"seed": "s-1", "actions": ["BLINK", "NOD", "SMILE"]},
  "uploadKey": {"keyId": "abc", "algorithm": "AES-256-GCM"},
  "attemptNo": 1,
  "attemptsRemaining": 2
}
''';

void main() {
  late _CapturingClient client;
  late HttpVerificationApi api;

  setUp(() {
    client = _CapturingClient();
    api = HttpVerificationApi(
      baseUrl: 'http://service.internal:8080',
      client: client,
      accessToken: () async => 'token-1',
    );
  });

  test('POST /attempts carries the key in the header and never in the body', () async {
    final AttemptKey key = await AttemptKey.generate();
    client
      ..statusCode = 201
      ..responseBody = _startResponse;

    final AttemptStart started = await api.startAttempt(
      const StartAttemptRequest(documentType: DocumentType.nationalId, deviceInfo: 'test'),
      attemptKey: key,
    );

    final http.Request request = client.request! as http.Request;
    expect(request.url.path, '/api/v1/attempts');
    expect(request.method, 'POST');
    expect(request.headers['X-Attempt-Key'], key.headerValue);
    expect(request.headers['Authorization'], 'Bearer token-1');
    expect(jsonDecode(request.body), <String, dynamic>{
      'documentType': 'NATIONAL_ID',
      'deviceInfo': 'test',
    });
    expect(request.body, isNot(contains(key.headerValue)));
    expect(request.body, isNot(contains(key.keyId)));
    expect(started.attemptId, 7);
    expect(started.challenge.actions, <String>['BLINK', 'NOD', 'SMILE']);
  });

  test('no token means no Authorization header, not an empty bearer', () async {
    final HttpVerificationApi signedOut = HttpVerificationApi(
      baseUrl: 'http://service.internal:8080',
      client: client,
      accessToken: () async => null,
    );
    client
      ..statusCode = 201
      ..responseBody = _startResponse;

    await signedOut.startAttempt(
      const StartAttemptRequest(documentType: DocumentType.passport, deviceInfo: 'test'),
      attemptKey: await AttemptKey.generate(),
    );

    expect((client.request! as http.Request).headers.containsKey('Authorization'), isFalse);
  });

  test('an envelope is posted verbatim to the document endpoint', () async {
    final AttemptKey key = await AttemptKey.generate();
    final Envelope envelope = await sealEnvelope(plaintextJson: '{"image":{}}', key: key);
    client
      ..statusCode = 202
      ..responseBody = '{"attemptId":7,"status":"CAPTURED","documentAccepted":true}';

    final DocumentUploadResult result = await api.uploadDocument(7, envelope);

    final http.Request request = client.request! as http.Request;
    expect(request.url.path, '/api/v1/attempts/7/document');
    expect(jsonDecode(request.body), envelope.toJson());
    expect(result.documentAccepted, isTrue);
  });

  test('a 429 keeps its Retry-After hint and maps to the rate-limited outcome', () async {
    client
      ..statusCode = 429
      ..responseHeaders = const <String, String>{'Retry-After': '30'}
      ..responseBody = '{"code":"RATE_LIMITED","message":"slow down","requestId":"r-9"}';

    final ApiFailure failure = await expectApiFailure(() => api.verify(7));

    expect(failure.kind, ApiErrorKind.rateLimited);
    expect(failure.retryAfter, const Duration(seconds: 30));
    expect(failure.requestId, 'r-9');
    expect(failure.outcome.kind, FlowOutcomeKind.tooManyRequests);
    expect(failure.outcome.canRetryAttempt, isTrue);
  });

  test('a 403 is not folded into the signed-out page', () async {
    client
      ..statusCode = 403
      ..responseBody = '{"code":"SCOPE_REQUIRED","message":"subject scope needed"}';

    final ApiFailure failure = await expectApiFailure(() => api.fetchAttempt(7));

    expect(failure.kind, ApiErrorKind.forbidden);
    expect(failure.outcome.kind, FlowOutcomeKind.wrongPermissions);
    expect(failure.outcome.canRetryAttempt, isFalse);
  });
}

/// Runs a call that must fail with an [ApiFailure], and returns it for assertions.
Future<ApiFailure> expectApiFailure(Future<Object?> Function() call) async {
  try {
    await call();
  } on ApiFailure catch (failure) {
    return failure;
  }
  throw TestFailure('expected the call to throw ApiFailure');
}
