# Plan 1 — Engineering Foundation: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A runnable, CI-checked monorepo with these parts:
- local infrastructure (Postgres with two least-privilege roles, Redis, Mailpit);
- a secure-by-default Spring Boot 4 backend skeleton;
- a React/Vite/shadcn frontend skeleton with a tested API client;
- a FastAPI ai-service skeleton;
- Dockerfiles for all three;
- a GitHub Actions pipeline.

This is blueprint Phase 1. It is the first of five plans implementing the spec:
- **Plan 1**: Foundation.
- **Plan 2**: Tenancy & Identity.
- **Plan 3**: RBAC, Audit & Rate limiting.
- **Plan 4**: Platform admin.
- **Plan 5**: Frontend screens & E2E.

Each later plan is written just before it is executed, against the real code this plan produces.

**Architecture:** A monorepo with `backend/` (Gradle KTS, Spring Boot 4.1 modular monolith, `com.nexusops.*`), `frontend/` (Vite React TS), `ai-service/` (FastAPI) and `infra/docker/` (Compose).

The backend connects to Postgres as `nexusops_app`: non-superuser and `NOBYPASSRLS`. Flyway connects as `nexusops_owner`. The application refuses to start if its runtime role could bypass RLS.

**Tech stack:**

| Area | Versions |
|---|---|
| Backend | JDK 25, Spring Boot 4.1.1, Gradle 9.7.1 wrapper, Spring Modulith 2.1.1, springdoc-openapi 3.1.1, Testcontainers |
| Frontend | Node 22, Vite 8, React 19, TS, Tailwind 4, shadcn/ui, React Router 8, TanStack Query, Vitest 5, Playwright 1.63 |
| ai-service | Python 3.13, FastAPI, ruff, pytest |
| Infra | Postgres 17 (`pgvector/pgvector:pg17`), Redis 7, Mailpit |

**Spec:** `docs/superpowers/specs/2026-10-04-platform-foundation-design.md`. ADRs are in `docs/decisions/`.

## Global Constraints

- Java toolchain is **25**. Spring Boot is **4.1.1**. Use `/api/v1` for every business route.
- Runtime DB role is `nexusops_app` (`NOSUPERUSER NOBYPASSRLS`, DML only). The migration role is `nexusops_owner`. **Never** run the app as `postgres` or as the owner, in any profile including tests.
- No secrets in git. Local-only defaults may appear **only** in `application-local.yml`, `infra/docker/.env.example` and the compose file's `${VAR:-default}` fallbacks, and their values must end in `_local`. `application.yml` and `application-prod.yml` contain no password defaults.
- Error responses are RFC 9457 `application/problem+json` and always include `requestId`. They never include stack traces, exception class names or exception messages from unexpected errors.
- Request id: honour `X-Request-Id` only if it matches `[A-Za-z0-9._-]{1,64}`; otherwise generate a UUID. Echo it in the response header. MDC keys are `request_id` and `correlation_id`.
- Host ports (to avoid clashing with other local services): Postgres **5433**, Redis **6380**, Mailpit SMTP **1025** / UI **8025**, backend **8080**, frontend dev **5173**, frontend container **3000**, ai-service **8000**.
- GitHub Actions are pinned by commit SHA (see Task 7). Do not use floating tags.
- Branching: work on `feat/foundation`, commit per task, merge `--no-ff` into `main` at the end. **Do not push.**
- Every commit message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **The app silently runs as a privileged DB role**, for example because someone points `DB_APP_USER` at `postgres`. Expected: startup fails with a clear message. *Pinned by `DatabaseRoleGuardTest` + `PlatformFoundationIT.runtimeRoleCannotBypassRls` (Task 3).*
2. **`@PreAuthorize` denials become 500s** because a catch-all `@ExceptionHandler(Exception.class)` swallows `AccessDeniedException`. Expected: 403 ProblemDetail. *Pinned by `GlobalExceptionHandlerTest.accessDeniedIs403` (Task 3).*
3. **A hostile `X-Request-Id`** (newline log injection, or a 10 KB header) is echoed into logs and responses. Expected: it is replaced with a generated UUID. *Pinned by `RequestIdFilterTest` (Task 3).*
4. **Prod profile starts with missing secrets**, for example by falling back to a dev password. Expected: startup fails, naming the missing variable. *Pinned by `ProdProfileRequiresSecretsTest` (Task 3).*
5. **Many parallel requests get 401 when the access token expires.** Expected: the frontend fires exactly **one** refresh, retries each request once, and never loops when the retry also returns 401. *Pinned by `client.test.ts` (Task 4).*

---

## File structure (created by this plan)

```
nexusops/
├── .gitignore                         (modify: add build outputs)
├── .editorconfig
├── Makefile
├── README.md
├── .github/workflows/ci.yml
├── infra/docker/
│   ├── docker-compose.yml
│   ├── .env.example
│   └── postgres/init/01-roles.sh      single source of truth for DB roles (compose + Testcontainers)
├── backend/
│   ├── build.gradle.kts, settings.gradle.kts, gradlew, gradle/wrapper/*
│   ├── Dockerfile, .dockerignore
│   └── src/
│       ├── main/java/com/nexusops/
│       │   ├── NexusOpsApplication.java
│       │   └── shared/
│       │       ├── package-info.java                    @ApplicationModule(type = OPEN)
│       │       ├── db/DatabaseRoleGuard.java            refuse to start as a privileged role
│       │       ├── security/SecurityConfig.java         baseline: stateless, deny-by-default
│       │       ├── security/ProblemDetailSecurityHandlers.java   401/403 JSON from filters
│       │       └── web/{RequestIdFilter,RequestIds,WebConfig,GlobalExceptionHandler,ProblemDetails}.java
│       ├── main/resources/
│       │   ├── application.yml, application-local.yml, application-test.yml, application-prod.yml
│       │   └── db/migration/V0_1__schema_privileges.sql
│       └── test/java/com/nexusops/
│           ├── ModularityTest.java
│           ├── support/IntegrationTestSupport.java
│           ├── PlatformFoundationIT.java
│           ├── ProdProfileRequiresSecretsTest.java
│           └── shared/{db/DatabaseRoleGuardTest, web/RequestIdFilterTest, web/GlobalExceptionHandlerTest}.java
├── frontend/   (Vite react-ts scaffold, plus:)
│   ├── src/lib/api/{client.ts, client.test.ts, tokenStore.ts}
│   ├── src/app/{App.tsx, App.test.tsx, router.tsx, queryClient.ts}
│   ├── src/pages/{HomePage.tsx, NotFoundPage.tsx}
│   ├── src/test/setup.ts
│   ├── e2e/smoke.spec.ts, playwright.config.ts
│   ├── Dockerfile, nginx.conf, .dockerignore
└── ai-service/
    ├── pyproject.toml, Dockerfile, .dockerignore
    ├── app/__init__.py, app/main.py
    └── tests/test_health.py
```

---

### Task 1: Branch, repo hygiene and local infrastructure

**Files:**
- Modify: `.gitignore`
- Create: `.editorconfig`, `infra/docker/docker-compose.yml`, `infra/docker/.env.example`, `infra/docker/postgres/init/01-roles.sh`, `Makefile` (infra targets only; extended in Task 7)

**Interfaces:**
- Produces: Postgres roles `nexusops_owner` and `nexusops_app`. Their passwords come from the env vars `NEXUSOPS_OWNER_PASSWORD` and `NEXUSOPS_APP_PASSWORD`, read by `01-roles.sh`. The `public` schema is owned by `nexusops_owner`.
- Produces: `make up` / `make down` / `make psql`.
- Task 3's Testcontainers reuse `infra/docker/postgres/init/01-roles.sh`.

- [ ] **Step 1: Create the branch**

```bash
cd /Users/user/Desktop/nexusops && git checkout -b feat/foundation
```

- [ ] **Step 2: Repo hygiene files**

Append to `.gitignore`:
```
# build outputs
backend/build/
backend/.gradle/
frontend/node_modules/
frontend/dist/
frontend/test-results/
frontend/playwright-report/
ai-service/.venv/
ai-service/**/__pycache__/
ai-service/.pytest_cache/
ai-service/.ruff_cache/
*.egg-info/
```

`.editorconfig`:
```
root = true

[*]
charset = utf-8
end_of_line = lf
insert_final_newline = true
indent_style = space
indent_size = 2
trim_trailing_whitespace = true

[*.{java,kts,py}]
indent_size = 4

[Makefile]
indent_style = tab
```

- [ ] **Step 3: Postgres role bootstrap script**

