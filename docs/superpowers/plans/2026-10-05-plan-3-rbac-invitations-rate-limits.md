# Plan 3 — RBAC Management, Invitations, Users, Modules, Audit API, Rate Limiting: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete blueprint Phase 3 on top of Plan 2's isolated identity core. Tenant admins can:
- invite people;
- manage users, custom roles and module toggles, within their plan's limits and without being able to escalate privileges;
- read the audit log.

Every public and authenticated route is rate-limited. The Plan 2 deferred items Plan 3 depends on are fixed first: async mail, proxy-aware client IPs, a race-free principal cache, and scoped cache eviction.

**Architecture:** The same modular monolith. New code lives in the existing modules:
- **`tenancy`:** module toggles and plan limits.
- **`authorization`:** role management and the escalation guard.
- **`identity`:** invitations, user administration and the API rate check.
- **`audit`:** the read API.
- **`shared`:** the rate limiter, pagination and current authorities.

Changes that affect permissions publish domain events (`RolesChanged`, `ModulesChanged`). Identity evicts the affected principal-cache entries after commit, so changes take effect on the very next request.

**Tech stack:** Spring Boot 4.1.1, JDK 25, Hibernate 7.4.5, Spring Security 7.1.1, Spring Data JPA Specifications, Redis 7 (Lua token bucket), PostgreSQL 17 RLS, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-10-04-platform-foundation-design.md` §5, §7, §8, §9, §10, §15. Builds on `main` at `37b05c2`, with Plans 1–2 merged.

## Scope

**In:**
- Infra hardening:
  - generation-guarded principal cache;
  - scoped `evictTenant`;
  - eviction driven by permission events;
  - async mail;
  - trusted proxy and client IP;
  - production `ALLOWED_ORIGINS` fail-fast.
- Redis rate limiting (spec §9).
- Plan limits and module toggles.
- Permission catalog and role management, with the escalation guard.
- Invitations: create, list, revoke, preview, accept.
- User administration: list, filter, page, get, rename, disable/enable, assign roles, last-owner protection.
- Audit read API.
- Cross-tenant 404 and escalation suites.
- OpenAPI, ADR-0006 and README.

**Out:**
- Plan 4: platform admin with TOTP, and cross-tenant platform access.
- Plan 5: frontend screens and E2E.
- Billing.
- Tenant erasure.
- SSO and MFA for tenant users.

## Decisions taken while writing this plan (flagged for review)

1. **Plan limits are enforced now** (your decision, 2026-10-05). Free allows 3 users and 2 modules.
   - The user count is ACTIVE users plus pending, unexpired invitations.
   - Limits come from `plans.limits`; a `null` value means unlimited.
2. **Pre-auth rate-limit keys are not tenant-prefixed.** No tenant is known at that point. They use `rl:ip:{ip}:{route}` and `rl:ws:{sha256(slug)}:login`, built only by `RateLimitKeys`. Authenticated API keys stay tenant-prefixed: `tenant:{tid}:user:{uid}:rl:api`.
3. **The principal cache uses a generation counter.** Each entry has a `…:principal-gen` key. Eviction increments it, and writes only land when the generation is unchanged. This closes Plan 2's evict-then-repopulate race without delayed deletes.
4. **Owner-set changes serialize per tenant.** They run under `pg_advisory_xact_lock(hashtext('owners:' || tenant_id))`. Two owners demoting or disabling each other at the same moment can't leave a workspace with no active owner.
5. **A role can't be deleted while it is assigned.** The API returns 409, and the caller must unassign it first. That's safer than silently stripping access through `ON DELETE CASCADE`.
6. **`PATCH /users/{id}` has one route with two permissions.** Changing names needs `identity.user.update`, and changing status needs `identity.user.disable`. The route's `@PreAuthorize` requires either one, and the service checks the specific one.
7. **Mail delivery runs on a bounded `mailExecutor`.** That's async in every profile except `test`, which runs it synchronously so tests stay deterministic. This fixes Plan 2's resend timing side channel and the DB connection being held during SMTP.
8. **Client IP comes from Tomcat's `RemoteIpValve`** (`server.forward-headers-strategy: native`), which only trusts internal proxy ranges. nginx now **overwrites** `X-Forwarded-For` with `$remote_addr`.
9. **Escalation uses grantable permissions**, not effective ones. `PrincipalState` carries the actor's `roleIds` and the unfiltered `grantablePermissions`. `PrincipalFilter` exposes them as `ActorDetails` on the authentication, so modules don't need cross-module lookups.

## Global Constraints

- **Unchanged from Plans 1–2:**
  - JDK 25, Spring Boot 4.1.1, `/api/v1`.
  - Runtime DB role `nexusops_app`; `DatabaseRoleGuard` must stay green.
  - problem+json errors with `requestId`.
  - Backend host port 8081; SHA-pinned actions.
  - Tenant context only from a verified JWT or a server-side lookup.
  - Every tenant table has `ENABLE` + `FORCE ROW LEVEL SECURITY` and a policy in its creating migration, using the predicate `tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid`.
  - Tenant-owned entities extend `TenantOwnedEntity`; there is no `tenantId` in any DTO.
  - UUIDv7 via `Ids.newId()`; `timestamptz` / `Instant`.
- **Commits carry NO AI attribution** of any kind: no `Co-Authored-By` trailer for any AI, and no "Generated with" line. Plain conventional-commit messages only (user requirement).
- **Cross-tenant ids return `404`** "… not found." and never 403. List endpoints never include another tenant's rows.
- **Pagination:**
  - `?page` is 0-based, default 0.
  - `?size` defaults to 20, minimum 1, maximum 100.
  - Responses look like `{"items":[…],"page":0,"size":20,"total":N}`.
  - An invalid page or size returns 400, with a field error on `page` or `size`.
- **Escalation guard (403):**
  - A role whose permission set is not a subset of the actor's **grantable** permissions can't be created, edited to, invited with or assigned. Grantable means the union of the actor's roles' permissions, regardless of module enablement, so an owner can prepare roles for modules that aren't enabled yet. The detail is "You can't grant permissions you don't have."
  - Only holders of the `TENANT_OWNER` role can invite to, assign or remove `TENANT_OWNER`. The detail is "Only workspace owners can manage the owner role."
- **Last owner (409):** the last ACTIVE `TENANT_OWNER` can't be disabled or lose the owner role. The detail is "A workspace needs at least one active owner."
- **System roles (409):** `TENANT_OWNER` and `TENANT_ADMIN` can't be renamed, re-permissioned or deleted. The detail is "System roles can't be changed."
- **Plan limits (409):**
  - Users: "Your plan allows {n} users. Upgrade to add more."
  - Modules: "Your plan allows {n} modules. Upgrade to enable more."
- **Rate limits (spec §9).** Defaults in `application.yml`; the test profile raises them (see Task 3).

  | Rule | Key | Limit |
  |---|---|---|
  | `login` | per IP **and** per workspace | 10/min |
  | `signup` | per IP | 5/min |
  | `verify-email` | per IP | 5/min |
  | `resend-verification` | per IP | 5/min |
  | `invitation-accept` | per IP | 5/min |
  | `invitation-preview` | per IP | 20/min |
  | `refresh` | per IP | 30/min |
  | `api` | per tenant+user | 300/min |

  Exceeding a limit returns `429` problem+json with:
  - title "Too Many Requests";
  - detail "Too many requests. Try again in {s} seconds.";
  - header `Retry-After: {s}`.

  If Redis is unavailable:
  - Public auth routes fail closed with `503` "Service temporarily unavailable. Please try again shortly."
  - The authenticated API fails open, with a WARN log.
- **Every change to a user's status, role assignment, role permissions or a tenant's modules evicts the affected principal-cache entries after commit.**
- **Audited actions (in the same transaction):**
  - `InvitationCreated`, `InvitationRevoked`, `InvitationAccepted`, `UserRegistered`
  - `UserUpdated`, `UserDisabled`, `UserEnabled`, `UserRolesChanged`
  - `RoleCreated`, `RoleUpdated`, `RolePermissionsChanged`, `RoleDeleted`
  - `ModuleEnabled`, `ModuleDisabled`
- **Never logged or audited:** passwords, hashes, tokens, cookies. Invitation tokens are stored only as SHA-256 hashes, using the same `OpaqueTokens` format as Plan 2.
- **Branch:** work on `feat/rbac-invitations`; never push or merge without asking.

## Review Focus

1. **Two tabs accept the same invitation at once.** Expected: exactly one user is created; the other request gets 400 "This invitation link is invalid or has expired." *Pinned by `InvitationAcceptIT.concurrentAcceptCreatesExactlyOneUser` (Task 7).*
2. **Two owners demote each other at the same time.** Expected: at least one ACTIVE owner always remains; one request succeeds and the other gets 409. *Pinned by `UserAdminIT.concurrentMutualDemotionKeepsAnOwner` (Task 8).*
3. **A user is disabled while their session is live.** Expected: their very next API request gets 401, and their refresh token no longer works. *Pinned by `UserAdminIT.disablingKillsLiveSessions` (Task 8).*
4. **An attacker spreads password guesses for one workspace across many IPs.** Expected: the per-workspace login bucket returns 429 after 10 attempts, whatever the source IP. *Pinned by `RateLimitIT.loginIsLimitedPerWorkspaceAcrossIps` (Task 3).*
5. **An admin removes a permission from a role while its members are logged in.** Expected: on their next request the members get 403 for the removed permission, with no 60 s staleness. *Pinned by `RoleManagementIT.permissionRemovalTakesEffectImmediately` (Task 5).*

---

### Task 1: Principal cache — generation-guarded writes, scoped tenant eviction, event-driven invalidation

**Files:**
- Modify: `backend/src/main/java/com/nexusops/identity/security/PrincipalStateCache.java` (full replacement below).
- Create:
  - `backend/src/main/java/com/nexusops/authorization/RolesChanged.java`;
  - `backend/src/main/java/com/nexusops/tenancy/ModulesChanged.java`;
  - `backend/src/main/java/com/nexusops/identity/security/PrincipalCacheInvalidator.java`.
- Modify test: `backend/src/test/java/com/nexusops/identity/security/PrincipalStateCacheTest.java`. It currently mocks `redis.delete`/`redis.scan` throwing; switch it to mocking `redis.execute(...)` throwing.
- Create test: `backend/src/test/java/com/nexusops/identity/security/PrincipalStateCacheIT.java`.

**Interfaces:**
- Produces `record RolesChanged(UUID tenantId)` in package `com.nexusops.authorization`, and `record ModulesChanged(UUID tenantId)` in `com.nexusops.tenancy`. Tasks 4 and 5 publish them inside their transactions.
- Produces these `PrincipalStateCache` methods (signatures unchanged, semantics new):
  - `get(UUID, UUID)`
  - `evict(UUID tenantId, UUID userId)`
  - `evictTenant(UUID tenantId)`, which now only touches `tenant:{t}:user:*:principal` entries
- Produces, package-private for tests: `boolean storeIfGeneration(String key, String generationKey, String seenGeneration, String value)` and `static String key(UUID, UUID)` / `static String generationKey(UUID, UUID)`.
- Produces `PrincipalCacheInvalidator`: on `RolesChanged` or `ModulesChanged`, after commit (or immediately with no transaction), it calls `evictTenant(tenantId)`.

- [ ] **Step 1: Create the branch**

```bash
cd /Users/user/Desktop/nexusops && git checkout -b feat/rbac-invitations
```

- [ ] **Step 2: Write the failing integration test**

`backend/src/test/java/com/nexusops/identity/security/PrincipalStateCacheIT.java`:
```java
package com.nexusops.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexusops.authorization.RolesChanged;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.tenancy.ModulesChanged;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class PrincipalStateCacheIT extends IntegrationTestSupport {

    @Autowired PrincipalStateCache cache;
    @Autowired StringRedisTemplate redis;
    @Autowired ApplicationEventPublisher events;
    @Autowired TransactionTemplate tx;

    final UUID tenant = UUID.randomUUID();
    final UUID user = UUID.randomUUID();

    @Test
    void writeWithTheCurrentGenerationLands() {
        String key = PrincipalStateCache.key(tenant, user);
        assertThat(cache.storeIfGeneration(key, PrincipalStateCache.generationKey(tenant, user), "0", "{}")).isTrue();
        assertThat(redis.opsForValue().get(key)).isEqualTo("{}");
    }

    @Test
    void staleWriteAfterAnEvictionIsDiscarded() {
        String key = PrincipalStateCache.key(tenant, user);
        String seen = "0"; // generation read by a request before it loaded state from the DB
        cache.evict(tenant, user); // a concurrent logout-all / disable / role change
        assertThat(cache.storeIfGeneration(key, PrincipalStateCache.generationKey(tenant, user), seen, "{\"stale\":true}"))
                .isFalse();
        assertThat(redis.opsForValue().get(key)).isNull();
    }

    @Test
    void evictTenantRemovesOnlyThatTenantsPrincipalEntries() {
        UUID other = UUID.randomUUID();
        UUID otherTenant = UUID.randomUUID();
        redis.opsForValue().set(PrincipalStateCache.key(tenant, user), "{}");
        redis.opsForValue().set(PrincipalStateCache.key(tenant, other), "{}");
        String rateLimitKey = "tenant:" + tenant + ":user:" + user + ":rl:api";
        redis.opsForValue().set(rateLimitKey, "5");
        redis.opsForValue().set(PrincipalStateCache.key(otherTenant, user), "{}");

        cache.evictTenant(tenant);

        assertThat(redis.hasKey(PrincipalStateCache.key(tenant, user))).isFalse();
        assertThat(redis.hasKey(PrincipalStateCache.key(tenant, other))).isFalse();
        assertThat(redis.opsForValue().get(PrincipalStateCache.generationKey(tenant, user))).isEqualTo("1");
        assertThat(redis.opsForValue().get(rateLimitKey)).isEqualTo("5");
        assertThat(redis.hasKey(PrincipalStateCache.key(otherTenant, user))).isTrue();
    }

    @Test
    void roleAndModuleEventsEvictTheTenantAfterCommit() {
        redis.opsForValue().set(PrincipalStateCache.key(tenant, user), "{}");
        tx.executeWithoutResult(s -> {
            events.publishEvent(new RolesChanged(tenant));
            assertThat(redis.hasKey(PrincipalStateCache.key(tenant, user))).as("not before commit").isTrue();
        });
        assertThat(redis.hasKey(PrincipalStateCache.key(tenant, user))).isFalse();

        redis.opsForValue().set(PrincipalStateCache.key(tenant, user), "{}");
        events.publishEvent(new ModulesChanged(tenant)); // no transaction: immediate
        assertThat(redis.hasKey(PrincipalStateCache.key(tenant, user))).isFalse();
    }
}
```

Run: `cd backend && ./gradlew test --tests '*PrincipalStateCacheIT'`
Expected: compilation FAILS (`RolesChanged`, `ModulesChanged`, `storeIfGeneration` and `generationKey` don't exist yet).

- [ ] **Step 3: Implement the events, the invalidator and the cache**

`backend/src/main/java/com/nexusops/authorization/RolesChanged.java`:
```java
package com.nexusops.authorization;

import java.util.UUID;

/** Published inside the transaction that changed a role's permissions or deleted a role. */
public record RolesChanged(UUID tenantId) {}
```

`backend/src/main/java/com/nexusops/tenancy/ModulesChanged.java`:
```java
package com.nexusops.tenancy;

import java.util.UUID;

/** Published inside the transaction that enabled or disabled a module for a tenant. */
public record ModulesChanged(UUID tenantId) {}
```

`backend/src/main/java/com/nexusops/identity/security/PrincipalCacheInvalidator.java`:
```java
package com.nexusops.identity.security;

import com.nexusops.authorization.RolesChanged;
import com.nexusops.tenancy.ModulesChanged;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Permission-affecting changes in other modules take effect on the members' very next request. */
@Component
class PrincipalCacheInvalidator {

    private final PrincipalStateCache cache;

