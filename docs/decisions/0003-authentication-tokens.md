# ADR-0003: Self-built JWT access tokens with rotating refresh tokens

- Status: Accepted
- Date: 2026-10-04

## Context
We need login, verification, refresh, logout/revocation and invitations (Phase 2), with no external identity-provider dependency for the MVP. The browser storage choice must resist token theft through XSS.

## Decision
- **Access token:** RS256 JWT, 15 minutes. Claims: `sub`, `tid`, `tv` (token_version), `jti`, `aud` (`tenant` or `platform`). Only RS256 is accepted.
- **Refresh token:** opaque `{tenantId}.{256-bit random}`. Only its SHA-256 hash is stored. It lasts 14 days, rotates on every use and belongs to a token family. Reusing a rotated token revokes the whole family and is audited.
- **Browser:** the access token is kept in memory. The refresh token is an httpOnly, Secure, SameSite=Strict cookie with path `/api/v1/auth`.
- **Revocation:**
  - logout revokes the token family;
  - "logout all" increments `token_version`, which every request checks against a cached value;
  - suspending a tenant or disabling a user takes effect on the next request.
- **Passwords:** Argon2id.
- **Platform admins:** a separate table, with password + TOTP and `aud=platform`.

## Consequences
- We control the whole flow, and it is demonstrable for the capstone.
- We must maintain this security-critical code ourselves, so it gets dedicated misuse tests.
- SSO and tenant-user MFA are deferred to the Enterprise tier and could be delegated to an external identity provider then.
