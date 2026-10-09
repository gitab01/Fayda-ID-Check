package id.fayda.verification.service.crypto;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.fayda.verification.infra.config.AppProperties;
import id.fayda.verification.service.HashService;
import id.fayda.verification.service.UtcTimes;
import id.fayda.verification.service.errors.ApiException;
import id.fayda.verification.service.errors.ErrorCode;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

/**
 * AES-256-GCM envelope handling (CONTRACT §3).
 *
 * <p>Replay window: a payload whose {@code sentAtUtc} is more than
 * {@code verification.crypto.replay-window-seconds} (120 s by default) away from now — in either
 * direction — is rejected before any decryption is attempted.</p>
 *
 * <p>Plaintext lives in a byte array on the heap for the duration of the request only. Nothing
 * here touches the filesystem; {@link #decrypt} returns an array the caller is expected to
 * {@link Arrays#fill(byte[], byte) zero} once consumed. Images inside it are decoded straight to
 * memory by {@link ImageDecoder} and are never persisted.</p>
 */
@Service
public class CryptoService {

    private static final int IV_BYTES = 12;
    private static final int TAG_BYTES = 16;
    private static final int TAG_BITS = TAG_BYTES * 8;
    private static final int AES_KEY_BYTES = 32;   // AES-256
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    public CryptoService(AppProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** keyId is hex SHA-256 of the raw 256-bit per-attempt key. */
    public static String keyIdOf(byte[] key) {
        return HashService.sha256Hex(key);
    }

    public byte[] decrypt(Envelope envelope, byte[] key) {
        if (envelope == null) {
            throw ApiException.of(ErrorCode.PAYLOAD_MALFORMED, "envelope body is required");
        }
        if (key == null || key.length != AES_KEY_BYTES) {
            throw ApiException.of(ErrorCode.PAYLOAD_MALFORMED,
                    "the per-attempt payload key must be 256 bits");
        }
        rejectStale(envelope.sentAtUtc());

        byte[] iv = decodeFixed(envelope.iv(), IV_BYTES, "iv");
        byte[] tag = decodeFixed(envelope.tag(), TAG_BYTES, "tag");
        byte[] ciphertext = decodeBase64(envelope.ciphertext(), "ciphertext");
        if (ciphertext.length > properties.getCrypto().getMaxCiphertextBytes()) {
            throw ApiException.of(ErrorCode.PAYLOAD_TOO_LARGE,
                    "ciphertext exceeds the %d byte limit",
                    properties.getCrypto().getMaxCiphertextBytes());
        }
        requireKeyIdMatches(envelope.keyId(), key);

        // Java's GCM engine expects ciphertext||tag as a single input.
        byte[] input = new byte[ciphertext.length + tag.length];
        System.arraycopy(ciphertext, 0, input, 0, ciphertext.length);
        System.arraycopy(tag, 0, input, ciphertext.length, tag.length);

        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            return cipher.doFinal(input);
        } catch (javax.crypto.AEADBadTagException e) {
            // Tampered ciphertext, wrong key or a forged tag: the body is not usable.
            throw ApiException.of(ErrorCode.PAYLOAD_UNPROCESSABLE,
                    "envelope failed GCM authentication; the payload is tampered or mismatched");
        } catch (java.security.GeneralSecurityException e) {
            throw ApiException.of(ErrorCode.PAYLOAD_MALFORMED, "envelope could not be processed");
        } finally {
            Arrays.fill(input, (byte) 0);
        }
    }

    /** Decrypts and binds the JSON body to {@code type}, then zeroes the plaintext buffer. */
    public <T> T decryptJson(Envelope envelope, byte[] key, Class<T> type) {
        byte[] plaintext = decrypt(envelope, key);
        try {
            String json = new String(plaintext, StandardCharsets.UTF_8);
            T value;
            try {
                value = objectMapper.readValue(json, type);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw ApiException.of(ErrorCode.PAYLOAD_MALFORMED,
                        "the decrypted payload is not valid JSON for this endpoint");
            }
            if (value == null) {
                throw ApiException.of(ErrorCode.PAYLOAD_MALFORMED, "the decrypted payload is empty");
            }
            return value;
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    private void rejectStale(String sentAtUtc) {
        Instant sentAt = parseInstant(sentAtUtc);
        Duration window = properties.getCrypto().replayWindow();
        Duration age = Duration.between(sentAt, Instant.now());
        // Reject both a stale replay and a far-future timestamp (clock skew or a forged value).
        if (age.abs().compareTo(window) > 0) {
            throw ApiException.of(ErrorCode.PAYLOAD_STALE,
                    "payload timestamp is outside the %d second replay window",
                    (int) window.getSeconds());
        }
    }

    private static Instant parseInstant(String sentAtUtc) {
        if (sentAtUtc == null || sentAtUtc.isBlank()) {
            throw ApiException.of(ErrorCode.PAYLOAD_MALFORMED, "sentAtUtc is required");
        }
        java.time.LocalDateTime asUtc = UtcTimes.parse(sentAtUtc);
        if (asUtc == null) {
            throw ApiException.of(ErrorCode.PAYLOAD_MALFORMED, "sentAtUtc is not an ISO-8601 UTC time");
        }
        return asUtc.toInstant(java.time.ZoneOffset.UTC);
    }

    private static void requireKeyIdMatches(String keyId, byte[] key) {
        String expected = keyIdOf(key);
        String presented = keyId == null ? "" : keyId.trim().toLowerCase(java.util.Locale.ROOT);
        if (!expected.equals(presented)) {
            throw ApiException.of(ErrorCode.KEY_ID_MISMATCH,
                    "envelope keyId does not belong to the key registered for this attempt");
        }
    }

    private static byte[] decodeFixed(String value, int expectedBytes, String field) {
        byte[] decoded = decodeBase64(value, field);
        if (decoded.length != expectedBytes) {
            throw ApiException.of(ErrorCode.PAYLOAD_MALFORMED,
                    "%s must be %d bytes (got %d)", field, expectedBytes, decoded.length);
        }
        return decoded;
    }

    private static byte[] decodeBase64(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.of(ErrorCode.PAYLOAD_MALFORMED, "%s is required", field);
        }
        try {
            return Base64.getDecoder().decode(value.trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.of(ErrorCode.PAYLOAD_MALFORMED, "%s is not valid base64", field);
        }
    }
}
