package id.fayda.verification.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /attempts}. */
public record StartAttemptRequest(
        @NotBlank(message = "documentType is required")
        @Size(max = 32, message = "documentType is too long") String documentType,

        @Size(max = 256, message = "deviceInfo must fit 256 characters") String deviceInfo) {
}
