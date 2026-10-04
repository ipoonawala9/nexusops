# ADR-0001: Modular monolith with an event backbone added later

- Status: Accepted
- Date: 2026-10-04

## Context
The team has 3 people. The blueprint (§10, §31) warns against starting with microservices or Kubernetes. The domains (tenancy, identity, authorization, audit, and later CRM, Inventory, HelpDesk and HRMS) share one transactional tenant context.

## Decision
- Build a single Spring Boot 4 application with bounded modules under `com.nexusops.*`: `shared`, `tenancy`, `identity`, `authorization`, `audit`, `notifications`, `platform`.
- Spring Modulith's `ApplicationModules.verify()` runs as a test and enforces the module boundaries.
- Modules talk to each other through public application services and, later, domain events (outbox, Phase 10). They never use each other's repositories.
- AI runs as a separate Python FastAPI service, because its runtime and dependencies differ fundamentally.

## Consequences
- One deployable, one database and simple transactions. Local development and CI stay fast.
- A module can be extracted later if scale or ownership justifies it, because its boundaries are already enforced.
- Discipline is still needed: boundary violations fail the build.
