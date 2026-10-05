# Design Spec — NexusOps Platform Foundation (Phases 1–3)

- **Status:** Draft, awaiting review
- **Date:** 2026-10-04
- **Source:** `docs/research/master-blueprint.md` (§10, §13–14, §18, §25–28, §30, §38 Phases 1–3, §40–44)

## 1. Intent

### Outcome
A secure, multi-tenant platform foundation that every later business module (CRM, Inventory, HelpDesk, HRMS, Workflows, AI) plugs into without re-solving tenancy, identity, authorization or audit.

### Audience
- The 3-person capstone team, who build on top of it.
- Academic reviewers: RQ1 asks for strong logical isolation in a shared architecture.
- Future pilot customers.

### Success criteria (exit criteria of blueprint Phases 1–3)
1. Backend, frontend and ai-service all start locally via Docker Compose, and CI passes.
2. Flyway migrations apply cleanly on an empty PostgreSQL 17.
3. Tenant A and Tenant B coexist. An automated suite proves that a Tenant A user cannot read, modify or detect any Tenant B resource. The proof covers both the application layer and the database layer (RLS).
4. Authorization is driven by permissions, not role names. Every endpoint has an explicit authorization rule, and a test enforces this.
5. Security-relevant actions produce immutable audit events.

### Non-goals (deferred)
- Business modules.
- Kafka/outbox.
- Object storage.
- OpenTelemetry export.
- Tenant-user MFA and SSO.
- Billing.
- AWS/Terraform.
- Any AI features (the ai-service is only a skeleton).

## 2. Confirmed decisions

| Topic | Decision |
|---|---|
| Runtime | JDK 25, Spring Boot 4.x, Gradle Kotlin DSL |
| Architecture | Modular monolith (ADR-0001) |
| Isolation | Shared DB + shared schema + `tenant_id`, app guards + PostgreSQL RLS (ADR-0002) |
| Auth | Self-built JWT access token (RS256, 15 min) + rotating opaque refresh token (ADR-0003) |
| Token storage | Access token in memory; refresh token in an httpOnly, Secure, SameSite=Strict cookie, path `/api/v1/auth` |
| User model | A user belongs to exactly one tenant; email is unique per tenant (case-insensitive) |
| Login | workspace slug + email + password |
| Onboarding | Self-serve signup → tenant (`PENDING_VERIFICATION`) + TENANT_OWNER → email verification → `ACTIVE`, Free plan |
| Authorization | Granular permission codes; roles are tenant-scoped bundles (ADR-0004) |
| Audit | Append-only table, written in the same transaction (ADR-0005) |
| Email | `MailSender` port; Mailpit adapter locally |
| Frontend | React, TS, Vite, Tailwind, shadcn/ui, React Router, TanStack Query, RHF + Zod |
| CI | GitHub Actions; trunk-based branching |
| Platform admin | Separate `platform_users` table; password + TOTP |

## 3. Architecture

```
React SPA ──HTTPS──▶ Spring Boot API (modular monolith) ──▶ PostgreSQL 17 (+pgvector, RLS)
                         │                                 ▶ Redis 7 (perm cache, rate limits)
                         └──▶ MailSender ──▶ Mailpit (local) / SES (later)
ai-service (FastAPI, /health only)
```

### Backend modules (`com.nexusops.*`)

| Module | Responsibility | May depend on |
|---|---|---|
| `shared` | Kernel: `TenantContext`, `TenantOwnedEntity`, errors, request context, ids, clock | — |
| `tenancy` | Tenants, plans, module catalog, tenant modules | shared |
| `identity` | Users, credentials, signup, login, tokens, verification, invitations | shared, tenancy, authorization, audit, notifications |
| `authorization` | Permissions, roles, assignments, permission resolution, escalation guard | shared, tenancy, audit |
| `audit` | `AuditService`, audit query API | shared |
| `notifications` | `MailSender` port + adapters, templates | shared |
| `platform` | Platform users, TOTP, cross-tenant administration | shared, tenancy, audit |

Each module has `api/` (controllers + DTOs), `application/` (services and transactions), `domain/` (entities and rules) and `infrastructure/` (repositories and adapters). Controllers contain no business logic, and entities are never exposed. Spring Modulith's `ApplicationModules.verify()` enforces the boundaries.

## 4. Tenant isolation design (central to the project)

