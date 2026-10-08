package com.nexusops.inventory.domain;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockLevelRepository extends JpaRepository<StockLevel, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from StockLevel l where l.productId = :productId and l.warehouseId = :warehouseId")
    Optional<StockLevel> lock(@Param("productId") UUID productId, @Param("warehouseId") UUID warehouseId);

    List<StockLevel> findByProductId(UUID productId);
}
