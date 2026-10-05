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
  - a user can't change or delete a role stronger than themselves: for `PATCH /roles/{id}`,
    `PUT /roles/{id}/permissions` and `DELETE /roles/{id}`, the target role's *current* permissions must be a subset
    of the actor's grantable permissions, otherwise 403 "You can't change a role with permissions you don't have."
    (PUT also still requires the *new* set to be grantable);
  - a user can't disable, re-enable or change the roles of a user stronger than themselves: for `PATCH /users/{id}`
    with a status change and `PUT /users/{id}/roles`, the target user's current permissions must be a subset of the
    actor's grantable permissions, otherwise 403 "You can't manage a user with permissions you don't have.";
  - only owners can manage the owner role;
  - the last owner can't be removed.

## Consequences
- Authorization is fine-grained and auditable, and roles stay flexible.
- Resolving permissions per request costs a cache lookup; we accept that.
- **Grantable vs effective (Plan 3):** effective permissions (request authorization) are gated by enabled modules;
  *grantable* permissions (what an actor may grant via roles/invitations/assignment) are the union of the actor's
  role permissions regardless of modules, so owners can prepare roles before enabling a module.
- The owner role is managed only by owners (inviting to, assigning, removing, and re-enabling a disabled owner); the
  last active owner can't be disabled or demoted; owner-set changes serialize per tenant via `pg_advisory_xact_lock`.
- Permission-affecting changes (role permissions, role deletion, module toggles, user status/roles) evict the affected
  principal-cache entries after commit, with generation-guarded cache writes so no stale state can be re-cached.
  Besides the per-user generations there is a tenant-level generation `tenant:{t}:principal-gen`, which
  `evictTenant` bumps first, so a tenant-wide eviction also invalidates in-flight cache writes for every user.
- **Role hierarchy (Plan 3 final fix, product decision):** enforced once in `AuthorizationService.mutable(UUID)`, in
  the order 404 (not in this tenant) → 409 (system role) → 403 (target not within the actor's grantable set), and
  before the assignment count on delete, so a lower actor learns nothing about roles it can't manage. It checks the
  managed entity's permissions inside the transaction; `Role @Version` turns a concurrent change into a 409. Owners
  hold the whole catalog, so they are never blocked. The user-level rule (product decision, 2026-10-06) is
  `AuthorizationService.requireOutranks(targetRoleIds)`, called by `UserAdminService` for disable, re-enable and role
  assignment, after the owners-only check (so owner targets still get the owner-only 403) and before the owner and
  seat locks. Peers with the same permissions can manage each other. Not covered: renaming a more-privileged user
  (`identity.user.update`) and revoking a more-privileged pending invitation.
