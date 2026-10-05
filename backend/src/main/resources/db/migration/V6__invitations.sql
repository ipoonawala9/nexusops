CREATE TABLE invitations (
    id                uuid PRIMARY KEY,
    tenant_id         uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    email             text NOT NULL CHECK (email = lower(btrim(email)) AND length(email) BETWEEN 3 AND 254),
    role_id           uuid NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    token_hash        char(64) NOT NULL UNIQUE,
    invited_by        uuid REFERENCES users (id) ON DELETE SET NULL,
    expires_at        timestamptz NOT NULL,
    accepted_at       timestamptz,
    accepted_user_id  uuid REFERENCES users (id) ON DELETE SET NULL,
    revoked_at        timestamptz,
    created_at        timestamptz NOT NULL,
    CHECK (accepted_at IS NULL OR revoked_at IS NULL)
);
-- At most one open (not accepted, not revoked) invitation per email per tenant.
CREATE UNIQUE INDEX invitations_open_email_uq ON invitations (tenant_id, email)
    WHERE accepted_at IS NULL AND revoked_at IS NULL;
CREATE INDEX invitations_tenant_created_idx ON invitations (tenant_id, created_at DESC);

ALTER TABLE invitations ENABLE ROW LEVEL SECURITY;
ALTER TABLE invitations FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON invitations
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
