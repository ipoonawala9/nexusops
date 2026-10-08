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
