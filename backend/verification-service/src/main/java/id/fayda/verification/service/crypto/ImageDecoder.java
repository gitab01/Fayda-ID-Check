package id.fayda.verification.service.crypto;

import java.util.Arrays;

import id.fayda.verification.infra.config.AppProperties;
import id.fayda.verification.service.errors.ApiException;
import id.fayda.verification.service.errors.ErrorCode;
import org.springframework.stereotype.Service;

/**
 * Decodes image payloads into heap buffers. There is deliberately no filesystem path, no temp
 * file and no cache directory anywhere in this class: the decoded bytes exist only as long as
 * the reference does, and {@link DecodedImage#destroy()} zeroes them.
 */
@Service
public class ImageDecoder {

    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 0x50, 0x4E, 0x47};

    private final AppProperties properties;

    public ImageDecoder(AppProperties properties) {
        this.properties = properties;
    }

    /** A decoded image held in memory. */
    public static final class DecodedImage {
        private final byte[] bytes;
        private final int width;
        private final int height;
        private final String mimeType;
        private final String base64;

        DecodedImage(byte[] bytes, int width, int height, String mimeType, String base64) {
            this.bytes = bytes;
            this.width = width;
            this.height = height;
            this.mimeType = mimeType;
            this.base64 = base64;
        }

        public byte[] bytes() {
            return Arrays.copyOf(bytes, bytes.length);
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        public String mimeType() {
            return mimeType;
        }

        /** Re-encoded base64 for the inference hop; identical to the input encoding. */
        public String base64() {
            return base64;
        }

        public int size() {
            return bytes.length;
        }

        public void destroy() {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    public DecodedImage decode(String base64, Integer reportedWidth, Integer reportedHeight,
                               String mimeType) {
        int width = positive(reportedWidth, "width");
        int height = positive(reportedHeight, "height");
        return decodeValidated(base64, mimeType, width, height);
    }

    /**
     * Selfie frames declare no dimensions in the contract, only bytes: validate the image content
     * itself and leave width/height at 0 to mean "not declared by the client".
     */
    public DecodedImage decodeFrame(String base64) {
        return decodeValidated(base64, "image/jpeg", 0, 0);
    }

    private DecodedImage decodeValidated(String base64, String claimedMimeType, int width,
                                         int height) {
        if (base64 == null || base64.isBlank()) {
            throw ApiException.of(ErrorCode.IMAGE_DECODE_FAILED, "image bytes64 is required");
        }
        byte[] raw;
        try {
            raw = java.util.Base64.getMimeDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.of(ErrorCode.IMAGE_DECODE_FAILED, "image bytes64 is not valid base64");
        }
        if (raw.length == 0) {
            throw ApiException.of(ErrorCode.IMAGE_DECODE_FAILED, "image payload is empty");
        }
        if (raw.length > properties.getCrypto().getMaxImageBytes()) {
            throw ApiException.of(ErrorCode.PAYLOAD_TOO_LARGE,
                    "image exceeds the %d byte in-memory limit",
                    properties.getCrypto().getMaxImageBytes());
        }
        String detected = detectMagic(raw, claimedMimeType);
        return new DecodedImage(raw, width, height, detected, base64.trim());
    }

    private static int positive(Integer value, String field) {
        if (value == null || value <= 0) {
            throw ApiException.of(ErrorCode.IMAGE_DECODE_FAILED,
                    "image %s must be a positive number of pixels", field);
        }
        return value;
    }

    /** Only real JPEG/PNG payloads are accepted; a disguised binary blob never reaches memory as an "image". */
    private static String detectMagic(byte[] raw, String claimedMimeType) {
        if (startsWith(raw, JPEG_MAGIC)) {
            return requireCompatible(claimedMimeType, "image/jpeg");
        }
        if (startsWith(raw, PNG_MAGIC)) {
            return requireCompatible(claimedMimeType, "image/png");
        }
        throw ApiException.of(ErrorCode.IMAGE_DECODE_FAILED,
                "payload is not a JPEG or PNG image");
    }

    private static String requireCompatible(String claimed, String detected) {
        if (claimed == null || claimed.isBlank()) {
            return detected;
        }
        String normalized = claimed.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.startsWith("image/")) {
            throw ApiException.of(ErrorCode.IMAGE_DECODE_FAILED,
                    "declared mimeType is not an image type");
        }
        return normalized;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