`infra/docker/postgres/init/01-roles.sh`. It runs once, as the superuser, when the data volume is first created.
```bash
#!/bin/bash
# Creates the least-privilege roles described in ADR-0002.
# nexusops_owner: owns the schema, runs Flyway migrations.
# nexusops_app:   runtime role. DML only (granted by migrations), never bypasses RLS.
set -euo pipefail

: "${NEXUSOPS_OWNER_PASSWORD:?NEXUSOPS_OWNER_PASSWORD must be set}"
: "${NEXUSOPS_APP_PASSWORD:?NEXUSOPS_APP_PASSWORD must be set}"

psql -v ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v owner_pw="$NEXUSOPS_OWNER_PASSWORD" \
  -v app_pw="$NEXUSOPS_APP_PASSWORD" <<'EOSQL'
CREATE ROLE nexusops_owner LOGIN PASSWORD :'owner_pw'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
CREATE ROLE nexusops_app LOGIN PASSWORD :'app_pw'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;

ALTER SCHEMA public OWNER TO nexusops_owner;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO nexusops_app;
EOSQL
```
Then run `chmod +x infra/docker/postgres/init/01-roles.sh`.

- [ ] **Step 4: Compose file (infrastructure services; the app profile is added in Task 6)**

`infra/docker/docker-compose.yml`:
```yaml
name: nexusops

services:
  postgres:
    image: pgvector/pgvector:pg17
    environment:
      POSTGRES_DB: nexusops
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-postgres_local}
      NEXUSOPS_OWNER_PASSWORD: ${DB_OWNER_PASSWORD:-nexusops_owner_local}
      NEXUSOPS_APP_PASSWORD: ${DB_APP_PASSWORD:-nexusops_app_local}
    ports:
      - "${POSTGRES_PORT:-5433}:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data
      - ./postgres/init:/docker-entrypoint-initdb.d:ro
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres -d nexusops"]
      interval: 5s
      timeout: 3s
      retries: 20

  redis:
    image: redis:7-alpine
    ports:
      - "${REDIS_PORT:-6380}:6379"
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 20

  mailpit:
    image: axllent/mailpit:latest
    ports:
      - "${MAILPIT_SMTP_PORT:-1025}:1025"
      - "${MAILPIT_UI_PORT:-8025}:8025"

volumes:
  pgdata:
```

`infra/docker/.env.example`:
```
# Local development only. Copy to .env to override. Never use these values outside your machine.
POSTGRES_PASSWORD=postgres_local
DB_OWNER_PASSWORD=nexusops_owner_local
DB_APP_PASSWORD=nexusops_app_local
POSTGRES_PORT=5433
REDIS_PORT=6380
MAILPIT_SMTP_PORT=1025
MAILPIT_UI_PORT=8025
```

- [ ] **Step 5: Makefile (infrastructure targets)**

`Makefile` (recipe lines must be indented with a TAB):
```make
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
```

- [ ] **Step 6: Verify roles were created with the right privileges**

Run:
```bash
make up && docker compose -f infra/docker/docker-compose.yml exec postgres psql -U postgres -d nexusops -tAc "select rolname, rolsuper, rolbypassrls from pg_roles where rolname like 'nexusops_%' order by 1; select nspowner::regrole from pg_namespace where nspname='public';"
```
Expected:
```
nexusops_app|f|f
nexusops_owner|f|f
nexusops_owner
```

- [ ] **Step 7: Commit**

```bash
git add .gitignore .editorconfig Makefile infra/
git commit -m "chore: local infrastructure with least-privilege Postgres roles

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Backend project scaffold

**Files:**
- Create: `backend/` via Spring Initializr. Then replace `backend/build.gradle.kts` and add `application*.yml`, `V0_1__schema_privileges.sql`, `shared/package-info.java` and `ModularityTest.java`.

**Interfaces:**
- Produces: the main class `com.nexusops.NexusOpsApplication`.
- Produces: profiles `local`, `test`, `prod`. Env vars: `DB_URL`, `DB_APP_USER`, `DB_APP_PASSWORD`, `DB_OWNER_USER`, `DB_OWNER_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `MAIL_HOST`, `MAIL_PORT`.
- Produces: an OPEN Modulith module `com.nexusops.shared`.

- [ ] **Step 1: Generate the project**

```bash
cd /Users/user/Desktop/nexusops && curl -s https://start.spring.io/starter.zip \
  -d type=gradle-project-kotlin -d language=java -d bootVersion=4.1.1 -d javaVersion=25 \
  -d groupId=com.nexusops -d artifactId=backend -d name=nexusops -d packageName=com.nexusops \
  -d applicationName=NexusOpsApplication \
  -d dependencies=web,security,data-jpa,validation,flyway,actuator,data-redis,postgresql,testcontainers,modulith,prometheus,mail \
  -o /tmp/nexusops-backend.zip && unzip -q /tmp/nexusops-backend.zip -d backend && rm /tmp/nexusops-backend.zip
rm -f backend/HELP.md backend/compose.yaml backend/src/main/resources/application.properties
rm -rf backend/src/test/java/com/nexusops/*   # remove generated sample tests; we write our own
```

- [ ] **Step 2: Replace `backend/build.gradle.kts`**

This trims Modulith to core + test, which follows YAGNI: the JPA event registry arrives with the outbox in Phase 10.
```kotlin
import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.nexusops"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

extra["springModulithVersion"] = "2.1.1"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.1")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.modulith:spring-modulith-bom:${property("springModulithVersion")}")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.named<BootRun>("bootRun") {
    systemProperty("spring.profiles.active", System.getenv("SPRING_PROFILES_ACTIVE") ?: "local")
}
```

- [ ] **Step 3: Configuration files**

`backend/src/main/resources/application.yml`:
```yaml
spring:
  application:
    name: nexusops
  threads:
    virtual:
      enabled: true
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5433/nexusops}
    username: ${DB_APP_USER:nexusops_app}
    password: ${DB_APP_PASSWORD}
  flyway:
    user: ${DB_OWNER_USER:nexusops_owner}
    password: ${DB_OWNER_PASSWORD}
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate
    properties:
      hibernate.jdbc.time_zone: UTC
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6380}
  mail:
    host: ${MAIL_HOST:localhost}
    port: ${MAIL_PORT:1025}
  mvc:
    problemdetails:
      enabled: true

server:
  shutdown: graceful
  error:
    include-stacktrace: never
    include-message: never

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  endpoint:
    health:
      probes:
        enabled: true

springdoc:
  api-docs:
    path: /v3/api-docs
```

`backend/src/main/resources/application-local.yml` (local-only defaults, matching `infra/docker/.env.example`):
```yaml
spring:
  datasource:
    password: ${DB_APP_PASSWORD:nexusops_app_local}
  flyway:
    password: ${DB_OWNER_PASSWORD:nexusops_owner_local}
```

`backend/src/main/resources/application-test.yml`:
```yaml
# Connection properties are supplied by IntegrationTestSupport (Testcontainers).
logging:
  level:
    org.testcontainers: WARN
```

`backend/src/main/resources/application-prod.yml`:
```yaml
logging:
  structured:
    format:
      console: ecs
```

- [ ] **Step 4: First migration: default privileges for the runtime role**

`backend/src/main/resources/db/migration/V0_1__schema_privileges.sql`:
```sql
-- Runs as nexusops_owner. Every table/sequence the owner creates from now on is
-- usable (DML only) by the runtime role. Tables that need narrower grants
-- (e.g. append-only audit_events) REVOKE explicitly in their own migration.
ALTER DEFAULT PRIVILEGES FOR ROLE nexusops_owner IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO nexusops_app;
ALTER DEFAULT PRIVILEGES FOR ROLE nexusops_owner IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO nexusops_app;
```

- [ ] **Step 5: Shared kernel module declaration**

`backend/src/main/java/com/nexusops/shared/package-info.java`:
```java
/**
 * Shared kernel: cross-cutting infrastructure (request context, errors, security baseline,
 * database guards). OPEN so every business module may depend on it; it must never depend on them.
 */
@org.springframework.modulith.ApplicationModule(type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.nexusops.shared;
```

- [ ] **Step 6: Write the modularity test**

`backend/src/test/java/com/nexusops/ModularityTest.java`:
```java
package com.nexusops;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTest {

    @Test
    void moduleBoundariesAreRespected() {
        ApplicationModules.of(NexusOpsApplication.class).verify();
    }
}
```

- [ ] **Step 7: Compile and run the modularity test**

