package id.fayda.verification.infra.inference;

import java.math.BigDecimal;
import java.util.List;

/**
 * The only outbound call the verification service makes: inference scores for one attempt.
 *
 * <p>Requests carry a request-scoped id and image material — never a user id, phone number or
 * national id (CONTRACT §Conventions). Implementations must not substitute a default score: a
 * failure is an {@link InferenceUnavailableException}, which the lifecycle turns into
 * {@code FAILED_RETRYABLE} plus HTTP 503.</p>
 */
public interface InferenceClient {

    VerifyScores verify(VerifyRequest request);

    /**
     * @param requestId      correlation id, the only identity-shaped value the peer ever sees
     * @param framesBase64   in-memory selfie frames, JPEG base64, never written to disk
     * @param challengeSeed  server-issued seed for this attempt
     * @param expectedActions the re-derived action order
     * @param faceBox        [x, y, w, h] as reported by the client's on-device gate
     */
    record VerifyRequest(String requestId, List<String> framesBase64, String challengeSeed,
                         List<String> expectedActions, List<Integer> faceBox) {
    }

    record VerifyScores(BigDecimal attackScore, BigDecimal similarity,
                        String livenessModelVersion, String embeddingModelVersion) {
    }
}
