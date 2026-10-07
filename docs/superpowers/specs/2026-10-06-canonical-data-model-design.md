# Design Spec — NexusOps Canonical Data Model (Phase 4)

- **Status:** Accepted (product decisions taken on the owner's standing instruction to proceed without re-asking)
- **Date:** 2026-10-06
- **Source:** blueprint §5.1 (canonical business identity), §15 (core data model), §38 Phase 4, plus the platform foundation
  spec `2026-10-04-platform-foundation-design.md`, whose conventions (isolation, audit, errors, paging) all still apply.

## 1. Intent

### Outcome
One shared, tenant-isolated record of the business's people, organizations, products, and the work and files attached to
them, which CRM, Inventory, HelpDesk and HRMS (Phases 5–8) all build on instead of keeping their own copies.

### Success criteria (blueprint Phase 4 exit)
1. People, organizations, customers, employees, suppliers, products, documents, activities and tasks exist end to end
   (database, API, UI), with tenant isolation proven at both layers like every earlier table.
2. **No duplicate canonical identities without an explicit reason.** Creating, or renaming into, a probable duplicate is
   refused with the candidates, unless the caller gives a reason. The reason is stored and audited.
3. One party record carries every business role it plays: a company that is both customer and supplier is one record.
4. Every write is audited, permission-gated, and visible in the UI.

### Non-goals (deferred, with the phase that owns them)
- Merging duplicates, CSV import/export, custom fields, tags, postal addresses: CRM (Phase 5).
- Postgres full-text search: Phase 5 (search). Contains-match search is used now.
- S3/MinIO object storage, malware scanning and document versions: Phase 13/14 (see ADR-0009).
- Domain events on Kafka: Phase 10. Phase 4 publishes no domain events, except an in-process mail request on task
  assignment.
- HR-confidential documents, an employee subject type, departments and job roles: HRMS (Phase 8).
- Editing or deleting activities: activities are an immutable timeline in this phase.

## 2. Decisions

| # | Topic | Decision |
|---|---|---|
| D1 | Modules | Three new Modulith modules. `directory` holds parties and their roles. `catalog` holds products. `collaboration` holds activities, tasks and documents. `identity` gains a public `Members` API. Dependencies: `directory` and `catalog` → `collaboration` (the subject SPI only); `collaboration` → `identity`, `tenancy`, `audit`, `notifications`. There are no cycles. |
| D2 | Party model | A single `parties` table with `kind` PERSON or ORGANIZATION (the party pattern). Persons and organizations share ids, search and roles. A person may belong to one organization (`organization_id`, same tenant via a composite FK). |
| D3 | Party roles | `party_roles(party_id, role, status, since, employee_number)`, where role is CUSTOMER, SUPPLIER or EMPLOYEE and status is ACTIVE or INACTIVE. There is one row per party and role, set idempotently by `PUT`. Only a PERSON can be an EMPLOYEE. Roles are never deleted, only made INACTIVE, so history stays visible. |
| D4 | Duplicates | A person's candidates have the same email, or the same normalized full name in the same organization (or both with none). An organization's candidates have the same domain, or the same normalized name, ignoring case, punctuation and legal suffixes (Inc, Ltd, LLC, GmbH …). Archived candidates count, so the user can restore instead. A match returns `409` with `duplicates[]`, unless the request carries `duplicateReason` (1–500 chars). The reason is stored on the party and audited with the candidate ids. Checks are serialized per tenant (`TenantLocks` scope `party-identity`). |
| D5 | Lifecycle | Parties and products are archived and restored, never deleted. They're referenced by other modules and polymorphic subjects, so a reference can't dangle. Archived records stay readable, are hidden from lists by default (`?archived=true` lists them), and can't take new roles, activities, tasks or documents (`409 "This record is archived."`). |
| D6 | Products | SKU is unique per tenant ignoring case, never reused, and has no override. Kind is GOODS or SERVICE. There's a unit and an optional list price plus ISO-4217 currency, which defaults to the workspace currency. |
| D7 | Subjects | Activities, tasks and documents attach to a subject `(subject_type, subject_id)`. A `SubjectResolver` SPI in `collaboration` is implemented by each owning module: `PARTY` by `directory`, `PRODUCT` by `catalog`, and later `LEAD`, `TICKET` and others. The resolver names the subject's read permission. Collaboration requires that permission on top of its own. |
| D8 | Activities | Types are NOTE, CALL, EMAIL and MEETING. Each has a summary (1–200) and a body (≤10 000), and `occurredAt` defaults to now and can't be more than 5 min in the future. They're immutable: the app role has no UPDATE or DELETE on the table. |
| D9 | Tasks | Each task has a title, description, status (OPEN, IN_PROGRESS, DONE, CANCELLED), priority (LOW, NORMAL, HIGH, URGENT), due date, assignee (an active member) and an optional subject. Managers edit any task. **The assignee may change the status of their own task with read permission only.** Assigning someone other than yourself emails them after commit. |
| D10 | Documents | (V13) Stored behind a `DocumentStorage` port, with a PostgreSQL adapter for now (ADR-0009). Max 10 MB per file. Per-plan storage quota `maxStorageMb`: FREE 100, STARTER 1000, BUSINESS 10000, ENTERPRISE unlimited. Downloads are always `attachment` with `nosniff` and `no-store`. The SHA-256 of the content is recorded. |
| D11 | Permissions | 11 new codes with no module, since canonical data is shared by all modules. Existing system roles get them by migration, and new workspaces get them automatically. See §4. |
| D12 | Edits | Edits are full-replacement `PUT`s that carry the `version` last read. A stale version gets `409 "This record was changed by someone else. Reload and try again."` |
| D13 | Search | `q` is a case-insensitive contains match, with LIKE wildcards escaped: parties on name, email and domain; products on SKU and name; tasks on title. |
| D14 | UI | Directory (list and detail), Products (list and detail) and Tasks pages. The detail pages share Activity, Tasks and Documents panels. The Overview shows "My open tasks". The permission matrix groups foundation permissions by area. |

## 3. Data model (Flyway V9–V13)

All tables are tenant-owned, with `tenant_id` and `ENABLE`/`FORCE ROW LEVEL SECURITY`. They use the standard
`tenant_isolation` policy in both `USING` and `WITH CHECK`, created in the same migration as the table (Plan 2 delta 1).

| Migration | Contents |
|---|---|
| V9 directory | `parties(id, tenant_id, kind, name, name_key, first_name, last_name, job_title, organization_id, email, phone, domain, website, duplicate_reason, archived_at, created_at, updated_at, version, UNIQUE(tenant_id,id), FK (tenant_id, organization_id) → parties(tenant_id,id))`, plus kind-specific CHECKs. `party_roles(id, tenant_id, party_id, role, status, since, employee_number, …, UNIQUE(tenant_id,party_id,role), UNIQUE(tenant_id,employee_number))`. Directory permissions, granted to existing system roles. |
| V10 catalog | `products(id, tenant_id, sku, name, description, kind, unit, list_price numeric(19,4), currency text CHECK (currency ~ '^[A-Z]{3}$'), archived_at, …, UNIQUE(tenant_id, lower(sku)))`. Catalog permissions. |
| V11 activities | `activities(id, tenant_id, subject_type, subject_id, type, summary, body, occurred_at, author_id, created_at)`: app role SELECT/INSERT only. Activity permissions. |
| V12 tasks | `tasks(id, tenant_id, title, description, status, priority, due_on, assignee_id, subject_type, subject_id, created_by, completed_at, …)`. Task permissions. |
| V13 documents | `documents(id, tenant_id, subject_type, subject_id, file_name, content_type, size_bytes, sha256, uploaded_by, created_at)` and `document_contents(document_id PK → documents ON DELETE CASCADE, tenant_id, content bytea)`. Plan limit `maxStorageMb`. Document permissions. |

Polymorphic subjects have no FK. Their integrity is enforced at write time through `SubjectResolver`, and holds
afterwards because subjects are never deleted (D5).

**Granting new permissions to existing workspaces.** FORCE RLS applies to the migration owner too. Each migration
therefore loops over `tenants`, sets `app.tenant_id` transaction-locally, and inserts `role_permissions` for that
tenant's system roles. The policies are honoured rather than bypassed.

## 4. Permission catalog additions (module NULL)

| Code | Description |
|---|---|
| `directory.party.read` | View people and organizations |
| `directory.party.manage` | Create, edit and archive people and organizations; mark customers and suppliers |
| `directory.employee.read` | View employee records |
| `directory.employee.manage` | Manage employee records |
| `catalog.product.read` | View products |
| `catalog.product.manage` | Create, edit and archive products |
| `collaboration.activity.create` | Log notes, calls, emails and meetings |
| `collaboration.task.read` | View tasks |
| `collaboration.task.manage` | Create, assign and edit any task |
| `collaboration.document.read` | View and download documents |
| `collaboration.document.manage` | Upload and delete documents |

The seeded `crm.customer.*` codes remain for CRM-specific customer data in Phase 5. Being a customer at all is a
directory fact.

## 5. API (`/api/v1`)

| Route | Authorization |
|---|---|
| `GET parties?q=&kind=&role=&organizationId=&archived=&page=&size=` | `directory.party.read`; `role=EMPLOYEE` also needs `directory.employee.read` (403) |
| `GET parties/{id}` | `directory.party.read` (employee role rows omitted without `directory.employee.read`) |
| `POST persons`, `PUT persons/{id}`, `POST organizations`, `PUT organizations/{id}` | `directory.party.manage` |
| `POST parties/{id}/archive`, `POST parties/{id}/restore` | `directory.party.manage` |
| `PUT parties/{id}/roles/{role}` `{status, since?, employeeNumber?}` | either manage code; the service requires `directory.employee.manage` for EMPLOYEE and `directory.party.manage` otherwise |
| `GET products?q=&kind=&archived=&page=&size=`, `GET products/{id}` | `catalog.product.read` |
| `POST products`, `PUT products/{id}`, `POST products/{id}/archive\|restore` | `catalog.product.manage` |
| `GET activities?subjectType=&subjectId=&page=&size=` | authenticated + subject read |
| `POST activities` | `collaboration.activity.create` + subject read |
| `GET tasks?assignee=me\|unassigned\|{id}&status=A,B&subjectType=&subjectId=&q=&page=&size=`, `GET tasks/{id}` | `collaboration.task.read` |
| `POST tasks`, `PUT tasks/{id}` | `collaboration.task.manage` (+ subject read when attaching a subject) |
| `POST tasks/{id}/status` `{status}` | read or manage; the service allows manage, or read when the caller is the assignee |
| `GET tasks/assignees?q=` | `collaboration.task.manage` |
| `GET documents?subjectType=&subjectId=` | `collaboration.document.read` + subject read |
| `POST documents` (multipart `file`, `subjectType`, `subjectId`) | `collaboration.document.manage` + subject read |
| `GET documents/{id}/content` | `collaboration.document.read` + subject read |
| `DELETE documents/{id}` | `collaboration.document.manage` + subject read |

**Subject checks happen in this order:**
1. an unknown `subjectType` → 400 field error;
2. a missing read permission for that type → 403;
3. a subject that isn't found (including another tenant's) → 404;
4. a write against an archived subject → 409.

