package id.fayda.verification.domain;

/**
 * Attempt lifecycle states, exactly the set the {@code ck_attempt_status} check constraint allows.
 * Terminal states: {@link #DECIDED} and {@link #EXPIRED}.
 */
public enum AttemptStatus {
    CREATED,
    CAPTURED,
    INFERRING,
    DECIDED,
    EXPIRED,
    FAILED_RETRYABLE;

    public static AttemptStatus fromStorage(String raw) {
        return AttemptStatus.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
