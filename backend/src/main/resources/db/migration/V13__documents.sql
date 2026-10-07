-- Documents (D10) attached to a subject record. Contents live in document_contents behind the DocumentStorage port
-- (ADR-0009: PostgreSQL now, object storage at cloud deployment). Max 10 MB per file; per-plan quota.
CREATE TABLE documents (
    id            uuid PRIMARY KEY,
    tenant_id     uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    subject_type  text NOT NULL CHECK (subject_type ~ '^[A-Z][A-Z_]{1,29}$'),
    subject_id    uuid NOT NULL,
    file_name     text NOT NULL CHECK (length(file_name) BETWEEN 1 AND 255),
    content_type  text NOT NULL CHECK (length(content_type) BETWEEN 3 AND 127),
    size_bytes    bigint NOT NULL CHECK (size_bytes BETWEEN 1 AND 10485760),
    sha256        text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    uploaded_by   uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at    timestamptz NOT NULL,
    UNIQUE (tenant_id, id)
);
CREATE INDEX documents_subject_idx ON documents (tenant_id, subject_type, subject_id, created_at DESC);

ALTER TABLE documents ENABLE ROW LEVEL SECURITY;
ALTER TABLE documents FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON documents
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE document_contents (
    document_id  uuid PRIMARY KEY,
    tenant_id    uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    content      bytea NOT NULL,
    FOREIGN KEY (tenant_id, document_id) REFERENCES documents (tenant_id, id) ON DELETE CASCADE
);

ALTER TABLE document_contents ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_contents FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON document_contents
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

UPDATE plans SET limits = limits || '{"maxStorageMb": 100}'::jsonb WHERE code = 'FREE';
UPDATE plans SET limits = limits || '{"maxStorageMb": 1000}'::jsonb WHERE code = 'STARTER';
UPDATE plans SET limits = limits || '{"maxStorageMb": 10000}'::jsonb WHERE code = 'BUSINESS';

INSERT INTO permissions (code, module_code, description) VALUES
    ('collaboration.document.read',   NULL, 'View and download documents'),
    ('collaboration.document.manage', NULL, 'Upload and delete documents');
SELECT grant_to_system_roles(ARRAY['collaboration.document.read', 'collaboration.document.manage']);
