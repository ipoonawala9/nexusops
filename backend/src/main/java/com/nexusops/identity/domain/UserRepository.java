package com.nexusops.identity.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {

    /** Callers pass an already-normalized (lower-case, trimmed) address. */
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    long countByStatus(UserStatus status);

    @Query("select count(u) from User u join u.roleIds r where r = :roleId and u.status = :status")
    long countByRoleAndStatus(@Param("roleId") UUID roleId, @Param("status") UserStatus status);
}
