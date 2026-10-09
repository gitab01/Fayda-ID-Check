package id.fayda.verification.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * UTC everywhere: the schema columns are {@code DATETIME2(3)} and every wire value is
 * ISO-8601 with milliseconds and an explicit {@code Z} (contract conventions §Conventions).
 */
public final class UtcTimes {

    private static final DateTimeFormatter WIRE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    private UtcTimes() {
    }

    public static LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    public static String iso(LocalDateTime value) {
        return value == null ? null : WIRE.format(value);
    }

    public static LocalDateTime parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            return java.time.Instant.parse(trimmed).atZone(ZoneOffset.UTC).toLocalDateTime();
        } catch (java.time.format.DateTimeParseException ignored) {
            // fall through to the lenient ISO forms
        }
        try {
            return java.time.OffsetDateTime.parse(trimmed).atZoneSameInstant(ZoneOffset.UTC)
                    .toLocalDateTime();
        } catch (java.time.format.DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDateTime.parse(trimmed, WIRE);
        } catch (java.time.format.DateTimeParseException ignored) {
            return LocalDateTime.parse(trimmed);
        }
    }
}
