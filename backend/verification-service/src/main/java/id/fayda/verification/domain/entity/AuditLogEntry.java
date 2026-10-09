package id.fayda.verification.domain.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Maps {@code audit_log}. Free-text detail is stored as {@code detail_hash} only, so the row
 * is informative to an auditor without disclosing what the text said.
 */
@Entity
@Table(name = "audit_log")
public class AuditLogEntry {

    public static final String ACTION_ATTEMPT_STARTED = "ATTEMPT_STARTED";
    public static final String ACTION_PAYLOAD_RECEIVED = "PAYLOAD_RECEIVED";
    public static final String ACTION_INFERENCE_CALLED = "INFERENCE_CALLED";
    public static final String ACTION_DECISION_MADE = "DECISION_MADE";
    public static final String ACTION_RECORD_READ = "RECORD_READ";
    public static final String ACTION_ATTEMPT_RETRIED = "ATTEMPT_RETRIED";
    public static final String ACTION_ATTEMPT_EXPIRED = "ATTEMPT_EXPIRED";

    public static final String OUTCOME_ACCEPTED = "ACCEPTED";
    public static final String OUTCOME_REJECTED = "REJECTED";
    public static final String OUTCOME_UNAVAILABLE = "UNAVAILABLE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "actor_id")
    private Long actorId;

    /** SUBJECT | REVIEWER | SYSTEM */
    @Column(name = "actor_kind", nullable = false, length = 16)
    private String actorKind;

    @Column(name = "action", nullable = false, length = 48)
    private String action;

    @Column(name = "attempt_id")
    private Long attemptId;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "outcome", nullable = false, length = 24)
    private String outcome;

    @Column(name = "detail_hash", length = 64)
    private String detailHash;

    @Column(name = "created_at_utc", nullable = false)
    private LocalDateTime createdAtUtc;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getActorId() {
        return actorId;
    }

    public void setActorId(Long actorId) {
        this.actorId = actorId;
    }

    public String getActorKind() {
        return actorKind;
    }

    public void setActorKind(String actorKind) {
        this.actorKind = actorKind;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public Long getAttemptId() {
        return attemptId;
    }

    public void setAttemptId(Long attemptId) {
        this.attemptId = attemptId;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public String getDetailHash() {
        return detailHash;
    }

    public void setDetailHash(String detailHash) {
        this.detailHash = detailHash;
    }

    public LocalDateTime getCreatedAtUtc() {
        return createdAtUtc;
    }

    public void setCreatedAtUtc(LocalDateTime createdAtUtc) {
        this.createdAtUtc = createdAtUtc;
    }

    @Override
    public String toString() {
        return "AuditLogEntry[action=" + action + ", actorKind=" + actorKind
                + ", attemptId=" + attemptId + ", outcome=" + outcome + "]";
    }
}
