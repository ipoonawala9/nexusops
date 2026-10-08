package com.nexusops.inventory;

import com.nexusops.shared.web.ApiProblem;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** One product that lacks available stock for an operation. */
public record Shortage(UUID productId, String sku, BigDecimal requested, BigDecimal available) {

    static ApiProblem conflict(List<Shortage> shortages) {
        return ApiProblem.conflict("Not enough stock.").withProperty("shortages", shortages);
    }
}
