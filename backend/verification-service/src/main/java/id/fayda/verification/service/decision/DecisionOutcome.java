package id.fayda.verification.service.decision;

import java.math.BigDecimal;

import id.fayda.verification.domain.Decision;

/**
 * Result of the composite: decision, machine-readable reason code, the guidance text shown to
 * the subject, the composite value and the threshold profile version that produced it.
 */
public record DecisionOutcome(
        Decision decision,
        String reasonCode,
        String guidance,
        BigDecimal compositeScore,
        String thresholdVersion) {
}
