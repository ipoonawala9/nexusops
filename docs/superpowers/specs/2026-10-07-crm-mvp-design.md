# Design Spec — NexusOps CRM MVP (Phase 5)

- **Status:** Accepted (product decisions taken on the owner's standing instruction to proceed without re-asking)
- **Date:** 2026-10-07
- **Source:** blueprint §2.1 (deeper CRM problem: loss of customer context), §15 (CRM entities), §16 (Sales → Inventory
  flow), Phase 5 in the roadmap, plus the platform foundation and canonical data model specs, whose conventions
  (isolation, audit, errors, paging, full-replacement edits with `version`, archived-never-deleted subjects) all apply.

## 1. Intent

### Outcome
A small sales team can capture leads (by hand or from the spreadsheet they already keep), work them, convert the good
ones into canonical customers without creating duplicates, track opportunities through a pipeline they configure, and
see one Customer 360 page that joins identity, deals and the full activity history. A workspace dashboard shows where
the pipeline stands.

### Success criteria (blueprint Phase 5 exit)
1. Leads, opportunities, a configurable pipeline, customers, contacts, activities, notes, tasks, search and a dashboard
   work end to end (database, API, UI), with tenant isolation proven at both layers like every earlier table.
2. **Lead conversion creates no duplicate identity without a reason.** Conversion reuses the directory's duplicate
   detection; the user links the existing party or records a reason.
3. **Customer context is not lost:** the Customer 360 page shows the party's contacts, converted leads, opportunities
   (open, won, lost, with totals) and one activity timeline that includes the activities of its leads and opportunities.
4. Everything CRM switches off with the CRM module: every new permission belongs to module `CRM`.
5. Every write is audited and permission-gated.

### Non-goals (deferred, with the phase that owns them)
- AI lead and customer summaries: Phase 12 (AI platform). They need permission-aware retrieval that does not exist yet.
- Merging duplicate parties, custom fields, tags, postal addresses, CSV export: later data-quality work (not scheduled
  in a phase yet). Merging needs every module to re-point references and is too large for an MVP.
- Several pipelines per workspace, products/line items on opportunities, quotes, orders: Phase 6 (Inventory) adds
  orders; line items come with it.
- Postgres full-text search: contains-match stays (D12). Names and emails are poorly served by stemming, and the data
  volumes of a 10–50 person business don't need an index yet.
- Lead assignment emails, web-to-lead forms, email sync: later.

## 2. Approach

Three ways to model a lead were considered:

| Option | Verdict |
|---|---|
| **A. Lead holds the raw contact details; conversion turns it into canonical parties** | **Chosen.** Unqualified prospects stay out of the directory; the duplicate check runs once, at conversion, where a human can decide. Matches the blueprint flow "lead created → lead converted → customer linked". |
| B. Lead references a party from creation | Every cold prospect becomes a canonical identity; imports of a few hundred rows would flood the directory and the duplicate prompts. |
| C. "Lead" as a party role | Mixes sales state (status, owner, value) into identity and makes the role table carry CRM columns. |

## 3. Decisions

| # | Topic | Decision |
|---|---|---|
| D1 | Module | New Modulith module `crm`. Depends on `directory` (public lookup and `ensureCustomer` API), `collaboration` (SPIs only), `identity` (`Members`), `tenancy` (settings, `WorkspaceRegistered`), `audit`, `shared`. Nothing depends on `crm`. |
| D2 | Leads | Contact fields (first/last name, company name, job title, email, phone), `source` (WEBSITE, REFERRAL, WALK_IN, PHONE, EMAIL, SOCIAL, EVENT, OTHER), `status`, optional owner (an active member; defaults to the creator), optional estimated value + currency, description (≤5 000). At least a last name or a company name is required. |
| D3 | Lead status | NEW, CONTACTED, QUALIFIED are open and freely interchangeable. Any open status → DISQUALIFIED needs a reason (1–500). DISQUALIFIED → NEW reopens and clears the reason. CONVERTED is reached only by conversion and is final: the lead becomes read-only (`409 "This lead was converted and can no longer be changed."`) and, as a collaboration subject, reports `archived=true` so its timeline is frozen. |
| D4 | Conversion | `POST leads/{id}/convert` in one transaction. Person: link an existing PERSON or create one from the lead (fields editable). Organization: link an existing ORGANIZATION, create one, or none. At least one party results. A created person is placed in the resulting organization. Creation goes through `PartyService`, so a probable duplicate returns the directory's `409` with `duplicates[]` and nothing is written; the client may resend with `duplicateReason` or link the candidate instead. The account (the organization if any, else the person) is marked CUSTOMER (D7). Optionally an opportunity is created for the account (D6). The lead stores the resulting ids and `converted_at`. Allowed from any open status. |
| D5 | Pipeline | One pipeline per workspace, as ordered stages: `name` (1–60, unique ignoring case), `probability` (0–100), `kind` OPEN, WON or LOST. Exactly one WON and one LOST stage, always last; they can be renamed but not deleted or reordered. 1–12 OPEN stages. A stage with opportunities can't be deleted (`409 "Move this stage's opportunities first."`). Defaults: Prospecting 10, Qualification 25, Proposal 50, Negotiation 75, Won 100, Lost 0, seeded for existing workspaces by migration and for new ones on `WorkspaceRegistered` (D13). |
| D6 | Opportunities | `name` (1–200), `account` (a non-archived party, required), optional `contact` (a non-archived PERSON), `stage`, optional amount (≥0, numeric(19,4)) + currency (ISO-4217, defaults to the workspace currency, null when there's no amount), expected close date, owner (active member, defaults to creator), description (≤5 000), optional source lead. **Status is the stage's kind.** Moving into the LOST stage needs `lostReason` (1–500); moving into WON or LOST sets `closed_at`; moving back to an OPEN stage clears `closed_at` and `lost_reason`. Stage moves use `POST opportunities/{id}/stage {stageId, lostReason?, version}`; other edits are a full `PUT`. Opportunities are not deleted. |
| D7 | Customers | A customer is a party with an active CUSTOMER role (a directory fact). CRM marks it in two places, as a business rule rather than a user action: conversion, and an opportunity moving to WON. Directory exposes `PartyRoles.ensureCustomer(partyId)`, which needs no user permission, is idempotent, and audits `PartyRoleChanged` when it changes something. |
| D8 | Customer 360 | Route `/app/crm/customers/{partyId}`. Backend `GET crm/customers/{partyId}` returns the party summary plus opportunity counts and per-currency totals (open, weighted open, won, lost count). The page also uses existing routes: contacts (`parties?organizationId=`), leads converted into it, opportunities, the related activity timeline (D10), tasks and documents. |
| D9 | Subjects | `LEAD` (read permission `crm.lead.read`) and `OPPORTUNITY` (`crm.opportunity.read`) implement `SubjectResolver`, so activities, notes, tasks and documents attach to them with no new collaboration tables. |
| D10 | Related timeline | New collaboration SPI `SubjectRelations`: given a subject, returns related subjects. CRM implements it: PARTY → opportunities where it's the account or contact, and leads converted into it; OPPORTUNITY → its source lead. `GET activities?subjectType=&subjectId=&includeRelated=true` merges those subjects' activities, newest first, each item carrying its subject. Related subjects whose read permission the caller lacks are left out silently. |
| D11 | Lead import | `POST leads/import` (multipart `file`): UTF-8 CSV ≤256 KB, ≤500 data rows, header row required. Columns (case-insensitive, any order, unknown columns rejected with a 400 naming them): `first_name, last_name, company, job_title, email, phone, source, estimated_value, currency, description`. **All or nothing:** any invalid row gives `422` with `rows: [{row, field, message}]` (first 50) and nothing is stored; otherwise every row becomes a NEW lead owned by the importer, and one `LeadsImported` audit event records the count. A UTF-8 BOM is accepted; quoted fields per RFC 4180. |
| D12 | Search | `SubjectResolver` gains `search(q, limit)` (default: none). Party, product, lead and opportunity resolvers implement it with the existing contains-match (LIKE wildcards escaped). `GET search?q=` (2–100 chars) returns up to 5 hits per type the caller can read, as `{type, id, label, detail, archived}`. The UI puts a search box in the app header (shortcut `/`). |
| D13 | Workspace event | `TenantDirectory.register` publishes `WorkspaceRegistered(tenantId)` inside the signup transaction, with the tenant bound. CRM listens synchronously and seeds the default stages. Future modules use the same hook. |
| D14 | Dashboard | `GET crm/dashboard?owner=me\|all`. Leads section (needs `crm.lead.read`): open counts by status, new in the last 30 days, conversion rate over leads closed in the last 90 days (converted ÷ (converted + disqualified), null when none). Pipeline section (needs `crm.opportunity.read`): per OPEN stage count and per-currency amount and weighted amount; won this month (count, totals) and lost this month (count), months in the workspace time zone; up to 10 open opportunities with expected close in the next 30 days. A section the caller can't read is `null`; the route needs either permission. |
| D15 | Permissions | Module `CRM`: `crm.lead.read`, `crm.lead.manage`, `crm.opportunity.read`, `crm.opportunity.manage`, `crm.pipeline.manage`; `crm.customer.read` is kept. The unused seeds `crm.customer.create/update/delete` are removed (creating customers is a directory job). System roles get the new codes by migration; new workspaces get them automatically. |
| D16 | UI | CRM nav entry (replacing the "coming in Phase 5" page) with sub-navigation: Dashboard, Leads, Pipeline, Customers. Lead list (filters, New lead, Import CSV) and detail (status actions, Convert dialog, activity/tasks/documents panels). Pipeline board: a column per OPEN stage with count and totals, plus Won and Lost columns limited to the last 30 days; each card has a "Move to" menu (no drag and drop: accessible and testable). Opportunity detail. Customers list and Customer 360. Settings → Pipeline for stages (add, rename, probability, move up/down, delete). Header search box. |

## 4. Data model (Flyway V15–V17)

All tables are tenant-owned with `ENABLE`/`FORCE ROW LEVEL SECURITY` and the standard `tenant_isolation` policy in
`USING` and `WITH CHECK`, created in the same migration. `UNIQUE (tenant_id, id)` on each table backs composite FKs, so a
reference can never cross tenants even at the database. Member references (`owner_id`, `created_by`) follow the
`tasks.assignee_id` pattern.

| Migration | Contents |
|---|---|
| V15 crm pipeline | Permission changes (D15) via `grant_to_system_roles`; removal of the three unused codes (their `role_permissions` rows deleted per tenant, the same way the grant helper iterates). `pipeline_stages(id, tenant_id, name, name_key, position, probability, kind, created_at, updated_at, version)`, `UNIQUE (tenant_id, name_key)`, partial unique indexes for one WON and one LOST per tenant. Default stages for every existing tenant. |
| V16 leads | `leads(id, tenant_id, first_name, last_name, company_name, job_title, email, phone, source, status, owner_id, estimated_value numeric(19,4), currency, description, disqualify_reason, converted_at, converted_person_id, converted_organization_id, converted_opportunity_id, created_by, created_at, updated_at, version)`. CHECKs: name-or-company, status/reason consistency, currency iff value. Composite FKs to `parties`. Indexes `(tenant_id, status)`, `(tenant_id, owner_id)`. |
| V17 opportunities | `opportunities(id, tenant_id, name, account_id, contact_id, stage_id, amount numeric(19,4), currency, expected_close_on, owner_id, lead_id, description, lost_reason, closed_at, created_by, created_at, updated_at, version)`. Composite FKs to `parties`, `pipeline_stages` (RESTRICT) and `leads`; then the deferred FK `leads.converted_opportunity_id → opportunities`. Indexes `(tenant_id, stage_id)`, `(tenant_id, account_id)`, `(tenant_id, contact_id)`, `(tenant_id, expected_close_on)`. |

## 5. API (`/api/v1`)

| Route | Authorization |
|---|---|
| `GET leads?q=&status=A,B&owner=me\|unassigned\|{id}&source=&page=&size=` (default status: open ones), `GET leads/{id}` | `crm.lead.read` |
| `POST leads`, `PUT leads/{id}`, `POST leads/{id}/status {status, reason?, version}` | `crm.lead.manage` |
| `POST leads/{id}/convert` | `crm.lead.manage`; plus `directory.party.manage` when it creates a party, `directory.party.read` when it links one, `crm.opportunity.manage` when it creates an opportunity (403 otherwise) |
| `POST leads/import` | `crm.lead.manage` |
| `GET opportunities?q=&status=&stageId=&accountId=&contactId=&owner=&page=&size=`, `GET opportunities/{id}` | `crm.opportunity.read` |
| `POST opportunities`, `PUT opportunities/{id}`, `POST opportunities/{id}/stage` | `crm.opportunity.manage` (+ `directory.party.read` to reference parties) |
| `GET crm/pipeline/stages`, `GET crm/pipeline/board?owner=` | `crm.opportunity.read` |
| `POST crm/pipeline/stages`, `PUT crm/pipeline/stages/{id}`, `DELETE crm/pipeline/stages/{id}`, `PUT crm/pipeline/stages/order {stageIds}` | `crm.pipeline.manage` |
| `GET crm/customers?q=&page=&size=`, `GET crm/customers/{partyId}` | `crm.customer.read` + `directory.party.read` |
| `GET crm/dashboard?owner=` | `crm.lead.read` or `crm.opportunity.read` |
| `GET search?q=` | authenticated; results filtered per type |
| `GET activities?…&includeRelated=true` | as today, related subjects filtered per type |

Errors follow the established order (400 → 403 → 404 → 409). Unknown or other-tenant stage, party, lead or member ids
give a 400 field error (`stageId`, `accountId`, `contactId`, `ownerId`) or 404 on the path id.

## 6. Security and isolation

- `pipeline_stages`, `leads`, `opportunities` join `RlsCoverageIT.EXPECTED_TENANT_TABLES`; a raw-JDBC test proves tenant
  A sees none of B's rows, an unbound connection sees none, and cross-tenant inserts are rejected.
- `CrossTenantApiIT` covers every new id-bearing route (404, B unchanged) and cross-tenant references in bodies (400).
- Disabling the CRM module removes every CRM permission, so every CRM route returns 403 (test).
- Conversion cannot be used to escalate: creating parties still needs `directory.party.manage`.
- The CSV parser bounds size, rows and field length before storing anything; cells are stored as text, never evaluated.
- Audit actions: `LeadCreated`, `LeadUpdated`, `LeadStatusChanged`, `LeadConverted`, `LeadsImported`,
  `OpportunityCreated`, `OpportunityUpdated`, `OpportunityStageChanged`, `PipelineStageCreated`,
  `PipelineStageUpdated`, `PipelineStageDeleted`, `PipelineStagesReordered`; directory's `PersonCreated`,
  `OrganizationCreated`, `PartyRoleChanged` when conversion or a win writes them.

## 7. Testing

- **Unit:** lead status transitions; CSV parsing (BOM, quotes, embedded newlines, unknown columns, limits); stage order
  validation; dashboard month boundaries in a non-UTC time zone.
- **Integration (Testcontainers):** every route's happy path and its 400/403/404/409 branches; conversion (link, create,
  duplicate 409 then reason, no-organization, with and without opportunity, rollback on failure, permission
  combinations); win marks customer; stage rules (WON/LOST fixed, delete with opportunities, reorder set mismatch);
  default stages for a new signup; import all-or-nothing; related timeline filtering; search per-type filtering;
  module-disabled 403s.
- **Isolation:** RLS coverage, raw-JDBC isolation of the new tables, cross-tenant API suite.
- **Architecture:** `ApplicationModules.verify()` and endpoint-authorization coverage, unchanged.
- **Frontend:** Vitest page tests against `fakeServer`.
- **E2E:** Playwright journey: enable CRM → create a lead → qualify → convert with a duplicate organization refused,
  then linked to the existing one, creating an opportunity → move it through the board to Won → the account appears in
  Customers with the won value and its Customer 360 timeline shows the lead's note → the header search finds the lead.

## 8. Implementation deltas (from planning)

- `GET /api/v1/crm/owners?q=` (needs `crm.lead.manage` or `crm.opportunity.manage`) lists the members who can own
  leads and opportunities; `/tasks/assignees` needs a task permission.
- `GET /leads` takes `partyId` (leads converted into that person or organization), for Customer 360.
- Leads also store `disqualified_at`, so the dashboard's 90-day conversion rate uses real close times.
- A lead needs a first name, a last name or a company (directory persons need only a first name).
- `ownerId` null means the creator on create and unassigned on update.
- `ensureCustomer` leaves archived parties alone, so a win never fails because the account was archived.
- Conversion reports directory field errors as `person.*` / `organization.*` / `opportunity.*`, and a duplicate
  conflict adds `party: "person" | "organization"`.