    PrincipalCacheInvalidator(PrincipalStateCache cache) {
        this.cache = cache;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void on(RolesChanged event) {
        cache.evictTenant(event.tenantId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void on(ModulesChanged event) {
        cache.evictTenant(event.tenantId());
    }
}
```

Replace `backend/src/main/java/com/nexusops/identity/security/PrincipalStateCache.java` with:
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
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Per-request principal state with a short Redis TTL.
 *
 * <p>Race-free invalidation: every entry has a generation counter ({@code …:principal-gen}). Eviction
 * increments it and deletes the entry; a request that loaded state from the database may only store it
 * if the generation it read BEFORE loading is still current. So a request that read the old token
 * version just before a logout-all/disable can never re-populate the cache with stale state.
 * Redis failures fall back to the database (correct, just slower).
 */
@Component
public class PrincipalStateCache {

    private static final Logger log = LoggerFactory.getLogger(PrincipalStateCache.class);
    static final Duration TTL = Duration.ofSeconds(60);
    static final Duration GENERATION_TTL = Duration.ofHours(1);

    private static final RedisScript<Long> STORE_IF_GENERATION = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[2]) or '0'
            if current == ARGV[1] then
              redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
              return 1
            end
            return 0
            """, Long.class);

    private static final RedisScript<Long> BUMP_AND_DELETE = new DefaultRedisScript<>("""
            redis.call('INCR', KEYS[2])
            redis.call('PEXPIRE', KEYS[2], ARGV[1])
            return redis.call('DEL', KEYS[1])
            """, Long.class);

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
        String generationKey = generationKey(tenantId, userId);
        String seenGeneration = null;
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                return json.readValue(cached, PrincipalState.class);
            }
            String generation = redis.opsForValue().get(generationKey);
            seenGeneration = generation == null ? "0" : generation;
        } catch (RuntimeException e) {
            log.warn("Principal cache read failed; using database", e);
        }
        PrincipalState state = load(userId);
        if (seenGeneration != null) {
            try {
                storeIfGeneration(key, generationKey, seenGeneration, json.writeValueAsString(state));
            } catch (RuntimeException e) {
                log.warn("Principal cache write failed", e);
            }
        }
        return state;
    }

    /** Best-effort: a Redis failure is logged, not thrown (entries expire within {@link #TTL} anyway). */
    public void evict(UUID tenantId, UUID userId) {
        try {
            bumpAndDelete(key(tenantId, userId));
        } catch (RuntimeException e) {
            log.warn("Principal cache eviction failed; entry expires within TTL", e);
        }
    }

    /** Evicts every principal entry of one tenant (and nothing else under its prefix, e.g. rate-limit buckets). */
    public void evictTenant(UUID tenantId) {
        String pattern = TenantKeys.key(tenantId, "user") + ":*:principal";
        try (Cursor<String> keys = redis.scan(ScanOptions.scanOptions().match(pattern).count(500).build())) {
            keys.forEachRemaining(this::bumpAndDelete);
        } catch (RuntimeException e) {
            log.warn("Tenant principal cache eviction failed; entries expire within TTL", e);
        }
    }

    boolean storeIfGeneration(String key, String generationKey, String seenGeneration, String value) {
        Long stored = redis.execute(STORE_IF_GENERATION, List.of(key, generationKey),
                seenGeneration, value, String.valueOf(TTL.toMillis()));
        return stored != null && stored == 1L;
    }

    private void bumpAndDelete(String key) {
        redis.execute(BUMP_AND_DELETE, List.of(key, key + "-gen"), String.valueOf(GENERATION_TTL.toMillis()));
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

    static String key(UUID tenantId, UUID userId) {
        return TenantKeys.key(tenantId, "user", userId.toString(), "principal");
    }

    static String generationKey(UUID tenantId, UUID userId) {
        return key(tenantId, userId) + "-gen";
    }
}
```

In `PrincipalStateCacheTest`, change the mocked failure to `redis.execute(...)` throwing, for example `when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenThrow(new RedisConnectionFailureException("down"))`. That way `evict` and `evictTenant` are still proven not to propagate Redis failures; keep its `scan` mock throwing for `evictTenant`.

- [ ] **Step 4: Run the tests and the full build**

Run: `./gradlew test --tests '*PrincipalStateCacheIT' --tests '*PrincipalStateCacheTest'`, then `./gradlew build`
Expected: PASS (4 tests + the existing unit tests), then a green build. `TokenMisuseIT.staleTokenVersionRejectedAfterLogoutAll`, `TokenMisuseIT.disabledUserIsRejectedImmediately` and `TenantIsolationIT.permissionsComeFromTheServerNotTheToken` must still pass, because they exercise `evict`/`evictTenant` end to end.

- [ ] **Step 5: Commit**

```bash
cd /Users/user/Desktop/nexusops && git add backend && git commit -m "feat(identity): race-free principal cache (generation-guarded writes), scoped tenant eviction, event-driven invalidation"
```

---

### Task 2: Async mail, trusted client IP behind the proxy, after-commit helper, production origin fail-fast

**Files:**
- Create:
  - `backend/src/main/java/com/nexusops/notifications/internal/MailExecutorConfig.java`;
  - `backend/src/main/java/com/nexusops/shared/db/AfterCommit.java`.
- Modify:
  - `notifications/internal/MailDispatcher.java` (add `@Async("mailExecutor")`);
  - `application.yml`, `application-test.yml`, `application-prod.yml`;
  - `shared/config/RequiredSecretsVerifier.java`;
  - `frontend/nginx.conf`.
- Test:
  - `notifications/MailExecutorConfigTest.java`;
  - `shared/db/AfterCommitIT.java`;
  - `ClientIpIT.java`;
  - `ProdProfileRequiresSecretsTest.java` (add a case).

**Interfaces:**
- Produces `AfterCommit.run(Runnable action)`. It runs the action after the current transaction commits, does nothing on rollback, and runs immediately when no transaction is active. Task 8 uses it for cache eviction after user changes.
- Produces the bean `mailExecutor` (`java.util.concurrent.Executor`):
  - With `nexusops.mail.async: true` (the default), a bounded `ThreadPoolTaskExecutor` with thread prefix `mail-`.
  - With `false` (the test profile), a `SyncTaskExecutor`.
- Produces this behaviour: `HttpServletRequest.getRemoteAddr()` is the client from `X-Forwarded-For` when the direct peer is an internal proxy (Tomcat `RemoteIpValve`).

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/com/nexusops/notifications/MailExecutorConfigTest.java`:
```java
package com.nexusops.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexusops.notifications.internal.MailExecutorConfig;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class MailExecutorConfigTest {

    @Test
    void asyncExecutorRunsOnBoundedMailThreads() throws Exception {
        Executor executor = new MailExecutorConfig().mailExecutor(true);
        assertThat(executor).isInstanceOf(ThreadPoolTaskExecutor.class);
        var pool = (ThreadPoolTaskExecutor) executor;
        assertThat(pool.getMaxPoolSize()).isEqualTo(4);
        assertThat(pool.getQueueCapacity()).isEqualTo(500);
        String thread = CompletableFuture.supplyAsync(() -> Thread.currentThread().getName(), executor)
                .get(5, TimeUnit.SECONDS);
        assertThat(thread).startsWith("mail-");
        pool.shutdown();
    }

    @Test
    void synchronousExecutorWhenAsyncIsDisabled() {
        assertThat(new MailExecutorConfig().mailExecutor(false)).isInstanceOf(SyncTaskExecutor.class);
    }

    @Test
    void dispatcherDeliversOnTheMailExecutor() throws Exception {
        var method = Class.forName("com.nexusops.notifications.internal.MailDispatcher")
                .getDeclaredMethod("on", MailRequested.class);
        var async = method.getAnnotation(org.springframework.scheduling.annotation.Async.class);
        assertThat(async).isNotNull();
        assertThat(async.value()).isEqualTo("mailExecutor");
    }
}
```

`backend/src/test/java/com/nexusops/shared/db/AfterCommitIT.java`:
```java
package com.nexusops.shared.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexusops.support.IntegrationTestSupport;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

class AfterCommitIT extends IntegrationTestSupport {

    @Autowired TransactionTemplate tx;

    @Test
    void runsOnlyAfterCommit() {
        var runs = new AtomicInteger();
        tx.executeWithoutResult(s -> {
            AfterCommit.run(runs::incrementAndGet);
            assertThat(runs).hasValue(0);
        });
        assertThat(runs).hasValue(1);
    }

    @Test
    void neverRunsOnRollback() {
        var runs = new AtomicInteger();
        tx.executeWithoutResult(s -> {
            AfterCommit.run(runs::incrementAndGet);
            s.setRollbackOnly();
        });
        assertThat(runs).hasValue(0);
    }

    @Test
    void runsImmediatelyWithoutATransaction() {
        var runs = new AtomicInteger();
        AfterCommit.run(runs::incrementAndGet);
        assertThat(runs).hasValue(1);
    }
}
```

`backend/src/test/java/com/nexusops/ClientIpIT.java`:
```java
package com.nexusops;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

/** Runs against the real embedded Tomcat (not MockMvc) so the RemoteIpValve is exercised. */
class ClientIpIT extends IntegrationTestSupport {

    @Value("${local.server.port}")
    int port;

    @Test
    void clientIpComesFromXForwardedForSentByAnInternalProxy() throws Exception {
        String workspace = "ip-probe-" + System.nanoTime();
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .header("X-Forwarded-For", "203.0.113.9")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"workspace":"%s","email":"a@b.test","password":"whatever-123456"}""".formatted(workspace)))
                .build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);

        String ip = OwnerJdbc.superuser().queryForObject(
                "select ip from audit_events where action = 'LoginFailed' and metadata->>'workspace' = ?",
                String.class, workspace);
        assertThat(ip).isEqualTo("203.0.113.9");
    }
}
```

In `ProdProfileRequiresSecretsTest`, add:
```java
    @Test
    void prodProfileFailsFastWithoutAllowedOrigins() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(NexusOpsApplication.class)
                        .profiles("prod")
                        .run("--spring.main.web-application-type=none",
                                "--spring.datasource.password=x", "--spring.flyway.password=y"))
                .hasStackTraceContaining("Missing required secret")
                .hasStackTraceContaining("ALLOWED_ORIGINS");
    }
```

Run: `cd backend && ./gradlew test --tests '*MailExecutorConfigTest' --tests '*AfterCommitIT' --tests '*ClientIpIT' --tests '*ProdProfileRequiresSecretsTest'`
Expected:
- compilation FAILS (`MailExecutorConfig` and `AfterCommit` are missing);
- after stubbing compilation, `ClientIpIT` FAILS because the stored IP is `127.0.0.1`;
- the prod test FAILS because nothing requires `ALLOWED_ORIGINS`.

- [ ] **Step 2: Implement**

`backend/src/main/java/com/nexusops/notifications/internal/MailExecutorConfig.java`:
```java
package com.nexusops.notifications.internal;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Mail is delivered off the request thread: SMTP latency never holds a DB connection or reveals, by
 * timing, whether a public endpoint (e.g. resend-verification) actually sent mail.
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
public class MailExecutorConfig {

    @Bean(name = "mailExecutor")
    public Executor mailExecutor(@Value("${nexusops.mail.async:true}") boolean async) {
        if (!async) {
            return new SyncTaskExecutor();
        }
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("mail-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
```

In `MailDispatcher`, annotate `on(MailRequested)` with `@org.springframework.scheduling.annotation.Async("mailExecutor")`. Keep `@TransactionalEventListener(phase = AFTER_COMMIT, fallbackExecution = true)`.

`backend/src/main/java/com/nexusops/shared/db/AfterCommit.java`:
```java
package com.nexusops.shared.db;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Defers side effects (cache eviction, notifications) until the surrounding transaction commits. */
public final class AfterCommit {

    private AfterCommit() {}

    public static void run(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
```

Configuration:
- `application.yml`:
  - Under `server:`, add `forward-headers-strategy: native`.
  - Under `nexusops.mail:`, add `async: true`.
- `application-test.yml`: add `nexusops.mail.async: false`, merging into any existing `nexusops:` key.
- `application-prod.yml`: add
  ```yaml
  nexusops:
    security:
      allowed-origins: ${ALLOWED_ORIGINS}
  ```
- `RequiredSecretsVerifier`: add the entry `REQUIRED.put("nexusops.security.allowed-origins", "ALLOWED_ORIGINS");` after the two password entries. In non-prod profiles the base default resolves, so nothing changes there.

In `frontend/nginx.conf`, in the `/api/` location, replace the `X-Forwarded-For` line with:
```nginx
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header X-Real-IP $remote_addr;
```
nginx is the edge, so it **overwrites** any client-supplied `X-Forwarded-For` instead of appending to it.

- [ ] **Step 3: Run the tests and the full build**

Run the Step 1 command again, then `./gradlew build`
Expected: PASS (3 + 3 + 1 + 2 tests), then a green build. `MailDispatcherIT` and `SignupIT` stay green because the test profile delivers synchronously.

- [ ] **Step 4: Commit**

```bash
cd /Users/user/Desktop/nexusops && git add backend frontend/nginx.conf && git commit -m "feat: async mail executor, trusted-proxy client IP, AfterCommit helper, prod ALLOWED_ORIGINS fail-fast"
```

---

### Task 3: Rate limiting — Redis token bucket, public-route and API limits, 429/503 semantics

**Files:**
- Create in `backend/src/main/java/com/nexusops/shared/ratelimit/`:
  - `RateLimitRule.java`, `RateLimitProperties.java`, `RateLimitKeys.java`;
  - `RedisRateLimiter.java`, `RateLimiterUnavailableException.java`;
  - `RateLimitExceeded.java`, `RateLimits.java`.
- Modify:
  - `shared/web/ApiProblem.java` (add `serviceUnavailable`);
  - `shared/web/GlobalExceptionHandler.java` (`Retry-After` header);
  - `identity/web/AuthController.java` (apply the public limits);
  - `identity/security/PrincipalFilter.java` (API limit);
  - `application.yml`, `application-test.yml`.
- Test: `shared/ratelimit/RedisRateLimiterIT.java`, `shared/ratelimit/RateLimitsTest.java`, `RateLimitIT.java`

**Interfaces:**
- Produces `record RateLimitRule(int capacity, Duration window)` and `record RateLimitProperties(Map<String, RateLimitRule> rules)`, bound from prefix `nexusops.rate-limits`.
- Produces `RedisRateLimiter.tryConsume(String key, RateLimitRule rule): RedisRateLimiter.Decision(boolean allowed, long retryAfterSeconds)`. It throws `RateLimiterUnavailableException` when Redis fails.
- Produces `RateLimitKeys` (the only builder of rate-limit keys):
  - `ip(String rule, String ip)` → `rl:ip:{ip}:{rule}`;
  - `workspace(String rule, String rawWorkspace)` → `rl:ws:{sha256(trim+lowercase)}:{rule}`;
  - `api(UUID tenantId, UUID userId)` → `tenant:{t}:user:{u}:rl:api`.
- Produces `RateLimits`, the public API used by Tasks 7 and later:
  - `checkPublic(String rule, String clientIp)` throws `RateLimitExceeded` (429), or `ApiProblem` 503 if Redis is unavailable;
  - `checkLoginWorkspace(String rawWorkspace)`, the same with rule `login`;
  - `apiRetryAfter(UUID tenantId, UUID userId): OptionalLong`, where empty means allowed (including when Redis is down).
- Produces the rule names: `login`, `signup`, `verify-email`, `resend-verification`, `invitation-accept`, `invitation-preview`, `refresh`, `api`.

- [ ] **Step 1: Configuration**

Add to `application.yml` under `nexusops:`:
```yaml
  rate-limits:
    rules:
      login: { capacity: 10, window: 1m }
      signup: { capacity: 5, window: 1m }
      verify-email: { capacity: 5, window: 1m }
      resend-verification: { capacity: 5, window: 1m }
      invitation-accept: { capacity: 5, window: 1m }
      invitation-preview: { capacity: 20, window: 1m }
      refresh: { capacity: 30, window: 1m }
      api: { capacity: 300, window: 1m }
```
Add to `application-test.yml` under `nexusops:`. The integration suite logs in and signs up hundreds of times from 127.0.0.1; `RateLimitIT` lowers the limits it tests.
```yaml
  rate-limits:
    rules:
      login: { capacity: 100000, window: 1m }
      signup: { capacity: 100000, window: 1m }
      verify-email: { capacity: 100000, window: 1m }
      resend-verification: { capacity: 100000, window: 1m }
      invitation-accept: { capacity: 100000, window: 1m }
      invitation-preview: { capacity: 100000, window: 1m }
      refresh: { capacity: 100000, window: 1m }
      api: { capacity: 100000, window: 1m }
```

- [ ] **Step 2: Write the failing tests**

`backend/src/test/java/com/nexusops/shared/ratelimit/RedisRateLimiterIT.java`:
```java
package com.nexusops.shared.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexusops.support.IntegrationTestSupport;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RedisRateLimiterIT extends IntegrationTestSupport {

    @Autowired RedisRateLimiter limiter;

    private static String key() {
        return "rl:test:" + UUID.randomUUID();
    }

    @Test
    void allowsUpToCapacityThenDeniesWithRetryAfter() {
        var rule = new RateLimitRule(3, Duration.ofMinutes(1));
        String key = key();
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryConsume(key, rule).allowed()).isTrue();
        }
        var denied = limiter.tryConsume(key, rule);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isBetween(1L, 60L);
    }

    @Test
    void bucketsAreIndependentPerKey() {
        var rule = new RateLimitRule(1, Duration.ofMinutes(1));
        String a = key();
        assertThat(limiter.tryConsume(a, rule).allowed()).isTrue();
        assertThat(limiter.tryConsume(a, rule).allowed()).isFalse();
        assertThat(limiter.tryConsume(key(), rule).allowed()).isTrue();
    }

    @Test
    void tokensRefillOverTheWindow() throws InterruptedException {
        var rule = new RateLimitRule(2, Duration.ofMillis(400));
        String key = key();
        limiter.tryConsume(key, rule);
        limiter.tryConsume(key, rule);
        assertThat(limiter.tryConsume(key, rule).allowed()).isFalse();
        Thread.sleep(450);
        assertThat(limiter.tryConsume(key, rule).allowed()).isTrue();
    }
}
```

`backend/src/test/java/com/nexusops/shared/ratelimit/RateLimitsTest.java`:
```java
package com.nexusops.shared.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nexusops.shared.web.ApiProblem;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RateLimitsTest {

    final RedisRateLimiter limiter = mock(RedisRateLimiter.class);
    final RateLimitRule rule = new RateLimitRule(10, Duration.ofMinutes(1));
    final RateLimits limits = new RateLimits(limiter,
            new RateLimitProperties(Map.of("login", rule, "api", rule, "signup", rule)));

    @Test
    void exceededPublicLimitIs429WithRetryAfter() {
        when(limiter.tryConsume(anyString(), any())).thenReturn(new RedisRateLimiter.Decision(false, 17));
        assertThatThrownBy(() -> limits.checkPublic("signup", "10.0.0.1"))
                .isInstanceOfSatisfying(RateLimitExceeded.class, e -> {
                    assertThat(e.status().value()).isEqualTo(429);
                    assertThat(e.retryAfterSeconds()).isEqualTo(17);
                    assertThat(e.getMessage()).isEqualTo("Too many requests. Try again in 17 seconds.");
                });
    }

    @Test
    void publicRoutesFailClosedWhenRedisIsDown() {
        when(limiter.tryConsume(anyString(), any())).thenThrow(new RateLimiterUnavailableException(new RuntimeException()));
        assertThatThrownBy(() -> limits.checkLoginWorkspace("acme"))
                .isInstanceOfSatisfying(ApiProblem.class, e -> assertThat(e.status().value()).isEqualTo(503));
    }

    @Test
    void apiFailsOpenWhenRedisIsDown() {
        when(limiter.tryConsume(anyString(), any())).thenThrow(new RateLimiterUnavailableException(new RuntimeException()));
        assertThat(limits.apiRetryAfter(UUID.randomUUID(), UUID.randomUUID())).isEmpty();
    }

    @Test
    void unknownRuleIsAProgrammingError() {
        assertThatThrownBy(() -> limits.checkPublic("nope", "10.0.0.1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void workspaceKeysAreNormalizedAndHashed() {
        assertThat(RateLimitKeys.workspace("login", " ACME ")).isEqualTo(RateLimitKeys.workspace("login", "acme"))
                .startsWith("rl:ws:").endsWith(":login").doesNotContain("acme");
        assertThat(RateLimitKeys.ip("login", "bad ip\n")).isEqualTo("rl:ip:unknown:login");
    }
}
```

`backend/src/test/java/com/nexusops/RateLimitIT.java`:
```java
package com.nexusops;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestTenants;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "nexusops.rate-limits.rules.login.capacity=3",
        "nexusops.rate-limits.rules.verify-email.capacity=2",
        "nexusops.rate-limits.rules.api.capacity=5"
})
class RateLimitIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;

    private static MockHttpServletRequestBuilder from(MockHttpServletRequestBuilder request, String ip) {
        return request.with(r -> {
            r.setRemoteAddr(ip);
            return r;
        });
    }

    private static String uniqueIp() {
        return "10.%d.%d.%d".formatted((int) (Math.random() * 250), (int) (Math.random() * 250), (int) (Math.random() * 250));
    }

    private ResultActions login(String ip, String workspace) throws Exception {
        return mvc.perform(from(post("/api/v1/auth/login"), ip).contentType(MediaType.APPLICATION_JSON).content("""
                {"workspace":"%s","email":"a@b.test","password":"whatever-123456"}""".formatted(workspace)));
    }

    @Test
    void loginIsLimitedPerIp() throws Exception {
        String ip = uniqueIp();
        for (int i = 0; i < 3; i++) {
            login(ip, "ws-" + UUID.randomUUID().toString().substring(0, 8)).andExpect(status().isUnauthorized());
        }
        login(ip, "ws-" + UUID.randomUUID().toString().substring(0, 8))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.title").value("Too Many Requests"))
                .andExpect(jsonPath("$.requestId").exists());
    }

    @Test
    void loginIsLimitedPerWorkspaceAcrossIps() throws Exception {
        String workspace = "target-" + UUID.randomUUID().toString().substring(0, 8);
        for (int i = 0; i < 3; i++) {
            login(uniqueIp(), workspace).andExpect(status().isUnauthorized());
        }
        login(uniqueIp(), workspace).andExpect(status().isTooManyRequests());
    }

    @Test
    void otherPublicRoutesAreLimited() throws Exception {
        String ip = uniqueIp();
        for (int i = 0; i < 2; i++) {
            mvc.perform(from(post("/api/v1/auth/verify-email"), ip).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"token\":\"garbage\"}")).andExpect(status().isBadRequest());
        }
        mvc.perform(from(post("/api/v1/auth/verify-email"), ip).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"garbage\"}")).andExpect(status().isTooManyRequests());
    }

    @Test
    void authenticatedApiIsLimitedPerUser() throws Exception {
        var ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("rl-api"));
        var session = TestTenants.login(mvc, ws, uniqueIp());
        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + session.accessToken()))
                    .andExpect(status().isOk());
        }
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }
}
```
Redis buckets persist across test classes, so this class must never depend on the shared 127.0.0.1 login bucket. Add this overload to `support/TestTenants.java`. Have the existing `login(MockMvc, Workspace)` delegate to it with `"127.0.0.1"`; that keeps every other caller unchanged.
```java
    public static Session login(MockMvc mvc, Workspace workspace, String remoteAddr) throws Exception {
        var result = mvc.perform(post("/api/v1/auth/login").with(r -> { r.setRemoteAddr(remoteAddr); return r; })
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"workspace":"%s","email":"%s","password":"%s"}""".formatted(workspace.slug(), workspace.email(), workspace.password())))
                .andExpect(status().isOk())
                .andReturn();
        return new Session(accessTokenOf(result), refreshCookieOf(result));
    }
```
In `authenticatedApiIsLimitedPerUser`, use `TestTenants.login(mvc, ws, uniqueIp())`.

Run: `cd backend && ./gradlew test --tests '*RedisRateLimiterIT' --tests '*RateLimitsTest' --tests '*RateLimitIT'`
Expected: compilation FAILS (the `shared.ratelimit` types are missing).

- [ ] **Step 3: Implement the limiter**

`backend/src/main/java/com/nexusops/shared/ratelimit/RateLimitRule.java`:
```java
package com.nexusops.shared.ratelimit;

import java.time.Duration;

/** {@code capacity} requests per {@code window}, refilled continuously (token bucket). */
public record RateLimitRule(int capacity, Duration window) {}
```

`backend/src/main/java/com/nexusops/shared/ratelimit/RateLimitProperties.java`:
```java
package com.nexusops.shared.ratelimit;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("nexusops.rate-limits")
public record RateLimitProperties(Map<String, RateLimitRule> rules) {}
```

`backend/src/main/java/com/nexusops/shared/ratelimit/RateLimitKeys.java`:
```java
package com.nexusops.shared.ratelimit;

import com.nexusops.shared.cache.TenantKeys;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** The only builder of rate-limit keys. Pre-auth keys cannot be tenant-prefixed: no tenant is known yet. */
public final class RateLimitKeys {

    private static final Pattern IP = Pattern.compile("[0-9A-Fa-f:.]{2,45}");

    private RateLimitKeys() {}

    public static String ip(String rule, String ip) {
        String safe = ip != null && IP.matcher(ip).matches() ? ip : "unknown";
        return "rl:ip:" + safe + ":" + rule;
    }

