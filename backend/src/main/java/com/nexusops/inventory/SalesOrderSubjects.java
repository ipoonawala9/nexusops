package com.nexusops.inventory;

import com.nexusops.collaboration.SearchHit;
import com.nexusops.collaboration.SubjectRef;
import com.nexusops.collaboration.SubjectResolver;
import com.nexusops.directory.PartyRef;
import com.nexusops.inventory.domain.SalesOrder;
import com.nexusops.inventory.domain.SalesOrderRepository;
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

/** Sales orders as collaboration subjects (type SALES_ORDER, D14). */
@Component
class SalesOrderSubjects implements SubjectResolver {

    static final String TYPE = "SALES_ORDER";

    private final SalesOrderRepository orders;
    private final OrderParties parties;

    SalesOrderSubjects(SalesOrderRepository orders, OrderParties parties) {
        this.orders = orders;
        this.parties = parties;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String readPermission() {
        return InventoryPermissions.ORDER_READ;
    }

    @Override
    public Optional<SubjectRef> find(UUID id) {
        return orders.findById(id).map(o -> new SubjectRef(TYPE, o.getId(), o.getNumber(), false));
    }

    @Override
    public Map<UUID, SubjectRef> findAll(Collection<UUID> ids) {
        return orders.findAllById(ids).stream().collect(Collectors.toMap(SalesOrder::getId,
                o -> new SubjectRef(TYPE, o.getId(), o.getNumber(), false)));
    }

    @Override
    public List<SearchHit> search(String pattern, int limit) {
        Specification<SalesOrder> spec = (root, cq, cb) -> cb.like(cb.lower(root.get("number")), pattern, '\\');
        List<SalesOrder> found = orders.findAll(spec,
                PageRequest.of(0, limit, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id")))).getContent();
        Map<UUID, PartyRef> names = parties.refs(found.stream().map(SalesOrder::getCustomerId)
                .collect(Collectors.toSet()));
        return found.stream().map(o -> new SearchHit(TYPE, o.getId(), o.getNumber(),
                names.containsKey(o.getCustomerId()) ? names.get(o.getCustomerId()).name() : null, false)).toList();
    }
}
