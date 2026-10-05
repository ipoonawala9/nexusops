# Plan 2 — Tenancy & Identity: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add multi-tenant identity to the Plan 1 foundation, so that two tenants can coexist and a user of Tenant A provably cannot read or change Tenant B's data. Proof covers both the application layer (Hibernate `@TenantId`) and the database layer (PostgreSQL FORCE RLS). Users can sign up a workspace, verify their email, log in, rotate refresh tokens, log out, and read and update their own tenant.

**Architecture:**
- A ThreadLocal `TenantContext` is bound **only** from the verified JWT, or from a server-side slug/token lookup in public flows.
- A `TenantAwareDataSource` writes `app.tenant_id` onto every pooled connection as it is checked out.
- Hibernate's `@TenantId` discriminator scopes ORM queries, and FORCE RLS policies scope every SQL statement.
- Login uses Spring Security's OAuth2 resource server, with self-issued RS256 JWTs.
- `PrincipalFilter` loads the user's status, token version, tenant status and effective permissions, caching them in Redis under a tenant-prefixed key, before any controller runs.

**Tech stack:** Spring Boot 4.1.1 (`spring-boot-starter-security-oauth2-resource-server`), Hibernate 7.4.5, Spring Security 7.1.1, Nimbus JOSE, BouncyCastle 1.86 (Argon2id), PostgreSQL 17 RLS, Redis 7, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-10-04-platform-foundation-design.md`, §4–§8 and §10, plus ADRs 0002–0005. This plan builds on Plan 1, which is merged into `main` at `80ad50a`.

## Scope

**In:**
- Migrations V0_2–V4: tenancy, identity, RBAC tables and seeded catalog, and audit. Each table has RLS from the moment it is created.
- Tenant context with fail-closed RLS.
- Base entities and UUIDv7 ids.
- `AuditService`.
- Mail port with Mailpit/SMTP delivery.
- System roles and the permission resolver.
- JWT issue and verify.
- Auth endpoints: signup, verify-email, resend-verification, login, refresh (rotation, reuse detection, grace window), logout, logout-all.
- `GET /me` and `GET`/`PATCH /tenant`.
- Endpoint-authorization coverage test, tenant isolation suite and token misuse suite.

**Out:**
- Plan 3: invitations, user management API, role management API, module toggles, audit read API, escalation guard, rate limiting.
- Plan 4: platform admin.
- Plan 5: frontend screens.

## Spec deltas (decided here, flagged for review)

1. **RLS is created in the same migration as each table**, instead of in a separate `V5__rls.sql`. That way no table ever exists without RLS.
2. **Join tables use policies instead of composite foreign keys.** `role_permissions` and `user_roles` carry no `tenant_id`. Their RLS policies require the referenced role and user to be visible under the current tenant. This gives the same guarantee as composite FKs, because a cross-tenant row is invisible and can't be inserted.
3. **`app.tenant_id` is set at the session level on connection checkout**, not with `SET LOCAL` per transaction. Every checkout overwrites it, so it can't carry over between users of the pool. `TenantContext` refuses to switch tenant while a transaction is active. ADR-0002 is updated in Task 11.
4. **`audit_events` rows may have a NULL `tenant_id`.** This covers pre-tenant events such as a login to an unknown workspace. Insert is allowed when the row is NULL or matches the current tenant; select only returns current-tenant rows.
5. **Added `POST /auth/resend-verification`.** It always returns 202, so it can't be used to discover accounts. Without it, a lost email leaves the workspace stuck.
6. **Principal cache TTL is 60 s instead of 5 min.** It is explicitly evicted on every user, role or tenant change. The short TTL bounds how stale it can get if an eviction is missed.
7. **Refresh-reuse grace window of 10 s.** Re-presenting a token that was rotated less than 10 s ago returns 401 *without* revoking the family. Two browser tabs share one cookie jar, so the losing tab's next attempt carries the new cookie. Any reuse after the grace window revokes the whole family and is audited.

## Global Constraints

- Everything from Plan 1 still applies:
  - JDK 25 and Boot 4.1.1.
  - The runtime DB role is `nexusops_app`. `DatabaseRoleGuard` must keep passing.
  - Errors are problem+json with a `requestId`.
  - Backend host port 8081.
  - SHA-pinned actions.
  - Every commit ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- **Every tenant-owned table:** `ENABLE` + `FORCE ROW LEVEL SECURITY` and at least one policy, all in the migration that creates the table. Global tables are exactly `plans`, `modules`, `permissions`, `tenants` and `flyway_schema_history`.
- **The RLS predicate is literally** `tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid`.
- **`TenantContext` is set only** from verified JWT claims, or from a server-side lookup (slug or token prefix). It is never set from a header, path or body.
- **Tenant-owned entities extend `TenantOwnedEntity`.** No DTO or request body has a `tenantId` field.
- **IDs are UUIDv7 from `Ids.newId()`. Timestamps are `timestamptz` in UTC** (`Instant` in Java).
- **Passwords:** Argon2id via `Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()`. Policy: 12–128 characters, not in `common-passwords.txt`, and not equal to the email.
- **Opaque tokens** (refresh, verification) have the form `{tenantId}.{43-char base64url of 32 random bytes}`. Only the SHA-256 hex of the whole token is stored.
- **Access JWT:** RS256 only.
  - Claims: `iss=nexusops`, `aud=nexusops-tenant`, `sub` = user id, `tid` = tenant id, `tv` = token version, `jti`, `iat`, `exp`.
  - TTL 15 min.
- **Refresh cookie:**
  - Name `nexus_rt`, `HttpOnly`, `Secure`, `SameSite=Strict`, `Path=/api/v1/auth`.
  - Max-Age 14 days.
  - Each rotation issues a new token but keeps the **family's original absolute expiry**.
- **Login failure response:** `401` "Invalid workspace, email or password." for every credential failure.
  - Correct password but unverified email: `403` "Email address not verified."
  - Correct password but suspended workspace: `403` "Workspace suspended."
- **Every Redis key comes from `TenantKeys`** and starts with `tenant:{uuid}:`.
- **Every controller handler** has `@PreAuthorize`, or its `"METHOD /path"` is listed in `PublicEndpoints.ROUTES`.
- **Never logged or audited:** passwords, password hashes, token values, token hashes, JWTs, cookies.
- **Branch:** work on `feat/tenancy-identity` and commit per task. Do not push or merge without asking.

## Review Focus

1. **Two tabs refresh at once with the same cookie.** Expected: one rotates; the other gets 401 without the family being revoked; the next refresh with the new cookie succeeds. *Pinned by `RefreshTokenIT.concurrentRefreshWithinGraceDoesNotRevokeFamily` (Task 9).*
2. **Email case and whitespace** (`" Owner@Acme.COM "`). Expected: stored normalized, and login works with any case. A second account with a case variant in the same tenant is refused by the database: the `UNIQUE (tenant_id, email)` and lower-case `CHECK` constraints. Plan 3's invitations surface this as 409. *Pinned by `SignupIT.emailIsNormalized`, `SignupIT.duplicateEmailCaseVariantRejected` (Task 8) and `LoginIT.workspaceAndEmailAreCaseInsensitive` (Task 9).*
3. **A developer switches tenant inside a running transaction.** That would silently keep the old tenant's connection setting. Expected: `IllegalStateException`. *Pinned by `TenantContextTest.refusesToSwitchTenantInsideActiveTransaction` (Task 2).*
4. **Workspace slug edge cases** (`"Acme"`, `"acme-"`, `"api"`, 41+ characters). Expected: input is trimmed and lower-cased; anything still invalid or reserved gets 400 with a field error on `slug`. *Pinned by `SlugTest` (Task 4).*
5. **A JWT that is still validly signed after logout-all.** Expected: 401 on the very next request, because the cache is evicted and `tv` is stale. *Pinned by `TokenMisuseIT.staleTokenVersionRejectedAfterLogoutAll` (Task 10).*

---

## File structure

```
backend/src/main/resources/
  db/migration/V0_2__revoke_function_execute.sql, V1__tenancy.sql, V2__identity.sql, V3__rbac.sql, V4__audit.sql
  security/common-passwords.txt
backend/src/main/java/com/nexusops/
  shared/        TenantContext, Ids,
                 db/{BaseEntity, TenantOwnedEntity, TenantAwareDataSource, TenantDataSourceConfig, TenantIdentifierResolver},
                 cache/TenantKeys, web/{ApiProblem, PublicEndpoints, OpenApiConfig}
                 (SecurityConfig moves to identity in Task 10; ProblemDetailSecurityHandlers.write becomes public)
  audit/         AuditService, AuditEntry, ActorType
  notifications/ OutgoingMail, MailRequested, MailSender, internal/{SmtpMailSender, MailDispatcher}
  tenancy/       Slug, TenantStatus, TenantSummary, TenantSettings, UpdateTenantSettings, TenantDirectory,
                 domain/{Tenant, TenantRepository, TenantModule, TenantModuleRepository},
                 web/{TenantController, UpdateTenantSettingsRequest}
  authorization/ AuthorizationService, SystemRoles, domain/{Permission, PermissionRepository, Role, RoleRepository}
  identity/      security/{JwtProperties, JwtKeyConfig, AccessTokenService, PasswordConfig, SecurityConfig, PrincipalFilter,
                           PrincipalState, PrincipalStateCache, CurrentUser, PublicRouteAwareBearerTokenResolver},
                 domain/{User, UserStatus, UserRepository, RefreshToken, RevokeReason, RefreshTokenRepository,
                         EmailVerification, EmailVerificationRepository},
                 application/{OpaqueTokens, PasswordPolicy, Emails, SignupCommand, SignupService, ClientInfo, AuthResult,
                              LoginService, RefreshService, ProfileService},
                 web/{AuthController, AuthDtos, RefreshCookies, OriginGuard, MeController}
backend/src/test/java/com/nexusops/
  support/       IntegrationTestSupport (+@Import TestBeans), TestBeans, RecordingMailSender, OwnerJdbc, TestTenants
  RlsCoverageIT, RlsBehaviourIT, EndpointAuthorizationCoverageTest, TenantIsolationIT, TokenMisuseIT, OpenApiContractIT
  shared/        TenantContextTest, IdsTest, cache/TenantKeysTest, db/TenantAwareDataSourceIT
  audit/AuditServiceIT, notifications/{MailDispatcherIT, SmtpMailSenderTest}
  tenancy/{SlugTest, TenantSettingsIT}, authorization/AuthorizationServiceIT
  identity/      AccessTokenServiceTest, OpaqueTokensTest, PasswordPolicyTest, EmailsTest, UserTenantScopingIT,
                 SignupIT, LoginIT, RefreshTokenIT, MeIT
```

---

### Task 1: Schema — tenancy, identity, RBAC, audit, with RLS on every tenant table

**Files:**
- Create: `backend/src/main/resources/db/migration/V0_2__revoke_function_execute.sql`, `V1__tenancy.sql`, `V2__identity.sql`, `V3__rbac.sql`, `V4__audit.sql`
- Test: `backend/src/test/java/com/nexusops/support/OwnerJdbc.java`, `RlsCoverageIT.java`, `RlsBehaviourIT.java`

**Interfaces:**
- Produces these tables, used by all later tasks:
  - `plans`, `modules`, `tenants`, `tenant_modules`;
  - `users`, `refresh_tokens`, `email_verifications`;
  - `permissions`, `roles`, `role_permissions`, `user_roles`;
  - `audit_events`.
- Produces `OwnerJdbc.jdbc()`, a test-only `JdbcTemplate` connected as `nexusops_owner`, for test setup that must bypass the app's role.

- [ ] **Step 1: Create the branch**

```bash
cd /Users/user/Desktop/nexusops && git checkout -b feat/tenancy-identity
```

- [ ] **Step 2: Write the test helper and the failing RLS coverage test**

`backend/src/test/java/com/nexusops/support/OwnerJdbc.java`:
```java
package com.nexusops.support;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Test-only connections that bypass the application's DataSource. NOTE: FORCE RLS applies to the
 * table owner too, so owner access to tenant tables must go through {@link #ownerAs(UUID)}.
 */
public final class OwnerJdbc {

    private OwnerJdbc() {}

    /** Owner connection without tenant context: fine for global tables (tenants, plans, ...). */
    public static JdbcTemplate jdbc() {
        var ds = new DriverManagerDataSource(
                IntegrationTestSupport.POSTGRES.getJdbcUrl(), "nexusops_owner", IntegrationTestSupport.OWNER_PASSWORD);
        return new JdbcTemplate(ds);
    }

    /** Single owner connection with app.tenant_id set, for setup/inspection of one tenant's rows. */
    public static JdbcTemplate ownerAs(UUID tenantId) {
        var ds = new SingleConnectionDataSource(IntegrationTestSupport.POSTGRES.getJdbcUrl(), "nexusops_owner",
                IntegrationTestSupport.OWNER_PASSWORD, true);
        var jdbc = new JdbcTemplate(ds);
        jdbc.queryForObject("select set_config('app.tenant_id', ?, false)", String.class, tenantId.toString());
        return jdbc;
    }

    /** Connection as the runtime role, WITHOUT the TenantAwareDataSource wrapper — raw RLS behaviour. */
    public static JdbcTemplate rawApp() {
        var ds = new DriverManagerDataSource(
                IntegrationTestSupport.POSTGRES.getJdbcUrl(), "nexusops_app", IntegrationTestSupport.APP_PASSWORD);
        return new JdbcTemplate(ds);
    }
}
```

`backend/src/test/java/com/nexusops/RlsCoverageIT.java`:
```java
package com.nexusops;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexusops.support.IntegrationTestSupport;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Guards every future migration: any table that is not explicitly global must have FORCE RLS and a
 * policy. Adding a table without RLS fails this test.
 */
class RlsCoverageIT extends IntegrationTestSupport {

    static final Set<String> GLOBAL_TABLES =
            Set.of("flyway_schema_history", "plans", "modules", "permissions", "tenants");

    static final Set<String> EXPECTED_TENANT_TABLES = Set.of(
            "tenant_modules", "users", "refresh_tokens", "email_verifications",
            "roles", "role_permissions", "user_roles", "audit_events");

    @Autowired JdbcTemplate jdbc;

    @Test
    void expectedTenantTablesExist() {
        List<String> tables = jdbc.queryForList(
                "select tablename from pg_tables where schemaname = 'public'", String.class);
        assertThat(tables).containsAll(EXPECTED_TENANT_TABLES).containsAll(GLOBAL_TABLES);
    }

    @Test
    void everyNonGlobalTableForcesRlsAndHasAPolicy() {
        List<String> unprotected = jdbc.queryForList("""
                select c.relname
                from pg_class c join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = 'public' and c.relkind = 'r'
                  and not (c.relrowsecurity and c.relforcerowsecurity
                           and exists (select 1 from pg_policies p where p.schemaname = 'public' and p.tablename = c.relname))
                """, String.class);
        assertThat(unprotected).allMatch(GLOBAL_TABLES::contains);
    }
}
```

- [ ] **Step 3: Run it and confirm it fails**

Run: `cd backend && ./gradlew test --tests '*RlsCoverageIT'`
Expected: FAIL. `expectedTenantTablesExist` reports the missing tables.

- [ ] **Step 4: Write the migrations**

`V0_2__revoke_function_execute.sql`:
```sql
-- Functions created by the owner are not executable by everyone by default (Plan 1 review minor).
ALTER DEFAULT PRIVILEGES FOR ROLE nexusops_owner IN SCHEMA public REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
```

`V1__tenancy.sql`:
```sql
CREATE TABLE plans (
    code        text PRIMARY KEY CHECK (code IN ('FREE', 'STARTER', 'BUSINESS', 'ENTERPRISE')),
    name        text NOT NULL,
    limits      jsonb NOT NULL DEFAULT '{}'::jsonb
);
INSERT INTO plans (code, name, limits) VALUES
    ('FREE',       'Free',       '{"maxUsers": 3,  "maxModules": 2}'),
    ('STARTER',    'Starter',    '{"maxUsers": 15, "maxModules": 4}'),
    ('BUSINESS',   'Business',   '{"maxUsers": 100, "maxModules": 4}'),
    ('ENTERPRISE', 'Enterprise', '{}');

CREATE TABLE modules (
    code  text PRIMARY KEY CHECK (code ~ '^[A-Z_]+$'),
    name  text NOT NULL
);
INSERT INTO modules (code, name) VALUES
    ('CRM', 'CRM'), ('INVENTORY', 'Inventory'), ('HELPDESK', 'HelpDesk'), ('HRMS', 'HRMS');

CREATE TABLE tenants (
    id          uuid PRIMARY KEY,
    slug        text NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9](-?[a-z0-9]){2,39}$'),
    name        text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 120),
    subdomain   text UNIQUE,
    status      text NOT NULL CHECK (status IN ('PENDING_VERIFICATION', 'ACTIVE', 'SUSPENDED')),
    plan_code   text NOT NULL REFERENCES plans (code),
    timezone    text NOT NULL DEFAULT 'UTC',
    locale      text NOT NULL DEFAULT 'en',
    currency    char(3) NOT NULL DEFAULT 'USD' CHECK (currency ~ '^[A-Z]{3}$'),
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    version     bigint NOT NULL DEFAULT 0
);

CREATE TABLE tenant_modules (
    id          uuid PRIMARY KEY,
    tenant_id   uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    module_code text NOT NULL REFERENCES modules (code),
    enabled     boolean NOT NULL DEFAULT false,
    UNIQUE (tenant_id, module_code)
);
ALTER TABLE tenant_modules ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_modules FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_modules
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Global catalogs are read-only for the runtime role; tenants are written by tenancy services.
REVOKE INSERT, UPDATE, DELETE ON plans, modules FROM nexusops_app;
REVOKE DELETE ON tenants FROM nexusops_app;
```

`V2__identity.sql`:
```sql
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
```

`V3__rbac.sql`:
```sql
CREATE TABLE permissions (
    code         text PRIMARY KEY CHECK (code ~ '^[a-z]+(\.[a-z]+){2}$'),
    module_code  text REFERENCES modules (code),
    description  text NOT NULL
);
INSERT INTO permissions (code, module_code, description) VALUES
    ('tenant.settings.read',     NULL, 'View workspace settings'),
    ('tenant.settings.update',   NULL, 'Change workspace settings'),
    ('tenant.modules.manage',    NULL, 'Enable or disable modules'),
    ('identity.user.read',       NULL, 'View users'),
    ('identity.user.invite',     NULL, 'Invite users'),
    ('identity.user.update',     NULL, 'Update users'),
    ('identity.user.disable',    NULL, 'Disable users'),
    ('authorization.role.read',  NULL, 'View roles'),
    ('authorization.role.manage',NULL, 'Create and edit roles'),
    ('authorization.role.assign',NULL, 'Assign roles to users'),
    ('audit.event.read',         NULL, 'View the audit log'),
    ('crm.customer.read',        'CRM', 'View customers'),
    ('crm.customer.create',      'CRM', 'Create customers'),
    ('crm.customer.update',      'CRM', 'Update customers'),
    ('crm.customer.delete',      'CRM', 'Delete customers'),
    ('inventory.product.read',   'INVENTORY', 'View products'),
    ('inventory.stock.adjust',   'INVENTORY', 'Adjust stock'),
    ('helpdesk.ticket.read',     'HELPDESK', 'View tickets'),
    ('helpdesk.ticket.assign',   'HELPDESK', 'Assign tickets'),
    ('helpdesk.ticket.resolve',  'HELPDESK', 'Resolve tickets'),
    ('hr.employee.read',         'HRMS', 'View employees'),
    ('hr.employee.update',       'HRMS', 'Update employees');
REVOKE INSERT, UPDATE, DELETE ON permissions FROM nexusops_app;

CREATE TABLE roles (
    id          uuid PRIMARY KEY,
    tenant_id   uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    name        text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 60),
    description text,
    system      boolean NOT NULL DEFAULT false,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    version     bigint NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX roles_tenant_name_uq ON roles (tenant_id, lower(name));
ALTER TABLE roles ENABLE ROW LEVEL SECURITY;
ALTER TABLE roles FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON roles
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Join tables carry no tenant_id: a row is visible/insertable only if every referenced row is
-- visible under the current tenant (RLS on roles/users applies inside the policy subqueries).
CREATE TABLE role_permissions (
    role_id          uuid NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    permission_code  text NOT NULL REFERENCES permissions (code),
    PRIMARY KEY (role_id, permission_code)
);
ALTER TABLE role_permissions ENABLE ROW LEVEL SECURITY;
ALTER TABLE role_permissions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON role_permissions
    USING (EXISTS (SELECT 1 FROM roles r WHERE r.id = role_id))
    WITH CHECK (EXISTS (SELECT 1 FROM roles r WHERE r.id = role_id));

CREATE TABLE user_roles (
    user_id  uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role_id  uuid NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);
CREATE INDEX user_roles_role_idx ON user_roles (role_id);
ALTER TABLE user_roles ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_roles FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON user_roles
    USING (EXISTS (SELECT 1 FROM users u WHERE u.id = user_id) AND EXISTS (SELECT 1 FROM roles r WHERE r.id = role_id))
    WITH CHECK (EXISTS (SELECT 1 FROM users u WHERE u.id = user_id) AND EXISTS (SELECT 1 FROM roles r WHERE r.id = role_id));
```

`V4__audit.sql`:
```sql
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
CREATE TRIGGER audit_events_no_update BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION audit_events_immutable();
```

- [ ] **Step 5: Run the coverage test and confirm it passes**

Run: `./gradlew test --tests '*RlsCoverageIT'`
Expected: PASS (2 tests).

- [ ] **Step 6: Write the RLS behaviour test (raw role, no wrapper)**

`backend/src/test/java/com/nexusops/RlsBehaviourIT.java`:
```java
package com.nexusops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Proves the database layer alone isolates tenants, independent of application code. */
class RlsBehaviourIT extends IntegrationTestSupport {

    static final UUID TENANT_A = UUID.randomUUID();
    static final UUID TENANT_B = UUID.randomUUID();
    static final UUID USER_B = UUID.randomUUID();

    @BeforeAll
    static void seed() {
        JdbcTemplate owner = OwnerJdbc.jdbc();
        Timestamp now = Timestamp.from(Instant.now());
        for (UUID t : new UUID[] {TENANT_A, TENANT_B}) {
            owner.update("insert into tenants (id, slug, name, status, plan_code, created_at, updated_at) "
                    + "values (?, ?, 'T', 'ACTIVE', 'FREE', ?, ?)", t, "rls-" + t.toString().substring(0, 8), now, now);
        }
        OwnerJdbc.ownerAs(TENANT_B).update(
                "insert into users (id, tenant_id, email, password_hash, first_name, last_name, status, created_at, updated_at) "
                + "values (?, ?, 'b@b.test', 'x', 'B', 'B', 'ACTIVE', ?, ?)", USER_B, TENANT_B, now, now);
    }

    /** One connection so set_config persists across statements, like a checked-out pooled connection. */
    private static JdbcTemplate appConnectionAs(UUID tenant) {
        var ds = new SingleConnectionDataSource(POSTGRES.getJdbcUrl(), "nexusops_app", APP_PASSWORD, true);
        var jdbc = new JdbcTemplate(ds);
        jdbc.queryForObject("select set_config('app.tenant_id', ?, false)", String.class,
                tenant == null ? "" : tenant.toString());
        return jdbc;
    }

    @Test
    void tenantACannotSeeTenantBUsers() {
        assertThat(appConnectionAs(TENANT_A).queryForObject(
                "select count(*) from users where id = ?", Long.class, USER_B)).isZero();
        assertThat(appConnectionAs(TENANT_B).queryForObject(
                "select count(*) from users where id = ?", Long.class, USER_B)).isOne();
    }

    @Test
    void noTenantContextSeesNothing() {
        JdbcTemplate jdbc = appConnectionAs(null);
        for (String table : RlsCoverageIT.EXPECTED_TENANT_TABLES) {
            assertThat(jdbc.queryForObject("select count(*) from " + table, Long.class)).as(table).isZero();
        }
    }

    @Test
    void cannotInsertRowForAnotherTenant() {
        Timestamp now = Timestamp.from(Instant.now());
        assertThatThrownBy(() -> appConnectionAs(TENANT_A).update(
                "insert into users (id, tenant_id, email, password_hash, first_name, last_name, status, created_at, updated_at) "
                        + "values (?, ?, 'x@x.test', 'x', 'X', 'X', 'ACTIVE', ?, ?)",
                UUID.randomUUID(), TENANT_B, now, now))
                .hasStackTraceContaining("row-level security");
    }

    @Test
    void cannotUpdateAnotherTenantsRows() {
        int updated = appConnectionAs(TENANT_A).update("update users set first_name = 'Hacked' where id = ?", USER_B);
        assertThat(updated).isZero();
    }

