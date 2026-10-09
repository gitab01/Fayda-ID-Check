package id.fayda.verification.infra.config;

import java.io.IOException;

import id.fayda.verification.api.ApiRequestContext;
import id.fayda.verification.service.RateLimiterService;
import id.fayda.verification.service.errors.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies the sliding-window limiter to the API surface, after the bearer token has been verified
 * so the per-subject dimension is known. Health is excluded: probes must not be throttled.
 *
 * <p>A breach returns HTTP 429 with the contract error body and {@code Retry-After}. Rejected
 * requests are deliberately <em>not</em> written to {@code audit_log}, because a flood would then
 * use the audit table as the amplifier of the flood.</p>
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String API_PREFIX = "/api/v1/";
    private static final String HEALTH = "/api/v1/health";

    private final RateLimiterService rateLimiter;
    private final AppProperties properties;
    private final ApiErrorWriter errorWriter;

    public RateLimitFilter(RateLimiterService rateLimiter, AppProperties properties,
                           ApiErrorWriter errorWriter) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !properties.getRateLimit().isEnabled()
                || !path.startsWith(API_PREFIX)
                || path.equals(HEALTH);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        RateLimiterService.Outcome userOutcome = rateLimiter.checkUser(currentUserId());
        RateLimiterService.Outcome ipOutcome = rateLimiter.checkIp(ApiRequestContext.clientIp(request));

        if (!userOutcome.allowed() || !ipOutcome.allowed()) {
            long retryAfter = Math.max(userOutcome.retryAfterSeconds(), ipOutcome.retryAfterSeconds());
            errorWriter.write(request, response, ErrorCode.RATE_LIMITED,
                    "Too many requests from this subject or address; retry in " + retryAfter
                            + " second(s).");
            response.setHeader("Retry-After", String.valueOf(retryAfter));
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static Long currentUserId() {
        org.springframework.security.core.Authentication authentication =
                org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthPrincipal principal) {
            return principal.userId();
        }
        return null;
    }
}