    /** Hashed so arbitrary user input never becomes a raw key (and key length stays bounded). */
    public static String workspace(String rule, String rawWorkspace) {
        String normalized = rawWorkspace == null ? "" : rawWorkspace.strip().toLowerCase(Locale.ROOT);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            return "rl:ws:" + HexFormat.of().formatHex(digest) + ":" + rule;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String api(UUID tenantId, UUID userId) {
        return TenantKeys.key(tenantId, "user", userId.toString(), "rl", "api");
    }
}
```

`backend/src/main/java/com/nexusops/shared/ratelimit/RateLimiterUnavailableException.java`:
```java
package com.nexusops.shared.ratelimit;

public class RateLimiterUnavailableException extends RuntimeException {

    public RateLimiterUnavailableException(Throwable cause) {
        super("Rate limiter unavailable", cause);
    }
}
```

`backend/src/main/java/com/nexusops/shared/ratelimit/RedisRateLimiter.java`:
```java
package com.nexusops.shared.ratelimit;

import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/** Atomic token bucket in Redis (spec §9). Uses Redis server time, so app-node clock skew is irrelevant. */
@Component
public class RedisRateLimiter {

    public record Decision(boolean allowed, long retryAfterSeconds) {}

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> TOKEN_BUCKET = new DefaultRedisScript<>("""
            local capacity = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local t = redis.call('TIME')
            local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            local rate = capacity / window
            local data = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
            local tokens = tonumber(data[1])
            local ts = tonumber(data[2])
            if tokens == nil or ts == nil then
              tokens = capacity
              ts = now
            end
            tokens = math.min(capacity, tokens + math.max(0, now - ts) * rate)
            local allowed = 0
            local retry = 0
            if tokens >= 1 then
              tokens = tokens - 1
              allowed = 1
            else
              retry = math.ceil(((1 - tokens) / rate) / 1000)
              if retry < 1 then retry = 1 end
            end
            redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(now))
            redis.call('PEXPIRE', KEYS[1], window)
            return {allowed, retry}
            """, List.class);

    private final StringRedisTemplate redis;

    RedisRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Decision tryConsume(String key, RateLimitRule rule) {
        try {
            List<?> result = redis.execute(TOKEN_BUCKET, List.of(key),
                    String.valueOf(rule.capacity()), String.valueOf(rule.window().toMillis()));
            long allowed = ((Number) result.get(0)).longValue();
            long retry = ((Number) result.get(1)).longValue();
            return new Decision(allowed == 1L, retry);
        } catch (RuntimeException e) {
            throw new RateLimiterUnavailableException(e);
        }
    }
}
```

`backend/src/main/java/com/nexusops/shared/ratelimit/RateLimitExceeded.java`:
```java
package com.nexusops.shared.ratelimit;

import com.nexusops.shared.web.ApiProblem;
import java.util.List;
import org.springframework.http.HttpStatus;

public class RateLimitExceeded extends ApiProblem {

    private final long retryAfterSeconds;

    public RateLimitExceeded(long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Try again in " + retryAfterSeconds + " seconds.", List.of());
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
```

`backend/src/main/java/com/nexusops/shared/ratelimit/RateLimits.java`:
```java
package com.nexusops.shared.ratelimit;

import com.nexusops.shared.web.ApiProblem;
import java.util.OptionalLong;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/** Public auth routes fail CLOSED when Redis is down; the authenticated API fails OPEN (spec §9). */
@Service
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimits {

    private static final Logger log = LoggerFactory.getLogger(RateLimits.class);
    static final String UNAVAILABLE = "Service temporarily unavailable. Please try again shortly.";

    private final RedisRateLimiter limiter;
    private final RateLimitProperties properties;

    public RateLimits(RedisRateLimiter limiter, RateLimitProperties properties) {
        this.limiter = limiter;
        this.properties = properties;
    }

    public void checkPublic(String rule, String clientIp) {
        enforce(RateLimitKeys.ip(rule, clientIp), rule(rule));
    }

    public void checkLoginWorkspace(String rawWorkspace) {
        enforce(RateLimitKeys.workspace("login", rawWorkspace), rule("login"));
    }

    public OptionalLong apiRetryAfter(UUID tenantId, UUID userId) {
        try {
            var decision = limiter.tryConsume(RateLimitKeys.api(tenantId, userId), rule("api"));
            return decision.allowed() ? OptionalLong.empty() : OptionalLong.of(decision.retryAfterSeconds());
        } catch (RateLimiterUnavailableException e) {
            log.warn("Rate limiter unavailable; allowing authenticated API request", e);
            return OptionalLong.empty();
        }
    }

    private void enforce(String key, RateLimitRule rule) {
        RedisRateLimiter.Decision decision;
        try {
            decision = limiter.tryConsume(key, rule);
        } catch (RateLimiterUnavailableException e) {
            log.warn("Rate limiter unavailable; rejecting public auth request", e);
            throw ApiProblem.serviceUnavailable(UNAVAILABLE);
        }
        if (!decision.allowed()) {
            throw new RateLimitExceeded(decision.retryAfterSeconds());
        }
    }

    private RateLimitRule rule(String name) {
        RateLimitRule rule = properties.rules() == null ? null : properties.rules().get(name);
        if (rule == null) {
            throw new IllegalArgumentException("No rate-limit rule configured: " + name);
        }
        return rule;
    }
}
```

In `ApiProblem`, add:
```java
    public static ApiProblem serviceUnavailable(String detail) {
        return new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE, detail, List.of());
    }
```
In `GlobalExceptionHandler.handleApiProblem`, replace the final `return` with:
```java
        var response = ResponseEntity.status(ex.status());
        if (ex instanceof com.nexusops.shared.ratelimit.RateLimitExceeded limited) {
            response.header(org.springframework.http.HttpHeaders.RETRY_AFTER, String.valueOf(limited.retryAfterSeconds()));
        }
        return response.body(problem);
```

- [ ] **Step 4: Apply the limits**

In `AuthController`, inject `com.nexusops.shared.ratelimit.RateLimits rateLimits` through the constructor, add an `HttpServletRequest http` parameter where it is missing, and call the following first in each handler:
- signup: `rateLimits.checkPublic("signup", http.getRemoteAddr());`
- verify-email: `rateLimits.checkPublic("verify-email", http.getRemoteAddr());`
- resend-verification: `rateLimits.checkPublic("resend-verification", http.getRemoteAddr());`
- login: `rateLimits.checkPublic("login", http.getRemoteAddr()); rateLimits.checkLoginWorkspace(request.workspace());`
- refresh: `rateLimits.checkPublic("refresh", http.getRemoteAddr());` (after `originGuard.check`)

In `PrincipalFilter`, inject `com.nexusops.shared.ratelimit.RateLimits rateLimits`. Directly after the tenant-status check, and before building authorities, add:
```java
            var retryAfter = rateLimits.apiRetryAfter(tenantId, userId);
            if (retryAfter.isPresent()) {
                response.setHeader(org.springframework.http.HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter.getAsLong()));
                reject(response, HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests",
                        "Too many requests. Try again in " + retryAfter.getAsLong() + " seconds.");
                return;
            }
```

- [ ] **Step 5: Run the tests and the full build**

Run the Step 2 command again, then `./gradlew build`
Expected:
- `RedisRateLimiterIT` passes 3;
- `RateLimitsTest` passes 5;
- `RateLimitIT` passes 4;
- the whole build is green. Every earlier IT runs with the test profile's high limits.

`ModularityTest` must pass: `identity` uses `com.nexusops.shared.ratelimit.RateLimits`, and `shared` is OPEN.

- [ ] **Step 6: Commit**

```bash
cd /Users/user/Desktop/nexusops && git add backend && git commit -m "feat: Redis token-bucket rate limiting for public auth routes (fail closed) and the authenticated API (fail open)"
```

---

### Task 4: Plan limits and module toggles

**Files:**
- Create:
  - `shared/db/TenantLocks.java`;
  - `tenancy/PlanLimits.java`, `tenancy/ModuleState.java`;
  - `tenancy/domain/ModuleDefinition.java`, `tenancy/domain/ModuleDefinitionRepository.java`;
  - `tenancy/web/TenantModulesController.java`.
- Modify:
  - `tenancy/domain/TenantModule.java` (accessors, `setEnabled`);
  - `tenancy/domain/TenantModuleRepository.java`, `tenancy/domain/TenantRepository.java` (limits projection);
  - `tenancy/TenantDirectory.java`.
- Test: `tenancy/TenantModulesIT.java`, `shared/db/TenantLocksIT.java`

**Interfaces:**
- Produces `TenantLocks.lock(String scope)`. It needs an active transaction (`MANDATORY`) and takes `pg_advisory_xact_lock(hashtext(scope || ':' || tenantId))` for the current tenant. Task 8 uses `lock("owners")`.
- Produces `record PlanLimits(Integer maxUsers, Integer maxModules)`, where `null` means unlimited.
- Produces `record ModuleState(String code, String name, boolean enabled)`.
- Produces `TenantDirectory`:
  - `currentLimits(): PlanLimits`;
  - `modules(): List<ModuleState>`, all catalog modules ordered by code;
  - `setModuleEnabled(String code, boolean enabled): ModuleState`, which publishes `ModulesChanged` and writes `ModuleEnabled` or `ModuleDisabled` audit entries, but only when the state actually changes.
- Produces:
  - `GET /api/v1/tenant/modules` (`tenant.settings.read`) → `[ModuleState]`;
  - `PUT /api/v1/tenant/modules/{code}` (`tenant.modules.manage`), body `{"enabled":true|false}` → `ModuleState`;
  - an unknown code → 404 "Module not found.";
  - over the limit → 409 "Your plan allows {n} modules. Upgrade to enable more."

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/com/nexusops/shared/db/TenantLocksIT.java`:
```java
package com.nexusops.shared.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexusops.shared.TenantContext;
import com.nexusops.support.IntegrationTestSupport;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

class TenantLocksIT extends IntegrationTestSupport {

    @Autowired TenantLocks locks;
    @Autowired TransactionTemplate tx;

    @Test
    void requiresATransaction() {
        UUID tenant = UUID.randomUUID();
        assertThatThrownBy(() -> TenantContext.runAs(tenant, () -> locks.lock("owners")))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void serializesTransactionsOfTheSameTenantAndScope() throws Exception {
        UUID tenant = UUID.randomUUID();
        var firstHolds = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var secondAcquiredAt = new AtomicLong();
        var firstReleasedAt = new AtomicLong();
        var pool = Executors.newFixedThreadPool(2);
        pool.submit(() -> TenantContext.runAs(tenant, () -> tx.executeWithoutResult(s -> {
            locks.lock("owners");
            firstHolds.countDown();
            await(releaseFirst);
            firstReleasedAt.set(System.nanoTime());
        })));
        firstHolds.await(5, TimeUnit.SECONDS);
        var second = pool.submit(() -> TenantContext.runAs(tenant, () -> tx.executeWithoutResult(s -> {
            locks.lock("owners");
            secondAcquiredAt.set(System.nanoTime());
        })));
        Thread.sleep(200);
        assertThat(secondAcquiredAt.get()).as("second must wait").isZero();
        releaseFirst.countDown();
        second.get(5, TimeUnit.SECONDS);
        assertThat(secondAcquiredAt.get()).isGreaterThan(firstReleasedAt.get());
        pool.shutdown();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

`backend/src/test/java/com/nexusops/tenancy/TenantModulesIT.java`:
```java
package com.nexusops.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Session;
import com.nexusops.support.TestTenants.Workspace;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@AutoConfigureMockMvc
class TenantModulesIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;

    Workspace ws;
    Session owner;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("mods"));
        owner = TestTenants.login(mvc, ws);
    }

    private ResultActions setModule(String code, boolean enabled) throws Exception {
        return mvc.perform(put("/api/v1/tenant/modules/" + code).header("Authorization", "Bearer " + owner.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":" + enabled + "}"));
    }

    private ResultActions me() throws Exception {
        return mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + owner.accessToken()));
    }

    @Test
    void listsTheCatalogAllDisabledForANewWorkspace() throws Exception {
        mvc.perform(get("/api/v1/tenant/modules").header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code", Matchers.contains("CRM", "HELPDESK", "HRMS", "INVENTORY")))
                .andExpect(jsonPath("$[*].enabled", Matchers.everyItem(Matchers.is(false))));
    }

    @Test
    void enablingAModuleTakesEffectImmediatelyForPermissions() throws Exception {
        me().andExpect(jsonPath("$.permissions", Matchers.not(Matchers.hasItem("crm.customer.read"))));
        setModule("CRM", true).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));
        me().andExpect(jsonPath("$.modules", Matchers.contains("CRM")))
                .andExpect(jsonPath("$.permissions", Matchers.hasItem("crm.customer.read")));
        setModule("CRM", false).andExpect(status().isOk());
        me().andExpect(jsonPath("$.permissions", Matchers.not(Matchers.hasItem("crm.customer.read"))));
    }

    @Test
    void freePlanAllowsTwoModules() throws Exception {
        setModule("CRM", true).andExpect(status().isOk());
        setModule("HELPDESK", true).andExpect(status().isOk());
        setModule("HRMS", true).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Your plan allows 2 modules. Upgrade to enable more."));
        setModule("CRM", false).andExpect(status().isOk());
        setModule("HRMS", true).andExpect(status().isOk());
    }

    @Test
    void unlimitedPlansHaveNoModuleCap() throws Exception {
        OwnerJdbc.jdbc().update("update tenants set plan_code = 'ENTERPRISE' where id = ?", ws.tenantId());
        for (String code : new String[] {"CRM", "HELPDESK", "HRMS", "INVENTORY"}) {
            setModule(code, true).andExpect(status().isOk());
        }
    }

    @Test
    void unknownModuleIs404AndRepeatedTogglesAuditOnce() throws Exception {
        setModule("NOPE", true).andExpect(status().isNotFound());
        setModule("CRM", true).andExpect(status().isOk());
        setModule("CRM", true).andExpect(status().isOk());
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select count(*) from audit_events where action = 'ModuleEnabled'", Long.class)).isOne();
    }
}
```

Run: `cd backend && ./gradlew test --tests '*TenantLocksIT' --tests '*TenantModulesIT'`
Expected: compilation FAILS.

- [ ] **Step 2: Implement**

`backend/src/main/java/com/nexusops/shared/db/TenantLocks.java`:
```java
package com.nexusops.shared.db;

import com.nexusops.shared.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-tenant, per-scope transaction-level advisory locks: serialize check-then-act invariants that
 * span rows (plan limits, "at least one owner") without locking whole tables.
 */
@Component
public class TenantLocks {

    private final JdbcTemplate jdbc;

    TenantLocks(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(String scope) {
        String tenant = TenantContext.requireTenantId().toString();
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtext(? || ':' || ?))::text", String.class, scope, tenant);
    }
}
```

`backend/src/main/java/com/nexusops/tenancy/PlanLimits.java`:
```java
package com.nexusops.tenancy;

/** Limits of the tenant's plan; {@code null} means unlimited. */
public record PlanLimits(Integer maxUsers, Integer maxModules) {}
```

`backend/src/main/java/com/nexusops/tenancy/ModuleState.java`:
```java
package com.nexusops.tenancy;

public record ModuleState(String code, String name, boolean enabled) {}
```

`backend/src/main/java/com/nexusops/tenancy/domain/ModuleDefinition.java`:
```java
package com.nexusops.tenancy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/** Global, read-only module catalog (V1__tenancy.sql). */
@Entity
@Immutable
@Table(name = "modules")
public class ModuleDefinition {

    @Id
    private String code;

    @Column(nullable = false)
    private String name;

