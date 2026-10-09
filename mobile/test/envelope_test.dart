// The §3 envelope, checked against the contract's byte-level rules.
//
// These are the numbers the Java side refuses anything else on: a 12-byte nonce, a separate
// 16-byte tag (the ciphertext must *not* have it appended), and a `keyId` that is the lowercase
// hex SHA-256 of the raw key. If any of those drift, the service answers 400 and the user sees a
// capture rejected for a reason they cannot act on.

import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';

import 'package:cryptography/cryptography.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:fayda_id_check/crypto/envelope.dart';

String _hex(List<int> bytes) =>
    bytes.map((int b) => b.toRadixString(16).padLeft(2, '0')).join();

void main() {
  group('AttemptKey', () {
    test('header value is base64 of 32 raw bytes and keyId is their SHA-256', () async {
      final AttemptKey key = await AttemptKey.generate(random: Random(20260501));
      final Uint8List raw = base64Decode(key.headerValue);

      expect(raw, hasLength(attemptKeyBytes));
      final Hash digest = await Sha256().hash(raw);
      expect(key.keyId, _hex(digest.bytes));
      expect(key.keyId, key.keyId.toLowerCase());
      expect(key.keyId, hasLength(64));
    });

    test('a seeded generator produces the same key twice', () async {
      final AttemptKey a = await AttemptKey.generate(random: Random(3));
      final AttemptKey b = await AttemptKey.generate(random: Random(3));
      expect(a.keyId, b.keyId);
      expect(a.headerValue, b.headerValue);
    });

    test('destroy overwrites the material it was built from', () async {
      final AttemptKey key = await AttemptKey.generate();
      expect(key.isDestroyed, isFalse);
      key.destroy();
      expect(key.isDestroyed, isTrue);
      expect(base64Decode(key.headerValue), everyElement(0));
    });
  });

  group('sealEnvelope', () {
    test('matches the contract shape and round-trips through AES-GCM', () async {
      final AttemptKey key = await AttemptKey.generate();
      const String plaintext = '{"image":{"bytes64":"AAA"}}';

      final Envelope envelope = await sealEnvelope(
        plaintextJson: plaintext,
        key: key,
        now: DateTime.utc(2026, 5, 1, 12, 30, 45, 123),
      );

      expect(envelope.keyId, key.keyId);
      expect(base64Decode(envelope.iv), hasLength(envelopeNonceBytes));
      expect(base64Decode(envelope.tag), hasLength(envelopeTagBytes));
      expect(envelope.sentAtUtc, '2026-05-01T12:30:45.123Z');
      expect(envelope.toJson().keys,
          containsAll(<String>['keyId', 'iv', 'ciphertext', 'tag', 'sentAtUtc']));

      final List<int> decrypted = await AesGcm.with256bits().decrypt(
        SecretBox(
          base64Decode(envelope.ciphertext),
          nonce: base64Decode(envelope.iv),
          mac: Mac(base64Decode(envelope.tag)),
        ),
        secretKey: SecretKey(key.aesBytes),
      );
      expect(utf8.decode(decrypted), plaintext);
    });

    test('ciphertext carries no appended tag', () async {
      final AttemptKey key = await AttemptKey.generate();
      final Envelope envelope = await sealEnvelope(plaintextJson: '{"n":1}', key: key);
      final List<int> cipher = base64Decode(envelope.ciphertext);
      // 7 bytes of plaintext, and GCM output is the same length as its input. If the tag were
      // appended the way some libraries do it, this would be 23.
      expect(cipher, hasLength(utf8.encode('{"n":1}').length));
    });

    test('a forged tag does not decrypt', () async {
      final AttemptKey key = await AttemptKey.generate();
      final Envelope envelope = await sealEnvelope(plaintextJson: '{"n":1}', key: key);
      final List<int> tag = base64Decode(envelope.tag);
      tag[0] = tag[0] ^ 0x01;

      await expectLater(
        AesGcm.with256bits().decrypt(
          SecretBox(
            base64Decode(envelope.ciphertext),
            nonce: base64Decode(envelope.iv),
            mac: Mac(tag),
          ),
          secretKey: SecretKey(key.aesBytes),
        ),
        throwsA(isA<SecretBoxAuthenticationError>()),
      );
    });

    test('two envelopes of the same body use different nonces', () async {
      final AttemptKey key = await AttemptKey.generate();
      final Envelope a = await sealEnvelope(plaintextJson: '{"n":1}', key: key);
      final Envelope b = await sealEnvelope(plaintextJson: '{"n":1}', key: key);
      expect(a.iv, isNot(b.iv));
      expect(a.ciphertext, isNot(b.ciphertext));
    });
  });
}
