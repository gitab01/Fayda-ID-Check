package id.fayda.verification.infra.config;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.Date;
import java.util.Optional;
import java.util.Set;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import id.fayda.verification.infra.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * HS256 verification of the contract's bearer token (issuer {@code fayda-id-check},
 * {@code sub} = user id, {@code scope} = subject | reviewer | system).
 *
 * <p>The signing secret comes from the environment. A clearly-labelled dev default ships in
 * {@link AppProperties}; under the {@code prod} profile it is refused at startup rather than
 * silently trusted.</p>
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final Set<String> ALLOWED_SCOPES =
            Set.of(AuthPrincipal.SCOPE_SUBJECT, AuthPrincipal.SCOPE_REVIEWER, AuthPrincipal.SCOPE_SYSTEM);
    private static final int MIN_SECRET_BYTES = 32;   // HS256 needs a 256-bit key

    private final byte[] secret;
    private final String issuer;

    public JwtService(AppProperties properties, Environment environment) {
        this.secret = properties.getSecurity().getJwtSecret().getBytes(StandardCharsets.UTF_8);
        this.issuer = properties.getSecurity().getIssuer();
        boolean production = Set.of(environment.getActiveProfiles()).contains("prod");
        if (AppProperties.DEV_JWT_SECRET.equals(properties.getSecurity().getJwtSecret())) {
            if (production) {
                throw new IllegalStateException(
                        "REFUSING TO START: the profile 'prod' is active but JWT_SECRET is unset, so"
                        + " the dev-only signing secret would protect real identity traffic. Provide"
                        + " a 256-bit secret through the environment.");
            }
            log.warn("Using the DEV-ONLY JWT secret. Never deploy with this value: set JWT_SECRET "
                    + "(and HASH_PEPPER / CHALLENGE_HMAC_SECRET).");
        }
        if (secret.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("verification.security.jwt-secret must be at least "
                    + MIN_SECRET_BYTES + " bytes for HS256");
        }
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException("verification.security.issuer must not be blank");
        }
    }

    /** Empty when the token is absent, malformed, unsigned, wrongly signed, expired or unknown-issuer. */
    public Optional<AuthPrincipal> verify(String authorizationHeader) {
        Optional<String> token = bearerToken(authorizationHeader);
        if (token.isEmpty()) {
            return Optional.empty();
        }
        try {
            SignedJWT jwt = SignedJWT.parse(token.get());
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())) {
                log.debug("Rejected token with algorithm {}", jwt.getHeader().getAlgorithm());
                return Optional.empty();
            }
            JWSVerifier verifier = new MACVerifier(secret);
            if (!jwt.verify(verifier)) {
                return Optional.empty();
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            if (!issuer.equals(claims.getIssuer())) {
                return Optional.empty();
            }
            Date expiry = claims.getExpirationTime();
            if (expiry == null || expiry.before(new Date())) {
                return Optional.empty();
            }
            Long userId = parseUserId(claims.getSubject());
            String scope = claims.getStringClaim("scope");
            if (userId == null || scope == null || !ALLOWED_SCOPES.contains(scope)) {
                return Optional.empty();
            }
            return Optional.of(new AuthPrincipal(userId, scope));
        } catch (ParseException | com.nimbusds.jose.JOSEException | RuntimeException e) {
            // Never echo token material into logs; a rejected token is a debug event only.
            log.debug("Bearer token rejected");
            return Optional.empty();
        }
    }

    private static Optional<String> bearerToken(String header) {
        if (header == null) {
            return Optional.empty();
        }
        String trimmed = header.trim();
        if (trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = trimmed.substring(7).trim();
            return token.isEmpty() ? Optional.empty() : Optional.of(token);
        }
        return Optional.empty();
    }

    private static Long parseUserId(String subject) {
        if (subject == null) {
            return null;
        }
        try {
            long value = Long.parseLong(subject.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