    @Test
    void auditEventsAreAppendOnly() {
        JdbcTemplate jdbc = appConnectionAs(TENANT_A);
        UUID id = UUID.randomUUID();
        jdbc.update("insert into audit_events (id, tenant_id, actor_type, action, occurred_at) values (?, ?, 'SYSTEM', 'Test', now())",
                id, TENANT_A);
        assertThatThrownBy(() -> jdbc.update("update audit_events set action = 'Changed' where id = ?", id))
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> OwnerJdbc.ownerAs(TENANT_A).update("delete from audit_events where id = ?", id))
                .hasStackTraceContaining("append-only");
    }

    @Test
    void globalCatalogsAreReadOnlyForTheApp() {
        assertThatThrownBy(() -> OwnerJdbc.rawApp().update("insert into modules (code, name) values ('EVIL', 'x')"))
                .hasStackTraceContaining("permission denied");
    }
}
```

- [ ] **Step 7: Run it**

Run: `./gradlew test --tests '*RlsBehaviourIT'`
Expected: PASS (6 tests). This test is a characterization of migrations written in Step 4. If any assertion fails, the migration is wrong. Fix the SQL, never the assertion.

- [ ] **Step 8: Full build and commit**

Run: `./gradlew build`. Expected: green (Plan 1 tests plus 8 new ones).
```bash
cd /Users/user/Desktop/nexusops && git add backend && git commit -m "feat(db): tenancy, identity, rbac, audit schema with FORCE RLS on every tenant table

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Shared kernel — TenantContext, UUIDv7, tenant-aware DataSource, Hibernate tenant resolver, TenantKeys, ApiProblem

**Files:**
- Create in `backend/src/main/java/com/nexusops/shared/`:
  - `TenantContext.java`, `Ids.java`;
  - `db/BaseEntity.java`, `db/TenantOwnedEntity.java`, `db/TenantAwareDataSource.java`, `db/TenantDataSourceConfig.java`, `db/TenantIdentifierResolver.java`;
  - `cache/TenantKeys.java`, `web/ApiProblem.java`.
- Modify: `shared/web/GlobalExceptionHandler.java` (handle `ApiProblem`).
- Test:
  - `shared/TenantContextTest.java`, `shared/IdsTest.java`, `shared/cache/TenantKeysTest.java`;
  - `shared/db/TenantAwareDataSourceIT.java`;
  - `shared/web/GlobalExceptionHandlerTest.java` (add cases).

**Interfaces:**
- Produces:
  - `TenantContext.tenantId(): Optional<UUID>`, `requireTenantId(): UUID`, `userId(): Optional<UUID>`.
  - `TenantContext.open(UUID tenantId, UUID userIdOrNull): TenantContext.Scope` (`AutoCloseable`, `close()` throws nothing).
  - `TenantContext.callAs(UUID, Supplier<T>): T` and `runAs(UUID, Runnable)`.
- Produces `Ids.newId(): UUID` (version 7).
- Produces the entity base classes:
  - `BaseEntity(UUID id)`, which implements `Persistable<UUID>` and has `getId()`;
  - `TenantOwnedEntity(UUID id)`, which adds `getTenantId()`.
- Produces `TenantKeys.key(UUID tenantId, String... parts): String` and `tenantPattern(UUID): String`.
- Produces `ApiProblem`, with factories:
  - `badRequest(String detail)`, `badRequestField(String field, String message)`;
  - `unauthorized(String)`, `forbidden(String)`, `notFound(String)`;
  - `conflict(String)`, `conflictField(String field, String message)`.

- [ ] **Step 1: Write the failing unit tests**

`backend/src/test/java/com/nexusops/shared/TenantContextTest.java`:
```java
package com.nexusops.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class TenantContextTest {

    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();

    @AfterEach
    void reset() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void emptyByDefault() {
        assertThat(TenantContext.tenantId()).isEmpty();
        assertThatThrownBy(TenantContext::requireTenantId).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void openBindsAndCloseRestoresPreviousIncludingMdc() {
        UUID user = UUID.randomUUID();
        try (var outer = TenantContext.open(a, user)) {
            assertThat(TenantContext.requireTenantId()).isEqualTo(a);
            assertThat(TenantContext.userId()).contains(user);
            assertThat(MDC.get("tenant_id")).isEqualTo(a.toString());
            try (var inner = TenantContext.open(b, null)) {
                assertThat(TenantContext.requireTenantId()).isEqualTo(b);
                assertThat(TenantContext.userId()).isEmpty();
            }
            assertThat(TenantContext.requireTenantId()).isEqualTo(a);
            assertThat(MDC.get("user_id")).isEqualTo(user.toString());
        }
        assertThat(TenantContext.tenantId()).isEmpty();
        assertThat(MDC.get("tenant_id")).isNull();
    }

    @Test
    void callAsReturnsValueAndUnbinds() {
        assertThat(TenantContext.callAs(a, TenantContext::requireTenantId)).isEqualTo(a);
        assertThat(TenantContext.tenantId()).isEmpty();
    }

    @Test
    void refusesToSwitchTenantInsideActiveTransaction() {
        try (var scope = TenantContext.open(a, null)) {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            assertThatThrownBy(() -> TenantContext.open(b, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("transaction");
            assertThat(TenantContext.requireTenantId()).isEqualTo(a);
        }
    }

    @Test
    void refusesToBindFirstTenantInsideActiveTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> TenantContext.open(a, null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void allowsReopeningSameTenantInsideTransaction() {
        try (var scope = TenantContext.open(a, null)) {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try (var same = TenantContext.open(a, UUID.randomUUID())) {
                assertThat(TenantContext.requireTenantId()).isEqualTo(a);
            }
        }
    }
}
```

`backend/src/test/java/com/nexusops/shared/IdsTest.java`:
```java
package com.nexusops.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdsTest {

    @Test
    void generatesVersion7RfcVariantIds() {
        UUID id = Ids.newId();
        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void idsAreUniqueAndTimeOrderedAcrossMilliseconds() throws InterruptedException {
        UUID first = Ids.newId();
        Thread.sleep(2);
        UUID second = Ids.newId();
        assertThat(first.getMostSignificantBits() >>> 16).isLessThan(second.getMostSignificantBits() >>> 16);
        var seen = new HashSet<UUID>();
        for (int i = 0; i < 10_000; i++) {
            assertThat(seen.add(Ids.newId())).isTrue();
        }
    }
}
```

`backend/src/test/java/com/nexusops/shared/cache/TenantKeysTest.java`:
```java
package com.nexusops.shared.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class TenantKeysTest {

    private final UUID tenant = UUID.fromString("00000000-0000-7000-8000-000000000001");

    @Test
    void buildsTenantPrefixedKeys() {
        assertThat(TenantKeys.key(tenant, "user", "abc", "principal"))
                .isEqualTo("tenant:00000000-0000-7000-8000-000000000001:user:abc:principal");
        assertThat(TenantKeys.tenantPattern(tenant)).isEqualTo("tenant:00000000-0000-7000-8000-000000000001:*");
    }

    @Test
    void rejectsMissingTenantOrUnsafeParts() {
        assertThatThrownBy(() -> TenantKeys.key(null, "x")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> TenantKeys.key(tenant)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TenantKeys.key(tenant, "a:b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TenantKeys.key(tenant, "*")).isInstanceOf(IllegalArgumentException.class);
    }
}
```

Add to `GlobalExceptionHandlerTest`: in `ThrowingController`, add
```java
        @GetMapping("/conflict")
        String conflict() {
            throw com.nexusops.shared.web.ApiProblem.conflictField("slug", "Workspace URL is already taken.");
        }
```
and the test:
```java
    @Test
    void apiProblemMapsToItsStatusWithFieldErrors() throws Exception {
        mvc.perform(get("/conflict"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.errors[0].field").value("slug"))
                .andExpect(jsonPath("$.errors[0].message").value("Workspace URL is already taken."))
                .andExpect(jsonPath("$.requestId").exists());
    }
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `cd backend && ./gradlew test --tests '*TenantContextTest' --tests '*IdsTest' --tests '*TenantKeysTest' --tests '*GlobalExceptionHandlerTest'`
Expected: compilation FAILS (missing `TenantContext`, `Ids`, `TenantKeys`, `ApiProblem`).

- [ ] **Step 3: Implement TenantContext and Ids**

`backend/src/main/java/com/nexusops/shared/TenantContext.java`:
```java
package com.nexusops.shared;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The tenant (and user) the current thread acts for. Bound ONLY from a verified JWT or a
 * server-side lookup (ADR-0002) — never from client-supplied headers or bodies.
 *
 * <p>The tenant is written onto each JDBC connection at checkout (TenantAwareDataSource), so a
 * scope must be opened BEFORE a transaction starts; switching tenant inside a transaction would
 * leave the connection on the old tenant and is refused.
 */
public final class TenantContext {

    private record Binding(UUID tenantId, UUID userId) {}

    /** A bound scope; closing it restores the previous binding. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    private static final ThreadLocal<Binding> CURRENT = new ThreadLocal<>();
    private static final String MDC_TENANT = "tenant_id";
    private static final String MDC_USER = "user_id";

    private TenantContext() {}

    public static Optional<UUID> tenantId() {
        Binding binding = CURRENT.get();
        return binding == null ? Optional.empty() : Optional.of(binding.tenantId());
    }

    public static UUID requireTenantId() {
        return tenantId().orElseThrow(() -> new IllegalStateException("No tenant bound to the current thread"));
    }

    public static Optional<UUID> userId() {
        Binding binding = CURRENT.get();
        return binding == null ? Optional.empty() : Optional.ofNullable(binding.userId());
    }

    public static Scope open(UUID tenantId, UUID userId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Binding previous = CURRENT.get();
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && (previous == null || !previous.tenantId().equals(tenantId))) {
            throw new IllegalStateException("Cannot bind or switch tenant inside an active transaction; "
                    + "open the TenantContext scope before the transaction starts");
        }
        String previousTenantMdc = MDC.get(MDC_TENANT);
        String previousUserMdc = MDC.get(MDC_USER);

        CURRENT.set(new Binding(tenantId, userId));
        MDC.put(MDC_TENANT, tenantId.toString());
        putOrRemove(MDC_USER, userId == null ? null : userId.toString());

        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
            putOrRemove(MDC_TENANT, previousTenantMdc);
            putOrRemove(MDC_USER, previousUserMdc);
        };
    }

    public static <T> T callAs(UUID tenantId, Supplier<T> work) {
        try (Scope scope = open(tenantId, null)) {
            return work.get();
        }
    }

    public static void runAs(UUID tenantId, Runnable work) {
        try (Scope scope = open(tenantId, null)) {
            work.run();
        }
    }

    private static void putOrRemove(String key, String value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }
}
```

`backend/src/main/java/com/nexusops/shared/Ids.java`:
```java
package com.nexusops.shared;

import java.security.SecureRandom;
import java.util.UUID;

/** Time-ordered UUIDv7 identifiers (RFC 9562): index-friendly and safe to generate in the app. */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {}

    public static UUID newId() {
        long millis = System.currentTimeMillis();
        long randA = RANDOM.nextInt(1 << 12);
        long msb = (millis << 16) | (0x7L << 12) | randA;
        long lsb = (RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
        return new UUID(msb, lsb);
    }
}
```

- [ ] **Step 4: Implement TenantKeys and ApiProblem; handle ApiProblem**

`backend/src/main/java/com/nexusops/shared/cache/TenantKeys.java`:
```java
package com.nexusops.shared.cache;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** The only way to build Redis keys: every key is prefixed with its tenant (spec §4.4). */
public final class TenantKeys {

    private static final Pattern SAFE_PART = Pattern.compile("[A-Za-z0-9._-]+");

    private TenantKeys() {}

    public static String key(UUID tenantId, String... parts) {
        Objects.requireNonNull(tenantId, "tenantId");
        if (parts.length == 0) {
            throw new IllegalArgumentException("At least one key part is required");
        }
        for (String part : parts) {
            if (part == null || !SAFE_PART.matcher(part).matches()) {
                throw new IllegalArgumentException("Unsafe cache key part: " + part);
            }
        }
        return "tenant:" + tenantId + ":" + String.join(":", parts);
    }

    public static String tenantPattern(UUID tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        return "tenant:" + tenantId + ":*";
    }
}
```

`backend/src/main/java/com/nexusops/shared/web/ApiProblem.java`:
```java
package com.nexusops.shared.web;

import java.util.List;
import org.springframework.http.HttpStatus;

/** An expected, client-facing failure. Mapped to problem+json by GlobalExceptionHandler. */
public class ApiProblem extends RuntimeException {

    public record FieldError(String field, String message) {}

    private final HttpStatus status;
    private final List<FieldError> errors;

    protected ApiProblem(HttpStatus status, String detail, List<FieldError> errors) {
        super(detail);
        this.status = status;
        this.errors = List.copyOf(errors);
    }

    public HttpStatus status() {
        return status;
    }

    public List<FieldError> errors() {
        return errors;
    }

    public static ApiProblem badRequest(String detail) {
        return new ApiProblem(HttpStatus.BAD_REQUEST, detail, List.of());
    }

    public static ApiProblem badRequestField(String field, String message) {
        return new ApiProblem(HttpStatus.BAD_REQUEST, "Request validation failed.", List.of(new FieldError(field, message)));
    }

    public static ApiProblem unauthorized(String detail) {
        return new ApiProblem(HttpStatus.UNAUTHORIZED, detail, List.of());
    }

    public static ApiProblem forbidden(String detail) {
        return new ApiProblem(HttpStatus.FORBIDDEN, detail, List.of());
    }

    public static ApiProblem notFound(String detail) {
        return new ApiProblem(HttpStatus.NOT_FOUND, detail, List.of());
    }

    public static ApiProblem conflict(String detail) {
        return new ApiProblem(HttpStatus.CONFLICT, detail, List.of());
    }

    public static ApiProblem conflictField(String field, String message) {
        return new ApiProblem(HttpStatus.CONFLICT, message, List.of(new FieldError(field, message)));
    }
}
```

In `GlobalExceptionHandler`, add (above `handleUnexpected`):
```java
    @ExceptionHandler(ApiProblem.class)
    ResponseEntity<ProblemDetail> handleApiProblem(ApiProblem ex) {
        ProblemDetail problem = ProblemDetails.of(ex.status(), ex.status().getReasonPhrase(), ex.getMessage());
        if (!ex.errors().isEmpty()) {
            problem.setProperty("errors", ex.errors().stream()
                    .map(e -> Map.of("field", e.field(), "message", e.message()))
                    .toList());
        }
        return ResponseEntity.status(ex.status()).body(problem);
    }
```

- [ ] **Step 5: Run the unit tests and confirm they pass**

Run: `./gradlew test --tests '*TenantContextTest' --tests '*IdsTest' --tests '*TenantKeysTest' --tests '*GlobalExceptionHandlerTest'`
Expected: PASS (6 + 2 + 2 + 5 tests).

- [ ] **Step 6: Write the failing DataSource integration test**

`backend/src/test/java/com/nexusops/shared/db/TenantAwareDataSourceIT.java`:
```java
package com.nexusops.shared.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexusops.shared.TenantContext;
import com.nexusops.support.IntegrationTestSupport;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class TenantAwareDataSourceIT extends IntegrationTestSupport {

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;

    private String currentSetting() {
        return jdbc.queryForObject("select current_setting('app.tenant_id', true)", String.class);
    }

    @Test
    void applicationDataSourceIsTenantAware() {
        assertThat(dataSource).isInstanceOf(TenantAwareDataSource.class);
    }

    @Test
    void connectionCarriesTheBoundTenant() {
        UUID tenant = UUID.randomUUID();
        assertThat(TenantContext.callAs(tenant, this::currentSetting)).isEqualTo(tenant.toString());
    }

    @Test
    void connectionWithoutTenantIsResetEvenAfterPooledReuse() {
        UUID tenant = UUID.randomUUID();
        TenantContext.runAs(tenant, this::currentSetting);
        // The pool hands back a previously used connection; it must not still carry the old tenant.
        for (int i = 0; i < 20; i++) {
            assertThat(currentSetting()).isEmpty();
        }
    }
}
```

Run: `./gradlew test --tests '*TenantAwareDataSourceIT'`
Expected: compilation FAILS (missing `TenantAwareDataSource`).

- [ ] **Step 7: Implement the data source wrapper, the Hibernate resolver and the base entities**

`backend/src/main/java/com/nexusops/shared/db/TenantAwareDataSource.java`:
```java
package com.nexusops.shared.db;

import com.nexusops.shared.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Writes the current tenant into the session setting {@code app.tenant_id} every time a connection
 * is checked out, so PostgreSQL RLS policies see it (ADR-0002). With no tenant bound, the setting is
 * the empty string, which the policies treat as NULL: zero rows (fail closed). Because EVERY checkout
 * overwrites the value, a pooled connection never leaks a previous tenant.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    private static final String SET_TENANT = "select set_config('app.tenant_id', ?, false)";

    public TenantAwareDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return bind(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return bind(super.getConnection(username, password));
    }

    private static Connection bind(Connection connection) throws SQLException {
        String tenant = TenantContext.tenantId().map(UUID::toString).orElse("");
        try (PreparedStatement statement = connection.prepareStatement(SET_TENANT)) {
            statement.setString(1, tenant);
            statement.execute();
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return connection;
    }
}
```

`backend/src/main/java/com/nexusops/shared/db/TenantDataSourceConfig.java`:
```java
package com.nexusops.shared.db;

import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class TenantDataSourceConfig {

    /** Wraps the application DataSource (Flyway uses its own owner connection and is unaffected). */
    @Bean
    static BeanPostProcessor tenantAwareDataSourcePostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource dataSource && !(bean instanceof TenantAwareDataSource)) {
                    return new TenantAwareDataSource(dataSource);
                }
                return bean;
            }
        };
    }
}
```

`backend/src/main/java/com/nexusops/shared/db/TenantIdentifierResolver.java`:
```java
package com.nexusops.shared.db;

import com.nexusops.shared.TenantContext;
import java.util.Map;
import java.util.UUID;
import org.hibernate.cfg.MultiTenancySettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.stereotype.Component;

/**
 * Application-layer tenant scoping: Hibernate stamps and filters every {@code @TenantId} column with
 * the bound tenant. With no tenant bound it uses an all-zero UUID that matches nothing (fail closed).
 */
@Component
class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<UUID>, HibernatePropertiesCustomizer {

    static final UUID NO_TENANT = new UUID(0L, 0L);

    @Override
    public UUID resolveCurrentTenantIdentifier() {
        return TenantContext.tenantId().orElse(NO_TENANT);
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return true;
    }

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.put(MultiTenancySettings.MULTI_TENANT_IDENTIFIER_RESOLVER, this);
    }
}
```

`backend/src/main/java/com/nexusops/shared/db/BaseEntity.java`:
```java
package com.nexusops.shared.db;

import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * Entities get their UUIDv7 id at construction ({@code Ids.newId()}); {@link Persistable} tells
 * Spring Data they are new, so save() persists without a wasted SELECT.
 */
@MappedSuperclass
public abstract class BaseEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    @Transient
    private boolean isNew = true;

    protected BaseEntity() {}

    protected BaseEntity(UUID id) {
        this.id = id;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }
}
```

`backend/src/main/java/com/nexusops/shared/db/TenantOwnedEntity.java`:
```java
package com.nexusops.shared.db;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/** Base for every tenant-owned entity: tenant_id is stamped and filtered by Hibernate, never set by code. */
@MappedSuperclass
public abstract class TenantOwnedEntity extends BaseEntity {

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    protected TenantOwnedEntity() {}

    protected TenantOwnedEntity(UUID id) {
        super(id);
    }

    public UUID getTenantId() {
        return tenantId;
    }
}
```

- [ ] **Step 8: Run the new tests and the full build**

Run: `./gradlew test --tests '*TenantAwareDataSourceIT'`, then `./gradlew build`
Expected: PASS (3 tests), then a green build. `PlatformFoundationIT` and `DatabaseRoleGuardStartupIT` must still pass, which proves the wrapper didn't break the role guard. `ModularityTest` must pass too.

- [ ] **Step 9: Commit**

```bash
cd /Users/user/Desktop/nexusops && git add backend && git commit -m "feat(shared): tenant context, tenant-aware DataSource, Hibernate tenant resolver, UUIDv7, TenantKeys, ApiProblem

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Audit service and mail port

**Files:**
- Create in `backend/src/main/java/com/nexusops/`:
  - `audit/AuditService.java`, `audit/AuditEntry.java`, `audit/ActorType.java`;
  - `notifications/OutgoingMail.java`, `notifications/MailRequested.java`, `notifications/MailSender.java`;
  - `notifications/internal/SmtpMailSender.java`, `notifications/internal/MailDispatcher.java`.
- Modify: `backend/src/main/resources/application.yml`, adding:
  ```yaml
  nexusops:
    mail:
      from: "NexusOps <no-reply@nexusops.local>"
  ```
- Modify: `support/IntegrationTestSupport.java` (add `@Import(TestBeans.class)`) and `support/OwnerJdbc.java` (add `superuser()`).
- Test: `support/TestBeans.java`, `support/RecordingMailSender.java`, `audit/AuditServiceIT.java`, `notifications/MailDispatcherIT.java`, `notifications/SmtpMailSenderTest.java`.

**Interfaces:**
- Produces:
  - `AuditService.record(AuditEntry)`: must run inside the caller's transaction (`MANDATORY`).
  - `AuditService.recordIndependently(AuditEntry)`: own transaction (`REQUIRES_NEW`), used for failures.
  - `AuditEntry.of(String action, String entityType, Object entityId)`, with `.withBefore(Map)`, `.withAfter(Map)`, `.withMetadata(Map)` and `.asActor(ActorType)`.
- Produces: `MailSender.send(OutgoingMail)`, `record OutgoingMail(String to, String subject, String textBody)`, and `record MailRequested(OutgoingMail mail)`. Mail is published as an event and delivered **after commit**.
- Produces these test helpers:
  - `RecordingMailSender.lastTokenFor(String email): String`, which extracts the `token=` query value from the last mail to that address;
  - `RecordingMailSender.sentTo(String email): List<OutgoingMail>`;
  - `OwnerJdbc.superuser(): JdbcTemplate`, which bypasses RLS and is for inspection only.

- [ ] **Step 1: Test support beans**

Add to `OwnerJdbc`:
```java
    /** Superuser connection — bypasses RLS. Use ONLY to inspect rows (e.g. NULL-tenant audit events). */
    public static JdbcTemplate superuser() {
        var pg = IntegrationTestSupport.POSTGRES;
        return new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
    }
```

`backend/src/test/java/com/nexusops/support/RecordingMailSender.java`:
```java
package com.nexusops.support;

import com.nexusops.notifications.MailSender;
import com.nexusops.notifications.OutgoingMail;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Captures outgoing mail in tests instead of sending it. */
public class RecordingMailSender implements MailSender {

    private static final Pattern TOKEN = Pattern.compile("token=([A-Za-z0-9._%-]+)");
    private final List<OutgoingMail> sent = new ArrayList<>();

    @Override
    public synchronized void send(OutgoingMail mail) {
        sent.add(mail);
    }

    public synchronized List<OutgoingMail> sentTo(String email) {
        return sent.stream().filter(m -> m.to().equalsIgnoreCase(email)).toList();
    }

    public synchronized String lastTokenFor(String email) {
        List<OutgoingMail> mails = sentTo(email);
        if (mails.isEmpty()) {
            throw new AssertionError("No mail sent to " + email);
        }
        Matcher matcher = TOKEN.matcher(mails.getLast().textBody());
        if (!matcher.find()) {
            throw new AssertionError("No token link in mail to " + email);
        }
        return java.net.URLDecoder.decode(matcher.group(1), java.nio.charset.StandardCharsets.UTF_8);
    }
}
```

`backend/src/test/java/com/nexusops/support/TestBeans.java`:
```java
package com.nexusops.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class TestBeans {

    @Bean
    @Primary
    RecordingMailSender recordingMailSender() {
        return new RecordingMailSender();
    }
}
```

Annotate `IntegrationTestSupport` with `@org.springframework.context.annotation.Import(TestBeans.class)`.

- [ ] **Step 2: Write the failing tests**

