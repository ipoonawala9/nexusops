# Plan 4 — Platform Administration with TOTP: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give NexusOps staff a separate, MFA-protected platform console API:
- platform users are created only from a CLI, which enrols them in TOTP;
- they sign in with password + TOTP;
- they list every workspace, with its active-user count and owners;
- admins (not support staff) suspend and reactivate workspaces.

Tenant and platform tokens are mutually unusable, and platform cross-tenant reads are possible only through one audited, transaction-local database flag.

**Architecture:** A new `platform` module (`com.nexusops.platform`, depends on `shared`, `tenancy`, `audit`):
- **Own security filter chain:** ordered first, matching `/api/v1/platform/**`, with its own JWT decoder (`aud=nexusops-platform`) and principal filter.
- **Own tables:** `platform_users` and `platform_refresh_tokens`. They have FORCE RLS but are visible only while `app.platform_access = 'on'`.
- **Flag owner:** `PlatformAccess`, the only code that may set that flag.
- **Read-only cross-tenant policies:** new `FOR SELECT` policies on `users`, `roles` and `user_roles` honour the same flag, so the tenant list can show counts and owners.
- **Suspension:** lives in `tenancy` (`TenantDirectory.suspendCurrent` / `reactivateCurrent`), which publishes `TenantStatusChanged`. `identity` evicts the tenant's principal cache after commit, so members are blocked on their very next request.

**Tech stack:** Spring Boot 4.1.1, JDK 25, Spring Security 7.1.1 (two `SecurityFilterChain`s), Nimbus JOSE (RS256), JCA `HmacSHA1` / `AES/GCM/NoPadding`, ZXing core (terminal QR code), PostgreSQL 17 RLS, Redis token buckets, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-10-04-platform-foundation-design.md` §2 (platform admin row), §3 (platform module), §4.2 (platform cross-tenant access), §5 (`platform_users`), §6 (platform login), §9, §10 (platform routes), §12, §13 (audience swap), §15–§16. Builds on `main` at `855551f` (Plans 1–3 merged).

## Scope

**In:**
- Shared groundwork: `Emails`, `PasswordPolicy` and `OriginGuard` move to `shared`, and audit entries can name a platform actor.
- V7 migration: platform tables with flag-gated RLS, plus `platform_read` SELECT policies.
- `PlatformAccess`, and a test proving no other code sets the flag.
- TOTP (RFC 6238) and AES-256-GCM secret encryption.
- Platform users and the CLI (`make platform-admin` and friends), with a terminal QR code and a confirmation code.
- Platform JWTs, the security chain and the principal filter. Tokens can't cross between tenant and platform APIs.
- Platform login, refresh and logout, with an 8-hour absolute session, TOTP replay protection and rate limits.
- Workspace suspension and reactivation, taking effect on members' next request.
- Platform tenant API: list (counts + owner emails), suspend and reactivate.
- ADR-0007, spec §17, threat model, README and OpenAPI.

**Out:**
- Plan 5: frontend screens (including `/platform/login` and `/platform/tenants`) and E2E.
- A platform audit-log viewer. Events are recorded; viewing them comes later.
- Managing platform users over HTTP (CLI only, by decision).
- WebAuthn and recovery codes. Recovery is `make platform-reset-totp`.
- Tenant deletion.

## Decisions taken while writing this plan (flagged for review)

1. **The first and every platform user is created from a CLI** (your decision, 2026-10-06).
   - `make platform-admin EMAIL=…` prompts for a password twice and shows a terminal QR code plus the otpauth URI and secret once.
   - It then requires a valid 6-digit code from the authenticator before it saves anything.
   - There is no HTTP enrolment surface. A lost authenticator is fixed with `make platform-reset-totp EMAIL=…`.
2. **Platform sessions last at most 8 hours** (your decision).
   - The access token is a 15-minute JWT with `aud=nexusops-platform`.
   - The refresh cookie is `nexus_prt` at path `/api/v1/platform/auth`. It rotates with family reuse detection (10 s grace, like Plan 2), but `expires_at` is fixed at login + 8 h and rotation never extends it. After that, password + TOTP again.
3. **Roles** (your decision):
   - `PLATFORM_SUPPORT` → `platform.tenant.read`.
   - `PLATFORM_ADMIN` → `platform.tenant.read` + `platform.tenant.suspend`.

   Authorities come from the database row on every request, never from the token.
4. **The tenant list shows active-user counts and active owner emails** (your decision). It reads through permissive `FOR SELECT` policies `platform_read` on `users`, `roles` and `user_roles`, gated by `app.platform_access = 'on'`. UPDATE, INSERT and DELETE stay governed only by the tenant policies, so the flag can never write tenant data. V3's join-table policies (`role_permissions`, `user_roles`) authorized by `EXISTS` over *visible* `users`/`roles`; V7 restates them with an explicit `tenant_id = app.tenant_id` predicate, because the new SELECT visibility would otherwise have widened their writes.
5. **Platform tables are RLS-protected, not plain global tables.** `platform_users` and `platform_refresh_tokens` have FORCE RLS with a policy that requires the flag. A bug in tenant code can't read an admin's password hash or TOTP secret, because tenant code paths never set the flag.
6. **`PlatformAccess` is the only setter of the flag.**
   - It uses `set_config('app.platform_access', 'on', true)`, so the flag is transaction-local and dies with the transaction.
   - It refuses to run inside a tenant scope or inside an already-open transaction.
   - `PlatformAccessConfinementTest` fails the build if the string `app.platform_access` appears in any main source outside `com/nexusops/platform/`. Within that package, only `PlatformAccess` may call `set_config` on it.
7. **One RS256 key, two audiences.**
   - The tenant decoder already requires `aud ∋ nexusops-tenant` and a `tid` claim.
   - The platform decoder requires `aud ∋ nexusops-platform`, a `pv` (platform token version) claim and **no** `tid`.
   - The platform decoder and encoder are not Spring beans, so the tenant chain's auto-configured decoder stays unambiguous.
8. **TOTP:**
   - RFC 6238 with HMAC-SHA1, 6 digits and a 30 s step, accepting ±1 step for clock skew.
   - Every code is single-use: `platform_users.totp_last_step` is updated under a row lock, and a code whose step is ≤ the last used step is rejected.
   - Secrets are 20 random bytes, stored AES-256-GCM encrypted (`v1:` + base64(iv‖ciphertext‖tag)), with the platform user id as AAD so ciphertexts can't be swapped between rows.
   - The key is `PLATFORM_TOTP_KEY` (32 bytes, base64). It is required at startup like the DB passwords, with fixed non-secret values in the `local` and `test` profiles only.
9. **Suspension blocks but doesn't destroy sessions.**
   - Only `ACTIVE` → `SUSPENDED` and `SUSPENDED` → `ACTIVE` are allowed; anything else is 409. A `PENDING_VERIFICATION` workspace can't be "reactivated" past email verification.
   - While suspended, members get 403 "Workspace suspended." on the next request (after-commit cache eviction), refresh returns 401, and login returns 403.
   - On reactivation, unexpired sessions work again. A reason (1–500 chars) is required. Both actions are audited **in the workspace's own audit log** with `actor_type = PLATFORM` and the operator's platform-user id.
10. **Module boundaries:** `platform` must not depend on `identity` (spec §3), so `Emails`, `PasswordPolicy` and `OriginGuard` move to `shared`. The platform JWT issuer comes from the `nexusops.security.jwt.issuer` property, and the signing key from the `RSAKey` bean (a Nimbus type, not an identity type).
11. **Platform login rate limits:**
    - `platform-login`: per IP, 10/min.
    - `platform-login-account`: per `sha256(email)`, 5/min, counting every attempt.

    Both fail closed (503) when Redis is down, like tenant login. Refresh reuses the per-IP `refresh` rule.

## Global Constraints

- **Unchanged from Plans 1–3:**
  - JDK 25, Spring Boot 4.1.1, `/api/v1`.
  - Runtime DB role `nexusops_app`; `DatabaseRoleGuard` must stay green.
  - problem+json errors with `requestId`.
  - Backend host port 8081; SHA-pinned actions.
  - Tenant context only from a verified JWT or a server-side lookup.
  - Every new table gets `ENABLE` + `FORCE ROW LEVEL SECURITY` and a policy in its creating migration (`RlsCoverageIT` enforces this).
  - UUIDv7 via `Ids.newId()`; `timestamptz` / `Instant`.
  - Pagination: `?page` is 0-based, default 0; `?size` is 1–100, default 20. Responses are `{"items":[…],"page":0,"size":20,"total":N}`. An invalid page or size is a 400 field error.
  - A malformed path id is 404; any other mistyped parameter is a 400 field error.
- **Commits carry NO AI attribution** of any kind: no `Co-Authored-By` trailer for any AI, and no "Generated with" line. Plain conventional-commit messages only (user requirement).
- **Module rule:** `com.nexusops.platform` may import only from `com.nexusops.shared..`, `com.nexusops.tenancy` (base package) and `com.nexusops.audit` (base package). `ModularityTest` must stay green.
- **The string `app.platform_access`** may appear only in:
  - `backend/src/main/resources/db/migration/V7__platform.sql`;
  - files under `backend/src/main/java/com/nexusops/platform/`.

  Only `platform/internal/PlatformAccess.java` may call `set_config` with it.
- **Exact user-facing texts:**
  - Platform login failure (any reason) → 401 "Invalid email, password or code."
  - Stale, disabled or unknown platform principal → 401 "Your session is no longer valid. Please sign in again."
  - Platform refresh failure → 401 "Your session has expired. Please sign in again."
  - Missing authority → 403 "You do not have permission to perform this action." (the existing handler text).
  - Suspend when not ACTIVE → 409 "Only an active workspace can be suspended."
  - Reactivate when not SUSPENDED → 409 "Only a suspended workspace can be reactivated."
  - Unknown workspace id → 404 "Workspace not found."
  - Reason outside 1–500 chars → 400 field `reason`: "Enter a reason between 1 and 500 characters."
- **Audited actions:**
  - `PlatformUserCreated`, `PlatformTotpReset`, `PlatformPasswordReset`, `PlatformUserDisabled`, `PlatformUserEnabled`: actor `SYSTEM`, tenant NULL.
  - `PlatformLoginSucceeded`: actor `PLATFORM` + id, tenant NULL.
  - `PlatformLoginFailed`: actor `ANONYMOUS`, tenant NULL, metadata `reason`.
  - `PlatformLogout`, `PlatformRefreshTokenReuseDetected`: actor `PLATFORM` + id, tenant NULL.
  - `TenantSuspended`, `TenantReactivated`: tenant = the workspace, actor `PLATFORM` + id, metadata `reason`.
- **Never logged or audited:** passwords, password hashes, TOTP secrets, TOTP codes, tokens, cookies. The CLI prints the TOTP secret to the interactive terminal only, never through a logger.
- **Branch:** work on `feat/platform-admin` (already created from `main`); never push or merge without asking.

## Review Focus

1. **A TOTP code is replayed within its 30-second window**, for example by a shoulder-surfer or a second tab. Expected: the second login with the same code is 401 even with the right password. *Pinned by `PlatformAuthIT.aTotpCodeWorksOnlyOnce` (Task 6).*
2. **A platform token is sent to a tenant API, or a tenant token to a platform API.** Expected: 401 both ways, and a correctly signed platform-audience token that also carries a `tid` is rejected. *Pinned by `PlatformSecurityIT.tokensDoNotCrossBetweenTenantAndPlatformApis` and `platformTokenWithATenantClaimIsRejected` (Task 5).*
3. **A workspace is suspended while its members are signed in.** Expected:
   - the members' next API call is 403 "Workspace suspended.";
   - refresh is 401, and login is 403;
   - another workspace is unaffected;
   - reactivation restores the same unexpired access token.

   *Pinned by `TenantSuspensionIT.suspensionBlocksMembersOnTheirNextRequestAndReactivationRestoresThem` (Task 7) and `PlatformTenantApiIT.adminSuspendsAndReactivatesAWorkspace` (Task 8).*
4. **The admin's phone clock is up to 30 seconds off.** Expected: a code from the previous or next step is accepted, and a code two steps away is rejected. *Pinned by `TotpTest.acceptsOneStepOfSkewEitherWay` (Task 3).*
5. **A platform session keeps refreshing for more than 8 hours.** Expected: once the family's fixed `expires_at` passes, refresh is 401 even if the token was rotated a minute ago, and rotation never moves `expires_at`. *Pinned by `PlatformAuthIT.rotationNeverExtendsTheEightHourCap` (Task 6).*

---

### Task 1: Shared groundwork — move `Emails`, `PasswordPolicy`, `OriginGuard` to `shared`; audit entries can name a platform actor

**Files:**
- Move: `backend/src/main/java/com/nexusops/identity/application/Emails.java` → `backend/src/main/java/com/nexusops/shared/Emails.java`
- Move: `backend/src/main/java/com/nexusops/identity/application/PasswordPolicy.java` → `backend/src/main/java/com/nexusops/shared/security/PasswordPolicy.java`
- Move: `backend/src/main/java/com/nexusops/identity/web/OriginGuard.java` → `backend/src/main/java/com/nexusops/shared/web/OriginGuard.java`
- Move tests: `backend/src/test/java/com/nexusops/identity/EmailsTest.java` → `backend/src/test/java/com/nexusops/shared/EmailsTest.java`; `backend/src/test/java/com/nexusops/identity/PasswordPolicyTest.java` → `backend/src/test/java/com/nexusops/shared/security/PasswordPolicyTest.java`
- Modify (imports only): `identity/application/InvitationService.java`, `identity/application/SignupService.java`, `identity/application/LoginService.java`, `identity/web/AuthController.java`
- Modify: `backend/src/main/java/com/nexusops/audit/AuditEntry.java`, `backend/src/main/java/com/nexusops/audit/AuditService.java`
- Test: `backend/src/test/java/com/nexusops/audit/AuditServiceIT.java`

**Interfaces:**
- Produces:
  - `com.nexusops.shared.Emails` with `static String normalize(String)` and `static Optional<String> tryNormalize(String)`.
  - `com.nexusops.shared.security.PasswordPolicy` (`@Component`), with `void check(String password, String email)`.
  - `com.nexusops.shared.web.OriginGuard` (public `@Component`), with `public void check(String origin)`.
  - `AuditEntry` gains a trailing component `UUID actorId` and `AuditEntry asPlatformActor(UUID platformUserId)`. `AuditService` records `entry.actorId()` when it is non-null, otherwise `TenantContext.userId()`.

- [ ] **Step 1: Write the failing audit test**

Append to `AuditServiceIT`:

```java
    @Test
    void recordsAPlatformActorWithOrWithoutATenant() {
        UUID operator = UUID.randomUUID();
        tx.executeWithoutResult(s -> audit.record(
                AuditEntry.of("PlatformThingDone", "PlatformUser", operator).asPlatformActor(operator)));
        Map<String, Object> global = OwnerJdbc.superuser().queryForMap(
                "select * from audit_events where action = 'PlatformThingDone' order by occurred_at desc limit 1");
        assertThat(global.get("tenant_id")).isNull();
        assertThat(global.get("actor_type")).isEqualTo("PLATFORM");
        assertThat(global.get("actor_id")).isEqualTo(operator);

        UUID boundUser = UUID.randomUUID();
        try (var scope = TenantContext.open(tenant, boundUser)) {
            tx.executeWithoutResult(s -> audit.record(
                    AuditEntry.of("PlatformTenantThingDone", "Tenant", tenant).asPlatformActor(operator)));
        }
        Map<String, Object> scoped = onlyRow("PlatformTenantThingDone");
        assertThat(scoped.get("tenant_id")).isEqualTo(tenant);
        assertThat(scoped.get("actor_type")).isEqualTo("PLATFORM");
        assertThat(scoped.get("actor_id")).isEqualTo(operator); // the operator, not whoever TenantContext names
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.nexusops.audit.AuditServiceIT'`
Expected: compilation FAILS: `cannot find symbol: method asPlatformActor(java.util.UUID)`.

- [ ] **Step 3: Implement the platform actor**

Replace `AuditEntry.java` with:

```java
package com.nexusops.audit;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One auditable action. Actor and tenant come from TenantContext unless the entry names its actor
 * explicitly ({@link #asPlatformActor}); request metadata from the current HTTP request. Keys that
 * look secret are dropped from before/after/metadata.
 */
public record AuditEntry(
        String action,
        String entityType,
        String entityId,
        Map<String, Object> before,
        Map<String, Object> after,
        Map<String, Object> metadata,
        ActorType actorType,
        UUID actorId) {

    public static AuditEntry of(String action, String entityType, Object entityId) {
        return new AuditEntry(action, entityType, entityId == null ? null : entityId.toString(), null, null, null, null,
                null);
    }

    public AuditEntry withBefore(Map<String, Object> value) {
        return new AuditEntry(action, entityType, entityId, value, after, metadata, actorType, actorId);
    }

    public AuditEntry withAfter(Map<String, Object> value) {
        return new AuditEntry(action, entityType, entityId, before, value, metadata, actorType, actorId);
    }

    public AuditEntry withMetadata(Map<String, Object> value) {
        return new AuditEntry(action, entityType, entityId, before, after, value, actorType, actorId);
    }

    public AuditEntry asActor(ActorType value) {
        return new AuditEntry(action, entityType, entityId, before, after, metadata, value, actorId);
    }

    /** A platform operator acted: recorded as actor_type PLATFORM with this id, whatever TenantContext holds. */
    public AuditEntry asPlatformActor(UUID platformUserId) {
        Objects.requireNonNull(platformUserId, "platformUserId");
        return new AuditEntry(action, entityType, entityId, before, after, metadata, ActorType.PLATFORM, platformUserId);
    }
}
```

In `AuditService.insert`, replace the line `UUID actorId = TenantContext.userId().orElse(null);` with:

```java
        UUID actorId = entry.actorId() != null ? entry.actorId() : TenantContext.userId().orElse(null);
```

Run `grep -rn "new AuditEntry(" backend/src`. No call sites outside `AuditEntry.java` exist today. If any appear, add a trailing `null` argument.

- [ ] **Step 4: Run the audit test to verify it passes**

Run: `cd backend && ./gradlew test --tests 'com.nexusops.audit.AuditServiceIT'`
Expected: PASS (all tests in the class).

- [ ] **Step 5: Move the three classes to `shared`**

```bash
cd backend
git mv src/main/java/com/nexusops/identity/application/Emails.java src/main/java/com/nexusops/shared/Emails.java
mkdir -p src/test/java/com/nexusops/shared/security
git mv src/main/java/com/nexusops/identity/application/PasswordPolicy.java src/main/java/com/nexusops/shared/security/PasswordPolicy.java
git mv src/main/java/com/nexusops/identity/web/OriginGuard.java src/main/java/com/nexusops/shared/web/OriginGuard.java
git mv src/test/java/com/nexusops/identity/EmailsTest.java src/test/java/com/nexusops/shared/EmailsTest.java
git mv src/test/java/com/nexusops/identity/PasswordPolicyTest.java src/test/java/com/nexusops/shared/security/PasswordPolicyTest.java
```

Then edit:
- `shared/Emails.java`: `package com.nexusops.shared;`. The body is unchanged; it already imports `com.nexusops.shared.web.ApiProblem`.
- `shared/security/PasswordPolicy.java`: `package com.nexusops.shared.security;`. The body is unchanged.
- `shared/web/OriginGuard.java`: `package com.nexusops.shared.web;`. Make the class `public` and `check` `public`. Remove the now-redundant `import com.nexusops.shared.web.ApiProblem;`. The resulting class:

```java
package com.nexusops.shared.web;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Defence in depth for cookie-authenticated routes: a browser Origin, if sent, must be ours (threat T8). */
@Component
public class OriginGuard {

    private final List<String> allowedOrigins;

    OriginGuard(@Value("${nexusops.security.allowed-origins}") List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins.stream().map(String::strip).toList();
    }

    public void check(String origin) {
        if (origin != null && !allowedOrigins.contains(origin)) {
            throw ApiProblem.forbidden("Cross-site request rejected.");
        }
    }
}
```

- `EmailsTest`: `package com.nexusops.shared;`, and remove `import com.nexusops.identity.application.Emails;`.
- `PasswordPolicyTest`: `package com.nexusops.shared.security;`, and drop its `identity.application` import.
- Add imports where the moved classes are used:
  - `identity/application/InvitationService.java`, `SignupService.java` and `LoginService.java`: add `import com.nexusops.shared.Emails;` and/or `import com.nexusops.shared.security.PasswordPolicy;`, whichever the file uses. Those classes were same-package before, so they had no import.
  - `identity/web/AuthController.java`: replace `import com.nexusops.identity.application.Emails;` with `import com.nexusops.shared.Emails;`, and add `import com.nexusops.shared.web.OriginGuard;`.

- [ ] **Step 6: Run the whole backend suite**

Run: `cd backend && ./gradlew build`
Expected: BUILD SUCCESSFUL. All prior tests pass, including `ModularityTest` and the moved `EmailsTest` and `PasswordPolicyTest`.

- [ ] **Step 7: Commit**

```bash
git add -A backend/src
git commit -m "refactor: move Emails, PasswordPolicy and OriginGuard to shared; audit entries can name a platform actor"
```

---

### Task 2: V7 migration — platform tables behind a transaction-local flag, read-only cross-tenant policies, `PlatformAccess`

**Files:**
- Create: `backend/src/main/resources/db/migration/V7__platform.sql`
- Create: `backend/src/main/java/com/nexusops/platform/package-info.java`
- Create: `backend/src/main/java/com/nexusops/platform/internal/PlatformAccess.java`
- Modify: `backend/src/test/java/com/nexusops/RlsCoverageIT.java`
- Test: `backend/src/test/java/com/nexusops/platform/PlatformAccessIT.java`, `backend/src/test/java/com/nexusops/platform/PlatformAccessConfinementTest.java`

**Interfaces:**
- Produces:
  - Tables `platform_users(id, email, password_hash, totp_secret_enc, totp_last_step, role, status, token_version, last_login_at, created_at, updated_at, version)` and `platform_refresh_tokens(id, platform_user_id, family_id, token_hash, expires_at, revoked_at, revoke_reason, replaced_by, token_version, created_ip, user_agent, created_at)`.
  - `com.nexusops.platform.internal.PlatformAccess` with:
    - `public <T> T read(Supplier<T> work)`;
    - `public <T> T write(Supplier<T> work)`;
    - `public void writeWithoutResult(Runnable work)`.

    Each opens a **new** transaction with the flag on. Each throws `IllegalStateException` if a tenant is bound or a transaction is already active.

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/com/nexusops/platform/PlatformAccessIT.java`:

```java
package com.nexusops.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexusops.platform.internal.PlatformAccess;
import com.nexusops.shared.TenantContext;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestTenants;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfigureMockMvc
class PlatformAccessIT extends IntegrationTestSupport {

    @Autowired PlatformAccess access;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;

    private static final String INSERT_PLATFORM_USER = """
            insert into platform_users (id, email, password_hash, totp_secret_enc, role, status, created_at, updated_at)
            values (?, ?, 'x', 'v1:x', 'PLATFORM_SUPPORT', 'ACTIVE', ?, ?)""";

    @Test
    void platformTablesAreInvisibleAndUnwritableWithoutTheFlag() {
        String email = "ops-" + UUID.randomUUID() + "@nexusops.test";
        Timestamp now = Timestamp.from(Instant.now());
        access.writeWithoutResult(() -> jdbc.update(INSERT_PLATFORM_USER, UUID.randomUUID(), email, now, now));

        assertThat(OwnerJdbc.rawApp().queryForObject(
                "select count(*) from platform_users where email = ?", Long.class, email)).isZero();
        assertThatThrownBy(() -> OwnerJdbc.rawApp().update(INSERT_PLATFORM_USER, UUID.randomUUID(),
                "x-" + email, now, now)).hasMessageContaining("row-level security");
        assertThat(access.read(() -> jdbc.queryForObject(
                "select count(*) from platform_users where email = ?", Long.class, email))).isOne();
    }

    @Test
    void theFlagGivesReadOnlyCrossTenantVisibility() throws Exception {
        UUID a = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("pa-a")).tenantId();
        UUID b = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("pa-b")).tenantId();

        assertThat(access.read(() -> jdbc.queryForObject(
                "select count(*) from users where tenant_id in (?, ?)", Long.class, a, b))).isEqualTo(2);
        assertThat(access.read(() -> jdbc.queryForObject("""
                select count(*) from user_roles ur join roles r on r.id = ur.role_id
                where r.tenant_id in (?, ?) and r.name = 'TENANT_OWNER'""", Long.class, a, b))).isEqualTo(2);
        // The flag never widens writes — including on the join tables whose policies use EXISTS subqueries.
        assertThat(access.write(() -> jdbc.update(
                "update users set first_name = 'Mallory' where tenant_id in (?, ?)", a, b))).isZero();
        assertThat(access.write(() -> jdbc.update("delete from user_roles"))).isZero();
        assertThat(access.write(() -> jdbc.update("delete from role_permissions"))).isZero();
        UUID userOfA = access.read(() -> jdbc.queryForObject("select id from users where tenant_id = ?", UUID.class, a));
        UUID roleOfB = access.read(() -> jdbc.queryForObject(
                "select id from roles where tenant_id = ? and name = 'TENANT_ADMIN'", UUID.class, b));
        assertThatThrownBy(() -> access.writeWithoutResult(() -> jdbc.update(
                "insert into user_roles (user_id, role_id) values (?, ?)", userOfA, roleOfB)))
                .hasMessageContaining("row-level security");
        // ...and tenant-scoped access is unchanged (the restated policies are not stricter in-tenant).
        assertThat(OwnerJdbc.ownerAs(a).queryForObject("select count(*) from user_roles", Long.class)).isOne();
    }

    @Test
    void theFlagEndsWithItsTransaction() {
        var single = new SingleConnectionDataSource(IntegrationTestSupport.POSTGRES.getJdbcUrl(), "nexusops_app",
                IntegrationTestSupport.APP_PASSWORD, true);
        try {
            JdbcTemplate one = new JdbcTemplate(single);
            new TransactionTemplate(new DataSourceTransactionManager(single)).executeWithoutResult(s ->
                    one.queryForObject("select set_config('app.platform_access', 'on', true)", String.class));
            assertThat(one.queryForObject(
                    "select coalesce(current_setting('app.platform_access', true), '')", String.class)).isEmpty();
            assertThat(one.queryForObject("select count(*) from users", Long.class)).isZero();
        } finally {
            single.destroy();
        }
    }

    @Test
    void refusesInsideATenantScopeOrAnOpenTransaction() {
        try (var scope = TenantContext.open(UUID.randomUUID(), null)) {
            assertThatThrownBy(() -> access.read(() -> 1)).isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> tx.execute(s -> access.read(() -> 1))).isInstanceOf(IllegalStateException.class);
    }
}
```

`backend/src/test/java/com/nexusops/platform/PlatformAccessConfinementTest.java`:

```java
package com.nexusops.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Spec §4.2: only the platform module may turn on cross-tenant visibility, and only through PlatformAccess. */
class PlatformAccessConfinementTest {

