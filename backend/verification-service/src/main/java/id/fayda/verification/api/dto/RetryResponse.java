package id.fayda.verification.api.dto;

/** Response of {@code POST /attempts/{id}/retry}. */
public record RetryResponse(
        long attemptId,
        String status,
        int attemptNo,
        int attemptsRemaining,
        String stageDeadlineUtc) {
}
