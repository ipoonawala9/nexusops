CREATE TABLE audit_events (
    id              uuid PRIMARY KEY,
    tenant_id       uuid REFERENCES tenants (id) ON DELETE CASCADE,
    actor_type      text NOT NULL CHECK (actor_type IN ('USER', 'ANONYMOUS', 'SYSTEM', 'PLATFORM')),
    actor_id        uuid,
    action          text NOT NULL CHECK (action ~ '^[A-Za-z]+$'),
    entity_type     text,
    entity_id       text,
    occurred_at     timestamptz NOT NULL,
    ip              text,
    user_agent      text,
    request_id      text,
    correlation_id  text,
    before          jsonb,
    after           jsonb,
    metadata        jsonb
);
CREATE INDEX audit_events_tenant_time_idx ON audit_events (tenant_id, occurred_at DESC);
CREATE INDEX audit_events_tenant_action_idx ON audit_events (tenant_id, action);

ALTER TABLE audit_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_events FORCE ROW LEVEL SECURITY;
CREATE POLICY audit_read ON audit_events FOR SELECT
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY audit_insert ON audit_events FOR INSERT
    WITH CHECK (tenant_id IS NULL OR tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Append-only (ADR-0005): no UPDATE/DELETE grant, and a trigger blocks them even for the owner.
REVOKE UPDATE, DELETE, TRUNCATE ON audit_events FROM nexusops_app;
CREATE FUNCTION audit_events_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_events is append-only';
END $$;
-- Statement-level: RLS has no UPDATE/DELETE policy, so a row-level trigger would never see a row;
-- the statement trigger fires regardless and rejects the attempt outright.
CREATE TRIGGER audit_events_no_update BEFORE UPDATE OR DELETE OR TRUNCATE ON audit_events
    FOR EACH STATEMENT EXECUTE FUNCTION audit_events_immutable();
