CREATE TABLE plans (
    code        text PRIMARY KEY CHECK (code IN ('FREE', 'STARTER', 'BUSINESS', 'ENTERPRISE')),
    name        text NOT NULL,
    limits      jsonb NOT NULL DEFAULT '{}'::jsonb
);
INSERT INTO plans (code, name, limits) VALUES
    ('FREE',       'Free',       '{"maxUsers": 3,  "maxModules": 2}'),
    ('STARTER',    'Starter',    '{"maxUsers": 15, "maxModules": 4}'),
    ('BUSINESS',   'Business',   '{"maxUsers": 100, "maxModules": 4}'),
    ('ENTERPRISE', 'Enterprise', '{}');

CREATE TABLE modules (
    code  text PRIMARY KEY CHECK (code ~ '^[A-Z_]+$'),
    name  text NOT NULL
);
INSERT INTO modules (code, name) VALUES
    ('CRM', 'CRM'), ('INVENTORY', 'Inventory'), ('HELPDESK', 'HelpDesk'), ('HRMS', 'HRMS');

CREATE TABLE tenants (
    id          uuid PRIMARY KEY,
    slug        text NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9](-?[a-z0-9]){2,39}$'),
    name        text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 120),
    subdomain   text UNIQUE,
    status      text NOT NULL CHECK (status IN ('PENDING_VERIFICATION', 'ACTIVE', 'SUSPENDED')),
    plan_code   text NOT NULL REFERENCES plans (code),
    timezone    text NOT NULL DEFAULT 'UTC',
    locale      text NOT NULL DEFAULT 'en',
    currency    char(3) NOT NULL DEFAULT 'USD' CHECK (currency ~ '^[A-Z]{3}$'),
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    version     bigint NOT NULL DEFAULT 0
);

CREATE TABLE tenant_modules (
    id          uuid PRIMARY KEY,
    tenant_id   uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    module_code text NOT NULL REFERENCES modules (code),
    enabled     boolean NOT NULL DEFAULT false,
    UNIQUE (tenant_id, module_code)
);
ALTER TABLE tenant_modules ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_modules FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_modules
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Global catalogs are read-only for the runtime role; tenants are written by tenancy services.
REVOKE INSERT, UPDATE, DELETE ON plans, modules FROM nexusops_app;
REVOKE DELETE ON tenants FROM nexusops_app;
