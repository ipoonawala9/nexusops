package com.nexusops.inventory.web;

import com.nexusops.inventory.PurchaseOrderQuery;
import com.nexusops.inventory.PurchaseOrderService;
import com.nexusops.inventory.PurchaseOrderStatus;
import com.nexusops.inventory.PurchaseOrderSummary;
import com.nexusops.inventory.PurchaseOrderView;
import com.nexusops.inventory.ReceiptCommand;
import com.nexusops.inventory.web.InventoryDtos.PurchaseOrderRequest;
import com.nexusops.inventory.web.InventoryDtos.ReceiptRequest;
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
@RequestMapping("/api/v1/purchase-orders")
class PurchaseOrderController {

    private final PurchaseOrderService orders;

    PurchaseOrderController(PurchaseOrderService orders) {
        this.orders = orders;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('inventory.purchase.read')")
    PageResponse<PurchaseOrderSummary> list(@RequestParam(required = false) String q,
            @RequestParam(required = false) PurchaseOrderStatus status, @RequestParam(required = false) UUID supplierId,
            @RequestParam(required = false) UUID warehouseId, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return orders.list(new PurchaseOrderQuery(q, status, supplierId, warehouseId), page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('inventory.purchase.read')")
    PurchaseOrderView get(@PathVariable UUID id) {
        return orders.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('inventory.purchase.manage')")
    PurchaseOrderView create(@RequestBody PurchaseOrderRequest request) {
        return orders.create(request.command());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('inventory.purchase.manage')")
    PurchaseOrderView update(@PathVariable UUID id, @RequestBody PurchaseOrderRequest request) {
        return orders.update(id, request.command(), request.version());
    }

    @PostMapping("/{id}/order")
    @PreAuthorize("hasAuthority('inventory.purchase.manage')")
    PurchaseOrderView order(@PathVariable UUID id, @RequestBody VersionRequest request) {
        return orders.order(id, request.version());
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('inventory.purchase.manage')")
    PurchaseOrderView cancel(@PathVariable UUID id, @RequestBody VersionRequest request) {
        return orders.cancel(id, request.version());
    }

    @PostMapping("/{id}/receipts")
    @PreAuthorize("hasAuthority('inventory.purchase.manage')")
    PurchaseOrderView receive(@PathVariable UUID id, @RequestBody ReceiptRequest request) {
        return orders.receive(id, new ReceiptCommand(request.lines()), request.version());
    }
}
