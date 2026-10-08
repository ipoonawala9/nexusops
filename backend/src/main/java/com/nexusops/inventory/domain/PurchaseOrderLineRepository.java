package com.nexusops.inventory.domain;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseOrderLineRepository extends JpaRepository<PurchaseOrderLine, UUID> {

    List<PurchaseOrderLine> findByOrderIdOrderByLineNoAsc(UUID orderId);

    List<PurchaseOrderLine> findByOrderIdInOrderByLineNoAsc(Collection<UUID> orderIds);

    void deleteByOrderId(UUID orderId);
}
