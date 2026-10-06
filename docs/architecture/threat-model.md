# Threat Model (STRIDE) — Tenancy and Identity, Phases 1–4

| # | Threat | STRIDE | Mitigation | Verified by |
|---|---|---|---|---|
| T1 | Tenant A user reads Tenant B data by guessing ids (IDOR) | I | `@TenantId` filter + RLS; return 404 rather than 403 | TenantIsolationIT |
| T2 | Client-supplied tenant id in a header or body | S/E | TenantContext comes from the verified JWT only; DTOs have no tenant field | TenantIsolationIT, code review |
| T3 | App bug forgets the tenant filter (native query) | I | RLS fails closed; runtime role is NOBYPASSRLS | RLS raw-JDBC tests |
| T4 | Insert a row pointing at another tenant's row | T | Composite FKs (tenant_id, id); RLS WITH CHECK | RLS tests |
| T5 | JWT forgery / `alg=none` / HS256 key confusion | S | RS256-only allowlist; reject on audience mismatch | TokenMisuseIT |
| T6 | Stolen refresh token replayed | S | Rotation + family reuse detection → revoke family + audit | RefreshReuseIT |
| T7 | XSS steals tokens | I | Access token in memory; refresh token in an httpOnly SameSite=Strict cookie; CSP | Review, E2E |
| T8 | CSRF on the refresh endpoint | T | SameSite=Strict, path-scoped cookie; Origin check on /auth/* | AuthIT |
| T9 | Credential stuffing / brute force | S/D | Per-IP and per-slug rate limits; uniform errors; Argon2id; audit failures | RateLimitIT |
| T10 | Privilege escalation through role editing | E | Subset rule; owner-only owner role; last-owner protection | EscalationIT |
| T11 | Endpoint added without authorization | E | EndpointAuthorizationCoverageTest | Build |
| T12 | Audit tampering | R/T | INSERT/SELECT-only grant + trigger; no API | AuditImmutabilityIT |
| T13 | Cache key collision leaks permissions | I | `TenantKeys` builder with a mandatory tenant prefix | Unit tests |
| T14 | Secrets or tokens leaked in logs | I | Never log credentials; `@Sensitive` redaction; structured logging | Log assertion tests |
| T15 | Suspended tenant keeps access | E | Tenant status checked per request (cached briefly), login refused | TenantSuspensionIT, PlatformTenantApiIT |
| T16 | Platform token used on tenant APIs or vice versa | E | Audience separation; platform decoder rejects any `tid`; separate security chains | PlatformSecurityIT |
| T17 | User enumeration through signup/login responses | I | Generic login errors; slug-availability endpoint is rate limited | AuthIT |
| T18 | Stolen platform password | S | Password + single-use TOTP; uniform failures; per-IP and per-account rate limits; audited failures; 10 consecutive wrong codes disable the account | PlatformAuthIT, PlatformLoginRateLimitIT |
| T19 | TOTP code replay (shoulder-surfing, intercepted code) | S | `totp_last_step` advanced under a row lock; a step ≤ last used is rejected | PlatformAuthIT |
| T20 | Tenant code path reads staff credentials or other tenants' rows | I/E | Platform tables and cross-tenant SELECT policies require `app.platform_access`; only `PlatformAccess` turns it on, transaction-locally; every connection checkout clears it at session level (`TenantAwareDataSource`) | PlatformAccessIT, PlatformAccessConfinementTest |
| T21 | Database dump exposes TOTP secrets | I | AES-256-GCM with `PLATFORM_TOTP_KEY` (not in the DB), AAD = user id | TotpSecretCipherTest |
| T22 | Long-lived stolen platform session | S/E | 15-min access tokens; refresh family capped at 8 h; credential changes bump `token_version` | PlatformAuthIT |
