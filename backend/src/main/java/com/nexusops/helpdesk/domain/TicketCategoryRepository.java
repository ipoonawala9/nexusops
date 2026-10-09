package com.nexusops.helpdesk.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketCategoryRepository extends JpaRepository<TicketCategory, UUID> {

    List<TicketCategory> findByArchivedAtIsNullOrderByPositionAscNameAsc();

    List<TicketCategory> findByArchivedAtIsNotNullOrderByPositionAscNameAsc();

    boolean existsByNameKeyAndIdNot(String nameKey, UUID id);

    boolean existsByNameKey(String nameKey);
}
