-- Canonical parties (ADR-0008): one row per person or organization; the business roles a party plays live in
-- party_roles. Person- and organization-only columns are enforced by CHECKs.
CREATE TABLE parties (
    id                uuid PRIMARY KEY,
    tenant_id         uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    kind              text NOT NULL CHECK (kind IN ('PERSON', 'ORGANIZATION')),
    name              text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 200),
    name_key          text NOT NULL CHECK (length(name_key) BETWEEN 1 AND 200),
    first_name        text CHECK (first_name IS NULL OR length(btrim(first_name)) BETWEEN 1 AND 80),
    last_name         text CHECK (last_name IS NULL OR length(btrim(last_name)) BETWEEN 1 AND 80),
    job_title         text CHECK (job_title IS NULL OR length(btrim(job_title)) BETWEEN 1 AND 100),
    organization_id   uuid,
    email             text CHECK (email IS NULL OR (email = lower(btrim(email)) AND length(email) BETWEEN 3 AND 254)),
    phone             text CHECK (phone IS NULL OR length(phone) BETWEEN 3 AND 40),
    domain            text CHECK (domain IS NULL OR (domain = lower(domain) AND length(domain) BETWEEN 3 AND 253)),
    website           text CHECK (website IS NULL OR length(website) <= 255),
    duplicate_reason  text CHECK (duplicate_reason IS NULL OR length(btrim(duplicate_reason)) BETWEEN 1 AND 500),
    archived_at       timestamptz,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    version           bigint NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    -- composite: a person can only belong to an organization of the same tenant (FK checks ignore RLS)
    FOREIGN KEY (tenant_id, organization_id) REFERENCES parties (tenant_id, id),
    CHECK (organization_id IS NULL OR organization_id <> id),
    CHECK (kind = 'PERSON'
           OR (first_name IS NULL AND last_name IS NULL AND job_title IS NULL AND organization_id IS NULL)),
    CHECK (kind = 'ORGANIZATION' OR (first_name IS NOT NULL AND domain IS NULL AND website IS NULL))
);
CREATE INDEX parties_tenant_name_idx ON parties (tenant_id, lower(name));
CREATE INDEX parties_tenant_key_idx ON parties (tenant_id, kind, name_key);
CREATE INDEX parties_tenant_email_idx ON parties (tenant_id, email) WHERE email IS NOT NULL;
CREATE INDEX parties_tenant_domain_idx ON parties (tenant_id, domain) WHERE domain IS NOT NULL;
CREATE INDEX parties_tenant_org_idx ON parties (tenant_id, organization_id) WHERE organization_id IS NOT NULL;

ALTER TABLE parties ENABLE ROW LEVEL SECURITY;
ALTER TABLE parties FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON parties
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE party_roles (
    id               uuid PRIMARY KEY,
    tenant_id        uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    party_id         uuid NOT NULL,
    role             text NOT NULL CHECK (role IN ('CUSTOMER', 'SUPPLIER', 'EMPLOYEE')),
    status           text NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    since            date,
    employee_number  text CHECK (employee_number IS NULL OR length(btrim(employee_number)) BETWEEN 1 AND 40),
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    version          bigint NOT NULL DEFAULT 0,
    FOREIGN KEY (tenant_id, party_id) REFERENCES parties (tenant_id, id) ON DELETE CASCADE,
    UNIQUE (tenant_id, party_id, role),
    CHECK (role = 'EMPLOYEE' OR employee_number IS NULL)
);
CREATE UNIQUE INDEX party_roles_employee_number_uq ON party_roles (tenant_id, lower(employee_number))
    WHERE employee_number IS NOT NULL;
CREATE INDEX party_roles_tenant_role_idx ON party_roles (tenant_id, role, status);

ALTER TABLE party_roles ENABLE ROW LEVEL SECURITY;
ALTER TABLE party_roles FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON party_roles
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Grants permission codes to every workspace's system roles (TENANT_OWNER, TENANT_ADMIN). FORCE RLS applies to the
-- owner too, so it visits one tenant at a time with app.tenant_id set transaction-locally, honouring the policies.
-- Not executable by the runtime role: PostgreSQL grants EXECUTE on new functions to PUBLIC, and V0_2's schema-scoped
-- default-privilege REVOKE cannot remove that built-in default, so it is revoked explicitly below.
CREATE FUNCTION grant_to_system_roles(codes text[]) RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    t uuid;
BEGIN
    FOR t IN SELECT id FROM tenants LOOP
        PERFORM set_config('app.tenant_id', t::text, true);
        INSERT INTO role_permissions (role_id, permission_code)
        SELECT r.id, c FROM roles r CROSS JOIN unnest(codes) AS c
        WHERE r.system
        ON CONFLICT DO NOTHING;
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END
$$;
REVOKE EXECUTE ON FUNCTION grant_to_system_roles(text[]) FROM PUBLIC;

INSERT INTO permissions (code, module_code, description) VALUES
    ('directory.party.read',      NULL, 'View people and organizations'),
    ('directory.party.manage',    NULL, 'Create, edit and archive people and organizations; mark customers and suppliers'),
    ('directory.employee.read',   NULL, 'View employee records'),
    ('directory.employee.manage', NULL, 'Manage employee records');
SELECT grant_to_system_roles(ARRAY['directory.party.read', 'directory.party.manage',
                                   'directory.employee.read', 'directory.employee.manage']);
