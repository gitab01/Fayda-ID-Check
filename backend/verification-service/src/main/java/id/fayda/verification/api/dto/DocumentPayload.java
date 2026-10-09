package id.fayda.verification.api.dto;

/**
 * Plaintext shape of the document envelope (CONTRACT §1). The image is held in memory for the
 * request only; only {@code ocr} facts and hashes are persisted.
 */
public record DocumentPayload(Image image, Ocr ocr, Quality quality) {

    public record Image(String bytes64, Integer width, Integer height, String mimeType) {
    }

    /** Raw id number appears here only inside the decrypted heap buffer — never persisted/logged. */
    public record Ocr(String idNumber, String fullName, String issuingRegion, String expiryDate) {
    }

    public record Quality(Double blurScore, Double glareRatio, Boolean cornersFound) {
    }
}
