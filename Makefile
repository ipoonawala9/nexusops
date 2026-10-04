COMPOSE := docker compose -f infra/docker/docker-compose.yml

.PHONY: up down reset-db psql
up:        ## start Postgres, Redis, Mailpit
	$(COMPOSE) up -d --wait postgres redis mailpit
down:      ## stop everything
	$(COMPOSE) --profile app down
reset-db:  ## wipe the local database volume (re-runs role bootstrap)
	$(COMPOSE) --profile app down -v
psql:      ## psql as the runtime app role
	$(COMPOSE) exec -e PGPASSWORD=$${DB_APP_PASSWORD:-nexusops_app_local} postgres psql -U nexusops_app -d nexusops

.PHONY: up-all backend frontend ai test test-backend test-frontend test-ai e2e
up-all:        ## build and start the whole stack (UI http://localhost:3000, API http://localhost:8081)
	$(COMPOSE) --profile app up -d --build
backend:       ## run the API locally on :8081 (needs `make up`)
	cd backend && ./gradlew bootRun
frontend:      ## run the Vite dev server (http://localhost:5173)
	cd frontend && npm run dev
ai:            ## run the ai-service locally
	cd ai-service && .venv/bin/uvicorn app.main:app --reload --port 8000
test: test-backend test-frontend test-ai
test-backend:
	cd backend && ./gradlew build
test-frontend:
	cd frontend && npm run format:check && npm run lint && npm run typecheck && npm test && npm run build
test-ai:
	cd ai-service && .venv/bin/ruff check . && .venv/bin/ruff format --check . && .venv/bin/pytest -q
e2e:
	cd frontend && npm run e2e