    protected ModuleDefinition() {}

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }
}
```

`backend/src/main/java/com/nexusops/tenancy/domain/ModuleDefinitionRepository.java`:
```java
package com.nexusops.tenancy.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModuleDefinitionRepository extends JpaRepository<ModuleDefinition, String> {

    List<ModuleDefinition> findAllByOrderByCodeAsc();
}
```

Add to `TenantModule`:
```java
    public String getModuleCode() {
        return moduleCode;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
```

Add to `TenantModuleRepository`:
```java
    java.util.Optional<TenantModule> findByModuleCode(String moduleCode);

    long countByEnabledTrue();
```

Add to `TenantRepository`:
```java
    interface PlanLimitsRow {
        String getMaxUsers();

        String getMaxModules();
    }

    @org.springframework.data.jpa.repository.Query(nativeQuery = true, value = """
            select p.limits->>'maxUsers' as maxUsers, p.limits->>'maxModules' as maxModules
            from plans p join tenants t on t.plan_code = p.code
            where t.id = :tenantId""")
    PlanLimitsRow findPlanLimits(@org.springframework.data.repository.query.Param("tenantId") UUID tenantId);
```

In `TenantDirectory`, extend the constructor with `ModuleDefinitionRepository catalog`, `org.springframework.context.ApplicationEventPublisher events` and `com.nexusops.shared.db.TenantLocks locks`, and add:
```java
    @Transactional(readOnly = true)
    public PlanLimits currentLimits() {
        var row = tenants.findPlanLimits(TenantContext.requireTenantId());
        return new PlanLimits(parse(row.getMaxUsers()), parse(row.getMaxModules()));
    }

    @Transactional(readOnly = true)
    public List<ModuleState> modules() {
        TenantContext.requireTenantId();
        var enabled = new java.util.HashSet<>(modules.findEnabledCodes());
        return catalog.findAllByOrderByCodeAsc().stream()
                .map(m -> new ModuleState(m.getCode(), m.getName(), enabled.contains(m.getCode())))
                .toList();
    }

    @Transactional
    public ModuleState setModuleEnabled(String code, boolean enabled) {
        UUID tenantId = TenantContext.requireTenantId();
        var definition = catalog.findById(code).orElseThrow(() -> ApiProblem.notFound("Module not found."));
        locks.lock("modules");
        var module = modules.findByModuleCode(code)
                .orElseGet(() -> modules.save(new com.nexusops.tenancy.domain.TenantModule(Ids.newId(), code, false)));
        if (module.isEnabled() == enabled) {
            return new ModuleState(code, definition.getName(), enabled);
        }
        if (enabled) {
            Integer max = currentLimits().maxModules();
            if (max != null && modules.countByEnabledTrue() >= max) {
                throw ApiProblem.conflict("Your plan allows " + max + " modules. Upgrade to enable more.");
            }
        }
        module.setEnabled(enabled);
        modules.flush();
        audit.record(AuditEntry.of(enabled ? "ModuleEnabled" : "ModuleDisabled", "Module", code));
        events.publishEvent(new ModulesChanged(tenantId));
        return new ModuleState(code, definition.getName(), enabled);
    }

    private static Integer parse(String value) {
        return value == null || value.isBlank() ? null : Integer.valueOf(value);
    }
```
Add the imports `com.nexusops.shared.Ids` and `com.nexusops.shared.db.TenantLocks`.

`backend/src/main/java/com/nexusops/tenancy/web/TenantModulesController.java`:
```java
package com.nexusops.tenancy.web;

import com.nexusops.tenancy.ModuleState;
import com.nexusops.tenancy.TenantDirectory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenant/modules")
class TenantModulesController {

    record ToggleRequest(@NotNull Boolean enabled) {}

    private final TenantDirectory directory;

    TenantModulesController(TenantDirectory directory) {
        this.directory = directory;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('tenant.settings.read')")
    List<ModuleState> list() {
        return directory.modules();
    }

    @PutMapping("/{code}")
    @PreAuthorize("hasAuthority('tenant.modules.manage')")
    ModuleState toggle(@PathVariable String code, @Valid @RequestBody ToggleRequest request) {
        return directory.setModuleEnabled(code, request.enabled());
    }
}
```

- [ ] **Step 3: Run the tests and the build**

Run: `./gradlew test --tests '*TenantLocksIT' --tests '*TenantModulesIT'`, then `./gradlew build`
Expected: PASS (2 + 5), then a green build including `EndpointAuthorizationCoverageTest` and `ModularityTest`. `enablingAModuleTakesEffectImmediatelyForPermissions` proves the `ModulesChanged` → eviction path from Task 1.

- [ ] **Step 4: Commit**

```bash
cd /Users/user/Desktop/nexusops && git add backend && git commit -m "feat(tenancy): module toggles with plan limits, advisory tenant locks, immediate permission effect"
```

---

### Task 5: Permission catalog, role management and the escalation guard

**Files:**
- Create:
  - `shared/security/ActorDetails.java`, `shared/security/CurrentActor.java`;
  - `authorization/PermissionView.java`, `authorization/RoleView.java`, `authorization/CreateRoleCommand.java`;
  - `authorization/web/PermissionController.java`, `authorization/web/RoleController.java`, `authorization/web/RoleDtos.java`.
- Modify:
  - `identity/security/PrincipalState.java` (add `roleIds`, `grantablePermissions`);
  - `identity/security/PrincipalStateCache.java` (`load` fills them);
  - `identity/security/PrincipalFilter.java` (sets `ActorDetails` as authentication details);
  - `authorization/AuthorizationService.java`, `authorization/domain/Role.java`, `authorization/domain/RoleRepository.java`.
- Test: `support/TestMembers.java`, `authorization/RoleManagementIT.java`

**Interfaces:**
- Produces `record ActorDetails(Set<UUID> roleIds, Set<String> grantablePermissions)`, plus `CurrentActor.require(): ActorDetails` (throws `IllegalStateException` without an authenticated actor).
- Produces `PrincipalState(String userStatus, int tokenVersion, String tenantStatus, Set<String> permissions, List<String> modules, Set<UUID> roleIds, Set<String> grantablePermissions)`. Null collections from older cache entries are normalised to empty sets by the compact constructor.
- Produces `AuthorizationService`; Tasks 6–8 use the starred methods:
  - `grantablePermissions(Collection<UUID> roleIds): Set<String>`
  - `permissionCatalog(): List<PermissionView>`
  - `listRoles(): List<RoleView>`, `getRole(UUID): RoleView`
  - `createRole(CreateRoleCommand): RoleView`
  - `updateRole(UUID, String name, String description): RoleView`
  - `replacePermissions(UUID, Set<String>): RoleView`
  - `deleteRole(UUID)`
  - ★ `ownerRoleId(): UUID`
  - ★ `roleNames(Collection<UUID>): Map<UUID,String>`
  - ★ `checkGrantable(Collection<UUID> roleIds)`: unknown role → 400 with field `roleIds`; the owner role without being an owner → 403 owner message; a permission not grantable → 403 escalation message. It uses `CurrentActor`.
  - ★ `requireOwnerActor()`: 403 owner message unless the actor holds `TENANT_OWNER`.
- Produces `record PermissionView(String code, String module, String description, boolean moduleEnabled)` and `record RoleView(UUID id, String name, String description, boolean system, List<String> permissions)`.
- Produces these endpoints:

  | Method & path | Permission | Success |
  |---|---|---|
  | `GET /api/v1/permissions` | `authorization.role.read` | 200 |
  | `GET /api/v1/roles` | `authorization.role.read` | 200 |
  | `POST /api/v1/roles` | `authorization.role.manage` | 201 |
  | `GET /api/v1/roles/{id}` | `authorization.role.read` | 200 |
  | `PATCH /api/v1/roles/{id}` | `authorization.role.manage` | 200 |
  | `DELETE /api/v1/roles/{id}` | `authorization.role.manage` | 204 |
  | `PUT /api/v1/roles/{id}/permissions` | `authorization.role.manage` | 200 |
- Produces the test helper `TestMembers.create(UUID tenantId, Set<UUID> roleIds): TestTenants.Workspace`, which creates a verified ACTIVE member with the given roles and `TestTenants.PASSWORD`. It is a test `@Component`.

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/com/nexusops/support/TestMembers.java`:
```java
package com.nexusops.support;

import com.nexusops.identity.domain.User;
import com.nexusops.identity.domain.UserRepository;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Test-only: adds a verified, active member with chosen roles (before invitations exist in this plan). */
@Component
public class TestMembers {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final TransactionTemplate tx;

    TestMembers(UserRepository users, PasswordEncoder encoder, TransactionTemplate tx) {
        this.users = users;
        this.encoder = encoder;
        this.tx = tx;
    }

    public TestTenants.Workspace create(UUID tenantId, Set<UUID> roleIds) {
        String slug = OwnerJdbc.jdbc().queryForObject("select slug from tenants where id = ?", String.class, tenantId);
        UUID userId = Ids.newId();
        String email = "member-" + userId.toString().substring(24) + "@" + slug + ".test";
        String hash = encoder.encode(TestTenants.PASSWORD);
        TenantContext.runAs(tenantId, () -> tx.executeWithoutResult(s -> {
            User user = User.registerOwner(userId, email, hash, "Mem", "Ber", null);
            user.markEmailVerified(Instant.now());
            users.save(user);
        }));
        for (UUID roleId : roleIds) {
            OwnerJdbc.ownerAs(tenantId).update("insert into user_roles (user_id, role_id) values (?, ?)", userId, roleId);
        }
        return new TestTenants.Workspace(tenantId, slug, email, TestTenants.PASSWORD);
    }
}
```

`backend/src/test/java/com/nexusops/authorization/RoleManagementIT.java`:
```java
package com.nexusops.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestMembers;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Session;
import com.nexusops.support.TestTenants.Workspace;
import java.util.Set;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@AutoConfigureMockMvc
class RoleManagementIT extends IntegrationTestSupport {

    static final String ESCALATION = "You can't grant permissions you don't have.";
    static final String SYSTEM = "System roles can't be changed.";

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Session owner;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("roles"));
        owner = TestTenants.login(mvc, ws);
    }

    private ResultActions as(Session s, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b)
            throws Exception {
        return mvc.perform(b.header("Authorization", "Bearer " + s.accessToken()));
    }

    private ResultActions createRole(Session s, String name, String... permissions) throws Exception {
        String perms = String.join("\",\"", permissions);
        return as(s, post("/api/v1/roles").contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"%s","description":"d","permissions":[%s]}""".formatted(name,
                permissions.length == 0 ? "" : "\"" + perms + "\"")));
    }

    private UUID createdId(ResultActions result) throws Exception {
        return UUID.fromString(JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id"));
    }

    private UUID systemRoleId(String name) {
        return OwnerJdbc.ownerAs(ws.tenantId()).queryForObject("select id from roles where name = ?", UUID.class, name);
    }

    @Test
    void ownerCreatesListsRenamesAndDeletesACustomRole() throws Exception {
        UUID id = createdId(createRole(owner, "Support", "identity.user.read", "tenant.settings.read")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.system").value(false))
                .andExpect(jsonPath("$.permissions", Matchers.contains("identity.user.read", "tenant.settings.read"))));
        as(owner, get("/api/v1/roles")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", Matchers.hasItems("TENANT_OWNER", "TENANT_ADMIN", "Support")));
        as(owner, patch("/api/v1/roles/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Helpdesk\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Helpdesk"));
        as(owner, delete("/api/v1/roles/" + id)).andExpect(status().isNoContent());
        as(owner, get("/api/v1/roles/" + id)).andExpect(status().isNotFound());
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForList("select action from audit_events", String.class))
                .contains("RoleCreated", "RoleUpdated", "RoleDeleted");
    }

    @Test
    void validationAndUniqueness() throws Exception {
        createRole(owner, "Support", "identity.user.read").andExpect(status().isCreated());
        createRole(owner, "support", "identity.user.read").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
        createRole(owner, "tenant_owner", "identity.user.read").andExpect(status().isConflict());
        createRole(owner, "Bogus", "no.such.permission").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("permissions"));
        createRole(owner, " ", "identity.user.read").andExpect(status().isBadRequest());
    }

    @Test
    void systemRolesAreImmutable() throws Exception {
        UUID ownerRole = systemRoleId("TENANT_OWNER");
        as(owner, patch("/api/v1/roles/" + ownerRole).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(SYSTEM));
        as(owner, put("/api/v1/roles/" + ownerRole + "/permissions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"permissions\":[]}")).andExpect(status().isConflict());
        as(owner, delete("/api/v1/roles/" + systemRoleId("TENANT_ADMIN"))).andExpect(status().isConflict());
    }

    @Test
    void assignedRolesCannotBeDeleted() throws Exception {
        UUID id = createdId(createRole(owner, "Assigned", "identity.user.read"));
        members.create(ws.tenantId(), Set.of(id));
        as(owner, delete("/api/v1/roles/" + id)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Remove this role from 1 user(s) first."));
    }

    @Test
    void ownersCanPrepareRolesForModulesThatAreNotEnabled() throws Exception {
        createRole(owner, "Sales", "crm.customer.read").andExpect(status().isCreated());
    }

    @Test
    void membersCannotGrantPermissionsTheyLack() throws Exception {
        UUID managerRole = createdId(createRole(owner, "RoleManager",
                "authorization.role.read", "authorization.role.manage", "identity.user.read"));
        Session manager = TestTenants.login(mvc, members.create(ws.tenantId(), Set.of(managerRole)));
        createRole(manager, "TooStrong", "tenant.settings.update").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(ESCALATION));
        UUID weak = createdId(createRole(manager, "Weak", "identity.user.read").andExpect(status().isCreated()));
        as(manager, put("/api/v1/roles/" + weak + "/permissions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"permissions\":[\"identity.user.read\",\"audit.event.read\"]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void permissionRemovalTakesEffectImmediately() throws Exception {
        UUID viewer = createdId(createRole(owner, "Viewer", "tenant.settings.read"));
        Session member = TestTenants.login(mvc, members.create(ws.tenantId(), Set.of(viewer)));
        as(member, get("/api/v1/tenant")).andExpect(status().isOk());
        as(owner, put("/api/v1/roles/" + viewer + "/permissions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"permissions\":[]}")).andExpect(status().isOk());
        as(member, get("/api/v1/tenant")).andExpect(status().isForbidden());
    }

    @Test
    void permissionCatalogShowsModuleState() throws Exception {
        as(owner, get("/api/v1/permissions")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'crm.customer.read')].moduleEnabled", Matchers.contains(false)))
                .andExpect(jsonPath("$[?(@.code == 'tenant.settings.read')].moduleEnabled", Matchers.contains(true)));
    }
}
```

Run: `cd backend && ./gradlew test --tests '*RoleManagementIT'`
Expected: FAIL. The role endpoints don't exist yet, so requests get 404 or 401. `com.jayway.jsonpath.JsonPath` comes from the Boot test starters.

- [ ] **Step 2: Actor details and principal state**

`backend/src/main/java/com/nexusops/shared/security/ActorDetails.java`:
```java
package com.nexusops.shared.security;

import java.util.Set;
import java.util.UUID;

/** The authenticated caller's roles and grantable permissions (union of role permissions, module-agnostic). */
public record ActorDetails(Set<UUID> roleIds, Set<String> grantablePermissions) {}
```

`backend/src/main/java/com/nexusops/shared/security/CurrentActor.java`:
```java
package com.nexusops.shared.security;

import org.springframework.security.core.context.SecurityContextHolder;

public final class CurrentActor {

    private CurrentActor() {}

    public static ActorDetails require() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getDetails() instanceof ActorDetails details) {
            return details;
        }
        throw new IllegalStateException("No authenticated actor");
    }
}
```

Replace `PrincipalState` with:
```java
package com.nexusops.identity.security;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** What every request needs to know about its caller, cached briefly in Redis (spec §6–§7). */
public record PrincipalState(String userStatus, int tokenVersion, String tenantStatus, Set<String> permissions,
        List<String> modules, Set<UUID> roleIds, Set<String> grantablePermissions) {

    public PrincipalState {
        permissions = permissions == null ? Set.of() : permissions;
        modules = modules == null ? List.of() : modules;
        roleIds = roleIds == null ? Set.of() : roleIds;
        grantablePermissions = grantablePermissions == null ? Set.of() : grantablePermissions;
    }

    static PrincipalState missing() {
        return new PrincipalState("MISSING", -1, "MISSING", Set.of(), List.of(), Set.of(), Set.of());
    }
}
```
In `PrincipalStateCache.load`, build it with `user.getRoleIds()` and `authorization.grantablePermissions(user.getRoleIds())` as the last two arguments.

In `PrincipalFilter`, replace the authentication construction with:
```java
            var authenticated = new JwtAuthenticationToken(jwt, authorities, userId.toString());
            authenticated.setDetails(new com.nexusops.shared.security.ActorDetails(state.roleIds(), state.grantablePermissions()));
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authenticated);
            SecurityContextHolder.setContext(context);
```

- [ ] **Step 3: Role domain and repository**

Add to `Role`:
```java
    public String getDescription() {
        return description;
    }

    public boolean isSystem() {
        return system;
    }

    public void rename(String newName, String newDescription) {
        if (newName != null) this.name = newName;
        if (newDescription != null) this.description = newDescription;
        this.updatedAt = Instant.now();
    }

    public void replacePermissions(Set<String> codes) {
        this.permissions.clear();
        this.permissions.addAll(codes);
        this.updatedAt = Instant.now();
    }
```

Add to `RoleRepository`:
```java
    boolean existsByNameIgnoreCase(String name);

    java.util.Optional<Role> findByNameAndSystemTrue(String name);

    java.util.List<Role> findAllByOrderBySystemDescNameAsc();

    /** user_roles rows are RLS-protected: only this tenant's assignments are counted. */
    @org.springframework.data.jpa.repository.Query(nativeQuery = true,
            value = "select count(*) from user_roles where role_id = :roleId")
    long countAssignments(@org.springframework.data.repository.query.Param("roleId") UUID roleId);
```

- [ ] **Step 4: API types and service**

`backend/src/main/java/com/nexusops/authorization/PermissionView.java`:
```java
package com.nexusops.authorization;

public record PermissionView(String code, String module, String description, boolean moduleEnabled) {}
```

`backend/src/main/java/com/nexusops/authorization/RoleView.java`:
```java
package com.nexusops.authorization;

import java.util.List;
import java.util.UUID;

public record RoleView(UUID id, String name, String description, boolean system, List<String> permissions) {}
```

`backend/src/main/java/com/nexusops/authorization/CreateRoleCommand.java`:
```java
package com.nexusops.authorization;

import java.util.Set;

public record CreateRoleCommand(String name, String description, Set<String> permissions) {}
```

Add to `Permission` a getter `getDescription()`.

Extend `AuthorizationService`. Add constructor parameters `TenantDirectory tenants`, `AuditService audit` and `ApplicationEventPublisher events`, plus the imports for `com.nexusops.tenancy.TenantDirectory`, `com.nexusops.audit.*`, `com.nexusops.shared.web.ApiProblem`, `com.nexusops.shared.security.CurrentActor`, `org.springframework.context.ApplicationEventPublisher`, `java.util.*` and `org.springframework.dao.DataIntegrityViolationException`. Then add:
```java
    static final String ESCALATION = "You can't grant permissions you don't have.";
    static final String OWNER_ONLY = "Only workspace owners can manage the owner role.";
    static final String SYSTEM_IMMUTABLE = "System roles can't be changed.";

    /** Union of the roles' permissions WITHOUT module gating: what this actor may grant to others. */
    @Transactional(readOnly = true)
    public Set<String> grantablePermissions(Collection<UUID> roleIds) {
        TenantContext.requireTenantId();
        Set<String> result = new HashSet<>();
        roles.findAllById(roleIds).forEach(r -> result.addAll(r.getPermissions()));
        return Set.copyOf(result);
    }

    @Transactional(readOnly = true)
    public List<PermissionView> permissionCatalog() {
        List<String> enabled = tenants.enabledModules();
        return permissions.findAll().stream()
                .sorted(Comparator.comparing(Permission::getCode))
                .map(p -> new PermissionView(p.getCode(), p.getModuleCode(), p.getDescription(),
                        p.getModuleCode() == null || enabled.contains(p.getModuleCode())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RoleView> listRoles() {
        TenantContext.requireTenantId();
        return roles.findAllByOrderBySystemDescNameAsc().stream().map(AuthorizationService::view).toList();
    }

    @Transactional(readOnly = true)
    public RoleView getRole(UUID id) {
        return view(find(id));
    }

    @Transactional
    public RoleView createRole(CreateRoleCommand command) {
        TenantContext.requireTenantId();
        String name = validName(command.name());
        String description = validDescription(command.description());
        Set<String> codes = validPermissions(command.permissions());
        requireGrantable(codes);
        if (isReservedName(name) || roles.existsByNameIgnoreCase(name)) {
            throw nameTaken();
        }
        Role role = new Role(Ids.newId(), name, description, false, codes);
        try {
            roles.saveAndFlush(role);
        } catch (DataIntegrityViolationException race) {
            throw nameTaken();
        }
        audit.record(AuditEntry.of("RoleCreated", "Role", role.getId())
                .withAfter(Map.of("name", name, "permissions", sorted(codes))));
        return view(role);
    }

    @Transactional
    public RoleView updateRole(UUID id, String rawName, String rawDescription) {
        Role role = mutable(id);
        RoleView before = view(role);
        String name = rawName == null ? null : validName(rawName);
        if (name != null && !name.equalsIgnoreCase(role.getName())
                && (isReservedName(name) || roles.existsByNameIgnoreCase(name))) {
            throw nameTaken();
        }
        role.rename(name, rawDescription == null ? null : validDescription(rawDescription));
        try {
            roles.flush();
        } catch (DataIntegrityViolationException race) {
            throw nameTaken();
        }
        audit.record(AuditEntry.of("RoleUpdated", "Role", id)
                .withBefore(Map.of("name", before.name(), "description", String.valueOf(before.description())))
                .withAfter(Map.of("name", role.getName(), "description", String.valueOf(role.getDescription()))));
        return view(role);
    }

    @Transactional
    public RoleView replacePermissions(UUID id, Set<String> requested) {
        Role role = mutable(id);
        Set<String> codes = validPermissions(requested);
        requireGrantable(codes);
        List<String> before = view(role).permissions();
        role.replacePermissions(codes);
        roles.flush();
        audit.record(AuditEntry.of("RolePermissionsChanged", "Role", id)
                .withBefore(Map.of("permissions", before))
                .withAfter(Map.of("permissions", sorted(codes))));
        events.publishEvent(new RolesChanged(TenantContext.requireTenantId()));
        return view(role);
    }

    @Transactional
    public void deleteRole(UUID id) {
        Role role = mutable(id);
        long assigned = roles.countAssignments(id);
        if (assigned > 0) {
            throw ApiProblem.conflict("Remove this role from " + assigned + " user(s) first.");
        }
        roles.delete(role);
        audit.record(AuditEntry.of("RoleDeleted", "Role", id).withBefore(Map.of("name", role.getName())));
        events.publishEvent(new RolesChanged(TenantContext.requireTenantId()));
    }

    @Transactional(readOnly = true)
    public UUID ownerRoleId() {
        TenantContext.requireTenantId();
        return roles.findByNameAndSystemTrue(SystemRoles.OWNER)
                .orElseThrow(() -> new IllegalStateException("Tenant has no owner role")).getId();
    }

    @Transactional(readOnly = true)
    public Map<UUID, String> roleNames(Collection<UUID> roleIds) {
        TenantContext.requireTenantId();
        Map<UUID, String> names = new HashMap<>();
        roles.findAllById(roleIds).forEach(r -> names.put(r.getId(), r.getName()));
        return names;
    }

    /** May the current actor grant every one of these roles? (Unknown → 400, owner role → owners only, superset → 403.) */
    @Transactional(readOnly = true)
    public void checkGrantable(Collection<UUID> roleIds) {
        TenantContext.requireTenantId();
        List<Role> found = roles.findAllById(roleIds);
        if (found.size() != new HashSet<>(roleIds).size()) {
            throw ApiProblem.badRequestField("roleIds", "Unknown role.");
        }
        Set<String> requested = new HashSet<>();
        for (Role role : found) {
            if (role.isSystem() && SystemRoles.OWNER.equals(role.getName())) {
                requireOwnerActor();
            }
            requested.addAll(role.getPermissions());
        }
        requireGrantable(requested);
    }

    @Transactional(readOnly = true)
    public void requireOwnerActor() {
        if (!CurrentActor.require().roleIds().contains(ownerRoleId())) {
            throw ApiProblem.forbidden(OWNER_ONLY);
        }
    }

    private void requireGrantable(Set<String> codes) {
        if (!CurrentActor.require().grantablePermissions().containsAll(codes)) {
            throw ApiProblem.forbidden(ESCALATION);
        }
    }

    private Role find(UUID id) {
        TenantContext.requireTenantId();
        return roles.findById(id).orElseThrow(() -> ApiProblem.notFound("Role not found."));
    }

    private Role mutable(UUID id) {
        Role role = find(id);
        if (role.isSystem()) {
            throw ApiProblem.conflict(SYSTEM_IMMUTABLE);
        }
        return role;
    }

    private Set<String> validPermissions(Set<String> requested) {
        Set<String> codes = requested == null ? Set.of() : Set.copyOf(requested);
        Set<String> known = permissions.findAll().stream().map(Permission::getCode).collect(Collectors.toSet());
        for (String code : codes) {
            if (!known.contains(code)) {
                throw ApiProblem.badRequestField("permissions", "Unknown permission: " + code);
            }
        }
        return codes;
    }

    private static String validName(String raw) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty() || name.length() > 60) {
            throw ApiProblem.badRequestField("name", "Enter a name between 1 and 60 characters.");
        }
        return name;
    }

    private static String validDescription(String raw) {
        if (raw == null) return null;
        String description = raw.strip();
        if (description.length() > 255) {
            throw ApiProblem.badRequestField("description", "Use at most 255 characters.");
        }
        return description;
    }

    private static boolean isReservedName(String name) {
        return name.equalsIgnoreCase(SystemRoles.OWNER) || name.equalsIgnoreCase(SystemRoles.ADMIN);
    }

    private static ApiProblem nameTaken() {
        return ApiProblem.conflictField("name", "A role with this name already exists.");
    }

    private static List<String> sorted(Collection<String> codes) {
        return codes.stream().sorted().toList();
    }

    private static RoleView view(Role role) {
        return new RoleView(role.getId(), role.getName(), role.getDescription(), role.isSystem(), sorted(role.getPermissions()));
    }
```
`AuthorizationService` now depends on `tenancy` (`TenantDirectory`); the spec's module table allows `authorization → tenancy`. Keep the existing `createSystemRoles` and `effectivePermissions` unchanged.

- [ ] **Step 5: Controllers**

`backend/src/main/java/com/nexusops/authorization/web/RoleDtos.java`:
```java
package com.nexusops.authorization.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Set;

final class RoleDtos {

    private RoleDtos() {}

    record CreateRoleRequest(@NotBlank @Size(max = 60) String name, @Size(max = 255) String description,
            @NotNull Set<@NotBlank String> permissions) {}

    record UpdateRoleRequest(@Size(max = 60) String name, @Size(max = 255) String description) {}

    record PermissionsRequest(@NotNull Set<@NotBlank String> permissions) {}
}
```

`backend/src/main/java/com/nexusops/authorization/web/PermissionController.java`:
```java
package com.nexusops.authorization.web;

import com.nexusops.authorization.AuthorizationService;
import com.nexusops.authorization.PermissionView;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PermissionController {

    private final AuthorizationService authorization;

    PermissionController(AuthorizationService authorization) {
        this.authorization = authorization;
    }

    @GetMapping("/api/v1/permissions")
    @PreAuthorize("hasAuthority('authorization.role.read')")
    List<PermissionView> catalog() {
        return authorization.permissionCatalog();
    }
}
```

`backend/src/main/java/com/nexusops/authorization/web/RoleController.java`:
```java
package com.nexusops.authorization.web;

import com.nexusops.authorization.AuthorizationService;
import com.nexusops.authorization.CreateRoleCommand;
import com.nexusops.authorization.RoleView;
import com.nexusops.authorization.web.RoleDtos.CreateRoleRequest;
import com.nexusops.authorization.web.RoleDtos.PermissionsRequest;
import com.nexusops.authorization.web.RoleDtos.UpdateRoleRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/roles")
class RoleController {

    private final AuthorizationService authorization;

    RoleController(AuthorizationService authorization) {
        this.authorization = authorization;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('authorization.role.read')")
    List<RoleView> list() {
        return authorization.listRoles();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('authorization.role.manage')")
    RoleView create(@Valid @RequestBody CreateRoleRequest request) {
        return authorization.createRole(new CreateRoleCommand(request.name(), request.description(), request.permissions()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('authorization.role.read')")
    RoleView get(@PathVariable UUID id) {
        return authorization.getRole(id);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('authorization.role.manage')")
    RoleView update(@PathVariable UUID id, @Valid @RequestBody UpdateRoleRequest request) {
        return authorization.updateRole(id, request.name(), request.description());
    }

    @PutMapping("/{id}/permissions")
    @PreAuthorize("hasAuthority('authorization.role.manage')")
    RoleView replacePermissions(@PathVariable UUID id, @Valid @RequestBody PermissionsRequest request) {
        return authorization.replacePermissions(id, request.permissions());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('authorization.role.manage')")
    void delete(@PathVariable UUID id) {
        authorization.deleteRole(id);
    }
}
```

A non-UUID `{id}` must give 404, not 500. Today `MethodArgumentTypeMismatchException` falls through to the catch-all 500, so add this to `GlobalExceptionHandler`:
```java
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> handleTypeMismatch(Exception ex) {
        return problem(HttpStatus.NOT_FOUND, "Not Found", "Resource not found.");
    }
```
Add the assertion `as(owner, get("/api/v1/roles/not-a-uuid")).andExpect(status().isNotFound());` to `validationAndUniqueness`.

- [ ] **Step 6: Run the tests and the build**

Run: `./gradlew test --tests '*RoleManagementIT'`, then `./gradlew build`
Expected: PASS (8 tests), then a green build. That includes `TenantIsolationIT.permissionsComeFromTheServerNotTheToken` (`PrincipalState` changed shape), `EndpointAuthorizationCoverageTest` (7 new routes, all with `@PreAuthorize`) and `ModularityTest` (`authorization` → `tenancy`, `audit`, `shared`).

- [ ] **Step 7: Commit**

```bash
cd /Users/user/Desktop/nexusops && git add backend && git commit -m "feat(authorization): permission catalog, custom role management, escalation guard on grantable permissions"
```

---

### Task 6: Invitations — schema, invite, list, revoke (seat limit and escalation)

**Files:**
- Create:
  - `backend/src/main/resources/db/migration/V6__invitations.sql`;
  - `identity/domain/Invitation.java`, `identity/domain/InvitationStatus.java`, `identity/domain/InvitationRepository.java`;
  - `identity/application/InvitationService.java`, `identity/application/InvitationView.java`;
  - `identity/web/InvitationController.java`.
- Modify:
  - `identity/domain/UserRepository.java` (add `existsByEmail`, `countByStatus`);
  - `RlsCoverageIT.java` (add `invitations` to `EXPECTED_TENANT_TABLES`).
- Test: `support/TestRoles.java`, `identity/InvitationIT.java`

**Interfaces:**
- Produces the table `invitations`, with FORCE RLS, plus the partial unique index on open `(tenant_id, email)`.
- Produces `Invitation`:
  - `issue(UUID id, String email, UUID roleId, String tokenHash, UUID invitedBy, Instant expiresAt)`;
  - `status(Instant now): InvitationStatus` (`PENDING | ACCEPTED | REVOKED | EXPIRED`);
  - `isPending(Instant)`, `revoke(Instant)`, `accept(Instant now, UUID userId)`;
  - getters `getEmail()`, `getRoleId()`, `getInvitedBy()`, `getExpiresAt()`, `getCreatedAt()`.
- Produces `InvitationRepository`:
  - `findByTokenHash`;
  - `findForUpdateByTokenHash`, a `PESSIMISTIC_WRITE` lock used by Task 7;
  - `findOpenByEmail(String)`;
  - `countPending(Instant now)`;
  - `findAllByOrderByCreatedAtDesc()`.
- Produces `record InvitationView(UUID id, String email, UUID roleId, String roleName, String status, UUID invitedBy, Instant expiresAt, Instant createdAt)`.
- Produces `InvitationService`:
  - `invite(String email, UUID roleId): InvitationView`;
  - `list(): List<InvitationView>`;
  - `revoke(UUID id)`.

  Task 7 adds `preview` and `accept` to the same service. `InvitationService.INVITATION_TTL = Duration.ofDays(7)`.
- Produces these endpoints:
  - `POST /api/v1/invitations` (`identity.user.invite`), body `{"email","roleId"}` → 201 `InvitationView`;
  - `GET /api/v1/invitations` (`identity.user.read`) → `[InvitationView]`, newest first;
  - `DELETE /api/v1/invitations/{id}` (`identity.user.invite`) → 204.
- Produces `TestRoles.create(MockMvc mvc, TestTenants.Session session, String name, String... permissions): UUID`.

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/com/nexusops/support/TestRoles.java`:
```java
package com.nexusops.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

public final class TestRoles {

    private TestRoles() {}

    public static UUID create(MockMvc mvc, TestTenants.Session session, String name, String... permissions) throws Exception {
        String perms = Arrays.stream(permissions).map(p -> "\"" + p + "\"").collect(Collectors.joining(","));
        String body = mvc.perform(post("/api/v1/roles").header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"permissions\":[" + perms + "]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    public static UUID system(UUID tenantId, String name) {
        return OwnerJdbc.ownerAs(tenantId).queryForObject("select id from roles where name = ?", UUID.class, name);
    }
}
```

`backend/src/test/java/com/nexusops/identity/InvitationIT.java`:
```java
package com.nexusops.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestMembers;
import com.nexusops.support.TestRoles;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Session;
import com.nexusops.support.TestTenants.Workspace;
import java.util.Set;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@AutoConfigureMockMvc
class InvitationIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Session owner;
    UUID support;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("inv"));
        owner = TestTenants.login(mvc, ws);
        support = TestRoles.create(mvc, owner, "Support", "identity.user.read", "tenant.settings.read");
    }

    private ResultActions invite(Session s, String email, UUID roleId) throws Exception {
        return mvc.perform(post("/api/v1/invitations").header("Authorization", "Bearer " + s.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"roleId\":\"" + roleId + "\"}"));
    }

    private UUID idOf(ResultActions result) throws Exception {
        return UUID.fromString(JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id"));
    }

    @Test
    void ownerInvitesAMemberAndAnEmailIsSent() throws Exception {
        invite(owner, " New@" + ws.slug() + ".TEST ", support)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("new@" + ws.slug() + ".test"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.roleName").value("Support"));
        assertThat(mail.sentTo("new@" + ws.slug() + ".test")).singleElement()
                .satisfies(m -> assertThat(m.textBody()).contains("/invite/accept?token="));
        mvc.perform(get("/api/v1/invitations").header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].email", Matchers.hasItem("new@" + ws.slug() + ".test")));
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select token_hash from invitations", String.class)).matches("[0-9a-f]{64}");
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForList("select action from audit_events", String.class))
                .contains("InvitationCreated");
    }

    @Test
    void duplicatesAndExistingMembersAreRejected() throws Exception {
        invite(owner, "dup@x.test", support).andExpect(status().isCreated());
        invite(owner, "DUP@x.test", support).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].field").value("email"))
                .andExpect(jsonPath("$.detail").value("An invitation is already pending for this email."));
        invite(owner, ws.email(), support).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This person is already a member of the workspace."));
        invite(owner, "x@x.test", UUID.randomUUID()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("roleIds"));
    }

    @Test
    void revokeAndReinvite() throws Exception {
        UUID id = idOf(invite(owner, "rev@x.test", support));
        mvc.perform(delete("/api/v1/invitations/" + id).header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/invitations/" + id).header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This invitation is no longer pending."));
        mvc.perform(delete("/api/v1/invitations/" + UUID.randomUUID()).header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isNotFound());
        invite(owner, "rev@x.test", support).andExpect(status().isCreated());
    }

    @Test
    void expiredInvitationsAreSupersededOnReinvite() throws Exception {
        invite(owner, "late@x.test", support).andExpect(status().isCreated());
        OwnerJdbc.ownerAs(ws.tenantId()).update("update invitations set expires_at = now() - interval '1 minute'");
        invite(owner, "late@x.test", support).andExpect(status().isCreated());
        mvc.perform(get("/api/v1/invitations").header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(jsonPath("$[?(@.email == 'late@x.test')].status", Matchers.containsInAnyOrder("PENDING", "REVOKED")));
    }

    @Test
    void freePlanSeatLimitCountsActiveUsersAndPendingInvitations() throws Exception {
        invite(owner, "a@x.test", support).andExpect(status().isCreated());
        UUID second = idOf(invite(owner, "b@x.test", support).andExpect(status().isCreated()));
        invite(owner, "c@x.test", support).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Your plan allows 3 users. Upgrade to add more."));
        mvc.perform(delete("/api/v1/invitations/" + second).header("Authorization", "Bearer " + owner.accessToken()));
        invite(owner, "c@x.test", support).andExpect(status().isCreated());
    }

    @Test
    void invitersCannotEscalate() throws Exception {
        UUID inviterRole = TestRoles.create(mvc, owner, "Inviter", "identity.user.invite", "identity.user.read");
        Session inviter = TestTenants.login(mvc, members.create(ws.tenantId(), Set.of(inviterRole)));
        invite(inviter, "esc@x.test", support).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("You can't grant permissions you don't have."));
        invite(inviter, "own@x.test", TestRoles.system(ws.tenantId(), "TENANT_OWNER")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Only workspace owners can manage the owner role."));
        UUID readers = TestRoles.create(mvc, owner, "Readers", "identity.user.read");
        invite(inviter, "ok@x.test", readers).andExpect(status().isCreated());
        invite(owner, "co-owner@x.test", TestRoles.system(ws.tenantId(), "TENANT_OWNER")).andExpect(status().isConflict());
    }
}
```
In the last assertion: the owner and the inviter are 2 ACTIVE users, and `ok@` is 1 pending invitation (the rejected invites were never saved). That makes 3 of the Free plan's 3 seats. The owner's co-owner invite therefore passes the owner-role check and is stopped by the seat limit: 409, not 403.

Run: `cd backend && ./gradlew test --tests '*InvitationIT' --tests '*RlsCoverageIT'`
Expected: FAIL. The invitations endpoints are 404, and `RlsCoverageIT` fails once `invitations` is added to the expected set, because the table is missing.

- [ ] **Step 2: Migration**

`backend/src/main/resources/db/migration/V6__invitations.sql`:
```sql
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
```
Add `"invitations"` to `RlsCoverageIT.EXPECTED_TENANT_TABLES`.

- [ ] **Step 3: Domain**

`backend/src/main/java/com/nexusops/identity/domain/InvitationStatus.java`:
```java
package com.nexusops.identity.domain;

