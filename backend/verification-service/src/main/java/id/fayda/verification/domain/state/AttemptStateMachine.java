package id.fayda.verification.domain.state;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import id.fayda.verification.domain.AttemptStatus;

/**
 * The attempt lifecycle, declared once as data:
 *
 * <pre>
 *   CREATED -> CAPTURED -> INFERRING -> DECIDED
 *      |          |           |
 *      |          |           +-> FAILED_RETRYABLE -> CREATED   (retry)
 *      +----------+--------------- EXPIRED                       (deadline / sweeper)
 * </pre>
 *
 * {@code DECIDED} and {@code EXPIRED} are terminal. Any edge not listed here is illegal and
 * raises {@link IllegalTransitionException}, which the API layer renders as HTTP 409 with the
 * contract error body. Deliberately free of Spring so it can be unit tested directly.
 */
public final class AttemptStateMachine {

    private static final Map<AttemptStatus, Set<AttemptStatus>> TRANSITIONS =
            buildTransitions();

    private static Map<AttemptStatus, Set<AttemptStatus>> buildTransitions() {
        Map<AttemptStatus, Set<AttemptStatus>> map = new EnumMap<>(AttemptStatus.class);
        map.put(AttemptStatus.CREATED, setOf(AttemptStatus.CAPTURED, AttemptStatus.EXPIRED));
        map.put(AttemptStatus.CAPTURED, setOf(AttemptStatus.INFERRING, AttemptStatus.EXPIRED));
        map.put(AttemptStatus.INFERRING, setOf(AttemptStatus.DECIDED,
                AttemptStatus.FAILED_RETRYABLE, AttemptStatus.EXPIRED));
        map.put(AttemptStatus.FAILED_RETRYABLE, setOf(AttemptStatus.CREATED, AttemptStatus.EXPIRED));
        map.put(AttemptStatus.DECIDED, Collections.emptySet());
        map.put(AttemptStatus.EXPIRED, Collections.emptySet());
        return Collections.unmodifiableMap(map);
    }

    private static Set<AttemptStatus> setOf(AttemptStatus... values) {
        Set<AttemptStatus> set = new LinkedHashSet<>();
        for (AttemptStatus value : values) {
            set.add(value);
        }
        return Collections.unmodifiableSet(set);
    }

    public boolean canTransition(AttemptStatus from, AttemptStatus to) {
        if (from == null || to == null) {
            return false;
        }
        return allowedFrom(from).contains(to);
    }

    public Set<AttemptStatus> allowedFrom(AttemptStatus from) {
        Set<AttemptStatus> allowed = TRANSITIONS.get(from);
        return allowed == null ? Collections.emptySet() : allowed;
    }

    public void assertCanTransition(AttemptStatus from, AttemptStatus to) {
        if (!canTransition(from, to)) {
            throw new IllegalTransitionException(from, to);
        }
    }

    /** Moves an attempt, returning the new state so callers can chain the lifecycle visibly. */
    public AttemptStatus transition(AttemptStatus from, AttemptStatus to) {
        assertCanTransition(from, to);
        return to;
    }

    public boolean isTerminal(AttemptStatus status) {
        return allowedFrom(status).isEmpty();
    }

    /** States the expiry sweeper may still claim. */
    public Set<AttemptStatus> expirableStates() {
        Set<AttemptStatus> expirable = new LinkedHashSet<>();
        for (Map.Entry<AttemptStatus, Set<AttemptStatus>> entry : TRANSITIONS.entrySet()) {
            if (entry.getValue().contains(AttemptStatus.EXPIRED)) {
                expirable.add(entry.getKey());
            }
        }
        return Collections.unmodifiableSet(expirable);
    }
}