Run: `cd backend && ./gradlew test --tests com.nexusops.ModularityTest`
Expected: `BUILD SUCCESSFUL`. The first run downloads Gradle 9.7.1 and the dependencies. If Gradle can't find JDK 25, check that `JAVA_HOME` points to `/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home`.

- [ ] **Step 8: Commit**

```bash
cd /Users/user/Desktop/nexusops && git add backend
git commit -m "chore(backend): Spring Boot 4.1 scaffold, profiles, schema privileges migration

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Backend shared kernel — request ids, ProblemDetail errors, security baseline, DB role guard

**Files:**
- Create: `backend/src/main/java/com/nexusops/shared/web/RequestIdFilter.java`, `RequestIds.java`, `WebConfig.java`, `ProblemDetails.java`, `GlobalExceptionHandler.java`
- Create: `backend/src/main/java/com/nexusops/shared/security/SecurityConfig.java`, `ProblemDetailSecurityHandlers.java`
- Create: `backend/src/main/java/com/nexusops/shared/db/DatabaseRoleGuard.java`
- Test: `backend/src/test/java/com/nexusops/shared/web/RequestIdFilterTest.java`, `GlobalExceptionHandlerTest.java`, `shared/db/DatabaseRoleGuardTest.java`, `support/IntegrationTestSupport.java`, `PlatformFoundationIT.java`, `ProdProfileRequiresSecretsTest.java`

**Interfaces:**
- Produces:
  - `RequestIdFilter`: constants `HEADER = "X-Request-Id"`, `CORRELATION_HEADER = "X-Correlation-Id"`, `MDC_REQUEST_ID = "request_id"`, `MDC_CORRELATION_ID = "correlation_id"`; method `static String sanitizeOrGenerate(String)`.
  - `RequestIds.current()` → `String` (the request id, or `"none"`).
  - `RequestIds.correlationId()` → `String`.
  - `ProblemDetails.of(HttpStatus status, String title, String detail)` → `ProblemDetail`, with `requestId` set.
  - `ProblemDetails.toMap(ProblemDetail)` → `Map<String,Object>`.
  - `GlobalExceptionHandler`: a `@RestControllerAdvice` that later plans extend by adding `@ExceptionHandler` methods for domain exceptions.
  - `SecurityConfig.PUBLIC_PATHS`: a `String[]` that Plan 2 extends with auth routes.
  - `DatabaseRoleGuard`: fails startup if `current_user` is a superuser or has `BYPASSRLS`.
  - `IntegrationTestSupport`: the abstract base class every later integration test extends. It exposes `static PostgreSQLContainer POSTGRES` and `OWNER_PASSWORD` / `APP_PASSWORD` constants.

- [ ] **Step 1: Write the failing RequestIdFilter test**

`backend/src/test/java/com/nexusops/shared/web/RequestIdFilterTest.java`:
```java
package com.nexusops.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void generatesRequestIdWhenAbsentAndEchoesIt() throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(), response, (req, res) -> {});
        assertThat(response.getHeader(RequestIdFilter.HEADER))
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    void preservesValidIncomingRequestId() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, "abc-123_X.y");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {});
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("abc-123_X.y");
    }

    @Test
    void replacesHostileOrOversizedRequestIds() {
        assertThat(RequestIdFilter.sanitizeOrGenerate("abc\nFAKE LOG LINE")).doesNotContain("FAKE");
        assertThat(RequestIdFilter.sanitizeOrGenerate("a".repeat(65))).hasSize(36);
        assertThat(RequestIdFilter.sanitizeOrGenerate("")).hasSize(36);
        assertThat(RequestIdFilter.sanitizeOrGenerate(null)).hasSize(36);
    }

    @Test
    void populatesMdcDuringChainAndClearsAfter() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, "req-1");
        var seenRequestId = new AtomicReference<String>();
        var seenCorrelationId = new AtomicReference<String>();
        FilterChain chain = (req, res) -> {
            seenRequestId.set(MDC.get(RequestIdFilter.MDC_REQUEST_ID));
            seenCorrelationId.set(MDC.get(RequestIdFilter.MDC_CORRELATION_ID));
        };

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(seenRequestId.get()).isEqualTo("req-1");
        assertThat(seenCorrelationId.get()).isEqualTo("req-1"); // defaults to the request id
        assertThat(MDC.get(RequestIdFilter.MDC_REQUEST_ID)).isNull();
        assertThat(MDC.get(RequestIdFilter.MDC_CORRELATION_ID)).isNull();
    }

    @Test
    void usesIncomingCorrelationIdWhenValid() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.CORRELATION_HEADER, "corr-9");
        var seen = new AtomicReference<String>();
        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> seen.set(MDC.get(RequestIdFilter.MDC_CORRELATION_ID)));
        assertThat(seen.get()).isEqualTo("corr-9");
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `cd backend && ./gradlew test --tests '*RequestIdFilterTest'`
Expected: compilation FAILS with "cannot find symbol: class RequestIdFilter".

- [ ] **Step 3: Implement RequestIdFilter, RequestIds and WebConfig**

`backend/src/main/java/com/nexusops/shared/web/RequestIdFilter.java`:
```java
package com.nexusops.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/** Assigns a safe request id (and correlation id) to every request, for logs, errors and audit. */
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String CORRELATION_HEADER = "X-Correlation-Id";
    public static final String MDC_REQUEST_ID = "request_id";
    public static final String MDC_CORRELATION_ID = "correlation_id";

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = sanitizeOrGenerate(request.getHeader(HEADER));
        String incomingCorrelation = request.getHeader(CORRELATION_HEADER);
        String correlationId = isSafe(incomingCorrelation) ? incomingCorrelation : requestId;

        MDC.put(MDC_REQUEST_ID, requestId);
        MDC.put(MDC_CORRELATION_ID, correlationId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_REQUEST_ID);
            MDC.remove(MDC_CORRELATION_ID);
        }
    }

    public static String sanitizeOrGenerate(String candidate) {
        return isSafe(candidate) ? candidate : UUID.randomUUID().toString();
    }

    private static boolean isSafe(String candidate) {
        return candidate != null && SAFE_ID.matcher(candidate).matches();
    }
}
```

`backend/src/main/java/com/nexusops/shared/web/RequestIds.java`:
```java
package com.nexusops.shared.web;

import org.slf4j.MDC;

/** Read access to the current request's ids (set by {@link RequestIdFilter}). */
public final class RequestIds {

    private RequestIds() {}

    public static String current() {
        String id = MDC.get(RequestIdFilter.MDC_REQUEST_ID);
        return id != null ? id : "none";
    }

    public static String correlationId() {
        String id = MDC.get(RequestIdFilter.MDC_CORRELATION_ID);
        return id != null ? id : current();
    }
}
```

`backend/src/main/java/com/nexusops/shared/web/WebConfig.java`:
```java
package com.nexusops.shared.web;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
class WebConfig {

    /** Runs before Spring Security so even 401/403 responses carry a request id. */
    @Bean
    FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        var registration = new FilterRegistrationBean<>(new RequestIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
```

- [ ] **Step 4: Run the filter test and confirm it passes**

Run: `./gradlew test --tests '*RequestIdFilterTest'`
Expected: PASS (5 tests). If `FilterRegistrationBean` isn't found, Boot 4 moved it; find the new package with `./gradlew dependencies`, or search the IDE for `FilterRegistrationBean` and fix the import.

- [ ] **Step 5: Write the failing GlobalExceptionHandler test**

`backend/src/test/java/com/nexusops/shared/web/GlobalExceptionHandlerTest.java`:
```java
package com.nexusops.shared.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {

    record Payload(@NotBlank String name) {}

    @RestController
    static class ThrowingController {
        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException("secret-db-detail password=hunter2");
        }

        @GetMapping("/denied")
        String denied() {
            throw new AccessDeniedException("nope");
        }

        @PostMapping("/validate")
        String validate(@Valid @RequestBody Payload payload) {
            return payload.name();
        }
    }

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new RequestIdFilter())
                .build();
    }

    @Test
    void unexpectedErrorsAreGeneric500WithoutLeakingDetails() throws Exception {
        mvc.perform(get("/boom").header(RequestIdFilter.HEADER, "req-500"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.requestId").value("req-500"))
                .andExpect(content().string(not(containsString("secret-db-detail"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))))
                .andExpect(content().string(not(containsString("trace"))));
    }

    @Test
    void accessDeniedIs403() throws Exception {
        mvc.perform(get("/denied"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.requestId").exists());
    }

    @Test
    void validationErrorsAre400WithFieldErrors() throws Exception {
        mvc.perform(post("/validate").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].message").exists())
                .andExpect(jsonPath("$.requestId").exists());
    }

    @Test
    void malformedJsonIs400() throws Exception {
        mvc.perform(post("/validate").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.requestId").exists());
    }
}
```

