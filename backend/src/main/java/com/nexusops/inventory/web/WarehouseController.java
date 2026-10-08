package com.nexusops.inventory.web;

import com.nexusops.inventory.WarehouseService;
import com.nexusops.inventory.WarehouseView;
import com.nexusops.inventory.web.InventoryDtos.WarehouseRequest;
import java.util.List;
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
@RequestMapping("/api/v1/inventory/warehouses")
class WarehouseController {

    private final WarehouseService warehouses;

    WarehouseController(WarehouseService warehouses) {
        this.warehouses = warehouses;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('inventory.stock.read')")
    List<WarehouseView> list(@RequestParam(defaultValue = "false") boolean archived) {
        return warehouses.list(archived);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('inventory.warehouse.manage')")
    WarehouseView create(@RequestBody WarehouseRequest request) {
        return warehouses.create(request.command());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('inventory.warehouse.manage')")
    WarehouseView update(@PathVariable UUID id, @RequestBody WarehouseRequest request) {
        return warehouses.update(id, request.command(), request.version());
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAuthority('inventory.warehouse.manage')")
    WarehouseView archive(@PathVariable UUID id) {
        return warehouses.archive(id);
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAuthority('inventory.warehouse.manage')")
    WarehouseView restore(@PathVariable UUID id) {
        return warehouses.restore(id);
    }
}
