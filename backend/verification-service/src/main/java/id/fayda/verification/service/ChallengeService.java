package id.fayda.verification.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import id.fayda.verification.domain.ChallengeAction;
import id.fayda.verification.infra.config.AppProperties;
import id.fayda.verification.service.errors.ApiException;
import id.fayda.verification.service.errors.ErrorCode;
import org.springframework.stereotype.Service;

/**
 * Issues the liveness challenge.
 *
 * <p>The ordered action list is a pure HMAC-SHA256 function of the server-issued seed, so the
 * service can <em>re-derive</em> the sequence on submit and compare it against what the client
 * claims it executed. No per-attempt challenge state is needed to verify a sequence — the seed
 * plus the server secret is sufficient (the DB copy in {@code challenge_actions} is required by
 * the baseline schema but is never trusted for verification).</p>
 *
 * <p>Because derivation is keyed by a server secret, a client that captured a seed cannot
 * compute the expected order in advance.</p>
 */
@Service
public class ChallengeService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final byte[] secret;
    private final int sequenceLength;

    public ChallengeService(AppProperties properties) {
        this.secret = properties.getChallenge().getHmacSecret().getBytes(StandardCharsets.UTF_8);
        this.sequenceLength = properties.getChallenge().getLength();
        if (sequenceLength < 1 || sequenceLength > ChallengeAction.poolSize()) {
            throw new IllegalStateException("verification.challenge.length must be between 1 and "
                    + ChallengeAction.poolSize() + " (distinct actions available)");
        }
    }

    /** 32 hex characters — fits {@code challenge_seed NVARCHAR(64)}. */
    public String newSeed() {
        byte[] raw = new byte[16];
        SecureHolder.RANDOM.nextBytes(raw);
        return toHex(raw);
    }

    /** Deterministic, duplicate-free ordered sequence for a seed. */
    public List<ChallengeAction> derive(String seed) {
        if (seed == null || seed.isBlank()) {
            throw ApiException.of(ErrorCode.VALIDATION_FAILED, "challenge seed is required");
        }
        List<ChallengeAction> chosen = new ArrayList<>(sequenceLength);
        // Bounded so a pathological collision can never loop forever.
        for (int counter = 0; chosen.size() < sequenceLength && counter < 4096; counter++) {
            byte[] digest = hmac(seed + "|" + counter);
            int bucket = ((digest[0] & 0xFF) << 16) | ((digest[1] & 0xFF) << 8) | (digest[2] & 0xFF);
            ChallengeAction candidate = ChallengeAction.at(bucket);
            if (!chosen.contains(candidate)) {
                chosen.add(candidate);
            }
        }
        if (chosen.size() < sequenceLength) {
            throw new IllegalStateException("could not derive a distinct action sequence for seed");
        }
        return List.copyOf(chosen);
    }

    public List<String> derivedNames(String seed) {
        return derive(seed).stream().map(Enum::name).toList();
    }

    /** Comma-separated, order preserving — {@code challenge_actions NVARCHAR(256)}. */
    public String toStoredForm(List<ChallengeAction> actions) {
        return String.join(",", actions.stream().map(Enum::name).toList());
    }

    public List<ChallengeAction> fromStoredForm(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        return Arrays.stream(stored.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(ChallengeAction::valueOf)
                .toList();
    }

    /** True when the submitted order is exactly the derived order. */
    public boolean sequenceMatches(String seed, List<String> submittedActions) {
        if (submittedActions == null) {
            return false;
        }
        List<String> expected = derivedNames(seed);
        if (expected.size() != submittedActions.size()) {
            return false;
        }
        for (int i = 0; i < expected.size(); i++) {
            if (!expected.get(i).equalsIgnoreCase(trimmed(submittedActions.get(i)))) {
                return false;
            }
        }
        return true;
    }

    /** Throws {@code CHALLENGE_SEQUENCE_MISMATCH} (HTTP 422) when the sequence does not match. */
    public void requireSequenceMatches(String seed, List<String> submittedActions) {
        if (!sequenceMatches(seed, submittedActions)) {
            throw ApiException.of(ErrorCode.CHALLENGE_SEQUENCE_MISMATCH,
                    "executed action sequence does not match the challenge issued for this attempt");
        }
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    private byte[] hmac(String message) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new IllegalStateException("challenge derivation unavailable", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** Shared, lazily seeded SecureRandom (it is expensive to construct). */
    private static final class SecureHolder {
        private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();
    }

    static {
        // Fail fast if the platform has no SHA-256; it is required by hashing and by the envelope.
        try {
            MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
