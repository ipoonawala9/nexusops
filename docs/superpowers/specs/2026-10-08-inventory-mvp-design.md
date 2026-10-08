# Design Spec — NexusOps Inventory MVP (Phase 6)

- **Status:** Accepted (product decisions taken on the owner's standing instruction to proceed without re-asking)
- **Date:** 2026-10-08
- **Source:** blueprint §2.3 (deeper inventory problem: descriptive counts instead of decision support), §15 (Inventory
  entities), §16 (Sales → Inventory flow), §5.4 (rules first, then ML, human approval), Phase 6 in the roadmap. The
  conventions of the platform, canonical data model and CRM specs all apply (isolation, audit, errors, paging,
  full-replacement edits with `version`, archived-never-deleted records, module-gated permissions, per-currency totals).

## 1. Intent

### Outcome
A small business can see how much of each product it holds in each warehouse, and why that number changed. It can
receive stock from suppliers through purchase orders, reserve and ship stock against sales orders, move stock between
warehouses, and correct counts. It gets **explained reorder suggestions** instead of bare numbers — "12 available, 0 on
order, below the minimum of 20; 38 used in the last 30 days (about 9 days of cover); order 48 to reach 60" — that turn
into draft purchase orders with one click, with a human approving every order.

### Success criteria (blueprint Phase 6 exit)
1. Warehouses, stock levels, an append-only stock ledger, adjustments, transfers, suppliers, purchase orders (with
   partial receipts), sales orders (reserve and fulfil) and reorder rules work end to end (database, API, UI), tenant
   isolated at both layers like every earlier table.
2. **Stock can never go negative and the books always balance:** every change to a stock level is one ledger row in
   the same transaction, on-hand never drops below zero, reserved never exceeds on-hand, and concurrent operations on the
   same item serialize.
3. **Reorder suggestions are explainable:** each shows available, on order, the rule, recent usage, days of cover and
   the suggested quantity, computed by a deterministic rule (blueprint §5.4: rules first).
4. Everything Inventory switches off with the module: every new permission belongs to module `INVENTORY`.
5. Every write is audited and permission-gated.

### Non-goals (deferred)
- Demand forecasting, stockout prediction, ABC classification, anomaly detection: Phase 11–12 (Insights, AI). The
  30-day usage figure is the statistical baseline they will improve on.
- Stock valuation (FIFO/average cost), landed costs, invoices, payments, tax, price lists: later finance work.
- Lots, serial numbers, expiry dates, bins/locations inside a warehouse, units of measure conversion.
- Partial fulfilment and back-orders on sales orders, returns: later. A sales order is fulfilled in full.
- Workflow automation ("deal won → create order"): Phase 9. Domain events on Kafka: Phase 10.
- Supplier price lists and lead-time tracking: later (lead time is a free-text expectation on the PO for now).

## 2. Approach

Three ways to hold stock were considered:

| Option | Verdict |
|---|---|
| **A. Append-only ledger + a stock-level row per product and warehouse, updated in the same transaction** | **Chosen.** The ledger explains every number; the level row makes reads cheap and lets the database enforce `on_hand ≥ 0` and `reserved ≤ on_hand` with CHECKs; a row lock on the level serializes concurrent changes. |
| B. Ledger only, levels computed by summing | Correct but slow as history grows; constraints like "never negative" can't be enforced by the database. |
| C. A quantity column only | No history: the blueprint's whole point (explain the number) is lost. |

## 3. Decisions

| # | Topic | Decision |
|---|---|---|
| D1 | Module | New Modulith module `inventory`. Depends on `catalog` (product lookups), `directory` (supplier and customer parties, `ensureRole`), `collaboration` (SPIs), `identity` (`Members`), `tenancy`, `audit`, `shared`. Nothing depends on `inventory`. |
| D2 | What is stocked | Only catalog products of kind GOODS. Services are refused with `400 "Services don't carry stock."` on the `productId` field. Archived products keep their stock and history but can't go on new orders, adjustments or transfers (`409 "This record is archived."`). |
| D3 | Quantities | `numeric(19,4)`, at most 4 decimals and 15 integer digits; order and movement quantities are > 0; counts are ≥ 0. Displayed with the product's unit. |
| D4 | Warehouses | Code (1–20, letters, digits, `-`/`_`, unique ignoring case, never reused), name (1–100), optional address (≤ 500). Archive and restore; a warehouse can be archived only when it holds no stock and no open order uses it (`409`). At least one active warehouse must remain. Every workspace starts with warehouse `MAIN` "Main warehouse", seeded by migration for existing workspaces and on `WorkspaceRegistered` for new ones. |
| D5 | Stock levels and ledger | `stock_levels(product, warehouse, on_hand, reserved, version)` with CHECKs `on_hand ≥ 0`, `reserved ≥ 0`, `reserved ≤ on_hand`; available = on_hand − reserved. `stock_movements` is append-only (app role has SELECT/INSERT only): product, warehouse, `kind` (RECEIPT, ISSUE, ADJUSTMENT, TRANSFER_OUT, TRANSFER_IN), signed `quantity`, `on_hand_after`, reference type and id (PURCHASE_ORDER, SALES_ORDER, TRANSFER, ADJUSTMENT), optional reason/note, actor, `occurred_at`. Reservations change `reserved` without a ledger row (they aren't stock movements); they are audited with the order. |
| D6 | Concurrency | Every operation locks the level rows it touches (`SELECT … FOR UPDATE`, creating missing rows first with `INSERT … ON CONFLICT DO NOTHING`) in a fixed order (product id, then warehouse id), so multi-line operations can't deadlock. Shortages are checked after locking. |
| D7 | Adjustments | A stock count: `POST inventory/adjustments {productId, warehouseId, countedQuantity, reason}` (reason 1–200 required). Sets on_hand to the count and writes one ADJUSTMENT movement with the difference (no movement when equal). The count can't be below the reserved quantity (`409 "Reserved stock can't be counted away. Release the reservations first."`). |
| D8 | Transfers | `POST inventory/transfers {productId, fromWarehouseId, toWarehouseId, quantity, note?}`: moves available stock; TRANSFER_OUT and TRANSFER_IN movements sharing a transfer id. Insufficient available stock → `409` with `shortages`. Same warehouse → `400`. |
| D9 | Purchase orders | Number `PO-00001` from a per-tenant sequence. Supplier (a non-archived party; gets an active SUPPLIER role via `ensureRole`), destination warehouse, currency (workspace default), expected date, notes, lines (GOODS product, quantity > 0, unit cost ≥ 0, ≤ 100 lines, a product at most once). Status DRAFT → ORDERED → PARTIALLY_RECEIVED → RECEIVED; DRAFT/ORDERED → CANCELLED. Lines and header are editable only in DRAFT (full `PUT`). Receipts: `POST purchase-orders/{id}/receipts {lines:[{lineId, quantity}]}` on ORDERED or PARTIALLY_RECEIVED; quantity ≤ remaining; RECEIPT movements; status follows. |
| D10 | Sales orders | Number `SO-00001`. Customer (non-archived party; gets an active CUSTOMER role), source warehouse, currency, notes, lines (GOODS product, quantity > 0, unit price ≥ 0 defaulting to the product's list price when it is in the order's currency, ≤ 100 lines, a product at most once). Status DRAFT → CONFIRMED (reserves every line; any shortage → `409` with `shortages: [{productId, sku, requested, available}]` and nothing reserved) → FULFILLED (issues every line: on_hand and reserved both drop; ISSUE movements); DRAFT/CONFIRMED → CANCELLED (releases reservations). Editable only in DRAFT. |
| D11 | Reorder rules | One per product and warehouse: `minQuantity` ≥ 0, `maxQuantity` > min, optional preferred supplier (a party). A product is **below minimum** when available + on order < min, where on order = remaining quantity on ORDERED/PARTIALLY_RECEIVED purchase orders to that warehouse. |
| D12 | Reorder suggestions | `GET inventory/reorder-suggestions`: every rule below minimum with: product, warehouse, available, on order, min, max, supplier, **usage in the last 30 days** (sum of ISSUE quantities there), average daily usage, **days of cover** (available ÷ daily usage, null when no usage) and **suggested quantity** = max − (available + on order). `POST inventory/reorder-suggestions/purchase-orders {items:[{productId, warehouseId, quantity}]}` creates one DRAFT purchase order per (supplier, warehouse) group from the rules' preferred suppliers (items without a supplier → `400`), with unit cost from the product's last received cost, else 0. A human reviews and orders each draft. |
| D13 | Overview | `GET inventory/overview`: counts of products below minimum, purchase orders awaiting receipt (ORDERED, PARTIALLY_RECEIVED), sales orders awaiting fulfilment (CONFIRMED), and the 10 most recent movements. |
| D14 | Subjects and relations | PURCHASE_ORDER (`inventory.purchase.read`) and SALES_ORDER (`inventory.order.read`) are collaboration subjects (notes, tasks, documents) and searchable by number and party name. `SubjectRelations`: a PARTY's timeline includes its purchase and sales orders' activities. |
| D15 | Directory | `PartyService.ensureRole(partyId, role)` generalizes Phase 5's `ensureCustomer` (which stays as a delegate): idempotent, no user permission, skips archived parties, audits `PartyRoleChanged` when it changes something. |
| D16 | Permissions | Module `INVENTORY`: `inventory.stock.read` (warehouses, levels, movements, suggestions, overview), `inventory.stock.adjust` (kept: adjustments, transfers), `inventory.warehouse.manage`, `inventory.purchase.read`, `inventory.purchase.manage` (create, edit, order, cancel, receive), `inventory.order.read`, `inventory.order.manage` (create, edit, confirm, fulfil, cancel), `inventory.reorder.manage` (rules; creating drafts from suggestions also needs `inventory.purchase.manage`). The unused seed `inventory.product.read` is removed (products are catalog). System roles get the new codes by migration. |
| D17 | UI | Inventory nav entry (replacing "coming in Phase 6") with tabs: Overview, Stock, Purchase orders, Sales orders, Reorder. Stock: levels per product and warehouse with filters (warehouse, below minimum, search), adjust and transfer dialogs, movement history per product. Purchase and sales order lists, an order editor (lines), detail pages with status actions, receipt dialog. Reorder: suggestions with their explanation, select and "Create purchase orders"; rules editor. Settings → Warehouses. The product page gets a Stock panel (per warehouse, with recent movements) when Inventory is on. Customer 360 and the directory page list a party's orders. |

## 4. Data model (Flyway V18–V22)

All tables are tenant-owned with `ENABLE`/`FORCE ROW LEVEL SECURITY` and the standard `tenant_isolation` policy in
`USING` and `WITH CHECK`, created in the same migration, `UNIQUE (tenant_id, id)` for composite FKs.

| Migration | Contents |
|---|---|
| V18 inventory base | Permission changes (D16); `number_sequences(tenant_id, kind, next_value)` PK (tenant_id, kind); `warehouses(id, tenant_id, code, code_key, name, address, archived_at, …)` UNIQUE (tenant_id, code_key); `MAIN` warehouse for existing tenants. |
| V19 stock | `stock_levels(id, tenant_id, product_id, warehouse_id, on_hand, reserved, updated_at, version)` UNIQUE (tenant_id, product_id, warehouse_id), CHECKs (D5), composite FKs to products and warehouses; `stock_movements(id, tenant_id, product_id, warehouse_id, kind, quantity, on_hand_after, reference_type, reference_id, reason, actor_id, occurred_at)` — app role SELECT/INSERT only; indexes (tenant, product, warehouse, occurred_at), (tenant, reference_type, reference_id). |
| V20 purchase orders | `purchase_orders(id, tenant_id, number, supplier_id, warehouse_id, status, currency, expected_on, notes, ordered_at, received_at, cancelled_at, created_by, …, version)` UNIQUE (tenant_id, number); `purchase_order_lines(id, tenant_id, order_id, line_no, product_id, quantity, received_quantity, unit_cost)` UNIQUE (tenant_id, order_id, product_id), CHECK received ≤ quantity. |
| V21 sales orders | `sales_orders(…, customer_id, warehouse_id, status, currency, notes, confirmed_at, fulfilled_at, cancelled_at, …)`; `sales_order_lines(id, tenant_id, order_id, line_no, product_id, quantity, unit_price)`. |
| V22 reorder rules | `reorder_rules(id, tenant_id, product_id, warehouse_id, min_quantity, max_quantity, supplier_id, …, version)` UNIQUE (tenant_id, product_id, warehouse_id), CHECK max > min ≥ 0. |

## 5. API (`/api/v1`)

| Route | Authorization |
|---|---|
| `GET inventory/warehouses?archived=` | `inventory.stock.read` |
| `POST inventory/warehouses`, `PUT …/{id}`, `POST …/{id}/archive\|restore` | `inventory.warehouse.manage` |
| `GET inventory/stock?q=&warehouseId=&belowMin=&page=&size=` (rows per product × warehouse with rule figures), `GET inventory/stock/products/{productId}` (levels per warehouse) | `inventory.stock.read` |
| `GET inventory/movements?productId=&warehouseId=&kind=&page=&size=` | `inventory.stock.read` |
| `POST inventory/adjustments`, `POST inventory/transfers` | `inventory.stock.adjust` |
| `GET purchase-orders?q=&status=&supplierId=&warehouseId=&page=&size=`, `GET purchase-orders/{id}` | `inventory.purchase.read` |
| `POST purchase-orders`, `PUT purchase-orders/{id}`, `POST …/{id}/order\|cancel`, `POST …/{id}/receipts` | `inventory.purchase.manage` (+ `directory.party.read` to reference a supplier) |
| `GET sales-orders?q=&status=&customerId=&warehouseId=&page=&size=`, `GET sales-orders/{id}` | `inventory.order.read` |
| `POST sales-orders`, `PUT sales-orders/{id}`, `POST …/{id}/confirm\|fulfil\|cancel` | `inventory.order.manage` (+ `directory.party.read`) |
| `GET inventory/reorder-rules?productId=&warehouseId=`, `GET inventory/reorder-suggestions` | `inventory.stock.read` |
| `PUT inventory/reorder-rules` `{productId, warehouseId, minQuantity, maxQuantity, supplierId?, version?}` (upsert), `DELETE inventory/reorder-rules/{id}` | `inventory.reorder.manage` |
| `POST inventory/reorder-suggestions/purchase-orders` | `inventory.reorder.manage` and `inventory.purchase.manage` |
| `GET inventory/overview` | `inventory.stock.read` |

Errors follow the established order. Unknown or other-tenant product, warehouse, party or line ids in bodies are 400
field errors; path ids are 404. Shortage conflicts extend the problem body with `shortages`.

## 6. Security and isolation

- The 9 new tables join `RlsCoverageIT.EXPECTED_TENANT_TABLES`; a raw-JDBC test proves tenant A sees none of B's rows,
  an unbound connection sees none, cross-tenant inserts are rejected, and `stock_movements` can't be updated or deleted
  by the app role.
- `CrossTenantApiIT` covers every new id-bearing route; the module-gate test is extended to every Inventory handler.
- Audit actions: `WarehouseCreated`, `WarehouseUpdated`, `WarehouseArchived`, `WarehouseRestored`, `StockAdjusted`,
  `StockTransferred`, `PurchaseOrderCreated`, `PurchaseOrderUpdated`, `PurchaseOrderOrdered`, `PurchaseOrderReceived`,
  `PurchaseOrderCancelled`, `SalesOrderCreated`, `SalesOrderUpdated`, `SalesOrderConfirmed`, `SalesOrderFulfilled`,
  `SalesOrderCancelled`, `ReorderRuleSaved`, `ReorderRuleDeleted`, `PartyRoleChanged` (via `ensureRole`).

## 7. Testing

- **Unit:** quantity validation; suggestion arithmetic (min/max, on order, days of cover with and without usage);
  status transition tables for both order types; lock ordering.
- **Integration:** every route's happy path and 400/403/404/409 branches; receipts (partial, over-receipt refused);
  confirm with a shortage reserves nothing; fulfil issues and releases; cancel releases; adjustment below reserved
  refused; transfer shortage; **two concurrent confirms competing for the last units: exactly one succeeds and stock
  never goes negative**; ledger balance (sum of movements equals on_hand for every level); default warehouse for a new
  signup; module-off 403s.
- **Isolation:** RLS coverage, raw-JDBC isolation, cross-tenant API suite, handler-level permission gate.
- **Frontend:** Vitest page tests against `fakeServer`.
- **E2E:** enable Inventory → count opening stock → set a reorder rule with a supplier → sell and fulfil below the
  minimum → the suggestion explains itself → create the draft purchase order from it → order it → receive it in two
  parts → stock and ledger show every step.
