package id.fayda.verification.api;

import java.util.Base64;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import id.fayda.verification.api.dto.AttemptStatusResponse;
import id.fayda.verification.api.dto.DecisionResponse;
import id.fayda.verification.api.dto.DocumentAcceptedResponse;
import id.fayda.verification.api.dto.EnvelopeDto;
import id.fayda.verification.api.dto.RetryResponse;
import id.fayda.verification.api.dto.SelfieAcceptedResponse;
import id.fayda.verification.api.dto.StartAttemptRequest;
import id.fayda.verification.api.dto.StartAttemptResponse;
import id.fayda.verification.service.Actor;
import id.fayda.verification.service.AttemptService;
import id.fayda.verification.service.errors.ApiException;
import id.fayda.verification.service.errors.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The six endpoints of CONTRACT §1. The controller holds no business rule: it resolves the
 * {@link Actor} from the verified token, decodes the envelope body, and delegates.
 *
 * <p>Scope enforcement lives in {@code SecurityConfig}; per-subject visibility lives in
 * {@link AttemptService}. Route guards and the ownership check are deliberately separate, because
 * a reviewer scope may legitimately read across subjects.</p>
 */
@RestController
@RequestMapping("/api/v1/attempts")
public class AttemptController {

    /** Base64 of a 256-bit key: 32 bytes, 44 base64 characters. */
    static final String ATTEMPT_KEY_HEADER = "X-Attempt-Key";
    private static final int PAYLOAD_KEY_BYTES = 32;

    private final AttemptService attempts;

    public AttemptController(AttemptService attempts) {
        this.attempts = attempts;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StartAttemptResponse start(@RequestHeader(name = ATTEMPT_KEY_HEADER, required = false) String attemptKey,
                                      @Valid @RequestBody StartAttemptRequest body,
                                      HttpServletRequest request) {
        return attempts.start(actor(request), body, decodeKey(attemptKey));
    }

    @PostMapping("/{attemptId}/document")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DocumentAcceptedResponse document(@PathVariable long attemptId,
                                             @Valid @RequestBody EnvelopeDto envelope,
                                             HttpServletRequest request) {
        return attempts.submitDocument(actor(request), attemptId, envelope.toEnvelope());
    }

    @PostMapping("/{attemptId}/selfie")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SelfieAcceptedResponse selfie(@PathVariable long attemptId,
                                         @Valid @RequestBody EnvelopeDto envelope,
                                         HttpServletRequest request) {
        return attempts.submitSelfie(actor(request), attemptId, envelope.toEnvelope());
    }

    @PostMapping("/{attemptId}/verify")
    public DecisionResponse verify(@PathVariable long attemptId, HttpServletRequest request) {
        return attempts.verify(actor(request), attemptId);
    }

    @PostMapping("/{attemptId}/retry")
    public RetryResponse retry(@PathVariable long attemptId, HttpServletRequest request) {
        return attempts.retry(actor(request), attemptId);
    }

    @GetMapping("/{attemptId}")
    public AttemptStatusResponse status(@PathVariable long attemptId, HttpServletRequest request) {
        return attempts.status(actor(request), attemptId);
    }

    private static Actor actor(HttpServletRequest request) {
        return ApiRequestContext.requireActor(request);
    }

    /**
     * The per-attempt key travels once, in this header, over TLS — see the deviation note in
     * {@code docs/CONTRACT.md} §3. A missing or mis-sized key is a 422, never a silent default.
     */
    private static byte[] decodeKey(String attemptKey) {
        if (attemptKey == null || attemptKey.isBlank()) {
            throw ApiException.of(ErrorCode.PAYLOAD_KEY_MISSING,
                    "the %s header is required when starting an attempt", ATTEMPT_KEY_HEADER);
        }
        try {
            return Base64.getDecoder().decode(attemptKey.trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.of(ErrorCode.PAYLOAD_MALFORMED,
                    "the %s header must be base64", ATTEMPT_KEY_HEADER);
        }
    }
}
