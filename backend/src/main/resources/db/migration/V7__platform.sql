-- Platform administration (Plan 4, ADR-0007). Platform tables are RLS-protected and visible ONLY while the
-- platform module has set app.platform_access = 'on' for the current transaction (PlatformAccess).
CREATE TABLE platform_users (
    id               uuid PRIMARY KEY,
    email            text NOT NULL CHECK (email = lower(btrim(email)) AND length(email) BETWEEN 3 AND 254),
    password_hash    text NOT NULL,
    totp_secret_enc  text NOT NULL CHECK (totp_secret_enc LIKE 'v1:%'),
    totp_last_step   bigint NOT NULL DEFAULT 0,
    role             text NOT NULL CHECK (role IN ('PLATFORM_ADMIN', 'PLATFORM_SUPPORT')),
    status           text NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    token_version    integer NOT NULL DEFAULT 0,
    last_login_at    timestamptz,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    version          bigint NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX platform_users_email_uq ON platform_users (email);

CREATE TABLE platform_refresh_tokens (
    id                uuid PRIMARY KEY,
    platform_user_id  uuid NOT NULL REFERENCES platform_users (id) ON DELETE CASCADE,
    family_id         uuid NOT NULL,
    token_hash        char(64) NOT NULL UNIQUE,
    expires_at        timestamptz NOT NULL,
    revoked_at        timestamptz,
    revoke_reason     text CHECK (revoke_reason IN ('ROTATED', 'LOGOUT', 'REVOKED', 'REUSE_DETECTED')),
    replaced_by       uuid,
    token_version     integer NOT NULL,
    created_ip        text,
    user_agent        text,
    created_at        timestamptz NOT NULL
);
CREATE INDEX platform_refresh_tokens_family_idx ON platform_refresh_tokens (family_id);
CREATE INDEX platform_refresh_tokens_user_idx ON platform_refresh_tokens (platform_user_id);

ALTER TABLE platform_users ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_users FORCE ROW LEVEL SECURITY;
CREATE POLICY platform_only ON platform_users
    USING (current_setting('app.platform_access', true) = 'on')
    WITH CHECK (current_setting('app.platform_access', true) = 'on');

ALTER TABLE platform_refresh_tokens ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_refresh_tokens FORCE ROW LEVEL SECURITY;
CREATE POLICY platform_only ON platform_refresh_tokens
    USING (current_setting('app.platform_access', true) = 'on')
    WITH CHECK (current_setting('app.platform_access', true) = 'on');

-- Platform users are disabled, never deleted (their id stays meaningful in audit_events).
REVOKE DELETE, TRUNCATE ON platform_users FROM nexusops_app;

-- The join-table policies from V3 authorize by EXISTS over users/roles, i.e. by what is VISIBLE. Once the
-- platform_read SELECT policies below exist, visibility no longer implies "same tenant", so restate them with an
-- explicit tenant predicate; otherwise the flag would let a statement write another tenant's assignments.
DROP POLICY tenant_isolation ON role_permissions;
CREATE POLICY tenant_isolation ON role_permissions
    USING (EXISTS (SELECT 1 FROM roles r WHERE r.id = role_id
                   AND r.tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (EXISTS (SELECT 1 FROM roles r WHERE r.id = role_id
                        AND r.tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid));
DROP POLICY tenant_isolation ON user_roles;
CREATE POLICY tenant_isolation ON user_roles
    USING (EXISTS (SELECT 1 FROM users u WHERE u.id = user_id
                   AND u.tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
       AND EXISTS (SELECT 1 FROM roles r WHERE r.id = role_id
                   AND r.tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid))
    WITH CHECK (EXISTS (SELECT 1 FROM users u WHERE u.id = user_id
                        AND u.tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
            AND EXISTS (SELECT 1 FROM roles r WHERE r.id = role_id
                        AND r.tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid));

-- Spec §4.2: read-only cross-tenant visibility for the platform console. FOR SELECT only, so UPDATE,
-- INSERT and DELETE remain governed solely by the tenant_isolation policies (now all explicit about tenant).
CREATE POLICY platform_read ON users FOR SELECT
    USING (current_setting('app.platform_access', true) = 'on');
CREATE POLICY platform_read ON roles FOR SELECT
    USING (current_setting('app.platform_access', true) = 'on');
CREATE POLICY platform_read ON user_roles FOR SELECT
    USING (current_setting('app.platform_access', true) = 'on');
