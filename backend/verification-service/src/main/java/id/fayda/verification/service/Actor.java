package id.fayda.verification.service;

/**
 * The acting subject of a request, resolved by the API layer from the verified JWT and the
 * transport request. Services take it explicitly instead of reaching for thread-locals, and it
 * is what every {@code audit_log} row is stamped with.
 *
 * @param actorId   user id from the token {@code sub}
 * @param actorKind SUBJECT | REVIEWER | SYSTEM (mirrors the audit_log check comment)
 * @param ipAddress best-effort client address
 * @param requestId X-Request-Id correlation value
 */
public record Actor(Long actorId, String actorKind, String ipAddress, String requestId) {

    public static final String KIND_SUBJECT = "SUBJECT";
    public static final String KIND_REVIEWER = "REVIEWER";
    public static final String KIND_SYSTEM = "SYSTEM";

    public boolean isSystem() {
        return KIND_SYSTEM.equals(actorKind);
    }

    public boolean isReviewer() {
        return KIND_REVIEWER.equals(actorKind);
    }

    /** Actor used for background work the subject never triggered (the expiry sweeper). */
    public static Actor system(String requestId) {
        return new Actor(null, KIND_SYSTEM, null, requestId);
    }
}