**Duplicate conflicts** extend the problem body with
`duplicates: [{id, kind, name, email, domain, archived}]`.

## 6. Security and isolation

- All new tables are in `RlsCoverageIT.EXPECTED_TENANT_TABLES`. Raw-JDBC tests prove that tenant A's context sees none of
  tenant B's rows.
- `CrossTenantApiIT` covers every new id-bearing route: tenant A's owner gets 404 on tenant B's ids, and B's data is
  unchanged.
- Cross-tenant references also fail:
  - an organization, assignee or subject id from another tenant gives 400 (organization, assignee) or 404 (subject);
  - the composite FK `(tenant_id, organization_id)` blocks it at the database too.
- Uploads:
  - the file name is sanitised (no path, no control characters, ≤255);
  - the content type is validated, else stored as `application/octet-stream`;
  - nginx `client_max_body_size 11m` on the upload route (`POST /api/v1/documents`) only; every other API route keeps
    nginx's 1 MB default.
- Audit actions:
  - parties: `PersonCreated`, `OrganizationCreated`, `PartyUpdated`, `PartyArchived`, `PartyRestored`, `PartyRoleChanged`;
  - products: `ProductCreated`, `ProductUpdated`, `ProductArchived`, `ProductRestored`;
  - collaboration: `ActivityLogged`, `TaskCreated`, `TaskUpdated`, `TaskStatusChanged`, `DocumentUploaded`, `DocumentDeleted`.
  - Activity bodies and document contents are never audited.

