# ADR-0004: Permission-based authorization

- Status: Accepted
- Date: 2026-10-04

## Context
§14 says that permissions, not role names, determine authorization. Tenants need custom roles. Disabling a module must disable its capabilities.

## Decision
- **Permissions:** a global catalog of granular codes (`crm.customer.read`, …), each optionally tied to a module.
- **Roles:** tenant-scoped bundles of permissions. The system roles `TENANT_OWNER` and `TENANT_ADMIN` are seeded for each tenant.
- **Effective permissions:** the union of the user's role permissions, minus permissions of disabled modules. Resolved per request and cached in Redis under `tenant:{tid}:user:{uid}:perms`, which is evicted on change. They are **not** embedded in the JWT, so changes take effect immediately.
- **Enforcement:** `@PreAuthorize("hasAuthority(...)")` on every non-public handler. A coverage test fails the build if any handler lacks an authorization rule and isn't allowlisted as public.
- **Escalation guard:**
  - a user can't grant permissions they don't hold;
  - only owners can manage the owner role;
  - the last owner can't be removed.

## Consequences
- Authorization is fine-grained and auditable, and roles stay flexible.
- Resolving permissions per request costs a cache lookup; we accept that.
