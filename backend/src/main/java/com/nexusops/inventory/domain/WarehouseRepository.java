package com.nexusops.inventory.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WarehouseRepository extends JpaRepository<Warehouse, UUID> {

    boolean existsByCodeKey(String codeKey);

    boolean existsByCodeKeyAndIdNot(String codeKey, UUID id);

    long countByArchivedAtIsNull();

    List<Warehouse> findByArchivedAtIsNullOrderByCodeKeyAsc();

    List<Warehouse> findByArchivedAtIsNotNullOrderByCodeKeyAsc();
}