- [ ] **Step 6: Run it and confirm it fails**

Run: `./gradlew test --tests '*GlobalExceptionHandlerTest'`
Expected: compilation FAILS with "cannot find symbol: class GlobalExceptionHandler".

- [ ] **Step 7: Implement ProblemDetails and GlobalExceptionHandler**

`backend/src/main/java/com/nexusops/shared/web/ProblemDetails.java`:
```java
package com.nexusops.shared.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/** Factory for RFC 9457 problem details that always carry the request id. */
public final class ProblemDetails {

    public static final String REQUEST_ID = "requestId";

    private ProblemDetails() {}

    public static ProblemDetail of(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return withRequestId(problem);
    }

    public static ProblemDetail withRequestId(ProblemDetail problem) {
        problem.setProperty(REQUEST_ID, RequestIds.current());
        return problem;
    }

    /** Flat map form for writers that bypass Spring MVC message converters (security filters). */
    public static Map<String, Object> toMap(ProblemDetail problem) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", problem.getType().toString());
        body.put("title", problem.getTitle());
        body.put("status", problem.getStatus());
        if (problem.getDetail() != null) {
            body.put("detail", problem.getDetail());
        }
        if (problem.getInstance() != null) {
            body.put("instance", problem.getInstance().toString());
        }
        if (problem.getProperties() != null) {
            body.putAll(problem.getProperties());
        }
        return body;
    }
}
```

`backend/src/main/java/com/nexusops/shared/web/GlobalExceptionHandler.java`:
```java
package com.nexusops.shared.web;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps every exception to an RFC 9457 problem. Unexpected errors become a generic 500 whose
 * body never contains the exception's class or message (they are logged server-side only).
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        return problem(HttpStatus.FORBIDDEN, "Forbidden", "You do not have permission to perform this action.");
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex) {
        return problem(HttpStatus.UNAUTHORIZED, "Unauthorized", "Authentication is required.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "An unexpected error occurred.");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed.");
        problem.setTitle("Bad Request");
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of(
                        "field", fe.getField(),
                        "message", fe.getDefaultMessage() == null ? "is invalid" : fe.getDefaultMessage()))
                .toList();
        problem.setProperty("errors", errors);
        return createResponseEntity(problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(
            Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            ProblemDetails.withRequestId(problem);
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String title, String detail) {
        return ResponseEntity.status(status).body(ProblemDetails.of(status, title, detail));
    }
}
```

- [ ] **Step 8: Run the handler test and confirm it passes**

Run: `./gradlew test --tests '*GlobalExceptionHandlerTest'`
Expected: PASS (4 tests).

- [ ] **Step 9: Write the failing DatabaseRoleGuard test**

`backend/src/test/java/com/nexusops/shared/db/DatabaseRoleGuardTest.java`:
```java
package com.nexusops.shared.db;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DatabaseRoleGuardTest {

    @Test
    void acceptsUnprivilegedRole() {
        assertThatCode(() -> DatabaseRoleGuard.check(new DatabaseRoleGuard.RoleInfo("nexusops_app", false, false)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsSuperuser() {
        assertThatThrownBy(() -> DatabaseRoleGuard.check(new DatabaseRoleGuard.RoleInfo("postgres", true, true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("postgres")
                .hasMessageContaining("Row-Level Security");
    }

    @Test
    void rejectsBypassRlsRole() {
        assertThatThrownBy(() -> DatabaseRoleGuard.check(new DatabaseRoleGuard.RoleInfo("svc", false, true)))
                .isInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **Step 10: Run it and confirm it fails**

Run: `./gradlew test --tests '*DatabaseRoleGuardTest'`
Expected: compilation FAILS.

- [ ] **Step 11: Implement DatabaseRoleGuard**

`backend/src/main/java/com/nexusops/shared/db/DatabaseRoleGuard.java`:
```java
package com.nexusops.shared.db;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Tenant isolation relies on PostgreSQL Row-Level Security (ADR-0002). Superusers and BYPASSRLS
 * roles ignore RLS, so the application refuses to start if its runtime connection uses one.
 * There is intentionally no override switch.
 */
@Component
class DatabaseRoleGuard implements InitializingBean {

    record RoleInfo(String name, boolean superuser, boolean bypassRls) {}

    private final JdbcTemplate jdbc;

    DatabaseRoleGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void afterPropertiesSet() {
        RoleInfo role = jdbc.queryForObject(
                "select rolname, rolsuper, rolbypassrls from pg_roles where rolname = current_user",
                (rs, n) -> new RoleInfo(rs.getString(1), rs.getBoolean(2), rs.getBoolean(3)));
        check(role);
    }

    static void check(RoleInfo role) {
        if (role.superuser() || role.bypassRls()) {
            throw new IllegalStateException("Refusing to start: runtime database role '" + role.name()
                    + "' is a superuser or has BYPASSRLS, which would disable tenant Row-Level Security. "
                    + "Connect as the unprivileged runtime role (nexusops_app).");
        }
    }
}
```

- [ ] **Step 12: Run it and confirm it passes**

Run: `./gradlew test --tests '*DatabaseRoleGuardTest'`
Expected: PASS (3 tests).

- [ ] **Step 13: Implement the security baseline**

`backend/src/main/java/com/nexusops/shared/security/ProblemDetailSecurityHandlers.java`:
```java
package com.nexusops.shared.security;

import com.nexusops.shared.web.ProblemDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Writes 401/403 from the security filter chain as problem+json (MVC advice is not reached there). */
@Component
public class ProblemDetailSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final JsonMapper jsonMapper;

    public ProblemDetailSecurityHandlers(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        write(response, HttpStatus.UNAUTHORIZED, "Unauthorized", "Authentication is required.");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        write(response, HttpStatus.FORBIDDEN, "Forbidden", "You do not have permission to perform this action.");
    }

    private void write(HttpServletResponse response, HttpStatus status, String title, String detail)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), ProblemDetails.toMap(ProblemDetails.of(status, title, detail)));
    }
}
```

`backend/src/main/java/com/nexusops/shared/security/SecurityConfig.java`:
```java
package com.nexusops.shared.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Baseline: stateless, deny-by-default. Plan 2 adds the JWT resource server and the auth routes.
 * CSRF is disabled because the API authenticates with bearer tokens; the cookie-based
 * /api/v1/auth/refresh endpoint gets SameSite=Strict + Origin checks in Plan 2.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfig {

    public static final String[] PUBLIC_PATHS = {
        "/actuator/health", "/actuator/health/**", "/actuator/info",
        "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**",
        "/error"
    };

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, ProblemDetailSecurityHandlers handlers) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(handlers)
                        .accessDeniedHandler(handlers));
        return http.build();
    }
}
```

- [ ] **Step 14: Write the Testcontainers base class**

`backend/src/test/java/com/nexusops/support/IntegrationTestSupport.java`:
```java
package com.nexusops.support;

import java.nio.file.Path;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Base for integration tests. Starts Postgres (bootstrapped with the SAME role script as local
 * compose) and Redis once per JVM. The app connects as nexusops_app, never as the superuser,
 * so RLS behaves exactly as in production.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTestSupport {

    public static final String OWNER_PASSWORD = "owner_test";
    public static final String APP_PASSWORD = "app_test";

    public static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("nexusops")
            .withUsername("postgres")
            .withPassword("postgres_test")
            .withEnv("NEXUSOPS_OWNER_PASSWORD", OWNER_PASSWORD)
            .withEnv("NEXUSOPS_APP_PASSWORD", APP_PASSWORD)
            .withCopyFileToContainer(
                    MountableFile.forHostPath(Path.of("../infra/docker/postgres/init/01-roles.sh"), 0755),
                    "/docker-entrypoint-initdb.d/01-roles.sh");

    @SuppressWarnings("resource")
    public static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "nexusops_app");
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.user", () -> "nexusops_owner");
        registry.add("spring.flyway.password", () -> OWNER_PASSWORD);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
