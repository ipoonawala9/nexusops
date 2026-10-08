package com.nexusops.inventory.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ReorderRuleRepository extends JpaRepository<ReorderRule, UUID>,
        JpaSpecificationExecutor<ReorderRule> {

    Optional<ReorderRule> findByProductIdAndWarehouseId(UUID productId, UUID warehouseId);
}
