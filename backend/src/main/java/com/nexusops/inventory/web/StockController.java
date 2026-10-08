package com.nexusops.inventory.web;

import com.nexusops.inventory.InventoryOverview;
import com.nexusops.inventory.MovementKind;
import com.nexusops.inventory.MovementQuery;
import com.nexusops.inventory.MovementView;
import com.nexusops.inventory.ProductStock;
import com.nexusops.inventory.StockQuery;
import com.nexusops.inventory.StockRow;
import com.nexusops.inventory.StockService;
import com.nexusops.inventory.web.InventoryDtos.AdjustRequest;
import com.nexusops.inventory.web.InventoryDtos.TransferRequest;
import com.nexusops.shared.web.PageResponse;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/inventory")
class StockController {

    private final StockService stock;

    StockController(StockService stock) {
        this.stock = stock;
    }

    @GetMapping("/stock")
    @PreAuthorize("hasAuthority('inventory.stock.read')")
    PageResponse<StockRow> stock(@RequestParam(required = false) String q,
            @RequestParam(required = false) UUID warehouseId, @RequestParam(defaultValue = "false") boolean belowMin,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return stock.list(new StockQuery(q, warehouseId, belowMin), page, size);
    }

    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('inventory.stock.read')")
    InventoryOverview overview() {
        return stock.overview();
    }

    @GetMapping("/stock/products/{productId}")
    @PreAuthorize("hasAuthority('inventory.stock.read')")
    ProductStock productStock(@PathVariable UUID productId) {
        return stock.productStock(productId);
    }

    @GetMapping("/movements")
    @PreAuthorize("hasAuthority('inventory.stock.read')")
    PageResponse<MovementView> movements(@RequestParam(required = false) UUID productId,
            @RequestParam(required = false) UUID warehouseId, @RequestParam(required = false) MovementKind kind,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return stock.movements(new MovementQuery(productId, warehouseId, kind), page, size);
    }

    @PostMapping("/adjustments")
    @PreAuthorize("hasAuthority('inventory.stock.adjust')")
    ProductStock adjust(@RequestBody AdjustRequest request) {
        return stock.adjust(request.command());
    }

    @PostMapping("/transfers")
    @PreAuthorize("hasAuthority('inventory.stock.adjust')")
    ProductStock transfer(@RequestBody TransferRequest request) {
        return stock.transfer(request.command());
    }
}
