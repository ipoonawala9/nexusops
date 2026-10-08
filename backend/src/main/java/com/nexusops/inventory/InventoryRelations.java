package com.nexusops.inventory;

import com.nexusops.collaboration.SubjectKey;
import com.nexusops.collaboration.SubjectRelations;
import com.nexusops.inventory.domain.PurchaseOrder;
import com.nexusops.inventory.domain.PurchaseOrderRepository;
import com.nexusops.inventory.domain.SalesOrder;
import com.nexusops.inventory.domain.SalesOrderRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/** A party's timeline includes its purchase orders (as supplier) and sales orders (as customer). */
@Component
class InventoryRelations implements SubjectRelations {

    private static final int LIMIT = 100;
    private static final Sort ORDER = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"));

    private final PurchaseOrderRepository purchaseOrders;
    private final SalesOrderRepository salesOrders;

    InventoryRelations(PurchaseOrderRepository purchaseOrders, SalesOrderRepository salesOrders) {
        this.purchaseOrders = purchaseOrders;
        this.salesOrders = salesOrders;
    }

    @Override
    public List<SubjectKey> related(String type, UUID id) {
        List<SubjectKey> keys = new ArrayList<>();
        if (type.equals("PARTY")) {
            Specification<PurchaseOrder> supplied = (root, cq, cb) -> cb.equal(root.get("supplierId"), id);
            purchaseOrders.findAll(supplied, PageRequest.of(0, LIMIT, ORDER))
                    .forEach(o -> keys.add(new SubjectKey(PurchaseOrderSubjects.TYPE, o.getId())));
            Specification<SalesOrder> sold = (root, cq, cb) -> cb.equal(root.get("customerId"), id);
            salesOrders.findAll(sold, PageRequest.of(0, LIMIT, ORDER))
                    .forEach(o -> keys.add(new SubjectKey(SalesOrderSubjects.TYPE, o.getId())));
        }
        return keys;
    }
}
