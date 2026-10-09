package id.fayda.verification.service;

import java.util.List;

import id.fayda.verification.domain.ChallengeAction;
import id.fayda.verification.infra.config.AppProperties;
import id.fayda.verification.service.errors.ApiException;
import id.fayda.verification.service.errors.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The challenge is verified by re-derivation, not by stored state, so derivation must be
 * deterministic, duplicate-free, and impossible to predict from the seed alone.
 */
class ChallengeServiceTest {

    private final ChallengeService challenges = new ChallengeService(new AppProperties());

    @Test
    void derivesTheSameOrderTwiceForTheSameSeed() {
        String seed = challenges.newSeed();

        assertThat(challenges.derive(seed)).isEqualTo(challenges.derive(seed));
        assertThat(challenges.derivedNames(seed)).isEqualTo(challenges.derive(seed).stream()
                .map(Enum::name).toList());
    }

    @Test
    void sequencesAreTheConfiguredLengthAndNeverRepeatAnAction() {
        for (int i = 0; i < 200; i++) {
            List<ChallengeAction> actions = challenges.derive(challenges.newSeed());
            assertThat(actions).hasSize(3).doesNotHaveDuplicates();
        }
    }

    @Test
    void everyPermutationIsReachableAcrossSeeds() {
        java.util.Set<List<ChallengeAction>> seen = new java.util.HashSet<>();
        for (int n = 0; n < 3000; n++) {
            seen.add(challenges.derive(challenges.newSeed()));
        }

        // 7 actions taken 3 at a time in order is 210 permutations. Reaching all of them is the
        // property under test: a generator that cannot is leaking structure into the challenge
        // (and, as a side effect, proves distinct seeds do not collapse onto one order).
        assertThat(seen).hasSize(210);
    }

    @Test
    void acceptsTheExactOrderAndRejectsAnyReordering() {
        String seed = challenges.newSeed();
        List<String> expected = challenges.derivedNames(seed);

        assertThat(challenges.sequenceMatches(seed, expected)).isTrue();
        assertThat(challenges.sequenceMatches(seed, List.copyOf(expected).reversed())).isFalse();
        assertThat(challenges.sequenceMatches(seed, expected.subList(0, 2))).isFalse();
        assertThat(challenges.sequenceMatches(seed, null)).isFalse();
    }

    @Test
    void matchingIsCaseAndWhitespaceTolerantBecauseClientsFormatStringsDifferently() {
        String seed = challenges.newSeed();
        List<String> lower = challenges.derivedNames(seed).stream()
                .map(name -> " " + name.toLowerCase() + " ")
                .toList();

        assertThat(challenges.sequenceMatches(seed, lower)).isTrue();
    }

    @Test
    void aMismatchRaisesTheContractCode() {
        String seed = challenges.newSeed();

        assertThatThrownBy(() -> challenges.requireSequenceMatches(seed, List.of("NOD", "NOD", "NOD")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.CHALLENGE_SEQUENCE_MISMATCH);
    }

    @Test
    void storedFormRoundTripsInOrder() {
        List<ChallengeAction> actions = challenges.derive(challenges.newSeed());
        String stored = challenges.toStoredForm(actions);

        assertThat(stored.length()).isLessThan(256);
        assertThat(challenges.fromStoredForm(stored)).isEqualTo(actions);
        assertThat(challenges.fromStoredForm(null)).isEmpty();
    }

    @Test
    void aSeedIsLowerCaseHexThatFitsTheColumn() {
        assertThat(challenges.newSeed()).matches("[0-9a-f]{32}");
    }

    @Test
    void anOutOfRangeSequenceLengthFailsAtConstructionNotAtFirstAttempt() {
        AppProperties tooLong = new AppProperties();
        tooLong.getChallenge().setLength(ChallengeAction.poolSize() + 1);

        assertThatThrownBy(() -> new ChallengeService(tooLong))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("verification.challenge.length");
    }
}
