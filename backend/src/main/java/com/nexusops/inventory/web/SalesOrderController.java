package com.nexusops.inventory.web;

import com.nexusops.inventory.SalesOrderQuery;
import com.nexusops.inventory.SalesOrderService;
import com.nexusops.inventory.SalesOrderStatus;
import com.nexusops.inventory.SalesOrderSummary;
import com.nexusops.inventory.SalesOrderView;
import com.nexusops.inventory.web.InventoryDtos.SalesOrderRequest;
import com.nexusops.inventory.web.InventoryDtos.VersionRequest;
import com.nexusops.shared.web.PageResponse;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sales-orders")
class SalesOrderController {

    private final SalesOrderService orders;

    SalesOrderController(SalesOrderService orders) {
        this.orders = orders;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('inventory.order.read')")
    PageResponse<SalesOrderSummary> list(@RequestParam(required = false) String q,
            @RequestParam(required = false) SalesOrderStatus status, @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) UUID warehouseId, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return orders.list(new SalesOrderQuery(q, status, customerId, warehouseId), page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('inventory.order.read')")
    SalesOrderView get(@PathVariable UUID id) {
        return orders.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('inventory.order.manage')")
    SalesOrderView create(@RequestBody SalesOrderRequest request) {
        return orders.create(request.command());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('inventory.order.manage')")
    SalesOrderView update(@PathVariable UUID id, @RequestBody SalesOrderRequest request) {
        return orders.update(id, request.command(), request.version());
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasAuthority('inventory.order.manage')")
    SalesOrderView confirm(@PathVariable UUID id, @RequestBody VersionRequest request) {
        return orders.confirm(id, request.version());
    }

    @PostMapping("/{id}/fulfil")
    @PreAuthorize("hasAuthority('inventory.order.manage')")
    SalesOrderView fulfil(@PathVariable UUID id, @RequestBody VersionRequest request) {
        return orders.fulfil(id, request.version());
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('inventory.order.manage')")
    SalesOrderView cancel(@PathVariable UUID id, @RequestBody VersionRequest request) {
        return orders.cancel(id, request.version());
    }
}
