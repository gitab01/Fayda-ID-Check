package id.fayda.verification.infra.config;

import java.io.IOException;

import id.fayda.verification.api.ApiRequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Establishes the {@code X-Request-Id} correlation value for every request (CONTRACT
 * §Conventions), echoes it back to the caller, and puts it in the log context so a support
 * engineer can follow one attempt across services without logging anything about the person.
 *
 * <p>A missing header is generated rather than rejected: the contract requires clients to send
 * it, but a correlator must never be the reason an attempt fails.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    static final String MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = ApiRequestContext.requestId(request);
        response.setHeader("X-Request-Id", requestId);
        MDC.put(MDC_KEY, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
