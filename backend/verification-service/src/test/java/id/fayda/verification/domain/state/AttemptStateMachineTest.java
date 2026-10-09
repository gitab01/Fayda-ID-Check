package id.fayda.verification.domain.state;

import id.fayda.verification.domain.AttemptStatus;
import org.junit.jupiter.api.Test;

import static id.fayda.verification.domain.AttemptStatus.CAPTURED;
import static id.fayda.verification.domain.AttemptStatus.CREATED;
import static id.fayda.verification.domain.AttemptStatus.DECIDED;
import static id.fayda.verification.domain.AttemptStatus.EXPIRED;
import static id.fayda.verification.domain.AttemptStatus.FAILED_RETRYABLE;
import static id.fayda.verification.domain.AttemptStatus.INFERRING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The lifecycle table is the whole state policy, so every edge of it is asserted here. */
class AttemptStateMachineTest {

    private final AttemptStateMachine machine = new AttemptStateMachine();

    @Test
    void allowsTheHappyPath() {
        assertThat(machine.canTransition(CREATED, CAPTURED)).isTrue();
        assertThat(machine.canTransition(CAPTURED, INFERRING)).isTrue();
        assertThat(machine.canTransition(INFERRING, DECIDED)).isTrue();
    }

    @Test
    void allowsRetryOnlyBackToCreated() {
        assertThat(machine.canTransition(INFERRING, FAILED_RETRYABLE)).isTrue();
        assertThat(machine.canTransition(FAILED_RETRYABLE, CREATED)).isTrue();
        assertThat(machine.canTransition(FAILED_RETRYABLE, DECIDED)).isFalse();
        assertThat(machine.canTransition(FAILED_RETRYABLE, CAPTURED)).isFalse();
    }

    @Test
    void treatsDecidedAndExpiredAsTerminal() {
        assertThat(machine.isTerminal(DECIDED)).isTrue();
        assertThat(machine.isTerminal(EXPIRED)).isTrue();
        for (AttemptStatus target : AttemptStatus.values()) {
            assertThat(machine.canTransition(DECIDED, target)).as("DECIDED -> " + target).isFalse();
            assertThat(machine.canTransition(EXPIRED, target)).as("EXPIRED -> " + target).isFalse();
        }
    }

    @Test
    void rejectsSkipsOverStages() {
        assertThat(machine.canTransition(CREATED, INFERRING)).isFalse();
        assertThat(machine.canTransition(CREATED, DECIDED)).isFalse();
        assertThat(machine.canTransition(CAPTURED, DECIDED)).isFalse();
    }

    @Test
    void everyOpenStageIsExpirable() {
        assertThat(machine.expirableStates()).containsExactlyInAnyOrder(
                CREATED, CAPTURED, INFERRING, FAILED_RETRYABLE);
    }

    @Test
    void transitionReturnsTheTargetAndThrowingEdgeNamesBothStates() {
        assertThat(machine.transition(CREATED, CAPTURED)).isEqualTo(CAPTURED);
        assertThatThrownBy(() -> machine.assertCanTransition(CREATED, DECIDED))
                .isInstanceOf(IllegalTransitionException.class)
                .hasMessageContaining("CREATED")
                .hasMessageContaining("DECIDED");
    }

    @Test
    void nullStatesAreNeverATransitions() {
        assertThat(machine.canTransition(null, CREATED)).isFalse();
        assertThat(machine.canTransition(CREATED, null)).isFalse();
    }
}
