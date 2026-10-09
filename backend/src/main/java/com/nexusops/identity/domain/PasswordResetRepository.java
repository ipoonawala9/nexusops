package com.nexusops.identity.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PasswordResetRepository extends JpaRepository<PasswordReset, UUID> {

    Optional<PasswordReset> findByTokenHash(String tokenHash);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update PasswordReset r set r.usedAt = :now where r.userId = :userId and r.usedAt is null")
    int invalidateOpenForUser(@Param("userId") UUID userId, @Param("now") Instant now);

    /** Atomically claims a still-usable link: of two concurrent redemptions only one sees a count of 1. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update PasswordReset r set r.usedAt = :now where r.id = :id and r.usedAt is null and r.expiresAt > :now")
    int consume(@Param("id") UUID id, @Param("now") Instant now);
}
