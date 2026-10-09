package id.fayda.verification.domain.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Maps {@code organizations} from V1__baseline.sql. */
@Entity
@Table(name = "organizations")
public class Organization {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "code", nullable = false, length = 32, unique = true)
    private String code;

    @Column(name = "display_name", nullable = false, length = 160)
    private String displayName;

    @Column(name = "status", nullable = false, length = 16)
    private String status = "ACTIVE";

    @Column(name = "created_at_utc", nullable = false)
    private LocalDateTime createdAtUtc;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
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
        // Never a data carrier in logs: identifiers only.
        return "Organization[id=" + id + ", code=" + code + "]";
    }
}
