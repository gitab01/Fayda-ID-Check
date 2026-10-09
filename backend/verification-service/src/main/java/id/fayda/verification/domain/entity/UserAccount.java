package id.fayda.verification.domain.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/**
 * Maps {@code users}. The national ID number is never a column: only
 * {@code national_id_hash} = SHA-256(salt || pepper || id_number) and the per-row random salt.
 *
 * <p>{@code id} is the identity-registry id that arrives in the JWT {@code sub}, so it is
 * assigned by the caller and never generated here — this service does not mint identities.
 * That also means Spring Data cannot infer "new" from a null id: {@link #isNew()} reads the
 * explicit flag the creating path sets, otherwise {@code save()} would merge and issue an
 * UPDATE against a row that does not exist.</p>
 */
@Entity
@Table(name = "users")
public class UserAccount implements Persistable<Long> {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "national_id_hash", length = 64)
    private String nationalIdHash;

    @Column(name = "id_hash_salt", length = 32)
    private String idHashSalt;

    @Column(name = "full_name", nullable = false, length = 160)
    private String fullName;

    @Column(name = "phone", length = 32)
    private String phone;

    @Column(name = "org_id")
    private Long orgId;

    @Column(name = "role", nullable = false, length = 24)
    private String role = "SUBJECT";

    @Column(name = "status", nullable = false, length = 24)
    private String status = "PENDING";

    @Column(name = "created_at_utc", nullable = false)
    private LocalDateTime createdAtUtc;

    /** Set only by the path that inserts this row; not a column. */
    @Transient
    private boolean newPlaceholder;

    @Override
    public boolean isNew() {
        return newPlaceholder;
    }

    public UserAccount asNewPlaceholder() {
        this.newPlaceholder = true;
        return this;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getNationalIdHash() {
        return nationalIdHash;
    }

    public void setNationalIdHash(String nationalIdHash) {
        this.nationalIdHash = nationalIdHash;
    }

    public String getIdHashSalt() {
        return idHashSalt;
    }

    public void setIdHashSalt(String idHashSalt) {
        this.idHashSalt = idHashSalt;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public Long getOrgId() {
        return orgId;
    }

    public void setOrgId(Long orgId) {
        this.orgId = orgId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAtUtc() {
        return createdAtUtc;
    }

    public void setCreatedAtUtc(LocalDateTime createdAtUtc) {
        this.createdAtUtc = createdAtUtc;
    }

    @Override
    public String toString() {
        // The hash is already a one-way value; the raw id number is not held here at all.
        return "UserAccount[id=" + id + ", role=" + role + ", status=" + status + "]";
    }
}
