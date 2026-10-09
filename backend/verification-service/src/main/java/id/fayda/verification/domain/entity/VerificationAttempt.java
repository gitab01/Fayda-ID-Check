package id.fayda.verification.domain.entity;

import java.time.LocalDateTime;

import id.fayda.verification.domain.AttemptStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Maps {@code verification_attempts}. Holds no image bytes, by design. */
@Entity
@Table(name = "verification_attempts")
public class VerificationAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AttemptStatus status;

    /** Server-issued seed; the action sequence is re-derived from it on submit. */
    @Column(name = "challenge_seed", nullable = false, length = 64)
    private String challengeSeed;

    /** Copy of the derived sequence. Advisory only: verification re-derives from the seed. */
    @Column(name = "challenge_actions", nullable = false, length = 256)
    private String challengeActions;

    @Column(name = "device_info", length = 256)
    private String deviceInfo;

    /** SHA-256 of the per-attempt payload key id. The key itself is never persisted. */
    @Column(name = "request_key_hash", length = 64)
    private String requestKeyHash;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo = 1;

    @Column(name = "started_at_utc", nullable = false)
    private LocalDateTime startedAtUtc;

    /** Deadline for the current stage; the sweeper expires attempts that blow past it. */
    @Column(name = "stage_deadline_utc")
    private LocalDateTime stageDeadlineUtc;

    @Column(name = "completed_at_utc")
    private LocalDateTime completedAtUtc;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public AttemptStatus getStatus() {
        return status;
    }

    public void setStatus(AttemptStatus status) {
        this.status = status;
    }

    public String getChallengeSeed() {
        return challengeSeed;
    }

    public void setChallengeSeed(String challengeSeed) {
        this.challengeSeed = challengeSeed;
    }

    public String getChallengeActions() {
        return challengeActions;
    }

    public void setChallengeActions(String challengeActions) {
        this.challengeActions = challengeActions;
    }

    public String getDeviceInfo() {
        return deviceInfo;
    }

    public void setDeviceInfo(String deviceInfo) {
        this.deviceInfo = deviceInfo;
    }

    public String getRequestKeyHash() {
        return requestKeyHash;
    }

    public void setRequestKeyHash(String requestKeyHash) {
        this.requestKeyHash = requestKeyHash;
    }

    public int getAttemptNo() {
        return attemptNo;
    }

    public void setAttemptNo(int attemptNo) {
        this.attemptNo = attemptNo;
    }

    public LocalDateTime getStartedAtUtc() {
        return startedAtUtc;
    }

    public void setStartedAtUtc(LocalDateTime startedAtUtc) {
        this.startedAtUtc = startedAtUtc;
    }

    public LocalDateTime getStageDeadlineUtc() {
        return stageDeadlineUtc;
    }

    public void setStageDeadlineUtc(LocalDateTime stageDeadlineUtc) {
        this.stageDeadlineUtc = stageDeadlineUtc;
    }

    public LocalDateTime getCompletedAtUtc() {
        return completedAtUtc;
    }

    public void setCompletedAtUtc(LocalDateTime completedAtUtc) {
        this.completedAtUtc = completedAtUtc;
    }

    @Override
    public String toString() {
        return "VerificationAttempt[id=" + id + ", status=" + status + ", attemptNo=" + attemptNo + "]";
    }
}
