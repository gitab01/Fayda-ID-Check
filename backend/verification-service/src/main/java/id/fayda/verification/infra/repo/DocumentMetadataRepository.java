package id.fayda.verification.infra.repo;

import java.util.Optional;

import id.fayda.verification.domain.entity.DocumentMetadata;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentMetadataRepository extends JpaRepository<DocumentMetadata, Long> {

    Optional<DocumentMetadata> findByAttemptId(long attemptId);
}
