package id.fayda.verification.infra.repo;

import java.util.List;
import java.util.Optional;

import id.fayda.verification.domain.entity.ThresholdProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ThresholdProfileRepository extends JpaRepository<ThresholdProfileEntity, String> {

    /** Newest active profile wins; the provider falls back to YAML when this is empty. */
    @Query("select t from ThresholdProfileEntity t where t.active = true "
            + "order by t.createdAtUtc desc, t.version desc")
    List<ThresholdProfileEntity> findActiveProfiles();

    Optional<ThresholdProfileEntity> findByVersion(String version);
}
