package id.fayda.verification.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import id.fayda.verification.service.errors.ApiException;
import id.fayda.verification.service.errors.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * The transient, per-attempt context that the baseline schema deliberately has no column for —
 * today only the document type declared at {@code POST /attempts}.
 *
 * <p>Like the payload key and the decoded frames, it lives on the heap for the duration of the
 * attempt and is dropped on a terminal transition. If the process restarts, the subject cannot
 * resume: the per-attempt AES key is gone with it, so a new attempt is the only correct path.</p>
 */
@Component
public class AttemptContextStore {

    private final Map<Long, Context> contexts = new ConcurrentHashMap<>();

    public void declareDocumentType(long attemptId, String documentType, Duration ttl) {
        contexts.put(attemptId, new Context(documentType, Instant.now().plus(ttl)));
        evictExpired();
    }

    public String requireDocumentType(long attemptId) {
        Context context = contexts.get(attemptId);
        if (context == null || context.isExpired()) {
            release(attemptId);
            throw ApiException.of(ErrorCode.CAPTURE_LOST,
                    "the document type declared for attempt %d is no longer held; start a new attempt",
                    attemptId);
        }
        return context.documentType();
    }

    public void release(long attemptId) {
        contexts.remove(attemptId);
    }

    public int size() {
        return contexts.size();
    }

    private void evictExpired() {
        contexts.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    private record Context(String documentType, Instant expiresAt) {
        private boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }
}
