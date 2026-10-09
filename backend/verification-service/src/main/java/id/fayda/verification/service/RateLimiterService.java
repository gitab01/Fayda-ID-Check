package id.fayda.verification.service;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import id.fayda.verification.infra.config.AppProperties;
import org.springframework.stereotype.Service;

/**
 * In-memory sliding-window rate limiter, keyed per subject and per client IP. Each key keeps the
 * timestamps of its own window, so a burst that ages out is released rather than penalised for a
 * whole fixed interval.
 *
 * <p>Explicitly in-memory: the contract gives this service one instance's worth of traffic
 * shaping and the state is not identity data, so it never touches the database.</p>
 */
@Service
public class RateLimiterService {

    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();
    private final AppProperties properties;

    public RateLimiterService(AppProperties properties) {
        this.properties = properties;
    }

    public record Outcome(boolean allowed, int remaining, long retryAfterSeconds) {
    }

    public Outcome checkUser(Long userId) {
        if (userId == null || !properties.getRateLimit().isEnabled()) {
            return new Outcome(true, Integer.MAX_VALUE, 0);
        }
        return check("user:" + userId, properties.getRateLimit().getPerUserRequests(),
                Duration.ofSeconds(properties.getRateLimit().getPerUserWindowSeconds()));
    }

    public Outcome checkIp(String ip) {
        if (ip == null || ip.isBlank() || !properties.getRateLimit().isEnabled()) {
            return new Outcome(true, Integer.MAX_VALUE, 0);
        }
        return check("ip:" + ip, properties.getRateLimit().getPerIpRequests(),
                Duration.ofSeconds(properties.getRateLimit().getPerIpWindowSeconds()));
    }

    /** Records a hit; used by callers that want the window consumed without re-deciding. */
    public Outcome recordHit(String key, int limit, Duration window) {
        return check(key, limit, window);
    }

    private Outcome check(String key, int limit, Duration window) {
        long now = System.currentTimeMillis();
        long cutoff = now - window.toMillis();
        Deque<Long> hits = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (hits) {
            while (!hits.isEmpty() && hits.peekFirst() < cutoff) {
                hits.removeFirst();
            }
            if (hits.size() >= limit) {
                long oldest = hits.peekFirst();
                long retryAfterMillis = oldest + window.toMillis() - now;
                return new Outcome(false, 0, Math.max(1, (retryAfterMillis + 999) / 1000));
            }
            hits.addLast(now);
            return new Outcome(true, Math.max(0, limit - hits.size()), 0);
        }
    }

    /** Releases a key whose window has fully aged out; called by the maintenance sweep. */
    public int evictIdle() {
        long cutoff = System.currentTimeMillis()
                - Duration.ofSeconds(properties.getRateLimit().getPerUserWindowSeconds()).toMillis() * 4;
        int[] removed = {0};
        windows.forEach((key, hits) -> {
            synchronized (hits) {
                if (hits.isEmpty() || hits.peekLast() < cutoff) {
                    if (windows.remove(key) != null) {
                        removed[0]++;
                    }
                }
            }
        });
        return removed[0];
    }

    int trackedKeys() {
        return windows.size();
    }
}
