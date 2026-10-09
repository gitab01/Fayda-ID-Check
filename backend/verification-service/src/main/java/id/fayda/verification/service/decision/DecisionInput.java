package id.fayda.verification.service.decision;

import java.math.BigDecimal;

/**
 * Everything the decision function reads. Scores come from the inference service (attack
 * probability and face similarity) or from document validation facts; no identity is present —
 * the function is pure and its inputs are numbers and booleans.
 *
 * @param attackScore  0..1, higher means "almost certainly an attack"
 * @param similarity   0..1 cosine similarity, normalised
 * @param documentScore 0..1 document validation score
 * @param mrzValid     MRZ / machine-readable zone parsed cleanly
 * @param checksumValid document checksum verified
 * @param attemptNo    1-based retry counter for this subject
 */
public record DecisionInput(
        BigDecimal attackScore,
        BigDecimal similarity,
        BigDecimal documentScore,
        boolean mrzValid,
        boolean checksumValid,
        int attemptNo) {

    public DecisionInput {
        attackScore = requireUnitInterval(attackScore, "attackScore");
        similarity = requireUnitInterval(similarity, "similarity");
        documentScore = requireUnitInterval(documentScore, "documentScore");
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must be >= 1");
        }
    }

    private static BigDecimal requireUnitInterval(BigDecimal value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required — the service never invents a score");
        }
        if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(name + " must lie in 0..1 (got " + value.toPlainString() + ")");
        }
        return value;
    }
}
