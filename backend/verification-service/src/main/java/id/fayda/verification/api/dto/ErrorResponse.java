package id.fayda.verification.api.dto;

/**
 * The single error body of CONTRACT §1: {@code { code, message, requestId, retryable }}.
 * Never carries payload contents, only a machine-readable code and safe prose.
 */
public record ErrorResponse(String code, String message, String requestId, boolean retryable) {
}
