-- CRM (Phase 5): module permissions (D15) and the workspace's sales pipeline (D5).
INSERT INTO permissions (code, module_code, description) VALUES
    ('crm.lead.read',          'CRM', 'View leads'),
    ('crm.lead.manage',        'CRM', 'Create, edit, qualify, import and convert leads'),
    ('crm.opportunity.read',   'CRM', 'View opportunities and the pipeline'),
    ('crm.opportunity.manage', 'CRM', 'Create, edit and move opportunities'),
    ('crm.pipeline.manage',    'CRM', 'Configure pipeline stages');
UPDATE permissions SET description = 'View customers and their sales history' WHERE code = 'crm.customer.read';
SELECT grant_to_system_roles(ARRAY['crm.lead.read', 'crm.lead.manage', 'crm.opportunity.read',
                                   'crm.opportunity.manage', 'crm.pipeline.manage']);

-- V3's unused seeds: creating customers is a directory job (ADR-0008). role_permissions is RLS-protected through
-- roles, so its rows are removed tenant by tenant, the way grant_to_system_roles adds them.
DO $$
DECLARE
    t uuid;
BEGIN
    FOR t IN SELECT id FROM tenants LOOP
        PERFORM set_config('app.tenant_id', t::text, true);
        DELETE FROM role_permissions
        WHERE permission_code IN ('crm.customer.create', 'crm.customer.update', 'crm.customer.delete');
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END
$$;
DELETE FROM permissions WHERE code IN ('crm.customer.create', 'crm.customer.update', 'crm.customer.delete');

-- Ordered stages; WON and LOST are single, fixed, and always last (board order: kind, then position).
CREATE TABLE pipeline_stages (
    id           uuid PRIMARY KEY,
    tenant_id    uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    name         text NOT NULL CHECK (name = btrim(name) AND length(name) BETWEEN 1 AND 60),
    name_key     text NOT NULL,
    position     integer NOT NULL CHECK (position >= 0),
    probability  integer NOT NULL CHECK (probability BETWEEN 0 AND 100),
    kind         text NOT NULL CHECK (kind IN ('OPEN', 'WON', 'LOST')),
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    version      bigint NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, name_key)
);
CREATE UNIQUE INDEX pipeline_stages_one_won_uq ON pipeline_stages (tenant_id) WHERE kind = 'WON';
CREATE UNIQUE INDEX pipeline_stages_one_lost_uq ON pipeline_stages (tenant_id) WHERE kind = 'LOST';

ALTER TABLE pipeline_stages ENABLE ROW LEVEL SECURITY;
ALTER TABLE pipeline_stages FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pipeline_stages
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Default stages for existing workspaces (new ones get them from PipelineSetup on WorkspaceRegistered).
DO $$
DECLARE
    t uuid;
BEGIN
    FOR t IN SELECT id FROM tenants LOOP
        PERFORM set_config('app.tenant_id', t::text, true);
        INSERT INTO pipeline_stages (id, tenant_id, name, name_key, position, probability, kind, created_at, updated_at)
        SELECT gen_random_uuid(), t, s.name, lower(s.name), s.position, s.probability, s.kind, now(), now()
        FROM (VALUES ('Prospecting', 0, 10, 'OPEN'), ('Qualification', 1, 25, 'OPEN'), ('Proposal', 2, 50, 'OPEN'),
                     ('Negotiation', 3, 75, 'OPEN'), ('Won', 0, 100, 'WON'), ('Lost', 0, 0, 'LOST'))
             AS s (name, position, probability, kind);
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END
$$;