```
If `org.testcontainers.postgresql.PostgreSQLContainer` doesn't resolve, the BOM brought an older Testcontainers 1.x. Use `org.testcontainers.containers.PostgreSQLContainer<?>` instead and add the generic parameter.

- [ ] **Step 15: Write the failing platform integration test**

`backend/src/test/java/com/nexusops/PlatformFoundationIT.java`:
```java
package com.nexusops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.shared.web.RequestIdFilter;
import com.nexusops.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class PlatformFoundationIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void healthIsUpAndPublic() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void openApiDocumentIsServed() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    }

    @Test
    void unknownApiRouteRequiresAuthenticationAsProblemJson() throws Exception {
        mvc.perform(get("/api/v1/anything").header(RequestIdFilter.HEADER, "it-401"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(RequestIdFilter.HEADER, "it-401"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.requestId").value("it-401"));
    }

    @Test
    void prometheusMetricsAreNotPublic() throws Exception {
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
    }

    @Test
    void runtimeRoleCannotBypassRls() {
        var row = jdbc.queryForMap(
                "select current_user as name, rolsuper, rolbypassrls from pg_roles where rolname = current_user");
        assertThat(row.get("name")).isEqualTo("nexusops_app");
        assertThat(row.get("rolsuper")).isEqualTo(false);
        assertThat(row.get("rolbypassrls")).isEqualTo(false);
    }

    @Test
    void migrationsRanAsOwner() {
        String owner = jdbc.queryForObject(
                "select tableowner from pg_tables where tablename = 'flyway_schema_history'", String.class);
        assertThat(owner).isEqualTo("nexusops_owner");
    }

    @Test
    void runtimeRoleCannotRunDdl() {
        assertThatThrownBy(() -> jdbc.execute("create table should_fail(id int)"))
                .isInstanceOf(BadSqlGrammarException.class)
                .hasMessageContaining("permission denied");
    }
}
```

- [ ] **Step 16: Run it**

Run: `./gradlew test --tests '*PlatformFoundationIT'` (Docker must be running).
Expected: PASS (7 tests). If `AutoConfigureMockMvc` doesn't resolve, Boot 4 placed it in `org.springframework.boot.webmvc.test.autoconfigure`, so check that the `spring-boot-starter-webmvc-test` dependency is present. If `migrationsRanAsOwner` finds no table, check that `V0_1__schema_privileges.sql` is under `db/migration`.

- [ ] **Step 17: Write the prod-secrets test**

`backend/src/test/java/com/nexusops/ProdProfileRequiresSecretsTest.java`:
```java
package com.nexusops;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;

class ProdProfileRequiresSecretsTest {

    @Test
    void prodProfileFailsFastWithoutDatabasePassword() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(NexusOpsApplication.class)
                        .profiles("prod")
                        .properties("spring.main.web-application-type=none")
                        .run())
                .rootCause()
                .hasMessageContaining("DB_APP_PASSWORD");
    }
}
```

- [ ] **Step 18: Run it**

Run: `./gradlew test --tests '*ProdProfileRequiresSecretsTest'`
Expected: PASS. If it fails because the context starts or the error mentions a connection instead, the binder isn't failing on the unresolved `${DB_APP_PASSWORD}`. Fix it by adding a `@ConfigurationProperties`-free fail-fast check to `DatabaseRoleGuard`'s module: a `@Component` that runs in the `prod` profile and throws `IllegalStateException("Missing required environment variable DB_APP_PASSWORD")` when `spring.datasource.password` is empty. Then re-run.

- [ ] **Step 19: Run the full backend build**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`, with all tests passing.

- [ ] **Step 20: Commit**

```bash
cd /Users/user/Desktop/nexusops && git add backend
git commit -m "feat(backend): shared kernel - request ids, problem+json errors, deny-by-default security, RLS role guard

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Frontend scaffold with a tested API client

**Files:**
- Create: the `frontend/` Vite react-ts scaffold, plus the files listed in the file-structure section.

**Interfaces:**
- Produces: `createApiClient(options: ApiClientOptions): ApiClient` with methods `request<T>`, `get<T>`, `post<T>`, `put<T>`, `patch<T>` and `del<T>`.
- Produces: `class ApiError extends Error { problem: ProblemDetail; status: number }`.
- Produces: `type ProblemDetail`.
- Produces: `createMemoryTokenStore(): TokenStore`.
- Plan 2's auth provider wires `refresh` to `POST /api/v1/auth/refresh`.

- [ ] **Step 1: Scaffold and install**

```bash
cd /Users/user/Desktop/nexusops && npm create vite@latest frontend -- --template react-ts
# if prompted "Install with npm and start now?" answer No
cd frontend && npm install
npm install react-router @tanstack/react-query react-hook-form zod @hookform/resolvers
npm install -D tailwindcss @tailwindcss/vite vitest jsdom @testing-library/react @testing-library/jest-dom @testing-library/user-event prettier @playwright/test @types/node
```

- [ ] **Step 2: Path alias, Tailwind, dev proxy and Vitest config**

In both `frontend/tsconfig.json` and `frontend/tsconfig.app.json`, add to `compilerOptions`:
```json
"baseUrl": ".",
"paths": { "@/*": ["./src/*"] }
```

Replace `frontend/vite.config.ts`:
```ts
/// <reference types="vitest/config" />
import path from 'node:path'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: { '@': path.resolve(__dirname, './src') },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
    include: ['src/**/*.test.{ts,tsx}'],
  },
})
```

Replace `frontend/src/index.css`:
```css
@import 'tailwindcss';
```

Create `frontend/src/test/setup.ts`:
```ts
import '@testing-library/jest-dom/vitest'
```

Delete the scaffold demo files: `rm src/App.css src/assets/react.svg public/vite.svg` (only delete those that exist).

- [ ] **Step 3: Initialise shadcn/ui**

```bash
cd frontend && npx shadcn@latest init -d && npx shadcn@latest add button card
```
Expected: `components.json` is created, along with `src/lib/utils.ts`, `src/components/ui/button.tsx` and `src/components/ui/card.tsx`. Theme CSS variables are added to `src/index.css`.

- [ ] **Step 4: Scripts and Prettier**

In `frontend/package.json`, set `"scripts"` to:
```json
"scripts": {
  "dev": "vite",
  "build": "tsc -b && vite build",
  "preview": "vite preview",
  "lint": "eslint .",
  "typecheck": "tsc -b",
  "format": "prettier --write .",
  "format:check": "prettier --check .",
  "test": "vitest run",
  "test:watch": "vitest",
  "e2e": "playwright test"
}
```

Create `frontend/.prettierrc`:
```json
{ "semi": false, "singleQuote": true, "printWidth": 100, "trailingComma": "all" }
```

Create `frontend/.prettierignore`:
```
dist
node_modules
playwright-report
test-results
src/components/ui
```

- [ ] **Step 5: Write the failing API client tests**

`frontend/src/lib/api/client.test.ts`:
```ts
import { describe, expect, it, vi } from 'vitest'
import { ApiError, createApiClient } from './client'
import { createMemoryTokenStore } from './tokenStore'

function json(status: number, body: unknown, contentType = 'application/json') {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': contentType } })
}

function setup(responses: Array<Response | ((req: Request) => Response)>, refreshResult: string | null = 'new-token') {
  const tokens = createMemoryTokenStore()
  tokens.set('old-token')
  const calls: Request[] = []
  const queue = [...responses]
  const fetchImpl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const req = new Request(new URL(String(input), 'http://localhost'), init)
    calls.push(req)
    const next = queue.shift()
    if (!next) throw new Error('unexpected fetch')
    return typeof next === 'function' ? next(req) : next
  })
  const refresh = vi.fn(async () => refreshResult)
  const onAuthFailure = vi.fn()
  const client = createApiClient({ baseUrl: '/api/v1', tokens, refresh, onAuthFailure, fetchImpl })
  return { client, tokens, calls, refresh, onAuthFailure, fetchImpl }
}