public enum InvitationStatus { PENDING, ACCEPTED, REVOKED, EXPIRED }
```

`backend/src/main/java/com/nexusops/identity/domain/Invitation.java`:
```java
package com.nexusops.identity.domain;

import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "invitations")
public class Invitation extends TenantOwnedEntity {

    @Column(nullable = false)
    private String email;

    @Column(name = "role_id", nullable = false)
    private UUID roleId;

    @Column(name = "token_hash", nullable = false, columnDefinition = "bpchar")
    private String tokenHash;

    @Column(name = "invited_by")
    private UUID invitedBy;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "accepted_user_id")
    private UUID acceptedUserId;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Invitation() {}

    private Invitation(UUID id) {
        super(id);
    }

    public static Invitation issue(UUID id, String email, UUID roleId, String tokenHash, UUID invitedBy, Instant expiresAt) {
        Invitation invitation = new Invitation(id);
        invitation.email = email;
        invitation.roleId = roleId;
        invitation.tokenHash = tokenHash;
        invitation.invitedBy = invitedBy;
        invitation.expiresAt = expiresAt;
        invitation.createdAt = Instant.now();
        return invitation;
    }

    public InvitationStatus status(Instant now) {
        if (acceptedAt != null) return InvitationStatus.ACCEPTED;
        if (revokedAt != null) return InvitationStatus.REVOKED;
        if (!expiresAt.isAfter(now)) return InvitationStatus.EXPIRED;
        return InvitationStatus.PENDING;
    }

    public boolean isPending(Instant now) {
        return status(now) == InvitationStatus.PENDING;
    }

    /** Also used to close an expired invitation before re-inviting the same address. */
    public void revoke(Instant now) {
        this.revokedAt = now;
    }

    public void accept(Instant now, UUID userId) {
        this.acceptedAt = now;
        this.acceptedUserId = userId;
    }

    public String getEmail() { return email; }
    public UUID getRoleId() { return roleId; }
    public UUID getInvitedBy() { return invitedBy; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
}
```

`backend/src/main/java/com/nexusops/identity/domain/InvitationRepository.java`:
```java
package com.nexusops.identity.domain;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InvitationRepository extends JpaRepository<Invitation, UUID> {

    Optional<Invitation> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invitation i where i.tokenHash = :hash")
    Optional<Invitation> findForUpdateByTokenHash(@Param("hash") String hash);

    @Query("select i from Invitation i where i.email = :email and i.acceptedAt is null and i.revokedAt is null")
    Optional<Invitation> findOpenByEmail(@Param("email") String email);

    @Query("select count(i) from Invitation i where i.acceptedAt is null and i.revokedAt is null and i.expiresAt > :now")
    long countPending(@Param("now") Instant now);

    List<Invitation> findAllByOrderByCreatedAtDesc();
}
```

Add to `UserRepository`:
```java
    boolean existsByEmail(String email);

    long countByStatus(UserStatus status);
```

- [ ] **Step 4: Service, view and controller**

`backend/src/main/java/com/nexusops/identity/application/InvitationView.java`:
```java
package com.nexusops.identity.application;

import java.time.Instant;
import java.util.UUID;

public record InvitationView(UUID id, String email, UUID roleId, String roleName, String status, UUID invitedBy,
        Instant expiresAt, Instant createdAt) {}
```

`backend/src/main/java/com/nexusops/identity/application/InvitationService.java`:
```java
package com.nexusops.identity.application;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.authorization.AuthorizationService;
import com.nexusops.identity.domain.Invitation;
import com.nexusops.identity.domain.InvitationRepository;
import com.nexusops.identity.domain.UserRepository;
import com.nexusops.identity.domain.UserStatus;
import com.nexusops.identity.security.CurrentUser;
import com.nexusops.notifications.MailRequested;
import com.nexusops.notifications.OutgoingMail;
import com.nexusops.shared.Ids;
import com.nexusops.shared.db.TenantLocks;
import com.nexusops.shared.web.ApiProblem;
import com.nexusops.tenancy.TenantDirectory;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Invitations: the only way (besides signup) a person joins a workspace. */
@Service
public class InvitationService {

    public static final Duration INVITATION_TTL = Duration.ofDays(7);

    private final InvitationRepository invitations;
    private final UserRepository users;
    private final AuthorizationService authorization;
    private final TenantDirectory tenants;
    private final TenantLocks locks;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final String appBaseUrl;

    InvitationService(InvitationRepository invitations, UserRepository users, AuthorizationService authorization,
            TenantDirectory tenants, TenantLocks locks, AuditService audit, ApplicationEventPublisher events,
            @Value("${nexusops.app.base-url}") String appBaseUrl) {
        this.invitations = invitations;
        this.users = users;
        this.authorization = authorization;
        this.tenants = tenants;
        this.locks = locks;
        this.audit = audit;
        this.events = events;
        this.appBaseUrl = appBaseUrl;
    }

