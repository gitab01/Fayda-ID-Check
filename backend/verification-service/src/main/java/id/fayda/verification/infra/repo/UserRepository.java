package id.fayda.verification.infra.repo;

import java.util.Optional;

import id.fayda.verification.domain.entity.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<UserAccount, Long> {

    Optional<UserAccount> findByNationalIdHash(String nationalIdHash);

    /** Lookup by the salted hash: the caller supplies hash + per-row salt, never the raw id. */
    Optional<UserAccount> findByNationalIdHashAndIdHashSalt(String nationalIdHash, String idHashSalt);
}
