-- The activity timeline (D8): notes, calls, emails and meetings logged against a subject record. Append-only:
-- corrections are new entries, so the runtime role may not UPDATE or DELETE.
CREATE TABLE activities (
    id            uuid PRIMARY KEY,
    tenant_id     uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    subject_type  text NOT NULL CHECK (subject_type ~ '^[A-Z][A-Z_]{1,29}$'),
    subject_id    uuid NOT NULL,
    type          text NOT NULL CHECK (type IN ('NOTE', 'CALL', 'EMAIL', 'MEETING')),
    summary       text NOT NULL CHECK (length(btrim(summary)) BETWEEN 1 AND 200),
    body          text CHECK (body IS NULL OR length(body) <= 10000),
    occurred_at   timestamptz NOT NULL,
    author_id     uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at    timestamptz NOT NULL
);
CREATE INDEX activities_subject_idx ON activities (tenant_id, subject_type, subject_id, occurred_at DESC);

ALTER TABLE activities ENABLE ROW LEVEL SECURITY;
ALTER TABLE activities FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON activities
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
REVOKE UPDATE, DELETE ON activities FROM nexusops_app;

INSERT INTO permissions (code, module_code, description) VALUES
    ('collaboration.activity.create', NULL, 'Log notes, calls, emails and meetings');
SELECT grant_to_system_roles(ARRAY['collaboration.activity.create']);
