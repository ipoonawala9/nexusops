package com.nexusops.inventory;

import java.math.BigDecimal;
import java.util.UUID;

public record SalesLineView(UUID id, int lineNo, ProductRef product, BigDecimal quantity, BigDecimal unitPrice,
        BigDecimal lineTotal) {}
