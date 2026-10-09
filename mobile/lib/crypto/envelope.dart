// CONTRACT.md §3: the payload envelope, and the per-attempt key it is sealed with.
//
// The key is generated on the device before `POST /attempts`, delivered once in that
// call's `X-Attempt-Key` header, and held in memory only. Every capture body is then
// sealed with AES-256-GCM under that key, and the envelope carries only the key's
// fingerprint (`keyId` = lowercase hex SHA-256 of the raw key bytes, which is how the
// server verifies it holds the same key before decrypting).
//
// Nothing here touches the filesystem, and nothing here outlives the attempt: the
// controller calls [AttemptKey.destroy] when the attempt reaches a terminal state.

import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';

import 'package:cryptography/cryptography.dart';

import '../api/wire.dart' show encodeBytes64, formatUtcMillis;

/// AES-256-GCM per CONTRACT.md §3: 256-bit key, 12-byte nonce, 128-bit tag.
const int attemptKeyBytes = 32;
const int envelopeNonceBytes = 12;
const int envelopeTagBytes = 16;

/// A fresh per-attempt payload key.
class AttemptKey {
  AttemptKey._(this._bytes, this.keyId);

  /// Generates 32 random bytes and their fingerprint.
  ///
  /// Async because the SHA-256 goes through `package:cryptography`; the value is bound at
  /// construction so a key and its id can never disagree.
  static Future<AttemptKey> generate({Random? random}) async {
    final Random rng = random ?? Random.secure();
    final Uint8List bytes = Uint8List(attemptKeyBytes);
    for (int i = 0; i < attemptKeyBytes; i++) {
      bytes[i] = rng.nextInt(256);
    }
    final Hash hash = await Sha256().hash(bytes);
    return AttemptKey._(bytes, _hex(hash.bytes));
  }

  final Uint8List _bytes;

  /// Lowercase hex SHA-256 of the key — the `keyId` inside every envelope.
  final String keyId;

  /// The value of the `X-Attempt-Key` header: base64 of the raw 32 bytes.
  String get headerValue => base64Encode(_bytes);

  List<int> get aesBytes => List<int>.unmodifiable(_bytes);

  bool get isDestroyed => _bytes.every((int b) => b == 0);

  /// Overwrites the key material. Best effort — a garbage-collected copy may still exist
  /// somewhere in the Dart heap — but it shrinks the window the user is told about.
  void destroy() => _bytes.fillRange(0, _bytes.length, 0);
}

/// The JSON body of `POST /attempts/{id}/document` and `.../selfie`.
class Envelope {
  const Envelope({
    required this.keyId,
    required this.iv,
    required this.ciphertext,
    required this.tag,
    required this.sentAtUtc,
  });

  final String keyId;

  /// Base64 of the 12-byte GCM nonce.
  final String iv;

  /// Base64 of the ciphertext *without* the tag — the server appends them itself.
  final String ciphertext;

  /// Base64 of the 16-byte GCM tag.
  final String tag;

  /// UTC ISO-8601 with milliseconds; the server rejects anything further than its
  /// replay window (120 s) in either direction.
  final String sentAtUtc;

  Map<String, dynamic> toJson() => <String, dynamic>{
        'keyId': keyId,
        'iv': iv,
        'ciphertext': ciphertext,
        'tag': tag,
        'sentAtUtc': sentAtUtc,
      };
}

/// Seals [plaintextJson] under [key] with a fresh random nonce.
///
/// [now] is injected so a test can prove the timestamp is the caller's clock in UTC with
/// milliseconds, not whatever the platform's local zone happens to be.
Future<Envelope> sealEnvelope({
  required String plaintextJson,
  required AttemptKey key,
  DateTime? now,
}) async {
  final Random rng = Random.secure();
  final Uint8List nonce = Uint8List.fromList(
    List<int>.generate(envelopeNonceBytes, (_) => rng.nextInt(256)),
  );

  final SecretBox box = await AesGcm.with256bits().encrypt(
    utf8.encode(plaintextJson),
    secretKey: SecretKey(key.aesBytes),
    nonce: nonce,
  );
  final List<int> mac = box.mac.bytes;
  if (mac.length != envelopeTagBytes) {
    throw StateError('GCM produced a ${mac.length}-byte tag, expected $envelopeTagBytes');
  }

  return Envelope(
    keyId: key.keyId,
    iv: encodeBytes64(nonce),
    ciphertext: encodeBytes64(box.cipherText),
    tag: encodeBytes64(mac),
    sentAtUtc: formatUtcMillis(now ?? DateTime.now().toUtc()),
  );
}

String _hex(List<int> bytes) =>
    bytes.map((int b) => b.toRadixString(16).padLeft(2, '0')).join();