    @Transactional
    public InvitationView invite(String rawEmail, UUID roleId) {
        CurrentUser actor = CurrentUser.require();
        String email = Emails.normalize(rawEmail);
        authorization.checkGrantable(List.of(roleId));
        locks.lock("seats");
        if (users.existsByEmail(email)) {
            throw ApiProblem.conflictField("email", "This person is already a member of the workspace.");
        }
        Instant now = Instant.now();
        invitations.findOpenByEmail(email).ifPresent(open -> {
            if (open.isPending(now)) {
                throw ApiProblem.conflictField("email", "An invitation is already pending for this email.");
            }
            open.revoke(now); // expired: close it so the partial unique index admits the new one
            invitations.flush();
        });
        Integer maxUsers = tenants.currentLimits().maxUsers();
        if (maxUsers != null && users.countByStatus(UserStatus.ACTIVE) + invitations.countPending(now) >= maxUsers) {
            throw ApiProblem.conflict("Your plan allows " + maxUsers + " users. Upgrade to add more.");
        }

        String token = OpaqueTokens.generate(actor.tenantId());
        Invitation invitation = Invitation.issue(Ids.newId(), email, roleId, OpaqueTokens.hash(token), actor.userId(),
                now.plus(INVITATION_TTL));
        invitations.save(invitation);
        String roleName = authorization.roleNames(List.of(roleId)).get(roleId);
        String workspace = tenants.current().name();
        audit.record(AuditEntry.of("InvitationCreated", "Invitation", invitation.getId())
                .withAfter(Map.of("email", email, "role", roleName)));
        String link = appBaseUrl + "/invite/accept?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        events.publishEvent(new MailRequested(new OutgoingMail(email, "You're invited to join " + workspace + " on NexusOps", """
                Hello,

                You've been invited to join the workspace "%s" on NexusOps as %s.
                Accept the invitation and set your password here:
                %s

                This link expires in 7 days. If you weren't expecting this, you can ignore this email.
                """.formatted(workspace, roleName, link))));
        return view(invitation, roleName, now);
    }

    @Transactional(readOnly = true)
    public List<InvitationView> list() {
        CurrentUser.require();
        Instant now = Instant.now();
        List<Invitation> all = invitations.findAllByOrderByCreatedAtDesc();
        Map<UUID, String> roleNames = authorization.roleNames(all.stream().map(Invitation::getRoleId).toList());
        return all.stream().map(i -> view(i, roleNames.get(i.getRoleId()), now)).toList();
    }

    @Transactional
    public void revoke(UUID id) {
        CurrentUser.require();
        Instant now = Instant.now();
        Invitation invitation = invitations.findById(id).orElseThrow(() -> ApiProblem.notFound("Invitation not found."));
        if (!invitation.isPending(now)) {
            throw ApiProblem.conflict("This invitation is no longer pending.");
        }
        invitation.revoke(now);
        audit.record(AuditEntry.of("InvitationRevoked", "Invitation", id).withBefore(Map.of("email", invitation.getEmail())));
    }

    static InvitationView view(Invitation i, String roleName, Instant now) {
        return new InvitationView(i.getId(), i.getEmail(), i.getRoleId(), roleName, i.status(now).name(), i.getInvitedBy(),
                i.getExpiresAt(), i.getCreatedAt());
    }
}
```

`backend/src/main/java/com/nexusops/identity/web/InvitationController.java`:
```java
package com.nexusops.identity.web;

import com.nexusops.identity.application.InvitationService;
import com.nexusops.identity.application.InvitationView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/invitations")
class InvitationController {

    record InviteRequest(@NotBlank @Size(max = 254) String email, @NotNull UUID roleId) {}

    private final InvitationService invitations;

    InvitationController(InvitationService invitations) {
        this.invitations = invitations;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('identity.user.invite')")
    InvitationView invite(@Valid @RequestBody InviteRequest request) {
        return invitations.invite(request.email(), request.roleId());
    }

    @GetMapping
    @PreAuthorize("hasAuthority('identity.user.read')")
    List<InvitationView> list() {
        return invitations.list();
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('identity.user.invite')")
    void revoke(@PathVariable UUID id) {
        invitations.revoke(id);
    }
}
```

- [ ] **Step 5: Run the tests and the build**

Run the Step 1 command again, then `./gradlew build`
Expected: `InvitationIT` passes 6 and `RlsCoverageIT` passes 2, then a green build. `RlsBehaviourIT.noTenantContextSeesNothing` iterates `EXPECTED_TENANT_TABLES`, so it now covers `invitations` too.

- [ ] **Step 6: Commit**

```bash
cd /Users/user/Desktop/nexusops && git add backend && git commit -m "feat(identity): invitations — invite, list, revoke with seat limit, escalation guard and FORCE RLS"
```

---

### Task 7: Invitation preview and acceptance (public, rate-limited, race-safe)

**Files:**
- Create:
  - `identity/application/Names.java` (move `SignupService.requireName` here as `Names.require`; `SignupService` calls it);
  - `identity/application/InvitationPreview.java`, `identity/application/AcceptedInvitation.java`;
  - `identity/web/PublicInvitationController.java`.
- Modify:
  - `identity/application/InvitationService.java` (`preview`, `accept`);
  - `identity/domain/User.java` (`joinFromInvitation`);
  - `shared/web/PublicEndpoints.java` (two routes);
  - `identity/application/SignupService.java` (use `Names.require`).
- Test: `identity/InvitationAcceptIT.java`

**Interfaces:**
- Produces `Names.require(String raw, String field): String`, which strips the value and requires 1–80 characters; otherwise it returns 400 with that field and "Enter between 1 and 80 characters."
- Produces `User.joinFromInvitation(UUID id, String email, String passwordHash, String firstName, String lastName, UUID roleId, Instant now)`, which creates an ACTIVE user, verified at `now`, holding the one role.
- Produces:
  - `record InvitationPreview(String workspace, String workspaceName, String email, String roleName, Instant expiresAt)`;
  - `record AcceptedInvitation(String workspace, String email)`.
- Produces `InvitationService.preview(String token): InvitationPreview` and `accept(String token, String firstName, String lastName, String password): AcceptedInvitation`.
  - An invalid, expired, revoked or accepted token returns 400 "This invitation link is invalid or has expired."
  - A suspended workspace returns 403 "Workspace suspended."
- Produces these public endpoints, listed in `PublicEndpoints`:
  - `GET /api/v1/invitations/preview?token=…` → 200 `InvitationPreview`, rate rule `invitation-preview`;
  - `POST /api/v1/invitations/accept`, body `{token, firstName, lastName, password}` → 201 `AcceptedInvitation`, rate rule `invitation-accept`.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/nexusops/identity/InvitationAcceptIT.java`:
```java
package com.nexusops.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestRoles;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Session;
import com.nexusops.support.TestTenants.Workspace;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@AutoConfigureMockMvc
class InvitationAcceptIT extends IntegrationTestSupport {

    static final String INVALID = "This invitation link is invalid or has expired.";

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;

    Workspace ws;
    Session owner;
    String email;
    String token;

    @BeforeEach
    void invite() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("acc"));
        owner = TestTenants.login(mvc, ws);
        UUID support = TestRoles.create(mvc, owner, "Support", "identity.user.read", "tenant.settings.read");
        email = "joiner-" + UUID.randomUUID().toString().substring(0, 6) + "@x.test";
        mvc.perform(post("/api/v1/invitations").header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"roleId\":\"" + support + "\"}"))
                .andExpect(status().isCreated());
        token = mail.lastTokenFor(email);
    }

    private ResultActions accept(String t, String password) throws Exception {
        return mvc.perform(post("/api/v1/invitations/accept").contentType(MediaType.APPLICATION_JSON).content("""
                {"token":"%s","firstName":"Jo","lastName":"Iner","password":"%s"}""".formatted(t, password)));
    }

    @Test
    void previewIsPublicAndShowsWhatIsBeingAccepted() throws Exception {
        mvc.perform(get("/api/v1/invitations/preview").param("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspace").value(ws.slug()))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.roleName").value("Support"));
    }

    @Test
    void acceptCreatesAVerifiedMemberWhoCanLogInWithTheInvitedRole() throws Exception {
        accept(token, TestTenants.PASSWORD).andExpect(status().isCreated())
                .andExpect(jsonPath("$.workspace").value(ws.slug()))
                .andExpect(jsonPath("$.email").value(email));
        Session member = TestTenants.login(mvc, new Workspace(ws.tenantId(), ws.slug(), email, TestTenants.PASSWORD));
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + member.accessToken()))
                .andExpect(jsonPath("$.permissions", Matchers.containsInAnyOrder("identity.user.read", "tenant.settings.read")));
        mvc.perform(get("/api/v1/invitations").header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(jsonPath("$[0].status").value("ACCEPTED"));
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForList("select action from audit_events", String.class))
                .contains("InvitationAccepted", "UserRegistered");
        accept(token, TestTenants.PASSWORD).andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(INVALID));
    }

    @Test
    void invalidTokensAreRejectedUniformly() throws Exception {
        accept("garbage", TestTenants.PASSWORD).andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(INVALID));
        accept(UUID.randomUUID() + token.substring(36), TestTenants.PASSWORD).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/invitations/preview").param("token", "garbage")).andExpect(status().isBadRequest());
        OwnerJdbc.ownerAs(ws.tenantId()).update("update invitations set expires_at = now() - interval '1 minute'");
        accept(token, TestTenants.PASSWORD).andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(INVALID));
    }

    @Test
    void weakPasswordIsAFieldErrorAndLeavesTheInvitationPending() throws Exception {
        accept(token, "Password1234").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("password"));
        accept(token, TestTenants.PASSWORD).andExpect(status().isCreated());
    }

    @Test
    void suspendedWorkspacesCannotBeJoined() throws Exception {
        OwnerJdbc.jdbc().update("update tenants set status = 'SUSPENDED' where id = ?", ws.tenantId());
        accept(token, TestTenants.PASSWORD).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Workspace suspended."));
    }

    @Test
    void concurrentAcceptCreatesExactlyOneUser() throws Exception {
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return accept(token, TestTenants.PASSWORD).andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : results) {
            statuses.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertThat(statuses).containsExactlyInAnyOrder(201, 400);
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select count(*) from users where email = ?", Long.class, email)).isOne();
    }
}
```

Run: `cd backend && ./gradlew test --tests '*InvitationAcceptIT'`
Expected: FAIL. The routes don't exist yet, so requests get 401.

- [ ] **Step 2: Implement**

`backend/src/main/java/com/nexusops/identity/application/Names.java`:
```java
package com.nexusops.identity.application;

import com.nexusops.shared.web.ApiProblem;

final class Names {

    private Names() {}

    static String require(String raw, String field) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty() || name.length() > 80) {
            throw ApiProblem.badRequestField(field, "Enter between 1 and 80 characters.");
        }
        return name;
    }
}
```
In `SignupService`, delete `requireName` and call `Names.require(...)` instead.

Add to `User`:
```java
    /** A person who accepted an invitation: active, email proven by the invitation link, holding one role. */
    public static User joinFromInvitation(UUID id, String email, String passwordHash, String firstName, String lastName,
            UUID roleId, Instant now) {
        User user = registerOwner(id, email, passwordHash, firstName, lastName, roleId);
        user.markEmailVerified(now);
        return user;
    }
```

`backend/src/main/java/com/nexusops/identity/application/InvitationPreview.java`:
```java
package com.nexusops.identity.application;

import java.time.Instant;

public record InvitationPreview(String workspace, String workspaceName, String email, String roleName, Instant expiresAt) {}
```

`backend/src/main/java/com/nexusops/identity/application/AcceptedInvitation.java`:
```java
package com.nexusops.identity.application;

public record AcceptedInvitation(String workspace, String email) {}
```

Add to `InvitationService`. Add the constructor parameters `PasswordPolicy passwordPolicy`, `org.springframework.security.crypto.password.PasswordEncoder passwordEncoder` and `org.springframework.transaction.support.TransactionTemplate tx`, plus the imports `com.nexusops.identity.domain.User`, `com.nexusops.shared.TenantContext` and `com.nexusops.tenancy.TenantStatus`:
```java
    static final String INVALID_LINK = "This invitation link is invalid or has expired.";

    /** Public: what the invitee is about to accept. */
    public InvitationPreview preview(String token) {
        OpaqueTokens.Parsed parsed = OpaqueTokens.parse(token).orElseThrow(InvitationService::invalidLink);
        try (var scope = TenantContext.open(parsed.tenantId(), null)) {
            return tx.execute(status -> {
                Invitation invitation = pendingInvitation(parsed.hash());
                var tenant = activeTenant();
                String roleName = authorization.roleNames(List.of(invitation.getRoleId())).get(invitation.getRoleId());
                return new InvitationPreview(tenant.slug(), tenant.name(), invitation.getEmail(), roleName,
                        invitation.getExpiresAt());
            });
        }
    }

    /** Public: creates the member. The invitation row lock makes concurrent accepts create exactly one user. */
    public AcceptedInvitation accept(String token, String rawFirstName, String rawLastName, String password) {
        OpaqueTokens.Parsed parsed = OpaqueTokens.parse(token).orElseThrow(InvitationService::invalidLink);
        String email;
        try (var scope = TenantContext.open(parsed.tenantId(), null)) {
            email = tx.execute(status -> {
                String invitedEmail = pendingInvitation(parsed.hash()).getEmail(); // token first: bad tokens → uniform 400
                activeTenant();
                return invitedEmail;
            });
        }
        passwordPolicy.check(password, email);
        String firstName = Names.require(rawFirstName, "firstName");
        String lastName = Names.require(rawLastName, "lastName");
        String passwordHash = passwordEncoder.encode(password); // slow: outside any transaction

        UUID userId = Ids.newId();
        try (var scope = TenantContext.open(parsed.tenantId(), userId)) {
            return tx.execute(status -> {
                Instant now = Instant.now();
                Invitation invitation = invitations.findForUpdateByTokenHash(parsed.hash())
                        .filter(i -> i.isPending(now))
                        .orElseThrow(InvitationService::invalidLink);
                if (users.existsByEmail(email)) {
                    throw ApiProblem.conflictField("email", "This person is already a member of the workspace.");
                }
                users.save(User.joinFromInvitation(userId, email, passwordHash, firstName, lastName,
                        invitation.getRoleId(), now));
                invitation.accept(now, userId);
                audit.record(AuditEntry.of("InvitationAccepted", "Invitation", invitation.getId()));
                audit.record(AuditEntry.of("UserRegistered", "User", userId)
                        .withAfter(Map.of("email", email, "via", "invitation")));
                return new AcceptedInvitation(tenants.current().slug(), email);
            });
        }
    }

    private Invitation pendingInvitation(String tokenHash) {
        Instant now = Instant.now();
        return invitations.findByTokenHash(tokenHash).filter(i -> i.isPending(now)).orElseThrow(InvitationService::invalidLink);
    }

    private com.nexusops.tenancy.TenantSummary activeTenant() {
        var tenant = tenants.current();
        if (tenant.status() == TenantStatus.SUSPENDED) {
            throw ApiProblem.forbidden("Workspace suspended.");
        }
        if (tenant.status() != TenantStatus.ACTIVE) {
            throw invalidLink();
        }
        return tenant;
    }

    private static ApiProblem invalidLink() {
        return ApiProblem.badRequest(INVALID_LINK);
    }
```
The invitation is always looked up **before** the tenant is checked. A token with a tampered or unknown tenant prefix therefore fails the lookup with the uniform 400, and never reaches `tenants.current()`, which would give 404.

`backend/src/main/java/com/nexusops/identity/web/PublicInvitationController.java`:
```java
package com.nexusops.identity.web;

import com.nexusops.identity.application.AcceptedInvitation;
import com.nexusops.identity.application.InvitationPreview;
import com.nexusops.identity.application.InvitationService;
import com.nexusops.shared.ratelimit.RateLimits;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Public invitation routes — both are listed in PublicEndpoints and rate-limited per IP. */
@RestController
@RequestMapping("/api/v1/invitations")
class PublicInvitationController {

    record AcceptRequest(@NotBlank @Size(max = 200) String token, @NotBlank @Size(max = 80) String firstName,
            @NotBlank @Size(max = 80) String lastName, @NotNull @Size(max = 128) String password) {}

    private final InvitationService invitations;
    private final RateLimits rateLimits;

    PublicInvitationController(InvitationService invitations, RateLimits rateLimits) {
        this.invitations = invitations;
        this.rateLimits = rateLimits;
    }

    @GetMapping("/preview")
    InvitationPreview preview(@RequestParam @Size(max = 200) String token, HttpServletRequest http) {
        rateLimits.checkPublic("invitation-preview", http.getRemoteAddr());
        return invitations.preview(token);
    }

    @PostMapping("/accept")
    @ResponseStatus(HttpStatus.CREATED)
    AcceptedInvitation accept(@Valid @RequestBody AcceptRequest request, HttpServletRequest http) {
        rateLimits.checkPublic("invitation-accept", http.getRemoteAddr());
        return invitations.accept(request.token(), request.firstName(), request.lastName(), request.password());
    }
}
```

Add to `PublicEndpoints.ROUTES`: `"GET /api/v1/invitations/preview"` and `"POST /api/v1/invitations/accept"`.

- [ ] **Step 3: Run the tests and the build**

Run: `./gradlew test --tests '*InvitationAcceptIT' --tests '*SignupIT' --tests '*EndpointAuthorizationCoverageTest'`, then `./gradlew build`
Expected: PASS (6 + 8 + 1), then a green build. The coverage test now sees the two new public routes in `PublicEndpoints` and the authenticated invitation routes with `@PreAuthorize`.

- [ ] **Step 4: Commit**

```bash
cd /Users/user/Desktop/nexusops && git add backend && git commit -m "feat(identity): public invitation preview and race-safe acceptance"
```

---

### Task 8: User administration — list/filter/page, rename, disable/enable, assign roles, last-owner protection

**Files:**
- Create:
  - `shared/web/PageResponse.java`, `shared/web/Paging.java`;
  - `shared/security/CurrentAuthorities.java`;
  - `identity/application/UserAdminService.java`, `identity/application/UserView.java`, `identity/application/UpdateUserCommand.java`;
  - `identity/web/UserController.java`.
- Modify:
  - `identity/domain/User.java` (rename, disable, enable, replaceRoles, getters);
  - `identity/domain/UserRepository.java` (Specifications, owner count).
- Test: `shared/web/PagingTest.java`, `identity/UserAdminIT.java`

**Interfaces:**
- Produces:
  - `record PageResponse<T>(List<T> items, int page, int size, long total)`;
  - `Paging.of(Integer page, Integer size): Pageable`, where page defaults to 0 and size to 20 (1..100); otherwise 400 with field `page` or `size`;
  - `PageResponse.from(Page<S>, Function<S,T>)`.
- Produces `CurrentAuthorities.codes(): Set<String>` and `CurrentAuthorities.has(String code): boolean`.
- Produces `User`:
  - `rename(String first, String last)`, where null means unchanged;
  - `disable()`, which sets status DISABLED and bumps `tokenVersion`;
  - `enable()`;
  - `replaceRoles(Set<UUID>)`;
  - `getLastLoginAt()`, `getCreatedAt()`.
- Produces:
  - `record UserView(UUID id, String email, String firstName, String lastName, String status, boolean emailVerified, List<UserView.RoleRef> roles, Instant lastLoginAt, Instant createdAt)`, with `record RoleRef(UUID id, String name)`;
  - `record UpdateUserCommand(String firstName, String lastName, String status)`.
- Produces `UserAdminService`:
  - `list(String status, String q, Integer page, Integer size): PageResponse<UserView>`;
  - `get(UUID)`;
  - `update(UUID, UpdateUserCommand)`;
  - `assignRoles(UUID, Set<UUID>)`.
- Produces these endpoints:
  - `GET /api/v1/users?status=&q=&page=&size=` (`identity.user.read`);
  - `GET /api/v1/users/{id}` (`identity.user.read`);
  - `PATCH /api/v1/users/{id}` (`hasAnyAuthority('identity.user.update','identity.user.disable')`, with the specific permission checked in the service);
  - `PUT /api/v1/users/{id}/roles` (`authorization.role.assign`), body `{"roleIds":[…]}`.

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/com/nexusops/shared/web/PagingTest.java`:
```java
package com.nexusops.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PagingTest {

    @Test
    void defaultsAndBounds() {
        assertThat(Paging.of(null, null).getPageNumber()).isZero();
        assertThat(Paging.of(null, null).getPageSize()).isEqualTo(20);
        assertThat(Paging.of(3, 100).getPageSize()).isEqualTo(100);
    }

    @Test
    void rejectsOutOfRangeValuesAsFieldErrors() {
        assertThatThrownBy(() -> Paging.of(-1, 10)).isInstanceOfSatisfying(ApiProblem.class,
                p -> assertThat(p.errors().getFirst().field()).isEqualTo("page"));
        assertThatThrownBy(() -> Paging.of(0, 0)).isInstanceOfSatisfying(ApiProblem.class,
                p -> assertThat(p.errors().getFirst().field()).isEqualTo("size"));
        assertThatThrownBy(() -> Paging.of(0, 101)).isInstanceOf(ApiProblem.class);
    }
}
```

`backend/src/test/java/com/nexusops/identity/UserAdminIT.java`:
```java
package com.nexusops.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestMembers;
import com.nexusops.support.TestRoles;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Session;
import com.nexusops.support.TestTenants.Workspace;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class UserAdminIT extends IntegrationTestSupport {

    static final String LAST_OWNER = "A workspace needs at least one active owner.";
    static final String OWNER_ONLY = "Only workspace owners can manage the owner role.";

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Session owner;
    UUID ownerId;
    UUID ownerRole;
    UUID support;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("users"));
        OwnerJdbc.jdbc().update("update tenants set plan_code = 'BUSINESS' where id = ?", ws.tenantId());
        owner = TestTenants.login(mvc, ws);
        ownerId = userId(ws.email());
        ownerRole = TestRoles.system(ws.tenantId(), "TENANT_OWNER");
        support = TestRoles.create(mvc, owner, "Support", "identity.user.read", "tenant.settings.read");
    }

    private UUID userId(String email) {
        return OwnerJdbc.ownerAs(ws.tenantId()).queryForObject("select id from users where email = ?", UUID.class, email);
    }

    private ResultActions as(Session s, MockHttpServletRequestBuilder b) throws Exception {
        return mvc.perform(b.header("Authorization", "Bearer " + s.accessToken()));
    }

    private ResultActions setStatus(Session s, UUID id, String status) throws Exception {
        return as(s, patch("/api/v1/users/" + id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + status + "\"}"));
    }

    private ResultActions setRoles(Session s, UUID id, UUID... roleIds) throws Exception {
        StringBuilder json = new StringBuilder("{\"roleIds\":[");
        for (int i = 0; i < roleIds.length; i++) {
            json.append(i == 0 ? "" : ",").append('"').append(roleIds[i]).append('"');
        }
        return as(s, put("/api/v1/users/" + id + "/roles").contentType(MediaType.APPLICATION_JSON)
                .content(json.append("]}").toString()));
    }

    @Test
    void listsWithPagingAndFilters() throws Exception {
        members.create(ws.tenantId(), Set.of(support));
        members.create(ws.tenantId(), Set.of(support));
        as(owner, get("/api/v1/users").param("size", "2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.size").value(2));
        as(owner, get("/api/v1/users").param("size", "2").param("page", "1"))
                .andExpect(jsonPath("$.items.length()").value(1));
        as(owner, get("/api/v1/users").param("q", "MEMBER-")).andExpect(jsonPath("$.total").value(2));
        as(owner, get("/api/v1/users").param("status", "ACTIVE")).andExpect(jsonPath("$.total").value(3));
        as(owner, get("/api/v1/users").param("size", "101")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("size"));
        as(owner, get("/api/v1/users").param("status", "BOGUS")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("status"));
    }

    @Test
    void getsAndRenamesAUser() throws Exception {
        UUID member = userId(members.create(ws.tenantId(), Set.of(support)).email());
        as(owner, get("/api/v1/users/" + member)).andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0].name").value("Support"))
                .andExpect(jsonPath("$.emailVerified").value(true));
        as(owner, patch("/api/v1/users/" + member).contentType(MediaType.APPLICATION_JSON)
                .content("{\"firstName\":\" Grace \",\"lastName\":\"Hopper\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.firstName").value("Grace"));
        as(owner, get("/api/v1/users/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void disablingKillsLiveSessions() throws Exception {
        Workspace memberWs = members.create(ws.tenantId(), Set.of(support));
        Session member = TestTenants.login(mvc, memberWs);
        UUID memberId = userId(memberWs.email());
        as(member, get("/api/v1/me")).andExpect(status().isOk());

        setStatus(owner, memberId, "DISABLED").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISABLED"));

        as(member, get("/api/v1/me")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("nexus_rt", member.refreshToken())))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"workspace":"%s","email":"%s","password":"%s"}""".formatted(ws.slug(), memberWs.email(), memberWs.password())))
                .andExpect(status().isUnauthorized());

        setStatus(owner, memberId, "ACTIVE").andExpect(status().isOk());
        TestTenants.login(mvc, memberWs);
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForList("select action from audit_events", String.class))
                .contains("UserDisabled", "UserEnabled");
    }

    @Test
    void ownersCannotLockThemselvesOrTheWorkspaceOut() throws Exception {
        setStatus(owner, ownerId, "DISABLED").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("You can't disable your own account."));
        setRoles(owner, ownerId, support).andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(LAST_OWNER));

        UUID coOwner = userId(members.create(ws.tenantId(), Set.of(support)).email());
        setRoles(owner, coOwner, ownerRole).andExpect(status().isOk());
        setRoles(owner, ownerId, support).andExpect(status().isOk()); // another owner remains
    }

    @Test
    void concurrentMutualDemotionKeepsAnOwner() throws Exception {
        Workspace secondWs = members.create(ws.tenantId(), Set.of(ownerRole));
        Session second = TestTenants.login(mvc, secondWs);
        UUID secondId = userId(secondWs.email());

        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        List<Future<Integer>> results = new ArrayList<>();
        results.add(pool.submit(() -> { start.await(); return setRoles(owner, secondId, support).andReturn().getResponse().getStatus(); }));
        results.add(pool.submit(() -> { start.await(); return setRoles(second, ownerId, support).andReturn().getResponse().getStatus(); }));
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : results) statuses.add(f.get(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForObject("""
                select count(*) from users u join user_roles ur on ur.user_id = u.id
                where ur.role_id = ? and u.status = 'ACTIVE'""", Long.class, ownerRole)).isOne();
    }

    @Test
    void escalationGuardsOnAssignmentAndDisable() throws Exception {
        UUID assigner = TestRoles.create(mvc, owner, "Assigner", "authorization.role.assign", "identity.user.read",
                "identity.user.disable");
        Session member = TestTenants.login(mvc, members.create(ws.tenantId(), Set.of(assigner)));
        UUID target = userId(members.create(ws.tenantId(), Set.of()).email());
        setRoles(member, target, support).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("You can't grant permissions you don't have."));
        setRoles(member, target, ownerRole).andExpect(status().isForbidden()).andExpect(jsonPath("$.detail").value(OWNER_ONLY));
        setStatus(member, ownerId, "DISABLED").andExpect(status().isForbidden()).andExpect(jsonPath("$.detail").value(OWNER_ONLY));
        as(member, patch("/api/v1/users/" + target).contentType(MediaType.APPLICATION_JSON).content("{\"firstName\":\"X\"}"))
                .andExpect(status().isForbidden()); // lacks identity.user.update
    }

    @Test
    void enablingRespectsTheSeatLimit() throws Exception {
        OwnerJdbc.jdbc().update("update tenants set plan_code = 'FREE' where id = ?", ws.tenantId());
        members.create(ws.tenantId(), Set.of(support));
        UUID third = userId(members.create(ws.tenantId(), Set.of(support)).email());
        setStatus(owner, third, "DISABLED").andExpect(status().isOk());
        as(owner, post("/api/v1/invitations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"seat@x.test\",\"roleId\":\"" + support + "\"}")).andExpect(status().isCreated());
        setStatus(owner, third, "ACTIVE").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Your plan allows 3 users. Upgrade to add more."));
    }
}
```

Run: `cd backend && ./gradlew test --tests '*PagingTest' --tests '*UserAdminIT'`
Expected: compilation FAILS (`Paging` is missing), then the HTTP tests fail with 404.

- [ ] **Step 2: Shared helpers**

`backend/src/main/java/com/nexusops/shared/web/PageResponse.java`:
```java
package com.nexusops.shared.web;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

