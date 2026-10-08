package com.nexusops.inventory;

import com.nexusops.catalog.ProductBrief;
import com.nexusops.catalog.ProductKind;
import com.nexusops.catalog.ProductService;
import com.nexusops.shared.web.ApiProblem;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Catalog products as Inventory sees them (D2): only GOODS carry stock. */
@Component
class InventoryProducts {

    static final String UNKNOWN = "Choose a product in this workspace.";
    static final String SERVICE = "Services don't carry stock.";
    static final String ARCHIVED = "This record is archived.";

    private final ProductService products;

    InventoryProducts(ProductService products) {
        this.products = products;
    }

    ProductBrief requireStockable(UUID id, String field) {
        return requireStockable(id, field, false);
    }

    /** Unknown or other-tenant → 400 on {@code field}; a service → 400; archived (unless allowed) → 409. */
    ProductBrief requireStockable(UUID id, String field, boolean allowArchived) {
        ProductBrief product = id == null ? null : products.briefs(List.of(id)).get(id);
        if (product == null) {
            throw ApiProblem.badRequestField(field, UNKNOWN);
        }
        if (product.kind() != ProductKind.GOODS) {
            throw ApiProblem.badRequestField(field, SERVICE);
        }
        if (product.archived() && !allowArchived) {
            throw ApiProblem.conflict(ARCHIVED);
        }
        return product;
    }

    Map<UUID, ProductBrief> briefs(Collection<UUID> ids) {
        return products.briefs(ids);
    }

    static ProductRef ref(ProductBrief p) {
        return p == null ? null : new ProductRef(p.id(), p.sku(), p.name(), p.unit());
    }
}
