package id.fayda.verification.service.crypto;

/**
 * The wire envelope of CONTRACT §3 (app -&gt; verification service). Transport is TLS; this is
 * payload-level encryption with the per-attempt AES-256-GCM key.
 *
 * @param keyId       hex SHA-256 of the per-attempt key — the key itself is never in this body
 * @param iv          base64, 12 bytes
 * @param ciphertext  base64 AES-256-GCM of the JSON body
 * @param tag         base64, 16 bytes GCM tag
 * @param sentAtUtc   ISO-8601; older than the replay window is rejected
 */
public record Envelope(String keyId, String iv, String ciphertext, String tag, String sentAtUtc) {
}
