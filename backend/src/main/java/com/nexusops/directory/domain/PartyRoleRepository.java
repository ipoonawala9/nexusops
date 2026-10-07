package com.nexusops.directory.domain;

import com.nexusops.directory.PartyRoleType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PartyRoleRepository extends JpaRepository<PartyRole, UUID> {

    List<PartyRole> findByPartyIdOrderByRoleAsc(UUID partyId);

    List<PartyRole> findByPartyIdIn(Collection<UUID> partyIds);

    Optional<PartyRole> findByPartyIdAndRole(UUID partyId, PartyRoleType role);

    @Query("select count(r) > 0 from PartyRole r where lower(r.employeeNumber) = lower(:number) and r.partyId <> :partyId")
    boolean employeeNumberTaken(@Param("number") String number, @Param("partyId") UUID partyId);
}
