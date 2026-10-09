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

.PHONY: up-all backend frontend ai test test-backend test-frontend test-ai test-infra e2e
up-all:        ## build and start the whole stack (UI http://localhost:3000, API http://localhost:8081)
	$(COMPOSE) --profile app up -d --build
backend:       ## run the API locally on :8081 (needs `make up`)
	cd backend && ./gradlew bootRun
frontend:      ## run the Vite dev server (http://localhost:5173)
	cd frontend && npm run dev
ai:            ## run the ai-service locally
	cd ai-service && .venv/bin/uvicorn app.main:app --reload --port 8000
test: test-infra test-backend test-frontend test-ai
test-infra:
	infra/docker/tests/check-loopback-ports.sh
test-backend:
	cd backend && ./gradlew build
test-frontend:
	cd frontend && npm run format:check && npm run lint && npm run typecheck && npm test && npm run build
test-ai:
	cd ai-service && .venv/bin/ruff check . && .venv/bin/ruff format --check . && .venv/bin/pytest -q
e2e:           ## Playwright journeys against the full Docker stack (UI :3000, Mailpit :8025); leaves it running
	REFRESH_RATE_LIMIT=600 $(COMPOSE) --profile app up -d --build --wait postgres redis mailpit backend frontend
	cd frontend && E2E_BASE_URL=http://localhost:3000 npm run e2e

PLATFORM_CLI = cd backend && ./gradlew -q bootJar && SPRING_PROFILES_ACTIVE=$${SPRING_PROFILES_ACTIVE:-local} \
	java -jar $$(ls build/libs/backend-*-SNAPSHOT.jar) --nexusops.cli.email=$(EMAIL)

.PHONY: platform-admin platform-reset-totp platform-reset-password platform-disable platform-enable
platform-admin:          ## create a platform user: make platform-admin EMAIL=you@example.com [ROLE=PLATFORM_SUPPORT]
	$(PLATFORM_CLI) --nexusops.cli.command=create-platform-admin --nexusops.cli.role=$(or $(ROLE),PLATFORM_ADMIN)
platform-reset-totp:     ## enrol a new authenticator: make platform-reset-totp EMAIL=you@example.com
	$(PLATFORM_CLI) --nexusops.cli.command=reset-platform-totp
platform-reset-password: ## set a new password: make platform-reset-password EMAIL=you@example.com
	$(PLATFORM_CLI) --nexusops.cli.command=reset-platform-password
platform-disable:        ## disable a platform user: make platform-disable EMAIL=you@example.com
	$(PLATFORM_CLI) --nexusops.cli.command=disable-platform-user
platform-enable:         ## re-enable a platform user: make platform-enable EMAIL=you@example.com
	$(PLATFORM_CLI) --nexusops.cli.command=enable-platform-user
