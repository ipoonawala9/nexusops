/**
 * Inventory (Phase 6, ADR-0011): warehouses, stock levels with an append-only ledger, counts and transfers, purchase
 * orders, sales orders and reorder rules. Stock changes only through StockLedger. Every permission belongs to module
 * INVENTORY, so the whole module switches off with it.
 */
package com.nexusops.inventory;