`backend/src/test/java/com/nexusops/audit/AuditServiceIT.java`:
```java
package com.nexusops.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexusops.shared.TenantContext;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

class AuditServiceIT extends IntegrationTestSupport {

    @Autowired AuditService audit;
    @Autowired TransactionTemplate tx;

    UUID tenant;

    @BeforeEach
    void createTenant() {
        tenant = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        OwnerJdbc.jdbc().update("insert into tenants (id, slug, name, status, plan_code, created_at, updated_at) "
                + "values (?, ?, 'Audit', 'ACTIVE', 'FREE', ?, ?)", tenant, "aud-" + tenant.toString().substring(0, 8), now, now);
    }

    private Map<String, Object> onlyRow(String action) {
        return OwnerJdbc.superuser().queryForMap(
                "select * from audit_events where action = ? and (tenant_id = ? or tenant_id is null) order by occurred_at desc limit 1",
                action, tenant);
    }

    @Test
    void recordRequiresAnExistingTransaction() {
        assertThatThrownBy(() -> audit.record(AuditEntry.of("Orphan", "Thing", "1")))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void recordsTenantActorAndPayload() {
        UUID user = UUID.randomUUID();
        try (var scope = TenantContext.open(tenant, user)) {
            tx.executeWithoutResult(s -> audit.record(AuditEntry.of("ThingChanged", "Thing", 42)
                    .withBefore(Map.of("name", "old"))
                    .withAfter(Map.of("name", "new"))));
        }
        Map<String, Object> row = onlyRow("ThingChanged");
        assertThat(row.get("tenant_id")).isEqualTo(tenant);
        assertThat(row.get("actor_id")).isEqualTo(user);
        assertThat(row.get("actor_type")).isEqualTo("USER");
        assertThat(row.get("entity_id")).isEqualTo("42");
        assertThat(row.get("before").toString()).contains("old");
        assertThat(row.get("after").toString()).contains("new");
    }

    @Test
    void independentRecordSurvivesCallerRollback() {
        try (var scope = TenantContext.open(tenant, null)) {
            tx.executeWithoutResult(s -> {
                audit.recordIndependently(AuditEntry.of("LoginFailed", "User", "x"));
                s.setRollbackOnly();
            });
        }
        assertThat(onlyRow("LoginFailed").get("actor_type")).isEqualTo("ANONYMOUS");
    }

    @Test
    void preTenantEventsAreStoredWithNullTenant() {
        String action = "UnknownWorkspace" + tenant.toString().replace("-", "").replaceAll("[0-9]", "");
        audit.recordIndependently(AuditEntry.of(action, "Tenant", null));
        Long count = OwnerJdbc.superuser().queryForObject(
                "select count(*) from audit_events where action = ? and tenant_id is null", Long.class, action);
        assertThat(count).isOne();
    }

    @Test
    void sensitiveKeysAreNeverPersisted() {
        try (var scope = TenantContext.open(tenant, null)) {
            tx.executeWithoutResult(s -> audit.record(AuditEntry.of("Scrubbed", "User", "1")
                    .withAfter(Map.of("email", "a@b.test", "passwordHash", "$argon2id$x", "refreshToken", "t", "apiSecret", "s"))));
        }
        String after = onlyRow("Scrubbed").get("after").toString();
        assertThat(after).contains("a@b.test").doesNotContain("argon2id").doesNotContain("passwordHash")
                .doesNotContain("refreshToken").doesNotContain("apiSecret");
    }
}
```

`backend/src/test/java/com/nexusops/notifications/MailDispatcherIT.java`:
```java
package com.nexusops.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.RecordingMailSender;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

class MailDispatcherIT extends IntegrationTestSupport {

    @Autowired ApplicationEventPublisher events;
    @Autowired TransactionTemplate tx;
    @Autowired RecordingMailSender mail;

    @Test
    void mailIsSentOnlyAfterCommit() {
        tx.executeWithoutResult(s -> events.publishEvent(new MailRequested(new OutgoingMail("commit@x.test", "Hi", "body"))));
        assertThat(mail.sentTo("commit@x.test")).hasSize(1);
    }

    @Test
    void mailIsNotSentWhenTransactionRollsBack() {
        tx.executeWithoutResult(s -> {
            events.publishEvent(new MailRequested(new OutgoingMail("rollback@x.test", "Hi", "body")));
            s.setRollbackOnly();
        });
        assertThat(mail.sentTo("rollback@x.test")).isEmpty();
    }

    @Test
    void mailOutsideTransactionIsSentImmediately() {
        events.publishEvent(new MailRequested(new OutgoingMail("now@x.test", "Hi", "body")));
        assertThat(mail.sentTo("now@x.test")).hasSize(1);
    }
}
```

`backend/src/test/java/com/nexusops/notifications/SmtpMailSenderTest.java`:
```java
package com.nexusops.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.nexusops.notifications.internal.SmtpMailSender;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class SmtpMailSenderTest {

    @Test
    void sendsPlainTextFromConfiguredAddress() {
        JavaMailSender javaMail = mock(JavaMailSender.class);
        new SmtpMailSender(javaMail, "NexusOps <no-reply@nexusops.local>")
                .send(new OutgoingMail("to@x.test", "Subject", "Body"));
        var captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(javaMail).send(captor.capture());
        assertThat(captor.getValue().getTo()).containsExactly("to@x.test");
        assertThat(captor.getValue().getFrom()).isEqualTo("NexusOps <no-reply@nexusops.local>");
        assertThat(captor.getValue().getSubject()).isEqualTo("Subject");
        assertThat(captor.getValue().getText()).isEqualTo("Body");
    }
}
```

Run: `cd backend && ./gradlew test --tests '*AuditServiceIT' --tests '*MailDispatcherIT' --tests '*SmtpMailSenderTest'`
Expected: compilation FAILS (missing audit and notifications types).

- [ ] **Step 3: Implement audit**

`backend/src/main/java/com/nexusops/audit/ActorType.java`:
```java
package com.nexusops.audit;

public enum ActorType { USER, ANONYMOUS, SYSTEM, PLATFORM }
```

`backend/src/main/java/com/nexusops/audit/AuditEntry.java`:
```java
package com.nexusops.audit;

import java.util.Map;

/**
 * One auditable action. Actor and tenant come from TenantContext; request metadata from the
 * current HTTP request. Keys that look secret are dropped from before/after/metadata.
 */
public record AuditEntry(
        String action,
        String entityType,
        String entityId,
        Map<String, Object> before,
        Map<String, Object> after,
        Map<String, Object> metadata,
        ActorType actorType) {

    public static AuditEntry of(String action, String entityType, Object entityId) {
        return new AuditEntry(action, entityType, entityId == null ? null : entityId.toString(), null, null, null, null);
    }

    public AuditEntry withBefore(Map<String, Object> value) {
        return new AuditEntry(action, entityType, entityId, value, after, metadata, actorType);
    }

    public AuditEntry withAfter(Map<String, Object> value) {
        return new AuditEntry(action, entityType, entityId, before, value, metadata, actorType);
    }

    public AuditEntry withMetadata(Map<String, Object> value) {
        return new AuditEntry(action, entityType, entityId, before, after, value, actorType);
    }

    public AuditEntry asActor(ActorType value) {
        return new AuditEntry(action, entityType, entityId, before, after, metadata, value);
    }
}
```

`backend/src/main/java/com/nexusops/audit/AuditService.java`:
```java
package com.nexusops.audit;

import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.web.RequestIds;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;

/**
 * Append-only audit log (ADR-0005). Written with plain JDBC (not JPA) because pre-tenant events
 * legitimately have no tenant, which Hibernate's @TenantId would refuse.
 */
@Service
public class AuditService {

    private static final Pattern SENSITIVE_KEY = Pattern.compile("(?i).*(password|token|secret|hash|cookie|jwt).*");
    private static final int MAX_USER_AGENT = 512;

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    AuditService(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Records in the caller's transaction: the audit row exists iff the business change commits. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditEntry entry) {
        insert(entry);
    }

    /** Records in its own transaction, for events that must persist even if the caller fails (e.g. failed login). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIndependently(AuditEntry entry) {
        insert(entry);
    }

    private void insert(AuditEntry entry) {
        UUID tenantId = TenantContext.tenantId().orElse(null);
        UUID actorId = TenantContext.userId().orElse(null);
        ActorType actorType = entry.actorType() != null ? entry.actorType()
                : actorId != null ? ActorType.USER : ActorType.ANONYMOUS;
        HttpServletRequest request = currentRequest();
        String requestId = RequestIds.current();

        jdbc.update("""
                insert into audit_events (id, tenant_id, actor_type, actor_id, action, entity_type, entity_id,
                    occurred_at, ip, user_agent, request_id, correlation_id, before, after, metadata)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb)
                """,
                Ids.newId(), tenantId, actorType.name(), actorId, entry.action(), entry.entityType(), entry.entityId(),
                Timestamp.from(Instant.now()),
                request == null ? null : request.getRemoteAddr(),
                request == null ? null : truncate(request.getHeader("User-Agent")),
                "none".equals(requestId) ? null : requestId,
                "none".equals(requestId) ? null : RequestIds.correlationId(),
                toJson(entry.before()), toJson(entry.after()), toJson(entry.metadata()));
    }

    private String toJson(Map<String, Object> values) {
        if (values == null) {
            return null;
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (!SENSITIVE_KEY.matcher(key).matches()) {
                safe.put(key, value);
            }
        });
        return json.writeValueAsString(safe);
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
                ? attrs.getRequest() : null;
    }

    private static String truncate(String value) {
        return value == null || value.length() <= MAX_USER_AGENT ? value : value.substring(0, MAX_USER_AGENT);
    }
}
```

- [ ] **Step 4: Implement notifications**

`backend/src/main/java/com/nexusops/notifications/OutgoingMail.java`:
```java
package com.nexusops.notifications;

public record OutgoingMail(String to, String subject, String textBody) {}
```

`backend/src/main/java/com/nexusops/notifications/MailRequested.java`:
```java
package com.nexusops.notifications;

/** Publish inside a transaction; the mail is delivered only after commit (MailDispatcher). */
public record MailRequested(OutgoingMail mail) {}
```

`backend/src/main/java/com/nexusops/notifications/MailSender.java`:
```java
package com.nexusops.notifications;

/** Delivery port. SMTP (Mailpit locally) today; SES later. */
public interface MailSender {
    void send(OutgoingMail mail);
}
```

`backend/src/main/java/com/nexusops/notifications/internal/SmtpMailSender.java`:
```java
package com.nexusops.notifications.internal;

import com.nexusops.notifications.MailSender;
import com.nexusops.notifications.OutgoingMail;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class SmtpMailSender implements MailSender {

    private final JavaMailSender javaMail;
    private final String from;

    public SmtpMailSender(JavaMailSender javaMail, @Value("${nexusops.mail.from}") String from) {
        this.javaMail = javaMail;
        this.from = from;
    }

    @Override
    public void send(OutgoingMail mail) {
        var message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(mail.to());
        message.setSubject(mail.subject());
        message.setText(mail.textBody());
        javaMail.send(message);
    }
}
```

`backend/src/main/java/com/nexusops/notifications/internal/MailDispatcher.java`:
```java
package com.nexusops.notifications.internal;

import com.nexusops.notifications.MailRequested;
import com.nexusops.notifications.MailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
class MailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(MailDispatcher.class);
    private final MailSender mailSender;

    MailDispatcher(MailSender mailSender) {
        this.mailSender = mailSender;
    }

    /** Never fails the (already committed) business operation; the recipient address is not logged (PII). */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void on(MailRequested event) {
        try {
            mailSender.send(event.mail());
        } catch (RuntimeException e) {
            log.warn("Mail delivery failed (subject: {})", event.mail().subject(), e);
        }
    }
}
```

Add to `application.yml` (top level):
```yaml
nexusops:
  mail:
    from: "NexusOps <no-reply@nexusops.local>"
```

- [ ] **Step 5: Run the tests and the full build**

Run: `./gradlew test --tests '*AuditServiceIT' --tests '*MailDispatcherIT' --tests '*SmtpMailSenderTest'`, then `./gradlew build`
Expected: PASS (5 + 3 + 1), then a green build, including `ModularityTest`. `audit` and `notifications` depend only on `shared`.

- [ ] **Step 6: Commit**

```bash
cd /Users/user/Desktop/nexusops && git add backend && git commit -m "feat: append-only AuditService and after-commit mail port

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Tenancy module — slugs, tenant directory, tenant settings

**Files:**
- Create in `backend/src/main/java/com/nexusops/tenancy/`:
  - `Slug.java`, `TenantStatus.java`, `TenantSummary.java`, `TenantSettings.java`, `UpdateTenantSettings.java`, `TenantDirectory.java`;
  - `domain/Tenant.java`, `domain/TenantRepository.java`, `domain/TenantModule.java`, `domain/TenantModuleRepository.java`;
  - `web/TenantController.java`, `web/UpdateTenantSettingsRequest.java`.
- Modify: `shared/web/GlobalExceptionHandler.java` (optimistic-lock conflicts become 409).
- Test: `tenancy/SlugTest.java`, `tenancy/TenantSettingsIT.java`, `shared/web/GlobalExceptionHandlerTest.java` (add a case).

**Interfaces:**
- Consumes: from Task 2, `TenantContext`, `Ids`, `BaseEntity`, `TenantOwnedEntity`, `ApiProblem`. From Task 3, `AuditService` and `AuditEntry`.
- Produces `Slug`:
  - `Slug.normalize(String): String` throws `ApiProblem` 400 with field `slug`;
  - `Slug.tryNormalize(String): Optional<String>`.
- Produces `enum TenantStatus { PENDING_VERIFICATION, ACTIVE, SUSPENDED }`.
- Produces `record TenantSummary(UUID id, String slug, String name, TenantStatus status, String planCode)`.
- Produces `record TenantSettings(UUID id, String slug, String name, TenantStatus status, String planCode, String timezone, String locale, String currency)`.
- Produces `record UpdateTenantSettings(String name, String timezone, String locale, String currency)`, where null means "leave unchanged".
- Produces `TenantDirectory`:
  - Pre-auth:
    - `findBySlug(String raw): Optional<TenantSummary>`;
    - `register(UUID id, String rawSlug, String name): TenantSummary`, which returns 409 with field `slug` when the slug is taken.
  - Current tenant only:
    - `current(): TenantSummary`;
    - `activateCurrent()`;
    - `currentSettings(): TenantSettings`;
    - `updateSettings(UpdateTenantSettings): TenantSettings`;
    - `enabledModules(): List<String>`.
- Produces `GET /api/v1/tenant` (`tenant.settings.read`) and `PATCH /api/v1/tenant` (`tenant.settings.update`). These are exercised over HTTP in Task 10.

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/com/nexusops/tenancy/SlugTest.java`:
```java
package com.nexusops.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexusops.shared.web.ApiProblem;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class SlugTest {

    @Test
    void trimsAndLowercases() {
        assertThat(Slug.normalize("  Acme-Corp ")).isEqualTo("acme-corp");
        assertThat(Slug.normalize("a1b")).isEqualTo("a1b");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "ab", "acme-", "-acme", "a--b", "acmé", "acme corp", "acme_corp",
            "abcdefghijabcdefghijabcdefghijabcdefghijk", "api", "API", "admin", "platform", "www"})
    void rejectsInvalidOrReservedSlugsWithAFieldError(String raw) {
        assertThatThrownBy(() -> Slug.normalize(raw))
                .isInstanceOfSatisfying(ApiProblem.class, p -> {
                    assertThat(p.status().value()).isEqualTo(400);
                    assertThat(p.errors()).singleElement().satisfies(e -> assertThat(e.field()).isEqualTo("slug"));
                });
        assertThat(Slug.tryNormalize(raw)).isEmpty();
    }

    @Test
    void acceptsFortyCharacters() {
        assertThat(Slug.normalize("abcdefghijabcdefghijabcdefghijabcdefghij")).hasSize(40);
    }
}
```

`backend/src/test/java/com/nexusops/tenancy/TenantSettingsIT.java`:
```java
package com.nexusops.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.web.ApiProblem;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TenantSettingsIT extends IntegrationTestSupport {

    @Autowired TenantDirectory directory;

    UUID tenantId;
    String slug;

    @BeforeEach
    void register() {
        tenantId = Ids.newId();
        slug = "set-" + tenantId.toString().substring(28);
        directory.register(tenantId, "  " + slug.toUpperCase() + " ", "Settings Co");
    }

    @Test
    void registeredTenantIsPendingOnFreePlanWithDefaults() {
        TenantSettings settings = TenantContext.callAs(tenantId, directory::currentSettings);
        assertThat(settings.slug()).isEqualTo(slug);
        assertThat(settings.status()).isEqualTo(TenantStatus.PENDING_VERIFICATION);
        assertThat(settings.planCode()).isEqualTo("FREE");
        assertThat(settings.timezone()).isEqualTo("UTC");
        assertThat(settings.currency()).isEqualTo("USD");
        assertThat(TenantContext.callAs(tenantId, directory::enabledModules)).isEmpty();
    }

    @Test
    void duplicateSlugIsAConflictOnTheSlugField() {
        assertThatThrownBy(() -> directory.register(Ids.newId(), slug, "Other"))
                .isInstanceOfSatisfying(ApiProblem.class, p -> {
                    assertThat(p.status().value()).isEqualTo(409);
                    assertThat(p.errors().getFirst().field()).isEqualTo("slug");
                });
    }

    @Test
    void findsBySlugLenientlyAndIgnoresGarbage() {
        assertThat(directory.findBySlug(" " + slug.toUpperCase())).map(TenantSummary::id).contains(tenantId);
        assertThat(directory.findBySlug("not a slug!")).isEmpty();
        assertThat(directory.findBySlug(null)).isEmpty();
    }

    @Test
    void activateCurrentMakesTenantActive() {
        TenantContext.runAs(tenantId, directory::activateCurrent);
        assertThat(TenantContext.callAs(tenantId, directory::current).status()).isEqualTo(TenantStatus.ACTIVE);
    }

    @Test
    void updatesSettingsAndAuditsBeforeAndAfter() {
        TenantSettings updated = TenantContext.callAs(tenantId,
                () -> directory.updateSettings(new UpdateTenantSettings(" Renamed Co ", "Asia/Kolkata", "en-IN", "inr")));
        assertThat(updated.name()).isEqualTo("Renamed Co");
        assertThat(updated.timezone()).isEqualTo("Asia/Kolkata");
        assertThat(updated.locale()).isEqualTo("en-IN");
        assertThat(updated.currency()).isEqualTo("INR");
        String after = OwnerJdbc.superuser().queryForObject(
                "select after::text from audit_events where tenant_id = ? and action = 'TenantSettingsUpdated'", String.class, tenantId);
        assertThat(after).contains("Asia/Kolkata");
    }

    @Test
    void nullFieldsAreLeftUnchanged() {
        TenantSettings updated = TenantContext.callAs(tenantId,
                () -> directory.updateSettings(new UpdateTenantSettings(null, "Europe/Berlin", null, null)));
        assertThat(updated.name()).isEqualTo("Settings Co");
        assertThat(updated.timezone()).isEqualTo("Europe/Berlin");
    }

    @Test
    void invalidSettingsAreFieldErrors() {
        assertFieldError(new UpdateTenantSettings(null, "Mars/Olympus", null, null), "timezone");
        assertFieldError(new UpdateTenantSettings(null, null, "!!", null), "locale");
        assertFieldError(new UpdateTenantSettings(null, null, null, "XYZ"), "currency");
        assertFieldError(new UpdateTenantSettings("   ", null, null, null), "name");
    }

    private void assertFieldError(UpdateTenantSettings command, String field) {
        assertThatThrownBy(() -> TenantContext.callAs(tenantId, () -> directory.updateSettings(command)))
                .isInstanceOfSatisfying(ApiProblem.class, p -> assertThat(p.errors().getFirst().field()).isEqualTo(field));
    }

    @Test
    void currentTenantOperationsRequireATenant() {
        assertThatThrownBy(directory::currentSettings).isInstanceOf(IllegalStateException.class);
    }
}
```

Add to `GlobalExceptionHandlerTest.ThrowingController`:
```java
        @GetMapping("/stale")
        String stale() {
            throw new org.springframework.orm.ObjectOptimisticLockingFailureException("Tenant", "id");
        }
```
and the test:
```java
    @Test
    void optimisticLockConflictIs409() throws Exception {
        mvc.perform(get("/stale"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This record was changed by someone else. Reload and try again."));
    }
```

Run: `cd backend && ./gradlew test --tests '*SlugTest' --tests '*TenantSettingsIT' --tests '*GlobalExceptionHandlerTest'`
Expected: compilation FAILS (missing tenancy types).

- [ ] **Step 2: Implement the API types and Slug**

`backend/src/main/java/com/nexusops/tenancy/TenantStatus.java`:
```java
package com.nexusops.tenancy;

public enum TenantStatus { PENDING_VERIFICATION, ACTIVE, SUSPENDED }
```

`backend/src/main/java/com/nexusops/tenancy/TenantSummary.java`:
```java
package com.nexusops.tenancy;

import java.util.UUID;

public record TenantSummary(UUID id, String slug, String name, TenantStatus status, String planCode) {}
```

`backend/src/main/java/com/nexusops/tenancy/TenantSettings.java`:
```java
package com.nexusops.tenancy;

import java.util.UUID;

public record TenantSettings(UUID id, String slug, String name, TenantStatus status, String planCode,
        String timezone, String locale, String currency) {}
```

`backend/src/main/java/com/nexusops/tenancy/UpdateTenantSettings.java`:
```java
package com.nexusops.tenancy;

/** Partial update: null fields are left unchanged. */
public record UpdateTenantSettings(String name, String timezone, String locale, String currency) {}
```

`backend/src/main/java/com/nexusops/tenancy/Slug.java`:
```java
package com.nexusops.tenancy;

import com.nexusops.shared.web.ApiProblem;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Workspace slugs: trimmed, lower-cased, 3–40 chars of [a-z0-9] with single inner hyphens, not reserved. */
public final class Slug {

    private static final Pattern VALID = Pattern.compile("^[a-z0-9](-?[a-z0-9]){2,39}$");
    private static final Set<String> RESERVED = Set.of(
            "api", "app", "admin", "www", "platform", "auth", "login", "signup", "static", "assets",
            "mail", "support", "help", "status", "nexusops");

    private Slug() {}

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiProblem.badRequestField("slug", "Workspace URL is required.");
        }
        String slug = raw.trim().toLowerCase(Locale.ROOT);
        if (!VALID.matcher(slug).matches()) {
            throw ApiProblem.badRequestField("slug",
                    "Use 3–40 lowercase letters, digits or single hyphens, starting and ending with a letter or digit.");
        }
        if (RESERVED.contains(slug)) {
            throw ApiProblem.badRequestField("slug", "This workspace URL is reserved.");
        }
        return slug;
    }

    public static Optional<String> tryNormalize(String raw) {
        try {
            return Optional.of(normalize(raw));
        } catch (ApiProblem e) {
            return Optional.empty();
        }
    }
}
```

- [ ] **Step 3: Implement the domain**

`backend/src/main/java/com/nexusops/tenancy/domain/Tenant.java`:
```java
package com.nexusops.tenancy.domain;

import com.nexusops.shared.db.BaseEntity;
import com.nexusops.tenancy.TenantSettings;
import com.nexusops.tenancy.TenantStatus;
import com.nexusops.tenancy.TenantSummary;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** Global (non-RLS) table; access is restricted by TenantDirectory to the current tenant or pre-auth lookups. */
@Entity
@Table(name = "tenants")
public class Tenant extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TenantStatus status;

    @Column(name = "plan_code", nullable = false)
    private String planCode;

    @Column(nullable = false)
    private String timezone;

    @Column(nullable = false)
    private String locale;

    @Column(nullable = false, columnDefinition = "bpchar")
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Tenant() {}

    public static Tenant register(UUID id, String slug, String name) {
        Tenant tenant = new Tenant();
        tenant.initId(id);
        tenant.slug = slug;
        tenant.name = name;
        tenant.status = TenantStatus.PENDING_VERIFICATION;
        tenant.planCode = "FREE";
        tenant.timezone = "UTC";
        tenant.locale = "en";
        tenant.currency = "USD";
        tenant.createdAt = Instant.now();
        tenant.updatedAt = tenant.createdAt;
        return tenant;
    }

    public void activate() {
        if (status == TenantStatus.PENDING_VERIFICATION) {
            status = TenantStatus.ACTIVE;
            updatedAt = Instant.now();
        }
    }

    public void applySettings(String newName, String newTimezone, String newLocale, String newCurrency) {
        if (newName != null) name = newName;
        if (newTimezone != null) timezone = newTimezone;
        if (newLocale != null) locale = newLocale;
        if (newCurrency != null) currency = newCurrency;
        updatedAt = Instant.now();
    }

    public TenantSummary toSummary() {
        return new TenantSummary(getId(), slug, name, status, planCode);
    }

    public TenantSettings toSettings() {
        return new TenantSettings(getId(), slug, name, status, planCode, timezone, locale, currency);
    }
}
```
This needs a protected id initializer in `BaseEntity`, because `Tenant` uses a static factory instead of a constructor. Add to `BaseEntity`:
```java
    /** For entities built by static factories; may be called once. */
    protected void initId(UUID newId) {
        if (this.id != null) {
            throw new IllegalStateException("id already set");
        }
        this.id = newId;
    }
```

`backend/src/main/java/com/nexusops/tenancy/domain/TenantRepository.java`:
```java
package com.nexusops.tenancy.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {

    Optional<Tenant> findBySlug(String slug);

    boolean existsBySlug(String slug);
}
```

`backend/src/main/java/com/nexusops/tenancy/domain/TenantModule.java`:
```java
package com.nexusops.tenancy.domain;

import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "tenant_modules")
public class TenantModule extends TenantOwnedEntity {

    @Column(name = "module_code", nullable = false)
    private String moduleCode;

    @Column(nullable = false)
    private boolean enabled;

    protected TenantModule() {}

    public TenantModule(UUID id, String moduleCode, boolean enabled) {
        super(id);
        this.moduleCode = moduleCode;
        this.enabled = enabled;
    }
}
```

`backend/src/main/java/com/nexusops/tenancy/domain/TenantModuleRepository.java`:
```java
package com.nexusops.tenancy.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TenantModuleRepository extends JpaRepository<TenantModule, UUID> {

    /** Tenant-scoped automatically by @TenantId and RLS. */
    @Query("select m.moduleCode from TenantModule m where m.enabled = true order by m.moduleCode")
    List<String> findEnabledCodes();
}
```

