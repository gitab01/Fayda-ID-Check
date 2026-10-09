// Wire helpers shared by the API layer.
//
// The contract pins two conventions: every request carries an `X-Request-Id`
// UUID, and every timestamp is UTC ISO-8601 with milliseconds. Both live here
// so nothing else in the app has to remember them.

import 'dart:convert';
import 'dart:math';

/// Generates the `X-Request-Id` value: a version-4 UUID from a secure RNG.
///
/// Hand-rolled rather than pulled from a package — the app only needs the one
/// string and `Random.secure` is already available.
String newRequestId({Random? random}) {
  final Random rng = random ?? Random.secure();
  final List<int> bytes = List<int>.generate(16, (_) => rng.nextInt(256));
  bytes[6] = (bytes[6] & 0x0f) | 0x40; // version 4
  bytes[8] = (bytes[8] & 0x3f) | 0x80; // variant 10
  final String hex = bytes
      .map((int b) => b.toRadixString(16).padLeft(2, '0'))
      .join();
  return '${hex.substring(0, 8)}-${hex.substring(8, 12)}-'
      '${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}';
}

/// Formats a timestamp the way the contract requires: UTC, ISO-8601,
/// milliseconds, `Z` suffix.
String formatUtcMillis(DateTime value) {
  final DateTime utc = value.toUtc();
  String two(int n) => n.toString().padLeft(2, '0');
  String three(int n) => n.toString().padLeft(3, '0');
  return '${utc.year}-${two(utc.month)}-${two(utc.day)}'
      'T${two(utc.hour)}:${two(utc.minute)}:${two(utc.second)}'
      '.${three(utc.millisecond)}Z';
}

/// Parses a contract timestamp, tolerating a missing or malformed value.
DateTime? parseUtcDate(String? value) {
  if (value == null || value.isEmpty) {
    return null;
  }
  return DateTime.tryParse(value)?.toUtc();
}

/// Base64 of arbitrary bytes, as used by the `bytes64` payload fields.
String encodeBytes64(List<int> bytes) => base64Encode(bytes);

List<int> decodeBytes64(String value) => base64Decode(value);

/// Milliseconds since epoch, used by the frame `capturedAtMs` field.
int epochMillis(DateTime value) => value.toUtc().millisecondsSinceEpoch;

Map<String, dynamic> asJsonObject(Object? value) {
  if (value is Map<String, dynamic>) {
    return value;
  }
  if (value is Map) {
    return value.map((Object? k, Object? v) => MapEntry<String, dynamic>('$k', v));
  }
  throw const ApiExceptionShape('Expected a JSON object in the response body');
}

List<dynamic> asJsonList(Object? value) {
  if (value is List<dynamic>) {
    return value;
  }
  if (value is List) {
    return value.cast<dynamic>();
  }
  throw const ApiExceptionShape('Expected a JSON array in the response body');
}

/// A response the client could not make sense of: same shape problem, different
/// layer. Surfaced as an unexpected server condition, never as a silent default.
class ApiExceptionShape implements Exception {
  const ApiExceptionShape(this.message);

  final String message;

  @override
  String toString() => 'ApiExceptionShape: $message';
}

String? optionalString(Map<String, dynamic> json, String key) {
  final Object? value = json[key];
  return value is String ? value : null;
}

double? optionalDouble(Map<String, dynamic> json, String key) {
  final Object? value = json[key];
  if (value is num) {
    return value.toDouble();
  }
  if (value is String) {
    return double.tryParse(value);
  }
  return null;
}

int? optionalInt(Map<String, dynamic> json, String key) {
  final Object? value = json[key];
  if (value is num) {
    return value.toInt();
  }
  if (value is String) {
    return int.tryParse(value);
  }
  return null;
}

bool optionalBool(Map<String, dynamic> json, String key, {bool fallback = false}) {
  final Object? value = json[key];
  if (value is bool) {
    return value;
  }
  if (value is String) {
    return bool.tryParse(value) ?? fallback;
  }
  if (value is num) {
    return value != 0;
  }
  return fallback;
}
