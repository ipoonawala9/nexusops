# NexusOps

Multi-tenant business operations platform (capstone → MVP). One tenant, one business context,
one permission model, one operational event stream — with modular business capabilities on top.

- Blueprint: `docs/research/master-blueprint.md`
- Current design: `docs/superpowers/specs/2026-10-04-platform-foundation-design.md`
- Canonical data model (Phase 4): `docs/superpowers/specs/2026-10-06-canonical-data-model-design.md`
- CRM (Phase 5): `docs/superpowers/specs/2026-10-07-crm-mvp-design.md`
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

## Using the app
- Run `make up && make backend` in one terminal and `make frontend` in another, then open http://localhost:5173/signup
  to create a workspace. The verification email arrives in Mailpit (http://localhost:8025).
- The staff console is at http://localhost:5173/platform/login. Create an operator first with
  `make platform-admin EMAIL=you@example.com`.

## Records (Phase 4)
Shared by every module, in the app under **Directory**, **Products** and **Tasks**:
- **Directory:** people and organizations, one record each. A probable duplicate is refused until you give a reason
  (ADR-0008). A record plays roles (customer, supplier, employee); employee details need the employee permissions.
- **Products:** goods and services with unique SKUs and list prices.
- **Activity, tasks and documents:** attached to any person, organization or product. Documents are up to 10 MB each,
  with a per-plan storage quota, and are always downloaded, never opened in the browser (ADR-0009).
- Records are archived, never deleted. Every change is in the audit log.

## CRM (Phase 5)
Switch the module on under **Settings → Modules**; every CRM permission switches off with it (ADR-0010).
- **Leads:** prospects as you heard of them, entered by hand or imported from a CSV file (up to 500 rows; all or
  nothing, with per-row errors). New → Contacted → Qualified, or Disqualified with a reason.
- **Conversion:** links or creates the canonical person and organization (the directory's duplicate check applies),
  makes the account a customer, and can open an opportunity.
- **Pipeline:** your own stages (Settings → Pipeline) with probabilities; a board with per-currency totals; Lost needs
  a reason; Won makes the account a customer.
- **Customers and Customer 360:** people, deals, converted leads and one timeline that includes the deals' and leads'
  activity. **Dashboard:** open leads, 90-day conversion rate, pipeline by stage, won and lost this month.
- **Search:** the box at the top of every page (press `/`) finds people, organizations, leads, deals and products you
  may see.

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

Ten consecutive wrong codes after a correct password disable the account (audited as `PlatformUserLockedOut`). To
recover, run `make platform-enable EMAIL=…` and also `make platform-reset-password EMAIL=…`, because whoever was
guessing codes knows the password.

In Docker, run `docker compose -f infra/docker/docker-compose.yml --profile app run --rm -it backend --nexusops.cli.command=create-platform-admin --nexusops.cli.email=you@example.com`.

Production must set `PLATFORM_TOTP_KEY`: 32 random bytes, base64, e.g. `openssl rand -base64 32`. Keep it out of the
database backups. Losing it means every operator must re-enrol.

## Tests
- `make test` runs the backend (including Testcontainers integration tests, so Docker must be running), the frontend and the ai-service.
- `make e2e` starts the full Docker stack (UI http://localhost:3000, Mailpit http://localhost:8025) and runs the
  Playwright journeys: sign up → verify → sign in → custom role → invite → accept → assign → audit trail, and a
  platform admin suspending and reactivating a workspace. The stack stays up afterwards; `make down` stops it.
- `make e2e` seeds a local-only platform operator (`e2e-ops@nexusops.test`); `make reset-db` removes it, and the next
  `make e2e` seeds it again.

## Database roles
- The app connects as `nexusops_app` (not a superuser, no BYPASSRLS) and refuses to start as anything more privileged.
- Flyway migrates as `nexusops_owner`.
- See ADR-0002.
- `make reset-db` wipes the local database.

## Branching
Trunk-based: `main` is always releasable. Work happens on short-lived `feat/*` / `fix/*` branches that are merged via PR once CI passes.
