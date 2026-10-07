package com.nexusops.directory.domain;

import com.nexusops.directory.PartyKind;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface PartyRepository extends JpaRepository<Party, UUID>, JpaSpecificationExecutor<Party> {

    /** Callers pass a normalized (lower-case) email. */
    List<Party> findByKindAndEmail(PartyKind kind, String email);

    List<Party> findByKindAndNameKey(PartyKind kind, String nameKey);

    /** Callers pass a normalized domain. */
    List<Party> findByKindAndDomain(PartyKind kind, String domain);
}
