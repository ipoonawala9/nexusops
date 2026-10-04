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
