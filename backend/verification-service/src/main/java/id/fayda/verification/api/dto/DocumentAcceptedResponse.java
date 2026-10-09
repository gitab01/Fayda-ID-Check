package id.fayda.verification.api.dto;

/** Response to {@code POST /attempts/{id}/document} (HTTP 202). */
public record DocumentAcceptedResponse(long attemptId, String status, boolean documentAccepted) {
}