    private static final Path MAIN = Path.of("src/main/java");

    @Test
    void onlyThePlatformModuleMentionsPlatformAccess() throws IOException {
        assertThat(filesContaining("app.platform_access"))
                .allMatch(path -> path.contains("com/nexusops/platform/"), "lives in com/nexusops/platform/");
    }

    @Test
    void onlyPlatformAccessSetsTheFlag() throws IOException {
        assertThat(filesContaining("set_config('app.platform_access'"))
                .containsExactly("src/main/java/com/nexusops/platform/internal/PlatformAccess.java");
    }

    private static List<String> filesContaining(String needle) throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> read(p).contains(needle))
                    .map(p -> p.toString().replace('\\', '/'))
                    .toList();
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
```

In `RlsCoverageIT`, add a set and an assertion:

```java
    static final Set<String> PLATFORM_TABLES = Set.of("platform_users", "platform_refresh_tokens");
```

In `expectedTenantTablesExist()`, append `.containsAll(PLATFORM_TABLES)` to the existing `assertThat(tables)` chain. `everyNonGlobalTableForcesRlsAndHasAPolicy` then covers them automatically: they are not in `GLOBAL_TABLES`.

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && ./gradlew test --tests 'com.nexusops.platform.*' --tests 'com.nexusops.RlsCoverageIT'`
Expected: compilation FAILS: `package com.nexusops.platform.internal does not exist`.

- [ ] **Step 3: Write the migration**

`backend/src/main/resources/db/migration/V7__platform.sql`:

```sql
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
```

- [ ] **Step 4: Write `PlatformAccess` and the module marker**

`backend/src/main/java/com/nexusops/platform/package-info.java`:

```java
/**
 * Platform administration (ADR-0007): platform users with password + TOTP, a separate token audience and
 * security chain, and cross-tenant workspace administration. Depends only on shared, tenancy and audit.
 */
package com.nexusops.platform;
```

`backend/src/main/java/com/nexusops/platform/internal/PlatformAccess.java`:

```java
package com.nexusops.platform.internal;

import com.nexusops.shared.TenantContext;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The ONLY code that turns on {@code app.platform_access} (spec §4.2, ADR-0007). The setting is
 * transaction-local ({@code set_config(..., true)}), so it ends with the transaction and never reaches the
 * next user of a pooled connection. Platform visibility never mixes with tenant work: running inside a
 * tenant scope or inside an already-open transaction is refused.
 */
@Component
public class PlatformAccess {

    private static final String ENABLE = "select set_config('app.platform_access', 'on', true)";

    private final TransactionTemplate readWrite;
    private final TransactionTemplate readOnly;
    private final JdbcTemplate jdbc;

    PlatformAccess(PlatformTransactionManager transactions, JdbcTemplate jdbc) {
        this.readWrite = new TransactionTemplate(transactions);
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
        this.jdbc = jdbc;
    }

    public <T> T read(Supplier<T> work) {
        return run(readOnly, work);
    }

    public <T> T write(Supplier<T> work) {
        return run(readWrite, work);
    }

    public void writeWithoutResult(Runnable work) {
        write(() -> {
            work.run();
            return null;
        });
    }

    private <T> T run(TransactionTemplate template, Supplier<T> work) {
        if (TenantContext.tenantId().isPresent()) {
            throw new IllegalStateException("Platform access is never combined with a tenant scope");
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Platform access must start its own transaction");
        }
        return template.execute(status -> {
            jdbc.queryForObject(ENABLE, String.class);
            return work.get();
        });
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd backend && ./gradlew test --tests 'com.nexusops.platform.*' --tests 'com.nexusops.RlsCoverageIT' --tests 'com.nexusops.ModularityTest'`
Expected: PASS. If `theFlagGivesReadOnlyCrossTenantVisibility` counts 0 users, check that `PlatformAccess` runs `ENABLE` through the same `JdbcTemplate` and that the transaction manager is the JPA one. Spring Boot's `JpaTransactionManager` exposes its JDBC connection to `JdbcTemplate`.

- [ ] **Step 6: Run the whole backend suite**

Run: `cd backend && ./gradlew build`
Expected: BUILD SUCCESSFUL. `CrossTenantApiIT`, `TenantIsolationIT` and `RlsBehaviourIT` stay green, because no tenant code path sets the flag.

- [ ] **Step 7: Commit**

```bash
git add backend/src
git commit -m "feat: platform tables behind a transaction-local platform_access flag with read-only cross-tenant policies"
```

---

### Task 3: TOTP (RFC 6238) and AES-256-GCM secret encryption; `PLATFORM_TOTP_KEY` is a required secret

**Files:**
- Create: `backend/src/main/java/com/nexusops/platform/totp/Totp.java`
- Create: `backend/src/main/java/com/nexusops/platform/totp/TotpSecretCipher.java`
- Create: `backend/src/main/java/com/nexusops/platform/PlatformProperties.java`
- Create: `backend/src/main/java/com/nexusops/platform/internal/PlatformConfig.java`
- Modify: `backend/src/main/resources/application.yml`, `application-local.yml`, `application-test.yml`
- Modify: `backend/src/main/java/com/nexusops/shared/config/RequiredSecretsVerifier.java`
- Test: `backend/src/test/java/com/nexusops/platform/totp/TotpTest.java`, `backend/src/test/java/com/nexusops/platform/totp/TotpSecretCipherTest.java`, `backend/src/test/java/com/nexusops/shared/config/RequiredSecretsVerifierTest.java`, `backend/src/test/java/com/nexusops/ProdProfileRequiresSecretsTest.java`

