package id.fayda.verification.infra.repo;

import java.util.List;

import id.fayda.verification.domain.entity.AuditLogEntry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLogEntry, Long> {

    List<AuditLogEntry> findByAttemptIdOrderByIdAsc(long attemptId);

    List<AuditLogEntry> findByActionOrderByIdAsc(String action);
}
