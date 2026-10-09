package id.fayda.verification.infra.config;

import id.fayda.verification.service.RateLimiterService;
import id.fayda.verification.service.errors.ErrorCode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Scope enforcement for the public API (CONTRACT §1: 401 unauthenticated, 403 wrong scope).
 *
 * <p>{@code subject} and {@code system} may drive an attempt end to end; {@code reviewer} may
 * read attempts but never submits a payload or forces a decision. Per-subject ownership is
 * enforced in the service layer, because the reviewer scope legitimately crosses subjects.</p>
 *
 * <p>The JWT and rate-limit filters are constructed here instead of being registered as beans, so
 * the container does not execute them a second time outside the security chain.</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String ATTEMPTS = "/api/v1/attempts";
    private static final String ATTEMPT_CHILDREN = "/api/v1/attempts/**";

    /** Granted authorities derived from the token {@code scope} claim. */
    static final class Authorities {
        static final String SUBJECT = "SCOPE_subject";
        static final String REVIEWER = "SCOPE_reviewer";
        static final String SYSTEM = "SCOPE_system";

        private Authorities() {
        }
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwtService,
                                            RateLimiterService rateLimiterService,
                                            AppProperties properties,
                                            ApiErrorWriter errorWriter) throws Exception {
        JwtAuthFilter jwtAuthFilter = new JwtAuthFilter(jwtService);
        RateLimitFilter rateLimitFilter =
                new RateLimitFilter(rateLimiterService, properties, errorWriter);

        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Health is open: a probe must not be able to fail on auth or throttle.
                        .requestMatchers("/api/v1/health").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**",
                                "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.GET, ATTEMPTS, ATTEMPT_CHILDREN)
                        .hasAnyAuthority(Authorities.SUBJECT, Authorities.REVIEWER, Authorities.SYSTEM)
                        .requestMatchers(HttpMethod.POST, ATTEMPTS, ATTEMPT_CHILDREN)
                        .hasAnyAuthority(Authorities.SUBJECT, Authorities.SYSTEM)
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, ex) -> errorWriter.write(
                                request, response, ErrorCode.UNAUTHENTICATED,
                                "A valid bearer token with a subject, reviewer or system scope is required."))
                        .accessDeniedHandler((request, response, ex) -> errorWriter.write(
                                request, response, ErrorCode.WRONG_SCOPE,
                                "The token scope does not permit this endpoint.")))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(rateLimitFilter, JwtAuthFilter.class);

        return http.build();
    }
}
