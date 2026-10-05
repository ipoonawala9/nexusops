package com.nexusops.platform.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Use ONLY inside PlatformAccess: without app.platform_access the table is invisible (RLS). */
public interface PlatformUserRepository extends JpaRepository<PlatformUser, UUID> {

    Optional<PlatformUser> findByEmail(String email);

    /** Row lock: concurrent logins with the same TOTP code serialize, so only one can use it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from PlatformUser u where u.id = :id")
    Optional<PlatformUser> findForUpdateById(@Param("id") UUID id);
}