### 4.1 Context source
- `TenantContext` holds `tenantId` and `userId`. It is set **only** by the JWT authentication filter, from the verified `tid` and `sub` claims. Request headers, paths and bodies never set it.
- Pre-authentication flows (signup, login, invitation acceptance, email verification) resolve the tenant from a server-side lookup: the slug, or the token hash. They then run inside `TenantContext.runAs(tenantId, …)`.

### 4.2 Two independent enforcement layers

**Application layer.**
- Every tenant-owned entity extends `TenantOwnedEntity`, whose `tenantId` uses Hibernate `@TenantId`. That means:
  - inserts are stamped automatically;
  - queries are filtered automatically.
- A `CurrentTenantIdentifierResolver` reads `TenantContext`. With no tenant, it returns a sentinel that matches nothing.
- Lookups by id are always tenant-filtered. A row that isn't found and a row from another tenant both return **404**, so the existence of another tenant's resources doesn't leak.

**Database layer (RLS).**
- Every tenant-owned table has `ENABLE` and `FORCE ROW LEVEL SECURITY`, with this policy:
  `USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)`, and the same expression in `WITH CHECK`.
- At the start of each transaction, the app runs `select set_config('app.tenant_id', ?, true)` (transaction-local).
- If no tenant is set, the setting is empty or NULL, the comparison yields NULL, and **zero rows** come back. It fails closed.
- The runtime connects as `nexusops_app`: not an owner, `NOBYPASSRLS`, DML only. Flyway connects as `nexusops_owner`.
- Platform cross-tenant access: an additional permissive policy `USING (current_setting('app.platform_access', true) = 'on')`. Only `platform` module services set this flag, transaction-locally, after verifying a platform-admin principal.

### 4.3 Global (non-tenant) tables
`plans`, `modules`, `permissions`, `tenants` and `platform_users` are global. `tenants` is reachable only through tenancy services, which always use the current tenant's id, plus the platform services.

### 4.4 Other isolation surfaces in scope
- **Redis keys:** always `tenant:{tenantId}:…`. A helper `TenantKeys` is the only way to build keys.
- **Logs:** MDC carries `tenant_id`, `user_id` and `request_id`.
- **Rate limits:** tenant-aware keys.

## 5. Data model (Flyway)

Conventions:
- UUID v7 primary keys, generated by the application;
- `created_at` / `updated_at` as `timestamptz`, in UTC;
- `version bigint` for optimistic locking on mutable aggregates;
- FK, unique and check constraints;
- tenant-aware indexes.

| Migration | Tables |
|---|---|
| V1 tenancy | `plans(code PK, name, limits jsonb)`, `modules(code PK, name)`, `tenants(id, slug UNIQUE CHECK '^[a-z0-9](-?[a-z0-9]){2,39}$', name, subdomain, status CHECK, plan_code FK, timezone, locale, currency CHAR(3), created_at, updated_at, version)`, `tenant_modules(tenant_id, module_code, enabled, PK(tenant_id,module_code))` |
| V2 identity | `users(id, tenant_id, email, password_hash, first_name, last_name, status CHECK(INVITED/ACTIVE/DISABLED), email_verified_at, token_version, last_login_at, …, UNIQUE(tenant_id, lower(email)))`, `refresh_tokens(id, tenant_id, user_id, family_id, token_hash UNIQUE, expires_at, revoked_at, replaced_by, created_ip, user_agent)`, `email_verifications(id, tenant_id, user_id, token_hash, expires_at, used_at)`, `invitations(id, tenant_id, email, role_id, token_hash, invited_by, expires_at, accepted_at, revoked_at)`, `platform_users(id, email UNIQUE, password_hash, totp_secret_enc, role CHECK(PLATFORM_ADMIN/PLATFORM_SUPPORT), status)` |
| V3 rbac | `permissions(code PK, module_code NULL FK, description)`, `roles(id, tenant_id, name, description, system, UNIQUE(tenant_id, lower(name)))`, `role_permissions(tenant_id, role_id, permission_code)`, `user_roles(tenant_id, user_id, role_id)` |
| V4 audit | `audit_events(id, tenant_id NULL, actor_type, actor_id, action, entity_type, entity_id, occurred_at, ip, user_agent, request_id, correlation_id, before jsonb, after jsonb, metadata jsonb)`. A trigger blocks UPDATE/DELETE, and the app role gets INSERT/SELECT only |
| V5 rls | Policies + grants described in §4.2 |

Note on the `tenants` row (V1): slug rule superseded by V5: `^[a-z0-9]+(-[a-z0-9]+)*$` and length 3–40.

Composite FKs include `tenant_id`, so a row cannot reference another tenant's row. Example: `user_roles(tenant_id, role_id) → roles(tenant_id, id)`.

