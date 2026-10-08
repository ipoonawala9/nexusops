package com.nexusops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
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

    private final AtomicInteger counter = new AtomicInteger(100);

    private UUID freshProduct() {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        OwnerJdbc.ownerAs(tenantB).update("insert into products (id, tenant_id, sku, name, kind, unit, created_at, "
                + "updated_at) values (?, ?, ?, 'Fresh', 'GOODS', 'each', ?, ?)", id, tenantB,
                "F-" + id.toString().substring(0, 8), now, now);
        return id;
    }

    private UUID freshWarehouse() {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        String code = "W" + id.toString().substring(0, 8);
        OwnerJdbc.ownerAs(tenantB).update("insert into warehouses (id, tenant_id, code, code_key, name, created_at, "
                + "updated_at) values (?, ?, ?, ?, 'Fresh', ?, ?)", id, tenantB, code, code.toLowerCase(), now, now);
        return id;
    }

    /** A row that is valid for tenant B in every respect except who inserts it: real FK targets, fresh keys. */
    private void insertTenantBRow(JdbcTemplate as, String table) {
        Timestamp now = Timestamp.from(Instant.now());
        UUID id = UUID.randomUUID();
        int n = counter.incrementAndGet();
        switch (table) {
            case "number_sequences" -> as.update("insert into number_sequences (tenant_id, kind, next_value) "
                    + "values (?, 'SALES_ORDER', 1)", tenantB);
            case "warehouses" -> as.update("insert into warehouses (id, tenant_id, code, code_key, name, created_at, "
                    + "updated_at) values (?, ?, ?, ?, 'Extra', ?, ?)", id, tenantB, "X" + n, "x" + n, now, now);
            case "stock_levels" -> as.update("insert into stock_levels (id, tenant_id, product_id, warehouse_id, "
                    + "updated_at) values (?, ?, ?, ?, ?)", id, tenantB, freshProduct(), warehouse, now);
            case "stock_movements" -> as.update("insert into stock_movements (id, tenant_id, product_id, warehouse_id, "
                    + "kind, quantity, on_hand_after, reference_type, reference_id, occurred_at) "
                    + "values (?, ?, ?, ?, 'ADJUSTMENT', 1, 6, 'ADJUSTMENT', ?, ?)", id, tenantB, product, warehouse,
                    UUID.randomUUID(), now);
            case "purchase_orders" -> as.update("insert into purchase_orders (id, tenant_id, number, supplier_id, "
                    + "warehouse_id, status, currency, created_at, updated_at) "
                    + "values (?, ?, ?, ?, ?, 'DRAFT', 'USD', ?, ?)", id, tenantB,
                    String.format("PO-%05d", 90000 + n), party, warehouse, now, now);
            case "purchase_order_lines" -> as.update("insert into purchase_order_lines (id, tenant_id, order_id, "
                    + "line_no, product_id, quantity, unit_cost) values (?, ?, ?, ?, ?, 1, 1)", id, tenantB,
                    purchaseOrder, n, freshProduct());
            case "sales_orders" -> as.update("insert into sales_orders (id, tenant_id, number, customer_id, "
                    + "warehouse_id, status, currency, created_at, updated_at) "
                    + "values (?, ?, ?, ?, ?, 'DRAFT', 'USD', ?, ?)", id, tenantB,
                    String.format("SO-%05d", 90000 + n), party, warehouse, now, now);
            case "sales_order_lines" -> as.update("insert into sales_order_lines (id, tenant_id, order_id, line_no, "
                    + "product_id, quantity, unit_price) values (?, ?, ?, ?, ?, 1, 1)", id, tenantB, salesOrder, n,
                    freshProduct());
            case "reorder_rules" -> as.update("insert into reorder_rules (id, tenant_id, product_id, warehouse_id, "
                    + "min_quantity, max_quantity, created_at, updated_at) values (?, ?, ?, ?, 1, 2, ?, ?)", id,
                    tenantB, freshProduct(), freshWarehouse(), now, now);
            default -> throw new IllegalArgumentException(table);
        }
    }

    /** The failure must be PostgreSQL's insufficient-privilege / row-level-security error (SQLState 42501). */
    private static void assertDeniedByPostgres(ThrowingCallable call, String what) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).as(what).isInstanceOf(DataAccessException.class);
        Throwable cause = thrown;
        while (cause != null && !(cause instanceof SQLException)) {
            cause = cause.getCause();
        }
        assertThat(cause).as(what + " (SQL cause)").isNotNull();
        assertThat(((SQLException) cause).getSQLState()).as(what).isEqualTo("42501");
    }

    @Test
    void insertsIntoAnotherTenantAreRejectedByRowLevelSecurity() {
        for (String table : TABLES) {
            assertDeniedByPostgres(() -> insertTenantBRow(app(tenantA.toString()), table), table + " as tenant A");
            assertDeniedByPostgres(() -> insertTenantBRow(app(""), table), table + " without tenant context");
            // positive control: the very same insert is valid for tenant B, so the rejections above are RLS only
            insertTenantBRow(app(tenantB.toString()), table);
            if (table.equals("number_sequences")) {
                OwnerJdbc.ownerAs(tenantB).update("delete from number_sequences where tenant_id = ? "
                        + "and kind = 'SALES_ORDER'", tenantB);
            }
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
        assertDeniedByPostgres(() -> app(tenantB.toString()).update("update stock_movements set quantity = 99 "
                + "where id = ?", movement), "update of the ledger");
        assertDeniedByPostgres(() -> app(tenantB.toString()).update("delete from stock_movements where id = ?",
                movement), "delete from the ledger");
        assertThat(OwnerJdbc.ownerAs(tenantB).queryForObject("select quantity from stock_movements where id = ?",
                java.math.BigDecimal.class, movement)).isEqualByComparingTo("5");
    }
}
