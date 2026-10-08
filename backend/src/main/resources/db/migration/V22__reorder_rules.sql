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
