package id.fayda.verification.service.decision;

import java.math.BigDecimal;

import id.fayda.verification.domain.Decision;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CONTRACT §4 as a table: floors first, in the contract's own order, then the weighted bands.
 * The profile below is the bundled default (0.30 / 0.55 / 0.15, pass 0.86, review 0.72,
 * min_liveness 0.35, min_match 0.48, max_attempts 3) so the expected outcomes are the numbers a
 * fresh deployment actually runs on.
 */
class DecisionServiceTest {

    private final DecisionService service = new DecisionService();
    private final ThresholdProfile profile = new ThresholdProfile("2026-05-1",
            new BigDecimal("0.30"), new BigDecimal("0.55"), new BigDecimal("0.15"),
            new BigDecimal("0.86000"), new BigDecimal("0.72000"),
            new BigDecimal("0.35000"), new BigDecimal("0.48000"), 3);

    @Test
    void attackFloorFailsEvenWhenTheCompositeIsAbovePass() {
        // 0.36 > min_liveness, yet composite 0.88650 is above pass_composite 0.86000.
        DecisionOutcome outcome = service.decide(input("0.36", "0.99", "1.0"), profile);

        assertThat(outcome.compositeScore()).isEqualByComparingTo("0.88650");
        assertThat(outcome.decision()).isEqualTo(Decision.FAIL);
        assertThat(outcome.reasonCode()).isEqualTo(DecisionService.REASON_ATTACK);
    }

    @Test
    void matchFloorFailsOnASimilarityJustUnderTheBar() {
        DecisionOutcome outcome = service.decide(input("0.01", "0.47", "1.0"), profile);

        assertThat(outcome.decision()).isEqualTo(Decision.FAIL);
        assertThat(outcome.reasonCode()).isEqualTo(DecisionService.REASON_FACE_MISMATCH);
    }

    @Test
    void invalidDocumentFailsOnItsOwnFloor() {
        DecisionOutcome outcome = service.decide(new DecisionInput(new BigDecimal("0.01"),
                new BigDecimal("0.95"), BigDecimal.ZERO, false, true, 1), profile);

        assertThat(outcome.decision()).isEqualTo(Decision.FAIL);
        assertThat(outcome.reasonCode()).isEqualTo(DecisionService.REASON_DOCUMENT_INVALID);
    }

    @Test
    void exhaustedRetriesStepUpToAReviewerInsteadOfAPass() {
        // A perfect composite still cannot self-pass once attempt_no is past max_attempts.
        DecisionOutcome outcome = service.decide(input("0.01", "0.99", "1.0", 4), profile);

        assertThat(outcome.decision()).isEqualTo(Decision.REVIEW);
        assertThat(outcome.reasonCode()).isEqualTo(DecisionService.REASON_ATTEMPT_CAP);
    }

    @Test
    void floorsAreCheckedInContractOrder() {
        // Attack, mismatch and an invalid document at once: the attack row wins.
        DecisionOutcome outcome = service.decide(new DecisionInput(new BigDecimal("0.90"),
                new BigDecimal("0.10"), BigDecimal.ZERO, false, false, 9), profile);
        assertThat(outcome.reasonCode()).isEqualTo(DecisionService.REASON_ATTACK);

        // No attack breach but a mismatch plus an invalid document: the face floor wins.
        assertThat(service.decide(new DecisionInput(new BigDecimal("0.10"),
                new BigDecimal("0.10"), BigDecimal.ZERO, false, false, 9), profile)
                .reasonCode()).isEqualTo(DecisionService.REASON_FACE_MISMATCH);

        // Only the document is bad and the attempt is over the cap: document still wins.
        assertThat(service.decide(new DecisionInput(new BigDecimal("0.10"),
                new BigDecimal("0.90"), BigDecimal.ZERO, false, true, 9), profile)
                .reasonCode()).isEqualTo(DecisionService.REASON_DOCUMENT_INVALID);
    }

