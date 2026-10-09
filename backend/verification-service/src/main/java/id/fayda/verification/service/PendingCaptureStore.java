package id.fayda.verification.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import id.fayda.verification.service.errors.ApiException;
import id.fayda.verification.service.errors.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * Keeps the decrypted selfie material between {@code /selfie} and {@code /verify} for one attempt.
 * This is the whole reason images never need a column: the frames live here, in the heap, for the
 * duration of the attempt and are then dropped (CONTRACT §1: "Images are held in memory only").
 *
 * <p>Entries vanish on terminal transitions, on TTL expiry and on shutdown. They are never written
 * to disk, never serialised and never logged.</p>
 */
@Component
public class PendingCaptureStore {

    private final Map<Long, PendingCapture> captures = new ConcurrentHashMap<>();

    public void store(long attemptId, List<String> framesBase64, List<String> executedActions,
                      List<Integer> faceBox, java.time.Duration ttl) {
        captures.put(attemptId, new PendingCapture(List.copyOf(framesBase64),
                List.copyOf(executedActions), List.copyOf(faceBox), Instant.now().plus(ttl)));
        evictExpired();
    }

    public PendingCapture require(long attemptId) {
        PendingCapture capture = captures.get(attemptId);
        if (capture == null || capture.isExpired()) {
            release(attemptId);
            throw ApiException.of(ErrorCode.CAPTURE_LOST,
                    "the in-memory capture for attempt %d is gone; submit the selfie again", attemptId);
        }
        return capture;
    }

    public boolean isPresent(long attemptId) {
        PendingCapture capture = captures.get(attemptId);
        return capture != null && !capture.isExpired();
    }

    public void release(long attemptId) {
        captures.remove(attemptId);
    }

    public int size() {
        return captures.size();
    }

    public void releaseAll() {
        captures.clear();
    }

    private void evictExpired() {
        captures.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    /** Frames are base64 strings only: they are handed to inference and then dropped. */
    public record PendingCapture(List<String> framesBase64, List<String> executedActions,
                                 List<Integer> faceBox, Instant expiresAt) {

        public boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }

        public int frameCount() {
            return framesBase64.size();
        }
    }
}
