package id.fayda.verification.service.errors;

import org.springframework.http.HttpStatus;

/**
 * Every failure the API can emit, paired with the HTTP status the contract assigns it and
 * whether a client may retry. Codes are the machine-readable {@code code} field of the
 * contract error body (§1 Errors).
 */
public enum ErrorCode {

    // 401 / 403
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, false),
    WRONG_SCOPE(HttpStatus.FORBIDDEN, false),
    ATTEMPT_NOT_VISIBLE(HttpStatus.FORBIDDEN, false),
    SUBJECT_UNKNOWN(HttpStatus.FORBIDDEN, false),

    // 404
    ATTEMPT_NOT_FOUND(HttpStatus.NOT_FOUND, false),

    // 409 — wrong state for this transition
    STATE_TRANSITION_ILLEGAL(HttpStatus.CONFLICT, false),
    ATTEMPT_NOT_CAPTURED(HttpStatus.CONFLICT, false),
    ATTEMPT_ALREADY_DECIDED(HttpStatus.CONFLICT, false),
    ATTEMPT_EXPIRED(HttpStatus.CONFLICT, false),
    RETRY_NOT_ALLOWED(HttpStatus.CONFLICT, false),
    DOCUMENT_ALREADY_SUBMITTED(HttpStatus.CONFLICT, false),
    CAPTURE_LOST(HttpStatus.CONFLICT, false),

    // 422 — payload failed validation
    VALIDATION_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, false),
    CHALLENGE_SEQUENCE_MISMATCH(HttpStatus.UNPROCESSABLE_ENTITY, false),
    INSUFFICIENT_FRAMES(HttpStatus.UNPROCESSABLE_ENTITY, false),
    PAYLOAD_STALE(HttpStatus.UNPROCESSABLE_ENTITY, false),
    PAYLOAD_MALFORMED(HttpStatus.UNPROCESSABLE_ENTITY, false),
    PAYLOAD_UNPROCESSABLE(HttpStatus.UNPROCESSABLE_ENTITY, false),
    KEY_ID_MISMATCH(HttpStatus.UNPROCESSABLE_ENTITY, false),
    PAYLOAD_KEY_MISSING(HttpStatus.UNPROCESSABLE_ENTITY, false),
    IMAGE_DECODE_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, false),
    DOCUMENT_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, false),
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, false),

    // 429
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, true),

    // 503 — inference unavailable; attempt goes to FAILED_RETRYABLE
    INFERENCE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, true),

    // 500
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, false);

    private final HttpStatus status;
    private final boolean retryable;

    ErrorCode(HttpStatus status, boolean retryable) {
        this.status = status;
        this.retryable = retryable;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