    @Test
    void compositeBandsAreInclusiveAtBothEnds() {
        assertThat(service.decide(input("0.02", "0.90", "1.0"), profile).reasonCode())
                .isEqualTo(DecisionService.REASON_COMPOSITE_PASS);
        assertThat(service.decide(input("0.02", "0.90", "1.0"), profile).decision())
                .isEqualTo(Decision.PASS);

        DecisionOutcome review = service.decide(input("0.05", "0.75", "1.0"), profile);
        assertThat(review.compositeScore()).isEqualByComparingTo("0.84750");
        assertThat(review.decision()).isEqualTo(Decision.REVIEW);
        assertThat(review.reasonCode()).isEqualTo(DecisionService.REASON_REVIEW_BAND);

        DecisionOutcome fail = service.decide(input("0.30", "0.50", "0.0"), profile);
        assertThat(fail.compositeScore()).isEqualByComparingTo("0.48500");
        assertThat(fail.reasonCode()).isEqualTo(DecisionService.REASON_COMPOSITE_FAIL);
    }

    @Test
    void compositeIsTheWeightedSumRoundedAtScaleFive() {
        BigDecimal composite = service.composite(input("0.0333", "0.7111", "0.9777"), profile);

        // 0.30*(1-0.0333) + 0.55*0.7111 + 0.15*0.9777 = 0.29001 + 0.391105 + 0.146655
        assertThat(composite).isEqualByComparingTo("0.82777");
        assertThat(composite.scale()).isEqualTo(5);
    }

    @Test
    void everyOutcomeCarriesTheProfileVersionAndGuidance() {
        for (DecisionOutcome outcome : new DecisionOutcome[]{
                service.decide(input("0.99", "0.99", "1.0"), profile),
                service.decide(input("0.01", "0.01", "1.0"), profile),
                service.decide(new DecisionInput(new BigDecimal("0.01"), new BigDecimal("0.99"),
                        BigDecimal.ZERO, false, true, 1), profile),
                service.decide(input("0.01", "0.99", "1.0", 7), profile),
                service.decide(input("0.01", "0.99", "1.0"), profile),
                service.decide(input("0.05", "0.75", "1.0"), profile),
                service.decide(input("0.30", "0.50", "0.0"), profile)}) {
            assertThat(outcome.thresholdVersion()).isEqualTo("2026-05-1");
            assertThat(outcome.guidance()).isNotBlank();
        }
    }

    @Test
    void aMisCalibratedProfileIsRejectedBeforeItCanDecideAnything() {
        assertThatThrownBy(() -> new ThresholdProfile("bad", new BigDecimal("0.50"),
                new BigDecimal("0.50"), new BigDecimal("0.50"), new BigDecimal("0.86000"),
                new BigDecimal("0.72000"), new BigDecimal("0.35000"), new BigDecimal("0.48000"), 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("weights must sum to 1");

        assertThatThrownBy(() -> new ThresholdProfile("bad", new BigDecimal("0.30"),
                new BigDecimal("0.55"), new BigDecimal("0.15"), new BigDecimal("0.40000"),
                new BigDecimal("0.72000"), new BigDecimal("0.35000"), new BigDecimal("0.48000"), 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pass_composite must be >= review_composite");
    }

    @Test
    void theFunctionRefusesToInventAMissingScore() {
        assertThatThrownBy(() -> service.decide(null, profile))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DecisionInput(null, BigDecimal.ONE, BigDecimal.ONE,
                true, true, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attackScore is required");
    }

    private static DecisionInput input(String attack, String similarity, String document) {
        return input(attack, similarity, document, 1);
    }

    private static DecisionInput input(String attack, String similarity, String document,
                                       int attemptNo) {
        return new DecisionInput(new BigDecimal(attack), new BigDecimal(similarity),
                new BigDecimal(document), true, true, attemptNo);
    }
}
