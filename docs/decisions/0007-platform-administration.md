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
  - Lockout: ten consecutive wrong codes after a correct password disable the account in the same transaction
    (`failed_totp_attempts`, audited as `PlatformUserLockedOut`; the response stays the uniform 401). A correct code
    resets the count. Recovery is `make platform-enable` plus `make platform-reset-password`, since the password is
    known to whoever was guessing. Wrong passwords never touch the count, so it adds no pre-auth lockout vector.
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
  - `PlatformAccess` is the only code that turns it on. It uses `set_config(..., true)`, refuses tenant scopes and
    refuses already-open transactions.
  - `TenantAwareDataSource` also clears the flag at session level on every connection checkout (in the same round
    trip as `app.tenant_id`). A session-level leak therefore can't outlive one checkout. Checkout happens before a
    transaction begins, so the transaction-local setting is unaffected.
  - `PlatformAccessConfinementTest` fails the build unless:
    - exactly these two main sources mention the flag;
    - only `PlatformAccess` sets it to `'on'`;
    - among resources, only `V7__platform.sql` mentions it.
- **Suspension lives in tenancy.**
  - `TenantDirectory.suspendCurrent/reactivateCurrent` allow only ACTIVE ⇄ SUSPENDED, require a reason, and are audited
    in the workspace's own log as `actor_type=PLATFORM` with the operator's id.
  - What the workspace may see: the reason and that a platform operator acted. Those tenant rows are written without
    the operator's IP address and user agent (`AuditEntry.withoutClientDetails`). The platform module writes a
    companion row with `tenant_id` NULL (`PlatformTenantSuspended`/`PlatformTenantReactivated`, metadata `tenantId`
    and `reason`) that keeps the operator's IP and user agent for staff forensics.
  - The platform module opens a TenantContext scope from the path id only around the tenancy call, for an
    authenticated operator holding `platform.tenant.suspend`. This is a server-side lookup (TenantDirectory 404s
    unknown ids), and it is the only place tenant scope comes from a URL.
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
- Residual risk, accepted: login timing. A correct password runs an extra locked TOTP transaction, so response
  timing can reveal that a stolen password is right. The 10-code lockout limits what an attacker gains from knowing
  this.
- Residual risk, accepted: pre-auth flag scope. `app.platform_access` is also on during unauthenticated platform
  login, refresh and logout, and in the principal filter. So the `platform_read` SELECT policies on `users`, `roles`
  and `user_roles` are open before a principal is verified. Those paths issue no such queries today. Future defence in
  depth: a separate tenant-read flag set only for the workspace listing.
