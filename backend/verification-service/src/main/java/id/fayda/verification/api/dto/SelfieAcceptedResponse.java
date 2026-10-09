package id.fayda.verification.api.dto;

/**
 * Response to {@code POST /attempts/{id}/selfie} (HTTP 202). The contract does not fix this body,
 * so it mirrors the document response and reports the state the machine moved to.
 */
public record SelfieAcceptedResponse(long attemptId, String status, int framesAccepted,
                                     boolean challengeVerified) {
}
