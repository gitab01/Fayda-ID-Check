package id.fayda.verification.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import id.fayda.verification.service.decision.ThresholdProfile;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The decision numbers exist in two places on purpose — the seeded {@code threshold_profiles} row
 * and the bundled fallback for a database that has none — and they must say the same thing. This
 * is the drift check; the values are read out of the migration text, not restated here.
 */
class ThresholdProfileSeedTest {

    private final ThresholdProfile bundled =
            new ThresholdProfileProvider(null).bundledDefault();

    @Test
    void theSeededRowMatchesTheBundledProfileFieldForField() throws IOException {
        String[] tokens = valuesTuple().split(",", -1);

        assertThat(tokens).as("one token per column").hasSize(11);
        assertThat(unquote(tokens[0])).isEqualTo(bundled.version());
        assertThat(decimal(tokens[1])).isEqualByComparingTo(bundled.weightLiveness());
        assertThat(decimal(tokens[2])).isEqualByComparingTo(bundled.weightMatch());
        assertThat(decimal(tokens[3])).isEqualByComparingTo(bundled.weightDocument());
        assertThat(decimal(tokens[4])).isEqualByComparingTo(bundled.passComposite());
        assertThat(decimal(tokens[5])).isEqualByComparingTo(bundled.reviewComposite());
        assertThat(decimal(tokens[6])).isEqualByComparingTo(bundled.minLiveness());
        assertThat(decimal(tokens[7])).isEqualByComparingTo(bundled.minMatch());
        assertThat(decimal(tokens[8])).isEqualByComparingTo(
                new java.math.BigDecimal(bundled.maxAttempts()));
        assertThat(unquote(tokens[9])).as("the seeded profile is the active one").isEqualTo("1");
    }

    @Test
    void theBundledFallbackIsAUsableProfileOnItsOwn() {
        assertThat(bundled.weightSum()).isEqualByComparingTo("1.00");
        assertThat(bundled.maxAttempts()).isGreaterThanOrEqualTo(1);
        assertThat(bundled.passComposite()).isGreaterThan(bundled.reviewComposite());
    }

    private static String valuesTuple() throws IOException {
        String sql = new ClassPathResource("db/migration/V2__seed_threshold_profile.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        int open = sql.indexOf("VALUES", 0);
        assertThat(open).as("the seed migration must insert a profile").isNotNegative();
        String tuple = sql.substring(sql.indexOf('(', open) + 1, sql.indexOf(')', open));
        // Whitespace/newlines inside the tuple are formatting only.
        return tuple.replaceAll("\\s+", " ").trim();
    }

    private static String unquote(String token) {
        return token.trim().replaceAll("^'|'$", "");
    }

    private static java.math.BigDecimal decimal(String token) {
        return new java.math.BigDecimal(token.trim());
    }
}
