package id.fayda.verification.domain.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Maps {@code threshold_profiles}; the primary key is the version string. */
@Entity
@Table(name = "threshold_profiles")
public class ThresholdProfileEntity {

    @Id
    @Column(name = "version", length = 24)
    private String version;

    @Column(name = "weight_liveness", nullable = false, precision = 5, scale = 4)
    private BigDecimal weightLiveness;

    @Column(name = "weight_match", nullable = false, precision = 5, scale = 4)
    private BigDecimal weightMatch;

    @Column(name = "weight_document", nullable = false, precision = 5, scale = 4)
    private BigDecimal weightDocument;

    @Column(name = "pass_composite", nullable = false, precision = 6, scale = 5)
    private BigDecimal passComposite;

    @Column(name = "review_composite", nullable = false, precision = 6, scale = 5)
    private BigDecimal reviewComposite;

    @Column(name = "min_liveness", nullable = false, precision = 6, scale = 5)
    private BigDecimal minLiveness;

    @Column(name = "min_match", nullable = false, precision = 6, scale = 5)
    private BigDecimal minMatch;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "calibrated_on", length = 64)
    private String calibratedOn;

    @Column(name = "created_at_utc", nullable = false)
    private LocalDateTime createdAtUtc;

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public BigDecimal getWeightLiveness() {
        return weightLiveness;
    }

    public void setWeightLiveness(BigDecimal weightLiveness) {
        this.weightLiveness = weightLiveness;
    }

    public BigDecimal getWeightMatch() {
        return weightMatch;
    }

    public void setWeightMatch(BigDecimal weightMatch) {
        this.weightMatch = weightMatch;
    }

    public BigDecimal getWeightDocument() {
        return weightDocument;
    }

    public void setWeightDocument(BigDecimal weightDocument) {
        this.weightDocument = weightDocument;
    }

    public BigDecimal getPassComposite() {
        return passComposite;
    }

    public void setPassComposite(BigDecimal passComposite) {
        this.passComposite = passComposite;
    }

    public BigDecimal getReviewComposite() {
        return reviewComposite;
    }

    public void setReviewComposite(BigDecimal reviewComposite) {
        this.reviewComposite = reviewComposite;
    }

    public BigDecimal getMinLiveness() {
        return minLiveness;
    }

    public void setMinLiveness(BigDecimal minLiveness) {
        this.minLiveness = minLiveness;
    }

    public BigDecimal getMinMatch() {
        return minMatch;
    }

    public void setMinMatch(BigDecimal minMatch) {
        this.minMatch = minMatch;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getCalibratedOn() {
        return calibratedOn;
    }

    public void setCalibratedOn(String calibratedOn) {
        this.calibratedOn = calibratedOn;
    }

    public LocalDateTime getCreatedAtUtc() {
        return createdAtUtc;
    }

    public void setCreatedAtUtc(LocalDateTime createdAtUtc) {
        this.createdAtUtc = createdAtUtc;
    }

    @Override
    public String toString() {
        return "ThresholdProfileEntity[version=" + version + ", active=" + active + "]";
    }
}