public record PageResponse<T>(List<T> items, int page, int size, long total) {

    public static <S, T> PageResponse<T> from(Page<S> page, Function<S, T> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(), page.getNumber(), page.getSize(),
                page.getTotalElements());
    }
}
```

`backend/src/main/java/com/nexusops/shared/web/Paging.java`:
```java
package com.nexusops.shared.web;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** `?page` (0-based, default 0) and `?size` (default 20, 1..100), per the plan's pagination constraint. */
public final class Paging {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    private Paging() {}

    public static Pageable of(Integer page, Integer size) {
        return of(page, size, Sort.unsorted());
    }

    public static Pageable of(Integer page, Integer size, Sort sort) {
        int p = page == null ? 0 : page;
        int s = size == null ? DEFAULT_SIZE : size;
        if (p < 0) {
            throw ApiProblem.badRequestField("page", "Page must be 0 or greater.");
        }
        if (s < 1 || s > MAX_SIZE) {
            throw ApiProblem.badRequestField("size", "Size must be between 1 and " + MAX_SIZE + ".");
        }
        return PageRequest.of(p, s, sort);
    }
}
```

`backend/src/main/java/com/nexusops/shared/security/CurrentAuthorities.java`:
```java
package com.nexusops.shared.security;

import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

public final class CurrentAuthorities {

    private CurrentAuthorities() {}

    public static Set<String> codes() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return Set.of();
        }
        return authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toUnmodifiableSet());
    }

    public static boolean has(String code) {
        return codes().contains(code);
    }
}
```

- [ ] **Step 3: Domain and repository**

Add to `User`:
```java
    public void rename(String newFirstName, String newLastName) {
        if (newFirstName != null) this.firstName = newFirstName;
        if (newLastName != null) this.lastName = newLastName;
        this.updatedAt = Instant.now();
    }

    /** Disabling also invalidates every outstanding access token (token version bump). */
    public void disable() {
        this.status = UserStatus.DISABLED;
        bumpTokenVersion();
    }

    public void enable() {
        this.status = UserStatus.ACTIVE;
        this.updatedAt = Instant.now();
    }

    public void replaceRoles(java.util.Set<UUID> newRoleIds) {
        this.roleIds.clear();
        this.roleIds.addAll(newRoleIds);
        this.updatedAt = Instant.now();
    }

    public Instant getLastLoginAt() { return lastLoginAt; }
    public Instant getCreatedAt() { return createdAt; }
```

Change `UserRepository` to `extends JpaRepository<User, UUID>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<User>` and add:
```java
    @org.springframework.data.jpa.repository.Query(
            "select count(u) from User u join u.roleIds r where r = :roleId and u.status = :status")
    long countByRoleAndStatus(@org.springframework.data.repository.query.Param("roleId") UUID roleId,
            @org.springframework.data.repository.query.Param("status") UserStatus status);
```

- [ ] **Step 4: Service, views and controller**

`backend/src/main/java/com/nexusops/identity/application/UserView.java`:
```java
package com.nexusops.identity.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record UserView(UUID id, String email, String firstName, String lastName, String status, boolean emailVerified,
        List<RoleRef> roles, Instant lastLoginAt, Instant createdAt) {

    public record RoleRef(UUID id, String name) {}
}
```

`backend/src/main/java/com/nexusops/identity/application/UpdateUserCommand.java`:
```java
package com.nexusops.identity.application;

/** Partial update: null fields are left unchanged. {@code status} is ACTIVE or DISABLED. */
public record UpdateUserCommand(String firstName, String lastName, String status) {}
```

`backend/src/main/java/com/nexusops/identity/application/UserAdminService.java`:
```java
package com.nexusops.identity.application;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.authorization.AuthorizationService;
import com.nexusops.identity.domain.InvitationRepository;
import com.nexusops.identity.domain.RefreshTokenRepository;
import com.nexusops.identity.domain.RevokeReason;
import com.nexusops.identity.domain.User;
import com.nexusops.identity.domain.UserRepository;
import com.nexusops.identity.domain.UserStatus;
import com.nexusops.identity.security.CurrentUser;
import com.nexusops.identity.security.PrincipalStateCache;
import com.nexusops.shared.db.AfterCommit;
import com.nexusops.shared.db.TenantLocks;
import com.nexusops.shared.security.CurrentAuthorities;
import com.nexusops.shared.web.ApiProblem;
import com.nexusops.shared.web.PageResponse;
import com.nexusops.shared.web.Paging;
import com.nexusops.tenancy.TenantDirectory;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tenant user administration. Owner-set changes are serialized per tenant (TenantLocks "owners"). */
@Service
public class UserAdminService {

    static final String LAST_OWNER = "A workspace needs at least one active owner.";
    static final String FORBIDDEN = "You do not have permission to perform this action.";

    private final UserRepository users;
    private final InvitationRepository invitations;
    private final RefreshTokenRepository refreshTokens;
    private final AuthorizationService authorization;
    private final TenantDirectory tenants;
    private final TenantLocks locks;
    private final PrincipalStateCache principals;
    private final AuditService audit;

    UserAdminService(UserRepository users, InvitationRepository invitations, RefreshTokenRepository refreshTokens,
            AuthorizationService authorization, TenantDirectory tenants, TenantLocks locks, PrincipalStateCache principals,
            AuditService audit) {
        this.users = users;
        this.invitations = invitations;
        this.refreshTokens = refreshTokens;
        this.authorization = authorization;
        this.tenants = tenants;
        this.locks = locks;
        this.principals = principals;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<UserView> list(String status, String q, Integer page, Integer size) {
        CurrentUser.require();
        Specification<User> spec = (root, query, cb) -> cb.conjunction();
        if (status != null && !status.isBlank()) {
            UserStatus wanted = parseStatus(status);
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), wanted));
        }
        if (q != null && !q.isBlank()) {
            String like = "%" + q.strip().toLowerCase(Locale.ROOT).replace("%", "\\%").replace("_", "\\_") + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("email")), like, '\\'),
                    cb.like(cb.lower(root.get("firstName")), like, '\\'),
                    cb.like(cb.lower(root.get("lastName")), like, '\\')));
        }
        var result = users.findAll(spec, Paging.of(page, size, Sort.by("createdAt", "id")));
        Map<UUID, String> names = authorization.roleNames(
                result.getContent().stream().flatMap(u -> u.getRoleIds().stream()).toList());
        return PageResponse.from(result, u -> view(u, names));
    }

    @Transactional(readOnly = true)
    public UserView get(UUID id) {
        CurrentUser.require();
        User user = find(id);
        return view(user, authorization.roleNames(user.getRoleIds()));
    }

    @Transactional
    public UserView update(UUID id, UpdateUserCommand command) {
        CurrentUser actor = CurrentUser.require();
        User user = find(id);
        boolean renaming = command.firstName() != null || command.lastName() != null;
        if (renaming) {
            requireAuthority("identity.user.update");
            Map<String, Object> before = Map.of("firstName", user.getFirstName(), "lastName", user.getLastName());
            user.rename(command.firstName() == null ? null : Names.require(command.firstName(), "firstName"),
                    command.lastName() == null ? null : Names.require(command.lastName(), "lastName"));
            audit.record(AuditEntry.of("UserUpdated", "User", id).withBefore(before)
                    .withAfter(Map.of("firstName", user.getFirstName(), "lastName", user.getLastName())));
        }
        if (command.status() != null) {
            requireAuthority("identity.user.disable");
            UserStatus target = parseStatus(command.status());
            if (target == UserStatus.DISABLED && user.getStatus() != UserStatus.DISABLED) {
                disable(actor, user);
            } else if (target == UserStatus.ACTIVE && user.getStatus() == UserStatus.DISABLED) {
                enable(user);
            } else if (target == UserStatus.INVITED) {
                throw ApiProblem.badRequestField("status", "Status must be ACTIVE or DISABLED.");
            }
        }
        users.flush();
        return view(user, authorization.roleNames(user.getRoleIds()));
    }

    @Transactional
    public UserView assignRoles(UUID id, Set<UUID> requested) {
        CurrentUser.require();
        User user = find(id);
        Set<UUID> wanted = requested == null ? Set.of() : Set.copyOf(requested);
        Set<UUID> current = user.getRoleIds();
        Set<UUID> added = difference(wanted, current);
        Set<UUID> removed = difference(current, wanted);
        if (!added.isEmpty()) {
            authorization.checkGrantable(added);
        }
        UUID ownerRole = authorization.ownerRoleId();
        if (removed.contains(ownerRole)) {
            authorization.requireOwnerActor();
            locks.lock("owners");
            if (user.getStatus() == UserStatus.ACTIVE && users.countByRoleAndStatus(ownerRole, UserStatus.ACTIVE) <= 1) {
                throw ApiProblem.conflict(LAST_OWNER);
            }
        }
        Map<UUID, String> names = authorization.roleNames(union(current, wanted));
        user.replaceRoles(wanted);
        users.flush();
        audit.record(AuditEntry.of("UserRolesChanged", "User", id)
                .withBefore(Map.of("roles", sortedNames(current, names)))
                .withAfter(Map.of("roles", sortedNames(wanted, names))));
        evictAfterCommit(user);
        return view(user, names);
    }

    private void disable(CurrentUser actor, User user) {
        if (user.getId().equals(actor.userId())) {
            throw ApiProblem.conflict("You can't disable your own account.");
        }
        UUID ownerRole = authorization.ownerRoleId();
        if (user.getRoleIds().contains(ownerRole)) {
            authorization.requireOwnerActor();
            locks.lock("owners");
            if (users.countByRoleAndStatus(ownerRole, UserStatus.ACTIVE) <= 1) {
                throw ApiProblem.conflict(LAST_OWNER);
            }
        }
        user.disable();
        refreshTokens.revokeAllForUser(user.getId(), RevokeReason.LOGOUT_ALL, Instant.now());
        audit.record(AuditEntry.of("UserDisabled", "User", user.getId()));
        evictAfterCommit(user);
    }

    private void enable(User user) {
        locks.lock("seats");
        Integer maxUsers = tenants.currentLimits().maxUsers();
        if (maxUsers != null
                && users.countByStatus(UserStatus.ACTIVE) + invitations.countPending(Instant.now()) >= maxUsers) {
            throw ApiProblem.conflict("Your plan allows " + maxUsers + " users. Upgrade to add more.");
        }
        user.enable();
        audit.record(AuditEntry.of("UserEnabled", "User", user.getId()));
        evictAfterCommit(user);
    }

    private void evictAfterCommit(User user) {
        UUID tenantId = user.getTenantId();
        UUID userId = user.getId();
        AfterCommit.run(() -> principals.evict(tenantId, userId));
    }

    private User find(UUID id) {
        return users.findById(id).orElseThrow(() -> ApiProblem.notFound("User not found."));
    }

    private static void requireAuthority(String code) {
        if (!CurrentAuthorities.has(code)) {
            throw ApiProblem.forbidden(FORBIDDEN);
        }
    }

    private static UserStatus parseStatus(String raw) {
        try {
            return UserStatus.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiProblem.badRequestField("status", "Status must be ACTIVE or DISABLED.");
        }
    }

    private static Set<UUID> difference(Set<UUID> a, Set<UUID> b) {
        Set<UUID> result = new HashSet<>(a);
        result.removeAll(b);
        return result;
    }

    private static Set<UUID> union(Collection<UUID> a, Collection<UUID> b) {
        Set<UUID> result = new HashSet<>(a);
        result.addAll(b);
        return result;
    }

    private static List<String> sortedNames(Collection<UUID> ids, Map<UUID, String> names) {
        return ids.stream().map(names::get).sorted().toList();
    }

    private static UserView view(User u, Map<UUID, String> roleNames) {
        List<UserView.RoleRef> roles = u.getRoleIds().stream()
                .map(r -> new UserView.RoleRef(r, roleNames.get(r)))
                .sorted(java.util.Comparator.comparing(UserView.RoleRef::name))
                .toList();
        return new UserView(u.getId(), u.getEmail(), u.getFirstName(), u.getLastName(), u.getStatus().name(),
                u.isEmailVerified(), roles, u.getLastLoginAt(), u.getCreatedAt());
    }
}
```

In `assignRoles`, the owner-count check runs **after** `locks.lock("owners")`, so two concurrent demotions serialize. The second one then sees the first one's committed result and is refused with 409.

`backend/src/main/java/com/nexusops/identity/web/UserController.java`:
```java
package com.nexusops.identity.web;

import com.nexusops.identity.application.UpdateUserCommand;
import com.nexusops.identity.application.UserAdminService;
import com.nexusops.identity.application.UserView;
import com.nexusops.shared.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
class UserController {

    record UpdateUserRequest(@Size(max = 80) String firstName, @Size(max = 80) String lastName,
            @Size(max = 20) String status) {}

    record AssignRolesRequest(@NotNull Set<UUID> roleIds) {}

    private final UserAdminService users;

