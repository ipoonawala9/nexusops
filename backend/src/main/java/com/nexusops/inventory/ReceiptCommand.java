package com.nexusops.inventory;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ReceiptCommand(List<Line> lines) {

    public record Line(UUID lineId, BigDecimal quantity) {}
}
