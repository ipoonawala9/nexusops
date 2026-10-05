# ADR-0002: Tenant isolation — shared schema, application guards and PostgreSQL RLS

- Status: Accepted
- Date: 2026-10-04

## Context
Blueprint §1.1 requires tenant isolation as a first-class concern. RQ1 asks how a shared architecture can provide strong logical isolation. Options:
1. shared schema + `tenant_id`;
2. schema per tenant;
3. database per tenant.

## Decision
Use **a shared database and schema with `tenant_id`**, enforced by two independent layers:
1. **Application:** a Hibernate `@TenantId` discriminator on every tenant-owned entity. The tenant resolver reads `TenantContext`, which is populated only from the verified JWT.
2. **Database:** `ENABLE` and `FORCE ROW LEVEL SECURITY` on every tenant-owned table. The policy compares `tenant_id` with the `app.tenant_id` setting. At **connection checkout**, `TenantAwareDataSource` runs `set_config('app.tenant_id', <tenant or ''>, false)`. It does this on every checkout, so a pooled connection never carries a previous tenant. Because of that, `TenantContext` must be bound **before** a transaction starts, and `TenantContext.open` refuses to bind or switch tenant inside an active transaction. The runtime role `nexusops_app` has `NOBYPASSRLS`, owns nothing and has no DDL rights. Migrations run as `nexusops_owner`.

Further rules:
- If no tenant is set, queries return no rows (fail closed).
- Cross-tenant reads by the platform go through a separate `app.platform_access` flag, which only the `platform` module sets.
- Composite foreign keys `(tenant_id, id)` prevent references across tenants.
- Join tables without `tenant_id` (`role_permissions`, `user_roles`) use RLS policies that require the referenced rows to be visible under the current tenant.
- Two policies intentionally differ from the literal predicate: the join tables above check visibility of referenced rows, and `audit_events` allows INSERT with a NULL `tenant_id` for pre-tenant events (SELECT stays tenant-scoped).

## Consequences
- A bug in one layer is caught by the other. This can be tested at both layers.
- Setting a session variable on every connection checkout adds a small overhead.
- Tenancy access is isolated behind `TenantContext` and `TenantOwnedEntity`. Moving to schema-per-tenant or database-per-tenant for the Enterprise tier (§1.1) would change only the connection routing.