    UserController(UserAdminService users) {
        this.users = users;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('identity.user.read')")
    PageResponse<UserView> list(@RequestParam(required = false) String status, @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return users.list(status, q, page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('identity.user.read')")
    UserView get(@PathVariable UUID id) {
        return users.get(id);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('identity.user.update', 'identity.user.disable')")
    UserView update(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
        return users.update(id, new UpdateUserCommand(request.firstName(), request.lastName(), request.status()));
    }

    @PutMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('authorization.role.assign')")
    UserView assignRoles(@PathVariable UUID id, @Valid @RequestBody AssignRolesRequest request) {
        return users.assignRoles(id, request.roleIds());
    }
}
```

- [ ] **Step 5: Run the tests and the build**

Run: `./gradlew test --tests '*PagingTest' --tests '*UserAdminIT'`, then `./gradlew build`
Expected: PASS (2 + 7), then a green build including `EndpointAuthorizationCoverageTest` and `ModularityTest`.

If `concurrentMutualDemotionKeepsAnOwner` ever produces `[200, 200]`, the lock is being taken after the owner count was read. Fix the ordering; don't relax the assertion.

- [ ] **Step 6: Commit**

```bash
cd /Users/user/Desktop/nexusops && git add backend && git commit -m "feat(identity): user administration — paging/filtering, rename, disable/enable, role assignment, last-owner protection"
```

---

### Task 9: Audit read API

**Files:**
- Create:
  - `audit/AuditEventView.java`, `audit/AuditQuery.java`, `audit/AuditQueryService.java`;
  - `audit/web/AuditController.java`.
- Test: `audit/AuditApiIT.java`

**Interfaces:**
- Produces `record AuditQuery(String action, String entityType, UUID actorId, String from, String to, Integer page, Integer size)`, where `from`/`to` are ISO-8601 instants.
- Produces `record AuditEventView(UUID id, Instant occurredAt, String actorType, UUID actorId, String action, String entityType, String entityId, String ip, String userAgent, String requestId, String correlationId, JsonNode before, JsonNode after, JsonNode metadata)`, with `JsonNode` = `tools.jackson.databind.JsonNode`.
- Produces `AuditQueryService.search(AuditQuery): PageResponse<AuditEventView>`, newest first.
- Produces `GET /api/v1/audit-events?action=&entityType=&actorId=&from=&to=&page=&size=` (`audit.event.read`). Invalid filters return 400 with a field error on `action`, `entityType`, `from` or `to`.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/nexusops/audit/AuditApiIT.java`:
```java
package com.nexusops.audit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestMembers;
import com.nexusops.support.TestRoles;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Session;
import com.nexusops.support.TestTenants.Workspace;
import java.time.Instant;
import java.util.Set;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@AutoConfigureMockMvc
class AuditApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Session owner;

    @BeforeEach
    void activity() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("aud"));
        owner = TestTenants.login(mvc, ws);
        mvc.perform(patch("/api/v1/tenant").header("Authorization", "Bearer " + owner.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Audited Co\"}")).andExpect(status().isOk());
        TestRoles.create(mvc, owner, "Auditors", "audit.event.read");
    }

    private ResultActions search(Session s, String... params) throws Exception {
        var request = get("/api/v1/audit-events").header("Authorization", "Bearer " + s.accessToken());
        for (int i = 0; i < params.length; i += 2) request.param(params[i], params[i + 1]);
        return mvc.perform(request);
    }

    @Test
    void listsTheTenantsEventsNewestFirst() throws Exception {
        search(owner).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].action").value("RoleCreated"))
                .andExpect(jsonPath("$.items[*].action", Matchers.hasItems("TenantSettingsUpdated", "LoginSucceeded",
                        "TenantCreated")))
                .andExpect(jsonPath("$.items[0].after.name").value("Auditors"))
                .andExpect(jsonPath("$.total", Matchers.greaterThanOrEqualTo(5)));
    }

    @Test
    void filtersAndPages() throws Exception {
        search(owner, "action", "TenantSettingsUpdated").andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].before.name").value(ws.slug() + " Inc"));
        search(owner, "entityType", "Role").andExpect(jsonPath("$.items[*].entityType", Matchers.everyItem(Matchers.is("Role"))));
        search(owner, "from", Instant.now().plusSeconds(60).toString()).andExpect(jsonPath("$.total").value(0));
        search(owner, "size", "1").andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void invalidFiltersAreFieldErrors() throws Exception {
        search(owner, "action", "drop table").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("action"));
        search(owner, "from", "yesterday").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("from"));
        search(owner, "from", "2026-10-05T10:00:00Z", "to", "2026-10-04T10:00:00Z").andExpect(status().isBadRequest());
    }

    @Test
    void requiresTheAuditPermission() throws Exception {
        var reader = TestRoles.create(mvc, owner, "Readers", "identity.user.read");
        Session member = TestTenants.login(mvc, members.create(ws.tenantId(), Set.of(reader)));
        search(member).andExpect(status().isForbidden());
    }
}
```

Run: `cd backend && ./gradlew test --tests '*AuditApiIT'`
Expected: FAIL (404 / compilation).

- [ ] **Step 2: Implement**

`backend/src/main/java/com/nexusops/audit/AuditQuery.java`:
```java
package com.nexusops.audit;

import java.util.UUID;

public record AuditQuery(String action, String entityType, UUID actorId, String from, String to, Integer page,
        Integer size) {}
```

`backend/src/main/java/com/nexusops/audit/AuditEventView.java`:
```java
package com.nexusops.audit;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record AuditEventView(UUID id, Instant occurredAt, String actorType, UUID actorId, String action,
        String entityType, String entityId, String ip, String userAgent, String requestId, String correlationId,
        JsonNode before, JsonNode after, JsonNode metadata) {}
```

`backend/src/main/java/com/nexusops/audit/AuditQueryService.java`:
```java
package com.nexusops.audit;

import com.nexusops.shared.TenantContext;
import com.nexusops.shared.web.ApiProblem;
import com.nexusops.shared.web.PageResponse;
import com.nexusops.shared.web.Paging;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Read side of the append-only audit log. RLS scopes rows; the explicit tenant predicate is belt and braces. */
@Service
public class AuditQueryService {

    private static final Pattern ACTION = Pattern.compile("[A-Za-z]{1,60}");
    private static final Pattern ENTITY_TYPE = Pattern.compile("[A-Za-z]{1,60}");

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    AuditQueryService(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditEventView> search(AuditQuery query) {
        UUID tenantId = TenantContext.requireTenantId();
        var pageable = Paging.of(query.page(), query.size());
        List<String> where = new ArrayList<>(List.of("tenant_id = ?"));
        List<Object> args = new ArrayList<>(List.of(tenantId));
        if (notBlank(query.action())) {
            require(ACTION, query.action(), "action");
            where.add("action = ?");
            args.add(query.action());
        }
        if (notBlank(query.entityType())) {
            require(ENTITY_TYPE, query.entityType(), "entityType");
            where.add("entity_type = ?");
            args.add(query.entityType());
        }
        if (query.actorId() != null) {
            where.add("actor_id = ?");
            args.add(query.actorId());
        }
        Instant from = parseInstant(query.from(), "from");
        Instant to = parseInstant(query.to(), "to");
        if (from != null && to != null && from.isAfter(to)) {
            throw ApiProblem.badRequestField("from", "'from' must not be after 'to'.");
        }
        if (from != null) {
            where.add("occurred_at >= ?");
            args.add(Timestamp.from(from));
        }
        if (to != null) {
            where.add("occurred_at <= ?");
            args.add(Timestamp.from(to));
        }
        String condition = String.join(" and ", where);
        Long total = jdbc.queryForObject("select count(*) from audit_events where " + condition, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageable.getPageSize());
        pageArgs.add(pageable.getOffset());
        List<AuditEventView> items = jdbc.query("""
                select id, occurred_at, actor_type, actor_id, action, entity_type, entity_id, ip, user_agent,
                       request_id, correlation_id, before::text as before, after::text as after, metadata::text as metadata
                from audit_events where %s
                order by occurred_at desc, id desc
                limit ? offset ?""".formatted(condition), this::row, pageArgs.toArray());
        return new PageResponse<>(items, pageable.getPageNumber(), pageable.getPageSize(), total == null ? 0 : total);
    }

    private AuditEventView row(ResultSet rs, int n) throws SQLException {
        return new AuditEventView(rs.getObject("id", UUID.class), rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("actor_type"), rs.getObject("actor_id", UUID.class), rs.getString("action"),
                rs.getString("entity_type"), rs.getString("entity_id"), rs.getString("ip"), rs.getString("user_agent"),
                rs.getString("request_id"), rs.getString("correlation_id"), node(rs.getString("before")),
                node(rs.getString("after")), node(rs.getString("metadata")));
    }

    private JsonNode node(String value) {
        return value == null ? null : json.readTree(value);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static void require(Pattern pattern, String value, String field) {
        if (!pattern.matcher(value).matches()) {
            throw ApiProblem.badRequestField(field, "Invalid value.");
        }
    }

    private static Instant parseInstant(String value, String field) {
        if (!notBlank(value)) return null;
        try {
            return Instant.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw ApiProblem.badRequestField(field, "Use an ISO-8601 instant such as 2026-10-05T09:00:00Z.");
        }
    }
}
```

`backend/src/main/java/com/nexusops/audit/web/AuditController.java`:
```java
package com.nexusops.audit.web;

import com.nexusops.audit.AuditEventView;
import com.nexusops.audit.AuditQuery;
import com.nexusops.audit.AuditQueryService;
import com.nexusops.shared.web.PageResponse;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AuditController {

    private final AuditQueryService audit;

    AuditController(AuditQueryService audit) {
        this.audit = audit;
    }

    @GetMapping("/api/v1/audit-events")
    @PreAuthorize("hasAuthority('audit.event.read')")
    PageResponse<AuditEventView> search(@RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType, @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return audit.search(new AuditQuery(action, entityType, actorId, from, to, page, size));
    }
}
```

- [ ] **Step 3: Run the tests and the build**

Run: `./gradlew test --tests '*AuditApiIT'`, then `./gradlew build`
Expected: PASS (4), then a green build.

- [ ] **Step 4: Commit**

```bash
cd /Users/user/Desktop/nexusops && git add backend && git commit -m "feat(audit): paginated, filterable audit read API"
```

---

### Task 10: Cross-tenant 404 suite for every new id-bearing endpoint, stricter coverage test

**Files:**
- Create: `backend/src/test/java/com/nexusops/CrossTenantApiIT.java`
- Modify: `backend/src/test/java/com/nexusops/EndpointAuthorizationCoverageTest.java` (reject `permitAll()`/`true` expressions)

**Interfaces:**
- Consumes every endpoint from Tasks 4–9. No production code changes are expected. If a test fails, the bug is in the endpoint's service: fix the root cause there, and record it in the report.

- [ ] **Step 1: Write the suite**

`backend/src/test/java/com/nexusops/CrossTenantApiIT.java`:
```java
package com.nexusops;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestMembers;
import com.nexusops.support.TestRoles;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Session;
import com.nexusops.support.TestTenants.Workspace;
import java.util.Set;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Tenant A's owner (full permissions) probes every Tenant B resource id: always 404, never 403 or 200. */
@AutoConfigureMockMvc
class CrossTenantApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Session ownerA;
    Session ownerB;
    Workspace b;
    UUID roleB;
    UUID userB;
    UUID invitationB;

    @BeforeEach
    void twoTenants() throws Exception {
        Workspace a = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("xa"));
        b = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("xb"));
        ownerA = TestTenants.login(mvc, a);
        ownerB = TestTenants.login(mvc, b);
        roleB = TestRoles.create(mvc, ownerB, "SecretB", "identity.user.read");
        String memberEmail = members.create(b.tenantId(), Set.of(roleB)).email();
        userB = OwnerJdbc.ownerAs(b.tenantId()).queryForObject("select id from users where email = ?", UUID.class, memberEmail);
        String body = as(ownerB, post("/api/v1/invitations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"invitee-b@x.test\",\"roleId\":\"" + roleB + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        invitationB = UUID.fromString(JsonPath.read(body, "$.id"));
        as(ownerB, put("/api/v1/tenant/modules/CRM").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isOk());
    }

    private ResultActions as(Session s, MockHttpServletRequestBuilder b) throws Exception {
        return mvc.perform(b.header("Authorization", "Bearer " + s.accessToken()));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void everyIdBearingEndpointReturns404ForAnotherTenantsIds() throws Exception {
        as(ownerA, get("/api/v1/users/" + userB)).andExpect(status().isNotFound());
        as(ownerA, json(patch("/api/v1/users/" + userB), "{\"firstName\":\"Hacked\"}")).andExpect(status().isNotFound());
        as(ownerA, json(patch("/api/v1/users/" + userB), "{\"status\":\"DISABLED\"}")).andExpect(status().isNotFound());
        as(ownerA, json(put("/api/v1/users/" + userB + "/roles"), "{\"roleIds\":[]}")).andExpect(status().isNotFound());
        as(ownerA, get("/api/v1/roles/" + roleB)).andExpect(status().isNotFound());
        as(ownerA, json(patch("/api/v1/roles/" + roleB), "{\"name\":\"Hacked\"}")).andExpect(status().isNotFound());
        as(ownerA, json(put("/api/v1/roles/" + roleB + "/permissions"), "{\"permissions\":[]}")).andExpect(status().isNotFound());
        as(ownerA, delete("/api/v1/roles/" + roleB)).andExpect(status().isNotFound());
        as(ownerA, delete("/api/v1/invitations/" + invitationB)).andExpect(status().isNotFound());

        as(ownerB, get("/api/v1/users/" + userB)).andExpect(status().isOk()).andExpect(jsonPath("$.firstName").value("Mem"));
        as(ownerB, get("/api/v1/roles/" + roleB)).andExpect(jsonPath("$.name").value("SecretB"));
    }

    @Test
    void anotherTenantsRoleCannotBeGrantedOrInvitedWith() throws Exception {
        UUID ownUser = UUID.fromString(JsonPath.read(
                as(ownerA, get("/api/v1/users")).andReturn().getResponse().getContentAsString(), "$.items[0].id"));
        as(ownerA, json(put("/api/v1/users/" + ownUser + "/roles"), "{\"roleIds\":[\"" + roleB + "\"]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("roleIds"));
        as(ownerA, json(post("/api/v1/invitations"), "{\"email\":\"x@x.test\",\"roleId\":\"" + roleB + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("roleIds"));
    }

    @Test
    void listsNeverContainAnotherTenantsRows() throws Exception {
        as(ownerA, get("/api/v1/users")).andExpect(jsonPath("$.items[*].id", Matchers.not(Matchers.hasItem(userB.toString()))))
                .andExpect(jsonPath("$.total").value(1));
        as(ownerA, get("/api/v1/roles")).andExpect(jsonPath("$[*].id", Matchers.not(Matchers.hasItem(roleB.toString()))));
        as(ownerA, get("/api/v1/invitations")).andExpect(jsonPath("$", Matchers.empty()));
        as(ownerA, get("/api/v1/audit-events").param("size", "100"))
                .andExpect(jsonPath("$.items[*].entityId", Matchers.not(Matchers.hasItem(roleB.toString()))))
                .andExpect(jsonPath("$.items[*].entityId", Matchers.not(Matchers.hasItem(invitationB.toString()))));
        as(ownerA, get("/api/v1/tenant/modules")).andExpect(jsonPath("$[?(@.code == 'CRM')].enabled", Matchers.contains(false)));
        as(ownerA, get("/api/v1/me")).andExpect(jsonPath("$.modules", Matchers.empty()));
    }
}
```


In `EndpointAuthorizationCoverageTest`, after computing `secured`, also fail when the annotation's expression is trivially open:
```java
            var preAuthorize = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), PreAuthorize.class);
            if (preAuthorize == null) {
                preAuthorize = AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), PreAuthorize.class);
            }
            if (preAuthorize != null && preAuthorize.value().replace(" ", "").matches("(?i)permitAll\\(\\)|true")) {
                violations.add(handler.getMethod() + " (@PreAuthorize is trivially open: " + preAuthorize.value() + ")");
            }
```

- [ ] **Step 2: Run the suite**

Run: `cd backend && ./gradlew test --tests '*CrossTenantApiIT' --tests '*EndpointAuthorizationCoverageTest'`
Expected: PASS (3 + 1). These are characterization tests over code from Tasks 4–9, so they should pass first time.

Prove that they can fail: temporarily delete the size check in `AuthorizationService.checkGrantable` (`found.size() != …` → 400). Then confirm `anotherTenantsRoleCannotBeGrantedOrInvitedWith` FAILS, because the RLS `WITH CHECK` on `user_roles` now surfaces as a 500 instead of the 400 field error. Restore the check and record the RED output in the report.

- [ ] **Step 3: Full build and commit**

Run: `./gradlew build`. Expected: green.
```bash
cd /Users/user/Desktop/nexusops && git add backend && git commit -m "test: cross-tenant 404 suite for users, roles, invitations, modules and audit; reject trivially-open @PreAuthorize"
```

---

### Task 11: OpenAPI, ADR-0006, spec and README updates, final verification

**Files:**
- Create: `docs/decisions/0006-rate-limiting-and-proxy-trust.md`
- Modify:
  - `docs/api/openapi.json` (regenerated);
  - `docs/decisions/0004-permission-based-authorization.md`;
  - `docs/superpowers/specs/2026-10-04-platform-foundation-design.md` (new §16, and the §5 slug regex note);
  - `README.md`;
  - `backend/src/test/java/com/nexusops/OpenApiContractIT.java` (assert the new routes).

- [ ] **Step 1: Extend the contract test (RED), then regenerate**

In `OpenApiContractIT`, add these to the `contains(...)` list:
```java
"\"/api/v1/users\"", "\"/api/v1/users/{id}/roles\"", "\"/api/v1/roles/{id}/permissions\"", "\"/api/v1/permissions\"",
"\"/api/v1/invitations\"", "\"/api/v1/invitations/accept\"", "\"/api/v1/invitations/preview\"",
"\"/api/v1/tenant/modules/{code}\"", "\"/api/v1/audit-events\""
```
Run `cd backend && ./gradlew test --tests '*OpenApiContractIT'`. It should pass, because the routes already exist; if a route is missing, fix it. Then regenerate the checked-in contract:
`./gradlew test --tests '*OpenApiContractIT' -Dopenapi.export=true --rerun-tasks`, and confirm `docs/api/openapi.json` contains `/api/v1/audit-events`.

- [ ] **Step 2: ADR-0006**

`docs/decisions/0006-rate-limiting-and-proxy-trust.md`:
```markdown
# ADR-0006: Rate limiting and proxy trust

- Status: Accepted
- Date: 2026-10-05

## Context
Public auth endpoints (login, signup, verification, invitations, refresh) are targets for credential stuffing,
mail flooding and enumeration; the authenticated API needs fair-use protection per user. Per-IP limits are only
meaningful if the client IP is trustworthy behind nginx/ALB.

## Decision
- Redis token bucket (atomic Lua script using Redis server time), one bucket per key and rule.
- Rules and defaults are spec §9 values in `nexusops.rate-limits.rules`.
  - Pre-auth keys: `rl:ip:{ip}:{rule}`, plus `rl:ws:{sha256(workspace)}:login` for login. The workspace bucket
    stops a distributed guessing attack spread across many IPs.
  - Authenticated: `tenant:{tid}:user:{uid}:rl:api`.
- 429 problem+json with `Retry-After`.
- If Redis is unavailable, public auth routes fail **closed** (503) and the authenticated API fails **open**
  (logged). Abuse resistance matters more than availability for anonymous traffic; for authenticated users,
  availability wins.
- Client IP: Tomcat `RemoteIpValve` (`server.forward-headers-strategy: native`) trusts `X-Forwarded-For` only
  from internal proxy ranges. nginx, the edge, **overwrites** `X-Forwarded-For` with `$remote_addr`, so
  clients cannot inject an IP.

## Consequences
- Limits are shared across app instances (Redis), and they survive restarts for the length of the window.
- A Redis outage blocks logins and signups until it recovers. Accepted, and monitored via the WARN log.
- Behind an ALB in production, `server.tomcat.remoteip.internal-proxies` must match the ALB subnets.
```

Append to `docs/decisions/0004-permission-based-authorization.md` under Decision:
```markdown
- **Grantable vs effective (Plan 3):** effective permissions (request authorization) are gated by enabled modules;
  *grantable* permissions (what an actor may grant via roles/invitations/assignment) are the union of the actor's
  role permissions regardless of modules, so owners can prepare roles before enabling a module.
- The owner role is managed only by owners; the last active owner can't be disabled or demoted; owner-set changes
  serialize per tenant via `pg_advisory_xact_lock`.
- Permission-affecting changes (role permissions, role deletion, module toggles, user status/roles) evict the affected
  principal-cache entries after commit, with generation-guarded cache writes so no stale state can be re-cached.
```

- [ ] **Step 3: Spec and README**

In the spec, add `## 16. Deltas adopted in Plan 3`, copying the nine "Decisions taken while writing this plan" from this plan's header verbatim. In §5, under the `tenants` row, add a note: "Slug rule superseded by V5: `^[a-z0-9]+(-[a-z0-9]+)*$` and length 3–40."

In `README.md`, extend "Try the API" with an invitation example. The walkthrough is:
1. The owner creates a role (`POST /api/v1/roles`).
2. The owner invites someone (`POST /api/v1/invitations`).
3. The token is read from Mailpit.
4. The invitee previews the invitation (`GET /api/v1/invitations/preview?token=`), then accepts it (`POST /api/v1/invitations/accept`).
5. The invitee logs in.

Use port 8081 and the exact JSON shapes from Tasks 5–7. Add one line that the API is rate-limited (spec §9 defaults), and that local defaults can be changed through `nexusops.rate-limits.rules.*`.

- [ ] **Step 4: Final verification**

From the repo root:
```bash
make test && make e2e
```
Expected: all green. Then a live smoke test with `make up-all`:
1. signup → verify (token from the Mailpit API at `localhost:8025/api/v1/messages`) → login;
2. create a role → invite → preview → accept (token from Mailpit) → log in as the invitee;
3. the invitee's `GET /api/v1/tenant` matches the role's permissions;
4. the owner disables the invitee → the invitee's next `GET /api/v1/me` returns 401;
5. 11 rapid failed logins for one workspace → the 11th returns 429 with `Retry-After`;
6. `GET /api/v1/audit-events` shows the trail.

Then stop the app containers: `docker compose -f infra/docker/docker-compose.yml --profile app stop backend frontend ai-service`.

- [ ] **Step 5: Commit**

```bash
cd /Users/user/Desktop/nexusops && git add -A backend docs README.md && git commit -m "docs: OpenAPI contract, ADR-0006 rate limiting and proxy trust, ADR-0004 grantable permissions, spec §16, README invitation walkthrough"
```

---

## Exit criteria (blueprint Phase 3)

| Criterion | Proven by |
|---|---|
| Permissions, custom roles, module permissions | `RoleManagementIT`, `TenantModulesIT` |
| Endpoint authorization on every route | `EndpointAuthorizationCoverageTest` (now also rejects trivially-open expressions) |
| Audit events, and the read API | the audit assertions in every IT, plus `AuditApiIT` |
| Rate limiting | `RedisRateLimiterIT`, `RateLimitsTest`, `RateLimitIT`, `ClientIpIT` |
| **"User from Tenant A attempts every known Tenant B resource"** | `CrossTenantApiIT` plus Plan 2's `TenantIsolationIT` / `RlsBehaviourIT` |
| Invitations (Phase 2 leftover) | `InvitationIT`, `InvitationAcceptIT` |
| No privilege escalation; the workspace is never ownerless | `RoleManagementIT`, `InvitationIT`, `UserAdminIT` (including the concurrent mutual demotion) |
