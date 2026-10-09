package id.fayda.verification.infra.repo;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import id.fayda.verification.domain.AttemptStatus;
import id.fayda.verification.domain.entity.VerificationAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VerificationAttemptRepository extends JpaRepository<VerificationAttempt, Long> {

    /** Feeds the expiry sweeper: non-terminal attempts whose stage deadline has passed. */
    List<VerificationAttempt> findByStatusInAndStageDeadlineUtcBefore(
            Collection<AttemptStatus> statuses, LocalDateTime cutoff);

    @Query("select coalesce(max(a.attemptNo), 0) from VerificationAttempt a where a.userId = :userId")
    int findHighestAttemptNo(@Param("userId") long userId);

    Optional<VerificationAttempt> findByIdAndUserId(long id, long userId);

    List<VerificationAttempt> findByUserIdOrderByIdDesc(long userId);
}
