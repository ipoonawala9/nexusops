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