- [ ] **Step 4: Implement TenantDirectory**

`backend/src/main/java/com/nexusops/tenancy/TenantDirectory.java`:
```java
package com.nexusops.tenancy;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.web.ApiProblem;
import com.nexusops.tenancy.domain.Tenant;
import com.nexusops.tenancy.domain.TenantModuleRepository;
import com.nexusops.tenancy.domain.TenantRepository;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Currency;
import java.util.IllformedLocaleException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The tenancy module's API. The tenants table is global (no RLS), so this class is the guard:
 * apart from the explicit pre-auth lookups, it only ever touches the tenant bound in TenantContext.
 */
@Service
public class TenantDirectory {

    private final TenantRepository tenants;
    private final TenantModuleRepository modules;
    private final AuditService audit;

    TenantDirectory(TenantRepository tenants, TenantModuleRepository modules, AuditService audit) {
        this.tenants = tenants;
        this.modules = modules;
        this.audit = audit;
    }

    /** Pre-auth: resolve a workspace by (leniently normalized) slug. Invalid input simply finds nothing. */
    @Transactional(readOnly = true)
    public Optional<TenantSummary> findBySlug(String rawSlug) {
        return Slug.tryNormalize(rawSlug).flatMap(tenants::findBySlug).map(Tenant::toSummary);
    }

    /** Pre-auth: create a workspace in PENDING_VERIFICATION on the Free plan. */
    @Transactional
    public TenantSummary register(UUID id, String rawSlug, String rawName) {
        String slug = Slug.normalize(rawSlug);
        String name = requireName(rawName, "workspaceName");
        if (tenants.existsBySlug(slug)) {
            throw slugTaken();
        }
        try {
            return tenants.saveAndFlush(Tenant.register(id, slug, name)).toSummary();
        } catch (DataIntegrityViolationException race) {
            throw slugTaken();
        }
    }

    @Transactional(readOnly = true)
    public TenantSummary current() {
        return currentTenant().toSummary();
    }

    @Transactional
    public void activateCurrent() {
        currentTenant().activate();
    }

    @Transactional(readOnly = true)
    public TenantSettings currentSettings() {
        return currentTenant().toSettings();
    }

    @Transactional
    public TenantSettings updateSettings(UpdateTenantSettings command) {
        Tenant tenant = currentTenant();
        TenantSettings before = tenant.toSettings();
        tenant.applySettings(
                command.name() == null ? null : requireName(command.name(), "name"),
                command.timezone() == null ? null : validTimezone(command.timezone()),
                command.locale() == null ? null : validLocale(command.locale()),
                command.currency() == null ? null : validCurrency(command.currency()));
        tenants.flush();
        TenantSettings after = tenant.toSettings();
        audit.record(AuditEntry.of("TenantSettingsUpdated", "Tenant", tenant.getId())
                .withBefore(settingsMap(before))
                .withAfter(settingsMap(after)));
        return after;
    }

    @Transactional(readOnly = true)
    public List<String> enabledModules() {
        TenantContext.requireTenantId();
        return modules.findEnabledCodes();
    }

    private Tenant currentTenant() {
        UUID id = TenantContext.requireTenantId();
        return tenants.findById(id).orElseThrow(() -> ApiProblem.notFound("Workspace not found."));
    }

    private static ApiProblem slugTaken() {
        return ApiProblem.conflictField("slug", "This workspace URL is already taken.");
    }

    private static String requireName(String raw, String field) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty() || name.length() > 120) {
            throw ApiProblem.badRequestField(field, "Enter a name between 1 and 120 characters.");
        }
        return name;
    }

    private static String validTimezone(String raw) {
        try {
            return ZoneId.of(raw.strip()).getId();
        } catch (DateTimeException e) {
            throw ApiProblem.badRequestField("timezone", "Unknown time zone.");
        }
    }

    private static String validLocale(String raw) {
        try {
            Locale locale = new Locale.Builder().setLanguageTag(raw.strip()).build();
            if (locale.getLanguage().isEmpty()) {
                throw new IllformedLocaleException("no language");
            }
            return locale.toLanguageTag();
        } catch (IllformedLocaleException e) {
            throw ApiProblem.badRequestField("locale", "Use a language tag such as en or en-IN.");
        }
    }

    private static String validCurrency(String raw) {
        try {
            String code = raw.strip().toUpperCase(Locale.ROOT);
            Currency.getInstance(code);
            return code;
        } catch (IllegalArgumentException e) {
            throw ApiProblem.badRequestField("currency", "Use an ISO 4217 currency code such as USD or INR.");
        }
    }

    private static Map<String, Object> settingsMap(TenantSettings s) {
        return Map.of("name", s.name(), "timezone", s.timezone(), "locale", s.locale(), "currency", s.currency());
    }
}
```

- [ ] **Step 5: Controller, request DTO and optimistic-lock mapping**

`backend/src/main/java/com/nexusops/tenancy/web/UpdateTenantSettingsRequest.java`:
```java
package com.nexusops.tenancy.web;

import jakarta.validation.constraints.Size;

public record UpdateTenantSettingsRequest(
        @Size(max = 120) String name,
        @Size(max = 64) String timezone,
        @Size(max = 35) String locale,
        @Size(min = 3, max = 3) String currency) {}
```

`backend/src/main/java/com/nexusops/tenancy/web/TenantController.java`:
```java
package com.nexusops.tenancy.web;

import com.nexusops.tenancy.TenantDirectory;
import com.nexusops.tenancy.TenantSettings;
import com.nexusops.tenancy.UpdateTenantSettings;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenant")
class TenantController {

    private final TenantDirectory directory;

    TenantController(TenantDirectory directory) {
        this.directory = directory;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('tenant.settings.read')")
    TenantSettings get() {
        return directory.currentSettings();
    }

    @PatchMapping
    @PreAuthorize("hasAuthority('tenant.settings.update')")
    TenantSettings update(@Valid @RequestBody UpdateTenantSettingsRequest request) {
        return directory.updateSettings(new UpdateTenantSettings(
                request.name(), request.timezone(), request.locale(), request.currency()));
    }
}
```

In `GlobalExceptionHandler`, add:
```java
    @ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> handleOptimisticLock(Exception ex) {
        return problem(HttpStatus.CONFLICT, "Conflict", "This record was changed by someone else. Reload and try again.");
    }
```

- [ ] **Step 6: Run the tests and the full build**

Run: `./gradlew test --tests '*SlugTest' --tests '*TenantSettingsIT' --tests '*GlobalExceptionHandlerTest'`, then `./gradlew build`
Expected:
- `SlugTest` passes 17 cases: 1 + 15 parameterized + 1.
- `TenantSettingsIT` passes 8.
- `GlobalExceptionHandlerTest` passes 6.
- The build is green.

`ModularityTest` must pass: `tenancy` depends on `shared` and `audit` only.

- [ ] **Step 7: Commit**

```bash
cd /Users/user/Desktop/nexusops && git add backend && git commit -m "feat(tenancy): slugs, tenant directory guarding the global tenants table, tenant settings API

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Authorization core — permission catalog, roles, system roles, effective permissions

**Files:**
- Create in `backend/src/main/java/com/nexusops/authorization/`:
  - `AuthorizationService.java`, `SystemRoles.java`;
  - `domain/Permission.java`, `domain/PermissionRepository.java`, `domain/Role.java`, `domain/RoleRepository.java`.
- Test: `backend/src/test/java/com/nexusops/authorization/AuthorizationServiceIT.java`

**Interfaces:**
- Consumes: from Task 2, `TenantOwnedEntity` and `Ids`.
- Produces: `SystemRoles.OWNER = "TENANT_OWNER"` and `SystemRoles.ADMIN = "TENANT_ADMIN"`.
- Produces `AuthorizationService`:
  - `createSystemRoles(): UUID` creates both system roles in the current tenant and returns the **owner** role id;
  - `effectivePermissions(Collection<UUID> roleIds, Collection<String> enabledModules): Set<String>` returns the union of the roles' permissions, minus those belonging to a disabled module. Roles of other tenants are invisible and so contribute nothing.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/nexusops/authorization/AuthorizationServiceIT.java`:
```java
package com.nexusops.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AuthorizationServiceIT extends IntegrationTestSupport {

    @Autowired AuthorizationService authorization;

    UUID tenantA;
    UUID tenantB;

    private static UUID newTenant() {
        UUID id = Ids.newId();
        Timestamp now = Timestamp.from(Instant.now());
        OwnerJdbc.jdbc().update("insert into tenants (id, slug, name, status, plan_code, created_at, updated_at) "
                + "values (?, ?, 'Authz', 'ACTIVE', 'FREE', ?, ?)", id, "az-" + id.toString().substring(24), now, now);
        return id;
    }

    @BeforeEach
    void tenants() {
        tenantA = newTenant();
        tenantB = newTenant();
    }

    @Test
    void ownerHasEveryFoundationPermissionButModulePermissionsNeedTheModule() {
        UUID owner = TenantContext.callAs(tenantA, authorization::createSystemRoles);
        Set<String> withoutModules = TenantContext.callAs(tenantA,
                () -> authorization.effectivePermissions(List.of(owner), List.of()));
        assertThat(withoutModules)
                .contains("tenant.settings.read", "tenant.settings.update", "identity.user.invite",
                        "authorization.role.manage", "audit.event.read")
                .doesNotContain("crm.customer.read", "hr.employee.read");

        Set<String> withCrm = TenantContext.callAs(tenantA,
                () -> authorization.effectivePermissions(List.of(owner), List.of("CRM")));
        assertThat(withCrm).contains("crm.customer.read", "crm.customer.delete").doesNotContain("hr.employee.read");
    }

    @Test
    void systemRolesAreStoredAndFlagged() {
        TenantContext.runAs(tenantA, authorization::createSystemRoles);
        List<String> names = OwnerJdbc.ownerAs(tenantA).queryForList(
                "select name from roles where system order by name", String.class);
        assertThat(names).containsExactly(SystemRoles.ADMIN, SystemRoles.OWNER);
    }

    @Test
    void rolesOfAnotherTenantContributeNothing() {
        UUID ownerOfB = TenantContext.callAs(tenantB, authorization::createSystemRoles);
        Set<String> seenFromA = TenantContext.callAs(tenantA,
                () -> authorization.effectivePermissions(List.of(ownerOfB), List.of("CRM", "HRMS")));
        assertThat(seenFromA).isEmpty();
    }

    @Test
    void noRolesMeansNoPermissions() {
        assertThat(TenantContext.callAs(tenantA, () -> authorization.effectivePermissions(List.of(), List.of("CRM"))))
                .isEmpty();
    }
}
```

Run: `cd backend && ./gradlew test --tests '*AuthorizationServiceIT'`
Expected: compilation FAILS.

- [ ] **Step 2: Implement the domain**

`backend/src/main/java/com/nexusops/authorization/SystemRoles.java`:
```java
package com.nexusops.authorization;

public final class SystemRoles {

    public static final String OWNER = "TENANT_OWNER";
    public static final String ADMIN = "TENANT_ADMIN";

    private SystemRoles() {}
}
```

`backend/src/main/java/com/nexusops/authorization/domain/Permission.java`:
```java
package com.nexusops.authorization.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/** Global, read-only permission catalog (seeded by V3__rbac.sql). */
@Entity
@Immutable
@Table(name = "permissions")
public class Permission {

    @Id
    private String code;

    @Column(name = "module_code")
    private String moduleCode;

    @Column(nullable = false)
    private String description;

    protected Permission() {}

    public String getCode() {
        return code;
    }

    public String getModuleCode() {
        return moduleCode;
    }
}
```

`backend/src/main/java/com/nexusops/authorization/domain/PermissionRepository.java`:
```java
package com.nexusops.authorization.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PermissionRepository extends JpaRepository<Permission, String> {}
```

`backend/src/main/java/com/nexusops/authorization/domain/Role.java`:
```java
package com.nexusops.authorization.domain;

import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "roles")
public class Role extends TenantOwnedEntity {

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(nullable = false)
    private boolean system;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "role_permissions", joinColumns = @JoinColumn(name = "role_id"))
    @Column(name = "permission_code")
    private Set<String> permissions = new HashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Role() {}

    public Role(UUID id, String name, String description, boolean system, Set<String> permissions) {
        super(id);
        this.name = name;
        this.description = description;
        this.system = system;
        this.permissions = new HashSet<>(permissions);
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public String getName() {
        return name;
    }

    public Set<String> getPermissions() {
        return Set.copyOf(permissions);
    }
}
```

`backend/src/main/java/com/nexusops/authorization/domain/RoleRepository.java`:
```java
package com.nexusops.authorization.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, UUID> {}
```

- [ ] **Step 3: Implement AuthorizationService**

`backend/src/main/java/com/nexusops/authorization/AuthorizationService.java`:
```java
package com.nexusops.authorization;

import com.nexusops.authorization.domain.Permission;
import com.nexusops.authorization.domain.PermissionRepository;
import com.nexusops.authorization.domain.Role;
import com.nexusops.authorization.domain.RoleRepository;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Permissions, not role names, decide authorization (ADR-0004). */
@Service
public class AuthorizationService {

    private final RoleRepository roles;
    private final PermissionRepository permissions;

    AuthorizationService(RoleRepository roles, PermissionRepository permissions) {
        this.roles = roles;
        this.permissions = permissions;
    }

    /** Creates TENANT_OWNER and TENANT_ADMIN (both with the full catalog) in the current tenant. */
    @Transactional
    public UUID createSystemRoles() {
        TenantContext.requireTenantId();
        Set<String> all = permissions.findAll().stream().map(Permission::getCode).collect(Collectors.toSet());
        Role owner = new Role(Ids.newId(), SystemRoles.OWNER, "Workspace owner — full access", true, all);
        Role admin = new Role(Ids.newId(), SystemRoles.ADMIN, "Workspace administrator — full access", true, all);
        roles.saveAll(List.of(owner, admin));
        return owner.getId();
    }

    /** Union of the roles' permissions, minus permissions of modules not enabled for the tenant. */
    @Transactional(readOnly = true)
    public Set<String> effectivePermissions(Collection<UUID> roleIds, Collection<String> enabledModules) {
        TenantContext.requireTenantId();
        if (roleIds.isEmpty()) {
            return Set.of();
        }
        Map<String, String> moduleOf = permissions.findAll().stream()
                .collect(HashMap::new, (m, p) -> m.put(p.getCode(), p.getModuleCode()), Map::putAll);
        Set<String> result = new HashSet<>();
        for (Role role : roles.findAllById(roleIds)) {
            for (String code : role.getPermissions()) {
                String module = moduleOf.get(code);
                if (module == null || enabledModules.contains(module)) {
                    result.add(code);
                }
            }
        }
        return Set.copyOf(result);
    }
}
```
The `collect(HashMap::new, ...)` form is used because `Collectors.toMap` rejects the `null` module codes of foundation permissions.

- [ ] **Step 4: Run the tests and the build**

Run: `./gradlew test --tests '*AuthorizationServiceIT'`, then `./gradlew build`
Expected: PASS (4 tests), then a green build including `ModularityTest`.

If Hibernate schema validation (`ddl-auto: validate`) rejects a `text` column mapped to `String`, for example "found [text], expecting [varchar(255)]", annotate the affected `String` fields with `@Column(columnDefinition = "text")`. Do the same for any other entity it reports, and ledger it as a ruling.

- [ ] **Step 5: Commit**

```bash
cd /Users/user/Desktop/nexusops && git add backend && git commit -m "feat(authorization): permission catalog, tenant roles, system roles, effective permissions

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: JWT — RSA keys, access-token issuing and strict verification

**Files:**
- Modify: `backend/build.gradle.kts`. Add the dependencies:
  ```kotlin
  implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
  implementation("org.bouncycastle:bcprov-jdk18on:1.86")
  testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server-test")
  ```
- Modify: `application.yml`, `application-local.yml`, `application-test.yml` (JWT settings).
- Create in `backend/src/main/java/com/nexusops/identity/security/`: `JwtProperties.java`, `JwtKeyConfig.java`, `AccessTokenService.java`.
- Test: `backend/src/test/java/com/nexusops/identity/AccessTokenServiceTest.java`

**Interfaces:**
- Produces `record JwtProperties(String issuer, String audience, Duration accessTokenTtl, Duration refreshTokenTtl, String privateKey, String publicKey, boolean ephemeralKeys)`, bound from the prefix `nexusops.security.jwt`.
- Produces `JwtKeyConfig`:
  - static `rsaKey(JwtProperties): RSAKey`;
  - beans `RSAKey`, `JwtEncoder` and `JwtDecoder`. The decoder accepts RS256 only and validates `iss`, `aud`, `exp` and the presence of `tid`, `tv` and `sub`.
- Produces:
  - `AccessTokenService(JwtEncoder, JwtProperties, Clock)`;
  - `issue(UUID tenantId, UUID userId, int tokenVersion): IssuedAccessToken`;
  - `record IssuedAccessToken(String value, Instant expiresAt)`.
- Produces the claim names `AccessTokenService.CLAIM_TENANT = "tid"` and `CLAIM_TOKEN_VERSION = "tv"`.

- [ ] **Step 1: Configuration**

Append to `application.yml` under the existing `nexusops:` key:
```yaml
  security:
    jwt:
      issuer: nexusops
      audience: nexusops-tenant
      access-token-ttl: 15m
      refresh-token-ttl: 14d
      private-key: ${JWT_PRIVATE_KEY:}
      public-key: ${JWT_PUBLIC_KEY:}
      ephemeral-keys: false
```
Add to both `application-local.yml` and `application-test.yml`:
```yaml
nexusops:
  security:
    jwt:
      ephemeral-keys: true
```

- [ ] **Step 2: Write the failing tests**

`backend/src/test/java/com/nexusops/identity/AccessTokenServiceTest.java`:
```java
package com.nexusops.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexusops.identity.security.AccessTokenService;
import com.nexusops.identity.security.JwtKeyConfig;
import com.nexusops.identity.security.JwtProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;

class AccessTokenServiceTest {

    static final JwtProperties PROPS = new JwtProperties("nexusops", "nexusops-tenant",
            Duration.ofMinutes(15), Duration.ofDays(14), "", "", true);

    final RSAKey key = JwtKeyConfig.rsaKey(PROPS);
    final JwtEncoder encoder = JwtKeyConfig.encoder(key);
    final JwtDecoder decoder = JwtKeyConfig.decoder(key, PROPS);
    final UUID tenant = UUID.randomUUID();
    final UUID user = UUID.randomUUID();

    private AccessTokenService serviceAt(Instant now) {
        return new AccessTokenService(encoder, PROPS, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void issuesAVerifiableTokenWithTenantUserAndVersion() {
        var issued = serviceAt(Instant.now()).issue(tenant, user, 3);
        var jwt = decoder.decode(issued.value());
        assertThat(jwt.getSubject()).isEqualTo(user.toString());
        assertThat(jwt.getClaimAsString("tid")).isEqualTo(tenant.toString());
        assertThat(((Number) jwt.getClaim("tv")).intValue()).isEqualTo(3);
        assertThat(jwt.getAudience()).containsExactly("nexusops-tenant");
        assertThat(jwt.getId()).isNotBlank();
        assertThat(issued.expiresAt()).isAfter(Instant.now().plus(Duration.ofMinutes(14)));
    }

    @Test
    void rejectsExpiredTokens() {
        var issued = serviceAt(Instant.now().minus(Duration.ofHours(1))).issue(tenant, user, 0);
        assertThatThrownBy(() -> decoder.decode(issued.value())).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsTokensSignedWithAnotherKey() {
        RSAKey otherKey = JwtKeyConfig.rsaKey(PROPS);
        String forged = new AccessTokenService(JwtKeyConfig.encoder(otherKey), PROPS, Clock.systemUTC())
                .issue(tenant, user, 0).value();
        assertThatThrownBy(() -> decoder.decode(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsUnsignedAlgNoneTokens() {
        String header = b64("{\"alg\":\"none\"}");
        String payload = b64("{\"sub\":\"" + user + "\",\"tid\":\"" + tenant + "\",\"tv\":0,\"iss\":\"nexusops\","
                + "\"aud\":\"nexusops-tenant\",\"exp\":" + (Instant.now().getEpochSecond() + 600) + "}");
        assertThatThrownBy(() -> decoder.decode(header + "." + payload + ".")).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsHmacKeyConfusionUsingThePublicKeyAsSecret() throws Exception {
        byte[] secret = key.toRSAPublicKey().getEncoded();
        var claims = new JWTClaimsSet.Builder().subject(user.toString()).claim("tid", tenant.toString()).claim("tv", 0)
                .issuer("nexusops").audience("nexusops-tenant").expirationTime(Date.from(Instant.now().plusSeconds(600)))
                .build();
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(secret));
        assertThatThrownBy(() -> decoder.decode(jwt.serialize())).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsWrongAudienceIssuerOrMissingTenant() {
        assertThatThrownBy(() -> decoder.decode(sign(b -> b.audience(List.of("nexusops-platform")))))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(sign(b -> b.issuer("someone-else"))))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(sign(b -> b.claims(c -> c.remove("tid")))))
                .isInstanceOf(JwtException.class);
    }

    private String sign(java.util.function.Consumer<JwtClaimsSet.Builder> tweak) {
        var builder = JwtClaimsSet.builder().subject(user.toString()).issuer("nexusops")
                .audience(List.of("nexusops-tenant")).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600))
                .claim("tid", tenant.toString()).claim("tv", 0);
        tweak.accept(builder);
        var header = org.springframework.security.oauth2.jwt.JwsHeader
                .with(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, builder.build())).getTokenValue();
    }

    @Test
    void failsFastWithoutKeysWhenEphemeralKeysAreDisabled() {
        var prod = new JwtProperties("nexusops", "nexusops-tenant", Duration.ofMinutes(15), Duration.ofDays(14), "", "", false);
        assertThatThrownBy(() -> JwtKeyConfig.rsaKey(prod))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_PRIVATE_KEY");
    }

    @Test
    void loadsPemKeysWhenConfigured() throws Exception {
        String privatePem = pem("PRIVATE KEY", key.toRSAPrivateKey().getEncoded());
        String publicPem = pem("PUBLIC KEY", key.toRSAPublicKey().getEncoded());
        var configured = new JwtProperties("nexusops", "nexusops-tenant", Duration.ofMinutes(15), Duration.ofDays(14),
                privatePem, publicPem, false);
        assertThat(JwtKeyConfig.rsaKey(configured).toRSAPublicKey()).isEqualTo(key.toRSAPublicKey());
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder().encodeToString(der) + "\n-----END " + type + "-----\n";
    }

    private static String b64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes());
    }
}
```

Run: `cd backend && ./gradlew test --tests '*AccessTokenServiceTest'`
Expected: compilation FAILS (missing `identity.security` types).

- [ ] **Step 3: Implement**

`backend/src/main/java/com/nexusops/identity/security/JwtProperties.java`:
```java
package com.nexusops.identity.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("nexusops.security.jwt")
public record JwtProperties(
        String issuer,
        String audience,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        String privateKey,
        String publicKey,
        boolean ephemeralKeys) {}
