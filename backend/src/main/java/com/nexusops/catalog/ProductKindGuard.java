package com.nexusops.catalog;

import java.util.UUID;

/**
 * Lets other modules refuse a change of a product's kind they depend on (Inventory: GOODS carry stock, D2). Called by
 * {@link ProductService#update} before saving, only when the kind changes; a guard refuses by throwing an ApiProblem.
 */
public interface ProductKindGuard {

    void beforeKindChange(UUID productId, ProductKind from, ProductKind to);
}
