package id.fayda.verification.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;

import id.fayda.verification.service.errors.ApiException;
import id.fayda.verification.service.errors.ErrorCode;
import org.springframework.stereotype.Service;

/**
 * Turns document OCR into the validation facts that {@code document_metadata} stores and that
 * §4's {@code DOCUMENT_INVALID} floor reads.
 *
 * <p>The contract names "document checksum or MRZ invalid" but does not publish the algorithm, so
 * this service defines and documents one: a NATIONAL_ID must be exactly 13 digits
 * ({@code mrzValid}) and the 13th digit must be the mod-11 check digit of the first 12 weighted by
 * position {@code 2..13} ({@code checksumValid}). Both are deterministic and unit tested; swapping
 * in the official specification later changes this class only.</p>
 *
 * <p>Nothing here needs the raw id after it has returned, and the raw id is never logged: the
 * result carries booleans, a score and the parsed expiry date only.</p>
 */
@Service
public class DocumentValidator {

    private static final int NATIONAL_ID_DIGITS = 13;
    private static final BigDecimal FULL = BigDecimal.ONE;
    private static final BigDecimal NONE = BigDecimal.ZERO;

    /**
     * @param mrzValid      structurally readable identifier
     * @param checksumValid mod-11 check digit agrees
     * @param expiryDate    parsed expiry, may be null
     * @param documentScore 1.0 when both checks pass, else 0.0 — never a guessed middle value
     */
    public record Validation(boolean mrzValid, boolean checksumValid, LocalDate expiryDate,
                             BigDecimal documentScore) {
        public boolean usable() {
            return mrzValid && checksumValid;
        }
    }

    public Validation validate(String documentType, String idNumber, String expiryDateRaw) {
        boolean structural = structuralCheck(documentType, idNumber);
        // The mod-11 rule below is specified for NATIONAL_ID only; extending it to document types
        // whose real specification is unknown would reject valid passports as "checksum invalid".
        boolean checksum = structural
                && (!isNationalId(documentType) || mod11CheckDigitAgrees(idNumber));
        LocalDate expiry = parseExpiry(expiryDateRaw);
        return new Validation(structural, checksum, expiry,
                (structural && checksum) ? FULL : NONE);
    }

    /** Rejects a payload whose document facts could never produce a decision. */
    public void requireReadable(String documentType, String idNumber) {
        if (idNumber == null || idNumber.isBlank()) {
            throw ApiException.of(ErrorCode.DOCUMENT_INVALID,
                    "ocr.idNumber is required to build the document hash");
        }
        if (documentType == null || documentType.isBlank()) {
            throw ApiException.of(ErrorCode.DOCUMENT_INVALID, "documentType is required");
        }
    }

    private static boolean structuralCheck(String documentType, String idNumber) {
        if (idNumber == null) {
            return false;
        }
        String digits = idNumber.trim();
        if (!digits.matches("[0-9]+")) {
            return false;
        }
        // PASSPORT machine-readable zones are alphanumeric and longer; national IDs are 13 digits.
        if (isNationalId(documentType)) {
            return digits.length() == NATIONAL_ID_DIGITS;
        }
        return digits.length() >= 6 && digits.length() <= 20;
    }

    private static boolean isNationalId(String documentType) {
        return documentType == null
                || "NATIONAL_ID".equalsIgnoreCase(documentType.trim().replace('-', '_'));
    }

    /** mod-11, weights 2..13 over the first 12 digits; the 13th digit must equal the check digit. */
    static boolean mod11CheckDigitAgrees(String idNumber) {
        String digits = idNumber.trim();
        if (digits.length() != NATIONAL_ID_DIGITS || !digits.matches("[0-9]+")) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < NATIONAL_ID_DIGITS - 1; i++) {
            sum += (digits.charAt(i) - '0') * (i + 2);
        }
        int checkDigit = (11 - (sum % 11)) % 11;
        if (checkDigit > 9) {
            // Not representable as a single digit: the number cannot be a valid FA id.
            return false;
        }
        return digits.charAt(NATIONAL_ID_DIGITS - 1) - '0' == checkDigit;
    }

    private static LocalDate parseExpiry(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim().toUpperCase(Locale.ROOT));
        } catch (DateTimeParseException e) {
            throw ApiException.of(ErrorCode.DOCUMENT_INVALID,
                    "ocr.expiryDate is not an ISO date (yyyy-MM-dd)");
        }
    }
}