```

`backend/src/main/java/com/nexusops/identity/security/JwtKeyConfig.java`:
```java
package com.nexusops.identity.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** RS256 key material (ADR-0003). Production keys come from JWT_PRIVATE_KEY / JWT_PUBLIC_KEY (PEM). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
public class JwtKeyConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtKeyConfig.class);

    @Bean
    RSAKey jwtRsaKey(JwtProperties properties) {
        return rsaKey(properties);
    }

    @Bean
    JwtEncoder jwtEncoder(RSAKey jwtRsaKey) {
        return encoder(jwtRsaKey);
    }

    @Bean
    JwtDecoder jwtDecoder(RSAKey jwtRsaKey, JwtProperties properties) {
        return decoder(jwtRsaKey, properties);
    }

    public static RSAKey rsaKey(JwtProperties properties) {
        try {
            if (notBlank(properties.privateKey()) && notBlank(properties.publicKey())) {
                KeyFactory rsa = KeyFactory.getInstance("RSA");
                var publicKey = (RSAPublicKey) rsa.generatePublic(new X509EncodedKeySpec(pemBody(properties.publicKey())));
                var privateKey = (RSAPrivateKey) rsa.generatePrivate(new PKCS8EncodedKeySpec(pemBody(properties.privateKey())));
                return new RSAKey.Builder(publicKey).privateKey(privateKey).keyIDFromThumbprint().build();
            }
            if (properties.ephemeralKeys()) {
                log.warn("Using an ephemeral JWT signing key (local/test only); tokens are invalid after restart.");
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                var pair = generator.generateKeyPair();
                return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                        .privateKey((RSAPrivateKey) pair.getPrivate()).keyIDFromThumbprint().build();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Invalid JWT key material", e);
        }
        throw new IllegalStateException(
                "Missing required secret: set environment variables JWT_PRIVATE_KEY and JWT_PUBLIC_KEY (PEM).");
    }

    public static JwtEncoder encoder(RSAKey key) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
    }

    public static JwtDecoder decoder(RSAKey key, JwtProperties properties) {
        try {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey())
                    .signatureAlgorithm(SignatureAlgorithm.RS256)
                    .build();
            OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer(properties.issuer()),
                    new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains(properties.audience())),
                    new JwtClaimValidator<Object>("tid", tid -> tid instanceof String s && !s.isBlank()),
                    new JwtClaimValidator<Object>("tv", tv -> tv instanceof Number),
                    new JwtClaimValidator<Object>("sub", sub -> sub instanceof String s && !s.isBlank()));
            decoder.setJwtValidator(validator);
            return decoder;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot build JWT decoder", e);
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static byte[] pemBody(String pem) {
        String body = pem.replaceAll("-----BEGIN [A-Z ]+-----", "")
                .replaceAll("-----END [A-Z ]+-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }
}
```

`backend/src/main/java/com/nexusops/identity/security/AccessTokenService.java`:
```java
package com.nexusops.identity.security;

import com.nexusops.shared.Ids;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/** Issues short-lived RS256 access tokens. Permissions are NOT embedded (resolved per request, ADR-0004). */
@Service
public class AccessTokenService {

    public static final String CLAIM_TENANT = "tid";
    public static final String CLAIM_TOKEN_VERSION = "tv";

    public record IssuedAccessToken(String value, Instant expiresAt) {}

    private final JwtEncoder encoder;
    private final JwtProperties properties;
    private final Clock clock;

    @Autowired
    public AccessTokenService(JwtEncoder encoder, JwtProperties properties) {
        this(encoder, properties, Clock.systemUTC());
    }

    public AccessTokenService(JwtEncoder encoder, JwtProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    public IssuedAccessToken issue(UUID tenantId, UUID userId, int tokenVersion) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .audience(List.of(properties.audience()))
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .id(Ids.newId().toString())
                .claim(CLAIM_TENANT, tenantId.toString())
                .claim(CLAIM_TOKEN_VERSION, tokenVersion)
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return new IssuedAccessToken(encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue(), expiresAt);
    }
}
```

- [ ] **Step 4: Run the tests and the build**

Run: `./gradlew test --tests '*AccessTokenServiceTest'`, then `./gradlew build`
Expected: PASS (8 tests), then a green build.

Adding the resource-server starter does **not** change behaviour yet, because Plan 1's `SecurityConfig` doesn't enable `oauth2ResourceServer`. `PlatformFoundationIT` must still pass.

- [ ] **Step 5: Commit**

```bash
cd /Users/user/Desktop/nexusops && git add backend && git commit -m "feat(identity): RS256 JWT keys, access-token issuing, strict decoder with misuse tests

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Identity domain — users, refresh tokens, email verifications, opaque tokens, password policy

**Files:**
- Create in `backend/src/main/java/com/nexusops/identity/`:
  - `domain/User.java`, `domain/UserStatus.java`, `domain/UserRepository.java`;
  - `domain/RefreshToken.java`, `domain/RevokeReason.java`, `domain/RefreshTokenRepository.java`;
  - `domain/EmailVerification.java`, `domain/EmailVerificationRepository.java`;
  - `application/OpaqueTokens.java`, `application/PasswordPolicy.java`, `application/Emails.java`.
- Create: `backend/src/main/resources/security/common-passwords.txt`
- Test: `identity/OpaqueTokensTest.java`, `identity/PasswordPolicyTest.java`, `identity/EmailsTest.java`, `identity/UserTenantScopingIT.java`

**Interfaces:**
- Consumes: from Task 2, `TenantOwnedEntity`, `Ids`, `TenantContext` and `ApiProblem`.
- Produces `User`:
  - `User.registerOwner(UUID id, String email, String passwordHash, String firstName, String lastName, UUID ownerRoleId)`;
  - getters `getEmail()`, `getPasswordHash()`, `getFirstName()`, `getLastName()`, `getStatus()`, `getTokenVersion()`, `getRoleIds(): Set<UUID>`, `isEmailVerified()`;
  - mutators `markEmailVerified(Instant)`, `recordLogin(Instant)`, `bumpTokenVersion()`.
- Produces `enum UserStatus { INVITED, ACTIVE, DISABLED }`.
- Produces `UserRepository.findByEmail(String normalizedEmail): Optional<User>`.
- Produces `RefreshToken`:
  - `RefreshToken.issue(UUID id, UUID userId, UUID familyId, String tokenHash, Instant expiresAt, String ip, String userAgent)`;
  - `isActive(Instant)`, `rotatedWithin(Duration, Instant)`, `markRotated(UUID replacedBy, Instant)`;
  - getters `getUserId()`, `getFamilyId()`, `getExpiresAt()`, `getRevokeReason()`.
- Produces `RefreshTokenRepository`:
  - `findByTokenHash(String)`;
  - `revokeFamily(UUID familyId, RevokeReason, Instant): int`;
  - `revokeAllForUser(UUID userId, RevokeReason, Instant): int`.
- Produces `EmailVerification`:
  - `EmailVerification.issue(UUID id, UUID userId, String tokenHash, Instant expiresAt)`;
  - `isUsable(Instant)`, `markUsed(Instant)`, `getUserId()`.
- Produces `EmailVerificationRepository.findByTokenHash(String)` and `invalidateOpenForUser(UUID userId, Instant now)`.
- Produces `OpaqueTokens`:
  - `generate(UUID tenantId): String`;
  - `hash(String token): String`, the 64-character lowercase hex SHA-256;
  - `parse(String token): Optional<OpaqueTokens.Parsed>`, where `record Parsed(UUID tenantId, String hash)`.
- Produces `PasswordPolicy.check(String password, String email)`, which throws a 400 with field `password`.
- Produces `Emails.normalize(String raw): String`, which throws a 400 with field `email`.

- [ ] **Step 1: Write the failing unit tests**

`backend/src/test/java/com/nexusops/identity/OpaqueTokensTest.java`:
```java
package com.nexusops.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexusops.identity.application.OpaqueTokens;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OpaqueTokensTest {

    final UUID tenant = UUID.randomUUID();

    @Test
    void generatesTenantPrefixedHighEntropyTokens() {
        String token = OpaqueTokens.generate(tenant);
        assertThat(token).startsWith(tenant + ".");
        assertThat(token.substring(37)).matches("[A-Za-z0-9_-]{43}");
        assertThat(OpaqueTokens.generate(tenant)).isNotEqualTo(token);
    }

    @Test
    void hashIsStableLowercaseHexSha256() {
        String token = OpaqueTokens.generate(tenant);
        assertThat(OpaqueTokens.hash(token)).matches("[0-9a-f]{64}").isEqualTo(OpaqueTokens.hash(token));
    }

    @Test
    void parseRoundTripsTenantAndHash() {
        String token = OpaqueTokens.generate(tenant);
        var parsed = OpaqueTokens.parse(token).orElseThrow();
        assertThat(parsed.tenantId()).isEqualTo(tenant);
        assertThat(parsed.hash()).isEqualTo(OpaqueTokens.hash(token));
    }

    @Test
    void swappingTheTenantPrefixChangesTheHash() {
        String token = OpaqueTokens.generate(tenant);
        String tampered = UUID.randomUUID() + token.substring(36);
        assertThat(OpaqueTokens.parse(tampered).orElseThrow().hash()).isNotEqualTo(OpaqueTokens.hash(token));
    }

    @Test
    void rejectsGarbage() {
        assertThat(OpaqueTokens.parse(null)).isEmpty();
        assertThat(OpaqueTokens.parse("")).isEmpty();
        assertThat(OpaqueTokens.parse("no-dot")).isEmpty();
        assertThat(OpaqueTokens.parse("not-a-uuid.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")).isEmpty();
        assertThat(OpaqueTokens.parse(tenant + ".short")).isEmpty();
        assertThat(OpaqueTokens.parse(tenant + "." + "A".repeat(500))).isEmpty();
    }
}
```

`backend/src/test/java/com/nexusops/identity/PasswordPolicyTest.java`:
```java
package com.nexusops.identity;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexusops.identity.application.PasswordPolicy;
import com.nexusops.shared.web.ApiProblem;
import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    final PasswordPolicy policy = new PasswordPolicy();

    @Test
    void acceptsAStrongPassword() {
        assertThatCode(() -> policy.check("correct horse battery staple", "owner@acme.test")).doesNotThrowAnyException();
    }

    @Test
    void rejectsShortLongCommonAndEmailPasswords() {
        assertRejected("short1!", "a@b.test");
        assertRejected("x".repeat(129), "a@b.test");
        assertRejected("Password1234", "a@b.test");
        assertRejected("owner@acme.test", "owner@acme.test");
        assertRejected(null, "a@b.test");
    }

    private void assertRejected(String password, String email) {
        assertThatThrownBy(() -> policy.check(password, email))
                .isInstanceOfSatisfying(ApiProblem.class,
                        p -> org.assertj.core.api.Assertions.assertThat(p.errors().getFirst().field()).isEqualTo("password"));
    }
}
```

`backend/src/test/java/com/nexusops/identity/EmailsTest.java`:
```java
package com.nexusops.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexusops.identity.application.Emails;
import com.nexusops.shared.web.ApiProblem;
import org.junit.jupiter.api.Test;

class EmailsTest {

    @Test
    void trimsAndLowercases() {
        assertThat(Emails.normalize("  Owner@Acme.COM ")).isEqualTo("owner@acme.com");
    }

    @Test
    void rejectsInvalidAddresses() {
        for (String bad : new String[] {null, "", "plain", "a@b", "a b@c.test", "@x.test", "a@" + "x".repeat(260) + ".test"}) {
            assertThatThrownBy(() -> Emails.normalize(bad)).as(String.valueOf(bad)).isInstanceOf(ApiProblem.class);
        }
    }
}
```

Run: `cd backend && ./gradlew test --tests '*OpaqueTokensTest' --tests '*PasswordPolicyTest' --tests '*EmailsTest'`
Expected: compilation FAILS.

- [ ] **Step 2: Implement the helpers**

`backend/src/main/java/com/nexusops/identity/application/OpaqueTokens.java`:
```java
package com.nexusops.identity.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Opaque bearer secrets of the form {tenantId}.{256-bit random}. The tenant prefix lets the server
 * bind RLS context before lookup; only the SHA-256 of the whole token is stored, so tampering with
 * the prefix simply finds nothing (spec §5).
 */
public final class OpaqueTokens {

    public record Parsed(UUID tenantId, String hash) {}

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern RANDOM_PART = Pattern.compile("[A-Za-z0-9_-]{43}");

    private OpaqueTokens() {}

    public static String generate(UUID tenantId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return tenantId + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Optional<Parsed> parse(String token) {
        if (token == null || token.length() != 36 + 1 + 43) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot != 36 || !RANDOM_PART.matcher(token.substring(dot + 1)).matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Parsed(UUID.fromString(token.substring(0, dot)), hash(token)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
```

`backend/src/main/java/com/nexusops/identity/application/Emails.java`:
```java
package com.nexusops.identity.application;

import com.nexusops.shared.web.ApiProblem;
import java.util.Locale;
import java.util.regex.Pattern;

public final class Emails {

    private static final Pattern SHAPE = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private Emails() {}

    public static String normalize(String raw) {
        String email = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        if (email.length() < 3 || email.length() > 254 || !SHAPE.matcher(email).matches()) {
            throw ApiProblem.badRequestField("email", "Enter a valid email address.");
        }
        return email;
    }
}
```

`backend/src/main/java/com/nexusops/identity/application/PasswordPolicy.java`:
```java
package com.nexusops.identity.application;

import com.nexusops.shared.web.ApiProblem;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Length 12–128, not a known-common password, not the email address (spec §6). */
@Component
public class PasswordPolicy {

    private final Set<String> common;

    public PasswordPolicy() {
        this.common = loadCommonPasswords();
    }

    public void check(String password, String email) {
        if (password == null || password.length() < 12) {
            throw invalid("Use at least 12 characters.");
        }
        if (password.length() > 128) {
            throw invalid("Use at most 128 characters.");
        }
        if (common.contains(password.toLowerCase(Locale.ROOT))) {
            throw invalid("This password is too common. Choose another.");
        }
        if (email != null && password.equalsIgnoreCase(email.strip())) {
            throw invalid("Don't use your email address as your password.");
        }
    }

    private static ApiProblem invalid(String message) {
        return ApiProblem.badRequestField("password", message);
    }

    private static Set<String> loadCommonPasswords() {
        var resource = new ClassPathResource("security/common-passwords.txt");
        try (var reader = new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .map(l -> l.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load common password list", e);
        }
    }
}
```

`backend/src/main/resources/security/common-passwords.txt`:
```
# Common passwords of 12+ characters (case-insensitive). Starter list; replace with a larger
# breached-password corpus before commercial launch.
password1234
password123!
passwordpassword
123456789012
123123123123
111111111111
aaaaaaaaaaaa
qwerty123456
qwertyuiop12
qwertyuiopasdf
1q2w3e4r5t6y
1qaz2wsx3edc
zaq12wsxcde3
abc123456789
1234567890ab
iloveyou1234
welcome12345
letmein12345
changeme1234
administrator
adminadmin12
football1234
baseball1234
sunshine1234
princess1234
monkey123456
dragon123456
superman1234
starwars1234
master123456
trustno11234
nexusops1234
```

Run the three unit tests. Expected: PASS (5 + 2 + 2).

- [ ] **Step 3: Write the failing persistence and isolation test**

`backend/src/test/java/com/nexusops/identity/UserTenantScopingIT.java`:
```java
package com.nexusops.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexusops.identity.domain.RefreshToken;
import com.nexusops.identity.domain.RefreshTokenRepository;
import com.nexusops.identity.domain.RevokeReason;
import com.nexusops.identity.domain.User;
import com.nexusops.identity.domain.UserRepository;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** Application-layer isolation (Hibernate @TenantId) on real entities, on top of RLS. */
class UserTenantScopingIT extends IntegrationTestSupport {

    @Autowired UserRepository users;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired TransactionTemplate tx;

    UUID tenantA;
    UUID tenantB;
    UUID userA;

    private static UUID newTenant() {
        UUID id = Ids.newId();
        Timestamp now = Timestamp.from(Instant.now());
        OwnerJdbc.jdbc().update("insert into tenants (id, slug, name, status, plan_code, created_at, updated_at) "
                + "values (?, ?, 'Scope', 'ACTIVE', 'FREE', ?, ?)", id, "sc-" + id.toString().substring(24), now, now);
        return id;
    }

    @BeforeEach
    void seed() {
        tenantA = newTenant();
        tenantB = newTenant();
        userA = Ids.newId();
        TenantContext.runAs(tenantA, () -> tx.executeWithoutResult(s -> users.save(
                User.registerOwner(userA, "owner@a.test", "hash", "Ada", "Owner", null))));
    }

    @Test
    void tenantIdIsStampedFromContext() {
        User loaded = TenantContext.callAs(tenantA, () -> users.findById(userA)).orElseThrow();
        assertThat(loaded.getTenantId()).isEqualTo(tenantA);
    }

    @Test
    void otherTenantCannotFindOrListTheUser() {
        TenantContext.runAs(tenantB, () -> {
            assertThat(users.findById(userA)).isEmpty();
            assertThat(users.findByEmail("owner@a.test")).isEmpty();
            assertThat(users.findAll()).noneMatch(u -> u.getId().equals(userA));
        });
    }

    @Test
    void sameEmailMayExistInDifferentTenants() {
        UUID userB = Ids.newId();
        TenantContext.runAs(tenantB, () -> tx.executeWithoutResult(s -> users.save(
                User.registerOwner(userB, "owner@a.test", "hash", "Bob", "Owner", null))));
        assertThat(TenantContext.callAs(tenantB, () -> users.findByEmail("owner@a.test")))
                .map(User::getId).contains(userB);
    }

    @Test
    void savingWithoutTenantContextFails() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> users.save(
                User.registerOwner(Ids.newId(), "x@x.test", "hash", "X", "X", null))))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void familyRevocationOnlyTouchesTheCurrentTenant() {
        UUID family = Ids.newId();
        Instant now = Instant.now();
        TenantContext.runAs(tenantA, () -> tx.executeWithoutResult(s -> refreshTokens.save(RefreshToken.issue(
                Ids.newId(), userA, family, "a".repeat(64), now.plus(Duration.ofDays(1)), null, null))));
        int fromB = TenantContext.callAs(tenantB,
                () -> tx.execute(s -> refreshTokens.revokeFamily(family, RevokeReason.LOGOUT, now)));
        assertThat(fromB).isZero();
        int fromA = TenantContext.callAs(tenantA,
                () -> tx.execute(s -> refreshTokens.revokeFamily(family, RevokeReason.LOGOUT, now)));
        assertThat(fromA).isOne();
    }
}
```

Run: `./gradlew test --tests '*UserTenantScopingIT'`
Expected: compilation FAILS (missing domain types).

- [ ] **Step 4: Implement the domain**

`backend/src/main/java/com/nexusops/identity/domain/UserStatus.java`:
```java
package com.nexusops.identity.domain;

public enum UserStatus { INVITED, ACTIVE, DISABLED }
```

`backend/src/main/java/com/nexusops/identity/domain/RevokeReason.java`:
```java
package com.nexusops.identity.domain;

public enum RevokeReason { ROTATED, LOGOUT, LOGOUT_ALL, REUSE_DETECTED }
```

`backend/src/main/java/com/nexusops/identity/domain/User.java`:
```java
package com.nexusops.identity.domain;

import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User extends TenantOwnedEntity {

    @Column(nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "token_version", nullable = false)
    private int tokenVersion;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    /** role ids only: identity does not depend on the authorization module's entities. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role_id")
    private Set<UUID> roleIds = new HashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected User() {}

    private User(UUID id) {
        super(id);
    }

    /** The workspace creator: active immediately, but cannot log in until the email is verified. */
    public static User registerOwner(UUID id, String email, String passwordHash, String firstName, String lastName,
            UUID ownerRoleId) {
        User user = new User(id);
        user.email = email;
        user.passwordHash = passwordHash;
        user.firstName = firstName;
        user.lastName = lastName;
        user.status = UserStatus.ACTIVE;
        if (ownerRoleId != null) {
            user.roleIds.add(ownerRoleId);
        }
        user.createdAt = Instant.now();
        user.updatedAt = user.createdAt;
        return user;
    }

    public void markEmailVerified(Instant now) {
        if (emailVerifiedAt == null) {
            emailVerifiedAt = now;
            updatedAt = now;
        }
    }

    public void recordLogin(Instant now) {
        lastLoginAt = now;
    }

    public void bumpTokenVersion() {
        tokenVersion++;
        updatedAt = Instant.now();
    }

    public boolean isEmailVerified() {
        return emailVerifiedAt != null;
    }

    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public UserStatus getStatus() { return status; }
    public int getTokenVersion() { return tokenVersion; }
    public Set<UUID> getRoleIds() { return Set.copyOf(roleIds); }
}
```

`backend/src/main/java/com/nexusops/identity/domain/UserRepository.java`:
```java
package com.nexusops.identity.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    /** Callers pass an already-normalized (lower-case, trimmed) address. */
    Optional<User> findByEmail(String email);
}
```

`backend/src/main/java/com/nexusops/identity/domain/RefreshToken.java`:
```java
package com.nexusops.identity.domain;

import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
public class RefreshToken extends TenantOwnedEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "token_hash", nullable = false, columnDefinition = "bpchar")
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoke_reason")
    private RevokeReason revokeReason;

    @Column(name = "replaced_by")
    private UUID replacedBy;

    @Column(name = "created_ip")
    private String createdIp;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RefreshToken() {}

    private RefreshToken(UUID id) {
        super(id);
    }

    public static RefreshToken issue(UUID id, UUID userId, UUID familyId, String tokenHash, Instant expiresAt,
            String ip, String userAgent) {
        RefreshToken token = new RefreshToken(id);
        token.userId = userId;
        token.familyId = familyId;
        token.tokenHash = tokenHash;
        token.expiresAt = expiresAt;
        token.createdIp = ip;
        token.userAgent = userAgent == null || userAgent.length() <= 512 ? userAgent : userAgent.substring(0, 512);
        token.createdAt = Instant.now();
        return token;
    }

    public boolean isActive(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    /** True if this token was rotated (not revoked for another reason) less than {@code grace} ago. */
    public boolean rotatedWithin(Duration grace, Instant now) {
        return revokeReason == RevokeReason.ROTATED && revokedAt != null && revokedAt.plus(grace).isAfter(now);
    }

    public void markRotated(UUID replacement, Instant now) {
        this.revokedAt = now;
        this.revokeReason = RevokeReason.ROTATED;
        this.replacedBy = replacement;
    }

    public UUID getUserId() { return userId; }
    public UUID getFamilyId() { return familyId; }
    public Instant getExpiresAt() { return expiresAt; }
    public RevokeReason getRevokeReason() { return revokeReason; }
}
```

`backend/src/main/java/com/nexusops/identity/domain/RefreshTokenRepository.java`:
```java
package com.nexusops.identity.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now, t.revokeReason = :reason "
            + "where t.familyId = :familyId and t.revokedAt is null")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("reason") RevokeReason reason, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now, t.revokeReason = :reason "
            + "where t.userId = :userId and t.revokedAt is null")
    int revokeAllForUser(@Param("userId") UUID userId, @Param("reason") RevokeReason reason, @Param("now") Instant now);
}
```

`backend/src/main/java/com/nexusops/identity/domain/EmailVerification.java`:
```java
package com.nexusops.identity.domain;

import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "email_verifications")
public class EmailVerification extends TenantOwnedEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, columnDefinition = "bpchar")
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected EmailVerification() {}

    private EmailVerification(UUID id) {
        super(id);
    }

    public static EmailVerification issue(UUID id, UUID userId, String tokenHash, Instant expiresAt) {
        EmailVerification verification = new EmailVerification(id);
        verification.userId = userId;
        verification.tokenHash = tokenHash;
        verification.expiresAt = expiresAt;
        verification.createdAt = Instant.now();
        return verification;
    }

    public boolean isUsable(Instant now) {
        return usedAt == null && expiresAt.isAfter(now);
    }

    public void markUsed(Instant now) {
        usedAt = now;
    }

    public UUID getUserId() { return userId; }
}
```

`backend/src/main/java/com/nexusops/identity/domain/EmailVerificationRepository.java`:
```java
package com.nexusops.identity.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, UUID> {

    Optional<EmailVerification> findByTokenHash(String tokenHash);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update EmailVerification v set v.usedAt = :now where v.userId = :userId and v.usedAt is null")
    int invalidateOpenForUser(@Param("userId") UUID userId, @Param("now") Instant now);
}
```

- [ ] **Step 5: Run the tests and the build**

Run: `./gradlew test --tests '*UserTenantScopingIT'`, then `./gradlew build`
Expected: PASS (5 tests), then a green build.

If `savingWithoutTenantContextFails` passes for the wrong reason, check the cause chain. It should end in a foreign-key or row-level-security violation for the all-zero tenant. Add `.hasStackTraceContaining` with whichever message actually appears, and ledger it.

- [ ] **Step 6: Commit**

```bash
cd /Users/user/Desktop/nexusops && git add backend && git commit -m "feat(identity): user, refresh-token and verification domain; opaque tokens; password and email rules

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Signup, email verification and resend

**Files:**
- Create:
  - `shared/web/PublicEndpoints.java`;
  - `identity/security/PasswordConfig.java`;
  - `identity/application/SignupService.java`, `identity/application/SignupCommand.java`;
  - `identity/web/AuthController.java`, `identity/web/AuthDtos.java`.
- Modify: `shared/security/SecurityConfig.java` (permit `PublicEndpoints`) and `application.yml` (`nexusops.app.base-url`).
- Test: `support/TestTenants.java`, `identity/SignupIT.java`

**Interfaces:**
- Consumes:
  - From Task 3: `AuditService`, `MailRequested`, `OutgoingMail`.
  - From Task 4: `TenantDirectory.register` and `TenantDirectory.activateCurrent`.
  - From Task 5: `AuthorizationService.createSystemRoles`.
  - From Task 7: the identity domain, `OpaqueTokens`, `PasswordPolicy`, `Emails`.
- Produces:
  - `PublicEndpoints.ROUTES: Set<String>`, as `"METHOD /path"`;
  - `PublicEndpoints.matchers(): RequestMatcher[]`.
- Produces the `PasswordEncoder` bean (Argon2id).
- Produces `SignupService`:
  - `signup(SignupCommand): SignupService.SignupResult(UUID tenantId, UUID userId, String slug)`;
  - `verifyEmail(String token)`;
  - `resendVerification(String workspace, String email)`.
- Produces these HTTP endpoints:
  - `POST /api/v1/auth/signup`, which returns 201 `{"slug","status"}`;
  - `POST /api/v1/auth/verify-email`, which takes `{"token"}` and returns 204;
  - `POST /api/v1/auth/resend-verification`, which takes `{"workspace","email"}` and always returns 202.
- Produces test helpers:
  - `TestTenants.signup(MockMvc, String slug): Workspace`;
  - `TestTenants.signupAndVerify(MockMvc, RecordingMailSender, String slug): Workspace`;
  - `record Workspace(UUID tenantId, String slug, String email, String password)`.

- [ ] **Step 1: Public endpoint registry, password encoder and base URL**

`backend/src/main/java/com/nexusops/shared/web/PublicEndpoints.java`:
```java
package com.nexusops.shared.web;

import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * The ONLY unauthenticated API routes. Security config permits exactly these, and
 * EndpointAuthorizationCoverageTest requires every other handler to declare @PreAuthorize.
 */
public final class PublicEndpoints {

    public static final Set<String> ROUTES = Set.of(
            "POST /api/v1/auth/signup",
            "POST /api/v1/auth/verify-email",
            "POST /api/v1/auth/resend-verification",
            "POST /api/v1/auth/login",
            "POST /api/v1/auth/refresh",
            "POST /api/v1/auth/logout");

    private PublicEndpoints() {}

    public static RequestMatcher[] matchers() {
        return ROUTES.stream()
                .map(route -> route.split(" ", 2))
                .map(parts -> PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.valueOf(parts[0]), parts[1]))
                .toArray(RequestMatcher[]::new);
    }
}
```

In `shared/security/SecurityConfig.java`, change the authorization rules to:
```java
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .requestMatchers(com.nexusops.shared.web.PublicEndpoints.matchers()).permitAll()
                        .anyRequest().authenticated())