Lookups by token hash must work before the tenant is known (refresh, verification, invitation). Two parts make that possible:
- The tokens are formatted `{tenantId}.{random}`. The tenant prefix lets the server set the RLS context first; the random part is what gets hashed and checked.
- Tampering with the prefix just finds no row.

### Permission catalog (seeded)
- **Platform-foundation permissions** (`module_code` NULL, always available):
  `tenant.settings.read|update`, `tenant.modules.manage`, `identity.user.read|invite|update|disable`, `authorization.role.read|manage`, `authorization.role.assign`, `audit.event.read`.
- **Module permissions** from §14: `crm.customer.*`, `inventory.product.read`, `inventory.stock.adjust`, `helpdesk.ticket.read|assign|resolve`, `hr.employee.read|update`. These are seeded now so roles can be designed ahead of time, and they take effect only when their module is enabled.

### System roles per tenant
- `TENANT_OWNER`: all permissions; can't be deleted or edited. There is always at least one owner.
- `TENANT_ADMIN`: all permissions except owner transfer.

## 6. Authentication flows

| Flow | Behaviour |
|---|---|
| Signup | Validate slug availability and the password policy (≥12 chars, not in a small breached-password list). Create the tenant (`PENDING_VERIFICATION`, FREE plan), the owner user, system roles and an email verification. Send mail. Audit `TenantCreated` and `UserRegistered`. |
| Verify email | Token → mark user verified → tenant `ACTIVE`. Single-use, 24h expiry. |
| Login | Resolve slug → tenant (must be `ACTIVE`) → user (must be `ACTIVE` + verified) → Argon2id verify. Use constant-time-ish failure handling with a dummy hash. Issue an access JWT (`sub`, `tid`, `tv`=token_version, `jti`, 15 min) and a refresh token (new family, 14 days). Audit success and failure. |
| Refresh | Cookie token → find by hash. If it's already rotated or revoked, that's **reuse**: revoke the whole family and audit `RefreshTokenReuseDetected` (401). Otherwise rotate it and issue a new access token. |
| Logout | Revoke the family and clear the cookie. |
| Logout all | Increment `token_version`. Access tokens with an older `tv` are rejected, and all refresh tokens are revoked. |
| Per-request check | Verify the JWT signature/expiry and the `alg` allowlist (RS256 only). Load a lightweight principal state from cache: user status, `token_version`, tenant status. Reject if the tenant isn't `ACTIVE` or the user isn't `ACTIVE`. |
| Invitation | An admin invites an email with a role → mail with a token (7d) → the invitee sets name and password → the user is created `ACTIVE` + verified with that role. |
| Platform login | Separate endpoint and JWT audience (`aud=platform`). Requires password + TOTP. Platform tokens can't be used on tenant APIs, and tenant tokens can't be used on platform APIs. |

## 7. Authorization

- **Authorities:** `PermissionResolver` computes the effective permissions for (tenant, user) = the union of the user's roles' permissions, minus permissions whose module is disabled for the tenant.
  - Cached at `tenant:{tid}:user:{uid}:perms` (TTL 5 min).
  - Evicted on role, assignment and module changes.
- **Enforcement:** `@PreAuthorize("hasAuthority('<code>')")` on every non-public handler. `PublicEndpoints` lists the unauthenticated routes. `EndpointAuthorizationCoverageTest` fails the build if any handler is neither annotated nor allowlisted.
- **Escalation guard:**
  - A user cannot create, edit or assign a role whose permissions are not a subset of their own effective permissions.
  - Only owners can assign or remove `TENANT_OWNER`.
  - The last owner cannot be removed or disabled.

## 8. Audit

`AuditService.record(AuditEntry)` is called in the same transaction as the business change. Request metadata (IP, user agent, request_id, correlation_id from `X-Correlation-Id` or request_id) comes from `RequestContext`. Before/after snapshots come from DTO projections that exclude `@Sensitive` fields: password hashes, token hashes and TOTP secrets are never logged or audited.

The read API is paginated and filterable by action, entity_type, actor and date range, and requires `audit.event.read`. There is no update or delete API, and the DB enforces it too.

## 9. Rate limiting

A Redis token bucket implemented as an atomic Lua script. The filter runs before authentication for public routes and after it for authenticated ones.

