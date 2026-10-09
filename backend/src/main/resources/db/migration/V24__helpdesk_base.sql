-- HelpDesk (Phase 7): module permissions (D16), ticket numbers, categories (D5) and SLA policies (D8).
INSERT INTO permissions (code, module_code, description) VALUES
    ('helpdesk.ticket.manage',   'HELPDESK', 'Create and edit tickets, reply, and add notes'),
    ('helpdesk.settings.manage', 'HELPDESK', 'Manage ticket categories and SLA policies'),
    ('helpdesk.article.read',    'HELPDESK', 'Read knowledge base articles'),
    ('helpdesk.article.manage',  'HELPDESK', 'Write, publish and archive knowledge base articles');
UPDATE permissions SET description = 'View tickets and the HelpDesk dashboard' WHERE code = 'helpdesk.ticket.read';
UPDATE permissions SET description = 'Assign tickets to team members' WHERE code = 'helpdesk.ticket.assign';
UPDATE permissions SET description = 'Move tickets between statuses: pending, resolved, closed, reopened'
WHERE code = 'helpdesk.ticket.resolve';
SELECT grant_to_system_roles(ARRAY['helpdesk.ticket.read', 'helpdesk.ticket.manage', 'helpdesk.ticket.assign',
                                   'helpdesk.ticket.resolve', 'helpdesk.settings.manage', 'helpdesk.article.read',
                                   'helpdesk.article.manage']);

-- Ticket numbers share the per-tenant document sequences (D2).
ALTER TABLE number_sequences DROP CONSTRAINT number_sequences_kind_check;
ALTER TABLE number_sequences ADD CONSTRAINT number_sequences_kind_check
    CHECK (kind IN ('PURCHASE_ORDER', 'SALES_ORDER', 'TICKET'));

CREATE TABLE ticket_categories (
    id                   uuid PRIMARY KEY,
    tenant_id            uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    name                 text NOT NULL CHECK (name = btrim(name) AND length(name) BETWEEN 1 AND 80),
    name_key             text NOT NULL,
    description          text CHECK (description IS NULL OR length(description) <= 500),
    default_assignee_id  uuid REFERENCES users (id) ON DELETE SET NULL,
    position             integer NOT NULL CHECK (position >= 0),
    archived_at          timestamptz,
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    version              bigint NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, name_key)
);
ALTER TABLE ticket_categories ENABLE ROW LEVEL SECURITY;
ALTER TABLE ticket_categories FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ticket_categories
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE sla_policies (
    id                      uuid PRIMARY KEY,
    tenant_id               uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    priority                text NOT NULL CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    first_response_minutes  integer NOT NULL CHECK (first_response_minutes BETWEEN 1 AND 86400),
    resolution_minutes      integer NOT NULL CHECK (resolution_minutes BETWEEN 1 AND 86400),
    updated_at              timestamptz NOT NULL,
    version                 bigint NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, priority),
    CHECK (resolution_minutes >= first_response_minutes)
);
ALTER TABLE sla_policies ENABLE ROW LEVEL SECURITY;
ALTER TABLE sla_policies FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sla_policies
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Existing workspaces get the default categories and policies (new ones get them from HelpDeskSetup).
DO $$
DECLARE
    t uuid;
BEGIN
    FOR t IN SELECT id FROM tenants LOOP
        PERFORM set_config('app.tenant_id', t::text, true);
        INSERT INTO ticket_categories (id, tenant_id, name, name_key, position, created_at, updated_at) VALUES
            (gen_random_uuid(), t, 'General', 'general', 0, now(), now()),
            (gen_random_uuid(), t, 'Billing', 'billing', 1, now(), now()),
            (gen_random_uuid(), t, 'Product issue', 'product issue', 2, now(), now()),
            (gen_random_uuid(), t, 'Delivery', 'delivery', 3, now(), now());
        INSERT INTO sla_policies (id, tenant_id, priority, first_response_minutes, resolution_minutes, updated_at) VALUES
            (gen_random_uuid(), t, 'URGENT', 60, 240, now()),
            (gen_random_uuid(), t, 'HIGH', 240, 1440, now()),
            (gen_random_uuid(), t, 'NORMAL', 480, 2880, now()),
            (gen_random_uuid(), t, 'LOW', 1440, 7200, now());
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END
$$;
