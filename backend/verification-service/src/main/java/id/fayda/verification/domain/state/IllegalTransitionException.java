package id.fayda.verification.domain.state;

import id.fayda.verification.domain.AttemptStatus;

/** Raised when a caller asks for a transition the lifecycle forbids; mapped to HTTP 409. */
public class IllegalTransitionException extends RuntimeException {

    private final AttemptStatus from;
    private final AttemptStatus to;

    public IllegalTransitionException(AttemptStatus from, AttemptStatus to) {
        super("Transition " + from + " -> " + to + " is not allowed");
        this.from = from;
        this.to = to;
    }

    public AttemptStatus getFrom() {
        return from;
    }

    public AttemptStatus getTo() {
        return to;
    }
}
