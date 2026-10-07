-- Canonical products (Phase 4). SKUs are identifiers: unique per tenant ignoring case, never reused (archived
-- products keep theirs).
CREATE TABLE products (
    id           uuid PRIMARY KEY,
    tenant_id    uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    sku          text NOT NULL CHECK (sku = btrim(sku) AND length(sku) BETWEEN 1 AND 64),
    name         text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 200),
    description  text CHECK (description IS NULL OR length(description) <= 2000),
    kind         text NOT NULL CHECK (kind IN ('GOODS', 'SERVICE')),
    unit         text NOT NULL CHECK (length(btrim(unit)) BETWEEN 1 AND 20),
    list_price   numeric(19, 4) CHECK (list_price IS NULL OR list_price >= 0),
    currency     text CHECK (currency IS NULL OR currency ~ '^[A-Z]{3}$'),
    archived_at  timestamptz,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    version      bigint NOT NULL DEFAULT 0,
    CHECK ((list_price IS NULL) = (currency IS NULL))
);
CREATE UNIQUE INDEX products_tenant_sku_uq ON products (tenant_id, lower(sku));
CREATE INDEX products_tenant_name_idx ON products (tenant_id, lower(name));

ALTER TABLE products ENABLE ROW LEVEL SECURITY;
ALTER TABLE products FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON products
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

INSERT INTO permissions (code, module_code, description) VALUES
    ('catalog.product.read',   NULL, 'View products'),
    ('catalog.product.manage', NULL, 'Create, edit and archive products');
SELECT grant_to_system_roles(ARRAY['catalog.product.read', 'catalog.product.manage']);
