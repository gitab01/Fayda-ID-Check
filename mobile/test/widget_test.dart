// The consent gate is the one screen that must be right before anything else can be: no capture
// starts until the user says so, and the button cannot be pressed while the app is mid-request.

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:fayda_id_check/api/api_models.dart';
import 'package:fayda_id_check/api/verification_api.dart';
import 'package:fayda_id_check/crypto/envelope.dart';
import 'package:fayda_id_check/main.dart';

class _NullApi implements VerificationApi {
  @override
  Future<AttemptStart> startAttempt(StartAttemptRequest request,
          {required AttemptKey attemptKey}) =>
      throw UnimplementedError('the consent screen must not call the service');

  @override
  Future<DocumentUploadResult> uploadDocument(int attemptId, Envelope envelope) =>
      throw UnimplementedError();

  @override
  Future<SelfieUploadResult> uploadSelfie(int attemptId, Envelope envelope) =>
      throw UnimplementedError();

  @override
  Future<DecisionRecord> verify(int attemptId) => throw UnimplementedError();

  @override
  Future<AttemptSnapshot> fetchAttempt(int attemptId) => throw UnimplementedError();

  @override
  Future<RetryResult> retryAttempt(int attemptId) => throw UnimplementedError();

  @override
  void clear() {}
}

void main() {
  testWidgets('the flow starts on consent and does not call the service until it is given',
      (WidgetTester tester) async {
    await tester.pumpWidget(FaydaIdCheckApp(api: _NullApi()));

    expect(find.text('Verify your ID document'), findsOneWidget);
    final FilledButton button = tester.widget<FilledButton>(find.byType(FilledButton));
    expect(button.onPressed, isNull, reason: 'start must stay disabled before consent');

    await tester.ensureVisible(find.byType(CheckboxListTile));
    await tester.pumpAndSettle();
    await tester.tap(find.byType(CheckboxListTile));
    await tester.pump();

    expect(tester.widget<FilledButton>(find.byType(FilledButton)).onPressed, isNotNull);
    expect(find.text('National ID'), findsOneWidget);
  });
}
