package id.fayda.verification.api.dto;

import java.math.BigDecimal;

import id.fayda.verification.domain.entity.DecisionRecord;

/** Response body of {@code POST /attempts/{id}/verify} and the decision part of a status read. */
public record DecisionResponse(
        long attemptId,
        String decision,
        BigDecimal compositeScore,
        Scores scores,
        String reasonCode,
        String guidance,
        String thresholdVersion,
        String decidedAtUtc) {

    public record Scores(BigDecimal liveness, BigDecimal match, BigDecimal document) {
    }

    public static DecisionResponse from(DecisionRecord record, String decidedAtUtc) {
        return new DecisionResponse(
                record.getAttemptId(),
                record.getDecision().name(),
                record.getCompositeScore(),
                new Scores(record.getLivenessScore(), record.getMatchScore(),
                        record.getDocValidationScore()),
                record.getReasonCode(),
                record.getGuidanceText(),
                record.getThresholdVersion(),
                decidedAtUtc);
    }
}
