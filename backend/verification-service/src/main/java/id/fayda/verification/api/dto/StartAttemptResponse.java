package id.fayda.verification.api.dto;

import java.util.List;

/**
 * Response to {@code POST /attempts} (HTTP 201), exactly as the contract shapes it.
 *
 * @param keyId hex SHA-256 of the client-generated per-attempt AES key; the key itself is
 *              never sent to the server and never stored by it
 */
public record StartAttemptResponse(
        long attemptId,
        String status,
        Challenge challenge,
        UploadKey uploadKey,
        String stageDeadlineUtc,
        int attemptNo,
        int attemptsRemaining) {

    public record Challenge(String seed, List<String> actions) {
    }

    public record UploadKey(String keyId, String algorithm, String expiresAtUtc) {
    }
}
