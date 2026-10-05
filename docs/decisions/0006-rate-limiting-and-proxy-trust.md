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
  - Pre-auth keys are not tenant-prefixed (no tenant is known yet) and are built only by `RateLimitKeys`:
    `rl:ip:{ip}:{rule}` for per-IP rules.
  - **Login has three buckets**, all of which must allow the request:
    - rule `login`: per IP, 10/min;
    - rule `login-account`: per `sha256(workspace + "\n" + email)`, 10/min, which stops guessing against one account
      from many IPs;
    - rule `login-workspace`: per workspace, 100/min of **failed** logins only, a coarse anti-spraying ceiling.
      Before authenticating, the bucket is only peeked (a cost-0 run of the same atomic Lua script, no write); if it
      is empty the login gets 429 with `Retry-After`. A failed authentication (bad password, unknown email, disabled
      user) then consumes one token. A successful login never consumes, so legitimate login volume (e.g. a large
      tenant at shift start) cannot exhaust it.
  - The account and workspace keys are built from the **canonical** workspace and email the login lookup itself uses
    (`Slug` normalization and `Emails` normalization, applied in `AuthController`), so padded or case variants that
    reach the same account share its buckets. If either value cannot canonicalize, the login cannot succeed and only
    the per-IP bucket is charged.
  - The original design had a per-workspace limit of 10/min. It was replaced because any anonymous client could
    exhaust it with 10 bad requests and lock every user of a tenant out of login (a tenant-lockout DoS). The
    per-account bucket gives the targeted-guessing protection without letting one attacker block a whole workspace,
    and the workspace bucket is sized well above legitimate login traffic.
  - Authenticated: `tenant:{tid}:user:{uid}:rl:api`, 300/min.
- IP keys are normalized: IPv6 addresses collapse to their /64 prefix (so a rotating interface id cannot bypass the
  limit), IPv4-mapped IPv6 becomes the IPv4 address, and only literal IPs are parsed (no DNS lookups); anything else
  becomes `unknown`. Workspace and email inputs are lowercased and hashed so user input never forms a raw key.
- 429 problem+json with `Retry-After`.
- Redis command and connect timeouts are 500 ms, so a hung Redis cannot stall request threads.
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
- Residual DoS, accepted: an attacker with about 10 IPs (or IPv6 /64s) sending failed logins can still exhaust a
  workspace's failure bucket and block that workspace's logins (successful ones included) for the window. Likewise,
  hammering one known account exhausts its per-account bucket and keeps that one user from logging in. Both are the
  usual industry trade-off for brute-force protection. CAPTCHA or step-up challenges instead of a hard 429 are
  future work.
