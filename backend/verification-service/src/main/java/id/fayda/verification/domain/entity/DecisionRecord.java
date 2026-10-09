package id.fayda.verification.domain.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import id.fayda.verification.domain.Decision;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Maps {@code decision_records} — 1:1 with an attempt (attempt_id is UNIQUE). */
@Entity
@Table(name = "decision_records")
public class DecisionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "attempt_id", nullable = false, unique = true)
    private Long attemptId;

    /** Attack probability, 0..1. */
    @Column(name = "liveness_score", nullable = false, precision = 6, scale = 5)
    private BigDecimal livenessScore;

    @Column(name = "match_score", nullable = false, precision = 6, scale = 5)
    private BigDecimal matchScore;

    @Column(name = "doc_validation_score", nullable = false, precision = 6, scale = 5)
    private BigDecimal docValidationScore;

    @Column(name = "composite_score", nullable = false, precision = 6, scale = 5)
    private BigDecimal compositeScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, length = 8)
    private Decision decision;

    @Column(name = "reason_code", nullable = false, length = 48)
    private String reasonCode;

    @Column(name = "guidance_text", length = 256)
    private String guidanceText;

    /** Which threshold profile produced this outcome. */
    @Column(name = "threshold_version", nullable = false, length = 24)
    private String thresholdVersion;

    @Column(name = "liveness_model_ver", nullable = false, length = 32)
    private String livenessModelVer;

    @Column(name = "embedding_model_ver", nullable = false, length = 32)
    private String embeddingModelVer;

    @Column(name = "decided_at_utc", nullable = false)
    private LocalDateTime decidedAtUtc;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getAttemptId() {
        return attemptId;
    }

    public void setAttemptId(Long attemptId) {
        this.attemptId = attemptId;
    }

    public BigDecimal getLivenessScore() {
        return livenessScore;
    }

    public void setLivenessScore(BigDecimal livenessScore) {
        this.livenessScore = livenessScore;
    }

    public BigDecimal getMatchScore() {
        return matchScore;
    }

    public void setMatchScore(BigDecimal matchScore) {
        this.matchScore = matchScore;
    }

    public BigDecimal getDocValidationScore() {
        return docValidationScore;
    }

    public void setDocValidationScore(BigDecimal docValidationScore) {
        this.docValidationScore = docValidationScore;
    }

    public BigDecimal getCompositeScore() {
        return compositeScore;
    }

    public void setCompositeScore(BigDecimal compositeScore) {
        this.compositeScore = compositeScore;
    }

    public Decision getDecision() {
        return decision;
    }

    public void setDecision(Decision decision) {
        this.decision = decision;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(String reasonCode) {
        this.reasonCode = reasonCode;
    }

    public String getGuidanceText() {
        return guidanceText;
    }

    public void setGuidanceText(String guidanceText) {
        this.guidanceText = guidanceText;
    }

    public String getThresholdVersion() {
        return thresholdVersion;
    }

    public void setThresholdVersion(String thresholdVersion) {
        this.thresholdVersion = thresholdVersion;
    }

    public String getLivenessModelVer() {
        return livenessModelVer;
    }

    public void setLivenessModelVer(String livenessModelVer) {
        this.livenessModelVer = livenessModelVer;
    }

    public String getEmbeddingModelVer() {
        return embeddingModelVer;
    }

    public void setEmbeddingModelVer(String embeddingModelVer) {
        this.embeddingModelVer = embeddingModelVer;
    }

    public LocalDateTime getDecidedAtUtc() {
        return decidedAtUtc;
    }

    public void setDecidedAtUtc(LocalDateTime decidedAtUtc) {
        this.decidedAtUtc = decidedAtUtc;
    }

    @Override
    public String toString() {
        return "DecisionRecord[attemptId=" + attemptId + ", decision=" + decision
                + ", reasonCode=" + reasonCode + "]";
    }
}
