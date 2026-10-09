package id.fayda.verification.infra.sweeper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import id.fayda.verification.domain.entity.VerificationAttempt;
import id.fayda.verification.domain.state.AttemptStateMachine;
import id.fayda.verification.infra.repo.VerificationAttemptRepository;
import id.fayda.verification.service.Actor;
import id.fayda.verification.service.AttemptService;
import id.fayda.verification.service.RateLimiterService;
import id.fayda.verification.service.UtcTimes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The deadline enforcer. Every stage of an attempt carries {@code stage_deadline_utc}; this
 * sweeper claims anything still sitting in a non-terminal state past it and drives it to EXPIRED
 * through the state machine, releasing the in-memory payload key and frames.
 *
 * <p>Runs on a fixed delay (configurable), so a client that abandons an attempt cannot leave a row
 * in {@code CREATED} or {@code INFERRING} forever — which also means an abandoned attempt never
 * becomes a decision.</p>
 */
@Component
public class AttemptExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(AttemptExpirySweeper.class);

    private final VerificationAttemptRepository attemptRepository;
    private final AttemptStateMachine stateMachine;
    private final AttemptService attemptService;
    private final RateLimiterService rateLimiter;

    public AttemptExpirySweeper(VerificationAttemptRepository attemptRepository,
                                AttemptStateMachine stateMachine,
                                AttemptService attemptService,
                                RateLimiterService rateLimiter) {
        this.attemptRepository = attemptRepository;
        this.stateMachine = stateMachine;
        this.attemptService = attemptService;
        this.rateLimiter = rateLimiter;
    }

    @Scheduled(fixedDelayString = "${verification.deadline.sweep-interval-ms:15000}",
            initialDelayString = "${verification.deadline.sweep-initial-delay-ms:15000}")
    public void scheduledSweep() {
        sweep();
    }

    /** @return how many attempts were expired by this pass */
    public int sweep() {
        LocalDateTime cutoff = UtcTimes.nowUtc();
        List<VerificationAttempt> stalled =
                attemptRepository.findByStatusInAndStageDeadlineUtcBefore(
                        stateMachine.expirableStates(), cutoff);
        int expired = 0;
        for (VerificationAttempt attempt : stalled) {
            Actor system = Actor.system("sweep-" + UUID.randomUUID());
            if (attemptService.expire(attempt.getId(), system)) {
                expired++;
            }
        }
        rateLimiter.evictIdle();
        if (expired > 0) {
            log.info("expiry sweep expired {} stalled attempt(s)", expired);
        }
        return expired;
    }
}
