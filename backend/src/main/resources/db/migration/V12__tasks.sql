-- Tasks (D9), optionally attached to a subject record. completed_at is set exactly when status is DONE.
CREATE TABLE tasks (
    id            uuid PRIMARY KEY,
    tenant_id     uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    title         text NOT NULL CHECK (length(btrim(title)) BETWEEN 1 AND 200),
    description   text CHECK (description IS NULL OR length(description) <= 5000),
    status        text NOT NULL CHECK (status IN ('OPEN', 'IN_PROGRESS', 'DONE', 'CANCELLED')),
    priority      text NOT NULL CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    due_on        date,
    assignee_id   uuid REFERENCES users (id) ON DELETE SET NULL,
    subject_type  text CHECK (subject_type IS NULL OR subject_type ~ '^[A-Z][A-Z_]{1,29}$'),
    subject_id    uuid,
    created_by    uuid REFERENCES users (id) ON DELETE SET NULL,
    completed_at  timestamptz,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    version       bigint NOT NULL DEFAULT 0,
    CHECK ((subject_type IS NULL) = (subject_id IS NULL)),
    CHECK ((status = 'DONE') = (completed_at IS NOT NULL))
);
CREATE INDEX tasks_assignee_idx ON tasks (tenant_id, assignee_id, status);
CREATE INDEX tasks_subject_idx ON tasks (tenant_id, subject_type, subject_id);
CREATE INDEX tasks_due_idx ON tasks (tenant_id, due_on);

ALTER TABLE tasks ENABLE ROW LEVEL SECURITY;
ALTER TABLE tasks FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tasks
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

INSERT INTO permissions (code, module_code, description) VALUES
    ('collaboration.task.read',   NULL, 'View tasks'),
    ('collaboration.task.manage', NULL, 'Create, assign and edit any task');
SELECT grant_to_system_roles(ARRAY['collaboration.task.read', 'collaboration.task.manage']);
