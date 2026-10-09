package id.fayda.verification.service.decision;

import java.math.BigDecimal;

/**
 * Immutable value object for one calibrated threshold profile — the in-memory twin of a
 * {@code threshold_profiles} row. The decision function takes it as data, which is what keeps
 * {@link DecisionService} free of Spring and of database access.
 *
 * <p>Weights are constrained to sum to 1 so the §4 composite stays a 0..1 quantity.</p>
 */
public record ThresholdProfile(
        String version,
        BigDecimal weightLiveness,
        BigDecimal weightMatch,
        BigDecimal weightDocument,
        BigDecimal passComposite,
        BigDecimal reviewComposite,
        BigDecimal minLiveness,
        BigDecimal minMatch,
        int maxAttempts) {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal WEIGHT_SUM_TOLERANCE = new BigDecimal("0.0005");

    public ThresholdProfile {
        version = requireText(version, "version");
        weightLiveness = requireWeight(weightLiveness, "weightLiveness");
        weightMatch = requireWeight(weightMatch, "weightMatch");
        weightDocument = requireWeight(weightDocument, "weightDocument");
        passComposite = requireUnitInterval(passComposite, "passComposite");
        reviewComposite = requireUnitInterval(reviewComposite, "reviewComposite");
        minLiveness = requireUnitInterval(minLiveness, "minLiveness");
        minMatch = requireUnitInterval(minMatch, "minMatch");
        if (passComposite.compareTo(reviewComposite) < 0) {
            throw new IllegalArgumentException("pass_composite must be >= review_composite");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("max_attempts must be >= 1");
        }
        BigDecimal sum = weightLiveness.add(weightMatch).add(weightDocument);
        if (sum.subtract(ONE).abs().compareTo(WEIGHT_SUM_TOLERANCE) > 0) {
            throw new IllegalArgumentException(
                    "weights must sum to 1 so the composite stays in 0..1 (got " + sum.toPlainString() + ")");
        }
    }

    public BigDecimal weightSum() {
        return weightLiveness.add(weightMatch).add(weightDocument);
    }

    private static BigDecimal requireWeight(BigDecimal value, String name) {
        BigDecimal checked = requireUnitInterval(value, name);
        if (checked.compareTo(ZERO) < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return checked;
    }

    private static BigDecimal requireUnitInterval(BigDecimal value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        if (value.compareTo(ZERO) < 0 || value.compareTo(ONE) > 0) {
            throw new IllegalArgumentException(name + " must lie in 0..1 (got " + value.toPlainString() + ")");
        }
        return value;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }
}
