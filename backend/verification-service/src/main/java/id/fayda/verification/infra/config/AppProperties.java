package id.fayda.verification.infra.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Every tunable in one place, each overridable by environment variable through
 * {@code application.yml}. Nothing here is a secret by value — the defaults are dev-only and
 * {@code JwtService} refuses to boot with the dev JWT secret under the {@code prod} profile.
 */
@ConfigurationProperties(prefix = "verification")
public class AppProperties {

    private final Security security = new Security();
    private final Challenge challenge = new Challenge();
    private final Crypto crypto = new Crypto();
    private final Hashing hashing = new Hashing();
    private final Deadline deadline = new Deadline();
    private final RateLimit rateLimit = new RateLimit();
    private final Inference inference = new Inference();

    public Security getSecurity() {
        return security;
    }

    public Challenge getChallenge() {
        return challenge;
    }

    public Crypto getCrypto() {
        return crypto;
    }

    public Hashing getHashing() {
        return hashing;
    }

    public Deadline getDeadline() {
        return deadline;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public Inference getInference() {
        return inference;
    }

    /** Clearly-labelled dev default; refused in prod. HS256 needs >= 32 bytes. */
    public static final String DEV_JWT_SECRET =
            "DEV-ONLY-fayda-id-check-jwt-secret-do-not-use-in-prod-0123456789";
    public static final String DEV_HASH_PEPPER = "DEV-ONLY-id-hash-pepper";

    public static class Security {
        private String jwtSecret = DEV_JWT_SECRET;
        private String issuer = "fayda-id-check";

        /**
         * An unset env var arrives as an empty string through {@code ${JWT_SECRET:}}. Falling back
         * to the labelled dev default here keeps one source of truth for that value instead of
         * duplicating it in YAML, and {@code JwtService} still refuses it under {@code prod}.
         */
        public void setJwtSecret(String jwtSecret) {
            this.jwtSecret = orDevDefault(jwtSecret, DEV_JWT_SECRET);
        }

        public String getJwtSecret() {
            return jwtSecret;
        }

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }
    }

    public static class Challenge {
        private String hmacSecret = DEV_JWT_SECRET + "|challenge";
        private int length = 3;

        public String getHmacSecret() {
            return hmacSecret;
        }

        public void setHmacSecret(String hmacSecret) {
            this.hmacSecret = orDevDefault(hmacSecret, DEV_JWT_SECRET + "|challenge");
        }

        public int getLength() {
            return length;
        }

        public void setLength(int length) {
            this.length = length;
        }
    }

    public static class Crypto {
        /** §3: sentAtUtc older than this is rejected as a replay. */
        private int replayWindowSeconds = 120;
        private int maxCiphertextBytes = 8_000_000;
        private int maxImageBytes = 2_000_000;
        private int maxFrames = 12;

        public int getReplayWindowSeconds() {
            return replayWindowSeconds;
        }

        public void setReplayWindowSeconds(int replayWindowSeconds) {
            this.replayWindowSeconds = replayWindowSeconds;
        }

        public Duration replayWindow() {
            return Duration.ofSeconds(replayWindowSeconds);
        }

        public int getMaxCiphertextBytes() {
            return maxCiphertextBytes;
        }

        public void setMaxCiphertextBytes(int maxCiphertextBytes) {
            this.maxCiphertextBytes = maxCiphertextBytes;
        }

        public int getMaxImageBytes() {
            return maxImageBytes;
        }

        public void setMaxImageBytes(int maxImageBytes) {
            this.maxImageBytes = maxImageBytes;
        }

        public int getMaxFrames() {
            return maxFrames;
        }

        public void setMaxFrames(int maxFrames) {
            this.maxFrames = maxFrames;
        }
    }

    public static class Hashing {
        /** Env-supplied pepper folded into the hash input; never persisted. */
        private String pepper = DEV_HASH_PEPPER;

        public String getPepper() {
            return pepper;
        }

        public void setPepper(String pepper) {
            this.pepper = orDevDefault(pepper, DEV_HASH_PEPPER);
        }
    }

    /** Blank or absent env input keeps the labelled dev default; {@code prod} refuses to boot on it. */
    private static String orDevDefault(String value, String devDefault) {
        return (value == null || value.isBlank()) ? devDefault : value;
    }

    public static class Deadline {
        private int createdSeconds = 300;
        private int capturedSeconds = 300;
        private int inferringSeconds = 90;
        private int failedRetryableSeconds = 900;
        private long sweepIntervalMs = 15_000L;

        public int getCreatedSeconds() {
            return createdSeconds;
        }

        public void setCreatedSeconds(int createdSeconds) {
            this.createdSeconds = createdSeconds;
        }

        public int getCapturedSeconds() {
            return capturedSeconds;
        }

        public void setCapturedSeconds(int capturedSeconds) {
            this.capturedSeconds = capturedSeconds;
        }

        public int getInferringSeconds() {
            return inferringSeconds;
        }

        public void setInferringSeconds(int inferringSeconds) {
            this.inferringSeconds = inferringSeconds;
        }

        public int getFailedRetryableSeconds() {
            return failedRetryableSeconds;
        }

        public void setFailedRetryableSeconds(int failedRetryableSeconds) {
            this.failedRetryableSeconds = failedRetryableSeconds;
        }

        public long getSweepIntervalMs() {
            return sweepIntervalMs;
        }

        public void setSweepIntervalMs(long sweepIntervalMs) {
            this.sweepIntervalMs = sweepIntervalMs;
        }
    }

    public static class RateLimit {
        private boolean enabled = true;
        private int perUserRequests = 60;
        private int perUserWindowSeconds = 60;
        private int perIpRequests = 120;
        private int perIpWindowSeconds = 60;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getPerUserRequests() {
            return perUserRequests;
        }

        public void setPerUserRequests(int perUserRequests) {
            this.perUserRequests = perUserRequests;
        }

        public int getPerUserWindowSeconds() {
            return perUserWindowSeconds;
        }

        public void setPerUserWindowSeconds(int perUserWindowSeconds) {
            this.perUserWindowSeconds = perUserWindowSeconds;
        }

        public int getPerIpRequests() {
            return perIpRequests;
        }

        public void setPerIpRequests(int perIpRequests) {
            this.perIpRequests = perIpRequests;
        }

        public int getPerIpWindowSeconds() {
            return perIpWindowSeconds;
        }

        public void setPerIpWindowSeconds(int perIpWindowSeconds) {
            this.perIpWindowSeconds = perIpWindowSeconds;
        }
    }

    public static class Inference {
        private String baseUrl = "http://localhost:8000";
        private int connectTimeoutMs = 5_000;
        private int readTimeoutMs = 10_000;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public int getReadTimeoutMs() {
            return readTimeoutMs;
        }

        public void setReadTimeoutMs(int readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
        }
    }
}