**Interfaces:**
- Produces:
  - `Totp` with these static members:
    - `byte[] newSecret()` (20 bytes);
    - `long step(Instant)`;
    - `String code(byte[] secret, long step)`;
    - `OptionalLong verify(byte[] secret, String submitted, Instant now, long lastUsedStep)`, which returns the matched step;
    - `String base32(byte[])`;
    - `String otpauthUri(String issuer, String account, byte[] secret)`.
  - `TotpSecretCipher(String base64Key)` with `String encrypt(byte[] secret, UUID owner)` and `byte[] decrypt(String stored, UUID owner)`.
  - `PlatformProperties(String audience, Duration accessTokenTtl, Duration sessionMaxAge, String totpIssuer, String totpKey)`, bound to `nexusops.platform`.
  - Bean `TotpSecretCipher`.

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/com/nexusops/platform/totp/TotpTest.java`:

```java
package com.nexusops.platform.totp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TotpTest {

    /** RFC 6238 Appendix B test secret (SHA-1). */
    static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    void matchesTheRfc6238TestVectorsTruncatedToSixDigits() {
        assertThat(Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(59)))).isEqualTo("287082");
        assertThat(Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(1111111109)))).isEqualTo("081804");
        assertThat(Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(1111111111)))).isEqualTo("050471");
        assertThat(Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(1234567890)))).isEqualTo("005924");
        assertThat(Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(2000000000)))).isEqualTo("279037");
    }

    @Test
    void acceptsOneStepOfSkewEitherWay() {
        Instant now = Instant.ofEpochSecond(1_800_000_000L);
        long step = Totp.step(now);
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step - 1), now, 0)).hasValue(step - 1);
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step), now, 0)).hasValue(step);
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step + 1), now, 0)).hasValue(step + 1);
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step - 2), now, 0)).isEmpty();
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step + 2), now, 0)).isEmpty();
    }

    @Test
    void rejectsAStepAtOrBeforeTheLastUsedOne() {
        Instant now = Instant.ofEpochSecond(1_800_000_000L);
        long step = Totp.step(now);
        String code = Totp.code(RFC_SECRET, step);
        assertThat(Totp.verify(RFC_SECRET, code, now, step)).isEmpty();
        assertThat(Totp.verify(RFC_SECRET, code, now, step - 1)).hasValue(step);
    }

    @Test
    void rejectsMalformedInputAndToleratesSpaces() {
        Instant now = Instant.ofEpochSecond(1_800_000_000L);
        String code = Totp.code(RFC_SECRET, Totp.step(now));
        assertThat(Totp.verify(RFC_SECRET, null, now, 0)).isEmpty();
        assertThat(Totp.verify(RFC_SECRET, "12345", now, 0)).isEmpty();
        assertThat(Totp.verify(RFC_SECRET, "abcdef", now, 0)).isEmpty();
        assertThat(Totp.verify(RFC_SECRET, code.substring(0, 3) + " " + code.substring(3), now, 0)).isPresent();
    }

    @Test
    void encodesBase32AndBuildsAnOtpauthUri() {
        assertThat(Totp.base32(RFC_SECRET)).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        assertThat(Totp.otpauthUri("NexusOps", "ops@nexusops.test", RFC_SECRET)).isEqualTo(
                "otpauth://totp/NexusOps:ops%40nexusops.test?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
                        + "&issuer=NexusOps&algorithm=SHA1&digits=6&period=30");
        assertThat(Totp.newSecret()).hasSize(20).isNotEqualTo(Totp.newSecret());
    }
}
```

`backend/src/test/java/com/nexusops/platform/totp/TotpSecretCipherTest.java`:

```java
package com.nexusops.platform.totp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TotpSecretCipherTest {

    static final String KEY = Base64.getEncoder().encodeToString("test-only-totp-key-32-bytes!!!!!".getBytes());
    final TotpSecretCipher cipher = new TotpSecretCipher(KEY);

    @Test
    void roundTripsAndUsesAFreshIvEachTime() {
        UUID owner = UUID.randomUUID();
        byte[] secret = Totp.newSecret();
        String first = cipher.encrypt(secret, owner);
        assertThat(first).startsWith("v1:").doesNotContain(Totp.base32(secret));
        assertThat(cipher.encrypt(secret, owner)).isNotEqualTo(first);
        assertThat(cipher.decrypt(first, owner)).isEqualTo(secret);
    }

    @Test
    void aCiphertextIsBoundToItsOwner() {
        String stored = cipher.encrypt(Totp.newSecret(), UUID.randomUUID());
        assertThatThrownBy(() -> cipher.decrypt(stored, UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tamperingAndWrongKeysAreDetected() {
        UUID owner = UUID.randomUUID();
        String stored = cipher.encrypt(Totp.newSecret(), owner);
        char last = stored.charAt(stored.length() - 2);
        String tampered = stored.substring(0, stored.length() - 2) + (last == 'A' ? 'B' : 'A') + stored.charAt(stored.length() - 1);
        assertThatThrownBy(() -> cipher.decrypt(tampered, owner)).isInstanceOf(IllegalStateException.class);
        var other = new TotpSecretCipher(Base64.getEncoder().encodeToString("another-test-key-of-32-bytes!!!!".getBytes()));
        assertThatThrownBy(() -> other.decrypt(stored, owner)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsAKeyThatIsNotThirtyTwoBytes() {
        assertThatThrownBy(() -> new TotpSecretCipher(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("PLATFORM_TOTP_KEY");
        assertThatThrownBy(() -> new TotpSecretCipher("not base64 !!"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("PLATFORM_TOTP_KEY");
    }
}
```

In `RequiredSecretsVerifierTest`:
- add `.withProperty("nexusops.platform.totp-key", "k")` to the environment in all three existing tests;
- add this test:

```java
    @Test
    void failsWithoutThePlatformTotpKey() {
        var env = new MockEnvironment()
                .withProperty("spring.datasource.password", "a")
                .withProperty("spring.flyway.password", "b")
                .withProperty("nexusops.security.allowed-origins", "https://app.example.com")
                .withProperty("nexusops.platform.totp-key", "");
        assertThatThrownBy(() -> RequiredSecretsVerifier.verify(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PLATFORM_TOTP_KEY");
    }
```

In `ProdProfileRequiresSecretsTest`, add:

```java
    @Test
    void prodProfileFailsFastWithoutThePlatformTotpKey() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(NexusOpsApplication.class)
                        .profiles("prod")
                        .run("--spring.main.web-application-type=none", "--spring.datasource.password=x",
                                "--spring.flyway.password=y", "--nexusops.security.allowed-origins=https://app.example.com"))
                .hasStackTraceContaining("Missing required secret")
                .hasStackTraceContaining("PLATFORM_TOTP_KEY");
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && ./gradlew test --tests 'com.nexusops.platform.totp.*' --tests 'com.nexusops.shared.config.RequiredSecretsVerifierTest' --tests 'com.nexusops.ProdProfileRequiresSecretsTest'`
Expected: compilation FAILS: `cannot find symbol: class Totp`.

- [ ] **Step 3: Implement `Totp`**

`backend/src/main/java/com/nexusops/platform/totp/Totp.java`:

```java
package com.nexusops.platform.totp;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.OptionalLong;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * RFC 6238 TOTP: HMAC-SHA1, 6 digits, 30-second steps, ±1 step of clock skew. {@link #verify} also enforces
 * single use: a code is accepted only for a step strictly after the last step the account used.
 */
public final class Totp {

    public static final int DIGITS = 6;
    public static final long PERIOD_SECONDS = 30;
    public static final int WINDOW = 1;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private static final Pattern SIX_DIGITS = Pattern.compile("\\d{6}");

    private Totp() {}

    public static byte[] newSecret() {
        byte[] secret = new byte[20];
        RANDOM.nextBytes(secret);
        return secret;
    }

    public static long step(Instant now) {
        return Math.floorDiv(now.getEpochSecond(), PERIOD_SECONDS);
    }

    public static String code(byte[] secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            return String.format("%06d", binary % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The step the submitted code belongs to, if it is within ±{@link #WINDOW} of {@code now} and strictly after
     * {@code lastUsedStep}; empty otherwise. Every candidate is compared in constant time, with no early exit.
     */
    public static OptionalLong verify(byte[] secret, String submitted, Instant now, long lastUsedStep) {
        if (submitted == null) {
            return OptionalLong.empty();
        }
        String code = submitted.replace(" ", "");
        if (!SIX_DIGITS.matcher(code).matches()) {
            return OptionalLong.empty();
        }
        byte[] given = code.getBytes(StandardCharsets.US_ASCII);
        long current = step(now);
        long matched = -1;
        for (long candidate = current - WINDOW; candidate <= current + WINDOW; candidate++) {
            boolean equal = MessageDigest.isEqual(code(secret, candidate).getBytes(StandardCharsets.US_ASCII), given);
            if (equal && candidate > lastUsedStep && matched < 0) {
                matched = candidate;
            }
        }
        return matched < 0 ? OptionalLong.empty() : OptionalLong.of(matched);
    }

    /** RFC 4648 base32, no padding (the form authenticator apps expect). */
    public static String base32(byte[] data) {
        StringBuilder out = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32[(buffer >> (bits - 5)) & 0x1f]);
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32[(buffer << (5 - bits)) & 0x1f]);
        }
        return out.toString();
    }

    public static String otpauthUri(String issuer, String account, byte[] secret) {
        return "otpauth://totp/" + encode(issuer) + ":" + encode(account)
                + "?secret=" + base32(secret)
                + "&issuer=" + encode(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + PERIOD_SECONDS;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
```

- [ ] **Step 4: Implement `TotpSecretCipher`, properties and config**

`backend/src/main/java/com/nexusops/platform/totp/TotpSecretCipher.java`:

```java
package com.nexusops.platform.totp;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts TOTP secrets at rest (spec §5 {@code totp_secret_enc}): AES-256-GCM with a random 96-bit IV and the
 * owning platform user's id as associated data, so a ciphertext copied onto another row fails to decrypt.
 * Stored form: {@code v1:} + base64(iv ‖ ciphertext ‖ tag).
 */
public final class TotpSecretCipher {

    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public TotpSecretCipher(String base64Key) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key == null ? "" : base64Key.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("PLATFORM_TOTP_KEY must be 32 bytes, base64-encoded.");
        }
        if (raw.length != 32) {
            throw new IllegalStateException("PLATFORM_TOTP_KEY must be 32 bytes, base64-encoded.");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public String encrypt(byte[] secret, UUID owner) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(owner.toString().getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(secret);
            return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + sealed.length)
                    .put(iv).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot encrypt TOTP secret", e);
        }
    }

    public byte[] decrypt(String stored, UUID owner) {
        try {
            if (stored == null || !stored.startsWith(PREFIX)) {
                throw new IllegalStateException("Unknown TOTP secret format");
            }
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            cipher.updateAAD(owner.toString().getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Cannot decrypt TOTP secret", e);
        }
    }
}
```

`backend/src/main/java/com/nexusops/platform/PlatformProperties.java`:

```java
package com.nexusops.platform;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code nexusops.platform.*}: platform token audience, lifetimes and the TOTP key/issuer (ADR-0007). */
@ConfigurationProperties("nexusops.platform")
public record PlatformProperties(
        String audience,
        Duration accessTokenTtl,
        Duration sessionMaxAge,
        String totpIssuer,
        String totpKey) {}
```

`backend/src/main/java/com/nexusops/platform/internal/PlatformConfig.java`:

```java
package com.nexusops.platform.internal;

import com.nexusops.platform.PlatformProperties;
import com.nexusops.platform.totp.TotpSecretCipher;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PlatformProperties.class)
class PlatformConfig {

    @Bean
    TotpSecretCipher totpSecretCipher(PlatformProperties properties) {
        return new TotpSecretCipher(properties.totpKey());
    }
}
```

In `RequiredSecretsVerifier`'s static block, append:

```java
        REQUIRED.put("nexusops.platform.totp-key", "PLATFORM_TOTP_KEY");
```

In `application.yml`, under `nexusops:` (a sibling of `security:`), add:

```yaml
  platform:
    audience: nexusops-platform
    access-token-ttl: 15m
    session-max-age: 8h
    totp-issuer: NexusOps
    totp-key: ${PLATFORM_TOTP_KEY:}
```

In `application-local.yml`, under `nexusops:`, add the following. This key is a fixed local-only value; it is the base64 of `local-only-totp-key-32-bytes!!!!`.

```yaml
  platform:
    # Local only — NOT a secret. Production must set PLATFORM_TOTP_KEY (32 random bytes, base64).
    totp-key: ${PLATFORM_TOTP_KEY:bG9jYWwtb25seS10b3RwLWtleS0zMi1ieXRlcyEhISE=}
```

In `application-test.yml`, under `nexusops:`, add (the base64 of `test-only-totp-key-32-bytes!!!!!`):

```yaml
  platform:
    totp-key: dGVzdC1vbmx5LXRvdHAta2V5LTMyLWJ5dGVzISEhISE=
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd backend && ./gradlew test --tests 'com.nexusops.platform.totp.*' --tests 'com.nexusops.shared.config.RequiredSecretsVerifierTest' --tests 'com.nexusops.ProdProfileRequiresSecretsTest'`
Expected: PASS. `prodProfileFailsFastWithoutAllowedOrigins` still reports `ALLOWED_ORIGINS` first, because the verifier checks in insertion order.

- [ ] **Step 6: Run the whole backend suite**

Run: `cd backend && ./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add backend/src
git commit -m "feat: RFC 6238 TOTP with replay guard and AES-GCM encrypted secrets; PLATFORM_TOTP_KEY required"
```

---
### Task 4: Platform users and the CLI — create, reset TOTP, reset password, disable, enable

**Files:**
- Create: `backend/src/main/java/com/nexusops/platform/domain/PlatformRole.java`
- Create: `backend/src/main/java/com/nexusops/platform/domain/PlatformUserStatus.java`
- Create: `backend/src/main/java/com/nexusops/platform/domain/PlatformUser.java`
- Create: `backend/src/main/java/com/nexusops/platform/domain/PlatformUserRepository.java`
- Create: `backend/src/main/java/com/nexusops/platform/application/PlatformUserAdmin.java`
- Create: `backend/src/main/java/com/nexusops/platform/cli/Terminal.java`
- Create: `backend/src/main/java/com/nexusops/platform/cli/ConsoleTerminal.java`
- Create: `backend/src/main/java/com/nexusops/platform/cli/QrCodes.java`
- Create: `backend/src/main/java/com/nexusops/platform/cli/PlatformCli.java`
- Modify: `backend/src/main/java/com/nexusops/NexusOpsApplication.java`
- Modify: `backend/src/main/java/com/nexusops/identity/security/SecurityConfig.java` (web-only, so the CLI context starts)
- Modify: `backend/build.gradle.kts` (add ZXing core)
- Modify: `Makefile`
- Create: `backend/src/test/java/com/nexusops/support/TestPlatformUsers.java`
- Test: `backend/src/test/java/com/nexusops/platform/PlatformCliIT.java`, `backend/src/test/java/com/nexusops/platform/PlatformCliStartupIT.java`, `backend/src/test/java/com/nexusops/platform/cli/QrCodesTest.java`, `backend/src/test/java/com/nexusops/NexusOpsApplicationTest.java`

**Interfaces:**
- Consumes:
  - `PlatformAccess.read/write/writeWithoutResult` (Task 2).
  - `Totp`, `TotpSecretCipher` and `PlatformProperties.totpIssuer()` (Task 3).
  - `shared.Emails` and `shared.security.PasswordPolicy` (Task 1).
  - `AuditEntry.asActor(ActorType.SYSTEM)`.
- Produces:
  - `PlatformRole { PLATFORM_ADMIN, PLATFORM_SUPPORT }` with `Set<String> authorities()`.
  - `PlatformUserStatus { ACTIVE, DISABLED }`.
  - Entity `PlatformUser`, with:
    - getters `getEmail()`, `getPasswordHash()`, `getTotpSecretEnc()`, `getTotpLastStep()`, `getRole()`, `getStatus()`, `getTokenVersion()`;
    - mutators `replaceTotp(String)`, `replacePassword(String)`, `disable()`, `enable()`, `recordTotpUse(long)`, `recordLogin(Instant)`.
  - `PlatformUserRepository`, with `findByEmail(String)` and `findForUpdateById(UUID)` (row lock). Use it **only inside `PlatformAccess`**.
  - `PlatformUserAdmin`, with:
    - `Enrollment newEnrollment(String email)`;
    - `void checkPassword(String password, String email)`;
    - `UUID create(String email, PlatformRole role, String password, byte[] secret)`;
    - `void resetTotp(String email, byte[] secret)`;
    - `void resetPassword(String email, String password)`;
    - `void setStatus(String email, PlatformUserStatus status)`.

    `Enrollment(String email, byte[] secret, String otpauthUri)` has `secretBase32()`.
  - Test support `TestPlatformUsers`:
    - `create(PlatformUserAdmin, PlatformRole)` → `Operator(UUID id, String email, String password, byte[] secret)`, where `Operator` has `codeAt(long step)` and `currentCode()`;
    - `platformJdbc()`;
    - `base32Decode(String)`.

- [ ] **Step 1: Add the dependency, test support and failing tests**

In `backend/build.gradle.kts` `dependencies`, after the bouncycastle line, add:

```kotlin
    implementation("com.google.zxing:core:3.5.3") // terminal QR code for platform TOTP enrolment (CLI only)
```

`backend/src/test/java/com/nexusops/support/TestPlatformUsers.java`:

```java
package com.nexusops.support;

import com.nexusops.platform.application.PlatformUserAdmin;
import com.nexusops.platform.domain.PlatformRole;
import com.nexusops.platform.totp.Totp;
import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Creates platform operators through the real service; test-only access to the flag-gated platform tables. */
public final class TestPlatformUsers {

    public static final String PASSWORD = "platform horse battery staple";

    public record Operator(UUID id, String email, String password, byte[] secret) {
        public String codeAt(long step) {
            return Totp.code(secret, step);
        }

        public String currentCode() {
            return codeAt(Totp.step(Instant.now()));
        }
    }

    private TestPlatformUsers() {}

    public static Operator create(PlatformUserAdmin admin, PlatformRole role) {
        String email = "ops-" + UUID.randomUUID().toString().substring(0, 8) + "@nexusops.test";
        byte[] secret = Totp.newSecret();
        UUID id = admin.create(email, role, PASSWORD, secret);
        return new Operator(id, email, PASSWORD, secret);
    }

    /** Owner connections with app.platform_access on at session level: TEST-ONLY inspection/setup of platform tables. */
    public static JdbcTemplate platformJdbc() {
        var target = new DriverManagerDataSource(IntegrationTestSupport.POSTGRES.getJdbcUrl(), "nexusops_owner",
                IntegrationTestSupport.OWNER_PASSWORD);
        return new JdbcTemplate(new DelegatingDataSource(target) {
            @Override
            public Connection getConnection() throws SQLException {
                Connection connection = super.getConnection();
                try (var ps = connection.prepareStatement("select set_config('app.platform_access', 'on', false)")) {
                    ps.execute();
                } catch (SQLException | RuntimeException e) {
                    connection.close();
                    throw e;
                }
                return connection;
            }
        });
    }

    public static byte[] base32Decode(String encoded) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        var out = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : encoded.replace(" ", "").toCharArray()) {
            buffer = (buffer << 5) | alphabet.indexOf(c);
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
```

`backend/src/test/java/com/nexusops/platform/PlatformCliIT.java`:

```java
package com.nexusops.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexusops.platform.application.PlatformUserAdmin;
import com.nexusops.platform.cli.PlatformCli;
import com.nexusops.platform.cli.Terminal;
import com.nexusops.platform.domain.PlatformRole;
import com.nexusops.platform.totp.Totp;
import com.nexusops.platform.totp.TotpSecretCipher;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.TestPlatformUsers;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

class PlatformCliIT extends IntegrationTestSupport {

    static final Instant NOW = Instant.parse("2026-10-06T10:00:00Z");
    static final String STRONG = "a long platform passphrase 42";
    static final Pattern SECRET_LINE = Pattern.compile("Or enter this secret manually: ([A-Z2-7 ]+)");

    @Autowired PlatformUserAdmin admin;
    @Autowired TotpSecretCipher cipher;
    @Autowired PasswordEncoder passwordEncoder;

    String email;

    @BeforeEach
    void freshEmail() {
        email = "ops-" + UUID.randomUUID().toString().substring(0, 8) + "@nexusops.test";
    }

    /** Answers secrets from a queue; answers code prompts with the code for the secret it printed (or a fixed wrong one). */
    static final class ScriptedTerminal implements Terminal {
        final Deque<String> secrets = new ArrayDeque<>();
        final List<String> output = new ArrayList<>();
        boolean answerCorrectly = true;

        ScriptedTerminal secrets(String... values) {
            secrets.addAll(List.of(values));
            return this;
        }

        @Override
        public String readLine(String prompt) {
            output.add(prompt);
            if (!answerCorrectly) {
                return "000000".equals(correctCode()) ? "111111" : "000000";
            }
            return correctCode();
        }

        @Override
        public char[] readSecret(String prompt) {
            output.add(prompt);
            return secrets.isEmpty() ? null : secrets.removeFirst().toCharArray();
        }

        @Override
        public void println(String line) {
            output.add(line);
        }

        byte[] printedSecret() {
            for (int i = output.size() - 1; i >= 0; i--) {
                var m = SECRET_LINE.matcher(output.get(i));
                if (m.find()) {
                    return TestPlatformUsers.base32Decode(m.group(1));
                }
            }
            throw new AssertionError("no secret printed");
        }

        String correctCode() {
            return Totp.code(printedSecret(), Totp.step(NOW));
        }

        String all() {
            return String.join("\n", output);
        }
    }

    private int run(ScriptedTerminal terminal, String command, String role) {
        return new PlatformCli(admin, terminal, command, email, role, Clock.fixed(NOW, ZoneOffset.UTC)).execute();
    }

    private Map<String, Object> row() {
        return TestPlatformUsers.platformJdbc().queryForMap("select * from platform_users where email = ?", email);
    }

    private long count() {
        return TestPlatformUsers.platformJdbc().queryForObject(
                "select count(*) from platform_users where email = ?", Long.class, email);
    }

    @Test
    void createsAnAdminOnlyAfterTheEnrolmentCodeIsConfirmed() {
        var terminal = new ScriptedTerminal().secrets(STRONG, STRONG);
        assertThat(run(terminal, "create-platform-admin", "PLATFORM_ADMIN")).isZero();

        Map<String, Object> row = row();
        UUID id = (UUID) row.get("id");
        assertThat(row.get("role")).isEqualTo("PLATFORM_ADMIN");
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        assertThat(cipher.decrypt((String) row.get("totp_secret_enc"), id)).isEqualTo(terminal.printedSecret());
        assertThat(passwordEncoder.matches(STRONG, (String) row.get("password_hash"))).isTrue();
        assertThat(terminal.all()).contains("otpauth://totp/NexusOps:").doesNotContain(STRONG);
        assertThat(OwnerJdbc.superuser().queryForObject("""
                select count(*) from audit_events where action = 'PlatformUserCreated' and entity_id = ?
                  and tenant_id is null and actor_type = 'SYSTEM'""", Long.class, id.toString())).isOne();
    }

    @Test
    void threeWrongCodesSaveNothing() {
        var terminal = new ScriptedTerminal().secrets(STRONG, STRONG);
        terminal.answerCorrectly = false;
        assertThat(run(terminal, "create-platform-admin", "PLATFORM_SUPPORT")).isEqualTo(1);
        assertThat(count()).isZero();
        assertThat(terminal.all()).contains("nothing was saved");
    }

    @Test
    void mismatchedOrWeakPasswordsSaveNothing() {
        var mismatch = new ScriptedTerminal().secrets(STRONG, STRONG + "!");
        assertThat(run(mismatch, "create-platform-admin", "PLATFORM_ADMIN")).isEqualTo(1);
        assertThat(mismatch.all()).contains("the passwords don't match");

        var weak = new ScriptedTerminal().secrets("short", "short");
        assertThat(run(weak, "create-platform-admin", "PLATFORM_ADMIN")).isEqualTo(1);
        assertThat(weak.all()).contains("Use at least 12 characters.");
        assertThat(count()).isZero();
    }

    @Test
    void refusesADuplicateEmailAndAnUnknownRole() {
        createExisting();
        var again = new ScriptedTerminal().secrets(STRONG, STRONG);
        assertThat(run(again, "create-platform-admin", "PLATFORM_ADMIN")).isEqualTo(1);
        assertThat(again.all()).contains("already exists");
        assertThat(run(new ScriptedTerminal(), "create-platform-admin", "ROOT")).isEqualTo(2);
    }

    @Test
    void resetTotpReplacesTheSecretAndEndsSessions() {
        createExisting();
        var terminal = new ScriptedTerminal();
        assertThat(run(terminal, "reset-platform-totp", null)).isZero();
        Map<String, Object> row = row();
        assertThat(cipher.decrypt((String) row.get("totp_secret_enc"), (UUID) row.get("id"))).isEqualTo(terminal.printedSecret());
        assertThat(row.get("token_version")).isEqualTo(1);
        assertThat(row.get("totp_last_step")).isEqualTo(0L);
    }

    @Test
    void resetPasswordDisableAndEnable() {
        createExisting();
        assertThat(run(new ScriptedTerminal().secrets(STRONG, STRONG), "reset-platform-password", null)).isZero();
        assertThat(passwordEncoder.matches(STRONG, (String) row().get("password_hash"))).isTrue();
        assertThat(run(new ScriptedTerminal(), "disable-platform-user", null)).isZero();
        assertThat(row().get("status")).isEqualTo("DISABLED");
        assertThat(row().get("token_version")).isEqualTo(2);
        assertThat(run(new ScriptedTerminal(), "enable-platform-user", null)).isZero();
        assertThat(row().get("status")).isEqualTo("ACTIVE");
        assertThat(run(new ScriptedTerminal(), "disable-platform-user", null)).isZero();
        email = "nobody-" + email;
        var missing = new ScriptedTerminal();
        assertThat(run(missing, "disable-platform-user", null)).isEqualTo(1);
        assertThat(missing.all()).contains("No platform user with that email.");
    }

    @Test
    void unknownCommandOrMissingEmailPrintsUsage() {
        assertThat(run(new ScriptedTerminal(), "make-coffee", null)).isEqualTo(2);
        email = " ";
        var terminal = new ScriptedTerminal();
        assertThat(run(terminal, "create-platform-admin", "PLATFORM_ADMIN")).isEqualTo(2);
        assertThat(terminal.all()).contains("Usage:");
    }

    private void createExisting() {
        admin.create(email, PlatformRole.PLATFORM_ADMIN, TestPlatformUsers.PASSWORD, Totp.newSecret());
    }
}
```

`backend/src/test/java/com/nexusops/platform/cli/QrCodesTest.java`:

```java
package com.nexusops.platform.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class QrCodesTest {

    @Test
    void rendersASquareBlackOnWhiteBlockOfHalfBlocks() {
        List<String> lines = QrCodes.render("otpauth://totp/NexusOps:ops%40nexusops.test?secret=GEZDGNBV").lines().toList();
        assertThat(lines).hasSizeGreaterThan(10);
        assertThat(lines).allSatisfy(line -> assertThat(line).startsWith("\u001b[30;47m").endsWith("\u001b[0m"));
        assertThat(lines.stream().map(String::length).distinct()).hasSize(1);
        assertThat(String.join("", lines)).contains("█");
    }
}
```

`backend/src/test/java/com/nexusops/NexusOpsApplicationTest.java`:

```java
package com.nexusops;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NexusOpsApplicationTest {

    @Test
    void recognisesACliInvocation() {
        assertThat(NexusOpsApplication.isCliCommand(new String[] {"--nexusops.cli.command=create-platform-admin"})).isTrue();
        assertThat(NexusOpsApplication.isCliCommand(new String[] {"--server.port=8081"})).isFalse();
        assertThat(NexusOpsApplication.isCliCommand(new String[0])).isFalse();
    }
}
```

`backend/src/test/java/com/nexusops/platform/PlatformCliStartupIT.java`. It boots the real application the way `make platform-admin` does: no web server, CLI property set. It checks that the context starts and that the command's exit code comes back. Security filter chains need `HttpSecurity`, which only exists in a servlet application, so they must be web-only.

```java
package com.nexusops.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexusops.NexusOpsApplication;
import com.nexusops.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

class PlatformCliStartupIT {

    @Test
    void theCliStartsWithoutAWebServerAndExitsWithTheCommandsCode() {
        var pg = IntegrationTestSupport.POSTGRES; // starts the shared containers
        var context = new SpringApplicationBuilder(NexusOpsApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("test")
                .properties(
                        "spring.datasource.url=" + pg.getJdbcUrl(),
                        "spring.datasource.username=nexusops_app",
                        "spring.datasource.password=" + IntegrationTestSupport.APP_PASSWORD,
                        "spring.flyway.user=nexusops_owner",
                        "spring.flyway.password=" + IntegrationTestSupport.OWNER_PASSWORD,
                        "spring.data.redis.host=" + IntegrationTestSupport.REDIS.getHost(),
                        "spring.data.redis.port=" + IntegrationTestSupport.REDIS.getMappedPort(6379),
                        "nexusops.cli.command=make-coffee",
                        "nexusops.cli.email=ops@nexusops.test")
                .run();
        assertThat(SpringApplication.exit(context)).isEqualTo(2); // usage
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && ./gradlew test --tests 'com.nexusops.platform.PlatformCliIT' --tests 'com.nexusops.platform.PlatformCliStartupIT' --tests 'com.nexusops.platform.cli.*' --tests 'com.nexusops.NexusOpsApplicationTest'`
Expected: compilation FAILS: `package com.nexusops.platform.application does not exist`.

- [ ] **Step 3: Write the domain**

`platform/domain/PlatformRole.java`:

```java
package com.nexusops.platform.domain;

import java.util.Set;

/** Platform roles (ADR-0007). Authorities are resolved from the stored role on every request, never from a token. */
public enum PlatformRole {
    PLATFORM_ADMIN(Set.of("platform.tenant.read", "platform.tenant.suspend")),
    PLATFORM_SUPPORT(Set.of("platform.tenant.read"));

    private final Set<String> authorities;

    PlatformRole(Set<String> authorities) {
        this.authorities = authorities;
    }

    public Set<String> authorities() {
        return authorities;
    }
}
```

`platform/domain/PlatformUserStatus.java`:

```java
package com.nexusops.platform.domain;

public enum PlatformUserStatus { ACTIVE, DISABLED }
```

`platform/domain/PlatformUser.java`:

```java
package com.nexusops.platform.domain;

import com.nexusops.shared.db.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * A NexusOps staff account (spec §5). The table is visible only inside PlatformAccess. Changing the password or
 * the authenticator, or disabling the account, bumps {@code tokenVersion}, which ends every platform session.
 */
@Entity
@Table(name = "platform_users")
public class PlatformUser extends BaseEntity {

    @Column(nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "totp_secret_enc", nullable = false)
    private String totpSecretEnc;

    @Column(name = "totp_last_step", nullable = false)
    private long totpLastStep;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PlatformRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PlatformUserStatus status;

    @Column(name = "token_version", nullable = false)
    private int tokenVersion;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected PlatformUser() {}

    public static PlatformUser create(UUID id, String email, String passwordHash, String totpSecretEnc, PlatformRole role) {
        PlatformUser user = new PlatformUser();
        user.initId(id);
        user.email = email;
        user.passwordHash = passwordHash;
        user.totpSecretEnc = totpSecretEnc;
        user.role = role;
        user.status = PlatformUserStatus.ACTIVE;
        user.createdAt = Instant.now();
        user.updatedAt = user.createdAt;
        return user;
    }

    public void replaceTotp(String encryptedSecret) {
        totpSecretEnc = encryptedSecret;
        totpLastStep = 0;
        endSessions();
    }

    public void replacePassword(String newHash) {
        passwordHash = newHash;
        endSessions();
    }

    public void disable() {
        status = PlatformUserStatus.DISABLED;
        endSessions();
    }

    public void enable() {
        status = PlatformUserStatus.ACTIVE;
        updatedAt = Instant.now();
    }

    /** Single use (RFC 6238 §5.2): callers verified {@code step > totpLastStep} under a row lock. */
    public void recordTotpUse(long step) {
        if (step <= totpLastStep) {
            throw new IllegalStateException("TOTP step already used");
        }
        totpLastStep = step;
    }

    public void recordLogin(Instant now) {
        lastLoginAt = now;
        updatedAt = now;
    }

    private void endSessions() {
        tokenVersion++;
        updatedAt = Instant.now();
    }

    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getTotpSecretEnc() { return totpSecretEnc; }
    public long getTotpLastStep() { return totpLastStep; }
    public PlatformRole getRole() { return role; }
    public PlatformUserStatus getStatus() { return status; }
    public int getTokenVersion() { return tokenVersion; }
}
```

`platform/domain/PlatformUserRepository.java`:

```java
package com.nexusops.platform.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Use ONLY inside PlatformAccess: without app.platform_access the table is invisible (RLS). */
public interface PlatformUserRepository extends JpaRepository<PlatformUser, UUID> {

    Optional<PlatformUser> findByEmail(String email);

    /** Row lock: concurrent logins with the same TOTP code serialize, so only one can use it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from PlatformUser u where u.id = :id")
    Optional<PlatformUser> findForUpdateById(@Param("id") UUID id);
}
```

- [ ] **Step 4: Write `PlatformUserAdmin`**

`platform/application/PlatformUserAdmin.java`:

```java
package com.nexusops.platform.application;

import com.nexusops.audit.ActorType;
import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.platform.PlatformProperties;
import com.nexusops.platform.domain.PlatformRole;
import com.nexusops.platform.domain.PlatformUser;
import com.nexusops.platform.domain.PlatformUserRepository;
import com.nexusops.platform.domain.PlatformUserStatus;
import com.nexusops.platform.internal.PlatformAccess;
import com.nexusops.platform.totp.Totp;
import com.nexusops.platform.totp.TotpSecretCipher;
import com.nexusops.shared.Emails;
import com.nexusops.shared.Ids;
import com.nexusops.shared.security.PasswordPolicy;
import com.nexusops.shared.web.ApiProblem;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** Platform account administration, used only by the CLI (decision 1, ADR-0007). Every change is audited. */
@Service
public class PlatformUserAdmin {

    static final String NOT_FOUND = "No platform user with that email.";

    public record Enrollment(String email, byte[] secret, String otpauthUri) {
        public String secretBase32() {
            return Totp.base32(secret);
        }
    }

    private final PlatformAccess access;
    private final PlatformUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final TotpSecretCipher cipher;
    private final AuditService audit;
    private final PlatformProperties properties;

    PlatformUserAdmin(PlatformAccess access, PlatformUserRepository users, PasswordEncoder passwordEncoder,
            PasswordPolicy passwordPolicy, TotpSecretCipher cipher, AuditService audit, PlatformProperties properties) {
        this.access = access;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.cipher = cipher;
        this.audit = audit;
        this.properties = properties;
    }

    /** A fresh secret for the operator to scan; nothing is stored until create/resetTotp. */
    public Enrollment newEnrollment(String rawEmail) {
        String email = Emails.normalize(rawEmail);
        byte[] secret = Totp.newSecret();
        return new Enrollment(email, secret, Totp.otpauthUri(properties.totpIssuer(), email, secret));
    }

    public void checkPassword(String password, String rawEmail) {
        passwordPolicy.check(password, Emails.normalize(rawEmail));
    }

    public UUID create(String rawEmail, PlatformRole role, String password, byte[] secret) {
        String email = Emails.normalize(rawEmail);
        passwordPolicy.check(password, email);
        String hash = passwordEncoder.encode(password);
        UUID id = Ids.newId();
        return access.write(() -> {
            if (users.findByEmail(email).isPresent()) {
                throw ApiProblem.conflictField("email", "A platform user with this email already exists.");
            }
            users.saveAndFlush(PlatformUser.create(id, email, hash, cipher.encrypt(secret, id), role));
            audit.record(AuditEntry.of("PlatformUserCreated", "PlatformUser", id)
                    .withMetadata(Map.of("email", email, "role", role.name()))
                    .asActor(ActorType.SYSTEM));
            return id;
        });
    }

    public void resetTotp(String rawEmail, byte[] secret) {
        access.writeWithoutResult(() -> {
            PlatformUser user = find(rawEmail);
            user.replaceTotp(cipher.encrypt(secret, user.getId()));
            audit.record(AuditEntry.of("PlatformTotpReset", "PlatformUser", user.getId()).asActor(ActorType.SYSTEM));
        });
    }

    public void resetPassword(String rawEmail, String password) {
        String email = Emails.normalize(rawEmail);
        passwordPolicy.check(password, email);
        String hash = passwordEncoder.encode(password);
        access.writeWithoutResult(() -> {
            PlatformUser user = find(email);
            user.replacePassword(hash);
            audit.record(AuditEntry.of("PlatformPasswordReset", "PlatformUser", user.getId()).asActor(ActorType.SYSTEM));
        });
    }

    public void setStatus(String rawEmail, PlatformUserStatus status) {
        access.writeWithoutResult(() -> {
            PlatformUser user = find(rawEmail);
            if (status == PlatformUserStatus.DISABLED) {
                user.disable();
            } else {
                user.enable();
            }
            String action = status == PlatformUserStatus.DISABLED ? "PlatformUserDisabled" : "PlatformUserEnabled";
            audit.record(AuditEntry.of(action, "PlatformUser", user.getId()).asActor(ActorType.SYSTEM));
        });
    }

    private PlatformUser find(String rawEmail) {
        return users.findByEmail(Emails.normalize(rawEmail)).orElseThrow(() -> ApiProblem.notFound(NOT_FOUND));
    }
}
```

- [ ] **Step 5: Write the CLI**

`platform/cli/Terminal.java`:

```java
package com.nexusops.platform.cli;

/** The CLI's only I/O. Secrets are read without echo and are never written to a logger. */
public interface Terminal {

    String readLine(String prompt);

    char[] readSecret(String prompt);

    void println(String line);
}
```

`platform/cli/ConsoleTerminal.java`:

```java
package com.nexusops.platform.cli;

import java.io.Console;

/**
 * System console for input; refuses to READ without an interactive terminal (secrets must not come from pipes).
 * Output goes to stdout, so usage and errors still print when no console is attached.
 */
final class ConsoleTerminal implements Terminal {

    private Console console() {
        Console console = System.console();
        if (console == null) {
            throw new IllegalStateException(
                    "Run this command in an interactive terminal (for Docker: docker compose run -it ...).");
        }
        return console;
    }

    @Override
    public String readLine(String prompt) {
        return console().readLine("%s", prompt);
    }

    @Override
    public char[] readSecret(String prompt) {
        return console().readPassword("%s", prompt);
    }

    @Override
    public void println(String line) {
        System.out.println(line);
        System.out.flush();
    }
}
```

`platform/cli/QrCodes.java`:

```java
package com.nexusops.platform.cli;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.util.Map;

/** Renders a QR code with Unicode half blocks, black on white, so authenticator apps can scan it from a terminal. */
final class QrCodes {

    private static final int QUIET_ZONE = 2;
    private static final String COLORS = "\u001b[30;47m";
    private static final String RESET = "\u001b[0m";

    private QrCodes() {}

    static String render(String text) {
        BitMatrix matrix;
        try {
            matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
                    Map.of(EncodeHintType.MARGIN, 0, EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M));
        } catch (WriterException e) {
            throw new IllegalStateException("Cannot render QR code", e);
        }
        StringBuilder out = new StringBuilder();
        int size = matrix.getWidth();
        for (int y = -QUIET_ZONE; y < size + QUIET_ZONE; y += 2) {
            out.append(COLORS);
            for (int x = -QUIET_ZONE; x < size + QUIET_ZONE; x++) {
                boolean top = dark(matrix, x, y);
                boolean bottom = dark(matrix, x, y + 1);
                out.append(top && bottom ? '█' : top ? '▀' : bottom ? '▄' : ' ');
            }
            out.append(RESET).append('\n');
        }
        return out.toString();
    }

    private static boolean dark(BitMatrix matrix, int x, int y) {
        return x >= 0 && y >= 0 && x < matrix.getWidth() && y < matrix.getHeight() && matrix.get(x, y);
    }
}
```

`platform/cli/PlatformCli.java`:

```java
package com.nexusops.platform.cli;

import com.nexusops.platform.application.PlatformUserAdmin;
import com.nexusops.platform.application.PlatformUserAdmin.Enrollment;
import com.nexusops.platform.domain.PlatformRole;
import com.nexusops.platform.domain.PlatformUserStatus;
import com.nexusops.platform.totp.Totp;
import com.nexusops.shared.web.ApiProblem;
import java.time.Clock;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Platform account CLI (decision 1, ADR-0007). Active only when started with
 * {@code --nexusops.cli.command=...} (see NexusOpsApplication and the Makefile's platform-* targets).
 * Exit codes: 0 done, 1 failed (nothing saved), 2 usage.
 */
@Component
@ConditionalOnProperty(name = "nexusops.cli.command")
public class PlatformCli implements ApplicationRunner, ExitCodeGenerator {

    static final int OK = 0;
    static final int FAILED = 1;
    static final int USAGE = 2;
    private static final int CODE_ATTEMPTS = 3;

    private final PlatformUserAdmin admin;
    private final Terminal terminal;
    private final String command;
    private final String email;
    private final String role;
    private final Clock clock;
    private int exitCode = FAILED;

    @Autowired
    PlatformCli(PlatformUserAdmin admin, @Value("${nexusops.cli.command}") String command,
            @Value("${nexusops.cli.email:}") String email, @Value("${nexusops.cli.role:PLATFORM_ADMIN}") String role) {
        this(admin, new ConsoleTerminal(), command, email, role, Clock.systemUTC());
    }

    public PlatformCli(PlatformUserAdmin admin, Terminal terminal, String command, String email, String role, Clock clock) {
        this.admin = admin;
        this.terminal = terminal;
        this.command = command;
        this.email = email;
        this.role = role;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        exitCode = execute();
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    public int execute() {
        if (email == null || email.isBlank()) {
            usage();
            return USAGE;
        }
        try {
            return switch (command) {
                case "create-platform-admin" -> create();
                case "reset-platform-totp" -> resetTotp();
                case "reset-platform-password" -> resetPassword();
                case "disable-platform-user" -> {
                    admin.setStatus(email, PlatformUserStatus.DISABLED);
                    terminal.println("Disabled " + email + ". Their platform sessions end on their next request.");
                    yield OK;
                }
                case "enable-platform-user" -> {
                    admin.setStatus(email, PlatformUserStatus.ACTIVE);
                    terminal.println("Enabled " + email + ".");
                    yield OK;
                }
                default -> {
                    usage();
                    yield USAGE;
                }
            };
        } catch (ApiProblem problem) {
            terminal.println("Error: " + describe(problem));
            return FAILED;
        }
    }

    private int create() {
        PlatformRole platformRole = parseRole();
        if (platformRole == null) {
            terminal.println("Error: role must be PLATFORM_ADMIN or PLATFORM_SUPPORT.");
            return USAGE;
        }
        String password = newPassword();
        if (password == null) {
            return FAILED;
        }
        Enrollment enrollment = admin.newEnrollment(email);
        if (!enrol(enrollment)) {
            return FAILED;
        }
        admin.create(email, platformRole, password, enrollment.secret());
        terminal.println("Created " + platformRole + " " + enrollment.email()
                + ". Sign in at /platform/login with your password and a code from your app.");
        return OK;
    }

    private int resetTotp() {
        Enrollment enrollment = admin.newEnrollment(email);
        if (!enrol(enrollment)) {
            return FAILED;
        }
        admin.resetTotp(email, enrollment.secret());
        terminal.println("New authenticator enrolled for " + enrollment.email() + "; all their sessions are signed out.");
        return OK;
    }

    private int resetPassword() {
        String password = newPassword();
        if (password == null) {
            return FAILED;
        }
        admin.resetPassword(email, password);
        terminal.println("Password changed for " + email + "; all their sessions are signed out.");
        return OK;
    }

    private String newPassword() {
        char[] first = terminal.readSecret("New password (at least 12 characters): ");
        char[] second = terminal.readSecret("Repeat the password: ");
        try {
            if (first == null || second == null || !Arrays.equals(first, second)) {
                terminal.println("Error: the passwords don't match. Nothing was saved.");
                return null;
            }
            String password = new String(first);
            admin.checkPassword(password, email);
            return password;
        } finally {
            if (first != null) Arrays.fill(first, '\0');
            if (second != null) Arrays.fill(second, '\0');
        }
    }

    /** Shows the secret once and requires a correct code before anything is saved. */
    private boolean enrol(Enrollment enrollment) {
        terminal.println("Scan this QR code with your authenticator app (1Password, Google Authenticator, Authy, ...):");
        terminal.println(QrCodes.render(enrollment.otpauthUri()));
        terminal.println("Or enter this secret manually: " + grouped(enrollment.secretBase32()));
        terminal.println("otpauth URI: " + enrollment.otpauthUri());
        for (int attempt = 1; attempt <= CODE_ATTEMPTS; attempt++) {
            String code = terminal.readLine("Enter the 6-digit code your app shows: ");
            if (Totp.verify(enrollment.secret(), code, clock.instant(), 0).isPresent()) {
                return true;
            }
            terminal.println("That code doesn't match. Check the time on your phone and try again.");
        }
        terminal.println("Error: enrolment not confirmed; nothing was saved.");
        return false;
    }

    private PlatformRole parseRole() {
        try {
            return PlatformRole.valueOf(role == null ? "PLATFORM_ADMIN" : role.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void usage() {
        terminal.println("""
                Usage: --nexusops.cli.command=<command> --nexusops.cli.email=<email> [--nexusops.cli.role=<role>]
                Commands: create-platform-admin, reset-platform-totp, reset-platform-password,
                          disable-platform-user, enable-platform-user
                Roles:    PLATFORM_ADMIN (default), PLATFORM_SUPPORT""");
    }

    private static String grouped(String base32) {
        return base32.replaceAll("(.{4})(?!$)", "$1 ");
    }

    private static String describe(ApiProblem problem) {
        return problem.errors().isEmpty() ? problem.getMessage()
                : problem.errors().stream().map(ApiProblem.FieldError::message).collect(Collectors.joining(" "));
    }
}
```

Replace `NexusOpsApplication.java`:

```java
package com.nexusops;

import java.util.Arrays;
import java.util.Map;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class NexusOpsApplication {

	public static void main(String[] args) {
		SpringApplication application = new SpringApplication(NexusOpsApplication.class);
		if (isCliCommand(args)) { // platform account CLI: no web server, quiet logs, exit with the command's code
			application.setWebApplicationType(WebApplicationType.NONE);
			application.setBannerMode(Banner.Mode.OFF);
			application.setDefaultProperties(Map.of("logging.level.root", "WARN"));
			System.exit(SpringApplication.exit(application.run(args)));
		}
		application.run(args);
	}

	static boolean isCliCommand(String[] args) {
		return Arrays.stream(args).anyMatch(arg -> arg.startsWith("--nexusops.cli.command="));
	}
}
```

In identity's `SecurityConfig`, add `@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)` to the class. Without it the CLI context (web type NONE) fails with "No qualifying bean of type HttpSecurity". `PlatformCliStartupIT` proves the fix. Task 5's `PlatformSecurityConfig` carries the same annotation.

Append to the `Makefile`:

```make
PLATFORM_CLI = cd backend && ./gradlew -q bootJar && SPRING_PROFILES_ACTIVE=$${SPRING_PROFILES_ACTIVE:-local} \
	java -jar $$(ls build/libs/backend-*-SNAPSHOT.jar) --nexusops.cli.email=$(EMAIL)

.PHONY: platform-admin platform-reset-totp platform-reset-password platform-disable platform-enable
platform-admin:          ## create a platform user: make platform-admin EMAIL=you@example.com [ROLE=PLATFORM_SUPPORT]
	$(PLATFORM_CLI) --nexusops.cli.command=create-platform-admin --nexusops.cli.role=$(or $(ROLE),PLATFORM_ADMIN)
platform-reset-totp:     ## enrol a new authenticator: make platform-reset-totp EMAIL=you@example.com
	$(PLATFORM_CLI) --nexusops.cli.command=reset-platform-totp
platform-reset-password: ## set a new password: make platform-reset-password EMAIL=you@example.com
	$(PLATFORM_CLI) --nexusops.cli.command=reset-platform-password
platform-disable:        ## disable a platform user: make platform-disable EMAIL=you@example.com
	$(PLATFORM_CLI) --nexusops.cli.command=disable-platform-user
platform-enable:         ## re-enable a platform user: make platform-enable EMAIL=you@example.com
	$(PLATFORM_CLI) --nexusops.cli.command=enable-platform-user
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `cd backend && ./gradlew test --tests 'com.nexusops.platform.*' --tests 'com.nexusops.NexusOpsApplicationTest' --tests 'com.nexusops.ModularityTest'`
Expected: PASS. `PlatformCliStartupIT` returns 2 (usage), which proves the CLI context boots without a web server.

- [ ] **Step 7: Smoke-test the real CLI**

Start Postgres with `make up`. Then run `make platform-admin EMAIL=smoke@nexusops.test` in an interactive terminal:
- type a 12+ character password twice;
- check that a QR code renders;
- scan it with any authenticator, or add the printed secret to `oathtool --totp -b <secret>` if installed, and enter the code.

Expected: "Created PLATFORM_ADMIN smoke@nexusops.test…" and exit status 0. Then `make platform-disable EMAIL=smoke@nexusops.test` prints "Disabled …". Record the outcome in the report. If no interactive terminal is available to the implementer, say so in the report rather than skipping silently.

- [ ] **Step 8: Run the whole backend suite**

Run: `cd backend && ./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add Makefile backend/build.gradle.kts backend/src
git commit -m "feat: platform users with CLI enrolment (terminal QR, confirmation code), password and TOTP resets"
```

---

### Task 5: Platform tokens, a dedicated security chain and principal filter; tokens can't cross between tenant and platform APIs

**Files:**
- Create: `backend/src/main/java/com/nexusops/platform/security/PlatformTokenService.java`
- Create: `backend/src/main/java/com/nexusops/platform/security/PlatformJwt.java`
- Create: `backend/src/main/java/com/nexusops/platform/security/PlatformBearerTokenResolver.java`
- Create: `backend/src/main/java/com/nexusops/platform/security/PlatformPrincipalFilter.java`
- Create: `backend/src/main/java/com/nexusops/platform/security/PlatformActor.java`
- Create: `backend/src/main/java/com/nexusops/platform/security/CurrentPlatformActor.java`
- Create: `backend/src/main/java/com/nexusops/platform/security/PlatformSecurityConfig.java`
- Create: `backend/src/main/java/com/nexusops/platform/web/PlatformMeController.java`
- Modify: `backend/src/main/java/com/nexusops/identity/security/SecurityConfig.java` (explicit `@Order(2)`)
- Test: `backend/src/test/java/com/nexusops/platform/PlatformSecurityIT.java`

**Interfaces:**
- Consumes:
  - `PlatformAccess` and `PlatformUserRepository` (Tasks 2 and 4).
  - The `RSAKey` bean `jwtRsaKey` (from identity's `JwtKeyConfig`, injected by type).
  - Property `nexusops.security.jwt.issuer`.
  - `PlatformProperties.audience()` and `accessTokenTtl()`.
  - `ProblemDetailSecurityHandlers` and `PublicEndpoints` (shared).
- Produces:
  - `PlatformTokenService.issue(UUID platformUserId, int tokenVersion)` → `IssuedToken(String value, Instant expiresAt)`, plus `public static final String CLAIM_VERSION = "pv"`.
  - `PlatformActor(UUID id, String email, PlatformRole role)` and `CurrentPlatformActor.require()`.
  - `GET /api/v1/platform/me` → `{id, email, role, permissions[]}`.
  - The constant `PlatformPrincipalFilter.STALE`.

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/com/nexusops/platform/PlatformSecurityIT.java`:

```java
package com.nexusops.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.platform.application.PlatformUserAdmin;
import com.nexusops.platform.domain.PlatformRole;
import com.nexusops.platform.domain.PlatformUserStatus;
import com.nexusops.platform.security.PlatformTokenService;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestPlatformUsers;
import com.nexusops.support.TestPlatformUsers.Operator;
import com.nexusops.support.TestTenants;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@AutoConfigureMockMvc
class PlatformSecurityIT extends IntegrationTestSupport {

    static final String STALE = "Your session is no longer valid. Please sign in again.";

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired PlatformUserAdmin admin;
    @Autowired PlatformTokenService platformTokens;
    @Autowired PlatformProperties properties;
    @Autowired RSAKey jwtRsaKey;

    Operator support;

    @BeforeEach
    void operator() {
        support = TestPlatformUsers.create(admin, PlatformRole.PLATFORM_SUPPORT);
    }

    private ResultActions platformMe(String token) throws Exception {
        return mvc.perform(get("/api/v1/platform/me").header("Authorization", "Bearer " + token));
    }

    @Test
    void aPlatformTokenReachesThePlatformApi() throws Exception {
        platformMe(platformTokens.issue(support.id(), 0).value()).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(support.email()))
                .andExpect(jsonPath("$.role").value("PLATFORM_SUPPORT"))
                .andExpect(jsonPath("$.permissions").value(org.hamcrest.Matchers.contains("platform.tenant.read")));
        mvc.perform(get("/api/v1/platform/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void tokensDoNotCrossBetweenTenantAndPlatformApis() throws Exception {
        var workspace = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("plat-x"));
        String tenantToken = TestTenants.login(mvc, workspace).accessToken();
        String platformToken = platformTokens.issue(support.id(), 0).value();

        platformMe(tenantToken).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + platformToken))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/users").header("Authorization", "Bearer " + platformToken))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + tenantToken)).andExpect(status().isOk());
    }

    @Test
    void platformTokenWithATenantClaimIsRejected() throws Exception {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("nexusops")
                .audience(List.of(properties.audience()))
                .subject(support.id().toString())
                .issuedAt(now)
                .expiresAt(now.plus(5, ChronoUnit.MINUTES))
                .claim(PlatformTokenService.CLAIM_VERSION, 0)
                .claim("tid", UUID.randomUUID().toString())
                .build();
        String token = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwtRsaKey)))
                .encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
                .getTokenValue();
        platformMe(token).andExpect(status().isUnauthorized());
    }

    @Test
    void aTokenSignedWithAForeignKeyIsRejected() throws Exception {
        RSAKey foreign = new RSAKeyGenerator(2048).generate();
        var forger = new PlatformTokenService(new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(foreign))),
                properties, "nexusops", Clock.systemUTC());
        platformMe(forger.issue(support.id(), 0).value()).andExpect(status().isUnauthorized());
    }

    @Test
    void staleDisabledOrUnknownPrincipalsAreRejected() throws Exception {
        String token = platformTokens.issue(support.id(), 0).value();
        admin.resetPassword(support.email(), "a brand new platform passphrase");
        platformMe(token).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.detail").value(STALE));
        platformMe(platformTokens.issue(support.id(), 1).value()).andExpect(status().isOk());

        Operator other = TestPlatformUsers.create(admin, PlatformRole.PLATFORM_ADMIN);
        admin.setStatus(other.email(), PlatformUserStatus.DISABLED);
        platformMe(platformTokens.issue(other.id(), 1).value()).andExpect(status().isUnauthorized());
        platformMe(platformTokens.issue(UUID.randomUUID(), 0).value()).andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && ./gradlew test --tests 'com.nexusops.platform.PlatformSecurityIT'`
Expected: compilation FAILS: `package com.nexusops.platform.security does not exist`.

- [ ] **Step 3: Write the token service and decoder**

`platform/security/PlatformTokenService.java`:

```java
package com.nexusops.platform.security;

import com.nexusops.platform.PlatformProperties;
import com.nexusops.shared.Ids;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

/**
 * Platform access tokens (ADR-0007): RS256, aud=nexusops-platform, {@code pv} = the platform user's token version,
 * no {@code tid}. The encoder is deliberately not a bean, so identity's JwtEncoder stays the only one.
 */
@Service
public class PlatformTokenService {

    public static final String CLAIM_VERSION = "pv";

    public record IssuedToken(String value, Instant expiresAt) {}

    private final JwtEncoder encoder;
    private final PlatformProperties properties;
    private final String issuer;
    private final Clock clock;

    @Autowired
    PlatformTokenService(RSAKey jwtRsaKey, PlatformProperties properties,
            @Value("${nexusops.security.jwt.issuer}") String issuer) {
        this(new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwtRsaKey))), properties, issuer, Clock.systemUTC());
    }

    public PlatformTokenService(JwtEncoder encoder, PlatformProperties properties, String issuer, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.issuer = issuer;
        this.clock = clock;
    }

    public IssuedToken issue(UUID platformUserId, int tokenVersion) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .audience(List.of(properties.audience()))
                .subject(platformUserId.toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .id(Ids.newId().toString())
                .claim(CLAIM_VERSION, tokenVersion)
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return new IssuedToken(encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue(), expiresAt);
    }
}
```

`platform/security/PlatformJwt.java`:

```java
package com.nexusops.platform.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import java.util.List;
import java.util.Objects;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** The platform chain's decoder: RS256 only, platform audience, a {@code pv} claim and NO tenant claim. */
final class PlatformJwt {

    private PlatformJwt() {}

    static JwtDecoder decoder(RSAKey key, String issuer, String audience) {
        NimbusJwtDecoder decoder;
        try {
            decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).signatureAlgorithm(SignatureAlgorithm.RS256).build();
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot build platform JWT decoder", e);
        }
        OAuth2TokenValidator<Jwt> noTenant = jwt -> jwt.hasClaim("tid")
                ? OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Not a platform token", null))
                : OAuth2TokenValidatorResult.success();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new JwtClaimValidator<Object>("exp", Objects::nonNull),
                new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains(audience)),
                new JwtClaimValidator<Object>(PlatformTokenService.CLAIM_VERSION, pv -> pv instanceof Number),
                new JwtClaimValidator<Object>("sub", sub -> sub instanceof String s && !s.isBlank()),
                noTenant));
        return decoder;
    }
}
```

- [ ] **Step 4: Write the principal, filter, resolver and chain**

`platform/security/PlatformActor.java`:

```java
package com.nexusops.platform.security;

import com.nexusops.platform.domain.PlatformRole;
import java.util.UUID;

/** The authenticated platform operator, set as the authentication's details by PlatformPrincipalFilter. */
public record PlatformActor(UUID id, String email, PlatformRole role) {}
```

`platform/security/CurrentPlatformActor.java`:

```java
package com.nexusops.platform.security;

import org.springframework.security.core.context.SecurityContextHolder;

public final class CurrentPlatformActor {

    private CurrentPlatformActor() {}

    public static PlatformActor require() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getDetails() instanceof PlatformActor actor) {
            return actor;
        }
        throw new IllegalStateException("No authenticated platform operator");
    }
}
```

`platform/security/PlatformBearerTokenResolver.java`:

```java
package com.nexusops.platform.security;

import com.nexusops.shared.web.PublicEndpoints;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.util.matcher.RequestMatcher;

/** A stale Authorization header must not break the public platform auth routes (login/refresh/logout). */
final class PlatformBearerTokenResolver implements BearerTokenResolver {

    private final BearerTokenResolver delegate = new DefaultBearerTokenResolver();
    private final RequestMatcher[] publicRoutes = PublicEndpoints.matchers();

    @Override
    public String resolve(HttpServletRequest request) {
        return Arrays.stream(publicRoutes).anyMatch(m -> m.matches(request)) ? null : delegate.resolve(request);
    }
}
```

`platform/security/PlatformPrincipalFilter.java`:

```java
package com.nexusops.platform.security;

import com.nexusops.platform.domain.PlatformUser;
import com.nexusops.platform.domain.PlatformUserRepository;
import com.nexusops.platform.domain.PlatformUserStatus;
import com.nexusops.platform.internal.PlatformAccess;
import com.nexusops.shared.security.ProblemDetailSecurityHandlers;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Runs after platform JWT verification: loads the operator's live row (inside PlatformAccess) and rejects
 * unknown, disabled or stale-version principals. Authorities come from the stored role, never from the token.
 * No TenantContext is ever bound on platform requests.
 */
@Component
public class PlatformPrincipalFilter extends OncePerRequestFilter {

    public static final String STALE = "Your session is no longer valid. Please sign in again.";

    private final PlatformAccess access;
    private final PlatformUserRepository users;
    private final ProblemDetailSecurityHandlers problems;

    PlatformPrincipalFilter(PlatformAccess access, PlatformUserRepository users, ProblemDetailSecurityHandlers problems) {
        this.access = access;
        this.users = users;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token)) {
            chain.doFilter(request, response);
            return;
        }
        Jwt jwt = token.getToken();
        UUID id;
        int version;
        try {
            id = UUID.fromString(jwt.getSubject());
            version = ((Number) jwt.getClaim(PlatformTokenService.CLAIM_VERSION)).intValue();
        } catch (RuntimeException malformed) {
            reject(response);
            return;
        }
        PlatformUser user = access.read(() -> users.findById(id).orElse(null));
        if (user == null || user.getStatus() != PlatformUserStatus.ACTIVE || user.getTokenVersion() != version) {
            reject(response);
            return;
        }
        var authorities = user.getRole().authorities().stream().map(SimpleGrantedAuthority::new).toList();
        var authenticated = new JwtAuthenticationToken(jwt, authorities, id.toString());
        authenticated.setDetails(new PlatformActor(id, user.getEmail(), user.getRole()));
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authenticated);
        SecurityContextHolder.setContext(context);
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response) throws IOException {
        SecurityContextHolder.clearContext();
        problems.write(response, HttpStatus.UNAUTHORIZED, "Unauthorized", STALE);
    }
}
```

`platform/security/PlatformSecurityConfig.java`:

```java
package com.nexusops.platform.security;

import com.nexusops.platform.PlatformProperties;
import com.nexusops.shared.security.ProblemDetailSecurityHandlers;
import com.nexusops.shared.web.PublicEndpoints;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The platform security chain (ADR-0007): ordered before the tenant chain and matching only /api/v1/platform/**.
 * Its JWT decoder accepts only platform-audience tokens; the tenant chain's decoder accepts only tenant tokens.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class PlatformSecurityConfig {

    static final String PLATFORM_PATHS = "/api/v1/platform/**";

    @Bean
    @Order(1)
    SecurityFilterChain platformSecurity(HttpSecurity http, ProblemDetailSecurityHandlers handlers,
            PlatformPrincipalFilter principalFilter, RSAKey jwtRsaKey, PlatformProperties properties,
            @Value("${nexusops.security.jwt.issuer}") String issuer) throws Exception {
        http.securityMatcher(PLATFORM_PATHS)
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PublicEndpoints.matchers()).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .bearerTokenResolver(new PlatformBearerTokenResolver())
                        .authenticationEntryPoint(handlers)
                        .accessDeniedHandler(handlers)
                        .jwt(jwt -> jwt.decoder(PlatformJwt.decoder(jwtRsaKey, issuer, properties.audience()))))
                .exceptionHandling(e -> e.authenticationEntryPoint(handlers).accessDeniedHandler(handlers))
                .addFilterAfter(principalFilter, BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    /** PlatformPrincipalFilter belongs to the platform security chain only, not the servlet filter chain. */
    @Bean
    FilterRegistrationBean<PlatformPrincipalFilter> platformPrincipalFilterServletRegistration(PlatformPrincipalFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
```

In identity's `SecurityConfig`, annotate the `apiSecurity` bean method with `@org.springframework.core.annotation.Order(2)`. It matches any request, so it must come after the platform chain. Without an explicit order Spring falls back to bean ordering, which is fragile.

`platform/web/PlatformMeController.java`:

```java
package com.nexusops.platform.web;

import com.nexusops.platform.security.CurrentPlatformActor;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/platform")
class PlatformMeController {

    record PlatformMeResponse(UUID id, String email, String role, List<String> permissions) {}

    @GetMapping("/me")
    @PreAuthorize("hasAuthority('platform.tenant.read')")
    PlatformMeResponse me() {
        var actor = CurrentPlatformActor.require();
        return new PlatformMeResponse(actor.id(), actor.email(), actor.role().name(),
                actor.role().authorities().stream().sorted().toList());
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd backend && ./gradlew test --tests 'com.nexusops.platform.PlatformSecurityIT' --tests 'com.nexusops.TokenMisuseIT' --tests 'com.nexusops.EndpointAuthorizationCoverageTest' --tests 'com.nexusops.ModularityTest'`
Expected: PASS.

If startup fails with "No qualifying bean of type JwtDecoder" or a duplicate-bean error, something registered the platform decoder or encoder as a bean. Neither may be a bean.

- [ ] **Step 6: Run the whole backend suite**

Run: `cd backend && ./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add backend/src
git commit -m "feat: platform security chain with its own token audience and live principal checks"
```

---

### Task 6: Platform login, refresh and logout — password + single-use TOTP, 8-hour sessions, rate limits

**Files:**
- Create: `backend/src/main/java/com/nexusops/platform/domain/PlatformRevokeReason.java`
- Create: `backend/src/main/java/com/nexusops/platform/domain/PlatformRefreshToken.java`
- Create: `backend/src/main/java/com/nexusops/platform/domain/PlatformRefreshTokenRepository.java`
- Create: `backend/src/main/java/com/nexusops/platform/security/PlatformSessionTokens.java`
- Create: `backend/src/main/java/com/nexusops/platform/application/PlatformAuthService.java`
- Create: `backend/src/main/java/com/nexusops/platform/web/PlatformCookies.java`
- Create: `backend/src/main/java/com/nexusops/platform/web/PlatformAuthController.java`
- Modify: `backend/src/main/java/com/nexusops/shared/web/PublicEndpoints.java`
- Modify: `backend/src/main/java/com/nexusops/shared/ratelimit/RateLimits.java`, `RateLimitKeys.java`
- Modify: `backend/src/main/resources/application.yml`, `application-test.yml`
- Test: `backend/src/test/java/com/nexusops/platform/PlatformAuthIT.java`, `backend/src/test/java/com/nexusops/platform/PlatformLoginRateLimitIT.java`

**Interfaces:**
- Consumes:
  - `PlatformTokenService.issue` (Task 5).
  - `PlatformUserRepository.findByEmail`/`findForUpdateById` and `PlatformUser.recordTotpUse`/`recordLogin` (Task 4).
  - `Totp.verify` and `TotpSecretCipher.decrypt` (Task 3).
  - `PlatformAccess` (Task 2).
  - `OriginGuard` and `Emails` (Task 1).
  - `RateLimits.checkPublic("refresh", ip)`.
- Produces:
  - Routes `POST /api/v1/platform/auth/login` (`{email, password, code}` → `{accessToken, tokenType, expiresIn}` + cookie `nexus_prt`), `POST /api/v1/platform/auth/refresh` and `POST /api/v1/platform/auth/logout`.
  - `RateLimits.checkPlatformLogin(String clientIp, String canonicalEmail)` and `RateLimitKeys.platformAccount(String canonicalEmail)`.
  - `PlatformSessionTokens.hash(String)` (public, used by tests).

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/com/nexusops/platform/PlatformAuthIT.java`:

```java
package com.nexusops.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.platform.application.PlatformUserAdmin;
import com.nexusops.platform.domain.PlatformRole;
import com.nexusops.platform.domain.PlatformUserStatus;
import com.nexusops.platform.security.PlatformSessionTokens;
import com.nexusops.platform.totp.Totp;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.TestPlatformUsers;
import com.nexusops.support.TestPlatformUsers.Operator;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

@AutoConfigureMockMvc
class PlatformAuthIT extends IntegrationTestSupport {

    static final String INVALID = "Invalid email, password or code.";
    static final Pattern ACCESS = Pattern.compile("\"accessToken\":\"([^\"]+)\"");
    static final Pattern COOKIE = Pattern.compile("nexus_prt=([^;]*)");

    @Autowired MockMvc mvc;
    @Autowired PlatformUserAdmin admin;

    Operator op;

    @BeforeEach
    void operator() {
        op = TestPlatformUsers.create(admin, PlatformRole.PLATFORM_ADMIN);
    }

    private ResultActions login(String email, String password, String code) throws Exception {
        return mvc.perform(post("/api/v1/platform/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"%s","password":"%s","code":"%s"}""".formatted(email, password, code)));
    }

    private ResultActions refresh(String cookie) throws Exception {
        return mvc.perform(post("/api/v1/platform/auth/refresh").cookie(new Cookie("nexus_prt", cookie)));
    }

    private static String cookieOf(MvcResult result) {
        for (String header : result.getResponse().getHeaders("Set-Cookie")) {
            var m = COOKIE.matcher(header);
            if (m.find()) return m.group(1);
        }
        throw new AssertionError("no nexus_prt cookie");
    }

    private static String accessOf(MvcResult result) throws Exception {
        var m = ACCESS.matcher(result.getResponse().getContentAsString());
        if (!m.find()) throw new AssertionError("no accessToken");
        return m.group(1);
    }

    private Timestamp expiresAt(String cookie) {
        return TestPlatformUsers.platformJdbc().queryForObject(
                "select expires_at from platform_refresh_tokens where token_hash = ?", Timestamp.class,
                PlatformSessionTokens.hash(cookie));
    }

    private long nextStep() {
        return Totp.step(Instant.now()) + 1;
    }

    @Test
    void signsInWithPasswordAndCodeAndSetsAScopedCookie() throws Exception {
        MvcResult result = login(op.email().toUpperCase(), op.password(), op.currentCode())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andReturn();
        String setCookie = String.join("\n", result.getResponse().getHeaders("Set-Cookie"));
        assertThat(setCookie).contains("nexus_prt=").contains("Path=/api/v1/platform/auth").contains("HttpOnly")
                .contains("Secure").contains("SameSite=Strict").contains("Max-Age=");
        mvc.perform(get("/api/v1/platform/me").header("Authorization", "Bearer " + accessOf(result)))
                .andExpect(status().isOk());
        assertThat(OwnerJdbc.superuser().queryForObject("""
                select count(*) from audit_events where action = 'PlatformLoginSucceeded' and tenant_id is null
                  and actor_type = 'PLATFORM' and actor_id = ?""", Long.class, op.id())).isOne();
    }

    @Test
    void everyFailureLooksTheSame() throws Exception {
        long far = Totp.step(Instant.now()) + 5;
        login(op.email(), "wrong platform password!", op.currentCode())
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.detail").value(INVALID));
        login(op.email(), op.password(), op.codeAt(far))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.detail").value(INVALID));
        login("nobody@nexusops.test", op.password(), op.currentCode())
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.detail").value(INVALID));
        Operator disabled = TestPlatformUsers.create(admin, PlatformRole.PLATFORM_SUPPORT);
        admin.setStatus(disabled.email(), PlatformUserStatus.DISABLED);
        login(disabled.email(), disabled.password(), disabled.currentCode())
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.detail").value(INVALID));

        List<String> reasons = OwnerJdbc.superuser().queryForList("""
                select metadata->>'reason' from audit_events where action = 'PlatformLoginFailed'
                  and (entity_id = ? or entity_id = ? or entity_id is null)""", String.class,
                op.id().toString(), disabled.id().toString());
        assertThat(reasons).contains("BAD_PASSWORD", "BAD_CODE", "UNKNOWN_USER", "DISABLED");
    }

    @Test
    void aTotpCodeWorksOnlyOnce() throws Exception {
        String code = op.currentCode();
        login(op.email(), op.password(), code).andExpect(status().isOk());
        login(op.email(), op.password(), code)
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.detail").value(INVALID));
    }

    @Test
    void refreshRotatesAndDetectsReuseAfterTheGraceWindow() throws Exception {
        String first = cookieOf(login(op.email(), op.password(), op.currentCode()).andExpect(status().isOk()).andReturn());
        String second = cookieOf(refresh(first).andExpect(status().isOk()).andReturn());
        assertThat(second).isNotEqualTo(first);
        refresh(first).andExpect(status().isUnauthorized()); // inside the 10 s grace window: family kept
        String third = cookieOf(refresh(second).andExpect(status().isOk()).andReturn());

        TestPlatformUsers.platformJdbc().update(
                "update platform_refresh_tokens set revoked_at = revoked_at - interval '11 seconds' where token_hash = ?",
                PlatformSessionTokens.hash(first));
        refresh(first).andExpect(status().isUnauthorized());
        refresh(third).andExpect(status().isUnauthorized()); // the whole family was revoked
        assertThat(OwnerJdbc.superuser().queryForObject(
                "select count(*) from audit_events where action = 'PlatformRefreshTokenReuseDetected' and actor_id = ?",
                Long.class, op.id())).isOne();
    }

    @Test
    void rotationNeverExtendsTheEightHourCap() throws Exception {
        String first = cookieOf(login(op.email(), op.password(), op.currentCode()).andExpect(status().isOk()).andReturn());
        Instant cap = expiresAt(first).toInstant();
        assertThat(Duration.between(Instant.now(), cap)).isBetween(Duration.ofHours(8).minusMinutes(1), Duration.ofHours(8));
        String second = cookieOf(refresh(first).andExpect(status().isOk()).andReturn());
        assertThat(expiresAt(second).toInstant()).isEqualTo(cap);

        TestPlatformUsers.platformJdbc().update(
                "update platform_refresh_tokens set expires_at = now() - interval '1 second' where platform_user_id = ?",
                op.id());
        refresh(second).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Your session has expired. Please sign in again."));
    }

    @Test
    void logoutEndsTheFamilyAndClearsTheCookie() throws Exception {
        String cookie = cookieOf(login(op.email(), op.password(), op.currentCode()).andExpect(status().isOk()).andReturn());
        MvcResult out = mvc.perform(post("/api/v1/platform/auth/logout").cookie(new Cookie("nexus_prt", cookie)))
                .andExpect(status().isNoContent()).andReturn();
        assertThat(String.join("\n", out.getResponse().getHeaders("Set-Cookie"))).contains("nexus_prt=").contains("Max-Age=0");
        refresh(cookie).andExpect(status().isUnauthorized());
        assertThat(OwnerJdbc.superuser().queryForObject(
                "select count(*) from audit_events where action = 'PlatformLogout' and actor_id = ?", Long.class, op.id()))
                .isOne();
    }

    @Test
    void credentialChangesEndExistingSessions() throws Exception {
        MvcResult result = login(op.email(), op.password(), op.currentCode()).andExpect(status().isOk()).andReturn();
        admin.resetPassword(op.email(), "a different platform passphrase");
        refresh(cookieOf(result)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/platform/me").header("Authorization", "Bearer " + accessOf(result)))
                .andExpect(status().isUnauthorized());
        login(op.email(), "a different platform passphrase", op.codeAt(nextStep())).andExpect(status().isOk());
    }

    @Test
    void refreshAndLogoutRejectAForeignOrigin() throws Exception {
        String cookie = cookieOf(login(op.email(), op.password(), op.currentCode()).andExpect(status().isOk()).andReturn());
        mvc.perform(post("/api/v1/platform/auth/refresh").cookie(new Cookie("nexus_prt", cookie))
                .header("Origin", "https://evil.example")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/platform/auth/logout").cookie(new Cookie("nexus_prt", cookie))
                .header("Origin", "https://evil.example")).andExpect(status().isForbidden());
        refresh("garbage").andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/platform/auth/refresh")).andExpect(status().isUnauthorized());
    }
}
```

`backend/src/test/java/com/nexusops/platform/PlatformLoginRateLimitIT.java`:

```java
package com.nexusops.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.IntegrationTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "nexusops.rate-limits.rules.platform-login.capacity=4",
        "nexusops.rate-limits.rules.platform-login-account.capacity=3"
})
class PlatformLoginRateLimitIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;

    private static String uniqueIp() {
        return "10.%d.%d.%d".formatted((int) (Math.random() * 250), (int) (Math.random() * 250), (int) (Math.random() * 250));
    }

    private ResultActions attempt(String email, String ip) throws Exception {
        return mvc.perform(post("/api/v1/platform/auth/login").with(r -> { r.setRemoteAddr(ip); return r; })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"guess guess guess\",\"code\":\"123456\"}".formatted(email)));
    }

    @Test
    void theAccountBucketHoldsAcrossIpsAndCaseOrSpacingVariants() throws Exception {
        String email = "target-" + UUID.randomUUID().toString().substring(0, 8) + "@nexusops.test";
        attempt(email, uniqueIp()).andExpect(status().isUnauthorized());
        attempt(email.toUpperCase(), uniqueIp()).andExpect(status().isUnauthorized());
        attempt("  " + email + " ", uniqueIp()).andExpect(status().isUnauthorized());
        attempt(email, uniqueIp()).andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        attempt("other-" + email, uniqueIp()).andExpect(status().isUnauthorized());
    }

    @Test
    void theIpBucketHoldsAcrossAccounts() throws Exception {
        String ip = uniqueIp();
        for (int i = 0; i < 4; i++) {
            attempt("spray-" + i + "-" + UUID.randomUUID().toString().substring(0, 6) + "@nexusops.test", ip)
                    .andExpect(status().isUnauthorized());
        }
        attempt("spray-last@nexusops.test", ip).andExpect(status().isTooManyRequests());
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && ./gradlew test --tests 'com.nexusops.platform.PlatformAuthIT' --tests 'com.nexusops.platform.PlatformLoginRateLimitIT'`
Expected: compilation FAILS: `cannot find symbol: class PlatformSessionTokens`.

- [ ] **Step 3: Add the rate-limit rules and keys**

In `RateLimitKeys`, add after `account(...)`:

```java
    /** Per-account platform login bucket, keyed by the canonical email (the platform has no workspace). */
    public static String platformAccount(String canonicalEmail) {
        return "rl:pacct:" + sha256(normalize(canonicalEmail)) + ":platform-login";
    }
```

In `RateLimits`, add after `recordLoginFailure(...)`:

```java
    /**
     * Platform login, before authenticating: per IP, then per account. Both count every attempt and fail closed.
     * {@code canonicalEmail} null means the input can't be a platform account, so only the IP bucket is charged.
     */
    public void checkPlatformLogin(String clientIp, String canonicalEmail) {
        enforce(RateLimitKeys.ip("platform-login", clientIp), rule("platform-login"));
        if (canonicalEmail != null) {
            enforce(RateLimitKeys.platformAccount(canonicalEmail), rule("platform-login-account"));
        }
    }
```

In `application.yml` under `nexusops.rate-limits.rules`, add:

```yaml
      platform-login: { capacity: 10, window: 1m }
      platform-login-account: { capacity: 5, window: 1m }
```

In `application-test.yml` under `nexusops.rate-limits.rules`, add:

```yaml
      platform-login: { capacity: 100000, window: 1m }
      platform-login-account: { capacity: 100000, window: 1m }
```

In `PublicEndpoints.ROUTES`, add:

```java
            "POST /api/v1/platform/auth/login",
            "POST /api/v1/platform/auth/refresh",
            "POST /api/v1/platform/auth/logout",
```

- [ ] **Step 4: Write the refresh-token domain and opaque tokens**

`platform/domain/PlatformRevokeReason.java`:

```java
package com.nexusops.platform.domain;

public enum PlatformRevokeReason { ROTATED, LOGOUT, REVOKED, REUSE_DETECTED }
```

`platform/domain/PlatformRefreshToken.java`:

```java
package com.nexusops.platform.domain;

import com.nexusops.shared.db.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A platform refresh token. {@code expiresAt} is the family's absolute cap (login + 8 h): rotation copies it,
 * never extends it (decision 2, ADR-0007).
 */
@Entity
@Table(name = "platform_refresh_tokens")
public class PlatformRefreshToken extends BaseEntity {

    @Column(name = "platform_user_id", nullable = false)
    private UUID platformUserId;

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
    private PlatformRevokeReason revokeReason;

    @Column(name = "replaced_by")
    private UUID replacedBy;

    @Column(name = "token_version", nullable = false)
    private int tokenVersion;

    @Column(name = "created_ip")
    private String createdIp;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PlatformRefreshToken() {}

    public static PlatformRefreshToken issue(UUID id, UUID platformUserId, UUID familyId, String tokenHash,
            Instant expiresAt, int tokenVersion, String ip, String userAgent) {
        PlatformRefreshToken token = new PlatformRefreshToken();
        token.initId(id);
        token.platformUserId = platformUserId;
        token.familyId = familyId;
        token.tokenHash = tokenHash;
        token.expiresAt = expiresAt;
        token.tokenVersion = tokenVersion;
        token.createdIp = ip;
        token.userAgent = userAgent == null || userAgent.length() <= 512 ? userAgent : userAgent.substring(0, 512);
        token.createdAt = Instant.now();
        return token;
    }

    public boolean isActive(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    public boolean rotatedWithin(Duration grace, Instant now) {
        return revokeReason == PlatformRevokeReason.ROTATED && revokedAt != null && revokedAt.plus(grace).isAfter(now);
    }

    public void markRotated(UUID replacement, Instant now) {
        revokedAt = now;
        revokeReason = PlatformRevokeReason.ROTATED;
        replacedBy = replacement;
    }

    public UUID getPlatformUserId() { return platformUserId; }
    public UUID getFamilyId() { return familyId; }
    public Instant getExpiresAt() { return expiresAt; }
    public int getTokenVersion() { return tokenVersion; }
    public PlatformRevokeReason getRevokeReason() { return revokeReason; }
}
```

`platform/domain/PlatformRefreshTokenRepository.java`:

```java
package com.nexusops.platform.domain;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Use ONLY inside PlatformAccess. */
public interface PlatformRefreshTokenRepository extends JpaRepository<PlatformRefreshToken, UUID> {

    Optional<PlatformRefreshToken> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from PlatformRefreshToken t where t.tokenHash = :hash")
    Optional<PlatformRefreshToken> findForUpdateByTokenHash(@Param("hash") String hash);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update PlatformRefreshToken t set t.revokedAt = :now, t.revokeReason = :reason "
            + "where t.familyId = :familyId and t.revokedAt is null")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("reason") PlatformRevokeReason reason,
            @Param("now") Instant now);
}
```

`platform/security/PlatformSessionTokens.java`:

```java
package com.nexusops.platform.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Opaque platform refresh tokens: 256 random bits, base64url; only the SHA-256 is stored. */
public final class PlatformSessionTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern SHAPE = Pattern.compile("[A-Za-z0-9_-]{43}");

    private PlatformSessionTokens() {}

    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static boolean isWellFormed(String token) {
        return token != null && SHAPE.matcher(token).matches();
    }

    public static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

- [ ] **Step 5: Write `PlatformAuthService`**

`platform/application/PlatformAuthService.java`:

```java
package com.nexusops.platform.application;

import com.nexusops.audit.ActorType;
import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.platform.PlatformProperties;
import com.nexusops.platform.domain.PlatformRefreshToken;
import com.nexusops.platform.domain.PlatformRefreshTokenRepository;
import com.nexusops.platform.domain.PlatformRevokeReason;
import com.nexusops.platform.domain.PlatformUser;
import com.nexusops.platform.domain.PlatformUserRepository;
import com.nexusops.platform.domain.PlatformUserStatus;
import com.nexusops.platform.internal.PlatformAccess;
import com.nexusops.platform.security.PlatformSessionTokens;
import com.nexusops.platform.security.PlatformTokenService;
import com.nexusops.platform.totp.Totp;
import com.nexusops.platform.totp.TotpSecretCipher;
import com.nexusops.shared.Emails;
import com.nexusops.shared.Ids;
import com.nexusops.shared.web.ApiProblem;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Platform sign-in (spec §6, ADR-0007): password + single-use TOTP, uniform failures with dummy-hash timing,
 * rotating refresh tokens with reuse detection inside a fixed 8-hour family lifetime.
 */
@Service
public class PlatformAuthService {

    static final String INVALID = "Invalid email, password or code.";
    static final String EXPIRED = "Your session has expired. Please sign in again.";
    public static final Duration REUSE_GRACE = Duration.ofSeconds(10);

    public record Client(String ip, String userAgent) {}

    public record PlatformSession(String accessToken, Instant accessTokenExpiresAt, String refreshToken,
            Instant refreshTokenExpiresAt) {}

    private final PlatformAccess access;
    private final PlatformUserRepository users;
    private final PlatformRefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final TotpSecretCipher cipher;
    private final PlatformTokenService tokens;
    private final PlatformProperties properties;
    private final AuditService audit;
    private final String dummyHash;

    PlatformAuthService(PlatformAccess access, PlatformUserRepository users, PlatformRefreshTokenRepository refreshTokens,
            PasswordEncoder passwordEncoder, TotpSecretCipher cipher, PlatformTokenService tokens,
            PlatformProperties properties, AuditService audit) {
        this.access = access;
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.cipher = cipher;
        this.tokens = tokens;
        this.properties = properties;
        this.audit = audit;
        this.dummyHash = passwordEncoder.encode("dummy platform password used to equalize timing");
    }

    public PlatformSession login(String rawEmail, String password, String code, Client client) {
        String email = Emails.tryNormalize(rawEmail).orElse(null);
        PlatformUser user = email == null ? null : access.read(() -> users.findByEmail(email).orElse(null));
        if (user == null) {
            passwordEncoder.matches(password, dummyHash);
            throw fail("UNKNOWN_USER", null);
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw fail("BAD_PASSWORD", user.getId());
        }
        if (user.getStatus() != PlatformUserStatus.ACTIVE) {
            throw fail("DISABLED", user.getId());
        }
        UUID id = user.getId();
        PlatformSession session = access.write(() -> {
            PlatformUser locked = users.findForUpdateById(id).orElse(null);
            if (locked == null || locked.getStatus() != PlatformUserStatus.ACTIVE) {
                return null;
            }
            Instant now = Instant.now();
            OptionalLong step = Totp.verify(cipher.decrypt(locked.getTotpSecretEnc(), id), code, now,
                    locked.getTotpLastStep());
            if (step.isEmpty()) {
                return null;
            }
            locked.recordTotpUse(step.getAsLong());
            locked.recordLogin(now);
            String refreshToken = PlatformSessionTokens.generate();
            Instant cap = now.plus(properties.sessionMaxAge());
            refreshTokens.save(PlatformRefreshToken.issue(Ids.newId(), id, Ids.newId(),
                    PlatformSessionTokens.hash(refreshToken), cap, locked.getTokenVersion(), client.ip(), client.userAgent()));
            audit.record(AuditEntry.of("PlatformLoginSucceeded", "PlatformUser", id).asPlatformActor(id));
            var accessToken = tokens.issue(id, locked.getTokenVersion());
            return new PlatformSession(accessToken.value(), accessToken.expiresAt(), refreshToken, cap);
        });
        if (session == null) {
            throw fail("BAD_CODE", id);
        }
        return session;
    }

    public PlatformSession refresh(String token, Client client) {
        if (!PlatformSessionTokens.isWellFormed(token)) {
            throw ApiProblem.unauthorized(EXPIRED);
        }
        String hash = PlatformSessionTokens.hash(token);
        PlatformSession session = access.write(() -> rotate(hash, client));
        if (session == null) {
            throw ApiProblem.unauthorized(EXPIRED); // any revocation above has been committed first
        }
        return session;
    }

    public void logout(String token) {
        if (!PlatformSessionTokens.isWellFormed(token)) {
            return;
        }
        String hash = PlatformSessionTokens.hash(token);
        access.writeWithoutResult(() -> refreshTokens.findByTokenHash(hash).ifPresent(existing -> {
            refreshTokens.revokeFamily(existing.getFamilyId(), PlatformRevokeReason.LOGOUT, Instant.now());
            audit.record(AuditEntry.of("PlatformLogout", "PlatformUser", existing.getPlatformUserId())
                    .asPlatformActor(existing.getPlatformUserId()));
        }));
    }

    private PlatformSession rotate(String hash, Client client) {
        Instant now = Instant.now();
        PlatformRefreshToken current = refreshTokens.findForUpdateByTokenHash(hash).orElse(null);
        if (current == null) {
            return null;
        }
        if (!current.isActive(now)) {
            if (current.getRevokeReason() == PlatformRevokeReason.ROTATED && !current.rotatedWithin(REUSE_GRACE, now)) {
                refreshTokens.revokeFamily(current.getFamilyId(), PlatformRevokeReason.REUSE_DETECTED, now);
                audit.record(AuditEntry.of("PlatformRefreshTokenReuseDetected", "PlatformUser", current.getPlatformUserId())
                        .withMetadata(Map.of("familyId", current.getFamilyId().toString()))
                        .asPlatformActor(current.getPlatformUserId()));
            }
            return null;
        }
        PlatformUser user = users.findById(current.getPlatformUserId()).orElse(null);
        if (user == null || user.getStatus() != PlatformUserStatus.ACTIVE
                || user.getTokenVersion() != current.getTokenVersion()) {
            refreshTokens.revokeFamily(current.getFamilyId(), PlatformRevokeReason.REVOKED, now);
            return null;
        }
        String next = PlatformSessionTokens.generate();
        UUID nextId = Ids.newId();
        refreshTokens.save(PlatformRefreshToken.issue(nextId, user.getId(), current.getFamilyId(),
                PlatformSessionTokens.hash(next), current.getExpiresAt(), user.getTokenVersion(), client.ip(),
                client.userAgent()));
        current.markRotated(nextId, now);
        var accessToken = tokens.issue(user.getId(), user.getTokenVersion());
        return new PlatformSession(accessToken.value(), accessToken.expiresAt(), next, current.getExpiresAt());
    }

    /** Audits a failed attempt in its own transaction and returns the uniform 401 to throw. */
    private ApiProblem fail(String reason, UUID platformUserId) {
        audit.recordIndependently(AuditEntry.of("PlatformLoginFailed", "PlatformUser", platformUserId)
                .withMetadata(Map.of("reason", reason))
                .asActor(ActorType.ANONYMOUS));
        return ApiProblem.unauthorized(INVALID);
    }
}
```

- [ ] **Step 6: Write the cookie helper and controller**

`platform/web/PlatformCookies.java`:

```java
package com.nexusops.platform.web;

import java.time.Duration;
import java.time.Instant;
import org.springframework.http.ResponseCookie;

/** The platform refresh cookie: httpOnly, Secure, SameSite=Strict, scoped to /api/v1/platform/auth (ADR-0007). */
final class PlatformCookies {

    static final String NAME = "nexus_prt";
    private static final String PATH = "/api/v1/platform/auth";

    private PlatformCookies() {}

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

`platform/web/PlatformAuthController.java`:

```java
package com.nexusops.platform.web;

import com.nexusops.platform.application.PlatformAuthService;
import com.nexusops.platform.application.PlatformAuthService.Client;
import com.nexusops.platform.application.PlatformAuthService.PlatformSession;
import com.nexusops.shared.Emails;
import com.nexusops.shared.ratelimit.RateLimits;
import com.nexusops.shared.web.OriginGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public platform auth routes (listed in PublicEndpoints). */
@RestController
@RequestMapping("/api/v1/platform/auth")
class PlatformAuthController {

    record PlatformLoginRequest(
            @NotBlank @Size(max = 254) String email,
            @NotBlank @Size(max = 128) String password,
            @NotBlank @Size(max = 16) String code) {}

    record PlatformTokenResponse(String accessToken, String tokenType, long expiresIn) {}

    private final PlatformAuthService auth;
    private final RateLimits rateLimits;
    private final OriginGuard originGuard;

    PlatformAuthController(PlatformAuthService auth, RateLimits rateLimits, OriginGuard originGuard) {
        this.auth = auth;
        this.rateLimits = rateLimits;
        this.originGuard = originGuard;
    }

    @PostMapping("/login")
    ResponseEntity<PlatformTokenResponse> login(@Valid @RequestBody PlatformLoginRequest request, HttpServletRequest http) {
        rateLimits.checkPlatformLogin(http.getRemoteAddr(), Emails.tryNormalize(request.email()).orElse(null));
        return tokens(auth.login(request.email(), request.password(), request.code(), client(http)));
    }

    @PostMapping("/refresh")
    ResponseEntity<PlatformTokenResponse> refresh(
            @CookieValue(name = PlatformCookies.NAME, required = false) String token,
            @RequestHeader(name = HttpHeaders.ORIGIN, required = false) String origin,
            HttpServletRequest http) {
        originGuard.check(origin);
        rateLimits.checkPublic("refresh", http.getRemoteAddr());
        return tokens(auth.refresh(token, client(http)));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(
            @CookieValue(name = PlatformCookies.NAME, required = false) String token,
            @RequestHeader(name = HttpHeaders.ORIGIN, required = false) String origin) {
        originGuard.check(origin);
        if (token != null) {
            auth.logout(token);
        }
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, PlatformCookies.clear().toString()).build();
    }

    private static ResponseEntity<PlatformTokenResponse> tokens(PlatformSession session) {
        long expiresIn = Duration.between(Instant.now(), session.accessTokenExpiresAt()).toSeconds();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        PlatformCookies.issue(session.refreshToken(), session.refreshTokenExpiresAt()).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new PlatformTokenResponse(session.accessToken(), "Bearer", Math.round(expiresIn / 60.0) * 60));
    }

    private static Client client(HttpServletRequest http) {
        return new Client(http.getRemoteAddr(), http.getHeader(HttpHeaders.USER_AGENT));
    }
}
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `cd backend && ./gradlew test --tests 'com.nexusops.platform.*' --tests 'com.nexusops.EndpointAuthorizationCoverageTest' --tests 'com.nexusops.RateLimitIT'`
Expected: PASS.

`aTotpCodeWorksOnlyOnce` must fail before Step 5's `recordTotpUse`. You can confirm by temporarily passing `0` instead of `locked.getTotpLastStep()`, watching the test fail, then restoring it. Report that you did this.

- [ ] **Step 8: Run the whole backend suite**

Run: `cd backend && ./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add backend/src
git commit -m "feat: platform login with single-use TOTP, rotating refresh within an 8-hour cap, and login rate limits"
```

---
### Task 7: Workspace suspension and reactivation in `tenancy` — members are blocked on their next request

**Files:**
- Create: `backend/src/main/java/com/nexusops/tenancy/TenantStatusChanged.java`
- Modify: `backend/src/main/java/com/nexusops/tenancy/domain/Tenant.java`
- Modify: `backend/src/main/java/com/nexusops/tenancy/TenantDirectory.java`
- Modify: `backend/src/main/java/com/nexusops/identity/security/PrincipalCacheInvalidator.java`
- Test: `backend/src/test/java/com/nexusops/tenancy/TenantSuspensionIT.java`

**Interfaces:**
- Consumes: `AuditEntry.asPlatformActor(UUID)` (Task 1), and `PrincipalStateCache.evictTenant(UUID)` (Plan 3).
- Produces:
  - `TenantDirectory.suspendCurrent(UUID platformUserId, String reason)` and `TenantDirectory.reactivateCurrent(UUID platformUserId, String reason)`, both returning `TenantSummary`. Both act on the tenant bound in `TenantContext`, and both are **platform-only** by contract.
  - The event `TenantStatusChanged(UUID tenantId)`.
  - Constants `TenantDirectory.SUSPEND_NOT_ACTIVE` and `TenantDirectory.REACTIVATE_NOT_SUSPENDED`.

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/com/nexusops/tenancy/TenantSuspensionIT.java`:

```java
package com.nexusops.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.shared.TenantContext;
import com.nexusops.shared.web.ApiProblem;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Session;
import com.nexusops.support.TestTenants.Workspace;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@AutoConfigureMockMvc
class TenantSuspensionIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TenantDirectory directory;

    private ResultActions me(Session session) throws Exception {
        return mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + session.accessToken()));
    }

    private void suspend(UUID tenantId, UUID operator, String reason) {
        try (var scope = TenantContext.open(tenantId, null)) {
            directory.suspendCurrent(operator, reason);
        }
    }

    private void reactivate(UUID tenantId, UUID operator, String reason) {
        try (var scope = TenantContext.open(tenantId, null)) {
            directory.reactivateCurrent(operator, reason);
        }
    }

    private static void assertProblem(ThrowingCallable call, HttpStatus status, String detail) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiProblem.class, p -> {
            assertThat(p.status()).isEqualTo(status);
            assertThat(p.getMessage()).isEqualTo(detail);
        });
    }

    @Test
    void suspensionBlocksMembersOnTheirNextRequestAndReactivationRestoresThem() throws Exception {
        Workspace a = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("susp-a"));
        Workspace b = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("susp-b"));
        Session sa = TestTenants.login(mvc, a);
        Session sb = TestTenants.login(mvc, b);
        me(sa).andExpect(status().isOk()); // warms the principal cache: only eviction can make the next call 403

        UUID operator = UUID.randomUUID();
        suspend(a.tenantId(), operator, "  Chargeback investigation  ");

        me(sa).andExpect(status().isForbidden()).andExpect(jsonPath("$.detail").value("Workspace suspended."));
        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("nexus_rt", sa.refreshToken())))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"workspace":"%s","email":"%s","password":"%s"}""".formatted(a.slug(), a.email(), a.password())))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.detail").value("Workspace suspended."));
        me(sb).andExpect(status().isOk());

        Map<String, Object> audit = OwnerJdbc.ownerAs(a.tenantId()).queryForMap("""
                select actor_type, actor_id, metadata->>'reason' as reason from audit_events
                where action = 'TenantSuspended'""");
        assertThat(audit.get("actor_type")).isEqualTo("PLATFORM");
        assertThat(audit.get("actor_id")).isEqualTo(operator);
        assertThat(audit.get("reason")).isEqualTo("Chargeback investigation");

        reactivate(a.tenantId(), operator, "Resolved");
        me(sa).andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("nexus_rt", sa.refreshToken())))
                .andExpect(status().isOk());
        assertThat(OwnerJdbc.ownerAs(a.tenantId()).queryForObject(
                "select count(*) from audit_events where action = 'TenantReactivated'", Long.class)).isOne();
    }

    @Test
    void onlyLegalTransitionsAreAllowed() throws Exception {
        Workspace active = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("susp-c"));
        Workspace pending = TestTenants.signup(mvc, TestTenants.uniqueSlug("susp-d"));
        UUID operator = UUID.randomUUID();

        assertProblem(() -> reactivate(active.tenantId(), operator, "x"), HttpStatus.CONFLICT,
                "Only a suspended workspace can be reactivated.");
        assertProblem(() -> suspend(pending.tenantId(), operator, "x"), HttpStatus.CONFLICT,
                "Only an active workspace can be suspended.");
        suspend(active.tenantId(), operator, "x".repeat(500));
        assertProblem(() -> suspend(active.tenantId(), operator, "again"), HttpStatus.CONFLICT,
                "Only an active workspace can be suspended.");
        assertProblem(() -> suspend(UUID.randomUUID(), operator, "x"), HttpStatus.NOT_FOUND, "Workspace not found.");

        for (String bad : new String[] {null, "   ", "x".repeat(501)}) {
            assertThatThrownBy(() -> reactivate(active.tenantId(), operator, bad))
                    .isInstanceOfSatisfying(ApiProblem.class, p -> {
                        assertThat(p.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(p.errors()).extracting(ApiProblem.FieldError::field).containsExactly("reason");
                    });
        }
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && ./gradlew test --tests 'com.nexusops.tenancy.TenantSuspensionIT'`
Expected: compilation FAILS: `cannot find symbol: method suspendCurrent(java.util.UUID,java.lang.String)`.

- [ ] **Step 3: Implement the transitions**

`tenancy/TenantStatusChanged.java`:

```java
package com.nexusops.tenancy;

import java.util.UUID;

/** Published inside the transaction that suspended or reactivated a tenant. */
public record TenantStatusChanged(UUID tenantId) {}
```

In `Tenant.java`, add after `activate()`:

```java
    /** ACTIVE → SUSPENDED. TenantDirectory checks the current status first and answers 409 otherwise. */
    public void suspend() {
        if (status != TenantStatus.ACTIVE) {
            throw new IllegalStateException("Only an active tenant can be suspended");
        }
        status = TenantStatus.SUSPENDED;
        updatedAt = Instant.now();
    }

    /** SUSPENDED → ACTIVE (never PENDING_VERIFICATION → ACTIVE: that requires email verification). */
    public void reactivate() {
        if (status != TenantStatus.SUSPENDED) {
            throw new IllegalStateException("Only a suspended tenant can be reactivated");
        }
        status = TenantStatus.ACTIVE;
        updatedAt = Instant.now();
    }
```

In `TenantDirectory.java`, add constants at the top of the class:

```java
    static final String SUSPEND_NOT_ACTIVE = "Only an active workspace can be suspended.";
    static final String REACTIVATE_NOT_SUSPENDED = "Only a suspended workspace can be reactivated.";
```

Add these methods after `setModuleEnabled(...)`:

```java
    /**
     * Platform only (ADR-0007): suspends the tenant bound in TenantContext on behalf of a platform operator.
     * Members are blocked on their next request: TenantStatusChanged evicts the principal cache after commit.
     * Concurrent status changes fail on Tenant's @Version (409).
     */
    @Transactional
    public TenantSummary suspendCurrent(UUID platformUserId, String rawReason) {
        String reason = requireReason(rawReason);
        Tenant tenant = currentTenant();
        if (tenant.toSummary().status() != TenantStatus.ACTIVE) {
            throw ApiProblem.conflict(SUSPEND_NOT_ACTIVE);
        }
        tenant.suspend();
        return statusChanged(tenant, "TenantSuspended", platformUserId, reason);
    }

    /** Platform only (ADR-0007): reactivates the suspended tenant bound in TenantContext. */
    @Transactional
    public TenantSummary reactivateCurrent(UUID platformUserId, String rawReason) {
        String reason = requireReason(rawReason);
        Tenant tenant = currentTenant();
        if (tenant.toSummary().status() != TenantStatus.SUSPENDED) {
            throw ApiProblem.conflict(REACTIVATE_NOT_SUSPENDED);
        }
        tenant.reactivate();
        return statusChanged(tenant, "TenantReactivated", platformUserId, reason);
    }

    private TenantSummary statusChanged(Tenant tenant, String action, UUID platformUserId, String reason) {
        tenants.flush();
        audit.record(AuditEntry.of(action, "Tenant", tenant.getId())
                .withMetadata(Map.of("reason", reason))
                .asPlatformActor(platformUserId));
        events.publishEvent(new TenantStatusChanged(tenant.getId()));
        return tenant.toSummary();
    }

    private static String requireReason(String raw) {
        String reason = raw == null ? "" : raw.strip();
        if (reason.isEmpty() || reason.length() > 500) {
            throw ApiProblem.badRequestField("reason", "Enter a reason between 1 and 500 characters.");
        }
        return reason;
    }
```

In `PrincipalCacheInvalidator.java`, add the import `com.nexusops.tenancy.TenantStatusChanged` and this listener:

```java
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void on(TenantStatusChanged event) {
        cache.evictTenant(event.tenantId());
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd backend && ./gradlew test --tests 'com.nexusops.tenancy.TenantSuspensionIT'`
Expected: PASS.

Then demonstrate that the eviction is load-bearing. Comment out the new `on(TenantStatusChanged)` listener and re-run: `suspensionBlocksMembersOnTheirNextRequestAndReactivationRestoresThem` must FAIL with `Status expected:<403> but was:<200>`. Restore the listener and record the result in the report.

- [ ] **Step 5: Run the whole backend suite**

Run: `cd backend && ./gradlew build`
Expected: BUILD SUCCESSFUL, with `ModularityTest` green (identity → tenancy event is an allowed dependency).

- [ ] **Step 6: Commit**

```bash
git add backend/src
git commit -m "feat: suspend and reactivate workspaces with platform-attributed audit and immediate session effect"
```

---

### Task 8: Platform tenant API — list with active-user counts and owners, suspend, reactivate

**Files:**
- Create: `backend/src/main/java/com/nexusops/platform/application/PlatformTenantView.java`
- Create: `backend/src/main/java/com/nexusops/platform/application/PlatformTenantQueries.java`
- Create: `backend/src/main/java/com/nexusops/platform/application/PlatformTenantAdmin.java`
- Create: `backend/src/main/java/com/nexusops/platform/web/PlatformTenantController.java`
- Test: `backend/src/test/java/com/nexusops/platform/PlatformTenantApiIT.java`

**Interfaces:**
- Consumes:
  - `PlatformAccess.read` (Task 2).
  - `CurrentPlatformActor.require()` (Task 5).
  - `TenantDirectory.suspendCurrent/reactivateCurrent` (Task 7).
  - `Paging.of`, `PageResponse`, `TenantContext`, `TenantStatus`.
- Produces:
  - `GET /api/v1/platform/tenants?status=&q=&page=&size=` → `PageResponse<PlatformTenantView>`.
  - `POST /api/v1/platform/tenants/{id}/suspend` and `POST /api/v1/platform/tenants/{id}/reactivate` (body `{"reason": "..."}`) → `PlatformTenantView`.
  - `PlatformTenantView(UUID id, String slug, String name, String status, String planCode, Instant createdAt, long activeUsers, List<String> ownerEmails)`.

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/com/nexusops/platform/PlatformTenantApiIT.java`:

```java
package com.nexusops.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.platform.application.PlatformUserAdmin;
import com.nexusops.platform.domain.PlatformRole;
import com.nexusops.platform.security.PlatformTokenService;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestMembers;
import com.nexusops.support.TestPlatformUsers;
import com.nexusops.support.TestPlatformUsers.Operator;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Workspace;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class PlatformTenantApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired PlatformUserAdmin admin;
    @Autowired PlatformTokenService platformTokens;
    @Autowired TestMembers members;

    Workspace a;
    Workspace b;
    Operator adminOp;
    String adminToken;
    String supportToken;

    @BeforeEach
    void setUp() throws Exception {
        a = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("pt-a"));
        b = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("pt-b"));
        members.create(a.tenantId(), Set.of());
        adminOp = TestPlatformUsers.create(admin, PlatformRole.PLATFORM_ADMIN);
        adminToken = platformTokens.issue(adminOp.id(), 0).value();
        supportToken = platformTokens.issue(TestPlatformUsers.create(admin, PlatformRole.PLATFORM_SUPPORT).id(), 0).value();
    }

    private ResultActions as(String token, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + token));
    }

    private ResultActions change(String token, UUID tenantId, String action, String reason) throws Exception {
        return as(token, post("/api/v1/platform/tenants/" + tenantId + "/" + action)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"" + reason + "\"}"));
    }

    @Test
    void listsWorkspacesWithActiveUserCountsAndOwners() throws Exception {
        as(supportToken, get("/api/v1/platform/tenants").param("q", a.slug().toUpperCase()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(a.tenantId().toString()))
                .andExpect(jsonPath("$.items[0].slug").value(a.slug()))
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].planCode").value("FREE"))
                .andExpect(jsonPath("$.items[0].activeUsers").value(2))
                .andExpect(jsonPath("$.items[0].ownerEmails[0]").value(a.email()))
                .andExpect(jsonPath("$.items[0].ownerEmails.length()").value(1));
        as(supportToken, get("/api/v1/platform/tenants").param("q", a.slug()).param("status", "SUSPENDED"))
                .andExpect(jsonPath("$.total").value(0));
        as(supportToken, get("/api/v1/platform/tenants").param("q", "%")).andExpect(jsonPath("$.total").value(0));
        as(supportToken, get("/api/v1/platform/tenants").param("size", "2"))
                .andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.size").value(2));
        as(supportToken, get("/api/v1/platform/tenants").param("status", "BOGUS"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("status"));
        as(supportToken, get("/api/v1/platform/tenants").param("size", "101"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("size"));
        as(supportToken, get("/api/v1/platform/tenants").param("page", "abc"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("page"));
    }

    @Test
    void adminSuspendsAndReactivatesAWorkspace() throws Exception {
        var sa = TestTenants.login(mvc, a);
        var sb = TestTenants.login(mvc, b);

        change(adminToken, a.tenantId(), "suspend", "Abuse report 42").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED")).andExpect(jsonPath("$.slug").value(a.slug()));
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + sa.accessToken()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.detail").value("Workspace suspended."));
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + sb.accessToken())).andExpect(status().isOk());

        Map<String, Object> audit = OwnerJdbc.ownerAs(a.tenantId()).queryForMap("""
                select actor_type, actor_id, metadata->>'reason' as reason from audit_events where action = 'TenantSuspended'""");
        assertThat(audit).containsEntry("actor_type", "PLATFORM").containsEntry("actor_id", adminOp.id())
                .containsEntry("reason", "Abuse report 42");

        change(adminToken, a.tenantId(), "reactivate", "Resolved").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + sa.accessToken())).andExpect(status().isOk());
    }

    @Test
    void supportCannotSuspendAndTenantTokensCannotReachThePlatform() throws Exception {
        change(supportToken, a.tenantId(), "suspend", "nope").andExpect(status().isForbidden());
        String tenantToken = TestTenants.login(mvc, a).accessToken();
        as(tenantToken, get("/api/v1/platform/tenants")).andExpect(status().isUnauthorized());
        assertThat(OwnerJdbc.jdbc().queryForObject("select status from tenants where id = ?", String.class, a.tenantId()))
                .isEqualTo("ACTIVE");
    }

    @Test
    void validatesIdsReasonsAndTransitions() throws Exception {
        change(adminToken, UUID.randomUUID(), "suspend", "x")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.detail").value("Workspace not found."));
        as(adminToken, post("/api/v1/platform/tenants/not-a-uuid/suspend").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"x\"}")).andExpect(status().isNotFound());
        change(adminToken, a.tenantId(), "suspend", "   ")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("reason"));
        change(adminToken, a.tenantId(), "reactivate", "x")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Only a suspended workspace can be reactivated."));
    }

    @Test
    void platformListingDoesNotLeakIntoTenantRequests() throws Exception {
        String ownerToken = TestTenants.login(mvc, a).accessToken();
        for (int i = 0; i < 5; i++) {
            as(supportToken, get("/api/v1/platform/tenants")).andExpect(status().isOk());
            String users = as(ownerToken, get("/api/v1/users")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(2))
                    .andReturn().getResponse().getContentAsString();
            assertThat(users).doesNotContain(b.slug());
        }
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && ./gradlew test --tests 'com.nexusops.platform.PlatformTenantApiIT'`
Expected: 404s on `/api/v1/platform/tenants`. Assertions fail with `Status expected:<200> but was:<404>`, because no handler exists yet.

- [ ] **Step 3: Implement the read model**

`platform/application/PlatformTenantView.java`:

```java
package com.nexusops.platform.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PlatformTenantView(UUID id, String slug, String name, String status, String planCode, Instant createdAt,
        long activeUsers, List<String> ownerEmails) {}
```

`platform/application/PlatformTenantQueries.java`:

```java
package com.nexusops.platform.application;

import com.nexusops.platform.internal.PlatformAccess;
import com.nexusops.shared.web.ApiProblem;
import com.nexusops.shared.web.PageResponse;
import com.nexusops.shared.web.Paging;
import com.nexusops.tenancy.TenantStatus;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

/**
 * Cross-tenant workspace read model for the platform console (decision 4). Runs only inside PlatformAccess, whose
 * flag makes the platform_read SELECT policies on users/roles/user_roles apply; nothing here can write tenant data.
 */
@Service
public class PlatformTenantQueries {

    static final String NOT_FOUND = "Workspace not found.";

    private static final String SELECT = """
            select t.id, t.slug, t.name, t.status, t.plan_code, t.created_at,
                   (select count(*) from users u where u.tenant_id = t.id and u.status = 'ACTIVE') as active_users,
                   array(select u.email from users u
                           join user_roles ur on ur.user_id = u.id
                           join roles r on r.id = ur.role_id
                          where u.tenant_id = t.id and u.status = 'ACTIVE' and r.system and r.name = 'TENANT_OWNER'
                          order by u.email) as owner_emails
            from tenants t""";

    private static final RowMapper<PlatformTenantView> ROW = (rs, n) -> new PlatformTenantView(
            rs.getObject("id", UUID.class),
            rs.getString("slug"),
            rs.getString("name"),
            rs.getString("status"),
            rs.getString("plan_code"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getLong("active_users"),
            Arrays.asList((String[]) rs.getArray("owner_emails").getArray()));

    private final PlatformAccess access;
    private final JdbcTemplate jdbc;

    PlatformTenantQueries(PlatformAccess access, JdbcTemplate jdbc) {
        this.access = access;
        this.jdbc = jdbc;
    }

    public PageResponse<PlatformTenantView> list(String status, String q, Integer page, Integer size) {
        Pageable pageable = Paging.of(page, size);
        StringBuilder where = new StringBuilder(" where true");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            where.append(" and t.status = ?");
            args.add(parseStatus(status).name());
        }
        if (q != null && !q.isBlank()) {
            String like = "%" + escapeLike(q.strip().toLowerCase(Locale.ROOT)) + "%";
            where.append(" and (lower(t.slug) like ? escape '\\' or lower(t.name) like ? escape '\\')");
            args.add(like);
            args.add(like);
        }
        return access.read(() -> {
            Long total = jdbc.queryForObject("select count(*) from tenants t" + where, Long.class, args.toArray());
            List<Object> pageArgs = new ArrayList<>(args);
            pageArgs.add(pageable.getPageSize());
            pageArgs.add(pageable.getOffset());
            List<PlatformTenantView> items = jdbc.query(
                    SELECT + where + " order by t.created_at desc, t.id limit ? offset ?", ROW, pageArgs.toArray());
            return new PageResponse<>(items, pageable.getPageNumber(), pageable.getPageSize(), total == null ? 0 : total);
        });
    }

    public PlatformTenantView get(UUID id) {
        return access.read(() -> jdbc.query(SELECT + " where t.id = ?", ROW, id).stream().findFirst()
                .orElseThrow(() -> ApiProblem.notFound(NOT_FOUND)));
    }

    private static TenantStatus parseStatus(String raw) {
        try {
            return TenantStatus.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiProblem.badRequestField("status", "Status must be PENDING_VERIFICATION, ACTIVE or SUSPENDED.");
        }
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
```

`platform/application/PlatformTenantAdmin.java`:

```java
package com.nexusops.platform.application;

import com.nexusops.platform.security.CurrentPlatformActor;
import com.nexusops.shared.TenantContext;
import com.nexusops.tenancy.TenantDirectory;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Suspends/reactivates a workspace as the current platform operator. The tenant scope is opened around the
 * tenancy call only (so the audit row lands in that workspace's log); the read-back runs outside it, in
 * PlatformAccess, which refuses tenant scopes.
 */
@Service
public class PlatformTenantAdmin {

    private final TenantDirectory tenants;
    private final PlatformTenantQueries queries;

    PlatformTenantAdmin(TenantDirectory tenants, PlatformTenantQueries queries) {
        this.tenants = tenants;
        this.queries = queries;
    }

    public PlatformTenantView suspend(UUID tenantId, String reason) {
        UUID operator = CurrentPlatformActor.require().id();
        try (var scope = TenantContext.open(tenantId, null)) {
            tenants.suspendCurrent(operator, reason);
        }
        return queries.get(tenantId);
    }

    public PlatformTenantView reactivate(UUID tenantId, String reason) {
        UUID operator = CurrentPlatformActor.require().id();
        try (var scope = TenantContext.open(tenantId, null)) {
            tenants.reactivateCurrent(operator, reason);
        }
        return queries.get(tenantId);
    }
}
```

`platform/web/PlatformTenantController.java`:

```java
package com.nexusops.platform.web;

import com.nexusops.platform.application.PlatformTenantAdmin;
import com.nexusops.platform.application.PlatformTenantQueries;
import com.nexusops.platform.application.PlatformTenantView;
import com.nexusops.shared.web.PageResponse;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/platform/tenants")
class PlatformTenantController {

    /** The reason is validated by TenantDirectory (1–500 characters, field error "reason"). */
    record StatusChangeRequest(String reason) {}

    private final PlatformTenantQueries queries;
    private final PlatformTenantAdmin admin;

    PlatformTenantController(PlatformTenantQueries queries, PlatformTenantAdmin admin) {
        this.queries = queries;
        this.admin = admin;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('platform.tenant.read')")
    PageResponse<PlatformTenantView> list(@RequestParam(required = false) String status,
            @RequestParam(required = false) String q, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return queries.list(status, q, page, size);
    }

    @PostMapping("/{id}/suspend")
    @PreAuthorize("hasAuthority('platform.tenant.suspend')")
    PlatformTenantView suspend(@PathVariable UUID id, @RequestBody StatusChangeRequest request) {
        return admin.suspend(id, request.reason());
    }

    @PostMapping("/{id}/reactivate")
    @PreAuthorize("hasAuthority('platform.tenant.suspend')")
    PlatformTenantView reactivate(@PathVariable UUID id, @RequestBody StatusChangeRequest request) {
        return admin.reactivate(id, request.reason());
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd backend && ./gradlew test --tests 'com.nexusops.platform.*' --tests 'com.nexusops.EndpointAuthorizationCoverageTest' --tests 'com.nexusops.CrossTenantApiIT' --tests 'com.nexusops.ModularityTest' --tests 'com.nexusops.platform.PlatformAccessConfinementTest'`
Expected: PASS.

- [ ] **Step 5: Run the whole backend suite**

Run: `cd backend && ./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add backend/src
git commit -m "feat: platform tenant API with active-user counts, owners, suspend and reactivate"
```

---

### Task 9: Documentation and contract — ADR-0007, spec §17, threat model, ERD, README, OpenAPI

**Files:**
- Create: `docs/decisions/0007-platform-administration.md`
- Modify: `docs/superpowers/specs/2026-10-04-platform-foundation-design.md` (§9 table, §10 platform routes, new §17)
- Modify: `docs/architecture/threat-model.md`, `docs/architecture/erd.md`
- Modify: `README.md`
- Modify: `backend/src/test/java/com/nexusops/OpenApiContractIT.java`; regenerate `docs/api/openapi.json`

**Interfaces:**
- Consumes: everything above. This task changes no production behaviour.

- [ ] **Step 1: Extend the contract test (failing first)**

In `OpenApiContractIT.documentListsTheV1RoutesAndBearerScheme`, add to the `contains(...)` list:

```java
                "\"/api/v1/platform/auth/login\"", "\"/api/v1/platform/auth/refresh\"",
                "\"/api/v1/platform/auth/logout\"", "\"/api/v1/platform/me\"", "\"/api/v1/platform/tenants\"",
                "\"/api/v1/platform/tenants/{id}/suspend\"", "\"/api/v1/platform/tenants/{id}/reactivate\""
```

Run: `cd backend && ./gradlew test --tests 'com.nexusops.OpenApiContractIT'`
Expected: PASS once Tasks 5–8 are in, since springdoc picks the routes up automatically. If it fails, a route is missing; fix that before continuing.

Then regenerate the published contract:

Run: `cd backend && ./gradlew test --tests 'com.nexusops.OpenApiContractIT' -Dopenapi.export=true --rerun-tasks`
Expected: PASS, and `git diff --stat docs/api/openapi.json` shows a change that contains `/api/v1/platform/tenants`.

- [ ] **Step 2: Write ADR-0007**

`docs/decisions/0007-platform-administration.md`:

```markdown
# ADR-0007: Platform administration — separate principals, TOTP, and a confined cross-tenant flag

- **Status:** Accepted (Plan 4, 2026-10-06)
- **Context:** NexusOps staff must list every workspace and suspend abusive ones (spec §2, §4.2, §6, §10). This is
  the only legitimate cross-tenant access in the system, so it must be strongly authenticated, attributable and
  impossible to reach from tenant code paths.

## Decision

- **Separate principals.**
  - Staff accounts live in `platform_users`, never in `users`.
  - They are created and managed only from a CLI (`make platform-admin`, `platform-reset-totp`,
    `platform-reset-password`, `platform-disable`, `platform-enable`).
  - The CLI requires an interactive terminal and confirms an authenticator code before saving anything.
  - Roles:
    - `PLATFORM_SUPPORT` → `platform.tenant.read`.
    - `PLATFORM_ADMIN` → `platform.tenant.read` + `platform.tenant.suspend`.

    Authorities come from the stored row on every request, never from the token.
- **Password + TOTP.**
  - RFC 6238: HMAC-SHA1, 6 digits, 30 s steps, ±1 step of skew.
  - Codes are single-use: `totp_last_step` is advanced under a row lock.
  - Secrets are 20 random bytes, encrypted with AES-256-GCM. The key is `PLATFORM_TOTP_KEY`, required at startup; the
    AAD is the platform user id. The secret is shown once, in the CLI.
  - Failures are uniform ("Invalid email, password or code."), with dummy-hash timing, and every failure is audited.
- **Separate tokens.**
  - One RS256 key, two audiences:
    - tenant tokens carry `aud=nexusops-tenant` and `tid`;
    - platform tokens carry `aud=nexusops-platform`, `pv` and no `tid`.
  - Each security chain's decoder accepts only its own kind. The platform chain is ordered first and matches only
    `/api/v1/platform/**`. Its decoder and encoder are deliberately not beans.
  - Platform refresh cookie: `nexus_prt`, path `/api/v1/platform/auth`. It rotates with family reuse detection, inside
    a fixed 8-hour family lifetime that rotation never extends. Changing the password or authenticator, or disabling
    the account, bumps `token_version` and ends all sessions.
- **A confined, transaction-local flag.**
  - `platform_users` and `platform_refresh_tokens` have FORCE RLS, visible only while `app.platform_access = 'on'`.
  - The same flag enables `FOR SELECT` policies `platform_read` on `users`, `roles` and `user_roles`, so the workspace
    list can show active-user counts and owner emails. Write policies are untouched.
  - `PlatformAccess` is the only setter. It uses `set_config(..., true)`, refuses tenant scopes and refuses
    already-open transactions.
  - `PlatformAccessConfinementTest` fails the build if any other main source mentions the flag.
- **Suspension lives in tenancy.**
  - `TenantDirectory.suspendCurrent/reactivateCurrent` allow only ACTIVE ⇄ SUSPENDED, require a reason, and are audited
    in the workspace's own log as `actor_type=PLATFORM` with the operator's id.
  - They publish `TenantStatusChanged`; identity evicts the tenant's principal cache after commit, so members are
    blocked on their next request.
  - Sessions are not revoked; they resume on reactivation if they haven't expired.

## Consequences

- A bug in tenant code can't read staff credentials or other tenants' rows, because it never holds the flag. A bug
  inside the platform module still can, so that module is small and reviewed as security-critical.
- Losing `PLATFORM_TOTP_KEY` makes every enrolment unusable. Recovery is `make platform-reset-totp` per operator.
  Rotating the key needs a re-encryption job (future work).
- There is no HTTP surface for managing staff accounts and no recovery codes. An operator who loses their phone
  needs someone with server access to run the reset.
- Platform events with no workspace (logins, account changes) are recorded with `tenant_id` NULL. Viewing them needs a
  future platform audit view.
```

- [ ] **Step 3: Update the spec**

In `docs/superpowers/specs/2026-10-04-platform-foundation-design.md`:
- **§9 table:** after the login row, add:
  `| platform login | per IP rl:ip:{ip}:platform-login; per account rl:pacct:{sha256(email)}:platform-login | 10/min per IP; 5/min per account |`
- **§10 Platform bullet:** replace the two existing lines with:
  - `POST platform/auth/login` (public), `POST platform/auth/refresh` and `POST platform/auth/logout` (public, cookie);
  - `GET platform/me`;
  - `GET platform/tenants?status=&q=&page=&size=`;
  - `POST platform/tenants/{id}/suspend|reactivate` with `{reason}`.
- **Append §17:**

```markdown
## 17. Deltas adopted in Plan 4

1. **Platform users are created only from a CLI** (your decision, 2026-10-06). It shows the TOTP QR code once and
   requires a confirming code before saving. There is no HTTP enrolment or account management.
2. **Platform sessions:** a 15-minute `aud=nexusops-platform` access token plus a rotating `nexus_prt` refresh cookie
   (path `/api/v1/platform/auth`) with a fixed 8-hour family lifetime (your decision).
3. **Roles:**
   - `PLATFORM_SUPPORT` reads workspaces;
   - `PLATFORM_ADMIN` also suspends and reactivates (your decision).

   Authorities are read from the database on every request.
4. **The workspace list shows active-user counts and owner emails** (your decision), via `FOR SELECT` policies
   `platform_read` on `users`, `roles` and `user_roles`, gated by `app.platform_access`.
5. **Platform tables are RLS-protected** behind the same flag, not plain global tables. `PlatformAccess` is the only
   setter, and the flag is transaction-local. A source-scan test confines the flag to the platform module.
6. **TOTP:** RFC 6238 SHA-1/6/30 s, ±1 step of skew, single-use codes. Secrets are AES-256-GCM encrypted with
   `PLATFORM_TOTP_KEY` (a required secret), bound to the user id.
7. **Suspension:** only ACTIVE ⇄ SUSPENDED, with a reason of 1–500 characters.
   - It is audited in the workspace's own log with `actor_type=PLATFORM`.
   - It takes effect on members' next request via after-commit cache eviction.
   - Sessions resume on reactivation.
8. **`Emails`, `PasswordPolicy` and `OriginGuard` moved to `shared`**, so `platform` doesn't depend on `identity`.
```

- [ ] **Step 4: Update the threat model and ERD**

In `docs/architecture/threat-model.md`:
- change the title to "Phases 1–4";
- update T15's "Verified by" to `TenantSuspensionIT, PlatformTenantApiIT`;
- update T16's mitigation to "Audience separation; platform decoder rejects any `tid`; separate security chains" and its "Verified by" to `PlatformSecurityIT`;
- append these rows:

```markdown
| T18 | Stolen platform password | S | Password + single-use TOTP; uniform failures; per-IP and per-account rate limits; audited failures | PlatformAuthIT, PlatformLoginRateLimitIT |
| T19 | TOTP code replay (shoulder-surfing, intercepted code) | S | `totp_last_step` advanced under a row lock; a step ≤ last used is rejected | PlatformAuthIT |
| T20 | Tenant code path reads staff credentials or other tenants' rows | I/E | Platform tables and cross-tenant SELECT policies require `app.platform_access`; only `PlatformAccess` sets it, transaction-locally | PlatformAccessIT, PlatformAccessConfinementTest |
| T21 | Database dump exposes TOTP secrets | I | AES-256-GCM with `PLATFORM_TOTP_KEY` (not in the DB), AAD = user id | TotpSecretCipherTest |
| T22 | Long-lived stolen platform session | S/E | 15-min access tokens; refresh family capped at 8 h; credential changes bump `token_version` | PlatformAuthIT |
```

In `docs/architecture/erd.md`, add `platform_users` and `platform_refresh_tokens` to the Mermaid diagram:
- `PLATFORM_USERS ||--o{ PLATFORM_REFRESH_TOKENS : "has"`;
- the columns exactly as in V7;
- a note that both are flag-gated RLS tables, not tenant tables.

- [ ] **Step 5: Update the README**

Add a section after "Invite a teammate":

````markdown
### Platform administration (NexusOps staff)

Platform users are created from the CLI only. It needs an interactive terminal and the database running (`make up`):

```bash
make platform-admin EMAIL=you@example.com                    # PLATFORM_ADMIN; add ROLE=PLATFORM_SUPPORT for read-only
```

Type a password (12+ characters) twice, scan the QR code with an authenticator app, and enter the code it shows.
Then sign in:

```bash
curl -i -X POST localhost:8081/api/v1/platform/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"you@example.com","password":"…","code":"123456"}'
curl localhost:8081/api/v1/platform/tenants -H "Authorization: Bearer $PLATFORM_TOKEN"
curl -X POST localhost:8081/api/v1/platform/tenants/$TENANT_ID/suspend -H "Authorization: Bearer $PLATFORM_TOKEN" \
  -H 'Content-Type: application/json' -d '{"reason":"Abuse report 42"}'
```

To recover, run one of these:
- `make platform-reset-totp EMAIL=…` for a lost phone;
- `make platform-reset-password EMAIL=…` for a lost password;
- `make platform-disable EMAIL=…` / `make platform-enable EMAIL=…` to disable or re-enable an account.

In Docker, run `docker compose -f infra/docker/docker-compose.yml --profile app run --rm -it backend --nexusops.cli.command=create-platform-admin --nexusops.cli.email=you@example.com`.

Production must set `PLATFORM_TOTP_KEY`: 32 random bytes, base64, e.g. `openssl rand -base64 32`. Keep it out of the
database backups. Losing it means every operator must re-enrol.
````

- [ ] **Step 6: Full verification**

Run: `make test`
Expected: all of the following pass:
- `test-infra`;
- backend `./gradlew build`;
- frontend format/lint/typecheck/test/build;
- ai-service ruff/pytest.

Run: `make e2e`
Expected: the existing Playwright journey passes unchanged. This plan adds no UI.

- [ ] **Step 7: Live smoke (record results in the report)**

With `make up` and `make backend` running, check each of the following:
1. `make platform-admin EMAIL=smoke-admin@nexusops.test` completes with exit 0, after you confirm a code. If no interactive terminal or authenticator is available, say so and use `PlatformCliIT` as the evidence.
2. Sign up and verify a workspace through the README flow; its owner's `GET /api/v1/me` returns 200.
3. A platform login with the right code gives 200. Repeating the same code gives 401.
4. `GET /api/v1/platform/tenants?q=<slug>` shows the workspace with `activeUsers: 1` and the owner's email.
5. Suspend gives 200, and the owner's next `GET /api/v1/me` gives 403 "Workspace suspended.". Reactivate gives 200, and the owner's `GET /api/v1/me` gives 200.
6. The owner's access token on `GET /api/v1/platform/me` gives 401, and the platform token on `GET /api/v1/me` gives 401.

- [ ] **Step 8: Commit**

```bash
git add docs README.md backend/src/test/java/com/nexusops/OpenApiContractIT.java
git commit -m "docs: ADR-0007 platform administration, spec §17, threat model, ERD, README, OpenAPI contract"
```
