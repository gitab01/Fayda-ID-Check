package id.fayda.verification.service.crypto;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import id.fayda.verification.service.errors.ApiException;
import id.fayda.verification.service.errors.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * Holds the per-attempt AES payload key for the lifetime of the attempt, in memory only.
 *
 * <p>The schema stores {@code request_key_hash} — a hash of the key id — and nothing else, so a
 * database dump never yields key material. Entries are zeroed and dropped as soon as the attempt
 * reaches a terminal state, and on TTL expiry.</p>
 *
 * <p>How the key reaches the service is documented in {@code docs/CONTRACT.md} §3 plus the
 * deviation note in this service's README: the key is never inside the envelope (only its
 * {@code keyId} is) and never persisted, so it is delivered once over TLS in the
 * {@code X-Attempt-Key} header of the payload submission.</p>
 */
@Component
public class AttemptKeyRegistry {

    private final Map<Long, Entry> entries = new ConcurrentHashMap<>();

    public void register(long attemptId, byte[] key, java.time.Duration ttl) {
        Instant expiresAt = Instant.now().plus(ttl);
        entries.put(attemptId, new Entry(Arrays.copyOf(key, key.length), expiresAt));
        evictExpired();
    }

    public byte[] require(long attemptId) {
        Entry entry = entries.get(attemptId);
        if (entry == null || entry.isExpired()) {
            if (entry != null) {
                release(attemptId);
            }
            throw ApiException.of(ErrorCode.PAYLOAD_KEY_MISSING,
                    "no live per-attempt payload key is registered for attempt %d; restart the attempt",
                    attemptId);
        }
        return Arrays.copyOf(entry.key, entry.key.length);
    }

    public void release(long attemptId) {
        Entry removed = entries.remove(attemptId);
        if (removed != null) {
            Arrays.fill(removed.key, (byte) 0);
        }
    }

    public int size() {
        return entries.size();
    }

    private void evictExpired() {
        entries.entrySet().removeIf(e -> {
            if (e.getValue().isExpired()) {
                Arrays.fill(e.getValue().key, (byte) 0);
                return true;
            }
            return false;
        });
    }

    private static final class Entry {
        private final byte[] key;
        private final Instant expiresAt;

        private Entry(byte[] key, Instant expiresAt) {
            this.key = key;
            this.expiresAt = expiresAt;
        }

        private boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }
}
