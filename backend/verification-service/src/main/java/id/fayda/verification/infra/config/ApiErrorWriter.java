package id.fayda.verification.infra.config;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.fayda.verification.api.ApiRequestContext;
import id.fayda.verification.api.dto.ErrorResponse;
import id.fayda.verification.service.errors.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * Writes the contract error body from outside the MVC layer — the security entry point and the
 * access-denied handler need the same shape as the {@code @RestControllerAdvice}, and a filter
 * that aborts the chain never reaches the advice.
 */
@Component
public class ApiErrorWriter {

    private final ObjectMapper objectMapper;

    public ApiErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, ErrorCode code,
                      String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(code.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        ErrorResponse body = new ErrorResponse(code.name(), message,
                ApiRequestContext.requestId(request), code.isRetryable());
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
