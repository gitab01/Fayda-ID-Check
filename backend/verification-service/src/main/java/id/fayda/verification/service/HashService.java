package id.fayda.verification.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

import id.fayda.verification.infra.config.AppProperties;
import org.springframework.stereotype.Service;

/**
 * Salted, peppered SHA-256 for identifiers.
 *
 * <p>Digest input is {@code SHA-256(salt || value)} where {@code salt} is the per-row random
 * salt <em>extended with the env-supplied pepper</em> ({@code randomSaltBytes || pepperBytes}).
 * Only the random part is persisted in {@code hash_salt}; the pepper is never stored, so a
 * database dump alone cannot be rainbow-trailed.</p>
 *
 * <p>The raw national ID number must never be persisted or logged: this class takes it, digests
 * it and holds nothing — it has no fields of its own and logs nothing.</p>
 */
@Service
public class HashService {

    private static final int SALT_BYTES = 16;      // -> 32 hex chars, fits NVARCHAR(32)
    private static final HexFormat HEX = HexFormat.of();

    private final byte[] pepper;
    private final java.security.SecureRandom random = new java.security.SecureRandom();

    public HashService(AppProperties properties) {
        this.pepper = properties.getHashing().getPepper().getBytes(StandardCharsets.UTF_8);
        if (this.pepper.length == 0) {
            throw new IllegalStateException("verification.hashing.pepper must not be empty");
        }
    }

    /** A computed hash plus the per-row salt needed to re-verify it. */
    public record SaltedHash(String hash, String salt) {
    }

    public SaltedHash hash(String value) {
        String required = requireValue(value);
        byte[] saltBytes = new byte[SALT_BYTES];
        random.nextBytes(saltBytes);
        return new SaltedHash(digest(saltBytes, required), HEX.formatHex(saltBytes));
    }

    /** Re-computes the digest for a stored salt — used by {@link #matches}. */
    public String recompute(String value, String saltHex) {
        String required = requireValue(value);
        return digest(fromHex(saltHex), required);
    }

    /** Verification path: constant-time comparison against the stored digest. */
    public boolean matches(String value, String saltHex, String expectedHash) {
        if (expectedHash == null || saltHex == null) {
            return false;
        }
        byte[] candidate = recompute(value, saltHex).getBytes(StandardCharsets.UTF_8);
        byte[] stored = expectedHash.trim().toLowerCase(java.util.Locale.ROOT)
                .getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(candidate, stored);
    }

    /** Plain SHA-256 hex; used for envelope key ids and audit detail hashes, never for identity. */
    public String sha256Hex(String value) {
        return HEX.formatHex(sha256(value.getBytes(StandardCharsets.UTF_8)));
    }

    public static String sha256Hex(byte[] value) {
        return HEX.formatHex(sha256(value));
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static byte[] sha256(byte[] input) {
        return newSha256().digest(input);
    }

    private String digest(byte[] saltBytes, String value) {
        byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
        MessageDigest digest = newSha256();
        digest.update(saltBytes);
        digest.update(pepper);
        digest.update(valueBytes);
        Arrays.fill(valueBytes, (byte) 0);
        return HEX.formatHex(digest.digest());
    }

    private static byte[] fromHex(String hex) {
        try {
            return HEX.parseHex(hex.trim().toLowerCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("stored salt is not canonical hex", e);
        }
    }

    private static String requireValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("refusing to hash an empty value");
        }
        return value.trim();
    }
}
