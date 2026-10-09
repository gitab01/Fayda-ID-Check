package id.fayda.verification.service;

import java.math.BigDecimal;

import id.fayda.verification.service.errors.ApiException;
import id.fayda.verification.service.errors.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The published contract names "checksum or MRZ invalid" without an algorithm, so this class
 * defines one (13 digits, mod-11 check digit with positional weights 2..13). These tests are what
 * pin that definition down.
 */
class DocumentValidatorTest {

    private final DocumentValidator validator = new DocumentValidator();

    @Test
    void aStructurallyValidNationalIdScoresFullMarks() {
        DocumentValidator.Validation validation =
                validator.validate("NATIONAL_ID", "1234567890126", "2030-04-01");

        assertThat(validation.mrzValid()).isTrue();
        assertThat(validation.checksumValid()).isTrue();
        assertThat(validation.usable()).isTrue();
        assertThat(validation.documentScore()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(validation.expiryDate()).hasToString("2030-04-01");
    }

    @Test
    void aWrongCheckDigitIsDetected() {
        // 123456789012 weights to check digit 6; anything else is not a valid id.
        assertThat(DocumentValidator.mod11CheckDigitAgrees("1234567890126")).isTrue();
        assertThat(DocumentValidator.mod11CheckDigitAgrees("1234567890120")).isFalse();
        assertThat(DocumentValidator.mod11CheckDigitAgrees("1234567890127")).isFalse();

        DocumentValidator.Validation validation =
                validator.validate("NATIONAL_ID", "1234567890120", null);
        assertThat(validation.mrzValid()).isTrue();
        assertThat(validation.checksumValid()).isFalse();
        assertThat(validation.documentScore()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void theScoreIsNeverAGuessedMiddleValue() {
        assertThat(validator.validate("NATIONAL_ID", "12345678901", null).documentScore())
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(validator.validate("NATIONAL_ID", "1234567890126", null).documentScore())
                .isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void wrongLengthOrNonDigitsFailTheStructuralCheck() {
        assertThat(validator.validate("NATIONAL_ID", "123456789012", null).mrzValid()).isFalse();
        assertThat(validator.validate("NATIONAL_ID", "12345678901267", null).mrzValid()).isFalse();
        assertThat(validator.validate("NATIONAL_ID", "12345 7890126", null).mrzValid()).isFalse();
        assertThat(validator.validate("NATIONAL_ID", null, null).mrzValid()).isFalse();
    }

    @Test
    void passportsUseTheAlphanumericLengthRuleInsteadOfThirteenDigits() {
        // A seven-digit number would have walked off the end of the mod-11 rule.
        assertThat(validator.validate("PASSPORT", "1234567", null).usable()).isTrue();
        assertThat(validator.validate("PASSPORT", "12345", null).mrzValid()).isFalse();
        assertThat(validator.validate("driving-license", "1234567890", null).usable()).isTrue();
    }

    @Test
    void documentTypeIsNormalisedBeforeItIsChecked() {
        assertThat(validator.validate("national-id", "1234567890126", null).usable()).isTrue();
    }

    @Test
    void anUnparseableExpiryIsARejectedPayloadNotASilentNull() {
        assertThat(validator.validate("NATIONAL_ID", "1234567890126", " ").expiryDate()).isNull();
        assertThatThrownBy(() -> validator.validate("NATIONAL_ID", "1234567890126", "01/04/2030"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.DOCUMENT_INVALID);
    }

    @Test
    void requireReadableRefusesAPayloadThatCouldNeverProduceADecision() {
        assertThatThrownBy(() -> validator.requireReadable("NATIONAL_ID", null))
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.DOCUMENT_INVALID);
        assertThatThrownBy(() -> validator.requireReadable("NATIONAL_ID", "  "))
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.DOCUMENT_INVALID);
        assertThatThrownBy(() -> validator.requireReadable(null, "1234567890126"))
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.DOCUMENT_INVALID);
    }
}
