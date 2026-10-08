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
