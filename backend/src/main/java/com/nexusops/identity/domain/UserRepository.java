package com.nexusops.identity.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    /** Callers pass an already-normalized (lower-case, trimmed) address. */
    Optional<User> findByEmail(String email);
}
