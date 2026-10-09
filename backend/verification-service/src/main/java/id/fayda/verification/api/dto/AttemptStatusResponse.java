package id.fayda.verification.api.dto;

/** Response of {@code GET /attempts/{id}}: current status plus the decision if one exists. */
public record AttemptStatusResponse(
        long attemptId,
        String status,
        int attemptNo,
        String stageDeadlineUtc,
        String completedAtUtc,
        DecisionResponse decision) {
}
