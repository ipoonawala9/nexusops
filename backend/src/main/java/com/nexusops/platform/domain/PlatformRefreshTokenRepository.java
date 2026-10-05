package com.nexusops.platform.domain;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Use ONLY inside PlatformAccess. */
public interface PlatformRefreshTokenRepository extends JpaRepository<PlatformRefreshToken, UUID> {

    Optional<PlatformRefreshToken> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from PlatformRefreshToken t where t.tokenHash = :hash")
    Optional<PlatformRefreshToken> findForUpdateByTokenHash(@Param("hash") String hash);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update PlatformRefreshToken t set t.revokedAt = :now, t.revokeReason = :reason "
            + "where t.familyId = :familyId and t.revokedAt is null")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("reason") PlatformRevokeReason reason,
            @Param("now") Instant now);
}
