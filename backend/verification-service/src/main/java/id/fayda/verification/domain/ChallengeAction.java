package id.fayda.verification.domain;

/**
 * Liveness prompts the client can execute. The ordered subset an attempt must follow is
 * derived from the server-issued seed by {@code ChallengeService}; this enum is the pool.
 */
public enum ChallengeAction {
    TURN_LEFT,
    TURN_RIGHT,
    BLINK,
    NOD,
    SMILE,
    TILT_LEFT,
    TILT_RIGHT;

    private static final ChallengeAction[] ORDERED = values();

    public static ChallengeAction at(int index) {
        return ORDERED[Math.floorMod(index, ORDERED.length)];
    }

    public static int poolSize() {
        return ORDERED.length;
    }
}
