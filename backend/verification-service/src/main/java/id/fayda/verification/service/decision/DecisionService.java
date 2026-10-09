package id.fayda.verification.service.decision;

import java.math.BigDecimal;
import java.math.RoundingMode;

import id.fayda.verification.domain.Decision;

/**
 * CONTRACT §4, implemented as a pure function — no Spring, no database, no clock.
 *
 * <pre>
 *   composite = w_l*(1 - attackScore) + w_m*similarity + w_d*documentScore
 * </pre>
 *
 * Hard floors are applied first, in the contract's order, and each short-circuits the composite:
 * <ol>
 *   <li>{@code attackScore > min_liveness} -&gt; FAIL / PRESENTATION_ATTACK_SUSPECTED</li>
 *   <li>{@code similarity < min_match}      -&gt; FAIL / FACE_MISMATCH</li>
 *   <li>MRZ or checksum invalid             -&gt; FAIL / DOCUMENT_INVALID</li>
 *   <li>retries exhausted                   -&gt; REVIEW / ATTEMPT_CAP_REACHED</li>
 * </ol>
 * Only then does the composite decide PASS / REVIEW / FAIL against {@code pass_composite} and
 * {@code review_composite}. Weights and thresholds arrive as a {@link ThresholdProfile} value
 * object, so a calibration change is data, not a code branch.
 */
public class DecisionService {

    public static final String REASON_ATTACK = "PRESENTATION_ATTACK_SUSPECTED";
    public static final String REASON_FACE_MISMATCH = "FACE_MISMATCH";
    public static final String REASON_DOCUMENT_INVALID = "DOCUMENT_INVALID";
    public static final String REASON_ATTEMPT_CAP = "ATTEMPT_CAP_REACHED";
    public static final String REASON_COMPOSITE_PASS = "COMPOSITE_ABOVE_PASS";
    public static final String REASON_REVIEW_BAND = "MATCH_IN_REVIEW_BAND";
    public static final String REASON_COMPOSITE_FAIL = "COMPOSITE_BELOW_REVIEW";

    private static final int SCALE = 5;

    public DecisionOutcome decide(DecisionInput input, ThresholdProfile profile) {
        if (input == null || profile == null) {
            throw new IllegalArgumentException("input and profile are both required");
        }
        BigDecimal composite = composite(input, profile);

        // --- hard floors, contract order -------------------------------------------------------
        if (input.attackScore().compareTo(profile.minLiveness()) > 0) {
            return outcome(Decision.FAIL, REASON_ATTACK, composite, profile);
        }
        if (input.similarity().compareTo(profile.minMatch()) < 0) {
            return outcome(Decision.FAIL, REASON_FACE_MISMATCH, composite, profile);
        }
        if (!input.mrzValid() || !input.checksumValid()) {
            return outcome(Decision.FAIL, REASON_DOCUMENT_INVALID, composite, profile);
        }
        // attempt_no has moved past the profile's cap: step up to a reviewer, never a self-service
        // pass, even when the composite looks healthy.
        if (input.attemptNo() > profile.maxAttempts()) {
            return outcome(Decision.REVIEW, REASON_ATTEMPT_CAP, composite, profile);
        }

        // --- weighted composite bands ---------------------------------------------------------
        if (composite.compareTo(profile.passComposite()) >= 0) {
            return outcome(Decision.PASS, REASON_COMPOSITE_PASS, composite, profile);
        }
        if (composite.compareTo(profile.reviewComposite()) >= 0) {
            return outcome(Decision.REVIEW, REASON_REVIEW_BAND, composite, profile);
        }
        return outcome(Decision.FAIL, REASON_COMPOSITE_FAIL, composite, profile);
    }

    /** Exposed for tests and for the stored {@code composite_score} column. */
    public BigDecimal composite(DecisionInput input, ThresholdProfile profile) {
        BigDecimal livenessTerm = profile.weightLiveness()
                .multiply(BigDecimal.ONE.subtract(input.attackScore()));
        BigDecimal matchTerm = profile.weightMatch().multiply(input.similarity());
        BigDecimal documentTerm = profile.weightDocument().multiply(input.documentScore());
        return livenessTerm.add(matchTerm).add(documentTerm).setScale(SCALE, RoundingMode.HALF_UP);
    }

    private DecisionOutcome outcome(Decision decision, String reasonCode,
                                    BigDecimal composite, ThresholdProfile profile) {
        return new DecisionOutcome(decision, reasonCode, guidance(reasonCode), composite,
                profile.version());
    }

    /** Guidance text shown to the subject; keyed off the reason code, per §1. */
    private String guidance(String reasonCode) {
        return switch (reasonCode) {
            case REASON_ATTACK ->
                    "Liveness could not be confirmed. Retry in even daylight with nothing covering "
                    + "the camera lens.";
            case REASON_FACE_MISMATCH ->
                    "Your selfie did not match the document photo. Retry with your full face visible "
                    + "and no sunglasses.";
            case REASON_DOCUMENT_INVALID ->
                    "The document could not be validated. Retry with all four corners in frame and "
                    + "the glare off.";
            case REASON_ATTEMPT_CAP ->
                    "You have used all self-service attempts. A reviewer will complete this "
                    + "verification; no further action is needed from you.";
            case REASON_COMPOSITE_PASS ->
                    "Verification complete. Your identity has been confirmed.";
            case REASON_REVIEW_BAND ->
                    "A reviewer will confirm this verification. No action needed from you.";
            default ->
                    "Image quality was too low to verify automatically. Retry closer to the camera "
                    + "in better lighting.";
        };
    }
}
