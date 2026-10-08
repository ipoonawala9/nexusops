package com.nexusops.inventory;

import java.util.List;

public record DraftOrdersResult(List<PurchaseOrderView> orders) {}