```

`backend/src/main/java/com/nexusops/identity/security/PasswordConfig.java`:
```java
package com.nexusops.identity.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
class PasswordConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }
}
```

Add to `application.yml` under `nexusops:`:
```yaml
  app:
    base-url: ${APP_BASE_URL:http://localhost:5173}
```

- [ ] **Step 2: Test helper and failing tests**

`backend/src/test/java/com/nexusops/support/TestTenants.java`:
```java
package com.nexusops.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Creates real workspaces through the public API. */
public final class TestTenants {

    public static final String PASSWORD = "correct horse battery staple";

    public record Workspace(UUID tenantId, String slug, String email, String password) {}

    private TestTenants() {}

    public static String uniqueSlug(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static Workspace signup(MockMvc mvc, String slug) throws Exception {
        String email = "owner@" + slug + ".test";
        mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content("""
                {"workspaceName":"%s Inc","slug":"%s","firstName":"Ada","lastName":"Owner","email":"%s","password":"%s"}
                """.formatted(slug, slug, email, PASSWORD)))
                .andExpect(status().isCreated());
        UUID tenantId = OwnerJdbc.jdbc().queryForObject("select id from tenants where slug = ?", UUID.class, slug);
        return new Workspace(tenantId, slug, email, PASSWORD);
    }

    public static Workspace signupAndVerify(MockMvc mvc, RecordingMailSender mail, String slug) throws Exception {
        Workspace workspace = signup(mvc, slug);
        mvc.perform(post("/api/v1/auth/verify-email").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + mail.lastTokenFor(workspace.email()) + "\"}"))
                .andExpect(status().isNoContent());
        return workspace;
    }
}
```

`backend/src/test/java/com/nexusops/identity/SignupIT.java`:
```java
package com.nexusops.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.identity.domain.User;
import com.nexusops.identity.domain.UserRepository;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestTenants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfigureMockMvc
class SignupIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired UserRepository users;
    @Autowired TransactionTemplate tx;

    private ResultActions signup(String json) throws Exception {
        return mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String body(String slug, String email, String password) {
        return """
                {"workspaceName":"Acme","slug":"%s","firstName":"Ada","lastName":"Owner","email":"%s","password":"%s"}
                """.formatted(slug, email, password);
    }

    @Test
    void signupCreatesPendingWorkspaceOwnerRolesAuditAndMail() throws Exception {
        String slug = TestTenants.uniqueSlug("acme");
        signup(body(slug, "owner@" + slug + ".test", TestTenants.PASSWORD))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value(slug))
                .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"));

        var tenantId = OwnerJdbc.jdbc().queryForObject("select id from tenants where slug = ?", java.util.UUID.class, slug);
        var owner = OwnerJdbc.ownerAs(tenantId);
        assertThat(owner.queryForObject("""
                select r.name from users u join user_roles ur on ur.user_id = u.id join roles r on r.id = ur.role_id
                where u.email = ?""", String.class, "owner@" + slug + ".test")).isEqualTo("TENANT_OWNER");
        assertThat(owner.queryForList("select action from audit_events order by occurred_at", String.class))
                .contains("TenantCreated", "UserRegistered");
        assertThat(owner.queryForObject("select password_hash from users", String.class)).startsWith("$argon2id$");
        assertThat(mail.sentTo("owner@" + slug + ".test")).singleElement()
                .satisfies(m -> assertThat(m.textBody()).contains("/verify-email?token="));
    }

    @Test
    void emailIsNormalized() throws Exception {
        String slug = TestTenants.uniqueSlug("norm");
        signup(body(slug, "  Owner@" + slug.toUpperCase() + ".TEST ", TestTenants.PASSWORD)).andExpect(status().isCreated());
        var tenantId = OwnerJdbc.jdbc().queryForObject("select id from tenants where slug = ?", java.util.UUID.class, slug);
        assertThat(OwnerJdbc.ownerAs(tenantId).queryForObject("select email from users", String.class))
                .isEqualTo("owner@" + slug + ".test");
        assertThat(mail.sentTo("owner@" + slug + ".test")).hasSize(1);
    }

    @Test
    void duplicateEmailCaseVariantRejected() throws Exception {
        var workspace = TestTenants.signup(mvc, TestTenants.uniqueSlug("dupe"));
        // Same normalized email in the same tenant violates UNIQUE (tenant_id, email);
        // a non-normalized email violates the CHECK constraint. (Invitations in Plan 3 surface these as 409.)
        assertThatThrownBy(() -> TenantContext.runAs(workspace.tenantId(), () -> tx.executeWithoutResult(s ->
                users.saveAndFlush(User.registerOwner(Ids.newId(), workspace.email(), "h", "X", "Y", null)))))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> TenantContext.runAs(workspace.tenantId(), () -> tx.executeWithoutResult(s ->
                users.saveAndFlush(User.registerOwner(Ids.newId(), workspace.email().toUpperCase(), "h", "X", "Y", null)))))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void duplicateSlugIsAConflict() throws Exception {
        String slug = TestTenants.uniqueSlug("taken");
        TestTenants.signup(mvc, slug);
        signup(body(slug.toUpperCase(), "other@x.test", TestTenants.PASSWORD))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].field").value("slug"));
    }

    @Test
    void invalidInputIsReportedPerField() throws Exception {
        String slug = TestTenants.uniqueSlug("bad");
        signup(body(slug, "owner@x.test", "Password1234")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("password"));
        signup(body(slug, "not-an-email", TestTenants.PASSWORD)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("email"));
        signup(body("api", "owner@x.test", TestTenants.PASSWORD)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("slug"));
        signup("{\"slug\":\"" + slug + "\"}").andExpect(status().isBadRequest());
        assertThat(OwnerJdbc.jdbc().queryForObject("select count(*) from tenants where slug = ?", Long.class, slug)).isZero();
    }

    @Test
    void verificationActivatesWorkspaceAndIsSingleUse() throws Exception {
        var workspace = TestTenants.signup(mvc, TestTenants.uniqueSlug("verify"));
        String token = mail.lastTokenFor(workspace.email());
        verify(token).andExpect(status().isNoContent());
        assertThat(OwnerJdbc.jdbc().queryForObject("select status from tenants where id = ?", String.class, workspace.tenantId()))
                .isEqualTo("ACTIVE");
        assertThat(OwnerJdbc.ownerAs(workspace.tenantId()).queryForObject(
                "select email_verified_at is not null from users", Boolean.class)).isTrue();
        verify(token).andExpect(status().isBadRequest());
    }

    @Test
    void verificationRejectsExpiredTamperedAndGarbageTokens() throws Exception {
        var workspace = TestTenants.signup(mvc, TestTenants.uniqueSlug("expire"));
        String token = mail.lastTokenFor(workspace.email());
        String tampered = java.util.UUID.randomUUID() + token.substring(36);
        verify(tampered).andExpect(status().isBadRequest());
        verify("garbage").andExpect(status().isBadRequest());
        OwnerJdbc.ownerAs(workspace.tenantId()).update("update email_verifications set expires_at = now() - interval '1 minute'");
        verify(token).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("This verification link is invalid or has expired."));
    }

    @Test
    void resendIsAlwaysAcceptedAndReplacesThePreviousLink() throws Exception {
        var workspace = TestTenants.signup(mvc, TestTenants.uniqueSlug("resend"));
        String first = mail.lastTokenFor(workspace.email());

        resend("no-such-workspace", workspace.email()).andExpect(status().isAccepted());
        resend(workspace.slug(), "nobody@x.test").andExpect(status().isAccepted());
        assertThat(mail.sentTo(workspace.email())).hasSize(1);

        resend(workspace.slug(), workspace.email()).andExpect(status().isAccepted());
        String second = mail.lastTokenFor(workspace.email());
        assertThat(second).isNotEqualTo(first);
        verify(first).andExpect(status().isBadRequest());
        verify(second).andExpect(status().isNoContent());

        resend(workspace.slug(), workspace.email()).andExpect(status().isAccepted());
        assertThat(mail.sentTo(workspace.email())).hasSize(2); // already verified: no further mail
    }

    private ResultActions verify(String token) throws Exception {
        return mvc.perform(post("/api/v1/auth/verify-email").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}"));
    }

    private ResultActions resend(String workspace, String email) throws Exception {
        return mvc.perform(post("/api/v1/auth/resend-verification").contentType(MediaType.APPLICATION_JSON)
                .content("{\"workspace\":\"" + workspace + "\",\"email\":\"" + email + "\"}"));
    }
}
```

Run: `cd backend && ./gradlew test --tests '*SignupIT'`
Expected: compilation FAILS (missing `SignupService`, `AuthController`, `PublicEndpoints`).

- [ ] **Step 3: Implement SignupService**

`backend/src/main/java/com/nexusops/identity/application/SignupCommand.java`:
```java
package com.nexusops.identity.application;

public record SignupCommand(String workspaceName, String slug, String firstName, String lastName, String email,
        String password) {}
```

`backend/src/main/java/com/nexusops/identity/application/SignupService.java`:
```java
package com.nexusops.identity.application;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.authorization.AuthorizationService;
import com.nexusops.identity.domain.EmailVerification;
import com.nexusops.identity.domain.EmailVerificationRepository;
import com.nexusops.identity.domain.User;
import com.nexusops.identity.domain.UserRepository;
import com.nexusops.identity.domain.UserStatus;
import com.nexusops.notifications.MailRequested;
import com.nexusops.notifications.OutgoingMail;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.web.ApiProblem;
import com.nexusops.tenancy.TenantDirectory;
import com.nexusops.tenancy.TenantSummary;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Self-serve workspace signup and email verification (spec §6). */
@Service
public class SignupService {

    public record SignupResult(UUID tenantId, UUID userId, String slug) {}

    private static final Duration VERIFICATION_TTL = Duration.ofHours(24);

    private final TenantDirectory tenants;
    private final AuthorizationService authorization;
    private final UserRepository users;
    private final EmailVerificationRepository verifications;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final String appBaseUrl;

    SignupService(TenantDirectory tenants, AuthorizationService authorization, UserRepository users,
            EmailVerificationRepository verifications, PasswordEncoder passwordEncoder, PasswordPolicy passwordPolicy,
            AuditService audit, ApplicationEventPublisher events, TransactionTemplate tx,
            @Value("${nexusops.app.base-url}") String appBaseUrl) {
        this.tenants = tenants;
        this.authorization = authorization;
        this.users = users;
        this.verifications = verifications;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.audit = audit;
        this.events = events;
        this.tx = tx;
        this.appBaseUrl = appBaseUrl;
    }

    public SignupResult signup(SignupCommand command) {
        String email = Emails.normalize(command.email());
        passwordPolicy.check(command.password(), email);
        String firstName = requireName(command.firstName(), "firstName");
        String lastName = requireName(command.lastName(), "lastName");
        String passwordHash = passwordEncoder.encode(command.password()); // slow: outside the transaction

        UUID tenantId = Ids.newId();
        UUID userId = Ids.newId();
        try (var scope = TenantContext.open(tenantId, userId)) {
            return tx.execute(status -> {
                TenantSummary tenant = tenants.register(tenantId, command.slug(), command.workspaceName());
                UUID ownerRole = authorization.createSystemRoles();
                users.save(User.registerOwner(userId, email, passwordHash, firstName, lastName, ownerRole));
                issueVerification(userId, email, firstName, tenant.slug());
                audit.record(AuditEntry.of("TenantCreated", "Tenant", tenantId)
                        .withAfter(Map.of("slug", tenant.slug(), "name", tenant.name())));
                audit.record(AuditEntry.of("UserRegistered", "User", userId).withAfter(Map.of("email", email)));
                return new SignupResult(tenantId, userId, tenant.slug());
            });
        }
    }

    public void verifyEmail(String token) {
        OpaqueTokens.Parsed parsed = OpaqueTokens.parse(token).orElseThrow(SignupService::invalidLink);
        Instant now = Instant.now();
        try (var scope = TenantContext.open(parsed.tenantId(), null)) {
            tx.executeWithoutResult(status -> {
                EmailVerification verification = verifications.findByTokenHash(parsed.hash())
                        .filter(v -> v.isUsable(now))
                        .orElseThrow(SignupService::invalidLink);
                verification.markUsed(now);
                User user = users.findById(verification.getUserId()).orElseThrow(SignupService::invalidLink);
                user.markEmailVerified(now);
                tenants.activateCurrent();
                audit.record(AuditEntry.of("EmailVerified", "User", user.getId()));
            });
        }
    }

    /** Always succeeds silently: never reveals whether the workspace or address exists. */
    public void resendVerification(String workspace, String rawEmail) {
        Optional<TenantSummary> tenant = tenants.findBySlug(workspace);
        if (tenant.isEmpty()) {
            return;
        }
        String email;
        try {
            email = Emails.normalize(rawEmail);
        } catch (ApiProblem invalid) {
            return;
        }
        try (var scope = TenantContext.open(tenant.get().id(), null)) {
            tx.executeWithoutResult(status -> users.findByEmail(email)
                    .filter(u -> !u.isEmailVerified() && u.getStatus() == UserStatus.ACTIVE)
                    .ifPresent(user -> {
                        verifications.invalidateOpenForUser(user.getId(), Instant.now());
                        issueVerification(user.getId(), email, user.getFirstName(), tenant.get().slug());
                    }));
        }
    }

    private void issueVerification(UUID userId, String email, String firstName, String slug) {
        String token = OpaqueTokens.generate(TenantContext.requireTenantId());
        verifications.save(EmailVerification.issue(Ids.newId(), userId, OpaqueTokens.hash(token),
                Instant.now().plus(VERIFICATION_TTL)));
        String link = appBaseUrl + "/verify-email?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        events.publishEvent(new MailRequested(new OutgoingMail(email, "Verify your NexusOps workspace", """
                Hi %s,

                Confirm your email address to activate the workspace "%s":
                %s

                This link expires in 24 hours. If you didn't create this workspace, you can ignore this email.
                """.formatted(firstName, slug, link))));
    }

    private static String requireName(String raw, String field) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty() || name.length() > 80) {
            throw ApiProblem.badRequestField(field, "Enter between 1 and 80 characters.");
        }
        return name;
    }

    private static ApiProblem invalidLink() {
        return ApiProblem.badRequest("This verification link is invalid or has expired.");
    }
}
```

- [ ] **Step 4: Implement the DTOs and the controller**

`backend/src/main/java/com/nexusops/identity/web/AuthDtos.java`:
```java
package com.nexusops.identity.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

final class AuthDtos {

    private AuthDtos() {}

    record SignupRequest(
            @NotBlank @Size(max = 120) String workspaceName,
            @NotBlank @Size(max = 64) String slug,
            @NotBlank @Size(max = 80) String firstName,
            @NotBlank @Size(max = 80) String lastName,
            @NotBlank @Size(max = 254) String email,
            @NotNull @Size(max = 128) String password) {}

    record SignupResponse(String slug, String status) {}

    record VerifyEmailRequest(@NotBlank @Size(max = 200) String token) {}

    record ResendVerificationRequest(@NotBlank @Size(max = 64) String workspace, @NotBlank @Size(max = 254) String email) {}
}
```

`backend/src/main/java/com/nexusops/identity/web/AuthController.java`:
```java
package com.nexusops.identity.web;

import com.nexusops.identity.application.SignupCommand;
import com.nexusops.identity.application.SignupService;
import com.nexusops.identity.web.AuthDtos.ResendVerificationRequest;
import com.nexusops.identity.web.AuthDtos.SignupRequest;
import com.nexusops.identity.web.AuthDtos.SignupResponse;
import com.nexusops.identity.web.AuthDtos.VerifyEmailRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Public authentication routes — each is listed in PublicEndpoints. */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    private final SignupService signup;

    AuthController(SignupService signup) {
        this.signup = signup;
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    SignupResponse signup(@Valid @RequestBody SignupRequest request) {
        var result = signup.signup(new SignupCommand(request.workspaceName(), request.slug(), request.firstName(),
                request.lastName(), request.email(), request.password()));
        return new SignupResponse(result.slug(), "PENDING_VERIFICATION");
    }

    @PostMapping("/verify-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        signup.verifyEmail(request.token());
    }

    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void resendVerification(@Valid @RequestBody ResendVerificationRequest request) {
        signup.resendVerification(request.workspace(), request.email());
    }
}
```

- [ ] **Step 5: Run the tests and the build**

Run: `./gradlew test --tests '*SignupIT'`, then `./gradlew build`
Expected: PASS (8 tests), then a green build. `ModularityTest` must pass; `identity` depends on `shared`, `tenancy`, `authorization`, `audit` and `notifications`. `PlatformFoundationIT.unknownApiRouteRequiresAuthenticationAsProblemJson` must still return 401.

- [ ] **Step 6: Commit**

```bash
cd /Users/user/Desktop/nexusops && git add backend && git commit -m "feat(identity): self-serve signup, email verification and resend

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Login, refresh rotation with reuse detection, logout

**Files:**
- Create:
  - `identity/application/ClientInfo.java`, `identity/application/AuthResult.java`;
  - `identity/application/LoginService.java`, `identity/application/RefreshService.java`;
  - `identity/web/RefreshCookies.java`, `identity/web/OriginGuard.java`.
- Modify:
  - `identity/web/AuthController.java` and `identity/web/AuthDtos.java` (login, refresh, logout);
  - `identity/domain/RefreshTokenRepository.java` (locking lookup);
  - `application.yml` (`nexusops.security.allowed-origins`).
- Test: `support/TestTenants.java` (add `login`, `Session`, `refreshCookieOf`), `identity/LoginIT.java`, `identity/RefreshTokenIT.java`.

**Interfaces:**
- Consumes:
  - From Task 6: `AccessTokenService.issue` and `JwtProperties.refreshTokenTtl()`.
  - From Task 7: the identity domain and `OpaqueTokens`.
  - From Task 4: `TenantDirectory.findBySlug` and `TenantDirectory.current`.
- Produces:
  - `record ClientInfo(String ip, String userAgent)`;
  - `record AuthResult(UUID tenantId, UUID userId, String accessToken, Instant accessTokenExpiresAt, String refreshToken, Instant refreshTokenExpiresAt)`.
- Produces:
  - `LoginService.login(String workspace, String email, String password, ClientInfo): AuthResult`;
  - `RefreshService.refresh(String refreshToken, ClientInfo): AuthResult`;
  - `RefreshService.logout(String refreshToken)`.
- Produces `RefreshService.REUSE_GRACE = Duration.ofSeconds(10)`.
- Produces these HTTP endpoints. Each successful response sets the `nexus_rt` cookie and returns `{"accessToken","tokenType":"Bearer","expiresIn"}`.
  - `POST /api/v1/auth/login`, body `{workspace, email, password}`, returns 200.
  - `POST /api/v1/auth/refresh` (cookie), returns 200.
  - `POST /api/v1/auth/logout` (cookie), returns 204 and clears the cookie.
- Produces test helpers:
  - `TestTenants.login(MockMvc, Workspace): Session`, where `record Session(String accessToken, String refreshToken)`;
  - `TestTenants.refreshCookieOf(MvcResult): String`.

- [ ] **Step 1: Configuration and helpers**

Add to `application.yml` under `nexusops.security:`:
```yaml
    allowed-origins: ${ALLOWED_ORIGINS:http://localhost:5173,http://localhost:3000}
```

Add to `TestTenants`:
```java
    public record Session(String accessToken, String refreshToken) {}

    private static final java.util.regex.Pattern ACCESS = java.util.regex.Pattern.compile("\"accessToken\":\"([^\"]+)\"");
    private static final java.util.regex.Pattern COOKIE = java.util.regex.Pattern.compile("nexus_rt=([^;]*)");

    public static Session login(MockMvc mvc, Workspace workspace) throws Exception {
        var result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"workspace":"%s","email":"%s","password":"%s"}""".formatted(workspace.slug(), workspace.email(), workspace.password())))
                .andExpect(status().isOk())
                .andReturn();
        return new Session(accessTokenOf(result), refreshCookieOf(result));
    }

    public static String accessTokenOf(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        var matcher = ACCESS.matcher(result.getResponse().getContentAsString());
        if (!matcher.find()) throw new AssertionError("no accessToken in response");
        return matcher.group(1);
    }

    public static String refreshCookieOf(org.springframework.test.web.servlet.MvcResult result) {
        for (String header : result.getResponse().getHeaders("Set-Cookie")) {
            var matcher = COOKIE.matcher(header);
            if (matcher.find()) return matcher.group(1);
        }
        throw new AssertionError("no nexus_rt cookie set");
    }
```

- [ ] **Step 2: Write the failing tests**

`backend/src/test/java/com/nexusops/identity/LoginIT.java`:
```java
package com.nexusops.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Workspace;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@AutoConfigureMockMvc
class LoginIT extends IntegrationTestSupport {

    static final String GENERIC = "Invalid workspace, email or password.";

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired JwtDecoder jwtDecoder;

