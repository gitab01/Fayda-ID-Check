package id.fayda.verification.api.dto;

import id.fayda.verification.service.crypto.Envelope;
import jakarta.validation.constraints.NotBlank;

/** Wire form of CONTRACT §3; the app posts this as the whole body of document and selfie calls. */
public record EnvelopeDto(
        @NotBlank(message = "keyId is required") String keyId,
        @NotBlank(message = "iv is required") String iv,
        @NotBlank(message = "ciphertext is required") String ciphertext,
        @NotBlank(message = "tag is required") String tag,
        @NotBlank(message = "sentAtUtc is required") String sentAtUtc) {

    public Envelope toEnvelope() {
        return new Envelope(keyId, iv, ciphertext, tag, sentAtUtc);
    }
}