describe('api client', () => {
  it('sends the bearer token and parses JSON', async () => {
    const { client, calls } = setup([json(200, { ok: true })])
    await expect(client.get('/me')).resolves.toEqual({ ok: true })
    expect(calls[0].headers.get('Authorization')).toBe('Bearer old-token')
    expect(calls[0].url).toBe('http://localhost/api/v1/me')
  })

  it('refreshes once on 401 and retries with the new token', async () => {
    const { client, calls, refresh, tokens } = setup([json(401, {}), json(200, { ok: 1 })])
    await expect(client.get('/me')).resolves.toEqual({ ok: 1 })
    expect(refresh).toHaveBeenCalledTimes(1)
    expect(calls[1].headers.get('Authorization')).toBe('Bearer new-token')
    expect(tokens.get()).toBe('new-token')
  })

  it('shares a single refresh between concurrent 401s', async () => {
    const { client, refresh } = setup([
      json(401, {}),
      json(401, {}),
      json(401, {}),
      json(200, 1),
      json(200, 2),
      json(200, 3),
    ])
    await Promise.all([client.get('/a'), client.get('/b'), client.get('/c')])
    expect(refresh).toHaveBeenCalledTimes(1)
  })

  it('clears the token and reports auth failure when refresh fails', async () => {
    const { client, tokens, onAuthFailure } = setup([json(401, { status: 401, title: 'Unauthorized' })], null)
    const error = await client.get('/me').catch((e: unknown) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).status).toBe(401)
    expect(tokens.get()).toBeNull()
    expect(onAuthFailure).toHaveBeenCalledTimes(1)
  })

  it('does not loop when the retried request is still 401', async () => {
    const { client, refresh, fetchImpl } = setup([json(401, {}), json(401, { status: 401 })])
    await expect(client.get('/me')).rejects.toBeInstanceOf(ApiError)
    expect(refresh).toHaveBeenCalledTimes(1)
    expect(fetchImpl).toHaveBeenCalledTimes(2)
  })

  it('exposes problem details from error responses', async () => {
    const problem = {
      status: 400,
      title: 'Bad Request',
      requestId: 'r1',
      errors: [{ field: 'email', message: 'must be valid' }],
    }
    const { client } = setup([json(400, problem, 'application/problem+json')])
    const error = (await client.post('/x', { a: 1 }).catch((e: unknown) => e)) as ApiError
    expect(error.problem.requestId).toBe('r1')
    expect(error.problem.errors?.[0].field).toBe('email')
  })

  it('handles non-JSON error bodies', async () => {
    const { client } = setup([new Response('gateway down', { status: 502, statusText: 'Bad Gateway' })])
    const error = (await client.get('/x').catch((e: unknown) => e)) as ApiError
    expect(error.status).toBe(502)
    expect(error.problem.title).toBe('Bad Gateway')
  })

  it('sends JSON bodies with content-type and returns undefined for 204', async () => {
    const { client, calls } = setup([new Response(null, { status: 204 })])
    await expect(client.post('/x', { a: 1 })).resolves.toBeUndefined()
    expect(calls[0].headers.get('Content-Type')).toBe('application/json')
    expect(await calls[0].text()).toBe('{"a":1}')
  })
})
```

- [ ] **Step 6: Run them and confirm they fail**

Run: `cd frontend && npx vitest run src/lib/api`
Expected: FAIL with "Failed to resolve import './client'".

- [ ] **Step 7: Implement the token store and client**

`frontend/src/lib/api/tokenStore.ts`:
```ts
/** Access tokens live only in memory (never localStorage) — see ADR-0003. */
export interface TokenStore {
  get(): string | null
  set(token: string | null): void
}

export function createMemoryTokenStore(): TokenStore {
  let token: string | null = null
  return {
    get: () => token,
    set: (next) => {
      token = next
    },
  }
}
```

`frontend/src/lib/api/client.ts`:
```ts
import type { TokenStore } from './tokenStore'

export interface ProblemDetail {
  type?: string
  title?: string
  status: number
  detail?: string
  instance?: string
  requestId?: string
  errors?: Array<{ field: string; message: string }>
}

export class ApiError extends Error {
  readonly problem: ProblemDetail

  constructor(problem: ProblemDetail) {
    super(problem.detail ?? problem.title ?? `Request failed with status ${problem.status}`)
    this.name = 'ApiError'
    this.problem = problem
  }

  get status(): number {
    return this.problem.status
  }
}

export interface ApiClientOptions {
  baseUrl: string
  tokens: TokenStore
  /** Obtains a new access token (e.g. via the refresh cookie). Resolve null when refresh is impossible. */
  refresh: () => Promise<string | null>
  onAuthFailure?: () => void
  fetchImpl?: typeof fetch
}

export interface ApiClient {
  request<T>(path: string, init?: RequestInit): Promise<T>
  get<T>(path: string): Promise<T>
  post<T>(path: string, body?: unknown): Promise<T>
  put<T>(path: string, body?: unknown): Promise<T>
  patch<T>(path: string, body?: unknown): Promise<T>
  del<T>(path: string): Promise<T>
}

export function createApiClient(options: ApiClientOptions): ApiClient {
  const doFetch: typeof fetch = (input, init) => (options.fetchImpl ?? fetch)(input, init)
  let inflightRefresh: Promise<string | null> | null = null

  function refreshOnce(): Promise<string | null> {
    if (!inflightRefresh) {
      inflightRefresh = options
        .refresh()
        .catch(() => null)
        .finally(() => {
          inflightRefresh = null
        })
    }
    return inflightRefresh
  }

  function send(path: string, init: RequestInit, token: string | null): Promise<Response> {
    const headers = new Headers(init.headers)
    if (token) headers.set('Authorization', `Bearer ${token}`)
    if (init.body !== undefined && !headers.has('Content-Type')) {
      headers.set('Content-Type', 'application/json')
    }
    headers.set('Accept', 'application/json, application/problem+json')
    return doFetch(`${options.baseUrl}${path}`, { ...init, headers, credentials: 'include' })
  }

  async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
    let response = await send(path, init, options.tokens.get())
    if (response.status === 401) {
      const newToken = await refreshOnce()
      if (newToken) {
        options.tokens.set(newToken)
        response = await send(path, init, newToken)
      } else {
        options.tokens.set(null)
        options.onAuthFailure?.()
      }
    }
    if (!response.ok) throw new ApiError(await toProblem(response))
    if (response.status === 204) return undefined as T
    return (await response.json()) as T
  }

  const withBody = (method: string) => <T>(path: string, body?: unknown) =>
    request<T>(path, { method, body: body === undefined ? undefined : JSON.stringify(body) })

  return {
    request,
    get: <T>(path: string) => request<T>(path),
    post: withBody('POST'),
    put: withBody('PUT'),
    patch: withBody('PATCH'),
    del: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
  }
}

async function toProblem(response: Response): Promise<ProblemDetail> {
  const contentType = response.headers.get('Content-Type') ?? ''
  if (contentType.includes('json')) {
    try {
      const body = (await response.json()) as Partial<ProblemDetail>
      return { ...body, status: response.status }
    } catch {
      // fall through to the generic problem below
    }
  }
  return { status: response.status, title: response.statusText || 'Request failed' }
}
```

- [ ] **Step 8: Run the client tests and confirm they pass**

Run: `npx vitest run src/lib/api`
Expected: PASS (8 tests).

- [ ] **Step 9: App shell with router and query client; write its test first**

`frontend/src/app/App.test.tsx`:
```tsx
import { render, screen } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { describe, expect, it } from 'vitest'
import { routes } from './router'

describe('App routes', () => {
  it('renders the home page', () => {
    render(<RouterProvider router={createMemoryRouter(routes, { initialEntries: ['/'] })} />)
    expect(screen.getByRole('heading', { name: /nexusops/i })).toBeInTheDocument()
  })

  it('renders a not-found page for unknown routes', () => {
    render(<RouterProvider router={createMemoryRouter(routes, { initialEntries: ['/nope'] })} />)
    expect(screen.getByRole('heading', { name: /page not found/i })).toBeInTheDocument()
  })
})
```

Run: `npx vitest run src/app`. Expected: FAIL (`./router` is missing).

- [ ] **Step 10: Implement the pages, router and App**

`frontend/src/pages/HomePage.tsx`:
```tsx
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'

export function HomePage() {
  return (
    <main className="mx-auto flex min-h-screen max-w-3xl items-center px-4">
      <Card className="w-full">
        <CardHeader>
          <CardTitle>
            <h1 className="text-2xl font-semibold">NexusOps</h1>
          </CardTitle>
          <CardDescription>One tenant, one business context, one permission model.</CardDescription>
        </CardHeader>
        <CardContent className="text-sm text-muted-foreground">
          Platform foundation is running. Sign-in arrives with the identity milestone.
        </CardContent>
      </Card>
    </main>
  )
}
```

`frontend/src/pages/NotFoundPage.tsx`:
```tsx
import { Link } from 'react-router'
import { Button } from '@/components/ui/button'

export function NotFoundPage() {
  return (
    <main className="mx-auto flex min-h-screen max-w-3xl flex-col items-start justify-center gap-4 px-4">
      <h1 className="text-2xl font-semibold">Page not found</h1>
      <p className="text-sm text-muted-foreground">The page you are looking for does not exist.</p>
      <Button asChild>
        <Link to="/">Go home</Link>
      </Button>
    </main>
  )
}
```

`frontend/src/app/router.tsx`:
```tsx
import type { RouteObject } from 'react-router'
import { HomePage } from '@/pages/HomePage'
import { NotFoundPage } from '@/pages/NotFoundPage'