| Route class | Key | Default |
|---|---|---|
| login / platform login | per IP `rl:ip:{ip}:login`; per account `rl:acct:{sha256(workspace+email)}:login`; per workspace `rl:ws:{sha256(workspace)}:login-workspace`, failed logins only (see §16 / ADR-0006) | 10/min per IP; 10/min per account; 100/min per workspace |
| signup, invitation accept, verify | `ip:{ip}:rl:{route}` | 5/min |
| refresh | `ip:{ip}:rl:refresh` | 30/min |
| authenticated API | `tenant:{tid}:user:{uid}:rl:api` | 300/min |

Exceeding a limit returns 429 ProblemDetail + `Retry-After`. Limits are configurable per environment. If Redis is unavailable, auth routes fail closed and other routes fail open with a warning log and a metric.

## 10. API surface (`/api/v1`, OpenAPI via springdoc)

- **Public:**
  - `POST auth/signup`, `auth/verify-email`, `auth/login`, `auth/refresh`, `auth/logout`;
  - `POST invitations/accept`;
  - `GET invitations/preview?token=`;
  - `POST platform/auth/login`.
- **Tenant (authenticated):**
  - `POST auth/logout-all`;
  - `GET me`;
  - `GET|PATCH tenant`;
  - `GET tenant/modules`, `PUT tenant/modules/{code}`;
  - `GET|POST invitations`, `DELETE invitations/{id}`;
  - `GET users`, `GET|PATCH users/{id}`, `PUT users/{id}/roles`;
  - `GET permissions`;
  - `GET|POST roles`, `GET|PATCH|DELETE roles/{id}`, `PUT roles/{id}/permissions`;
  - `GET audit-events`.
- **Platform (authenticated, `aud=platform`):**
  - `GET platform/tenants`;
  - `POST platform/tenants/{id}/suspend|reactivate`.

**Errors:** RFC 9457 ProblemDetail with `type`, `title`, `status`, `detail`, `instance`, `requestId` and field `errors[]`. No stack traces. **Pagination:** `?page=&size=` (max 100) returns `{items, page, size, total}`.

## 11. Frontend

- **Routes:**
  - public: `/signup`, `/verify-email`, `/login`, `/invite/accept`;
  - app (`/app/...`): Overview, CRM, Inventory, HelpDesk, HRMS, Workflows, Insights, AI Assistant (module and feature entries show empty states naming their phase), Audit, Settings (Workspace, Users, Roles, Modules);
  - platform: `/platform/login`, `/platform/tenants`.
- **State:**
  - the auth provider keeps the access token in memory and calls `/auth/refresh` on boot;
  - the API client retries once after a refresh when it gets a 401;
  - TanStack Query handles server state.
- **Permission-aware UI:** `useCan(code)` and `<RequirePermission>` hide what the user can't use, and nav items are filtered by enabled modules. This is a UX convenience only; the server enforces everything.
- **Every screen** has loading (skeletons), error (ProblemDetail-aware) and empty states. It's responsive and keyboard navigable, and forms are validated with Zod schemas.

## 12. Observability (foundation level)

- Structured JSON logs. MDC carries `request_id`, `tenant_id` and `user_id`, and `X-Request-Id` is echoed in the response.
- Actuator health/readiness.
- Micrometer metrics at `/actuator/prometheus`, including counters for:
  - login failures;
  - refresh reuse;
  - rate-limit rejections;
  - cross-tenant 404s on id lookups.
- Never logged: passwords, tokens, cookies, TOTP secrets.

## 13. Testing strategy

| Layer | What |
|---|---|
| Unit | Password policy, token generation/hashing, JWT service, permission resolver, escalation guard, rate-limit key builder |
| Integration (Testcontainers Postgres 17 + Redis) | Migrations, repositories, all auth flows, RBAC flows, audit immutability |
| **Isolation suite** | Seeds tenants A/B. A's token on B's ids returns 404 for every id endpoint. Lists exclude B. Raw JDBC as `nexusops_app`: A's context sees 0 of B's rows; no context sees 0 rows in every RLS table; an INSERT with a foreign `tenant_id` is rejected by `WITH CHECK` |
| Token misuse | Expired, bad-signature, `alg=none`, HS256-confusion, platform↔tenant audience swap, stale `token_version`, refresh reuse, suspended tenant |
| Escalation | Non-admin assign → 403; admin granting a permission they lack → 403; removing the last owner → 409 |
| Architecture | `ApplicationModules.verify()`, endpoint authorization coverage |
| Frontend | Vitest (auth provider, API client refresh, forms) |
| E2E (Playwright) | Signup → verify (Mailpit API) → login → invite → accept → custom role → assign → audit trail shows each step |
| ai-service | pytest `/health` |

