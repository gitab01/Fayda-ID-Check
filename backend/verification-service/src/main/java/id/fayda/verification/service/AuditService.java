package id.fayda.verification.service;

import id.fayda.verification.domain.entity.AuditLogEntry;
import id.fayda.verification.infra.repo.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The audit trail: attempt start, payload received, inference called, decision made, record read.
 *
 * <p>Free-text detail is never stored in the clear — it is digested into
 * {@code detail_hash} — and the log line carries identifiers only, so application logs cannot
 * leak payload contents either.</p>
 *
 * <p>Writes use {@code REQUIRES_NEW}: an audit row about a failure must survive the rollback of
 * the business transaction that failed.</p>
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository auditLogRepository;
    private final HashService hashService;

    public AuditService(AuditLogRepository auditLogRepository, HashService hashService) {
        this.auditLogRepository = auditLogRepository;
        this.hashService = hashService;
    }

    public AuditLogEntry attemptStarted(Actor actor, long attemptId) {
        return record(actor, AuditLogEntry.ACTION_ATTEMPT_STARTED, attemptId,
                AuditLogEntry.OUTCOME_ACCEPTED, "attempt created");
    }

    public AuditLogEntry payloadReceived(Actor actor, long attemptId, String kind, boolean accepted) {
        return record(actor, AuditLogEntry.ACTION_PAYLOAD_RECEIVED, attemptId,
                accepted ? AuditLogEntry.OUTCOME_ACCEPTED : AuditLogEntry.OUTCOME_REJECTED,
                kind + " payload accepted for processing");
    }

    public AuditLogEntry inferenceCalled(Actor actor, long attemptId, String requestId,
                                         boolean succeeded, String modelVersions) {
        return record(actor, AuditLogEntry.ACTION_INFERENCE_CALLED, attemptId,
                succeeded ? AuditLogEntry.OUTCOME_ACCEPTED : AuditLogEntry.OUTCOME_UNAVAILABLE,
                "inference request " + requestId + " " + modelVersions);
    }

    public AuditLogEntry decisionMade(Actor actor, long attemptId, String decision,
                                      String reasonCode, String thresholdVersion) {
        return record(actor, AuditLogEntry.ACTION_DECISION_MADE, attemptId,
                AuditLogEntry.OUTCOME_ACCEPTED,
                decision + "/" + reasonCode + " profile " + thresholdVersion);
    }

    public AuditLogEntry recordRead(Actor actor, long attemptId) {
        return record(actor, AuditLogEntry.ACTION_RECORD_READ, attemptId,
                AuditLogEntry.OUTCOME_ACCEPTED, "status read");
    }

    public AuditLogEntry attemptRetried(Actor actor, long attemptId, int attemptNo) {
        return record(actor, AuditLogEntry.ACTION_ATTEMPT_RETRIED, attemptId,
                AuditLogEntry.OUTCOME_ACCEPTED, "retry " + attemptNo);
    }

    public AuditLogEntry attemptExpired(Actor actor, long attemptId) {
        return record(actor, AuditLogEntry.ACTION_ATTEMPT_EXPIRED, attemptId,
                AuditLogEntry.OUTCOME_REJECTED, "stage deadline exceeded");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditLogEntry record(Actor actor, String action, Long attemptId, String outcome,
                                String detail) {
        AuditLogEntry entry = new AuditLogEntry();
        entry.setActorId(actor == null ? null : actor.actorId());
        entry.setActorKind(actor == null ? Actor.KIND_SYSTEM : actor.actorKind());
        entry.setAction(action);
        entry.setAttemptId(attemptId);
        entry.setIpAddress(actor == null ? null : truncate(actor.ipAddress(), 45));
        entry.setOutcome(truncate(outcome, 24));
        entry.setDetailHash(detail == null ? null : hashService.sha256Hex(detail));
        entry.setCreatedAtUtc(UtcTimes.nowUtc());
        AuditLogEntry saved = auditLogRepository.save(entry);
        log.debug("audit action={} actorKind={} attemptId={} outcome={}", action,
                entry.getActorKind(), attemptId, entry.getOutcome());
        return saved;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
