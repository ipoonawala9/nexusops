CREATE TABLE users (
    id                uuid PRIMARY KEY,
    tenant_id         uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    email             text NOT NULL CHECK (email = lower(btrim(email)) AND length(email) BETWEEN 3 AND 254),
    password_hash     text NOT NULL,
    first_name        text NOT NULL CHECK (length(btrim(first_name)) BETWEEN 1 AND 80),
    last_name         text NOT NULL CHECK (length(btrim(last_name)) BETWEEN 1 AND 80),
    status            text NOT NULL CHECK (status IN ('INVITED', 'ACTIVE', 'DISABLED')),
    email_verified_at timestamptz,
    token_version     integer NOT NULL DEFAULT 0,
    last_login_at     timestamptz,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    version           bigint NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, email),
    UNIQUE (tenant_id, id)
);
CREATE INDEX users_tenant_status_idx ON users (tenant_id, status);

CREATE TABLE refresh_tokens (
    id            uuid PRIMARY KEY,
    tenant_id     uuid NOT NULL,
    user_id       uuid NOT NULL,
    family_id     uuid NOT NULL,
    token_hash    char(64) NOT NULL UNIQUE,
    expires_at    timestamptz NOT NULL,
    revoked_at    timestamptz,
    revoke_reason text CHECK (revoke_reason IN ('ROTATED', 'LOGOUT', 'LOGOUT_ALL', 'REUSE_DETECTED')),
    replaced_by   uuid,
    created_ip    text,
    user_agent    text,
    created_at    timestamptz NOT NULL,
    FOREIGN KEY (tenant_id, user_id) REFERENCES users (tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX refresh_tokens_family_idx ON refresh_tokens (tenant_id, family_id);
CREATE INDEX refresh_tokens_user_idx ON refresh_tokens (tenant_id, user_id);

CREATE TABLE email_verifications (
    id          uuid PRIMARY KEY,
    tenant_id   uuid NOT NULL,
    user_id     uuid NOT NULL,
    token_hash  char(64) NOT NULL UNIQUE,
    expires_at  timestamptz NOT NULL,
    used_at     timestamptz,
    created_at  timestamptz NOT NULL,
    FOREIGN KEY (tenant_id, user_id) REFERENCES users (tenant_id, id) ON DELETE CASCADE
);

DO $$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['users', 'refresh_tokens', 'email_verifications'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY tenant_isolation ON %I
            USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
            WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)$p$, t);
    END LOOP;
END $$;