export const routes: RouteObject[] = [
  { path: '/', element: <HomePage /> },
  { path: '*', element: <NotFoundPage /> },
]
```

`frontend/src/app/queryClient.ts`:
```ts
import { QueryClient } from '@tanstack/react-query'
import { ApiError } from '@/lib/api/client'

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      retry: (failureCount, error) =>
        !(error instanceof ApiError && error.status < 500) && failureCount < 2,
    },
  },
})
```

`frontend/src/app/App.tsx`:
```tsx
import { QueryClientProvider } from '@tanstack/react-query'
import { createBrowserRouter, RouterProvider } from 'react-router'
import { queryClient } from './queryClient'
import { routes } from './router'

const router = createBrowserRouter(routes)

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  )
}
```

Replace `frontend/src/main.tsx`:
```tsx
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './app/App'
import './index.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
```
Delete the scaffold's `src/App.tsx`. Set `<title>NexusOps</title>` in `frontend/index.html`.

- [ ] **Step 11: Playwright smoke test**

`frontend/playwright.config.ts`:
```ts
import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? 'github' : 'list',
  use: { baseURL: 'http://localhost:5173', trace: 'on-first-retry' },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: { command: 'npm run dev', url: 'http://localhost:5173', reuseExistingServer: !process.env.CI },
})
```

`frontend/e2e/smoke.spec.ts`:
```ts
import { expect, test } from '@playwright/test'

test('home page loads', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'NexusOps' })).toBeVisible()
})
```

Add `e2e` to Vitest's exclusion by keeping `include: ['src/**/*.test.{ts,tsx}']` (already set). Install the browser: `npx playwright install chromium`.

- [ ] **Step 12: Run the whole frontend check suite**

Run: `npm run format && npm run lint && npm run typecheck && npm test && npm run build && npm run e2e`
Expected:
- lint shows 0 errors;
- typecheck is OK;
- Vitest passes all 10 tests;
- build writes `dist/`;
- Playwright passes 1 test.

If ESLint flags shadcn's generated `src/components/ui/*` (react-refresh rule), add `'src/components/ui'` to `globalIgnores` in `eslint.config.js`.

- [ ] **Step 13: Commit**

```bash
cd /Users/user/Desktop/nexusops && git add frontend
git commit -m "feat(frontend): Vite React TS scaffold with shadcn, router, query client, tested API client

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: ai-service skeleton

**Files:**
- Create: `ai-service/pyproject.toml`, `ai-service/app/__init__.py`, `ai-service/app/main.py`, `ai-service/tests/test_health.py`

**Interfaces:**
- Produces: `GET /health` → `200 {"status":"ok","service":"ai-service"}`. The ASGI app is `app.main:app`.

- [ ] **Step 1: Write `pyproject.toml`**

```toml
[project]
name = "nexusops-ai-service"
version = "0.1.0"
requires-python = ">=3.13"
dependencies = [
    "fastapi>=0.115",
    "uvicorn[standard]>=0.32",
    "pydantic>=2.9",
]

[project.optional-dependencies]
dev = ["pytest>=8", "httpx>=0.27", "ruff>=0.7"]

[build-system]
requires = ["hatchling"]
build-backend = "hatchling.build"

[tool.hatch.build.targets.wheel]
packages = ["app"]

[tool.ruff]
line-length = 100
target-version = "py313"

[tool.ruff.lint]
select = ["E", "F", "I", "B", "UP"]

[tool.pytest.ini_options]
testpaths = ["tests"]
```

- [ ] **Step 2: Write the failing test**

`ai-service/tests/test_health.py`:
```python
from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_health_reports_ok() -> None:
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok", "service": "ai-service"}
```

- [ ] **Step 3: Create the venv and confirm the test fails**

```bash
cd /Users/user/Desktop/nexusops/ai-service && python3 -m venv .venv && .venv/bin/pip install -q -e '.[dev]' ; .venv/bin/pytest -q
```
Expected: FAIL with `ModuleNotFoundError: No module named 'app'` (or the pip install errors because `app/` is missing; create `app/__init__.py` as an empty file and re-run the install).

- [ ] **Step 4: Implement the app**

`ai-service/app/__init__.py`: empty file.

`ai-service/app/main.py`:
```python
"""NexusOps AI service. Phase 12 adds RAG, classification and forecasting behind the
Spring Boot AI gateway; until then it only exposes a health endpoint."""

from fastapi import FastAPI
from pydantic import BaseModel


class Health(BaseModel):
    status: str
    service: str


app = FastAPI(title="NexusOps AI Service", version="0.1.0")


@app.get("/health", response_model=Health)
def health() -> Health:
    return Health(status="ok", service="ai-service")
```

- [ ] **Step 5: Run lint, format check and tests**

Run: `.venv/bin/ruff check . && .venv/bin/ruff format --check . && .venv/bin/pytest -q`
Expected: no lint errors and `1 passed`. If the format check fails, run `.venv/bin/ruff format .` and re-run.

- [ ] **Step 6: Commit**

```bash
cd /Users/user/Desktop/nexusops && git add ai-service
git commit -m "feat(ai-service): FastAPI skeleton with health endpoint

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Container images and the compose `app` profile

**Files:**
- Create: `backend/Dockerfile`, `backend/.dockerignore`, `frontend/Dockerfile`, `frontend/nginx.conf`, `frontend/.dockerignore`, `ai-service/Dockerfile`, `ai-service/.dockerignore`
- Modify: `infra/docker/docker-compose.yml` (add the `app` profile services)

**Interfaces:**
- Produces: `docker compose -f infra/docker/docker-compose.yml --profile app up -d --build`. The backend is on :8080, the frontend on :3000 (nginx proxies `/api` to the backend) and the ai-service on :8000.

- [ ] **Step 1: Backend image**

`backend/.dockerignore`:
```
build
.gradle
.idea
*.iml
```

`backend/Dockerfile`:
```dockerfile
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon bootJar -x test && cp build/libs/*-SNAPSHOT.jar /src/app.jar

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 --no-create-home app
WORKDIR /app
COPY --from=build /src/app.jar app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
```
If `cp build/libs/*-SNAPSHOT.jar` matches two files (a `-plain.jar` exists), change it to `cp build/libs/backend-*-SNAPSHOT.jar` after excluding plain with `tasks.jar { enabled = false }` in `build.gradle.kts`.

- [ ] **Step 2: Frontend image**

`frontend/.dockerignore`:
```
node_modules
dist
test-results
playwright-report
```

`frontend/nginx.conf`:
```nginx
server {
    listen 8080;
    root /usr/share/nginx/html;
    index index.html;

    add_header X-Content-Type-Options nosniff always;
    add_header X-Frame-Options DENY always;
    add_header Referrer-Policy strict-origin-when-cross-origin always;

    location /api/ {
        proxy_pass http://backend:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location / {
        try_files $uri $uri/ /index.html;
    }
}
```

`frontend/Dockerfile`:
```dockerfile
FROM node:22-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginxinc/nginx-unprivileged:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 8080
```

- [ ] **Step 3: ai-service image**

`ai-service/.dockerignore`:
```
.venv
**/__pycache__
.pytest_cache
.ruff_cache
tests
```

`ai-service/Dockerfile`:
```dockerfile
FROM python:3.13-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
COPY pyproject.toml ./
COPY app app
RUN pip install --no-cache-dir . && useradd --system --uid 10001 --no-create-home app
USER 10001
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
```

- [ ] **Step 4: Add the `app` profile to compose**

Append under `services:` in `infra/docker/docker-compose.yml`:
```yaml
  backend:
    profiles: [app]
    build: ../../backend
    environment:
      SPRING_PROFILES_ACTIVE: local
      DB_URL: jdbc:postgresql://postgres:5432/nexusops
      DB_APP_PASSWORD: ${DB_APP_PASSWORD:-nexusops_app_local}
      DB_OWNER_PASSWORD: ${DB_OWNER_PASSWORD:-nexusops_owner_local}
      REDIS_HOST: redis
      REDIS_PORT: "6379"
      MAIL_HOST: mailpit
      MAIL_PORT: "1025"
    ports:
      - "8080:8080"
    depends_on:
      postgres: { condition: service_healthy }
      redis: { condition: service_healthy }

  frontend:
    profiles: [app]
    build: ../../frontend
    ports:
      - "3000:8080"
    depends_on: [backend]

  ai-service:
    profiles: [app]
    build: ../../ai-service
    ports:
      - "8000:8000"
