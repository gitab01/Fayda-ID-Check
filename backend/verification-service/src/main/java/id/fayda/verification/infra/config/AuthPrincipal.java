package id.fayda.verification.infra.config;

/**
 * A verified bearer token, reduced to what the service is allowed to know about it: the subject
 * id from {@code sub} and the scope. Nothing else from the token is carried into the domain.
 */
public record AuthPrincipal(Long userId, String scope) {

    public static final String SCOPE_SUBJECT = "subject";
    public static final String SCOPE_REVIEWER = "reviewer";
    public static final String SCOPE_SYSTEM = "system";

    /** Maps the token scope onto the {@code audit_log.actor_kind} vocabulary. */
    public String actorKind() {
        return switch (scope) {
            case SCOPE_REVIEWER -> "REVIEWER";
            case SCOPE_SYSTEM -> "SYSTEM";
            default -> "SUBJECT";
        };
    }

    public String authority() {
        return "SCOPE_" + scope;
    }
}
