package com.nexusops.inventory.domain;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SalesOrderLineRepository extends JpaRepository<SalesOrderLine, UUID> {

    List<SalesOrderLine> findByOrderIdOrderByLineNoAsc(UUID orderId);

    List<SalesOrderLine> findByOrderIdInOrderByLineNoAsc(Collection<UUID> orderIds);

    void deleteByOrderId(UUID orderId);
}
