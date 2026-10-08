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
