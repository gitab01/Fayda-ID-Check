package id.fayda.verification.domain.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Maps {@code document_metadata}. Facts and a salted hash only — the raw ID number and the
 * document image never reach this row (see the retention test).
 */
@Entity
@Table(name = "document_metadata")
public class DocumentMetadata {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "attempt_id", nullable = false, unique = true)
    private Long attemptId;

    @Column(name = "document_type", nullable = false, length = 32)
    private String documentType;

    /** SHA-256(salt || pepper || id_number); the number itself is not persisted. */
    @Column(name = "id_number_hash", nullable = false, length = 64)
    private String idNumberHash;

    @Column(name = "hash_salt", nullable = false, length = 32)
    private String hashSalt;

    @Column(name = "issuing_region", length = 64)
    private String issuingRegion;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(name = "mrz_valid", nullable = false)
    private boolean mrzValid;

    @Column(name = "checksum_valid", nullable = false)
    private boolean checksumValid;

    @Column(name = "ocr_confidence", precision = 5, scale = 4)
    private BigDecimal ocrConfidence;

    @Column(name = "captured_at_utc", nullable = false)
    private LocalDateTime capturedAtUtc;

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

    public String getDocumentType() {
        return documentType;
    }

    public void setDocumentType(String documentType) {
        this.documentType = documentType;
    }

    public String getIdNumberHash() {
        return idNumberHash;
    }

    public void setIdNumberHash(String idNumberHash) {
        this.idNumberHash = idNumberHash;
    }

    public String getHashSalt() {
        return hashSalt;
    }

    public void setHashSalt(String hashSalt) {
        this.hashSalt = hashSalt;
    }

    public String getIssuingRegion() {
        return issuingRegion;
    }

    public void setIssuingRegion(String issuingRegion) {
        this.issuingRegion = issuingRegion;
    }

    public LocalDate getExpiryDate() {
        return expiryDate;
    }

    public void setExpiryDate(LocalDate expiryDate) {
        this.expiryDate = expiryDate;
    }

    public boolean isMrzValid() {
        return mrzValid;
    }

    public void setMrzValid(boolean mrzValid) {
        this.mrzValid = mrzValid;
    }

    public boolean isChecksumValid() {
        return checksumValid;
    }

    public void setChecksumValid(boolean checksumValid) {
        this.checksumValid = checksumValid;
    }

    public BigDecimal getOcrConfidence() {
        return ocrConfidence;
    }

    public void setOcrConfidence(BigDecimal ocrConfidence) {
        this.ocrConfidence = ocrConfidence;
    }

    public LocalDateTime getCapturedAtUtc() {
        return capturedAtUtc;
    }

    public void setCapturedAtUtc(LocalDateTime capturedAtUtc) {
        this.capturedAtUtc = capturedAtUtc;
    }

    @Override
    public String toString() {
        return "DocumentMetadata[attemptId=" + attemptId + ", documentType=" + documentType
                + ", mrzValid=" + mrzValid + ", checksumValid=" + checksumValid + "]";
    }
}
