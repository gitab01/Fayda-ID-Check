package id.fayda.verification.infra.repo;

import id.fayda.verification.domain.entity.Organization;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationRepository extends JpaRepository<Organization, Long> {

    boolean existsByCode(String code);
}
