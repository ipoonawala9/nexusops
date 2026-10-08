package com.nexusops.inventory;

/** Raw input; WarehouseService validates it. */
public record WarehouseCommand(String code, String name, String address) {}
