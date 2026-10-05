# NexusOps

Multi-tenant business operations platform (capstone → MVP). One tenant, one business context,
one permission model, one operational event stream — with modular business capabilities on top.

- Blueprint: `docs/research/master-blueprint.md`
- Current design: `docs/superpowers/specs/2026-10-04-platform-foundation-design.md`
- Decisions: `docs/decisions/`
- Implementation plans: `docs/superpowers/plans/`

## Prerequisites
JDK 25, Node 22, Python 3.13, Docker.

## Quick start
```bash
make up        # Postgres :5433, Redis :6380, Mailpit UI http://localhost:8025
make backend   # API http://localhost:8081 (Swagger UI: /swagger-ui.html)
make frontend  # UI  http://localhost:5173 (proxies /api to :8081)
```
Or run everything in containers with `make up-all` (UI http://localhost:3000, API http://localhost:8081).

One-time ai-service setup: `cd ai-service && python3 -m venv .venv && .venv/bin/pip install -e '.[dev]'`.

| Service | Host port |
|---|---|
| Postgres | 5433 |
| Redis | 6380 |
| Mailpit SMTP / UI | 1025 / 8025 |
| Backend API | 8081 |
| Frontend (dev / container) | 5173 / 3000 |
| ai-service | 8000 |

Host ports can be overridden through `infra/docker/.env` (see `.env.example`).

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
### Invite a teammate
```bash
TOKEN=<accessToken from the owner login above>
# 1. the owner creates a role (permission codes: GET /api/v1/permissions)
curl -s -X POST localhost:8081/api/v1/roles -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"Support","description":"Read-only support","permissions":["identity.user.read","tenant.settings.read"]}'
# 2. the owner invites someone with that role (roleId from the response above)
curl -s -X POST localhost:8081/api/v1/invitations -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"email":"sam@acme.test","roleId":"<roleId>"}'
# 3. read the invitation token from the email in Mailpit (http://localhost:8025)
# 4. the invitee previews the invitation, then accepts it
curl -s "localhost:8081/api/v1/invitations/preview?token=<token>"
curl -s -X POST localhost:8081/api/v1/invitations/accept -H 'Content-Type: application/json' \
  -d '{"token":"<token>","firstName":"Sam","lastName":"Support","password":"another long passphrase"}'
# 5. the invitee logs in
curl -s -X POST localhost:8081/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"workspace":"acme","email":"sam@acme.test","password":"another long passphrase"}'
```
The API is rate-limited (defaults from spec §9, e.g. 10 logins per minute per IP and per account, 300 API calls per
minute per user); exceeding a limit returns `429` with a `Retry-After` header. Change the local defaults through
`nexusops.rate-limits.rules.*`.

The API contract is in `docs/api/openapi.json`; Swagger UI at http://localhost:8081/swagger-ui.html.

## Tests
- `make test` runs the backend (including Testcontainers integration tests, so Docker must be running), the frontend and the ai-service.
- `make e2e` runs Playwright.

## Database roles
- The app connects as `nexusops_app` (not a superuser, no BYPASSRLS) and refuses to start as anything more privileged.
- Flyway migrates as `nexusops_owner`.
- See ADR-0002.
- `make reset-db` wipes the local database.

## Branching
Trunk-based: `main` is always releasable. Work happens on short-lived `feat/*` / `fix/*` branches that are merged via PR once CI passes.
