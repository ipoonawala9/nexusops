package com.nexusops.inventory;

import com.nexusops.collaboration.SearchHit;
import com.nexusops.collaboration.SubjectRef;
import com.nexusops.collaboration.SubjectResolver;
import com.nexusops.directory.PartyRef;
import com.nexusops.inventory.domain.PurchaseOrder;
import com.nexusops.inventory.domain.PurchaseOrderRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/** Purchase orders as collaboration subjects (type PURCHASE_ORDER, D14). */
@Component
class PurchaseOrderSubjects implements SubjectResolver {

    static final String TYPE = "PURCHASE_ORDER";

    private final PurchaseOrderRepository orders;
    private final OrderParties parties;

    PurchaseOrderSubjects(PurchaseOrderRepository orders, OrderParties parties) {
        this.orders = orders;
        this.parties = parties;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String readPermission() {
        return InventoryPermissions.PURCHASE_READ;
    }

    @Override
    public Optional<SubjectRef> find(UUID id) {
        return orders.findById(id).map(o -> new SubjectRef(TYPE, o.getId(), o.getNumber(), false));
    }

    @Override
    public Map<UUID, SubjectRef> findAll(Collection<UUID> ids) {
        return orders.findAllById(ids).stream().collect(Collectors.toMap(PurchaseOrder::getId,
                o -> new SubjectRef(TYPE, o.getId(), o.getNumber(), false)));
    }

    @Override
    public List<SearchHit> search(String pattern, int limit) {
        Specification<PurchaseOrder> spec = (root, cq, cb) -> cb.like(cb.lower(root.get("number")), pattern, '\\');
        List<PurchaseOrder> found = orders.findAll(spec,
                PageRequest.of(0, limit, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id")))).getContent();
        Map<UUID, PartyRef> names = parties.refs(found.stream().map(PurchaseOrder::getSupplierId)
                .collect(Collectors.toSet()));
        return found.stream().map(o -> new SearchHit(TYPE, o.getId(), o.getNumber(),
                names.containsKey(o.getSupplierId()) ? names.get(o.getSupplierId()).name() : null, false)).toList();
    }
}
