// The exact JSON keys the two capture endpoints bind, pinned on the client side.
//
// The service deserializes these bodies into records
// (`id.fayda.verification.api.dto.DocumentPayload` and `SelfiePayload`), and Spring Boot's mapper
// ignores unknown keys — which is convenient right up until a field is renamed on one side only
// and silently becomes null in the decrypted payload. `AttemptFlowIntegrationTest` builds its
// fixture from this same key list, so a rename here fails a Dart test and a rename there fails a
// Java one; the two files are the contract's tie-breaker.

import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';

import 'package:fayda_id_check/api/api_models.dart';
import 'package:fayda_id_check/capture/quality.dart';

Map<String, dynamic> _decoded(String json) =>
    jsonDecode(json) as Map<String, dynamic>;

SelfiePayload _selfie({List<String> actions = const <String>['TURN_LEFT', 'BLINK', 'SMILE']}) =>
    SelfiePayload(
      frames: <SelfieFrame>[
        for (final String action in actions)
          SelfieFrame(
            bytes: Uint8List.fromList(<int>[1, 2, 3]),
            capturedAtMs: 1746100000000,
            action: action,
          ),
      ],
      executedActions: actions,
      challengeSeed: 'seed-from-the-service',
      quality: const SelfieQuality(blurScore: 180.0, faceBox: <int>[412, 180, 240, 300]),
    );

DocumentPayload _document() => DocumentPayload(
      image: CaptureImage(
        bytes: Uint8List.fromList(<int>[9, 8, 7]),
        width: 1280,
        height: 720,
        mimeType: 'image/jpeg',
      ),
      ocr: const DocumentOcr(
        idNumber: '1234567890126',
        fullName: 'Abel Bekele',
        issuingRegion: 'Addis Ababa',
        expiryDate: '2030-04-01',
      ),
      quality: const DocumentQualityFacts(
        blurScore: 212.5,
        glareRatio: 0.01,
        cornersFound: true,
      ),
    );

void main() {
  group('document body', () {
    test('has exactly the keys DocumentPayload binds', () {
      final Map<String, dynamic> body = _decoded(_document().encodeJson());

      expect(body.keys, unorderedEquals(<String>['image', 'ocr', 'quality']));
      expect((body['image'] as Map<String, dynamic>).keys,
          unorderedEquals(<String>['bytes64', 'width', 'height', 'mimeType']));
      expect((body['ocr'] as Map<String, dynamic>).keys,
          unorderedEquals(<String>['idNumber', 'fullName', 'issuingRegion', 'expiryDate']));
      expect((body['quality'] as Map<String, dynamic>).keys,
          unorderedEquals(<String>['blurScore', 'glareRatio', 'cornersFound']));
    });

    test('keeps a partially-read OCR block to the fields it actually read', () {
      const DocumentOcr partial = DocumentOcr(
        idNumber: '1234567890126',
        expiryDate: '2030-04-01',
      );

      expect(partial.toJson().keys, unorderedEquals(<String>['idNumber', 'expiryDate']));
    });

    test('sends an empty ocr object rather than null when nothing was read', () {
      final DocumentPayload noOcr = DocumentPayload(
        image: CaptureImage(
          bytes: Uint8List.fromList(<int>[1]),
          width: 640,
          height: 480,
          mimeType: 'image/jpeg',
        ),
        quality: const DocumentQualityFacts(blurScore: 90, glareRatio: 0, cornersFound: true),
      );

      expect(_decoded(noOcr.encodeJson())['ocr'], isEmpty);
    });

    test('base64-encodes the image bytes rather than leaking raw numbers into JSON', () {
      final Map<String, dynamic> image =
          _decoded(_document().encodeJson())['image'] as Map<String, dynamic>;

      expect(image['bytes64'], base64Encode(<int>[9, 8, 7]));
    });
  });

  group('selfie body', () {
    test('has exactly the keys SelfiePayload binds, plus the echoed seed', () {
      final Map<String, dynamic> body = _decoded(_selfie().encodeJson());

      expect(
        body.keys,
        unorderedEquals(<String>['frames', 'executedActions', 'challengeSeed', 'quality']),
        reason: 'challengeSeed is the one key the DTO does not bind, so it must stay documented',
      );
      expect((body['quality'] as Map<String, dynamic>).keys,
          unorderedEquals(<String>['blurScore', 'faceBox']));
    });

    test('every frame carries the action it was recorded for', () {
      final SelfiePayload payload = _selfie();
      final List<dynamic> frames = _decoded(payload.encodeJson())['frames'] as List<dynamic>;

      expect(frames, hasLength(3));
      for (int i = 0; i < frames.length; i++) {
        final Map<String, dynamic> frame = frames[i] as Map<String, dynamic>;
        expect(frame.keys, unorderedEquals(<String>['bytes64', 'action', 'capturedAtMs']));
        expect(frame['action'], payload.executedActions[i]);
      }
    });

    test('drops the action key rather than sending null when a frame has no prompt', () {
      final SelfiePayload payload = SelfiePayload(
        frames: <SelfieFrame>[
          SelfieFrame(bytes: Uint8List.fromList(<int>[1]), capturedAtMs: 1),
        ],
        executedActions: const <String>['BLINK'],
        challengeSeed: 's',
        quality: const SelfieQuality(blurScore: 100),
      );
      final Map<String, dynamic> frame =
          (_decoded(payload.encodeJson())['frames'] as List<dynamic>).single
              as Map<String, dynamic>;

      expect(frame.keys, unorderedEquals(<String>['bytes64', 'capturedAtMs']));
    });

    test('drops faceBox when the app never localised a face', () {
      final SelfiePayload payload = SelfiePayload(
        frames: _selfie().frames,
        executedActions: const <String>['BLINK'],
        challengeSeed: 's',
        quality: const SelfieQuality(blurScore: 100),
      );

      expect((_decoded(payload.encodeJson())['quality'] as Map<String, dynamic>).keys,
          unorderedEquals(<String>['blurScore']));
    });

    test('refuses to encode fewer than the three frames the contract requires', () {
      expect(_selfie(actions: const <String>['BLINK']).hasMinimumFrames, isFalse);
      expect(_selfie().hasMinimumFrames, isTrue);
    });
  });

  group('quality facts uploaded from the gate', () {
    test('match the keys the document DTO binds', () {
      final DocumentQualityFacts facts = QualityGate()
          .evaluate(
            GrayFrame(
              width: 8,
              height: 8,
              luma: Uint8List(64)..fillRange(0, 64, 120),
            ),
          )
          .toUploadFacts();

      expect(facts.toJson().keys,
          unorderedEquals(<String>['blurScore', 'glareRatio', 'cornersFound']));
    });
  });
}