```

- [ ] **Step 5: Build and smoke-test the full stack**

Run:
```bash
cd /Users/user/Desktop/nexusops && docker compose -f infra/docker/docker-compose.yml --profile app up -d --build \
 && for i in $(seq 1 60); do curl -fs localhost:8080/actuator/health >/dev/null && break; sleep 2; done; \
 curl -s localhost:8080/actuator/health; echo; curl -s -o /dev/null -w "%{http_code}\n" localhost:3000/; \
 curl -s localhost:3000/api/v1/anything; echo; curl -s localhost:8000/health
```
Expected:
```
{"status":"UP",...}
200
{"type":"about:blank","title":"Unauthorized","status":401,...,"requestId":"..."}
{"status":"ok","service":"ai-service"}
```
Then run `docker compose -f infra/docker/docker-compose.yml --profile app stop backend frontend ai-service`.

- [ ] **Step 6: Commit**

```bash
git add backend/Dockerfile backend/.dockerignore frontend/Dockerfile frontend/nginx.conf frontend/.dockerignore ai-service/Dockerfile ai-service/.dockerignore infra/docker/docker-compose.yml backend/build.gradle.kts
git commit -m "build: container images for backend, frontend, ai-service and compose app profile

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: CI pipeline, Makefile targets, README, then merge

**Files:**
- Create: `.github/workflows/ci.yml`, `README.md`
- Modify: `Makefile`

**Interfaces:**
- Produces: the CI jobs `backend`, `frontend`, `ai-service`, `trivy-fs`, `dependency-review` (PRs only), `images` (matrix) and `e2e`.

- [ ] **Step 1: Write the workflow (actions pinned by SHA, resolved on 2026-10-04)**

`.github/workflows/ci.yml`:
```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:
  workflow_dispatch:

permissions:
  contents: read

concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: true

jobs:
  backend:
    runs-on: ubuntu-latest
    defaults: { run: { working-directory: backend } }
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
      - uses: actions/setup-java@de7274f081f381c8f8158605e0321c36c376e2e6 # v6.0.1
        with: { distribution: temurin, java-version: "25" }
      - uses: gradle/actions/setup-gradle@3f5f9adaf7d9fecd50b5935e54106014257a94e6 # v6.4.0
      - run: ./gradlew build --no-daemon

  frontend:
    runs-on: ubuntu-latest
    defaults: { run: { working-directory: frontend } }
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
      - uses: actions/setup-node@820762786026740c76f36085b0efc47a31fe5020 # v7.0.0
        with: { node-version: "22", cache: npm, cache-dependency-path: frontend/package-lock.json }
      - run: npm ci
      - run: npm run format:check
      - run: npm run lint
      - run: npm run typecheck
      - run: npm test
      - run: npm run build

  ai-service:
    runs-on: ubuntu-latest
    defaults: { run: { working-directory: ai-service } }
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
      - uses: actions/setup-python@5fda3b95a4ea91299a34e894583c3862153e4b97 # v7.0.0
        with: { python-version: "3.13" }
      - run: pip install -e '.[dev]'
      - run: ruff check .
      - run: ruff format --check .
      - run: pytest -q

  trivy-fs:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
      - uses: aquasecurity/trivy-action@ed142fd0673e97e23eac54620cfb913e5ce36c25 # v0.36.0
        with:
          scan-type: fs
          scan-ref: .
          scanners: vuln,secret,misconfig
          severity: CRITICAL,HIGH
          ignore-unfixed: true
          exit-code: "1"

  dependency-review:
    if: github.event_name == 'pull_request'
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
      - uses: actions/dependency-review-action@a1d282b36b6f3519aa1f3fc636f609c47dddb294 # v5.0.0
        with: { fail-on-severity: high }

  images:
    needs: [backend, frontend, ai-service]
    runs-on: ubuntu-latest
    strategy:
      matrix:
        service: [backend, frontend, ai-service]
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
      - run: docker build -t nexusops-${{ matrix.service }}:ci ${{ matrix.service }}
      - uses: aquasecurity/trivy-action@ed142fd0673e97e23eac54620cfb913e5ce36c25 # v0.36.0
        with:
          image-ref: nexusops-${{ matrix.service }}:ci
          severity: CRITICAL,HIGH
          ignore-unfixed: true
          exit-code: "1"

  e2e:
    if: github.event_name != 'pull_request'
    needs: [frontend]
    runs-on: ubuntu-latest
    defaults: { run: { working-directory: frontend } }
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
      - uses: actions/setup-node@820762786026740c76f36085b0efc47a31fe5020 # v7.0.0
        with: { node-version: "22", cache: npm, cache-dependency-path: frontend/package-lock.json }
      - run: npm ci
      - run: npx playwright install --with-deps chromium
      - run: npm run e2e
```

- [ ] **Step 2: Lint the workflow**

Run: `docker run --rm -v "$PWD:/repo" -w /repo rhysd/actionlint:latest -color`
Expected: no output, exit 0.

- [ ] **Step 3: Run Trivy locally against the repo (same gate as CI)**

Run: `docker run --rm -v "$PWD:/repo" aquasec/trivy:latest fs --scanners vuln,secret,misconfig --severity CRITICAL,HIGH --ignore-unfixed --exit-code 1 /repo`
Expected: exit 0. If it reports misconfigurations in the Dockerfiles (for example a missing `HEALTHCHECK`), fix the finding where it's real. If not, add a justified ignore to a `.trivyignore` file with one comment line per ID.

- [ ] **Step 4: Extend the Makefile**

Append to `Makefile`:
```make
.PHONY: up-all backend frontend ai test test-backend test-frontend test-ai e2e
up-all:        ## build and start the whole stack (frontend http://localhost:3000)
	$(COMPOSE) --profile app up -d --build
backend:       ## run the API locally (needs `make up`)
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
```

- [ ] **Step 5: Write the README**

`README.md`:
````markdown
# NexusOps

Multi-tenant business operations platform (capstone → MVP). One tenant, one business context,
one permission model, one operational event stream — with modular business capabilities on top.

- Blueprint: `docs/research/master-blueprint.md`
- Current design: `docs/superpowers/specs/2026-10-04-platform-foundation-design.md`
- Decisions: `docs/decisions/`

## Prerequisites
JDK 25, Node 22, Python 3.13, Docker.

## Quick start
```bash
make up                 # Postgres :5433, Redis :6380, Mailpit UI http://localhost:8025
make backend            # API http://localhost:8080 (Swagger: /swagger-ui.html)
make frontend           # UI  http://localhost:5173
```
Or run everything in containers: `make up-all` (UI http://localhost:3000).

The ai-service needs a one-time setup: `cd ai-service && python3 -m venv .venv && .venv/bin/pip install -e '.[dev]'`.

## Tests
`make test` runs the backend (including Testcontainers integration tests, so Docker must be running), the frontend and the ai-service. `make e2e` runs Playwright.

## Database roles
The app connects as `nexusops_app` (no superuser, no BYPASSRLS) and refuses to start otherwise. Flyway migrates as `nexusops_owner`. See ADR-0002. Run `make reset-db` to wipe the local database.

## Branching
Trunk-based: `main` is always releasable; work happens on short-lived `feat/*` / `fix/*` branches merged via PR once CI passes.
````

- [ ] **Step 6: Final full verification**

Run: `make test && make e2e`
Expected: everything passes, with backend `BUILD SUCCESSFUL`, Vitest passing all tests, `1 passed` from pytest and `1 passed` from Playwright.

- [ ] **Step 7: Commit and merge to main**

```bash
git add .github Makefile README.md .trivyignore 2>/dev/null; git add .github Makefile README.md
git commit -m "ci: GitHub Actions pipeline (SHA-pinned), Makefile targets, README

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git checkout main && git merge --no-ff feat/foundation -m "Merge feat/foundation: Phase 1 engineering foundation

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git branch -d feat/foundation
```

---

## Exit criteria (blueprint Phase 1)

| Criterion | Proven by |
|---|---|
| Backend starts | `PlatformFoundationIT.healthIsUpAndPublic`; Task 6 smoke test |
| Frontend starts | Playwright smoke test; Task 6 smoke test |
| Migrations work | `PlatformFoundationIT.migrationsRanAsOwner` |
| CI passes | Locally: `make test && make e2e` + actionlint + Trivy. On GitHub: after you push |
| Least-privilege DB role, ready for Plan 2's RLS | `runtimeRoleCannotBypassRls`, `runtimeRoleCannotRunDdl`, `DatabaseRoleGuardTest` |
