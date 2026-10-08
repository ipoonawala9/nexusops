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
