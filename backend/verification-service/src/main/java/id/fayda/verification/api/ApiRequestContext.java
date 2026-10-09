package id.fayda.verification.api;

import id.fayda.verification.service.Actor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import id.fayda.verification.infra.config.AuthPrincipal;
import id.fayda.verification.service.errors.ApiException;
import id.fayda.verification.service.errors.ErrorCode;

/**
 * Resolves the acting {@link Actor} for a request: who (verified JWT {@code sub}), from where
 * (client IP), correlated by what ({@code X-Request-Id}). Every service takes this explicitly so
 * audit rows are always attributable and no thread-local lookup is buried in business code.
 */
public final class ApiRequestContext {

    public static final String REQUEST_ID_ATTRIBUTE = "verification.requestId";

    private ApiRequestContext() {
    }

    public static String requestId(HttpServletRequest request) {
        Object attribute = request.getAttribute(REQUEST_ID_ATTRIBUTE);
        if (attribute instanceof String value && !value.isBlank()) {
            return value;
        }
        String header = request.getHeader("X-Request-Id");
        String resolved = (header == null || header.isBlank())
                ? UUID.randomUUID().toString() : header.trim();
        request.setAttribute(REQUEST_ID_ATTRIBUTE, resolved);
        return resolved;
    }

    public static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // Leftmost entry is the client; the rest are proxies.
            return first(forwarded.split(",")[0].trim(), 45);
        }
        return first(request.getRemoteAddr(), 45);
    }

    /** The authenticated actor; throws 401 when the JWT filter did not establish an identity. */
    public static Actor requireActor(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthPrincipal principal)) {
            throw ApiException.of(ErrorCode.UNAUTHENTICATED,
                    "a verified bearer token is required for this endpoint");
        }
        return new Actor(principal.userId(), principal.actorKind(), clientIp(request),
                requestId(request));
    }

    private static String first(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }
}
