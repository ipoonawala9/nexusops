package com.nexusops.identity.domain;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InvitationRepository extends JpaRepository<Invitation, UUID> {

    Optional<Invitation> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invitation i where i.tokenHash = :hash")
    Optional<Invitation> findForUpdateByTokenHash(@Param("hash") String hash);

    @Query("select i from Invitation i where i.email = :email and i.acceptedAt is null and i.revokedAt is null")
    Optional<Invitation> findOpenByEmail(@Param("email") String email);

    @Query("select count(i) from Invitation i where i.acceptedAt is null and i.revokedAt is null and i.expiresAt > :now")
    long countPending(@Param("now") Instant now);

    List<Invitation> findAllByOrderByCreatedAtDesc();
}
