package id.fayda.verification.api;

import jakarta.servlet.http.HttpServletRequest;

import id.fayda.verification.api.dto.ErrorResponse;
import id.fayda.verification.domain.state.IllegalTransitionException;
import id.fayda.verification.infra.inference.InferenceUnavailableException;
import id.fayda.verification.service.errors.ApiException;
import id.fayda.verification.service.errors.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The single place the contract error body is assembled for controller failures (CONTRACT §1):
 * {@code { code, message, requestId, retryable }}.
 *
 * <p>Messages are the ones the throwing layer wrote — they are prose about the request, never
 * about its contents. Nothing here echoes a payload, an id number or a frame back to the caller,
 * and the catch-all deliberately hides the exception text: a 500 carries a requestId, and the
 * stack trace stays in the log.</p>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> apiException(ApiException e, HttpServletRequest request) {
        return render(e.getCode(), e.getMessage(), request);
    }

    @ExceptionHandler(InferenceUnavailableException.class)
    public ResponseEntity<ErrorResponse> inferenceDown(InferenceUnavailableException e,
                                                       HttpServletRequest request) {
        log.warn("inference unavailable requestId={} inferenceRequestId={} type={}",
                ApiRequestContext.requestId(request), e.getRequestId(),
                e.getClass().getSimpleName());
        return render(ErrorCode.INFERENCE_UNAVAILABLE,
                "the scoring service is unavailable; the attempt is pending retry", request);
    }

    @ExceptionHandler(IllegalTransitionException.class)
    public ResponseEntity<ErrorResponse> illegalTransition(IllegalTransitionException e,
                                                           HttpServletRequest request) {
        return render(ErrorCode.STATE_TRANSITION_ILLEGAL, e.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> invalidBody(MethodArgumentNotValidException e,
                                                     HttpServletRequest request) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("the request body failed validation");
        return render(ErrorCode.VALIDATION_FAILED, detail, request);
    }

    /** A body that is not JSON at all is a malformed payload, not a server fault. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> unreadableBody(HttpMessageNotReadableException e,
                                                        HttpServletRequest request) {
        return render(ErrorCode.PAYLOAD_MALFORMED,
                "the request body could not be read as JSON", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unexpected(Exception e, HttpServletRequest request) {
        // Spring's own routing failures (unknown path, wrong method, unsupported media type) carry
        // a 4xx status and a safe message; they must not be flattened into a 500.
        if (e instanceof org.springframework.web.ErrorResponse frameworkError) {
            HttpStatus status = HttpStatus.valueOf(frameworkError.getStatusCode().value());
            ErrorCode code = status.is4xxClientError() ? ErrorCode.VALIDATION_FAILED
                    : ErrorCode.INTERNAL_ERROR;
            return ResponseEntity.status(status).body(new ErrorResponse(code.name(),
                    status.getReasonPhrase(), ApiRequestContext.requestId(request),
                    code.isRetryable()));
        }
        String requestId = ApiRequestContext.requestId(request);
        log.error("unhandled failure requestId={} type={} message={}", requestId,
                e.getClass().getName(), e.getMessage(), e);
        return render(ErrorCode.INTERNAL_ERROR,
                "the service could not complete this request; reference " + requestId, request);
    }

    private static ResponseEntity<ErrorResponse> render(ErrorCode code, String message,
                                                        HttpServletRequest request) {
        ErrorResponse body = new ErrorResponse(code.name(), message,
                ApiRequestContext.requestId(request), code.isRetryable());
        return ResponseEntity.status(code.getStatus()).body(body);
    }
}