## 14. Delivery and verification

- Compose: postgres (pgvector/pg17), redis, mailpit, plus the `app` profile for backend, frontend and ai-service.
- `Makefile` targets: `up`, `down`, `test`, `e2e`.
- CI: backend build + tests; frontend lint/typecheck/test/build; ai-service ruff/pytest; Docker builds; Trivy scans; dependency review; E2E on main.

**Done when:**
- every success criterion in §1 is demonstrated by a passing command or test;
- the OpenAPI spec has been exported to `docs/api/openapi.json`;
- the README quick start works from a clean clone.

## 15. Deltas adopted in Plan 2

1. **RLS is created in the same migration as each table**, instead of in a separate `V5__rls.sql`. That way no table ever exists without RLS.
2. **Join tables use policies instead of composite foreign keys.** `role_permissions` and `user_roles` carry no `tenant_id`. Their RLS policies require the referenced role and user to be visible under the current tenant. This gives the same guarantee as composite FKs, because a cross-tenant row is invisible and can't be inserted.
3. **`app.tenant_id` is set at the session level on connection checkout**, not with `SET LOCAL` per transaction. Every checkout overwrites it, so it can't carry over between users of the pool. `TenantContext` refuses to switch tenant while a transaction is active. ADR-0002 is updated in Task 11.
4. **`audit_events` rows may have a NULL `tenant_id`.** This covers pre-tenant events such as a login to an unknown workspace. Insert is allowed when the row is NULL or matches the current tenant; select only returns current-tenant rows.
5. **Added `POST /auth/resend-verification`.** It always returns 202, so it can't be used to discover accounts. Without it, a lost email leaves the workspace stuck.
6. **Principal cache TTL is 60 s instead of 5 min.** It is explicitly evicted on every user, role or tenant change. The short TTL bounds how stale it can get if an eviction is missed.
7. **Refresh-reuse grace window of 10 s.** Re-presenting a token that was rotated less than 10 s ago returns 401 *without* revoking the family. Two browser tabs share one cookie jar, so the losing tab's next attempt carries the new cookie. Any reuse after the grace window revokes the whole family and is audited.


## 16. Deltas adopted in Plan 3

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

### Changes during implementation

The built system deviates from the decisions above as follows:

- **Login rate limiting uses three buckets** instead of the per-IP plus per-workspace pair in decision 2: rule `login`
  per IP (10/min), rule `login-account` per `sha256(workspace + "\n" + email)` (10/min) and rule `login-workspace` per
  workspace (100/min, anti-spraying). A per-workspace 10/min limit let any anonymous client lock a whole tenant out
  of login. See ADR-0006.
  - **The workspace bucket counts failed logins only.** The login peeks it without consuming (429 if empty) and a failed
    authentication consumes one token; a successful login never does. Residual risk, accepted: an attacker with about
    10 IPs sending failures can still block a workspace's logins for the window, and hammering one account can lock
    that one user out. CAPTCHA/step-up is future work.
  - **The account and workspace keys use the canonical slug and email** the login lookup uses, so control-character
    padded or case variants share one bucket. Input that can't canonicalize charges only the per-IP bucket.
- **Rate-limit IP keys are normalized:** IPv6 uses the /64 prefix, IPv4-mapped IPv6 becomes the IPv4 address, and only
  literal IPs are parsed (no DNS). Redis command and connect timeouts are 500 ms.
- **The principal cache also has a tenant-level generation** `tenant:{t}:principal-gen`, bumped first by
  `evictTenant`, in addition to the per-user generations (decision 3).
- **`spring.task.execution.mode: force`** keeps Boot's `applicationTaskExecutor` alongside the bounded `mailExecutor`
  (decision 7).
- **Owners-only also covers re-enabling a disabled owner:** it requires an owner, like assigning or removing the
  owner role.
- **A role manager can't weaken or delete a more-powerful role** (your decision, 2026-10-05). For `PATCH /roles/{id}`,
  `PUT /roles/{id}/permissions` and `DELETE /roles/{id}`, the target role's current permissions must be within the
  actor's grantable permissions, else 403 "You can't change a role with permissions you don't have." Order: 404
  cross-tenant → 409 system role → 403 hierarchy (before the assignment count on delete). PUT still also requires the
  new set to be grantable. See ADR-0004.
- **Mistyped parameters:** a malformed path id (e.g. not a UUID) is 404; any other mistyped parameter
  (`?page=abc`, `?actorId=not-a-uuid`) is 400 with a field error.
