# Phase 6 — Inventory MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Warehouses, stock levels with an append-only ledger, counts and transfers, purchase orders with partial receipts, sales orders that reserve and fulfil stock, and explained reorder suggestions that become draft purchase orders — all inside the Inventory module and isolated per tenant.

**Architecture:** A new Spring Modulith module `inventory` (`com.nexusops.inventory`) owns nine tenant tables (Flyway V18–V22). A `StockLedger` component is the only writer of stock: it locks `stock_levels` rows in a fixed order, changes them and appends `stock_movements` rows in the same transaction, so the database's CHECKs keep stock non-negative and reservations within on-hand. Purchase and sales orders are collaboration subjects; read models (stock list, reorder suggestions, overview) are SQL with an explicit tenant predicate. The React app gets an Inventory area, Settings → Warehouses, a Stock panel on product pages and order panels on party pages.

**Tech Stack:** Java 25, Spring Boot 4.1 (Web MVC, Security, Data JPA, Modulith), Hibernate `@TenantId`, PostgreSQL 17 RLS, Flyway, JUnit 5 + Testcontainers + MockMvc; React 19 + TypeScript + Vite, TanStack Query, React Hook Form + Zod, Tailwind/shadcn, Vitest + Testing Library, Playwright.

**Spec:** `docs/superpowers/specs/2026-10-08-inventory-mvp-design.md` (decisions D1–D17 are referenced below).

## Global Constraints

- Every new table: `tenant_id uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE`, `UNIQUE (tenant_id, id)` (except `number_sequences`, keyed by `(tenant_id, kind)`), `ENABLE` + `FORCE ROW LEVEL SECURITY`, policy `tenant_isolation` with `USING` and `WITH CHECK` `(tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)`, in the same migration as the table.
- References inside a tenant use composite FKs `(tenant_id, x_id) REFERENCES t (tenant_id, id)`; member references (`created_by`, `actor_id`) are `uuid REFERENCES users (id) ON DELETE SET NULL`.
- Every Inventory permission has `module_code = 'INVENTORY'`: `inventory.stock.read`, `inventory.stock.adjust`, `inventory.warehouse.manage`, `inventory.purchase.read`, `inventory.purchase.manage`, `inventory.order.read`, `inventory.order.manage`, `inventory.reorder.manage`. `inventory.product.read` is removed.
- Every handler has `@PreAuthorize`. Path prefix `/api/v1`. Routes live under `/api/v1/inventory/…`, `/api/v1/purchase-orders…`, `/api/v1/sales-orders…`.
- Error order: invalid body 400 → missing permission 403 → path id not found (incl. other tenant) 404 → state conflict 409. Body references to unknown or other-tenant ids are 400 field errors; list-item fields are named `lines[i].field` / `items[i].field`.
- Messages, verbatim: `"Record not found."`, `"This record is archived."`, `"This record was changed by someone else. Reload and try again."`, `"You do not have permission to perform this action."`, `"Reload the record and try again."`, `"Services don't carry stock."`, `"Not enough stock."` (409 with `shortages`).
- Quantities: `numeric(19,4)`, ≤ 4 decimals, ≤ 15 integer digits; movement and order quantities > 0; counts ≥ 0. Money as in earlier phases; one currency per order; totals never add currencies.
- Stock is changed only through `StockLedger`; every on-hand change writes exactly one `stock_movements` row in the same transaction; `stock_movements` is append-only for the app role (no UPDATE/DELETE grant).
- Level rows are locked in ascending (productId, warehouseId) order; shortages are checked after locking.
- JDBC (non-JPA) queries always add an explicit `tenant_id = :tenant` predicate on top of RLS.
- Audit actions (verbatim): `WarehouseCreated`, `WarehouseUpdated`, `WarehouseArchived`, `WarehouseRestored`, `StockAdjusted`, `StockTransferred`, `PurchaseOrderCreated`, `PurchaseOrderUpdated`, `PurchaseOrderOrdered`, `PurchaseOrderReceived`, `PurchaseOrderCancelled`, `SalesOrderCreated`, `SalesOrderUpdated`, `SalesOrderConfirmed`, `SalesOrderFulfilled`, `SalesOrderCancelled`, `ReorderRuleSaved`, `ReorderRuleDeleted`.
- Commits: conventional messages, **no `Co-Authored-By` or any AI attribution trailer** (repository owner's rule).
- Backend tests: `cd backend && ./gradlew test` (Docker running). Frontend: `cd frontend && npm run format:check && npm run lint && npm run typecheck && npm test` — CI runs `prettier --check`, so format new and changed files with Prettier.

## Review Focus

1. A purchase order received in several parts — the receipt that completes the last line moves the order to RECEIVED, and any further receipt is refused (Task 3 test `partialReceiptsCompleteTheOrderAndThenStop`).
2. A product archived after it was put on a draft order — the existing line still edits, orders, confirms and fulfils; only adding it anew is refused (Task 4 test `anArchivedProductOnAnExistingLineDoesNotBlockTheOrder`).
3. A reorder rule for a product with no usage in 30 days — the suggestion still appears, says "no usage in the last 30 days", and days of cover is null, never a division by zero (Task 5 `ReorderMathTest.noUsageMeansNoDaysOfCover`).
4. Counting or transferring stock in an archived warehouse, or transferring into one, is refused with 409 instead of silently writing stock there (Task 2 test `archivedWarehousesTakeNoStock`).
5. A stock count that equals the current on-hand writes no movement and no audit event, so the ledger doesn't fill with no-ops (Task 2 test `countingTheSameQuantityChangesNothing`).

## File Structure

```
backend/src/main/resources/db/migration/
  V18__inventory_base.sql        permissions (D16), number_sequences, warehouses + MAIN for existing workspaces
  V19__stock.sql                 products UNIQUE (tenant_id, id); stock_levels; stock_movements (append-only)
  V20__purchase_orders.sql       purchase_orders, purchase_order_lines
  V21__sales_orders.sql          sales_orders, sales_order_lines
  V22__reorder_rules.sql         reorder_rules
backend/src/main/java/com/nexusops/
  shared/Decimals.java, shared/Currencies.java          pure validation, reused by crm.Money and inventory
  crm/Money.java                                         delegates to Decimals/Currencies
  catalog/ProductBrief.java, catalog/ProductService.java (+ briefs)
  directory/PartyService.java                            ensureRole (ensureCustomer delegates)
  collaboration/SearchService.java                       ORDER gains PURCHASE_ORDER, SALES_ORDER
  inventory/package-info.java, InventoryPermissions.java, InventoryProducts.java, Quantities.java,
      ProductRef.java, WarehouseRef.java, Shortage.java, NumberSequences.java, SequenceKind.java
  inventory/WarehouseCommand.java, WarehouseView.java, WarehouseService.java, InventorySetup.java
  inventory/StockKey.java, MovementKind.java, ReferenceType.java, StockLedger.java, StockService.java,
      AdjustCommand.java, TransferCommand.java, ProductStock.java, MovementView.java, MovementQuery.java
  inventory/PurchaseOrderStatus.java, PurchaseLineCommand.java, PurchaseOrderCommand.java, ReceiptCommand.java,
      PurchaseOrderView.java, PurchaseLineView.java, PurchaseOrderSummary.java, PurchaseOrderQuery.java,
      PurchaseOrderService.java, PurchaseOrderSubjects.java
  inventory/SalesOrderStatus.java, SalesLineCommand.java, SalesOrderCommand.java, SalesOrderView.java,
      SalesLineView.java, SalesOrderSummary.java, SalesOrderQuery.java, SalesOrderService.java, SalesOrderSubjects.java
  inventory/ReorderRuleCommand.java, ReorderRuleView.java, ReorderService.java, ReorderMath.java,
      ReorderSuggestion.java, DraftOrdersCommand.java, StockRow.java, StockQuery.java, InventoryOverview.java,
      InventoryQueries.java, InventoryRelations.java
  inventory/domain/…  Warehouse, StockLevel, StockMovement, PurchaseOrder, PurchaseOrderLine, SalesOrder,
      SalesOrderLine, ReorderRule (+ repositories)
  inventory/web/InventoryDtos.java, WarehouseController.java, StockController.java, PurchaseOrderController.java,
      SalesOrderController.java, ReorderController.java
backend/src/test/java/com/nexusops/
  support/TestInventory.java
  shared/DecimalsTest.java
  inventory/WarehouseApiIT, StockApiIT, PurchaseOrderApiIT, SalesOrderApiIT, ReorderApiIT, ReorderMathTest,
      InventoryModuleGateIT
  InventoryRlsIT; RlsCoverageIT, CrossTenantApiIT, OpenApiContractIT, collaboration/SearchApiIT (modified)
docs/decisions/0011-inventory-ledger-and-orders.md; docs/api/openapi.json (re-exported)
frontend/src/
  lib/api/types.ts, features/auth/permissions.tsx, test/records.ts, features/records/SubjectLink.tsx
  features/inventory/InventoryLayout.tsx, routes.tsx, labels.ts, quantity.ts, WarehouseSelect.tsx, ProductPicker.tsx,
      OrderLinesEditor.tsx, StockPage.tsx, AdjustStockDialog.tsx, TransferStockDialog.tsx, ProductStockPanel.tsx,
      PurchaseOrdersPage.tsx, PurchaseOrderFormDialog.tsx, PurchaseOrderDetailPage.tsx, ReceiveDialog.tsx,
      SalesOrdersPage.tsx, SalesOrderFormDialog.tsx, SalesOrderDetailPage.tsx, ReorderPage.tsx, ReorderRuleDialog.tsx,
      InventoryOverviewPage.tsx, PartyOrdersPanel.tsx (+ tests)
  features/settings/WarehousesSettingsPage.tsx (+ test)
  features/products/ProductDetailPage.tsx, features/directory/PartyDetailPage.tsx, features/crm/Customer360Page.tsx
  features/shell/routes.tsx, nav.ts, ComingSoonPage.test.tsx
frontend/e2e/inventory.spec.ts; README.md
```

## Pre-flight rulings (made while writing this plan)

- Suppliers get the SUPPLIER role when a purchase order is **ordered**, and customers the CUSTOMER role when a sales order is **confirmed** — the moment of commitment — not when a draft is saved (spec D9/D10 say "gets an … role"; drafts shouldn't mark parties).
- The stock list (`GET inventory/stock`) shows product × warehouse pairs that have a stock level row or a reorder rule, for active products in active warehouses; products never stocked appear on their product page's Stock panel (all active warehouses, zeros included).
- Movements of orders carry the order number in `reason` (e.g. `PO-00001`), so the ledger reads without joins.
- `shared.Decimals`/`shared.Currencies` hold the pure validation that `crm.Money` already had; `crm.Money` delegates, so the rules can't drift between modules.
- A line's archived product stays usable on that line (edit, order, confirm, fulfil); only adding an archived product to a line is refused (409), like unchanged references elsewhere.
- Warehouse archive is refused while the warehouse holds stock or is used by a DRAFT or open order; each order task adds its own check to `WarehouseService.requireUnused`.

---
### Task 1: Inventory permissions, warehouses with a default MAIN warehouse, and shared building blocks

**Files:**
- Create: `backend/src/main/resources/db/migration/V18__inventory_base.sql`
- Create: `backend/src/main/java/com/nexusops/shared/Decimals.java`, `shared/Currencies.java`; Modify: `crm/Money.java`
- Create: `backend/src/main/java/com/nexusops/catalog/ProductBrief.java`; Modify: `catalog/ProductService.java` (+ `briefs`)
- Modify: `backend/src/main/java/com/nexusops/directory/PartyService.java` (`ensureRole`; `ensureCustomer` delegates)
- Create: `backend/src/main/java/com/nexusops/inventory/package-info.java`, `InventoryPermissions.java`, `WarehouseRef.java`, `WarehouseCommand.java`, `WarehouseView.java`, `WarehouseService.java`, `InventorySetup.java`
- Create: `backend/src/main/java/com/nexusops/inventory/domain/Warehouse.java`, `WarehouseRepository.java`
- Create: `backend/src/main/java/com/nexusops/inventory/web/InventoryDtos.java`, `WarehouseController.java`
- Create: `backend/src/test/java/com/nexusops/support/TestInventory.java`
- Test: `backend/src/test/java/com/nexusops/shared/DecimalsTest.java`, `backend/src/test/java/com/nexusops/inventory/WarehouseApiIT.java`

**Interfaces:**
- Produces:
  - `shared.Decimals`: `static BigDecimal nonNegative(BigDecimal value, String field, String negativeMessage)` (null stays null; ≥ 0, ≤ 4 decimals "Use at most 4 decimal places.", ≤ 15 integer digits "Enter a smaller amount."), `static BigDecimal positive(BigDecimal value, String field, String message)` (required; > 0 else `message`; same scale/size rules).
  - `shared.Currencies.parse(String raw, String field)`: null for blank, else upper-case ISO-4217 code, else 400 field error "Use a 3-letter currency code like USD.".
  - `catalog.ProductBrief(UUID id, String sku, String name, ProductKind kind, String unit, BigDecimal listPrice, String currency, boolean archived)`; `ProductService.briefs(Collection<UUID>) : Map<UUID, ProductBrief>`.
  - `PartyService.ensureRole(UUID partyId, PartyRoleType role)` (CUSTOMER or SUPPLIER; EMPLOYEE → `IllegalArgumentException`).
  - `InventoryPermissions.{STOCK_READ, STOCK_ADJUST, WAREHOUSE_MANAGE, PURCHASE_READ, PURCHASE_MANAGE, ORDER_READ, ORDER_MANAGE, REORDER_MANAGE}`.
  - `WarehouseRef(UUID id, String code, String name)`; `WarehouseView(UUID id, String code, String name, String address, Instant archivedAt, long version)`.
  - `WarehouseService` public `list(boolean archived)`, `create`, `update(UUID, WarehouseCommand, Long)`, `archive(UUID)`, `restore(UUID)`, `seedDefault()`; package-private `Warehouse requireActive(UUID id, String field)` (unknown → 400 field "Choose a warehouse in this workspace.", archived → 409 ARCHIVED), `Map<UUID, Warehouse> byIds(Collection<UUID>)`, `static WarehouseRef ref(Warehouse)`, and the extension point `private void requireUnused(Warehouse)` that Tasks 2–4 extend.
  - Test helpers: `TestInventory.enable(Api owner)`, `TestInventory.goods(Api owner, String sku, String name) : UUID` (creates a GOODS product), `TestInventory.mainWarehouse(Api owner) : UUID`.

- [ ] **Step 1: Unit test the shared decimals**

`backend/src/test/java/com/nexusops/shared/DecimalsTest.java`:

```java
package com.nexusops.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexusops.shared.web.ApiProblem;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class DecimalsTest {

    private static void refused(Runnable call, String message) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiProblem.class,
                p -> assertThat(p.errors().getFirst().message()).isEqualTo(message));
    }

    @Test
    void nonNegativeKeepsNullAndZero() {
        assertThat(Decimals.nonNegative(null, "x", "neg")).isNull();
        assertThat(Decimals.nonNegative(BigDecimal.ZERO, "x", "neg")).isEqualByComparingTo("0");
        assertThat(Decimals.nonNegative(new BigDecimal("12.3400"), "x", "neg")).isEqualByComparingTo("12.34");
    }

    @Test
    void positiveIsRequiredAndAboveZero() {
        refused(() -> Decimals.positive(null, "qty", "Enter a quantity greater than 0."), "Enter a quantity greater than 0.");
        refused(() -> Decimals.positive(BigDecimal.ZERO, "qty", "Enter a quantity greater than 0."),
                "Enter a quantity greater than 0.");
        assertThat(Decimals.positive(new BigDecimal("0.0001"), "qty", "m")).isEqualByComparingTo("0.0001");
    }

    @Test
    void scaleAndSizeAreBounded() {
        refused(() -> Decimals.nonNegative(new BigDecimal("-1"), "x", "Enter 0 or more."), "Enter 0 or more.");
        refused(() -> Decimals.nonNegative(new BigDecimal("1.23456"), "x", "neg"), "Use at most 4 decimal places.");
        refused(() -> Decimals.positive(new BigDecimal("1000000000000000"), "x", "m"), "Enter a smaller amount.");
        refused(() -> Decimals.positive(new BigDecimal("1E+16"), "x", "m"), "Enter a smaller amount.");
    }

    @Test
    void currencies() {
        assertThat(Currencies.parse(" inr ", "c")).isEqualTo("INR");
        assertThat(Currencies.parse(" ", "c")).isNull();
        refused(() -> Currencies.parse("EURO", "c"), "Use a 3-letter currency code like USD.");
        refused(() -> Currencies.parse("ZZZ", "c"), "Use a 3-letter currency code like USD.");
    }
}
```

- [ ] **Step 2: Write the failing API test and helpers**

`backend/src/test/java/com/nexusops/support/TestInventory.java`:

```java
package com.nexusops.support;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

/** Enables the Inventory module and creates stockable products for tests. */
public final class TestInventory {

    private TestInventory() {}

    public static void enable(Api owner) throws Exception {
        owner.put("/api/v1/tenant/modules/INVENTORY", "{\"enabled\":true}").andExpect(status().isOk());
    }

    public static UUID goods(Api owner, String sku, String name) throws Exception {
        return Api.id(owner.post("/api/v1/products", "{\"sku\":\"" + sku + "\",\"name\":\"" + name + "\",\"listPrice\":10}")
                .andExpect(status().isCreated()));
    }

    public static UUID mainWarehouse(Api owner) throws Exception {
        java.util.List<String> ids = Api.read(owner.get("/api/v1/inventory/warehouses"), "$[?(@.code == 'MAIN')].id");
        return UUID.fromString(ids.getFirst());
    }
}
```

`backend/src/test/java/com/nexusops/inventory/WarehouseApiIT.java`:

```java
package com.nexusops.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestInventory;
import com.nexusops.support.TestMembers;
import com.nexusops.support.TestRoles;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Workspace;
import java.util.Set;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class WarehouseApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("wh"));
        owner = Api.login(mvc, ws);
        TestInventory.enable(owner);
    }

    @Test
    void aNewWorkspaceHasAMainWarehouse() throws Exception {
        owner.get("/api/v1/inventory/warehouses").andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code").value(Matchers.contains("MAIN")))
                .andExpect(jsonPath("$[0].name").value("Main warehouse"));
    }

    @Test
    void inventoryRoutesNeedTheModule() throws Exception {
        Api other = Api.login(mvc, TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("nowh")));
        other.get("/api/v1/inventory/warehouses").andExpect(status().isForbidden());
    }

    @Test
    void ownersHoldTheInventoryPermissionsAndTheUnusedSeedIsGone() throws Exception {
        owner.get("/api/v1/me").andExpect(jsonPath("$.permissions", Matchers.hasItems("inventory.stock.read",
                "inventory.stock.adjust", "inventory.warehouse.manage", "inventory.purchase.read",
                "inventory.purchase.manage", "inventory.order.read", "inventory.order.manage",
                "inventory.reorder.manage")));
        owner.get("/api/v1/permissions").andExpect(jsonPath("$[*].code",
                Matchers.not(Matchers.hasItem("inventory.product.read"))));
    }

    @Test
    void createsRenamesArchivesAndRestores() throws Exception {
        UUID pune = Api.id(owner.post("/api/v1/inventory/warehouses",
                "{\"code\":\" pune-1 \",\"name\":\" Pune   store \",\"address\":\"Hadapsar, Pune\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("pune-1"))
                .andExpect(jsonPath("$.name").value("Pune store")));
        owner.put("/api/v1/inventory/warehouses/" + pune, "{\"code\":\"PUNE-1\",\"name\":\"Pune\",\"version\":0}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("PUNE-1"))
                .andExpect(jsonPath("$.address").doesNotExist());
        owner.post("/api/v1/inventory/warehouses/" + pune + "/archive", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").exists());
        owner.get("/api/v1/inventory/warehouses").andExpect(jsonPath("$[*].code").value(Matchers.contains("MAIN")));
        owner.get("/api/v1/inventory/warehouses?archived=true").andExpect(jsonPath("$[*].code").value(Matchers.contains("PUNE-1")));
        owner.put("/api/v1/inventory/warehouses/" + pune, "{\"code\":\"P\",\"name\":\"P\",\"version\":2}")
                .andExpect(status().isConflict());
        owner.post("/api/v1/inventory/warehouses/" + pune + "/restore", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").doesNotExist());
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForObject("select count(*) from audit_events where action in "
                + "('WarehouseCreated','WarehouseUpdated','WarehouseArchived','WarehouseRestored')", Long.class))
                .isEqualTo(4);
    }

    @Test
    void invalidWarehousesAreFieldErrors() throws Exception {
        String[][] cases = {
                {"{\"code\":\"\",\"name\":\"X\"}", "code"},
                {"{\"code\":\"has space\",\"name\":\"X\"}", "code"},
                {"{\"code\":\"" + "A".repeat(21) + "\",\"name\":\"X\"}", "code"},
                {"{\"code\":\"OK\",\"name\":\" \"}", "name"},
                {"{\"code\":\"OK\",\"name\":\"X\",\"address\":\"" + "a".repeat(501) + "\"}", "address"},
        };
        for (String[] c : cases) {
            owner.post("/api/v1/inventory/warehouses", c[0]).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value(c[1]));
        }
        owner.post("/api/v1/inventory/warehouses", "{\"code\":\"main\",\"name\":\"Again\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].field").value("code"));
    }

    @Test
    void theLastActiveWarehouseStays() throws Exception {
        UUID main = TestInventory.mainWarehouse(owner);
        owner.post("/api/v1/inventory/warehouses/" + main + "/archive", "").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Keep at least one active warehouse."));
        owner.post("/api/v1/inventory/warehouses/" + UUID.randomUUID() + "/archive", "").andExpect(status().isNotFound());
    }

    @Test
    void readersSeeWarehousesButCannotChangeThem() throws Exception {
        UUID role = TestRoles.create(mvc, owner.session(), "Stock reader", "inventory.stock.read");
        Api reader = Api.login(mvc, members.create(ws.tenantId(), Set.of(role)));
        reader.get("/api/v1/inventory/warehouses").andExpect(status().isOk());
        reader.post("/api/v1/inventory/warehouses", "{\"code\":\"X\",\"name\":\"X\"}").andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `cd backend && ./gradlew test --tests '*DecimalsTest' --tests '*WarehouseApiIT'`
Expected: FAIL — compilation (`Decimals` missing).

- [ ] **Step 4: Migration V18**

`backend/src/main/resources/db/migration/V18__inventory_base.sql`:

```sql
-- Inventory (Phase 6): module permissions (D16), document numbers and warehouses (D4).
INSERT INTO permissions (code, module_code, description) VALUES
    ('inventory.stock.read',       'INVENTORY', 'View warehouses, stock, movements and reorder suggestions'),
    ('inventory.warehouse.manage', 'INVENTORY', 'Create, edit and archive warehouses'),
    ('inventory.purchase.read',    'INVENTORY', 'View purchase orders'),
    ('inventory.purchase.manage',  'INVENTORY', 'Create, order, receive and cancel purchase orders'),
    ('inventory.order.read',       'INVENTORY', 'View sales orders'),
    ('inventory.order.manage',     'INVENTORY', 'Create, confirm, fulfil and cancel sales orders'),
    ('inventory.reorder.manage',   'INVENTORY', 'Set reorder rules and draft purchase orders from suggestions');
UPDATE permissions SET description = 'Count stock and transfer it between warehouses'
WHERE code = 'inventory.stock.adjust';
SELECT grant_to_system_roles(ARRAY['inventory.stock.read', 'inventory.stock.adjust', 'inventory.warehouse.manage',
                                   'inventory.purchase.read', 'inventory.purchase.manage', 'inventory.order.read',
                                   'inventory.order.manage', 'inventory.reorder.manage']);

-- V3's unused seed: products are catalog records (ADR-0008).
DO $$
DECLARE
    t uuid;
BEGIN
    FOR t IN SELECT id FROM tenants LOOP
        PERFORM set_config('app.tenant_id', t::text, true);
        DELETE FROM role_permissions WHERE permission_code = 'inventory.product.read';
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END
$$;
DELETE FROM permissions WHERE code = 'inventory.product.read';

-- Per-tenant document numbers (PO-00001, SO-00001); incremented with UPDATE … RETURNING under the row lock.
CREATE TABLE number_sequences (
    tenant_id   uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    kind        text NOT NULL CHECK (kind IN ('PURCHASE_ORDER', 'SALES_ORDER')),
    next_value  bigint NOT NULL CHECK (next_value >= 1),
    PRIMARY KEY (tenant_id, kind)
);
ALTER TABLE number_sequences ENABLE ROW LEVEL SECURITY;
ALTER TABLE number_sequences FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON number_sequences
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE warehouses (
    id           uuid PRIMARY KEY,
    tenant_id    uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    code         text NOT NULL CHECK (code ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,19}$'),
    code_key     text NOT NULL,
    name         text NOT NULL CHECK (name = btrim(name) AND length(name) BETWEEN 1 AND 100),
    address      text CHECK (address IS NULL OR length(address) <= 500),
    archived_at  timestamptz,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    version      bigint NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, code_key)
);
ALTER TABLE warehouses ENABLE ROW LEVEL SECURITY;
ALTER TABLE warehouses FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON warehouses
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Every existing workspace gets its MAIN warehouse (new ones get it from InventorySetup on WorkspaceRegistered).
DO $$
DECLARE
    t uuid;
BEGIN
    FOR t IN SELECT id FROM tenants LOOP
        PERFORM set_config('app.tenant_id', t::text, true);
        INSERT INTO warehouses (id, tenant_id, code, code_key, name, created_at, updated_at)
        VALUES (gen_random_uuid(), t, 'MAIN', 'main', 'Main warehouse', now(), now());
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END
$$;
```

- [ ] **Step 5: Shared decimals and currencies; `crm.Money` delegates**

`shared/Decimals.java`:

```java
package com.nexusops.shared;

import com.nexusops.shared.web.ApiProblem;
import java.math.BigDecimal;

/** Validation of numeric(19,4) inputs: amounts, quantities, counts. Failures are 400 field errors. */
public final class Decimals {

    private static final int MAX_INTEGER_DIGITS = 15;

    private Decimals() {}

    /** Null stays null; otherwise ≥ 0, at most 4 decimals and 15 integer digits. */
    public static BigDecimal nonNegative(BigDecimal value, String field, String negativeMessage) {
        if (value == null) {
            return null;
        }
        if (value.signum() < 0) {
            throw ApiProblem.badRequestField(field, negativeMessage);
        }
        return bounded(value, field);
    }

    /** Required and > 0, at most 4 decimals and 15 integer digits. */
    public static BigDecimal positive(BigDecimal value, String field, String message) {
        if (value == null || value.signum() <= 0) {
            throw ApiProblem.badRequestField(field, message);
        }
        return bounded(value, field);
    }

    private static BigDecimal bounded(BigDecimal value, String field) {
        if (value.stripTrailingZeros().scale() > 4) {
            throw ApiProblem.badRequestField(field, "Use at most 4 decimal places.");
        }
        if (value.precision() - value.scale() > MAX_INTEGER_DIGITS) {
            throw ApiProblem.badRequestField(field, "Enter a smaller amount.");
        }
        return value;
    }
}
```

`shared/Currencies.java`:

```java
package com.nexusops.shared;

import com.nexusops.shared.web.ApiProblem;
import java.util.Currency;
import java.util.Locale;

public final class Currencies {

    private Currencies() {}

    /** Null for blank input; otherwise the upper-case ISO-4217 code, or a 400 field error. */
    public static String parse(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String code = raw.strip().toUpperCase(Locale.ROOT);
        try {
            if (code.length() == 3 && Currency.getInstance(code) != null) {
                return code;
            }
        } catch (IllegalArgumentException unknown) {
            // falls through to the field error
        }
        throw ApiProblem.badRequestField(field, "Use a 3-letter currency code like USD.");
    }
}
```

Replace the bodies of `crm/Money.java`'s two methods (keep the class, signatures and Javadoc):

```java
    static BigDecimal amount(BigDecimal value, String field) {
        return Decimals.nonNegative(value, field, "Enter an amount of 0 or more.");
    }

    static String currency(String raw, String field, TenantDirectory tenants) {
        String code = Currencies.parse(raw, field);
        return code != null ? code : tenants.currentSettings().currency();
    }
```

(Remove the now-unused `MAX_INTEGER_DIGITS` constant and imports; the CRM tests must still pass unchanged.)

- [ ] **Step 6: Catalog briefs and `PartyService.ensureRole`**

`catalog/ProductBrief.java`:

```java
package com.nexusops.catalog;

import java.math.BigDecimal;
import java.util.UUID;

/** A product as other modules reference it. */
public record ProductBrief(UUID id, String sku, String name, ProductKind kind, String unit, BigDecimal listPrice,
        String currency, boolean archived) {}
```

Add to `ProductService` (imports `java.util.Collection`, `java.util.Set`, `java.util.stream.Collectors`):

```java
    /** Tenant-scoped lookup for other modules; unknown ids (or other tenants') are simply absent. */
    @Transactional(readOnly = true)
    public Map<UUID, ProductBrief> briefs(Collection<UUID> ids) {
        TenantContext.requireTenantId();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return products.findAllById(Set.copyOf(ids)).stream().collect(Collectors.toMap(Product::getId,
                p -> new ProductBrief(p.getId(), p.getSku(), p.getName(), p.getKind(), p.getUnit(), p.getListPrice(),
                        p.getCurrency(), p.isArchived())));
    }
```

In `PartyService`, replace `ensureCustomer(UUID id)` with:

```java
    /** CRM's business rule (spec D7): kept for its callers. */
    @Transactional
    public void ensureCustomer(UUID id) {
        ensureRole(id, PartyRoleType.CUSTOMER);
    }

    /**
     * Business rules of other modules (CRM: customers; Inventory: suppliers and customers) make a party play a role.
     * Not a user action, so no permission check here. Idempotent; archived parties are left alone.
     */
    @Transactional
    public void ensureRole(UUID id, PartyRoleType role) {
        if (role == PartyRoleType.EMPLOYEE) {
            throw new IllegalArgumentException("Employee roles are managed in the directory");
        }
        Party party = find(id);
        if (party.isArchived()) {
            return;
        }
        locks.lock(ROLES_LOCK);
        PartyRole row = roles.findByPartyIdAndRole(id, role).orElse(null);
        if (row != null && row.getStatus() == RoleStatus.ACTIVE) {
            return;
        }
        Map<String, Object> before = row == null ? null : roleSnapshot(row);
        if (row == null) {
            row = new PartyRole(Ids.newId(), id, role);
        }
        row.update(RoleStatus.ACTIVE, row.getSince(), null);
        roles.saveAndFlush(row);
        audit.record(AuditEntry.of("PartyRoleChanged", "Party", id).withBefore(before).withAfter(roleSnapshot(row)));
    }
```

- [ ] **Step 7: The inventory module and warehouses**

`inventory/package-info.java`:

```java
/**
 * Inventory (Phase 6, ADR-0011): warehouses, stock levels with an append-only ledger, counts and transfers, purchase
 * orders, sales orders and reorder rules. Stock changes only through StockLedger. Every permission belongs to module
 * INVENTORY, so the whole module switches off with it.
 */
package com.nexusops.inventory;
```

`inventory/InventoryPermissions.java`:

```java
package com.nexusops.inventory;

/** Permission codes of the Inventory module (V18). All have module_code INVENTORY. */
public final class InventoryPermissions {

    public static final String STOCK_READ = "inventory.stock.read";
    public static final String STOCK_ADJUST = "inventory.stock.adjust";
    public static final String WAREHOUSE_MANAGE = "inventory.warehouse.manage";
    public static final String PURCHASE_READ = "inventory.purchase.read";
    public static final String PURCHASE_MANAGE = "inventory.purchase.manage";
    public static final String ORDER_READ = "inventory.order.read";
    public static final String ORDER_MANAGE = "inventory.order.manage";
    public static final String REORDER_MANAGE = "inventory.reorder.manage";

    private InventoryPermissions() {}
}
```

`inventory/WarehouseRef.java`, `WarehouseCommand.java`, `WarehouseView.java`:

```java
package com.nexusops.inventory;

import java.util.UUID;

public record WarehouseRef(UUID id, String code, String name) {}
```

```java
package com.nexusops.inventory;

/** Raw input; WarehouseService validates it. */
public record WarehouseCommand(String code, String name, String address) {}
```

```java
package com.nexusops.inventory;

import java.time.Instant;
import java.util.UUID;

public record WarehouseView(UUID id, String code, String name, String address, Instant archivedAt, long version) {}
```

`inventory/domain/Warehouse.java`:

```java
package com.nexusops.inventory.domain;

import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "warehouses")
public class Warehouse extends TenantOwnedEntity {

    @Column(nullable = false)
    private String code;

    @Column(name = "code_key", nullable = false)
    private String codeKey;

    @Column(nullable = false)
    private String name;

    private String address;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Warehouse() {}

    public Warehouse(UUID id, String code, String codeKey, String name, String address) {
        super(id);
        this.createdAt = Instant.now();
        apply(code, codeKey, name, address);
    }

    public void apply(String newCode, String newKey, String newName, String newAddress) {
        this.code = newCode;
        this.codeKey = newKey;
        this.name = newName;
        this.address = newAddress;
        this.updatedAt = Instant.now();
    }

    public void archive(Instant now) {
        this.archivedAt = now;
        this.updatedAt = now;
    }

    public void restore() {
        this.archivedAt = null;
        this.updatedAt = Instant.now();
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    public String getCode() {
        return code;
    }

    public String getCodeKey() {
        return codeKey;
    }

    public String getName() {
        return name;
    }

    public String getAddress() {
        return address;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public long getVersion() {
        return version;
    }
}
```

`inventory/domain/WarehouseRepository.java`:

```java
package com.nexusops.inventory.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WarehouseRepository extends JpaRepository<Warehouse, UUID> {

    boolean existsByCodeKey(String codeKey);

    boolean existsByCodeKeyAndIdNot(String codeKey, UUID id);

    long countByArchivedAtIsNull();

    List<Warehouse> findByArchivedAtIsNullOrderByCodeKeyAsc();

    List<Warehouse> findByArchivedAtIsNotNullOrderByCodeKeyAsc();
}
```

`inventory/WarehouseService.java`:

```java
package com.nexusops.inventory;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.inventory.domain.Warehouse;
import com.nexusops.inventory.domain.WarehouseRepository;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.Text;
import com.nexusops.shared.db.TenantLocks;
import com.nexusops.shared.web.ApiProblem;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Warehouses (D4). Codes are unique ignoring case and never reused; at least one warehouse stays active. */
@Service
public class WarehouseService {

    static final String NOT_FOUND = "Record not found.";
    static final String ARCHIVED = "This record is archived.";
    static final String STALE = "This record was changed by someone else. Reload and try again.";
    static final String CODE_TAKEN = "Another warehouse already uses this code.";
    static final String LAST_ACTIVE = "Keep at least one active warehouse.";
    static final String UNKNOWN = "Choose a warehouse in this workspace.";
    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,19}$");
    private static final String LOCK = "warehouses";

    private final WarehouseRepository warehouses;
    private final TenantLocks locks;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    WarehouseService(WarehouseRepository warehouses, TenantLocks locks, AuditService audit, JdbcTemplate jdbc) {
        this.warehouses = warehouses;
        this.locks = locks;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<WarehouseView> list(boolean archived) {
        TenantContext.requireTenantId();
        return (archived ? warehouses.findByArchivedAtIsNotNullOrderByCodeKeyAsc()
                : warehouses.findByArchivedAtIsNullOrderByCodeKeyAsc()).stream().map(WarehouseService::view).toList();
    }

    @Transactional
    public WarehouseView create(WarehouseCommand command) {
        TenantContext.requireTenantId();
        String code = code(command.code());
        String name = Text.required(command.name(), 100, "name").replaceAll("\\s+", " ");
        String address = Text.optional(command.address(), 500, "address");
        locks.lock(LOCK);
        if (warehouses.existsByCodeKey(key(code))) {
            throw ApiProblem.conflictField("code", CODE_TAKEN);
        }
        Warehouse warehouse = new Warehouse(Ids.newId(), code, key(code), name, address);
        save(warehouse);
        audit.record(AuditEntry.of("WarehouseCreated", "Warehouse", warehouse.getId()).withAfter(snapshot(warehouse)));
        return view(warehouse);
    }

    @Transactional
    public WarehouseView update(UUID id, WarehouseCommand command, Long version) {
        Warehouse warehouse = find(id);
        if (version == null) {
            throw ApiProblem.badRequestField("version", "Reload the record and try again.");
        }
        if (warehouse.getVersion() != version) {
            throw ApiProblem.conflict(STALE);
        }
        String code = code(command.code());
        String name = Text.required(command.name(), 100, "name").replaceAll("\\s+", " ");
        String address = Text.optional(command.address(), 500, "address");
        locks.lock(LOCK);
        if (warehouses.existsByCodeKeyAndIdNot(key(code), id)) {
            throw ApiProblem.conflictField("code", CODE_TAKEN);
        }
        Map<String, Object> before = snapshot(warehouse);
        warehouse.apply(code, key(code), name, address);
        save(warehouse);
        audit.record(AuditEntry.of("WarehouseUpdated", "Warehouse", id).withBefore(before).withAfter(snapshot(warehouse)));
        return view(warehouse);
    }

    @Transactional
    public WarehouseView archive(UUID id) {
        Warehouse warehouse = find(id);
        if (!warehouse.isArchived()) {
            locks.lock(LOCK);
            if (warehouses.countByArchivedAtIsNull() <= 1) {
                throw ApiProblem.conflict(LAST_ACTIVE);
            }
            requireUnused(warehouse);
            warehouse.archive(Instant.now());
            warehouses.flush();
            audit.record(AuditEntry.of("WarehouseArchived", "Warehouse", id).withBefore(snapshot(warehouse)));
        }
        return view(warehouse);
    }

    @Transactional
    public WarehouseView restore(UUID id) {
        Warehouse warehouse = find(id);
        if (warehouse.isArchived()) {
            warehouse.restore();
            warehouses.flush();
            audit.record(AuditEntry.of("WarehouseRestored", "Warehouse", id).withAfter(snapshot(warehouse)));
        }
        return view(warehouse);
    }

    /** Idempotent: a workspace that has any warehouse keeps them. */
    @Transactional
    public void seedDefault() {
        TenantContext.requireTenantId();
        locks.lock(LOCK);
        if (warehouses.count() == 0) {
            warehouses.saveAndFlush(new Warehouse(Ids.newId(), "MAIN", "main", "Main warehouse", null));
        }
    }

    /** A warehouse referenced from a request body: unknown is 400 on {@code field}, archived is 409. */
    Warehouse requireActive(UUID id, String field) {
        TenantContext.requireTenantId();
        Warehouse warehouse = id == null ? null : warehouses.findById(id).orElse(null);
        if (warehouse == null) {
            throw ApiProblem.badRequestField(field, UNKNOWN);
        }
        if (warehouse.isArchived()) {
            throw ApiProblem.conflict(ARCHIVED);
        }
        return warehouse;
    }

    Map<UUID, Warehouse> byIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return warehouses.findAllById(Set.copyOf(ids)).stream()
                .collect(Collectors.toMap(Warehouse::getId, w -> w, (a, b) -> a, LinkedHashMap::new));
    }

    static WarehouseRef ref(Warehouse w) {
        return w == null ? null : new WarehouseRef(w.getId(), w.getCode(), w.getName());
    }

    /**
     * Refuses archiving while the warehouse is in use. Task 2 adds the stock check, Tasks 3 and 4 the purchase- and
     * sales-order checks (JDBC with an explicit tenant predicate).
     */
    private void requireUnused(Warehouse warehouse) {
        // no stock or orders exist before Task 2
    }

    private Warehouse find(UUID id) {
        TenantContext.requireTenantId();
        return warehouses.findById(id).orElseThrow(() -> ApiProblem.notFound(NOT_FOUND));
    }

    private void save(Warehouse warehouse) {
        try {
            warehouses.saveAndFlush(warehouse);
        } catch (DataIntegrityViolationException race) {
            throw ApiProblem.conflictField("code", CODE_TAKEN);
        }
    }

    private static String code(String raw) {
        String code = Text.required(raw, 20, "code");
        if (!CODE.matcher(code).matches()) {
            throw ApiProblem.badRequestField("code", "Use letters, digits, - and _ only, up to 20 characters.");
        }
        return code;
    }

    private static String key(String code) {
        return code.toLowerCase(Locale.ROOT);
    }

    private static Map<String, Object> snapshot(Warehouse w) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("code", w.getCode());
        values.put("name", w.getName());
        if (w.getAddress() != null) {
            values.put("address", w.getAddress());
        }
        return values;
    }

    static WarehouseView view(Warehouse w) {
        return new WarehouseView(w.getId(), w.getCode(), w.getName(), w.getAddress(), w.getArchivedAt(), w.getVersion());
    }
}
```

(`requireUnused` is empty in this task on purpose: there is no stock or order yet. Tasks 2–4 fill it, which is why `JdbcTemplate` is injected now.)

`inventory/InventorySetup.java`:

```java
package com.nexusops.inventory;

import com.nexusops.tenancy.WorkspaceRegistered;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Every new workspace starts with its MAIN warehouse, in the signup transaction (D4). */
@Component
class InventorySetup {

    private final WarehouseService warehouses;

    InventorySetup(WarehouseService warehouses) {
        this.warehouses = warehouses;
    }

    @EventListener
    void on(WorkspaceRegistered event) {
        warehouses.seedDefault();
    }
}
```

- [ ] **Step 8: Web layer**

`inventory/web/InventoryDtos.java` (later tasks add more records here):

```java
package com.nexusops.inventory.web;

import com.nexusops.inventory.WarehouseCommand;

final class InventoryDtos {

    private InventoryDtos() {}

    record WarehouseRequest(String code, String name, String address, Long version) {
        WarehouseCommand command() {
            return new WarehouseCommand(code, name, address);
        }
    }
}
```

`inventory/web/WarehouseController.java`:

```java
package com.nexusops.inventory.web;

import com.nexusops.inventory.WarehouseService;
import com.nexusops.inventory.WarehouseView;
import com.nexusops.inventory.web.InventoryDtos.WarehouseRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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
@RequestMapping("/api/v1/inventory/warehouses")
class WarehouseController {

    private final WarehouseService warehouses;

    WarehouseController(WarehouseService warehouses) {
        this.warehouses = warehouses;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('inventory.stock.read')")
    List<WarehouseView> list(@RequestParam(defaultValue = "false") boolean archived) {
        return warehouses.list(archived);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('inventory.warehouse.manage')")
    WarehouseView create(@RequestBody WarehouseRequest request) {
        return warehouses.create(request.command());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('inventory.warehouse.manage')")
    WarehouseView update(@PathVariable UUID id, @RequestBody WarehouseRequest request) {
        return warehouses.update(id, request.command(), request.version());
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAuthority('inventory.warehouse.manage')")
    WarehouseView archive(@PathVariable UUID id) {
        return warehouses.archive(id);
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAuthority('inventory.warehouse.manage')")
    WarehouseView restore(@PathVariable UUID id) {
        return warehouses.restore(id);
    }
}
```

- [ ] **Step 9: Run the tests to verify they pass**

Run: `cd backend && ./gradlew test --tests '*DecimalsTest' --tests '*WarehouseApiIT' --tests '*ModularityTest' --tests '*EndpointAuthorizationCoverageTest' --tests '*RlsCoverageIT' --tests 'com.nexusops.crm.*'`
Expected: PASS (CRM tests unchanged; RLS coverage passes because both new tables have forced policies).

- [ ] **Step 10: Run the whole backend suite, then commit**

Run: `cd backend && ./gradlew test` — Expected: PASS.

```bash
git add backend/src/main/resources/db/migration/V18__inventory_base.sql backend/src/main/java/com/nexusops \
  backend/src/test/java/com/nexusops/support/TestInventory.java backend/src/test/java/com/nexusops/shared/DecimalsTest.java \
  backend/src/test/java/com/nexusops/inventory
git commit -m "feat(inventory): module permissions and warehouses, with a MAIN warehouse for every workspace"
```

---
### Task 2: Stock levels, the append-only ledger, counts and transfers

**Files:**
- Create: `backend/src/main/resources/db/migration/V19__stock.sql`
- Create: `backend/src/main/java/com/nexusops/inventory/StockKey.java`, `MovementKind.java`, `ReferenceType.java`, `Quantities.java`, `ProductRef.java`, `InventoryProducts.java`, `Shortage.java`, `StockLedger.java`, `StockService.java`, `AdjustCommand.java`, `TransferCommand.java`, `ProductStock.java`, `MovementView.java`, `MovementQuery.java`
- Create: `backend/src/main/java/com/nexusops/inventory/domain/StockLevel.java`, `StockLevelRepository.java`, `StockMovement.java`, `StockMovementRepository.java`
- Create: `backend/src/main/java/com/nexusops/inventory/web/StockController.java`; Modify: `web/InventoryDtos.java`
- Modify: `backend/src/main/java/com/nexusops/inventory/WarehouseService.java` (`requireUnused`: stock check)
- Test: `backend/src/test/java/com/nexusops/inventory/StockApiIT.java`

**Interfaces:**
- Consumes (Task 1): `WarehouseService.requireActive/byIds/ref`, `Warehouse` getters, `ProductService.briefs`, `ProductBrief`, `Decimals`, `TestInventory`.
- Produces:
  - `StockKey(UUID productId, UUID warehouseId) implements Comparable<StockKey>` (order: productId, then warehouseId).
  - `enum MovementKind {RECEIPT, ISSUE, ADJUSTMENT, TRANSFER_OUT, TRANSFER_IN}`; `enum ReferenceType {PURCHASE_ORDER, SALES_ORDER, TRANSFER, ADJUSTMENT}`.
  - `Quantities.positive(BigDecimal, String field)` ("Enter a quantity greater than 0."), `Quantities.count(BigDecimal, String field)` (required, ≥ 0, "Enter 0 or more.").
  - `ProductRef(UUID id, String sku, String name, String unit)`.
  - `InventoryProducts` (package-private `@Component`): `ProductBrief requireStockable(UUID id, String field)` (not archived), `ProductBrief requireStockable(UUID id, String field, boolean allowArchived)`, `Map<UUID, ProductBrief> briefs(Collection<UUID>)`, `static ProductRef ref(ProductBrief)`.
  - `Shortage(UUID productId, String sku, BigDecimal requested, BigDecimal available)`; `static ApiProblem Shortage.conflict(List<Shortage>)` → 409 "Not enough stock." with property `shortages`.
  - `StockLedger` (package-private `@Component`): `Map<StockKey, StockLevel> lock(Collection<StockKey>)` (MANDATORY transaction; creates missing rows; locks in key order), `StockMovement move(StockLevel, MovementKind, BigDecimal signedDelta, ReferenceType, UUID referenceId, String reason)`, `void reserve(StockLevel, BigDecimal)`, `void release(StockLevel, BigDecimal)`.
  - `StockLevel` getters `getProductId() getWarehouseId() getOnHand() getReserved() getAvailable()`.
  - `ProductStock(ProductRef product, List<ProductStock.Level> levels, BigDecimal onHand, BigDecimal reserved, BigDecimal available)`, `ProductStock.Level(WarehouseRef warehouse, BigDecimal onHand, BigDecimal reserved, BigDecimal available)`.
  - `MovementView(UUID id, ProductRef product, WarehouseRef warehouse, MovementKind kind, BigDecimal quantity, BigDecimal onHandAfter, ReferenceType referenceType, UUID referenceId, String reason, MemberRef actor, Instant occurredAt)`.
  - `StockService` public `productStock(UUID)`, `movements(MovementQuery, Integer, Integer)`, `adjust(AdjustCommand)`, `transfer(TransferCommand)`; package-private `List<MovementView> views(List<StockMovement>)` (Task 5 reuses it).
  - Routes: `GET /api/v1/inventory/stock/products/{productId}`, `GET /api/v1/inventory/movements`, `POST /api/v1/inventory/adjustments`, `POST /api/v1/inventory/transfers`.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/nexusops/inventory/StockApiIT.java`:

```java
package com.nexusops.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestInventory;
import com.nexusops.support.TestMembers;
import com.nexusops.support.TestRoles;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Workspace;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@AutoConfigureMockMvc
class StockApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;
    UUID widget;
    UUID main;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("stock"));
        owner = Api.login(mvc, ws);
        TestInventory.enable(owner);
        widget = TestInventory.goods(owner, "W-1", "Widget");
        main = TestInventory.mainWarehouse(owner);
    }

    private ResultActions count(UUID warehouse, String quantity, String reason) throws Exception {
        return owner.post("/api/v1/inventory/adjustments", "{\"productId\":\"" + widget + "\",\"warehouseId\":\"" + warehouse
                + "\",\"countedQuantity\":" + quantity + ",\"reason\":\"" + reason + "\"}");
    }

    private UUID warehouse(String code) throws Exception {
        return Api.id(owner.post("/api/v1/inventory/warehouses", "{\"code\":\"" + code + "\",\"name\":\"" + code + "\"}"));
    }

    private long audits(String action) {
        return OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select count(*) from audit_events where action = ?", Long.class, action);
    }

    /** Every level's on-hand equals the sum of its ledger rows. */
    private void ledgerBalances() {
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForObject("""
                select count(*) from stock_levels l
                where l.on_hand <> coalesce((select sum(m.quantity) from stock_movements m
                                             where m.product_id = l.product_id and m.warehouse_id = l.warehouse_id), 0)
                """, Long.class)).isZero();
    }

    @Test
    void productStockStartsAtZeroInEveryWarehouse() throws Exception {
        owner.get("/api/v1/inventory/stock/products/" + widget).andExpect(status().isOk())
                .andExpect(jsonPath("$.product.sku").value("W-1"))
                .andExpect(jsonPath("$.levels[*].warehouse.code").value(Matchers.contains("MAIN")))
                .andExpect(jsonPath("$.levels[0].onHand").value(0))
                .andExpect(jsonPath("$.available").value(0));
        owner.get("/api/v1/inventory/stock/products/" + UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void countsStockAndWritesTheLedger() throws Exception {
        count(main, "10", "Opening stock").andExpect(status().isOk())
                .andExpect(jsonPath("$.levels[0].onHand").value(10.0))
                .andExpect(jsonPath("$.onHand").value(10.0));
        count(main, "4", "Damaged").andExpect(status().isOk()).andExpect(jsonPath("$.levels[0].onHand").value(4.0));
        owner.get("/api/v1/inventory/movements?productId=" + widget).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].kind").value("ADJUSTMENT"))
                .andExpect(jsonPath("$.items[0].quantity").value(-6.0))
                .andExpect(jsonPath("$.items[0].onHandAfter").value(4.0))
                .andExpect(jsonPath("$.items[0].reason").value("Damaged"))
                .andExpect(jsonPath("$.items[0].actor").exists())
                .andExpect(jsonPath("$.items[1].quantity").value(10.0));
        assertThat(audits("StockAdjusted")).isEqualTo(2);
        ledgerBalances();
    }

    @Test
    void countingTheSameQuantityChangesNothing() throws Exception {
        count(main, "5", "Count").andExpect(status().isOk());
        count(main, "5.0000", "Count again").andExpect(status().isOk());
        owner.get("/api/v1/inventory/movements?productId=" + widget).andExpect(jsonPath("$.total").value(1));
        assertThat(audits("StockAdjusted")).isEqualTo(1);
    }

    @Test
    void invalidCountsAreFieldErrors() throws Exception {
        UUID service = Api.id(owner.post("/api/v1/products", "{\"sku\":\"S-1\",\"name\":\"Setup\",\"kind\":\"SERVICE\"}"));
        String[][] cases = {
                {"{\"productId\":\"" + widget + "\",\"warehouseId\":\"" + main + "\",\"countedQuantity\":-1,\"reason\":\"x\"}", "countedQuantity"},
                {"{\"productId\":\"" + widget + "\",\"warehouseId\":\"" + main + "\",\"reason\":\"x\"}", "countedQuantity"},
                {"{\"productId\":\"" + widget + "\",\"warehouseId\":\"" + main + "\",\"countedQuantity\":1.23456,\"reason\":\"x\"}", "countedQuantity"},
                {"{\"productId\":\"" + widget + "\",\"warehouseId\":\"" + main + "\",\"countedQuantity\":1,\"reason\":\" \"}", "reason"},
                {"{\"productId\":\"" + UUID.randomUUID() + "\",\"warehouseId\":\"" + main + "\",\"countedQuantity\":1,\"reason\":\"x\"}", "productId"},
                {"{\"productId\":\"" + service + "\",\"warehouseId\":\"" + main + "\",\"countedQuantity\":1,\"reason\":\"x\"}", "productId"},
                {"{\"productId\":\"" + widget + "\",\"warehouseId\":\"" + UUID.randomUUID() + "\",\"countedQuantity\":1,\"reason\":\"x\"}", "warehouseId"},
        };
        for (String[] c : cases) {
            owner.post("/api/v1/inventory/adjustments", c[0]).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value(c[1]));
        }
        owner.post("/api/v1/inventory/adjustments", cases[5][0])
                .andExpect(jsonPath("$.errors[0].message").value("Services don't carry stock."));
    }

    @Test
    void archivedWarehousesTakeNoStock() throws Exception {
        UUID spare = warehouse("SPARE");
        owner.post("/api/v1/inventory/warehouses/" + spare + "/archive", "").andExpect(status().isOk());
        count(spare, "1", "x").andExpect(status().isConflict());
        count(main, "5", "x").andExpect(status().isOk());
        owner.post("/api/v1/inventory/transfers", "{\"productId\":\"" + widget + "\",\"fromWarehouseId\":\"" + main
                + "\",\"toWarehouseId\":\"" + spare + "\",\"quantity\":1}").andExpect(status().isConflict());
        owner.post("/api/v1/products/" + widget + "/archive", "").andExpect(status().isOk());
        count(main, "3", "x").andExpect(status().isConflict());
        // the archived product's stock stays readable
        owner.get("/api/v1/inventory/stock/products/" + widget).andExpect(jsonPath("$.onHand").value(5.0));
    }

    @Test
    void transfersMoveAvailableStock() throws Exception {
        UUID pune = warehouse("PUNE");
        count(main, "10", "Opening stock").andExpect(status().isOk());
        owner.post("/api/v1/inventory/transfers", "{\"productId\":\"" + widget + "\",\"fromWarehouseId\":\"" + main
                        + "\",\"toWarehouseId\":\"" + pune + "\",\"quantity\":3,\"note\":\"For the Pune shop\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.levels[?(@.warehouse.code == 'MAIN')].onHand").value(Matchers.contains(7.0)))
                .andExpect(jsonPath("$.levels[?(@.warehouse.code == 'PUNE')].onHand").value(Matchers.contains(3.0)));
        List<String> refs = Api.read(owner.get("/api/v1/inventory/movements?productId=" + widget + "&kind=TRANSFER_OUT"),
                "$.items[*].referenceId");
        owner.get("/api/v1/inventory/movements?productId=" + widget + "&kind=TRANSFER_IN")
                .andExpect(jsonPath("$.items[0].referenceId").value(refs.getFirst()))
                .andExpect(jsonPath("$.items[0].quantity").value(3.0))
                .andExpect(jsonPath("$.items[0].reason").value("For the Pune shop"));
        owner.get("/api/v1/inventory/movements?warehouseId=" + pune).andExpect(jsonPath("$.total").value(1));
        assertThat(audits("StockTransferred")).isEqualTo(1);
        ledgerBalances();
    }

    @Test
    void transferShortagesAndSameWarehouseAreRefused() throws Exception {
        UUID pune = warehouse("PUNE");
        count(main, "10", "Opening stock").andExpect(status().isOk());
        owner.post("/api/v1/inventory/transfers", "{\"productId\":\"" + widget + "\",\"fromWarehouseId\":\"" + main
                        + "\",\"toWarehouseId\":\"" + pune + "\",\"quantity\":11}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("Not enough stock."))
                .andExpect(jsonPath("$.shortages[0].requested").value(11))
                .andExpect(jsonPath("$.shortages[0].available").value(10.0))
                .andExpect(jsonPath("$.shortages[0].sku").value("W-1"));
        owner.post("/api/v1/inventory/transfers", "{\"productId\":\"" + widget + "\",\"fromWarehouseId\":\"" + main
                        + "\",\"toWarehouseId\":\"" + main + "\",\"quantity\":1}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("toWarehouseId"));
        owner.post("/api/v1/inventory/transfers", "{\"productId\":\"" + widget + "\",\"fromWarehouseId\":\"" + main
                        + "\",\"toWarehouseId\":\"" + pune + "\",\"quantity\":0}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("quantity"));
    }

    @Test
    void aWarehouseWithStockCannotBeArchived() throws Exception {
        UUID pune = warehouse("PUNE");
        count(pune, "2", "x").andExpect(status().isOk());
        owner.post("/api/v1/inventory/warehouses/" + pune + "/archive", "").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Move or count out this warehouse's stock first."));
        count(pune, "0", "Moved out").andExpect(status().isOk());
        owner.post("/api/v1/inventory/warehouses/" + pune + "/archive", "").andExpect(status().isOk());
    }

    @Test
    void readersSeeStockButCannotChangeIt() throws Exception {
        UUID role = TestRoles.create(mvc, owner.session(), "Stock reader", "inventory.stock.read");
        Api reader = Api.login(mvc, members.create(ws.tenantId(), Set.of(role)));
        reader.get("/api/v1/inventory/stock/products/" + widget).andExpect(status().isOk());
        reader.get("/api/v1/inventory/movements").andExpect(status().isOk());
        reader.post("/api/v1/inventory/adjustments", "{\"productId\":\"" + widget + "\",\"warehouseId\":\"" + main
                + "\",\"countedQuantity\":1,\"reason\":\"x\"}").andExpect(status().isForbidden());
        owner.get("/api/v1/inventory/movements?kind=SIDEWAYS").andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./gradlew test --tests '*StockApiIT'`
Expected: FAIL — 404 on `/api/v1/inventory/stock/products/…` and `/adjustments`.

- [ ] **Step 3: Migration V19**

`backend/src/main/resources/db/migration/V19__stock.sql`:

```sql
-- Stock (D5): one level row per product and warehouse, and an append-only ledger. The CHECKs make negative stock and
-- over-reservation impossible whatever the application does.
ALTER TABLE products ADD CONSTRAINT products_tenant_id_id_uq UNIQUE (tenant_id, id);

CREATE TABLE stock_levels (
    id            uuid PRIMARY KEY,
    tenant_id     uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    product_id    uuid NOT NULL,
    warehouse_id  uuid NOT NULL,
    on_hand       numeric(19, 4) NOT NULL DEFAULT 0 CHECK (on_hand >= 0),
    reserved      numeric(19, 4) NOT NULL DEFAULT 0 CHECK (reserved >= 0),
    updated_at    timestamptz NOT NULL,
    version       bigint NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, product_id, warehouse_id),
    CHECK (reserved <= on_hand),
    FOREIGN KEY (tenant_id, product_id) REFERENCES products (tenant_id, id),
    FOREIGN KEY (tenant_id, warehouse_id) REFERENCES warehouses (tenant_id, id)
);
CREATE INDEX stock_levels_warehouse_idx ON stock_levels (tenant_id, warehouse_id);

ALTER TABLE stock_levels ENABLE ROW LEVEL SECURITY;
ALTER TABLE stock_levels FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON stock_levels
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE stock_movements (
    id              uuid PRIMARY KEY,
    tenant_id       uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    product_id      uuid NOT NULL,
    warehouse_id    uuid NOT NULL,
    kind            text NOT NULL CHECK (kind IN ('RECEIPT', 'ISSUE', 'ADJUSTMENT', 'TRANSFER_OUT', 'TRANSFER_IN')),
    quantity        numeric(19, 4) NOT NULL CHECK (quantity <> 0),
    on_hand_after   numeric(19, 4) NOT NULL CHECK (on_hand_after >= 0),
    reference_type  text NOT NULL CHECK (reference_type IN ('PURCHASE_ORDER', 'SALES_ORDER', 'TRANSFER', 'ADJUSTMENT')),
    reference_id    uuid NOT NULL,
    reason          text CHECK (reason IS NULL OR length(reason) <= 500),
    actor_id        uuid REFERENCES users (id) ON DELETE SET NULL,
    occurred_at     timestamptz NOT NULL,
    UNIQUE (tenant_id, id),
    CHECK ((kind IN ('RECEIPT', 'TRANSFER_IN') AND quantity > 0)
        OR (kind IN ('ISSUE', 'TRANSFER_OUT') AND quantity < 0)
        OR kind = 'ADJUSTMENT'),
    FOREIGN KEY (tenant_id, product_id) REFERENCES products (tenant_id, id),
    FOREIGN KEY (tenant_id, warehouse_id) REFERENCES warehouses (tenant_id, id)
);
CREATE INDEX stock_movements_item_idx ON stock_movements (tenant_id, product_id, warehouse_id, occurred_at);
CREATE INDEX stock_movements_reference_idx ON stock_movements (tenant_id, reference_type, reference_id);
CREATE INDEX stock_movements_time_idx ON stock_movements (tenant_id, occurred_at);

ALTER TABLE stock_movements ENABLE ROW LEVEL SECURITY;
ALTER TABLE stock_movements FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON stock_movements
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
-- The ledger is append-only for the application.
REVOKE UPDATE, DELETE ON stock_movements FROM nexusops_app;
```

- [ ] **Step 4: Small types and validation helpers**

```java
package com.nexusops.inventory;

import java.util.UUID;

/** A stock level's identity. Natural order = lock order (product, then warehouse), so locks can't deadlock. */
public record StockKey(UUID productId, UUID warehouseId) implements Comparable<StockKey> {

    @Override
    public int compareTo(StockKey other) {
        int byProduct = productId.compareTo(other.productId);
        return byProduct != 0 ? byProduct : warehouseId.compareTo(other.warehouseId);
    }
}
```

```java
package com.nexusops.inventory;

public enum MovementKind {
    RECEIPT, ISSUE, ADJUSTMENT, TRANSFER_OUT, TRANSFER_IN
}
```

```java
package com.nexusops.inventory;

public enum ReferenceType {
    PURCHASE_ORDER, SALES_ORDER, TRANSFER, ADJUSTMENT
}
```

```java
package com.nexusops.inventory;

import com.nexusops.shared.Decimals;
import com.nexusops.shared.web.ApiProblem;
import java.math.BigDecimal;

/** Quantity rules (D3). */
final class Quantities {

    private Quantities() {}

    static BigDecimal positive(BigDecimal value, String field) {
        return Decimals.positive(value, field, "Enter a quantity greater than 0.");
    }

    static BigDecimal count(BigDecimal value, String field) {
        if (value == null) {
            throw ApiProblem.badRequestField(field, "Enter 0 or more.");
        }
        return Decimals.nonNegative(value, field, "Enter 0 or more.");
    }
}
```

```java
package com.nexusops.inventory;

import java.util.UUID;

public record ProductRef(UUID id, String sku, String name, String unit) {}
```

```java
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
```

`inventory/InventoryProducts.java`:

```java
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
```

- [ ] **Step 5: Domain**

`inventory/domain/StockLevel.java`:

```java
package com.nexusops.inventory.domain;

import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Rows are created by StockLedger (INSERT … ON CONFLICT DO NOTHING) and only ever changed under a row lock. */
@Entity
@Table(name = "stock_levels")
public class StockLevel extends TenantOwnedEntity {

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private UUID warehouseId;

    @Column(name = "on_hand", nullable = false, precision = 19, scale = 4)
    private BigDecimal onHand;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal reserved;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected StockLevel() {}

    public void changeOnHand(BigDecimal delta) {
        this.onHand = onHand.add(delta);
        this.updatedAt = Instant.now();
    }

    public void reserve(BigDecimal quantity) {
        this.reserved = reserved.add(quantity);
        this.updatedAt = Instant.now();
    }

    public void release(BigDecimal quantity) {
        this.reserved = reserved.subtract(quantity);
        this.updatedAt = Instant.now();
    }

    public BigDecimal getAvailable() {
        return onHand.subtract(reserved);
    }

    public UUID getProductId() {
        return productId;
    }

    public UUID getWarehouseId() {
        return warehouseId;
    }

    public BigDecimal getOnHand() {
        return onHand;
    }

    public BigDecimal getReserved() {
        return reserved;
    }
}
```

`inventory/domain/StockLevelRepository.java`:

```java
package com.nexusops.inventory.domain;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockLevelRepository extends JpaRepository<StockLevel, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from StockLevel l where l.productId = :productId and l.warehouseId = :warehouseId")
    Optional<StockLevel> lock(@Param("productId") UUID productId, @Param("warehouseId") UUID warehouseId);

    List<StockLevel> findByProductId(UUID productId);
}
```

`inventory/domain/StockMovement.java`:

```java
package com.nexusops.inventory.domain;

import com.nexusops.inventory.MovementKind;
import com.nexusops.inventory.ReferenceType;
import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** One ledger row (append-only: the app role has no UPDATE or DELETE grant). */
@Entity
@Immutable
@Table(name = "stock_movements")
public class StockMovement extends TenantOwnedEntity {

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MovementKind kind;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    @Column(name = "on_hand_after", nullable = false, precision = 19, scale = 4)
    private BigDecimal onHandAfter;

    @Enumerated(EnumType.STRING)
    @Column(name = "reference_type", nullable = false)
    private ReferenceType referenceType;

    @Column(name = "reference_id", nullable = false)
    private UUID referenceId;

    private String reason;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected StockMovement() {}

    public StockMovement(UUID id, UUID productId, UUID warehouseId, MovementKind kind, BigDecimal quantity,
            BigDecimal onHandAfter, ReferenceType referenceType, UUID referenceId, String reason, UUID actorId,
            Instant occurredAt) {
        super(id);
        this.productId = productId;
        this.warehouseId = warehouseId;
        this.kind = kind;
        this.quantity = quantity;
        this.onHandAfter = onHandAfter;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.reason = reason;
        this.actorId = actorId;
        this.occurredAt = occurredAt;
    }

    public UUID getProductId() {
        return productId;
    }

    public UUID getWarehouseId() {
        return warehouseId;
    }

    public MovementKind getKind() {
        return kind;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getOnHandAfter() {
        return onHandAfter;
    }

    public ReferenceType getReferenceType() {
        return referenceType;
    }

    public UUID getReferenceId() {
        return referenceId;
    }

    public String getReason() {
        return reason;
    }

    public UUID getActorId() {
        return actorId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
```

`inventory/domain/StockMovementRepository.java`:

```java
package com.nexusops.inventory.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface StockMovementRepository extends JpaRepository<StockMovement, UUID>,
        JpaSpecificationExecutor<StockMovement> {}
```

- [ ] **Step 6: The ledger**

`inventory/StockLedger.java`:

```java
package com.nexusops.inventory;

import com.nexusops.inventory.domain.StockLevel;
import com.nexusops.inventory.domain.StockLevelRepository;
import com.nexusops.inventory.domain.StockMovement;
import com.nexusops.inventory.domain.StockMovementRepository;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only writer of stock (D5, D6). Callers lock the levels they need (one call, all keys), check availability, then
 * move, reserve or release. Every on-hand change appends one ledger row in the same transaction; the table CHECKs
 * reject negative stock and over-reservation if a caller ever gets it wrong.
 */
@Component
class StockLedger {

    private static final String CREATE_LEVEL = """
            insert into stock_levels (id, tenant_id, product_id, warehouse_id, on_hand, reserved, updated_at, version)
            values (?, ?, ?, ?, 0, 0, now(), 0)
            on conflict (tenant_id, product_id, warehouse_id) do nothing
            """;

    private final StockLevelRepository levels;
    private final StockMovementRepository movements;
    private final JdbcTemplate jdbc;

    StockLedger(StockLevelRepository levels, StockMovementRepository movements, JdbcTemplate jdbc) {
        this.levels = levels;
        this.movements = movements;
        this.jdbc = jdbc;
    }

    /** Creates missing level rows, then locks every row in key order. Must run inside the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    Map<StockKey, StockLevel> lock(Collection<StockKey> keys) {
        UUID tenant = TenantContext.requireTenantId();
        List<StockKey> sorted = keys.stream().distinct().sorted().toList();
        for (StockKey key : sorted) {
            jdbc.update(CREATE_LEVEL, Ids.newId(), tenant, key.productId(), key.warehouseId());
        }
        Map<StockKey, StockLevel> locked = new LinkedHashMap<>();
        for (StockKey key : sorted) {
            locked.put(key, levels.lock(key.productId(), key.warehouseId())
                    .orElseThrow(() -> new IllegalStateException("Stock level vanished: " + key)));
        }
        return locked;
    }

    /** Changes on-hand by {@code delta} (signed) and appends the ledger row. The level must be locked. */
    @Transactional(propagation = Propagation.MANDATORY)
    StockMovement move(StockLevel level, MovementKind kind, BigDecimal delta, ReferenceType referenceType,
            UUID referenceId, String reason) {
        level.changeOnHand(delta);
        levels.flush();
        StockMovement movement = new StockMovement(Ids.newId(), level.getProductId(), level.getWarehouseId(), kind,
                delta, level.getOnHand(), referenceType, referenceId, reason, TenantContext.userId().orElse(null),
                Instant.now());
        movements.saveAndFlush(movement);
        return movement;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void reserve(StockLevel level, BigDecimal quantity) {
        level.reserve(quantity);
        levels.flush();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void release(StockLevel level, BigDecimal quantity) {
        level.release(quantity);
        levels.flush();
    }
}
```

- [ ] **Step 7: Commands, views and the stock service**

```java
package com.nexusops.inventory;

import java.math.BigDecimal;
import java.util.UUID;

/** A stock count (D7). */
public record AdjustCommand(UUID productId, UUID warehouseId, BigDecimal countedQuantity, String reason) {}
```

```java
package com.nexusops.inventory;

import java.math.BigDecimal;
import java.util.UUID;

/** D8. */
public record TransferCommand(UUID productId, UUID fromWarehouseId, UUID toWarehouseId, BigDecimal quantity,
        String note) {}
```

```java
package com.nexusops.inventory;

import java.math.BigDecimal;
import java.util.List;

/** A product's stock in every active warehouse (zeros included) and in total. */
public record ProductStock(ProductRef product, List<Level> levels, BigDecimal onHand, BigDecimal reserved,
        BigDecimal available) {

    public record Level(WarehouseRef warehouse, BigDecimal onHand, BigDecimal reserved, BigDecimal available) {}
}
```

```java
package com.nexusops.inventory;

import com.nexusops.collaboration.MemberRef;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record MovementView(UUID id, ProductRef product, WarehouseRef warehouse, MovementKind kind, BigDecimal quantity,
        BigDecimal onHandAfter, ReferenceType referenceType, UUID referenceId, String reason, MemberRef actor,
        Instant occurredAt) {}
```

```java
package com.nexusops.inventory;

import java.util.UUID;

public record MovementQuery(UUID productId, UUID warehouseId, MovementKind kind) {}
```

`inventory/StockService.java`:

```java
package com.nexusops.inventory;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.catalog.ProductBrief;
import com.nexusops.catalog.ProductKind;
import com.nexusops.collaboration.MemberRef;
import com.nexusops.identity.Members;
import com.nexusops.inventory.domain.StockLevel;
import com.nexusops.inventory.domain.StockLevelRepository;
import com.nexusops.inventory.domain.StockMovement;
import com.nexusops.inventory.domain.StockMovementRepository;
import com.nexusops.inventory.domain.Warehouse;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.Text;
import com.nexusops.shared.web.ApiProblem;
import com.nexusops.shared.web.PageResponse;
import com.nexusops.shared.web.Paging;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stock reads, counts (D7) and transfers (D8). Orders move stock through StockLedger in their own services. */
@Service
public class StockService {

    static final String NOT_FOUND = "Record not found.";
    static final String BELOW_RESERVED = "Reserved stock can't be counted away. Release the reservations first.";
    static final String SAME_WAREHOUSE = "Choose a different warehouse.";

    private final StockLedger ledger;
    private final StockLevelRepository levels;
    private final StockMovementRepository movements;
    private final InventoryProducts products;
    private final WarehouseService warehouses;
    private final Members members;
    private final AuditService audit;

    StockService(StockLedger ledger, StockLevelRepository levels, StockMovementRepository movements,
            InventoryProducts products, WarehouseService warehouses, Members members, AuditService audit) {
        this.ledger = ledger;
        this.levels = levels;
        this.movements = movements;
        this.products = products;
        this.warehouses = warehouses;
        this.members = members;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public ProductStock productStock(UUID productId) {
        TenantContext.requireTenantId();
        ProductBrief product = products.briefs(List.of(productId)).get(productId);
        if (product == null) {
            throw ApiProblem.notFound(NOT_FOUND);
        }
        if (product.kind() != ProductKind.GOODS) {
            throw ApiProblem.badRequest(InventoryProducts.SERVICE);
        }
        Map<UUID, StockLevel> byWarehouse = levels.findByProductId(productId).stream()
                .collect(Collectors.toMap(StockLevel::getWarehouseId, Function.identity()));
        List<ProductStock.Level> rows = new ArrayList<>();
        BigDecimal onHand = BigDecimal.ZERO;
        BigDecimal reserved = BigDecimal.ZERO;
        for (WarehouseView w : warehouses.list(false)) {
            StockLevel level = byWarehouse.get(w.id());
            BigDecimal h = level == null ? BigDecimal.ZERO : level.getOnHand();
            BigDecimal r = level == null ? BigDecimal.ZERO : level.getReserved();
            rows.add(new ProductStock.Level(new WarehouseRef(w.id(), w.code(), w.name()), h, r, h.subtract(r)));
            onHand = onHand.add(h);
            reserved = reserved.add(r);
        }
        return new ProductStock(InventoryProducts.ref(product), rows, onHand, reserved, onHand.subtract(reserved));
    }

    @Transactional(readOnly = true)
    public PageResponse<MovementView> movements(MovementQuery query, Integer page, Integer size) {
        TenantContext.requireTenantId();
        Specification<StockMovement> spec = (root, cq, cb) -> cb.conjunction();
        if (query.productId() != null) {
            spec = spec.and((root, cq, cb) -> cb.equal(root.get("productId"), query.productId()));
        }
        if (query.warehouseId() != null) {
            spec = spec.and((root, cq, cb) -> cb.equal(root.get("warehouseId"), query.warehouseId()));
        }
        if (query.kind() != null) {
            spec = spec.and((root, cq, cb) -> cb.equal(root.get("kind"), query.kind()));
        }
        Page<StockMovement> result = movements.findAll(spec,
                Paging.of(page, size, Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id"))));
        return new PageResponse<>(views(result.getContent()), result.getNumber(), result.getSize(),
                result.getTotalElements());
    }

    @Transactional
    public ProductStock adjust(AdjustCommand command) {
        TenantContext.requireTenantId();
        BigDecimal counted = Quantities.count(command.countedQuantity(), "countedQuantity");
        String reason = Text.required(command.reason(), 200, "reason");
        ProductBrief product = products.requireStockable(command.productId(), "productId");
        Warehouse warehouse = warehouses.requireActive(command.warehouseId(), "warehouseId");
        StockKey key = new StockKey(product.id(), warehouse.getId());
        StockLevel level = ledger.lock(List.of(key)).get(key);
        if (counted.compareTo(level.getReserved()) < 0) {
            throw ApiProblem.conflict(BELOW_RESERVED);
        }
        BigDecimal before = level.getOnHand();
        BigDecimal delta = counted.subtract(before);
        if (delta.signum() != 0) {
            ledger.move(level, MovementKind.ADJUSTMENT, delta, ReferenceType.ADJUSTMENT, Ids.newId(), reason);
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("warehouseId", warehouse.getId().toString());
            after.put("onHand", level.getOnHand().toPlainString());
            after.put("reason", reason);
            audit.record(AuditEntry.of("StockAdjusted", "Product", product.id())
                    .withBefore(Map.of("warehouseId", warehouse.getId().toString(), "onHand", before.toPlainString()))
                    .withAfter(after));
        }
        return productStock(product.id());
    }

    @Transactional
    public ProductStock transfer(TransferCommand command) {
        TenantContext.requireTenantId();
        BigDecimal quantity = Quantities.positive(command.quantity(), "quantity");
        String note = Text.optional(command.note(), 200, "note");
        if (command.fromWarehouseId() != null && command.fromWarehouseId().equals(command.toWarehouseId())) {
            throw ApiProblem.badRequestField("toWarehouseId", SAME_WAREHOUSE);
        }
        ProductBrief product = products.requireStockable(command.productId(), "productId");
        Warehouse from = warehouses.requireActive(command.fromWarehouseId(), "fromWarehouseId");
        Warehouse to = warehouses.requireActive(command.toWarehouseId(), "toWarehouseId");
        StockKey fromKey = new StockKey(product.id(), from.getId());
        StockKey toKey = new StockKey(product.id(), to.getId());
        Map<StockKey, StockLevel> locked = ledger.lock(List.of(fromKey, toKey));
        StockLevel source = locked.get(fromKey);
        if (source.getAvailable().compareTo(quantity) < 0) {
            throw Shortage.conflict(List.of(new Shortage(product.id(), product.sku(), quantity, source.getAvailable())));
        }
        UUID transferId = Ids.newId();
        ledger.move(source, MovementKind.TRANSFER_OUT, quantity.negate(), ReferenceType.TRANSFER, transferId, note);
        ledger.move(locked.get(toKey), MovementKind.TRANSFER_IN, quantity, ReferenceType.TRANSFER, transferId, note);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("fromWarehouseId", from.getId().toString());
        after.put("toWarehouseId", to.getId().toString());
        after.put("quantity", quantity.toPlainString());
        if (note != null) {
            after.put("note", note);
        }
        audit.record(AuditEntry.of("StockTransferred", "Product", product.id()).withAfter(after));
        return productStock(product.id());
    }

    List<MovementView> views(List<StockMovement> page) {
        Map<UUID, ProductBrief> productNames = products.briefs(page.stream().map(StockMovement::getProductId)
                .collect(Collectors.toSet()));
        Map<UUID, Warehouse> warehouseNames = warehouses.byIds(page.stream().map(StockMovement::getWarehouseId)
                .collect(Collectors.toSet()));
        Map<UUID, Members.Member> people = members.findAll(page.stream().map(StockMovement::getActorId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        return page.stream().map(m -> {
            Members.Member actor = m.getActorId() == null ? null : people.get(m.getActorId());
            return new MovementView(m.getId(), InventoryProducts.ref(productNames.get(m.getProductId())),
                    WarehouseService.ref(warehouseNames.get(m.getWarehouseId())), m.getKind(), m.getQuantity(),
                    m.getOnHandAfter(), m.getReferenceType(), m.getReferenceId(), m.getReason(),
                    actor == null ? null : new MemberRef(actor.id(), actor.name()), m.getOccurredAt());
        }).toList();
    }
}
```

- [ ] **Step 8: Warehouse archive refuses stock**

Replace `WarehouseService.requireUnused` with:

```java
    /**
     * Refuses archiving while the warehouse is in use: it holds stock (here), or open orders use it (Tasks 3 and 4
     * add those checks). JDBC with an explicit tenant predicate on top of RLS.
     */
    private void requireUnused(Warehouse warehouse) {
        UUID tenant = TenantContext.requireTenantId();
        Boolean stocked = jdbc.queryForObject("select exists (select 1 from stock_levels "
                + "where tenant_id = ? and warehouse_id = ? and on_hand > 0)", Boolean.class, tenant, warehouse.getId());
        if (Boolean.TRUE.equals(stocked)) {
            throw ApiProblem.conflict("Move or count out this warehouse's stock first.");
        }
    }
```

- [ ] **Step 9: Web layer**

Add to `InventoryDtos`:

```java
    record AdjustRequest(UUID productId, UUID warehouseId, BigDecimal countedQuantity, String reason) {
        AdjustCommand command() {
            return new AdjustCommand(productId, warehouseId, countedQuantity, reason);
        }
    }

    record TransferRequest(UUID productId, UUID fromWarehouseId, UUID toWarehouseId, BigDecimal quantity, String note) {
        TransferCommand command() {
            return new TransferCommand(productId, fromWarehouseId, toWarehouseId, quantity, note);
        }
    }
```

(imports: `java.math.BigDecimal`, `java.util.UUID`, `com.nexusops.inventory.AdjustCommand`, `com.nexusops.inventory.TransferCommand`.)

`inventory/web/StockController.java`:

```java
package com.nexusops.inventory.web;

import com.nexusops.inventory.MovementKind;
import com.nexusops.inventory.MovementQuery;
import com.nexusops.inventory.MovementView;
import com.nexusops.inventory.ProductStock;
import com.nexusops.inventory.StockService;
import com.nexusops.inventory.web.InventoryDtos.AdjustRequest;
import com.nexusops.inventory.web.InventoryDtos.TransferRequest;
import com.nexusops.shared.web.PageResponse;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/inventory")
class StockController {

    private final StockService stock;

    StockController(StockService stock) {
        this.stock = stock;
    }

    @GetMapping("/stock/products/{productId}")
    @PreAuthorize("hasAuthority('inventory.stock.read')")
    ProductStock productStock(@PathVariable UUID productId) {
        return stock.productStock(productId);
    }

    @GetMapping("/movements")
    @PreAuthorize("hasAuthority('inventory.stock.read')")
    PageResponse<MovementView> movements(@RequestParam(required = false) UUID productId,
            @RequestParam(required = false) UUID warehouseId, @RequestParam(required = false) MovementKind kind,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return stock.movements(new MovementQuery(productId, warehouseId, kind), page, size);
    }

    @PostMapping("/adjustments")
    @PreAuthorize("hasAuthority('inventory.stock.adjust')")
    ProductStock adjust(@RequestBody AdjustRequest request) {
        return stock.adjust(request.command());
    }

    @PostMapping("/transfers")
    @PreAuthorize("hasAuthority('inventory.stock.adjust')")
    ProductStock transfer(@RequestBody TransferRequest request) {
        return stock.transfer(request.command());
    }
}
```

- [ ] **Step 10: Run the tests to verify they pass**

Run: `cd backend && ./gradlew test --tests '*StockApiIT' --tests '*WarehouseApiIT' --tests '*ModularityTest' --tests '*EndpointAuthorizationCoverageTest' --tests '*RlsCoverageIT'`
Expected: PASS. If the pessimistic-lock query is rejected because the entity isn't loaded in a transaction, confirm `adjust`/`transfer` are `@Transactional` and called through the controller (proxy).

- [ ] **Step 11: Run the whole backend suite, then commit**

Run: `cd backend && ./gradlew test` — Expected: PASS.

```bash
git add backend/src/main/resources/db/migration/V19__stock.sql backend/src/main/java/com/nexusops/inventory \
  backend/src/test/java/com/nexusops/inventory/StockApiIT.java
git commit -m "feat(inventory): stock levels with an append-only ledger, counts and transfers"
```

---
### Task 3: Purchase orders with numbered documents and partial receipts

**Files:**
- Create: `backend/src/main/resources/db/migration/V20__purchase_orders.sql`
- Create: `backend/src/main/java/com/nexusops/inventory/SequenceKind.java`, `NumberSequences.java`, `Orders.java`, `OrderParties.java`, `PurchaseOrderStatus.java`, `PurchaseLineCommand.java`, `PurchaseOrderCommand.java`, `ReceiptCommand.java`, `PurchaseLineView.java`, `PurchaseOrderView.java`, `PurchaseOrderSummary.java`, `PurchaseOrderQuery.java`, `PurchaseOrderService.java`, `PurchaseOrderSubjects.java`
- Create: `backend/src/main/java/com/nexusops/inventory/domain/PurchaseOrder.java`, `PurchaseOrderLine.java`, `PurchaseOrderRepository.java`, `PurchaseOrderLineRepository.java`
- Create: `backend/src/main/java/com/nexusops/inventory/web/PurchaseOrderController.java`; Modify: `web/InventoryDtos.java`
- Modify: `backend/src/main/java/com/nexusops/inventory/WarehouseService.java` (`requireUnused`: open purchase orders)
- Test: `backend/src/test/java/com/nexusops/inventory/PurchaseOrderApiIT.java`

**Interfaces:**
- Consumes (Tasks 1–2): `WarehouseService.requireActive/byIds/ref`, `InventoryProducts.requireStockable/briefs/ref`, `Quantities.positive`, `StockLedger.lock/move`, `StockKey`, `MovementKind.RECEIPT`, `ReferenceType.PURCHASE_ORDER`, `PartyService.ensureRole`, `Decimals`, `Currencies`, `Members`, `TestInventory`.
- Produces:
  - `enum SequenceKind {PURCHASE_ORDER("PO-"), SALES_ORDER("SO-")}` with `prefix()`; `NumberSequences.next(SequenceKind) : String` (e.g. `PO-00001`).
  - `Orders` (package-private, shared by both order kinds): constants `NOT_FOUND`, `STALE`, `FORBIDDEN`, `ARCHIVED`; `static String field(int index, String name)` → `"lines[" + index + "]." + name`; `static void requireCount(List<?> lines)` (null/empty → 400 `lines` "Add at least one line."; > 100 → 400 `lines` "Use at most 100 lines."); `static void checkVersion(long current, Long version)` (null → 400 `version` "Reload the record and try again."; mismatch → 409 STALE); `static BigDecimal lineTotal(BigDecimal quantity, BigDecimal price)` (scale 4, HALF_UP).
  - `OrderParties` (package-private `@Component`): `void requireUsable(UUID id, String field, String missingMessage)` (null → 400 `field` `missingMessage`; no `directory.party.read` → 403; unknown → 400 `field` "Choose a person or organization in this workspace."; archived → 409), `void requireNotArchived(UUID id)` (archived or gone → 409 ARCHIVED), `Map<UUID, PartyRef> refs(Collection<UUID>)`.
  - `enum PurchaseOrderStatus {DRAFT, ORDERED, PARTIALLY_RECEIVED, RECEIVED, CANCELLED}`.
  - `PurchaseLineCommand(UUID productId, BigDecimal quantity, BigDecimal unitCost)`; `PurchaseOrderCommand(UUID supplierId, UUID warehouseId, String currency, LocalDate expectedOn, String notes, List<PurchaseLineCommand> lines)`; `ReceiptCommand(List<ReceiptCommand.Line> lines)`, `ReceiptCommand.Line(UUID lineId, BigDecimal quantity)`.
  - `PurchaseLineView(UUID id, int lineNo, ProductRef product, BigDecimal quantity, BigDecimal receivedQuantity, BigDecimal remainingQuantity, BigDecimal unitCost, BigDecimal lineTotal)`.
  - `PurchaseOrderView(UUID id, String number, PartyRef supplier, WarehouseRef warehouse, PurchaseOrderStatus status, String currency, LocalDate expectedOn, String notes, List<PurchaseLineView> lines, BigDecimal total, Instant orderedAt, Instant receivedAt, Instant cancelledAt, MemberRef createdBy, Instant createdAt, Instant updatedAt, long version)`.
  - `PurchaseOrderSummary(UUID id, String number, PartyRef supplier, WarehouseRef warehouse, PurchaseOrderStatus status, String currency, BigDecimal total, int lineCount, LocalDate expectedOn, Instant createdAt)`.
  - `PurchaseOrderQuery(String q, PurchaseOrderStatus status, UUID supplierId, UUID warehouseId)`.
  - `PurchaseOrderService` public `get`, `list`, `create(PurchaseOrderCommand)`, `update(UUID, PurchaseOrderCommand, Long)`, `order(UUID, Long)`, `cancel(UUID, Long)`, `receive(UUID, ReceiptCommand, Long)`.
  - `PurchaseOrderSubjects` type `"PURCHASE_ORDER"` (label = number; search by number).
  - Routes under `/api/v1/purchase-orders`: `GET`, `GET /{id}`, `POST`, `PUT /{id}`, `POST /{id}/order`, `POST /{id}/cancel`, `POST /{id}/receipts`; state-change bodies carry `{version}` (receipts: `{lines, version}`).

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/nexusops/inventory/PurchaseOrderApiIT.java`:

```java
package com.nexusops.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestInventory;
import com.nexusops.support.TestMembers;
import com.nexusops.support.TestRoles;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Workspace;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@AutoConfigureMockMvc
class PurchaseOrderApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;
    UUID supplier, widget, gadget, main;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("po"));
        owner = Api.login(mvc, ws);
        TestInventory.enable(owner);
        supplier = Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Konkan Supplies\"}"));
        widget = TestInventory.goods(owner, "W-1", "Widget");
        gadget = TestInventory.goods(owner, "G-1", "Gadget");
        main = TestInventory.mainWarehouse(owner);
    }

    private String body(UUID warehouse, String lines) {
        return "{\"supplierId\":\"" + supplier + "\",\"warehouseId\":\"" + warehouse + "\",\"lines\":[" + lines + "]}";
    }

    private String line(UUID product, String quantity, String cost) {
        return "{\"productId\":\"" + product + "\",\"quantity\":" + quantity + ",\"unitCost\":" + cost + "}";
    }

    private UUID draft() throws Exception {
        return Api.id(owner.post("/api/v1/purchase-orders", body(main, line(widget, "10", "2.5") + "," + line(gadget, "4", "10")))
                .andExpect(status().isCreated()));
    }

    private ResultActions receive(UUID order, String lines, long version) throws Exception {
        return owner.post("/api/v1/purchase-orders/" + order + "/receipts", "{\"lines\":[" + lines + "],\"version\":" + version + "}");
    }

    private String lineIds(UUID order, int index) throws Exception {
        List<String> ids = Api.read(owner.get("/api/v1/purchase-orders/" + order), "$.lines[*].id");
        return ids.get(index);
    }

    private long audits(String action) {
        return OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select count(*) from audit_events where action = ?", Long.class, action);
    }

    @Test
    void createsNumberedDrafts() throws Exception {
        owner.post("/api/v1/purchase-orders", body(main, line(widget, "10", "2.5") + "," + line(gadget, "4", "10")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.number").value("PO-00001"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.supplier.name").value("Konkan Supplies"))
                .andExpect(jsonPath("$.warehouse.code").value("MAIN"))
                .andExpect(jsonPath("$.lines[*].lineNo").value(Matchers.contains(1, 2)))
                .andExpect(jsonPath("$.lines[0].product.sku").value("W-1"))
                .andExpect(jsonPath("$.lines[0].lineTotal").value(25.0))
                .andExpect(jsonPath("$.total").value(65.0));
        owner.post("/api/v1/purchase-orders", body(main, line(widget, "1", "2"))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.number").value("PO-00002"));
        // a draft doesn't make the party a supplier yet
        owner.get("/api/v1/parties/" + supplier).andExpect(jsonPath("$.roles", Matchers.empty()));
    }

    @Test
    void invalidOrdersAreFieldErrors() throws Exception {
        UUID service = Api.id(owner.post("/api/v1/products", "{\"sku\":\"S-1\",\"name\":\"Setup\",\"kind\":\"SERVICE\"}"));
        String[][] cases = {
                {body(main, ""), "lines"},
                {body(main, line(widget, "1", "1") + "," + line(widget, "2", "1")), "lines[1].productId"},
                {body(main, line(widget, "0", "1")), "lines[0].quantity"},
                {body(main, line(widget, "1", "-1")), "lines[0].unitCost"},
                {body(main, "{\"productId\":\"" + widget + "\",\"quantity\":1}"), "lines[0].unitCost"},
                {body(main, line(service, "1", "1")), "lines[0].productId"},
                {body(main, line(UUID.randomUUID(), "1", "1")), "lines[0].productId"},
                {body(UUID.randomUUID(), line(widget, "1", "1")), "warehouseId"},
                {"{\"warehouseId\":\"" + main + "\",\"lines\":[" + line(widget, "1", "1") + "]}", "supplierId"},
                {body(main, line(widget, "1", "1")).replace(supplier.toString(), UUID.randomUUID().toString()), "supplierId"},
                {body(main, line(widget, "1", "1")).replace("{\"supplierId\"", "{\"currency\":\"EURO\",\"supplierId\""), "currency"},
        };
        for (String[] c : cases) {
            owner.post("/api/v1/purchase-orders", c[0]).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value(c[1]));
        }
    }

    @Test
    void onlyDraftsAreEditedAndOrderingMakesTheSupplier() throws Exception {
        UUID order = draft();
        // no version in the body
        owner.put("/api/v1/purchase-orders/" + order, body(main, line(gadget, "3", "9")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("version"));
        owner.put("/api/v1/purchase-orders/" + order, "{\"supplierId\":\"" + supplier + "\",\"warehouseId\":\"" + main
                        + "\",\"expectedOn\":\"2026-11-15\",\"notes\":\"Call before delivery\",\"lines\":["
                        + line(gadget, "3", "9") + "],\"version\":0}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.expectedOn").value("2026-11-15")).andExpect(jsonPath("$.total").value(27.0));
        owner.post("/api/v1/purchase-orders/" + order + "/order", "{\"version\":0}").andExpect(status().isConflict());
        owner.post("/api/v1/purchase-orders/" + order + "/order", "{\"version\":1}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ORDERED")).andExpect(jsonPath("$.orderedAt").exists());
        owner.get("/api/v1/parties/" + supplier).andExpect(jsonPath("$.roles[?(@.role == 'SUPPLIER')].status")
                .value(Matchers.contains("ACTIVE")));
        owner.put("/api/v1/purchase-orders/" + order, "{\"supplierId\":\"" + supplier + "\",\"warehouseId\":\"" + main
                + "\",\"lines\":[" + line(gadget, "1", "1") + "],\"version\":2}").andExpect(status().isConflict());
        owner.post("/api/v1/purchase-orders/" + order + "/order", "{\"version\":2}").andExpect(status().isConflict());
    }

    @Test
    void partialReceiptsCompleteTheOrderAndThenStop() throws Exception {
        UUID order = draft();
        owner.post("/api/v1/purchase-orders/" + order + "/order", "{\"version\":0}").andExpect(status().isOk());
        String first = lineIds(order, 0);
        String second = lineIds(order, 1);
        receive(order, "{\"lineId\":\"" + first + "\",\"quantity\":4}", 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIALLY_RECEIVED"))
                .andExpect(jsonPath("$.lines[0].receivedQuantity").value(4.0))
                .andExpect(jsonPath("$.lines[0].remainingQuantity").value(6.0));
        owner.get("/api/v1/inventory/stock/products/" + widget).andExpect(jsonPath("$.onHand").value(4.0));
        receive(order, "{\"lineId\":\"" + first + "\",\"quantity\":7}", 2).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("lines[0].quantity"));
        receive(order, "{\"lineId\":\"" + first + "\",\"quantity\":6},{\"lineId\":\"" + second + "\",\"quantity\":4}", 2)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.receivedAt").exists());
        receive(order, "{\"lineId\":\"" + first + "\",\"quantity\":1}", 3).andExpect(status().isConflict());
        owner.get("/api/v1/inventory/movements?productId=" + widget).andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].kind").value("RECEIPT"))
                .andExpect(jsonPath("$.items[0].referenceType").value("PURCHASE_ORDER"))
                .andExpect(jsonPath("$.items[0].referenceId").value(order.toString()))
                .andExpect(jsonPath("$.items[0].reason").value("PO-00001"));
        owner.get("/api/v1/inventory/stock/products/" + gadget).andExpect(jsonPath("$.onHand").value(4.0));
        assertThat(audits("PurchaseOrderReceived")).isEqualTo(2);
    }

    @Test
    void receiptsNeedAnOrderedOrderAndItsOwnLines() throws Exception {
        UUID order = draft();
        String first = lineIds(order, 0);
        receive(order, "{\"lineId\":\"" + first + "\",\"quantity\":1}", 0).andExpect(status().isConflict());
        owner.post("/api/v1/purchase-orders/" + order + "/order", "{\"version\":0}").andExpect(status().isOk());
        receive(order, "{\"lineId\":\"" + UUID.randomUUID() + "\",\"quantity\":1}", 1).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("lines[0].lineId"));
        receive(order, "", 1).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("lines"));
        receive(order, "{\"lineId\":\"" + first + "\",\"quantity\":1},{\"lineId\":\"" + first + "\",\"quantity\":1}", 1)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("lines[1].lineId"));
    }

    @Test
    void cancellation() throws Exception {
        UUID drafted = draft();
        owner.post("/api/v1/purchase-orders/" + drafted + "/cancel", "{\"version\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED")).andExpect(jsonPath("$.cancelledAt").exists());
        UUID ordered = draft();
        owner.post("/api/v1/purchase-orders/" + ordered + "/order", "{\"version\":0}").andExpect(status().isOk());
        owner.post("/api/v1/purchase-orders/" + ordered + "/cancel", "{\"version\":1}").andExpect(status().isOk());
        UUID received = draft();
        owner.post("/api/v1/purchase-orders/" + received + "/order", "{\"version\":0}").andExpect(status().isOk());
        receive(received, "{\"lineId\":\"" + lineIds(received, 0) + "\",\"quantity\":1}", 1).andExpect(status().isOk());
        owner.post("/api/v1/purchase-orders/" + received + "/cancel", "{\"version\":2}").andExpect(status().isConflict());
        assertThat(audits("PurchaseOrderCancelled")).isEqualTo(2);
    }

    @Test
    void aWarehouseWithOpenPurchaseOrdersCannotBeArchived() throws Exception {
        UUID pune = Api.id(owner.post("/api/v1/inventory/warehouses", "{\"code\":\"PUNE\",\"name\":\"Pune\"}"));
        UUID order = Api.id(owner.post("/api/v1/purchase-orders", body(pune, line(widget, "1", "1"))));
        owner.post("/api/v1/inventory/warehouses/" + pune + "/archive", "").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Close or move this warehouse's open orders first."));
        owner.post("/api/v1/purchase-orders/" + order + "/cancel", "{\"version\":0}").andExpect(status().isOk());
        owner.post("/api/v1/inventory/warehouses/" + pune + "/archive", "").andExpect(status().isOk());
    }

    @Test
    void listsAndFilters() throws Exception {
        UUID first = draft();
        UUID second = draft();
        owner.post("/api/v1/purchase-orders/" + second + "/order", "{\"version\":0}").andExpect(status().isOk());
        owner.get("/api/v1/purchase-orders").andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].number").value("PO-00002"))
                .andExpect(jsonPath("$.items[0].lineCount").value(2))
                .andExpect(jsonPath("$.items[0].total").value(65.0));
        owner.get("/api/v1/purchase-orders?status=ORDERED").andExpect(jsonPath("$.items[*].id").value(Matchers.contains(second.toString())));
        owner.get("/api/v1/purchase-orders?q=00001").andExpect(jsonPath("$.items[*].id").value(Matchers.contains(first.toString())));
        owner.get("/api/v1/purchase-orders?supplierId=" + supplier).andExpect(jsonPath("$.total").value(2));
        owner.get("/api/v1/purchase-orders?warehouseId=" + main).andExpect(jsonPath("$.total").value(2));
        owner.get("/api/v1/purchase-orders/" + UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void permissionsAndRecordPanels() throws Exception {
        UUID order = draft();
        owner.post("/api/v1/activities", "{\"subjectType\":\"PURCHASE_ORDER\",\"subjectId\":\"" + order
                + "\",\"type\":\"NOTE\",\"summary\":\"Asked for delivery on Friday\"}").andExpect(status().isCreated());
        UUID readerRole = TestRoles.create(mvc, owner.session(), "PO reader", "inventory.purchase.read");
        Api reader = Api.login(mvc, members.create(ws.tenantId(), Set.of(readerRole)));
        reader.get("/api/v1/purchase-orders/" + order).andExpect(status().isOk());
        reader.post("/api/v1/purchase-orders", body(main, line(widget, "1", "1"))).andExpect(status().isForbidden());
        UUID blindRole = TestRoles.create(mvc, owner.session(), "Buyer without directory", "inventory.purchase.read",
                "inventory.purchase.manage");
        Api blind = Api.login(mvc, members.create(ws.tenantId(), Set.of(blindRole)));
        blind.post("/api/v1/purchase-orders", body(main, line(widget, "1", "1"))).andExpect(status().isForbidden());
        assertThat(audits("PurchaseOrderCreated")).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./gradlew test --tests '*PurchaseOrderApiIT'`
Expected: FAIL — 404 on `/api/v1/purchase-orders`.

- [ ] **Step 3: Migration V20**

`backend/src/main/resources/db/migration/V20__purchase_orders.sql`:

```sql
-- Purchase orders (D9). Lines are editable while DRAFT; receipts raise received_quantity, never above quantity.
CREATE TABLE purchase_orders (
    id            uuid PRIMARY KEY,
    tenant_id     uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    number        text NOT NULL CHECK (number ~ '^PO-[0-9]{5,}$'),
    supplier_id   uuid NOT NULL,
    warehouse_id  uuid NOT NULL,
    status        text NOT NULL CHECK (status IN ('DRAFT', 'ORDERED', 'PARTIALLY_RECEIVED', 'RECEIVED', 'CANCELLED')),
    currency      text NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    expected_on   date,
    notes         text CHECK (notes IS NULL OR length(notes) <= 2000),
    ordered_at    timestamptz,
    received_at   timestamptz,
    cancelled_at  timestamptz,
    created_by    uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    version       bigint NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, number),
    CHECK ((status = 'RECEIVED') = (received_at IS NOT NULL)),
    CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL)),
    FOREIGN KEY (tenant_id, supplier_id) REFERENCES parties (tenant_id, id),
    FOREIGN KEY (tenant_id, warehouse_id) REFERENCES warehouses (tenant_id, id)
);
CREATE INDEX purchase_orders_status_idx ON purchase_orders (tenant_id, status, created_at);
CREATE INDEX purchase_orders_supplier_idx ON purchase_orders (tenant_id, supplier_id);
CREATE INDEX purchase_orders_warehouse_idx ON purchase_orders (tenant_id, warehouse_id);

ALTER TABLE purchase_orders ENABLE ROW LEVEL SECURITY;
ALTER TABLE purchase_orders FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON purchase_orders
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE purchase_order_lines (
    id                 uuid PRIMARY KEY,
    tenant_id          uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    order_id           uuid NOT NULL,
    line_no            integer NOT NULL CHECK (line_no >= 1),
    product_id         uuid NOT NULL,
    quantity           numeric(19, 4) NOT NULL CHECK (quantity > 0),
    received_quantity  numeric(19, 4) NOT NULL DEFAULT 0 CHECK (received_quantity >= 0),
    unit_cost          numeric(19, 4) NOT NULL CHECK (unit_cost >= 0),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, order_id, line_no),
    UNIQUE (tenant_id, order_id, product_id),
    CHECK (received_quantity <= quantity),
    FOREIGN KEY (tenant_id, order_id) REFERENCES purchase_orders (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, product_id) REFERENCES products (tenant_id, id)
);
CREATE INDEX purchase_order_lines_product_idx ON purchase_order_lines (tenant_id, product_id);

ALTER TABLE purchase_order_lines ENABLE ROW LEVEL SECURITY;
ALTER TABLE purchase_order_lines FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON purchase_order_lines
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
```

- [ ] **Step 4: Numbers and line helpers**

```java
package com.nexusops.inventory;

/** Per-tenant document number series. */
public enum SequenceKind {
    PURCHASE_ORDER("PO-"), SALES_ORDER("SO-");

    private final String prefix;

    SequenceKind(String prefix) {
        this.prefix = prefix;
    }

    public String prefix() {
        return prefix;
    }
}
```

```java
package com.nexusops.inventory;

import com.nexusops.shared.TenantContext;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Gap-free per tenant within committed orders: UPDATE … RETURNING takes the row lock until the transaction ends. */
@Component
class NumberSequences {

    private final JdbcTemplate jdbc;

    NumberSequences(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    String next(SequenceKind kind) {
        UUID tenant = TenantContext.requireTenantId();
        jdbc.update("insert into number_sequences (tenant_id, kind, next_value) values (?, ?, 1) on conflict do nothing",
                tenant, kind.name());
        Long value = jdbc.queryForObject("update number_sequences set next_value = next_value + 1 "
                + "where tenant_id = ? and kind = ? returning next_value - 1", Long.class, tenant, kind.name());
        return kind.prefix() + String.format("%05d", value);
    }
}
```

```java
package com.nexusops.inventory;

import com.nexusops.shared.web.ApiProblem;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** Rules shared by purchase and sales orders. Line field names follow the request: lines[i].name. */
final class Orders {

    static final int MAX_LINES = 100;
    static final String NOT_FOUND = "Record not found.";
    static final String STALE = "This record was changed by someone else. Reload and try again.";
    static final String FORBIDDEN = "You do not have permission to perform this action.";
    static final String ARCHIVED = "This record is archived.";

    private Orders() {}

    static String field(int index, String name) {
        return "lines[" + index + "]." + name;
    }

    static void requireCount(List<?> lines) {
        if (lines == null || lines.isEmpty()) {
            throw ApiProblem.badRequestField("lines", "Add at least one line.");
        }
        if (lines.size() > MAX_LINES) {
            throw ApiProblem.badRequestField("lines", "Use at most " + MAX_LINES + " lines.");
        }
    }

    static void checkVersion(long current, Long version) {
        if (version == null) {
            throw ApiProblem.badRequestField("version", "Reload the record and try again.");
        }
        if (current != version) {
            throw ApiProblem.conflict(STALE);
        }
    }

    static BigDecimal lineTotal(BigDecimal quantity, BigDecimal price) {
        return quantity.multiply(price).setScale(4, RoundingMode.HALF_UP);
    }
}
```

```java
package com.nexusops.inventory;

import com.nexusops.directory.DirectoryPermissions;
import com.nexusops.directory.PartyBrief;
import com.nexusops.directory.PartyRef;
import com.nexusops.directory.PartyService;
import com.nexusops.shared.security.CurrentAuthorities;
import com.nexusops.shared.web.ApiProblem;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Suppliers and customers as orders see them. */
@Component
class OrderParties {

    static final String UNKNOWN = "Choose a person or organization in this workspace.";

    private final PartyService parties;

    OrderParties(PartyService parties) {
        this.parties = parties;
    }

    /** For a newly chosen party: required, readable by the caller, in this workspace and not archived. */
    void requireUsable(UUID id, String field, String missingMessage) {
        if (id == null) {
            throw ApiProblem.badRequestField(field, missingMessage);
        }
        if (!CurrentAuthorities.has(DirectoryPermissions.PARTY_READ)) {
            throw ApiProblem.forbidden(Orders.FORBIDDEN);
        }
        PartyBrief party = parties.briefs(List.of(id)).get(id);
        if (party == null) {
            throw ApiProblem.badRequestField(field, UNKNOWN);
        }
        if (party.archived()) {
            throw ApiProblem.conflict(Orders.ARCHIVED);
        }
    }

    /** At the moment of commitment (order, confirm) the party must still be active. */
    void requireNotArchived(UUID id) {
        PartyBrief party = parties.briefs(List.of(id)).get(id);
        if (party == null || party.archived()) {
            throw ApiProblem.conflict(Orders.ARCHIVED);
        }
    }

    Map<UUID, PartyRef> refs(Collection<UUID> ids) {
        return parties.briefs(ids).values().stream()
                .collect(Collectors.toMap(PartyBrief::id, p -> new PartyRef(p.id(), p.name())));
    }
}
```

- [ ] **Step 5: Types**

```java
package com.nexusops.inventory;

public enum PurchaseOrderStatus {
    DRAFT, ORDERED, PARTIALLY_RECEIVED, RECEIVED, CANCELLED;

    boolean receivable() {
        return this == ORDERED || this == PARTIALLY_RECEIVED;
    }

    boolean cancellable() {
        return this == DRAFT || this == ORDERED;
    }
}
```

```java
package com.nexusops.inventory;

import java.math.BigDecimal;
import java.util.UUID;

public record PurchaseLineCommand(UUID productId, BigDecimal quantity, BigDecimal unitCost) {}
```

```java
package com.nexusops.inventory;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Raw input; currency defaults to the workspace currency. */
public record PurchaseOrderCommand(UUID supplierId, UUID warehouseId, String currency, LocalDate expectedOn,
        String notes, List<PurchaseLineCommand> lines) {}
```

```java
package com.nexusops.inventory;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ReceiptCommand(List<Line> lines) {

    public record Line(UUID lineId, BigDecimal quantity) {}
}
```

```java
package com.nexusops.inventory;

import java.math.BigDecimal;
import java.util.UUID;

public record PurchaseLineView(UUID id, int lineNo, ProductRef product, BigDecimal quantity,
        BigDecimal receivedQuantity, BigDecimal remainingQuantity, BigDecimal unitCost, BigDecimal lineTotal) {}
```

```java
package com.nexusops.inventory;

import com.nexusops.collaboration.MemberRef;
import com.nexusops.directory.PartyRef;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PurchaseOrderView(UUID id, String number, PartyRef supplier, WarehouseRef warehouse,
        PurchaseOrderStatus status, String currency, LocalDate expectedOn, String notes, List<PurchaseLineView> lines,
        BigDecimal total, Instant orderedAt, Instant receivedAt, Instant cancelledAt, MemberRef createdBy,
        Instant createdAt, Instant updatedAt, long version) {}
```

```java
package com.nexusops.inventory;

import com.nexusops.directory.PartyRef;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record PurchaseOrderSummary(UUID id, String number, PartyRef supplier, WarehouseRef warehouse,
        PurchaseOrderStatus status, String currency, BigDecimal total, int lineCount, LocalDate expectedOn,
        Instant createdAt) {}
```

```java
package com.nexusops.inventory;

import java.util.UUID;

public record PurchaseOrderQuery(String q, PurchaseOrderStatus status, UUID supplierId, UUID warehouseId) {}
```

- [ ] **Step 6: Domain**

`inventory/domain/PurchaseOrder.java`:

```java
package com.nexusops.inventory.domain;

import com.nexusops.inventory.PurchaseOrderStatus;
import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "purchase_orders")
public class PurchaseOrder extends TenantOwnedEntity {

    @Column(nullable = false, updatable = false)
    private String number;

    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PurchaseOrderStatus status;

    @Column(nullable = false)
    private String currency;

    @Column(name = "expected_on")
    private LocalDate expectedOn;

    private String notes;

    @Column(name = "ordered_at")
    private Instant orderedAt;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected PurchaseOrder() {}

    public PurchaseOrder(UUID id, String number, UUID supplierId, UUID warehouseId, String currency,
            LocalDate expectedOn, String notes, UUID createdBy) {
        super(id);
        this.number = number;
        this.status = PurchaseOrderStatus.DRAFT;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        apply(supplierId, warehouseId, currency, expectedOn, notes);
    }

    public void apply(UUID newSupplier, UUID newWarehouse, String newCurrency, LocalDate newExpected, String newNotes) {
        this.supplierId = newSupplier;
        this.warehouseId = newWarehouse;
        this.currency = newCurrency;
        this.expectedOn = newExpected;
        this.notes = newNotes;
        this.updatedAt = Instant.now();
    }

    public void order(Instant now) {
        this.status = PurchaseOrderStatus.ORDERED;
        this.orderedAt = now;
        this.updatedAt = now;
    }

    public void cancel(Instant now) {
        this.status = PurchaseOrderStatus.CANCELLED;
        this.cancelledAt = now;
        this.updatedAt = now;
    }

    /** After a receipt: RECEIVED when every line is complete, else PARTIALLY_RECEIVED. */
    public void received(boolean complete, Instant now) {
        this.status = complete ? PurchaseOrderStatus.RECEIVED : PurchaseOrderStatus.PARTIALLY_RECEIVED;
        this.receivedAt = complete ? now : null;
        this.updatedAt = now;
    }

    public String getNumber() {
        return number;
    }

    public UUID getSupplierId() {
        return supplierId;
    }

    public UUID getWarehouseId() {
        return warehouseId;
    }

    public PurchaseOrderStatus getStatus() {
        return status;
    }

    public String getCurrency() {
        return currency;
    }

    public LocalDate getExpectedOn() {
        return expectedOn;
    }

    public String getNotes() {
        return notes;
    }

    public Instant getOrderedAt() {
        return orderedAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
```

`inventory/domain/PurchaseOrderLine.java`:

```java
package com.nexusops.inventory.domain;

import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "purchase_order_lines")
public class PurchaseOrderLine extends TenantOwnedEntity {

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    @Column(name = "received_quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal receivedQuantity;

    @Column(name = "unit_cost", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitCost;

    protected PurchaseOrderLine() {}

    public PurchaseOrderLine(UUID id, UUID orderId, int lineNo, UUID productId, BigDecimal quantity,
            BigDecimal unitCost) {
        super(id);
        this.orderId = orderId;
        this.lineNo = lineNo;
        this.productId = productId;
        this.quantity = quantity;
        this.receivedQuantity = BigDecimal.ZERO;
        this.unitCost = unitCost;
    }

    public void receive(BigDecimal amount) {
        this.receivedQuantity = receivedQuantity.add(amount);
    }

    public BigDecimal getRemaining() {
        return quantity.subtract(receivedQuantity);
    }

    public UUID getOrderId() {
        return orderId;
    }

    public int getLineNo() {
        return lineNo;
    }

    public UUID getProductId() {
        return productId;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getReceivedQuantity() {
        return receivedQuantity;
    }

    public BigDecimal getUnitCost() {
        return unitCost;
    }
}
```

`inventory/domain/PurchaseOrderRepository.java`:

```java
package com.nexusops.inventory.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, UUID>,
        JpaSpecificationExecutor<PurchaseOrder> {}
```

`inventory/domain/PurchaseOrderLineRepository.java`:

```java
package com.nexusops.inventory.domain;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseOrderLineRepository extends JpaRepository<PurchaseOrderLine, UUID> {

    List<PurchaseOrderLine> findByOrderIdOrderByLineNoAsc(UUID orderId);

    List<PurchaseOrderLine> findByOrderIdInOrderByLineNoAsc(Collection<UUID> orderIds);

    void deleteByOrderId(UUID orderId);
}
```

- [ ] **Step 7: The service and the subject resolver**

`inventory/PurchaseOrderService.java`:

```java
package com.nexusops.inventory;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.catalog.ProductBrief;
import com.nexusops.collaboration.MemberRef;
import com.nexusops.directory.PartyRef;
import com.nexusops.directory.PartyRoleType;
import com.nexusops.directory.PartyService;
import com.nexusops.identity.Members;
import com.nexusops.inventory.domain.PurchaseOrder;
import com.nexusops.inventory.domain.PurchaseOrderLine;
import com.nexusops.inventory.domain.PurchaseOrderLineRepository;
import com.nexusops.inventory.domain.PurchaseOrderRepository;
import com.nexusops.inventory.domain.StockLevel;
import com.nexusops.inventory.domain.Warehouse;
import com.nexusops.shared.Currencies;
import com.nexusops.shared.Decimals;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.Text;
import com.nexusops.shared.web.ApiProblem;
import com.nexusops.shared.web.PageResponse;
import com.nexusops.shared.web.Paging;
import com.nexusops.tenancy.TenantDirectory;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Purchase orders (D9): DRAFT → ORDERED → PARTIALLY_RECEIVED → RECEIVED; DRAFT/ORDERED → CANCELLED. */
@Service
public class PurchaseOrderService {

    static final String DRAFT_ONLY = "Only a draft can be changed.";
    static final String ORDER_DRAFT_ONLY = "Only a draft can be ordered.";
    static final String NOT_RECEIVABLE = "Only an ordered purchase order can be received.";
    static final String NOT_CANCELLABLE = "Only a draft or ordered purchase order can be cancelled.";

    private final PurchaseOrderRepository orders;
    private final PurchaseOrderLineRepository lines;
    private final NumberSequences numbers;
    private final InventoryProducts products;
    private final WarehouseService warehouses;
    private final OrderParties orderParties;
    private final PartyService parties;
    private final StockLedger ledger;
    private final Members members;
    private final TenantDirectory tenants;
    private final AuditService audit;

    PurchaseOrderService(PurchaseOrderRepository orders, PurchaseOrderLineRepository lines, NumberSequences numbers,
            InventoryProducts products, WarehouseService warehouses, OrderParties orderParties, PartyService parties,
            StockLedger ledger, Members members, TenantDirectory tenants, AuditService audit) {
        this.orders = orders;
        this.lines = lines;
        this.numbers = numbers;
        this.products = products;
        this.warehouses = warehouses;
        this.orderParties = orderParties;
        this.parties = parties;
        this.ledger = ledger;
        this.members = members;
        this.tenants = tenants;
        this.audit = audit;
    }

    private record Draft(UUID supplierId, UUID warehouseId, String currency, LocalDate expectedOn, String notes,
            List<PurchaseLineCommand> lines) {}

    @Transactional(readOnly = true)
    public PurchaseOrderView get(UUID id) {
        PurchaseOrder order = find(id);
        return view(order, lines.findByOrderIdOrderByLineNoAsc(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<PurchaseOrderSummary> list(PurchaseOrderQuery query, Integer page, Integer size) {
        TenantContext.requireTenantId();
        Specification<PurchaseOrder> spec = (root, cq, cb) -> cb.conjunction();
        if (query.status() != null) {
            spec = spec.and((root, cq, cb) -> cb.equal(root.get("status"), query.status()));
        }
        if (query.supplierId() != null) {
            spec = spec.and((root, cq, cb) -> cb.equal(root.get("supplierId"), query.supplierId()));
        }
        if (query.warehouseId() != null) {
            spec = spec.and((root, cq, cb) -> cb.equal(root.get("warehouseId"), query.warehouseId()));
        }
        String q = Text.optional(query.q(), 100, "q");
        if (q != null) {
            String like = Text.containsPattern(q);
            spec = spec.and((root, cq, cb) -> cb.like(cb.lower(root.get("number")), like, '\\'));
        }
        Page<PurchaseOrder> result = orders.findAll(spec,
                Paging.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        List<PurchaseOrder> found = result.getContent();
        Map<UUID, List<PurchaseOrderLine>> byOrder = lines.findByOrderIdInOrderByLineNoAsc(
                found.stream().map(PurchaseOrder::getId).toList()).stream()
                .collect(Collectors.groupingBy(PurchaseOrderLine::getOrderId));
        Map<UUID, PartyRef> suppliers = orderParties.refs(found.stream().map(PurchaseOrder::getSupplierId)
                .collect(Collectors.toSet()));
        Map<UUID, Warehouse> places = warehouses.byIds(found.stream().map(PurchaseOrder::getWarehouseId)
                .collect(Collectors.toSet()));
        return new PageResponse<>(found.stream().map(o -> {
            List<PurchaseOrderLine> ls = byOrder.getOrDefault(o.getId(), List.of());
            return new PurchaseOrderSummary(o.getId(), o.getNumber(), suppliers.get(o.getSupplierId()),
                    WarehouseService.ref(places.get(o.getWarehouseId())), o.getStatus(), o.getCurrency(), total(ls),
                    ls.size(), o.getExpectedOn(), o.getCreatedAt());
        }).toList(), result.getNumber(), result.getSize(), result.getTotalElements());
    }

    @Transactional
    public PurchaseOrderView create(PurchaseOrderCommand command) {
        TenantContext.requireTenantId();
        Draft draft = validate(command, null, List.of());
        PurchaseOrder order = new PurchaseOrder(Ids.newId(), numbers.next(SequenceKind.PURCHASE_ORDER),
                draft.supplierId(), draft.warehouseId(), draft.currency(), draft.expectedOn(), draft.notes(),
                TenantContext.userId().orElse(null));
        orders.saveAndFlush(order);
        List<PurchaseOrderLine> saved = saveLines(order.getId(), draft.lines());
        audit.record(AuditEntry.of("PurchaseOrderCreated", "PurchaseOrder", order.getId())
                .withAfter(snapshot(order, saved)));
        return view(order, saved);
    }

    @Transactional
    public PurchaseOrderView update(UUID id, PurchaseOrderCommand command, Long version) {
        PurchaseOrder order = find(id);
        Orders.checkVersion(order.getVersion(), version);
        if (order.getStatus() != PurchaseOrderStatus.DRAFT) {
            throw ApiProblem.conflict(DRAFT_ONLY);
        }
        List<PurchaseOrderLine> current = lines.findByOrderIdOrderByLineNoAsc(id);
        Draft draft = validate(command, order, current);
        Map<String, Object> before = snapshot(order, current);
        order.apply(draft.supplierId(), draft.warehouseId(), draft.currency(), draft.expectedOn(), draft.notes());
        orders.flush();
        lines.deleteByOrderId(id);
        lines.flush();
        List<PurchaseOrderLine> saved = saveLines(id, draft.lines());
        audit.record(AuditEntry.of("PurchaseOrderUpdated", "PurchaseOrder", id).withBefore(before)
                .withAfter(snapshot(order, saved)));
        return view(order, saved);
    }

    @Transactional
    public PurchaseOrderView order(UUID id, Long version) {
        PurchaseOrder order = find(id);
        Orders.checkVersion(order.getVersion(), version);
        if (order.getStatus() != PurchaseOrderStatus.DRAFT) {
            throw ApiProblem.conflict(ORDER_DRAFT_ONLY);
        }
        orderParties.requireNotArchived(order.getSupplierId());
        order.order(Instant.now());
        orders.flush();
        parties.ensureRole(order.getSupplierId(), PartyRoleType.SUPPLIER);
        audit.record(AuditEntry.of("PurchaseOrderOrdered", "PurchaseOrder", id)
                .withAfter(Map.of("number", order.getNumber())));
        return view(order, lines.findByOrderIdOrderByLineNoAsc(id));
    }

    @Transactional
    public PurchaseOrderView cancel(UUID id, Long version) {
        PurchaseOrder order = find(id);
        Orders.checkVersion(order.getVersion(), version);
        if (!order.getStatus().cancellable()) {
            throw ApiProblem.conflict(NOT_CANCELLABLE);
        }
        PurchaseOrderStatus before = order.getStatus();
        order.cancel(Instant.now());
        orders.flush();
        audit.record(AuditEntry.of("PurchaseOrderCancelled", "PurchaseOrder", id)
                .withBefore(Map.of("status", before.name())).withAfter(Map.of("number", order.getNumber())));
        return view(order, lines.findByOrderIdOrderByLineNoAsc(id));
    }

    @Transactional
    public PurchaseOrderView receive(UUID id, ReceiptCommand command, Long version) {
        PurchaseOrder order = find(id);
        Orders.checkVersion(order.getVersion(), version);
        List<ReceiptCommand.Line> receipt = command.lines();
        if (receipt == null || receipt.isEmpty()) {
            throw ApiProblem.badRequestField("lines", "Choose what was received.");
        }
        if (!order.getStatus().receivable()) {
            throw ApiProblem.conflict(NOT_RECEIVABLE);
        }
        Map<UUID, PurchaseOrderLine> byId = lines.findByOrderIdOrderByLineNoAsc(id).stream()
                .collect(Collectors.toMap(PurchaseOrderLine::getId, l -> l, (a, b) -> a, LinkedHashMap::new));
        Set<UUID> seen = new HashSet<>();
        Map<PurchaseOrderLine, BigDecimal> amounts = new LinkedHashMap<>();
        for (int i = 0; i < receipt.size(); i++) {
            ReceiptCommand.Line r = receipt.get(i);
            PurchaseOrderLine line = r.lineId() == null ? null : byId.get(r.lineId());
            if (line == null) {
                throw ApiProblem.badRequestField(Orders.field(i, "lineId"), "Choose a line of this order.");
            }
            if (!seen.add(line.getId())) {
                throw ApiProblem.badRequestField(Orders.field(i, "lineId"), "This line is already in the receipt.");
            }
            BigDecimal quantity = Quantities.positive(r.quantity(), Orders.field(i, "quantity"));
            if (quantity.compareTo(line.getRemaining()) > 0) {
                throw ApiProblem.badRequestField(Orders.field(i, "quantity"),
                        "Receive at most " + line.getRemaining().stripTrailingZeros().toPlainString() + ".");
            }
            amounts.put(line, quantity);
        }
        Map<StockKey, StockLevel> locked = ledger.lock(amounts.keySet().stream()
                .map(l -> new StockKey(l.getProductId(), order.getWarehouseId())).toList());
        List<Map<String, Object>> received = new ArrayList<>();
        amounts.forEach((line, quantity) -> {
            ledger.move(locked.get(new StockKey(line.getProductId(), order.getWarehouseId())), MovementKind.RECEIPT,
                    quantity, ReferenceType.PURCHASE_ORDER, order.getId(), order.getNumber());
            line.receive(quantity);
            received.add(Map.of("productId", line.getProductId().toString(), "quantity", quantity.toPlainString()));
        });
        lines.flush();
        boolean complete = byId.values().stream().allMatch(l -> l.getRemaining().signum() == 0);
        order.received(complete, Instant.now());
        orders.flush();
        audit.record(AuditEntry.of("PurchaseOrderReceived", "PurchaseOrder", id)
                .withAfter(Map.of("number", order.getNumber(), "lines", received, "status", order.getStatus().name())));
        return view(order, new ArrayList<>(byId.values()));
    }

    /** {@code current} is null on create. An unchanged supplier, warehouse or line product isn't re-checked. */
    private Draft validate(PurchaseOrderCommand command, PurchaseOrder current, List<PurchaseOrderLine> currentLines) {
        Orders.requireCount(command.lines());
        Set<UUID> existingProducts = currentLines.stream().map(PurchaseOrderLine::getProductId)
                .collect(Collectors.toSet());
        Set<UUID> seen = new HashSet<>();
        List<PurchaseLineCommand> clean = new ArrayList<>();
        for (int i = 0; i < command.lines().size(); i++) {
            PurchaseLineCommand l = command.lines().get(i);
            BigDecimal quantity = Quantities.positive(l.quantity(), Orders.field(i, "quantity"));
            if (l.unitCost() == null) {
                throw ApiProblem.badRequestField(Orders.field(i, "unitCost"), "Enter a cost.");
            }
            BigDecimal cost = Decimals.nonNegative(l.unitCost(), Orders.field(i, "unitCost"),
                    "Enter a cost of 0 or more.");
            if (l.productId() != null && !seen.add(l.productId())) {
                throw ApiProblem.badRequestField(Orders.field(i, "productId"), "This product is already on the order.");
            }
            clean.add(new PurchaseLineCommand(l.productId(), quantity, cost));
        }
        String currency = Currencies.parse(command.currency(), "currency");
        String notes = Text.optional(command.notes(), 2000, "notes");
        if (command.supplierId() == null) {
            throw ApiProblem.badRequestField("supplierId", "Choose a supplier.");
        }
        for (int i = 0; i < clean.size(); i++) {
            UUID product = clean.get(i).productId();
            products.requireStockable(product, Orders.field(i, "productId"), existingProducts.contains(product));
        }
        if (current == null || !command.supplierId().equals(current.getSupplierId())) {
            orderParties.requireUsable(command.supplierId(), "supplierId", "Choose a supplier.");
        }
        UUID warehouseId = command.warehouseId();
        if (current == null || !Objects.equals(warehouseId, current.getWarehouseId())) {
            warehouseId = warehouses.requireActive(warehouseId, "warehouseId").getId();
        }
        return new Draft(command.supplierId(), warehouseId,
                currency != null ? currency : tenants.currentSettings().currency(), command.expectedOn(), notes, clean);
    }

    private List<PurchaseOrderLine> saveLines(UUID orderId, List<PurchaseLineCommand> commands) {
        List<PurchaseOrderLine> rows = new ArrayList<>();
        for (int i = 0; i < commands.size(); i++) {
            PurchaseLineCommand c = commands.get(i);
            rows.add(new PurchaseOrderLine(Ids.newId(), orderId, i + 1, c.productId(), c.quantity(), c.unitCost()));
        }
        return lines.saveAllAndFlush(rows);
    }

    private PurchaseOrderView view(PurchaseOrder o, List<PurchaseOrderLine> ls) {
        Map<UUID, ProductBrief> names = products.briefs(ls.stream().map(PurchaseOrderLine::getProductId)
                .collect(Collectors.toSet()));
        Members.Member creator = o.getCreatedBy() == null ? null
                : members.findAll(List.of(o.getCreatedBy())).get(o.getCreatedBy());
        List<PurchaseLineView> lineViews = ls.stream().map(l -> new PurchaseLineView(l.getId(), l.getLineNo(),
                InventoryProducts.ref(names.get(l.getProductId())), l.getQuantity(), l.getReceivedQuantity(),
                l.getRemaining(), l.getUnitCost(), Orders.lineTotal(l.getQuantity(), l.getUnitCost()))).toList();
        return new PurchaseOrderView(o.getId(), o.getNumber(),
                orderParties.refs(List.of(o.getSupplierId())).get(o.getSupplierId()),
                WarehouseService.ref(warehouses.byIds(List.of(o.getWarehouseId())).get(o.getWarehouseId())),
                o.getStatus(), o.getCurrency(), o.getExpectedOn(), o.getNotes(), lineViews, total(ls),
                o.getOrderedAt(), o.getReceivedAt(), o.getCancelledAt(),
                creator == null ? null : new MemberRef(creator.id(), creator.name()), o.getCreatedAt(),
                o.getUpdatedAt(), o.getVersion());
    }

    private static BigDecimal total(List<PurchaseOrderLine> ls) {
        return ls.stream().map(l -> Orders.lineTotal(l.getQuantity(), l.getUnitCost()))
                .reduce(BigDecimal.ZERO.setScale(4), BigDecimal::add);
    }

    private static Map<String, Object> snapshot(PurchaseOrder o, List<PurchaseOrderLine> ls) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("number", o.getNumber());
        values.put("supplierId", o.getSupplierId().toString());
        values.put("warehouseId", o.getWarehouseId().toString());
        values.put("currency", o.getCurrency());
        values.put("lines", ls.stream().map(l -> Map.of("productId", l.getProductId().toString(),
                "quantity", l.getQuantity().toPlainString(), "unitCost", l.getUnitCost().toPlainString())).toList());
        return values;
    }

    private PurchaseOrder find(UUID id) {
        TenantContext.requireTenantId();
        return orders.findById(id).orElseThrow(() -> ApiProblem.notFound(Orders.NOT_FOUND));
    }
}
```

**Version bump on a lines-only edit.** `order.apply(...)` always sets `updatedAt`, so Hibernate sees a dirty entity and raises `version` even when only the lines changed — the test's `order` call with `version: 1` after one edit relies on that. Don't skip `apply` when the header is unchanged.

`inventory/PurchaseOrderSubjects.java`:

```java
package com.nexusops.inventory;

import com.nexusops.collaboration.SearchHit;
import com.nexusops.collaboration.SubjectRef;
import com.nexusops.collaboration.SubjectResolver;
import com.nexusops.directory.PartyRef;
import com.nexusops.inventory.domain.PurchaseOrder;
import com.nexusops.inventory.domain.PurchaseOrderRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/** Purchase orders as collaboration subjects (type PURCHASE_ORDER, D14). */
@Component
class PurchaseOrderSubjects implements SubjectResolver {

    static final String TYPE = "PURCHASE_ORDER";

    private final PurchaseOrderRepository orders;
    private final OrderParties parties;

    PurchaseOrderSubjects(PurchaseOrderRepository orders, OrderParties parties) {
        this.orders = orders;
        this.parties = parties;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String readPermission() {
        return InventoryPermissions.PURCHASE_READ;
    }

    @Override
    public Optional<SubjectRef> find(UUID id) {
        return orders.findById(id).map(o -> new SubjectRef(TYPE, o.getId(), o.getNumber(), false));
    }

    @Override
    public Map<UUID, SubjectRef> findAll(Collection<UUID> ids) {
        return orders.findAllById(ids).stream().collect(Collectors.toMap(PurchaseOrder::getId,
                o -> new SubjectRef(TYPE, o.getId(), o.getNumber(), false)));
    }

    @Override
    public List<SearchHit> search(String pattern, int limit) {
        Specification<PurchaseOrder> spec = (root, cq, cb) -> cb.like(cb.lower(root.get("number")), pattern, '\\');
        List<PurchaseOrder> found = orders.findAll(spec,
                PageRequest.of(0, limit, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id")))).getContent();
        Map<UUID, PartyRef> names = parties.refs(found.stream().map(PurchaseOrder::getSupplierId)
                .collect(Collectors.toSet()));
        return found.stream().map(o -> new SearchHit(TYPE, o.getId(), o.getNumber(),
                names.containsKey(o.getSupplierId()) ? names.get(o.getSupplierId()).name() : null, false)).toList();
    }
}
```

- [ ] **Step 8: Warehouse archive refuses open purchase orders**

In `WarehouseService.requireUnused`, after the stock check, add:

```java
        Boolean ordered = jdbc.queryForObject("select exists (select 1 from purchase_orders where tenant_id = ? "
                + "and warehouse_id = ? and status in ('DRAFT', 'ORDERED', 'PARTIALLY_RECEIVED'))", Boolean.class,
                tenant, warehouse.getId());
        if (Boolean.TRUE.equals(ordered)) {
            throw ApiProblem.conflict(OPEN_ORDERS);
        }
```

with `static final String OPEN_ORDERS = "Close or move this warehouse's open orders first.";` beside the other messages.

- [ ] **Step 9: Web layer**

Add to `InventoryDtos`:

```java
    record PurchaseOrderRequest(UUID supplierId, UUID warehouseId, String currency, LocalDate expectedOn,
            String notes, List<PurchaseLineCommand> lines, Long version) {
        PurchaseOrderCommand command() {
            return new PurchaseOrderCommand(supplierId, warehouseId, currency, expectedOn, notes, lines);
        }
    }

    record VersionRequest(Long version) {}

    record ReceiptRequest(List<ReceiptCommand.Line> lines, Long version) {}
```

`inventory/web/PurchaseOrderController.java`:

```java
package com.nexusops.inventory.web;

import com.nexusops.inventory.PurchaseOrderQuery;
import com.nexusops.inventory.PurchaseOrderService;
import com.nexusops.inventory.PurchaseOrderStatus;
import com.nexusops.inventory.PurchaseOrderSummary;
import com.nexusops.inventory.PurchaseOrderView;
import com.nexusops.inventory.ReceiptCommand;
import com.nexusops.inventory.web.InventoryDtos.PurchaseOrderRequest;
import com.nexusops.inventory.web.InventoryDtos.ReceiptRequest;
import com.nexusops.inventory.web.InventoryDtos.VersionRequest;
import com.nexusops.shared.web.PageResponse;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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
@RequestMapping("/api/v1/purchase-orders")
class PurchaseOrderController {

    private final PurchaseOrderService orders;

    PurchaseOrderController(PurchaseOrderService orders) {
        this.orders = orders;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('inventory.purchase.read')")
    PageResponse<PurchaseOrderSummary> list(@RequestParam(required = false) String q,
            @RequestParam(required = false) PurchaseOrderStatus status, @RequestParam(required = false) UUID supplierId,
            @RequestParam(required = false) UUID warehouseId, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return orders.list(new PurchaseOrderQuery(q, status, supplierId, warehouseId), page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('inventory.purchase.read')")
    PurchaseOrderView get(@PathVariable UUID id) {
        return orders.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('inventory.purchase.manage')")
    PurchaseOrderView create(@RequestBody PurchaseOrderRequest request) {
        return orders.create(request.command());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('inventory.purchase.manage')")
    PurchaseOrderView update(@PathVariable UUID id, @RequestBody PurchaseOrderRequest request) {
        return orders.update(id, request.command(), request.version());
    }

    @PostMapping("/{id}/order")
    @PreAuthorize("hasAuthority('inventory.purchase.manage')")
    PurchaseOrderView order(@PathVariable UUID id, @RequestBody VersionRequest request) {
        return orders.order(id, request.version());
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('inventory.purchase.manage')")
    PurchaseOrderView cancel(@PathVariable UUID id, @RequestBody VersionRequest request) {
        return orders.cancel(id, request.version());
    }

    @PostMapping("/{id}/receipts")
    @PreAuthorize("hasAuthority('inventory.purchase.manage')")
    PurchaseOrderView receive(@PathVariable UUID id, @RequestBody ReceiptRequest request) {
        return orders.receive(id, new ReceiptCommand(request.lines()), request.version());
    }
}
```

- [ ] **Step 10: Run the tests to verify they pass**

Run: `cd backend && ./gradlew test --tests '*PurchaseOrderApiIT' --tests '*StockApiIT' --tests '*WarehouseApiIT' --tests '*ModularityTest' --tests '*EndpointAuthorizationCoverageTest' --tests '*RlsCoverageIT'`
Expected: PASS.

- [ ] **Step 11: Run the whole backend suite, then commit**

Run: `cd backend && ./gradlew test` — Expected: PASS.

```bash
git add backend/src/main/resources/db/migration/V20__purchase_orders.sql backend/src/main/java/com/nexusops/inventory \
  backend/src/test/java/com/nexusops/inventory/PurchaseOrderApiIT.java
git commit -m "feat(inventory): purchase orders with document numbers and partial receipts into stock"
```

---
### Task 4: Sales orders that reserve, fulfil and release stock

**Files:**
- Create: `backend/src/main/resources/db/migration/V21__sales_orders.sql`
- Create: `backend/src/main/java/com/nexusops/inventory/SalesOrderStatus.java`, `SalesLineCommand.java`, `SalesOrderCommand.java`, `SalesLineView.java`, `SalesOrderView.java`, `SalesOrderSummary.java`, `SalesOrderQuery.java`, `SalesOrderService.java`, `SalesOrderSubjects.java`
- Create: `backend/src/main/java/com/nexusops/inventory/domain/SalesOrder.java`, `SalesOrderLine.java`, `SalesOrderRepository.java`, `SalesOrderLineRepository.java`
- Create: `backend/src/main/java/com/nexusops/inventory/web/SalesOrderController.java`; Modify: `web/InventoryDtos.java`
- Modify: `backend/src/main/java/com/nexusops/inventory/WarehouseService.java` (`requireUnused`: open sales orders)
- Test: `backend/src/test/java/com/nexusops/inventory/SalesOrderApiIT.java`

**Interfaces:**
- Consumes (Tasks 1–3): `Orders` (`field`, `requireCount`, `checkVersion`, `lineTotal`, `NOT_FOUND`), `OrderParties` (`requireUsable`, `requireNotArchived`, `refs`), `NumberSequences.next(SequenceKind.SALES_ORDER)`, `StockLedger.lock/move/reserve/release`, `StockKey`, `StockLevel.getAvailable()`, `Shortage` + `Shortage.conflict`, `MovementKind.ISSUE`, `ReferenceType.SALES_ORDER`, `InventoryProducts.requireStockable/briefs/ref`, `WarehouseService.requireActive/byIds/ref`, `WarehouseService.OPEN_ORDERS`, `Quantities.positive`, `Decimals.nonNegative`, `Currencies.parse`, `PartyService.ensureRole`, `InventoryDtos.VersionRequest`, `TestInventory`.
- Produces:
  - `enum SalesOrderStatus {DRAFT, CONFIRMED, FULFILLED, CANCELLED}`.
  - `SalesLineCommand(UUID productId, BigDecimal quantity, BigDecimal unitPrice)` (unitPrice optional — defaults to the product's list price when its currency is the order's).
  - `SalesOrderCommand(UUID customerId, UUID warehouseId, String currency, String notes, List<SalesLineCommand> lines)`.
  - `SalesLineView(UUID id, int lineNo, ProductRef product, BigDecimal quantity, BigDecimal unitPrice, BigDecimal lineTotal)`.
  - `SalesOrderView(UUID id, String number, PartyRef customer, WarehouseRef warehouse, SalesOrderStatus status, String currency, String notes, List<SalesLineView> lines, BigDecimal total, Instant confirmedAt, Instant fulfilledAt, Instant cancelledAt, MemberRef createdBy, Instant createdAt, Instant updatedAt, long version)`.
  - `SalesOrderSummary(UUID id, String number, PartyRef customer, WarehouseRef warehouse, SalesOrderStatus status, String currency, BigDecimal total, int lineCount, Instant createdAt)`.
  - `SalesOrderQuery(String q, SalesOrderStatus status, UUID customerId, UUID warehouseId)`.
  - `SalesOrderService` public `get`, `list`, `create`, `update(UUID, SalesOrderCommand, Long)`, `confirm(UUID, Long)`, `fulfil(UUID, Long)`, `cancel(UUID, Long)`.
  - `SalesOrderSubjects` type `"SALES_ORDER"` (label = number; search by number, detail = customer name).
  - Routes under `/api/v1/sales-orders`: `GET`, `GET /{id}`, `POST`, `PUT /{id}`, `POST /{id}/confirm`, `POST /{id}/fulfil`, `POST /{id}/cancel`; state-change bodies carry `{version}`.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/nexusops/inventory/SalesOrderApiIT.java`:

```java
package com.nexusops.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestInventory;
import com.nexusops.support.TestMembers;
import com.nexusops.support.TestRoles;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Workspace;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@AutoConfigureMockMvc
class SalesOrderApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;
    UUID customer, widget, gadget, main;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("so"));
        owner = Api.login(mvc, ws);
        TestInventory.enable(owner);
        customer = Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Deccan Retail\"}"));
        widget = TestInventory.goods(owner, "W-1", "Widget");
        gadget = TestInventory.goods(owner, "G-1", "Gadget");
        main = TestInventory.mainWarehouse(owner);
        owner.put("/api/v1/products/" + widget, "{\"sku\":\"W-1\",\"name\":\"Widget\",\"kind\":\"GOODS\","
                + "\"listPrice\":12.5,\"currency\":\"USD\",\"version\":0}").andExpect(status().isOk());
        count(widget, "10");
        count(gadget, "3");
    }

    private void count(UUID product, String quantity) throws Exception {
        owner.post("/api/v1/inventory/adjustments", "{\"productId\":\"" + product + "\",\"warehouseId\":\"" + main
                + "\",\"countedQuantity\":" + quantity + ",\"reason\":\"Opening count\"}").andExpect(status().isOk());
    }

    private String body(String lines) {
        return "{\"customerId\":\"" + customer + "\",\"warehouseId\":\"" + main + "\",\"lines\":[" + lines + "]}";
    }

    private String line(UUID product, String quantity, String price) {
        return "{\"productId\":\"" + product + "\",\"quantity\":" + quantity
                + (price == null ? "" : ",\"unitPrice\":" + price) + "}";
    }

    private UUID draft(String lines) throws Exception {
        return Api.id(owner.post("/api/v1/sales-orders", body(lines)).andExpect(status().isCreated()));
    }

    private ResultActions act(UUID order, String action, long version) throws Exception {
        return owner.post("/api/v1/sales-orders/" + order + "/" + action, "{\"version\":" + version + "}");
    }

    private ResultActions stock(UUID product) throws Exception {
        return owner.get("/api/v1/inventory/stock/products/" + product);
    }

    private long audits(String action) {
        return OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select count(*) from audit_events where action = ?", Long.class, action);
    }

    @Test
    void draftsAreNumberedAndPricedFromTheCatalog() throws Exception {
        owner.post("/api/v1/sales-orders", body(line(widget, "2", null) + "," + line(gadget, "1", "40")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.number").value("SO-00001"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.customer.name").value("Deccan Retail"))
                .andExpect(jsonPath("$.lines[0].unitPrice").value(12.5))
                .andExpect(jsonPath("$.lines[0].lineTotal").value(25.0))
                .andExpect(jsonPath("$.total").value(65.0));
        // a draft reserves nothing and marks nobody a customer
        stock(widget).andExpect(jsonPath("$.reserved").value(0.0));
        owner.get("/api/v1/parties/" + customer).andExpect(jsonPath("$.roles", Matchers.empty()));
    }

    @Test
    void invalidOrdersAreFieldErrors() throws Exception {
        String eur = body(line(widget, "1", null)).replace("{\"customerId\"", "{\"currency\":\"EUR\",\"customerId\"");
        String[][] cases = {
                {body(""), "lines"},
                {body(line(gadget, "1", null)), "lines[0].unitPrice"},
                {eur, "lines[0].unitPrice"},
                {body(line(widget, "0", "1")), "lines[0].quantity"},
                {body(line(widget, "1", "-1")), "lines[0].unitPrice"},
                {body(line(widget, "1", "1") + "," + line(widget, "1", "1")), "lines[1].productId"},
                {body(line(UUID.randomUUID(), "1", "1")), "lines[0].productId"},
                {"{\"warehouseId\":\"" + main + "\",\"lines\":[" + line(widget, "1", "1") + "]}", "customerId"},
        };
        for (String[] c : cases) {
            owner.post("/api/v1/sales-orders", c[0]).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value(c[1]));
        }
    }

    @Test
    void confirmingReservesAndMakesTheCustomer() throws Exception {
        UUID order = draft(line(widget, "4", null) + "," + line(gadget, "3", "40"));
        act(order, "confirm", 0).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED")).andExpect(jsonPath("$.confirmedAt").exists());
        stock(widget).andExpect(jsonPath("$.onHand").value(10.0)).andExpect(jsonPath("$.reserved").value(4.0))
                .andExpect(jsonPath("$.available").value(6.0));
        stock(gadget).andExpect(jsonPath("$.available").value(0.0));
        owner.get("/api/v1/parties/" + customer).andExpect(jsonPath("$.roles[?(@.role == 'CUSTOMER')].status")
                .value(Matchers.contains("ACTIVE")));
        owner.put("/api/v1/sales-orders/" + order, body(line(widget, "1", null)).replace("]}", "],\"version\":1}"))
                .andExpect(status().isConflict());
        act(order, "confirm", 1).andExpect(status().isConflict());
    }

    @Test
    void shortagesRefuseTheWholeConfirmation() throws Exception {
        UUID order = draft(line(widget, "4", null) + "," + line(gadget, "5", "40"));
        act(order, "confirm", 0).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Not enough stock."))
                .andExpect(jsonPath("$.shortages.length()").value(1))
                .andExpect(jsonPath("$.shortages[0].sku").value("G-1"))
                .andExpect(jsonPath("$.shortages[0].requested").value(5.0))
                .andExpect(jsonPath("$.shortages[0].available").value(3.0));
        stock(widget).andExpect(jsonPath("$.reserved").value(0.0));
        owner.get("/api/v1/sales-orders/" + order).andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void fulfilmentIssuesStockAndCancellationReleasesIt() throws Exception {
        UUID shipped = draft(line(widget, "4", null));
        act(shipped, "fulfil", 0).andExpect(status().isConflict());
        act(shipped, "confirm", 0).andExpect(status().isOk());
        act(shipped, "fulfil", 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FULFILLED")).andExpect(jsonPath("$.fulfilledAt").exists());
        stock(widget).andExpect(jsonPath("$.onHand").value(6.0)).andExpect(jsonPath("$.reserved").value(0.0));
        owner.get("/api/v1/inventory/movements?productId=" + widget)
                .andExpect(jsonPath("$.items[0].kind").value("ISSUE"))
                .andExpect(jsonPath("$.items[0].quantity").value(-4.0))
                .andExpect(jsonPath("$.items[0].referenceType").value("SALES_ORDER"))
                .andExpect(jsonPath("$.items[0].reason").value("SO-00001"));
        act(shipped, "cancel", 2).andExpect(status().isConflict());

        UUID held = draft(line(widget, "5", null));
        act(held, "confirm", 0).andExpect(status().isOk());
        stock(widget).andExpect(jsonPath("$.reserved").value(5.0));
        act(held, "cancel", 1).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        stock(widget).andExpect(jsonPath("$.reserved").value(0.0)).andExpect(jsonPath("$.onHand").value(6.0));
        UUID dropped = draft(line(widget, "1", null));
        act(dropped, "cancel", 0).andExpect(status().isOk());
        assertThat(audits("SalesOrderFulfilled")).isEqualTo(1);
        assertThat(audits("SalesOrderCancelled")).isEqualTo(2);
    }

    @Test
    void reservedStockCannotBeCountedAwayOrTransferred() throws Exception {
        UUID order = draft(line(widget, "8", null));
        act(order, "confirm", 0).andExpect(status().isOk());
        owner.post("/api/v1/inventory/adjustments", "{\"productId\":\"" + widget + "\",\"warehouseId\":\"" + main
                + "\",\"countedQuantity\":7,\"reason\":\"Recount\"}").andExpect(status().isConflict());
        UUID pune = Api.id(owner.post("/api/v1/inventory/warehouses", "{\"code\":\"PUNE\",\"name\":\"Pune\"}"));
        owner.post("/api/v1/inventory/transfers", "{\"productId\":\"" + widget + "\",\"fromWarehouseId\":\"" + main
                + "\",\"toWarehouseId\":\"" + pune + "\",\"quantity\":3}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.shortages[0].available").value(2.0));
    }

    @Test
    void anArchivedProductOnAnExistingLineDoesNotBlockTheOrder() throws Exception {
        UUID order = draft(line(widget, "2", null));
        owner.post("/api/v1/products/" + widget + "/archive", "").andExpect(status().isOk());
        owner.post("/api/v1/sales-orders", body(line(widget, "1", "1"))).andExpect(status().isConflict());
        owner.put("/api/v1/sales-orders/" + order, body(line(widget, "3", "11")).replace("]}", "],\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lines[0].quantity").value(3.0));
        act(order, "confirm", 1).andExpect(status().isOk());
        act(order, "fulfil", 2).andExpect(status().isOk());
        stock(widget).andExpect(jsonPath("$.onHand").value(7.0));
    }

    @Test
    void concurrentConfirmationsNeverOversell() throws Exception {
        UUID first = draft(line(gadget, "2", "40"));
        UUID second = draft(line(gadget, "2", "40"));
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (UUID order : List.of(first, second)) {
                results.add(pool.submit(() -> {
                    start.await();
                    return act(order, "confirm", 0).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get(30, TimeUnit.SECONDS));
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        stock(gadget).andExpect(jsonPath("$.reserved").value(2.0)).andExpect(jsonPath("$.available").value(1.0));
    }

    @Test
    void aWarehouseWithOpenSalesOrdersCannotBeArchived() throws Exception {
        UUID pune = Api.id(owner.post("/api/v1/inventory/warehouses", "{\"code\":\"PUNE\",\"name\":\"Pune\"}"));
        UUID order = Api.id(owner.post("/api/v1/sales-orders", body(line(widget, "1", null))
                .replace(main.toString(), pune.toString())).andExpect(status().isCreated()));
        owner.post("/api/v1/inventory/warehouses/" + pune + "/archive", "").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Close or move this warehouse's open orders first."));
        act(order, "cancel", 0).andExpect(status().isOk());
        owner.post("/api/v1/inventory/warehouses/" + pune + "/archive", "").andExpect(status().isOk());
    }

    @Test
    void listsFiltersAndPermissions() throws Exception {
        UUID first = draft(line(widget, "1", null));
        UUID second = draft(line(widget, "1", null) + "," + line(gadget, "1", "40"));
        act(second, "confirm", 0).andExpect(status().isOk());
        owner.get("/api/v1/sales-orders").andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].number").value("SO-00002"))
                .andExpect(jsonPath("$.items[0].lineCount").value(2));
        owner.get("/api/v1/sales-orders?status=CONFIRMED")
                .andExpect(jsonPath("$.items[*].id").value(Matchers.contains(second.toString())));
        owner.get("/api/v1/sales-orders?q=00001")
                .andExpect(jsonPath("$.items[*].id").value(Matchers.contains(first.toString())));
        owner.get("/api/v1/sales-orders?customerId=" + customer).andExpect(jsonPath("$.total").value(2));
        owner.get("/api/v1/sales-orders/" + UUID.randomUUID()).andExpect(status().isNotFound());

        UUID readerRole = TestRoles.create(mvc, owner.session(), "Order reader", "inventory.order.read");
        Api reader = Api.login(mvc, members.create(ws.tenantId(), Set.of(readerRole)));
        reader.get("/api/v1/sales-orders/" + first).andExpect(status().isOk());
        reader.post("/api/v1/sales-orders/" + first + "/confirm", "{\"version\":0}").andExpect(status().isForbidden());
        owner.post("/api/v1/activities", "{\"subjectType\":\"SALES_ORDER\",\"subjectId\":\"" + first
                + "\",\"type\":\"NOTE\",\"summary\":\"Customer wants it gift-wrapped\"}").andExpect(status().isCreated());
        assertThat(audits("SalesOrderCreated")).isEqualTo(2);
        assertThat(audits("SalesOrderConfirmed")).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./gradlew test --tests '*SalesOrderApiIT'`
Expected: FAIL — 404 on `/api/v1/sales-orders`.

- [ ] **Step 3: Migration V21**

`backend/src/main/resources/db/migration/V21__sales_orders.sql`:

```sql
-- Sales orders (D10). Confirming reserves every line; fulfilling issues it; cancelling a confirmed order releases it.
CREATE TABLE sales_orders (
    id            uuid PRIMARY KEY,
    tenant_id     uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    number        text NOT NULL CHECK (number ~ '^SO-[0-9]{5,}$'),
    customer_id   uuid NOT NULL,
    warehouse_id  uuid NOT NULL,
    status        text NOT NULL CHECK (status IN ('DRAFT', 'CONFIRMED', 'FULFILLED', 'CANCELLED')),
    currency      text NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    notes         text CHECK (notes IS NULL OR length(notes) <= 2000),
    confirmed_at  timestamptz,
    fulfilled_at  timestamptz,
    cancelled_at  timestamptz,
    created_by    uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    version       bigint NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, number),
    CHECK ((status = 'FULFILLED') = (fulfilled_at IS NOT NULL)),
    CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL)),
    CHECK (status = 'DRAFT' OR status = 'CANCELLED' OR confirmed_at IS NOT NULL),
    FOREIGN KEY (tenant_id, customer_id) REFERENCES parties (tenant_id, id),
    FOREIGN KEY (tenant_id, warehouse_id) REFERENCES warehouses (tenant_id, id)
);
CREATE INDEX sales_orders_status_idx ON sales_orders (tenant_id, status, created_at);
CREATE INDEX sales_orders_customer_idx ON sales_orders (tenant_id, customer_id);
CREATE INDEX sales_orders_warehouse_idx ON sales_orders (tenant_id, warehouse_id);

ALTER TABLE sales_orders ENABLE ROW LEVEL SECURITY;
ALTER TABLE sales_orders FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sales_orders
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE sales_order_lines (
    id          uuid PRIMARY KEY,
    tenant_id   uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    order_id    uuid NOT NULL,
    line_no     integer NOT NULL CHECK (line_no >= 1),
    product_id  uuid NOT NULL,
    quantity    numeric(19, 4) NOT NULL CHECK (quantity > 0),
    unit_price  numeric(19, 4) NOT NULL CHECK (unit_price >= 0),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, order_id, line_no),
    UNIQUE (tenant_id, order_id, product_id),
    FOREIGN KEY (tenant_id, order_id) REFERENCES sales_orders (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, product_id) REFERENCES products (tenant_id, id)
);
CREATE INDEX sales_order_lines_product_idx ON sales_order_lines (tenant_id, product_id);

ALTER TABLE sales_order_lines ENABLE ROW LEVEL SECURITY;
ALTER TABLE sales_order_lines FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sales_order_lines
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
```

- [ ] **Step 4: Types**

```java
package com.nexusops.inventory;

public enum SalesOrderStatus {
    DRAFT, CONFIRMED, FULFILLED, CANCELLED;

    boolean cancellable() {
        return this == DRAFT || this == CONFIRMED;
    }
}
```

```java
package com.nexusops.inventory;

import java.math.BigDecimal;
import java.util.UUID;

/** {@code unitPrice} null → the product's list price when it is in the order's currency. */
public record SalesLineCommand(UUID productId, BigDecimal quantity, BigDecimal unitPrice) {}
```

```java
package com.nexusops.inventory;

import java.util.List;
import java.util.UUID;

/** Raw input; currency defaults to the workspace currency. */
public record SalesOrderCommand(UUID customerId, UUID warehouseId, String currency, String notes,
        List<SalesLineCommand> lines) {}
```

```java
package com.nexusops.inventory;

import java.math.BigDecimal;
import java.util.UUID;

public record SalesLineView(UUID id, int lineNo, ProductRef product, BigDecimal quantity, BigDecimal unitPrice,
        BigDecimal lineTotal) {}
```

```java
package com.nexusops.inventory;

import com.nexusops.collaboration.MemberRef;
import com.nexusops.directory.PartyRef;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SalesOrderView(UUID id, String number, PartyRef customer, WarehouseRef warehouse,
        SalesOrderStatus status, String currency, String notes, List<SalesLineView> lines, BigDecimal total,
        Instant confirmedAt, Instant fulfilledAt, Instant cancelledAt, MemberRef createdBy, Instant createdAt,
        Instant updatedAt, long version) {}
```

```java
package com.nexusops.inventory;

import com.nexusops.directory.PartyRef;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record SalesOrderSummary(UUID id, String number, PartyRef customer, WarehouseRef warehouse,
        SalesOrderStatus status, String currency, BigDecimal total, int lineCount, Instant createdAt) {}
```

```java
package com.nexusops.inventory;

import java.util.UUID;

public record SalesOrderQuery(String q, SalesOrderStatus status, UUID customerId, UUID warehouseId) {}
```

- [ ] **Step 5: Domain**

`inventory/domain/SalesOrder.java`:

```java
package com.nexusops.inventory.domain;

import com.nexusops.inventory.SalesOrderStatus;
import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sales_orders")
public class SalesOrder extends TenantOwnedEntity {

    @Column(nullable = false, updatable = false)
    private String number;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SalesOrderStatus status;

    @Column(nullable = false)
    private String currency;

    private String notes;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "fulfilled_at")
    private Instant fulfilledAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected SalesOrder() {}

    public SalesOrder(UUID id, String number, UUID customerId, UUID warehouseId, String currency, String notes,
            UUID createdBy) {
        super(id);
        this.number = number;
        this.status = SalesOrderStatus.DRAFT;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        apply(customerId, warehouseId, currency, notes);
    }

    /** Always moves updatedAt, so a lines-only edit still raises the version. */
    public void apply(UUID newCustomer, UUID newWarehouse, String newCurrency, String newNotes) {
        this.customerId = newCustomer;
        this.warehouseId = newWarehouse;
        this.currency = newCurrency;
        this.notes = newNotes;
        this.updatedAt = Instant.now();
    }

    public void confirm(Instant now) {
        this.status = SalesOrderStatus.CONFIRMED;
        this.confirmedAt = now;
        this.updatedAt = now;
    }

    public void fulfil(Instant now) {
        this.status = SalesOrderStatus.FULFILLED;
        this.fulfilledAt = now;
        this.updatedAt = now;
    }

    public void cancel(Instant now) {
        this.status = SalesOrderStatus.CANCELLED;
        this.cancelledAt = now;
        this.updatedAt = now;
    }

    public String getNumber() {
        return number;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public UUID getWarehouseId() {
        return warehouseId;
    }

    public SalesOrderStatus getStatus() {
        return status;
    }

    public String getCurrency() {
        return currency;
    }

    public String getNotes() {
        return notes;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public Instant getFulfilledAt() {
        return fulfilledAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
```

`inventory/domain/SalesOrderLine.java`:

```java
package com.nexusops.inventory.domain;

import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "sales_order_lines")
public class SalesOrderLine extends TenantOwnedEntity {

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice;

    protected SalesOrderLine() {}

    public SalesOrderLine(UUID id, UUID orderId, int lineNo, UUID productId, BigDecimal quantity,
            BigDecimal unitPrice) {
        super(id);
        this.orderId = orderId;
        this.lineNo = lineNo;
        this.productId = productId;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public int getLineNo() {
        return lineNo;
    }

    public UUID getProductId() {
        return productId;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }
}
```

`inventory/domain/SalesOrderRepository.java`:

```java
package com.nexusops.inventory.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SalesOrderRepository extends JpaRepository<SalesOrder, UUID>, JpaSpecificationExecutor<SalesOrder> {}
```

`inventory/domain/SalesOrderLineRepository.java`:

```java
package com.nexusops.inventory.domain;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SalesOrderLineRepository extends JpaRepository<SalesOrderLine, UUID> {

    List<SalesOrderLine> findByOrderIdOrderByLineNoAsc(UUID orderId);

    List<SalesOrderLine> findByOrderIdInOrderByLineNoAsc(Collection<UUID> orderIds);

    void deleteByOrderId(UUID orderId);
}
```

- [ ] **Step 6: The service and the subject resolver**

`inventory/SalesOrderService.java`:

```java
package com.nexusops.inventory;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.catalog.ProductBrief;
import com.nexusops.collaboration.MemberRef;
import com.nexusops.directory.PartyRef;
import com.nexusops.directory.PartyRoleType;
import com.nexusops.directory.PartyService;
import com.nexusops.identity.Members;
import com.nexusops.inventory.domain.SalesOrder;
import com.nexusops.inventory.domain.SalesOrderLine;
import com.nexusops.inventory.domain.SalesOrderLineRepository;
import com.nexusops.inventory.domain.SalesOrderRepository;
import com.nexusops.inventory.domain.StockLevel;
import com.nexusops.inventory.domain.Warehouse;
import com.nexusops.shared.Currencies;
import com.nexusops.shared.Decimals;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.Text;
import com.nexusops.shared.web.ApiProblem;
import com.nexusops.shared.web.PageResponse;
import com.nexusops.shared.web.Paging;
import com.nexusops.tenancy.TenantDirectory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Sales orders (D10): DRAFT → CONFIRMED (reserves) → FULFILLED (issues); DRAFT/CONFIRMED → CANCELLED (releases). */
@Service
public class SalesOrderService {

    static final String DRAFT_ONLY = "Only a draft can be changed.";
    static final String CONFIRM_DRAFT_ONLY = "Only a draft can be confirmed.";
    static final String FULFIL_CONFIRMED_ONLY = "Only a confirmed sales order can be fulfilled.";
    static final String NOT_CANCELLABLE = "Only a draft or confirmed sales order can be cancelled.";

    private final SalesOrderRepository orders;
    private final SalesOrderLineRepository lines;
    private final NumberSequences numbers;
    private final InventoryProducts products;
    private final WarehouseService warehouses;
    private final OrderParties orderParties;
    private final PartyService parties;
    private final StockLedger ledger;
    private final Members members;
    private final TenantDirectory tenants;
    private final AuditService audit;

    SalesOrderService(SalesOrderRepository orders, SalesOrderLineRepository lines, NumberSequences numbers,
            InventoryProducts products, WarehouseService warehouses, OrderParties orderParties, PartyService parties,
            StockLedger ledger, Members members, TenantDirectory tenants, AuditService audit) {
        this.orders = orders;
        this.lines = lines;
        this.numbers = numbers;
        this.products = products;
        this.warehouses = warehouses;
        this.orderParties = orderParties;
        this.parties = parties;
        this.ledger = ledger;
        this.members = members;
        this.tenants = tenants;
        this.audit = audit;
    }

    private record Draft(UUID customerId, UUID warehouseId, String currency, String notes,
            List<SalesLineCommand> lines) {}

    @Transactional(readOnly = true)
    public SalesOrderView get(UUID id) {
        SalesOrder order = find(id);
        return view(order, lines.findByOrderIdOrderByLineNoAsc(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<SalesOrderSummary> list(SalesOrderQuery query, Integer page, Integer size) {
        TenantContext.requireTenantId();
        Specification<SalesOrder> spec = (root, cq, cb) -> cb.conjunction();
        if (query.status() != null) {
            spec = spec.and((root, cq, cb) -> cb.equal(root.get("status"), query.status()));
        }
        if (query.customerId() != null) {
            spec = spec.and((root, cq, cb) -> cb.equal(root.get("customerId"), query.customerId()));
        }
        if (query.warehouseId() != null) {
            spec = spec.and((root, cq, cb) -> cb.equal(root.get("warehouseId"), query.warehouseId()));
        }
        String q = Text.optional(query.q(), 100, "q");
        if (q != null) {
            String like = Text.containsPattern(q);
            spec = spec.and((root, cq, cb) -> cb.like(cb.lower(root.get("number")), like, '\\'));
        }
        Page<SalesOrder> result = orders.findAll(spec,
                Paging.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        List<SalesOrder> found = result.getContent();
        Map<UUID, List<SalesOrderLine>> byOrder = lines.findByOrderIdInOrderByLineNoAsc(
                found.stream().map(SalesOrder::getId).toList()).stream()
                .collect(Collectors.groupingBy(SalesOrderLine::getOrderId));
        Map<UUID, PartyRef> customers = orderParties.refs(found.stream().map(SalesOrder::getCustomerId)
                .collect(Collectors.toSet()));
        Map<UUID, Warehouse> places = warehouses.byIds(found.stream().map(SalesOrder::getWarehouseId)
                .collect(Collectors.toSet()));
        return new PageResponse<>(found.stream().map(o -> {
            List<SalesOrderLine> ls = byOrder.getOrDefault(o.getId(), List.of());
            return new SalesOrderSummary(o.getId(), o.getNumber(), customers.get(o.getCustomerId()),
                    WarehouseService.ref(places.get(o.getWarehouseId())), o.getStatus(), o.getCurrency(), total(ls),
                    ls.size(), o.getCreatedAt());
        }).toList(), result.getNumber(), result.getSize(), result.getTotalElements());
    }

    @Transactional
    public SalesOrderView create(SalesOrderCommand command) {
        TenantContext.requireTenantId();
        Draft draft = validate(command, null, List.of());
        SalesOrder order = new SalesOrder(Ids.newId(), numbers.next(SequenceKind.SALES_ORDER), draft.customerId(),
                draft.warehouseId(), draft.currency(), draft.notes(), TenantContext.userId().orElse(null));
        orders.saveAndFlush(order);
        List<SalesOrderLine> saved = saveLines(order.getId(), draft.lines());
        audit.record(AuditEntry.of("SalesOrderCreated", "SalesOrder", order.getId()).withAfter(snapshot(order, saved)));
        return view(order, saved);
    }

    @Transactional
    public SalesOrderView update(UUID id, SalesOrderCommand command, Long version) {
        SalesOrder order = find(id);
        Orders.checkVersion(order.getVersion(), version);
        if (order.getStatus() != SalesOrderStatus.DRAFT) {
            throw ApiProblem.conflict(DRAFT_ONLY);
        }
        List<SalesOrderLine> current = lines.findByOrderIdOrderByLineNoAsc(id);
        Draft draft = validate(command, order, current);
        Map<String, Object> before = snapshot(order, current);
        order.apply(draft.customerId(), draft.warehouseId(), draft.currency(), draft.notes());
        orders.flush();
        lines.deleteByOrderId(id);
        lines.flush();
        List<SalesOrderLine> saved = saveLines(id, draft.lines());
        audit.record(AuditEntry.of("SalesOrderUpdated", "SalesOrder", id).withBefore(before)
                .withAfter(snapshot(order, saved)));
        return view(order, saved);
    }

    /** All or nothing: lock every line's level, then either reserve them all or report every shortage. */
    @Transactional
    public SalesOrderView confirm(UUID id, Long version) {
        SalesOrder order = find(id);
        Orders.checkVersion(order.getVersion(), version);
        if (order.getStatus() != SalesOrderStatus.DRAFT) {
            throw ApiProblem.conflict(CONFIRM_DRAFT_ONLY);
        }
        orderParties.requireNotArchived(order.getCustomerId());
        warehouses.requireActive(order.getWarehouseId(), "warehouseId");
        List<SalesOrderLine> ls = lines.findByOrderIdOrderByLineNoAsc(id);
        Map<StockKey, StockLevel> locked = lock(order, ls);
        List<Shortage> shortages = new ArrayList<>();
        Map<UUID, ProductBrief> names = products.briefs(ls.stream().map(SalesOrderLine::getProductId).toList());
        for (SalesOrderLine line : ls) {
            StockLevel level = locked.get(key(order, line));
            if (level.getAvailable().compareTo(line.getQuantity()) < 0) {
                shortages.add(new Shortage(line.getProductId(), names.get(line.getProductId()).sku(),
                        line.getQuantity(), level.getAvailable()));
            }
        }
        if (!shortages.isEmpty()) {
            throw Shortage.conflict(shortages);
        }
        ls.forEach(line -> ledger.reserve(locked.get(key(order, line)), line.getQuantity()));
        order.confirm(Instant.now());
        orders.flush();
        parties.ensureRole(order.getCustomerId(), PartyRoleType.CUSTOMER);
        audit.record(AuditEntry.of("SalesOrderConfirmed", "SalesOrder", id).withAfter(Map.of("number", order.getNumber())));
        return view(order, ls);
    }

    @Transactional
    public SalesOrderView fulfil(UUID id, Long version) {
        SalesOrder order = find(id);
        Orders.checkVersion(order.getVersion(), version);
        if (order.getStatus() != SalesOrderStatus.CONFIRMED) {
            throw ApiProblem.conflict(FULFIL_CONFIRMED_ONLY);
        }
        List<SalesOrderLine> ls = lines.findByOrderIdOrderByLineNoAsc(id);
        Map<StockKey, StockLevel> locked = lock(order, ls);
        for (SalesOrderLine line : ls) {
            StockLevel level = locked.get(key(order, line));
            ledger.release(level, line.getQuantity());
            ledger.move(level, MovementKind.ISSUE, line.getQuantity().negate(), ReferenceType.SALES_ORDER,
                    order.getId(), order.getNumber());
        }
        order.fulfil(Instant.now());
        orders.flush();
        audit.record(AuditEntry.of("SalesOrderFulfilled", "SalesOrder", id).withAfter(Map.of("number", order.getNumber())));
        return view(order, ls);
    }

    @Transactional
    public SalesOrderView cancel(UUID id, Long version) {
        SalesOrder order = find(id);
        Orders.checkVersion(order.getVersion(), version);
        if (!order.getStatus().cancellable()) {
            throw ApiProblem.conflict(NOT_CANCELLABLE);
        }
        SalesOrderStatus before = order.getStatus();
        List<SalesOrderLine> ls = lines.findByOrderIdOrderByLineNoAsc(id);
        if (before == SalesOrderStatus.CONFIRMED) {
            Map<StockKey, StockLevel> locked = lock(order, ls);
            ls.forEach(line -> ledger.release(locked.get(key(order, line)), line.getQuantity()));
        }
        order.cancel(Instant.now());
        orders.flush();
        audit.record(AuditEntry.of("SalesOrderCancelled", "SalesOrder", id)
                .withBefore(Map.of("status", before.name())).withAfter(Map.of("number", order.getNumber())));
        return view(order, ls);
    }

    private Map<StockKey, StockLevel> lock(SalesOrder order, List<SalesOrderLine> ls) {
        return ledger.lock(ls.stream().map(line -> key(order, line)).toList());
    }

    private static StockKey key(SalesOrder order, SalesOrderLine line) {
        return new StockKey(line.getProductId(), order.getWarehouseId());
    }

    /** {@code current} is null on create. An unchanged customer, warehouse or line product isn't re-checked. */
    private Draft validate(SalesOrderCommand command, SalesOrder current, List<SalesOrderLine> currentLines) {
        Orders.requireCount(command.lines());
        String currency = Currencies.parse(command.currency(), "currency");
        String orderCurrency = currency != null ? currency : tenants.currentSettings().currency();
        String notes = Text.optional(command.notes(), 2000, "notes");
        Set<UUID> existingProducts = currentLines.stream().map(SalesOrderLine::getProductId)
                .collect(Collectors.toSet());
        Set<UUID> seen = new HashSet<>();
        List<SalesLineCommand> checked = new ArrayList<>();
        for (int i = 0; i < command.lines().size(); i++) {
            SalesLineCommand l = command.lines().get(i);
            BigDecimal quantity = Quantities.positive(l.quantity(), Orders.field(i, "quantity"));
            BigDecimal price = Decimals.nonNegative(l.unitPrice(), Orders.field(i, "unitPrice"),
                    "Enter a price of 0 or more.");
            if (l.productId() != null && !seen.add(l.productId())) {
                throw ApiProblem.badRequestField(Orders.field(i, "productId"), "This product is already on the order.");
            }
            checked.add(new SalesLineCommand(l.productId(), quantity, price));
        }
        if (command.customerId() == null) {
            throw ApiProblem.badRequestField("customerId", "Choose a customer.");
        }
        // products after the body checks: an unknown product is a 400, an archived new one a 409
        List<SalesLineCommand> clean = new ArrayList<>();
        for (int i = 0; i < checked.size(); i++) {
            SalesLineCommand l = checked.get(i);
            ProductBrief product = products.requireStockable(l.productId(), Orders.field(i, "productId"),
                    existingProducts.contains(l.productId()));
            BigDecimal price = l.unitPrice();
            if (price == null) {
                if (product.listPrice() == null || !orderCurrency.equals(product.currency())) {
                    throw ApiProblem.badRequestField(Orders.field(i, "unitPrice"), "Enter a price.");
                }
                price = product.listPrice();
            }
            clean.add(new SalesLineCommand(l.productId(), l.quantity(), price));
        }
        if (current == null || !command.customerId().equals(current.getCustomerId())) {
            orderParties.requireUsable(command.customerId(), "customerId", "Choose a customer.");
        }
        UUID warehouseId = command.warehouseId();
        if (current == null || !Objects.equals(warehouseId, current.getWarehouseId())) {
            warehouseId = warehouses.requireActive(warehouseId, "warehouseId").getId();
        }
        return new Draft(command.customerId(), warehouseId, orderCurrency, notes, clean);
    }

    private List<SalesOrderLine> saveLines(UUID orderId, List<SalesLineCommand> commands) {
        List<SalesOrderLine> rows = new ArrayList<>();
        for (int i = 0; i < commands.size(); i++) {
            SalesLineCommand c = commands.get(i);
            rows.add(new SalesOrderLine(Ids.newId(), orderId, i + 1, c.productId(), c.quantity(), c.unitPrice()));
        }
        return lines.saveAllAndFlush(rows);
    }

    private SalesOrderView view(SalesOrder o, List<SalesOrderLine> ls) {
        Map<UUID, ProductBrief> names = products.briefs(ls.stream().map(SalesOrderLine::getProductId)
                .collect(Collectors.toSet()));
        Members.Member creator = o.getCreatedBy() == null ? null
                : members.findAll(List.of(o.getCreatedBy())).get(o.getCreatedBy());
        List<SalesLineView> lineViews = ls.stream().map(l -> new SalesLineView(l.getId(), l.getLineNo(),
                InventoryProducts.ref(names.get(l.getProductId())), l.getQuantity(), l.getUnitPrice(),
                Orders.lineTotal(l.getQuantity(), l.getUnitPrice()))).toList();
        return new SalesOrderView(o.getId(), o.getNumber(),
                orderParties.refs(List.of(o.getCustomerId())).get(o.getCustomerId()),
                WarehouseService.ref(warehouses.byIds(List.of(o.getWarehouseId())).get(o.getWarehouseId())),
                o.getStatus(), o.getCurrency(), o.getNotes(), lineViews, total(ls), o.getConfirmedAt(),
                o.getFulfilledAt(), o.getCancelledAt(),
                creator == null ? null : new MemberRef(creator.id(), creator.name()), o.getCreatedAt(),
                o.getUpdatedAt(), o.getVersion());
    }

    private static BigDecimal total(List<SalesOrderLine> ls) {
        return ls.stream().map(l -> Orders.lineTotal(l.getQuantity(), l.getUnitPrice()))
                .reduce(BigDecimal.ZERO.setScale(4), BigDecimal::add);
    }

    private static Map<String, Object> snapshot(SalesOrder o, List<SalesOrderLine> ls) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("number", o.getNumber());
        values.put("customerId", o.getCustomerId().toString());
        values.put("warehouseId", o.getWarehouseId().toString());
        values.put("currency", o.getCurrency());
        values.put("lines", ls.stream().map(l -> Map.of("productId", l.getProductId().toString(),
                "quantity", l.getQuantity().toPlainString(), "unitPrice", l.getUnitPrice().toPlainString())).toList());
        return values;
    }

    private SalesOrder find(UUID id) {
        TenantContext.requireTenantId();
        return orders.findById(id).orElseThrow(() -> ApiProblem.notFound(Orders.NOT_FOUND));
    }
}
```

`confirm` re-checks the warehouse (`requireActive`): a draft whose warehouse was archived can't reserve there. Warehouses with open orders can't be archived (Step 7), so this only bites after a race, but it keeps "archived warehouses take no stock" absolute.

`inventory/SalesOrderSubjects.java`:

```java
package com.nexusops.inventory;

import com.nexusops.collaboration.SearchHit;
import com.nexusops.collaboration.SubjectRef;
import com.nexusops.collaboration.SubjectResolver;
import com.nexusops.directory.PartyRef;
import com.nexusops.inventory.domain.SalesOrder;
import com.nexusops.inventory.domain.SalesOrderRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/** Sales orders as collaboration subjects (type SALES_ORDER, D14). */
@Component
class SalesOrderSubjects implements SubjectResolver {

    static final String TYPE = "SALES_ORDER";

    private final SalesOrderRepository orders;
    private final OrderParties parties;

    SalesOrderSubjects(SalesOrderRepository orders, OrderParties parties) {
        this.orders = orders;
        this.parties = parties;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String readPermission() {
        return InventoryPermissions.ORDER_READ;
    }

    @Override
    public Optional<SubjectRef> find(UUID id) {
        return orders.findById(id).map(o -> new SubjectRef(TYPE, o.getId(), o.getNumber(), false));
    }

    @Override
    public Map<UUID, SubjectRef> findAll(Collection<UUID> ids) {
        return orders.findAllById(ids).stream().collect(Collectors.toMap(SalesOrder::getId,
                o -> new SubjectRef(TYPE, o.getId(), o.getNumber(), false)));
    }

    @Override
    public List<SearchHit> search(String pattern, int limit) {
        Specification<SalesOrder> spec = (root, cq, cb) -> cb.like(cb.lower(root.get("number")), pattern, '\\');
        List<SalesOrder> found = orders.findAll(spec,
                PageRequest.of(0, limit, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id")))).getContent();
        Map<UUID, PartyRef> names = parties.refs(found.stream().map(SalesOrder::getCustomerId)
                .collect(Collectors.toSet()));
        return found.stream().map(o -> new SearchHit(TYPE, o.getId(), o.getNumber(),
                names.containsKey(o.getCustomerId()) ? names.get(o.getCustomerId()).name() : null, false)).toList();
    }
}
```

- [ ] **Step 7: Warehouse archive refuses open sales orders**

In `WarehouseService.requireUnused`, after the purchase-order check, add:

```java
        Boolean selling = jdbc.queryForObject("select exists (select 1 from sales_orders where tenant_id = ? "
                + "and warehouse_id = ? and status in ('DRAFT', 'CONFIRMED'))", Boolean.class, tenant, warehouse.getId());
        if (Boolean.TRUE.equals(selling)) {
            throw ApiProblem.conflict(OPEN_ORDERS);
        }
```

- [ ] **Step 8: Web layer**

Add to `InventoryDtos`:

```java
    record SalesOrderRequest(UUID customerId, UUID warehouseId, String currency, String notes,
            List<SalesLineCommand> lines, Long version) {
        SalesOrderCommand command() {
            return new SalesOrderCommand(customerId, warehouseId, currency, notes, lines);
        }
    }
```

`inventory/web/SalesOrderController.java`:

```java
package com.nexusops.inventory.web;

import com.nexusops.inventory.SalesOrderQuery;
import com.nexusops.inventory.SalesOrderService;
import com.nexusops.inventory.SalesOrderStatus;
import com.nexusops.inventory.SalesOrderSummary;
import com.nexusops.inventory.SalesOrderView;
import com.nexusops.inventory.web.InventoryDtos.SalesOrderRequest;
import com.nexusops.inventory.web.InventoryDtos.VersionRequest;
import com.nexusops.shared.web.PageResponse;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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
@RequestMapping("/api/v1/sales-orders")
class SalesOrderController {

    private final SalesOrderService orders;

    SalesOrderController(SalesOrderService orders) {
        this.orders = orders;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('inventory.order.read')")
    PageResponse<SalesOrderSummary> list(@RequestParam(required = false) String q,
            @RequestParam(required = false) SalesOrderStatus status, @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) UUID warehouseId, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return orders.list(new SalesOrderQuery(q, status, customerId, warehouseId), page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('inventory.order.read')")
    SalesOrderView get(@PathVariable UUID id) {
        return orders.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('inventory.order.manage')")
    SalesOrderView create(@RequestBody SalesOrderRequest request) {
        return orders.create(request.command());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('inventory.order.manage')")
    SalesOrderView update(@PathVariable UUID id, @RequestBody SalesOrderRequest request) {
        return orders.update(id, request.command(), request.version());
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasAuthority('inventory.order.manage')")
    SalesOrderView confirm(@PathVariable UUID id, @RequestBody VersionRequest request) {
        return orders.confirm(id, request.version());
    }

    @PostMapping("/{id}/fulfil")
    @PreAuthorize("hasAuthority('inventory.order.manage')")
    SalesOrderView fulfil(@PathVariable UUID id, @RequestBody VersionRequest request) {
        return orders.fulfil(id, request.version());
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('inventory.order.manage')")
    SalesOrderView cancel(@PathVariable UUID id, @RequestBody VersionRequest request) {
        return orders.cancel(id, request.version());
    }
}
```

- [ ] **Step 9: Run the tests to verify they pass**

Run: `cd backend && ./gradlew test --tests '*SalesOrderApiIT' --tests '*PurchaseOrderApiIT' --tests '*StockApiIT' --tests '*WarehouseApiIT' --tests '*ModularityTest' --tests '*EndpointAuthorizationCoverageTest'`
Expected: PASS.

- [ ] **Step 10: Run the whole backend suite, then commit**

Run: `cd backend && ./gradlew test` — Expected: PASS.

```bash
git add backend/src/main/resources/db/migration/V21__sales_orders.sql backend/src/main/java/com/nexusops/inventory \
  backend/src/test/java/com/nexusops/inventory/SalesOrderApiIT.java
git commit -m "feat(inventory): sales orders that reserve on confirm, issue on fulfil and release on cancel"
```

---
### Task 5: Reorder rules, the stock list, explained suggestions and the overview

**Files:**
- Create: `backend/src/main/resources/db/migration/V22__reorder_rules.sql`
- Create: `backend/src/main/java/com/nexusops/inventory/ReorderMath.java`, `ReorderRuleCommand.java`, `ReorderRuleView.java`, `ReorderSuggestion.java`, `DraftOrdersCommand.java`, `DraftOrdersResult.java`, `StockRow.java`, `StockQuery.java`, `InventoryOverview.java`, `InventoryQueries.java`, `ReorderService.java`
- Create: `backend/src/main/java/com/nexusops/inventory/domain/ReorderRule.java`, `ReorderRuleRepository.java`
- Create: `backend/src/main/java/com/nexusops/inventory/web/ReorderController.java`
- Modify: `backend/src/main/java/com/nexusops/inventory/StockService.java` (`list`, `overview`), `web/StockController.java` (`GET /stock`, `GET /overview`), `web/InventoryDtos.java`
- Test: `backend/src/test/java/com/nexusops/inventory/ReorderMathTest.java`, `ReorderApiIT.java`

**Interfaces:**
- Consumes (Tasks 1–4): `InventoryProducts`, `WarehouseService.requireActive/byIds/ref`, `OrderParties.requireUsable/refs`, `Orders.checkVersion/NOT_FOUND/field`, `Quantities.count/positive`, `PurchaseOrderService.create(PurchaseOrderCommand)`, `PurchaseLineCommand`, `PurchaseOrderView`, `StockService.movements(MovementQuery, Integer, Integer)`, `MovementQuery(UUID productId, UUID warehouseId, MovementKind kind)`, `TenantLocks.lock(String)`, `TenantDirectory.currentSettings().currency()`.
- Produces:
  - `ReorderMath` (pure, package-private): `record Figures(BigDecimal available, BigDecimal onOrder, BigDecimal min, BigDecimal max, BigDecimal usedLast30Days)` with `boolean belowMin()`, `BigDecimal averageDailyUsage()` (used ÷ 30, scale 4), `BigDecimal daysOfCover()` (null without usage; available ÷ daily, scale 1), `BigDecimal suggestedQuantity()` (max − (available + on order), never below 0), `String explanation()`.
  - `ReorderRuleCommand(UUID productId, UUID warehouseId, BigDecimal minQuantity, BigDecimal maxQuantity, UUID supplierId)`; `ReorderRuleView(UUID id, ProductRef product, WarehouseRef warehouse, BigDecimal minQuantity, BigDecimal maxQuantity, PartyRef supplier, Instant updatedAt, long version)`.
  - `ReorderSuggestion(UUID ruleId, ProductRef product, WarehouseRef warehouse, BigDecimal available, BigDecimal onOrder, BigDecimal minQuantity, BigDecimal maxQuantity, PartyRef supplier, BigDecimal usedLast30Days, BigDecimal averageDailyUsage, BigDecimal daysOfCover, BigDecimal suggestedQuantity, String explanation)`.
  - `DraftOrdersCommand(List<DraftOrdersCommand.Item> items)`, `Item(UUID productId, UUID warehouseId, BigDecimal quantity)`; `DraftOrdersResult(List<PurchaseOrderView> orders)`.
  - `StockQuery(String q, UUID warehouseId, boolean belowMin)`; `StockRow(ProductRef product, WarehouseRef warehouse, BigDecimal onHand, BigDecimal reserved, BigDecimal available, BigDecimal onOrder, UUID ruleId, BigDecimal minQuantity, BigDecimal maxQuantity, boolean belowMin)`.
  - `InventoryOverview(long belowMinimum, long purchaseOrdersAwaitingReceipt, long salesOrdersAwaitingFulfilment, List<MovementView> recentMovements)`.
  - `ReorderService` public `rules(UUID productId, UUID warehouseId)`, `saveRule(ReorderRuleCommand, Long version)`, `deleteRule(UUID)`, `suggestions()`, `draftOrders(DraftOrdersCommand)`.
  - `StockService` public `list(StockQuery, Integer page, Integer size) : PageResponse<StockRow>`, `overview() : InventoryOverview`.
  - Routes: `GET /api/v1/inventory/stock`, `GET /api/v1/inventory/overview`, `GET|PUT /api/v1/inventory/reorder-rules`, `DELETE /api/v1/inventory/reorder-rules/{id}`, `GET /api/v1/inventory/reorder-suggestions`, `POST /api/v1/inventory/reorder-suggestions/purchase-orders` (201).

- [ ] **Step 1: Unit test the reorder arithmetic**

`backend/src/test/java/com/nexusops/inventory/ReorderMathTest.java`:

```java
package com.nexusops.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ReorderMathTest {

    private static ReorderMath.Figures figures(String available, String onOrder, String min, String max, String used) {
        return new ReorderMath.Figures(new BigDecimal(available), new BigDecimal(onOrder), new BigDecimal(min),
                new BigDecimal(max), new BigDecimal(used));
    }

    @Test
    void theSpecExampleExplainsItself() {
        ReorderMath.Figures f = figures("12", "0", "20", "60", "38");
        assertThat(f.belowMin()).isTrue();
        assertThat(f.averageDailyUsage()).isEqualByComparingTo("1.2667");
        assertThat(f.daysOfCover()).isEqualByComparingTo("9.5");
        assertThat(f.suggestedQuantity()).isEqualByComparingTo("48");
        assertThat(f.explanation()).isEqualTo("12 available, 0 on order, below the minimum of 20; "
                + "38 used in the last 30 days (about 9 days of cover); order 48 to reach 60");
    }

    @Test
    void onOrderCountsTowardsTheMinimumAndTheSuggestion() {
        assertThat(figures("12", "8", "20", "60", "0").belowMin()).isFalse();
        ReorderMath.Figures f = figures("12", "5", "20", "60", "0");
        assertThat(f.belowMin()).isTrue();
        assertThat(f.suggestedQuantity()).isEqualByComparingTo("43");
    }

    @Test
    void noUsageMeansNoDaysOfCover() {
        ReorderMath.Figures f = figures("3", "0", "5", "10", "0");
        assertThat(f.averageDailyUsage()).isEqualByComparingTo("0");
        assertThat(f.daysOfCover()).isNull();
        assertThat(f.explanation()).isEqualTo("3 available, 0 on order, below the minimum of 5; "
                + "no usage in the last 30 days; order 7 to reach 10");
    }

    @Test
    void fractionsAndSingularsReadNaturally() {
        ReorderMath.Figures f = figures("1.5", "0.25", "2", "4.5", "45");
        assertThat(f.daysOfCover()).isEqualByComparingTo("1.0");
        assertThat(f.suggestedQuantity()).isEqualByComparingTo("2.75");
        assertThat(f.explanation()).isEqualTo("1.5 available, 0.25 on order, below the minimum of 2; "
                + "45 used in the last 30 days (about 1 day of cover); order 2.75 to reach 4.5");
    }

    @Test
    void emptyShelvesHaveZeroDaysOfCoverAndTheSuggestionNeverGoesNegative() {
        assertThat(figures("0", "0", "5", "10", "30").daysOfCover()).isEqualByComparingTo("0");
        assertThat(figures("50", "20", "5", "10", "0").suggestedQuantity()).isEqualByComparingTo("0");
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && ./gradlew test --tests '*ReorderMathTest'`
Expected: FAIL — compilation error, `ReorderMath` does not exist.

- [ ] **Step 3: The arithmetic**

`inventory/ReorderMath.java`:

```java
package com.nexusops.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** The deterministic reorder rule (D11–D12, blueprint §5.4: rules first). */
final class ReorderMath {

    static final BigDecimal WINDOW_DAYS = BigDecimal.valueOf(30);

    private ReorderMath() {}

    record Figures(BigDecimal available, BigDecimal onOrder, BigDecimal min, BigDecimal max,
            BigDecimal usedLast30Days) {

        BigDecimal position() {
            return available.add(onOrder);
        }

        boolean belowMin() {
            return position().compareTo(min) < 0;
        }

        BigDecimal averageDailyUsage() {
            return usedLast30Days.divide(WINDOW_DAYS, 4, RoundingMode.HALF_UP);
        }

        /** Null when nothing was used: no usage means no rate to divide by. */
        BigDecimal daysOfCover() {
            BigDecimal daily = averageDailyUsage();
            if (daily.signum() == 0) {
                return null;
            }
            return available.max(BigDecimal.ZERO).divide(daily, 1, RoundingMode.HALF_UP);
        }

        BigDecimal suggestedQuantity() {
            return max.subtract(position()).max(BigDecimal.ZERO);
        }

        String explanation() {
            StringBuilder text = new StringBuilder()
                    .append(plain(available)).append(" available, ")
                    .append(plain(onOrder)).append(" on order, below the minimum of ").append(plain(min)).append("; ");
            BigDecimal daily = averageDailyUsage();
            if (daily.signum() == 0) {
                text.append("no usage in the last 30 days; ");
            } else {
                long days = available.max(BigDecimal.ZERO).divide(daily, 4, RoundingMode.HALF_UP)
                        .setScale(0, RoundingMode.HALF_UP).longValue();
                text.append(plain(usedLast30Days)).append(" used in the last 30 days (about ").append(days)
                        .append(days == 1 ? " day" : " days").append(" of cover); ");
            }
            return text.append("order ").append(plain(suggestedQuantity())).append(" to reach ").append(plain(max))
                    .toString();
        }
    }

    static String plain(BigDecimal value) {
        return value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
    }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `cd backend && ./gradlew test --tests '*ReorderMathTest'`
Expected: PASS.

- [ ] **Step 5: Write the failing API test**

`backend/src/test/java/com/nexusops/inventory/ReorderApiIT.java`:

```java
package com.nexusops.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestInventory;
import com.nexusops.support.TestMembers;
import com.nexusops.support.TestRoles;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Workspace;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@AutoConfigureMockMvc
class ReorderApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;
    UUID supplier, customer, widget, gadget, main;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("reorder"));
        owner = Api.login(mvc, ws);
        TestInventory.enable(owner);
        supplier = Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Konkan Supplies\"}"));
        customer = Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Deccan Retail\"}"));
        widget = TestInventory.goods(owner, "W-1", "Widget");
        gadget = TestInventory.goods(owner, "G-1", "Gadget");
        main = TestInventory.mainWarehouse(owner);
    }

    private ResultActions rule(UUID product, String min, String max, UUID preferred, Long version) throws Exception {
        return owner.put("/api/v1/inventory/reorder-rules", "{\"productId\":\"" + product + "\",\"warehouseId\":\""
                + main + "\",\"minQuantity\":" + min + ",\"maxQuantity\":" + max
                + (preferred == null ? "" : ",\"supplierId\":\"" + preferred + "\"")
                + (version == null ? "" : ",\"version\":" + version) + "}");
    }

    private void count(UUID product, String quantity) throws Exception {
        owner.post("/api/v1/inventory/adjustments", "{\"productId\":\"" + product + "\",\"warehouseId\":\"" + main
                + "\",\"countedQuantity\":" + quantity + ",\"reason\":\"Count\"}").andExpect(status().isOk());
    }

    /** Sells and ships {@code quantity} so it counts as usage. */
    private void sell(UUID product, String quantity) throws Exception {
        UUID order = Api.id(owner.post("/api/v1/sales-orders", "{\"customerId\":\"" + customer + "\",\"warehouseId\":\""
                + main + "\",\"lines\":[{\"productId\":\"" + product + "\",\"quantity\":" + quantity
                + ",\"unitPrice\":1}]}").andExpect(status().isCreated()));
        owner.post("/api/v1/sales-orders/" + order + "/confirm", "{\"version\":0}").andExpect(status().isOk());
        owner.post("/api/v1/sales-orders/" + order + "/fulfil", "{\"version\":1}").andExpect(status().isOk());
    }

    private long audits(String action) {
        return OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select count(*) from audit_events where action = ?", Long.class, action);
    }

    @Test
    void rulesAreUpsertedPerProductAndWarehouse() throws Exception {
        UUID id = Api.id(rule(widget, "20", "60", supplier, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.product.sku").value("W-1"))
                .andExpect(jsonPath("$.supplier.name").value("Konkan Supplies"))
                .andExpect(jsonPath("$.version").value(0)));
        rule(widget, "10", "30", null, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("version"));
        rule(widget, "10", "30", null, 5L).andExpect(status().isConflict());
        rule(widget, "10", "30", null, 0L).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.minQuantity").value(10.0)).andExpect(jsonPath("$.supplier").doesNotExist());
        owner.get("/api/v1/inventory/reorder-rules?productId=" + widget).andExpect(jsonPath("$.length()").value(1));
        owner.get("/api/v1/inventory/reorder-rules?productId=" + gadget).andExpect(jsonPath("$.length()").value(0));
        owner.delete("/api/v1/inventory/reorder-rules/" + id).andExpect(status().isNoContent());
        owner.delete("/api/v1/inventory/reorder-rules/" + id).andExpect(status().isNotFound());
        assertThat(audits("ReorderRuleSaved")).isEqualTo(2);
        assertThat(audits("ReorderRuleDeleted")).isEqualTo(1);
    }

    @Test
    void invalidRulesAreFieldErrors() throws Exception {
        UUID service = Api.id(owner.post("/api/v1/products", "{\"sku\":\"S-1\",\"name\":\"Setup\",\"kind\":\"SERVICE\"}"));
        rule(widget, "-1", "5", null, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("minQuantity"));
        rule(widget, "5", "5", null, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("maxQuantity"));
        rule(service, "1", "5", null, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("productId"));
        rule(widget, "1", "5", UUID.randomUUID(), null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("supplierId"));
    }

    @Test
    void theStockListShowsLevelsRulesAndWhatIsOnOrder() throws Exception {
        count(widget, "12");
        rule(gadget, "5", "10", supplier, null).andExpect(status().isOk());
        UUID po = Api.id(owner.post("/api/v1/purchase-orders", "{\"supplierId\":\"" + supplier + "\",\"warehouseId\":\""
                + main + "\",\"lines\":[{\"productId\":\"" + gadget + "\",\"quantity\":2,\"unitCost\":3}]}"));
        owner.post("/api/v1/purchase-orders/" + po + "/order", "{\"version\":0}").andExpect(status().isOk());
        owner.get("/api/v1/inventory/stock").andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[*].product.sku").value(Matchers.contains("G-1", "W-1")))
                .andExpect(jsonPath("$.items[0].onHand").value(0.0))
                .andExpect(jsonPath("$.items[0].onOrder").value(2.0))
                .andExpect(jsonPath("$.items[0].minQuantity").value(5.0))
                .andExpect(jsonPath("$.items[0].belowMin").value(true))
                .andExpect(jsonPath("$.items[1].available").value(12.0))
                .andExpect(jsonPath("$.items[1].ruleId").doesNotExist())
                .andExpect(jsonPath("$.items[1].belowMin").value(false));
        owner.get("/api/v1/inventory/stock?belowMin=true").andExpect(jsonPath("$.total").value(1));
        owner.get("/api/v1/inventory/stock?q=widg").andExpect(jsonPath("$.items[*].product.sku")
                .value(Matchers.contains("W-1")));
        UUID pune = Api.id(owner.post("/api/v1/inventory/warehouses", "{\"code\":\"PUNE\",\"name\":\"Pune\"}"));
        owner.get("/api/v1/inventory/stock?warehouseId=" + pune).andExpect(jsonPath("$.total").value(0));
        owner.post("/api/v1/products/" + widget + "/archive", "").andExpect(status().isOk());
        owner.get("/api/v1/inventory/stock").andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void suggestionsExplainThemselvesAndBecomeDraftOrders() throws Exception {
        count(widget, "50");
        sell(widget, "38");
        rule(widget, "20", "60", supplier, null).andExpect(status().isOk());
        count(gadget, "3");
        rule(gadget, "5", "10", null, null).andExpect(status().isOk());
        owner.get("/api/v1/inventory/reorder-suggestions").andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].product.sku").value("G-1"))
                .andExpect(jsonPath("$[0].daysOfCover").doesNotExist())
                .andExpect(jsonPath("$[0].explanation").value("3 available, 0 on order, below the minimum of 5; "
                        + "no usage in the last 30 days; order 7 to reach 10"))
                .andExpect(jsonPath("$[1].available").value(12.0))
                .andExpect(jsonPath("$[1].usedLast30Days").value(38.0))
                .andExpect(jsonPath("$[1].suggestedQuantity").value(48.0))
                .andExpect(jsonPath("$[1].supplier.name").value("Konkan Supplies"))
                .andExpect(jsonPath("$[1].explanation").value("12 available, 0 on order, below the minimum of 20; "
                        + "38 used in the last 30 days (about 9 days of cover); order 48 to reach 60"));

        String items = "{\"items\":[{\"productId\":\"" + widget + "\",\"warehouseId\":\"" + main + "\",\"quantity\":48}";
        owner.post("/api/v1/inventory/reorder-suggestions/purchase-orders", items + ",{\"productId\":\"" + gadget
                + "\",\"warehouseId\":\"" + main + "\",\"quantity\":7}]}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("items[1].productId"));
        owner.post("/api/v1/inventory/reorder-suggestions/purchase-orders", items + "]}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orders.length()").value(1))
                .andExpect(jsonPath("$.orders[0].status").value("DRAFT"))
                .andExpect(jsonPath("$.orders[0].supplier.name").value("Konkan Supplies"))
                .andExpect(jsonPath("$.orders[0].lines[0].quantity").value(48.0))
                .andExpect(jsonPath("$.orders[0].lines[0].unitCost").value(0.0));
        // a draft isn't on order yet; ordering it takes the product off the suggestions
        owner.get("/api/v1/inventory/reorder-suggestions").andExpect(jsonPath("$.length()").value(2));
        UUID draft = UUID.fromString(Api.read(owner.get("/api/v1/purchase-orders?status=DRAFT"), "$.items[0].id"));
        owner.post("/api/v1/purchase-orders/" + draft + "/order", "{\"version\":0}").andExpect(status().isOk());
        owner.get("/api/v1/inventory/reorder-suggestions").andExpect(jsonPath("$[*].product.sku")
                .value(Matchers.contains("G-1")));
    }

    @Test
    void draftsUseTheLastReceivedCostAndGroupBySupplier() throws Exception {
        UUID other = Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Malabar Traders\"}"));
        UUID po = Api.id(owner.post("/api/v1/purchase-orders", "{\"supplierId\":\"" + supplier + "\",\"warehouseId\":\""
                + main + "\",\"lines\":[{\"productId\":\"" + widget + "\",\"quantity\":1,\"unitCost\":4.25}]}"));
        owner.post("/api/v1/purchase-orders/" + po + "/order", "{\"version\":0}").andExpect(status().isOk());
        String line = Api.<List<String>>read(owner.get("/api/v1/purchase-orders/" + po), "$.lines[*].id").get(0);
        owner.post("/api/v1/purchase-orders/" + po + "/receipts", "{\"lines\":[{\"lineId\":\"" + line
                + "\",\"quantity\":1}],\"version\":1}").andExpect(status().isOk());
        rule(widget, "20", "60", supplier, null).andExpect(status().isOk());
        rule(gadget, "5", "10", other, null).andExpect(status().isOk());
        owner.post("/api/v1/inventory/reorder-suggestions/purchase-orders", "{\"items\":[{\"productId\":\"" + widget
                + "\",\"warehouseId\":\"" + main + "\",\"quantity\":59},{\"productId\":\"" + gadget
                + "\",\"warehouseId\":\"" + main + "\",\"quantity\":10}]}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.orders.length()").value(2))
                .andExpect(jsonPath("$.orders[0].supplier.name").value("Konkan Supplies"))
                .andExpect(jsonPath("$.orders[0].lines[0].unitCost").value(4.25))
                .andExpect(jsonPath("$.orders[1].supplier.name").value("Malabar Traders"));
    }

    @Test
    void theOverviewCountsWhatNeedsAttention() throws Exception {
        count(widget, "10");
        rule(gadget, "5", "10", supplier, null).andExpect(status().isOk());
        UUID po = Api.id(owner.post("/api/v1/purchase-orders", "{\"supplierId\":\"" + supplier + "\",\"warehouseId\":\""
                + main + "\",\"lines\":[{\"productId\":\"" + gadget + "\",\"quantity\":2,\"unitCost\":3}]}"));
        owner.post("/api/v1/purchase-orders/" + po + "/order", "{\"version\":0}").andExpect(status().isOk());
        UUID so = Api.id(owner.post("/api/v1/sales-orders", "{\"customerId\":\"" + customer + "\",\"warehouseId\":\""
                + main + "\",\"lines\":[{\"productId\":\"" + widget + "\",\"quantity\":1,\"unitPrice\":1}]}"));
        owner.post("/api/v1/sales-orders/" + so + "/confirm", "{\"version\":0}").andExpect(status().isOk());
        owner.get("/api/v1/inventory/overview")
                .andExpect(jsonPath("$.belowMinimum").value(1))
                .andExpect(jsonPath("$.purchaseOrdersAwaitingReceipt").value(1))
                .andExpect(jsonPath("$.salesOrdersAwaitingFulfilment").value(1))
                .andExpect(jsonPath("$.recentMovements[0].product.sku").value("W-1"));
    }

    @Test
    void permissions() throws Exception {
        UUID readerRole = TestRoles.create(mvc, owner.session(), "Stock reader", "inventory.stock.read");
        Api reader = Api.login(mvc, members.create(ws.tenantId(), Set.of(readerRole)));
        reader.get("/api/v1/inventory/reorder-suggestions").andExpect(status().isOk());
        reader.get("/api/v1/inventory/stock").andExpect(status().isOk());
        reader.get("/api/v1/inventory/overview").andExpect(status().isOk());
        reader.put("/api/v1/inventory/reorder-rules", "{}").andExpect(status().isForbidden());
        UUID plannerRole = TestRoles.create(mvc, owner.session(), "Planner", "inventory.stock.read",
                "inventory.reorder.manage");
        Api planner = Api.login(mvc, members.create(ws.tenantId(), Set.of(plannerRole)));
        planner.post("/api/v1/inventory/reorder-suggestions/purchase-orders", "{\"items\":[]}")
                .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 6: Run it to verify it fails**

Run: `cd backend && ./gradlew test --tests '*ReorderApiIT'`
Expected: FAIL — 404/405 on `/api/v1/inventory/reorder-rules`.

- [ ] **Step 7: Migration V22**

`backend/src/main/resources/db/migration/V22__reorder_rules.sql`:

```sql
-- Reorder rules (D11): one per product and warehouse.
CREATE TABLE reorder_rules (
    id            uuid PRIMARY KEY,
    tenant_id     uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    product_id    uuid NOT NULL,
    warehouse_id  uuid NOT NULL,
    min_quantity  numeric(19, 4) NOT NULL CHECK (min_quantity >= 0),
    max_quantity  numeric(19, 4) NOT NULL,
    supplier_id   uuid,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    version       bigint NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, product_id, warehouse_id),
    CHECK (max_quantity > min_quantity),
    FOREIGN KEY (tenant_id, product_id) REFERENCES products (tenant_id, id),
    FOREIGN KEY (tenant_id, warehouse_id) REFERENCES warehouses (tenant_id, id),
    FOREIGN KEY (tenant_id, supplier_id) REFERENCES parties (tenant_id, id)
);
CREATE INDEX reorder_rules_warehouse_idx ON reorder_rules (tenant_id, warehouse_id);

ALTER TABLE reorder_rules ENABLE ROW LEVEL SECURITY;
ALTER TABLE reorder_rules FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON reorder_rules
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
```

(A composite FK with a nullable `supplier_id` is only enforced when `supplier_id` is set — the default `MATCH SIMPLE` behaviour.)

- [ ] **Step 8: Types and the rule entity**

```java
package com.nexusops.inventory;

import java.math.BigDecimal;
import java.util.UUID;

public record ReorderRuleCommand(UUID productId, UUID warehouseId, BigDecimal minQuantity, BigDecimal maxQuantity,
        UUID supplierId) {}
```

```java
package com.nexusops.inventory;

import com.nexusops.directory.PartyRef;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ReorderRuleView(UUID id, ProductRef product, WarehouseRef warehouse, BigDecimal minQuantity,
        BigDecimal maxQuantity, PartyRef supplier, Instant updatedAt, long version) {}
```

```java
package com.nexusops.inventory;

import com.nexusops.directory.PartyRef;
import java.math.BigDecimal;
import java.util.UUID;

/** One rule below its minimum, with the figures that explain it (D12). */
public record ReorderSuggestion(UUID ruleId, ProductRef product, WarehouseRef warehouse, BigDecimal available,
        BigDecimal onOrder, BigDecimal minQuantity, BigDecimal maxQuantity, PartyRef supplier,
        BigDecimal usedLast30Days, BigDecimal averageDailyUsage, BigDecimal daysOfCover, BigDecimal suggestedQuantity,
        String explanation) {}
```

```java
package com.nexusops.inventory;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record DraftOrdersCommand(List<Item> items) {

    public record Item(UUID productId, UUID warehouseId, BigDecimal quantity) {}
}
```

```java
package com.nexusops.inventory;

import java.util.List;

public record DraftOrdersResult(List<PurchaseOrderView> orders) {}
```

```java
package com.nexusops.inventory;

import java.util.UUID;

public record StockQuery(String q, UUID warehouseId, boolean belowMin) {}
```

```java
package com.nexusops.inventory;

import java.math.BigDecimal;
import java.util.UUID;

/** A product × warehouse pair that has a stock level or a reorder rule (rule fields null without one). */
public record StockRow(ProductRef product, WarehouseRef warehouse, BigDecimal onHand, BigDecimal reserved,
        BigDecimal available, BigDecimal onOrder, UUID ruleId, BigDecimal minQuantity, BigDecimal maxQuantity,
        boolean belowMin) {}
```

```java
package com.nexusops.inventory;

import java.util.List;

public record InventoryOverview(long belowMinimum, long purchaseOrdersAwaitingReceipt,
        long salesOrdersAwaitingFulfilment, List<MovementView> recentMovements) {}
```

`inventory/domain/ReorderRule.java`:

```java
package com.nexusops.inventory.domain;

import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reorder_rules")
public class ReorderRule extends TenantOwnedEntity {

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private UUID warehouseId;

    @Column(name = "min_quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal minQuantity;

    @Column(name = "max_quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal maxQuantity;

    @Column(name = "supplier_id")
    private UUID supplierId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected ReorderRule() {}

    public ReorderRule(UUID id, UUID productId, UUID warehouseId) {
        super(id);
        this.productId = productId;
        this.warehouseId = warehouseId;
        this.createdAt = Instant.now();
    }

    public void apply(BigDecimal min, BigDecimal max, UUID supplier) {
        this.minQuantity = min;
        this.maxQuantity = max;
        this.supplierId = supplier;
        this.updatedAt = Instant.now();
    }

    public UUID getProductId() {
        return productId;
    }

    public UUID getWarehouseId() {
        return warehouseId;
    }

    public BigDecimal getMinQuantity() {
        return minQuantity;
    }

    public BigDecimal getMaxQuantity() {
        return maxQuantity;
    }

    public UUID getSupplierId() {
        return supplierId;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
```

`inventory/domain/ReorderRuleRepository.java`:

```java
package com.nexusops.inventory.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ReorderRuleRepository extends JpaRepository<ReorderRule, UUID>,
        JpaSpecificationExecutor<ReorderRule> {

    Optional<ReorderRule> findByProductIdAndWarehouseId(UUID productId, UUID warehouseId);
}
```

- [ ] **Step 9: The read models (SQL)**

`inventory/InventoryQueries.java` — every statement carries `tenant_id = :tenant` on every table it reads, on top of RLS. "Active" means the product and the warehouse aren't archived.

```java
package com.nexusops.inventory;

import com.nexusops.shared.TenantContext;
import com.nexusops.shared.Text;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** SQL read models for the stock list, reorder suggestions and the overview (D11–D13). */
@Component
class InventoryQueries {

    /** Product × warehouse pairs with a level or a rule, with on-hand, reserved, on order, rule and 30-day usage. */
    private static final String PAIRS = """
            with pairs as (
                select product_id, warehouse_id from stock_levels where tenant_id = :tenant
                union
                select product_id, warehouse_id from reorder_rules where tenant_id = :tenant
            ),
            on_order as (
                select l.product_id, o.warehouse_id, sum(l.quantity - l.received_quantity) as quantity
                from purchase_order_lines l
                join purchase_orders o on o.tenant_id = l.tenant_id and o.id = l.order_id
                where l.tenant_id = :tenant and o.tenant_id = :tenant
                  and o.status in ('ORDERED', 'PARTIALLY_RECEIVED')
                group by l.product_id, o.warehouse_id
            ),
            used as (
                select product_id, warehouse_id, -sum(quantity) as quantity
                from stock_movements
                where tenant_id = :tenant and kind = 'ISSUE' and occurred_at >= now() - interval '30 days'
                group by product_id, warehouse_id
            ),
            rows as (
                select p.id as product_id, p.sku, p.name as product_name, p.unit,
                       w.id as warehouse_id, w.code as warehouse_code, w.name as warehouse_name,
                       coalesce(s.on_hand, 0) as on_hand, coalesce(s.reserved, 0) as reserved,
                       coalesce(s.on_hand, 0) - coalesce(s.reserved, 0) as available,
                       coalesce(oo.quantity, 0) as on_order, coalesce(u.quantity, 0) as used,
                       r.id as rule_id, r.min_quantity, r.max_quantity, r.supplier_id
                from pairs x
                join products p on p.tenant_id = :tenant and p.id = x.product_id and p.archived_at is null
                join warehouses w on w.tenant_id = :tenant and w.id = x.warehouse_id and w.archived_at is null
                left join stock_levels s on s.tenant_id = :tenant and s.product_id = x.product_id
                     and s.warehouse_id = x.warehouse_id
                left join reorder_rules r on r.tenant_id = :tenant and r.product_id = x.product_id
                     and r.warehouse_id = x.warehouse_id
                left join on_order oo on oo.product_id = x.product_id and oo.warehouse_id = x.warehouse_id
                left join used u on u.product_id = x.product_id and u.warehouse_id = x.warehouse_id
            )
            """;

    private static final String BELOW_MIN = "rule_id is not null and available + on_order < min_quantity";

    record Row(UUID productId, String sku, String productName, String unit, UUID warehouseId, String warehouseCode,
            String warehouseName, BigDecimal onHand, BigDecimal reserved, BigDecimal available, BigDecimal onOrder,
            BigDecimal used, UUID ruleId, BigDecimal minQuantity, BigDecimal maxQuantity, UUID supplierId) {

        ProductRef product() {
            return new ProductRef(productId, sku, productName, unit);
        }

        WarehouseRef warehouse() {
            return new WarehouseRef(warehouseId, warehouseCode, warehouseName);
        }

        boolean belowMin() {
            return ruleId != null && available.add(onOrder).compareTo(minQuantity) < 0;
        }
    }

    record Counts(long belowMinimum, long purchaseOrdersAwaitingReceipt, long salesOrdersAwaitingFulfilment) {}

    private final NamedParameterJdbcTemplate jdbc;

    InventoryQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<Row> stockRows(StockQuery query, int limit, long offset) {
        MapSqlParameterSource params = params().addValue("limit", limit).addValue("offset", offset);
        return jdbc.query(PAIRS + "select * from rows where " + filters(query, params)
                + " order by lower(product_name), lower(sku), warehouse_code limit :limit offset :offset", params,
                InventoryQueries::row);
    }

    long countStockRows(StockQuery query) {
        MapSqlParameterSource params = params();
        Long total = jdbc.queryForObject(PAIRS + "select count(*) from rows where " + filters(query, params), params,
                Long.class);
        return total == null ? 0 : total;
    }

    /** Every rule below its minimum, for active products in active warehouses. */
    List<Row> belowMinimum() {
        return jdbc.query(PAIRS + "select * from rows where " + BELOW_MIN
                + " order by lower(product_name), lower(sku), warehouse_code", params(), InventoryQueries::row);
    }

    Counts counts() {
        MapSqlParameterSource params = params();
        Long below = jdbc.queryForObject(PAIRS + "select count(*) from rows where " + BELOW_MIN, params, Long.class);
        Long receiving = jdbc.queryForObject("select count(*) from purchase_orders where tenant_id = :tenant "
                + "and status in ('ORDERED', 'PARTIALLY_RECEIVED')", params, Long.class);
        Long shipping = jdbc.queryForObject("select count(*) from sales_orders where tenant_id = :tenant "
                + "and status = 'CONFIRMED'", params, Long.class);
        return new Counts(below == null ? 0 : below, receiving == null ? 0 : receiving, shipping == null ? 0 : shipping);
    }

    /** The unit cost on each product's most recently received purchase line in {@code currency}. */
    Map<UUID, BigDecimal> lastReceivedCosts(Collection<UUID> productIds, String currency) {
        Map<UUID, BigDecimal> costs = new HashMap<>();
        if (productIds.isEmpty()) {
            return costs;
        }
        jdbc.query("""
                select distinct on (l.product_id) l.product_id, l.unit_cost
                from purchase_order_lines l
                join purchase_orders o on o.tenant_id = l.tenant_id and o.id = l.order_id
                where l.tenant_id = :tenant and o.tenant_id = :tenant and l.product_id in (:products)
                  and l.received_quantity > 0 and o.currency = :currency
                order by l.product_id, o.updated_at desc, l.id
                """, params().addValue("products", productIds).addValue("currency", currency),
                rs -> {
                    costs.put(rs.getObject("product_id", UUID.class), rs.getBigDecimal("unit_cost"));
                });
        return costs;
    }

    private static String filters(StockQuery query, MapSqlParameterSource params) {
        StringBuilder where = new StringBuilder("true");
        if (query.warehouseId() != null) {
            where.append(" and warehouse_id = :warehouse");
            params.addValue("warehouse", query.warehouseId());
        }
        String q = Text.optional(query.q(), 100, "q");
        if (q != null) {
            where.append(" and (lower(sku) like :pattern escape '\\' or lower(product_name) like :pattern escape '\\')");
            params.addValue("pattern", Text.containsPattern(q));
        }
        if (query.belowMin()) {
            where.append(" and ").append(BELOW_MIN);
        }
        return where.toString();
    }

    private static MapSqlParameterSource params() {
        return new MapSqlParameterSource("tenant", TenantContext.requireTenantId());
    }

    private static Row row(ResultSet rs, int n) throws SQLException {
        return new Row(rs.getObject("product_id", UUID.class), rs.getString("sku"), rs.getString("product_name"),
                rs.getString("unit"), rs.getObject("warehouse_id", UUID.class), rs.getString("warehouse_code"),
                rs.getString("warehouse_name"), rs.getBigDecimal("on_hand"), rs.getBigDecimal("reserved"),
                rs.getBigDecimal("available"), rs.getBigDecimal("on_order"), rs.getBigDecimal("used"),
                rs.getObject("rule_id", UUID.class), rs.getBigDecimal("min_quantity"),
                rs.getBigDecimal("max_quantity"), rs.getObject("supplier_id", UUID.class));
    }
}
```

`Text.containsPattern` returns a lower-cased `%…%` pattern with `\ % _` escaped. The Java literal `escape '\\'` is the SQL `escape '\'`, which is what Postgres expects with `standard_conforming_strings` on (the default).

- [ ] **Step 10: The reorder service**

`inventory/ReorderService.java`:

```java
package com.nexusops.inventory;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.catalog.ProductBrief;
import com.nexusops.directory.PartyRef;
import com.nexusops.inventory.domain.ReorderRule;
import com.nexusops.inventory.domain.ReorderRuleRepository;
import com.nexusops.inventory.domain.Warehouse;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.db.TenantLocks;
import com.nexusops.shared.web.ApiProblem;
import com.nexusops.tenancy.TenantDirectory;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reorder rules and explained suggestions (D11–D12). */
@Service
public class ReorderService {

    static final String MAX_ABOVE_MIN = "Enter a maximum above the minimum.";
    static final String NO_RULE = "This product has no reorder rule for that warehouse.";
    static final String NO_SUPPLIER = "Set a preferred supplier on this product's reorder rule first.";
    static final String DUPLICATE = "This product and warehouse are already in the list.";

    private final ReorderRuleRepository rules;
    private final InventoryProducts products;
    private final WarehouseService warehouses;
    private final OrderParties parties;
    private final InventoryQueries queries;
    private final PurchaseOrderService purchaseOrders;
    private final TenantDirectory tenants;
    private final TenantLocks locks;
    private final AuditService audit;

    ReorderService(ReorderRuleRepository rules, InventoryProducts products, WarehouseService warehouses,
            OrderParties parties, InventoryQueries queries, PurchaseOrderService purchaseOrders,
            TenantDirectory tenants, TenantLocks locks, AuditService audit) {
        this.rules = rules;
        this.products = products;
        this.warehouses = warehouses;
        this.parties = parties;
        this.queries = queries;
        this.purchaseOrders = purchaseOrders;
        this.tenants = tenants;
        this.locks = locks;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<ReorderRuleView> rules(UUID productId, UUID warehouseId) {
        TenantContext.requireTenantId();
        Specification<ReorderRule> spec = (root, cq, cb) -> cb.conjunction();
        if (productId != null) {
            spec = spec.and((root, cq, cb) -> cb.equal(root.get("productId"), productId));
        }
        if (warehouseId != null) {
            spec = spec.and((root, cq, cb) -> cb.equal(root.get("warehouseId"), warehouseId));
        }
        return views(rules.findAll(spec, Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id"))));
    }

    /** Upsert by (product, warehouse). A new rule takes no version; changing one needs the current version. */
    @Transactional
    public ReorderRuleView saveRule(ReorderRuleCommand command, Long version) {
        TenantContext.requireTenantId();
        BigDecimal min = Quantities.count(command.minQuantity(), "minQuantity");
        BigDecimal max = Quantities.count(command.maxQuantity(), "maxQuantity");
        if (max.compareTo(min) <= 0) {
            throw ApiProblem.badRequestField("maxQuantity", MAX_ABOVE_MIN);
        }
        if (command.productId() == null) {
            throw ApiProblem.badRequestField("productId", "Choose a product in this workspace.");
        }
        if (command.warehouseId() == null) {
            throw ApiProblem.badRequestField("warehouseId", "Choose a warehouse in this workspace.");
        }
        locks.lock("reorder-rule:" + command.productId() + ":" + command.warehouseId());
        ReorderRule rule = rules.findByProductIdAndWarehouseId(command.productId(), command.warehouseId())
                .orElse(null);
        if (rule != null) {
            Orders.checkVersion(rule.getVersion(), version);
        }
        products.requireStockable(command.productId(), "productId", rule != null);
        if (rule == null) {
            warehouses.requireActive(command.warehouseId(), "warehouseId");
        }
        if (command.supplierId() != null && (rule == null || !command.supplierId().equals(rule.getSupplierId()))) {
            parties.requireUsable(command.supplierId(), "supplierId", "Choose a supplier.");
        }
        Map<String, Object> before = rule == null ? null : snapshot(rule);
        if (rule == null) {
            rule = new ReorderRule(Ids.newId(), command.productId(), command.warehouseId());
        }
        rule.apply(min, max, command.supplierId());
        rules.saveAndFlush(rule);
        AuditEntry entry = AuditEntry.of("ReorderRuleSaved", "ReorderRule", rule.getId()).withAfter(snapshot(rule));
        audit.record(before == null ? entry : entry.withBefore(before));
        return views(List.of(rule)).get(0);
    }

    @Transactional
    public void deleteRule(UUID id) {
        TenantContext.requireTenantId();
        ReorderRule rule = rules.findById(id).orElseThrow(() -> ApiProblem.notFound(Orders.NOT_FOUND));
        rules.delete(rule);
        rules.flush();
        audit.record(AuditEntry.of("ReorderRuleDeleted", "ReorderRule", id).withBefore(snapshot(rule)));
    }

    @Transactional(readOnly = true)
    public List<ReorderSuggestion> suggestions() {
        TenantContext.requireTenantId();
        List<InventoryQueries.Row> rows = queries.belowMinimum();
        Map<UUID, PartyRef> suppliers = parties.refs(rows.stream().map(InventoryQueries.Row::supplierId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        return rows.stream().map(r -> {
            ReorderMath.Figures f = new ReorderMath.Figures(r.available(), r.onOrder(), r.minQuantity(),
                    r.maxQuantity(), r.used());
            return new ReorderSuggestion(r.ruleId(), r.product(), r.warehouse(), r.available(), r.onOrder(),
                    r.minQuantity(), r.maxQuantity(), r.supplierId() == null ? null : suppliers.get(r.supplierId()),
                    r.used(), f.averageDailyUsage(), f.daysOfCover(), f.suggestedQuantity(), f.explanation());
        }).toList();
    }

    /** One DRAFT purchase order per (supplier, warehouse), in the order the items first name them. */
    @Transactional
    public DraftOrdersResult draftOrders(DraftOrdersCommand command) {
        TenantContext.requireTenantId();
        List<DraftOrdersCommand.Item> items = command.items();
        if (items == null || items.isEmpty()) {
            throw ApiProblem.badRequestField("items", "Choose at least one suggestion.");
        }
        record Key(UUID supplierId, UUID warehouseId) {}
        Map<Key, List<PurchaseLineCommand>> groups = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            DraftOrdersCommand.Item item = items.get(i);
            String field = "items[" + i + "].";
            BigDecimal quantity = Quantities.positive(item.quantity(), field + "quantity");
            if (item.productId() == null || item.warehouseId() == null) {
                throw ApiProblem.badRequestField(field + "productId", NO_RULE);
            }
            if (!seen.add(item.productId() + ":" + item.warehouseId())) {
                throw ApiProblem.badRequestField(field + "productId", DUPLICATE);
            }
            ReorderRule rule = rules.findByProductIdAndWarehouseId(item.productId(), item.warehouseId())
                    .orElseThrow(() -> ApiProblem.badRequestField(field + "productId", NO_RULE));
            if (rule.getSupplierId() == null) {
                throw ApiProblem.badRequestField(field + "productId", NO_SUPPLIER);
            }
            groups.computeIfAbsent(new Key(rule.getSupplierId(), rule.getWarehouseId()), k -> new ArrayList<>())
                    .add(new PurchaseLineCommand(item.productId(), quantity, null));
        }
        String currency = tenants.currentSettings().currency();
        Map<UUID, BigDecimal> costs = queries.lastReceivedCosts(items.stream()
                .map(DraftOrdersCommand.Item::productId).collect(Collectors.toSet()), currency);
        List<PurchaseOrderView> created = new ArrayList<>();
        groups.forEach((key, lines) -> created.add(purchaseOrders.create(new PurchaseOrderCommand(key.supplierId(),
                key.warehouseId(), currency, null, null, lines.stream().map(l -> new PurchaseLineCommand(
                        l.productId(), l.quantity(), costs.getOrDefault(l.productId(), BigDecimal.ZERO))).toList()))));
        return new DraftOrdersResult(created);
    }

    private List<ReorderRuleView> views(List<ReorderRule> list) {
        Map<UUID, ProductBrief> names = products.briefs(list.stream().map(ReorderRule::getProductId)
                .collect(Collectors.toSet()));
        Map<UUID, Warehouse> places = warehouses.byIds(list.stream().map(ReorderRule::getWarehouseId)
                .collect(Collectors.toSet()));
        Map<UUID, PartyRef> suppliers = parties.refs(list.stream().map(ReorderRule::getSupplierId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        return list.stream().map(r -> new ReorderRuleView(r.getId(), InventoryProducts.ref(names.get(r.getProductId())),
                WarehouseService.ref(places.get(r.getWarehouseId())), r.getMinQuantity(), r.getMaxQuantity(),
                r.getSupplierId() == null ? null : suppliers.get(r.getSupplierId()), r.getUpdatedAt(), r.getVersion()))
                .toList();
    }

    private static Map<String, Object> snapshot(ReorderRule r) {
        Map<String, Object> values = new HashMap<>();
        values.put("productId", r.getProductId().toString());
        values.put("warehouseId", r.getWarehouseId().toString());
        values.put("minQuantity", r.getMinQuantity().toPlainString());
        values.put("maxQuantity", r.getMaxQuantity().toPlainString());
        values.put("supplierId", r.getSupplierId() == null ? null : r.getSupplierId().toString());
        return values;
    }
}
```

`snapshot` uses a `HashMap` because `Map.of` refuses the null `supplierId`.

- [ ] **Step 11: Stock list and overview on the stock service**

Add to `StockService` (constructor gains `InventoryQueries queries`):

```java
    @Transactional(readOnly = true)
    public PageResponse<StockRow> list(StockQuery query, Integer page, Integer size) {
        TenantContext.requireTenantId();
        Pageable paging = Paging.of(page, size);
        List<StockRow> rows = queries.stockRows(query, paging.getPageSize(), paging.getOffset()).stream()
                .map(r -> new StockRow(r.product(), r.warehouse(), r.onHand(), r.reserved(), r.available(),
                        r.onOrder(), r.ruleId(), r.minQuantity(), r.maxQuantity(), r.belowMin()))
                .toList();
        return new PageResponse<>(rows, paging.getPageNumber(), paging.getPageSize(), queries.countStockRows(query));
    }

    @Transactional(readOnly = true)
    public InventoryOverview overview() {
        TenantContext.requireTenantId();
        InventoryQueries.Counts counts = queries.counts();
        return new InventoryOverview(counts.belowMinimum(), counts.purchaseOrdersAwaitingReceipt(),
                counts.salesOrdersAwaitingFulfilment(), movements(new MovementQuery(null, null, null), 0, 10).items());
    }
```

(imports: `org.springframework.data.domain.Pageable`.)

- [ ] **Step 12: Web layer**

Add to `InventoryDtos`:

```java
    record ReorderRuleRequest(UUID productId, UUID warehouseId, BigDecimal minQuantity, BigDecimal maxQuantity,
            UUID supplierId, Long version) {
        ReorderRuleCommand command() {
            return new ReorderRuleCommand(productId, warehouseId, minQuantity, maxQuantity, supplierId);
        }
    }
```

Add to `StockController`:

```java
    @GetMapping("/stock")
    @PreAuthorize("hasAuthority('inventory.stock.read')")
    PageResponse<StockRow> stock(@RequestParam(required = false) String q,
            @RequestParam(required = false) UUID warehouseId, @RequestParam(defaultValue = "false") boolean belowMin,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return stock.list(new StockQuery(q, warehouseId, belowMin), page, size);
    }

    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('inventory.stock.read')")
    InventoryOverview overview() {
        return stock.overview();
    }
```

`inventory/web/ReorderController.java`:

```java
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
```

- [ ] **Step 13: Run the tests to verify they pass**

Run: `cd backend && ./gradlew test --tests '*ReorderApiIT' --tests '*ReorderMathTest' --tests '*StockApiIT' --tests '*ModularityTest' --tests '*EndpointAuthorizationCoverageTest'`
Expected: PASS.

- [ ] **Step 14: Run the whole backend suite, then commit**

Run: `cd backend && ./gradlew test` — Expected: PASS.

```bash
git add backend/src/main/resources/db/migration/V22__reorder_rules.sql backend/src/main/java/com/nexusops/inventory \
  backend/src/test/java/com/nexusops/inventory/ReorderMathTest.java backend/src/test/java/com/nexusops/inventory/ReorderApiIT.java
git commit -m "feat(inventory): reorder rules, stock list, explained reorder suggestions and the overview"
```

---
### Task 6: Timelines, search, isolation proofs, the module gate and the ADR

**Files:**
- Create: `backend/src/main/java/com/nexusops/inventory/InventoryRelations.java`
- Modify: `backend/src/main/java/com/nexusops/collaboration/SearchService.java` (`ORDER`)
- Create: `backend/src/test/java/com/nexusops/InventoryRlsIT.java`, `backend/src/test/java/com/nexusops/inventory/InventoryModuleGateIT.java`
- Modify: `backend/src/test/java/com/nexusops/RlsCoverageIT.java`, `CrossTenantApiIT.java`, `OpenApiContractIT.java`, `collaboration/SearchApiIT.java`
- Create: `docs/decisions/0011-inventory-ledger-and-orders.md`; Modify: `docs/api/openapi.json` (re-exported)

**Interfaces:**
- Consumes (Tasks 1–5): `PurchaseOrderSubjects.TYPE` (`"PURCHASE_ORDER"`), `SalesOrderSubjects.TYPE` (`"SALES_ORDER"`), `PurchaseOrderRepository`, `SalesOrderRepository` (both `JpaSpecificationExecutor`), `SubjectRelations`, `SubjectKey(String type, UUID id)`, every Inventory route, `TestInventory`.
- Produces: `InventoryRelations` (a party's timeline includes its purchase and sales orders); `SearchService.ORDER = [PARTY, LEAD, OPPORTUNITY, PRODUCT, PURCHASE_ORDER, SALES_ORDER]`.

- [ ] **Step 1: Write the failing tests for search and the party timeline**

Add to `collaboration/SearchApiIT.java` (imports `com.nexusops.support.TestInventory`, `java.util.UUID` if missing):

```java
    @Test
    void findsOrdersByNumberAfterTheOtherTypes() throws Exception {
        TestInventory.enable(owner);
        UUID supplier = Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Ordering Co\"}"));
        UUID widget = TestInventory.goods(owner, "PO-W", "Order widget");
        UUID main = TestInventory.mainWarehouse(owner);
        owner.post("/api/v1/purchase-orders", "{\"supplierId\":\"" + supplier + "\",\"warehouseId\":\"" + main
                + "\",\"lines\":[{\"productId\":\"" + widget + "\",\"quantity\":1,\"unitCost\":1}]}")
                .andExpect(status().isCreated());
        owner.post("/api/v1/sales-orders", "{\"customerId\":\"" + supplier + "\",\"warehouseId\":\"" + main
                + "\",\"lines\":[{\"productId\":\"" + widget + "\",\"quantity\":1,\"unitPrice\":1}]}")
                .andExpect(status().isCreated());
        owner.get("/api/v1/search?q=o-0000").andExpect(jsonPath("$[*].type")
                .value(Matchers.contains("PURCHASE_ORDER", "SALES_ORDER")))
                .andExpect(jsonPath("$[0].label").value("PO-00001"))
                .andExpect(jsonPath("$[0].detail").value("Ordering Co"));
    }
```

Add to `inventory/PurchaseOrderApiIT.java`:

```java
    @Test
    void theSuppliersTimelineIncludesItsOrders() throws Exception {
        UUID order = draft();
        owner.post("/api/v1/activities", "{\"subjectType\":\"PURCHASE_ORDER\",\"subjectId\":\"" + order
                + "\",\"type\":\"NOTE\",\"summary\":\"Chased the supplier\"}").andExpect(status().isCreated());
        owner.get("/api/v1/activities?subjectType=PARTY&subjectId=" + supplier + "&includeRelated=true")
                .andExpect(jsonPath("$.items[*].summary").value(Matchers.hasItem("Chased the supplier")));
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && ./gradlew test --tests '*SearchApiIT' --tests '*PurchaseOrderApiIT'`
Expected: FAIL — search returns no order types; the party timeline lacks the order's note.

- [ ] **Step 3: Relations and search order**

`inventory/InventoryRelations.java`:

```java
package com.nexusops.inventory;

import com.nexusops.collaboration.SubjectKey;
import com.nexusops.collaboration.SubjectRelations;
import com.nexusops.inventory.domain.PurchaseOrder;
import com.nexusops.inventory.domain.PurchaseOrderRepository;
import com.nexusops.inventory.domain.SalesOrder;
import com.nexusops.inventory.domain.SalesOrderRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/** A party's timeline includes its purchase orders (as supplier) and sales orders (as customer). */
@Component
class InventoryRelations implements SubjectRelations {

    private static final int LIMIT = 100;
    private static final Sort ORDER = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"));

    private final PurchaseOrderRepository purchaseOrders;
    private final SalesOrderRepository salesOrders;

    InventoryRelations(PurchaseOrderRepository purchaseOrders, SalesOrderRepository salesOrders) {
        this.purchaseOrders = purchaseOrders;
        this.salesOrders = salesOrders;
    }

    @Override
    public List<SubjectKey> related(String type, UUID id) {
        List<SubjectKey> keys = new ArrayList<>();
        if (type.equals("PARTY")) {
            Specification<PurchaseOrder> supplied = (root, cq, cb) -> cb.equal(root.get("supplierId"), id);
            purchaseOrders.findAll(supplied, PageRequest.of(0, LIMIT, ORDER))
                    .forEach(o -> keys.add(new SubjectKey(PurchaseOrderSubjects.TYPE, o.getId())));
            Specification<SalesOrder> sold = (root, cq, cb) -> cb.equal(root.get("customerId"), id);
            salesOrders.findAll(sold, PageRequest.of(0, LIMIT, ORDER))
                    .forEach(o -> keys.add(new SubjectKey(SalesOrderSubjects.TYPE, o.getId())));
        }
        return keys;
    }
}
```

In `collaboration/SearchService.java`:

```java
    static final List<String> ORDER = List.of("PARTY", "LEAD", "OPPORTUNITY", "PRODUCT", "PURCHASE_ORDER", "SALES_ORDER");
```

- [ ] **Step 4: Run them to verify they pass**

Run: `cd backend && ./gradlew test --tests '*SearchApiIT' --tests '*PurchaseOrderApiIT'`
Expected: PASS.

- [ ] **Step 5: Database isolation for the nine new tables**

`backend/src/test/java/com/nexusops/InventoryRlsIT.java`:

```java
package com.nexusops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** The database alone isolates every Phase 6 table, independent of application code. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InventoryRlsIT extends IntegrationTestSupport {

    static final List<String> TABLES = List.of("number_sequences", "warehouses", "stock_levels", "stock_movements",
            "purchase_orders", "purchase_order_lines", "sales_orders", "sales_order_lines", "reorder_rules");

    final UUID tenantA = UUID.randomUUID();
    final UUID tenantB = UUID.randomUUID();
    final UUID party = UUID.randomUUID();
    final UUID product = UUID.randomUUID();
    final UUID warehouse = UUID.randomUUID();
    final UUID level = UUID.randomUUID();
    final UUID movement = UUID.randomUUID();
    final UUID purchaseOrder = UUID.randomUUID();
    final UUID purchaseLine = UUID.randomUUID();
    final UUID salesOrder = UUID.randomUUID();
    final UUID salesLine = UUID.randomUUID();
    final UUID rule = UUID.randomUUID();

    @Autowired JdbcTemplate contextStarted;

    @BeforeAll
    void seedTenantB() {
        Timestamp now = Timestamp.from(Instant.now());
        for (UUID t : new UUID[] {tenantA, tenantB}) {
            OwnerJdbc.jdbc().update("insert into tenants (id, slug, name, status, plan_code, created_at, updated_at) "
                    + "values (?, ?, 'T', 'ACTIVE', 'FREE', ?, ?)", t, "inv-" + t.toString().substring(0, 8), now, now);
        }
        JdbcTemplate b = OwnerJdbc.ownerAs(tenantB);
        b.update("insert into parties (id, tenant_id, kind, name, name_key, created_at, updated_at) "
                + "values (?, ?, 'ORGANIZATION', 'Beta', 'beta', ?, ?)", party, tenantB, now, now);
        b.update("insert into products (id, tenant_id, sku, name, kind, unit, created_at, updated_at) "
                + "values (?, ?, 'B-1', 'Beta widget', 'GOODS', 'each', ?, ?)", product, tenantB, now, now);
        b.update("insert into warehouses (id, tenant_id, code, code_key, name, created_at, updated_at) "
                + "values (?, ?, 'BETA', 'beta', 'Beta store', ?, ?)", warehouse, tenantB, now, now);
        b.update("insert into number_sequences (tenant_id, kind, next_value) values (?, 'PURCHASE_ORDER', 2)", tenantB);
        b.update("insert into stock_levels (id, tenant_id, product_id, warehouse_id, on_hand, reserved, updated_at) "
                + "values (?, ?, ?, ?, 5, 1, ?)", level, tenantB, product, warehouse, now);
        b.update("insert into stock_movements (id, tenant_id, product_id, warehouse_id, kind, quantity, on_hand_after, "
                + "reference_type, reference_id, occurred_at) values (?, ?, ?, ?, 'ADJUSTMENT', 5, 5, 'ADJUSTMENT', ?, ?)",
                movement, tenantB, product, warehouse, UUID.randomUUID(), now);
        b.update("insert into purchase_orders (id, tenant_id, number, supplier_id, warehouse_id, status, currency, "
                + "created_at, updated_at) values (?, ?, 'PO-00001', ?, ?, 'DRAFT', 'USD', ?, ?)",
                purchaseOrder, tenantB, party, warehouse, now, now);
        b.update("insert into purchase_order_lines (id, tenant_id, order_id, line_no, product_id, quantity, unit_cost) "
                + "values (?, ?, ?, 1, ?, 3, 2)", purchaseLine, tenantB, purchaseOrder, product);
        b.update("insert into sales_orders (id, tenant_id, number, customer_id, warehouse_id, status, currency, "
                + "created_at, updated_at) values (?, ?, 'SO-00001', ?, ?, 'DRAFT', 'USD', ?, ?)",
                salesOrder, tenantB, party, warehouse, now, now);
        b.update("insert into sales_order_lines (id, tenant_id, order_id, line_no, product_id, quantity, unit_price) "
                + "values (?, ?, ?, 1, ?, 1, 9)", salesLine, tenantB, salesOrder, product);
        b.update("insert into reorder_rules (id, tenant_id, product_id, warehouse_id, min_quantity, max_quantity, "
                + "created_at, updated_at) values (?, ?, ?, ?, 2, 10, ?, ?)", rule, tenantB, product, warehouse, now, now);
    }

    private static JdbcTemplate app(String tenantSetting) {
        return OwnerJdbc.tenantScoped("nexusops_app", APP_PASSWORD, tenantSetting);
    }

    @Test
    void anotherTenantsContextAndNoContextSeeNothing() {
        for (String table : TABLES) {
            assertThat(app(tenantA.toString()).queryForObject("select count(*) from " + table, Long.class)).as(table)
                    .isZero();
            assertThat(app("").queryForObject("select count(*) from " + table, Long.class)).as(table).isZero();
            assertThat(app(tenantB.toString()).queryForObject("select count(*) from " + table, Long.class)).as(table)
                    .isPositive();
        }
    }

    private void insertTenantBRow(JdbcTemplate as, String table) {
        Timestamp now = Timestamp.from(Instant.now());
        UUID id = UUID.randomUUID();
        switch (table) {
            case "number_sequences" -> as.update("insert into number_sequences (tenant_id, kind, next_value) "
                    + "values (?, 'SALES_ORDER', 1)", tenantB);
            case "warehouses" -> as.update("insert into warehouses (id, tenant_id, code, code_key, name, created_at, "
                    + "updated_at) values (?, ?, 'EVIL', 'evil', 'Evil', ?, ?)", id, tenantB, now, now);
            case "stock_levels" -> as.update("insert into stock_levels (id, tenant_id, product_id, warehouse_id, "
                    + "updated_at) values (?, ?, ?, ?, ?)", id, tenantB, product, UUID.randomUUID(), now);
            case "stock_movements" -> as.update("insert into stock_movements (id, tenant_id, product_id, warehouse_id, "
                    + "kind, quantity, on_hand_after, reference_type, reference_id, occurred_at) "
                    + "values (?, ?, ?, ?, 'ADJUSTMENT', 1, 6, 'ADJUSTMENT', ?, ?)", id, tenantB, product, warehouse,
                    UUID.randomUUID(), now);
            case "purchase_orders" -> as.update("insert into purchase_orders (id, tenant_id, number, supplier_id, "
                    + "warehouse_id, status, currency, created_at, updated_at) "
                    + "values (?, ?, 'PO-00009', ?, ?, 'DRAFT', 'USD', ?, ?)", id, tenantB, party, warehouse, now, now);
            case "purchase_order_lines" -> as.update("insert into purchase_order_lines (id, tenant_id, order_id, "
                    + "line_no, product_id, quantity, unit_cost) values (?, ?, ?, 9, ?, 1, 1)", id, tenantB,
                    purchaseOrder, UUID.randomUUID());
            case "sales_orders" -> as.update("insert into sales_orders (id, tenant_id, number, customer_id, "
                    + "warehouse_id, status, currency, created_at, updated_at) "
                    + "values (?, ?, 'SO-00009', ?, ?, 'DRAFT', 'USD', ?, ?)", id, tenantB, party, warehouse, now, now);
            case "sales_order_lines" -> as.update("insert into sales_order_lines (id, tenant_id, order_id, line_no, "
                    + "product_id, quantity, unit_price) values (?, ?, ?, 9, ?, 1, 1)", id, tenantB, salesOrder,
                    UUID.randomUUID());
            case "reorder_rules" -> as.update("insert into reorder_rules (id, tenant_id, product_id, warehouse_id, "
                    + "min_quantity, max_quantity, created_at, updated_at) values (?, ?, ?, ?, 1, 2, ?, ?)", id, tenantB,
                    UUID.randomUUID(), warehouse, now, now);
            default -> throw new IllegalArgumentException(table);
        }
    }

    @Test
    void insertsIntoAnotherTenantAreRejectedByRowLevelSecurity() {
        for (String table : TABLES) {
            assertThatThrownBy(() -> insertTenantBRow(app(tenantA.toString()), table)).as(table + " as tenant A")
                    .isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> insertTenantBRow(app(""), table)).as(table + " without tenant context")
                    .isInstanceOf(DataAccessException.class);
        }
    }

    @Test
    void updatesAndDeletesOfAnotherTenantsRowsAffectNothing() {
        record Case(String update, String delete, UUID id) {}
        for (Case c : List.of(
                new Case("update warehouses set name = 'Evil' where id = ?", "delete from warehouses where id = ?",
                        warehouse),
                new Case("update stock_levels set on_hand = 99 where id = ?", "delete from stock_levels where id = ?",
                        level),
                new Case("update purchase_orders set notes = 'Evil' where id = ?",
                        "delete from purchase_orders where id = ?", purchaseOrder),
                new Case("update purchase_order_lines set quantity = 99 where id = ?",
                        "delete from purchase_order_lines where id = ?", purchaseLine),
                new Case("update sales_orders set notes = 'Evil' where id = ?", "delete from sales_orders where id = ?",
                        salesOrder),
                new Case("update sales_order_lines set quantity = 99 where id = ?",
                        "delete from sales_order_lines where id = ?", salesLine),
                new Case("update reorder_rules set min_quantity = 0 where id = ?",
                        "delete from reorder_rules where id = ?", rule))) {
            assertThat(app(tenantA.toString()).update(c.update(), c.id())).isZero();
            assertThat(app(tenantA.toString()).update(c.delete(), c.id())).isZero();
        }
        assertThat(app(tenantA.toString()).update("update number_sequences set next_value = 99 where tenant_id = ?",
                tenantB)).isZero();
        for (String table : TABLES) {
            assertThat(OwnerJdbc.ownerAs(tenantB).queryForObject("select count(*) from " + table, Long.class)).as(table)
                    .isPositive();
        }
    }

    @Test
    void theLedgerIsAppendOnlyEvenForItsOwnTenant() {
        assertThatThrownBy(() -> app(tenantB.toString()).update("update stock_movements set quantity = 99 where id = ?",
                movement)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> app(tenantB.toString()).update("delete from stock_movements where id = ?", movement))
                .isInstanceOf(DataAccessException.class);
        assertThat(OwnerJdbc.ownerAs(tenantB).queryForObject("select quantity from stock_movements where id = ?",
                java.math.BigDecimal.class, movement)).isEqualByComparingTo("5");
    }
}
```

In `RlsCoverageIT.EXPECTED_TENANT_TABLES` add `"number_sequences", "warehouses", "stock_levels", "stock_movements", "purchase_orders", "purchase_order_lines", "sales_orders", "sales_order_lines", "reorder_rules"`.

- [ ] **Step 6: The module gate**

`backend/src/test/java/com/nexusops/inventory/InventoryModuleGateIT.java`:

```java
package com.nexusops.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestInventory;
import com.nexusops.support.TestTenants;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Disabling Inventory removes every Inventory permission, so every Inventory route answers 403 (criterion 4). */
@AutoConfigureMockMvc
class InventoryModuleGateIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mapping;

    @Test
    void everyInventoryHandlerRequiresAnInventoryPermission() {
        List<String> violations = new ArrayList<>();
        Set<String> checked = new HashSet<>();
        mapping.getHandlerMethods().forEach((info, handler) -> {
            for (String path : info.getPatternValues()) {
                if (!(path.startsWith("/api/v1/inventory/") || path.startsWith("/api/v1/purchase-orders")
                        || path.startsWith("/api/v1/sales-orders"))) {
                    continue;
                }
                var preAuthorize = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), PreAuthorize.class);
                if (preAuthorize == null) {
                    preAuthorize = AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), PreAuthorize.class);
                }
                String expression = preAuthorize == null ? "" : preAuthorize.value().replace(" ", "");
                checked.add(path);
                if (!(expression.contains("hasAuthority('inventory.")
                        || expression.contains("hasAnyAuthority('inventory."))) {
                    violations.add(info.getMethodsCondition() + " " + path + " -> " + expression);
                }
            }
        });
        assertThat(checked).as("Inventory routes were found").hasSizeGreaterThanOrEqualTo(20);
        assertThat(violations).isEmpty();
    }

    @Test
    void everyInventoryRouteIsForbiddenWhileTheModuleIsOff() throws Exception {
        Api owner = Api.login(mvc, TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("igate")));
        TestInventory.enable(owner);
        UUID main = TestInventory.mainWarehouse(owner);
        UUID widget = TestInventory.goods(owner, "G-1", "Gate widget");
        owner.put("/api/v1/tenant/modules/INVENTORY", "{\"enabled\":false}").andExpect(status().isOk());
        UUID any = UUID.randomUUID();
        owner.get("/api/v1/inventory/warehouses").andExpect(status().isForbidden());
        owner.post("/api/v1/inventory/warehouses", "{\"code\":\"X\",\"name\":\"X\"}").andExpect(status().isForbidden());
        owner.post("/api/v1/inventory/warehouses/" + main + "/archive", "").andExpect(status().isForbidden());
        owner.get("/api/v1/inventory/stock").andExpect(status().isForbidden());
        owner.get("/api/v1/inventory/stock/products/" + widget).andExpect(status().isForbidden());
        owner.get("/api/v1/inventory/movements").andExpect(status().isForbidden());
        owner.post("/api/v1/inventory/adjustments", "{}").andExpect(status().isForbidden());
        owner.post("/api/v1/inventory/transfers", "{}").andExpect(status().isForbidden());
        owner.get("/api/v1/inventory/overview").andExpect(status().isForbidden());
        owner.get("/api/v1/inventory/reorder-rules").andExpect(status().isForbidden());
        owner.put("/api/v1/inventory/reorder-rules", "{}").andExpect(status().isForbidden());
        owner.get("/api/v1/inventory/reorder-suggestions").andExpect(status().isForbidden());
        owner.post("/api/v1/inventory/reorder-suggestions/purchase-orders", "{}").andExpect(status().isForbidden());
        owner.get("/api/v1/purchase-orders").andExpect(status().isForbidden());
        owner.post("/api/v1/purchase-orders", "{}").andExpect(status().isForbidden());
        owner.post("/api/v1/purchase-orders/" + any + "/receipts", "{}").andExpect(status().isForbidden());
        owner.get("/api/v1/sales-orders").andExpect(status().isForbidden());
        owner.post("/api/v1/sales-orders/" + any + "/confirm", "{}").andExpect(status().isForbidden());
        TestInventory.enable(owner);
        owner.get("/api/v1/inventory/warehouses").andExpect(status().isOk());
    }
}
```

- [ ] **Step 7: Another tenant's inventory is invisible and untouchable**

In `CrossTenantApiIT`, add fields `UUID warehouseB, goodsB, purchaseOrderB, purchaseLineB, salesOrderB, ruleB;`, and at the end of `twoTenants()`:

```java
        as(ownerB, put("/api/v1/tenant/modules/INVENTORY").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}")).andExpect(status().isOk());
        warehouseB = UUID.fromString(com.nexusops.support.Api.read(apiB.get("/api/v1/inventory/warehouses"), "$[0].id"));
        goodsB = com.nexusops.support.Api.id(apiB.post("/api/v1/products",
                "{\"sku\":\"B-G\",\"name\":\"Beta goods\",\"kind\":\"GOODS\"}"));
        apiB.post("/api/v1/inventory/adjustments", "{\"productId\":\"" + goodsB + "\",\"warehouseId\":\"" + warehouseB
                + "\",\"countedQuantity\":5,\"reason\":\"Opening\"}").andExpect(status().isOk());
        purchaseOrderB = com.nexusops.support.Api.id(apiB.post("/api/v1/purchase-orders", "{\"supplierId\":\"" + orgB
                + "\",\"warehouseId\":\"" + warehouseB + "\",\"lines\":[{\"productId\":\"" + goodsB
                + "\",\"quantity\":2,\"unitCost\":1}]}"));
        purchaseLineB = UUID.fromString(com.nexusops.support.Api.<java.util.List<String>>read(
                apiB.get("/api/v1/purchase-orders/" + purchaseOrderB), "$.lines[*].id").get(0));
        salesOrderB = com.nexusops.support.Api.id(apiB.post("/api/v1/sales-orders", "{\"customerId\":\"" + orgB
                + "\",\"warehouseId\":\"" + warehouseB + "\",\"lines\":[{\"productId\":\"" + goodsB
                + "\",\"quantity\":1,\"unitPrice\":1}]}"));
        ruleB = com.nexusops.support.Api.id(apiB.put("/api/v1/inventory/reorder-rules", "{\"productId\":\"" + goodsB
                + "\",\"warehouseId\":\"" + warehouseB + "\",\"minQuantity\":1,\"maxQuantity\":9}"));
```

Then add the test:

```java
    @Test
    void inventoryRecordsOfAnotherTenantAreInvisibleAndUntouchable() throws Exception {
        as(ownerA, put("/api/v1/tenant/modules/INVENTORY").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}")).andExpect(status().isOk());
        as(ownerA, json(put("/api/v1/inventory/warehouses/" + warehouseB), "{\"code\":\"H\",\"name\":\"H\",\"version\":0}"))
                .andExpect(status().isNotFound());
        as(ownerA, post("/api/v1/inventory/warehouses/" + warehouseB + "/archive")).andExpect(status().isNotFound());
        as(ownerA, post("/api/v1/inventory/warehouses/" + warehouseB + "/restore")).andExpect(status().isNotFound());
        as(ownerA, get("/api/v1/inventory/stock/products/" + goodsB)).andExpect(status().isNotFound());
        as(ownerA, get("/api/v1/purchase-orders/" + purchaseOrderB)).andExpect(status().isNotFound());
        as(ownerA, json(put("/api/v1/purchase-orders/" + purchaseOrderB), "{\"supplierId\":\"" + orgB
                + "\",\"warehouseId\":\"" + warehouseB + "\",\"lines\":[],\"version\":0}")).andExpect(status().isNotFound());
        for (String action : new String[] {"order", "cancel"}) {
            as(ownerA, json(post("/api/v1/purchase-orders/" + purchaseOrderB + "/" + action), "{\"version\":0}"))
                    .andExpect(status().isNotFound());
        }
        as(ownerA, json(post("/api/v1/purchase-orders/" + purchaseOrderB + "/receipts"), "{\"lines\":[{\"lineId\":\""
                + purchaseLineB + "\",\"quantity\":1}],\"version\":0}")).andExpect(status().isNotFound());
        as(ownerA, get("/api/v1/sales-orders/" + salesOrderB)).andExpect(status().isNotFound());
        as(ownerA, json(put("/api/v1/sales-orders/" + salesOrderB), "{\"customerId\":\"" + orgB
                + "\",\"warehouseId\":\"" + warehouseB + "\",\"lines\":[],\"version\":0}")).andExpect(status().isNotFound());
        for (String action : new String[] {"confirm", "fulfil", "cancel"}) {
            as(ownerA, json(post("/api/v1/sales-orders/" + salesOrderB + "/" + action), "{\"version\":0}"))
                    .andExpect(status().isNotFound());
        }
        as(ownerA, delete("/api/v1/inventory/reorder-rules/" + ruleB)).andExpect(status().isNotFound());
        as(ownerA, get("/api/v1/activities").param("subjectType", "PURCHASE_ORDER")
                .param("subjectId", purchaseOrderB.toString())).andExpect(status().isNotFound());

        // references to another tenant's rows inside request bodies are refused
        String warehouseA = JsonPath.<java.util.List<String>>read(as(ownerA, get("/api/v1/inventory/warehouses"))
                .andReturn().getResponse().getContentAsString(), "$[*].id").get(0);
        as(ownerA, json(post("/api/v1/inventory/adjustments"), "{\"productId\":\"" + goodsB + "\",\"warehouseId\":\""
                + warehouseA + "\",\"countedQuantity\":1,\"reason\":\"x\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("productId"));
        String goodsA = JsonPath.read(as(ownerA, json(post("/api/v1/products"),
                "{\"sku\":\"A-G\",\"name\":\"Alpha goods\",\"kind\":\"GOODS\"}")).andReturn().getResponse()
                .getContentAsString(), "$.id");
        as(ownerA, json(post("/api/v1/inventory/transfers"), "{\"productId\":\"" + goodsA + "\",\"fromWarehouseId\":\""
                + warehouseA + "\",\"toWarehouseId\":\"" + warehouseB + "\",\"quantity\":1}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("toWarehouseId"));
        as(ownerA, json(post("/api/v1/purchase-orders"), "{\"supplierId\":\"" + orgB + "\",\"warehouseId\":\""
                + warehouseA + "\",\"lines\":[{\"productId\":\"" + goodsA + "\",\"quantity\":1,\"unitCost\":1}]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("supplierId"));
        as(ownerA, json(put("/api/v1/inventory/reorder-rules"), "{\"productId\":\"" + goodsA + "\",\"warehouseId\":\""
                + warehouseB + "\",\"minQuantity\":1,\"maxQuantity\":2}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("warehouseId"));

        // lists, search and the overview never contain B's rows
        as(ownerA, get("/api/v1/inventory/stock")).andExpect(jsonPath("$.total").value(0));
        as(ownerA, get("/api/v1/inventory/movements")).andExpect(jsonPath("$.total").value(0));
        as(ownerA, get("/api/v1/purchase-orders")).andExpect(jsonPath("$.total").value(0));
        as(ownerA, get("/api/v1/sales-orders")).andExpect(jsonPath("$.total").value(0));
        as(ownerA, get("/api/v1/inventory/reorder-rules")).andExpect(jsonPath("$.length()").value(0));
        as(ownerA, get("/api/v1/inventory/warehouses")).andExpect(jsonPath("$.length()").value(1));
        as(ownerA, get("/api/v1/search").param("q", "o-0000")).andExpect(jsonPath("$.length()").value(0));
        as(ownerA, get("/api/v1/inventory/overview")).andExpect(jsonPath("$.recentMovements.length()").value(0));

        // tenant B is untouched
        as(ownerB, get("/api/v1/purchase-orders/" + purchaseOrderB)).andExpect(jsonPath("$.status").value("DRAFT"));
        as(ownerB, get("/api/v1/inventory/stock/products/" + goodsB)).andExpect(jsonPath("$.onHand").value(5.0));
    }
}
```

The setup adds no parties, so `everyIdBearingEndpointReturns404ForAnotherTenantsIds`'s party count for tenant B stays 2; product counts are only asserted for tenant A, which this setup doesn't touch.

- [ ] **Step 8: The OpenAPI contract and export**

In `OpenApiContractIT.documentListsTheV1RoutesAndBearerScheme`, add to the `contains(...)` list:

```java
                "\"/api/v1/inventory/warehouses\"", "\"/api/v1/inventory/warehouses/{id}\"",
                "\"/api/v1/inventory/warehouses/{id}/archive\"", "\"/api/v1/inventory/warehouses/{id}/restore\"",
                "\"/api/v1/inventory/stock\"", "\"/api/v1/inventory/stock/products/{productId}\"",
                "\"/api/v1/inventory/movements\"", "\"/api/v1/inventory/adjustments\"", "\"/api/v1/inventory/transfers\"",
                "\"/api/v1/inventory/overview\"", "\"/api/v1/inventory/reorder-rules\"",
                "\"/api/v1/inventory/reorder-rules/{id}\"", "\"/api/v1/inventory/reorder-suggestions\"",
                "\"/api/v1/inventory/reorder-suggestions/purchase-orders\"", "\"/api/v1/purchase-orders\"",
                "\"/api/v1/purchase-orders/{id}\"", "\"/api/v1/purchase-orders/{id}/order\"",
                "\"/api/v1/purchase-orders/{id}/cancel\"", "\"/api/v1/purchase-orders/{id}/receipts\"",
                "\"/api/v1/sales-orders\"", "\"/api/v1/sales-orders/{id}\"", "\"/api/v1/sales-orders/{id}/confirm\"",
                "\"/api/v1/sales-orders/{id}/fulfil\"", "\"/api/v1/sales-orders/{id}/cancel\"",
```

- [ ] **Step 9: Run the platform suites**

Run: `cd backend && ./gradlew test --tests '*InventoryRlsIT' --tests '*RlsCoverageIT' --tests '*InventoryModuleGateIT' --tests '*CrossTenantApiIT' --tests '*OpenApiContractIT' --tests '*SearchApiIT' --tests '*ModularityTest' --tests '*EndpointAuthorizationCoverageTest'`
Expected: PASS.

- [ ] **Step 10: ADR-0011**

`docs/decisions/0011-inventory-ledger-and-orders.md`:

```markdown
# ADR-0011: Inventory — a locked level projection over an append-only ledger, orders that move stock

- Status: Accepted
- Date: 2026-10-08
- Spec: docs/superpowers/specs/2026-10-08-inventory-mvp-design.md

## Context
Phase 6 adds stock. Stock must never go negative, every change must be explainable, and two people selling the last
units at the same moment must not both succeed. Products already live in the catalog (Phase 4); suppliers and
customers are directory parties (ADR-0008).

## Decision
1. **`stock_levels` is a projection, `stock_movements` the books.** One level row per (product, warehouse) holds
   on-hand and reserved; every on-hand change appends one movement in the same transaction. The app role has no UPDATE
   or DELETE on movements, so the ledger is append-only at the database. CHECKs keep `on_hand ≥ 0`,
   `0 ≤ reserved ≤ on_hand`, and each movement's sign matching its kind.
2. **One writer.** Only `StockLedger` changes stock. It creates missing level rows (`insert … on conflict do nothing`),
   then takes row locks in ascending (product, warehouse) order, so concurrent operations on the same items serialize
   and can't deadlock each other. Shortages are checked after locking.
3. **Orders move stock at their commitment points.** A purchase order receives into stock (partial receipts, never
   above the ordered quantity). A sales order reserves everything on confirm (or refuses with every shortage), issues
   on fulfil and releases on cancel. Drafts touch nothing, and parties get the SUPPLIER or CUSTOMER role only when an
   order is placed or confirmed.
4. **Document numbers** (`PO-00001`, `SO-00001`) come from a per-tenant `number_sequences` row updated in the order's
   transaction, so numbers are unique and only skip on rollback.
5. **Reorder suggestions are a deterministic rule** (blueprint §5.4, rules first): below minimum when available + on
   order < min; suggest max − (available + on order); explain with 30-day usage and days of cover. Suggestions become
   DRAFT purchase orders that a person reviews and places.
6. Read models (stock list, suggestions, overview) are SQL with an explicit `tenant_id` predicate on top of RLS, like
   ADR-0010's aggregates. Every Inventory permission belongs to module INVENTORY.

## Consequences
- The level can be rebuilt from the ledger, and a test proves they agree after every operation.
- Lock order is a rule every future stock writer must follow; keeping all writes in `StockLedger` enforces it.
- Forecasting beyond the rule (Phases 11–12), valuation, lots, serials and bins, units-of-measure conversion,
  partial fulfilment, back-orders and returns, and supplier price lists are later work (spec §1 non-goals).
```

- [ ] **Step 11: Re-export the OpenAPI document, run the whole suite, commit**

Run: `cd backend && ./gradlew test --tests '*OpenApiContractIT' -Dopenapi.export=true` — then `git diff --stat docs/api/openapi.json` shows the new paths.

(`backend/build.gradle.kts` forwards `openapi.export` to the test JVM.)

Run: `cd backend && ./gradlew test` — Expected: PASS.

```bash
git add backend/src docs/decisions/0011-inventory-ledger-and-orders.md docs/api/openapi.json
git commit -m "test(inventory): isolation, module gate and cross-tenant proofs; search, timelines and ADR-0011"
```

---
### Task 7: Frontend foundations — types, permissions, the Inventory area, overview and Settings → Warehouses

**Files:**
- Modify: `frontend/src/lib/api/types.ts`, `frontend/src/features/auth/permissions.tsx`, `frontend/src/test/records.ts`
- Move: `frontend/src/features/crm/PartyPicker.tsx` → `frontend/src/features/records/PartyPicker.tsx` (update its importers in `features/crm`)
- Modify: `frontend/src/features/records/SubjectLink.tsx`, `frontend/src/features/shell/GlobalSearch.tsx`, `frontend/src/features/shell/routes.tsx`, `frontend/src/features/shell/nav.ts`, `frontend/src/features/shell/ComingSoonPage.test.tsx`
- Create: `frontend/src/features/inventory/labels.ts`, `quantity.ts`, `quantity.test.ts`, `WarehouseSelect.tsx`, `ProductPicker.tsx`, `MovementsTable.tsx`, `InventoryLayout.tsx`, `InventoryLayout.test.tsx`, `InventoryOverviewPage.tsx`, `InventoryOverviewPage.test.tsx`, `routes.tsx`
- Create: `frontend/src/features/settings/WarehousesSettingsPage.tsx`, `WarehousesSettingsPage.test.tsx`

**Interfaces:**
- Consumes (backend Tasks 1–6): every Inventory route and JSON shape (records in Java become the TS interfaces below, field for field).
- Produces (later frontend tasks rely on these exact names):
  - Types: `WarehouseRef`, `WarehouseView`, `StockProductRef`, `StockLevelView`, `ProductStock`, `MovementKind`, `ReferenceType`, `MovementView`, `StockRow`, `Shortage`, `PurchaseOrderStatus`, `PurchaseLineView`, `PurchaseOrderView`, `PurchaseOrderSummary`, `SalesOrderStatus`, `SalesLineView`, `SalesOrderView`, `SalesOrderSummary`, `ReorderRuleView`, `ReorderSuggestion`, `InventoryOverview`; `SubjectType` gains `'PURCHASE_ORDER' | 'SALES_ORDER'`.
  - `PERMISSIONS.stockRead|stockAdjust|warehouseManage|purchaseRead|purchaseManage|orderRead|orderManage|reorderManage`.
  - Fixtures: `aWarehouse`, `aProductStock`, `aMovement`, `aStockRow`, `aPurchaseOrder`, `aPurchaseSummary`, `aSalesOrder`, `aSalesSummary`, `aReorderRule`, `aSuggestion`, `anOverview`.
  - `features/inventory/quantity.ts`: `formatQuantity(value: number, unit?: string): string`, `quantitySchema` (≥ 0, ≤ 4 decimals), `positiveQuantitySchema` (> 0).
  - `features/inventory/labels.ts`: `MOVEMENT_KIND_LABELS`, `PURCHASE_STATUS_LABELS`, `SALES_STATUS_LABELS`.
  - `WarehouseSelect({ id, label, value, onChange, error?, blankLabel? })` (active warehouses; `blankLabel` adds a '' option).
  - `ProductPicker({ id, label, value, onChange, current?, error? })` (GOODS products; `onChange(id, product: StockProductRef | null)`).
  - `MovementsTable({ movements, showProduct? })`.
  - `features/records/PartyPicker` (moved, unchanged).
  - `InventoryLayout` with `TABS` (this task: Overview); `inventoryChildren: RouteObject[]` in `features/inventory/routes.tsx` that later tasks extend.
  - Query keys (shared by every Inventory page so one invalidation refreshes them): `['inventory']` prefix — `['inventory', 'overview']`, `['inventory', 'warehouses', archived]`, `['inventory', 'stock', …]`, `['inventory', 'product-stock', productId]`, `['inventory', 'movements', …]`, `['inventory', 'purchase-orders', …]`, `['inventory', 'purchase-order', id]`, `['inventory', 'sales-orders', …]`, `['inventory', 'sales-order', id]`, `['inventory', 'reorder-rules']`, `['inventory', 'suggestions']`; `invalidateInventory(queryClient)` in `features/inventory/invalidation.ts` invalidates the whole prefix.

- [ ] **Step 1: Types**

Append to `frontend/src/lib/api/types.ts`:

```ts
export interface WarehouseRef {
  id: string
  code: string
  name: string
}

export interface WarehouseView extends WarehouseRef {
  address: string | null
  archivedAt: string | null
  version: number
}

export interface StockProductRef {
  id: string
  sku: string
  name: string
  unit: string
}

export interface StockLevelView {
  warehouse: WarehouseRef
  onHand: number
  reserved: number
  available: number
}

export interface ProductStock {
  product: StockProductRef
  levels: StockLevelView[]
  onHand: number
  reserved: number
  available: number
}

export type MovementKind = 'RECEIPT' | 'ISSUE' | 'ADJUSTMENT' | 'TRANSFER_OUT' | 'TRANSFER_IN'
export type ReferenceType = 'PURCHASE_ORDER' | 'SALES_ORDER' | 'TRANSFER' | 'ADJUSTMENT'

export interface MovementView {
  id: string
  product: StockProductRef
  warehouse: WarehouseRef
  kind: MovementKind
  /** Signed: issues and transfers out are negative. */
  quantity: number
  onHandAfter: number
  referenceType: ReferenceType
  referenceId: string
  reason: string | null
  actor: MemberRef | null
  occurredAt: string
}

export interface StockRow {
  product: StockProductRef
  warehouse: WarehouseRef
  onHand: number
  reserved: number
  available: number
  onOrder: number
  ruleId: string | null
  minQuantity: number | null
  maxQuantity: number | null
  belowMin: boolean
}

/** 409 "Not enough stock." carries these in `shortages`. */
export interface Shortage {
  productId: string
  sku: string
  requested: number
  available: number
}

export type PurchaseOrderStatus =
  'DRAFT' | 'ORDERED' | 'PARTIALLY_RECEIVED' | 'RECEIVED' | 'CANCELLED'

export interface PurchaseLineView {
  id: string
  lineNo: number
  product: StockProductRef
  quantity: number
  receivedQuantity: number
  remainingQuantity: number
  unitCost: number
  lineTotal: number
}

export interface PurchaseOrderView {
  id: string
  number: string
  supplier: PartyRef | null
  warehouse: WarehouseRef
  status: PurchaseOrderStatus
  currency: string
  expectedOn: string | null
  notes: string | null
  lines: PurchaseLineView[]
  total: number
  orderedAt: string | null
  receivedAt: string | null
  cancelledAt: string | null
  createdBy: MemberRef | null
  createdAt: string
  updatedAt: string
  version: number
}

export interface PurchaseOrderSummary {
  id: string
  number: string
  supplier: PartyRef | null
  warehouse: WarehouseRef
  status: PurchaseOrderStatus
  currency: string
  total: number
  lineCount: number
  expectedOn: string | null
  createdAt: string
}

export type SalesOrderStatus = 'DRAFT' | 'CONFIRMED' | 'FULFILLED' | 'CANCELLED'

export interface SalesLineView {
  id: string
  lineNo: number
  product: StockProductRef
  quantity: number
  unitPrice: number
  lineTotal: number
}

export interface SalesOrderView {
  id: string
  number: string
  customer: PartyRef | null
  warehouse: WarehouseRef
  status: SalesOrderStatus
  currency: string
  notes: string | null
  lines: SalesLineView[]
  total: number
  confirmedAt: string | null
  fulfilledAt: string | null
  cancelledAt: string | null
  createdBy: MemberRef | null
  createdAt: string
  updatedAt: string
  version: number
}

export interface SalesOrderSummary {
  id: string
  number: string
  customer: PartyRef | null
  warehouse: WarehouseRef
  status: SalesOrderStatus
  currency: string
  total: number
  lineCount: number
  createdAt: string
}

export interface ReorderRuleView {
  id: string
  product: StockProductRef
  warehouse: WarehouseRef
  minQuantity: number
  maxQuantity: number
  supplier: PartyRef | null
  updatedAt: string
  version: number
}

export interface ReorderSuggestion {
  ruleId: string
  product: StockProductRef
  warehouse: WarehouseRef
  available: number
  onOrder: number
  minQuantity: number
  maxQuantity: number
  supplier: PartyRef | null
  usedLast30Days: number
  averageDailyUsage: number
  /** null when nothing was used in the last 30 days */
  daysOfCover: number | null
  suggestedQuantity: number
  explanation: string
}

export interface InventoryOverview {
  belowMinimum: number
  purchaseOrdersAwaitingReceipt: number
  salesOrdersAwaitingFulfilment: number
  recentMovements: MovementView[]
}
```

and change `SubjectType` to:

```ts
export type SubjectType =
  'PARTY' | 'PRODUCT' | 'LEAD' | 'OPPORTUNITY' | 'PURCHASE_ORDER' | 'SALES_ORDER'
```

- [ ] **Step 2: Permissions, subject paths and search labels**

In `PERMISSIONS` (after `customerRead`), add:

```ts
  stockRead: 'inventory.stock.read',
  stockAdjust: 'inventory.stock.adjust',
  warehouseManage: 'inventory.warehouse.manage',
  purchaseRead: 'inventory.purchase.read',
  purchaseManage: 'inventory.purchase.manage',
  orderRead: 'inventory.order.read',
  orderManage: 'inventory.order.manage',
  reorderManage: 'inventory.reorder.manage',
```

and update its doc comment's catalog list to `(V3, V9–V22 catalog)`.

In `SubjectLink.tsx`'s `subjectPath`, before `return null`:

```ts
  if (type === 'PURCHASE_ORDER') return `/app/inventory/purchase-orders/${id}`
  if (type === 'SALES_ORDER') return `/app/inventory/sales-orders/${id}`
```

In `GlobalSearch.tsx`'s `TYPE_LABELS`, add `PURCHASE_ORDER: 'Purchase order', SALES_ORDER: 'Sales order',`.

- [ ] **Step 3: Move the party picker**

```bash
git mv frontend/src/features/crm/PartyPicker.tsx frontend/src/features/records/PartyPicker.tsx
grep -rl "./PartyPicker'" frontend/src/features/crm | xargs sed -i '' "s#from './PartyPicker'#from '@/features/records/PartyPicker'#"
```

(On Linux use `sed -i` without `''`.) Then `cd frontend && npm run typecheck` — Expected: no errors.

- [ ] **Step 4: Fixtures**

Add to `frontend/src/test/records.ts` (and the new type names to its `import type` list):

```ts
export function aWarehouse(overrides: Partial<WarehouseView> = {}): WarehouseView {
  return {
    id: 'w-main',
    code: 'MAIN',
    name: 'Main warehouse',
    address: null,
    archivedAt: null,
    version: 0,
    ...overrides,
  }
}

const WIDGET = { id: 'pr-widget', sku: 'W-1', name: 'Widget', unit: 'each' }
const MAIN = { id: 'w-main', code: 'MAIN', name: 'Main warehouse' }
const SUPPLIER = { id: 'p-konkan', name: 'Konkan Supplies' }
const CUSTOMER = { id: 'p-deccan', name: 'Deccan Retail' }

export function aProductStock(overrides: Partial<ProductStock> = {}): ProductStock {
  return {
    product: WIDGET,
    levels: [{ warehouse: MAIN, onHand: 12, reserved: 2, available: 10 }],
    onHand: 12,
    reserved: 2,
    available: 10,
    ...overrides,
  }
}

export function aMovement(overrides: Partial<MovementView> = {}): MovementView {
  return {
    id: 'm-1',
    product: WIDGET,
    warehouse: MAIN,
    kind: 'RECEIPT',
    quantity: 10,
    onHandAfter: 12,
    referenceType: 'PURCHASE_ORDER',
    referenceId: 'po-1',
    reason: 'PO-00001',
    actor: { id: 'u-ada', name: 'Ada Lovelace' },
    occurredAt: '2026-10-08T09:00:00Z',
    ...overrides,
  }
}

export function aStockRow(overrides: Partial<StockRow> = {}): StockRow {
  return {
    product: WIDGET,
    warehouse: MAIN,
    onHand: 12,
    reserved: 2,
    available: 10,
    onOrder: 0,
    ruleId: 'rr-1',
    minQuantity: 20,
    maxQuantity: 60,
    belowMin: true,
    ...overrides,
  }
}

export function aPurchaseOrder(overrides: Partial<PurchaseOrderView> = {}): PurchaseOrderView {
  return {
    id: 'po-1',
    number: 'PO-00001',
    supplier: SUPPLIER,
    warehouse: MAIN,
    status: 'DRAFT',
    currency: 'USD',
    expectedOn: null,
    notes: null,
    lines: [
      {
        id: 'pl-1',
        lineNo: 1,
        product: WIDGET,
        quantity: 10,
        receivedQuantity: 0,
        remainingQuantity: 10,
        unitCost: 2.5,
        lineTotal: 25,
      },
    ],
    total: 25,
    orderedAt: null,
    receivedAt: null,
    cancelledAt: null,
    createdBy: { id: 'u-ada', name: 'Ada Lovelace' },
    createdAt: '2026-10-08T09:00:00Z',
    updatedAt: '2026-10-08T09:00:00Z',
    version: 0,
    ...overrides,
  }
}

export function aPurchaseSummary(
  overrides: Partial<PurchaseOrderSummary> = {},
): PurchaseOrderSummary {
  return {
    id: 'po-1',
    number: 'PO-00001',
    supplier: SUPPLIER,
    warehouse: MAIN,
    status: 'DRAFT',
    currency: 'USD',
    total: 25,
    lineCount: 1,
    expectedOn: null,
    createdAt: '2026-10-08T09:00:00Z',
    ...overrides,
  }
}

export function aSalesOrder(overrides: Partial<SalesOrderView> = {}): SalesOrderView {
  return {
    id: 'so-1',
    number: 'SO-00001',
    customer: CUSTOMER,
    warehouse: MAIN,
    status: 'DRAFT',
    currency: 'USD',
    notes: null,
    lines: [
      { id: 'sl-1', lineNo: 1, product: WIDGET, quantity: 4, unitPrice: 12.5, lineTotal: 50 },
    ],
    total: 50,
    confirmedAt: null,
    fulfilledAt: null,
    cancelledAt: null,
    createdBy: { id: 'u-ada', name: 'Ada Lovelace' },
    createdAt: '2026-10-08T09:00:00Z',
    updatedAt: '2026-10-08T09:00:00Z',
    version: 0,
    ...overrides,
  }
}

export function aSalesSummary(overrides: Partial<SalesOrderSummary> = {}): SalesOrderSummary {
  return {
    id: 'so-1',
    number: 'SO-00001',
    customer: CUSTOMER,
    warehouse: MAIN,
    status: 'DRAFT',
    currency: 'USD',
    total: 50,
    lineCount: 1,
    createdAt: '2026-10-08T09:00:00Z',
    ...overrides,
  }
}

export function aReorderRule(overrides: Partial<ReorderRuleView> = {}): ReorderRuleView {
  return {
    id: 'rr-1',
    product: WIDGET,
    warehouse: MAIN,
    minQuantity: 20,
    maxQuantity: 60,
    supplier: SUPPLIER,
    updatedAt: '2026-10-08T09:00:00Z',
    version: 0,
    ...overrides,
  }
}

export function aSuggestion(overrides: Partial<ReorderSuggestion> = {}): ReorderSuggestion {
  return {
    ruleId: 'rr-1',
    product: WIDGET,
    warehouse: MAIN,
    available: 12,
    onOrder: 0,
    minQuantity: 20,
    maxQuantity: 60,
    supplier: SUPPLIER,
    usedLast30Days: 38,
    averageDailyUsage: 1.2667,
    daysOfCover: 9.5,
    suggestedQuantity: 48,
    explanation:
      '12 available, 0 on order, below the minimum of 20; 38 used in the last 30 days (about 9 days of cover); order 48 to reach 60',
    ...overrides,
  }
}

export function anOverview(overrides: Partial<InventoryOverview> = {}): InventoryOverview {
  return {
    belowMinimum: 2,
    purchaseOrdersAwaitingReceipt: 1,
    salesOrdersAwaitingFulfilment: 3,
    recentMovements: [aMovement()],
    ...overrides,
  }
}
```

- [ ] **Step 5: Quantities and labels (test first)**

`frontend/src/features/inventory/quantity.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import { formatQuantity, positiveQuantitySchema, quantitySchema } from './quantity'

describe('quantities', () => {
  it('formats without trailing zeros and with the unit', () => {
    expect(formatQuantity(12)).toBe('12')
    expect(formatQuantity(2.5, 'kg')).toBe('2.5 kg')
    expect(formatQuantity(0.1234)).toBe('0.1234')
  })

  it('accepts counts of 0 or more with at most 4 decimals', () => {
    expect(quantitySchema.safeParse('0').success).toBe(true)
    expect(quantitySchema.safeParse('2.5').success).toBe(true)
    expect(quantitySchema.safeParse('').error?.issues[0].message).toBe('Enter a number like 12 or 2.5.')
    expect(quantitySchema.safeParse('-1').error?.issues[0].message).toBe(
      'Enter a number like 12 or 2.5.',
    )
    expect(quantitySchema.safeParse('1.23456').error?.issues[0].message).toBe(
      'Use at most 4 decimal places.',
    )
  })

  it('needs more than 0 for movements and order lines', () => {
    expect(positiveQuantitySchema.safeParse('0').error?.issues[0].message).toBe(
      'Enter a quantity greater than 0.',
    )
    expect(positiveQuantitySchema.safeParse('0.5').success).toBe(true)
  })
})
```

Run: `cd frontend && npx vitest run src/features/inventory/quantity.test.ts` — Expected: FAIL (module missing).

`frontend/src/features/inventory/quantity.ts`:

```ts
import { z } from 'zod'

const numberFormat = new Intl.NumberFormat(undefined, { maximumFractionDigits: 4 })

/** 12, 2.5 kg — never trailing zeros. */
export function formatQuantity(value: number, unit?: string): string {
  const text = numberFormat.format(value)
  return unit ? `${text} ${unit}` : text
}

/** A quantity typed as text: 0 or more, at most 4 decimals (the server's numeric(19,4)). */
export const quantitySchema = z
  .string()
  .trim()
  .refine((v) => /^\d+(\.\d+)?$/.test(v), 'Enter a number like 12 or 2.5.')
  .refine((v) => !/\.\d{5,}$/.test(v), 'Use at most 4 decimal places.')

export const positiveQuantitySchema = quantitySchema.refine(
  (v) => Number(v) > 0,
  'Enter a quantity greater than 0.',
)
```

`frontend/src/features/inventory/labels.ts`:

```ts
import type { MovementKind, PurchaseOrderStatus, SalesOrderStatus } from '@/lib/api/types'

export const MOVEMENT_KIND_LABELS: Record<MovementKind, string> = {
  RECEIPT: 'Receipt',
  ISSUE: 'Issue',
  ADJUSTMENT: 'Count',
  TRANSFER_OUT: 'Transfer out',
  TRANSFER_IN: 'Transfer in',
}

export const PURCHASE_STATUS_LABELS: Record<PurchaseOrderStatus, string> = {
  DRAFT: 'Draft',
  ORDERED: 'Ordered',
  PARTIALLY_RECEIVED: 'Partly received',
  RECEIVED: 'Received',
  CANCELLED: 'Cancelled',
}

export const SALES_STATUS_LABELS: Record<SalesOrderStatus, string> = {
  DRAFT: 'Draft',
  CONFIRMED: 'Confirmed',
  FULFILLED: 'Fulfilled',
  CANCELLED: 'Cancelled',
}
```

`frontend/src/features/inventory/invalidation.ts`:

```ts
import type { QueryClient } from '@tanstack/react-query'

/** Stock, orders and suggestions all depend on each other: any Inventory write refreshes every Inventory query. */
export async function invalidateInventory(queryClient: QueryClient): Promise<void> {
  await queryClient.invalidateQueries({ queryKey: ['inventory'] })
}
```

Run the quantity test again — Expected: PASS.

- [ ] **Step 6: Shared pickers and the movements table**

`frontend/src/features/inventory/WarehouseSelect.tsx`:

```tsx
import { useQuery } from '@tanstack/react-query'
import { describedBy, Field } from '@/components/form/Field'
import { NativeSelect } from '@/components/form/NativeSelect'
import { useApi } from '@/lib/api/ApiContext'
import type { WarehouseView } from '@/lib/api/types'

/** Active warehouses. With `blankLabel`, '' is an option ("All warehouses", "Choose…"). */
export function WarehouseSelect({
  id,
  label,
  value,
  onChange,
  error,
  blankLabel,
}: {
  id: string
  label: string
  value: string
  onChange: (id: string) => void
  error?: string
  blankLabel?: string
}) {
  const api = useApi()
  const warehouses = useQuery({
    queryKey: ['inventory', 'warehouses', false],
    queryFn: () => api.get<WarehouseView[]>('/inventory/warehouses'),
  })
  return (
    <Field id={id} label={label} error={error}>
      <NativeSelect
        id={id}
        value={value}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy(id, error)}
        onChange={(e) => onChange(e.target.value)}
      >
        {blankLabel !== undefined && <option value="">{blankLabel}</option>}
        {(warehouses.data ?? []).map((w) => (
          <option key={w.id} value={w.id}>
            {w.code} · {w.name}
          </option>
        ))}
      </NativeSelect>
    </Field>
  )
}
```

`frontend/src/features/inventory/ProductPicker.tsx`:

```tsx
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Input } from '@/components/ui/input'
import { describedBy, Field } from '@/components/form/Field'
import { NativeSelect } from '@/components/form/NativeSelect'
import { useApi } from '@/lib/api/ApiContext'
import type { Page, ProductView, StockProductRef } from '@/lib/api/types'
import { toQuery } from '@/lib/query'

/** Pick a stocked (GOODS) product: a search box over the first 20 matches plus a select. '' = none. */
export function ProductPicker({
  id,
  label,
  value,
  onChange,
  current,
  error,
}: {
  id: string
  label: string
  value: string
  onChange: (id: string, product: StockProductRef | null) => void
  current?: StockProductRef | null
  error?: string
}) {
  const api = useApi()
  const [search, setSearch] = useState('')
  const products = useQuery({
    queryKey: ['product-picker', search.trim()],
    queryFn: () =>
      api.get<Page<ProductView>>(`/products?${toQuery({ kind: 'GOODS', q: search.trim(), size: 20 })}`),
  })
  const options: StockProductRef[] = (products.data?.items ?? []).map((p) => ({
    id: p.id,
    sku: p.sku,
    name: p.name,
    unit: p.unit,
  }))
  const all = current && !options.some((p) => p.id === current.id) ? [current, ...options] : options
  const searchId = `${id}-search`
  return (
    <div className="grid gap-3 sm:grid-cols-2">
      <Field id={searchId} label={`Find ${label.toLowerCase()}`}>
        <Input
          id={searchId}
          type="search"
          autoComplete="off"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
      </Field>
      <Field id={id} label={label} error={error}>
        <NativeSelect
          id={id}
          value={value}
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy(id, error)}
          onChange={(e) => onChange(e.target.value, all.find((p) => p.id === e.target.value) ?? null)}
        >
          <option value="">Choose…</option>
          {all.map((p) => (
            <option key={p.id} value={p.id}>
              {p.sku} · {p.name}
            </option>
          ))}
        </NativeSelect>
      </Field>
    </div>
  )
}
```

`frontend/src/features/inventory/MovementsTable.tsx`:

```tsx
import { Link } from 'react-router'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { subjectPath } from '@/features/records/SubjectLink'
import type { MovementView } from '@/lib/api/types'
import { formatDateTime } from '@/lib/format'
import { MOVEMENT_KIND_LABELS } from './labels'
import { formatQuantity } from './quantity'

/** The ledger, newest first. Order movements link to their order. */
export function MovementsTable({
  movements,
  showProduct = false,
}: {
  movements: MovementView[]
  showProduct?: boolean
}) {
  return (
    <div className="overflow-x-auto rounded-lg border">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>When</TableHead>
            {showProduct && <TableHead>Product</TableHead>}
            <TableHead>Warehouse</TableHead>
            <TableHead>Movement</TableHead>
            <TableHead className="text-right">Change</TableHead>
            <TableHead className="text-right">On hand after</TableHead>
            <TableHead>Reason</TableHead>
            <TableHead>By</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {movements.map((m) => {
            const orderPath = subjectPath(m.referenceType, m.referenceId)
            return (
              <TableRow key={m.id}>
                <TableCell className="whitespace-nowrap">{formatDateTime(m.occurredAt)}</TableCell>
                {showProduct && (
                  <TableCell>
                    <Link
                      to={`/app/products/${m.product.id}`}
                      className="underline-offset-4 hover:underline"
                    >
                      {m.product.sku}
                    </Link>{' '}
                    {m.product.name}
                  </TableCell>
                )}
                <TableCell>{m.warehouse.code}</TableCell>
                <TableCell>{MOVEMENT_KIND_LABELS[m.kind]}</TableCell>
                <TableCell className="text-right tabular-nums">
                  {m.quantity > 0 ? '+' : ''}
                  {formatQuantity(m.quantity)}
                </TableCell>
                <TableCell className="text-right tabular-nums">
                  {formatQuantity(m.onHandAfter)}
                </TableCell>
                <TableCell>
                  {orderPath && m.reason ? (
                    <Link to={orderPath} className="underline-offset-4 hover:underline">
                      {m.reason}
                    </Link>
                  ) : (
                    (m.reason ?? '—')
                  )}
                </TableCell>
                <TableCell>{m.actor?.name ?? '—'}</TableCell>
              </TableRow>
            )
          })}
        </TableBody>
      </Table>
    </div>
  )
}
```

- [ ] **Step 7: Write the failing layout, overview and settings tests**

`frontend/src/features/inventory/InventoryLayout.test.tsx`:

```tsx
import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ALL_TENANT_PERMISSIONS } from '@/features/auth/permissions'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { anOverview } from '@/test/records'
import { renderApp } from '@/test/renderApp'

describe('InventoryLayout', () => {
  it('explains that Inventory is off when the module is disabled', async () => {
    const server = fakeServer()
    signedIn(server, testProfile({ modules: [] }))
    renderApp({ server, path: '/app/inventory' })
    expect(await screen.findByText('Inventory is not enabled for this workspace.')).toBeInTheDocument()
  })

  it('shows the sections a stock reader may open', async () => {
    const server = fakeServer()
    signedIn(
      server,
      testProfile({ modules: ['INVENTORY'], permissions: ['inventory.stock.read'] }),
    ).on('GET /inventory/overview', { body: anOverview() })
    renderApp({ server, path: '/app/inventory' })
    const nav = await screen.findByRole('navigation', { name: 'Inventory' })
    expect(nav).toHaveTextContent('Overview')
  })

  it('has no sections without any Inventory permission', async () => {
    const server = fakeServer()
    signedIn(server, testProfile({ modules: ['INVENTORY'], permissions: [...ALL_TENANT_PERMISSIONS].filter((p) => !p.startsWith('inventory.')) }))
    renderApp({ server, path: '/app/inventory' })
    expect(await screen.findByText("You don't have access to this page")).toBeInTheDocument()
  })
})
```

`frontend/src/features/inventory/InventoryOverviewPage.test.tsx`:

```tsx
import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { anOverview } from '@/test/records'
import { renderApp } from '@/test/renderApp'

describe('InventoryOverviewPage', () => {
  it('counts what needs attention and lists recent movements', async () => {
    const server = fakeServer()
    signedIn(server, testProfile({ modules: ['INVENTORY'] })).on('GET /inventory/overview', {
      body: anOverview(),
    })
    renderApp({ server, path: '/app/inventory' })
    expect(await screen.findByRole('link', { name: /2\s*below minimum/i })).toHaveAttribute(
      'href',
      '/app/inventory/stock?belowMin=true',
    )
    expect(screen.getByRole('link', { name: /1\s*awaiting receipt/i })).toHaveAttribute(
      'href',
      '/app/inventory/purchase-orders?status=ORDERED',
    )
    expect(screen.getByRole('link', { name: /3\s*awaiting fulfilment/i })).toHaveAttribute(
      'href',
      '/app/inventory/sales-orders?status=CONFIRMED',
    )
    expect(screen.getByRole('link', { name: 'PO-00001' })).toHaveAttribute(
      'href',
      '/app/inventory/purchase-orders/po-1',
    )
    expect(screen.getByText('+10')).toBeInTheDocument()
  })

  it('says when nothing has moved yet', async () => {
    const server = fakeServer()
    signedIn(server, testProfile({ modules: ['INVENTORY'] })).on('GET /inventory/overview', {
      body: anOverview({ recentMovements: [], belowMinimum: 0 }),
    })
    renderApp({ server, path: '/app/inventory' })
    expect(await screen.findByText('No stock has moved yet.')).toBeInTheDocument()
  })
})
```

`frontend/src/features/settings/WarehousesSettingsPage.test.tsx`:

```tsx
import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aWarehouse } from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup() {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['INVENTORY'] }))
    .on('GET /inventory/warehouses', (request) => ({
      body:
        request.query.get('archived') === 'true'
          ? [aWarehouse({ id: 'w-old', code: 'OLD', name: 'Old shed', archivedAt: '2026-10-01T00:00:00Z' })]
          : [aWarehouse()],
    }))
  return { server, ...renderApp({ server, path: '/app/settings/warehouses' }) }
}

describe('WarehousesSettingsPage', () => {
  it('lists active and archived warehouses', async () => {
    setup()
    expect(await screen.findByText('Main warehouse')).toBeInTheDocument()
    expect(await screen.findByText('Old shed')).toBeInTheDocument()
  })

  it('adds a warehouse', async () => {
    const { server, user } = setup()
    server.on('POST /inventory/warehouses', {
      status: 201,
      body: aWarehouse({ id: 'w-pune', code: 'PUNE', name: 'Pune' }),
    })
    await user.click(await screen.findByRole('button', { name: 'New warehouse' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Code'), 'PUNE')
    await user.type(within(dialog).getByLabelText('Name'), 'Pune')
    await user.click(within(dialog).getByRole('button', { name: 'Add warehouse' }))
    expect(server.callsTo('POST /inventory/warehouses')[0].body).toEqual({
      code: 'PUNE',
      name: 'Pune',
      address: null,
    })
  })

  it('shows why a warehouse cannot be archived', async () => {
    const { server, user } = setup()
    server.on('POST /inventory/warehouses/:id/archive', {
      status: 409,
      body: { detail: "Move or count out this warehouse's stock first." },
    })
    await user.click(await screen.findByRole('button', { name: 'Archive MAIN' }))
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Archive' }))
    expect(await screen.findByText("Move or count out this warehouse's stock first.")).toBeInTheDocument()
  })

  it('maps a taken code onto the code field', async () => {
    const { server, user } = setup()
    server.on('POST /inventory/warehouses', {
      status: 409,
      body: {
        detail: 'Another warehouse already uses this code.',
        errors: [{ field: 'code', message: 'Another warehouse already uses this code.' }],
      },
    })
    await user.click(await screen.findByRole('button', { name: 'New warehouse' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Code'), 'MAIN')
    await user.type(within(dialog).getByLabelText('Name'), 'Again')
    await user.click(within(dialog).getByRole('button', { name: 'Add warehouse' }))
    expect(
      await within(dialog).findByText('Another warehouse already uses this code.'),
    ).toBeInTheDocument()
  })
})
```

Run: `cd frontend && npx vitest run src/features/inventory src/features/settings/WarehousesSettingsPage.test.tsx` — Expected: FAIL.

- [ ] **Step 8: Layout, routes, overview**

`frontend/src/features/inventory/InventoryLayout.tsx`:

```tsx
import { NavLink, Outlet } from 'react-router'
import { NoAccess } from '@/components/states'
import { PERMISSIONS, useCan, type PermissionCode } from '@/features/auth/permissions'
import { useTenantSession } from '@/features/auth/tenantSession'
import { ComingSoonPage } from '@/features/shell/ComingSoonPage'
import { MODULE_PHASES } from '@/features/shell/nav'
import { cn } from '@/lib/utils'

/** Later tasks append Stock, Purchase orders, Sales orders and Reorder. */
export const TABS: Array<{ to: string; label: string; anyOf: PermissionCode[]; end?: boolean }> = [
  { to: '/app/inventory', label: 'Overview', anyOf: [PERMISSIONS.stockRead], end: true },
]

/** The Inventory area: only when the module is enabled; tabs follow the user's Inventory permissions. */
export function InventoryLayout() {
  const session = useTenantSession()
  const can = useCan()
  const modules = session.state.status === 'authenticated' ? session.state.profile.modules : []
  if (!modules.includes('INVENTORY')) {
    const info = MODULE_PHASES.INVENTORY
    return (
      <ComingSoonPage
        title={info.label}
        phase={info.phase}
        description={info.description}
        module="INVENTORY"
      />
    )
  }
  const tabs = TABS.filter((tab) => can(...tab.anyOf))
  if (tabs.length === 0) return <NoAccess />
  return (
    <div className="space-y-6">
      <nav aria-label="Inventory" className="flex flex-wrap gap-1 border-b pb-2">
        {tabs.map((tab) => (
          <NavLink
            key={tab.to}
            to={tab.to}
            end={tab.end}
            className={({ isActive }) =>
              cn('rounded-md px-3 py-1.5 text-sm hover:bg-muted', isActive && 'bg-muted font-medium')
            }
          >
            {tab.label}
          </NavLink>
        ))}
      </nav>
      <Outlet />
    </div>
  )
}
```

`frontend/src/features/inventory/InventoryOverviewPage.tsx`:

```tsx
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { EmptyState, ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { useApi } from '@/lib/api/ApiContext'
import type { InventoryOverview } from '@/lib/api/types'
import { MovementsTable } from './MovementsTable'

export function InventoryOverviewPage() {
  const api = useApi()
  const overview = useQuery({
    queryKey: ['inventory', 'overview'],
    queryFn: () => api.get<InventoryOverview>('/inventory/overview'),
    refetchOnMount: 'always',
  })
  if (overview.isPending) return <ListSkeleton />
  if (overview.isError)
    return <ErrorState error={overview.error} onRetry={() => void overview.refetch()} />
  const o = overview.data
  const tiles = [
    { count: o.belowMinimum, label: 'below minimum', to: '/app/inventory/stock?belowMin=true' },
    {
      count: o.purchaseOrdersAwaitingReceipt,
      label: 'awaiting receipt',
      to: '/app/inventory/purchase-orders?status=ORDERED',
    },
    {
      count: o.salesOrdersAwaitingFulfilment,
      label: 'awaiting fulfilment',
      to: '/app/inventory/sales-orders?status=CONFIRMED',
    },
  ]
  return (
    <div className="space-y-6">
      <PageHeader title="Inventory" description="What needs attention, and what moved last." />
      <div className="grid gap-3 sm:grid-cols-3">
        {tiles.map((t) => (
          <Link key={t.label} to={t.to} className="rounded-lg border p-4 hover:bg-muted">
            <span className="block text-3xl font-semibold tabular-nums">{t.count}</span>
            <span className="text-sm text-muted-foreground">{t.label}</span>
          </Link>
        ))}
      </div>
      <Card>
        <CardHeader>
          <CardTitle>Recent movements</CardTitle>
        </CardHeader>
        <CardContent>
          {o.recentMovements.length === 0 ? (
            <EmptyState
              title="No stock has moved yet."
              description="Count opening stock or receive a purchase order to begin."
            />
          ) : (
            <MovementsTable movements={o.recentMovements} showProduct />
          )}
        </CardContent>
      </Card>
    </div>
  )
}
```

(The overview's purchase-order link uses `status=ORDERED`; partly received orders are one click away on that list's status filter.)

`frontend/src/features/inventory/routes.tsx`:

```tsx
import type { RouteObject } from 'react-router'
import { PERMISSIONS, RequirePermission } from '@/features/auth/permissions'
import { InventoryOverviewPage } from './InventoryOverviewPage'

/** /app/inventory/* children; later tasks append theirs. */
export const inventoryChildren: RouteObject[] = [
  {
    index: true,
    element: (
      <RequirePermission anyOf={[PERMISSIONS.stockRead]}>
        <InventoryOverviewPage />
      </RequirePermission>
    ),
  },
]
```

In `features/shell/routes.tsx` replace `modulePage('inventory', 'INVENTORY'),` with `{ path: 'inventory', element: <InventoryLayout />, children: inventoryChildren },` (imports from `@/features/inventory/InventoryLayout` and `@/features/inventory/routes`).

In `features/shell/nav.ts`, set `MODULE_PHASES.INVENTORY.description` to `'Warehouses, stock, purchase and sales orders.'` and add to `SETTINGS_TABS`:

```ts
  { to: '/app/settings/warehouses', label: 'Warehouses', anyOf: [PERMISSIONS.warehouseManage] },
```

In `ComingSoonPage.test.tsx`, the first two tests move to HelpDesk: path `/app/helpdesk`, heading `'HelpDesk'`, text `/Phase 7/`, and `'HelpDesk is not enabled for this workspace.'` (modules `['HELPDESK']` in the first).

- [ ] **Step 9: Settings → Warehouses**

`frontend/src/features/settings/WarehousesSettingsPage.tsx`:

```tsx
import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { FormError } from '@/components/form/FormError'
import { TextField } from '@/components/form/TextField'
import { ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { requiredText } from '@/features/auth/schemas'
import { invalidateInventory } from '@/features/inventory/invalidation'
import { useApi } from '@/lib/api/ApiContext'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import type { WarehouseView } from '@/lib/api/types'

const schema = z.object({
  code: z
    .string()
    .trim()
    .regex(/^[A-Za-z0-9][A-Za-z0-9_-]{0,19}$/, 'Use up to 20 letters, digits, - or _.'),
  name: requiredText(100),
  address: z.string().trim().max(500, 'Use at most 500 characters.'),
})
type Values = z.infer<typeof schema>
const FIELDS = ['code', 'name', 'address'] as const

/** Settings → Warehouses (D3): add, rename, archive (refused while in use) and restore. */
export function WarehousesSettingsPage() {
  const api = useApi()
  const queryClient = useQueryClient()
  const active = useQuery({
    queryKey: ['inventory', 'warehouses', false],
    queryFn: () => api.get<WarehouseView[]>('/inventory/warehouses'),
  })
  const archived = useQuery({
    queryKey: ['inventory', 'warehouses', true],
    queryFn: () => api.get<WarehouseView[]>('/inventory/warehouses?archived=true'),
  })
  const [editing, setEditing] = useState<WarehouseView | 'new' | null>(null)
  const [archiving, setArchiving] = useState<WarehouseView | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function act(action: () => Promise<unknown>, success: string) {
    setBusy(true)
    setError(null)
    try {
      await action()
      await invalidateInventory(queryClient)
      toast.success(success)
      return true
    } catch (e) {
      setError(problemMessage(e))
      return false
    } finally {
      setBusy(false)
    }
  }

  if (active.isPending) return <ListSkeleton />
  if (active.isError) return <ErrorState error={active.error} onRetry={() => void active.refetch()} />
  const rows = [...active.data, ...(archived.data ?? [])]

  return (
    <>
      <PageHeader
        title="Warehouses"
        description="Where stock is kept. Every workspace keeps at least one active warehouse."
        actions={<Button onClick={() => setEditing('new')}>New warehouse</Button>}
      />
      <FormError message={archiving ? null : error} />
      <div className="overflow-x-auto rounded-lg border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Code</TableHead>
              <TableHead>Name</TableHead>
              <TableHead>Address</TableHead>
              <TableHead className="text-right">Actions</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {rows.map((w) => (
              <TableRow key={w.id}>
                <TableCell className="font-mono">{w.code}</TableCell>
                <TableCell>
                  {w.name} {w.archivedAt && <Badge variant="outline">Archived</Badge>}
                </TableCell>
                <TableCell>{w.address ?? '—'}</TableCell>
                <TableCell className="space-x-2 text-right">
                  {w.archivedAt ? (
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={busy}
                      aria-label={`Restore ${w.code}`}
                      onClick={() =>
                        void act(() => api.post(`/inventory/warehouses/${w.id}/restore`), `${w.code} restored.`)
                      }
                    >
                      Restore
                    </Button>
                  ) : (
                    <>
                      <Button size="sm" variant="outline" aria-label={`Edit ${w.code}`} onClick={() => setEditing(w)}>
                        Edit
                      </Button>
                      <Button
                        size="sm"
                        variant="outline"
                        aria-label={`Archive ${w.code}`}
                        onClick={() => {
                          setError(null)
                          setArchiving(w)
                        }}
                      >
                        Archive
                      </Button>
                    </>
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
      <ConfirmDialog
        open={archiving !== null}
        title={`Archive ${archiving?.code ?? ''}?`}
        description="Archived warehouses take no stock and can't be chosen on orders. You can restore it later."
        confirmLabel="Archive"
        busy={busy}
        error={error}
        onCancel={() => setArchiving(null)}
        onConfirm={() =>
          void act(
            () => api.post(`/inventory/warehouses/${archiving?.id}/archive`),
            `${archiving?.code} archived.`,
          ).then((ok) => ok && setArchiving(null))
        }
      />
      {editing && (
        <WarehouseDialog
          warehouse={editing === 'new' ? undefined : editing}
          onClose={() => setEditing(null)}
          onSaved={async () => {
            await invalidateInventory(queryClient)
            setEditing(null)
          }}
        />
      )}
    </>
  )
}

function WarehouseDialog({
  warehouse,
  onClose,
  onSaved,
}: {
  warehouse?: WarehouseView
  onClose: () => void
  onSaved: () => Promise<void>
}) {
  const api = useApi()
  const [formError, setFormError] = useState<string | null>(null)
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: {
      code: warehouse?.code ?? '',
      name: warehouse?.name ?? '',
      address: warehouse?.address ?? '',
    },
  })
  const submit = form.handleSubmit(async (values) => {
    setFormError(null)
    const body = {
      code: values.code,
      name: values.name,
      address: values.address || null,
      ...(warehouse ? { version: warehouse.version } : {}),
    }
    try {
      if (warehouse) await api.put(`/inventory/warehouses/${warehouse.id}`, body)
      else await api.post('/inventory/warehouses', body)
      toast.success(warehouse ? 'Changes saved.' : `${values.code.toUpperCase()} added.`)
      await onSaved()
    } catch (error) {
      if (!applyFieldErrors(error, form.setError, FIELDS)) setFormError(problemMessage(error))
    }
  })
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{warehouse ? `Edit ${warehouse.code}` : 'New warehouse'}</DialogTitle>
          <DialogDescription>Codes are unique in the workspace, ignoring case.</DialogDescription>
        </DialogHeader>
        <form noValidate onSubmit={submit} className="space-y-4">
          <TextField form={form} name="code" label="Code" maxLength={20} />
          <TextField form={form} name="name" label="Name" maxLength={100} />
          <TextField form={form} name="address" label="Address" maxLength={500} />
          <FormError message={formError} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={form.formState.isSubmitting}>
              {warehouse ? 'Save changes' : 'Add warehouse'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
```

Add the settings route in `features/shell/routes.tsx`'s `settingsChildren`, after `pipeline` and before the `'*'` catch-all:

```tsx
  {
    path: 'warehouses',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.warehouseManage]}>
        <WarehousesSettingsPage />
      </RequirePermission>
    ),
  },
```

- [ ] **Step 10: Run the frontend checks**

Run: `cd frontend && npx prettier --write src && npm run format:check && npm run lint && npm run typecheck && npm test`
Expected: PASS (all suites, including the moved `PartyPicker` importers and the updated `ComingSoonPage` test).

- [ ] **Step 11: Commit**

```bash
git add frontend/src
git commit -m "feat(frontend): Inventory area, overview, warehouse settings and shared inventory types"
```

---
### Task 8: The stock page, count and transfer dialogs, and the product's Stock panel

**Files:**
- Create: `frontend/src/features/inventory/StockPage.tsx`, `StockPage.test.tsx`, `AdjustStockDialog.tsx`, `TransferStockDialog.tsx`, `ProductStockPanel.tsx`, `ProductStockPanel.test.tsx`
- Modify: `frontend/src/features/inventory/InventoryLayout.tsx` (`TABS`), `routes.tsx`, `frontend/src/features/products/ProductDetailPage.tsx`

**Interfaces:**
- Consumes (Task 7): types `StockRow`, `ProductStock`, `MovementView`, `Shortage`, `StockProductRef`, `Page`; `WarehouseSelect`, `ProductPicker`, `MovementsTable`, `formatQuantity`, `quantitySchema`, `positiveQuantitySchema`, `invalidateInventory`, `PERMISSIONS.stockRead|stockAdjust`, fixtures `aStockRow`, `aProductStock`, `aMovement`, `aWarehouse`, `aProduct`.
- Produces: `AdjustStockDialog({ product?, warehouseId?, onClose })`, `TransferStockDialog({ product?, fromWarehouseId?, onClose })` (both pick the product themselves when `product` is absent), `ProductStockPanel({ productId, kind })` (renders nothing for SERVICE products or without Inventory), the Stock tab and route `/app/inventory/stock`, and the exported helper `shortageText(error): string | null` in `AdjustStockDialog.tsx`'s sibling `shortages.ts`.

- [ ] **Step 1: Write the failing tests**

`frontend/src/features/inventory/StockPage.test.tsx`:

```tsx
import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aProduct, aProductStock, aStockRow, aWarehouse, pageOf } from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup(path = '/app/inventory/stock', permissions?: string[]) {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['INVENTORY'], ...(permissions ? { permissions } : {}) }))
    .on('GET /inventory/stock', {
      body: pageOf([
        aStockRow(),
        aStockRow({
          product: { id: 'pr-gadget', sku: 'G-1', name: 'Gadget', unit: 'box' },
          onHand: 3,
          reserved: 0,
          available: 3,
          onOrder: 5,
          ruleId: null,
          minQuantity: null,
          maxQuantity: null,
          belowMin: false,
        }),
      ]),
    })
    .on('GET /inventory/warehouses', {
      body: [aWarehouse(), aWarehouse({ id: 'w-pune', code: 'PUNE', name: 'Pune' })],
    })
    .on('GET /products', { body: pageOf([aProduct()]) })
    .on('GET /inventory/stock/products/:id', { body: aProductStock() })
  return { server, ...renderApp({ server, path }) }
}

describe('StockPage', () => {
  it('lists stock per product and warehouse with the rule and what is on order', async () => {
    setup()
    const row = (await screen.findByRole('link', { name: 'W-1' })).closest('tr') as HTMLElement
    expect(within(row).getByText('Widget')).toBeInTheDocument()
    expect(within(row).getByText('MAIN')).toBeInTheDocument()
    expect(within(row).getByText('20 – 60')).toBeInTheDocument()
    expect(within(row).getByText('Below minimum')).toBeInTheDocument()
    const gadget = screen.getByRole('link', { name: 'G-1' }).closest('tr') as HTMLElement
    expect(within(gadget).getByText('5')).toBeInTheDocument()
    expect(within(gadget).getByText('No rule')).toBeInTheDocument()
  })

  it('filters by search, warehouse and below minimum, starting from the URL', async () => {
    const { server, user } = setup('/app/inventory/stock?belowMin=true')
    await screen.findByRole('link', { name: 'W-1' })
    expect(server.callsTo('GET /inventory/stock')[0].query.get('belowMin')).toBe('true')
    expect(screen.getByLabelText('Below minimum only')).toBeChecked()
    await user.selectOptions(screen.getByLabelText('Warehouse'), 'w-pune')
    await user.type(screen.getByLabelText('Search stock'), 'wid')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    const last = server.callsTo('GET /inventory/stock').at(-1)?.query
    expect(last?.get('warehouseId')).toBe('w-pune')
    expect(last?.get('q')).toBe('wid')
  })

  it('counts stock and shows the new figures', async () => {
    const { server, user } = setup()
    server.on('POST /inventory/adjustments', { body: aProductStock({ onHand: 15, available: 13 }) })
    await user.click(await screen.findByRole('button', { name: 'Count stock' }))
    const dialog = await screen.findByRole('dialog')
    await user.selectOptions(within(dialog).getByLabelText('Product'), 'pr-widget')
    await user.selectOptions(within(dialog).getByLabelText('Warehouse'), 'w-main')
    await user.type(within(dialog).getByLabelText('Counted quantity'), '15')
    await user.type(within(dialog).getByLabelText('Reason'), 'Cycle count')
    await user.click(within(dialog).getByRole('button', { name: 'Save count' }))
    expect(server.callsTo('POST /inventory/adjustments')[0].body).toEqual({
      productId: 'pr-widget',
      warehouseId: 'w-main',
      countedQuantity: 15,
      reason: 'Cycle count',
    })
    expect(await screen.findByText('Count saved.')).toBeInTheDocument()
  })

  it('explains a transfer shortage', async () => {
    const { server, user } = setup()
    server.on('POST /inventory/transfers', {
      status: 409,
      body: {
        detail: 'Not enough stock.',
        shortages: [{ productId: 'pr-widget', sku: 'W-1', requested: 50, available: 10 }],
      },
    })
    await user.click(await screen.findByRole('button', { name: 'Transfer stock' }))
    const dialog = await screen.findByRole('dialog')
    await user.selectOptions(within(dialog).getByLabelText('Product'), 'pr-widget')
    await user.selectOptions(within(dialog).getByLabelText('From'), 'w-main')
    await user.selectOptions(within(dialog).getByLabelText('To'), 'w-pune')
    await user.type(within(dialog).getByLabelText('Quantity'), '50')
    await user.click(within(dialog).getByRole('button', { name: 'Transfer' }))
    expect(
      await within(dialog).findByText('Not enough stock: W-1 needs 50, 10 available.'),
    ).toBeInTheDocument()
  })

  it('hides the stock actions from readers', async () => {
    setup('/app/inventory/stock', ['inventory.stock.read'])
    await screen.findByRole('link', { name: 'W-1' })
    expect(screen.queryByRole('button', { name: 'Count stock' })).not.toBeInTheDocument()
  })
})
```

`frontend/src/features/inventory/ProductStockPanel.test.tsx`:

```tsx
import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aMovement, aProduct, aProductStock, aWarehouse, pageOf } from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup(modules: string[], kind: 'GOODS' | 'SERVICE' = 'GOODS') {
  const server = fakeServer()
  signedIn(server, testProfile({ modules }))
    .on('GET /products/:id', { body: aProduct({ kind }) })
    .on('GET /activities', { body: pageOf([]) })
    .on('GET /documents', { body: [] })
    .on('GET /inventory/stock/products/:id', { body: aProductStock() })
    .on('GET /inventory/movements', { body: pageOf([aMovement()]) })
    .on('GET /inventory/warehouses', { body: [aWarehouse()] })
  return { server, ...renderApp({ server, path: '/app/products/pr-widget' }) }
}

describe('ProductStockPanel', () => {
  it('shows stock per warehouse and recent movements when Inventory is on', async () => {
    const { server } = setup(['INVENTORY'])
    const panel = await screen.findByRole('region', { name: 'Stock' })
    // the levels table comes first; the movements table repeats the warehouse code
    const row = (await within(panel).findAllByText('MAIN'))[0].closest('tr') as HTMLElement
    expect(within(row).getByText('12')).toBeInTheDocument()
    expect(within(row).getByText('10')).toBeInTheDocument()
    expect(await within(panel).findByRole('link', { name: 'PO-00001' })).toBeInTheDocument()
    expect(server.callsTo('GET /inventory/movements')[0].query.get('productId')).toBe('pr-widget')
  })

  it('is absent without Inventory', async () => {
    setup([])
    await screen.findByRole('heading', { name: 'Widget' })
    expect(screen.queryByRole('region', { name: 'Stock' })).not.toBeInTheDocument()
  })

  it('is absent for a service', async () => {
    setup(['INVENTORY'], 'SERVICE')
    await screen.findByRole('heading', { name: 'Widget' })
    expect(screen.queryByRole('region', { name: 'Stock' })).not.toBeInTheDocument()
  })
})
```

Run: `cd frontend && npx vitest run src/features/inventory/StockPage.test.tsx src/features/inventory/ProductStockPanel.test.tsx`
Expected: FAIL.

- [ ] **Step 2: Shortage text**

`frontend/src/features/inventory/shortages.ts`:

```ts
import { ApiError } from '@/lib/api/client'
import type { Shortage } from '@/lib/api/types'
import { formatQuantity } from './quantity'

/** "Not enough stock: W-1 needs 50, 10 available; G-1 needs 2, 0 available." — or null for other errors. */
export function shortageText(error: unknown): string | null {
  if (!(error instanceof ApiError)) return null
  const shortages = (error.problem as { shortages?: Shortage[] }).shortages
  if (!shortages?.length) return null
  return `Not enough stock: ${shortages
    .map((s) => `${s.sku} needs ${formatQuantity(s.requested)}, ${formatQuantity(s.available)} available`)
    .join('; ')}.`
}
```

(`toProblem` in `lib/api/client.ts` spreads the whole JSON body into the problem, so `shortages` is there at runtime.)

- [ ] **Step 3: The dialogs**

`frontend/src/features/inventory/AdjustStockDialog.tsx`:

```tsx
import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { FormError } from '@/components/form/FormError'
import { TextField } from '@/components/form/TextField'
import { requiredText } from '@/features/auth/schemas'
import { useApi } from '@/lib/api/ApiContext'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import type { StockProductRef } from '@/lib/api/types'
import { invalidateInventory } from './invalidation'
import { ProductPicker } from './ProductPicker'
import { quantitySchema } from './quantity'
import { WarehouseSelect } from './WarehouseSelect'

const schema = z.object({
  productId: z.string().min(1, 'Choose a product.'),
  warehouseId: z.string().min(1, 'Choose a warehouse.'),
  countedQuantity: quantitySchema,
  reason: requiredText(200),
})
type Values = z.infer<typeof schema>
const FIELDS = ['productId', 'warehouseId', 'countedQuantity', 'reason'] as const

/** A stock count (D7): on hand becomes the counted quantity; the difference is one ledger row. */
export function AdjustStockDialog({
  product,
  warehouseId,
  onClose,
}: {
  product?: StockProductRef
  warehouseId?: string
  onClose: () => void
}) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [formError, setFormError] = useState<string | null>(null)
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: {
      productId: product?.id ?? '',
      warehouseId: warehouseId ?? '',
      countedQuantity: '',
      reason: '',
    },
  })
  const errors = form.formState.errors
  const submit = form.handleSubmit(async (values) => {
    setFormError(null)
    try {
      await api.post('/inventory/adjustments', {
        productId: values.productId,
        warehouseId: values.warehouseId,
        countedQuantity: Number(values.countedQuantity),
        reason: values.reason,
      })
      await invalidateInventory(queryClient)
      toast.success('Count saved.')
      onClose()
    } catch (error) {
      if (!applyFieldErrors(error, form.setError, FIELDS)) setFormError(problemMessage(error))
    }
  })
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>Count stock</DialogTitle>
          <DialogDescription>
            Enter what is physically there. Reserved stock can't be counted away.
          </DialogDescription>
        </DialogHeader>
        <form noValidate onSubmit={submit} className="space-y-4">
          {!product && (
            <ProductPicker
              id="field-productId"
              label="Product"
              value={form.watch('productId')}
              error={errors.productId?.message}
              onChange={(id) => form.setValue('productId', id, { shouldValidate: form.formState.isSubmitted })}
            />
          )}
          <WarehouseSelect
            id="field-warehouseId"
            label="Warehouse"
            blankLabel="Choose…"
            value={form.watch('warehouseId')}
            error={errors.warehouseId?.message}
            onChange={(id) => form.setValue('warehouseId', id, { shouldValidate: form.formState.isSubmitted })}
          />
          <TextField form={form} name="countedQuantity" label="Counted quantity" inputMode="numeric" />
          <TextField form={form} name="reason" label="Reason" maxLength={200} />
          <FormError message={formError} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={form.formState.isSubmitting}>
              Save count
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
```

`frontend/src/features/inventory/TransferStockDialog.tsx`:

```tsx
import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { FormError } from '@/components/form/FormError'
import { TextField } from '@/components/form/TextField'
import { useApi } from '@/lib/api/ApiContext'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import type { StockProductRef } from '@/lib/api/types'
import { invalidateInventory } from './invalidation'
import { ProductPicker } from './ProductPicker'
import { positiveQuantitySchema } from './quantity'
import { shortageText } from './shortages'
import { WarehouseSelect } from './WarehouseSelect'

const schema = z
  .object({
    productId: z.string().min(1, 'Choose a product.'),
    fromWarehouseId: z.string().min(1, 'Choose a warehouse.'),
    toWarehouseId: z.string().min(1, 'Choose a warehouse.'),
    quantity: positiveQuantitySchema,
    note: z.string().trim().max(200, 'Use at most 200 characters.'),
  })
  .refine((v) => v.fromWarehouseId !== v.toWarehouseId, {
    path: ['toWarehouseId'],
    message: 'Choose a different warehouse.',
  })
type Values = z.infer<typeof schema>
const FIELDS = ['productId', 'fromWarehouseId', 'toWarehouseId', 'quantity', 'note'] as const

/** Moves available stock between two active warehouses (D8). */
export function TransferStockDialog({
  product,
  fromWarehouseId,
  onClose,
}: {
  product?: StockProductRef
  fromWarehouseId?: string
  onClose: () => void
}) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [formError, setFormError] = useState<string | null>(null)
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: {
      productId: product?.id ?? '',
      fromWarehouseId: fromWarehouseId ?? '',
      toWarehouseId: '',
      quantity: '',
      note: '',
    },
  })
  const errors = form.formState.errors
  const revalidate = { shouldValidate: form.formState.isSubmitted }
  const submit = form.handleSubmit(async (values) => {
    setFormError(null)
    try {
      await api.post('/inventory/transfers', {
        productId: values.productId,
        fromWarehouseId: values.fromWarehouseId,
        toWarehouseId: values.toWarehouseId,
        quantity: Number(values.quantity),
        note: values.note || null,
      })
      await invalidateInventory(queryClient)
      toast.success('Stock transferred.')
      onClose()
    } catch (error) {
      const shortage = shortageText(error)
      if (shortage) setFormError(shortage)
      else if (!applyFieldErrors(error, form.setError, FIELDS)) setFormError(problemMessage(error))
    }
  })
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>Transfer stock</DialogTitle>
          <DialogDescription>Only available stock (on hand minus reserved) can move.</DialogDescription>
        </DialogHeader>
        <form noValidate onSubmit={submit} className="space-y-4">
          {!product && (
            <ProductPicker
              id="field-productId"
              label="Product"
              value={form.watch('productId')}
              error={errors.productId?.message}
              onChange={(id) => form.setValue('productId', id, revalidate)}
            />
          )}
          <div className="grid gap-3 sm:grid-cols-2">
            <WarehouseSelect
              id="field-fromWarehouseId"
              label="From"
              blankLabel="Choose…"
              value={form.watch('fromWarehouseId')}
              error={errors.fromWarehouseId?.message}
              onChange={(id) => form.setValue('fromWarehouseId', id, revalidate)}
            />
            <WarehouseSelect
              id="field-toWarehouseId"
              label="To"
              blankLabel="Choose…"
              value={form.watch('toWarehouseId')}
              error={errors.toWarehouseId?.message}
              onChange={(id) => form.setValue('toWarehouseId', id, revalidate)}
            />
          </div>
          <TextField form={form} name="quantity" label="Quantity" inputMode="numeric" />
          <TextField form={form} name="note" label="Note" maxLength={200} />
          <FormError message={formError} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={form.formState.isSubmitting}>
              Transfer
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
```

- [ ] **Step 4: The stock page**

`frontend/src/features/inventory/StockPage.tsx`:

```tsx
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link, useSearchParams } from 'react-router'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Pagination } from '@/components/Pagination'
import { EmptyState, ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useApi } from '@/lib/api/ApiContext'
import type { Page, StockRow } from '@/lib/api/types'
import { toQuery } from '@/lib/query'
import { AdjustStockDialog } from './AdjustStockDialog'
import { formatQuantity } from './quantity'
import { TransferStockDialog } from './TransferStockDialog'
import { WarehouseSelect } from './WarehouseSelect'

const SIZE = 50

export function StockPage() {
  const api = useApi()
  const can = useCan()
  const [params, setParams] = useSearchParams()
  const q = params.get('q') ?? ''
  const warehouseId = params.get('warehouseId') ?? ''
  const belowMin = params.get('belowMin') === 'true'
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0)
  const [dialog, setDialog] = useState<'count' | 'transfer' | null>(null)
  const canAdjust = can(PERMISSIONS.stockAdjust)

  const stock = useQuery({
    queryKey: ['inventory', 'stock', { q, warehouseId, belowMin, page }],
    queryFn: () =>
      api.get<Page<StockRow>>(
        `/inventory/stock?${toQuery({ q, warehouseId, belowMin: belowMin ? 'true' : '', page, size: SIZE })}`,
      ),
    placeholderData: keepPreviousData,
    refetchOnMount: 'always',
  })

  function update(next: Record<string, string>) {
    const merged = new URLSearchParams(params)
    for (const [key, value] of Object.entries(next)) {
      if (value) merged.set(key, value)
      else merged.delete(key)
    }
    setParams(merged, { replace: true })
  }

  function onSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const value = new FormData(event.currentTarget).get('q')
    update({ q: typeof value === 'string' ? value.trim() : '', page: '' })
  }

  return (
    <>
      <PageHeader
        title="Stock"
        description="On hand, reserved, available and on order, per product and warehouse."
        actions={
          canAdjust && (
            <>
              <Button variant="outline" onClick={() => setDialog('transfer')}>
                Transfer stock
              </Button>
              <Button onClick={() => setDialog('count')}>Count stock</Button>
            </>
          )
        }
      />
      <div className="mb-4 flex flex-wrap items-end gap-3">
        <form onSubmit={onSearch} className="flex items-end gap-2" role="search">
          <div className="space-y-1.5">
            <Label htmlFor="stock-q">Search stock</Label>
            <Input id="stock-q" name="q" defaultValue={q} placeholder="SKU or name" />
          </div>
          <Button type="submit" variant="outline">
            Search
          </Button>
        </form>
        <WarehouseSelect
          id="stock-warehouse"
          label="Warehouse"
          blankLabel="All warehouses"
          value={warehouseId}
          onChange={(id) => update({ warehouseId: id, page: '' })}
        />
        <label className="flex items-center gap-2 pb-2 text-sm">
          <input
            type="checkbox"
            checked={belowMin}
            onChange={(e) => update({ belowMin: e.target.checked ? 'true' : '', page: '' })}
          />
          Below minimum only
        </label>
      </div>

      {stock.isPending ? (
        <ListSkeleton />
      ) : stock.isError ? (
        <ErrorState error={stock.error} onRetry={() => void stock.refetch()} />
      ) : stock.data.items.length === 0 ? (
        <EmptyState
          title={q || warehouseId || belowMin ? 'Nothing matches these filters.' : 'No stock yet.'}
          description="Count opening stock, receive a purchase order or set a reorder rule to see products here."
        />
      ) : (
        <>
          <div className="overflow-x-auto rounded-lg border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>SKU</TableHead>
                  <TableHead>Product</TableHead>
                  <TableHead>Warehouse</TableHead>
                  <TableHead className="text-right">On hand</TableHead>
                  <TableHead className="text-right">Reserved</TableHead>
                  <TableHead className="text-right">Available</TableHead>
                  <TableHead className="text-right">On order</TableHead>
                  <TableHead>Min – max</TableHead>
                  <TableHead>Status</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {stock.data.items.map((r) => (
                  <TableRow key={`${r.product.id}:${r.warehouse.id}`}>
                    <TableCell>
                      <Link
                        to={`/app/products/${r.product.id}`}
                        className="font-medium underline-offset-4 hover:underline"
                      >
                        {r.product.sku}
                      </Link>
                    </TableCell>
                    <TableCell>{r.product.name}</TableCell>
                    <TableCell>{r.warehouse.code}</TableCell>
                    <TableCell className="text-right tabular-nums">{formatQuantity(r.onHand)}</TableCell>
                    <TableCell className="text-right tabular-nums">{formatQuantity(r.reserved)}</TableCell>
                    <TableCell className="text-right tabular-nums">{formatQuantity(r.available)}</TableCell>
                    <TableCell className="text-right tabular-nums">{formatQuantity(r.onOrder)}</TableCell>
                    <TableCell>
                      {r.minQuantity != null && r.maxQuantity != null
                        ? `${formatQuantity(r.minQuantity)} – ${formatQuantity(r.maxQuantity)}`
                        : 'No rule'}
                    </TableCell>
                    <TableCell>
                      {r.belowMin ? <Badge variant="destructive">Below minimum</Badge> : '—'}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <Pagination
            page={stock.data.page}
            size={stock.data.size}
            total={stock.data.total}
            onPage={(p) => update({ page: String(p) })}
          />
        </>
      )}
      {dialog === 'count' && <AdjustStockDialog onClose={() => setDialog(null)} />}
      {dialog === 'transfer' && <TransferStockDialog onClose={() => setDialog(null)} />}
    </>
  )
}
```

Append to `InventoryLayout`'s `TABS`: `{ to: '/app/inventory/stock', label: 'Stock', anyOf: [PERMISSIONS.stockRead] },` and to `inventoryChildren`:

```tsx
  {
    path: 'stock',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.stockRead]}>
        <StockPage />
      </RequirePermission>
    ),
  },
```

- [ ] **Step 5: The product's Stock panel**

`frontend/src/features/inventory/ProductStockPanel.tsx`:

```tsx
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { ErrorState, ListSkeleton } from '@/components/states'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useTenantSession } from '@/features/auth/tenantSession'
import { useApi } from '@/lib/api/ApiContext'
import type { MovementView, Page, ProductKind, ProductStock } from '@/lib/api/types'
import { AdjustStockDialog } from './AdjustStockDialog'
import { MovementsTable } from './MovementsTable'
import { formatQuantity } from './quantity'
import { TransferStockDialog } from './TransferStockDialog'

/** Stock per active warehouse and the last 10 movements, on a GOODS product's page when Inventory is on. */
export function ProductStockPanel({ productId, kind }: { productId: string; kind: ProductKind }) {
  const session = useTenantSession()
  const can = useCan()
  const modules = session.state.status === 'authenticated' ? session.state.profile.modules : []
  const visible = kind === 'GOODS' && modules.includes('INVENTORY') && can(PERMISSIONS.stockRead)
  if (!visible) return null
  return <StockCard productId={productId} canAdjust={can(PERMISSIONS.stockAdjust)} />
}

function StockCard({ productId, canAdjust }: { productId: string; canAdjust: boolean }) {
  const api = useApi()
  const [dialog, setDialog] = useState<'count' | 'transfer' | null>(null)
  const stock = useQuery({
    queryKey: ['inventory', 'product-stock', productId],
    queryFn: () => api.get<ProductStock>(`/inventory/stock/products/${productId}`),
  })
  const movements = useQuery({
    queryKey: ['inventory', 'movements', { productId }],
    queryFn: () => api.get<Page<MovementView>>(`/inventory/movements?productId=${productId}&size=10`),
  })
  return (
    <Card role="region" aria-label="Stock">
      <CardHeader className="flex flex-row items-center justify-between gap-2">
        <CardTitle>Stock</CardTitle>
        {canAdjust && stock.data && (
          <div className="space-x-2">
            <Button size="sm" variant="outline" onClick={() => setDialog('transfer')}>
              Transfer
            </Button>
            <Button size="sm" variant="outline" onClick={() => setDialog('count')}>
              Count
            </Button>
          </div>
        )}
      </CardHeader>
      <CardContent className="space-y-4">
        {stock.isPending ? (
          <ListSkeleton rows={2} />
        ) : stock.isError ? (
          <ErrorState error={stock.error} onRetry={() => void stock.refetch()} />
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Warehouse</TableHead>
                <TableHead className="text-right">On hand</TableHead>
                <TableHead className="text-right">Reserved</TableHead>
                <TableHead className="text-right">Available</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {stock.data.levels.map((l) => (
                <TableRow key={l.warehouse.id}>
                  <TableCell>{l.warehouse.code}</TableCell>
                  <TableCell className="text-right tabular-nums">{formatQuantity(l.onHand)}</TableCell>
                  <TableCell className="text-right tabular-nums">{formatQuantity(l.reserved)}</TableCell>
                  <TableCell className="text-right tabular-nums">{formatQuantity(l.available)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
        {movements.data && movements.data.items.length > 0 && (
          <MovementsTable movements={movements.data.items} />
        )}
      </CardContent>
      {dialog === 'count' && stock.data && (
        <AdjustStockDialog product={stock.data.product} onClose={() => setDialog(null)} />
      )}
      {dialog === 'transfer' && stock.data && (
        <TransferStockDialog product={stock.data.product} onClose={() => setDialog(null)} />
      )}
    </Card>
  )
}
```

In `ProductDetailPage.tsx`, render `<ProductStockPanel productId={p.id} kind={p.kind} />` right after the Details `Card` (import from `@/features/inventory/ProductStockPanel`).

The panel test's first case asserts `12` (on hand) and `10` (available) in the MAIN row: `aProductStock()`'s level has reserved `2`, so those three numbers are distinct.

- [ ] **Step 6: Run the frontend checks**

Run: `cd frontend && npx prettier --write src && npm run format:check && npm run lint && npm run typecheck && npm test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add frontend/src
git commit -m "feat(frontend): stock list with count and transfer dialogs, and a Stock panel on product pages"
```

---
### Task 9: Purchase orders — list, editor, detail with status actions, and receipts

**Files:**
- Create: `frontend/src/features/inventory/OrderLinesEditor.tsx`, `OrderLinesEditor.test.tsx`, `useOrderAction.ts`, `PurchaseOrdersPage.tsx`, `PurchaseOrdersPage.test.tsx`, `PurchaseOrderFormDialog.tsx`, `PurchaseOrderDetailPage.tsx`, `PurchaseOrderDetailPage.test.tsx`, `ReceiveDialog.tsx`
- Modify: `frontend/src/features/inventory/quantity.ts` (`priceSchema`), `InventoryLayout.tsx` (`TABS`), `routes.tsx`

**Interfaces:**
- Consumes (Tasks 7–8): `PurchaseOrderView`, `PurchaseOrderSummary`, `PurchaseOrderStatus`, `StockProductRef`, `Page`; `ProductPicker`, `WarehouseSelect`, `PartyPicker` (`@/features/records/PartyPicker`), `positiveQuantitySchema`, `formatQuantity`, `PURCHASE_STATUS_LABELS`, `invalidateInventory`, `shortageText`, fixtures `aPurchaseOrder`, `aPurchaseSummary`, `aWarehouse`, `aProduct`, `aSummary`, `pageOf`.
- Produces (Task 10 reuses):
  - `OrderLinesEditor({ lines, onChange, priceField, priceLabel, priceHint?, errors })`; `LineDraft { key, product, quantity, price }`; `newLine(product?, quantity?, price?)`; `LineErrors = Record<string, string>`; `validateLines(lines, priceField, priceRequired): LineErrors`; `serverLineErrors(error): LineErrors | null`.
  - `priceSchema` in `quantity.ts`.
  - `useOrderAction(queryKey)` → `{ busy, error, run(path, body, success) }` posting a state change, storing the returned order under `queryKey`, invalidating Inventory, and on failure showing the shortage or problem text and refetching.
  - Routes `/app/inventory/purchase-orders` and `/app/inventory/purchase-orders/:orderId`; the "Purchase orders" tab.

- [ ] **Step 1: Write the failing tests**

`frontend/src/features/inventory/OrderLinesEditor.test.tsx`:

```tsx
import { describe, expect, it } from 'vitest'
import { ApiError } from '@/lib/api/client'
import { newLine, serverLineErrors, validateLines } from './OrderLinesEditor'

const widget = { id: 'pr-widget', sku: 'W-1', name: 'Widget', unit: 'each' }

describe('order lines', () => {
  it('needs a line, a product once, a positive quantity and a valid price', () => {
    expect(validateLines([], 'unitCost', true)).toEqual({ lines: 'Add at least one line.' })
    expect(
      validateLines(
        [newLine(widget, '2', '1.5'), newLine(widget, '0', ''), newLine(null, '1', '-1')],
        'unitCost',
        true,
      ),
    ).toEqual({
      'lines[1].productId': 'This product is already on the order.',
      'lines[1].quantity': 'Enter a quantity greater than 0.',
      'lines[1].unitCost': 'Enter an amount.',
      'lines[2].productId': 'Choose a product.',
      'lines[2].unitCost': 'Enter an amount like 12.50.',
    })
  })

  it('lets a sales price stay blank', () => {
    expect(validateLines([newLine(widget, '1', '')], 'unitPrice', false)).toEqual({})
  })

  it('keeps the server line errors', () => {
    const error = new ApiError({
      status: 400,
      detail: 'Request validation failed.',
      errors: [
        { field: 'lines[0].quantity', message: 'Use at most 4 decimal places.' },
        { field: 'supplierId', message: 'Choose a supplier.' },
      ],
    })
    expect(serverLineErrors(error)).toEqual({ 'lines[0].quantity': 'Use at most 4 decimal places.' })
    expect(serverLineErrors(new Error('x'))).toBeNull()
  })
})
```

`frontend/src/features/inventory/PurchaseOrdersPage.test.tsx`:

```tsx
import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import {
  aProduct,
  aPurchaseOrder,
  aPurchaseSummary,
  aSummary,
  aWarehouse,
  pageOf,
} from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup(path = '/app/inventory/purchase-orders', permissions?: string[]) {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['INVENTORY'], ...(permissions ? { permissions } : {}) }))
    .on('GET /purchase-orders', {
      body: pageOf([
        aPurchaseSummary({ status: 'ORDERED', expectedOn: '2026-11-15' }),
        aPurchaseSummary({ id: 'po-2', number: 'PO-00002', total: 7.5, lineCount: 3 }),
      ]),
    })
    .on('GET /purchase-orders/:id', { body: aPurchaseOrder() })
    .on('GET /inventory/warehouses', { body: [aWarehouse()] })
    .on('GET /parties', { body: pageOf([aSummary({ id: 'p-konkan', name: 'Konkan Supplies' })]) })
    .on('GET /products', { body: pageOf([aProduct()]) })
    .on('GET /activities', { body: pageOf([]) })
    .on('GET /documents', { body: [] })
  return { server, ...renderApp({ server, path }) }
}

describe('PurchaseOrdersPage', () => {
  it('lists purchase orders with supplier, status and total', async () => {
    setup()
    const row = (await screen.findByRole('link', { name: 'PO-00001' })).closest('tr') as HTMLElement
    expect(within(row).getByText('Konkan Supplies')).toBeInTheDocument()
    expect(within(row).getByText('Ordered')).toBeInTheDocument()
    expect(within(row).getByText(/25\.00/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'PO-00001' })).toHaveAttribute(
      'href',
      '/app/inventory/purchase-orders/po-1',
    )
  })

  it('filters by status from the URL and by search', async () => {
    const { server, user } = setup('/app/inventory/purchase-orders?status=ORDERED')
    await screen.findByRole('link', { name: 'PO-00001' })
    expect(server.callsTo('GET /purchase-orders')[0].query.get('status')).toBe('ORDERED')
    await user.type(screen.getByLabelText('Search purchase orders'), '00002')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    expect(server.callsTo('GET /purchase-orders').at(-1)?.query.get('q')).toBe('00002')
  })

  it('creates a draft and opens it', async () => {
    const { server, user, router } = setup()
    server.on('POST /purchase-orders', { status: 201, body: aPurchaseOrder({ id: 'po-new' }) })
    await user.click(await screen.findByRole('button', { name: 'New purchase order' }))
    const dialog = await screen.findByRole('dialog')
    await user.selectOptions(within(dialog).getByLabelText('Supplier'), 'p-konkan')
    await user.selectOptions(within(dialog).getByLabelText('Warehouse'), 'w-main')
    await user.selectOptions(within(dialog).getByLabelText('Line 1 product'), 'pr-widget')
    await user.type(within(dialog).getByLabelText('Line 1 quantity'), '10')
    await user.type(within(dialog).getByLabelText('Line 1 unit cost'), '2.5')
    await user.click(within(dialog).getByRole('button', { name: 'Create purchase order' }))
    expect(server.callsTo('POST /purchase-orders')[0].body).toEqual({
      supplierId: 'p-konkan',
      warehouseId: 'w-main',
      currency: null,
      expectedOn: null,
      notes: null,
      lines: [{ productId: 'pr-widget', quantity: 10, unitCost: 2.5 }],
    })
    await screen.findByRole('heading', { name: 'PO-00001' })
    expect(router.state.location.pathname).toBe('/app/inventory/purchase-orders/po-new')
  })

  it('shows line errors from the server next to the line', async () => {
    const { server, user } = setup()
    server.on('POST /purchase-orders', {
      status: 400,
      body: {
        detail: 'Request validation failed.',
        errors: [{ field: 'lines[0].productId', message: "Services don't carry stock." }],
      },
    })
    await user.click(await screen.findByRole('button', { name: 'New purchase order' }))
    const dialog = await screen.findByRole('dialog')
    await user.selectOptions(within(dialog).getByLabelText('Supplier'), 'p-konkan')
    await user.selectOptions(within(dialog).getByLabelText('Warehouse'), 'w-main')
    await user.selectOptions(within(dialog).getByLabelText('Line 1 product'), 'pr-widget')
    await user.type(within(dialog).getByLabelText('Line 1 quantity'), '1')
    await user.type(within(dialog).getByLabelText('Line 1 unit cost'), '1')
    await user.click(within(dialog).getByRole('button', { name: 'Create purchase order' }))
    expect(await within(dialog).findByText("Services don't carry stock.")).toBeInTheDocument()
  })

  it('hides New purchase order from readers', async () => {
    setup('/app/inventory/purchase-orders', ['inventory.purchase.read'])
    await screen.findByRole('link', { name: 'PO-00001' })
    expect(screen.queryByRole('button', { name: 'New purchase order' })).not.toBeInTheDocument()
  })
})
```

`frontend/src/features/inventory/PurchaseOrderDetailPage.test.tsx`:

```tsx
import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aPurchaseOrder, pageOf } from '@/test/records'
import { renderApp } from '@/test/renderApp'
import type { PurchaseOrderView } from '@/lib/api/types'

function setup(order: PurchaseOrderView, permissions?: string[]) {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['INVENTORY'], ...(permissions ? { permissions } : {}) }))
    .on('GET /purchase-orders/:id', { body: order })
    .on('GET /activities', { body: pageOf([]) })
    .on('GET /documents', { body: [] })
  return { server, ...renderApp({ server, path: `/app/inventory/purchase-orders/${order.id}` }) }
}

const ordered = aPurchaseOrder({
  status: 'ORDERED',
  orderedAt: '2026-10-08T10:00:00Z',
  version: 1,
  lines: [
    {
      id: 'pl-1',
      lineNo: 1,
      product: { id: 'pr-widget', sku: 'W-1', name: 'Widget', unit: 'each' },
      quantity: 10,
      receivedQuantity: 4,
      remainingQuantity: 6,
      unitCost: 2.5,
      lineTotal: 25,
    },
  ],
})

describe('PurchaseOrderDetailPage', () => {
  it('shows the lines and places a draft order', async () => {
    const { server, user } = setup(aPurchaseOrder())
    expect(await screen.findByRole('heading', { name: 'PO-00001' })).toBeInTheDocument()
    expect(screen.getByText('Draft')).toBeInTheDocument()
    const row = screen.getByText('W-1').closest('tr') as HTMLElement
    expect(within(row).getByText(/25\.00$/)).toBeInTheDocument()
    const placed = { ...ordered, lines: aPurchaseOrder().lines }
    // the page refetches after an action: the server now answers with the placed order
    server.on('POST /purchase-orders/:id/order', { body: placed })
    server.on('GET /purchase-orders/:id', { body: placed })
    await user.click(screen.getByRole('button', { name: 'Place order' }))
    expect(server.callsTo('POST /purchase-orders/:id/order')[0].body).toEqual({ version: 0 })
    expect(await screen.findByText('Ordered')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Place order' })).not.toBeInTheDocument()
  })

  it('receives part of what is still due', async () => {
    const { server, user } = setup(ordered)
    const received = { ...ordered, status: 'RECEIVED' as const, version: 2 }
    server.on('POST /purchase-orders/:id/receipts', { body: received })
    server.on('GET /purchase-orders/:id', { body: received })
    await user.click(await screen.findByRole('button', { name: 'Receive' }))
    const dialog = await screen.findByRole('dialog')
    const input = within(dialog).getByLabelText('Receive W-1 (6 due)')
    expect(input).toHaveValue('6')
    await user.clear(input)
    await user.type(input, '2')
    await user.click(within(dialog).getByRole('button', { name: 'Record receipt' }))
    expect(server.callsTo('POST /purchase-orders/:id/receipts')[0].body).toEqual({
      lines: [{ lineId: 'pl-1', quantity: 2 }],
      version: 1,
    })
    expect(await screen.findByText('Received')).toBeInTheDocument()
  })

  it('cancels after confirmation', async () => {
    const { server, user } = setup(aPurchaseOrder())
    const cancelled = aPurchaseOrder({
      status: 'CANCELLED',
      cancelledAt: '2026-10-08T11:00:00Z',
      version: 1,
    })
    server.on('POST /purchase-orders/:id/cancel', { body: cancelled })
    server.on('GET /purchase-orders/:id', { body: cancelled })
    await user.click(await screen.findByRole('button', { name: 'Cancel order' }))
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Cancel order' }))
    expect(await screen.findByText('Cancelled')).toBeInTheDocument()
  })

  it('shows a stale-version conflict and reloads', async () => {
    const { server, user } = setup(aPurchaseOrder())
    server.on('POST /purchase-orders/:id/order', {
      status: 409,
      body: { detail: 'This record was changed by someone else. Reload and try again.' },
    })
    await user.click(await screen.findByRole('button', { name: 'Place order' }))
    expect(
      await screen.findByText('This record was changed by someone else. Reload and try again.'),
    ).toBeInTheDocument()
    expect(server.callsTo('GET /purchase-orders/:id').length).toBeGreaterThan(1)
  })

  it('offers no actions to readers', async () => {
    setup(aPurchaseOrder(), ['inventory.purchase.read'])
    await screen.findByRole('heading', { name: 'PO-00001' })
    expect(screen.queryByRole('button', { name: 'Place order' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument()
  })
})
```

Run: `cd frontend && npx vitest run src/features/inventory` — Expected: FAIL (new modules missing).

- [ ] **Step 2: Price schema**

Append to `frontend/src/features/inventory/quantity.ts`:

```ts
/** A unit cost or price typed as text: 0 or more, at most 4 decimals. */
export const priceSchema = z
  .string()
  .trim()
  .refine((v) => /^\d+(\.\d+)?$/.test(v), 'Enter an amount like 12.50.')
  .refine((v) => !/\.\d{5,}$/.test(v), 'Use at most 4 decimal places.')
```

- [ ] **Step 3: The lines editor**

`frontend/src/features/inventory/OrderLinesEditor.tsx`:

```tsx
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { describedBy, Field } from '@/components/form/Field'
import { ApiError } from '@/lib/api/client'
import type { StockProductRef } from '@/lib/api/types'
import { ProductPicker } from './ProductPicker'
import { positiveQuantitySchema, priceSchema } from './quantity'

export interface LineDraft {
  key: string
  product: StockProductRef | null
  quantity: string
  price: string
}

/** Keys are the server's field names: 'lines', 'lines[0].quantity', … */
export type LineErrors = Record<string, string>

let nextKey = 0

export function newLine(product: StockProductRef | null = null, quantity = '', price = ''): LineDraft {
  nextKey += 1
  return { key: `line-${nextKey}`, product, quantity, price }
}

export function validateLines(
  lines: LineDraft[],
  priceField: 'unitCost' | 'unitPrice',
  priceRequired: boolean,
): LineErrors {
  const errors: LineErrors = {}
  if (lines.length === 0) errors.lines = 'Add at least one line.'
  if (lines.length > 100) errors.lines = 'Use at most 100 lines.'
  const seen = new Set<string>()
  lines.forEach((line, i) => {
    if (!line.product) errors[`lines[${i}].productId`] = 'Choose a product.'
    else if (seen.has(line.product.id))
      errors[`lines[${i}].productId`] = 'This product is already on the order.'
    else seen.add(line.product.id)
    const quantity = positiveQuantitySchema.safeParse(line.quantity)
    if (!quantity.success) errors[`lines[${i}].quantity`] = quantity.error.issues[0].message
    if (line.price.trim() === '') {
      if (priceRequired) errors[`lines[${i}].${priceField}`] = 'Enter an amount.'
    } else {
      const price = priceSchema.safeParse(line.price)
      if (!price.success) errors[`lines[${i}].${priceField}`] = price.error.issues[0].message
    }
  })
  return errors
}

/** The server's `lines…` field errors, or null when the error carries none. */
export function serverLineErrors(error: unknown): LineErrors | null {
  if (!(error instanceof ApiError)) return null
  const found = (error.problem.errors ?? []).filter(
    (e) => e.field === 'lines' || e.field.startsWith('lines['),
  )
  if (found.length === 0) return null
  return Object.fromEntries(found.map((e) => [e.field, e.message]))
}

export function OrderLinesEditor({
  lines,
  onChange,
  priceField,
  priceLabel,
  priceHint,
  errors,
}: {
  lines: LineDraft[]
  onChange: (lines: LineDraft[]) => void
  priceField: 'unitCost' | 'unitPrice'
  priceLabel: string
  priceHint?: string
  errors: LineErrors
}) {
  function update(index: number, patch: Partial<LineDraft>) {
    onChange(lines.map((line, i) => (i === index ? { ...line, ...patch } : line)))
  }
  return (
    <fieldset className="space-y-3">
      <legend className="text-sm font-medium">Lines</legend>
      {lines.map((line, i) => {
        const quantityId = `line-${i}-quantity`
        const priceId = `line-${i}-price`
        const quantityError = errors[`lines[${i}].quantity`]
        const priceError = errors[`lines[${i}].${priceField}`]
        return (
          <div key={line.key} className="space-y-2 rounded-md border p-3">
            <ProductPicker
              id={`line-${i}-product`}
              label={`Line ${i + 1} product`}
              value={line.product?.id ?? ''}
              current={line.product}
              error={errors[`lines[${i}].productId`]}
              onChange={(_, product) => update(i, { product })}
            />
            <div className="grid items-end gap-3 sm:grid-cols-[1fr_1fr_auto]">
              <Field id={quantityId} label={`Line ${i + 1} quantity`} error={quantityError}>
                <Input
                  id={quantityId}
                  inputMode="decimal"
                  value={line.quantity}
                  aria-invalid={quantityError ? true : undefined}
                  aria-describedby={describedBy(quantityId, quantityError)}
                  onChange={(e) => update(i, { quantity: e.target.value })}
                />
              </Field>
              <Field
                id={priceId}
                label={`Line ${i + 1} ${priceLabel.toLowerCase()}`}
                error={priceError}
                hint={priceHint}
              >
                <Input
                  id={priceId}
                  inputMode="decimal"
                  value={line.price}
                  aria-invalid={priceError ? true : undefined}
                  aria-describedby={describedBy(priceId, priceError, priceHint)}
                  onChange={(e) => update(i, { price: e.target.value })}
                />
              </Field>
              <Button
                type="button"
                variant="ghost"
                aria-label={`Remove line ${i + 1}`}
                onClick={() => onChange(lines.filter((_, j) => j !== i))}
              >
                Remove
              </Button>
            </div>
          </div>
        )
      })}
      {errors.lines && (
        <p role="alert" className="text-sm text-destructive">
          {errors.lines}
        </p>
      )}
      <Button type="button" variant="outline" onClick={() => onChange([...lines, newLine()])}>
        Add line
      </Button>
    </fieldset>
  )
}
```

- [ ] **Step 4: The shared status-action hook**

`frontend/src/features/inventory/useOrderAction.ts`:

```ts
import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { toast } from 'sonner'
import { useApi } from '@/lib/api/ApiContext'
import { problemMessage } from '@/lib/api/problems'
import { invalidateInventory } from './invalidation'
import { shortageText } from './shortages'

/** Posts an order state change; on success stores the returned order, on failure explains and reloads it. */
export function useOrderAction(queryKey: readonly unknown[]) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function run(path: string, body: unknown, success: string): Promise<boolean> {
    setBusy(true)
    setError(null)
    try {
      const updated = await api.post<unknown>(path, body)
      queryClient.setQueryData(queryKey, updated)
      await invalidateInventory(queryClient)
      toast.success(success)
      return true
    } catch (e) {
      setError(shortageText(e) ?? problemMessage(e))
      // the version or the stock may have moved: show the current order
      void queryClient.invalidateQueries({ queryKey })
      return false
    } finally {
      setBusy(false)
    }
  }

  return { busy, error, setError, run }
}
```

`invalidateInventory` invalidates the order's own key too (it sits under the `['inventory']` prefix); `setQueryData` first means the fresh order shows immediately while the refetch runs.

- [ ] **Step 5: The purchase-order editor**

`frontend/src/features/inventory/PurchaseOrderFormDialog.tsx`:

```tsx
import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { describedBy, Field } from '@/components/form/Field'
import { FormError } from '@/components/form/FormError'
import { TextAreaField } from '@/components/form/TextAreaField'
import { TextField } from '@/components/form/TextField'
import { PartyPicker } from '@/features/records/PartyPicker'
import { useApi } from '@/lib/api/ApiContext'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import type { PurchaseOrderView } from '@/lib/api/types'
import { invalidateInventory } from './invalidation'
import {
  newLine,
  OrderLinesEditor,
  serverLineErrors,
  validateLines,
  type LineDraft,
  type LineErrors,
} from './OrderLinesEditor'
import { WarehouseSelect } from './WarehouseSelect'

const schema = z.object({
  supplierId: z.string().min(1, 'Choose a supplier.'),
  warehouseId: z.string().min(1, 'Choose a warehouse.'),
  currency: z
    .string()
    .trim()
    .refine((v) => v === '' || /^[A-Za-z]{3}$/.test(v), 'Use a 3-letter currency code like USD.'),
  expectedOn: z.string(),
  notes: z.string().max(2000, 'Use at most 2000 characters.'),
})
type Values = z.infer<typeof schema>
const FIELDS = ['supplierId', 'warehouseId', 'currency', 'expectedOn', 'notes'] as const

/** Create or edit a DRAFT purchase order (D9). */
export function PurchaseOrderFormDialog({
  order,
  onClose,
  onSaved,
}: {
  order?: PurchaseOrderView
  onClose: () => void
  onSaved: (saved: PurchaseOrderView) => void
}) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [formError, setFormError] = useState<string | null>(null)
  const [lines, setLines] = useState<LineDraft[]>(
    order
      ? order.lines.map((l) => newLine(l.product, String(l.quantity), String(l.unitCost)))
      : [newLine()],
  )
  const [lineErrors, setLineErrors] = useState<LineErrors>({})
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: {
      supplierId: order?.supplier?.id ?? '',
      warehouseId: order?.warehouse.id ?? '',
      currency: order?.currency ?? '',
      expectedOn: order?.expectedOn ?? '',
      notes: order?.notes ?? '',
    },
  })
  const errors = form.formState.errors
  const revalidate = { shouldValidate: form.formState.isSubmitted }

  function submit(event: FormEvent<HTMLFormElement>) {
    const found = validateLines(lines, 'unitCost', true)
    setLineErrors(found)
    void form.handleSubmit(async (values) => {
      if (Object.keys(found).length > 0) return
      setFormError(null)
      const body = {
        supplierId: values.supplierId,
        warehouseId: values.warehouseId,
        currency: values.currency === '' ? null : values.currency.toUpperCase(),
        expectedOn: values.expectedOn || null,
        notes: values.notes.trim() || null,
        lines: lines.map((l) => ({
          productId: l.product?.id,
          quantity: Number(l.quantity),
          unitCost: Number(l.price),
        })),
        ...(order ? { version: order.version } : {}),
      }
      try {
        const saved = order
          ? await api.put<PurchaseOrderView>(`/purchase-orders/${order.id}`, body)
          : await api.post<PurchaseOrderView>('/purchase-orders', body)
        queryClient.setQueryData(['inventory', 'purchase-order', saved.id], saved)
        await invalidateInventory(queryClient)
        toast.success(order ? 'Changes saved.' : `${saved.number} created.`)
        onSaved(saved)
      } catch (error) {
        const fromServer = serverLineErrors(error)
        if (fromServer) setLineErrors(fromServer)
        const header = applyFieldErrors(error, form.setError, FIELDS)
        if (!fromServer && !header) setFormError(problemMessage(error))
      }
    })(event)
  }

  const expectedError = errors.expectedOn?.message
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{order ? `Edit ${order.number}` : 'New purchase order'}</DialogTitle>
          <DialogDescription>
            A draft changes nothing until you place it. Receipts then add the stock.
          </DialogDescription>
        </DialogHeader>
        <form noValidate onSubmit={submit} className="space-y-4">
          <PartyPicker
            id="field-supplierId"
            label="Supplier"
            value={form.watch('supplierId')}
            current={order?.supplier}
            error={errors.supplierId?.message}
            noneLabel="Choose…"
            onChange={(id) => form.setValue('supplierId', id, revalidate)}
          />
          <div className="grid gap-3 sm:grid-cols-3">
            <WarehouseSelect
              id="field-warehouseId"
              label="Warehouse"
              blankLabel="Choose…"
              value={form.watch('warehouseId')}
              error={errors.warehouseId?.message}
              onChange={(id) => form.setValue('warehouseId', id, revalidate)}
            />
            <TextField
              form={form}
              name="currency"
              label="Currency"
              maxLength={3}
              hint="Defaults to the workspace currency"
            />
            <Field id="field-expectedOn" label="Expected on" error={expectedError}>
              <Input
                id="field-expectedOn"
                type="date"
                aria-invalid={expectedError ? true : undefined}
                aria-describedby={describedBy('field-expectedOn', expectedError)}
                {...form.register('expectedOn')}
              />
            </Field>
          </div>
          <OrderLinesEditor
            lines={lines}
            onChange={setLines}
            priceField="unitCost"
            priceLabel="Unit cost"
            errors={lineErrors}
          />
          <TextAreaField form={form} name="notes" label="Notes" maxLength={2000} />
          <FormError message={formError} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={form.formState.isSubmitting}>
              {order ? 'Save changes' : 'Create purchase order'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
```

- [ ] **Step 6: The list page**

`frontend/src/features/inventory/PurchaseOrdersPage.tsx`:

```tsx
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { NativeSelect } from '@/components/form/NativeSelect'
import { Pagination } from '@/components/Pagination'
import { EmptyState, ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useApi } from '@/lib/api/ApiContext'
import type { Page, PurchaseOrderSummary } from '@/lib/api/types'
import { formatDate, formatMoney } from '@/lib/format'
import { toQuery } from '@/lib/query'
import { PURCHASE_STATUS_LABELS } from './labels'
import { PurchaseOrderFormDialog } from './PurchaseOrderFormDialog'

const SIZE = 20

export function PurchaseOrdersPage() {
  const api = useApi()
  const can = useCan()
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const q = params.get('q') ?? ''
  const status = params.get('status') ?? ''
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0)
  const [creating, setCreating] = useState(false)
  const canManage = can(PERMISSIONS.purchaseManage)

  const orders = useQuery({
    queryKey: ['inventory', 'purchase-orders', { q, status, page }],
    queryFn: () =>
      api.get<Page<PurchaseOrderSummary>>(`/purchase-orders?${toQuery({ q, status, page, size: SIZE })}`),
    placeholderData: keepPreviousData,
    refetchOnMount: 'always',
  })

  function update(next: Record<string, string>) {
    const merged = new URLSearchParams(params)
    for (const [key, value] of Object.entries(next)) {
      if (value) merged.set(key, value)
      else merged.delete(key)
    }
    setParams(merged, { replace: true })
  }

  function onSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const value = new FormData(event.currentTarget).get('q')
    update({ q: typeof value === 'string' ? value.trim() : '', page: '' })
  }

  return (
    <>
      <PageHeader
        title="Purchase orders"
        description="What you've ordered from suppliers, and what has arrived."
        actions={canManage && <Button onClick={() => setCreating(true)}>New purchase order</Button>}
      />
      <div className="mb-4 flex flex-wrap items-end gap-3">
        <form onSubmit={onSearch} className="flex items-end gap-2" role="search">
          <div className="space-y-1.5">
            <Label htmlFor="po-q">Search purchase orders</Label>
            <Input id="po-q" name="q" defaultValue={q} placeholder="Number" />
          </div>
          <Button type="submit" variant="outline">
            Search
          </Button>
        </form>
        <div className="space-y-1.5">
          <Label htmlFor="po-status">Status</Label>
          <NativeSelect
            id="po-status"
            value={status}
            onChange={(e) => update({ status: e.target.value, page: '' })}
          >
            <option value="">Any status</option>
            {Object.entries(PURCHASE_STATUS_LABELS).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </NativeSelect>
        </div>
      </div>
      {orders.isPending ? (
        <ListSkeleton />
      ) : orders.isError ? (
        <ErrorState error={orders.error} onRetry={() => void orders.refetch()} />
      ) : orders.data.items.length === 0 ? (
        <EmptyState
          title={q || status ? 'No purchase orders match these filters.' : 'No purchase orders yet.'}
          description="Create one here, or from the reorder suggestions."
        />
      ) : (
        <>
          <div className="overflow-x-auto rounded-lg border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Number</TableHead>
                  <TableHead>Supplier</TableHead>
                  <TableHead>Warehouse</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead className="text-right">Lines</TableHead>
                  <TableHead className="text-right">Total</TableHead>
                  <TableHead>Expected</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {orders.data.items.map((o) => (
                  <TableRow key={o.id}>
                    <TableCell>
                      <Link
                        to={`/app/inventory/purchase-orders/${o.id}`}
                        className="font-medium underline-offset-4 hover:underline"
                      >
                        {o.number}
                      </Link>
                    </TableCell>
                    <TableCell>{o.supplier?.name ?? '—'}</TableCell>
                    <TableCell>{o.warehouse.code}</TableCell>
                    <TableCell>
                      <Badge variant={o.status === 'CANCELLED' ? 'outline' : 'secondary'}>
                        {PURCHASE_STATUS_LABELS[o.status]}
                      </Badge>
                    </TableCell>
                    <TableCell className="text-right tabular-nums">{o.lineCount}</TableCell>
                    <TableCell className="text-right tabular-nums">
                      {formatMoney(o.total, o.currency)}
                    </TableCell>
                    <TableCell>{formatDate(o.expectedOn)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <Pagination
            page={orders.data.page}
            size={orders.data.size}
            total={orders.data.total}
            onPage={(p) => update({ page: String(p) })}
          />
        </>
      )}
      {creating && (
        <PurchaseOrderFormDialog
          onClose={() => setCreating(false)}
          onSaved={(saved) => {
            setCreating(false)
            navigate(`/app/inventory/purchase-orders/${saved.id}`)
          }}
        />
      )}
    </>
  )
}
```

- [ ] **Step 7: The detail page and the receipt dialog**

`frontend/src/features/inventory/ReceiveDialog.tsx`:

```tsx
import { useState, type FormEvent } from 'react'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { describedBy, Field } from '@/components/form/Field'
import { FormError } from '@/components/form/FormError'
import type { PurchaseOrderView } from '@/lib/api/types'
import { formatQuantity, quantitySchema } from './quantity'

/** One receipt against the lines still due; blank or 0 skips a line (D9). */
export function ReceiveDialog({
  order,
  busy,
  error,
  onReceive,
  onClose,
}: {
  order: PurchaseOrderView
  busy: boolean
  error: string | null
  onReceive: (lines: Array<{ lineId: string; quantity: number }>) => Promise<boolean>
  onClose: () => void
}) {
  const due = order.lines.filter((l) => l.remainingQuantity > 0)
  const [values, setValues] = useState<Record<string, string>>(
    Object.fromEntries(due.map((l) => [l.id, String(l.remainingQuantity)])),
  )
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)

  async function submit(event: FormEvent) {
    event.preventDefault()
    const found: Record<string, string> = {}
    const lines: Array<{ lineId: string; quantity: number }> = []
    for (const line of due) {
      const raw = (values[line.id] ?? '').trim()
      if (raw === '' || Number(raw) === 0) continue
      const parsed = quantitySchema.safeParse(raw)
      if (!parsed.success) found[line.id] = parsed.error.issues[0].message
      else if (Number(raw) > line.remainingQuantity)
        found[line.id] = `Receive at most ${formatQuantity(line.remainingQuantity)}.`
      else lines.push({ lineId: line.id, quantity: Number(raw) })
    }
    setErrors(found)
    if (Object.keys(found).length > 0) return
    if (lines.length === 0) {
      setFormError('Enter what arrived on at least one line.')
      return
    }
    setFormError(null)
    if (await onReceive(lines)) onClose()
  }

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>Receive {order.number}</DialogTitle>
          <DialogDescription>
            Into {order.warehouse.code}. What arrives is added to stock now.
          </DialogDescription>
        </DialogHeader>
        <form noValidate onSubmit={(e) => void submit(e)} className="space-y-4">
          {due.map((line) => {
            const id = `receive-${line.id}`
            const fieldError = errors[line.id]
            return (
              <Field
                key={line.id}
                id={id}
                label={`Receive ${line.product.sku} (${formatQuantity(line.remainingQuantity)} due)`}
                error={fieldError}
              >
                <Input
                  id={id}
                  inputMode="decimal"
                  value={values[line.id] ?? ''}
                  aria-invalid={fieldError ? true : undefined}
                  aria-describedby={describedBy(id, fieldError)}
                  onChange={(e) => setValues({ ...values, [line.id]: e.target.value })}
                />
              </Field>
            )
          })}
          <FormError message={formError ?? error} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={busy}>
              Record receipt
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
```

`frontend/src/features/inventory/PurchaseOrderDetailPage.tsx`:

```tsx
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { FormError } from '@/components/form/FormError'
import { ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { ActivityPanel } from '@/features/records/ActivityPanel'
import { DocumentsPanel } from '@/features/records/DocumentsPanel'
import { SubjectTasksPanel } from '@/features/records/SubjectTasksPanel'
import { useApi } from '@/lib/api/ApiContext'
import type { PurchaseOrderView } from '@/lib/api/types'
import { formatDate, formatDateTime, formatMoney } from '@/lib/format'
import { PURCHASE_STATUS_LABELS } from './labels'
import { PurchaseOrderFormDialog } from './PurchaseOrderFormDialog'
import { formatQuantity } from './quantity'
import { ReceiveDialog } from './ReceiveDialog'
import { useOrderAction } from './useOrderAction'

export function PurchaseOrderDetailPage() {
  const { orderId = '' } = useParams()
  const api = useApi()
  const can = useCan()
  const key = ['inventory', 'purchase-order', orderId] as const
  const order = useQuery({
    queryKey: key,
    queryFn: () => api.get<PurchaseOrderView>(`/purchase-orders/${orderId}`),
  })
  const action = useOrderAction(key)
  const [dialog, setDialog] = useState<'edit' | 'receive' | 'cancel' | null>(null)
  const canManage = can(PERMISSIONS.purchaseManage)
  const back = (
    <Link to="/app/inventory/purchase-orders" className="text-sm underline-offset-4 hover:underline">
      ← Purchase orders
    </Link>
  )
  if (order.isPending)
    return (
      <>
        {back}
        <ListSkeleton />
      </>
    )
  if (order.isError)
    return (
      <>
        {back}
        <ErrorState error={order.error} onRetry={() => void order.refetch()} />
      </>
    )
  const o = order.data
  // a received order stays open for notes (quality issues, invoices); a cancelled one is history
  const closed = o.status === 'CANCELLED'
  const path = `/purchase-orders/${o.id}`

  return (
    <div className="space-y-6">
      {back}
      <PageHeader
        title={o.number}
        description={`${o.supplier?.name ?? 'Supplier'} · into ${o.warehouse.code}`}
        actions={
          <>
            <Badge variant={o.status === 'CANCELLED' ? 'outline' : 'secondary'}>
              {PURCHASE_STATUS_LABELS[o.status]}
            </Badge>
            {canManage && o.status === 'DRAFT' && (
              <>
                <Button variant="outline" size="sm" onClick={() => setDialog('edit')}>
                  Edit
                </Button>
                <Button
                  size="sm"
                  disabled={action.busy}
                  onClick={() => void action.run(`${path}/order`, { version: o.version }, `${o.number} placed.`)}
                >
                  Place order
                </Button>
              </>
            )}
            {canManage && (o.status === 'ORDERED' || o.status === 'PARTIALLY_RECEIVED') && (
              <Button size="sm" onClick={() => setDialog('receive')}>
                Receive
              </Button>
            )}
            {canManage && (o.status === 'DRAFT' || o.status === 'ORDERED') && (
              <Button variant="outline" size="sm" onClick={() => setDialog('cancel')}>
                Cancel order
              </Button>
            )}
          </>
        }
      />
      {!dialog && <FormError message={action.error} />}
      <Card>
        <CardHeader>
          <CardTitle>Lines</CardTitle>
        </CardHeader>
        <CardContent>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>SKU</TableHead>
                <TableHead>Product</TableHead>
                <TableHead className="text-right">Qty ordered</TableHead>
                <TableHead className="text-right">Qty received</TableHead>
                <TableHead className="text-right">Qty due</TableHead>
                <TableHead className="text-right">Unit cost</TableHead>
                <TableHead className="text-right">Total</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {o.lines.map((l) => (
                <TableRow key={l.id}>
                  <TableCell>
                    <Link to={`/app/products/${l.product.id}`} className="underline-offset-4 hover:underline">
                      {l.product.sku}
                    </Link>
                  </TableCell>
                  <TableCell>{l.product.name}</TableCell>
                  <TableCell className="text-right tabular-nums">{formatQuantity(l.quantity, l.product.unit)}</TableCell>
                  <TableCell className="text-right tabular-nums">{formatQuantity(l.receivedQuantity)}</TableCell>
                  <TableCell className="text-right tabular-nums">{formatQuantity(l.remainingQuantity)}</TableCell>
                  <TableCell className="text-right tabular-nums">{formatMoney(l.unitCost, o.currency)}</TableCell>
                  <TableCell className="text-right tabular-nums">{formatMoney(l.lineTotal, o.currency)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          <p className="mt-3 text-right text-sm font-medium">Total {formatMoney(o.total, o.currency)}</p>
        </CardContent>
      </Card>
      <Card>
        <CardHeader>
          <CardTitle>Details</CardTitle>
        </CardHeader>
        <CardContent className="space-y-2 text-sm">
          {o.notes && <p className="whitespace-pre-wrap">{o.notes}</p>}
          <dl className="grid grid-cols-[9rem_1fr] gap-x-3 gap-y-2">
            <dt className="text-muted-foreground">Supplier</dt>
            <dd>
              {o.supplier ? (
                <Link to={`/app/directory/${o.supplier.id}`} className="underline-offset-4 hover:underline">
                  {o.supplier.name}
                </Link>
              ) : (
                '—'
              )}
            </dd>
            <dt className="text-muted-foreground">Warehouse</dt>
            <dd>
              {o.warehouse.code} · {o.warehouse.name}
            </dd>
            <dt className="text-muted-foreground">Expected</dt>
            <dd>{formatDate(o.expectedOn)}</dd>
            <dt className="text-muted-foreground">Created</dt>
            <dd>
              {formatDateTime(o.createdAt)}
              {o.createdBy ? ` by ${o.createdBy.name}` : ''}
            </dd>
            {o.orderedAt && (
              <>
                <dt className="text-muted-foreground">Ordered on</dt>
                <dd>{formatDateTime(o.orderedAt)}</dd>
              </>
            )}
            {o.receivedAt && (
              <>
                <dt className="text-muted-foreground">Received on</dt>
                <dd>{formatDateTime(o.receivedAt)}</dd>
              </>
            )}
            {o.cancelledAt && (
              <>
                <dt className="text-muted-foreground">Cancelled on</dt>
                <dd>{formatDateTime(o.cancelledAt)}</dd>
              </>
            )}
          </dl>
        </CardContent>
      </Card>
      <SubjectTasksPanel subjectType="PURCHASE_ORDER" subjectId={o.id} label={o.number} archived={closed} />
      <ActivityPanel subjectType="PURCHASE_ORDER" subjectId={o.id} archived={closed} />
      <DocumentsPanel subjectType="PURCHASE_ORDER" subjectId={o.id} archived={closed} />
      {dialog === 'edit' && (
        <PurchaseOrderFormDialog order={o} onClose={() => setDialog(null)} onSaved={() => setDialog(null)} />
      )}
      {dialog === 'receive' && (
        <ReceiveDialog
          order={o}
          busy={action.busy}
          error={action.error}
          onClose={() => {
            action.setError(null)
            setDialog(null)
          }}
          onReceive={(lines) => action.run(`${path}/receipts`, { lines, version: o.version }, 'Receipt recorded.')}
        />
      )}
      <ConfirmDialog
        open={dialog === 'cancel'}
        title={`Cancel ${o.number}?`}
        description="The order stays on record as cancelled. Nothing was received, so stock doesn't change."
        confirmLabel="Cancel order"
        busy={action.busy}
        error={action.error}
        onCancel={() => {
          action.setError(null)
          setDialog(null)
        }}
        onConfirm={() =>
          void action
            .run(`${path}/cancel`, { version: o.version }, `${o.number} cancelled.`)
            .then((ok) => ok && setDialog(null))
        }
      />
    </div>
  )
}
```

Append to `TABS`: `{ to: '/app/inventory/purchase-orders', label: 'Purchase orders', anyOf: [PERMISSIONS.purchaseRead] },` and to `inventoryChildren`:

```tsx
  {
    path: 'purchase-orders',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.purchaseRead]}>
        <PurchaseOrdersPage />
      </RequirePermission>
    ),
  },
  {
    path: 'purchase-orders/:orderId',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.purchaseRead]}>
        <PurchaseOrderDetailPage />
      </RequirePermission>
    ),
  },
```

- [ ] **Step 8: Run the frontend checks**

Run: `cd frontend && npx prettier --write src && npm run format:check && npm run lint && npm run typecheck && npm test`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add frontend/src
git commit -m "feat(frontend): purchase orders with a line editor, status actions and partial receipts"
```

---
### Task 10: Sales orders — list, editor, and confirm / fulfil / cancel with shortages explained

**Files:**
- Create: `frontend/src/features/inventory/SalesOrdersPage.tsx`, `SalesOrdersPage.test.tsx`, `SalesOrderFormDialog.tsx`, `SalesOrderDetailPage.tsx`, `SalesOrderDetailPage.test.tsx`
- Modify: `frontend/src/features/inventory/InventoryLayout.tsx` (`TABS`), `routes.tsx`

**Interfaces:**
- Consumes (Tasks 7–9): `SalesOrderView`, `SalesOrderSummary`, `Page`; `OrderLinesEditor`, `newLine`, `validateLines`, `serverLineErrors`, `LineDraft`, `LineErrors`, `useOrderAction`, `WarehouseSelect`, `PartyPicker`, `formatQuantity`, `SALES_STATUS_LABELS`, `invalidateInventory`, fixtures `aSalesOrder`, `aSalesSummary`, `aWarehouse`, `aProduct`, `aSummary`, `pageOf`.
- Produces: routes `/app/inventory/sales-orders` and `/app/inventory/sales-orders/:orderId`; the "Sales orders" tab.

- [ ] **Step 1: Write the failing tests**

`frontend/src/features/inventory/SalesOrdersPage.test.tsx`:

```tsx
import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aProduct, aSalesOrder, aSalesSummary, aSummary, aWarehouse, pageOf } from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup(path = '/app/inventory/sales-orders') {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['INVENTORY'] }))
    .on('GET /sales-orders', {
      body: pageOf([aSalesSummary({ status: 'CONFIRMED' }), aSalesSummary({ id: 'so-2', number: 'SO-00002' })]),
    })
    .on('GET /sales-orders/:id', { body: aSalesOrder() })
    .on('GET /inventory/warehouses', { body: [aWarehouse()] })
    .on('GET /parties', { body: pageOf([aSummary({ id: 'p-deccan', name: 'Deccan Retail' })]) })
    .on('GET /products', { body: pageOf([aProduct()]) })
    .on('GET /activities', { body: pageOf([]) })
    .on('GET /documents', { body: [] })
  return { server, ...renderApp({ server, path }) }
}

describe('SalesOrdersPage', () => {
  it('lists sales orders and filters by status from the URL', async () => {
    const { server } = setup('/app/inventory/sales-orders?status=CONFIRMED')
    const row = (await screen.findByRole('link', { name: 'SO-00001' })).closest('tr') as HTMLElement
    expect(within(row).getByText('Deccan Retail')).toBeInTheDocument()
    expect(within(row).getByText('Confirmed')).toBeInTheDocument()
    expect(server.callsTo('GET /sales-orders')[0].query.get('status')).toBe('CONFIRMED')
  })

  it('creates a draft priced from the catalog when the price is left blank', async () => {
    const { server, user, router } = setup()
    server.on('POST /sales-orders', { status: 201, body: aSalesOrder({ id: 'so-new' }) })
    await user.click(await screen.findByRole('button', { name: 'New sales order' }))
    const dialog = await screen.findByRole('dialog')
    await user.selectOptions(within(dialog).getByLabelText('Customer'), 'p-deccan')
    await user.selectOptions(within(dialog).getByLabelText('Warehouse'), 'w-main')
    await user.selectOptions(within(dialog).getByLabelText('Line 1 product'), 'pr-widget')
    await user.type(within(dialog).getByLabelText('Line 1 quantity'), '4')
    await user.click(within(dialog).getByRole('button', { name: 'Create sales order' }))
    expect(server.callsTo('POST /sales-orders')[0].body).toEqual({
      customerId: 'p-deccan',
      warehouseId: 'w-main',
      currency: null,
      notes: null,
      lines: [{ productId: 'pr-widget', quantity: 4, unitPrice: null }],
    })
    await screen.findByRole('heading', { name: 'SO-00001' })
    expect(router.state.location.pathname).toBe('/app/inventory/sales-orders/so-new')
  })
})
```

`frontend/src/features/inventory/SalesOrderDetailPage.test.tsx`:

```tsx
import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { SalesOrderView } from '@/lib/api/types'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aSalesOrder, pageOf } from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup(order: SalesOrderView, permissions?: string[]) {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['INVENTORY'], ...(permissions ? { permissions } : {}) }))
    .on('GET /sales-orders/:id', { body: order })
    .on('GET /activities', { body: pageOf([]) })
    .on('GET /documents', { body: [] })
  return { server, ...renderApp({ server, path: `/app/inventory/sales-orders/${order.id}` }) }
}

describe('SalesOrderDetailPage', () => {
  it('confirms a draft, reserving its stock', async () => {
    const { server, user } = setup(aSalesOrder())
    const confirmed = aSalesOrder({ status: 'CONFIRMED', confirmedAt: '2026-10-08T10:00:00Z', version: 1 })
    server.on('POST /sales-orders/:id/confirm', { body: confirmed })
    server.on('GET /sales-orders/:id', { body: confirmed })
    await user.click(await screen.findByRole('button', { name: 'Confirm' }))
    expect(server.callsTo('POST /sales-orders/:id/confirm')[0].body).toEqual({ version: 0 })
    expect(await screen.findByRole('button', { name: 'Fulfil' })).toBeInTheDocument()
  })

  it('lists every shortage when confirming fails', async () => {
    const { server, user } = setup(aSalesOrder())
    server.on('POST /sales-orders/:id/confirm', {
      status: 409,
      body: {
        detail: 'Not enough stock.',
        shortages: [
          { productId: 'pr-widget', sku: 'W-1', requested: 4, available: 1 },
          { productId: 'pr-gadget', sku: 'G-1', requested: 2, available: 0 },
        ],
      },
    })
    await user.click(await screen.findByRole('button', { name: 'Confirm' }))
    expect(
      await screen.findByText('Not enough stock: W-1 needs 4, 1 available; G-1 needs 2, 0 available.'),
    ).toBeInTheDocument()
  })

  it('fulfils a confirmed order', async () => {
    const confirmed = aSalesOrder({ status: 'CONFIRMED', confirmedAt: '2026-10-08T10:00:00Z', version: 1 })
    const { server, user } = setup(confirmed)
    const fulfilled = { ...confirmed, status: 'FULFILLED' as const, fulfilledAt: '2026-10-08T12:00:00Z', version: 2 }
    server.on('POST /sales-orders/:id/fulfil', { body: fulfilled })
    server.on('GET /sales-orders/:id', { body: fulfilled })
    await user.click(await screen.findByRole('button', { name: 'Fulfil' }))
    expect(server.callsTo('POST /sales-orders/:id/fulfil')[0].body).toEqual({ version: 1 })
    expect(await screen.findByText('Fulfilled')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Cancel order' })).not.toBeInTheDocument()
  })

  it('cancels a confirmed order after saying its reservation is released', async () => {
    const confirmed = aSalesOrder({ status: 'CONFIRMED', confirmedAt: '2026-10-08T10:00:00Z', version: 1 })
    const { server, user } = setup(confirmed)
    const cancelled = { ...confirmed, status: 'CANCELLED' as const, cancelledAt: '2026-10-08T12:00:00Z', version: 2 }
    server.on('POST /sales-orders/:id/cancel', { body: cancelled })
    server.on('GET /sales-orders/:id', { body: cancelled })
    await user.click(await screen.findByRole('button', { name: 'Cancel order' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText(/reserved stock is released/i)).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Cancel order' }))
    expect(await screen.findByText('Cancelled')).toBeInTheDocument()
  })

  it('offers no actions to readers', async () => {
    setup(aSalesOrder(), ['inventory.order.read'])
    await screen.findByRole('heading', { name: 'SO-00001' })
    expect(screen.queryByRole('button', { name: 'Confirm' })).not.toBeInTheDocument()
  })
})
```

Run: `cd frontend && npx vitest run src/features/inventory/SalesOrdersPage.test.tsx src/features/inventory/SalesOrderDetailPage.test.tsx`
Expected: FAIL.

- [ ] **Step 2: The sales-order editor**

`frontend/src/features/inventory/SalesOrderFormDialog.tsx`:

```tsx
import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { FormError } from '@/components/form/FormError'
import { TextAreaField } from '@/components/form/TextAreaField'
import { TextField } from '@/components/form/TextField'
import { PartyPicker } from '@/features/records/PartyPicker'
import { useApi } from '@/lib/api/ApiContext'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import type { SalesOrderView } from '@/lib/api/types'
import { invalidateInventory } from './invalidation'
import {
  newLine,
  OrderLinesEditor,
  serverLineErrors,
  validateLines,
  type LineDraft,
  type LineErrors,
} from './OrderLinesEditor'
import { WarehouseSelect } from './WarehouseSelect'

const schema = z.object({
  customerId: z.string().min(1, 'Choose a customer.'),
  warehouseId: z.string().min(1, 'Choose a warehouse.'),
  currency: z
    .string()
    .trim()
    .refine((v) => v === '' || /^[A-Za-z]{3}$/.test(v), 'Use a 3-letter currency code like USD.'),
  notes: z.string().max(2000, 'Use at most 2000 characters.'),
})
type Values = z.infer<typeof schema>
const FIELDS = ['customerId', 'warehouseId', 'currency', 'notes'] as const

/** Create or edit a DRAFT sales order (D10). A blank price uses the product's list price. */
export function SalesOrderFormDialog({
  order,
  onClose,
  onSaved,
}: {
  order?: SalesOrderView
  onClose: () => void
  onSaved: (saved: SalesOrderView) => void
}) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [formError, setFormError] = useState<string | null>(null)
  const [lines, setLines] = useState<LineDraft[]>(
    order
      ? order.lines.map((l) => newLine(l.product, String(l.quantity), String(l.unitPrice)))
      : [newLine()],
  )
  const [lineErrors, setLineErrors] = useState<LineErrors>({})
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: {
      customerId: order?.customer?.id ?? '',
      warehouseId: order?.warehouse.id ?? '',
      currency: order?.currency ?? '',
      notes: order?.notes ?? '',
    },
  })
  const errors = form.formState.errors
  const revalidate = { shouldValidate: form.formState.isSubmitted }

  function submit(event: FormEvent<HTMLFormElement>) {
    const found = validateLines(lines, 'unitPrice', false)
    setLineErrors(found)
    void form.handleSubmit(async (values) => {
      if (Object.keys(found).length > 0) return
      setFormError(null)
      const body = {
        customerId: values.customerId,
        warehouseId: values.warehouseId,
        currency: values.currency === '' ? null : values.currency.toUpperCase(),
        notes: values.notes.trim() || null,
        lines: lines.map((l) => ({
          productId: l.product?.id,
          quantity: Number(l.quantity),
          unitPrice: l.price.trim() === '' ? null : Number(l.price),
        })),
        ...(order ? { version: order.version } : {}),
      }
      try {
        const saved = order
          ? await api.put<SalesOrderView>(`/sales-orders/${order.id}`, body)
          : await api.post<SalesOrderView>('/sales-orders', body)
        queryClient.setQueryData(['inventory', 'sales-order', saved.id], saved)
        await invalidateInventory(queryClient)
        toast.success(order ? 'Changes saved.' : `${saved.number} created.`)
        onSaved(saved)
      } catch (error) {
        const fromServer = serverLineErrors(error)
        if (fromServer) setLineErrors(fromServer)
        const header = applyFieldErrors(error, form.setError, FIELDS)
        if (!fromServer && !header) setFormError(problemMessage(error))
      }
    })(event)
  }

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{order ? `Edit ${order.number}` : 'New sales order'}</DialogTitle>
          <DialogDescription>
            A draft reserves nothing. Confirming reserves every line, or tells you what is short.
          </DialogDescription>
        </DialogHeader>
        <form noValidate onSubmit={submit} className="space-y-4">
          <PartyPicker
            id="field-customerId"
            label="Customer"
            value={form.watch('customerId')}
            current={order?.customer}
            error={errors.customerId?.message}
            noneLabel="Choose…"
            onChange={(id) => form.setValue('customerId', id, revalidate)}
          />
          <div className="grid gap-3 sm:grid-cols-2">
            <WarehouseSelect
              id="field-warehouseId"
              label="Warehouse"
              blankLabel="Choose…"
              value={form.watch('warehouseId')}
              error={errors.warehouseId?.message}
              onChange={(id) => form.setValue('warehouseId', id, revalidate)}
            />
            <TextField
              form={form}
              name="currency"
              label="Currency"
              maxLength={3}
              hint="Defaults to the workspace currency"
            />
          </div>
          <OrderLinesEditor
            lines={lines}
            onChange={setLines}
            priceField="unitPrice"
            priceLabel="Unit price"
            priceHint="Blank uses the list price"
            errors={lineErrors}
          />
          <TextAreaField form={form} name="notes" label="Notes" maxLength={2000} />
          <FormError message={formError} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={form.formState.isSubmitting}>
              {order ? 'Save changes' : 'Create sales order'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
```

- [ ] **Step 3: The list page**

`frontend/src/features/inventory/SalesOrdersPage.tsx` — the same structure as `PurchaseOrdersPage` (Task 9 Step 6) with these differences, written out in full:

```tsx
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { NativeSelect } from '@/components/form/NativeSelect'
import { Pagination } from '@/components/Pagination'
import { EmptyState, ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useApi } from '@/lib/api/ApiContext'
import type { Page, SalesOrderSummary } from '@/lib/api/types'
import { formatDateTime, formatMoney } from '@/lib/format'
import { toQuery } from '@/lib/query'
import { SALES_STATUS_LABELS } from './labels'
import { SalesOrderFormDialog } from './SalesOrderFormDialog'

const SIZE = 20

export function SalesOrdersPage() {
  const api = useApi()
  const can = useCan()
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const q = params.get('q') ?? ''
  const status = params.get('status') ?? ''
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0)
  const [creating, setCreating] = useState(false)
  const canManage = can(PERMISSIONS.orderManage)

  const orders = useQuery({
    queryKey: ['inventory', 'sales-orders', { q, status, page }],
    queryFn: () =>
      api.get<Page<SalesOrderSummary>>(`/sales-orders?${toQuery({ q, status, page, size: SIZE })}`),
    placeholderData: keepPreviousData,
    refetchOnMount: 'always',
  })

  function update(next: Record<string, string>) {
    const merged = new URLSearchParams(params)
    for (const [key, value] of Object.entries(next)) {
      if (value) merged.set(key, value)
      else merged.delete(key)
    }
    setParams(merged, { replace: true })
  }

  function onSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const value = new FormData(event.currentTarget).get('q')
    update({ q: typeof value === 'string' ? value.trim() : '', page: '' })
  }

  return (
    <>
      <PageHeader
        title="Sales orders"
        description="Orders from customers: confirm to reserve stock, fulfil to ship it."
        actions={canManage && <Button onClick={() => setCreating(true)}>New sales order</Button>}
      />
      <div className="mb-4 flex flex-wrap items-end gap-3">
        <form onSubmit={onSearch} className="flex items-end gap-2" role="search">
          <div className="space-y-1.5">
            <Label htmlFor="so-q">Search sales orders</Label>
            <Input id="so-q" name="q" defaultValue={q} placeholder="Number" />
          </div>
          <Button type="submit" variant="outline">
            Search
          </Button>
        </form>
        <div className="space-y-1.5">
          <Label htmlFor="so-status">Status</Label>
          <NativeSelect
            id="so-status"
            value={status}
            onChange={(e) => update({ status: e.target.value, page: '' })}
          >
            <option value="">Any status</option>
            {Object.entries(SALES_STATUS_LABELS).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </NativeSelect>
        </div>
      </div>
      {orders.isPending ? (
        <ListSkeleton />
      ) : orders.isError ? (
        <ErrorState error={orders.error} onRetry={() => void orders.refetch()} />
      ) : orders.data.items.length === 0 ? (
        <EmptyState
          title={q || status ? 'No sales orders match these filters.' : 'No sales orders yet.'}
          description="Create one when a customer orders goods you stock."
        />
      ) : (
        <>
          <div className="overflow-x-auto rounded-lg border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Number</TableHead>
                  <TableHead>Customer</TableHead>
                  <TableHead>Warehouse</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead className="text-right">Lines</TableHead>
                  <TableHead className="text-right">Total</TableHead>
                  <TableHead>Created</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {orders.data.items.map((o) => (
                  <TableRow key={o.id}>
                    <TableCell>
                      <Link
                        to={`/app/inventory/sales-orders/${o.id}`}
                        className="font-medium underline-offset-4 hover:underline"
                      >
                        {o.number}
                      </Link>
                    </TableCell>
                    <TableCell>{o.customer?.name ?? '—'}</TableCell>
                    <TableCell>{o.warehouse.code}</TableCell>
                    <TableCell>
                      <Badge variant={o.status === 'CANCELLED' ? 'outline' : 'secondary'}>
                        {SALES_STATUS_LABELS[o.status]}
                      </Badge>
                    </TableCell>
                    <TableCell className="text-right tabular-nums">{o.lineCount}</TableCell>
                    <TableCell className="text-right tabular-nums">
                      {formatMoney(o.total, o.currency)}
                    </TableCell>
                    <TableCell>{formatDateTime(o.createdAt)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <Pagination
            page={orders.data.page}
            size={orders.data.size}
            total={orders.data.total}
            onPage={(p) => update({ page: String(p) })}
          />
        </>
      )}
      {creating && (
        <SalesOrderFormDialog
          onClose={() => setCreating(false)}
          onSaved={(saved) => {
            setCreating(false)
            navigate(`/app/inventory/sales-orders/${saved.id}`)
          }}
        />
      )}
    </>
  )
}
```

- [ ] **Step 4: The detail page**

`frontend/src/features/inventory/SalesOrderDetailPage.tsx`:

```tsx
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { FormError } from '@/components/form/FormError'
import { ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { ActivityPanel } from '@/features/records/ActivityPanel'
import { DocumentsPanel } from '@/features/records/DocumentsPanel'
import { SubjectTasksPanel } from '@/features/records/SubjectTasksPanel'
import { useApi } from '@/lib/api/ApiContext'
import type { SalesOrderView } from '@/lib/api/types'
import { formatDateTime, formatMoney } from '@/lib/format'
import { SALES_STATUS_LABELS } from './labels'
import { formatQuantity } from './quantity'
import { SalesOrderFormDialog } from './SalesOrderFormDialog'
import { useOrderAction } from './useOrderAction'

export function SalesOrderDetailPage() {
  const { orderId = '' } = useParams()
  const api = useApi()
  const can = useCan()
  const key = ['inventory', 'sales-order', orderId] as const
  const order = useQuery({
    queryKey: key,
    queryFn: () => api.get<SalesOrderView>(`/sales-orders/${orderId}`),
  })
  const action = useOrderAction(key)
  const [dialog, setDialog] = useState<'edit' | 'cancel' | null>(null)
  const canManage = can(PERMISSIONS.orderManage)
  const back = (
    <Link to="/app/inventory/sales-orders" className="text-sm underline-offset-4 hover:underline">
      ← Sales orders
    </Link>
  )
  if (order.isPending)
    return (
      <>
        {back}
        <ListSkeleton />
      </>
    )
  if (order.isError)
    return (
      <>
        {back}
        <ErrorState error={order.error} onRetry={() => void order.refetch()} />
      </>
    )
  const o = order.data
  const path = `/sales-orders/${o.id}`

  return (
    <div className="space-y-6">
      {back}
      <PageHeader
        title={o.number}
        description={`${o.customer?.name ?? 'Customer'} · from ${o.warehouse.code}`}
        actions={
          <>
            <Badge variant={o.status === 'CANCELLED' ? 'outline' : 'secondary'}>
              {SALES_STATUS_LABELS[o.status]}
            </Badge>
            {canManage && o.status === 'DRAFT' && (
              <>
                <Button variant="outline" size="sm" onClick={() => setDialog('edit')}>
                  Edit
                </Button>
                <Button
                  size="sm"
                  disabled={action.busy}
                  onClick={() =>
                    void action.run(`${path}/confirm`, { version: o.version }, `${o.number} confirmed.`)
                  }
                >
                  Confirm
                </Button>
              </>
            )}
            {canManage && o.status === 'CONFIRMED' && (
              <Button
                size="sm"
                disabled={action.busy}
                onClick={() =>
                  void action.run(`${path}/fulfil`, { version: o.version }, `${o.number} fulfilled.`)
                }
              >
                Fulfil
              </Button>
            )}
            {canManage && (o.status === 'DRAFT' || o.status === 'CONFIRMED') && (
              <Button variant="outline" size="sm" onClick={() => setDialog('cancel')}>
                Cancel order
              </Button>
            )}
          </>
        }
      />
      {dialog !== 'cancel' && <FormError message={action.error} />}
      <Card>
        <CardHeader>
          <CardTitle>Lines</CardTitle>
        </CardHeader>
        <CardContent>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>SKU</TableHead>
                <TableHead>Product</TableHead>
                <TableHead className="text-right">Quantity</TableHead>
                <TableHead className="text-right">Unit price</TableHead>
                <TableHead className="text-right">Total</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {o.lines.map((l) => (
                <TableRow key={l.id}>
                  <TableCell>
                    <Link to={`/app/products/${l.product.id}`} className="underline-offset-4 hover:underline">
                      {l.product.sku}
                    </Link>
                  </TableCell>
                  <TableCell>{l.product.name}</TableCell>
                  <TableCell className="text-right tabular-nums">{formatQuantity(l.quantity, l.product.unit)}</TableCell>
                  <TableCell className="text-right tabular-nums">{formatMoney(l.unitPrice, o.currency)}</TableCell>
                  <TableCell className="text-right tabular-nums">{formatMoney(l.lineTotal, o.currency)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          <p className="mt-3 text-right text-sm font-medium">Total {formatMoney(o.total, o.currency)}</p>
        </CardContent>
      </Card>
      <Card>
        <CardHeader>
          <CardTitle>Details</CardTitle>
        </CardHeader>
        <CardContent className="space-y-2 text-sm">
          {o.notes && <p className="whitespace-pre-wrap">{o.notes}</p>}
          <dl className="grid grid-cols-[9rem_1fr] gap-x-3 gap-y-2">
            <dt className="text-muted-foreground">Customer</dt>
            <dd>
              {o.customer ? (
                <Link to={`/app/directory/${o.customer.id}`} className="underline-offset-4 hover:underline">
                  {o.customer.name}
                </Link>
              ) : (
                '—'
              )}
            </dd>
            <dt className="text-muted-foreground">Warehouse</dt>
            <dd>
              {o.warehouse.code} · {o.warehouse.name}
            </dd>
            <dt className="text-muted-foreground">Created</dt>
            <dd>
              {formatDateTime(o.createdAt)}
              {o.createdBy ? ` by ${o.createdBy.name}` : ''}
            </dd>
            {o.confirmedAt && (
              <>
                <dt className="text-muted-foreground">Confirmed on</dt>
                <dd>{formatDateTime(o.confirmedAt)}</dd>
              </>
            )}
            {o.fulfilledAt && (
              <>
                <dt className="text-muted-foreground">Fulfilled on</dt>
                <dd>{formatDateTime(o.fulfilledAt)}</dd>
              </>
            )}
            {o.cancelledAt && (
              <>
                <dt className="text-muted-foreground">Cancelled on</dt>
                <dd>{formatDateTime(o.cancelledAt)}</dd>
              </>
            )}
          </dl>
        </CardContent>
      </Card>
      <SubjectTasksPanel
        subjectType="SALES_ORDER"
        subjectId={o.id}
        label={o.number}
        archived={o.status === 'CANCELLED'}
      />
      <ActivityPanel subjectType="SALES_ORDER" subjectId={o.id} archived={o.status === 'CANCELLED'} />
      <DocumentsPanel subjectType="SALES_ORDER" subjectId={o.id} archived={o.status === 'CANCELLED'} />
      {dialog === 'edit' && (
        <SalesOrderFormDialog order={o} onClose={() => setDialog(null)} onSaved={() => setDialog(null)} />
      )}
      <ConfirmDialog
        open={dialog === 'cancel'}
        title={`Cancel ${o.number}?`}
        description={
          o.status === 'CONFIRMED'
            ? 'Its reserved stock is released for other orders. The order stays on record as cancelled.'
            : 'The order stays on record as cancelled.'
        }
        confirmLabel="Cancel order"
        busy={action.busy}
        error={action.error}
        onCancel={() => {
          action.setError(null)
          setDialog(null)
        }}
        onConfirm={() =>
          void action
            .run(`${path}/cancel`, { version: o.version }, `${o.number} cancelled.`)
            .then((ok) => ok && setDialog(null))
        }
      />
    </div>
  )
}
```

Append to `TABS`: `{ to: '/app/inventory/sales-orders', label: 'Sales orders', anyOf: [PERMISSIONS.orderRead] },` and to `inventoryChildren` the two routes `sales-orders` (`SalesOrdersPage`) and `sales-orders/:orderId` (`SalesOrderDetailPage`), both behind `RequirePermission anyOf={[PERMISSIONS.orderRead]}`, written like the purchase-order routes in Task 9 Step 7.

- [ ] **Step 5: Run the frontend checks**

Run: `cd frontend && npx prettier --write src && npm run format:check && npm run lint && npm run typecheck && npm test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src
git commit -m "feat(frontend): sales orders that confirm with explained shortages, fulfil and cancel"
```

---
### Task 11: Reorder suggestions and rules, and a party's orders

**Files:**
- Create: `frontend/src/features/inventory/ReorderPage.tsx`, `ReorderPage.test.tsx`, `ReorderRuleDialog.tsx`, `PartyOrdersPanel.tsx`, `PartyOrdersPanel.test.tsx`
- Modify: `frontend/src/features/inventory/InventoryLayout.tsx` (`TABS`), `routes.tsx`, `frontend/src/features/directory/PartyDetailPage.tsx`, `frontend/src/features/crm/Customer360Page.tsx`

**Interfaces:**
- Consumes (Tasks 7–10): `ReorderSuggestion`, `ReorderRuleView`, `PurchaseOrderView`, `PurchaseOrderSummary`, `SalesOrderSummary`, `Page`; `ProductPicker`, `WarehouseSelect`, `PartyPicker`, `quantitySchema`, `positiveQuantitySchema`, `formatQuantity`, `PURCHASE_STATUS_LABELS`, `SALES_STATUS_LABELS`, `invalidateInventory`, fixtures `aSuggestion`, `aReorderRule`, `aPurchaseOrder`, `aPurchaseSummary`, `aSalesSummary`, `aParty`, `aWarehouse`, `aProduct`, `aSummary`, `pageOf`.
- Produces: route `/app/inventory/reorder` and the "Reorder" tab; `PartyOrdersPanel({ partyId })` (a region named "Orders"; nothing without Inventory, without order permissions, or when the party has no orders).

- [ ] **Step 1: Write the failing tests**

`frontend/src/features/inventory/ReorderPage.test.tsx`:

```tsx
import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import {
  aProduct,
  aPurchaseOrder,
  aReorderRule,
  aSuggestion,
  aSummary,
  aWarehouse,
  pageOf,
} from '@/test/records'
import { renderApp } from '@/test/renderApp'

const GADGET = { id: 'pr-gadget', sku: 'G-1', name: 'Gadget', unit: 'box' }

function setup(permissions?: string[]) {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['INVENTORY'], ...(permissions ? { permissions } : {}) }))
    .on('GET /inventory/reorder-suggestions', {
      body: [
        aSuggestion(),
        aSuggestion({
          ruleId: 'rr-2',
          product: GADGET,
          supplier: null,
          daysOfCover: null,
          suggestedQuantity: 7,
          explanation: '3 available, 0 on order, below the minimum of 5; no usage in the last 30 days; order 7 to reach 10',
        }),
      ],
    })
    .on('GET /inventory/reorder-rules', { body: [aReorderRule()] })
    .on('GET /inventory/warehouses', { body: [aWarehouse()] })
    .on('GET /products', { body: pageOf([aProduct()]) })
    .on('GET /parties', { body: pageOf([aSummary({ id: 'p-konkan', name: 'Konkan Supplies' })]) })
  return { server, ...renderApp({ server, path: '/app/inventory/reorder' }) }
}

describe('ReorderPage', () => {
  it('explains each suggestion', async () => {
    setup()
    expect(
      await screen.findByText(
        '12 available, 0 on order, below the minimum of 20; 38 used in the last 30 days (about 9 days of cover); order 48 to reach 60',
      ),
    ).toBeInTheDocument()
    expect(screen.getByText(/no usage in the last 30 days/)).toBeInTheDocument()
  })

  it('turns the selected suggestions into draft purchase orders', async () => {
    const { server, user } = setup()
    server.on('POST /inventory/reorder-suggestions/purchase-orders', {
      status: 201,
      body: { orders: [aPurchaseOrder({ id: 'po-9', number: 'PO-00009' })] },
    })
    await user.click(await screen.findByLabelText('Select W-1 at MAIN'))
    const quantity = screen.getByLabelText('Order quantity for W-1 at MAIN')
    expect(quantity).toHaveValue('48')
    await user.clear(quantity)
    await user.type(quantity, '50')
    expect(screen.getByLabelText('Select G-1 at MAIN')).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Create purchase orders' }))
    expect(server.callsTo('POST /inventory/reorder-suggestions/purchase-orders')[0].body).toEqual({
      items: [{ productId: 'pr-widget', warehouseId: 'w-main', quantity: 50 }],
    })
    expect(await screen.findByRole('link', { name: 'PO-00009' })).toHaveAttribute(
      'href',
      '/app/inventory/purchase-orders/po-9',
    )
  })

  it('saves a new rule', async () => {
    const { server, user } = setup()
    server.on('PUT /inventory/reorder-rules', { body: aReorderRule({ id: 'rr-3' }) })
    await user.click(await screen.findByRole('button', { name: 'New rule' }))
    const dialog = await screen.findByRole('dialog')
    await user.selectOptions(within(dialog).getByLabelText('Product'), 'pr-widget')
    await user.selectOptions(within(dialog).getByLabelText('Warehouse'), 'w-main')
    await user.type(within(dialog).getByLabelText('Minimum'), '20')
    await user.type(within(dialog).getByLabelText('Maximum'), '60')
    await user.selectOptions(within(dialog).getByLabelText('Preferred supplier'), 'p-konkan')
    await user.click(within(dialog).getByRole('button', { name: 'Save rule' }))
    expect(server.callsTo('PUT /inventory/reorder-rules')[0].body).toEqual({
      productId: 'pr-widget',
      warehouseId: 'w-main',
      minQuantity: 20,
      maxQuantity: 60,
      supplierId: 'p-konkan',
    })
  })

  it('needs a maximum above the minimum', async () => {
    const { user } = setup()
    await user.click(await screen.findByRole('button', { name: 'New rule' }))
    const dialog = await screen.findByRole('dialog')
    await user.selectOptions(within(dialog).getByLabelText('Product'), 'pr-widget')
    await user.selectOptions(within(dialog).getByLabelText('Warehouse'), 'w-main')
    await user.type(within(dialog).getByLabelText('Minimum'), '20')
    await user.type(within(dialog).getByLabelText('Maximum'), '20')
    await user.click(within(dialog).getByRole('button', { name: 'Save rule' }))
    expect(await within(dialog).findByText('Enter a maximum above the minimum.')).toBeInTheDocument()
  })

  it('deletes a rule after confirmation', async () => {
    const { server, user } = setup()
    server.on('DELETE /inventory/reorder-rules/:id', { status: 204 })
    await user.click(await screen.findByRole('button', { name: 'Delete rule for W-1 at MAIN' }))
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Delete rule' }))
    expect(server.callsTo('DELETE /inventory/reorder-rules/:id')[0].params.id).toBe('rr-1')
  })

  it('lets a stock reader see suggestions but not act on them', async () => {
    setup(['inventory.stock.read'])
    await screen.findByText(/38 used in the last 30 days/)
    expect(screen.queryByRole('button', { name: 'Create purchase orders' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'New rule' })).not.toBeInTheDocument()
  })
})
```

`frontend/src/features/inventory/PartyOrdersPanel.test.tsx`:

```tsx
import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aParty, aPurchaseSummary, aSalesSummary, pageOf } from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup(modules: string[], purchases = [aPurchaseSummary({ status: 'ORDERED' })]) {
  const server = fakeServer()
  signedIn(server, testProfile({ modules }))
    .on('GET /parties/:id', { body: aParty() })
    .on('GET /parties', { body: pageOf([]) })
    .on('GET /activities', { body: pageOf([]) })
    .on('GET /documents', { body: [] })
    .on('GET /purchase-orders', { body: pageOf(purchases) })
    .on('GET /sales-orders', { body: pageOf([aSalesSummary({ status: 'FULFILLED' })]) })
  return { server, ...renderApp({ server, path: '/app/directory/p-acme' }) }
}

describe('PartyOrdersPanel', () => {
  it("lists the party's purchase and sales orders", async () => {
    const { server } = setup(['INVENTORY'])
    const panel = await screen.findByRole('region', { name: 'Orders' })
    expect(await within(panel).findByRole('link', { name: 'PO-00001' })).toBeInTheDocument()
    expect(within(panel).getByText('Ordered')).toBeInTheDocument()
    expect(within(panel).getByRole('link', { name: 'SO-00001' })).toBeInTheDocument()
    expect(within(panel).getByText('Fulfilled')).toBeInTheDocument()
    expect(server.callsTo('GET /purchase-orders')[0].query.get('supplierId')).toBe('p-acme')
    expect(server.callsTo('GET /sales-orders')[0].query.get('customerId')).toBe('p-acme')
  })

  it('is absent without Inventory', async () => {
    setup([])
    await screen.findByRole('heading', { name: 'Acme' })
    expect(screen.queryByRole('region', { name: 'Orders' })).not.toBeInTheDocument()
  })
})
```

Run: `cd frontend && npx vitest run src/features/inventory/ReorderPage.test.tsx src/features/inventory/PartyOrdersPanel.test.tsx`
Expected: FAIL.

- [ ] **Step 2: The rule dialog**

`frontend/src/features/inventory/ReorderRuleDialog.tsx`:

```tsx
import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { FormError } from '@/components/form/FormError'
import { TextField } from '@/components/form/TextField'
import { PartyPicker } from '@/features/records/PartyPicker'
import { useApi } from '@/lib/api/ApiContext'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import type { ReorderRuleView } from '@/lib/api/types'
import { invalidateInventory } from './invalidation'
import { ProductPicker } from './ProductPicker'
import { quantitySchema } from './quantity'
import { WarehouseSelect } from './WarehouseSelect'

const schema = z
  .object({
    productId: z.string().min(1, 'Choose a product.'),
    warehouseId: z.string().min(1, 'Choose a warehouse.'),
    minQuantity: quantitySchema,
    maxQuantity: quantitySchema,
    supplierId: z.string(),
  })
  .refine((v) => Number(v.maxQuantity) > Number(v.minQuantity), {
    path: ['maxQuantity'],
    message: 'Enter a maximum above the minimum.',
  })
type Values = z.infer<typeof schema>
const FIELDS = ['productId', 'warehouseId', 'minQuantity', 'maxQuantity', 'supplierId'] as const

/** One rule per product and warehouse (D11): below min → suggest up to max, from the preferred supplier. */
export function ReorderRuleDialog({ rule, onClose }: { rule?: ReorderRuleView; onClose: () => void }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [formError, setFormError] = useState<string | null>(null)
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: {
      productId: rule?.product.id ?? '',
      warehouseId: rule?.warehouse.id ?? '',
      minQuantity: rule ? String(rule.minQuantity) : '',
      maxQuantity: rule ? String(rule.maxQuantity) : '',
      supplierId: rule?.supplier?.id ?? '',
    },
  })
  const errors = form.formState.errors
  const revalidate = { shouldValidate: form.formState.isSubmitted }
  const submit = form.handleSubmit(async (values) => {
    setFormError(null)
    try {
      await api.put('/inventory/reorder-rules', {
        productId: values.productId,
        warehouseId: values.warehouseId,
        minQuantity: Number(values.minQuantity),
        maxQuantity: Number(values.maxQuantity),
        supplierId: values.supplierId || null,
        ...(rule ? { version: rule.version } : {}),
      })
      await invalidateInventory(queryClient)
      toast.success('Reorder rule saved.')
      onClose()
    } catch (error) {
      if (!applyFieldErrors(error, form.setError, FIELDS)) setFormError(problemMessage(error))
    }
  })
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>
            {rule ? `Rule for ${rule.product.sku} at ${rule.warehouse.code}` : 'New reorder rule'}
          </DialogTitle>
          <DialogDescription>
            When available plus on order falls below the minimum, suggest ordering up to the maximum.
          </DialogDescription>
        </DialogHeader>
        <form noValidate onSubmit={submit} className="space-y-4">
          {!rule && (
            <>
              <ProductPicker
                id="field-productId"
                label="Product"
                value={form.watch('productId')}
                error={errors.productId?.message}
                onChange={(id) => form.setValue('productId', id, revalidate)}
              />
              <WarehouseSelect
                id="field-warehouseId"
                label="Warehouse"
                blankLabel="Choose…"
                value={form.watch('warehouseId')}
                error={errors.warehouseId?.message}
                onChange={(id) => form.setValue('warehouseId', id, revalidate)}
              />
            </>
          )}
          <div className="grid gap-3 sm:grid-cols-2">
            <TextField form={form} name="minQuantity" label="Minimum" inputMode="numeric" />
            <TextField form={form} name="maxQuantity" label="Maximum" inputMode="numeric" />
          </div>
          <PartyPicker
            id="field-supplierId"
            label="Preferred supplier"
            value={form.watch('supplierId')}
            current={rule?.supplier}
            error={errors.supplierId?.message}
            onChange={(id) => form.setValue('supplierId', id)}
          />
          <FormError message={formError} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={form.formState.isSubmitting}>
              Save rule
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
```

(`quantitySchema` refuses an empty minimum or maximum with "Enter a number like 12 or 2.5.", so the `refine` only compares two numbers.)

- [ ] **Step 3: The reorder page**

`frontend/src/features/inventory/ReorderPage.tsx`:

```tsx
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { toast } from 'sonner'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { FormError } from '@/components/form/FormError'
import { EmptyState, ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useApi } from '@/lib/api/ApiContext'
import { problemMessage } from '@/lib/api/problems'
import type { PurchaseOrderView, ReorderRuleView, ReorderSuggestion } from '@/lib/api/types'
import { invalidateInventory } from './invalidation'
import { formatQuantity, positiveQuantitySchema } from './quantity'
import { ReorderRuleDialog } from './ReorderRuleDialog'

const keyOf = (s: { product: { id: string }; warehouse: { id: string } }) =>
  `${s.product.id}:${s.warehouse.id}`
const nameOf = (s: { product: { sku: string }; warehouse: { code: string } }) =>
  `${s.product.sku} at ${s.warehouse.code}`

export function ReorderPage() {
  const api = useApi()
  const can = useCan()
  const queryClient = useQueryClient()
  const canRules = can(PERMISSIONS.reorderManage)
  const canDraft = canRules && can(PERMISSIONS.purchaseManage)
  const suggestions = useQuery({
    queryKey: ['inventory', 'suggestions'],
    queryFn: () => api.get<ReorderSuggestion[]>('/inventory/reorder-suggestions'),
    refetchOnMount: 'always',
  })
  const rules = useQuery({
    queryKey: ['inventory', 'reorder-rules'],
    queryFn: () => api.get<ReorderRuleView[]>('/inventory/reorder-rules'),
  })
  const [selected, setSelected] = useState<Record<string, boolean>>({})
  const [quantities, setQuantities] = useState<Record<string, string>>({})
  const [created, setCreated] = useState<PurchaseOrderView[]>([])
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [editing, setEditing] = useState<ReorderRuleView | 'new' | null>(null)
  const [deleting, setDeleting] = useState<ReorderRuleView | null>(null)

  const quantityFor = (s: ReorderSuggestion) => quantities[keyOf(s)] ?? String(s.suggestedQuantity)

  async function draft() {
    const chosen = (suggestions.data ?? []).filter((s) => selected[keyOf(s)])
    if (chosen.length === 0) {
      setError('Select at least one suggestion.')
      return
    }
    const bad = chosen.find((s) => !positiveQuantitySchema.safeParse(quantityFor(s)).success)
    if (bad) {
      setError(`Enter a quantity greater than 0 for ${nameOf(bad)}.`)
      return
    }
    setBusy(true)
    setError(null)
    try {
      const result = await api.post<{ orders: PurchaseOrderView[] }>(
        '/inventory/reorder-suggestions/purchase-orders',
        {
          items: chosen.map((s) => ({
            productId: s.product.id,
            warehouseId: s.warehouse.id,
            quantity: Number(quantityFor(s)),
          })),
        },
      )
      setCreated(result.orders)
      setSelected({})
      setQuantities({})
      await invalidateInventory(queryClient)
      toast.success(
        result.orders.length === 1
          ? 'Draft purchase order created.'
          : `${result.orders.length} draft purchase orders created.`,
      )
    } catch (e) {
      setError(problemMessage(e))
    } finally {
      setBusy(false)
    }
  }

  async function remove(rule: ReorderRuleView) {
    setBusy(true)
    setError(null)
    try {
      await api.del(`/inventory/reorder-rules/${rule.id}`)
      await invalidateInventory(queryClient)
      toast.success('Reorder rule deleted.')
      setDeleting(null)
    } catch (e) {
      setError(problemMessage(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title="Reorder"
        description="Products below their minimum, with the figures behind each suggestion."
      />
      <Card>
        <CardHeader className="flex flex-row items-center justify-between gap-2">
          <CardTitle>Suggestions</CardTitle>
          {canDraft && (suggestions.data?.length ?? 0) > 0 && (
            <Button disabled={busy} onClick={() => void draft()}>
              Create purchase orders
            </Button>
          )}
        </CardHeader>
        <CardContent className="space-y-3">
          {!deleting && <FormError message={error} />}
          {created.length > 0 && (
            <p role="status" className="text-sm">
              Drafts to review and place:{' '}
              {created.map((o, i) => (
                <span key={o.id}>
                  {i > 0 && ', '}
                  <Link
                    to={`/app/inventory/purchase-orders/${o.id}`}
                    className="underline-offset-4 hover:underline"
                  >
                    {o.number}
                  </Link>
                </span>
              ))}
            </p>
          )}
          {suggestions.isPending ? (
            <ListSkeleton rows={3} />
          ) : suggestions.isError ? (
            <ErrorState error={suggestions.error} onRetry={() => void suggestions.refetch()} />
          ) : suggestions.data.length === 0 ? (
            <EmptyState
              title="Nothing to reorder."
              description="Every product with a reorder rule is at or above its minimum."
            />
          ) : (
            <div className="overflow-x-auto rounded-lg border">
              <Table>
                <TableHeader>
                  <TableRow>
                    {canDraft && <TableHead className="w-8" />}
                    <TableHead>Product</TableHead>
                    <TableHead>Warehouse</TableHead>
                    <TableHead>Why</TableHead>
                    <TableHead>Supplier</TableHead>
                    <TableHead className="w-32">Order</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {suggestions.data.map((s) => (
                    <TableRow key={keyOf(s)}>
                      {canDraft && (
                        <TableCell>
                          <input
                            type="checkbox"
                            aria-label={`Select ${nameOf(s)}`}
                            disabled={!s.supplier}
                            checked={!!selected[keyOf(s)]}
                            onChange={(e) => setSelected({ ...selected, [keyOf(s)]: e.target.checked })}
                          />
                        </TableCell>
                      )}
                      <TableCell>
                        <Link to={`/app/products/${s.product.id}`} className="underline-offset-4 hover:underline">
                          {s.product.sku}
                        </Link>{' '}
                        {s.product.name}
                      </TableCell>
                      <TableCell>{s.warehouse.code}</TableCell>
                      <TableCell className="max-w-md text-sm">{s.explanation}</TableCell>
                      <TableCell>{s.supplier?.name ?? <span className="text-muted-foreground">No supplier</span>}</TableCell>
                      <TableCell>
                        {canDraft ? (
                          <Input
                            aria-label={`Order quantity for ${nameOf(s)}`}
                            inputMode="decimal"
                            value={quantityFor(s)}
                            disabled={!s.supplier}
                            onChange={(e) => setQuantities({ ...quantities, [keyOf(s)]: e.target.value })}
                          />
                        ) : (
                          formatQuantity(s.suggestedQuantity, s.product.unit)
                        )}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader className="flex flex-row items-center justify-between gap-2">
          <CardTitle>Rules</CardTitle>
          {canRules && <Button variant="outline" onClick={() => setEditing('new')}>New rule</Button>}
        </CardHeader>
        <CardContent>
          {rules.isPending ? (
            <ListSkeleton rows={3} />
          ) : rules.isError ? (
            <ErrorState error={rules.error} onRetry={() => void rules.refetch()} />
          ) : rules.data.length === 0 ? (
            <EmptyState
              title="No reorder rules yet."
              description="Add a minimum and maximum for the products you never want to run out of."
            />
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Product</TableHead>
                  <TableHead>Warehouse</TableHead>
                  <TableHead className="text-right">Minimum</TableHead>
                  <TableHead className="text-right">Maximum</TableHead>
                  <TableHead>Preferred supplier</TableHead>
                  {canRules && <TableHead className="text-right">Actions</TableHead>}
                </TableRow>
              </TableHeader>
              <TableBody>
                {rules.data.map((r) => (
                  <TableRow key={r.id}>
                    <TableCell>
                      {r.product.sku} {r.product.name}
                    </TableCell>
                    <TableCell>{r.warehouse.code}</TableCell>
                    <TableCell className="text-right tabular-nums">{formatQuantity(r.minQuantity)}</TableCell>
                    <TableCell className="text-right tabular-nums">{formatQuantity(r.maxQuantity)}</TableCell>
                    <TableCell>{r.supplier?.name ?? '—'}</TableCell>
                    {canRules && (
                      <TableCell className="space-x-2 text-right">
                        <Button size="sm" variant="outline" aria-label={`Edit rule for ${nameOf(r)}`} onClick={() => setEditing(r)}>
                          Edit
                        </Button>
                        <Button
                          size="sm"
                          variant="outline"
                          aria-label={`Delete rule for ${nameOf(r)}`}
                          onClick={() => {
                            setError(null)
                            setDeleting(r)
                          }}
                        >
                          Delete
                        </Button>
                      </TableCell>
                    )}
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>
      {editing && (
        <ReorderRuleDialog rule={editing === 'new' ? undefined : editing} onClose={() => setEditing(null)} />
      )}
      <ConfirmDialog
        open={deleting !== null}
        title={`Delete the rule for ${deleting ? nameOf(deleting) : ''}?`}
        description="The product stops appearing in reorder suggestions for that warehouse."
        confirmLabel="Delete rule"
        busy={busy}
        error={error}
        onCancel={() => setDeleting(null)}
        onConfirm={() => deleting && void remove(deleting)}
      />
    </div>
  )
}
```

Append to `TABS`: `{ to: '/app/inventory/reorder', label: 'Reorder', anyOf: [PERMISSIONS.stockRead] },` and to `inventoryChildren` a `reorder` route rendering `ReorderPage` behind `RequirePermission anyOf={[PERMISSIONS.stockRead]}`.

- [ ] **Step 4: A party's orders**

`frontend/src/features/inventory/PartyOrdersPanel.tsx`:

```tsx
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useTenantSession } from '@/features/auth/tenantSession'
import { useApi } from '@/lib/api/ApiContext'
import type { Page, PurchaseOrderSummary, SalesOrderSummary } from '@/lib/api/types'
import { formatMoney } from '@/lib/format'
import { PURCHASE_STATUS_LABELS, SALES_STATUS_LABELS } from './labels'

/** The party's latest purchase orders (as supplier) and sales orders (as customer), when Inventory is on. */
export function PartyOrdersPanel({ partyId }: { partyId: string }) {
  const session = useTenantSession()
  const can = useCan()
  const api = useApi()
  const inventory =
    session.state.status === 'authenticated' && session.state.profile.modules.includes('INVENTORY')
  const canPurchases = inventory && can(PERMISSIONS.purchaseRead)
  const canSales = inventory && can(PERMISSIONS.orderRead)
  const purchases = useQuery({
    queryKey: ['inventory', 'purchase-orders', { supplierId: partyId }],
    queryFn: () => api.get<Page<PurchaseOrderSummary>>(`/purchase-orders?supplierId=${partyId}&size=10`),
    enabled: canPurchases,
  })
  const sales = useQuery({
    queryKey: ['inventory', 'sales-orders', { customerId: partyId }],
    queryFn: () => api.get<Page<SalesOrderSummary>>(`/sales-orders?customerId=${partyId}&size=10`),
    enabled: canSales,
  })
  const bought = canPurchases ? (purchases.data?.items ?? []) : []
  const sold = canSales ? (sales.data?.items ?? []) : []
  if (bought.length === 0 && sold.length === 0) return null
  return (
    <Card role="region" aria-label="Orders">
      <CardHeader>
        <CardTitle>Orders</CardTitle>
      </CardHeader>
      <CardContent className="grid gap-4 text-sm lg:grid-cols-2">
        {bought.length > 0 && (
          <section aria-label="Purchase orders">
            <h3 className="mb-2 font-medium">Purchase orders</h3>
            <ul className="space-y-1">
              {bought.map((o) => (
                <li key={o.id} className="flex flex-wrap gap-2">
                  <Link to={`/app/inventory/purchase-orders/${o.id}`} className="underline-offset-4 hover:underline">
                    {o.number}
                  </Link>
                  <span className="text-muted-foreground">{PURCHASE_STATUS_LABELS[o.status]}</span>
                  <span className="tabular-nums">{formatMoney(o.total, o.currency)}</span>
                </li>
              ))}
            </ul>
          </section>
        )}
        {sold.length > 0 && (
          <section aria-label="Sales orders">
            <h3 className="mb-2 font-medium">Sales orders</h3>
            <ul className="space-y-1">
              {sold.map((o) => (
                <li key={o.id} className="flex flex-wrap gap-2">
                  <Link to={`/app/inventory/sales-orders/${o.id}`} className="underline-offset-4 hover:underline">
                    {o.number}
                  </Link>
                  <span className="text-muted-foreground">{SALES_STATUS_LABELS[o.status]}</span>
                  <span className="tabular-nums">{formatMoney(o.total, o.currency)}</span>
                </li>
              ))}
            </ul>
          </section>
        )}
      </CardContent>
    </Card>
  )
}
```

In `PartyDetailPage.tsx`, render `<PartyOrdersPanel partyId={p.id} />` just before the `{/* record panels */}` comment. In `Customer360Page.tsx`, render `<PartyOrdersPanel partyId={party.id} />` right after the "Converted leads" section and before `SubjectTasksPanel`. Existing tests of both pages sign in without the INVENTORY module, so the panel stays out of them and needs no new routes there.

- [ ] **Step 5: Run the frontend checks**

Run: `cd frontend && npx prettier --write src && npm run format:check && npm run lint && npm run typecheck && npm test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src
git commit -m "feat(frontend): explained reorder suggestions to draft purchase orders, rules, and a party's orders"
```

---
### Task 12: The end-to-end journey and the README

**Files:**
- Create: `frontend/e2e/inventory.spec.ts`
- Modify: `README.md`

**Interfaces:**
- Consumes: everything above, through the UI only; `newWorkspace`, `signUpAndVerify`, `signIn` from `frontend/e2e/support/workspace.ts`.
- Produces: the spec §7 E2E journey.

- [ ] **Step 1: Write the journey**

`frontend/e2e/inventory.spec.ts`:

```ts
import { expect, test, type Page } from '@playwright/test'
import { newWorkspace, signIn, signUpAndVerify } from './support/workspace'

async function newOrganization(page: Page, name: string) {
  await page.getByRole('navigation', { name: 'Workspace' }).getByRole('link', { name: 'Directory' }).click()
  await page.getByRole('button', { name: 'New organization' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('Name').fill(name)
  await dialog.getByRole('button', { name: 'Create organization' }).click()
  await expect(page.getByRole('heading', { name })).toBeVisible()
}

function inventoryTab(page: Page, name: string) {
  return page.getByRole('navigation', { name: 'Inventory' }).getByRole('link', { name })
}

test('stock is counted, sold, explained, reordered and received in two parts', async ({ page }) => {
  const ws = newWorkspace('inv')
  await signUpAndVerify(page, ws)
  await signIn(page, ws, ws.email)
  await expect(page.getByRole('heading', { name: 'Welcome, Ada' })).toBeVisible()
  const nav = page.getByRole('navigation', { name: 'Workspace' })

  // Enable Inventory; a supplier and a customer exist in the directory.
  await page.goto('/app/settings/modules')
  await page.getByRole('switch', { name: 'Inventory' }).click()
  await expect(page.getByText('Inventory enabled.')).toBeVisible()
  await newOrganization(page, 'Konkan Supplies')
  await newOrganization(page, 'Deccan Retail')

  // A stocked product with a list price, counted in at 50.
  await nav.getByRole('link', { name: 'Products' }).click()
  await page.getByRole('button', { name: 'New product' }).click()
  let dialog = page.getByRole('dialog')
  await dialog.getByLabel('SKU').fill('W-1')
  await dialog.getByLabel('Name').fill('Widget')
  await dialog.getByLabel('List price').fill('12.50')
  await dialog.getByLabel('Currency').fill('USD')
  await dialog.getByRole('button', { name: 'Create product' }).click()
  await expect(page.getByRole('heading', { name: 'Widget' })).toBeVisible()
  const stock = page.getByRole('region', { name: 'Stock' })
  await stock.getByRole('button', { name: 'Count' }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByLabel('Warehouse').selectOption({ label: 'MAIN · Main warehouse' })
  await dialog.getByLabel('Counted quantity').fill('50')
  await dialog.getByLabel('Reason').fill('Opening count')
  await dialog.getByRole('button', { name: 'Save count' }).click()
  await expect(page.getByText('Count saved.')).toBeVisible()
  await expect(stock.getByRole('row', { name: /MAIN/ }).first()).toContainText('50')

  // A reorder rule with a preferred supplier.
  await nav.getByRole('link', { name: 'Inventory' }).click()
  await inventoryTab(page, 'Reorder').click()
  await page.getByRole('button', { name: 'New rule' }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByLabel('Product', { exact: true }).selectOption({ label: 'W-1 · Widget' })
  await dialog.getByLabel('Warehouse').selectOption({ label: 'MAIN · Main warehouse' })
  await dialog.getByLabel('Minimum').fill('20')
  await dialog.getByLabel('Maximum').fill('60')
  await dialog.getByLabel('Preferred supplier', { exact: true }).selectOption({ label: 'Konkan Supplies' })
  await dialog.getByRole('button', { name: 'Save rule' }).click()
  await expect(page.getByText('Reorder rule saved.')).toBeVisible()
  await expect(page.getByText('Nothing to reorder.')).toBeVisible()

  // Sell 38 and ship them: below the minimum now.
  await inventoryTab(page, 'Sales orders').click()
  await page.getByRole('button', { name: 'New sales order' }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByLabel('Customer', { exact: true }).selectOption({ label: 'Deccan Retail' })
  await dialog.getByLabel('Warehouse').selectOption({ label: 'MAIN · Main warehouse' })
  await dialog.getByLabel('Line 1 product', { exact: true }).selectOption({ label: 'W-1 · Widget' })
  await dialog.getByLabel('Line 1 quantity').fill('38')
  await dialog.getByRole('button', { name: 'Create sales order' }).click()
  await expect(page.getByRole('heading', { name: 'SO-00001' })).toBeVisible()
  await page.getByRole('button', { name: 'Confirm' }).click()
  await expect(page.getByText('SO-00001 confirmed.')).toBeVisible()
  await page.getByRole('button', { name: 'Fulfil' }).click()
  await expect(page.getByText('SO-00001 fulfilled.')).toBeVisible()

  // The suggestion explains itself and becomes a draft purchase order.
  await inventoryTab(page, 'Reorder').click()
  await expect(
    page.getByText(
      '12 available, 0 on order, below the minimum of 20; 38 used in the last 30 days (about 9 days of cover); order 48 to reach 60',
    ),
  ).toBeVisible()
  await page.getByLabel('Select W-1 at MAIN').check()
  await page.getByRole('button', { name: 'Create purchase orders' }).click()
  await page.getByRole('link', { name: 'PO-00001' }).click()

  // Place it and receive it in two parts.
  await expect(page.getByRole('heading', { name: 'PO-00001' })).toBeVisible()
  await page.getByRole('button', { name: 'Place order' }).click()
  await expect(page.getByText('PO-00001 placed.')).toBeVisible()
  await page.getByRole('button', { name: 'Receive' }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByLabel('Receive W-1 (48 due)').fill('20')
  await dialog.getByRole('button', { name: 'Record receipt' }).click()
  await expect(page.getByText('Partly received')).toBeVisible()
  await page.getByRole('button', { name: 'Receive' }).click()
  dialog = page.getByRole('dialog')
  await expect(dialog.getByLabel('Receive W-1 (28 due)')).toHaveValue('28')
  await dialog.getByRole('button', { name: 'Record receipt' }).click()
  await expect(page.getByText('Received', { exact: true })).toBeVisible()

  // Stock and the ledger show every step: 50 − 38 + 20 + 28 = 60.
  await page.getByRole('link', { name: 'W-1' }).first().click()
  await expect(page.getByRole('heading', { name: 'Widget' })).toBeVisible()
  await expect(stock.getByRole('row', { name: /MAIN/ }).first()).toContainText('60')
  for (const reference of ['PO-00001', 'SO-00001', 'Opening count']) {
    await expect(stock.getByText(reference).first()).toBeVisible()
  }
  await expect(stock.getByText('Receipt')).toHaveCount(2)
  await expect(stock.getByText('Issue')).toHaveCount(1)
})
```

Selectors to keep in mind while running it:
- `getByLabel('Product', { exact: true })` and the other `exact` options avoid the pickers' "Find …" search boxes.
- The order pages' toasts (`… confirmed.`, `… placed.`) come from `useOrderAction`'s success texts in Tasks 9–10.
- The last check counts movement rows by their kind label (exact text "Receipt" and "Issue"): two receipts, one issue.

- [ ] **Step 2: Run it**

Run: `make e2e` (Docker running). If Playwright's bundled Chromium isn't installed, run with the local Chrome the way the
Phase 5 E2E run did — a temporary config with `use: { channel: 'chrome' }` — and delete the temporary config afterwards.
Expected: every journey passes, including `inventory.spec.ts`.

- [ ] **Step 3: README**

In `README.md`, add after the CRM spec line at the top:

```markdown
- Inventory (Phase 6): `docs/superpowers/specs/2026-10-08-inventory-mvp-design.md`
```

and after the `## CRM (Phase 5)` section:

```markdown
## Inventory (Phase 6)
Switch the module on under **Settings → Modules**; every Inventory permission switches off with it (ADR-0011).
- **Warehouses:** Settings → Warehouses. Every workspace starts with MAIN and keeps at least one active warehouse; a
  warehouse that holds stock or has open orders can't be archived.
- **Stock:** on hand, reserved, available and on order per product and warehouse. Counts set on hand to what you
  found; transfers move available stock. Every change is one row in an append-only ledger, shown on the product page.
- **Purchase orders:** draft → place → receive, in as many parts as deliveries arrive. Placing makes the party a
  supplier.
- **Sales orders:** confirming reserves every line or lists what is short; fulfilling ships it; cancelling releases
  it. Confirming makes the party a customer.
- **Reorder:** per product and warehouse, a minimum and maximum with a preferred supplier. Suggestions explain
  themselves ("12 available, 0 on order, below the minimum of 20; 38 used in the last 30 days …") and become draft
  purchase orders a person reviews and places.
```

In `## Tests`, replace `and a
  platform admin suspending and reactivating a workspace.` with:

```markdown
a platform admin suspending and reactivating a workspace, a lead becoming a customer with a won deal, and stock
  counted, sold, explained by a reorder suggestion and received in two parts.
```

- [ ] **Step 4: Commit**

```bash
git add frontend/e2e/inventory.spec.ts README.md
git commit -m "test(e2e): inventory journey from opening count to a reorder received in two parts; README"
```