    private ResultActions login(String workspace, String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"workspace":"%s","email":"%s","password":"%s"}""".formatted(workspace, email, password)));
    }

    @Test
    void successfulLoginReturnsAccessTokenAndHardenedRefreshCookie() throws Exception {
        Workspace ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("login"));
        var result = login(ws.slug(), ws.email(), ws.password())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andReturn();

        String setCookie = String.join("\n", result.getResponse().getHeaders("Set-Cookie"));
        assertThat(setCookie).contains("nexus_rt=").contains("HttpOnly").contains("Secure")
                .contains("SameSite=Strict").contains("Path=/api/v1/auth").contains("Max-Age=");

        var jwt = jwtDecoder.decode(TestTenants.accessTokenOf(result));
        assertThat(jwt.getClaimAsString("tid")).isEqualTo(ws.tenantId().toString());

        var owner = OwnerJdbc.ownerAs(ws.tenantId());
        assertThat(owner.queryForObject("select last_login_at is not null from users", Boolean.class)).isTrue();
        assertThat(owner.queryForList("select action from audit_events", String.class)).contains("LoginSucceeded");
    }

    @Test
    void workspaceAndEmailAreCaseInsensitive() throws Exception {
        Workspace ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("case"));
        login(" " + ws.slug().toUpperCase(), ws.email().toUpperCase(), ws.password()).andExpect(status().isOk());
    }

    @Test
    void everyCredentialFailureIsTheSameGeneric401AndIsAudited() throws Exception {
        Workspace ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("fail"));
        login(ws.slug(), ws.email(), "wrong password!!").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value(GENERIC));
        login(ws.slug(), "nobody@x.test", ws.password()).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value(GENERIC));
        login("no-such-workspace", ws.email(), ws.password()).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value(GENERIC));

        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select count(*) from audit_events where action = 'LoginFailed'", Long.class)).isEqualTo(2);
        assertThat(OwnerJdbc.superuser().queryForObject(
                "select count(*) from audit_events where action = 'LoginFailed' and tenant_id is null "
                        + "and metadata->>'workspace' = 'no-such-workspace'", Long.class)).isPositive();
    }

    @Test
    void unverifiedEmailIsRevealedOnlyAfterTheCorrectPassword() throws Exception {
        Workspace ws = TestTenants.signup(mvc, TestTenants.uniqueSlug("unver"));
        login(ws.slug(), ws.email(), "wrong password!!").andExpect(status().isUnauthorized());
        login(ws.slug(), ws.email(), ws.password()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Email address not verified."));
    }

    @Test
    void suspendedWorkspaceCannotLogIn() throws Exception {
        Workspace ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("susp"));
        OwnerJdbc.jdbc().update("update tenants set status = 'SUSPENDED' where id = ?", ws.tenantId());
        login(ws.slug(), ws.email(), ws.password()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Workspace suspended."));
    }
}
```

`backend/src/test/java/com/nexusops/identity/RefreshTokenIT.java`:
```java
package com.nexusops.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.identity.application.OpaqueTokens;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Session;
import com.nexusops.support.TestTenants.Workspace;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@AutoConfigureMockMvc
class RefreshTokenIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;

    Workspace ws;
    Session session;

    @BeforeEach
    void login() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("rt"));
        session = TestTenants.login(mvc, ws);
    }

    private ResultActions refresh(String token) throws Exception {
        var request = post("/api/v1/auth/refresh");
        if (token != null) request.cookie(new Cookie("nexus_rt", token));
        return mvc.perform(request);
    }

    private String rotate(String token) throws Exception {
        return TestTenants.refreshCookieOf(refresh(token).andExpect(status().isOk()).andReturn());
    }

    @Test
    void refreshRotatesTheTokenAndIssuesANewAccessToken() throws Exception {
        String second = rotate(session.refreshToken());
        assertThat(second).isNotEqualTo(session.refreshToken());
        String third = rotate(second);
        assertThat(third).isNotEqualTo(second);
    }

    @Test
    void rotationKeepsTheFamilyAbsoluteExpiry() throws Exception {
        String second = rotate(session.refreshToken());
        var owner = OwnerJdbc.ownerAs(ws.tenantId());
        var first = owner.queryForObject("select expires_at from refresh_tokens where token_hash = ?",
                java.sql.Timestamp.class, OpaqueTokens.hash(session.refreshToken()));
        var next = owner.queryForObject("select expires_at from refresh_tokens where token_hash = ?",
                java.sql.Timestamp.class, OpaqueTokens.hash(second));
        assertThat(next).isEqualTo(first);
    }

    @Test
    void concurrentRefreshWithinGraceDoesNotRevokeFamily() throws Exception {
        String second = rotate(session.refreshToken());
        refresh(session.refreshToken()).andExpect(status().isUnauthorized()); // the "other tab"
        rotate(second); // the family is still alive
    }

    @Test
    void reuseAfterGraceRevokesTheWholeFamilyAndIsAudited() throws Exception {
        String second = rotate(session.refreshToken());
        var owner = OwnerJdbc.ownerAs(ws.tenantId());
        owner.update("update refresh_tokens set revoked_at = now() - interval '1 minute' where token_hash = ?",
                OpaqueTokens.hash(session.refreshToken()));

        refresh(session.refreshToken()).andExpect(status().isUnauthorized());
        refresh(second).andExpect(status().isUnauthorized());
        assertThat(owner.queryForList("select action from audit_events", String.class)).contains("RefreshTokenReuseDetected");
        assertThat(owner.queryForObject("select count(*) from refresh_tokens where revoke_reason = 'REUSE_DETECTED'",
                Long.class)).isPositive();
    }

    @Test
    void expiredMissingAndTamperedTokensAreRejected() throws Exception {
        refresh(null).andExpect(status().isUnauthorized());
        refresh("garbage").andExpect(status().isUnauthorized());
        refresh(java.util.UUID.randomUUID() + session.refreshToken().substring(36)).andExpect(status().isUnauthorized());
        OwnerJdbc.ownerAs(ws.tenantId()).update("update refresh_tokens set expires_at = now() - interval '1 second'");
        refresh(session.refreshToken()).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesTheFamilyAndClearsTheCookie() throws Exception {
        String second = rotate(session.refreshToken());
        mvc.perform(post("/api/v1/auth/logout").cookie(new Cookie("nexus_rt", second)))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
        refresh(second).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/logout")).andExpect(status().isNoContent()); // idempotent without cookie
    }

    @Test
    void crossSiteOriginIsRejectedButSameOriginAllowed() throws Exception {
        mvc.perform(post("/api/v1/auth/refresh").header("Origin", "https://evil.example")
                        .cookie(new Cookie("nexus_rt", session.refreshToken())))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/refresh").header("Origin", "http://localhost:5173")
                        .cookie(new Cookie("nexus_rt", session.refreshToken())))
                .andExpect(status().isOk());
    }
}
```

Run: `cd backend && ./gradlew test --tests '*LoginIT' --tests '*RefreshTokenIT'`
Expected: compilation FAILS.

- [ ] **Step 3: Locking lookup for refresh**

Add to `RefreshTokenRepository`:
```java
    /** Row lock: concurrent refreshes of the same token serialize, so only one can rotate it. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.tokenHash = :hash")
    Optional<RefreshToken> findForUpdateByTokenHash(@Param("hash") String hash);
```

- [ ] **Step 4: Implement the services**

`backend/src/main/java/com/nexusops/identity/application/ClientInfo.java`:
```java
package com.nexusops.identity.application;

public record ClientInfo(String ip, String userAgent) {}
```

`backend/src/main/java/com/nexusops/identity/application/AuthResult.java`:
```java
package com.nexusops.identity.application;

import java.time.Instant;
import java.util.UUID;

public record AuthResult(UUID tenantId, UUID userId, String accessToken, Instant accessTokenExpiresAt,
        String refreshToken, Instant refreshTokenExpiresAt) {}
```

`backend/src/main/java/com/nexusops/identity/application/LoginService.java`:
```java
package com.nexusops.identity.application;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.identity.domain.RefreshToken;
import com.nexusops.identity.domain.RefreshTokenRepository;
import com.nexusops.identity.domain.User;
import com.nexusops.identity.domain.UserRepository;
import com.nexusops.identity.domain.UserStatus;
import com.nexusops.identity.security.AccessTokenService;
import com.nexusops.identity.security.JwtProperties;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.web.ApiProblem;
import com.nexusops.tenancy.TenantDirectory;
import com.nexusops.tenancy.TenantStatus;
import com.nexusops.tenancy.TenantSummary;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Password login (spec §6): uniform failures, dummy-hash timing, audited outcomes. */
@Service
public class LoginService {

    static final String INVALID_CREDENTIALS = "Invalid workspace, email or password.";

    private final TenantDirectory tenants;
    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenService accessTokens;
    private final JwtProperties jwtProperties;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final String dummyHash;

    LoginService(TenantDirectory tenants, UserRepository users, RefreshTokenRepository refreshTokens,
            PasswordEncoder passwordEncoder, AccessTokenService accessTokens, JwtProperties jwtProperties,
            AuditService audit, TransactionTemplate tx) {
        this.tenants = tenants;
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.accessTokens = accessTokens;
        this.jwtProperties = jwtProperties;
        this.audit = audit;
        this.tx = tx;
        this.dummyHash = passwordEncoder.encode("dummy password used to equalize timing");
    }

    public AuthResult login(String workspace, String rawEmail, String password, ClientInfo client) {
        Optional<TenantSummary> tenant = tenants.findBySlug(workspace);
        String email = normalizeOrNull(rawEmail);
        if (tenant.isEmpty() || email == null) {
            passwordEncoder.matches(password, dummyHash);
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("workspace", truncate(workspace));
            metadata.put("reason", tenant.isEmpty() ? "UNKNOWN_WORKSPACE" : "INVALID_EMAIL");
            audit.recordIndependently(AuditEntry.of("LoginFailed", "Tenant", null).withMetadata(metadata));
            throw ApiProblem.unauthorized(INVALID_CREDENTIALS);
        }
        UUID tenantId = tenant.get().id();

        User user;
        try (var scope = TenantContext.open(tenantId, null)) {
            user = tx.execute(status -> users.findByEmail(email).orElse(null));
            if (user == null) {
                passwordEncoder.matches(password, dummyHash);
                throw fail("UNKNOWN_USER", null);
            }
            if (!passwordEncoder.matches(password, user.getPasswordHash())) {
                throw fail("BAD_PASSWORD", user.getId());
            }
            if (user.getStatus() != UserStatus.ACTIVE) {
                throw fail("USER_" + user.getStatus(), user.getId());
            }
            if (!user.isEmailVerified()) {
                throw ApiProblem.forbidden("Email address not verified.");
            }
            if (tenant.get().status() == TenantStatus.SUSPENDED) {
                fail("WORKSPACE_SUSPENDED", user.getId());
                throw ApiProblem.forbidden("Workspace suspended.");
            }
        }

        UUID userId = user.getId();
        try (var scope = TenantContext.open(tenantId, userId)) {
            return tx.execute(status -> {
                Instant now = Instant.now();
                User managed = users.findById(userId).orElseThrow(() -> ApiProblem.unauthorized(INVALID_CREDENTIALS));
                managed.recordLogin(now);
                String refreshToken = OpaqueTokens.generate(tenantId);
                Instant refreshExpiresAt = now.plus(jwtProperties.refreshTokenTtl());
                refreshTokens.save(RefreshToken.issue(Ids.newId(), userId, Ids.newId(), OpaqueTokens.hash(refreshToken),
                        refreshExpiresAt, client.ip(), client.userAgent()));
                audit.record(AuditEntry.of("LoginSucceeded", "User", userId));
                var access = accessTokens.issue(tenantId, userId, managed.getTokenVersion());
                return new AuthResult(tenantId, userId, access.value(), access.expiresAt(), refreshToken, refreshExpiresAt);
            });
        }
    }

    /** Audits a failed attempt (own transaction) and returns the generic 401 to throw. */
    private ApiProblem fail(String reason, UUID userId) {
        audit.recordIndependently(AuditEntry.of("LoginFailed", "User", userId).withMetadata(Map.of("reason", reason)));
        return ApiProblem.unauthorized(INVALID_CREDENTIALS);
    }

    private static String normalizeOrNull(String rawEmail) {
        try {
            return Emails.normalize(rawEmail);
        } catch (ApiProblem invalid) {
            return null;
        }
    }

    private static String truncate(String value) {
        if (value == null) return "";
        String trimmed = value.strip();
        return trimmed.length() <= 64 ? trimmed : trimmed.substring(0, 64);
    }
}
```

`backend/src/main/java/com/nexusops/identity/application/RefreshService.java`:
```java
package com.nexusops.identity.application;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.identity.domain.RefreshToken;
import com.nexusops.identity.domain.RefreshTokenRepository;
import com.nexusops.identity.domain.RevokeReason;
import com.nexusops.identity.domain.User;
import com.nexusops.identity.domain.UserRepository;
import com.nexusops.identity.domain.UserStatus;
import com.nexusops.identity.security.AccessTokenService;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.web.ApiProblem;
import com.nexusops.tenancy.TenantDirectory;
import com.nexusops.tenancy.TenantStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Refresh-token rotation with family reuse detection (ADR-0003). A rotated token re-presented within
 * {@link #REUSE_GRACE} is treated as a benign multi-tab race (401, family kept); after that it is
 * theft: the whole family is revoked and the event audited.
 */
@Service
public class RefreshService {

    public static final Duration REUSE_GRACE = Duration.ofSeconds(10);
    static final String SESSION_EXPIRED = "Your session has expired. Please sign in again.";

    private record Outcome(AuthResult result, boolean reuseDetected) {}

    private final RefreshTokenRepository refreshTokens;
    private final UserRepository users;
    private final TenantDirectory tenants;
    private final AccessTokenService accessTokens;
    private final AuditService audit;
    private final TransactionTemplate tx;

    RefreshService(RefreshTokenRepository refreshTokens, UserRepository users, TenantDirectory tenants,
            AccessTokenService accessTokens, AuditService audit, TransactionTemplate tx) {
        this.refreshTokens = refreshTokens;
        this.users = users;
        this.tenants = tenants;
        this.accessTokens = accessTokens;
        this.audit = audit;
        this.tx = tx;
    }

    public AuthResult refresh(String token, ClientInfo client) {
        OpaqueTokens.Parsed parsed = OpaqueTokens.parse(token).orElseThrow(RefreshService::expired);
        Outcome outcome;
        try (var scope = TenantContext.open(parsed.tenantId(), null)) {
            outcome = tx.execute(status -> rotate(parsed, client));
        }
        if (outcome == null || outcome.result() == null) {
            throw expired(); // includes reuse: the revocation above has been committed first
        }
        return outcome.result();
    }

    public void logout(String token) {
        OpaqueTokens.parse(token).ifPresent(parsed -> {
            try (var scope = TenantContext.open(parsed.tenantId(), null)) {
                tx.executeWithoutResult(status -> refreshTokens.findByTokenHash(parsed.hash()).ifPresent(existing -> {
                    refreshTokens.revokeFamily(existing.getFamilyId(), RevokeReason.LOGOUT, Instant.now());
                    audit.record(AuditEntry.of("Logout", "User", existing.getUserId()));
                }));
            }
        });
    }

    private Outcome rotate(OpaqueTokens.Parsed parsed, ClientInfo client) {
        Instant now = Instant.now();
        RefreshToken current = refreshTokens.findForUpdateByTokenHash(parsed.hash()).orElse(null);
        if (current == null) {
            return new Outcome(null, false);
        }
        if (!current.isActive(now)) {
            if (current.getRevokeReason() == RevokeReason.ROTATED && !current.rotatedWithin(REUSE_GRACE, now)) {
                refreshTokens.revokeFamily(current.getFamilyId(), RevokeReason.REUSE_DETECTED, now);
                audit.record(AuditEntry.of("RefreshTokenReuseDetected", "User", current.getUserId())
                        .withMetadata(Map.of("familyId", current.getFamilyId().toString())));
                return new Outcome(null, true);
            }
            return new Outcome(null, false);
        }
        User user = users.findById(current.getUserId()).orElse(null);
        if (user == null || user.getStatus() != UserStatus.ACTIVE || !user.isEmailVerified()
                || tenants.current().status() != TenantStatus.ACTIVE) {
            return new Outcome(null, false);
        }

        UUID tenantId = TenantContext.requireTenantId();
        String nextToken = OpaqueTokens.generate(tenantId);
        UUID nextId = Ids.newId();
        refreshTokens.save(RefreshToken.issue(nextId, user.getId(), current.getFamilyId(), OpaqueTokens.hash(nextToken),
                current.getExpiresAt(), client.ip(), client.userAgent()));
        current.markRotated(nextId, now);
        var access = accessTokens.issue(tenantId, user.getId(), user.getTokenVersion());
        return new Outcome(new AuthResult(tenantId, user.getId(), access.value(), access.expiresAt(), nextToken,
                current.getExpiresAt()), false);
    }

    private static ApiProblem expired() {
        return ApiProblem.unauthorized(SESSION_EXPIRED);
    }
}
```

- [ ] **Step 5: Cookies, the origin guard and the controller**

`backend/src/main/java/com/nexusops/identity/web/RefreshCookies.java`:
```java
package com.nexusops.identity.web;

import java.time.Duration;
import java.time.Instant;
import org.springframework.http.ResponseCookie;

/** The refresh-token cookie: httpOnly, Secure, SameSite=Strict, scoped to /api/v1/auth (ADR-0003). */
final class RefreshCookies {

    static final String NAME = "nexus_rt";
    private static final String PATH = "/api/v1/auth";

    private RefreshCookies() {}

    static ResponseCookie issue(String token, Instant expiresAt) {
        Duration maxAge = Duration.between(Instant.now(), expiresAt);
        return base(token).maxAge(maxAge.isNegative() ? Duration.ZERO : maxAge).build();
    }

    static ResponseCookie clear() {
        return base("").maxAge(Duration.ZERO).build();
    }

    private static ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value).httpOnly(true).secure(true).sameSite("Strict").path(PATH);
    }
}
```

`backend/src/main/java/com/nexusops/identity/web/OriginGuard.java`:
```java
package com.nexusops.identity.web;

import com.nexusops.shared.web.ApiProblem;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Defence in depth for cookie-authenticated routes: a browser Origin, if sent, must be ours (threat T8). */
@Component
class OriginGuard {

    private final List<String> allowedOrigins;

    OriginGuard(@Value("${nexusops.security.allowed-origins}") List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins.stream().map(String::strip).toList();
    }

    void check(String origin) {
        if (origin != null && !allowedOrigins.contains(origin)) {
            throw ApiProblem.forbidden("Cross-site request rejected.");
        }
    }
}
```

Add to `AuthDtos`:
```java
    record LoginRequest(
            @NotBlank @Size(max = 64) String workspace,
            @NotBlank @Size(max = 254) String email,
            @NotNull @Size(max = 128) String password) {}

    record TokenResponse(String accessToken, String tokenType, long expiresIn) {}
```

Replace `AuthController` with this version, which keeps the Task 8 handlers and adds login, refresh and logout:
```java
package com.nexusops.identity.web;

import com.nexusops.identity.application.AuthResult;
import com.nexusops.identity.application.ClientInfo;
import com.nexusops.identity.application.LoginService;
import com.nexusops.identity.application.RefreshService;
import com.nexusops.identity.application.SignupCommand;
import com.nexusops.identity.application.SignupService;
import com.nexusops.identity.web.AuthDtos.LoginRequest;
import com.nexusops.identity.web.AuthDtos.ResendVerificationRequest;
import com.nexusops.identity.web.AuthDtos.SignupRequest;
import com.nexusops.identity.web.AuthDtos.SignupResponse;
import com.nexusops.identity.web.AuthDtos.TokenResponse;
import com.nexusops.identity.web.AuthDtos.VerifyEmailRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Public authentication routes — each is listed in PublicEndpoints. */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    private final SignupService signup;
    private final LoginService login;
    private final RefreshService refresh;
    private final OriginGuard originGuard;

    AuthController(SignupService signup, LoginService login, RefreshService refresh, OriginGuard originGuard) {
        this.signup = signup;
        this.login = login;
        this.refresh = refresh;
        this.originGuard = originGuard;
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    SignupResponse signup(@Valid @RequestBody SignupRequest request) {
        var result = signup.signup(new SignupCommand(request.workspaceName(), request.slug(), request.firstName(),
                request.lastName(), request.email(), request.password()));
        return new SignupResponse(result.slug(), "PENDING_VERIFICATION");
    }

    @PostMapping("/verify-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        signup.verifyEmail(request.token());
    }

    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void resendVerification(@Valid @RequestBody ResendVerificationRequest request) {
        signup.resendVerification(request.workspace(), request.email());
    }

    @PostMapping("/login")
    ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return tokens(login.login(request.workspace(), request.email(), request.password(), client(http)));
    }

    @PostMapping("/refresh")
    ResponseEntity<TokenResponse> refresh(
            @CookieValue(name = RefreshCookies.NAME, required = false) String token,
            @RequestHeader(name = HttpHeaders.ORIGIN, required = false) String origin,
            HttpServletRequest http) {
        originGuard.check(origin);
        return tokens(refresh.refresh(token, client(http)));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(
            @CookieValue(name = RefreshCookies.NAME, required = false) String token,
            @RequestHeader(name = HttpHeaders.ORIGIN, required = false) String origin) {
        originGuard.check(origin);
        if (token != null) {
            refresh.logout(token);
        }
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, RefreshCookies.clear().toString()).build();
    }

    private static ResponseEntity<TokenResponse> tokens(AuthResult result) {
        long expiresIn = Duration.between(Instant.now(), result.accessTokenExpiresAt()).toSeconds();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        RefreshCookies.issue(result.refreshToken(), result.refreshTokenExpiresAt()).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new TokenResponse(result.accessToken(), "Bearer", Math.round(expiresIn / 60.0) * 60));
    }

    private static ClientInfo client(HttpServletRequest http) {
        return new ClientInfo(http.getRemoteAddr(), http.getHeader(HttpHeaders.USER_AGENT));
    }
}
```

- [ ] **Step 6: Run the tests and the build**

Run: `./gradlew test --tests '*LoginIT' --tests '*RefreshTokenIT'`, then `./gradlew build`
Expected: PASS (5 + 7), then a green build.

- [ ] **Step 7: Commit**

```bash
cd /Users/user/Desktop/nexusops && git add backend && git commit -m "feat(identity): login, refresh rotation with family reuse detection and grace window, logout

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: Security wiring — JWT resource server, PrincipalFilter, /me, logout-all, coverage, isolation and misuse suites

**Files:**
- Move: `shared/security/SecurityConfig.java` → `identity/security/SecurityConfig.java`. Shared code can't depend on identity, and the new config needs the principal filter.
- Modify: `shared/security/ProblemDetailSecurityHandlers.java` (make `write` public).
- Create in `identity/security/`: `PrincipalState.java`, `PrincipalStateCache.java`, `PrincipalFilter.java`, `CurrentUser.java`, `PublicRouteAwareBearerTokenResolver.java`.
- Create: `identity/application/ProfileService.java`, `identity/web/MeController.java`.
- Modify: `identity/application/RefreshService.java` (`logoutAll`) and `identity/web/AuthController.java` (`POST /logout-all`).
- Test: `EndpointAuthorizationCoverageTest.java`, `TenantIsolationIT.java`, `TokenMisuseIT.java`, `identity/MeIT.java`

**Interfaces:**
- Consumes everything from Tasks 2–9.
- Produces `record PrincipalState(String userStatus, int tokenVersion, String tenantStatus, Set<String> permissions, List<String> modules)`.
- Produces `PrincipalStateCache`:
  - `get(UUID tenantId, UUID userId): PrincipalState`, which must be called inside a `TenantContext` scope for that tenant;
  - `evict(UUID tenantId, UUID userId)`;
  - `evictTenant(UUID tenantId)`.

  The Redis key is `TenantKeys.key(tenantId, "user", userId, "principal")`, with a TTL of 60 s.
- Produces `record CurrentUser(UUID tenantId, UUID userId)` with `CurrentUser.require()`.
- Produces:
  - `GET /api/v1/me` (authenticated), returning `{user{id,email,firstName,lastName}, tenant{id,slug,name,status,planCode}, permissions[], modules[]}`;
  - `POST /api/v1/auth/logout-all` (authenticated), returning 204.
- Produces `SecurityConfig.INFRA_PUBLIC_PATHS: String[]`, the Plan 1 infrastructure paths.

- [ ] **Step 1: Write the failing tests**

First, a fix to a Plan 1 test. `@SpringBootTest` component-scans test classes too, so `GlobalExceptionHandlerTest.ThrowingController` (a nested `@RestController`) gets registered in every integration-test context and exposes `/boom` and the other test routes. In `GlobalExceptionHandlerTest`, replace `@RestController` on `ThrowingController` with:
```java
    @org.springframework.web.bind.annotation.RequestMapping
    @org.springframework.web.bind.annotation.ResponseBody
```
`standaloneSetup` still detects it, because it has a type-level `@RequestMapping`. Component scanning no longer does, because it has no stereotype. Run `./gradlew test --tests '*GlobalExceptionHandlerTest'` and expect it to still pass.

`backend/src/test/java/com/nexusops/EndpointAuthorizationCoverageTest.java`:
```java
package com.nexusops;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexusops.shared.web.PublicEndpoints;
import com.nexusops.support.IntegrationTestSupport;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Claude Code rule §41.6: no endpoint without an authorization requirement. */
class EndpointAuthorizationCoverageTest extends IntegrationTestSupport {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mapping;

    @Test
    void everyHandlerIsEitherPublicOrHasPreAuthorize() {
        List<String> violations = new ArrayList<>();
        Set<String> seenRoutes = new HashSet<>();
        mapping.getHandlerMethods().forEach((info, handler) -> {
            if (!handler.getBeanType().getPackageName().startsWith("com.nexusops")) {
                return;
            }
            boolean secured = AnnotatedElementUtils.hasAnnotation(handler.getMethod(), PreAuthorize.class)
                    || AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), PreAuthorize.class);
            var methods = info.getMethodsCondition().getMethods();
            for (String path : info.getPatternValues()) {
                if (methods.isEmpty()) {
                    if (!secured) violations.add("ANY " + path);
                    continue;
                }
                for (var method : methods) {
                    String route = method.name() + " " + path;
                    seenRoutes.add(route);
                    boolean isPublic = PublicEndpoints.ROUTES.contains(route);
                    if (isPublic == secured) {
                        violations.add(route + (secured ? " (public AND @PreAuthorize)" : " (no @PreAuthorize)"));
                    }
                }
            }
        });
        assertThat(violations).isEmpty();
        assertThat(seenRoutes).as("PublicEndpoints must not list routes that no longer exist")
                .containsAll(PublicEndpoints.ROUTES);
    }
}
```

`backend/src/test/java/com/nexusops/identity/MeIT.java`:
```java
package com.nexusops.identity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestTenants;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class MeIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;

    @Test
    void meReturnsProfileTenantPermissionsAndModules() throws Exception {
        var ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("me"));
        var session = TestTenants.login(mvc, ws);
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(ws.email()))
                .andExpect(jsonPath("$.user.firstName").value("Ada"))
                .andExpect(jsonPath("$.tenant.slug").value(ws.slug()))
                .andExpect(jsonPath("$.tenant.status").value("ACTIVE"))
                .andExpect(jsonPath("$.permissions", Matchers.hasItem("tenant.settings.update")))
                .andExpect(jsonPath("$.permissions", Matchers.not(Matchers.hasItem("crm.customer.read"))))
                .andExpect(jsonPath("$.modules", Matchers.empty()))
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist());
    }

    @Test
    void meRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.requestId").exists());
    }
}
```

`backend/src/test/java/com/nexusops/TenantIsolationIT.java`:
```java
package com.nexusops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.identity.security.PrincipalStateCache;
import com.nexusops.shared.TenantContext;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Session;
import com.nexusops.support.TestTenants.Workspace;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Blueprint Phase 2/3 exit criterion: Tenant A and B coexist; A cannot reach B. */
@AutoConfigureMockMvc
class TenantIsolationIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired PrincipalStateCache principalCache;

    Workspace a;
    Workspace b;
    Session sessionA;
    Session sessionB;

    @BeforeEach
    void twoTenants() throws Exception {
        a = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("iso-a"));
        b = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("iso-b"));
        sessionA = TestTenants.login(mvc, a);
        sessionB = TestTenants.login(mvc, b);
    }

    private String bearer(Session s) {
        return "Bearer " + s.accessToken();
    }

    @Test
    void eachTenantSeesOnlyItsOwnSettings() throws Exception {
        mvc.perform(get("/api/v1/tenant").header("Authorization", bearer(sessionA)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.slug").value(a.slug()));
        mvc.perform(get("/api/v1/tenant").header("Authorization", bearer(sessionB)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.slug").value(b.slug()));
    }

    @Test
    void updatingOneTenantNeverTouchesTheOther() throws Exception {
        mvc.perform(patch("/api/v1/tenant").header("Authorization", bearer(sessionA))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Renamed A\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Renamed A"));
        assertThat(OwnerJdbc.jdbc().queryForObject("select name from tenants where id = ?", String.class, b.tenantId()))
                .isEqualTo(b.slug() + " Inc");
    }

    @Test
    void refreshTokenOfBCannotBeReplayedUnderTenantA() throws Exception {
        String forged = a.tenantId() + sessionB.refreshToken().substring(36);
        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("nexus_rt", forged)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void applicationConnectionsBoundToACannotSeeAnyRowOfB() {
        for (String table : RlsCoverageIT.EXPECTED_TENANT_TABLES) {
            String predicate = switch (table) {
                case "role_permissions" -> "role_id in (select id from roles where tenant_id = ?)";
                case "user_roles" -> "user_id in (select id from users where tenant_id = ?)";
                default -> "tenant_id = ?";
            };
            Long count = TenantContext.callAs(a.tenantId(),
                    () -> jdbc.queryForObject("select count(*) from " + table + " where " + predicate, Long.class, b.tenantId()));
            assertThat(count).as(table).isZero();
        }
        Long ownRows = TenantContext.callAs(a.tenantId(),
                () -> jdbc.queryForObject("select count(*) from users", Long.class));
        assertThat(ownRows).isOne();
    }

    @Test
    void principalCacheKeysAreTenantPrefixed() throws Exception {
        mvc.perform(get("/api/v1/me").header("Authorization", bearer(sessionA))).andExpect(status().isOk());
        assertThat(redis.keys("tenant:" + a.tenantId() + ":user:*:principal")).hasSize(1);
        assertThat(redis.keys("*principal*")).allMatch(k -> k.startsWith("tenant:"));
    }

    @Test
    void permissionsComeFromTheServerNotTheToken() throws Exception {
        UUID userA = OwnerJdbc.ownerAs(a.tenantId()).queryForObject("select id from users", UUID.class);
        OwnerJdbc.ownerAs(a.tenantId()).update("delete from user_roles where user_id = ?", userA);
        principalCache.evict(a.tenantId(), userA);
        mvc.perform(get("/api/v1/tenant").header("Authorization", bearer(sessionA)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }
}
```

`backend/src/test/java/com/nexusops/TokenMisuseIT.java`:
```java
package com.nexusops;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.identity.security.AccessTokenService;
import com.nexusops.identity.security.JwtKeyConfig;
import com.nexusops.identity.security.JwtProperties;
import com.nexusops.identity.security.PrincipalStateCache;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Session;
import com.nexusops.support.TestTenants.Workspace;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@AutoConfigureMockMvc
class TokenMisuseIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired AccessTokenService accessTokens;
    @Autowired JwtProperties jwtProperties;
    @Autowired PrincipalStateCache principalCache;

    Workspace ws;
    Session session;
    UUID userId;

    @BeforeEach
    void login() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("misuse"));
        session = TestTenants.login(mvc, ws);
        userId = OwnerJdbc.ownerAs(ws.tenantId()).queryForObject("select id from users", UUID.class);
    }

    private ResultActions me(String token) throws Exception {
        return mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token));
    }

    @Test
    void tamperedSignatureIsRejected() throws Exception {
        String token = session.accessToken();
        char last = token.charAt(token.length() - 2);
        String tampered = token.substring(0, token.length() - 2) + (last == 'A' ? 'B' : 'A') + token.charAt(token.length() - 1);
        me(tampered).andExpect(status().isUnauthorized());
        me("not.a.jwt").andExpect(status().isUnauthorized());
    }

    @Test
    void tokenSignedWithAForeignKeyIsRejected() throws Exception {
        var foreign = new AccessTokenService(JwtKeyConfig.encoder(JwtKeyConfig.rsaKey(jwtProperties)), jwtProperties);
        me(foreign.issue(ws.tenantId(), userId, 0).value()).andExpect(status().isUnauthorized());
    }

    @Test
    void validTokenForAUserOfAnotherTenantIsRejected() throws Exception {
        Workspace other = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("misuse-b"));
        // correctly signed, but tid points at a tenant where this user does not exist
        me(accessTokens.issue(other.tenantId(), userId, 0).value()).andExpect(status().isUnauthorized());
        me(accessTokens.issue(ws.tenantId(), UUID.randomUUID(), 0).value()).andExpect(status().isUnauthorized());
    }

    @Test
    void staleTokenVersionRejectedAfterLogoutAll() throws Exception {
        mvc.perform(post("/api/v1/auth/logout-all").header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isNoContent());
        me(session.accessToken()).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("nexus_rt", session.refreshToken())))
                .andExpect(status().isUnauthorized());
        me(TestTenants.login(mvc, ws).accessToken()).andExpect(status().isOk());
    }

    @Test
    void disabledUserIsRejectedImmediately() throws Exception {
        OwnerJdbc.ownerAs(ws.tenantId()).update("update users set status = 'DISABLED'");
        principalCache.evict(ws.tenantId(), userId);
        me(session.accessToken()).andExpect(status().isUnauthorized());
    }

    @Test
    void suspendedWorkspaceIsForbidden() throws Exception {
        OwnerJdbc.jdbc().update("update tenants set status = 'SUSPENDED' where id = ?", ws.tenantId());
        principalCache.evictTenant(ws.tenantId());
        me(session.accessToken()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Workspace suspended."));
    }

    @Test
    void publicAuthRoutesIgnoreStaleBearerTokens() throws Exception {
        mvc.perform(post("/api/v1/auth/refresh").header("Authorization", "Bearer expired.or.garbage")
                        .cookie(new Cookie("nexus_rt", session.refreshToken())))
                .andExpect(status().isOk());
    }
}
```

Run: `cd backend && ./gradlew test --tests '*EndpointAuthorizationCoverageTest' --tests '*MeIT' --tests '*TenantIsolationIT' --tests '*TokenMisuseIT'`
Expected: compilation FAILS (missing `PrincipalStateCache` and the other new types).

- [ ] **Step 2: Principal state and cache**

`backend/src/main/java/com/nexusops/identity/security/PrincipalState.java`:
```java
package com.nexusops.identity.security;

import java.util.List;
import java.util.Set;

/** What every request needs to know about its caller, cached briefly in Redis (spec §6–§7). */
public record PrincipalState(String userStatus, int tokenVersion, String tenantStatus, Set<String> permissions,
        List<String> modules) {

    static PrincipalState missing() {
        return new PrincipalState("MISSING", -1, "MISSING", Set.of(), List.of());
    }
}
```

`backend/src/main/java/com/nexusops/identity/security/PrincipalStateCache.java`:
```java
package com.nexusops.identity.security;

import com.nexusops.authorization.AuthorizationService;
import com.nexusops.identity.domain.User;
import com.nexusops.identity.domain.UserRepository;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.cache.TenantKeys;
import com.nexusops.tenancy.TenantDirectory;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Per-request principal state with a short Redis TTL. Explicit eviction keeps it fresh; if Redis is
 * unavailable we fall back to the database (still correct, just slower).
 */
@Component
public class PrincipalStateCache {

    private static final Logger log = LoggerFactory.getLogger(PrincipalStateCache.class);
    static final Duration TTL = Duration.ofSeconds(60);

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final UserRepository users;
    private final TenantDirectory tenants;
    private final AuthorizationService authorization;
    private final TransactionTemplate tx;

    PrincipalStateCache(StringRedisTemplate redis, JsonMapper json, UserRepository users, TenantDirectory tenants,
            AuthorizationService authorization, TransactionTemplate tx) {
        this.redis = redis;
        this.json = json;
        this.users = users;
        this.tenants = tenants;
        this.authorization = authorization;
        this.tx = tx;
    }

    /** Must be called with TenantContext bound to {@code tenantId}. */
    public PrincipalState get(UUID tenantId, UUID userId) {
        if (!tenantId.equals(TenantContext.requireTenantId())) {
            throw new IllegalStateException("Principal lookup outside its tenant scope");
        }
        String key = key(tenantId, userId);
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                return json.readValue(cached, PrincipalState.class);
            }
        } catch (RuntimeException e) {
            log.warn("Principal cache read failed; using database", e);
        }
        PrincipalState state = load(userId);
        try {
            redis.opsForValue().set(key, json.writeValueAsString(state), TTL);
        } catch (RuntimeException e) {
            log.warn("Principal cache write failed", e);
        }
        return state;
    }

    public void evict(UUID tenantId, UUID userId) {
        redis.delete(key(tenantId, userId));
    }

    public void evictTenant(UUID tenantId) {
        try (Cursor<String> keys = redis.scan(ScanOptions.scanOptions().match(TenantKeys.tenantPattern(tenantId)).count(500).build())) {
            keys.forEachRemaining(redis::delete);
        }
    }

    private PrincipalState load(UUID userId) {
        return tx.execute(status -> {
            User user = users.findById(userId).orElse(null);
            if (user == null) {
                return PrincipalState.missing();
            }
            var tenant = tenants.current();
            List<String> modules = tenants.enabledModules();
            Set<String> permissions = authorization.effectivePermissions(user.getRoleIds(), modules);
            return new PrincipalState(user.getStatus().name(), user.getTokenVersion(), tenant.status().name(),
                    permissions, modules);
        });
    }

    private static String key(UUID tenantId, UUID userId) {
        return TenantKeys.key(tenantId, "user", userId.toString(), "principal");
    }
}
```

`backend/src/main/java/com/nexusops/identity/security/CurrentUser.java`:
```java
package com.nexusops.identity.security;

import com.nexusops.shared.TenantContext;
import java.util.UUID;

public record CurrentUser(UUID tenantId, UUID userId) {

    public static CurrentUser require() {
        return new CurrentUser(TenantContext.requireTenantId(),
                TenantContext.userId().orElseThrow(() -> new IllegalStateException("No authenticated user")));
    }
}
```

- [ ] **Step 3: Filter, bearer resolver and security config**

In `ProblemDetailSecurityHandlers`, change `private void write(...)` to `public void write(...)`.

`backend/src/main/java/com/nexusops/identity/security/PublicRouteAwareBearerTokenResolver.java`:
```java
package com.nexusops.identity.security;

import com.nexusops.shared.web.PublicEndpoints;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.util.matcher.RequestMatcher;

/** A stale/expired Authorization header must not break public routes such as /auth/refresh. */
class PublicRouteAwareBearerTokenResolver implements BearerTokenResolver {

    private final BearerTokenResolver delegate = new DefaultBearerTokenResolver();
    private final RequestMatcher[] publicRoutes = PublicEndpoints.matchers();

    @Override
    public String resolve(HttpServletRequest request) {
        boolean isPublic = Arrays.stream(publicRoutes).anyMatch(m -> m.matches(request));
        return isPublic ? null : delegate.resolve(request);
    }
}
```

`backend/src/main/java/com/nexusops/identity/security/PrincipalFilter.java`:
```java
package com.nexusops.identity.security;

import com.nexusops.shared.TenantContext;
import com.nexusops.shared.security.ProblemDetailSecurityHandlers;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Runs after JWT verification: binds TenantContext from the verified claims, then loads the
 * caller's live state (user status, token version, tenant status, permissions). Rejects stale
 * tokens and suspended tenants before any controller runs; grants permissions as authorities.
 */
@Component
public class PrincipalFilter extends OncePerRequestFilter {

    private final PrincipalStateCache principals;
    private final ProblemDetailSecurityHandlers problems;

    PrincipalFilter(PrincipalStateCache principals, ProblemDetailSecurityHandlers problems) {
        this.principals = principals;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            chain.doFilter(request, response);
            return;
        }
        Jwt jwt = token.getToken();
        UUID tenantId;
        UUID userId;
        int tokenVersion;
        try {
            tenantId = UUID.fromString(jwt.getClaimAsString(AccessTokenService.CLAIM_TENANT));
            userId = UUID.fromString(jwt.getSubject());
            tokenVersion = ((Number) jwt.getClaim(AccessTokenService.CLAIM_TOKEN_VERSION)).intValue();
        } catch (RuntimeException malformed) {
            reject(response, HttpStatus.UNAUTHORIZED, "Unauthorized", "Authentication is required.");
            return;
        }

        try (var scope = TenantContext.open(tenantId, userId)) {
            PrincipalState state = principals.get(tenantId, userId);
            if (!"ACTIVE".equals(state.userStatus()) || state.tokenVersion() != tokenVersion) {
                reject(response, HttpStatus.UNAUTHORIZED, "Unauthorized", "Your session is no longer valid. Please sign in again.");
                return;
            }
            if ("SUSPENDED".equals(state.tenantStatus())) {
                reject(response, HttpStatus.FORBIDDEN, "Forbidden", "Workspace suspended.");
                return;
            }
            List<SimpleGrantedAuthority> authorities = state.permissions().stream().map(SimpleGrantedAuthority::new).toList();
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new JwtAuthenticationToken(jwt, authorities, userId.toString()));
            SecurityContextHolder.setContext(context);
            chain.doFilter(request, response);
        }
    }

    private void reject(HttpServletResponse response, HttpStatus status, String title, String detail) throws IOException {
        SecurityContextHolder.clearContext();
        problems.write(response, status, title, detail);
    }
}
```

Delete `backend/src/main/java/com/nexusops/shared/security/SecurityConfig.java` and create `backend/src/main/java/com/nexusops/identity/security/SecurityConfig.java`:
```java
package com.nexusops.identity.security;

import com.nexusops.shared.security.ProblemDetailSecurityHandlers;
import com.nexusops.shared.web.PublicEndpoints;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/** Stateless, deny-by-default; JWT bearer auth; principal state checked on every request. */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfig {

    public static final String[] INFRA_PUBLIC_PATHS = {
        "/actuator/health", "/actuator/health/**", "/actuator/info",
        "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**",
        "/error"
    };

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, ProblemDetailSecurityHandlers handlers,
            PrincipalFilter principalFilter) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(INFRA_PUBLIC_PATHS).permitAll()
                        .requestMatchers(PublicEndpoints.matchers()).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .bearerTokenResolver(new PublicRouteAwareBearerTokenResolver())
                        .authenticationEntryPoint(handlers)
                        .accessDeniedHandler(handlers)
                        .jwt(jwt -> {}))
                .exceptionHandling(e -> e.authenticationEntryPoint(handlers).accessDeniedHandler(handlers))
                .addFilterAfter(principalFilter, BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    /** PrincipalFilter belongs to the security chain only — not also to the servlet filter chain. */
    @Bean
    FilterRegistrationBean<PrincipalFilter> principalFilterServletRegistration(PrincipalFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
```

- [ ] **Step 4: Logout-all and /me**

Add to `RefreshService`, injecting `PrincipalStateCache principals` through the constructor:
```java
    /** Invalidates every session of the current user: bumps token_version and revokes all refresh tokens. */
    public void logoutAll() {
        var current = com.nexusops.identity.security.CurrentUser.require();
        tx.executeWithoutResult(status -> {
            User user = users.findById(current.userId()).orElseThrow(RefreshService::expired);
            user.bumpTokenVersion();
            refreshTokens.revokeAllForUser(user.getId(), RevokeReason.LOGOUT_ALL, Instant.now());
            audit.record(AuditEntry.of("LogoutAll", "User", user.getId()));
        });
        principals.evict(current.tenantId(), current.userId());
    }
```

Add to `AuthController`:
```java
    @PostMapping("/logout-all")
    @org.springframework.security.access.prepost.PreAuthorize("isAuthenticated()")
    ResponseEntity<Void> logoutAll() {
        refresh.logoutAll();
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, RefreshCookies.clear().toString()).build();
    }
```

`backend/src/main/java/com/nexusops/identity/application/ProfileService.java`:
```java
package com.nexusops.identity.application;

import com.nexusops.identity.domain.User;
import com.nexusops.identity.domain.UserRepository;
import com.nexusops.identity.security.CurrentUser;
import com.nexusops.identity.security.PrincipalStateCache;
import com.nexusops.shared.web.ApiProblem;
import com.nexusops.tenancy.TenantDirectory;
import com.nexusops.tenancy.TenantSummary;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileService {

    public record Profile(UserView user, TenantSummary tenant, List<String> permissions, List<String> modules) {}

    public record UserView(UUID id, String email, String firstName, String lastName) {}

    private final UserRepository users;
    private final TenantDirectory tenants;
    private final PrincipalStateCache principals;

    ProfileService(UserRepository users, TenantDirectory tenants, PrincipalStateCache principals) {
        this.users = users;
        this.tenants = tenants;
        this.principals = principals;
    }

    @Transactional(readOnly = true)
    public Profile me() {
        CurrentUser current = CurrentUser.require();
        User user = users.findById(current.userId()).orElseThrow(() -> ApiProblem.unauthorized("Authentication is required."));
        var state = principals.get(current.tenantId(), current.userId());
        return new Profile(
                new UserView(user.getId(), user.getEmail(), user.getFirstName(), user.getLastName()),
                tenants.current(),
                state.permissions().stream().sorted().toList(),
                state.modules());
    }
}
```

`backend/src/main/java/com/nexusops/identity/web/MeController.java`:
```java
package com.nexusops.identity.web;

import com.nexusops.identity.application.ProfileService;
import com.nexusops.identity.application.ProfileService.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class MeController {

    private final ProfileService profiles;

    MeController(ProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping("/api/v1/me")
    @PreAuthorize("isAuthenticated()")
    Profile me() {
        return profiles.me();
    }
}
```

- [ ] **Step 5: Run the tests and the full build**

Run: `./gradlew test --tests '*EndpointAuthorizationCoverageTest' --tests '*MeIT' --tests '*TenantIsolationIT' --tests '*TokenMisuseIT'`, then `./gradlew build`
Expected:
- `EndpointAuthorizationCoverageTest` passes 1;
- `MeIT` passes 2;
- `TenantIsolationIT` passes 6;
- `TokenMisuseIT` passes 7.

Then the **whole** build is green, including:
- `PlatformFoundationIT`: unknown route → 401 problem+json; Prometheus not public;
- `ModularityTest`;
- all Task 1–9 tests.

- [ ] **Step 6: Commit**

```bash
cd /Users/user/Desktop/nexusops && git add backend && git commit -m "feat(identity): JWT resource server with per-request principal checks, /me, logout-all; isolation and misuse suites

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: OpenAPI contract, ADR update, README, final verification

**Files:**
- Create: `shared/web/OpenApiConfig.java`, `backend/src/test/java/com/nexusops/OpenApiContractIT.java`, `docs/api/openapi.json` (generated).
- Modify: `docs/decisions/0002-tenant-isolation.md`, `docs/superpowers/specs/2026-10-04-platform-foundation-design.md` (record the spec deltas), `README.md`.

**Interfaces:**
- Produces: `/v3/api-docs` with a `bearerAuth` HTTP bearer (JWT) security scheme, plus `docs/api/openapi.json` checked into the repo.

- [ ] **Step 1: Write the failing contract test**

`backend/src/test/java/com/nexusops/OpenApiContractIT.java`:
```java
package com.nexusops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.IntegrationTestSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** Keeps the published API contract honest (Claude Code rule §41.11). */
@AutoConfigureMockMvc
class OpenApiContractIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;

    @Test
    void documentListsTheV1RoutesAndBearerScheme() throws Exception {
        String doc = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(doc).contains("\"/api/v1/auth/signup\"", "\"/api/v1/auth/login\"", "\"/api/v1/auth/refresh\"",
                "\"/api/v1/auth/logout-all\"", "\"/api/v1/me\"", "\"/api/v1/tenant\"", "\"bearerAuth\"");
        if (Boolean.getBoolean("openapi.export")) {
            Files.writeString(Path.of("../docs/api/openapi.json"), doc);
        }
    }
}
```

Run: `cd backend && ./gradlew test --tests '*OpenApiContractIT'`
Expected: FAIL. The document has no `bearerAuth` scheme yet.

- [ ] **Step 2: Implement the OpenAPI config**

`backend/src/main/java/com/nexusops/shared/web/OpenApiConfig.java`:
```java
package com.nexusops.shared.web;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(
        info = @Info(title = "NexusOps API", version = "v1",
                description = "Multi-tenant business operations platform. Tenant context always comes from the bearer token."),
        security = @SecurityRequirement(name = "bearerAuth"))
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
class OpenApiConfig {}
```

Run: `./gradlew test --tests '*OpenApiContractIT'`. Expected: PASS.
Then export the contract: `./gradlew test --tests '*OpenApiContractIT' -Dopenapi.export=true --rerun-tasks`. To forward the system property, add to the `tasks.withType<Test>` block in `build.gradle.kts`:
```kotlin
    systemProperty("openapi.export", System.getProperty("openapi.export") ?: "false")
```
Expected: `docs/api/openapi.json` exists and contains `/api/v1/me`.

- [ ] **Step 3: Update the docs**

In `docs/decisions/0002-tenant-isolation.md`, replace the sentence about setting `app.tenant_id` with:
> At **connection checkout**, `TenantAwareDataSource` runs `set_config('app.tenant_id', <tenant or ''>, false)`. It does this on every checkout, so a pooled connection never carries a previous tenant. Because of that, `TenantContext` must be bound **before** a transaction starts, and `TenantContext.open` refuses to bind or switch tenant inside an active transaction.

Also add to the Decision bullets:
> Join tables without `tenant_id` (`role_permissions`, `user_roles`) use RLS policies that require the referenced rows to be visible under the current tenant.

In the spec, add a section `## 15. Deltas adopted in Plan 2` that copies the seven "Spec deltas" from this plan's header verbatim.

In `README.md`, add a section:
````markdown
## Try the API (local)
```bash
curl -s -X POST localhost:8081/api/v1/auth/signup -H 'Content-Type: application/json' \
  -d '{"workspaceName":"Acme","slug":"acme","firstName":"Ada","lastName":"Owner","email":"ada@acme.test","password":"correct horse battery staple"}'
# open Mailpit (http://localhost:8025), click the verification link's token into:
curl -s -X POST localhost:8081/api/v1/auth/verify-email -H 'Content-Type: application/json' -d '{"token":"<token>"}'
curl -s -c /tmp/nx.cookies -X POST localhost:8081/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"workspace":"acme","email":"ada@acme.test","password":"correct horse battery staple"}'
curl -s localhost:8081/api/v1/me -H "Authorization: Bearer <accessToken>"
```
The API contract is in `docs/api/openapi.json`; Swagger UI at http://localhost:8081/swagger-ui.html.
````

- [ ] **Step 4: Final verification**

Run from the repo root:
```bash
make test && make e2e
```
Expected: everything is green. Backend counts:
- all Plan 1 tests;
- Task 1: 8;
- Task 2: 14;
- Task 3: 9;
- Task 4: 26;
- Task 5: 4;
- Task 6: 8;
- Task 7: 14;
- Task 8: 8;
- Task 9: 12;
- Task 10: 16;
- Task 11: 1.

Frontend: 13 tests plus 1 e2e. ai-service: 1. Infra loopback check: OK.

Then a live smoke test against the real stack:
```bash
make up-all
```
Follow the README "Try the API" steps. The verification mail appears in Mailpit, `/api/v1/me` returns the workspace, and `/api/v1/tenant` with a garbage token returns a 401 problem+json. Stop the app containers afterwards.

- [ ] **Step 5: Commit**

```bash
cd /Users/user/Desktop/nexusops && git add -A backend docs README.md && git commit -m "docs: OpenAPI contract with bearer scheme, ADR-0002 connection-checkout model, spec deltas, README API walkthrough

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Exit criteria (blueprint Phase 2, plus the parts of Phase 3 delivered here)

| Criterion | Proven by |
|---|---|
| Registration, verification, login, refresh tokens, logout/revocation | `SignupIT`, `LoginIT`, `RefreshTokenIT`, `TokenMisuseIT.staleTokenVersionRejectedAfterLogoutAll` |
| Organization creation, tenant context, tenant status | `SignupIT`, `TenantSettingsIT`, `TokenMisuseIT.suspendedWorkspaceIsForbidden` |
| **Tenant A and B coexist; users cannot cross tenants** | `TenantIsolationIT` (HTTP + app connections), `RlsBehaviourIT` (DB alone), `UserTenantScopingIT` (Hibernate alone), `RlsCoverageIT` (every table) |
| Permissions, not role names, authorize; every endpoint has a rule | `AuthorizationServiceIT`, `TenantIsolationIT.permissionsComeFromTheServerNotTheToken`, `EndpointAuthorizationCoverageTest` |
| Audit of security events; append-only | `AuditServiceIT`, `RlsBehaviourIT.auditEventsAreAppendOnly`, login/refresh audit assertions |
| Deferred to Plan 3 | invitations, user and role management APIs, module toggles, audit read API, escalation guard, rate limiting |
