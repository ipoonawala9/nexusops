package com.nexusops.inventory.web;

import com.nexusops.inventory.DraftOrdersCommand;
import com.nexusops.inventory.DraftOrdersResult;
import com.nexusops.inventory.ReorderRuleView;
import com.nexusops.inventory.ReorderService;
import com.nexusops.inventory.ReorderSuggestion;
import com.nexusops.inventory.web.InventoryDtos.ReorderRuleRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/api/v1/inventory")
class ReorderController {

    private final ReorderService reorder;

    ReorderController(ReorderService reorder) {
        this.reorder = reorder;
    }

    @GetMapping("/reorder-rules")
    @PreAuthorize("hasAuthority('inventory.stock.read')")
    List<ReorderRuleView> rules(@RequestParam(required = false) UUID productId,
            @RequestParam(required = false) UUID warehouseId) {
        return reorder.rules(productId, warehouseId);
    }

    @PutMapping("/reorder-rules")
    @PreAuthorize("hasAuthority('inventory.reorder.manage')")
    ReorderRuleView save(@RequestBody ReorderRuleRequest request) {
        return reorder.saveRule(request.command(), request.version());
    }

    @DeleteMapping("/reorder-rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('inventory.reorder.manage')")
    void delete(@PathVariable UUID id) {
        reorder.deleteRule(id);
    }

    @GetMapping("/reorder-suggestions")
    @PreAuthorize("hasAuthority('inventory.stock.read')")
    List<ReorderSuggestion> suggestions() {
        return reorder.suggestions();
    }

    @PostMapping("/reorder-suggestions/purchase-orders")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('inventory.reorder.manage') and hasAuthority('inventory.purchase.manage')")
    DraftOrdersResult draft(@RequestBody DraftOrdersCommand command) {
        return reorder.draftOrders(command);
    }
}