## 7. Testing

- **Unit:** name, domain and phone normalization, and organization match keys.
- **Integration (Testcontainers):**
  - every route's happy path and its 400/403/404/409 branches;
  - duplicate detection and override;
  - archive rules;
  - the employee permission split;
  - task assignee status rule and assignment email;
  - document quota, size limit, download headers and SHA-256.
- **Isolation:** RLS coverage, raw-JDBC isolation of the new tables, and the cross-tenant API suite.
- **Architecture:** `ApplicationModules.verify()` and endpoint-authorization coverage, both unchanged.
- **Frontend:** Vitest page tests against `fakeServer`.
- **E2E:** a Playwright journey:
  1. create an organization and a person;
  2. have a duplicate refused, then accepted with a reason;
  3. mark a customer;
  4. log a note;
  5. assign and complete a task;
  6. upload and download a document;
  7. create a product and have its duplicate SKU refused.

## 8. Implementation deltas

- Migrations are split one per concern: V9 directory, V10 catalog, V11 activities, V12 tasks, V13 documents.
- `grant_to_system_roles(text[])` (V9) is the one way migrations grant new permission codes to existing workspaces.
- The activity list route requires authentication only. Its authorization is the subject's read permission.
- Task titles (and phone numbers) collapse internal whitespace, so assignment-email subjects stay single-line.
- V14 closes V0_2's no-op default-privilege revoke; a test asserts the runtime role can execute no owner function.
- File names drop every Unicode control and format character (`\p{Cc}`, `\p{Cf}`, line/paragraph separators), so bidi
  overrides like U+202E can't disguise an extension.
- Person duplicate keys fold like organization keys: accents, case, punctuation, hyphens, apostrophes and spacing are
  ignored ("Seán O’Brien" matches "Sean O'Brien"). Rows stored before this change keep their old key until next edited;
  Phase 4 is unreleased, so there is no re-key migration.
- The duplicate notice is cleared, with its reason, as soon as an identity field changes (person: first name, last
  name, email, organization; organization: name, domain), so a reason can never vouch for a duplicate the user didn't
  see. The notice is a `role="alert"` region.
- The task assignee and person organization pickers have a search box (`q`) over the server's first 20 matches; the
  current and the chosen option always stay listed.
- nginx allows 11 MB bodies only on `location = /api/v1/documents` (the upload); the rest of `/api/` keeps the 1 MB
  default, so unauthenticated routes can't be sent large bodies.
