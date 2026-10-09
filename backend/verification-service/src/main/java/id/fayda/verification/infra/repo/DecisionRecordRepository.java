package id.fayda.verification.infra.repo;

import java.util.List;
import java.util.Optional;

import id.fayda.verification.domain.entity.DecisionRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DecisionRecordRepository extends JpaRepository<DecisionRecord, Long> {

    Optional<DecisionRecord> findByAttemptId(long attemptId);
}
