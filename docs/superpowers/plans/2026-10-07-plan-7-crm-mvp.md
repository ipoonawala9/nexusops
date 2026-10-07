# Phase 5 — CRM MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Leads (manual and CSV import), lead conversion into canonical parties, a configurable pipeline with opportunities, customers with a Customer 360 page, a CRM dashboard and workspace-wide search, all inside the CRM module and isolated per tenant like every earlier table.

**Architecture:** A new Spring Modulith module `crm` (`com.nexusops.crm`) owns three tenant tables (`pipeline_stages`, `leads`, `opportunities`, Flyway V15–V17). Leads and opportunities plug into the existing collaboration `SubjectResolver` SPI, so activities, tasks and documents attach to them unchanged; two new collaboration SPIs (`SubjectRelations`, `SubjectResolver.search`) give the Customer 360 timeline and global search. The directory gains two public calls (`briefs`, `ensureCustomer`); tenancy publishes `WorkspaceRegistered`. The React app gets a CRM area with its own sub-navigation, a Pipeline settings tab and a header search box.

**Tech Stack:** Java 25, Spring Boot 4.1 (Web MVC, Security, Data JPA, Modulith), Hibernate `@TenantId`, PostgreSQL 17 RLS, Flyway, JUnit 5 + Testcontainers + MockMvc; React 19 + TypeScript + Vite, TanStack Query, React Hook Form + Zod, Tailwind/shadcn, Vitest + Testing Library, Playwright.

**Spec:** `docs/superpowers/specs/2026-10-07-crm-mvp-design.md` (read it before your task; decisions D1–D16 are referenced below).

## Global Constraints

- Every new table: `tenant_id uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE`, `UNIQUE (tenant_id, id)`, `ENABLE` + `FORCE ROW LEVEL SECURITY`, policy `tenant_isolation` with `USING` and `WITH CHECK` `(tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)`, created in the same migration as the table.
- Cross-table references inside a tenant use composite FKs `(tenant_id, x_id) REFERENCES t (tenant_id, id)`. Member references (`owner_id`, `created_by`) are `uuid REFERENCES users (id) ON DELETE SET NULL` (the `tasks.assignee_id` pattern).
- Every CRM permission has `module_code = 'CRM'`: `crm.lead.read`, `crm.lead.manage`, `crm.opportunity.read`, `crm.opportunity.manage`, `crm.pipeline.manage`, `crm.customer.read`. `crm.customer.create/update/delete` are removed.
- Every handler has `@PreAuthorize` (EndpointAuthorizationCoverageTest fails otherwise). Path prefix `/api/v1`.
- Error order: unknown/invalid input 400 → missing permission 403 → id on the path not found (incl. other tenant) 404 → state conflict 409. Body references to unknown or other-tenant ids are 400 field errors.
- Messages, verbatim: `"Record not found."`, `"This record is archived."`, `"This record was changed by someone else. Reload and try again."`, `"You do not have permission to perform this action."`, `"Reload the record and try again."` (missing version), `"Choose an active member of this workspace."`.
- Edits are full-replacement `PUT`s carrying `version`; stale → 409 with the STALE message above.
- JDBC (non-JPA) queries always add an explicit `tenant_id = :tenant` predicate (layer 1) on top of RLS (layer 2).
- Money: `numeric(19, 4)`, ≥ 0, at most 4 decimals and 15 integer digits; currency ISO-4217 upper case, defaulting to the workspace currency when an amount is given; null currency when there is no amount. Totals are always per currency — never add amounts of different currencies.
- Audit actions (names verbatim): `PipelineStageCreated`, `PipelineStageUpdated`, `PipelineStageDeleted`, `PipelineStagesReordered`, `LeadCreated`, `LeadUpdated`, `LeadStatusChanged`, `LeadConverted`, `LeadsImported`, `OpportunityCreated`, `OpportunityUpdated`, `OpportunityStageChanged`.
- Commits: conventional messages, **no `Co-Authored-By` or any AI attribution trailer** (repository owner's rule).
- Backend test command: `cd backend && ./gradlew test` (Docker must be running for Testcontainers). Frontend: `cd frontend && npm run lint && npm run typecheck && npm test`.

## Review Focus

1. A lead with only a company (no person name) — its display name is the company everywhere: lead list, subject label, search hit, conversion defaults (Task 2 test `aCompanyOnlyLeadIsNamedAfterTheCompany`).
2. An opportunity whose account was archived after creation can still be edited and moved between stages, as long as the account is not changed (Task 3 test `anArchivedAccountDoesNotBlockEditingOrMoving`).
3. A CSV saved by Excel — UTF-8 BOM, `\r\n` line endings, quoted commas and a trailing empty line — imports cleanly (Task 5 test `importsAnExcelStyleFile`).
4. Moving a card to a stage that was deleted in the meantime gives a 400 field error on `stageId`, never a 500 (Task 3 test `movingToAnUnknownStageIsAFieldError`).
5. Opportunities in two currencies are reported as two totals on the board, dashboard and customer summary, never summed (Task 3 `boardTotalsArePerCurrency`, Task 6 `customerTotalsArePerCurrency`).

## File Structure

```
backend/src/main/resources/db/migration/
  V15__crm_pipeline.sql            permissions (D15) + pipeline_stages + default stages for existing workspaces
  V16__leads.sql                   leads
  V17__opportunities.sql           opportunities (+ leads.converted_opportunity_id FK)
backend/src/main/java/com/nexusops/
  tenancy/WorkspaceRegistered.java          new event (D13); TenantDirectory publishes it
  directory/PartyBrief.java                 new public lookup record
  directory/PartyService.java               + briefs(), ensureCustomer()
  directory/PartySubjects.java              + search()
  catalog/ProductSubjects.java              + search()
  shared/web/ApiProblem.java                + unprocessable(), prefixed()
  collaboration/SubjectResolver.java        + default search()
  collaboration/SearchHit.java, SearchService.java, web/SearchController.java
  collaboration/SubjectKey.java, SubjectRelations.java
  collaboration/ActivityService.java, ActivityView.java, web/ActivityController.java   includeRelated + subject
  crm/package-info.java, CrmPermissions.java, Money.java, MoneyTotal.java
  crm/StageKind.java, StageView.java, StageRef.java, StageCommand.java, PipelineService.java, PipelineSetup.java
  crm/LeadSource.java, LeadStatus.java, LeadCommand.java, LeadQuery.java, LeadView.java, LeadService.java, LeadSubjects.java
  crm/OpportunityStatus.java, OpportunityCommand.java, OpportunityQuery.java, OpportunityView.java,
      OpportunitySummary.java, BoardView.java, OpportunityService.java, OpportunitySubjects.java
  crm/ConversionCommand.java, LeadConversionService.java
  crm/LeadCsv.java, LeadImportService.java, ImportResult.java
  crm/CustomerRow.java, CustomerSummary.java, CustomerService.java
  crm/DashboardView.java, DashboardPeriods.java, DashboardService.java
  crm/CrmRelations.java
  crm/domain/PipelineStage.java, PipelineStageRepository.java, Lead.java, LeadDetails.java, LeadRepository.java,
             Opportunity.java, OpportunityDetails.java, OpportunityRepository.java
  crm/web/CrmDtos.java, StageController.java, LeadController.java, OwnerController.java, OpportunityController.java,
          CustomerController.java, DashboardController.java
backend/src/test/java/com/nexusops/
  support/TestCrm.java
  crm/PipelineStageApiIT.java, LeadApiIT.java, OpportunityApiIT.java, LeadConversionIT.java, LeadCsvTest.java,
      LeadImportIT.java, CustomerApiIT.java, DashboardIT.java, DashboardPeriodsTest.java, LeadStatusRulesTest.java
  collaboration/SearchApiIT.java, RelatedActivityIT.java
  CrmRlsIT.java; RlsCoverageIT, CrossTenantApiIT, OpenApiContractIT, authorization/AuthorizationServiceIT (modified)
docs/decisions/0010-crm-leads-pipeline-customers.md; docs/api/openapi.json (re-exported)
frontend/src/
  lib/api/types.ts (+ CRM types), features/auth/permissions.tsx (+ CRM codes), test/records.ts (+ CRM fixtures)
  features/records/SubjectLink.tsx (LEAD, OPPORTUNITY paths), ActivityPanel.tsx (includeRelated)
  features/crm/CrmLayout.tsx, labels.ts, schemas.ts, OwnerSelect.tsx, PartyPicker.tsx, money.tsx
  features/crm/CrmDashboardPage.tsx, LeadsPage.tsx, LeadFormDialog.tsx, LeadImportDialog.tsx, LeadDetailPage.tsx,
      LeadStatusActions.tsx, ConvertLeadDialog.tsx, PipelinePage.tsx, OpportunityFormDialog.tsx, MoveStageControl.tsx,
      OpportunityDetailPage.tsx, CustomersPage.tsx, Customer360Page.tsx (+ *.test.tsx for each page)
  features/settings/PipelineSettingsPage.tsx (+ test)
  features/shell/GlobalSearch.tsx (+ test), AppLayout.tsx, routes.tsx, nav.ts, ComingSoonPage.test.tsx
frontend/e2e/crm.spec.ts; README.md
```

## Pre-flight rulings (made while writing this plan)

- Owner on create: `ownerId` null means "the creator" for leads and opportunities; on update null means unassigned (JSON can't distinguish absent from null in these records). Recorded in the ADR.
- Lead owners and opportunity owners are picked from `GET /api/v1/crm/owners?q=` (needs `crm.lead.manage` or `crm.opportunity.manage`), because `/tasks/assignees` needs a task permission. (Spec §5 delta.)
- Leads list gains `partyId` (leads converted into that party) for Customer 360. (Spec §5 delta.)
- Leads get `disqualified_at` next to `converted_at` so the dashboard's 90-day conversion rate uses real close times. (Spec §4 delta.)
- A lead needs a first name, a last name or a company (directory persons need only a first name, so "last name or company" in D2 is widened to match).
- `ensureCustomer` skips archived parties silently (a won deal must not fail because the account was archived).
- Conversion field errors from the directory are re-reported with a `person.` / `organization.` prefix, and duplicate conflicts carry `party: "person" | "organization"`.

---
### Task 1: CRM permissions, the pipeline and default stages for every workspace

**Files:**
- Create: `backend/src/main/resources/db/migration/V15__crm_pipeline.sql`
- Create: `backend/src/main/java/com/nexusops/tenancy/WorkspaceRegistered.java`
- Modify: `backend/src/main/java/com/nexusops/tenancy/TenantDirectory.java` (`register` publishes the event)
- Create: `backend/src/main/java/com/nexusops/crm/package-info.java`, `CrmPermissions.java`, `StageKind.java`, `StageView.java`, `StageRef.java`, `StageCommand.java`, `PipelineService.java`, `PipelineSetup.java`
- Create: `backend/src/main/java/com/nexusops/crm/domain/PipelineStage.java`, `PipelineStageRepository.java`
- Create: `backend/src/main/java/com/nexusops/crm/web/CrmDtos.java`, `StageController.java`
- Create: `backend/src/test/java/com/nexusops/support/TestCrm.java`
- Test: `backend/src/test/java/com/nexusops/crm/PipelineStageApiIT.java`
- Modify: `backend/src/test/java/com/nexusops/authorization/AuthorizationServiceIT.java:51`

**Interfaces:**
- Produces: `com.nexusops.tenancy.WorkspaceRegistered(UUID tenantId)`; `CrmPermissions.{LEAD_READ, LEAD_MANAGE, OPPORTUNITY_READ, OPPORTUNITY_MANAGE, PIPELINE_MANAGE, CUSTOMER_READ}`; `enum StageKind {OPEN, WON, LOST}` (declaration order is the board order); `StageView(UUID id, String name, int probability, StageKind kind, int position, long version)`; `StageRef(UUID id, String name, StageKind kind, int probability)`; `PipelineService` public `stages()`, `create(StageCommand)`, `update(UUID, StageCommand, Long)`, `delete(UUID)`, `reorder(List<UUID>)`, `seedDefaults()`; package-private for other crm services: `PipelineStage require(UUID id, String field)` (400 field error when unknown), `PipelineStage firstOpen()`, `List<PipelineStage> ordered()`, `static StageRef ref(PipelineStage)`; `PipelineStage` getters `getId() getName() getProbability() getKind() getPosition() getVersion()`; test helper `TestCrm.enable(Api owner)`.

- [ ] **Step 1: Write the failing test helper and integration test**

`backend/src/test/java/com/nexusops/support/TestCrm.java`:

```java
package com.nexusops.support;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Enables the CRM module for a workspace (permissions of module CRM are inert until it is). */
public final class TestCrm {

    private TestCrm() {}

    public static void enable(Api owner) throws Exception {
        owner.put("/api/v1/tenant/modules/CRM", "{\"enabled\":true}").andExpect(status().isOk());
    }
}
```

`backend/src/test/java/com/nexusops/crm/PipelineStageApiIT.java`:

```java
package com.nexusops.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestCrm;
import com.nexusops.support.TestMembers;
import com.nexusops.support.TestRoles;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Workspace;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class PipelineStageApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("pipe"));
        owner = Api.login(mvc, ws);
        TestCrm.enable(owner);
    }

    private List<String> ids() throws Exception {
        return Api.read(owner.get("/api/v1/crm/pipeline/stages"), "$[*].id");
    }

    @Test
    void aNewWorkspaceStartsWithTheDefaultPipeline() throws Exception {
        owner.get("/api/v1/crm/pipeline/stages").andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name").value(Matchers.contains("Prospecting", "Qualification", "Proposal",
                        "Negotiation", "Won", "Lost")))
                .andExpect(jsonPath("$[*].probability").value(Matchers.contains(10, 25, 50, 75, 100, 0)))
                .andExpect(jsonPath("$[*].kind").value(Matchers.contains("OPEN", "OPEN", "OPEN", "OPEN", "WON", "LOST")));
    }

    @Test
    void crmRoutesNeedTheCrmModule() throws Exception {
        Workspace other = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("nocrm"));
        Api api = Api.login(mvc, other);
        api.get("/api/v1/crm/pipeline/stages").andExpect(status().isForbidden());
        api.post("/api/v1/crm/pipeline/stages", "{\"name\":\"X\",\"probability\":5}").andExpect(status().isForbidden());
    }

    @Test
    void ownersHoldTheNewCrmPermissionsAndTheUnusedSeedsAreGone() throws Exception {
        owner.get("/api/v1/me").andExpect(jsonPath("$.permissions", Matchers.hasItems("crm.lead.read", "crm.lead.manage",
                "crm.opportunity.read", "crm.opportunity.manage", "crm.pipeline.manage", "crm.customer.read")));
        owner.get("/api/v1/permissions").andExpect(jsonPath("$[*].code",
                Matchers.not(Matchers.hasItems("crm.customer.create"))))
                .andExpect(jsonPath("$[?(@.code == 'crm.lead.manage')].module").value(Matchers.contains("CRM")));
    }

    @Test
    void addsRenamesAndReordersOpenStages() throws Exception {
        UUID demo = Api.id(owner.post("/api/v1/crm/pipeline/stages", "{\"name\":\"  Demo   booked \",\"probability\":40}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Demo booked"))
                .andExpect(jsonPath("$.kind").value("OPEN")));
        // a new stage goes after the last open stage, before Won and Lost
        owner.get("/api/v1/crm/pipeline/stages").andExpect(jsonPath("$[*].name").value(Matchers.contains(
                "Prospecting", "Qualification", "Proposal", "Negotiation", "Demo booked", "Won", "Lost")));
        owner.put("/api/v1/crm/pipeline/stages/" + demo, "{\"name\":\"Demo\",\"probability\":45,\"version\":0}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Demo"))
                .andExpect(jsonPath("$.probability").value(45));

        List<String> all = ids();
        List<String> open = all.subList(0, 5);
        String reversed = String.join("\",\"", List.of(open.get(4), open.get(3), open.get(2), open.get(1), open.get(0)));
        owner.put("/api/v1/crm/pipeline/stages/order", "{\"stageIds\":[\"" + reversed + "\"]}").andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name").value(Matchers.contains("Demo", "Negotiation", "Proposal",
                        "Qualification", "Prospecting", "Won", "Lost")));
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select count(*) from audit_events where action in ('PipelineStageCreated', 'PipelineStageUpdated',"
                        + " 'PipelineStagesReordered')", Long.class)).isEqualTo(3);
    }

    @Test
    void invalidStagesAreFieldErrors() throws Exception {
        owner.post("/api/v1/crm/pipeline/stages", "{\"name\":\" \",\"probability\":5}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
        owner.post("/api/v1/crm/pipeline/stages", "{\"name\":\"X\",\"probability\":101}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("probability"));
        owner.post("/api/v1/crm/pipeline/stages", "{\"name\":\"X\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("probability"));
        owner.post("/api/v1/crm/pipeline/stages", "{\"name\":\"proposal\",\"probability\":5}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void reorderNeedsExactlyTheOpenStages() throws Exception {
        List<String> all = ids();
        owner.put("/api/v1/crm/pipeline/stages/order", "{\"stageIds\":[\"" + all.get(0) + "\"]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("stageIds"));
        String withWon = String.join("\",\"", List.of(all.get(0), all.get(1), all.get(2), all.get(3), all.get(4)));
        owner.put("/api/v1/crm/pipeline/stages/order", "{\"stageIds\":[\"" + withWon + "\"]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("stageIds"));
    }

    @Test
    void wonAndLostAreFixedAndOneOpenStageMustRemain() throws Exception {
        List<String> all = ids();
        owner.delete("/api/v1/crm/pipeline/stages/" + all.get(4)).andExpect(status().isConflict());
        owner.delete("/api/v1/crm/pipeline/stages/" + all.get(5)).andExpect(status().isConflict());
        owner.put("/api/v1/crm/pipeline/stages/" + all.get(4), "{\"name\":\"Closed won\",\"probability\":100,\"version\":0}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("WON"));
        for (int i = 0; i < 3; i++) {
            owner.delete("/api/v1/crm/pipeline/stages/" + all.get(i)).andExpect(status().isNoContent());
        }
        owner.delete("/api/v1/crm/pipeline/stages/" + all.get(3)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("The pipeline needs at least one open stage."));
        owner.delete("/api/v1/crm/pipeline/stages/" + UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void atMostTwelveOpenStages() throws Exception {
        for (int i = 0; i < 8; i++) {
            owner.post("/api/v1/crm/pipeline/stages", "{\"name\":\"Step " + i + "\",\"probability\":5}")
                    .andExpect(status().isCreated());
        }
        owner.post("/api/v1/crm/pipeline/stages", "{\"name\":\"One too many\",\"probability\":5}")
                .andExpect(status().isConflict());
    }

    @Test
    void staleVersionsConflict() throws Exception {
        String first = ids().get(0);
        owner.put("/api/v1/crm/pipeline/stages/" + first, "{\"name\":\"A\",\"probability\":10,\"version\":0}")
                .andExpect(status().isOk());
        owner.put("/api/v1/crm/pipeline/stages/" + first, "{\"name\":\"B\",\"probability\":10,\"version\":0}")
                .andExpect(status().isConflict());
        owner.put("/api/v1/crm/pipeline/stages/" + first, "{\"name\":\"B\",\"probability\":10}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("version"));
    }

    @Test
    void readersSeeTheStagesButCannotChangeThem() throws Exception {
        UUID reader = TestRoles.create(mvc, owner.session(), "Sales reader", "crm.opportunity.read");
        Api api = Api.login(mvc, members.create(ws.tenantId(), Set.of(reader)));
        api.get("/api/v1/crm/pipeline/stages").andExpect(status().isOk());
        api.post("/api/v1/crm/pipeline/stages", "{\"name\":\"X\",\"probability\":5}").andExpect(status().isForbidden());
    }
}
```

Also change `AuthorizationServiceIT.java:51` — `crm.customer.delete` no longer exists:

```java
        assertThat(withCrm).contains("crm.customer.read", "crm.lead.manage").doesNotContain("hr.employee.read");
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./gradlew test --tests '*PipelineStageApiIT'`
Expected: FAIL — 404/403 on `/api/v1/crm/pipeline/stages` (no controller yet).

- [ ] **Step 3: Migration V15**

`backend/src/main/resources/db/migration/V15__crm_pipeline.sql`:

```sql
-- CRM (Phase 5): module permissions (D15) and the workspace's sales pipeline (D5).
INSERT INTO permissions (code, module_code, description) VALUES
    ('crm.lead.read',          'CRM', 'View leads'),
    ('crm.lead.manage',        'CRM', 'Create, edit, qualify, import and convert leads'),
    ('crm.opportunity.read',   'CRM', 'View opportunities and the pipeline'),
    ('crm.opportunity.manage', 'CRM', 'Create, edit and move opportunities'),
    ('crm.pipeline.manage',    'CRM', 'Configure pipeline stages');
UPDATE permissions SET description = 'View customers and their sales history' WHERE code = 'crm.customer.read';
SELECT grant_to_system_roles(ARRAY['crm.lead.read', 'crm.lead.manage', 'crm.opportunity.read',
                                   'crm.opportunity.manage', 'crm.pipeline.manage']);

-- V3's unused seeds: creating customers is a directory job (ADR-0008). role_permissions is RLS-protected through
-- roles, so its rows are removed tenant by tenant, the way grant_to_system_roles adds them.
DO $$
DECLARE
    t uuid;
BEGIN
    FOR t IN SELECT id FROM tenants LOOP
        PERFORM set_config('app.tenant_id', t::text, true);
        DELETE FROM role_permissions
        WHERE permission_code IN ('crm.customer.create', 'crm.customer.update', 'crm.customer.delete');
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END
$$;
DELETE FROM permissions WHERE code IN ('crm.customer.create', 'crm.customer.update', 'crm.customer.delete');

-- Ordered stages; WON and LOST are single, fixed, and always last (board order: kind, then position).
CREATE TABLE pipeline_stages (
    id           uuid PRIMARY KEY,
    tenant_id    uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    name         text NOT NULL CHECK (name = btrim(name) AND length(name) BETWEEN 1 AND 60),
    name_key     text NOT NULL,
    position     integer NOT NULL CHECK (position >= 0),
    probability  integer NOT NULL CHECK (probability BETWEEN 0 AND 100),
    kind         text NOT NULL CHECK (kind IN ('OPEN', 'WON', 'LOST')),
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    version      bigint NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, name_key)
);
CREATE UNIQUE INDEX pipeline_stages_one_won_uq ON pipeline_stages (tenant_id) WHERE kind = 'WON';
CREATE UNIQUE INDEX pipeline_stages_one_lost_uq ON pipeline_stages (tenant_id) WHERE kind = 'LOST';

ALTER TABLE pipeline_stages ENABLE ROW LEVEL SECURITY;
ALTER TABLE pipeline_stages FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pipeline_stages
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Default stages for existing workspaces (new ones get them from PipelineSetup on WorkspaceRegistered).
DO $$
DECLARE
    t uuid;
BEGIN
    FOR t IN SELECT id FROM tenants LOOP
        PERFORM set_config('app.tenant_id', t::text, true);
        INSERT INTO pipeline_stages (id, tenant_id, name, name_key, position, probability, kind, created_at, updated_at)
        SELECT gen_random_uuid(), t, s.name, lower(s.name), s.position, s.probability, s.kind, now(), now()
        FROM (VALUES ('Prospecting', 0, 10, 'OPEN'), ('Qualification', 1, 25, 'OPEN'), ('Proposal', 2, 50, 'OPEN'),
                     ('Negotiation', 3, 75, 'OPEN'), ('Won', 0, 100, 'WON'), ('Lost', 0, 0, 'LOST'))
             AS s (name, position, probability, kind);
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END
$$;
```

- [ ] **Step 4: Workspace event**

`backend/src/main/java/com/nexusops/tenancy/WorkspaceRegistered.java`:

```java
package com.nexusops.tenancy;

import java.util.UUID;

/**
 * Published by {@link TenantDirectory#register} inside the signup transaction, with the new workspace bound to
 * TenantContext (spec D13). Listeners seed per-workspace defaults synchronously, in that transaction.
 */
public record WorkspaceRegistered(UUID tenantId) {}
```

In `TenantDirectory.register`, publish after the save (the class already has `events`):

```java
        try {
            TenantSummary created = tenants.saveAndFlush(Tenant.register(id, slug, name)).toSummary();
            events.publishEvent(new WorkspaceRegistered(id));
            return created;
        } catch (DataIntegrityViolationException race) {
            throw slugTaken();
        }
```

- [ ] **Step 5: crm module, domain and service**

`crm/package-info.java`:

```java
/**
 * Sales (Phase 5): leads, the pipeline and opportunities, customers and the CRM dashboard. Every permission belongs to
 * module CRM, so the whole module switches off with it. Leads and opportunities are collaboration subjects; customers
 * are directory parties with an active CUSTOMER role (ADR-0010).
 */
package com.nexusops.crm;
```

`crm/CrmPermissions.java`:

```java
package com.nexusops.crm;

/** Permission codes of the CRM module (V15). All have module_code CRM. */
public final class CrmPermissions {

    public static final String LEAD_READ = "crm.lead.read";
    public static final String LEAD_MANAGE = "crm.lead.manage";
    public static final String OPPORTUNITY_READ = "crm.opportunity.read";
    public static final String OPPORTUNITY_MANAGE = "crm.opportunity.manage";
    public static final String PIPELINE_MANAGE = "crm.pipeline.manage";
    public static final String CUSTOMER_READ = "crm.customer.read";

    private CrmPermissions() {}
}
```

`crm/StageKind.java`:

```java
package com.nexusops.crm;

/** Declaration order is the board order: open stages, then Won, then Lost. */
public enum StageKind {
    OPEN, WON, LOST
}
```

`crm/StageView.java`, `crm/StageRef.java`, `crm/StageCommand.java`:

```java
package com.nexusops.crm;

import java.util.UUID;

public record StageView(UUID id, String name, int probability, StageKind kind, int position, long version) {}
```

```java
package com.nexusops.crm;

import java.util.UUID;

public record StageRef(UUID id, String name, StageKind kind, int probability) {}
```

```java
package com.nexusops.crm;

/** Raw input; PipelineService validates it. */
public record StageCommand(String name, Integer probability) {}
```

`crm/domain/PipelineStage.java`:

```java
package com.nexusops.crm.domain;

import com.nexusops.crm.StageKind;
import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pipeline_stages")
public class PipelineStage extends TenantOwnedEntity {

    @Column(nullable = false)
    private String name;

    @Column(name = "name_key", nullable = false)
    private String nameKey;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false)
    private int probability;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private StageKind kind;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected PipelineStage() {}

    public PipelineStage(UUID id, String name, String nameKey, int position, int probability, StageKind kind) {
        super(id);
        this.name = name;
        this.nameKey = nameKey;
        this.position = position;
        this.probability = probability;
        this.kind = kind;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public void rename(String newName, String newKey, int newProbability) {
        this.name = newName;
        this.nameKey = newKey;
        this.probability = newProbability;
        this.updatedAt = Instant.now();
    }

    public void moveTo(int newPosition) {
        if (this.position != newPosition) {
            this.position = newPosition;
            this.updatedAt = Instant.now();
        }
    }

    public String getName() {
        return name;
    }

    public String getNameKey() {
        return nameKey;
    }

    public int getPosition() {
        return position;
    }

    public int getProbability() {
        return probability;
    }

    public StageKind getKind() {
        return kind;
    }

    public long getVersion() {
        return version;
    }
}
```

`crm/domain/PipelineStageRepository.java`:

```java
package com.nexusops.crm.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PipelineStageRepository extends JpaRepository<PipelineStage, UUID> {

    boolean existsByNameKey(String nameKey);

    boolean existsByNameKeyAndIdNot(String nameKey, UUID id);
}
```

`crm/PipelineService.java`:

```java
package com.nexusops.crm;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.crm.domain.PipelineStage;
import com.nexusops.crm.domain.PipelineStageRepository;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.Text;
import com.nexusops.shared.db.TenantLocks;
import com.nexusops.shared.web.ApiProblem;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The workspace's single pipeline (D5): 1–12 open stages, then one Won and one Lost stage. */
@Service
public class PipelineService {

    static final String NOT_FOUND = "Record not found.";
    static final String STALE = "This record was changed by someone else. Reload and try again.";
    static final String NAME_TAKEN = "Another stage already has this name.";
    static final String IN_USE = "Move this stage's opportunities first.";
    static final String LAST_OPEN = "The pipeline needs at least one open stage.";
    static final String TOO_MANY = "A pipeline can have at most 12 open stages.";
    static final String FIXED = "The Won and Lost stages can be renamed but not deleted or moved.";
    static final String UNKNOWN_STAGE = "Choose a stage of this pipeline.";
    static final int MAX_OPEN = 12;
    private static final String LOCK = "pipeline";
    private static final Comparator<PipelineStage> BOARD_ORDER = Comparator.comparing(PipelineStage::getKind)
            .thenComparingInt(PipelineStage::getPosition).thenComparing(PipelineStage::getId);
    private static final List<Object[]> DEFAULTS = List.of(new Object[] {"Prospecting", 10, StageKind.OPEN},
            new Object[] {"Qualification", 25, StageKind.OPEN}, new Object[] {"Proposal", 50, StageKind.OPEN},
            new Object[] {"Negotiation", 75, StageKind.OPEN}, new Object[] {"Won", 100, StageKind.WON},
            new Object[] {"Lost", 0, StageKind.LOST});

    private final PipelineStageRepository stages;
    private final TenantLocks locks;
    private final AuditService audit;

    PipelineService(PipelineStageRepository stages, TenantLocks locks, AuditService audit) {
        this.stages = stages;
        this.locks = locks;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<StageView> stages() {
        return ordered().stream().map(PipelineService::view).toList();
    }

    @Transactional
    public StageView create(StageCommand command) {
        TenantContext.requireTenantId();
        String name = name(command.name());
        int probability = probability(command.probability());
        locks.lock(LOCK);
        List<PipelineStage> open = ordered().stream().filter(s -> s.getKind() == StageKind.OPEN).toList();
        if (open.size() >= MAX_OPEN) {
            throw ApiProblem.conflict(TOO_MANY);
        }
        if (stages.existsByNameKey(key(name))) {
            throw ApiProblem.conflictField("name", NAME_TAKEN);
        }
        int position = open.isEmpty() ? 0 : open.getLast().getPosition() + 1;
        PipelineStage stage = new PipelineStage(Ids.newId(), name, key(name), position, probability, StageKind.OPEN);
        save(stage);
        audit.record(AuditEntry.of("PipelineStageCreated", "PipelineStage", stage.getId())
                .withAfter(Map.of("name", name, "probability", probability)));
        return view(stage);
    }

    @Transactional
    public StageView update(UUID id, StageCommand command, Long version) {
        PipelineStage stage = find(id);
        if (version == null) {
            throw ApiProblem.badRequestField("version", "Reload the record and try again.");
        }
        if (stage.getVersion() != version) {
            throw ApiProblem.conflict(STALE);
        }
        String name = name(command.name());
        int probability = probability(command.probability());
        locks.lock(LOCK);
        if (stages.existsByNameKeyAndIdNot(key(name), id)) {
            throw ApiProblem.conflictField("name", NAME_TAKEN);
        }
        Map<String, Object> before = Map.of("name", stage.getName(), "probability", stage.getProbability());
        stage.rename(name, key(name), probability);
        save(stage);
        audit.record(AuditEntry.of("PipelineStageUpdated", "PipelineStage", id).withBefore(before)
                .withAfter(Map.of("name", name, "probability", probability)));
        return view(stage);
    }

    @Transactional
    public void delete(UUID id) {
        PipelineStage stage = find(id);
        if (stage.getKind() != StageKind.OPEN) {
            throw ApiProblem.conflict(FIXED);
        }
        locks.lock(LOCK);
        long open = ordered().stream().filter(s -> s.getKind() == StageKind.OPEN).count();
        if (open <= 1) {
            throw ApiProblem.conflict(LAST_OPEN);
        }
        try {
            stages.delete(stage);
            stages.flush();
        } catch (DataIntegrityViolationException referenced) {
            throw ApiProblem.conflict(IN_USE);
        }
        audit.record(AuditEntry.of("PipelineStageDeleted", "PipelineStage", id)
                .withBefore(Map.of("name", stage.getName())));
    }

    /** {@code openStageIds} must be exactly the open stages, in their new order; Won and Lost stay last. */
    @Transactional
    public List<StageView> reorder(List<UUID> openStageIds) {
        TenantContext.requireTenantId();
        locks.lock(LOCK);
        List<PipelineStage> open = ordered().stream().filter(s -> s.getKind() == StageKind.OPEN).toList();
        List<UUID> requested = openStageIds == null ? List.of() : openStageIds;
        if (requested.size() != open.size() || new HashSet<>(requested).size() != requested.size()
                || !new HashSet<>(requested).equals(new HashSet<>(open.stream().map(PipelineStage::getId).toList()))) {
            throw ApiProblem.badRequestField("stageIds", "List every open stage exactly once.");
        }
        Map<UUID, PipelineStage> byId = new java.util.HashMap<>();
        open.forEach(s -> byId.put(s.getId(), s));
        for (int i = 0; i < requested.size(); i++) {
            byId.get(requested.get(i)).moveTo(i);
        }
        stages.flush();
        audit.record(AuditEntry.of("PipelineStagesReordered", "Pipeline", TenantContext.requireTenantId())
                .withAfter(Map.of("stageIds", requested.stream().map(UUID::toString).toList())));
        return stages();
    }

    /** Idempotent: a workspace that has any stage keeps its pipeline. */
    @Transactional
    public void seedDefaults() {
        TenantContext.requireTenantId();
        locks.lock(LOCK);
        if (stages.count() > 0) {
            return;
        }
        int position = 0;
        for (Object[] row : DEFAULTS) {
            String name = (String) row[0];
            StageKind kind = (StageKind) row[2];
            stages.save(new PipelineStage(Ids.newId(), name, key(name), kind == StageKind.OPEN ? position++ : 0,
                    (Integer) row[1], kind));
        }
        stages.flush();
    }

    List<PipelineStage> ordered() {
        TenantContext.requireTenantId();
        return stages.findAll().stream().sorted(BOARD_ORDER).toList();
    }

    /** A stage referenced from a request body: unknown (or another tenant's) is a 400 on {@code field}. */
    PipelineStage require(UUID id, String field) {
        TenantContext.requireTenantId();
        if (id == null) {
            throw ApiProblem.badRequestField(field, UNKNOWN_STAGE);
        }
        return stages.findById(id).orElseThrow(() -> ApiProblem.badRequestField(field, UNKNOWN_STAGE));
    }

    PipelineStage firstOpen() {
        return ordered().stream().filter(s -> s.getKind() == StageKind.OPEN).findFirst()
                .orElseThrow(() -> new IllegalStateException("A pipeline always has an open stage"));
    }

    static StageRef ref(PipelineStage s) {
        return new StageRef(s.getId(), s.getName(), s.getKind(), s.getProbability());
    }

    static StageView view(PipelineStage s) {
        return new StageView(s.getId(), s.getName(), s.getProbability(), s.getKind(), s.getPosition(), s.getVersion());
    }

    private PipelineStage find(UUID id) {
        TenantContext.requireTenantId();
        return stages.findById(id).orElseThrow(() -> ApiProblem.notFound(NOT_FOUND));
    }

    private void save(PipelineStage stage) {
        try {
            stages.saveAndFlush(stage);
        } catch (DataIntegrityViolationException race) {
            throw ApiProblem.conflictField("name", NAME_TAKEN);
        }
    }

    private static String name(String raw) {
        return Text.required(raw, 60, "name").replaceAll("\\s+", " ");
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static int probability(Integer raw) {
        if (raw == null || raw < 0 || raw > 100) {
            throw ApiProblem.badRequestField("probability", "Enter a probability from 0 to 100.");
        }
        return raw;
    }
}
```

`crm/PipelineSetup.java`:

```java
package com.nexusops.crm;

import com.nexusops.tenancy.WorkspaceRegistered;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Gives every new workspace the default pipeline, in the signup transaction (D13). */
@Component
class PipelineSetup {

    private final PipelineService pipeline;

    PipelineSetup(PipelineService pipeline) {
        this.pipeline = pipeline;
    }

    @EventListener
    void on(WorkspaceRegistered event) {
        pipeline.seedDefaults();
    }
}
```

- [ ] **Step 6: Web layer**

`crm/web/CrmDtos.java` (later tasks add more records to this file):

```java
package com.nexusops.crm.web;

import com.nexusops.crm.StageCommand;
import java.util.List;
import java.util.UUID;

final class CrmDtos {

    private CrmDtos() {}

    record StageRequest(String name, Integer probability, Long version) {
        StageCommand command() {
            return new StageCommand(name, probability);
        }
    }

    record StageOrderRequest(List<UUID> stageIds) {}
}
```

`crm/web/StageController.java`:

```java
package com.nexusops.crm.web;

import com.nexusops.crm.PipelineService;
import com.nexusops.crm.StageView;
import com.nexusops.crm.web.CrmDtos.StageOrderRequest;
import com.nexusops.crm.web.CrmDtos.StageRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/crm/pipeline/stages")
class StageController {

    private final PipelineService pipeline;

    StageController(PipelineService pipeline) {
        this.pipeline = pipeline;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('crm.opportunity.read')")
    List<StageView> list() {
        return pipeline.stages();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('crm.pipeline.manage')")
    StageView create(@RequestBody StageRequest request) {
        return pipeline.create(request.command());
    }

    @PutMapping("/order")
    @PreAuthorize("hasAuthority('crm.pipeline.manage')")
    List<StageView> reorder(@RequestBody StageOrderRequest request) {
        return pipeline.reorder(request.stageIds());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('crm.pipeline.manage')")
    StageView update(@PathVariable UUID id, @RequestBody StageRequest request) {
        return pipeline.update(id, request.command(), request.version());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('crm.pipeline.manage')")
    void delete(@PathVariable UUID id) {
        pipeline.delete(id);
    }
}
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `cd backend && ./gradlew test --tests '*PipelineStageApiIT' --tests '*AuthorizationServiceIT' --tests '*ModularityTest' --tests '*EndpointAuthorizationCoverageTest' --tests '*RlsCoverageIT'`
Expected: PASS except `RlsCoverageIT.expectedTenantTablesExist` is unaffected and `everyNonGlobalTableForcesRlsAndHasAPolicy` passes (the new table has its policy). If `ModularityTest` reports `crm` → `tenancy` as a violation, the event type must live in the `tenancy` top-level package (it does) — fix imports, not the test.

- [ ] **Step 8: Run the whole backend suite**

Run: `cd backend && ./gradlew test`
Expected: PASS (every signup now also seeds stages).

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/resources/db/migration/V15__crm_pipeline.sql backend/src/main/java/com/nexusops/tenancy \
  backend/src/main/java/com/nexusops/crm backend/src/test/java/com/nexusops/support/TestCrm.java \
  backend/src/test/java/com/nexusops/crm backend/src/test/java/com/nexusops/authorization/AuthorizationServiceIT.java
git commit -m "feat(crm): CRM permissions and a configurable pipeline seeded for every workspace"
```

---

### Task 2: Leads — create, edit, status changes, list, and leads as record subjects

**Files:**
- Create: `backend/src/main/resources/db/migration/V16__leads.sql`
- Create: `backend/src/main/java/com/nexusops/directory/PartyBrief.java`; Modify: `directory/PartyService.java` (+ `briefs`)
- Create: `backend/src/main/java/com/nexusops/crm/Money.java`, `MoneyTotal.java`, `LeadSource.java`, `LeadStatus.java`, `LeadCommand.java`, `LeadQuery.java`, `LeadView.java`, `LeadService.java`, `LeadSubjects.java`
- Create: `backend/src/main/java/com/nexusops/crm/domain/Lead.java`, `LeadDetails.java`, `LeadRepository.java`
- Create: `backend/src/main/java/com/nexusops/crm/web/LeadController.java`, `OwnerController.java`; Modify: `crm/web/CrmDtos.java`
- Test: `backend/src/test/java/com/nexusops/crm/LeadApiIT.java`, `LeadStatusRulesTest.java`

**Interfaces:**
- Consumes: `CrmPermissions`, `TestCrm.enable` (Task 1); `Members.findActive/findAll/searchActive`, `MemberRef`, `AssigneeView` (collaboration), `SubjectResolver`, `SubjectRef`.
- Produces:
  - `directory.PartyBrief(UUID id, PartyKind kind, String name, UUID organizationId, boolean archived)`; `PartyService.briefs(Collection<UUID>) : Map<UUID, PartyBrief>` (read-only, tenant-scoped, unknown ids absent).
  - `crm.Money` (package-private): `static BigDecimal amount(BigDecimal value, String field)`, `static String currency(String raw, String field, TenantDirectory tenants)`.
  - `crm.MoneyTotal(String currency, BigDecimal amount)`.
  - `enum LeadSource {WEBSITE, REFERRAL, WALK_IN, PHONE, EMAIL, SOCIAL, EVENT, OTHER}`; `enum LeadStatus {NEW, CONTACTED, QUALIFIED, DISQUALIFIED, CONVERTED}` with `boolean isOpen()` and `static LeadStatus.check(LeadStatus from, LeadStatus to)` (the D3 rules; throws `ApiProblem`).
  - `LeadCommand(String firstName, String lastName, String companyName, String jobTitle, String email, String phone, LeadSource source, UUID ownerId, BigDecimal estimatedValue, String currency, String description)`.
  - `LeadView(UUID id, String name, String firstName, String lastName, String companyName, String jobTitle, String email, String phone, LeadSource source, LeadStatus status, MemberRef owner, BigDecimal estimatedValue, String currency, String description, String disqualifyReason, Instant disqualifiedAt, Instant convertedAt, PartyRef convertedPerson, PartyRef convertedOrganization, UUID convertedOpportunityId, MemberRef createdBy, Instant createdAt, Instant updatedAt, long version)`.
  - `LeadService` public `get`, `list(LeadQuery, Integer, Integer)`, `create`, `update(UUID, LeadCommand, Long)`, `changeStatus(UUID, LeadStatus, String reason, Long version)`; package-private `Lead find(UUID)`, `LeadDetails validate(LeadCommand, Lead current)` (Task 5 reuses it), `Lead requireConvertible(UUID id, Long version)` (Task 4), `LeadView view(Lead)`.
  - `Lead` entity: getters for every column, `static String displayName(String first, String last, String company)`, `markConverted(UUID personId, UUID organizationId, UUID opportunityId, Instant at)`, `changeStatus(LeadStatus, String reason, Instant at)`, `apply(LeadDetails)`.
  - `LeadSubjects` type `"LEAD"`; converted leads report `archived = true`.
  - `GET /api/v1/crm/owners?q=` → `List<AssigneeView>`.

- [ ] **Step 1: Unit test the status rules**

`backend/src/test/java/com/nexusops/crm/LeadStatusRulesTest.java`:

```java
package com.nexusops.crm;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexusops.shared.web.ApiProblem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LeadStatusRulesTest {

    @ParameterizedTest
    @CsvSource({"NEW,CONTACTED", "CONTACTED,QUALIFIED", "QUALIFIED,NEW", "NEW,DISQUALIFIED", "QUALIFIED,DISQUALIFIED",
            "DISQUALIFIED,NEW"})
    void allowed(LeadStatus from, LeadStatus to) {
        assertThatCode(() -> LeadStatus.check(from, to)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource({"DISQUALIFIED,CONTACTED,409", "DISQUALIFIED,QUALIFIED,409", "CONVERTED,NEW,409",
            "CONVERTED,DISQUALIFIED,409", "NEW,CONVERTED,400", "QUALIFIED,CONVERTED,400"})
    void refused(LeadStatus from, LeadStatus to, int status) {
        assertThatThrownBy(() -> LeadStatus.check(from, to)).isInstanceOfSatisfying(ApiProblem.class,
                p -> org.assertj.core.api.Assertions.assertThat(p.status().value()).isEqualTo(status));
    }

    @Test
    void openStatuses() {
        org.assertj.core.api.Assertions.assertThat(LeadStatus.NEW.isOpen()).isTrue();
        org.assertj.core.api.Assertions.assertThat(LeadStatus.QUALIFIED.isOpen()).isTrue();
        org.assertj.core.api.Assertions.assertThat(LeadStatus.DISQUALIFIED.isOpen()).isFalse();
        org.assertj.core.api.Assertions.assertThat(LeadStatus.CONVERTED.isOpen()).isFalse();
    }
}
```

- [ ] **Step 2: Write the failing API test**

`backend/src/test/java/com/nexusops/crm/LeadApiIT.java`:

```java
package com.nexusops.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestCrm;
import com.nexusops.support.TestMembers;
import com.nexusops.support.TestRoles;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Workspace;
import java.util.Set;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class LeadApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;
    UUID ownerId;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("lead"));
        owner = Api.login(mvc, ws);
        TestCrm.enable(owner);
        ownerId = UUID.fromString(Api.read(owner.get("/api/v1/me"), "$.user.id"));
    }

    private UUID lead(String json) throws Exception {
        return Api.id(owner.post("/api/v1/leads", json).andExpect(status().isCreated()));
    }

    @Test
    void createsALeadOwnedByItsCreator() throws Exception {
        owner.post("/api/v1/leads", "{\"firstName\":\" Grace \",\"lastName\":\"Hopper\",\"companyName\":\"Acme\","
                        + "\"email\":\" GRACE@Acme.test \",\"phone\":\" +91  98200 41130 \",\"source\":\"REFERRAL\","
                        + "\"estimatedValue\":50000}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Grace Hopper"))
                .andExpect(jsonPath("$.email").value("grace@acme.test"))
                .andExpect(jsonPath("$.phone").value("+91 98200 41130"))
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.source").value("REFERRAL"))
                .andExpect(jsonPath("$.owner.id").value(ownerId.toString()))
                .andExpect(jsonPath("$.createdBy.id").value(ownerId.toString()))
                .andExpect(jsonPath("$.estimatedValue").value(50000))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.version").value(0));
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select count(*) from audit_events where action = 'LeadCreated'", Long.class)).isEqualTo(1);
    }

    @Test
    void aCompanyOnlyLeadIsNamedAfterTheCompany() throws Exception {
        UUID id = lead("{\"companyName\":\"Deccan Spices\"}");
        owner.get("/api/v1/leads/" + id).andExpect(jsonPath("$.name").value("Deccan Spices"))
                .andExpect(jsonPath("$.source").value("OTHER"));
        owner.get("/api/v1/leads").andExpect(jsonPath("$.items[0].name").value("Deccan Spices"));
        owner.post("/api/v1/activities", "{\"subjectType\":\"LEAD\",\"subjectId\":\"" + id
                + "\",\"type\":\"NOTE\",\"summary\":\"Called\"}").andExpect(status().isCreated());
        owner.post("/api/v1/tasks", "{\"title\":\"Follow up\",\"subjectType\":\"LEAD\",\"subjectId\":\"" + id + "\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.subject.label").value("Deccan Spices"));
    }

    @Test
    void invalidLeadsAreFieldErrors() throws Exception {
        String[][] cases = {
                {"{}", "lastName"},
                {"{\"jobTitle\":\"CEO\"}", "lastName"},
                {"{\"lastName\":\"X\",\"email\":\"nope\"}", "email"},
                {"{\"lastName\":\"X\",\"estimatedValue\":-1}", "estimatedValue"},
                {"{\"lastName\":\"X\",\"estimatedValue\":1,\"currency\":\"EURO\"}", "currency"},
                {"{\"lastName\":\"X\",\"ownerId\":\"" + UUID.randomUUID() + "\"}", "ownerId"},
                {"{\"lastName\":\"X\",\"description\":\"" + "d".repeat(5001) + "\"}", "description"},
        };
        for (String[] c : cases) {
            owner.post("/api/v1/leads", c[0]).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value(c[1]));
        }
    }

    @Test
    void editsWithTheCurrentVersion() throws Exception {
        UUID id = lead("{\"lastName\":\"Iyer\"}");
        owner.put("/api/v1/leads/" + id, "{\"firstName\":\"Meera\",\"lastName\":\"Iyer\",\"source\":\"EVENT\","
                + "\"version\":0}").andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Meera Iyer"))
                .andExpect(jsonPath("$.owner").doesNotExist()); // null on update means unassigned
        owner.put("/api/v1/leads/" + id, "{\"lastName\":\"Iyer\",\"version\":0}").andExpect(status().isConflict());
        owner.put("/api/v1/leads/" + id, "{\"lastName\":\"Iyer\"}").andExpect(status().isBadRequest());
        owner.get("/api/v1/leads/" + UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void statusChangesFollowTheRules() throws Exception {
        UUID id = lead("{\"lastName\":\"Patil\"}");
        owner.post("/api/v1/leads/" + id + "/status", "{\"status\":\"CONTACTED\",\"version\":0}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONTACTED"));
        owner.post("/api/v1/leads/" + id + "/status", "{\"status\":\"DISQUALIFIED\",\"version\":1}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("reason"));
        owner.post("/api/v1/leads/" + id + "/status", "{\"status\":\"DISQUALIFIED\",\"reason\":\"No budget\",\"version\":1}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.disqualifyReason").value("No budget"))
                .andExpect(jsonPath("$.disqualifiedAt").exists());
        owner.post("/api/v1/leads/" + id + "/status", "{\"status\":\"QUALIFIED\",\"version\":2}")
                .andExpect(status().isConflict());
        owner.post("/api/v1/leads/" + id + "/status", "{\"status\":\"NEW\",\"version\":2}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.disqualifyReason").doesNotExist())
                .andExpect(jsonPath("$.disqualifiedAt").doesNotExist());
        owner.post("/api/v1/leads/" + id + "/status", "{\"status\":\"CONVERTED\",\"version\":3}")
                .andExpect(status().isBadRequest());
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select count(*) from audit_events where action = 'LeadStatusChanged'", Long.class)).isEqualTo(3);
    }

    @Test
    void listsOpenLeadsByDefaultAndFilters() throws Exception {
        UUID a = lead("{\"firstName\":\"Anjali\",\"lastName\":\"Deshpande\",\"companyName\":\"Deccan\",\"source\":\"WEBSITE\"}");
        UUID b = lead("{\"companyName\":\"Konkan Logistics\",\"email\":\"ops@konkan.test\"}");
        UUID c = lead("{\"lastName\":\"Gone\"}");
        owner.post("/api/v1/leads/" + c + "/status", "{\"status\":\"DISQUALIFIED\",\"reason\":\"Spam\",\"version\":0}")
                .andExpect(status().isOk());
        owner.get("/api/v1/leads").andExpect(jsonPath("$.total").value(2));
        owner.get("/api/v1/leads?status=DISQUALIFIED").andExpect(jsonPath("$.items[*].id").value(Matchers.contains(c.toString())));
        owner.get("/api/v1/leads?q=KONKAN").andExpect(jsonPath("$.items[*].id").value(Matchers.contains(b.toString())));
        owner.get("/api/v1/leads?q=ops@").andExpect(jsonPath("$.total").value(1));
        owner.get("/api/v1/leads?source=WEBSITE").andExpect(jsonPath("$.items[*].id").value(Matchers.contains(a.toString())));
        owner.get("/api/v1/leads?owner=me").andExpect(jsonPath("$.total").value(2));
        owner.get("/api/v1/leads?owner=unassigned").andExpect(jsonPath("$.total").value(0));
        owner.get("/api/v1/leads?status=BOGUS").andExpect(status().isBadRequest());
        owner.get("/api/v1/leads?owner=nobody").andExpect(status().isBadRequest());
    }

    @Test
    void permissionsSplitReadingFromManaging() throws Exception {
        UUID id = lead("{\"lastName\":\"Kulkarni\"}");
        UUID role = TestRoles.create(mvc, owner.session(), "Lead reader", "crm.lead.read");
        Api reader = Api.login(mvc, members.create(ws.tenantId(), Set.of(role)));
        reader.get("/api/v1/leads/" + id).andExpect(status().isOk());
        reader.post("/api/v1/leads", "{\"lastName\":\"X\"}").andExpect(status().isForbidden());
        reader.post("/api/v1/leads/" + id + "/status", "{\"status\":\"CONTACTED\",\"version\":0}").andExpect(status().isForbidden());
        reader.get("/api/v1/crm/owners").andExpect(status().isForbidden());
        owner.get("/api/v1/crm/owners?q=ada").andExpect(status().isOk());
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `cd backend && ./gradlew test --tests '*LeadApiIT' --tests '*LeadStatusRulesTest'`
Expected: FAIL — compilation errors (`LeadStatus` missing).

- [ ] **Step 4: Migration V16**

`backend/src/main/resources/db/migration/V16__leads.sql`:

```sql
-- Leads (D2, D3): raw contact details of a prospect; conversion links them to canonical parties (D4).
CREATE TABLE leads (
    id                         uuid PRIMARY KEY,
    tenant_id                  uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    first_name                 text CHECK (first_name IS NULL OR length(first_name) BETWEEN 1 AND 80),
    last_name                  text CHECK (last_name IS NULL OR length(last_name) BETWEEN 1 AND 80),
    company_name               text CHECK (company_name IS NULL OR length(company_name) BETWEEN 1 AND 200),
    job_title                  text CHECK (job_title IS NULL OR length(job_title) <= 100),
    email                      text CHECK (email IS NULL OR length(email) <= 254),
    phone                      text CHECK (phone IS NULL OR length(phone) <= 40),
    source                     text NOT NULL CHECK (source IN ('WEBSITE', 'REFERRAL', 'WALK_IN', 'PHONE', 'EMAIL',
                                                               'SOCIAL', 'EVENT', 'OTHER')),
    status                     text NOT NULL CHECK (status IN ('NEW', 'CONTACTED', 'QUALIFIED', 'DISQUALIFIED',
                                                               'CONVERTED')),
    owner_id                   uuid REFERENCES users (id) ON DELETE SET NULL,
    estimated_value            numeric(19, 4) CHECK (estimated_value IS NULL OR estimated_value >= 0),
    currency                   text CHECK (currency IS NULL OR currency ~ '^[A-Z]{3}$'),
    description                text CHECK (description IS NULL OR length(description) <= 5000),
    disqualify_reason          text CHECK (disqualify_reason IS NULL OR length(disqualify_reason) BETWEEN 1 AND 500),
    disqualified_at            timestamptz,
    converted_at               timestamptz,
    converted_person_id        uuid,
    converted_organization_id  uuid,
    converted_opportunity_id   uuid,
    created_by                 uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at                 timestamptz NOT NULL,
    updated_at                 timestamptz NOT NULL,
    version                    bigint NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    CHECK (first_name IS NOT NULL OR last_name IS NOT NULL OR company_name IS NOT NULL),
    CHECK ((estimated_value IS NULL) = (currency IS NULL)),
    CHECK ((status = 'DISQUALIFIED') = (disqualify_reason IS NOT NULL)),
    CHECK ((status = 'DISQUALIFIED') = (disqualified_at IS NOT NULL)),
    CHECK ((status = 'CONVERTED') = (converted_at IS NOT NULL)),
    CHECK (status <> 'CONVERTED' OR converted_person_id IS NOT NULL OR converted_organization_id IS NOT NULL),
    FOREIGN KEY (tenant_id, converted_person_id) REFERENCES parties (tenant_id, id),
    FOREIGN KEY (tenant_id, converted_organization_id) REFERENCES parties (tenant_id, id)
);
CREATE INDEX leads_status_idx ON leads (tenant_id, status, created_at);
CREATE INDEX leads_owner_idx ON leads (tenant_id, owner_id);
CREATE INDEX leads_converted_person_idx ON leads (tenant_id, converted_person_id);
CREATE INDEX leads_converted_organization_idx ON leads (tenant_id, converted_organization_id);

ALTER TABLE leads ENABLE ROW LEVEL SECURITY;
ALTER TABLE leads FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON leads
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
```

- [ ] **Step 5: Directory lookup**

`directory/PartyBrief.java`:

```java
package com.nexusops.directory;

import java.util.UUID;

/** A party as other modules reference it: enough to label, type-check and reject archived records. */
public record PartyBrief(UUID id, PartyKind kind, String name, UUID organizationId, boolean archived) {}
```

Add to `PartyService` (imports `java.util.Collection`):

```java
    /** Tenant-scoped lookup for other modules; unknown ids (or other tenants') are simply absent. */
    @Transactional(readOnly = true)
    public Map<UUID, PartyBrief> briefs(Collection<UUID> ids) {
        TenantContext.requireTenantId();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return parties.findAllById(Set.copyOf(ids)).stream().collect(Collectors.toMap(Party::getId,
                p -> new PartyBrief(p.getId(), p.getKind(), p.getName(), p.getOrganizationId(), p.isArchived())));
    }
```

- [ ] **Step 6: Money helper and lead types**

`crm/Money.java`:

```java
package com.nexusops.crm;

import com.nexusops.shared.web.ApiProblem;
import com.nexusops.tenancy.TenantDirectory;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.Locale;

/** Validation shared by lead values and opportunity amounts (same rules as product prices). */
final class Money {

    private static final int MAX_INTEGER_DIGITS = 15;

    private Money() {}

    /** Null stays null; otherwise ≥ 0, ≤ 4 decimals, ≤ 15 integer digits. */
    static BigDecimal amount(BigDecimal value, String field) {
        if (value == null) {
            return null;
        }
        if (value.signum() < 0) {
            throw ApiProblem.badRequestField(field, "Enter an amount of 0 or more.");
        }
        if (value.stripTrailingZeros().scale() > 4) {
            throw ApiProblem.badRequestField(field, "Use at most 4 decimal places.");
        }
        if (value.precision() - value.scale() > MAX_INTEGER_DIGITS) {
            throw ApiProblem.badRequestField(field, "Enter a smaller amount.");
        }
        return value;
    }

    /** The currency for a non-null amount: the given ISO code, or the workspace currency when blank. */
    static String currency(String raw, String field, TenantDirectory tenants) {
        if (raw == null || raw.isBlank()) {
            return tenants.currentSettings().currency();
        }
        String code = raw.strip().toUpperCase(Locale.ROOT);
        try {
            if (code.length() == 3 && Currency.getInstance(code) != null) {
                return code;
            }
        } catch (IllegalArgumentException unknown) {
            // falls through to the field error
        }
        throw ApiProblem.badRequestField(field, "Use a 3-letter currency code like USD.");
    }
}
```

`crm/MoneyTotal.java`:

```java
package com.nexusops.crm;

import java.math.BigDecimal;

/** A sum in one currency. Amounts of different currencies are never added together. */
public record MoneyTotal(String currency, BigDecimal amount) {}
```

`crm/LeadSource.java`:

```java
package com.nexusops.crm;

public enum LeadSource {
    WEBSITE, REFERRAL, WALK_IN, PHONE, EMAIL, SOCIAL, EVENT, OTHER
}
```

`crm/LeadStatus.java`:

```java
package com.nexusops.crm;

import com.nexusops.shared.web.ApiProblem;

/** D3: NEW, CONTACTED and QUALIFIED are open and interchangeable; DISQUALIFIED reopens only as NEW; CONVERTED is final. */
public enum LeadStatus {
    NEW, CONTACTED, QUALIFIED, DISQUALIFIED, CONVERTED;

    static final String CONVERTED_FINAL = "This lead was converted and can no longer be changed.";

    public boolean isOpen() {
        return this == NEW || this == CONTACTED || this == QUALIFIED;
    }

    /** Throws when {@code from → to} isn't allowed through a status change (conversion has its own route). */
    static void check(LeadStatus from, LeadStatus to) {
        if (to == CONVERTED) {
            throw ApiProblem.badRequestField("status", "Convert the lead instead.");
        }
        if (from == CONVERTED) {
            throw ApiProblem.conflict(CONVERTED_FINAL);
        }
        if (from == DISQUALIFIED && to != NEW && to != DISQUALIFIED) {
            throw ApiProblem.conflict("Reopen the lead as New first.");
        }
    }
}
```

`crm/LeadCommand.java`, `crm/LeadQuery.java`, `crm/LeadView.java`:

```java
package com.nexusops.crm;

import java.math.BigDecimal;
import java.util.UUID;

/** Raw input; LeadService validates it. Source defaults to OTHER; ownerId null means the creator on create only. */
public record LeadCommand(String firstName, String lastName, String companyName, String jobTitle, String email,
        String phone, LeadSource source, UUID ownerId, BigDecimal estimatedValue, String currency, String description) {}
```

```java
package com.nexusops.crm;

import java.util.UUID;

/** {@code status}: comma-separated, default the open statuses. {@code owner}: me, unassigned or a member id.
 * {@code partyId}: leads converted into that person or organization. */
public record LeadQuery(String q, String status, String owner, LeadSource source, UUID partyId) {}
```

```java
package com.nexusops.crm;

import com.nexusops.collaboration.MemberRef;
import com.nexusops.directory.PartyRef;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LeadView(UUID id, String name, String firstName, String lastName, String companyName, String jobTitle,
        String email, String phone, LeadSource source, LeadStatus status, MemberRef owner, BigDecimal estimatedValue,
        String currency, String description, String disqualifyReason, Instant disqualifiedAt, Instant convertedAt,
        PartyRef convertedPerson, PartyRef convertedOrganization, UUID convertedOpportunityId, MemberRef createdBy,
        Instant createdAt, Instant updatedAt, long version) {}
```

- [ ] **Step 7: Lead entity and repository**

`crm/domain/LeadDetails.java`:

```java
package com.nexusops.crm.domain;

import com.nexusops.crm.LeadSource;
import java.math.BigDecimal;
import java.util.UUID;

/** Validated lead fields (LeadService builds these). */
public record LeadDetails(String firstName, String lastName, String companyName, String jobTitle, String email,
        String phone, LeadSource source, UUID ownerId, BigDecimal estimatedValue, String currency, String description) {}
```

`crm/domain/Lead.java`:

```java
package com.nexusops.crm.domain;

import com.nexusops.crm.LeadSource;
import com.nexusops.crm.LeadStatus;
import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "leads")
public class Lead extends TenantOwnedEntity {

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    @Column(name = "company_name")
    private String companyName;

    @Column(name = "job_title")
    private String jobTitle;

    private String email;

    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LeadSource source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LeadStatus status;

    @Column(name = "owner_id")
    private UUID ownerId;

    @Column(name = "estimated_value", precision = 19, scale = 4)
    private BigDecimal estimatedValue;

    private String currency;

    private String description;

    @Column(name = "disqualify_reason")
    private String disqualifyReason;

    @Column(name = "disqualified_at")
    private Instant disqualifiedAt;

    @Column(name = "converted_at")
    private Instant convertedAt;

    @Column(name = "converted_person_id")
    private UUID convertedPersonId;

    @Column(name = "converted_organization_id")
    private UUID convertedOrganizationId;

    @Column(name = "converted_opportunity_id")
    private UUID convertedOpportunityId;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Lead() {}

    public Lead(UUID id, LeadDetails details, UUID createdBy) {
        super(id);
        this.status = LeadStatus.NEW;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        apply(details);
    }

    /** Full name, else the company: a lead needs one of the two. */
    public static String displayName(String first, String last, String company) {
        String person = ((first == null ? "" : first) + " " + (last == null ? "" : last)).strip();
        return person.isEmpty() ? company : person;
    }

    public void apply(LeadDetails d) {
        this.firstName = d.firstName();
        this.lastName = d.lastName();
        this.companyName = d.companyName();
        this.jobTitle = d.jobTitle();
        this.email = d.email();
        this.phone = d.phone();
        this.source = d.source();
        this.ownerId = d.ownerId();
        this.estimatedValue = d.estimatedValue();
        this.currency = d.currency();
        this.description = d.description();
        this.updatedAt = Instant.now();
    }

    /** Callers check the transition (LeadStatus.check) first. */
    public void changeStatus(LeadStatus next, String reason, Instant at) {
        this.status = next;
        this.disqualifyReason = next == LeadStatus.DISQUALIFIED ? reason : null;
        this.disqualifiedAt = next == LeadStatus.DISQUALIFIED ? at : null;
        this.updatedAt = at;
    }

    public void markConverted(UUID personId, UUID organizationId, UUID opportunityId, Instant at) {
        this.status = LeadStatus.CONVERTED;
        this.convertedPersonId = personId;
        this.convertedOrganizationId = organizationId;
        this.convertedOpportunityId = opportunityId;
        this.convertedAt = at;
        this.disqualifyReason = null;
        this.disqualifiedAt = null;
        this.updatedAt = at;
    }

    public String getName() {
        return displayName(firstName, lastName, companyName);
    }

    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public String getCompanyName() { return companyName; }
    public String getJobTitle() { return jobTitle; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public LeadSource getSource() { return source; }
    public LeadStatus getStatus() { return status; }
    public UUID getOwnerId() { return ownerId; }
    public BigDecimal getEstimatedValue() { return estimatedValue; }
    public String getCurrency() { return currency; }
    public String getDescription() { return description; }
    public String getDisqualifyReason() { return disqualifyReason; }
    public Instant getDisqualifiedAt() { return disqualifiedAt; }
    public Instant getConvertedAt() { return convertedAt; }
    public UUID getConvertedPersonId() { return convertedPersonId; }
    public UUID getConvertedOrganizationId() { return convertedOrganizationId; }
    public UUID getConvertedOpportunityId() { return convertedOpportunityId; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
```

(Format the one-line getters in the repository's usual multi-line style when you write the file.)

`crm/domain/LeadRepository.java`:

```java
package com.nexusops.crm.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface LeadRepository extends JpaRepository<Lead, UUID>, JpaSpecificationExecutor<Lead> {}
```

- [ ] **Step 8: LeadService and LeadSubjects**

`crm/LeadService.java`:

```java
package com.nexusops.crm;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.collaboration.MemberRef;
import com.nexusops.crm.domain.Lead;
import com.nexusops.crm.domain.LeadDetails;
import com.nexusops.crm.domain.LeadRepository;
import com.nexusops.directory.PartyBrief;
import com.nexusops.directory.PartyRef;
import com.nexusops.directory.PartyService;
import com.nexusops.identity.Members;
import com.nexusops.shared.Emails;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.Text;
import com.nexusops.shared.web.ApiProblem;
import com.nexusops.shared.web.PageResponse;
import com.nexusops.shared.web.Paging;
import com.nexusops.tenancy.TenantDirectory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Leads (D2, D3). Conversion (D4) and import (D11) live in their own services and reuse validate/find. */
@Service
public class LeadService {

    static final String NOT_FOUND = "Record not found.";
    static final String STALE = "This record was changed by someone else. Reload and try again.";
    static final String NOT_A_MEMBER = "Choose an active member of this workspace.";
    static final String NAME_REQUIRED = "Enter a name or a company.";
    private static final Set<LeadStatus> OPEN = Set.of(LeadStatus.NEW, LeadStatus.CONTACTED, LeadStatus.QUALIFIED);

    private final LeadRepository leads;
    private final Members members;
    private final PartyService parties;
    private final TenantDirectory tenants;
    private final AuditService audit;

    LeadService(LeadRepository leads, Members members, PartyService parties, TenantDirectory tenants,
            AuditService audit) {
        this.leads = leads;
        this.members = members;
        this.parties = parties;
        this.tenants = tenants;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public LeadView get(UUID id) {
        return view(find(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<LeadView> list(LeadQuery query, Integer page, Integer size) {
        TenantContext.requireTenantId();
        Set<LeadStatus> statuses = parseStatuses(query.status());
        Specification<Lead> spec = (root, cq, cb) -> root.get("status").in(statuses);
        String owner = query.owner() == null ? "" : query.owner().strip();
        if (owner.equals("me")) {
            UUID me = currentUser();
            spec = spec.and((root, cq, cb) -> cb.equal(root.get("ownerId"), me));
        } else if (owner.equals("unassigned")) {
            spec = spec.and((root, cq, cb) -> cb.isNull(root.get("ownerId")));
        } else if (!owner.isEmpty()) {
            UUID id = parseUuid(owner, "owner");
            spec = spec.and((root, cq, cb) -> cb.equal(root.get("ownerId"), id));
        }
        if (query.source() != null) {
            spec = spec.and((root, cq, cb) -> cb.equal(root.get("source"), query.source()));
        }
        if (query.partyId() != null) {
            UUID party = query.partyId();
            spec = spec.and((root, cq, cb) -> cb.or(cb.equal(root.get("convertedPersonId"), party),
                    cb.equal(root.get("convertedOrganizationId"), party)));
        }
        String q = Text.optional(query.q(), 100, "q");
        if (q != null) {
            String like = Text.containsPattern(q);
            spec = spec.and((root, cq, cb) -> cb.or(cb.like(cb.lower(root.get("firstName")), like, '\\'),
                    cb.like(cb.lower(root.get("lastName")), like, '\\'),
                    cb.like(cb.lower(root.get("companyName")), like, '\\'), cb.like(root.get("email"), like, '\\')));
        }
        Page<Lead> result = leads.findAll(spec,
                Paging.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"))));
        return new PageResponse<>(views(result.getContent()), result.getNumber(), result.getSize(),
                result.getTotalElements());
    }

    @Transactional
    public LeadView create(LeadCommand command) {
        TenantContext.requireTenantId();
        LeadDetails details = validate(command, null);
        if (details.ownerId() == null) {
            details = withOwner(details, currentUser());
        }
        Lead lead = new Lead(Ids.newId(), details, currentUser());
        leads.saveAndFlush(lead);
        audit.record(AuditEntry.of("LeadCreated", "Lead", lead.getId()).withAfter(snapshot(lead)));
        return view(lead);
    }

    @Transactional
    public LeadView update(UUID id, LeadCommand command, Long version) {
        Lead lead = find(id);
        checkVersion(lead, version);
        if (lead.getStatus() == LeadStatus.CONVERTED) {
            throw ApiProblem.conflict(LeadStatus.CONVERTED_FINAL);
        }
        LeadDetails details = validate(command, lead);
        Map<String, Object> before = snapshot(lead);
        lead.apply(details);
        leads.flush();
        audit.record(AuditEntry.of("LeadUpdated", "Lead", id).withBefore(before).withAfter(snapshot(lead)));
        return view(lead);
    }

    @Transactional
    public LeadView changeStatus(UUID id, LeadStatus status, String reason, Long version) {
        Lead lead = find(id);
        checkVersion(lead, version);
        if (status == null) {
            throw ApiProblem.badRequestField("status", "Choose a status.");
        }
        LeadStatus.check(lead.getStatus(), status);
        String why = status == LeadStatus.DISQUALIFIED ? Text.required(reason, 500, "reason") : null;
        if (lead.getStatus() != status || !Objects.equals(lead.getDisqualifyReason(), why)) {
            LeadStatus before = lead.getStatus();
            lead.changeStatus(status, why, Instant.now());
            leads.flush();
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("status", status.name());
            if (why != null) {
                after.put("reason", why);
            }
            audit.record(AuditEntry.of("LeadStatusChanged", "Lead", id).withBefore(Map.of("status", before.name()))
                    .withAfter(after));
        }
        return view(lead);
    }

    Lead find(UUID id) {
        TenantContext.requireTenantId();
        return leads.findById(id).orElseThrow(() -> ApiProblem.notFound(NOT_FOUND));
    }

    /** For conversion: the lead exists, the version matches, and it is open. */
    Lead requireConvertible(UUID id, Long version) {
        Lead lead = find(id);
        checkVersion(lead, version);
        if (lead.getStatus() == LeadStatus.CONVERTED) {
            throw ApiProblem.conflict(LeadStatus.CONVERTED_FINAL);
        }
        if (lead.getStatus() == LeadStatus.DISQUALIFIED) {
            throw ApiProblem.conflict("Reopen the lead before converting it.");
        }
        return lead;
    }

    /** {@code current} is null on create (and import); an unchanged owner isn't re-checked. */
    LeadDetails validate(LeadCommand command, Lead current) {
        String firstName = Text.optional(command.firstName(), 80, "firstName");
        String lastName = Text.optional(command.lastName(), 80, "lastName");
        String company = Text.optional(command.companyName(), 200, "companyName");
        if (firstName == null && lastName == null && company == null) {
            throw ApiProblem.badRequestField("lastName", NAME_REQUIRED);
        }
        String jobTitle = Text.optional(command.jobTitle(), 100, "jobTitle");
        String email = command.email() == null || command.email().isBlank() ? null : Emails.normalize(command.email());
        String phone = Text.optional(command.phone(), 40, "phone");
        if (phone != null) {
            phone = phone.replaceAll("\\s+", " ");
        }
        LeadSource source = command.source() == null ? LeadSource.OTHER : command.source();
        UUID ownerId = command.ownerId();
        if (ownerId != null && (current == null || !ownerId.equals(current.getOwnerId()))
                && members.findActive(ownerId).isEmpty()) {
            throw ApiProblem.badRequestField("ownerId", NOT_A_MEMBER);
        }
        BigDecimal value = Money.amount(command.estimatedValue(), "estimatedValue");
        String currency = value == null ? null : Money.currency(command.currency(), "currency", tenants);
        String description = Text.optional(command.description(), 5000, "description");
        return new LeadDetails(firstName, lastName, company, jobTitle, email, phone, source, ownerId, value, currency,
                description);
    }

    LeadView view(Lead lead) {
        return views(List.of(lead)).getFirst();
    }

    private List<LeadView> views(List<Lead> page) {
        Map<UUID, Members.Member> people = members.findAll(page.stream()
                .flatMap(l -> Stream.of(l.getOwnerId(), l.getCreatedBy())).filter(Objects::nonNull)
                .collect(Collectors.toSet()));
        Map<UUID, PartyBrief> partyNames = parties.briefs(page.stream()
                .flatMap(l -> Stream.of(l.getConvertedPersonId(), l.getConvertedOrganizationId()))
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        return page.stream().map(l -> new LeadView(l.getId(), l.getName(), l.getFirstName(), l.getLastName(),
                l.getCompanyName(), l.getJobTitle(), l.getEmail(), l.getPhone(), l.getSource(), l.getStatus(),
                member(people, l.getOwnerId()), l.getEstimatedValue(), l.getCurrency(), l.getDescription(),
                l.getDisqualifyReason(), l.getDisqualifiedAt(), l.getConvertedAt(),
                party(partyNames, l.getConvertedPersonId()), party(partyNames, l.getConvertedOrganizationId()),
                l.getConvertedOpportunityId(), member(people, l.getCreatedBy()), l.getCreatedAt(), l.getUpdatedAt(),
                l.getVersion())).toList();
    }

    static Map<String, Object> snapshot(Lead lead) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", lead.getName());
        values.put("status", lead.getStatus().name());
        values.put("source", lead.getSource().name());
        if (lead.getCompanyName() != null) values.put("companyName", lead.getCompanyName());
        if (lead.getOwnerId() != null) values.put("ownerId", lead.getOwnerId().toString());
        if (lead.getEstimatedValue() != null) {
            values.put("estimatedValue", lead.getEstimatedValue().toPlainString());
            values.put("currency", lead.getCurrency());
        }
        return values;
    }

    private static LeadDetails withOwner(LeadDetails d, UUID owner) {
        return new LeadDetails(d.firstName(), d.lastName(), d.companyName(), d.jobTitle(), d.email(), d.phone(),
                d.source(), owner, d.estimatedValue(), d.currency(), d.description());
    }

    private static void checkVersion(Lead lead, Long version) {
        if (version == null) {
            throw ApiProblem.badRequestField("version", "Reload the record and try again.");
        }
        if (lead.getVersion() != version) {
            throw ApiProblem.conflict(STALE);
        }
    }

    private static MemberRef member(Map<UUID, Members.Member> people, UUID id) {
        Members.Member m = id == null ? null : people.get(id);
        return m == null ? null : new MemberRef(m.id(), m.name());
    }

    private static PartyRef party(Map<UUID, PartyBrief> parties, UUID id) {
        PartyBrief p = id == null ? null : parties.get(id);
        return p == null ? null : new PartyRef(p.id(), p.name());
    }

    static UUID currentUser() {
        return TenantContext.userId().orElseThrow(() -> new IllegalStateException("No user bound"));
    }

    static UUID parseUuid(String raw, String field) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException invalid) {
            throw ApiProblem.badRequestField(field, "Use me, unassigned or a member id.");
        }
    }

    private static Set<LeadStatus> parseStatuses(String raw) {
        if (raw == null || raw.isBlank()) {
            return OPEN;
        }
        try {
            return Arrays.stream(raw.split(",")).map(s -> LeadStatus.valueOf(s.strip().toUpperCase(Locale.ROOT)))
                    .collect(Collectors.toSet());
        } catch (IllegalArgumentException invalid) {
            throw ApiProblem.badRequestField("status", "Use NEW, CONTACTED, QUALIFIED, DISQUALIFIED or CONVERTED.");
        }
    }
}
```

`crm/LeadSubjects.java`:

```java
package com.nexusops.crm;

import com.nexusops.collaboration.SubjectRef;
import com.nexusops.collaboration.SubjectResolver;
import com.nexusops.crm.domain.Lead;
import com.nexusops.crm.domain.LeadRepository;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Leads as collaboration subjects (type LEAD, D9). A converted lead is frozen: it reports archived. */
@Component
class LeadSubjects implements SubjectResolver {

    static final String TYPE = "LEAD";

    private final LeadRepository leads;

    LeadSubjects(LeadRepository leads) {
        this.leads = leads;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String readPermission() {
        return CrmPermissions.LEAD_READ;
    }

    @Override
    public Optional<SubjectRef> find(UUID id) {
        return leads.findById(id).map(LeadSubjects::ref);
    }

    @Override
    public Map<UUID, SubjectRef> findAll(Collection<UUID> ids) {
        return leads.findAllById(ids).stream().collect(Collectors.toMap(Lead::getId, LeadSubjects::ref));
    }

    static SubjectRef ref(Lead lead) {
        return new SubjectRef(TYPE, lead.getId(), lead.getName(), lead.getStatus() == LeadStatus.CONVERTED);
    }
}
```

- [ ] **Step 9: Web layer**

Add to `CrmDtos`:

```java
    record LeadRequest(String firstName, String lastName, String companyName, String jobTitle, String email,
            String phone, com.nexusops.crm.LeadSource source, UUID ownerId, java.math.BigDecimal estimatedValue,
            String currency, String description, Long version) {
        com.nexusops.crm.LeadCommand command() {
            return new com.nexusops.crm.LeadCommand(firstName, lastName, companyName, jobTitle, email, phone, source,
                    ownerId, estimatedValue, currency, description);
        }
    }

    record LeadStatusRequest(com.nexusops.crm.LeadStatus status, String reason, Long version) {}
```

(Use imports rather than fully-qualified names when you write the file.)

`crm/web/LeadController.java`:

```java
package com.nexusops.crm.web;

import com.nexusops.crm.LeadQuery;
import com.nexusops.crm.LeadService;
import com.nexusops.crm.LeadSource;
import com.nexusops.crm.LeadView;
import com.nexusops.crm.web.CrmDtos.LeadRequest;
import com.nexusops.crm.web.CrmDtos.LeadStatusRequest;
import com.nexusops.shared.web.PageResponse;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/leads")
class LeadController {

    private final LeadService leads;

    LeadController(LeadService leads) {
        this.leads = leads;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('crm.lead.read')")
    PageResponse<LeadView> list(@RequestParam(required = false) String q, @RequestParam(required = false) String status,
            @RequestParam(required = false) String owner, @RequestParam(required = false) LeadSource source,
            @RequestParam(required = false) UUID partyId, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return leads.list(new LeadQuery(q, status, owner, source, partyId), page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('crm.lead.read')")
    LeadView get(@PathVariable UUID id) {
        return leads.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('crm.lead.manage')")
    LeadView create(@RequestBody LeadRequest request) {
        return leads.create(request.command());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('crm.lead.manage')")
    LeadView update(@PathVariable UUID id, @RequestBody LeadRequest request) {
        return leads.update(id, request.command(), request.version());
    }

    @PostMapping("/{id}/status")
    @PreAuthorize("hasAuthority('crm.lead.manage')")
    LeadView status(@PathVariable UUID id, @RequestBody LeadStatusRequest request) {
        return leads.changeStatus(id, request.status(), request.reason(), request.version());
    }
}
```

An invalid `source` query value (e.g. `?source=X`) must be a 400, not a 500: check `GlobalExceptionHandler` already maps `MethodArgumentTypeMismatchException` to 400 (Phase 4's `?kind=` filter relies on it); if it doesn't, add a test case and the mapping.

`crm/web/OwnerController.java`:

```java
package com.nexusops.crm.web;

import com.nexusops.collaboration.AssigneeView;
import com.nexusops.identity.Members;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Members who can own leads and opportunities (first 20 active matches). */
@RestController
class OwnerController {

    private final Members members;

    OwnerController(Members members) {
        this.members = members;
    }

    @GetMapping("/api/v1/crm/owners")
    @PreAuthorize("hasAnyAuthority('crm.lead.manage', 'crm.opportunity.manage')")
    List<AssigneeView> owners(@RequestParam(required = false) String q) {
        return members.searchActive(q, 20).stream().map(m -> new AssigneeView(m.id(), m.name(), m.email())).toList();
    }
}
```

If `AssigneeView`'s constructor isn't public, make it public (it is a record in `collaboration`'s top-level package, so it is visible to `crm`).

- [ ] **Step 10: Run the tests to verify they pass**

Run: `cd backend && ./gradlew test --tests '*LeadApiIT' --tests '*LeadStatusRulesTest' --tests '*ModularityTest' --tests '*EndpointAuthorizationCoverageTest' --tests '*RlsCoverageIT'`
Expected: `RlsCoverageIT.everyNonGlobalTableForcesRlsAndHasAPolicy` PASS; the others PASS. (Task 8 adds the new tables to `EXPECTED_TENANT_TABLES`.)

- [ ] **Step 11: Run the whole backend suite, then commit**

Run: `cd backend && ./gradlew test` — Expected: PASS.

```bash
git add backend/src/main/resources/db/migration/V16__leads.sql backend/src/main/java/com/nexusops/directory \
  backend/src/main/java/com/nexusops/crm backend/src/test/java/com/nexusops/crm
git commit -m "feat(crm): leads with owners, status rules and record panels"
```

---
### Task 3: Opportunities, stage moves, the pipeline board, and won deals make customers

**Files:**
- Create: `backend/src/main/resources/db/migration/V17__opportunities.sql`
- Modify: `backend/src/main/java/com/nexusops/directory/PartyService.java` (+ `ensureCustomer`)
- Create: `backend/src/main/java/com/nexusops/crm/OpportunityStatus.java`, `OpportunityCommand.java`, `OpportunityQuery.java`, `OpportunityView.java`, `OpportunitySummary.java`, `BoardView.java`, `OpportunityService.java`, `OpportunitySubjects.java`
- Create: `backend/src/main/java/com/nexusops/crm/domain/Opportunity.java`, `OpportunityDetails.java`, `OpportunityRepository.java`
- Create: `backend/src/main/java/com/nexusops/crm/web/OpportunityController.java`, `BoardController.java`; Modify: `crm/web/CrmDtos.java`
- Test: `backend/src/test/java/com/nexusops/crm/OpportunityApiIT.java`

**Interfaces:**
- Consumes: `PipelineService.require/firstOpen/ordered/ref/view`, `StageKind`, `StageRef`, `StageView` (Task 1); `Money`, `MoneyTotal`, `LeadService.currentUser/parseUuid`, `PartyService.briefs`, `PartyBrief` (Task 2).
- Produces:
  - `PartyService.ensureCustomer(UUID partyId)` — public, no user-permission check, idempotent, skips archived parties, audits `PartyRoleChanged` only when it changes something.
  - `enum OpportunityStatus {OPEN, WON, LOST}` (= the stage's kind).
  - `OpportunityCommand(String name, UUID accountId, UUID contactId, UUID stageId, BigDecimal amount, String currency, LocalDate expectedCloseOn, UUID ownerId, String description)` — `stageId` is used on create only (null → first open stage; must be an OPEN stage); PUT ignores it.
  - `OpportunityView(UUID id, String name, PartyRef account, PartyRef contact, StageRef stage, OpportunityStatus status, BigDecimal amount, String currency, LocalDate expectedCloseOn, MemberRef owner, UUID leadId, String description, String lostReason, Instant closedAt, MemberRef createdBy, Instant createdAt, Instant updatedAt, long version)`.
  - `OpportunitySummary(UUID id, String name, PartyRef account, StageRef stage, BigDecimal amount, String currency, LocalDate expectedCloseOn, MemberRef owner, long version)`.
  - `BoardView(List<BoardColumn> columns)`, nested `BoardView.BoardColumn(StageView stage, long count, List<MoneyTotal> totals, List<MoneyTotal> weighted, List<OpportunitySummary> opportunities)`.
  - `OpportunityService` public `get`, `list(OpportunityQuery, Integer, Integer)`, `create(OpportunityCommand)`, `update(UUID, OpportunityCommand, Long)`, `moveStage(UUID, UUID stageId, String lostReason, Long version)`, `board(String owner)`; package-private `Opportunity open(OpportunityCommand command, UUID leadId)` (Task 4) and `List<OpportunitySummary> summaries(List<Opportunity>)` (Task 6).
  - `Opportunity` getters: `getName() getAccountId() getContactId() getStageId() getAmount() getCurrency() getExpectedCloseOn() getOwnerId() getLeadId() getDescription() getLostReason() getClosedAt() getCreatedBy() getCreatedAt() getUpdatedAt() getVersion()`.
  - `OpportunitySubjects` type `"OPPORTUNITY"`.
  - Route `GET /api/v1/crm/pipeline/board?owner=me|all`.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/nexusops/crm/OpportunityApiIT.java`:

```java
package com.nexusops.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestCrm;
import com.nexusops.support.TestMembers;
import com.nexusops.support.TestRoles;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Workspace;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class OpportunityApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;
    UUID acme, grace;
    List<String> stages; // Prospecting, Qualification, Proposal, Negotiation, Won, Lost

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("opp"));
        owner = Api.login(mvc, ws);
        TestCrm.enable(owner);
        acme = Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Acme\"}"));
        grace = Api.id(owner.post("/api/v1/persons", "{\"firstName\":\"Grace\",\"organizationId\":\"" + acme + "\"}"));
        stages = Api.read(owner.get("/api/v1/crm/pipeline/stages"), "$[*].id");
    }

    private UUID deal(String extra) throws Exception {
        return Api.id(owner.post("/api/v1/opportunities", "{\"name\":\"Deal\",\"accountId\":\"" + acme + "\"" + extra + "}")
                .andExpect(status().isCreated()));
    }

    private String move(UUID id, String stage, long version, String reason) {
        return "{\"stageId\":\"" + stage + "\",\"version\":" + version
                + (reason == null ? "" : ",\"lostReason\":\"" + reason + "\"") + "}";
    }

    @Test
    void createsInTheFirstOpenStage() throws Exception {
        owner.post("/api/v1/opportunities", "{\"name\":\" Packaging order \",\"accountId\":\"" + acme
                        + "\",\"contactId\":\"" + grace + "\",\"amount\":120000,\"expectedCloseOn\":\"2026-11-30\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Packaging order"))
                .andExpect(jsonPath("$.account.name").value("Acme"))
                .andExpect(jsonPath("$.contact.name").value("Grace"))
                .andExpect(jsonPath("$.stage.name").value("Prospecting"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.owner").exists())
                .andExpect(jsonPath("$.closedAt").doesNotExist());
    }

    @Test
    void invalidOpportunitiesAreFieldErrors() throws Exception {
        String[][] cases = {
                {"{\"name\":\" \",\"accountId\":\"" + acme + "\"}", "name"},
                {"{\"name\":\"D\"}", "accountId"},
                {"{\"name\":\"D\",\"accountId\":\"" + UUID.randomUUID() + "\"}", "accountId"},
                {"{\"name\":\"D\",\"accountId\":\"" + acme + "\",\"contactId\":\"" + acme + "\"}", "contactId"},
                {"{\"name\":\"D\",\"accountId\":\"" + acme + "\",\"stageId\":\"" + stages.get(4) + "\"}", "stageId"},
                {"{\"name\":\"D\",\"accountId\":\"" + acme + "\",\"stageId\":\"" + UUID.randomUUID() + "\"}", "stageId"},
                {"{\"name\":\"D\",\"accountId\":\"" + acme + "\",\"amount\":-5}", "amount"},
                {"{\"name\":\"D\",\"accountId\":\"" + acme + "\",\"ownerId\":\"" + UUID.randomUUID() + "\"}", "ownerId"},
        };
        for (String[] c : cases) {
            owner.post("/api/v1/opportunities", c[0]).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value(c[1]));
        }
        owner.post("/api/v1/parties/" + acme + "/archive", "").andExpect(status().isOk());
        owner.post("/api/v1/opportunities", "{\"name\":\"D\",\"accountId\":\"" + acme + "\"}")
                .andExpect(status().isConflict());
    }

    @Test
    void movesThroughTheStagesAndAWinMakesTheAccountACustomer() throws Exception {
        UUID id = deal(",\"amount\":10");
        owner.post("/api/v1/opportunities/" + id + "/stage", move(id, stages.get(2), 0, null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.stage.name").value("Proposal")).andExpect(jsonPath("$.closedAt").doesNotExist());
        owner.post("/api/v1/opportunities/" + id + "/stage", move(id, stages.get(4), 1, null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WON")).andExpect(jsonPath("$.closedAt").exists());
        owner.get("/api/v1/parties/" + acme).andExpect(jsonPath("$.roles[?(@.role == 'CUSTOMER')].status")
                .value(Matchers.contains("ACTIVE")));
        owner.post("/api/v1/opportunities/" + id + "/stage", move(id, stages.get(5), 2, null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("lostReason"));
        owner.post("/api/v1/opportunities/" + id + "/stage", move(id, stages.get(5), 2, "Chose a competitor"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("LOST"))
                .andExpect(jsonPath("$.lostReason").value("Chose a competitor"));
        owner.post("/api/v1/opportunities/" + id + "/stage", move(id, stages.get(0), 3, null))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.lostReason").doesNotExist()).andExpect(jsonPath("$.closedAt").doesNotExist());
        owner.post("/api/v1/opportunities/" + id + "/stage", move(id, stages.get(1), 0, null))
                .andExpect(status().isConflict());
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select count(*) from audit_events where action = 'OpportunityStageChanged'", Long.class)).isEqualTo(4);
        // a second win doesn't touch the already-active customer role again
        long roleChanges = OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select count(*) from audit_events where action = 'PartyRoleChanged'", Long.class);
        owner.post("/api/v1/opportunities/" + id + "/stage", move(id, stages.get(4), 4, null)).andExpect(status().isOk());
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select count(*) from audit_events where action = 'PartyRoleChanged'", Long.class)).isEqualTo(roleChanges);
    }

    @Test
    void movingToAnUnknownStageIsAFieldError() throws Exception {
        UUID id = deal("");
        UUID demo = Api.id(owner.post("/api/v1/crm/pipeline/stages", "{\"name\":\"Demo\",\"probability\":30}"));
        owner.delete("/api/v1/crm/pipeline/stages/" + demo).andExpect(status().isNoContent());
        owner.post("/api/v1/opportunities/" + id + "/stage", move(id, demo.toString(), 0, null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("stageId"));
    }

    @Test
    void aStageWithOpportunitiesCannotBeDeleted() throws Exception {
        deal("");
        owner.delete("/api/v1/crm/pipeline/stages/" + stages.get(0)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Move this stage's opportunities first."));
    }

    @Test
    void anArchivedAccountDoesNotBlockEditingOrMoving() throws Exception {
        UUID id = deal("");
        owner.post("/api/v1/parties/" + acme + "/archive", "").andExpect(status().isOk());
        owner.put("/api/v1/opportunities/" + id, "{\"name\":\"Renamed\",\"accountId\":\"" + acme + "\",\"version\":0}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Renamed"));
        owner.post("/api/v1/opportunities/" + id + "/stage", move(id, stages.get(4), 1, null)).andExpect(status().isOk());
        // archived parties are not made customers
        owner.get("/api/v1/parties/" + acme).andExpect(jsonPath("$.roles", Matchers.empty()));
    }

    @Test
    void editsWithTheCurrentVersionAndKeepTheStage() throws Exception {
        UUID id = deal(",\"amount\":10");
        owner.put("/api/v1/opportunities/" + id, "{\"name\":\"Bigger\",\"accountId\":\"" + acme + "\",\"amount\":20,"
                + "\"currency\":\"eur\",\"stageId\":\"" + stages.get(3) + "\",\"version\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.stage.name").value("Prospecting"));
        owner.put("/api/v1/opportunities/" + id, "{\"name\":\"X\",\"accountId\":\"" + acme + "\",\"version\":0}")
                .andExpect(status().isConflict());
    }

    @Test
    void boardTotalsArePerCurrency() throws Exception {
        deal(",\"amount\":100");
        deal(",\"amount\":50,\"currency\":\"EUR\"");
        deal(",\"amount\":25");
        deal("");
        UUID won = deal(",\"amount\":7");
        owner.post("/api/v1/opportunities/" + won + "/stage", move(won, stages.get(4), 0, null)).andExpect(status().isOk());
        owner.get("/api/v1/crm/pipeline/board").andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[*].stage.name").value(Matchers.contains("Prospecting", "Qualification",
                        "Proposal", "Negotiation", "Won", "Lost")))
                .andExpect(jsonPath("$.columns[0].count").value(4))
                .andExpect(jsonPath("$.columns[0].opportunities.length()").value(4))
                .andExpect(jsonPath("$.columns[0].totals[?(@.currency == 'USD')].amount").value(Matchers.contains(125.0)))
                .andExpect(jsonPath("$.columns[0].totals[?(@.currency == 'EUR')].amount").value(Matchers.contains(50.0)))
                .andExpect(jsonPath("$.columns[0].weighted[?(@.currency == 'USD')].amount").value(Matchers.contains(12.5)))
                .andExpect(jsonPath("$.columns[4].count").value(1))
                .andExpect(jsonPath("$.columns[1].count").value(0));
        owner.get("/api/v1/crm/pipeline/board?owner=me").andExpect(jsonPath("$.columns[0].count").value(4));
        owner.get("/api/v1/crm/pipeline/board?owner=someone").andExpect(status().isBadRequest());
    }

    @Test
    void listsAndFilters() throws Exception {
        UUID a = deal("");
        UUID b = Api.id(owner.post("/api/v1/opportunities", "{\"name\":\"Spices contract\",\"accountId\":\"" + grace + "\"}"));
        owner.post("/api/v1/opportunities/" + a + "/stage", move(a, stages.get(4), 0, null)).andExpect(status().isOk());
        owner.get("/api/v1/opportunities").andExpect(jsonPath("$.total").value(2));
        owner.get("/api/v1/opportunities?status=WON").andExpect(jsonPath("$.items[*].id").value(Matchers.contains(a.toString())));
        owner.get("/api/v1/opportunities?q=spices").andExpect(jsonPath("$.items[*].id").value(Matchers.contains(b.toString())));
        owner.get("/api/v1/opportunities?accountId=" + grace).andExpect(jsonPath("$.total").value(1));
        owner.get("/api/v1/opportunities?stageId=" + stages.get(0)).andExpect(jsonPath("$.total").value(1));
        owner.get("/api/v1/opportunities?status=MAYBE").andExpect(status().isBadRequest());
    }

    @Test
    void opportunitiesTakeActivitiesAndPermissionsAreEnforced() throws Exception {
        UUID id = deal("");
        owner.post("/api/v1/activities", "{\"subjectType\":\"OPPORTUNITY\",\"subjectId\":\"" + id
                + "\",\"type\":\"CALL\",\"summary\":\"Pricing call\"}").andExpect(status().isCreated());
        UUID readerRole = TestRoles.create(mvc, owner.session(), "Deal reader", "crm.opportunity.read");
        Api reader = Api.login(mvc, members.create(ws.tenantId(), Set.of(readerRole)));
        reader.get("/api/v1/opportunities/" + id).andExpect(status().isOk());
        reader.post("/api/v1/opportunities", "{\"name\":\"D\",\"accountId\":\"" + acme + "\"}").andExpect(status().isForbidden());
        UUID blindRole = TestRoles.create(mvc, owner.session(), "Deal manager without directory",
                "crm.opportunity.read", "crm.opportunity.manage");
        Api blind = Api.login(mvc, members.create(ws.tenantId(), Set.of(blindRole)));
        blind.post("/api/v1/opportunities", "{\"name\":\"D\",\"accountId\":\"" + acme + "\"}").andExpect(status().isForbidden());
        blind.post("/api/v1/opportunities/" + id + "/stage", move(id, stages.get(1), 0, null)).andExpect(status().isOk());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./gradlew test --tests '*OpportunityApiIT'`
Expected: FAIL — 404 on `/api/v1/opportunities`.

- [ ] **Step 3: Migration V17**

`backend/src/main/resources/db/migration/V17__opportunities.sql`:

```sql
-- Opportunities (D6). Status is the stage's kind; closed_at is set while the stage is WON or LOST.
CREATE TABLE opportunities (
    id                 uuid PRIMARY KEY,
    tenant_id          uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    name               text NOT NULL CHECK (name = btrim(name) AND length(name) BETWEEN 1 AND 200),
    account_id         uuid NOT NULL,
    contact_id         uuid,
    stage_id           uuid NOT NULL,
    amount             numeric(19, 4) CHECK (amount IS NULL OR amount >= 0),
    currency           text CHECK (currency IS NULL OR currency ~ '^[A-Z]{3}$'),
    expected_close_on  date,
    owner_id           uuid REFERENCES users (id) ON DELETE SET NULL,
    lead_id            uuid,
    description        text CHECK (description IS NULL OR length(description) <= 5000),
    lost_reason        text CHECK (lost_reason IS NULL OR length(lost_reason) BETWEEN 1 AND 500),
    closed_at          timestamptz,
    created_by         uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL,
    version            bigint NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    CHECK ((amount IS NULL) = (currency IS NULL)),
    CHECK (lost_reason IS NULL OR closed_at IS NOT NULL),
    FOREIGN KEY (tenant_id, account_id) REFERENCES parties (tenant_id, id),
    FOREIGN KEY (tenant_id, contact_id) REFERENCES parties (tenant_id, id),
    FOREIGN KEY (tenant_id, stage_id) REFERENCES pipeline_stages (tenant_id, id),
    FOREIGN KEY (tenant_id, lead_id) REFERENCES leads (tenant_id, id)
);
CREATE INDEX opportunities_stage_idx ON opportunities (tenant_id, stage_id);
CREATE INDEX opportunities_account_idx ON opportunities (tenant_id, account_id);
CREATE INDEX opportunities_contact_idx ON opportunities (tenant_id, contact_id);
CREATE INDEX opportunities_close_idx ON opportunities (tenant_id, expected_close_on);
CREATE INDEX opportunities_owner_idx ON opportunities (tenant_id, owner_id);

ALTER TABLE opportunities ENABLE ROW LEVEL SECURITY;
ALTER TABLE opportunities FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON opportunities
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE leads ADD FOREIGN KEY (tenant_id, converted_opportunity_id) REFERENCES opportunities (tenant_id, id);
```

- [ ] **Step 4: `PartyService.ensureCustomer`**

Add to `PartyService` (imports `java.time.LocalDate` not needed; `RoleStatus`, `PartyRoleType`, `PartyRole` are already imported):

```java
    /**
     * CRM's business rule (spec D7): converting a lead and winning a deal make the party a customer. Not a user action,
     * so no permission check here (callers hold their own). Idempotent; archived parties are left alone.
     */
    @Transactional
    public void ensureCustomer(UUID id) {
        Party party = find(id);
        if (party.isArchived()) {
            return;
        }
        locks.lock(ROLES_LOCK);
        PartyRole row = roles.findByPartyIdAndRole(id, PartyRoleType.CUSTOMER).orElse(null);
        if (row != null && row.getStatus() == RoleStatus.ACTIVE) {
            return;
        }
        Map<String, Object> before = row == null ? null : roleSnapshot(row);
        if (row == null) {
            row = new PartyRole(Ids.newId(), id, PartyRoleType.CUSTOMER);
        }
        row.update(RoleStatus.ACTIVE, row.getSince(), null);
        roles.saveAndFlush(row);
        audit.record(AuditEntry.of("PartyRoleChanged", "Party", id).withBefore(before).withAfter(roleSnapshot(row)));
    }
```

- [ ] **Step 5: Opportunity types, entity and repository**

```java
package com.nexusops.crm;

/** The kind of the opportunity's current stage. */
public enum OpportunityStatus {
    OPEN, WON, LOST
}
```

```java
package com.nexusops.crm;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Raw input. stageId: create only (null = first open stage); PUT keeps the stage (moves use the /stage route).
 * ownerId null means the creator on create, unassigned on update. */
public record OpportunityCommand(String name, UUID accountId, UUID contactId, UUID stageId, BigDecimal amount,
        String currency, LocalDate expectedCloseOn, UUID ownerId, String description) {}
```

```java
package com.nexusops.crm;

import java.util.UUID;

public record OpportunityQuery(String q, OpportunityStatus status, UUID stageId, UUID accountId, UUID contactId,
        String owner) {}
```

```java
package com.nexusops.crm;

import com.nexusops.collaboration.MemberRef;
import com.nexusops.directory.PartyRef;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record OpportunityView(UUID id, String name, PartyRef account, PartyRef contact, StageRef stage,
        OpportunityStatus status, BigDecimal amount, String currency, LocalDate expectedCloseOn, MemberRef owner,
        UUID leadId, String description, String lostReason, Instant closedAt, MemberRef createdBy, Instant createdAt,
        Instant updatedAt, long version) {}
```

```java
package com.nexusops.crm;

import com.nexusops.collaboration.MemberRef;
import com.nexusops.directory.PartyRef;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** A board card / dashboard row. */
public record OpportunitySummary(UUID id, String name, PartyRef account, StageRef stage, BigDecimal amount,
        String currency, LocalDate expectedCloseOn, MemberRef owner, long version) {}
```

```java
package com.nexusops.crm;

import java.util.List;

/** One column per stage, in board order. Won and Lost columns hold deals closed in the last 30 days. */
public record BoardView(List<BoardColumn> columns) {

    /** {@code opportunities}: the first 50, soonest expected close first; {@code count} and totals cover all. */
    public record BoardColumn(StageView stage, long count, List<MoneyTotal> totals, List<MoneyTotal> weighted,
            List<OpportunitySummary> opportunities) {}
}
```

`crm/domain/OpportunityDetails.java`:

```java
package com.nexusops.crm.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Validated opportunity fields except the stage (OpportunityService builds these). */
public record OpportunityDetails(String name, UUID accountId, UUID contactId, BigDecimal amount, String currency,
        LocalDate expectedCloseOn, UUID ownerId, String description) {}
```

`crm/domain/Opportunity.java`:

```java
package com.nexusops.crm.domain;

import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "opportunities")
public class Opportunity extends TenantOwnedEntity {

    @Column(nullable = false)
    private String name;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "contact_id")
    private UUID contactId;

    @Column(name = "stage_id", nullable = false)
    private UUID stageId;

    @Column(precision = 19, scale = 4)
    private BigDecimal amount;

    private String currency;

    @Column(name = "expected_close_on")
    private LocalDate expectedCloseOn;

    @Column(name = "owner_id")
    private UUID ownerId;

    @Column(name = "lead_id", updatable = false)
    private UUID leadId;

    private String description;

    @Column(name = "lost_reason")
    private String lostReason;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Opportunity() {}

    public Opportunity(UUID id, OpportunityDetails details, UUID stageId, UUID leadId, UUID createdBy) {
        super(id);
        this.stageId = stageId;
        this.leadId = leadId;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        apply(details);
    }

    public void apply(OpportunityDetails d) {
        this.name = d.name();
        this.accountId = d.accountId();
        this.contactId = d.contactId();
        this.amount = d.amount();
        this.currency = d.currency();
        this.expectedCloseOn = d.expectedCloseOn();
        this.ownerId = d.ownerId();
        this.description = d.description();
        this.updatedAt = Instant.now();
    }

    /** {@code closed} is null for an open stage; {@code reason} only for the Lost stage. */
    public void moveTo(UUID stage, Instant closed, String reason) {
        this.stageId = stage;
        this.closedAt = closed;
        this.lostReason = reason;
        this.updatedAt = Instant.now();
    }

    public String getName() {
        return name;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public UUID getContactId() {
        return contactId;
    }

    public UUID getStageId() {
        return stageId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public LocalDate getExpectedCloseOn() {
        return expectedCloseOn;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public UUID getLeadId() {
        return leadId;
    }

    public String getDescription() {
        return description;
    }

    public String getLostReason() {
        return lostReason;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
```

`crm/domain/OpportunityRepository.java`:

```java
package com.nexusops.crm.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface OpportunityRepository extends JpaRepository<Opportunity, UUID>, JpaSpecificationExecutor<Opportunity> {}
```

- [ ] **Step 6: OpportunityService and OpportunitySubjects**

`crm/OpportunityService.java`:

```java
package com.nexusops.crm;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.collaboration.MemberRef;
import com.nexusops.crm.BoardView.BoardColumn;
import com.nexusops.crm.domain.Opportunity;
import com.nexusops.crm.domain.OpportunityDetails;
import com.nexusops.crm.domain.OpportunityRepository;
import com.nexusops.crm.domain.PipelineStage;
import com.nexusops.directory.DirectoryPermissions;
import com.nexusops.directory.PartyBrief;
import com.nexusops.directory.PartyKind;
import com.nexusops.directory.PartyRef;
import com.nexusops.directory.PartyService;
import com.nexusops.identity.Members;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.Text;
import com.nexusops.shared.security.CurrentAuthorities;
import com.nexusops.shared.web.ApiProblem;
import com.nexusops.shared.web.PageResponse;
import com.nexusops.shared.web.Paging;
import com.nexusops.tenancy.TenantDirectory;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Opportunities (D6) and the pipeline board. Won deals make their account a customer (D7). */
@Service
public class OpportunityService {

    static final String NOT_FOUND = "Record not found.";
    static final String STALE = "This record was changed by someone else. Reload and try again.";
    static final String FORBIDDEN = "You do not have permission to perform this action.";
    static final String NOT_A_MEMBER = "Choose an active member of this workspace.";
    static final String UNKNOWN_PARTY = "Choose a person or organization in this workspace.";
    static final String NOT_A_PERSON = "Choose a person.";
    static final String ARCHIVED = "This record is archived.";
    static final String OPEN_STAGE_ONLY = "Create opportunities in an open stage, then move them.";
    private static final int BOARD_LIMIT = 50;
    private static final Duration RECENTLY_CLOSED = Duration.ofDays(30);
    private static final Sort CARD_ORDER = Sort.by(Sort.Order.asc("expectedCloseOn").nullsLast(),
            Sort.Order.desc("createdAt"), Sort.Order.asc("id"));

    private final OpportunityRepository opportunities;
    private final PipelineService pipeline;
    private final PartyService parties;
    private final Members members;
    private final TenantDirectory tenants;
    private final AuditService audit;
    private final NamedParameterJdbcTemplate jdbc;

    OpportunityService(OpportunityRepository opportunities, PipelineService pipeline, PartyService parties,
            Members members, TenantDirectory tenants, AuditService audit, NamedParameterJdbcTemplate jdbc) {
        this.opportunities = opportunities;
        this.pipeline = pipeline;
        this.parties = parties;
        this.members = members;
        this.tenants = tenants;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public OpportunityView get(UUID id) {
        return views(List.of(find(id))).getFirst();
    }

    @Transactional(readOnly = true)
    public PageResponse<OpportunityView> list(OpportunityQuery query, Integer page, Integer size) {
        TenantContext.requireTenantId();
        Specification<Opportunity> spec = ownerFilter(query.owner());
        if (query.status() != null) {
            Set<UUID> ofKind = pipeline.ordered().stream()
                    .filter(s -> s.getKind().name().equals(query.status().name()))
                    .map(PipelineStage::getId).collect(Collectors.toSet());
            spec = spec.and((root, cq, cb) -> root.get("stageId").in(ofKind));
        }
        if (query.stageId() != null) {
            spec = spec.and((root, cq, cb) -> cb.equal(root.get("stageId"), query.stageId()));
        }
        if (query.accountId() != null) {
            spec = spec.and((root, cq, cb) -> cb.equal(root.get("accountId"), query.accountId()));
        }
        if (query.contactId() != null) {
            spec = spec.and((root, cq, cb) -> cb.equal(root.get("contactId"), query.contactId()));
        }
        String q = Text.optional(query.q(), 100, "q");
        if (q != null) {
            String like = Text.containsPattern(q);
            spec = spec.and((root, cq, cb) -> cb.like(cb.lower(root.get("name")), like, '\\'));
        }
        Page<Opportunity> result = opportunities.findAll(spec,
                Paging.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"))));
        return new PageResponse<>(views(result.getContent()), result.getNumber(), result.getSize(),
                result.getTotalElements());
    }

    @Transactional
    public OpportunityView create(OpportunityCommand command) {
        return get(open(command, null).getId());
    }

    /** Creates an opportunity in an open stage; {@code leadId} when converting a lead (Task 4). */
    @Transactional
    Opportunity open(OpportunityCommand command, UUID leadId) {
        TenantContext.requireTenantId();
        OpportunityDetails details = validate(command, null);
        if (details.ownerId() == null) {
            details = withOwner(details, LeadService.currentUser());
        }
        PipelineStage stage = command.stageId() == null ? pipeline.firstOpen()
                : pipeline.require(command.stageId(), "stageId");
        if (stage.getKind() != StageKind.OPEN) {
            throw ApiProblem.badRequestField("stageId", OPEN_STAGE_ONLY);
        }
        Opportunity opportunity = new Opportunity(Ids.newId(), details, stage.getId(), leadId, LeadService.currentUser());
        opportunities.saveAndFlush(opportunity);
        audit.record(AuditEntry.of("OpportunityCreated", "Opportunity", opportunity.getId())
                .withAfter(snapshot(opportunity, stage)));
        return opportunity;
    }

    @Transactional
    public OpportunityView update(UUID id, OpportunityCommand command, Long version) {
        Opportunity opportunity = find(id);
        checkVersion(opportunity, version);
        OpportunityDetails details = validate(command, opportunity);
        PipelineStage stage = pipeline.require(opportunity.getStageId(), "stageId");
        Map<String, Object> before = snapshot(opportunity, stage);
        opportunity.apply(details);
        opportunities.flush();
        audit.record(AuditEntry.of("OpportunityUpdated", "Opportunity", id).withBefore(before)
                .withAfter(snapshot(opportunity, stage)));
        return get(id);
    }

    @Transactional
    public OpportunityView moveStage(UUID id, UUID stageId, String lostReason, Long version) {
        Opportunity opportunity = find(id);
        checkVersion(opportunity, version);
        PipelineStage target = pipeline.require(stageId, "stageId");
        if (target.getId().equals(opportunity.getStageId())) {
            return get(id);
        }
        PipelineStage current = pipeline.require(opportunity.getStageId(), "stageId");
        String reason = target.getKind() == StageKind.LOST ? Text.required(lostReason, 500, "lostReason") : null;
        Instant closed = target.getKind() == StageKind.OPEN ? null
                : current.getKind() == target.getKind() ? opportunity.getClosedAt() : Instant.now();
        Map<String, Object> before = Map.of("stageId", current.getId().toString(), "stage", current.getName());
        opportunity.moveTo(target.getId(), closed, reason);
        opportunities.flush();
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("stageId", target.getId().toString());
        after.put("stage", target.getName());
        if (reason != null) {
            after.put("lostReason", reason);
        }
        audit.record(AuditEntry.of("OpportunityStageChanged", "Opportunity", id).withBefore(before).withAfter(after));
        if (target.getKind() == StageKind.WON) {
            parties.ensureCustomer(opportunity.getAccountId());
        }
        return get(id);
    }

    @Transactional(readOnly = true)
    public BoardView board(String owner) {
        UUID tenant = TenantContext.requireTenantId();
        UUID ownerId = boardOwner(owner);
        Instant since = Instant.now().minus(RECENTLY_CLOSED);
        Map<UUID, List<Object[]>> sums = stageSums(tenant, ownerId, since);
        List<BoardColumn> columns = new ArrayList<>();
        for (PipelineStage stage : pipeline.ordered()) {
            Specification<Opportunity> spec = (root, cq, cb) -> cb.equal(root.get("stageId"), stage.getId());
            if (ownerId != null) {
                spec = spec.and((root, cq, cb) -> cb.equal(root.get("ownerId"), ownerId));
            }
            if (stage.getKind() != StageKind.OPEN) {
                spec = spec.and((root, cq, cb) -> cb.greaterThanOrEqualTo(root.get("closedAt"), since));
            }
            List<Opportunity> cards = opportunities.findAll(spec, PageRequest.of(0, BOARD_LIMIT, CARD_ORDER)).getContent();
            List<Object[]> rows = sums.getOrDefault(stage.getId(), List.of());
            long count = rows.stream().mapToLong(r -> (Long) r[1]).sum();
            List<MoneyTotal> totals = rows.stream().filter(r -> r[0] != null)
                    .map(r -> new MoneyTotal((String) r[0], (BigDecimal) r[2])).toList();
            List<MoneyTotal> weighted = totals.stream().map(t -> new MoneyTotal(t.currency(),
                    t.amount().multiply(BigDecimal.valueOf(stage.getProbability()))
                            .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP))).toList();
            columns.add(new BoardColumn(PipelineService.view(stage), count, totals, weighted, summaries(cards)));
        }
        return new BoardView(columns);
    }

    Opportunity find(UUID id) {
        TenantContext.requireTenantId();
        return opportunities.findById(id).orElseThrow(() -> ApiProblem.notFound(NOT_FOUND));
    }

    List<OpportunitySummary> summaries(List<Opportunity> list) {
        Map<UUID, PartyBrief> partyNames = parties.briefs(list.stream().map(Opportunity::getAccountId)
                .collect(Collectors.toSet()));
        Map<UUID, Members.Member> people = members.findAll(list.stream().map(Opportunity::getOwnerId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<UUID, PipelineStage> stages = stagesById();
        return list.stream().map(o -> new OpportunitySummary(o.getId(), o.getName(), party(partyNames, o.getAccountId()),
                PipelineService.ref(stages.get(o.getStageId())), o.getAmount(), o.getCurrency(), o.getExpectedCloseOn(),
                member(people, o.getOwnerId()), o.getVersion())).toList();
    }

    /** Rows of [currency, count, sum] per stage; open stages count everything, closed ones since {@code since}. */
    private Map<UUID, List<Object[]>> stageSums(UUID tenant, UUID ownerId, Instant since) {
        String sql = """
                select o.stage_id, o.currency, count(*) as n, sum(o.amount) as total
                from opportunities o join pipeline_stages s on s.id = o.stage_id and s.tenant_id = o.tenant_id
                where o.tenant_id = :tenant and (s.kind = 'OPEN' or o.closed_at >= :since)
                """ + (ownerId == null ? "" : " and o.owner_id = :owner") + " group by o.stage_id, o.currency";
        MapSqlParameterSource params = new MapSqlParameterSource("tenant", tenant)
                .addValue("since", java.sql.Timestamp.from(since)).addValue("owner", ownerId);
        Map<UUID, List<Object[]>> result = new HashMap<>();
        jdbc.query(sql, params, rs -> {
            result.computeIfAbsent(rs.getObject("stage_id", UUID.class), k -> new ArrayList<>())
                    .add(new Object[] {rs.getString("currency"), rs.getLong("n"), rs.getBigDecimal("total")});
        });
        return result;
    }

    /** {@code current} null on create. Unchanged parties and owners aren't re-checked (they may since be archived or
     * disabled, which mustn't block editing the rest). Referencing a party needs directory.party.read. */
    private OpportunityDetails validate(OpportunityCommand command, Opportunity current) {
        String name = Text.required(command.name(), 200, "name").replaceAll("\\s+", " ");
        UUID accountId = command.accountId();
        if (accountId == null) {
            throw ApiProblem.badRequestField("accountId", UNKNOWN_PARTY);
        }
        if (current == null || !accountId.equals(current.getAccountId())) {
            requireParty(accountId, "accountId", false);
        }
        UUID contactId = command.contactId();
        if (contactId != null && (current == null || !contactId.equals(current.getContactId()))) {
            requireParty(contactId, "contactId", true);
        }
        BigDecimal amount = Money.amount(command.amount(), "amount");
        String currency = amount == null ? null : Money.currency(command.currency(), "currency", tenants);
        UUID ownerId = command.ownerId();
        if (ownerId != null && (current == null || !ownerId.equals(current.getOwnerId()))
                && members.findActive(ownerId).isEmpty()) {
            throw ApiProblem.badRequestField("ownerId", NOT_A_MEMBER);
        }
        String description = Text.optional(command.description(), 5000, "description");
        return new OpportunityDetails(name, accountId, contactId, amount, currency, command.expectedCloseOn(), ownerId,
                description);
    }

    private void requireParty(UUID id, String field, boolean person) {
        if (!CurrentAuthorities.has(DirectoryPermissions.PARTY_READ)) {
            throw ApiProblem.forbidden(FORBIDDEN);
        }
        PartyBrief party = parties.briefs(List.of(id)).get(id);
        if (party == null) {
            throw ApiProblem.badRequestField(field, UNKNOWN_PARTY);
        }
        if (person && party.kind() != PartyKind.PERSON) {
            throw ApiProblem.badRequestField(field, NOT_A_PERSON);
        }
        if (party.archived()) {
            throw ApiProblem.conflict(ARCHIVED);
        }
    }

    private List<OpportunityView> views(List<Opportunity> page) {
        Map<UUID, PartyBrief> partyNames = parties.briefs(page.stream()
                .flatMap(o -> Stream.of(o.getAccountId(), o.getContactId())).filter(Objects::nonNull)
                .collect(Collectors.toSet()));
        Map<UUID, Members.Member> people = members.findAll(page.stream()
                .flatMap(o -> Stream.of(o.getOwnerId(), o.getCreatedBy())).filter(Objects::nonNull)
                .collect(Collectors.toSet()));
        Map<UUID, PipelineStage> stages = stagesById();
        return page.stream().map(o -> {
            PipelineStage stage = stages.get(o.getStageId());
            return new OpportunityView(o.getId(), o.getName(), party(partyNames, o.getAccountId()),
                    party(partyNames, o.getContactId()), PipelineService.ref(stage),
                    OpportunityStatus.valueOf(stage.getKind().name()), o.getAmount(), o.getCurrency(),
                    o.getExpectedCloseOn(), member(people, o.getOwnerId()), o.getLeadId(), o.getDescription(),
                    o.getLostReason(), o.getClosedAt(), member(people, o.getCreatedBy()), o.getCreatedAt(),
                    o.getUpdatedAt(), o.getVersion());
        }).toList();
    }

    private Map<UUID, PipelineStage> stagesById() {
        return pipeline.ordered().stream().collect(Collectors.toMap(PipelineStage::getId, s -> s));
    }

    private Specification<Opportunity> ownerFilter(String raw) {
        String owner = raw == null ? "" : raw.strip();
        if (owner.equals("me")) {
            UUID me = LeadService.currentUser();
            return (root, cq, cb) -> cb.equal(root.get("ownerId"), me);
        }
        if (owner.equals("unassigned")) {
            return (root, cq, cb) -> cb.isNull(root.get("ownerId"));
        }
        if (!owner.isEmpty()) {
            UUID id = LeadService.parseUuid(owner, "owner");
            return (root, cq, cb) -> cb.equal(root.get("ownerId"), id);
        }
        return (root, cq, cb) -> cb.conjunction();
    }

    private static UUID boardOwner(String raw) {
        String owner = raw == null ? "" : raw.strip();
        if (owner.isEmpty() || owner.equals("all")) {
            return null;
        }
        if (owner.equals("me")) {
            return LeadService.currentUser();
        }
        throw ApiProblem.badRequestField("owner", "Use me or all.");
    }

    private static Map<String, Object> snapshot(Opportunity o, PipelineStage stage) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", o.getName());
        values.put("accountId", o.getAccountId().toString());
        values.put("stage", stage.getName());
        if (o.getContactId() != null) values.put("contactId", o.getContactId().toString());
        if (o.getAmount() != null) {
            values.put("amount", o.getAmount().toPlainString());
            values.put("currency", o.getCurrency());
        }
        if (o.getExpectedCloseOn() != null) values.put("expectedCloseOn", o.getExpectedCloseOn().toString());
        if (o.getOwnerId() != null) values.put("ownerId", o.getOwnerId().toString());
        return values;
    }

    private static OpportunityDetails withOwner(OpportunityDetails d, UUID owner) {
        return new OpportunityDetails(d.name(), d.accountId(), d.contactId(), d.amount(), d.currency(),
                d.expectedCloseOn(), owner, d.description());
    }

    private static void checkVersion(Opportunity o, Long version) {
        if (version == null) {
            throw ApiProblem.badRequestField("version", "Reload the record and try again.");
        }
        if (o.getVersion() != version) {
            throw ApiProblem.conflict(STALE);
        }
    }

    private static PartyRef party(Map<UUID, PartyBrief> parties, UUID id) {
        PartyBrief p = id == null ? null : parties.get(id);
        return p == null ? null : new PartyRef(p.id(), p.name());
    }

    private static MemberRef member(Map<UUID, Members.Member> people, UUID id) {
        Members.Member m = id == null ? null : people.get(id);
        return m == null ? null : new MemberRef(m.id(), m.name());
    }
}
```

Note: the `@Transactional` on the package-private `open` is only effective when called through the proxy from another bean (Task 4); `create` already runs in its own transaction.

`crm/OpportunitySubjects.java`:

```java
package com.nexusops.crm;

import com.nexusops.collaboration.SubjectRef;
import com.nexusops.collaboration.SubjectResolver;
import com.nexusops.crm.domain.Opportunity;
import com.nexusops.crm.domain.OpportunityRepository;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Opportunities as collaboration subjects (type OPPORTUNITY, D9). Closed deals still take notes. */
@Component
class OpportunitySubjects implements SubjectResolver {

    static final String TYPE = "OPPORTUNITY";

    private final OpportunityRepository opportunities;

    OpportunitySubjects(OpportunityRepository opportunities) {
        this.opportunities = opportunities;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String readPermission() {
        return CrmPermissions.OPPORTUNITY_READ;
    }

    @Override
    public Optional<SubjectRef> find(UUID id) {
        return opportunities.findById(id).map(OpportunitySubjects::ref);
    }

    @Override
    public Map<UUID, SubjectRef> findAll(Collection<UUID> ids) {
        return opportunities.findAllById(ids).stream().collect(Collectors.toMap(Opportunity::getId, OpportunitySubjects::ref));
    }

    static SubjectRef ref(Opportunity o) {
        return new SubjectRef(TYPE, o.getId(), o.getName(), false);
    }
}
```

- [ ] **Step 7: Web layer**

Add to `CrmDtos`:

```java
    record OpportunityRequest(String name, UUID accountId, UUID contactId, UUID stageId, BigDecimal amount,
            String currency, LocalDate expectedCloseOn, UUID ownerId, String description, Long version) {
        OpportunityCommand command() {
            return new OpportunityCommand(name, accountId, contactId, stageId, amount, currency, expectedCloseOn,
                    ownerId, description);
        }
    }

    record StageMoveRequest(UUID stageId, String lostReason, Long version) {}
```

`crm/web/OpportunityController.java`:

```java
package com.nexusops.crm.web;

import com.nexusops.crm.OpportunityQuery;
import com.nexusops.crm.OpportunityService;
import com.nexusops.crm.OpportunityStatus;
import com.nexusops.crm.OpportunityView;
import com.nexusops.crm.web.CrmDtos.OpportunityRequest;
import com.nexusops.crm.web.CrmDtos.StageMoveRequest;
import com.nexusops.shared.web.PageResponse;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/opportunities")
class OpportunityController {

    private final OpportunityService opportunities;

    OpportunityController(OpportunityService opportunities) {
        this.opportunities = opportunities;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('crm.opportunity.read')")
    PageResponse<OpportunityView> list(@RequestParam(required = false) String q,
            @RequestParam(required = false) OpportunityStatus status, @RequestParam(required = false) UUID stageId,
            @RequestParam(required = false) UUID accountId, @RequestParam(required = false) UUID contactId,
            @RequestParam(required = false) String owner, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return opportunities.list(new OpportunityQuery(q, status, stageId, accountId, contactId, owner), page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('crm.opportunity.read')")
    OpportunityView get(@PathVariable UUID id) {
        return opportunities.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('crm.opportunity.manage')")
    OpportunityView create(@RequestBody OpportunityRequest request) {
        return opportunities.create(request.command());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('crm.opportunity.manage')")
    OpportunityView update(@PathVariable UUID id, @RequestBody OpportunityRequest request) {
        return opportunities.update(id, request.command(), request.version());
    }

    @PostMapping("/{id}/stage")
    @PreAuthorize("hasAuthority('crm.opportunity.manage')")
    OpportunityView move(@PathVariable UUID id, @RequestBody StageMoveRequest request) {
        return opportunities.moveStage(id, request.stageId(), request.lostReason(), request.version());
    }
}
```

`crm/web/BoardController.java` — the board route, kept apart from `StageController` (which is about configuring stages):

```java
package com.nexusops.crm.web;

import com.nexusops.crm.BoardView;
import com.nexusops.crm.OpportunityService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class BoardController {

    private final OpportunityService opportunities;

    BoardController(OpportunityService opportunities) {
        this.opportunities = opportunities;
    }

    @GetMapping("/api/v1/crm/pipeline/board")
    @PreAuthorize("hasAuthority('crm.opportunity.read')")
    BoardView board(@RequestParam(required = false) String owner) {
        return opportunities.board(owner);
    }
}
```


- [ ] **Step 8: Run the tests to verify they pass**

Run: `cd backend && ./gradlew test --tests '*OpportunityApiIT' --tests '*PipelineStageApiIT' --tests '*ModularityTest' --tests '*EndpointAuthorizationCoverageTest'`
Expected: PASS. If `NamedParameterJdbcTemplate` is not auto-configured as a bean, add it in a small `@Configuration` in `shared/db` (`new NamedParameterJdbcTemplate(dataSource)` using the tenant-aware `DataSource` bean) — check `TenantDataSourceConfig` first.

- [ ] **Step 9: Run the whole backend suite, then commit**

Run: `cd backend && ./gradlew test` — Expected: PASS.

```bash
git add backend/src/main/resources/db/migration/V17__opportunities.sql backend/src/main/java/com/nexusops/directory \
  backend/src/main/java/com/nexusops/crm backend/src/test/java/com/nexusops/crm
git commit -m "feat(crm): opportunities, stage moves and the pipeline board; won deals mark the account a customer"
```

---
### Task 4: Lead conversion into canonical parties, with duplicate checks and an optional opportunity

**Files:**
- Modify: `backend/src/main/java/com/nexusops/shared/web/ApiProblem.java` (+ `prefixed`)
- Create: `backend/src/main/java/com/nexusops/crm/ConversionCommand.java`, `LeadConversionService.java`
- Modify: `backend/src/main/java/com/nexusops/crm/web/LeadController.java`, `CrmDtos.java`
- Test: `backend/src/test/java/com/nexusops/crm/LeadConversionIT.java`

**Interfaces:**
- Consumes: `LeadService.requireConvertible/view`, `Lead.markConverted`, `LeadRepository` (Task 2); `OpportunityService.open(OpportunityCommand, UUID leadId)` (Task 3); `PartyService.createPerson/createOrganization/briefs/ensureCustomer`, `PersonCommand`, `OrganizationCommand`, `PartyKind`, `DirectoryPermissions.PARTY_READ/PARTY_MANAGE`.
- Produces:
  - `ApiProblem.prefixed(String prefix)` — copy with every field name prefixed.
  - `ConversionCommand(PersonChoice person, OrganizationChoice organization, NewOpportunity opportunity)` with nested `PersonChoice(UUID existingId, String firstName, String lastName, String jobTitle, String email, String phone, String duplicateReason)`, `OrganizationChoice(UUID existingId, String name, String domain, String website, String email, String phone, String duplicateReason)`, `NewOpportunity(String name, BigDecimal amount, String currency, UUID stageId, LocalDate expectedCloseOn)`. A choice with `existingId` links; without it creates; a null choice means none.
  - `POST /api/v1/leads/{id}/convert` body `{person?, organization?, opportunity?, version}` → `LeadView`.
  - Error contract: field errors are prefixed `person.` / `organization.` / `opportunity.`; a directory duplicate 409 keeps `duplicates[]` and adds `party: "person" | "organization"`.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/nexusops/crm/LeadConversionIT.java`:

```java
package com.nexusops.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestCrm;
import com.nexusops.support.TestMembers;
import com.nexusops.support.TestRoles;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Workspace;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class LeadConversionIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;
    UUID lead;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("conv"));
        owner = Api.login(mvc, ws);
        TestCrm.enable(owner);
        lead = Api.id(owner.post("/api/v1/leads", "{\"firstName\":\"Grace\",\"lastName\":\"Hopper\",\"companyName\":"
                + "\"Acme Robotics\",\"email\":\"grace@acme.test\",\"estimatedValue\":5000}"));
    }

    private long count(String sql) {
        return OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(sql, Long.class);
    }

    private static final String CREATE_BOTH = "{\"person\":{\"firstName\":\"Grace\",\"lastName\":\"Hopper\","
            + "\"email\":\"grace@acme.test\"},\"organization\":{\"name\":\"Acme Robotics\"},\"opportunity\":{},\"version\":0}";

    @Test
    void createsThePersonTheOrganizationAndAnOpportunity() throws Exception {
        owner.post("/api/v1/leads/" + lead + "/convert", CREATE_BOTH).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONVERTED"))
                .andExpect(jsonPath("$.convertedAt").exists())
                .andExpect(jsonPath("$.convertedPerson.name").value("Grace Hopper"))
                .andExpect(jsonPath("$.convertedOrganization.name").value("Acme Robotics"))
                .andExpect(jsonPath("$.convertedOpportunityId").exists());
        String body = Api.body(owner.get("/api/v1/leads/" + lead));
        String org = com.jayway.jsonpath.JsonPath.read(body, "$.convertedOrganization.id");
        String person = com.jayway.jsonpath.JsonPath.read(body, "$.convertedPerson.id");
        String opportunity = com.jayway.jsonpath.JsonPath.read(body, "$.convertedOpportunityId");
        owner.get("/api/v1/parties/" + org).andExpect(jsonPath("$.roles[?(@.role == 'CUSTOMER')].status")
                .value(Matchers.contains("ACTIVE")));
        owner.get("/api/v1/parties/" + person).andExpect(jsonPath("$.organization.id").value(org));
        owner.get("/api/v1/opportunities/" + opportunity).andExpect(jsonPath("$.name").value("Grace Hopper"))
                .andExpect(jsonPath("$.account.id").value(org)).andExpect(jsonPath("$.contact.id").value(person))
                .andExpect(jsonPath("$.amount").value(5000.0)).andExpect(jsonPath("$.leadId").value(lead.toString()))
                .andExpect(jsonPath("$.stage.name").value("Prospecting"));
        // the converted lead is frozen
        owner.put("/api/v1/leads/" + lead, "{\"lastName\":\"X\",\"version\":1}").andExpect(status().isConflict());
        owner.post("/api/v1/activities", "{\"subjectType\":\"LEAD\",\"subjectId\":\"" + lead
                + "\",\"type\":\"NOTE\",\"summary\":\"late\"}").andExpect(status().isConflict());
        owner.post("/api/v1/leads/" + lead + "/convert", CREATE_BOTH.replace("\"version\":0", "\"version\":1"))
                .andExpect(status().isConflict());
        assertThat(count("select count(*) from audit_events where action = 'LeadConverted'")).isEqualTo(1);
    }

    @Test
    void aProbableDuplicateOrganizationIsRefusedThenLinked() throws Exception {
        UUID existing = Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Acme Robotics Ltd\"}"));
        long partiesBefore = count("select count(*) from parties");
        owner.post("/api/v1/leads/" + lead + "/convert", CREATE_BOTH).andExpect(status().isConflict())
                .andExpect(jsonPath("$.party").value("organization"))
                .andExpect(jsonPath("$.duplicates[0].id").value(existing.toString()));
        assertThat(count("select count(*) from parties")).isEqualTo(partiesBefore);
        owner.get("/api/v1/leads/" + lead).andExpect(jsonPath("$.status").value("NEW"));

        owner.post("/api/v1/leads/" + lead + "/convert", "{\"person\":{\"firstName\":\"Grace\"},\"organization\":"
                + "{\"existingId\":\"" + existing + "\"},\"version\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.convertedOrganization.id").value(existing.toString()))
                .andExpect(jsonPath("$.convertedOpportunityId").doesNotExist());
    }

    @Test
    void aReasonCreatesTheDuplicateAnyway() throws Exception {
        Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Acme Robotics\"}"));
        owner.post("/api/v1/leads/" + lead + "/convert", "{\"organization\":{\"name\":\"Acme Robotics\","
                + "\"duplicateReason\":\"Separate Pune branch\"},\"version\":0}").andExpect(status().isOk());
        assertThat(count("select count(*) from parties where duplicate_reason = 'Separate Pune branch'")).isEqualTo(1);
    }

    @Test
    void aDuplicatePersonRollsBackTheNewOrganization() throws Exception {
        Api.id(owner.post("/api/v1/persons", "{\"firstName\":\"G\",\"email\":\"grace@acme.test\"}"));
        long organizations = count("select count(*) from parties where kind = 'ORGANIZATION'");
        owner.post("/api/v1/leads/" + lead + "/convert", CREATE_BOTH).andExpect(status().isConflict())
                .andExpect(jsonPath("$.party").value("person"));
        assertThat(count("select count(*) from parties where kind = 'ORGANIZATION'")).isEqualTo(organizations);
        assertThat(count("select count(*) from opportunities")).isZero();
    }

    @Test
    void aCompanyOnlyConversionMakesTheOrganizationTheCustomer() throws Exception {
        UUID company = Api.id(owner.post("/api/v1/leads", "{\"companyName\":\"Deccan Spices\",\"email\":\"hi@deccan.test\"}"));
        owner.post("/api/v1/leads/" + company + "/convert", "{\"organization\":{\"name\":\"Deccan Spices\","
                + "\"email\":\"hi@deccan.test\"},\"opportunity\":{\"name\":\"Spice supply\",\"amount\":900,"
                + "\"currency\":\"INR\"},\"version\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.convertedPerson").doesNotExist());
        owner.get("/api/v1/opportunities?q=spice").andExpect(jsonPath("$.items[0].contact").doesNotExist())
                .andExpect(jsonPath("$.items[0].currency").value("INR"));
    }

    @Test
    void invalidConversionsAreRefused() throws Exception {
        UUID org = Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Other\"}"));
        List<String> stages = Api.read(owner.get("/api/v1/crm/pipeline/stages"), "$[*].id");
        String[][] badRequests = {
                {"{\"version\":0}", "person"},
                {"{\"person\":{\"existingId\":\"" + org + "\"},\"version\":0}", "person.existingId"},
                {"{\"person\":{\"existingId\":\"" + UUID.randomUUID() + "\"},\"version\":0}", "person.existingId"},
                {"{\"person\":{\"lastName\":\"Only\"},\"version\":0}", "person.firstName"},
                {"{\"organization\":{\"name\":\"X\",\"domain\":\"not a domain\"},\"version\":0}", "organization.domain"},
                {"{\"organization\":{\"existingId\":\"" + org + "\"},\"opportunity\":{\"stageId\":\"" + stages.get(4)
                        + "\"},\"version\":0}", "opportunity.stageId"},
                {"{\"organization\":{\"existingId\":\"" + org + "\"}}", "version"},
        };
        for (String[] c : badRequests) {
            owner.post("/api/v1/leads/" + lead + "/convert", c[0]).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value(c[1]));
        }
        owner.post("/api/v1/leads/" + lead + "/convert", "{\"organization\":{\"existingId\":\"" + org + "\"},\"version\":5}")
                .andExpect(status().isConflict());
        owner.post("/api/v1/leads/" + lead + "/status", "{\"status\":\"DISQUALIFIED\",\"reason\":\"No\",\"version\":0}")
                .andExpect(status().isOk());
        owner.post("/api/v1/leads/" + lead + "/convert", "{\"organization\":{\"existingId\":\"" + org + "\"},\"version\":1}")
                .andExpect(status().isConflict());
        assertThat(count("select count(*) from leads where status = 'CONVERTED'")).isZero();
    }

    @Test
    void conversionNeedsTheDirectoryAndOpportunityPermissionsItUses() throws Exception {
        UUID org = Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Linked Co\"}"));
        UUID role = TestRoles.create(mvc, owner.session(), "Lead converter", "crm.lead.read", "crm.lead.manage",
                "directory.party.read");
        Api converter = Api.login(mvc, members.create(ws.tenantId(), Set.of(role)));
        converter.post("/api/v1/leads/" + lead + "/convert", CREATE_BOTH).andExpect(status().isForbidden());
        converter.post("/api/v1/leads/" + lead + "/convert", "{\"organization\":{\"existingId\":\"" + org
                + "\"},\"opportunity\":{},\"version\":0}").andExpect(status().isForbidden());
        converter.post("/api/v1/leads/" + lead + "/convert", "{\"organization\":{\"existingId\":\"" + org
                + "\"},\"version\":0}").andExpect(status().isOk());
        // linking still makes the account a customer: a CRM business rule, not the converter's directory permission
        owner.get("/api/v1/parties/" + org).andExpect(jsonPath("$.roles[?(@.role == 'CUSTOMER')].status")
                .value(Matchers.contains("ACTIVE")));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./gradlew test --tests '*LeadConversionIT'`
Expected: FAIL — 404 (no `/convert` route).

- [ ] **Step 3: `ApiProblem.prefixed`**

Add to `shared/web/ApiProblem.java`:

```java
    /** A copy whose field errors are prefixed (e.g. "person." + "firstName"), for nested request objects. */
    public ApiProblem prefixed(String prefix) {
        return new ApiProblem(status, getMessage(),
                errors.stream().map(e -> new FieldError(prefix + e.field(), e.message())).toList(), properties);
    }
```

- [ ] **Step 4: Command and service**

`crm/ConversionCommand.java`:

```java
package com.nexusops.crm;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** D4. Each choice links {@code existingId} or, without it, creates from its fields; a null choice means none. */
public record ConversionCommand(PersonChoice person, OrganizationChoice organization, NewOpportunity opportunity) {

    public record PersonChoice(UUID existingId, String firstName, String lastName, String jobTitle, String email,
            String phone, String duplicateReason) {}

    public record OrganizationChoice(UUID existingId, String name, String domain, String website, String email,
            String phone, String duplicateReason) {}

    /** Name defaults to the lead's name; amount and currency default to the lead's estimate. */
    public record NewOpportunity(String name, BigDecimal amount, String currency, UUID stageId,
            LocalDate expectedCloseOn) {}
}
```

`crm/LeadConversionService.java`:

```java
package com.nexusops.crm;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.crm.ConversionCommand.NewOpportunity;
import com.nexusops.crm.ConversionCommand.OrganizationChoice;
import com.nexusops.crm.ConversionCommand.PersonChoice;
import com.nexusops.crm.domain.Lead;
import com.nexusops.crm.domain.LeadRepository;
import com.nexusops.directory.DirectoryPermissions;
import com.nexusops.directory.OrganizationCommand;
import com.nexusops.directory.PartyBrief;
import com.nexusops.directory.PartyKind;
import com.nexusops.directory.PartyService;
import com.nexusops.directory.PersonCommand;
import com.nexusops.identity.Members;
import com.nexusops.shared.security.CurrentAuthorities;
import com.nexusops.shared.web.ApiProblem;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Converts an open lead (D4) in one transaction: organization, then person (placed in it), the CUSTOMER role on the
 * account, an optional opportunity, then the lead itself. Any failure — a duplicate included — writes nothing.
 */
@Service
public class LeadConversionService {

    static final String FORBIDDEN = "You do not have permission to perform this action.";
    static final String NOTHING_CHOSEN = "Choose or create a person or an organization.";

    private final LeadService leads;
    private final LeadRepository leadRows;
    private final PartyService parties;
    private final OpportunityService opportunities;
    private final Members members;
    private final AuditService audit;

    LeadConversionService(LeadService leads, LeadRepository leadRows, PartyService parties,
            OpportunityService opportunities, Members members, AuditService audit) {
        this.leads = leads;
        this.leadRows = leadRows;
        this.parties = parties;
        this.opportunities = opportunities;
        this.members = members;
        this.audit = audit;
    }

    @Transactional
    public LeadView convert(UUID id, ConversionCommand command, Long version) {
        Lead lead = leads.requireConvertible(id, version);
        PersonChoice person = command.person();
        OrganizationChoice organization = command.organization();
        if (person == null && organization == null) {
            throw ApiProblem.badRequestField("person", NOTHING_CHOSEN);
        }
        boolean creates = (person != null && person.existingId() == null)
                || (organization != null && organization.existingId() == null);
        boolean links = (person != null && person.existingId() != null)
                || (organization != null && organization.existingId() != null);
        require(!creates || CurrentAuthorities.has(DirectoryPermissions.PARTY_MANAGE));
        require(!links || CurrentAuthorities.has(DirectoryPermissions.PARTY_READ));
        require(command.opportunity() == null || CurrentAuthorities.has(CrmPermissions.OPPORTUNITY_MANAGE));

        UUID organizationId = organization == null ? null
                : organization.existingId() != null
                        ? linked(organization.existingId(), PartyKind.ORGANIZATION, "organization.existingId")
                        : createOrganization(organization);
        UUID personId = person == null ? null
                : person.existingId() != null ? linked(person.existingId(), PartyKind.PERSON, "person.existingId")
                        : createPerson(person, organizationId);
        UUID account = organizationId != null ? organizationId : personId;
        parties.ensureCustomer(account);
        UUID opportunityId = command.opportunity() == null ? null
                : openOpportunity(lead, command.opportunity(), account, personId);

        lead.markConverted(personId, organizationId, opportunityId, Instant.now());
        leadRows.flush();
        Map<String, Object> after = new LinkedHashMap<>();
        if (personId != null) after.put("personId", personId.toString());
        if (organizationId != null) after.put("organizationId", organizationId.toString());
        if (opportunityId != null) after.put("opportunityId", opportunityId.toString());
        audit.record(AuditEntry.of("LeadConverted", "Lead", id).withAfter(after));
        return leads.view(lead);
    }

    private UUID linked(UUID id, PartyKind kind, String field) {
        PartyBrief party = parties.briefs(List.of(id)).get(id);
        if (party == null || party.kind() != kind) {
            throw ApiProblem.badRequestField(field,
                    kind == PartyKind.PERSON ? "Choose a person in this workspace." : "Choose an organization in this workspace.");
        }
        if (party.archived()) {
            throw ApiProblem.conflict("This record is archived.");
        }
        return id;
    }

    private UUID createOrganization(OrganizationChoice c) {
        try {
            return parties.createOrganization(new OrganizationCommand(c.name(), c.domain(), c.website(), c.email(),
                    c.phone(), c.duplicateReason())).id();
        } catch (ApiProblem problem) {
            throw relabel(problem, "organization");
        }
    }

    private UUID createPerson(PersonChoice c, UUID organizationId) {
        try {
            return parties.createPerson(new PersonCommand(c.firstName(), c.lastName(), c.jobTitle(), organizationId,
                    c.email(), c.phone(), c.duplicateReason())).id();
        } catch (ApiProblem problem) {
            throw relabel(problem, "person");
        }
    }

    private UUID openOpportunity(Lead lead, NewOpportunity o, UUID account, UUID contact) {
        String name = o.name() == null || o.name().isBlank() ? lead.getName() : o.name();
        BigDecimal amount = o.amount() != null ? o.amount() : lead.getEstimatedValue();
        String currency = o.amount() != null ? o.currency() : lead.getCurrency();
        UUID owner = lead.getOwnerId() != null && members.findActive(lead.getOwnerId()).isPresent()
                ? lead.getOwnerId() : null;
        try {
            return opportunities.open(new OpportunityCommand(name, account, contact, o.stageId(), amount, currency,
                    o.expectedCloseOn(), owner, null), lead.getId()).getId();
        } catch (ApiProblem problem) {
            throw problem.prefixed("opportunity.");
        }
    }

    /** Duplicates keep their candidates and say which party they are about; field errors get the prefix. */
    private static ApiProblem relabel(ApiProblem problem, String party) {
        return problem.properties().containsKey("duplicates") ? problem.withProperty("party", party)
                : problem.prefixed(party + ".");
    }

    private static void require(boolean allowed) {
        if (!allowed) {
            throw ApiProblem.forbidden(FORBIDDEN);
        }
    }
}
```

- [ ] **Step 5: Route**

Add to `CrmDtos`:

```java
    record ConversionRequest(ConversionCommand.PersonChoice person, ConversionCommand.OrganizationChoice organization,
            ConversionCommand.NewOpportunity opportunity, Long version) {
        ConversionCommand command() {
            return new ConversionCommand(person, organization, opportunity);
        }
    }
```

In `LeadController` inject `LeadConversionService conversions` (constructor) and add:

```java
    @PostMapping("/{id}/convert")
    @PreAuthorize("hasAuthority('crm.lead.manage')")
    LeadView convert(@PathVariable UUID id, @RequestBody ConversionRequest request) {
        return conversions.convert(id, request.command(), request.version());
    }
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `cd backend && ./gradlew test --tests '*LeadConversionIT' --tests '*LeadApiIT' --tests '*OpportunityApiIT'`
Expected: PASS. If `aDuplicatePersonRollsBackTheNewOrganization` finds the organization committed, the conversion is not running in one transaction — check that `LeadConversionService.convert` is called through the Spring proxy (controller → service) and that no inner call uses `REQUIRES_NEW`.

- [ ] **Step 7: Run the whole backend suite, then commit**

Run: `cd backend && ./gradlew test` — Expected: PASS.

```bash
git add backend/src/main/java/com/nexusops/shared/web/ApiProblem.java backend/src/main/java/com/nexusops/crm \
  backend/src/test/java/com/nexusops/crm/LeadConversionIT.java
git commit -m "feat(crm): convert leads into canonical people and organizations, with duplicate checks"
```

---

### Task 5: Lead import from CSV (all or nothing)

**Files:**
- Modify: `backend/src/main/java/com/nexusops/shared/web/ApiProblem.java` (+ `unprocessable`)
- Create: `backend/src/main/java/com/nexusops/crm/LeadCsv.java`, `LeadImportService.java`, `ImportResult.java`
- Modify: `backend/src/main/java/com/nexusops/crm/LeadService.java` (`withOwner` becomes package-private static)
- Modify: `backend/src/main/java/com/nexusops/crm/web/LeadController.java`
- Test: `backend/src/test/java/com/nexusops/crm/LeadCsvTest.java`, `LeadImportIT.java`

**Interfaces:**
- Consumes: `LeadService.validate(LeadCommand, null)`, `LeadService.withOwner`, `LeadService.currentUser`, `Lead`, `LeadRepository` (Task 2).
- Produces:
  - `ApiProblem.unprocessable(String detail)` (HTTP 422).
  - `LeadCsv.parse(byte[]) : List<LeadCsv.Row>`, `LeadCsv.Row(int row, Map<String, String> values, boolean tooManyValues)`, constants `MAX_BYTES = 262_144`, `MAX_ROWS = 500`, `COLUMNS` (verbatim from D11).
  - `ImportResult(int imported)`.
  - `POST /api/v1/leads/import` (multipart `file`) → `ImportResult`; 422 body adds `rows: [{row, field, message}]` (first 50) and `errorCount`.

- [ ] **Step 1: Unit test the parser**

`backend/src/test/java/com/nexusops/crm/LeadCsvTest.java`:

```java
package com.nexusops.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexusops.shared.web.ApiProblem;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class LeadCsvTest {

    private static List<LeadCsv.Row> parse(String text) {
        return LeadCsv.parse(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void refused(byte[] bytes, String message) {
        assertThatThrownBy(() -> LeadCsv.parse(bytes)).isInstanceOfSatisfying(ApiProblem.class, p -> {
            assertThat(p.errors()).extracting(ApiProblem.FieldError::field).containsExactly("file");
            assertThat(p.errors().getFirst().message()).contains(message);
        });
    }

    @Test
    void readsHeadersInAnyOrderAndCase() {
        List<LeadCsv.Row> rows = parse("Company,First Name,EMAIL\nAcme,Grace,g@acme.test\n");
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().row()).isEqualTo(2);
        assertThat(rows.getFirst().values()).containsEntry("company", "Acme").containsEntry("first_name", "Grace")
                .containsEntry("email", "g@acme.test");
    }

    @Test
    void handlesExcelOutput() {
        String text = "﻿first_name,company,description\r\n"
                + "Grace,\"Acme, Inc\",\"Said \"\"call me\"\"\"\r\n"
                + "Anjali,Deccan,\"two\r\nlines\"\r\n"
                + "\r\n";
        List<LeadCsv.Row> rows = parse(text);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).values()).containsEntry("company", "Acme, Inc").containsEntry("description", "Said \"call me\"");
        assertThat(rows.get(1).values()).containsEntry("description", "two\r\nlines");
        assertThat(rows.get(1).row()).isEqualTo(3);
    }

    @Test
    void flagsRowsWithMoreValuesThanTheHeader() {
        assertThat(parse("company\nA,B\n").getFirst().tooManyValues()).isTrue();
        assertThat(parse("company,email\nA\n").getFirst().values()).containsEntry("company", "A")
                .doesNotContainKey("email");
    }

    @Test
    void refusesBadFiles() {
        refused("".getBytes(StandardCharsets.UTF_8), "empty");
        refused("company,colour,size\nA,red,L\n".getBytes(StandardCharsets.UTF_8), "colour, size");
        refused("email,phone\na@b.test,1\n".getBytes(StandardCharsets.UTF_8), "first_name, last_name or company");
        refused("company,company\nA,B\n".getBytes(StandardCharsets.UTF_8), "more than once");
        refused("company\n\"Acme\n".getBytes(StandardCharsets.UTF_8), "quoted value");
        refused(new byte[] {'c', 'o', 'm', 'p', 'a', 'n', 'y', '\n', (byte) 0xC3, (byte) 0x28}, "UTF-8");
        refused(("company\n" + "A\n".repeat(501)).getBytes(StandardCharsets.UTF_8), "500");
        refused(new byte[LeadCsv.MAX_BYTES + 1], "256 KB");
    }
}
```

- [ ] **Step 2: Write the failing API test**

`backend/src/test/java/com/nexusops/crm/LeadImportIT.java`:

```java
package com.nexusops.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestCrm;
import com.nexusops.support.TestMembers;
import com.nexusops.support.TestRoles;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Workspace;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

@AutoConfigureMockMvc
class LeadImportIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("imp"));
        owner = Api.login(mvc, ws);
        TestCrm.enable(owner);
    }

    private ResultActions upload(Api api, String csv) throws Exception {
        return api.perform(MockMvcRequestBuilders.multipart("/api/v1/leads/import").file(
                new MockMultipartFile("file", "leads.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8))));
    }

    private long leads() {
        return OwnerJdbc.ownerAs(ws.tenantId()).queryForObject("select count(*) from leads", Long.class);
    }

    @Test
    void importsAnExcelStyleFile() throws Exception {
        String csv = "﻿first_name,last_name,company,email,source,estimated_value,currency\r\n"
                + "Grace,Hopper,\"Acme, Inc\",GRACE@acme.test,referral,1200.50,\r\n"
                + ",,Deccan Spices,,,900,inr\r\n"
                + "\r\n";
        upload(owner, csv).andExpect(status().isOk()).andExpect(jsonPath("$.imported").value(2));
        owner.get("/api/v1/leads?q=acme").andExpect(jsonPath("$.items[0].companyName").value("Acme, Inc"))
                .andExpect(jsonPath("$.items[0].email").value("grace@acme.test"))
                .andExpect(jsonPath("$.items[0].source").value("REFERRAL"))
                .andExpect(jsonPath("$.items[0].currency").value("USD"))
                .andExpect(jsonPath("$.items[0].owner").exists());
        owner.get("/api/v1/leads?q=deccan").andExpect(jsonPath("$.items[0].name").value("Deccan Spices"))
                .andExpect(jsonPath("$.items[0].currency").value("INR"))
                .andExpect(jsonPath("$.items[0].source").value("OTHER"));
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select metadata ->> 'count' from audit_events where action = 'LeadsImported'", String.class)).isEqualTo("2");
    }

    @Test
    void anyInvalidRowRejectsTheWholeFile() throws Exception {
        String csv = "first_name,company,email,estimated_value,source\n"
                + "Grace,Acme,grace@acme.test,10,WEBSITE\n"
                + "Bad,Co,not-an-email,10,WEBSITE\n"
                + "Neg,Co,,-5,WEBSITE\n"
                + ",,,,\n"
                + "Src,Co,,,BILLBOARD\n"
                + "Num,Co,,lots,WEBSITE\n";
        upload(owner, csv).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCount").value(4))
                .andExpect(jsonPath("$.rows[*].row").value(Matchers.contains(3, 4, 6, 7)))
                .andExpect(jsonPath("$.rows[*].field").value(Matchers.contains("email", "estimated_value", "source",
                        "estimated_value")));
        assertThat(leads()).isZero();
    }

    @Test
    void aRowWithoutAnyNameIsAnError() throws Exception {
        upload(owner, "first_name,company,email\nGrace,,\n,,x@y.test\n").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.rows[0].row").value(3)).andExpect(jsonPath("$.rows[0].field").value("last_name"));
    }

    @Test
    void fileLevelProblemsAreFieldErrors() throws Exception {
        upload(owner, "company,colour\nA,red\n").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("file"));
        upload(owner, "company\n").andExpect(status().isBadRequest());
        owner.perform(MockMvcRequestBuilders.multipart("/api/v1/leads/import")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("file"));
    }

    @Test
    void readersCannotImport() throws Exception {
        UUID role = TestRoles.create(mvc, owner.session(), "Lead reader", "crm.lead.read");
        Api reader = Api.login(mvc, members.create(ws.tenantId(), Set.of(role)));
        upload(reader, "company\nAcme\n").andExpect(status().isForbidden());
        assertThat(leads()).isZero();
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `cd backend && ./gradlew test --tests '*LeadCsvTest' --tests '*LeadImportIT'`
Expected: FAIL — `LeadCsv` does not exist.

- [ ] **Step 4: `ApiProblem.unprocessable`**

```java
    /** 422: the request was understood but its content can't be accepted (e.g. rows of an import). */
    public static ApiProblem unprocessable(String detail) {
        return new ApiProblem(HttpStatus.valueOf(422), detail, List.of());
    }
```

Check `GlobalExceptionHandler` maps `ApiProblem` with `problem.status()` and copies `properties()` (Phase 4's `duplicates` already rely on that); a 422 needs no handler change.

- [ ] **Step 5: The parser**

`crm/LeadCsv.java`:

```java
package com.nexusops.crm;

import com.nexusops.shared.web.ApiProblem;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * RFC 4180 CSV for lead import (D11): UTF-8 (a BOM is ignored), comma-separated, quoted fields with "" escapes and
 * embedded line breaks, \n or \r\n records. File-level problems are 400 field errors on "file"; row-level ones are
 * left to the importer. Cell text is only ever stored, never evaluated.
 */
final class LeadCsv {

    static final int MAX_BYTES = 256 * 1024;
    static final int MAX_ROWS = 500;
    static final List<String> COLUMNS = List.of("first_name", "last_name", "company", "job_title", "email", "phone",
            "source", "estimated_value", "currency", "description");

    /** {@code row} is the spreadsheet row number (the header is row 1). */
    record Row(int row, Map<String, String> values, boolean tooManyValues) {}

    private LeadCsv() {}

    static List<Row> parse(byte[] bytes) {
        if (bytes.length > MAX_BYTES) {
            throw fileError("Use a CSV file of at most 256 KB.");
        }
        String text = decode(bytes);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        List<List<String>> records = records(text);
        int headerIndex = 0;
        while (headerIndex < records.size() && blank(records.get(headerIndex))) {
            headerIndex++;
        }
        if (headerIndex == records.size()) {
            throw fileError("The file is empty.");
        }
        List<String> header = records.get(headerIndex).stream()
                .map(h -> h.strip().toLowerCase(Locale.ROOT).replace(' ', '_')).toList();
        checkHeader(header);
        List<Row> rows = new ArrayList<>();
        // Blank lines are skipped but still counted, so row numbers match the spreadsheet the user sees.
        for (int i = headerIndex + 1; i < records.size(); i++) {
            List<String> cells = records.get(i);
            if (blank(cells)) {
                continue;
            }
            Map<String, String> values = new LinkedHashMap<>();
            for (int c = 0; c < Math.min(cells.size(), header.size()); c++) {
                values.put(header.get(c), cells.get(c));
            }
            rows.add(new Row(i + 1, values, cells.size() > header.size()));
        }
        if (rows.size() > MAX_ROWS) {
            throw fileError("Import at most " + MAX_ROWS + " leads at a time.");
        }
        return rows;
    }

    private static void checkHeader(List<String> header) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> unknown = new ArrayList<>();
        for (String column : header) {
            if (!seen.add(column)) {
                throw fileError("The column " + column + " appears more than once.");
            }
            if (!COLUMNS.contains(column)) {
                unknown.add(column.isEmpty() ? "(blank)" : column);
            }
        }
        if (!unknown.isEmpty()) {
            throw fileError("Unknown columns: " + String.join(", ", unknown) + ". Use: " + String.join(", ", COLUMNS) + ".");
        }
        if (!seen.contains("first_name") && !seen.contains("last_name") && !seen.contains("company")) {
            throw fileError("Add a first_name, last_name or company column.");
        }
    }

    /** Splits into records of fields. Line breaks inside quotes belong to the field. */
    private static List<List<String>> records(String text) {
        List<List<String>> records = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            any = true;
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(ch);
                }
            } else if (ch == '"' && field.isEmpty()) {
                quoted = true;
            } else if (ch == ',') {
                fields.add(field.toString());
                field.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                fields.add(field.toString());
                field.setLength(0);
                records.add(fields);
                fields = new ArrayList<>();
                any = false;
            } else {
                field.append(ch);
            }
        }
        if (quoted) {
            throw fileError("A quoted value is not closed.");
        }
        if (any) {
            fields.add(field.toString());
            records.add(fields);
        }
        return records;
    }

    private static boolean blank(List<String> record) {
        return record.stream().allMatch(String::isBlank);
    }

    private static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) {
            throw fileError("Save the file as UTF-8 CSV and try again.");
        }
    }

    private static ApiProblem fileError(String message) {
        return ApiProblem.badRequestField("file", message);
    }
}
```

Note: the `new byte[MAX_BYTES + 1]` case in the test is all zero bytes; the size check runs before decoding, so it fails on size.

- [ ] **Step 6: The import service and route**

In `LeadService`, change `private static LeadDetails withOwner(...)` to package-private `static LeadDetails withOwner(...)`.

`crm/ImportResult.java`:

```java
package com.nexusops.crm;

public record ImportResult(int imported) {}
```

`crm/LeadImportService.java`:

```java
package com.nexusops.crm;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.crm.domain.Lead;
import com.nexusops.crm.domain.LeadDetails;
import com.nexusops.crm.domain.LeadRepository;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.web.ApiProblem;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** D11: every row is validated like a hand-made lead; one bad row stores nothing. */
@Service
public class LeadImportService {

    public record RowError(int row, String field, String message) {}

    static final int MAX_REPORTED = 50;
    /** Service field names → CSV column names, so errors point at the user's columns. */
    private static final Map<String, String> COLUMN_OF = Map.of("firstName", "first_name", "lastName", "last_name",
            "companyName", "company", "jobTitle", "job_title", "estimatedValue", "estimated_value");

    private final LeadService leads;
    private final LeadRepository rows;
    private final AuditService audit;

    LeadImportService(LeadService leads, LeadRepository rows, AuditService audit) {
        this.leads = leads;
        this.rows = rows;
        this.audit = audit;
    }

    @Transactional
    public ImportResult importLeads(MultipartFile file) {
        TenantContext.requireTenantId();
        if (file == null || file.isEmpty()) {
            throw ApiProblem.badRequestField("file", "Choose a CSV file.");
        }
        if (file.getSize() > LeadCsv.MAX_BYTES) {
            throw ApiProblem.badRequestField("file", "Use a CSV file of at most 256 KB.");
        }
        List<LeadCsv.Row> parsed;
        try {
            parsed = LeadCsv.parse(file.getBytes());
        } catch (IOException unreadable) {
            throw ApiProblem.badRequestField("file", "The file could not be read.");
        }
        if (parsed.isEmpty()) {
            throw ApiProblem.badRequestField("file", "The file has no leads.");
        }
        List<RowError> errors = new ArrayList<>();
        List<LeadDetails> valid = new ArrayList<>();
        for (LeadCsv.Row row : parsed) {
            if (row.tooManyValues()) {
                errors.add(new RowError(row.row(), "row", "This row has more values than the header."));
                continue;
            }
            try {
                valid.add(leads.validate(command(row.values()), null));
            } catch (ApiProblem problem) {
                problem.errors().forEach(e -> errors.add(new RowError(row.row(),
                        COLUMN_OF.getOrDefault(e.field(), e.field()), e.message())));
            }
        }
        if (!errors.isEmpty()) {
            throw ApiProblem.unprocessable("Some rows can't be imported. Fix them and upload the file again.")
                    .withProperty("rows", errors.subList(0, Math.min(MAX_REPORTED, errors.size())))
                    .withProperty("errorCount", errors.size());
        }
        UUID me = LeadService.currentUser();
        rows.saveAll(valid.stream().map(d -> new Lead(Ids.newId(), LeadService.withOwner(d, me), me)).toList());
        rows.flush();
        audit.record(AuditEntry.of("LeadsImported", "Lead", null).withMetadata(Map.of("count", valid.size())));
        return new ImportResult(valid.size());
    }

    private static LeadCommand command(Map<String, String> v) {
        return new LeadCommand(v.get("first_name"), v.get("last_name"), v.get("company"), v.get("job_title"),
                v.get("email"), v.get("phone"), source(v.get("source")), null, amount(v.get("estimated_value")),
                v.get("currency"), v.get("description"));
    }

    private static LeadSource source(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LeadSource.valueOf(raw.strip().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_'));
        } catch (IllegalArgumentException unknown) {
            throw ApiProblem.badRequestField("source",
                    "Use WEBSITE, REFERRAL, WALK_IN, PHONE, EMAIL, SOCIAL, EVENT or OTHER.");
        }
    }

    private static BigDecimal amount(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.strip());
        } catch (NumberFormatException invalid) {
            throw ApiProblem.badRequestField("estimatedValue", "Enter a number like 1200.50.");
        }
    }
}
```

Check: `source(...)` and `amount(...)` throw inside `command(...)`, which is inside the `try`, so they become row errors. `LeadCsv.parse` throws a 400 for the whole file.

In `LeadController` inject `LeadImportService imports` and add:

```java
    @PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('crm.lead.manage')")
    ImportResult importLeads(@RequestPart(name = "file", required = false) MultipartFile file) {
        return imports.importLeads(file);
    }
```

(imports: `org.springframework.http.MediaType`, `org.springframework.web.bind.annotation.RequestPart`, `org.springframework.web.multipart.MultipartFile`, `com.nexusops.crm.ImportResult`, `com.nexusops.crm.LeadImportService`.) A multipart request with no file part must reach the service (`required = false`) so the user gets the field error, not a 500.

- [ ] **Step 7: Run the tests to verify they pass**

Run: `cd backend && ./gradlew test --tests '*LeadCsvTest' --tests '*LeadImportIT' --tests '*LeadApiIT'`
Expected: PASS.

- [ ] **Step 8: Run the whole backend suite, then commit**

Run: `cd backend && ./gradlew test` — Expected: PASS.

```bash
git add backend/src/main/java/com/nexusops/shared/web/ApiProblem.java backend/src/main/java/com/nexusops/crm \
  backend/src/test/java/com/nexusops/crm
git commit -m "feat(crm): import leads from a CSV file, all or nothing, with per-row errors"
```

---

### Task 6: Customers, Customer 360 summary and the CRM dashboard

**Files:**
- Create: `backend/src/main/java/com/nexusops/crm/CustomerRow.java`, `CustomerSummary.java`, `CustomerService.java`, `DashboardView.java`, `DashboardPeriods.java`, `DashboardService.java`, `MoneySums.java`
- Create: `backend/src/main/java/com/nexusops/crm/web/CustomerController.java`, `DashboardController.java`
- Modify: `backend/src/main/java/com/nexusops/crm/OpportunityService.java` (`boardOwner` → package-private static `meOrAll`)
- Test: `backend/src/test/java/com/nexusops/crm/CustomerApiIT.java`, `DashboardIT.java`, `DashboardPeriodsTest.java`

**Interfaces:**
- Consumes: `PartyService.list/get`, `PartyQuery`, `PartySummary`, `PartyView`, `PartyRoleType.CUSTOMER`; `PipelineService.ordered/view`; `OpportunityService.summaries`, `OpportunityRepository`; `MoneyTotal`; `TenantDirectory.currentSettings().timezone()`.
- Produces:
  - `CustomerRow(PartySummary party, long openCount, List<MoneyTotal> openValue, long wonCount, List<MoneyTotal> wonValue)`.
  - `CustomerSummary(PartyView party, long openCount, List<MoneyTotal> openValue, List<MoneyTotal> weightedValue, long wonCount, List<MoneyTotal> wonValue, long lostCount, long leadCount)`.
  - `DashboardView(LeadStats leads, PipelineStats pipeline)` with nested `LeadStats(Map<LeadStatus, Long> open, long newLast30Days, long converted90Days, long disqualified90Days, BigDecimal conversionRate)`, `PipelineStats(List<StageStats> stages, ClosedStats wonThisMonth, ClosedStats lostThisMonth, List<OpportunitySummary> closingSoon)`, `StageStats(StageView stage, long count, List<MoneyTotal> totals, List<MoneyTotal> weighted)`, `ClosedStats(long count, List<MoneyTotal> totals)`. A section the caller can't read is null.
  - `DashboardPeriods.monthStart(Instant now, ZoneId zone) : Instant`, `DashboardPeriods.today(Instant now, ZoneId zone) : LocalDate`.
  - Routes: `GET /api/v1/crm/customers?q=&page=&size=`, `GET /api/v1/crm/customers/{partyId}`, `GET /api/v1/crm/dashboard?owner=me|all`.

- [ ] **Step 1: Unit test the periods**

`backend/src/test/java/com/nexusops/crm/DashboardPeriodsTest.java`:

```java
package com.nexusops.crm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class DashboardPeriodsTest {

    final Instant lateOctoberUtc = Instant.parse("2026-10-31T20:00:00Z");

    @Test
    void theMonthStartsInTheWorkspaceTimeZone() {
        // 20:00 UTC on 31 October is already 1 November in India
        assertThat(DashboardPeriods.monthStart(lateOctoberUtc, ZoneId.of("Asia/Kolkata")))
                .isEqualTo(Instant.parse("2026-10-31T18:30:00Z"));
        assertThat(DashboardPeriods.monthStart(lateOctoberUtc, ZoneId.of("UTC")))
                .isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
    }

    @Test
    void todayIsTheWorkspaceDate() {
        assertThat(DashboardPeriods.today(lateOctoberUtc, ZoneId.of("Asia/Kolkata"))).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(DashboardPeriods.today(lateOctoberUtc, ZoneId.of("America/New_York"))).isEqualTo(LocalDate.of(2026, 10, 31));
    }
}
```

- [ ] **Step 2: Write the failing API tests**

`backend/src/test/java/com/nexusops/crm/CustomerApiIT.java`:

```java
package com.nexusops.crm;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestCrm;
import com.nexusops.support.TestMembers;
import com.nexusops.support.TestRoles;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Workspace;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class CustomerApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;
    UUID acme;
    List<String> stages;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("cust"));
        owner = Api.login(mvc, ws);
        TestCrm.enable(owner);
        acme = Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Acme\"}"));
        owner.put("/api/v1/parties/" + acme + "/roles/CUSTOMER", "{}").andExpect(status().isOk());
        Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Prospect Only\"}"));
        stages = Api.read(owner.get("/api/v1/crm/pipeline/stages"), "$[*].id");
    }

    private UUID deal(String amount, String currency, int stage) throws Exception {
        UUID id = Api.id(owner.post("/api/v1/opportunities", "{\"name\":\"D\",\"accountId\":\"" + acme + "\",\"amount\":"
                + amount + ",\"currency\":\"" + currency + "\"}"));
        if (stage > 0) {
            owner.post("/api/v1/opportunities/" + id + "/stage", "{\"stageId\":\"" + stages.get(stage) + "\",\"version\":0"
                    + (stage == 5 ? ",\"lostReason\":\"Price\"" : "") + "}").andExpect(status().isOk());
        }
        return id;
    }

    @Test
    void listsOnlyCustomersWithTheirDeals() throws Exception {
        deal("20", "USD", 0);
        deal("100", "USD", 4);
        owner.get("/api/v1/crm/customers").andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].party.name").value("Acme"))
                .andExpect(jsonPath("$.items[0].openCount").value(1))
                .andExpect(jsonPath("$.items[0].wonCount").value(1))
                .andExpect(jsonPath("$.items[0].openValue[0].amount").value(20.0))
                .andExpect(jsonPath("$.items[0].wonValue[0].amount").value(100.0));
        owner.get("/api/v1/crm/customers?q=zzz").andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void customerTotalsArePerCurrency() throws Exception {
        deal("100", "USD", 4);
        deal("50", "EUR", 4);
        deal("20", "USD", 2);
        deal("9", "USD", 5);
        owner.get("/api/v1/crm/customers/" + acme).andExpect(status().isOk())
                .andExpect(jsonPath("$.party.name").value("Acme"))
                .andExpect(jsonPath("$.wonCount").value(2))
                .andExpect(jsonPath("$.wonValue[?(@.currency == 'USD')].amount").value(Matchers.contains(100.0)))
                .andExpect(jsonPath("$.wonValue[?(@.currency == 'EUR')].amount").value(Matchers.contains(50.0)))
                .andExpect(jsonPath("$.openCount").value(1))
                .andExpect(jsonPath("$.weightedValue[0].amount").value(10.0)) // 20 × 50 %
                .andExpect(jsonPath("$.lostCount").value(1))
                .andExpect(jsonPath("$.leadCount").value(0));
    }

    @Test
    void countsLeadsConvertedIntoTheCustomer() throws Exception {
        UUID lead = Api.id(owner.post("/api/v1/leads", "{\"companyName\":\"Acme\"}"));
        owner.post("/api/v1/leads/" + lead + "/convert", "{\"organization\":{\"existingId\":\"" + acme + "\"},\"version\":0}")
                .andExpect(status().isOk());
        owner.get("/api/v1/crm/customers/" + acme).andExpect(jsonPath("$.leadCount").value(1));
        owner.get("/api/v1/leads?status=CONVERTED&partyId=" + acme).andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void customersNeedBothCrmAndDirectoryReadAccess() throws Exception {
        owner.get("/api/v1/crm/customers/" + UUID.randomUUID()).andExpect(status().isNotFound());
        UUID crmOnly = TestRoles.create(mvc, owner.session(), "CRM only", "crm.customer.read");
        Api a = Api.login(mvc, members.create(ws.tenantId(), Set.of(crmOnly)));
        a.get("/api/v1/crm/customers").andExpect(status().isForbidden());
        UUID dirOnly = TestRoles.create(mvc, owner.session(), "Directory only", "directory.party.read");
        Api b = Api.login(mvc, members.create(ws.tenantId(), Set.of(dirOnly)));
        b.get("/api/v1/crm/customers/" + acme).andExpect(status().isForbidden());
    }
}
```

`backend/src/test/java/com/nexusops/crm/DashboardIT.java`:

```java
package com.nexusops.crm;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestCrm;
import com.nexusops.support.TestMembers;
import com.nexusops.support.TestRoles;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Workspace;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class DashboardIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;
    UUID acme;
    List<String> stages;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("dash"));
        owner = Api.login(mvc, ws);
        TestCrm.enable(owner);
        acme = Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Acme\"}"));
        stages = Api.read(owner.get("/api/v1/crm/pipeline/stages"), "$[*].id");
    }

    private String today(int plusDays) {
        // the default workspace time zone is UTC (tenants.timezone default)
        return LocalDate.now(ZoneId.of("UTC")).plusDays(plusDays).toString();
    }

    @Test
    void anEmptyWorkspace() throws Exception {
        owner.get("/api/v1/crm/dashboard").andExpect(status().isOk())
                .andExpect(jsonPath("$.leads.open.NEW").value(0))
                .andExpect(jsonPath("$.leads.conversionRate").doesNotExist())
                .andExpect(jsonPath("$.pipeline.stages.length()").value(4))
                .andExpect(jsonPath("$.pipeline.wonThisMonth.count").value(0))
                .andExpect(jsonPath("$.pipeline.closingSoon", Matchers.empty()));
    }

    @Test
    void leadFiguresAndConversionRate() throws Exception {
        Api.id(owner.post("/api/v1/leads", "{\"lastName\":\"A\"}"));
        Api.id(owner.post("/api/v1/leads", "{\"lastName\":\"B\"}"));
        UUID contacted = Api.id(owner.post("/api/v1/leads", "{\"lastName\":\"C\"}"));
        owner.post("/api/v1/leads/" + contacted + "/status", "{\"status\":\"CONTACTED\",\"version\":0}").andExpect(status().isOk());
        UUID lost = Api.id(owner.post("/api/v1/leads", "{\"lastName\":\"D\"}"));
        owner.post("/api/v1/leads/" + lost + "/status", "{\"status\":\"DISQUALIFIED\",\"reason\":\"No\",\"version\":0}")
                .andExpect(status().isOk());
        UUID won = Api.id(owner.post("/api/v1/leads", "{\"companyName\":\"Acme\"}"));
        owner.post("/api/v1/leads/" + won + "/convert", "{\"organization\":{\"existingId\":\"" + acme + "\"},\"version\":0}")
                .andExpect(status().isOk());
        owner.get("/api/v1/crm/dashboard").andExpect(jsonPath("$.leads.open.NEW").value(2))
                .andExpect(jsonPath("$.leads.open.CONTACTED").value(1))
                .andExpect(jsonPath("$.leads.open.QUALIFIED").value(0))
                .andExpect(jsonPath("$.leads.newLast30Days").value(5))
                .andExpect(jsonPath("$.leads.converted90Days").value(1))
                .andExpect(jsonPath("$.leads.disqualified90Days").value(1))
                .andExpect(jsonPath("$.leads.conversionRate").value(0.5));
    }

    @Test
    void pipelineFigures() throws Exception {
        String open = "{\"name\":\"Soon\",\"accountId\":\"" + acme + "\",\"amount\":100,\"expectedCloseOn\":\"" + today(5) + "\"}";
        Api.id(owner.post("/api/v1/opportunities", open));
        Api.id(owner.post("/api/v1/opportunities", "{\"name\":\"Later\",\"accountId\":\"" + acme
                + "\",\"amount\":40,\"currency\":\"EUR\",\"expectedCloseOn\":\"" + today(40) + "\"}"));
        UUID won = Api.id(owner.post("/api/v1/opportunities", "{\"name\":\"Won\",\"accountId\":\"" + acme
                + "\",\"amount\":30,\"expectedCloseOn\":\"" + today(3) + "\"}"));
        owner.post("/api/v1/opportunities/" + won + "/stage", "{\"stageId\":\"" + stages.get(4) + "\",\"version\":0}")
                .andExpect(status().isOk());
        UUID lost = Api.id(owner.post("/api/v1/opportunities", "{\"name\":\"Lost\",\"accountId\":\"" + acme + "\"}"));
        owner.post("/api/v1/opportunities/" + lost + "/stage", "{\"stageId\":\"" + stages.get(5)
                + "\",\"lostReason\":\"Price\",\"version\":0}").andExpect(status().isOk());

        owner.get("/api/v1/crm/dashboard").andExpect(jsonPath("$.pipeline.stages[0].count").value(2))
                .andExpect(jsonPath("$.pipeline.stages[0].totals[?(@.currency == 'USD')].amount").value(Matchers.contains(100.0)))
                .andExpect(jsonPath("$.pipeline.stages[0].totals[?(@.currency == 'EUR')].amount").value(Matchers.contains(40.0)))
                .andExpect(jsonPath("$.pipeline.stages[0].weighted[?(@.currency == 'USD')].amount").value(Matchers.contains(10.0)))
                .andExpect(jsonPath("$.pipeline.wonThisMonth.count").value(1))
                .andExpect(jsonPath("$.pipeline.wonThisMonth.totals[0].amount").value(30.0))
                .andExpect(jsonPath("$.pipeline.lostThisMonth.count").value(1))
                .andExpect(jsonPath("$.pipeline.closingSoon[*].name").value(Matchers.contains("Soon")));
    }

    @Test
    void sectionsFollowPermissionsAndOwnerFilters() throws Exception {
        UUID leadsOnly = TestRoles.create(mvc, owner.session(), "Leads only", "crm.lead.read");
        Api a = Api.login(mvc, members.create(ws.tenantId(), Set.of(leadsOnly)));
        a.get("/api/v1/crm/dashboard").andExpect(status().isOk()).andExpect(jsonPath("$.pipeline").doesNotExist())
                .andExpect(jsonPath("$.leads").exists());
        UUID none = TestRoles.create(mvc, owner.session(), "No CRM", "directory.party.read");
        Api b = Api.login(mvc, members.create(ws.tenantId(), Set.of(none)));
        b.get("/api/v1/crm/dashboard").andExpect(status().isForbidden());

        Api.id(owner.post("/api/v1/leads", "{\"lastName\":\"Mine\"}"));
        UUID salesRole = TestRoles.create(mvc, owner.session(), "Sales", "crm.lead.read", "crm.lead.manage");
        Api seller = Api.login(mvc, members.create(ws.tenantId(), Set.of(salesRole)));
        Api.id(seller.post("/api/v1/leads", "{\"lastName\":\"Theirs\"}"));
        seller.get("/api/v1/crm/dashboard?owner=me").andExpect(jsonPath("$.leads.open.NEW").value(1));
        seller.get("/api/v1/crm/dashboard?owner=all").andExpect(jsonPath("$.leads.open.NEW").value(2));
        seller.get("/api/v1/crm/dashboard?owner=x").andExpect(status().isBadRequest());
    }
}
```

Check before you rely on it: the default `tenants.timezone` for a new workspace (V1/V5). If it is not `UTC`, read the workspace setting in `today(...)` via `GET /api/v1/tenant` instead of hard-coding `UTC`.

- [ ] **Step 3: Run the tests to verify they fail**

Run: `cd backend && ./gradlew test --tests '*CustomerApiIT' --tests '*DashboardIT' --tests '*DashboardPeriodsTest'`
Expected: FAIL — compilation (`DashboardPeriods` missing).

- [ ] **Step 4: Shared sums, records and periods**

`crm/MoneySums.java` — accumulates per-currency sums in a stable (alphabetical) order:

```java
package com.nexusops.crm;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Per-currency running totals; never adds different currencies. Null currency (no amount) is ignored. */
final class MoneySums {

    private final Map<String, BigDecimal> sums = new TreeMap<>();

    void add(String currency, BigDecimal amount) {
        if (currency != null && amount != null) {
            sums.merge(currency, amount, BigDecimal::add);
        }
    }

    List<MoneyTotal> totals() {
        return sums.entrySet().stream()
                .map(e -> new MoneyTotal(e.getKey(), e.getValue().setScale(4, RoundingMode.HALF_UP))).toList();
    }
}
```

`crm/CustomerRow.java`, `crm/CustomerSummary.java`:

```java
package com.nexusops.crm;

import com.nexusops.directory.PartySummary;
import java.util.List;

public record CustomerRow(PartySummary party, long openCount, List<MoneyTotal> openValue, long wonCount,
        List<MoneyTotal> wonValue) {}
```

```java
package com.nexusops.crm;

import com.nexusops.directory.PartyView;
import java.util.List;

/** Customer 360 header figures (D8): opportunities where the party is the account, and leads converted into it. */
public record CustomerSummary(PartyView party, long openCount, List<MoneyTotal> openValue,
        List<MoneyTotal> weightedValue, long wonCount, List<MoneyTotal> wonValue, long lostCount, long leadCount) {}
```

`crm/DashboardView.java`:

```java
package com.nexusops.crm;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** D14. A section is null when the caller can't read it. */
public record DashboardView(LeadStats leads, PipelineStats pipeline) {

    /** {@code open}: counts for NEW, CONTACTED, QUALIFIED. {@code conversionRate}: 0–1 over leads closed in 90 days. */
    public record LeadStats(Map<LeadStatus, Long> open, long newLast30Days, long converted90Days,
            long disqualified90Days, BigDecimal conversionRate) {}

    public record PipelineStats(List<StageStats> stages, ClosedStats wonThisMonth, ClosedStats lostThisMonth,
            List<OpportunitySummary> closingSoon) {}

    public record StageStats(StageView stage, long count, List<MoneyTotal> totals, List<MoneyTotal> weighted) {}

    public record ClosedStats(long count, List<MoneyTotal> totals) {}
}
```

`crm/DashboardPeriods.java`:

```java
package com.nexusops.crm;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Calendar periods in the workspace time zone (a month starts at local midnight on the 1st). */
final class DashboardPeriods {

    private DashboardPeriods() {}

    static Instant monthStart(Instant now, ZoneId zone) {
        return today(now, zone).withDayOfMonth(1).atStartOfDay(zone).toInstant();
    }

    static LocalDate today(Instant now, ZoneId zone) {
        return LocalDate.ofInstant(now, zone);
    }
}
```

- [ ] **Step 5: Customer service**

`crm/CustomerService.java`:

```java
package com.nexusops.crm;

import com.nexusops.directory.PartyQuery;
import com.nexusops.directory.PartyRoleType;
import com.nexusops.directory.PartyService;
import com.nexusops.directory.PartySummary;
import com.nexusops.directory.PartyView;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.web.PageResponse;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Customers are directory parties with an active CUSTOMER role (D7); this adds their sales figures. */
@Service
public class CustomerService {

    private static final class Stats {
        long open;
        long won;
        long lost;
        final MoneySums openValue = new MoneySums();
        final MoneySums weighted = new MoneySums();
        final MoneySums wonValue = new MoneySums();
    }

    private final PartyService parties;
    private final NamedParameterJdbcTemplate jdbc;

    CustomerService(PartyService parties, NamedParameterJdbcTemplate jdbc) {
        this.parties = parties;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public PageResponse<CustomerRow> list(String q, Integer page, Integer size) {
        PageResponse<PartySummary> found = parties.list(new PartyQuery(q, null, PartyRoleType.CUSTOMER, null, false),
                page, size);
        Map<UUID, Stats> stats = stats(found.items().stream().map(PartySummary::id).toList());
        return new PageResponse<>(found.items().stream().map(p -> {
            Stats s = stats.getOrDefault(p.id(), new Stats());
            return new CustomerRow(p, s.open, s.openValue.totals(), s.won, s.wonValue.totals());
        }).toList(), found.page(), found.size(), found.total());
    }

    @Transactional(readOnly = true)
    public CustomerSummary summary(UUID partyId) {
        PartyView party = parties.get(partyId);
        Stats s = stats(List.of(partyId)).getOrDefault(partyId, new Stats());
        Long leads = jdbc.queryForObject("""
                select count(*) from leads
                where tenant_id = :tenant and (converted_person_id = :party or converted_organization_id = :party)
                """, new MapSqlParameterSource("tenant", TenantContext.requireTenantId()).addValue("party", partyId),
                Long.class);
        return new CustomerSummary(party, s.open, s.openValue.totals(), s.weighted.totals(), s.won, s.wonValue.totals(),
                s.lost, leads == null ? 0 : leads);
    }

    private Map<UUID, Stats> stats(Collection<UUID> accountIds) {
        Map<UUID, Stats> result = new HashMap<>();
        if (accountIds.isEmpty()) {
            return result;
        }
        jdbc.query("""
                select o.account_id, s.kind, s.probability, o.currency, count(*) as n, sum(o.amount) as total
                from opportunities o join pipeline_stages s on s.id = o.stage_id and s.tenant_id = o.tenant_id
                where o.tenant_id = :tenant and o.account_id in (:ids)
                group by o.account_id, s.kind, s.probability, o.currency
                """, new MapSqlParameterSource("tenant", TenantContext.requireTenantId()).addValue("ids", accountIds),
                rs -> {
                    Stats s = result.computeIfAbsent(rs.getObject("account_id", UUID.class), k -> new Stats());
                    long n = rs.getLong("n");
                    String currency = rs.getString("currency");
                    BigDecimal total = rs.getBigDecimal("total");
                    switch (StageKind.valueOf(rs.getString("kind"))) {
                        case OPEN -> {
                            s.open += n;
                            s.openValue.add(currency, total);
                            s.weighted.add(currency, total == null ? null
                                    : total.multiply(BigDecimal.valueOf(rs.getInt("probability"))).movePointLeft(2));
                        }
                        case WON -> {
                            s.won += n;
                            s.wonValue.add(currency, total);
                        }
                        case LOST -> s.lost += n;
                    }
                });
        return result;
    }
}
```

`crm/web/CustomerController.java`:

```java
package com.nexusops.crm.web;

import com.nexusops.crm.CustomerRow;
import com.nexusops.crm.CustomerService;
import com.nexusops.crm.CustomerSummary;
import com.nexusops.shared.web.PageResponse;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/crm/customers")
class CustomerController {

    private final CustomerService customers;

    CustomerController(CustomerService customers) {
        this.customers = customers;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('crm.customer.read') and hasAuthority('directory.party.read')")
    PageResponse<CustomerRow> list(@RequestParam(required = false) String q, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return customers.list(q, page, size);
    }

    @GetMapping("/{partyId}")
    @PreAuthorize("hasAuthority('crm.customer.read') and hasAuthority('directory.party.read')")
    CustomerSummary summary(@PathVariable UUID partyId) {
        return customers.summary(partyId);
    }
}
```

- [ ] **Step 6: Dashboard service**

In `OpportunityService`, rename `private static UUID boardOwner(String raw)` to package-private `static UUID meOrAll(String raw)` and update its caller in `board`.

`crm/DashboardService.java`:

```java
package com.nexusops.crm;

import com.nexusops.crm.DashboardView.ClosedStats;
import com.nexusops.crm.DashboardView.LeadStats;
import com.nexusops.crm.DashboardView.PipelineStats;
import com.nexusops.crm.DashboardView.StageStats;
import com.nexusops.crm.domain.Opportunity;
import com.nexusops.crm.domain.OpportunityRepository;
import com.nexusops.crm.domain.PipelineStage;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.security.CurrentAuthorities;
import com.nexusops.tenancy.TenantDirectory;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The CRM dashboard (D14). Periods are in the workspace time zone; totals are per currency. */
@Service
public class DashboardService {

    private static final int CLOSING_SOON_DAYS = 30;
    private static final int CLOSING_SOON_LIMIT = 10;

    private final NamedParameterJdbcTemplate jdbc;
    private final PipelineService pipeline;
    private final OpportunityService opportunities;
    private final OpportunityRepository opportunityRows;
    private final TenantDirectory tenants;

    DashboardService(NamedParameterJdbcTemplate jdbc, PipelineService pipeline, OpportunityService opportunities,
            OpportunityRepository opportunityRows, TenantDirectory tenants) {
        this.jdbc = jdbc;
        this.pipeline = pipeline;
        this.opportunities = opportunities;
        this.opportunityRows = opportunityRows;
        this.tenants = tenants;
    }

    @Transactional(readOnly = true)
    public DashboardView dashboard(String owner) {
        UUID tenant = TenantContext.requireTenantId();
        UUID ownerId = OpportunityService.meOrAll(owner);
        Instant now = Instant.now();
        ZoneId zone = ZoneId.of(tenants.currentSettings().timezone());
        LeadStats leads = CurrentAuthorities.has(CrmPermissions.LEAD_READ) ? leads(tenant, ownerId, now) : null;
        PipelineStats pipelineStats = CurrentAuthorities.has(CrmPermissions.OPPORTUNITY_READ)
                ? pipeline(tenant, ownerId, now, zone) : null;
        return new DashboardView(leads, pipelineStats);
    }

    private LeadStats leads(UUID tenant, UUID ownerId, Instant now) {
        String mine = ownerId == null ? "" : " and owner_id = :owner";
        MapSqlParameterSource p = params(tenant, ownerId)
                .addValue("since30", Timestamp.from(now.minus(Duration.ofDays(30))))
                .addValue("since90", Timestamp.from(now.minus(Duration.ofDays(90))));
        Map<LeadStatus, Long> open = new EnumMap<>(LeadStatus.class);
        open.put(LeadStatus.NEW, 0L);
        open.put(LeadStatus.CONTACTED, 0L);
        open.put(LeadStatus.QUALIFIED, 0L);
        jdbc.query("select status, count(*) as n from leads where tenant_id = :tenant"
                + " and status in ('NEW', 'CONTACTED', 'QUALIFIED')" + mine + " group by status", p,
                rs -> {
                    open.put(LeadStatus.valueOf(rs.getString("status")), rs.getLong("n"));
                });
        Map<String, Object> row = jdbc.queryForMap("""
                select count(*) filter (where created_at >= :since30) as created,
                       count(*) filter (where status = 'CONVERTED' and converted_at >= :since90) as converted,
                       count(*) filter (where status = 'DISQUALIFIED' and disqualified_at >= :since90) as disqualified
                from leads where tenant_id = :tenant""" + mine, p);
        long converted = ((Number) row.get("converted")).longValue();
        long disqualified = ((Number) row.get("disqualified")).longValue();
        BigDecimal rate = converted + disqualified == 0 ? null
                : BigDecimal.valueOf(converted).divide(BigDecimal.valueOf(converted + disqualified), 4, RoundingMode.HALF_UP);
        return new LeadStats(open, ((Number) row.get("created")).longValue(), converted, disqualified, rate);
    }

    private PipelineStats pipeline(UUID tenant, UUID ownerId, Instant now, ZoneId zone) {
        String mine = ownerId == null ? "" : " and o.owner_id = :owner";
        MapSqlParameterSource p = params(tenant, ownerId)
                .addValue("monthStart", Timestamp.from(DashboardPeriods.monthStart(now, zone)));
        List<PipelineStage> stages = pipeline.ordered();
        Map<UUID, long[]> counts = new HashMap<>();
        Map<UUID, MoneySums> sums = new HashMap<>();
        Map<UUID, MoneySums> weighted = new HashMap<>();
        Map<UUID, Integer> probability = stages.stream()
                .collect(Collectors.toMap(PipelineStage::getId, PipelineStage::getProbability));
        jdbc.query("""
                select o.stage_id, o.currency, count(*) as n, sum(o.amount) as total
                from opportunities o join pipeline_stages s on s.id = o.stage_id and s.tenant_id = o.tenant_id
                where o.tenant_id = :tenant and s.kind = 'OPEN'""" + mine + " group by o.stage_id, o.currency", p,
                rs -> {
                    UUID stage = rs.getObject("stage_id", UUID.class);
                    counts.computeIfAbsent(stage, k -> new long[1])[0] += rs.getLong("n");
                    BigDecimal total = rs.getBigDecimal("total");
                    String currency = rs.getString("currency");
                    sums.computeIfAbsent(stage, k -> new MoneySums()).add(currency, total);
                    weighted.computeIfAbsent(stage, k -> new MoneySums()).add(currency, total == null ? null
                            : total.multiply(BigDecimal.valueOf(probability.get(stage))).movePointLeft(2));
                });
        List<StageStats> stageStats = new ArrayList<>();
        for (PipelineStage s : stages) {
            if (s.getKind() == StageKind.OPEN) {
                stageStats.add(new StageStats(PipelineService.view(s), counts.getOrDefault(s.getId(), new long[1])[0],
                        sums.getOrDefault(s.getId(), new MoneySums()).totals(),
                        weighted.getOrDefault(s.getId(), new MoneySums()).totals()));
            }
        }
        long[] wonCount = new long[1];
        long[] lostCount = new long[1];
        MoneySums wonSums = new MoneySums();
        jdbc.query("""
                select s.kind, o.currency, count(*) as n, sum(o.amount) as total
                from opportunities o join pipeline_stages s on s.id = o.stage_id and s.tenant_id = o.tenant_id
                where o.tenant_id = :tenant and s.kind in ('WON', 'LOST') and o.closed_at >= :monthStart""" + mine
                + " group by s.kind, o.currency", p,
                rs -> {
                    if (rs.getString("kind").equals("WON")) {
                        wonCount[0] += rs.getLong("n");
                        wonSums.add(rs.getString("currency"), rs.getBigDecimal("total"));
                    } else {
                        lostCount[0] += rs.getLong("n");
                    }
                });
        return new PipelineStats(stageStats, new ClosedStats(wonCount[0], wonSums.totals()),
                new ClosedStats(lostCount[0], List.of()), closingSoon(stages, ownerId, now, zone));
    }

    private List<OpportunitySummary> closingSoon(List<PipelineStage> stages, UUID ownerId, Instant now, ZoneId zone) {
        Set<UUID> open = stages.stream().filter(s -> s.getKind() == StageKind.OPEN).map(PipelineStage::getId)
                .collect(Collectors.toSet());
        LocalDate today = DashboardPeriods.today(now, zone);
        LocalDate until = today.plusDays(CLOSING_SOON_DAYS);
        Specification<Opportunity> spec = (root, cq, cb) -> cb.and(root.get("stageId").in(open),
                cb.between(root.get("expectedCloseOn"), today, until));
        if (ownerId != null) {
            spec = spec.and((root, cq, cb) -> cb.equal(root.get("ownerId"), ownerId));
        }
        List<Opportunity> soon = opportunityRows.findAll(spec, PageRequest.of(0, CLOSING_SOON_LIMIT,
                Sort.by(Sort.Order.asc("expectedCloseOn"), Sort.Order.asc("id")))).getContent();
        return opportunities.summaries(soon);
    }

    private static MapSqlParameterSource params(UUID tenant, UUID ownerId) {
        return new MapSqlParameterSource("tenant", tenant).addValue("owner", ownerId);
    }
}
```

`crm/web/DashboardController.java`:

```java
package com.nexusops.crm.web;

import com.nexusops.crm.DashboardService;
import com.nexusops.crm.DashboardView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class DashboardController {

    private final DashboardService dashboard;

    DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/api/v1/crm/dashboard")
    @PreAuthorize("hasAnyAuthority('crm.lead.read', 'crm.opportunity.read')")
    DashboardView dashboard(@RequestParam(required = false) String owner) {
        return dashboard.dashboard(owner);
    }
}
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `cd backend && ./gradlew test --tests '*CustomerApiIT' --tests '*DashboardIT' --tests '*DashboardPeriodsTest' --tests '*OpportunityApiIT'`
Expected: PASS. If `rs -> { ... }` is reported ambiguous between `RowCallbackHandler` and `ResultSetExtractor`, cast it: `(RowCallbackHandler) rs -> { ... }`.

- [ ] **Step 8: Run the whole backend suite, then commit**

Run: `cd backend && ./gradlew test` — Expected: PASS.

```bash
git add backend/src/main/java/com/nexusops/crm backend/src/test/java/com/nexusops/crm
git commit -m "feat(crm): customers with sales figures, Customer 360 summary and the CRM dashboard"
```

---
### Task 7: Workspace search and the related activity timeline

**Files:**
- Create: `backend/src/main/java/com/nexusops/collaboration/SearchHit.java`, `SearchService.java`, `SubjectKey.java`, `SubjectRelations.java`, `web/SearchController.java`
- Modify: `backend/src/main/java/com/nexusops/collaboration/SubjectResolver.java` (+ default `search`)
- Modify: `collaboration/ActivityService.java`, `collaboration/ActivityView.java`, `collaboration/web/ActivityController.java`, `collaboration/domain/ActivityRepository.java`
- Modify: `directory/PartySubjects.java`, `catalog/ProductSubjects.java`, `crm/LeadSubjects.java`, `crm/OpportunitySubjects.java` (+ `search`)
- Create: `backend/src/main/java/com/nexusops/crm/CrmRelations.java`
- Test: `backend/src/test/java/com/nexusops/collaboration/SearchApiIT.java`, `RelatedActivityIT.java`

**Interfaces:**
- Consumes: `LeadRepository`, `OpportunityRepository`, `Lead.getName()`, `PartyService.briefs` (Tasks 2–3).
- Produces:
  - `collaboration.SearchHit(String type, UUID id, String label, String detail, boolean archived)`.
  - `SubjectResolver.search(String pattern, int limit)` — default `List.of()`; `pattern` is a lower-case LIKE pattern from `Text.containsPattern` (escape char `\`).
  - `SearchService.search(String q) : List<SearchHit>` — 2–100 chars, ≤ 5 hits per readable type, ordered PARTY, LEAD, OPPORTUNITY, PRODUCT; route `GET /api/v1/search?q=`.
  - `collaboration.SubjectKey(String type, UUID id)`; `SubjectRelations.related(String type, UUID id) : List<SubjectKey>`.
  - `ActivityView` gains a last component `SubjectRef subject` (label null when the caller can't read it).
  - `GET /api/v1/activities?…&includeRelated=true`.

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/com/nexusops/collaboration/SearchApiIT.java`:

```java
package com.nexusops.collaboration;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestCrm;
import com.nexusops.support.TestMembers;
import com.nexusops.support.TestRoles;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Workspace;
import java.util.Set;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class SearchApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("srch"));
        owner = Api.login(mvc, ws);
        TestCrm.enable(owner);
        UUID org = Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Konkan Logistics\",\"domain\":\"konkan.test\"}"));
        owner.post("/api/v1/products", "{\"sku\":\"KON-1\",\"name\":\"Konkan crate\"}").andExpect(status().isCreated());
        owner.post("/api/v1/leads", "{\"companyName\":\"Konkan Traders\"}").andExpect(status().isCreated());
        owner.post("/api/v1/opportunities", "{\"name\":\"Konkan renewal\",\"accountId\":\"" + org + "\"}")
                .andExpect(status().isCreated());
    }

    @Test
    void findsEveryReadableRecordTypeInAFixedOrder() throws Exception {
        owner.get("/api/v1/search?q=KONKAN").andExpect(status().isOk())
                .andExpect(jsonPath("$[*].type").value(Matchers.contains("PARTY", "LEAD", "OPPORTUNITY", "PRODUCT")))
                .andExpect(jsonPath("$[0].label").value("Konkan Logistics"))
                .andExpect(jsonPath("$[0].detail").value("konkan.test"))
                .andExpect(jsonPath("$[2].detail").value("Konkan Logistics"))
                .andExpect(jsonPath("$[3].detail").value("KON-1"));
    }

    @Test
    void returnsAtMostFivePerType() throws Exception {
        for (int i = 0; i < 7; i++) {
            owner.post("/api/v1/organizations", "{\"name\":\"Zebra " + i + "\"}").andExpect(status().isCreated());
        }
        owner.get("/api/v1/search?q=zebra").andExpect(jsonPath("$.length()").value(5));
    }

    @Test
    void onlyTypesTheCallerCanRead() throws Exception {
        UUID role = TestRoles.create(mvc, owner.session(), "Directory reader", "directory.party.read");
        Api reader = Api.login(mvc, members.create(ws.tenantId(), Set.of(role)));
        reader.get("/api/v1/search?q=konkan").andExpect(jsonPath("$[*].type").value(Matchers.contains("PARTY")));
        owner.put("/api/v1/tenant/modules/CRM", "{\"enabled\":false}").andExpect(status().isOk());
        owner.get("/api/v1/search?q=konkan").andExpect(jsonPath("$[*].type").value(Matchers.contains("PARTY", "PRODUCT")));
    }

    @Test
    void queriesAreBoundedAndWildcardsAreLiteral() throws Exception {
        owner.get("/api/v1/search?q=k").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("q"));
        owner.get("/api/v1/search?q=" + "x".repeat(101)).andExpect(status().isBadRequest());
        owner.get("/api/v1/search?q=%25%25").andExpect(jsonPath("$.length()").value(0));
        owner.get("/api/v1/search?q=__").andExpect(jsonPath("$.length()").value(0));
    }
}
```

`backend/src/test/java/com/nexusops/collaboration/RelatedActivityIT.java`:

```java
package com.nexusops.collaboration;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestCrm;
import com.nexusops.support.TestMembers;
import com.nexusops.support.TestRoles;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Workspace;
import java.util.Set;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class RelatedActivityIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;
    UUID acme;

    private void note(String type, UUID id, String summary) throws Exception {
        owner.post("/api/v1/activities", "{\"subjectType\":\"" + type + "\",\"subjectId\":\"" + id
                + "\",\"type\":\"NOTE\",\"summary\":\"" + summary + "\"}").andExpect(status().isCreated());
    }

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("rel"));
        owner = Api.login(mvc, ws);
        TestCrm.enable(owner);
        acme = Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Acme\"}"));
        UUID other = Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Other\"}"));
        UUID lead = Api.id(owner.post("/api/v1/leads", "{\"companyName\":\"Acme\"}"));
        note("LEAD", lead, "First call");
        owner.post("/api/v1/leads/" + lead + "/convert", "{\"organization\":{\"existingId\":\"" + acme + "\"},\"version\":0}")
                .andExpect(status().isOk());
        UUID deal = Api.id(owner.post("/api/v1/opportunities", "{\"name\":\"Renewal\",\"accountId\":\"" + acme + "\"}"));
        note("OPPORTUNITY", deal, "Pricing call");
        note("PARTY", acme, "Kick-off");
        note("PARTY", other, "Unrelated");
    }

    @Test
    void theCustomerTimelineIncludesItsLeadsAndOpportunities() throws Exception {
        owner.get("/api/v1/activities?subjectType=PARTY&subjectId=" + acme + "&includeRelated=true")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items[*].summary").value(Matchers.contains("Kick-off", "Pricing call", "First call")))
                .andExpect(jsonPath("$.items[1].subject.type").value("OPPORTUNITY"))
                .andExpect(jsonPath("$.items[1].subject.label").value("Renewal"))
                .andExpect(jsonPath("$.items[2].subject.type").value("LEAD"));
        owner.get("/api/v1/activities?subjectType=PARTY&subjectId=" + acme)
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].subject.label").value("Acme"));
    }

    @Test
    void relatedRecordsTheCallerCannotReadAreLeftOut() throws Exception {
        UUID role = TestRoles.create(mvc, owner.session(), "Directory and leads", "directory.party.read", "crm.lead.read");
        Api reader = Api.login(mvc, members.create(ws.tenantId(), Set.of(role)));
        reader.get("/api/v1/activities?subjectType=PARTY&subjectId=" + acme + "&includeRelated=true")
                .andExpect(jsonPath("$.items[*].summary").value(Matchers.contains("Kick-off", "First call")));
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && ./gradlew test --tests '*SearchApiIT' --tests '*RelatedActivityIT'`
Expected: FAIL — 404 on `/api/v1/search`; `subject` missing on activities.

- [ ] **Step 3: Collaboration SPIs and search**

`collaboration/SearchHit.java`:

```java
package com.nexusops.collaboration;

import java.util.UUID;

/** A search result: {@code detail} is a short hint (email, SKU, company…), may be null. */
public record SearchHit(String type, UUID id, String label, String detail, boolean archived) {}
```

Add to `SubjectResolver` (imports `java.util.List`):

```java
    /**
     * Up to {@code limit} records matching {@code pattern}, a lower-case LIKE pattern from Text.containsPattern
     * (escape character '\'). Types that aren't searchable keep the default.
     */
    default List<SearchHit> search(String pattern, int limit) {
        return List.of();
    }
```

`collaboration/SubjectKey.java`:

```java
package com.nexusops.collaboration;

import java.util.UUID;

public record SubjectKey(String type, UUID id) {}
```

`collaboration/SubjectRelations.java`:

```java
package com.nexusops.collaboration;

import java.util.List;
import java.util.UUID;

/**
 * Implemented by modules that know which other records belong to a subject (e.g. CRM: a customer's opportunities).
 * Used to merge timelines (spec D10). Tenant-scoped; return at most a few hundred keys.
 */
public interface SubjectRelations {

    List<SubjectKey> related(String type, UUID id);
}
```

`collaboration/SearchService.java`:

```java
package com.nexusops.collaboration;

import com.nexusops.shared.TenantContext;
import com.nexusops.shared.Text;
import com.nexusops.shared.security.CurrentAuthorities;
import com.nexusops.shared.web.ApiProblem;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Workspace search (D12): contains-match over every subject type the caller may read, 5 hits per type. */
@Service
public class SearchService {

    static final List<String> ORDER = List.of("PARTY", "LEAD", "OPPORTUNITY", "PRODUCT");
    static final int PER_TYPE = 5;

    private final List<SubjectResolver> resolvers;

    SearchService(List<SubjectResolver> resolvers) {
        this.resolvers = resolvers;
    }

    @Transactional(readOnly = true)
    public List<SearchHit> search(String q) {
        TenantContext.requireTenantId();
        String text = q == null ? "" : q.strip();
        if (text.length() < 2 || text.length() > 100) {
            throw ApiProblem.badRequestField("q", "Enter 2 to 100 characters.");
        }
        String pattern = Text.containsPattern(text);
        return resolvers.stream().filter(r -> CurrentAuthorities.has(r.readPermission()))
                .sorted(Comparator.comparingInt(r -> rank(r.type())))
                .flatMap(r -> r.search(pattern, PER_TYPE).stream()).toList();
    }

    private static int rank(String type) {
        int index = ORDER.indexOf(type);
        return index < 0 ? ORDER.size() : index;
    }
}
```

`collaboration/web/SearchController.java`:

```java
package com.nexusops.collaboration.web;

import com.nexusops.collaboration.SearchHit;
import com.nexusops.collaboration.SearchService;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SearchController {

    private final SearchService search;

    SearchController(SearchService search) {
        this.search = search;
    }

    /** Authorization is per record type (each type's read permission), applied by SearchService. */
    @GetMapping("/api/v1/search")
    @PreAuthorize("isAuthenticated()")
    List<SearchHit> search(@RequestParam(required = false) String q) {
        return search.search(q);
    }
}
```

- [ ] **Step 4: Searchable resolvers**

`PartySubjects` — add (imports `SearchHit`, `PageRequest`, `Sort`, `Specification`, `List`; `PartyRepository` already extends `JpaSpecificationExecutor`):

```java
    @Override
    public List<SearchHit> search(String pattern, int limit) {
        Specification<Party> spec = (root, cq, cb) -> cb.and(cb.isNull(root.get("archivedAt")),
                cb.or(cb.like(cb.lower(root.get("name")), pattern, '\\'), cb.like(root.get("email"), pattern, '\\'),
                        cb.like(root.get("domain"), pattern, '\\')));
        return parties.findAll(spec, PageRequest.of(0, limit, Sort.by("name", "id"))).stream()
                .map(p -> new SearchHit(TYPE, p.getId(), p.getName(),
                        p.getEmail() != null ? p.getEmail() : p.getDomain(), false))
                .toList();
    }
```

`ProductSubjects` — add (`ProductRepository` already extends `JpaSpecificationExecutor`):

```java
    @Override
    public List<SearchHit> search(String pattern, int limit) {
        Specification<Product> spec = (root, cq, cb) -> cb.and(cb.isNull(root.get("archivedAt")),
                cb.or(cb.like(cb.lower(root.get("sku")), pattern, '\\'), cb.like(cb.lower(root.get("name")), pattern, '\\')));
        return products.findAll(spec, PageRequest.of(0, limit, Sort.by("name", "id"))).stream()
                .map(p -> new SearchHit(TYPE, p.getId(), p.getName(), p.getSku(), false)).toList();
    }
```

`LeadSubjects` — add:

```java
    @Override
    public List<SearchHit> search(String pattern, int limit) {
        Specification<Lead> spec = (root, cq, cb) -> cb.or(cb.like(cb.lower(root.get("firstName")), pattern, '\\'),
                cb.like(cb.lower(root.get("lastName")), pattern, '\\'),
                cb.like(cb.lower(root.get("companyName")), pattern, '\\'), cb.like(root.get("email"), pattern, '\\'));
        return leads.findAll(spec, PageRequest.of(0, limit, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"))))
                .stream().map(l -> new SearchHit(TYPE, l.getId(), l.getName(),
                        l.getName().equals(l.getCompanyName()) ? l.getEmail() : l.getCompanyName(),
                        l.getStatus() == LeadStatus.CONVERTED))
                .toList();
    }
```

`OpportunitySubjects` — inject `PartyService parties` (constructor) and add:

```java
    @Override
    public List<SearchHit> search(String pattern, int limit) {
        Specification<Opportunity> spec = (root, cq, cb) -> cb.like(cb.lower(root.get("name")), pattern, '\\');
        List<Opportunity> found = opportunities.findAll(spec,
                PageRequest.of(0, limit, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id")))).getContent();
        Map<UUID, PartyBrief> accounts = parties.briefs(found.stream().map(Opportunity::getAccountId)
                .collect(Collectors.toSet()));
        return found.stream().map(o -> new SearchHit(TYPE, o.getId(), o.getName(),
                accounts.containsKey(o.getAccountId()) ? accounts.get(o.getAccountId()).name() : null, false)).toList();
    }
```

- [ ] **Step 5: Related timeline**

`collaboration/domain/ActivityRepository.java` — also extend `JpaSpecificationExecutor<Activity>` (keep the existing finder).

`ActivityView` gains `SubjectRef subject` as its last component:

```java
public record ActivityView(UUID id, String subjectType, UUID subjectId, ActivityType type, String summary, String body,
        Instant occurredAt, MemberRef author, Instant createdAt, SubjectRef subject) {}
```

`ActivityService` — constructor also takes `List<SubjectRelations> relations`; replace `list` and `view`:

```java
    private static final int MAX_RELATED = 200;

    @Transactional(readOnly = true)
    public PageResponse<ActivityView> list(String subjectType, UUID subjectId, boolean includeRelated, Integer page,
            Integer size) {
        SubjectRef subject = subjects.requireReadable(subjectType, subjectId);
        Map<String, Set<UUID>> keys = new LinkedHashMap<>();
        keys.computeIfAbsent(subject.type(), k -> new LinkedHashSet<>()).add(subject.id());
        if (includeRelated) {
            relations.stream().flatMap(r -> r.related(subject.type(), subject.id()).stream())
                    .filter(k -> subjects.canRead(k.type())).limit(MAX_RELATED)
                    .forEach(k -> keys.computeIfAbsent(k.type(), t -> new LinkedHashSet<>()).add(k.id()));
        }
        Specification<Activity> spec = (root, cq, cb) -> cb.or(keys.entrySet().stream()
                .map(e -> cb.and(cb.equal(root.get("subjectType"), e.getKey()), root.get("subjectId").in(e.getValue())))
                .toArray(jakarta.persistence.criteria.Predicate[]::new));
        Page<Activity> result = activities.findAll(spec,
                Paging.of(page, size, Sort.by(Sort.Direction.DESC, "occurredAt", "id")));
        Map<UUID, Members.Member> authors = members.findAll(
                result.getContent().stream().map(Activity::getAuthorId).filter(Objects::nonNull).toList());
        Map<String, Map<UUID, SubjectRef>> labels = new HashMap<>();
        result.getContent().stream().collect(Collectors.groupingBy(Activity::getSubjectType,
                        Collectors.mapping(Activity::getSubjectId, Collectors.toSet())))
                .forEach((type, ids) -> labels.put(type, subjects.labels(type, ids)));
        return PageResponse.from(result, a -> view(a, authors, labels.getOrDefault(a.getSubjectType(), Map.of())
                .getOrDefault(a.getSubjectId(), new SubjectRef(a.getSubjectType(), a.getSubjectId(), null, false))));
    }
```

In `log(...)`, return `view(activity, members.findAll(List.of(author)), subject)`, and change `view`:

```java
    private static ActivityView view(Activity a, Map<UUID, Members.Member> authors, SubjectRef subject) {
        Members.Member author = a.getAuthorId() == null ? null : authors.get(a.getAuthorId());
        return new ActivityView(a.getId(), a.getSubjectType(), a.getSubjectId(), a.getType(), a.getSummary(),
                a.getBody(), a.getOccurredAt(), author == null ? null : new MemberRef(author.id(), author.name()),
                a.getCreatedAt(), subject);
    }
```

`ActivityController.list` gains `@RequestParam(defaultValue = "false") boolean includeRelated` and passes it through.

`crm/CrmRelations.java`:

```java
package com.nexusops.crm;

import com.nexusops.collaboration.SubjectKey;
import com.nexusops.collaboration.SubjectRelations;
import com.nexusops.crm.domain.Lead;
import com.nexusops.crm.domain.LeadRepository;
import com.nexusops.crm.domain.Opportunity;
import com.nexusops.crm.domain.OpportunityRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/** D10: a party's timeline includes its opportunities (as account or contact) and the leads converted into it;
 * an opportunity's includes its source lead. */
@Component
class CrmRelations implements SubjectRelations {

    private static final int LIMIT = 100;

    private final OpportunityRepository opportunities;
    private final LeadRepository leads;

    CrmRelations(OpportunityRepository opportunities, LeadRepository leads) {
        this.opportunities = opportunities;
        this.leads = leads;
    }

    @Override
    public List<SubjectKey> related(String type, UUID id) {
        List<SubjectKey> keys = new ArrayList<>();
        if (type.equals("PARTY")) {
            Specification<Opportunity> deals = (root, cq, cb) -> cb.or(cb.equal(root.get("accountId"), id),
                    cb.equal(root.get("contactId"), id));
            opportunities.findAll(deals, PageRequest.of(0, LIMIT))
                    .forEach(o -> keys.add(new SubjectKey(OpportunitySubjects.TYPE, o.getId())));
            Specification<Lead> converted = (root, cq, cb) -> cb.or(cb.equal(root.get("convertedPersonId"), id),
                    cb.equal(root.get("convertedOrganizationId"), id));
            leads.findAll(converted, PageRequest.of(0, LIMIT))
                    .forEach(l -> keys.add(new SubjectKey(LeadSubjects.TYPE, l.getId())));
        } else if (type.equals(OpportunitySubjects.TYPE)) {
            opportunities.findById(id).map(Opportunity::getLeadId)
                    .ifPresent(lead -> keys.add(new SubjectKey(LeadSubjects.TYPE, lead)));
        }
        return keys;
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `cd backend && ./gradlew test --tests '*SearchApiIT' --tests '*RelatedActivityIT' --tests '*ActivityApiIT' --tests '*ModularityTest' --tests '*EndpointAuthorizationCoverageTest'`
Expected: PASS. `ActivityApiIT` must still pass unchanged (the new `subject` member is additive).

- [ ] **Step 7: Run the whole backend suite, then commit**

Run: `cd backend && ./gradlew test` — Expected: PASS.

```bash
git add backend/src/main/java/com/nexusops/collaboration backend/src/main/java/com/nexusops/directory \
  backend/src/main/java/com/nexusops/catalog backend/src/main/java/com/nexusops/crm \
  backend/src/test/java/com/nexusops/collaboration
git commit -m "feat(search): workspace search across records, and customer timelines that include their deals and leads"
```

---

### Task 8: Isolation proof, API contract and the decision record

**Files:**
- Create: `backend/src/test/java/com/nexusops/CrmRlsIT.java`
- Modify: `backend/src/test/java/com/nexusops/RlsCoverageIT.java`, `CrossTenantApiIT.java`, `OpenApiContractIT.java`
- Create: `backend/src/test/java/com/nexusops/crm/CrmModuleGateIT.java`
- Create: `docs/decisions/0010-crm-leads-pipeline-customers.md`
- Modify: `docs/api/openapi.json` (re-exported)

**Interfaces:**
- Consumes: every route from Tasks 1–7.
- Produces: no production code. If a test here exposes a defect, fix it in the owning class with a test.

- [ ] **Step 1: RLS coverage and raw-SQL isolation**

In `RlsCoverageIT.EXPECTED_TENANT_TABLES` add `"pipeline_stages", "leads", "opportunities"`.

`backend/src/test/java/com/nexusops/CrmRlsIT.java`:

```java
package com.nexusops;

import static com.nexusops.support.IntegrationTestSupport.APP_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** The database alone isolates every Phase 5 table, independent of application code. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CrmRlsIT extends IntegrationTestSupport {

    static final List<String> TABLES = List.of("pipeline_stages", "leads", "opportunities");

    final UUID tenantA = UUID.randomUUID();
    final UUID tenantB = UUID.randomUUID();
    final UUID party = UUID.randomUUID();
    final UUID stage = UUID.randomUUID();
    final UUID lead = UUID.randomUUID();
    final UUID opportunity = UUID.randomUUID();

    @BeforeAll
    void seedTenantB() {
        Timestamp now = Timestamp.from(Instant.now());
        for (UUID t : new UUID[] {tenantA, tenantB}) {
            OwnerJdbc.jdbc().update("insert into tenants (id, slug, name, status, plan_code, created_at, updated_at) "
                    + "values (?, ?, 'T', 'ACTIVE', 'FREE', ?, ?)", t, "crm-" + t.toString().substring(0, 8), now, now);
        }
        JdbcTemplate b = OwnerJdbc.ownerAs(tenantB);
        b.update("insert into parties (id, tenant_id, kind, name, name_key, created_at, updated_at) "
                + "values (?, ?, 'ORGANIZATION', 'Beta', 'beta', ?, ?)", party, tenantB, now, now);
        b.update("insert into pipeline_stages (id, tenant_id, name, name_key, position, probability, kind, created_at, "
                + "updated_at) values (?, ?, 'Open', 'open', 0, 10, 'OPEN', ?, ?)", stage, tenantB, now, now);
        b.update("insert into leads (id, tenant_id, company_name, source, status, created_at, updated_at) "
                + "values (?, ?, 'Beta lead', 'OTHER', 'NEW', ?, ?)", lead, tenantB, now, now);
        b.update("insert into opportunities (id, tenant_id, name, account_id, stage_id, created_at, updated_at) "
                + "values (?, ?, 'Beta deal', ?, ?, ?, ?)", opportunity, tenantB, party, stage, now, now);
    }

    private static JdbcTemplate app(String tenantSetting) {
        return OwnerJdbc.tenantScoped("nexusops_app", APP_PASSWORD, tenantSetting);
    }

    @Test
    void anotherTenantsContextAndNoContextSeeNothing() {
        for (String table : TABLES) {
            assertThat(app(tenantA.toString()).queryForObject("select count(*) from " + table, Long.class)).as(table).isZero();
            assertThat(app("").queryForObject("select count(*) from " + table, Long.class)).as(table).isZero();
            assertThat(app(tenantB.toString()).queryForObject("select count(*) from " + table, Long.class)).as(table)
                    .isPositive();
        }
    }

    private void insertTenantBRow(JdbcTemplate as, String table) {
        Timestamp now = Timestamp.from(Instant.now());
        UUID id = UUID.randomUUID();
        switch (table) {
            case "pipeline_stages" -> as.update("insert into pipeline_stages (id, tenant_id, name, name_key, position, "
                    + "probability, kind, created_at, updated_at) values (?, ?, 'Evil', 'evil', 1, 5, 'OPEN', ?, ?)",
                    id, tenantB, now, now);
            case "leads" -> as.update("insert into leads (id, tenant_id, company_name, source, status, created_at, "
                    + "updated_at) values (?, ?, 'Evil', 'OTHER', 'NEW', ?, ?)", id, tenantB, now, now);
            case "opportunities" -> as.update("insert into opportunities (id, tenant_id, name, account_id, stage_id, "
                    + "created_at, updated_at) values (?, ?, 'Evil', ?, ?, ?, ?)", id, tenantB, party, stage, now, now);
            default -> throw new IllegalArgumentException(table);
        }
    }

    @Test
    void insertsIntoAnotherTenantAreRejectedByRowLevelSecurity() {
        for (String table : TABLES) {
            assertThatThrownBy(() -> insertTenantBRow(app(tenantA.toString()), table)).as(table + " as tenant A")
                    .isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> insertTenantBRow(app(""), table)).as(table + " without tenant context")
                    .isInstanceOf(DataAccessException.class);
        }
    }

    @Test
    void updatesAndDeletesOfAnotherTenantsRowsAffectNothing() {
        record Case(String update, String delete, UUID id) {}
        for (Case c : List.of(
                new Case("update opportunities set name = 'Evil' where id = ?", "delete from opportunities where id = ?",
                        opportunity),
                new Case("update leads set company_name = 'Evil' where id = ?", "delete from leads where id = ?", lead),
                new Case("update pipeline_stages set name = 'Evil' where id = ?",
                        "delete from pipeline_stages where id = ?", stage))) {
            assertThat(app(tenantA.toString()).update(c.update(), c.id())).isZero();
            assertThat(app(tenantA.toString()).update(c.delete(), c.id())).isZero();
        }
        for (String table : TABLES) {
            assertThat(OwnerJdbc.ownerAs(tenantB).queryForObject("select count(*) from " + table, Long.class)).as(table)
                    .isPositive();
        }
    }
}
```

(The tenant-A insert into `opportunities` references tenant B's party and stage; the composite FKs and the policy both reject it — the test only asserts that it fails.) If `APP_PASSWORD` is not a public static on `IntegrationTestSupport`, copy how `CanonicalRlsIT` obtains it.

- [ ] **Step 2: Cross-tenant API probes**

In `CrossTenantApiIT`:
- add fields `UUID leadB, opportunityB, stageB;`
- at the end of `twoTenants()` (CRM is already enabled for B there; enable it for A too):

```java
        as(ownerA, put("/api/v1/tenant/modules/CRM").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isOk());
        leadB = com.nexusops.support.Api.id(apiB.post("/api/v1/leads", "{\"companyName\":\"Beta prospect\"}"));
        opportunityB = com.nexusops.support.Api.id(apiB.post("/api/v1/opportunities",
                "{\"name\":\"Beta deal\",\"accountId\":\"" + orgB + "\"}"));
        stageB = UUID.fromString(com.nexusops.support.Api.read(apiB.get("/api/v1/crm/pipeline/stages"), "$[0].id"));
        apiB.post("/api/v1/activities", "{\"subjectType\":\"OPPORTUNITY\",\"subjectId\":\"" + opportunityB
                + "\",\"type\":\"NOTE\",\"summary\":\"B deal secret\"}").andExpect(status().isCreated());
```

- add the test:

```java
    @Test
    void crmRecordsOfAnotherTenantAreInvisibleAndUntouchable() throws Exception {
        as(ownerA, get("/api/v1/leads/" + leadB)).andExpect(status().isNotFound());
        as(ownerA, json(put("/api/v1/leads/" + leadB), "{\"lastName\":\"Hacked\",\"version\":0}")).andExpect(status().isNotFound());
        as(ownerA, json(post("/api/v1/leads/" + leadB + "/status"), "{\"status\":\"CONTACTED\",\"version\":0}"))
                .andExpect(status().isNotFound());
        as(ownerA, json(post("/api/v1/leads/" + leadB + "/convert"), "{\"organization\":{\"name\":\"X\"},\"version\":0}"))
                .andExpect(status().isNotFound());
        as(ownerA, get("/api/v1/opportunities/" + opportunityB)).andExpect(status().isNotFound());
        as(ownerA, json(put("/api/v1/opportunities/" + opportunityB), "{\"name\":\"H\",\"accountId\":\"" + orgB
                + "\",\"version\":0}")).andExpect(status().isNotFound());
        as(ownerA, json(post("/api/v1/opportunities/" + opportunityB + "/stage"), "{\"stageId\":\"" + stageB
                + "\",\"version\":0}")).andExpect(status().isNotFound());
        as(ownerA, json(put("/api/v1/crm/pipeline/stages/" + stageB), "{\"name\":\"H\",\"probability\":1,\"version\":0}"))
                .andExpect(status().isNotFound());
        as(ownerA, delete("/api/v1/crm/pipeline/stages/" + stageB)).andExpect(status().isNotFound());
        as(ownerA, get("/api/v1/crm/customers/" + orgB)).andExpect(status().isNotFound());
        as(ownerA, get("/api/v1/activities").param("subjectType", "OPPORTUNITY").param("subjectId", opportunityB.toString()))
                .andExpect(status().isNotFound());

        // references to another tenant's rows inside request bodies are refused
        as(ownerA, json(post("/api/v1/opportunities"), "{\"name\":\"X\",\"accountId\":\"" + orgB + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("accountId"));
        String orgA = JsonPath.read(as(ownerA, json(post("/api/v1/organizations"), "{\"name\":\"Alpha\"}"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        as(ownerA, json(post("/api/v1/opportunities"), "{\"name\":\"X\",\"accountId\":\"" + orgA + "\",\"stageId\":\""
                + stageB + "\"}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("stageId"));
        as(ownerA, json(post("/api/v1/leads"), "{\"lastName\":\"X\",\"ownerId\":\"" + userBId + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("ownerId"));
        String leadA = JsonPath.read(as(ownerA, json(post("/api/v1/leads"), "{\"lastName\":\"Alpha lead\"}"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        as(ownerA, json(post("/api/v1/leads/" + leadA + "/convert"), "{\"organization\":{\"existingId\":\"" + orgB
                + "\"},\"version\":0}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("organization.existingId"));

        // lists, search, board and dashboard never contain B's rows
        as(ownerA, get("/api/v1/leads")).andExpect(jsonPath("$.items[*].name", Matchers.not(Matchers.hasItem("Beta prospect"))));
        as(ownerA, get("/api/v1/opportunities")).andExpect(jsonPath("$.total").value(0));
        as(ownerA, get("/api/v1/search").param("q", "beta")).andExpect(jsonPath("$.length()").value(0));
        as(ownerA, get("/api/v1/crm/pipeline/board")).andExpect(jsonPath("$.columns[0].count").value(0));
        as(ownerA, get("/api/v1/crm/customers")).andExpect(jsonPath("$.total").value(0));

        // tenant B is untouched
        as(ownerB, get("/api/v1/leads/" + leadB)).andExpect(jsonPath("$.status").value("NEW"));
        as(ownerB, get("/api/v1/opportunities/" + opportunityB)).andExpect(jsonPath("$.name").value("Beta deal"))
                .andExpect(jsonPath("$.stage.id").value(stageB.toString()));
    }
```

`canonicalListsNeverContainAnotherTenantsRows` asserts `ownerB` sees 2 parties; with the setup above that still holds (no new parties for B).

- [ ] **Step 3: Module gate**

`backend/src/test/java/com/nexusops/crm/CrmModuleGateIT.java`:

```java
package com.nexusops.crm;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestCrm;
import com.nexusops.support.TestTenants;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** Disabling CRM removes every CRM permission, so every CRM route answers 403 (spec success criterion 4). */
@AutoConfigureMockMvc
class CrmModuleGateIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;

    @Test
    void everyCrmRouteIsForbiddenWhileTheModuleIsOff() throws Exception {
        Api owner = Api.login(mvc, TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("gate")));
        TestCrm.enable(owner);
        UUID lead = Api.id(owner.post("/api/v1/leads", "{\"lastName\":\"Gate\"}"));
        owner.put("/api/v1/tenant/modules/CRM", "{\"enabled\":false}").andExpect(status().isOk());
        owner.get("/api/v1/leads").andExpect(status().isForbidden());
        owner.get("/api/v1/leads/" + lead).andExpect(status().isForbidden());
        owner.post("/api/v1/leads", "{\"lastName\":\"X\"}").andExpect(status().isForbidden());
        owner.get("/api/v1/opportunities").andExpect(status().isForbidden());
        owner.get("/api/v1/crm/pipeline/stages").andExpect(status().isForbidden());
        owner.get("/api/v1/crm/pipeline/board").andExpect(status().isForbidden());
        owner.get("/api/v1/crm/customers").andExpect(status().isForbidden());
        owner.get("/api/v1/crm/dashboard").andExpect(status().isForbidden());
        owner.get("/api/v1/crm/owners").andExpect(status().isForbidden());
        owner.get("/api/v1/activities?subjectType=LEAD&subjectId=" + lead).andExpect(status().isForbidden());
        TestCrm.enable(owner);
        owner.get("/api/v1/leads/" + lead).andExpect(status().isOk());
    }
}
```

- [ ] **Step 4: API contract**

In `OpenApiContractIT`, add to the `contains(...)` list: `"\"/api/v1/leads\""`, `"\"/api/v1/leads/{id}\""`, `"\"/api/v1/leads/{id}/status\""`, `"\"/api/v1/leads/{id}/convert\""`, `"\"/api/v1/leads/import\""`, `"\"/api/v1/opportunities\""`, `"\"/api/v1/opportunities/{id}\""`, `"\"/api/v1/opportunities/{id}/stage\""`, `"\"/api/v1/crm/pipeline/stages\""`, `"\"/api/v1/crm/pipeline/stages/{id}\""`, `"\"/api/v1/crm/pipeline/stages/order\""`, `"\"/api/v1/crm/pipeline/board\""`, `"\"/api/v1/crm/customers\""`, `"\"/api/v1/crm/customers/{partyId}\""`, `"\"/api/v1/crm/dashboard\""`, `"\"/api/v1/crm/owners\""`, `"\"/api/v1/search\""`.

- [ ] **Step 5: Run the isolation suites**

Run: `cd backend && ./gradlew test --tests '*CrmRlsIT' --tests '*RlsCoverageIT' --tests '*CrossTenantApiIT' --tests '*CrmModuleGateIT' --tests '*OpenApiContractIT'`
Expected: PASS.

- [ ] **Step 6: Decision record**

`docs/decisions/0010-crm-leads-pipeline-customers.md`:

```markdown
# ADR-0010: CRM — leads outside the directory, one pipeline, customers as a directory role

- Status: Accepted
- Date: 2026-10-07
- Spec: docs/superpowers/specs/2026-10-07-crm-mvp-design.md

## Context
Phase 5 adds sales. ADR-0008 made people and organizations canonical, shared by every module, with duplicate
prevention. Sales teams handle many unqualified prospects, often imported from spreadsheets, most of which never
become customers.

## Decision
1. **A lead keeps the raw contact details it arrived with** and is not a party. Conversion creates or links the
   canonical person and organization through the directory, so its duplicate check runs once, with a human deciding.
   Converted leads are read-only and appear archived as collaboration subjects.
2. **One pipeline per workspace**, as ordered stages with a probability and a kind (OPEN, WON, LOST). Exactly one WON
   and one LOST stage, always last. An opportunity's status is its stage's kind.
3. **A customer is a party with an active CUSTOMER role.** CRM sets it through `PartyService.ensureCustomer` when a
   lead is converted or a deal is won — a business rule, so it needs no directory permission of its own.
4. Leads and opportunities are collaboration subjects (LEAD, OPPORTUNITY). A new `SubjectRelations` SPI lets the
   Customer 360 timeline include the activities of a party's leads and opportunities; `SubjectResolver.search` powers
   workspace search. Collaboration still depends on no business module.
5. Every CRM permission belongs to module CRM, so switching the module off removes CRM access everywhere.
6. Owner on create: a null `ownerId` means the creator; on update it means unassigned.
7. Aggregates (board, customers, dashboard) use SQL with an explicit `tenant_id` predicate on top of RLS.

## Consequences
- The directory isn't flooded with prospects; customers stay unique.
- An opportunity shows its account's and contact's names to anyone who can read the opportunity.
- Several pipelines, line items, merging duplicates and AI summaries are later work (spec §1 non-goals).
```

- [ ] **Step 7: Re-export the API document, run everything, commit**

Run: `cd backend && ./gradlew test -Dopenapi.export=true --tests '*OpenApiContractIT'` and confirm `docs/api/openapi.json` now contains `/api/v1/leads`. If the system property isn't forwarded to the test JVM, check how `build.gradle.kts` passes `openapi.export` (Phase 4 used the same mechanism) and use that.

Run: `cd backend && ./gradlew test` — Expected: PASS.

```bash
git add backend/src/test/java/com/nexusops docs/decisions/0010-crm-leads-pipeline-customers.md docs/api/openapi.json
git commit -m "test(crm): prove tenant isolation of leads, opportunities and stages; ADR-0010; API contract"
```

---
### Task 9: Frontend foundations — CRM types, permissions, the CRM area and Settings → Pipeline

**Files:**
- Modify: `frontend/src/lib/api/types.ts`, `frontend/src/features/auth/permissions.tsx`, `frontend/src/test/records.ts`
- Modify: `frontend/src/features/records/SubjectLink.tsx`, `frontend/src/features/shell/routes.tsx`, `frontend/src/features/shell/nav.ts`, `frontend/src/features/shell/ComingSoonPage.test.tsx`
- Create: `frontend/src/features/crm/labels.ts`, `money.ts`, `OwnerSelect.tsx`, `PartyPicker.tsx`, `CrmLayout.tsx`, `routes.tsx`, `CrmLayout.test.tsx`
- Create: `frontend/src/features/settings/PipelineSettingsPage.tsx`, `PipelineSettingsPage.test.tsx`

**Interfaces:**
- Consumes: the backend routes and JSON of Tasks 1–7.
- Produces (later frontend tasks import these exact names):
  - Types in `@/lib/api/types`: `StageKind`, `StageView`, `StageRef`, `MoneyTotal`, `LeadSource`, `LeadStatus`, `LeadView`, `OpportunityStatus`, `OpportunityView`, `OpportunitySummary`, `BoardColumn`, `BoardView`, `CustomerRow`, `CustomerSummary`, `LeadStats`, `StageStats`, `ClosedStats`, `PipelineStats`, `DashboardView`, `SearchHit`, `ImportResult`; `SubjectType` gains `'LEAD' | 'OPPORTUNITY'`; `ActivityView` gains `subject: SubjectRef | null`.
  - `PERMISSIONS.leadRead/leadManage/opportunityRead/opportunityManage/pipelineManage/customerRead`.
  - Fixtures in `@/test/records`: `aStage(o?)`, `defaultStages()`, `aLead(o?)`, `anOpportunity(o?)`, `anOpportunitySummary(o?)`, `aBoard()`, `aCustomerRow(o?)`, `aCustomerSummary(o?)`, `aDashboard(o?)`; `anActivity` gains `subject: null`.
  - `subjectPath('LEAD', id) → /app/crm/leads/:id`, `subjectPath('OPPORTUNITY', id) → /app/crm/opportunities/:id`.
  - `@/features/crm/labels`: `LEAD_STATUS_LABELS`, `LEAD_SOURCE_LABELS`, `OPPORTUNITY_STATUS_LABELS`, `OPEN_LEAD_STATUSES`.
  - `@/features/crm/money`: `formatTotals(totals: MoneyTotal[]): string`.
  - `<OwnerSelect value onChange current error? />` (controlled; `''` = unassigned; field labels "Find a teammate" / "Owner").
  - `<PartyPicker id label kind? value onChange current error? />` (controlled; search label is `Find ${label.toLowerCase()}`).
  - `crmChildren: RouteObject[]` from `@/features/crm/routes` (later tasks replace its placeholder elements with real pages).
  - CRM area routes: `/app/crm` (dashboard), `/app/crm/leads`, `/app/crm/leads/:leadId`, `/app/crm/pipeline`, `/app/crm/opportunities/:opportunityId`, `/app/crm/customers`, `/app/crm/customers/:partyId`; settings route `/app/settings/pipeline`.

- [ ] **Step 1: Types, permissions and fixtures**

Append to `frontend/src/lib/api/types.ts` (and edit `SubjectType` and `ActivityView` in place):

```ts
export type SubjectType = 'PARTY' | 'PRODUCT' | 'LEAD' | 'OPPORTUNITY'
```

```ts
export interface ActivityView {
  id: string
  subjectType: string
  subjectId: string
  type: ActivityType
  summary: string
  body: string | null
  occurredAt: string
  author: MemberRef | null
  createdAt: string
  /** The record the activity is on; its label is null when the viewer can't read it. */
  subject: SubjectRef | null
}
```

```ts
export type StageKind = 'OPEN' | 'WON' | 'LOST'

export interface StageView {
  id: string
  name: string
  probability: number
  kind: StageKind
  position: number
  version: number
}

export interface StageRef {
  id: string
  name: string
  kind: StageKind
  probability: number
}

/** A sum in one currency: amounts in different currencies are never added. */
export interface MoneyTotal {
  currency: string
  amount: number
}

export type LeadSource =
  | 'WEBSITE'
  | 'REFERRAL'
  | 'WALK_IN'
  | 'PHONE'
  | 'EMAIL'
  | 'SOCIAL'
  | 'EVENT'
  | 'OTHER'
export type LeadStatus = 'NEW' | 'CONTACTED' | 'QUALIFIED' | 'DISQUALIFIED' | 'CONVERTED'

export interface LeadView {
  id: string
  name: string
  firstName: string | null
  lastName: string | null
  companyName: string | null
  jobTitle: string | null
  email: string | null
  phone: string | null
  source: LeadSource
  status: LeadStatus
  owner: MemberRef | null
  estimatedValue: number | null
  currency: string | null
  description: string | null
  disqualifyReason: string | null
  disqualifiedAt: string | null
  convertedAt: string | null
  convertedPerson: PartyRef | null
  convertedOrganization: PartyRef | null
  convertedOpportunityId: string | null
  createdBy: MemberRef | null
  createdAt: string
  updatedAt: string
  version: number
}

export type OpportunityStatus = StageKind

export interface OpportunityView {
  id: string
  name: string
  account: PartyRef | null
  contact: PartyRef | null
  stage: StageRef
  status: OpportunityStatus
  amount: number | null
  currency: string | null
  expectedCloseOn: string | null
  owner: MemberRef | null
  leadId: string | null
  description: string | null
  lostReason: string | null
  closedAt: string | null
  createdBy: MemberRef | null
  createdAt: string
  updatedAt: string
  version: number
}

export interface OpportunitySummary {
  id: string
  name: string
  account: PartyRef | null
  stage: StageRef
  amount: number | null
  currency: string | null
  expectedCloseOn: string | null
  owner: MemberRef | null
  version: number
}

export interface BoardColumn {
  stage: StageView
  count: number
  totals: MoneyTotal[]
  weighted: MoneyTotal[]
  opportunities: OpportunitySummary[]
}

export interface BoardView {
  columns: BoardColumn[]
}

export interface CustomerRow {
  party: PartySummary
  openCount: number
  openValue: MoneyTotal[]
  wonCount: number
  wonValue: MoneyTotal[]
}

export interface CustomerSummary {
  party: PartyView
  openCount: number
  openValue: MoneyTotal[]
  weightedValue: MoneyTotal[]
  wonCount: number
  wonValue: MoneyTotal[]
  lostCount: number
  leadCount: number
}

export interface LeadStats {
  open: Partial<Record<LeadStatus, number>>
  newLast30Days: number
  converted90Days: number
  disqualified90Days: number
  /** 0–1, null when no lead closed in the last 90 days */
  conversionRate: number | null
}

export interface StageStats {
  stage: StageView
  count: number
  totals: MoneyTotal[]
  weighted: MoneyTotal[]
}

export interface ClosedStats {
  count: number
  totals: MoneyTotal[]
}

export interface PipelineStats {
  stages: StageStats[]
  wonThisMonth: ClosedStats
  lostThisMonth: ClosedStats
  closingSoon: OpportunitySummary[]
}

/** A section is null when the viewer can't read it. */
export interface DashboardView {
  leads: LeadStats | null
  pipeline: PipelineStats | null
}

export interface SearchHit {
  type: string
  id: string
  label: string
  detail: string | null
  archived: boolean
}

export interface ImportResult {
  imported: number
}
```

In `features/auth/permissions.tsx` add to `PERMISSIONS` (and update the doc comment to "V3, V9–V15"):

```ts
  leadRead: 'crm.lead.read',
  leadManage: 'crm.lead.manage',
  opportunityRead: 'crm.opportunity.read',
  opportunityManage: 'crm.opportunity.manage',
  pipelineManage: 'crm.pipeline.manage',
  customerRead: 'crm.customer.read',
```

In `test/records.ts`: add `subject: null,` to `anActivity`'s defaults, extend the type import, and append:

```ts
export function aStage(overrides: Partial<StageView> = {}): StageView {
  return {
    id: 's-prospecting',
    name: 'Prospecting',
    probability: 10,
    kind: 'OPEN',
    position: 0,
    version: 0,
    ...overrides,
  }
}

export function defaultStages(): StageView[] {
  return [
    aStage(),
    aStage({ id: 's-qualification', name: 'Qualification', probability: 25, position: 1 }),
    aStage({ id: 's-proposal', name: 'Proposal', probability: 50, position: 2 }),
    aStage({ id: 's-negotiation', name: 'Negotiation', probability: 75, position: 3 }),
    aStage({ id: 's-won', name: 'Won', probability: 100, kind: 'WON', position: 0 }),
    aStage({ id: 's-lost', name: 'Lost', probability: 0, kind: 'LOST', position: 0 }),
  ]
}

export function aLead(overrides: Partial<LeadView> = {}): LeadView {
  return {
    id: 'l-grace',
    name: 'Grace Hopper',
    firstName: 'Grace',
    lastName: 'Hopper',
    companyName: 'Acme Robotics',
    jobTitle: null,
    email: 'grace@acme.test',
    phone: null,
    source: 'REFERRAL',
    status: 'NEW',
    owner: { id: 'u-ada', name: 'Ada Lovelace' },
    estimatedValue: 5000,
    currency: 'USD',
    description: null,
    disqualifyReason: null,
    disqualifiedAt: null,
    convertedAt: null,
    convertedPerson: null,
    convertedOrganization: null,
    convertedOpportunityId: null,
    createdBy: { id: 'u-ada', name: 'Ada Lovelace' },
    createdAt: '2026-10-01T09:00:00Z',
    updatedAt: '2026-10-01T09:00:00Z',
    version: 0,
    ...overrides,
  }
}

export function anOpportunity(overrides: Partial<OpportunityView> = {}): OpportunityView {
  return {
    id: 'o-renewal',
    name: 'Packaging renewal',
    account: { id: 'p-acme', name: 'Acme' },
    contact: { id: 'p-grace', name: 'Grace Hopper' },
    stage: { id: 's-prospecting', name: 'Prospecting', kind: 'OPEN', probability: 10 },
    status: 'OPEN',
    amount: 1200,
    currency: 'USD',
    expectedCloseOn: '2026-11-30',
    owner: { id: 'u-ada', name: 'Ada Lovelace' },
    leadId: null,
    description: null,
    lostReason: null,
    closedAt: null,
    createdBy: { id: 'u-ada', name: 'Ada Lovelace' },
    createdAt: '2026-10-01T09:00:00Z',
    updatedAt: '2026-10-01T09:00:00Z',
    version: 0,
    ...overrides,
  }
}

export function anOpportunitySummary(overrides: Partial<OpportunitySummary> = {}): OpportunitySummary {
  const o = anOpportunity()
  return {
    id: o.id,
    name: o.name,
    account: o.account,
    stage: o.stage,
    amount: o.amount,
    currency: o.currency,
    expectedCloseOn: o.expectedCloseOn,
    owner: o.owner,
    version: o.version,
    ...overrides,
  }
}

export function aBoard(): BoardView {
  return {
    columns: defaultStages().map((stage, index) => ({
      stage,
      count: index === 0 ? 1 : 0,
      totals: index === 0 ? [{ currency: 'USD', amount: 1200 }] : [],
      weighted: index === 0 ? [{ currency: 'USD', amount: 120 }] : [],
      opportunities: index === 0 ? [anOpportunitySummary()] : [],
    })),
  }
}

export function aCustomerRow(overrides: Partial<CustomerRow> = {}): CustomerRow {
  return {
    party: aSummary({ roles: ['CUSTOMER'] }),
    openCount: 1,
    openValue: [{ currency: 'USD', amount: 1200 }],
    wonCount: 2,
    wonValue: [
      { currency: 'EUR', amount: 50 },
      { currency: 'USD', amount: 300 },
    ],
    ...overrides,
  }
}

export function aCustomerSummary(overrides: Partial<CustomerSummary> = {}): CustomerSummary {
  return {
    party: aParty({ roles: [{ role: 'CUSTOMER', status: 'ACTIVE', since: null, employeeNumber: null }] }),
    openCount: 1,
    openValue: [{ currency: 'USD', amount: 1200 }],
    weightedValue: [{ currency: 'USD', amount: 120 }],
    wonCount: 2,
    wonValue: [{ currency: 'USD', amount: 300 }],
    lostCount: 1,
    leadCount: 1,
    ...overrides,
  }
}

export function aDashboard(overrides: Partial<DashboardView> = {}): DashboardView {
  const stages = defaultStages().filter((s) => s.kind === 'OPEN')
  return {
    leads: {
      open: { NEW: 3, CONTACTED: 2, QUALIFIED: 1 },
      newLast30Days: 6,
      converted90Days: 1,
      disqualified90Days: 3,
      conversionRate: 0.25,
    },
    pipeline: {
      stages: stages.map((stage, index) => ({
        stage,
        count: index === 0 ? 2 : 0,
        totals: index === 0 ? [{ currency: 'USD', amount: 1200 }] : [],
        weighted: index === 0 ? [{ currency: 'USD', amount: 120 }] : [],
      })),
      wonThisMonth: { count: 1, totals: [{ currency: 'USD', amount: 300 }] },
      lostThisMonth: { count: 1, totals: [] },
      closingSoon: [anOpportunitySummary()],
    },
    ...overrides,
  }
}
```

- [ ] **Step 2: Subject paths, labels, money, pickers**

`features/records/SubjectLink.tsx` — `subjectPath` gains:

```ts
  if (type === 'LEAD') return `/app/crm/leads/${id}`
  if (type === 'OPPORTUNITY') return `/app/crm/opportunities/${id}`
```

`features/crm/labels.ts`:

```ts
import type { LeadSource, LeadStatus, OpportunityStatus } from '@/lib/api/types'

export const LEAD_STATUS_LABELS: Record<LeadStatus, string> = {
  NEW: 'New',
  CONTACTED: 'Contacted',
  QUALIFIED: 'Qualified',
  DISQUALIFIED: 'Disqualified',
  CONVERTED: 'Converted',
}

export const OPEN_LEAD_STATUSES: LeadStatus[] = ['NEW', 'CONTACTED', 'QUALIFIED']

export const LEAD_SOURCE_LABELS: Record<LeadSource, string> = {
  WEBSITE: 'Website',
  REFERRAL: 'Referral',
  WALK_IN: 'Walk-in',
  PHONE: 'Phone',
  EMAIL: 'Email',
  SOCIAL: 'Social media',
  EVENT: 'Event',
  OTHER: 'Other',
}

export const OPPORTUNITY_STATUS_LABELS: Record<OpportunityStatus, string> = {
  OPEN: 'Open',
  WON: 'Won',
  LOST: 'Lost',
}
```

`features/crm/money.ts`:

```ts
import { formatMoney } from '@/lib/format'
import type { MoneyTotal } from '@/lib/api/types'

/** "$1,200.00 · €50.00" — one figure per currency, never summed across currencies. */
export function formatTotals(totals: MoneyTotal[]): string {
  return totals.length ? totals.map((t) => formatMoney(t.amount, t.currency)).join(' · ') : '—'
}
```

`features/crm/OwnerSelect.tsx` (same pattern as the task assignee picker, but over `/crm/owners`):

```tsx
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Input } from '@/components/ui/input'
import { describedBy, Field } from '@/components/form/Field'
import { NativeSelect } from '@/components/form/NativeSelect'
import { useApi } from '@/lib/api/ApiContext'
import type { AssigneeView, MemberRef } from '@/lib/api/types'
import { toQuery } from '@/lib/query'

/** Owner of a lead or opportunity. '' = unassigned. The current and picked owner always stay listed. */
export function OwnerSelect({
  value,
  onChange,
  current,
  error,
}: {
  value: string
  onChange: (id: string) => void
  current?: MemberRef | null
  error?: string
}) {
  const api = useApi()
  const [search, setSearch] = useState('')
  const [picked, setPicked] = useState<MemberRef | null>(null)
  const owners = useQuery({
    queryKey: ['crm-owners', search.trim()],
    queryFn: () => api.get<AssigneeView[]>(`/crm/owners?${toQuery({ q: search.trim() })}`),
  })
  const options = owners.data ?? []
  const pinned = [current, picked].filter(
    (m, i, all): m is MemberRef => !!m && all.findIndex((o) => o?.id === m.id) === i,
  )
  return (
    <div className="grid gap-3 sm:grid-cols-2">
      <Field id="field-ownerSearch" label="Find a teammate">
        <Input
          id="field-ownerSearch"
          type="search"
          autoComplete="off"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
      </Field>
      <Field id="field-ownerId" label="Owner" error={error}>
        <NativeSelect
          id="field-ownerId"
          value={value}
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy('field-ownerId', error)}
          onChange={(e) => {
            const id = e.target.value
            setPicked(options.find((m) => m.id === id) ?? pinned.find((m) => m.id === id) ?? null)
            onChange(id)
          }}
        >
          <option value="">Unassigned</option>
          {pinned.map((m) => (
            <option key={m.id} value={m.id}>
              {m.name}
            </option>
          ))}
          {options
            .filter((m) => !pinned.some((p) => p.id === m.id))
            .map((m) => (
              <option key={m.id} value={m.id}>
                {m.name}
              </option>
            ))}
        </NativeSelect>
      </Field>
    </div>
  )
}
```

`features/crm/PartyPicker.tsx`:

```tsx
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Input } from '@/components/ui/input'
import { describedBy, Field } from '@/components/form/Field'
import { NativeSelect } from '@/components/form/NativeSelect'
import { useApi } from '@/lib/api/ApiContext'
import type { Page, PartyKind, PartyRef, PartySummary } from '@/lib/api/types'
import { toQuery } from '@/lib/query'

/** Pick a person or organization: a search box over the server's first 20 matches plus a select. '' = none. */
export function PartyPicker({
  id,
  label,
  kind,
  value,
  onChange,
  current,
  error,
  noneLabel = 'None',
}: {
  id: string
  label: string
  kind?: PartyKind
  value: string
  onChange: (id: string, party: PartyRef | null) => void
  current?: PartyRef | null
  error?: string
  noneLabel?: string
}) {
  const api = useApi()
  const [search, setSearch] = useState('')
  const [picked, setPicked] = useState<PartyRef | null>(null)
  const parties = useQuery({
    queryKey: ['party-picker', kind ?? 'ANY', search.trim()],
    queryFn: () =>
      api.get<Page<PartySummary>>(`/parties?${toQuery({ kind, q: search.trim(), size: 20 })}`),
  })
  const options: PartyRef[] = (parties.data?.items ?? []).map((p) => ({ id: p.id, name: p.name }))
  const pinned = [current, picked].filter(
    (p, i, all): p is PartyRef => !!p && all.findIndex((o) => o?.id === p.id) === i,
  )
  const searchId = `${id}-search`
  return (
    <div className="grid gap-3 sm:grid-cols-2">
      <Field id={searchId} label={`Find ${label.toLowerCase()}`}>
        <Input
          id={searchId}
          type="search"
          autoComplete="off"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
      </Field>
      <Field id={id} label={label} error={error}>
        <NativeSelect
          id={id}
          value={value}
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy(id, error)}
          onChange={(e) => {
            const chosen =
              options.find((p) => p.id === e.target.value) ??
              pinned.find((p) => p.id === e.target.value) ??
              null
            setPicked(chosen)
            onChange(e.target.value, chosen)
          }}
        >
          <option value="">{noneLabel}</option>
          {pinned.map((p) => (
            <option key={p.id} value={p.id}>
              {p.name}
            </option>
          ))}
          {options
            .filter((p) => !pinned.some((o) => o.id === p.id))
            .map((p) => (
              <option key={p.id} value={p.id}>
                {p.name}
              </option>
            ))}
        </NativeSelect>
      </Field>
    </div>
  )
}
```

- [ ] **Step 3: Write the failing tests for the CRM area and the pipeline settings**

`features/crm/CrmLayout.test.tsx`:

```tsx
import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ALL_TENANT_PERMISSIONS } from '@/features/auth/permissions'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aDashboard } from '@/test/records'
import { renderApp } from '@/test/renderApp'

describe('CrmLayout', () => {
  it('explains that CRM is off when the module is disabled', async () => {
    const server = fakeServer()
    signedIn(server, testProfile({ modules: [] }))
    renderApp({ server, path: '/app/crm' })
    expect(await screen.findByText('CRM is not enabled for this workspace.')).toBeInTheDocument()
  })

  it('shows the sections the user may open', async () => {
    const server = fakeServer()
    signedIn(server, testProfile({ modules: ['CRM'], permissions: ['crm.lead.read'] })).on(
      'GET /crm/dashboard',
      { body: aDashboard({ pipeline: null }) },
    )
    renderApp({ server, path: '/app/crm' })
    const nav = await screen.findByRole('navigation', { name: 'CRM' })
    expect(nav).toHaveTextContent('Dashboard')
    expect(nav).toHaveTextContent('Leads')
    expect(nav).not.toHaveTextContent('Pipeline')
    expect(nav).not.toHaveTextContent('Customers')
  })

  it('shows every section to a full user', async () => {
    const server = fakeServer()
    signedIn(server, testProfile({ modules: ['CRM'], permissions: [...ALL_TENANT_PERMISSIONS] })).on(
      'GET /crm/dashboard',
      { body: aDashboard() },
    )
    renderApp({ server, path: '/app/crm' })
    const nav = await screen.findByRole('navigation', { name: 'CRM' })
    for (const name of ['Dashboard', 'Leads', 'Pipeline', 'Customers'])
      expect(nav).toHaveTextContent(name)
  })
})
```

`features/settings/PipelineSettingsPage.test.tsx`:

```tsx
import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ALL_TENANT_PERMISSIONS } from '@/features/auth/permissions'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aStage, defaultStages } from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup() {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['CRM'], permissions: [...ALL_TENANT_PERMISSIONS] })).on(
    'GET /crm/pipeline/stages',
    { body: defaultStages() },
  )
  return renderApp({ server, path: '/app/settings/pipeline' })
}

async function stageRows() {
  return within(await screen.findByRole('list', { name: 'Stages' })).getAllByRole('listitem')
}

describe('PipelineSettingsPage', () => {
  it('lists the stages in order with fixed Won and Lost stages', async () => {
    setup()
    const rows = await stageRows()
    expect(rows.map((r) => within(r).getByRole('textbox', { name: /Stage name/ }))).toHaveLength(6)
    const won = rows[4]
    expect(within(won).getByText('Won stage')).toBeInTheDocument()
    expect(within(won).queryByRole('button', { name: /Delete/ })).not.toBeInTheDocument()
    expect(within(rows[0]).getByRole('button', { name: 'Move Prospecting up' })).toBeDisabled()
  })

  it('adds a stage', async () => {
    const { server, user } = setup()
    server.on('POST /crm/pipeline/stages', {
      status: 201,
      body: aStage({ id: 's-demo', name: 'Demo', probability: 40, position: 4 }),
    })
    await stageRows()
    await user.type(screen.getByLabelText('New stage name'), 'Demo')
    await user.clear(screen.getByLabelText('New stage probability (%)'))
    await user.type(screen.getByLabelText('New stage probability (%)'), '40')
    await user.click(screen.getByRole('button', { name: 'Add stage' }))
    expect(server.callsTo('POST /crm/pipeline/stages')[0].body).toEqual({ name: 'Demo', probability: 40 })
  })

  it('saves a renamed stage with its version', async () => {
    const { server, user } = setup()
    server.on('PUT /crm/pipeline/stages/:id', { body: aStage({ name: 'Discovery', version: 1 }) })
    const rows = await stageRows()
    const name = within(rows[0]).getByRole('textbox', { name: 'Stage name Prospecting' })
    await user.clear(name)
    await user.type(name, 'Discovery')
    await user.click(within(rows[0]).getByRole('button', { name: 'Save' }))
    expect(server.callsTo('PUT /crm/pipeline/stages/:id')[0].body).toEqual({
      name: 'Discovery',
      probability: 10,
      version: 0,
    })
  })

  it('moves an open stage down by sending the new order', async () => {
    const { server, user } = setup()
    server.on('PUT /crm/pipeline/stages/order', { body: defaultStages() })
    await stageRows()
    await user.click(screen.getByRole('button', { name: 'Move Prospecting down' }))
    expect(server.callsTo('PUT /crm/pipeline/stages/order')[0].body).toEqual({
      stageIds: ['s-qualification', 's-prospecting', 's-proposal', 's-negotiation'],
    })
  })

  it('shows why a stage cannot be deleted', async () => {
    const { server, user } = setup()
    server.on('DELETE /crm/pipeline/stages/:id', {
      status: 409,
      body: { detail: "Move this stage's opportunities first." },
    })
    const rows = await stageRows()
    await user.click(within(rows[0]).getByRole('button', { name: 'Delete Prospecting' }))
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Delete' }))
    expect(await screen.findByText("Move this stage's opportunities first.")).toBeInTheDocument()
  })
})
```

Update `features/shell/ComingSoonPage.test.tsx`'s first test — CRM is no longer "coming soon"; use Inventory:

```tsx
  it('names the blueprint phase of an enabled module', async () => {
    const server = fakeServer()
    signedIn(server, testProfile({ modules: ['INVENTORY'] }))
    renderApp({ server, path: '/app/inventory' })
    expect(await screen.findByRole('heading', { name: 'Inventory' })).toBeInTheDocument()
    expect(screen.getByText(/Phase 6/)).toBeInTheDocument()
  })
```

- [ ] **Step 4: Run the tests to verify they fail**

Run: `cd frontend && npx vitest run src/features/crm src/features/settings/PipelineSettingsPage.test.tsx src/features/shell/ComingSoonPage.test.tsx`
Expected: FAIL — modules not found.

- [ ] **Step 5: CRM layout and routes**

`features/crm/CrmLayout.tsx`:

```tsx
import { NavLink, Outlet } from 'react-router'
import { NoAccess } from '@/components/states'
import { PERMISSIONS, useCan, type PermissionCode } from '@/features/auth/permissions'
import { useTenantSession } from '@/features/auth/tenantSession'
import { ComingSoonPage } from '@/features/shell/ComingSoonPage'
import { MODULE_PHASES } from '@/features/shell/nav'
import { cn } from '@/lib/utils'

const TABS: Array<{ to: string; label: string; anyOf: PermissionCode[]; end?: boolean }> = [
  {
    to: '/app/crm',
    label: 'Dashboard',
    anyOf: [PERMISSIONS.leadRead, PERMISSIONS.opportunityRead],
    end: true,
  },
  { to: '/app/crm/leads', label: 'Leads', anyOf: [PERMISSIONS.leadRead] },
  { to: '/app/crm/pipeline', label: 'Pipeline', anyOf: [PERMISSIONS.opportunityRead] },
  { to: '/app/crm/customers', label: 'Customers', anyOf: [PERMISSIONS.customerRead] },
]

/** The CRM area: only when the module is enabled; tabs follow the user's CRM permissions. */
export function CrmLayout() {
  const session = useTenantSession()
  const can = useCan()
  const modules = session.state.status === 'authenticated' ? session.state.profile.modules : []
  if (!modules.includes('CRM')) {
    const info = MODULE_PHASES.CRM
    return (
      <ComingSoonPage title={info.label} phase={info.phase} description={info.description} module="CRM" />
    )
  }
  const tabs = TABS.filter((tab) => can(...tab.anyOf))
  if (tabs.length === 0) return <NoAccess />
  return (
    <div className="space-y-6">
      <nav aria-label="CRM" className="flex flex-wrap gap-1 border-b pb-2">
        {tabs.map((tab) => (
          <NavLink
            key={tab.to}
            to={tab.to}
            end={tab.end}
            className={({ isActive }) =>
              cn('rounded-md px-3 py-1.5 text-sm hover:bg-muted', isActive && 'bg-muted font-medium')
            }
          >
            {tab.label}
          </NavLink>
        ))}
      </nav>
      <Outlet />
    </div>
  )
}
```

`features/crm/routes.tsx` (Tasks 10–13 replace each `Pending` element with the real page; keep the `RequirePermission` wrappers exactly):

```tsx
import type { RouteObject } from 'react-router'
import { EmptyState } from '@/components/states'
import { PERMISSIONS, RequirePermission } from '@/features/auth/permissions'

function Pending({ title }: { title: string }) {
  return <EmptyState title={title} description="This page is being built." />
}

export const crmChildren: RouteObject[] = [
  {
    index: true,
    element: (
      <RequirePermission anyOf={[PERMISSIONS.leadRead, PERMISSIONS.opportunityRead]}>
        <Pending title="Dashboard" />
      </RequirePermission>
    ),
  },
  {
    path: 'leads',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.leadRead]}>
        <Pending title="Leads" />
      </RequirePermission>
    ),
  },
  {
    path: 'leads/:leadId',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.leadRead]}>
        <Pending title="Lead" />
      </RequirePermission>
    ),
  },
  {
    path: 'pipeline',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.opportunityRead]}>
        <Pending title="Pipeline" />
      </RequirePermission>
    ),
  },
  {
    path: 'opportunities/:opportunityId',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.opportunityRead]}>
        <Pending title="Opportunity" />
      </RequirePermission>
    ),
  },
  {
    path: 'customers',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.customerRead]}>
        <Pending title="Customers" />
      </RequirePermission>
    ),
  },
  {
    path: 'customers/:partyId',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.customerRead]}>
        <Pending title="Customer" />
      </RequirePermission>
    ),
  },
]
```

The `CrmLayout.test` dashboard expectations only need the tabs, so `Pending` is fine until Task 13.

In `features/shell/routes.tsx` replace `modulePage('crm', 'CRM'),` with:

```tsx
  { path: 'crm', element: <CrmLayout />, children: crmChildren },
```

and add the settings child before the catch-all:

```tsx
  {
    path: 'pipeline',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.pipelineManage]}>
        <PipelineSettingsPage />
      </RequirePermission>
    ),
  },
```

In `features/shell/nav.ts`, add to `SETTINGS_TABS` after Modules: `{ to: '/app/settings/pipeline', label: 'Pipeline', anyOf: [PERMISSIONS.pipelineManage] }`.

- [ ] **Step 6: Pipeline settings page**

`features/settings/PipelineSettingsPage.tsx`:

```tsx
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState, type FormEvent } from 'react'
import { toast } from 'sonner'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { FormError } from '@/components/form/FormError'
import { ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { useApi } from '@/lib/api/ApiContext'
import { problemMessage } from '@/lib/api/problems'
import type { StageView } from '@/lib/api/types'

const KEY = ['crm-stages']

/** Settings → Pipeline (D5): open stages can be added, renamed, reordered and deleted; Won and Lost only renamed. */
export function PipelineSettingsPage() {
  const api = useApi()
  const queryClient = useQueryClient()
  const stages = useQuery({ queryKey: KEY, queryFn: () => api.get<StageView[]>('/crm/pipeline/stages') })
  const [error, setError] = useState<string | null>(null)
  const [deleting, setDeleting] = useState<StageView | null>(null)
  const [busy, setBusy] = useState(false)
  const [newName, setNewName] = useState('')
  const [newProbability, setNewProbability] = useState('50')

  async function refresh() {
    await queryClient.invalidateQueries({ queryKey: KEY })
    await queryClient.invalidateQueries({ queryKey: ['crm-board'] })
  }

  async function run(action: () => Promise<unknown>, success: string) {
    setError(null)
    setBusy(true)
    try {
      await action()
      await refresh()
      toast.success(success)
      return true
    } catch (e) {
      setError(problemMessage(e))
      return false
    } finally {
      setBusy(false)
    }
  }

  async function add(event: FormEvent) {
    event.preventDefault()
    const ok = await run(
      () => api.post('/crm/pipeline/stages', { name: newName, probability: Number(newProbability) }),
      'Stage added.',
    )
    if (ok) setNewName('')
  }

  if (stages.isPending) return <ListSkeleton />
  if (stages.isError) return <ErrorState error={stages.error} onRetry={() => void stages.refetch()} />
  const open = stages.data.filter((s) => s.kind === 'OPEN')

  function move(stage: StageView, delta: number) {
    const ids = open.map((s) => s.id)
    const from = ids.indexOf(stage.id)
    const to = from + delta
    ;[ids[from], ids[to]] = [ids[to], ids[from]]
    void run(() => api.put('/crm/pipeline/stages/order', { stageIds: ids }), 'Order saved.')
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title="Pipeline"
        description="The stages every opportunity moves through. Won and Lost are always last."
      />
      <FormError message={error} />
      <ol aria-label="Stages" className="space-y-2">
        {stages.data.map((stage) => {
          const index = open.findIndex((s) => s.id === stage.id)
          return (
            <StageRow
              key={`${stage.id}-${stage.version}`}
              stage={stage}
              busy={busy}
              canMoveUp={index > 0}
              canMoveDown={index >= 0 && index < open.length - 1}
              onMove={(delta) => move(stage, delta)}
              onSave={(name, probability) =>
                void run(
                  () =>
                    api.put(`/crm/pipeline/stages/${stage.id}`, {
                      name,
                      probability,
                      version: stage.version,
                    }),
                  'Stage saved.',
                )
              }
              onDelete={() => setDeleting(stage)}
            />
          )
        })}
      </ol>
      <form onSubmit={add} className="flex flex-wrap items-end gap-3 rounded-lg border p-4">
        <div className="space-y-1.5">
          <Label htmlFor="new-stage-name">New stage name</Label>
          <Input
            id="new-stage-name"
            maxLength={60}
            value={newName}
            onChange={(e) => setNewName(e.target.value)}
          />
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="new-stage-probability">New stage probability (%)</Label>
          <Input
            id="new-stage-probability"
            type="number"
            min={0}
            max={100}
            className="w-28"
            value={newProbability}
            onChange={(e) => setNewProbability(e.target.value)}
          />
        </div>
        <Button type="submit" disabled={busy || !newName.trim()}>
          Add stage
        </Button>
      </form>
      <ConfirmDialog
        open={deleting !== null}
        title={`Delete ${deleting?.name ?? 'stage'}?`}
        description="Only a stage without opportunities can be deleted."
        confirmLabel="Delete"
        busy={busy}
        onCancel={() => setDeleting(null)}
        onConfirm={async () => {
          const stage = deleting
          setDeleting(null)
          if (stage) await run(() => api.del(`/crm/pipeline/stages/${stage.id}`), 'Stage deleted.')
        }}
      />
    </div>
  )
}

function StageRow({
  stage,
  busy,
  canMoveUp,
  canMoveDown,
  onMove,
  onSave,
  onDelete,
}: {
  stage: StageView
  busy: boolean
  canMoveUp: boolean
  canMoveDown: boolean
  onMove: (delta: number) => void
  onSave: (name: string, probability: number) => void
  onDelete: () => void
}) {
  const [name, setName] = useState(stage.name)
  const [probability, setProbability] = useState(String(stage.probability))
  useEffect(() => {
    setName(stage.name)
    setProbability(String(stage.probability))
  }, [stage.name, stage.probability])
  const changed = name !== stage.name || probability !== String(stage.probability)
  const fixed = stage.kind !== 'OPEN'
  return (
    <li className="flex flex-wrap items-center gap-2 rounded-lg border p-3">
      <Input
        aria-label={`Stage name ${stage.name}`}
        className="w-48"
        maxLength={60}
        value={name}
        onChange={(e) => setName(e.target.value)}
      />
      <Input
        aria-label={`Probability of ${stage.name} (%)`}
        type="number"
        min={0}
        max={100}
        className="w-24"
        value={probability}
        onChange={(e) => setProbability(e.target.value)}
      />
      {fixed ? (
        <Badge variant="outline">{stage.kind === 'WON' ? 'Won stage' : 'Lost stage'}</Badge>
      ) : (
        <>
          <Button
            variant="outline"
            size="sm"
            aria-label={`Move ${stage.name} up`}
            disabled={busy || !canMoveUp}
            onClick={() => onMove(-1)}
          >
            ↑
          </Button>
          <Button
            variant="outline"
            size="sm"
            aria-label={`Move ${stage.name} down`}
            disabled={busy || !canMoveDown}
            onClick={() => onMove(1)}
          >
            ↓
          </Button>
        </>
      )}
      <Button size="sm" disabled={busy || !changed} onClick={() => onSave(name, Number(probability))}>
        Save
      </Button>
      {!fixed && (
        <Button variant="ghost" size="sm" aria-label={`Delete ${stage.name}`} disabled={busy} onClick={onDelete}>
          Delete
        </Button>
      )}
    </li>
  )
}
```


- [ ] **Step 7: Run the tests, lint and typecheck**

Run: `cd frontend && npx vitest run src/features/crm src/features/settings src/features/shell && npm run lint && npm run typecheck`
Expected: PASS. Then the full suite: `npm test` — Expected: PASS (fix any test that builds an `ActivityView` literal without `subject`).

- [ ] **Step 8: Commit**

```bash
git add frontend/src
git commit -m "feat(frontend): CRM area with permission-aware tabs, CRM types, and Settings → Pipeline"
```

---

### Task 10: Leads — list with filters, create/edit dialog and CSV import

**Files:**
- Create: `frontend/src/features/crm/schemas.ts`, `LeadsPage.tsx`, `LeadFormDialog.tsx`, `LeadImportDialog.tsx`, `LeadsPage.test.tsx`
- Modify: `frontend/src/features/crm/routes.tsx` (`leads` → `<LeadsPage />`)

**Interfaces:**
- Consumes: Task 9's types, labels, `OwnerSelect`, fixtures `aLead`, `pageOf`.
- Produces: `<LeadFormDialog lead? onClose onSaved />`, `leadSchema`, `LeadValues`, `moneySchema` (shared money string refinement), `<LeadImportDialog onClose />`.

- [ ] **Step 1: Write the failing test**

`features/crm/LeadsPage.test.tsx`:

```tsx
import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ALL_TENANT_PERMISSIONS } from '@/features/auth/permissions'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aLead, pageOf } from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup(permissions: string[] = [...ALL_TENANT_PERMISSIONS]) {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['CRM'], permissions }))
    .on('GET /leads', {
      body: pageOf([aLead(), aLead({ id: 'l-deccan', name: 'Deccan Spices', firstName: null, lastName: null, companyName: 'Deccan Spices', owner: null, estimatedValue: null, currency: null })]),
    })
    .on('GET /leads/:id', { body: aLead() })
    .on('GET /crm/owners', { body: [{ id: 'u-ada', name: 'Ada Lovelace', email: 'ada@acme.test' }] })
    .on('GET /activities', { body: pageOf([]) })
    .on('GET /documents', { body: [] })
  return renderApp({ server, path: '/app/crm/leads' })
}

describe('LeadsPage', () => {
  it('lists open leads with company, status, owner and value', async () => {
    const { server } = setup()
    const row = (await screen.findByRole('link', { name: 'Grace Hopper' })).closest('tr') as HTMLElement
    expect(within(row).getByText('Acme Robotics')).toBeInTheDocument()
    expect(within(row).getByText('New')).toBeInTheDocument()
    expect(within(row).getByText('Ada Lovelace')).toBeInTheDocument()
    expect(within(row).getByText(/5,000/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Grace Hopper' })).toHaveAttribute('href', '/app/crm/leads/l-grace')
    expect(server.callsTo('GET /leads')[0].query.get('status')).toBeNull()
  })

  it('filters by status, owner, source and search', async () => {
    const { server, user } = setup()
    await screen.findByRole('link', { name: 'Grace Hopper' })
    await user.selectOptions(screen.getByLabelText('Status'), 'DISQUALIFIED')
    await user.selectOptions(screen.getByLabelText('Owner'), 'me')
    await user.selectOptions(screen.getByLabelText('Source'), 'EVENT')
    await user.type(screen.getByLabelText('Search leads'), 'acme')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    const last = server.callsTo('GET /leads').at(-1)?.query
    expect(last?.get('status')).toBe('DISQUALIFIED')
    expect(last?.get('owner')).toBe('me')
    expect(last?.get('source')).toBe('EVENT')
    expect(last?.get('q')).toBe('acme')
  })

  it('creates a lead and opens it', async () => {
    const { server, user, router } = setup()
    server.on('POST /leads', { status: 201, body: aLead({ id: 'l-new', name: 'Meera Iyer' }) })
    await user.click(await screen.findByRole('button', { name: 'New lead' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('First name'), 'Meera')
    await user.type(within(dialog).getByLabelText('Last name'), 'Iyer')
    await user.type(within(dialog).getByLabelText('Estimated value'), '2500')
    await user.selectOptions(within(dialog).getByLabelText('Source'), 'WALK_IN')
    await user.click(within(dialog).getByRole('button', { name: 'Create lead' }))
    expect(server.callsTo('POST /leads')[0].body).toEqual({
      firstName: 'Meera',
      lastName: 'Iyer',
      companyName: null,
      jobTitle: null,
      email: null,
      phone: null,
      source: 'WALK_IN',
      ownerId: null,
      estimatedValue: 2500,
      currency: null,
      description: null,
    })
    await screen.findByText('Meera Iyer added.')
    expect(router.state.location.pathname).toBe('/app/crm/leads/l-new')
  })

  it('needs a name or a company before sending', async () => {
    const { server, user } = setup()
    await user.click(await screen.findByRole('button', { name: 'New lead' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Create lead' }))
    expect(await within(dialog).findByText('Enter a name or a company.')).toBeInTheDocument()
    expect(server.callsTo('POST /leads')).toHaveLength(0)
  })

  it('imports a CSV file and shows row errors', async () => {
    const { server, user } = setup()
    server.on('POST /leads/import', {
      status: 422,
      body: {
        detail: "Some rows can't be imported. Fix them and upload the file again.",
        errorCount: 1,
        rows: [{ row: 3, field: 'email', message: 'Enter a valid email address.' }],
      },
    })
    await user.click(await screen.findByRole('button', { name: 'Import CSV' }))
    const dialog = await screen.findByRole('dialog')
    const file = new File(['company\nAcme\n'], 'leads.csv', { type: 'text/csv' })
    await user.upload(within(dialog).getByLabelText('CSV file'), file)
    await user.click(within(dialog).getByRole('button', { name: 'Import' }))
    const errors = await within(dialog).findByRole('table', { name: 'Rows to fix' })
    expect(within(errors).getByText('3')).toBeInTheDocument()
    expect(within(errors).getByText('email')).toBeInTheDocument()
    expect(within(errors).getByText('Enter a valid email address.')).toBeInTheDocument()
    expect(server.callsTo('POST /leads/import')[0].form?.get('file')).toBeInstanceOf(File)

    server.on('POST /leads/import', { body: { imported: 2 } })
    await user.click(within(dialog).getByRole('button', { name: 'Import' }))
    expect(await screen.findByText('2 leads imported.')).toBeInTheDocument()
  })

  it('hides create and import from readers', async () => {
    setup(['crm.lead.read'])
    await screen.findByRole('link', { name: 'Grace Hopper' })
    expect(screen.queryByRole('button', { name: 'New lead' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Import CSV' })).not.toBeInTheDocument()
  })
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd frontend && npx vitest run src/features/crm/LeadsPage.test.tsx`
Expected: FAIL — module not found.

- [ ] **Step 3: Schemas**

`features/crm/schemas.ts`:

```ts
import { z } from 'zod'
import { requiredText } from '@/features/auth/schemas'

/** Amount typed as text: '' or a non-negative number with at most 4 decimals. */
export const moneySchema = z
  .string()
  .trim()
  .refine((v) => v === '' || /^-?\d+(\.\d+)?$/.test(v), 'Enter a number like 1200.50.')
  .refine((v) => !v.startsWith('-'), 'Enter an amount of 0 or more.')
  .refine((v) => v === '' || !/\.\d{5,}$/.test(v), 'Use at most 4 decimal places.')

export const currencySchema = z
  .string()
  .trim()
  .refine((v) => v === '' || /^[A-Za-z]{3}$/.test(v), 'Use a 3-letter currency code like USD.')

/** Mirrors LeadService: the server stays the authority. */
export const leadSchema = z
  .object({
    firstName: z.string().trim().max(80, 'Use at most 80 characters.'),
    lastName: z.string().trim().max(80, 'Use at most 80 characters.'),
    companyName: z.string().trim().max(200, 'Use at most 200 characters.'),
    jobTitle: z.string().trim().max(100, 'Use at most 100 characters.'),
    email: z
      .string()
      .trim()
      .refine((v) => v === '' || /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(v), 'Enter a valid email address.'),
    phone: z.string().trim().max(40, 'Use at most 40 characters.'),
    source: z.enum(['WEBSITE', 'REFERRAL', 'WALK_IN', 'PHONE', 'EMAIL', 'SOCIAL', 'EVENT', 'OTHER']),
    ownerId: z.string(),
    estimatedValue: moneySchema,
    currency: currencySchema,
    description: z.string().max(5000, 'Use at most 5000 characters.'),
  })
  .refine((v) => v.firstName || v.lastName || v.companyName, {
    path: ['lastName'],
    message: 'Enter a name or a company.',
  })
export type LeadValues = z.infer<typeof leadSchema>

export const reasonSchema = z.object({ reason: requiredText(500) })
```

- [ ] **Step 4: Lead form dialog**

`features/crm/LeadFormDialog.tsx`:

```tsx
import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Field } from '@/components/form/Field'
import { FormError } from '@/components/form/FormError'
import { NativeSelect } from '@/components/form/NativeSelect'
import { TextAreaField } from '@/components/form/TextAreaField'
import { TextField } from '@/components/form/TextField'
import { useApi } from '@/lib/api/ApiContext'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import type { LeadView } from '@/lib/api/types'
import { LEAD_SOURCE_LABELS } from './labels'
import { OwnerSelect } from './OwnerSelect'
import { leadSchema, type LeadValues } from './schemas'

const FIELDS = [
  'firstName',
  'lastName',
  'companyName',
  'jobTitle',
  'email',
  'phone',
  'source',
  'ownerId',
  'estimatedValue',
  'currency',
  'description',
] as const

/** Create or edit a lead. On create a blank owner means "me" (the server's rule); on edit it means unassigned. */
export function LeadFormDialog({
  lead,
  onClose,
  onSaved,
}: {
  lead?: LeadView
  onClose: () => void
  onSaved: (saved: LeadView) => void
}) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [formError, setFormError] = useState<string | null>(null)
  const form = useForm<LeadValues>({
    resolver: zodResolver(leadSchema),
    defaultValues: {
      firstName: lead?.firstName ?? '',
      lastName: lead?.lastName ?? '',
      companyName: lead?.companyName ?? '',
      jobTitle: lead?.jobTitle ?? '',
      email: lead?.email ?? '',
      phone: lead?.phone ?? '',
      source: lead?.source ?? 'OTHER',
      ownerId: lead?.owner?.id ?? '',
      estimatedValue: lead?.estimatedValue == null ? '' : String(lead.estimatedValue),
      currency: lead?.currency ?? '',
      description: lead?.description ?? '',
    },
  })
  const blank = (v: string) => v.trim() || null

  const submit = form.handleSubmit(async (values) => {
    setFormError(null)
    const body = {
      firstName: blank(values.firstName),
      lastName: blank(values.lastName),
      companyName: blank(values.companyName),
      jobTitle: blank(values.jobTitle),
      email: blank(values.email),
      phone: blank(values.phone),
      source: values.source,
      ownerId: values.ownerId || null,
      estimatedValue: values.estimatedValue === '' ? null : Number(values.estimatedValue),
      currency: values.currency === '' ? null : values.currency.toUpperCase(),
      description: blank(values.description),
      ...(lead ? { version: lead.version } : {}),
    }
    try {
      const saved = lead
        ? await api.put<LeadView>(`/leads/${lead.id}`, body)
        : await api.post<LeadView>('/leads', body)
      queryClient.setQueryData(['lead', saved.id], saved)
      await queryClient.invalidateQueries({ queryKey: ['leads'] })
      toast.success(lead ? 'Changes saved.' : `${saved.name} added.`)
      onSaved(saved)
    } catch (error) {
      if (!applyFieldErrors(error, form.setError, FIELDS)) setFormError(problemMessage(error))
    }
  })

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
    >
      <DialogContent className="max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{lead ? `Edit ${lead.name}` : 'New lead'}</DialogTitle>
          <DialogDescription>
            A prospect, as you heard about them. Converting it later creates the customer record.
          </DialogDescription>
        </DialogHeader>
        <form noValidate onSubmit={submit} className="space-y-4">
          <div className="grid gap-3 sm:grid-cols-2">
            <TextField form={form} name="firstName" label="First name" maxLength={80} />
            <TextField form={form} name="lastName" label="Last name" maxLength={80} />
          </div>
          <div className="grid gap-3 sm:grid-cols-2">
            <TextField form={form} name="companyName" label="Company" maxLength={200} />
            <TextField form={form} name="jobTitle" label="Job title" maxLength={100} />
          </div>
          <div className="grid gap-3 sm:grid-cols-2">
            <TextField form={form} name="email" label="Email" type="email" />
            <TextField form={form} name="phone" label="Phone" maxLength={40} />
          </div>
          <div className="grid gap-3 sm:grid-cols-3">
            <Field id="field-source" label="Source">
              <NativeSelect id="field-source" {...form.register('source')}>
                {Object.entries(LEAD_SOURCE_LABELS).map(([value, label]) => (
                  <option key={value} value={value}>
                    {label}
                  </option>
                ))}
              </NativeSelect>
            </Field>
            <TextField form={form} name="estimatedValue" label="Estimated value" />
            <TextField
              form={form}
              name="currency"
              label="Currency"
              maxLength={3}
              hint="Defaults to the workspace currency"
            />
          </div>
          <OwnerSelect
            value={form.watch('ownerId')}
            onChange={(id) => form.setValue('ownerId', id)}
            current={lead?.owner}
            error={form.formState.errors.ownerId?.message}
          />
          <TextAreaField form={form} name="description" label="Notes" maxLength={5000} />
          <FormError message={formError} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={form.formState.isSubmitting}>
              {lead ? 'Save changes' : 'Create lead'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
```

- [ ] **Step 5: Import dialog**

`features/crm/LeadImportDialog.tsx`:

```tsx
import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { FormError } from '@/components/form/FormError'
import { useApi } from '@/lib/api/ApiContext'
import { ApiError } from '@/lib/api/client'
import { problemMessage } from '@/lib/api/problems'
import type { ImportResult } from '@/lib/api/types'

interface RowError {
  row: number
  field: string
  message: string
}

const COLUMNS =
  'first_name, last_name, company, job_title, email, phone, source, estimated_value, currency, description'

/** D11: all or nothing. Row errors are listed so the user can fix the spreadsheet and upload again. */
export function LeadImportDialog({ onClose }: { onClose: () => void }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [file, setFile] = useState<File | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [rows, setRows] = useState<RowError[]>([])
  const [errorCount, setErrorCount] = useState(0)

  async function submit() {
    if (!file) return
    setBusy(true)
    setError(null)
    setRows([])
    const form = new FormData()
    form.append('file', file)
    try {
      const result = await api.upload<ImportResult>('/leads/import', form)
      await queryClient.invalidateQueries({ queryKey: ['leads'] })
      toast.success(`${result.imported} leads imported.`)
      onClose()
    } catch (e) {
      const problem = e instanceof ApiError ? (e.problem as { rows?: RowError[]; errorCount?: number }) : null
      if (problem?.rows?.length) {
        setRows(problem.rows)
        setErrorCount(problem.errorCount ?? problem.rows.length)
      }
      setError(problemMessage(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
    >
      <DialogContent className="max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>Import leads</DialogTitle>
          <DialogDescription>
            A UTF-8 CSV file with a header row, up to 500 leads and 256 KB. Columns: {COLUMNS}. Nothing is
            imported if any row has a problem.
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-1.5">
          <Label htmlFor="lead-import-file">CSV file</Label>
          <Input
            id="lead-import-file"
            type="file"
            accept=".csv,text/csv"
            onChange={(e) => setFile(e.target.files?.[0] ?? null)}
          />
        </div>
        <FormError message={error} />
        {rows.length > 0 && (
          <div className="space-y-2">
            {errorCount > rows.length && (
              <p className="text-sm text-muted-foreground">
                Showing the first {rows.length} of {errorCount} problems.
              </p>
            )}
            <Table aria-label="Rows to fix">
              <TableHeader>
                <TableRow>
                  <TableHead>Row</TableHead>
                  <TableHead>Column</TableHead>
                  <TableHead>Problem</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((r, i) => (
                  <TableRow key={`${r.row}-${r.field}-${i}`}>
                    <TableCell>{r.row}</TableCell>
                    <TableCell className="font-mono text-xs">{r.field}</TableCell>
                    <TableCell>{r.message}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
        <DialogFooter>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button onClick={() => void submit()} disabled={!file || busy}>
            Import
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
```

Check `Table` forwards `aria-label` to the `<table>` element (shadcn's `Table` spreads props onto `<table>`); if it puts them on the wrapper `div`, put `role="table"` expectations on the wrapper or adjust the test to `getByRole('table')`.

- [ ] **Step 6: Leads page**

`features/crm/LeadsPage.tsx`:

```tsx
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { NativeSelect } from '@/components/form/NativeSelect'
import { Pagination } from '@/components/Pagination'
import { EmptyState, ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useApi } from '@/lib/api/ApiContext'
import type { LeadView, Page } from '@/lib/api/types'
import { formatMoney } from '@/lib/format'
import { toQuery } from '@/lib/query'
import { LEAD_SOURCE_LABELS, LEAD_STATUS_LABELS } from './labels'
import { LeadFormDialog } from './LeadFormDialog'
import { LeadImportDialog } from './LeadImportDialog'

const SIZE = 20

export function LeadsPage() {
  const api = useApi()
  const can = useCan()
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const q = params.get('q') ?? ''
  const status = params.get('status') ?? ''
  const owner = params.get('owner') ?? ''
  const source = params.get('source') ?? ''
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0)
  const [creating, setCreating] = useState(false)
  const [importing, setImporting] = useState(false)
  const canManage = can(PERMISSIONS.leadManage)

  const leads = useQuery({
    queryKey: ['leads', { q, status, owner, source, page }],
    queryFn: () =>
      api.get<Page<LeadView>>(`/leads?${toQuery({ q, status, owner, source, page, size: SIZE })}`),
    placeholderData: keepPreviousData,
    refetchOnMount: 'always',
  })

  function update(next: Record<string, string>) {
    const merged = new URLSearchParams(params)
    for (const [key, value] of Object.entries(next)) {
      if (value) merged.set(key, value)
      else merged.delete(key)
    }
    setParams(merged, { replace: true })
  }

  function onSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const value = new FormData(event.currentTarget).get('q')
    update({ q: typeof value === 'string' ? value.trim() : '', page: '' })
  }

  return (
    <>
      <PageHeader
        title="Leads"
        description="Prospects to contact, qualify and convert into customers."
        actions={
          canManage && (
            <>
              <Button variant="outline" onClick={() => setImporting(true)}>
                Import CSV
              </Button>
              <Button onClick={() => setCreating(true)}>New lead</Button>
            </>
          )
        }
      />
      <div className="mb-4 flex flex-wrap items-end gap-3">
        <form onSubmit={onSearch} className="flex items-end gap-2" role="search">
          <div className="space-y-1.5">
            <Label htmlFor="leads-q">Search leads</Label>
            <Input id="leads-q" name="q" defaultValue={q} placeholder="Name, company or email" />
          </div>
          <Button type="submit" variant="outline">
            Search
          </Button>
        </form>
        <div className="space-y-1.5">
          <Label htmlFor="leads-status">Status</Label>
          <NativeSelect
            id="leads-status"
            value={status}
            onChange={(e) => update({ status: e.target.value, page: '' })}
          >
            <option value="">Open</option>
            {Object.entries(LEAD_STATUS_LABELS).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </NativeSelect>
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="leads-owner">Owner</Label>
          <NativeSelect
            id="leads-owner"
            value={owner}
            onChange={(e) => update({ owner: e.target.value, page: '' })}
          >
            <option value="">Anyone</option>
            <option value="me">Mine</option>
            <option value="unassigned">Unassigned</option>
          </NativeSelect>
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="leads-source">Source</Label>
          <NativeSelect
            id="leads-source"
            value={source}
            onChange={(e) => update({ source: e.target.value, page: '' })}
          >
            <option value="">Any source</option>
            {Object.entries(LEAD_SOURCE_LABELS).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </NativeSelect>
        </div>
      </div>

      {leads.isPending ? (
        <ListSkeleton />
      ) : leads.isError ? (
        <ErrorState error={leads.error} onRetry={() => void leads.refetch()} />
      ) : leads.data.items.length === 0 ? (
        <EmptyState
          title={q || status || owner || source ? 'No leads match these filters.' : 'No open leads.'}
          description={canManage ? 'Add a lead, or import the spreadsheet you already keep.' : undefined}
          action={
            page > 0 && (
              <Button variant="outline" onClick={() => update({ page: '' })}>
                Back to first page
              </Button>
            )
          }
        />
      ) : (
        <>
          <div className="overflow-x-auto rounded-lg border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Name</TableHead>
                  <TableHead>Company</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead>Source</TableHead>
                  <TableHead>Owner</TableHead>
                  <TableHead>Estimated value</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {leads.data.items.map((lead) => (
                  <TableRow key={lead.id}>
                    <TableCell>
                      <Link
                        to={`/app/crm/leads/${lead.id}`}
                        className="font-medium underline-offset-4 hover:underline"
                      >
                        {lead.name}
                      </Link>
                    </TableCell>
                    <TableCell>{lead.companyName ?? '—'}</TableCell>
                    <TableCell>
                      <Badge variant={lead.status === 'DISQUALIFIED' ? 'outline' : 'secondary'}>
                        {LEAD_STATUS_LABELS[lead.status]}
                      </Badge>
                    </TableCell>
                    <TableCell>{LEAD_SOURCE_LABELS[lead.source]}</TableCell>
                    <TableCell>{lead.owner?.name ?? 'Unassigned'}</TableCell>
                    <TableCell>
                      {lead.estimatedValue != null && lead.currency
                        ? formatMoney(lead.estimatedValue, lead.currency)
                        : '—'}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <Pagination
            page={leads.data.page}
            size={leads.data.size}
            total={leads.data.total}
            onPage={(p) => update({ page: String(p) })}
          />
        </>
      )}

      {creating && (
        <LeadFormDialog
          onClose={() => setCreating(false)}
          onSaved={(saved) => {
            setCreating(false)
            navigate(`/app/crm/leads/${saved.id}`)
          }}
        />
      )}
      {importing && <LeadImportDialog onClose={() => setImporting(false)} />}
    </>
  )
}
```

The page's "Company" column shows the company name, so in the test row the company "Acme Robotics" is found inside the row; the `Deccan Spices` lead has `companyName` equal to its name (the row shows it twice — fine).

Wire it: in `features/crm/routes.tsx` replace the `leads` element's `<Pending title="Leads" />` with `<LeadsPage />` (import from `./LeadsPage`).

- [ ] **Step 7: Run tests, lint, typecheck, commit**

Run: `cd frontend && npx vitest run src/features/crm && npm run lint && npm run typecheck`
Expected: PASS.

```bash
git add frontend/src/features/crm
git commit -m "feat(frontend): leads list with filters, lead dialog and CSV import"
```

---

### Task 11: Lead detail, status actions and the conversion dialog

**Files:**
- Create: `frontend/src/features/crm/LeadDetailPage.tsx`, `LeadStatusActions.tsx`, `ConvertLeadDialog.tsx`, `LeadDetailPage.test.tsx`, `ConvertLeadDialog.test.tsx`
- Modify: `frontend/src/features/crm/routes.tsx` (`leads/:leadId` → `<LeadDetailPage />`)

**Interfaces:**
- Consumes: Task 9 (`PartyPicker`, labels, fixtures, `defaultStages`), Task 10 (`LeadFormDialog`, `reasonSchema`, `moneySchema`), existing `ActivityPanel`, `SubjectTasksPanel`, `DocumentsPanel`, `DuplicateNotice`, `duplicatesOf`.
- Produces: `<ConvertLeadDialog lead onClose onConverted />`.

- [ ] **Step 1: Write the failing tests**

`features/crm/LeadDetailPage.test.tsx`:

```tsx
import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ALL_TENANT_PERMISSIONS } from '@/features/auth/permissions'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aLead, pageOf } from '@/test/records'
import { renderApp } from '@/test/renderApp'
import type { LeadView } from '@/lib/api/types'

function setup(lead: LeadView = aLead(), permissions: string[] = [...ALL_TENANT_PERMISSIONS]) {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['CRM'], permissions }))
    .on('GET /leads/:id', { body: lead })
    .on('GET /activities', { body: pageOf([]) })
    .on('GET /documents', { body: [] })
    .on('GET /tasks', { body: pageOf([]) })
    .on('GET /crm/owners', { body: [] })
  return renderApp({ server, path: `/app/crm/leads/${lead.id}` })
}

describe('LeadDetailPage', () => {
  it('shows the lead with its record panels', async () => {
    const { server } = setup()
    expect(await screen.findByRole('heading', { name: 'Grace Hopper' })).toBeInTheDocument()
    expect(screen.getByText('grace@acme.test')).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Activity' })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Tasks' })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Documents' })).toBeInTheDocument()
    expect(server.callsTo('GET /activities')[0].query.get('subjectType')).toBe('LEAD')
  })

  it('moves the lead through its statuses', async () => {
    const { server, user } = setup()
    server.on('POST /leads/:id/status', { body: aLead({ status: 'CONTACTED', version: 1 }) })
    await user.click(await screen.findByRole('button', { name: 'Mark contacted' }))
    expect(server.callsTo('POST /leads/:id/status')[0].body).toEqual({ status: 'CONTACTED', version: 0 })
    expect(await screen.findByText('Contacted')).toBeInTheDocument()
  })

  it('asks for a reason to disqualify and can reopen', async () => {
    const { server, user } = setup()
    server.on('POST /leads/:id/status', (req) =>
      (req.body as { status: string }).status === 'DISQUALIFIED'
        ? { body: aLead({ status: 'DISQUALIFIED', disqualifyReason: 'No budget', version: 1 }) }
        : { body: aLead({ status: 'NEW', version: 2 }) },
    )
    await user.click(await screen.findByRole('button', { name: 'Disqualify' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Disqualify' }))
    expect(await within(dialog).findByText('Required.')).toBeInTheDocument()
    await user.type(within(dialog).getByLabelText('Reason'), 'No budget')
    await user.click(within(dialog).getByRole('button', { name: 'Disqualify' }))
    expect(await screen.findByText(/No budget/)).toBeInTheDocument()
    expect(server.callsTo('POST /leads/:id/status')[0].body).toEqual({
      status: 'DISQUALIFIED',
      reason: 'No budget',
      version: 0,
    })
    await user.click(screen.getByRole('button', { name: 'Reopen' }))
    expect(server.callsTo('POST /leads/:id/status')[1].body).toEqual({ status: 'NEW', version: 1 })
  })

  it('shows what a converted lead became and freezes it', async () => {
    setup(
      aLead({
        status: 'CONVERTED',
        convertedAt: '2026-10-06T10:00:00Z',
        convertedPerson: { id: 'p-grace', name: 'Grace Hopper' },
        convertedOrganization: { id: 'p-acme', name: 'Acme Robotics' },
        convertedOpportunityId: 'o-renewal',
      }),
    )
    expect(await screen.findByText(/This lead was converted/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Acme Robotics' })).toHaveAttribute('href', '/app/directory/p-acme')
    expect(screen.getByRole('link', { name: 'Open the opportunity' })).toHaveAttribute(
      'href',
      '/app/crm/opportunities/o-renewal',
    )
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Convert' })).not.toBeInTheDocument()
  })

  it('shows no actions to readers', async () => {
    setup(aLead(), ['crm.lead.read'])
    await screen.findByRole('heading', { name: 'Grace Hopper' })
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Mark contacted' })).not.toBeInTheDocument()
  })
})
```

`features/crm/ConvertLeadDialog.test.tsx`:

```tsx
import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ALL_TENANT_PERMISSIONS } from '@/features/auth/permissions'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aLead, aSummary, defaultStages, pageOf } from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup() {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['CRM'], permissions: [...ALL_TENANT_PERMISSIONS] }))
    .on('GET /leads/:id', { body: aLead() })
    .on('GET /activities', { body: pageOf([]) })
    .on('GET /documents', { body: [] })
    .on('GET /tasks', { body: pageOf([]) })
    .on('GET /crm/pipeline/stages', { body: defaultStages() })
    .on('GET /parties', { body: pageOf([aSummary({ id: 'p-acme-ltd', name: 'Acme Robotics Ltd' })]) })
  return renderApp({ server, path: '/app/crm/leads/l-grace' })
}

async function open(user: ReturnType<typeof setup>['user']) {
  await user.click(await screen.findByRole('button', { name: 'Convert' }))
  return screen.findByRole('dialog')
}

describe('ConvertLeadDialog', () => {
  it('creates the person, organization and opportunity from the lead', async () => {
    const { server, user } = setup()
    server.on('POST /leads/:id/convert', { body: aLead({ status: 'CONVERTED', convertedAt: '2026-10-07T00:00:00Z', version: 1 }) })
    const dialog = await open(user)
    expect(within(dialog).getByLabelText('Organization name')).toHaveValue('Acme Robotics')
    expect(within(dialog).getByLabelText('First name')).toHaveValue('Grace')
    expect(within(dialog).getByLabelText('Opportunity name')).toHaveValue('Acme Robotics')
    await user.click(within(dialog).getByRole('button', { name: 'Convert lead' }))
    expect(server.callsTo('POST /leads/:id/convert')[0].body).toEqual({
      organization: { name: 'Acme Robotics', domain: null, duplicateReason: null },
      person: {
        firstName: 'Grace',
        lastName: 'Hopper',
        jobTitle: null,
        email: 'grace@acme.test',
        phone: null,
        duplicateReason: null,
      },
      opportunity: { name: 'Acme Robotics', amount: 5000, currency: 'USD', stageId: 's-prospecting', expectedCloseOn: null },
      version: 0,
    })
    expect(await screen.findByText('Lead converted.')).toBeInTheDocument()
  })

  it('shows a duplicate organization and lets the user link it instead', async () => {
    const { server, user } = setup()
    server.on('POST /leads/:id/convert', (req) => {
      const org = (req.body as { organization?: { existingId?: string } }).organization
      return org?.existingId
        ? { body: aLead({ status: 'CONVERTED', convertedAt: '2026-10-07T00:00:00Z', version: 1 }) }
        : {
            status: 409,
            body: {
              detail: 'This looks like a record that already exists.',
              party: 'organization',
              duplicates: [{ id: 'p-acme-ltd', kind: 'ORGANIZATION', name: 'Acme Robotics Ltd', email: null, domain: null, archived: false }],
            },
          }
    })
    const dialog = await open(user)
    await user.click(within(dialog).getByRole('button', { name: 'Convert lead' }))
    const notice = await within(dialog).findByRole('alert')
    expect(within(notice).getByText('Acme Robotics Ltd')).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Use Acme Robotics Ltd' }))
    await user.click(within(dialog).getByRole('button', { name: 'Convert lead' }))
    expect(server.callsTo('POST /leads/:id/convert')[1].body).toMatchObject({
      organization: { existingId: 'p-acme-ltd' },
    })
  })

  it('can convert without an organization or opportunity', async () => {
    const { server, user } = setup()
    server.on('POST /leads/:id/convert', { body: aLead({ status: 'CONVERTED', convertedAt: '2026-10-07T00:00:00Z' }) })
    const dialog = await open(user)
    await user.click(within(dialog).getByRole('radio', { name: 'No organization' }))
    await user.click(within(dialog).getByRole('checkbox', { name: 'Create an opportunity' }))
    await user.click(within(dialog).getByRole('button', { name: 'Convert lead' }))
    const body = server.callsTo('POST /leads/:id/convert')[0].body as Record<string, unknown>
    expect(body.organization).toBeNull()
    expect(body.opportunity).toBeNull()
  })

  it('puts server field errors on the right section', async () => {
    const { server, user } = setup()
    server.on('POST /leads/:id/convert', {
      status: 400,
      body: { detail: 'Request validation failed.', errors: [{ field: 'person.firstName', message: 'Enter between 1 and 80 characters.' }] },
    })
    const dialog = await open(user)
    await user.click(within(dialog).getByRole('button', { name: 'Convert lead' }))
    expect(await within(dialog).findByText('Enter between 1 and 80 characters.')).toBeInTheDocument()
  })
})
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd frontend && npx vitest run src/features/crm/LeadDetailPage.test.tsx src/features/crm/ConvertLeadDialog.test.tsx`
Expected: FAIL.

- [ ] **Step 3: Status actions**

`features/crm/LeadStatusActions.tsx`:

```tsx
import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { FormError } from '@/components/form/FormError'
import { TextAreaField } from '@/components/form/TextAreaField'
import { useApi } from '@/lib/api/ApiContext'
import { problemMessage } from '@/lib/api/problems'
import type { LeadStatus, LeadView } from '@/lib/api/types'
import { reasonSchema } from './schemas'

/** D3: open statuses move freely; disqualifying needs a reason; a disqualified lead reopens as New. */
export function LeadStatusActions({ lead, onChanged }: { lead: LeadView; onChanged: (l: LeadView) => void }) {
  const api = useApi()
  const [busy, setBusy] = useState(false)
  const [disqualifying, setDisqualifying] = useState(false)

  async function change(status: LeadStatus, reason?: string) {
    setBusy(true)
    try {
      const updated = await api.post<LeadView>(`/leads/${lead.id}/status`, {
        status,
        ...(reason ? { reason } : {}),
        version: lead.version,
      })
      onChanged(updated)
      return true
    } catch (error) {
      toast.error(problemMessage(error))
      return false
    } finally {
      setBusy(false)
    }
  }

  if (lead.status === 'CONVERTED') return null
  if (lead.status === 'DISQUALIFIED')
    return (
      <Button variant="outline" size="sm" disabled={busy} onClick={() => void change('NEW')}>
        Reopen
      </Button>
    )
  return (
    <>
      {lead.status !== 'CONTACTED' && (
        <Button variant="outline" size="sm" disabled={busy} onClick={() => void change('CONTACTED')}>
          Mark contacted
        </Button>
      )}
      {lead.status !== 'QUALIFIED' && (
        <Button variant="outline" size="sm" disabled={busy} onClick={() => void change('QUALIFIED')}>
          Mark qualified
        </Button>
      )}
      <Button variant="outline" size="sm" disabled={busy} onClick={() => setDisqualifying(true)}>
        Disqualify
      </Button>
      {disqualifying && (
        <DisqualifyDialog
          onClose={() => setDisqualifying(false)}
          onSubmit={async (reason) => {
            if (await change('DISQUALIFIED', reason)) setDisqualifying(false)
          }}
        />
      )}
    </>
  )
}

function DisqualifyDialog({
  onClose,
  onSubmit,
}: {
  onClose: () => void
  onSubmit: (reason: string) => Promise<void>
}) {
  const [error] = useState<string | null>(null)
  const form = useForm<{ reason: string }>({ resolver: zodResolver(reasonSchema), defaultValues: { reason: '' } })
  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
    >
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Disqualify lead</DialogTitle>
          <DialogDescription>Say why, so the team knows. You can reopen it later.</DialogDescription>
        </DialogHeader>
        <form noValidate onSubmit={form.handleSubmit((v) => onSubmit(v.reason))} className="space-y-4">
          <TextAreaField form={form} name="reason" label="Reason" maxLength={500} />
          <FormError message={error} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" variant="destructive" disabled={form.formState.isSubmitting}>
              Disqualify
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
```

- [ ] **Step 4: Conversion dialog**

`features/crm/ConvertLeadDialog.tsx`:

```tsx
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { Field } from '@/components/form/Field'
import { FormError } from '@/components/form/FormError'
import { NativeSelect } from '@/components/form/NativeSelect'
import { useApi } from '@/lib/api/ApiContext'
import { ApiError } from '@/lib/api/client'
import { problemMessage } from '@/lib/api/problems'
import type { DuplicateCandidate, LeadView, PartyRef, StageView } from '@/lib/api/types'
import { duplicatesOf } from '@/features/records/duplicates'
import { PartyPicker } from './PartyPicker'

type Mode = 'create' | 'link' | 'none'

interface Section {
  mode: Mode
  existingId: string
  /** The candidate chosen from a duplicate notice, so the picker lists it before any search. */
  chosen: PartyRef | null
  candidates: DuplicateCandidate[] | null
  reason: string
}

const fresh = (mode: Mode): Section => ({ mode, existingId: '', chosen: null, candidates: null, reason: '' })

/**
 * D4: choose (link or create) an organization and a person, optionally open an opportunity. A probable duplicate
 * comes back as a 409 naming the section; the user links the candidate or gives a reason.
 */
export function ConvertLeadDialog({
  lead,
  onClose,
  onConverted,
}: {
  lead: LeadView
  onClose: () => void
  onConverted: (converted: LeadView) => void
}) {
  const api = useApi()
  const queryClient = useQueryClient()
  const stages = useQuery({ queryKey: ['crm-stages'], queryFn: () => api.get<StageView[]>('/crm/pipeline/stages') })
  const openStages = (stages.data ?? []).filter((s) => s.kind === 'OPEN')

  const [org, setOrg] = useState<Section>(fresh(lead.companyName ? 'create' : 'none'))
  const [orgName, setOrgName] = useState(lead.companyName ?? '')
  const [orgDomain, setOrgDomain] = useState('')
  const [person, setPerson] = useState<Section>(fresh(lead.firstName || lead.lastName ? 'create' : 'none'))
  const [firstName, setFirstName] = useState(lead.firstName ?? '')
  const [lastName, setLastName] = useState(lead.lastName ?? '')
  const [withDeal, setWithDeal] = useState(true)
  const [dealName, setDealName] = useState(lead.companyName ?? lead.name)
  const [amount, setAmount] = useState(lead.estimatedValue == null ? '' : String(lead.estimatedValue))
  const [stageId, setStageId] = useState('')
  const [closeOn, setCloseOn] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  function section(mode: Mode, value: Section, fields: () => Record<string, unknown>) {
    if (mode === 'none') return null
    if (mode === 'link') return { existingId: value.existingId || null }
    return { ...fields(), duplicateReason: value.candidates ? value.reason.trim() || null : null }
  }

  async function submit() {
    setErrors({})
    setFormError(null)
    setBusy(true)
    const body = {
      organization: section(org.mode, org, () => ({ name: orgName.trim(), domain: orgDomain.trim() || null })),
      person: section(person.mode, person, () => ({
        firstName: firstName.trim(),
        lastName: lastName.trim() || null,
        jobTitle: lead.jobTitle,
        email: lead.email,
        phone: lead.phone,
      })),
      opportunity: withDeal
        ? {
            name: dealName.trim(),
            amount: amount === '' ? null : Number(amount),
            currency: amount === '' ? null : lead.currency,
            stageId: stageId || openStages[0]?.id || null,
            expectedCloseOn: closeOn || null,
          }
        : null,
      version: lead.version,
    }
    try {
      const converted = await api.post<LeadView>(`/leads/${lead.id}/convert`, body)
      queryClient.setQueryData(['lead', converted.id], converted)
      await queryClient.invalidateQueries({ queryKey: ['leads'] })
      await queryClient.invalidateQueries({ queryKey: ['crm-board'] })
      toast.success('Lead converted.')
      onConverted(converted)
    } catch (error) {
      const candidates = duplicatesOf(error)
      const party = error instanceof ApiError ? (error.problem as { party?: string }).party : undefined
      if (candidates && party === 'organization') setOrg((s) => ({ ...s, candidates }))
      else if (candidates && party === 'person') setPerson((s) => ({ ...s, candidates }))
      else if (error instanceof ApiError && error.problem.errors?.length) {
        setErrors(Object.fromEntries(error.problem.errors.map((e) => [e.field, e.message])))
      } else setFormError(problemMessage(error))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
    >
      <DialogContent className="max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>Convert {lead.name}</DialogTitle>
          <DialogDescription>
            Link the people and organizations you already have, or create them. The account becomes a customer.
          </DialogDescription>
        </DialogHeader>

        <fieldset className="space-y-3 rounded-lg border p-3">
          <legend className="px-1 text-sm font-medium">Organization</legend>
          <ModeRadios name="org" value={org.mode} onChange={(mode) => setOrg(fresh(mode))} noneLabel="No organization" />
          {org.mode === 'create' && (
            <div className="grid gap-3 sm:grid-cols-2">
              <TextInput id="convert-org-name" label="Organization name" value={orgName} error={errors['organization.name']}
                onChange={(v) => { setOrgName(v); setOrg((s) => ({ ...s, candidates: null, reason: '' })) }} />
              <TextInput id="convert-org-domain" label="Domain" value={orgDomain} error={errors['organization.domain']}
                onChange={(v) => { setOrgDomain(v); setOrg((s) => ({ ...s, candidates: null, reason: '' })) }} />
            </div>
          )}
          {org.mode === 'link' && (
            <PartyPicker id="convert-org-existing" label="Organization" kind="ORGANIZATION" value={org.existingId}
              current={org.chosen} error={errors['organization.existingId']} noneLabel="Choose…"
              onChange={(id) => setOrg((s) => ({ ...s, existingId: id }))} />
          )}
          {org.candidates && (
            <Duplicates candidates={org.candidates} reason={org.reason} onClose={onClose}
              onReason={(reason) => setOrg((s) => ({ ...s, reason }))}
              onUse={(c) => setOrg({ ...fresh('link'), existingId: c.id, chosen: { id: c.id, name: c.name } })} />
          )}
        </fieldset>

        <fieldset className="space-y-3 rounded-lg border p-3">
          <legend className="px-1 text-sm font-medium">Person</legend>
          <ModeRadios name="person" value={person.mode} onChange={(mode) => setPerson(fresh(mode))} noneLabel="No person" />
          {person.mode === 'create' && (
            <div className="grid gap-3 sm:grid-cols-2">
              <TextInput id="convert-first-name" label="First name" value={firstName} error={errors['person.firstName']}
                onChange={(v) => { setFirstName(v); setPerson((s) => ({ ...s, candidates: null, reason: '' })) }} />
              <TextInput id="convert-last-name" label="Last name" value={lastName} error={errors['person.lastName']}
                onChange={(v) => { setLastName(v); setPerson((s) => ({ ...s, candidates: null, reason: '' })) }} />
            </div>
          )}
          {person.mode === 'link' && (
            <PartyPicker id="convert-person-existing" label="Person" kind="PERSON" value={person.existingId}
              current={person.chosen} error={errors['person.existingId']} noneLabel="Choose…"
              onChange={(id) => setPerson((s) => ({ ...s, existingId: id }))} />
          )}
          {person.candidates && (
            <Duplicates candidates={person.candidates} reason={person.reason} onClose={onClose}
              onReason={(reason) => setPerson((s) => ({ ...s, reason }))}
              onUse={(c) => setPerson({ ...fresh('link'), existingId: c.id, chosen: { id: c.id, name: c.name } })} />
          )}
        </fieldset>

        <fieldset className="space-y-3 rounded-lg border p-3">
          <legend className="px-1 text-sm font-medium">Opportunity</legend>
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={withDeal} onChange={(e) => setWithDeal(e.target.checked)} />
            Create an opportunity
          </label>
          {withDeal && (
            <div className="grid gap-3 sm:grid-cols-2">
              <TextInput id="convert-deal-name" label="Opportunity name" value={dealName} error={errors['opportunity.name']}
                onChange={setDealName} />
              <TextInput id="convert-deal-amount" label={`Amount${lead.currency ? ` (${lead.currency})` : ''}`}
                value={amount} error={errors['opportunity.amount']} onChange={setAmount} />
              <Field id="convert-deal-stage" label="Stage" error={errors['opportunity.stageId']}>
                <NativeSelect id="convert-deal-stage" value={stageId || openStages[0]?.id || ''}
                  onChange={(e) => setStageId(e.target.value)}>
                  {openStages.map((s) => (
                    <option key={s.id} value={s.id}>{s.name}</option>
                  ))}
                </NativeSelect>
              </Field>
              <Field id="convert-deal-close" label="Expected close" error={errors['opportunity.expectedCloseOn']}>
                <Input id="convert-deal-close" type="date" value={closeOn} onChange={(e) => setCloseOn(e.target.value)} />
              </Field>
            </div>
          )}
        </fieldset>

        <FormError message={formError ?? (errors.person ? errors.person : null)} />
        <DialogFooter>
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button onClick={() => void submit()} disabled={busy}>Convert lead</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function ModeRadios({ name, value, onChange, noneLabel }: { name: string; value: Mode; onChange: (m: Mode) => void; noneLabel: string }) {
  const options: Array<[Mode, string]> = [['create', 'Create new'], ['link', 'Use existing'], ['none', noneLabel]]
  return (
    <div role="radiogroup" className="flex flex-wrap gap-4 text-sm">
      {options.map(([mode, label]) => (
        <label key={mode} className="flex items-center gap-2">
          <input type="radio" name={`convert-${name}`} checked={value === mode} onChange={() => onChange(mode)} />
          {label}
        </label>
      ))}
    </div>
  )
}

function TextInput({ id, label, value, onChange, error }: { id: string; label: string; value: string; onChange: (v: string) => void; error?: string }) {
  return (
    <Field id={id} label={label} error={error}>
      <Input id={id} value={value} aria-invalid={error ? true : undefined} onChange={(e) => onChange(e.target.value)} />
    </Field>
  )
}

function Duplicates({ candidates, reason, onReason, onUse, onClose }: {
  candidates: DuplicateCandidate[]
  reason: string
  onReason: (v: string) => void
  onUse: (c: DuplicateCandidate) => void
  onClose: () => void
}) {
  return (
    <div role="alert" className="space-y-2 rounded-md border border-amber-300 bg-amber-50 p-3 text-sm dark:border-amber-800 dark:bg-amber-950/30">
      <p className="font-medium">This looks like a record that already exists.</p>
      <ul className="space-y-1">
        {candidates.map((c) => (
          <li key={c.id} className="flex flex-wrap items-center gap-2">
            <Link to={`/app/directory/${c.id}`} className="underline" onClick={onClose}>{c.name}</Link>
            <Button size="sm" variant="outline" onClick={() => onUse(c)}>Use {c.name}</Button>
          </li>
        ))}
      </ul>
      <Label htmlFor="convert-duplicate-reason">Or keep a separate record because…</Label>
      <Textarea id="convert-duplicate-reason" rows={2} maxLength={500} value={reason} onChange={(e) => onReason(e.target.value)} />
    </div>
  )
}
```

(Run Prettier — `npm run lint -- --fix` or the repo's format script — to reflow the long JSX lines.)

Choosing a candidate from the duplicate notice switches that section to "Use existing" with the candidate preselected (`chosen` keeps it listed in the picker).

- [ ] **Step 5: Lead detail page**

`features/crm/LeadDetailPage.tsx`:

```tsx
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { ActivityPanel } from '@/features/records/ActivityPanel'
import { DocumentsPanel } from '@/features/records/DocumentsPanel'
import { SubjectTasksPanel } from '@/features/records/SubjectTasksPanel'
import { useApi } from '@/lib/api/ApiContext'
import type { LeadView } from '@/lib/api/types'
import { formatDateTime, formatMoney } from '@/lib/format'
import { ConvertLeadDialog } from './ConvertLeadDialog'
import { LEAD_SOURCE_LABELS, LEAD_STATUS_LABELS } from './labels'
import { LeadFormDialog } from './LeadFormDialog'
import { LeadStatusActions } from './LeadStatusActions'

export function LeadDetailPage() {
  const { leadId = '' } = useParams()
  const api = useApi()
  const can = useCan()
  const queryClient = useQueryClient()
  const [editing, setEditing] = useState(false)
  const [converting, setConverting] = useState(false)
  const lead = useQuery({ queryKey: ['lead', leadId], queryFn: () => api.get<LeadView>(`/leads/${leadId}`) })
  const back = (
    <Link to="/app/crm/leads" className="text-sm underline-offset-4 hover:underline">
      ← Leads
    </Link>
  )
  if (lead.isPending)
    return (
      <>
        {back}
        <ListSkeleton />
      </>
    )
  if (lead.isError)
    return (
      <>
        {back}
        <ErrorState error={lead.error} onRetry={() => void lead.refetch()} />
      </>
    )

  const l = lead.data
  const converted = l.status === 'CONVERTED'
  const canManage = can(PERMISSIONS.leadManage) && !converted
  function stored(updated: LeadView) {
    queryClient.setQueryData(['lead', updated.id], updated)
    void queryClient.invalidateQueries({ queryKey: ['leads'] })
  }

  return (
    <div className="space-y-6">
      {back}
      <PageHeader
        title={l.name}
        description={[l.companyName !== l.name ? l.companyName : null, LEAD_SOURCE_LABELS[l.source]]
          .filter(Boolean)
          .join(' · ')}
        actions={
          canManage && (
            <>
              <Badge variant="secondary">{LEAD_STATUS_LABELS[l.status]}</Badge>
              <LeadStatusActions lead={l} onChanged={stored} />
              <Button variant="outline" size="sm" onClick={() => setEditing(true)}>
                Edit
              </Button>
              {l.status !== 'DISQUALIFIED' && (
                <Button size="sm" onClick={() => setConverting(true)}>
                  Convert
                </Button>
              )}
            </>
          )
        }
      />
      {!canManage && <Badge variant="secondary">{LEAD_STATUS_LABELS[l.status]}</Badge>}
      {l.status === 'DISQUALIFIED' && (
        <p role="status" className="rounded-md border bg-muted/40 px-3 py-2 text-sm">
          Disqualified: {l.disqualifyReason}
        </p>
      )}
      {converted && (
        <div role="status" className="space-y-1 rounded-md border bg-muted/40 px-3 py-2 text-sm">
          <p>This lead was converted on {formatDateTime(l.convertedAt)}. It can no longer be changed.</p>
          <p className="flex flex-wrap gap-3">
            {l.convertedOrganization && (
              <Link to={`/app/directory/${l.convertedOrganization.id}`} className="underline">
                {l.convertedOrganization.name}
              </Link>
            )}
            {l.convertedPerson && (
              <Link to={`/app/directory/${l.convertedPerson.id}`} className="underline">
                {l.convertedPerson.name}
              </Link>
            )}
            {l.convertedOpportunityId && (
              <Link to={`/app/crm/opportunities/${l.convertedOpportunityId}`} className="underline">
                Open the opportunity
              </Link>
            )}
          </p>
        </div>
      )}
      <Card>
        <CardHeader>
          <CardTitle>Details</CardTitle>
        </CardHeader>
        <CardContent className="space-y-2 text-sm">
          {l.description && <p className="whitespace-pre-wrap">{l.description}</p>}
          <dl className="grid grid-cols-[9rem_1fr] gap-x-3 gap-y-2">
            <dt className="text-muted-foreground">Email</dt>
            <dd>{l.email ?? '—'}</dd>
            <dt className="text-muted-foreground">Phone</dt>
            <dd>{l.phone ?? '—'}</dd>
            <dt className="text-muted-foreground">Job title</dt>
            <dd>{l.jobTitle ?? '—'}</dd>
            <dt className="text-muted-foreground">Owner</dt>
            <dd>{l.owner?.name ?? 'Unassigned'}</dd>
            <dt className="text-muted-foreground">Estimated value</dt>
            <dd>{l.estimatedValue != null && l.currency ? formatMoney(l.estimatedValue, l.currency) : '—'}</dd>
          </dl>
        </CardContent>
      </Card>
      <SubjectTasksPanel subjectType="LEAD" subjectId={l.id} label={l.name} archived={converted} />
      <ActivityPanel subjectType="LEAD" subjectId={l.id} archived={converted} />
      <DocumentsPanel subjectType="LEAD" subjectId={l.id} archived={converted} />
      {editing && (
        <LeadFormDialog
          lead={l}
          onClose={() => setEditing(false)}
          onSaved={(saved) => {
            stored(saved)
            setEditing(false)
          }}
        />
      )}
      {converting && (
        <ConvertLeadDialog
          lead={l}
          onClose={() => setConverting(false)}
          onConverted={(c) => {
            stored(c)
            setConverting(false)
          }}
        />
      )}
    </div>
  )
}
```

Note the status badge: shown once — inside the actions for managers, under the header for readers. (In the "moves through statuses" test the badge text `Contacted` appears after the update.)

Wire it: `leads/:leadId` → `<LeadDetailPage />` in `features/crm/routes.tsx`.

- [ ] **Step 6: Run tests, lint, typecheck, commit**

Run: `cd frontend && npx vitest run src/features/crm && npm run lint && npm run typecheck`
Expected: PASS.

```bash
git add frontend/src/features/crm
git commit -m "feat(frontend): lead detail with status actions and the lead conversion dialog"
```

---
### Task 12: Pipeline board, opportunity dialog and opportunity detail

**Files:**
- Create: `frontend/src/features/crm/MoveStageControl.tsx`, `OpportunityFormDialog.tsx`, `PipelinePage.tsx`, `OpportunityDetailPage.tsx`, `PipelinePage.test.tsx`, `OpportunityDetailPage.test.tsx`
- Modify: `frontend/src/features/crm/schemas.ts` (+ `opportunitySchema`), `routes.tsx` (`pipeline`, `opportunities/:opportunityId`)

**Interfaces:**
- Consumes: Task 9 (`PartyPicker`, `OwnerSelect`, `formatTotals`, `OPPORTUNITY_STATUS_LABELS`, fixtures `aBoard`, `anOpportunity`, `defaultStages`), Task 10 (`moneySchema`, `currencySchema`, `reasonSchema`).
- Produces: `<MoveStageControl opportunity={{id, name, stage, version}} stages onMoved />` (select labelled `Move {name} to`; choosing Lost opens a reason dialog), `<OpportunityFormDialog opportunity? account? onClose onSaved />`.

- [ ] **Step 1: Write the failing tests**

`features/crm/PipelinePage.test.tsx`:

```tsx
import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ALL_TENANT_PERMISSIONS } from '@/features/auth/permissions'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aBoard, anOpportunity, aSummary, defaultStages, pageOf } from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup(permissions: string[] = [...ALL_TENANT_PERMISSIONS]) {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['CRM'], permissions }))
    .on('GET /crm/pipeline/board', { body: aBoard() })
    .on('GET /crm/pipeline/stages', { body: defaultStages() })
    .on('GET /crm/owners', { body: [] })
    .on('GET /parties', { body: pageOf([aSummary()]) })
    .on('GET /opportunities/:id', { body: anOpportunity() })
    .on('GET /activities', { body: pageOf([]) })
    .on('GET /documents', { body: [] })
  return renderApp({ server, path: '/app/crm/pipeline' })
}

describe('PipelinePage', () => {
  it('shows a column per stage with counts and per-currency totals', async () => {
    setup()
    const column = await screen.findByRole('region', { name: 'Prospecting' })
    expect(within(column).getByText('1 deal')).toBeInTheDocument()
    // the column total and the card both show the amount
    expect(within(column).getAllByText(/\$1,200\.00/)).toHaveLength(2)
    expect(within(column).getByRole('link', { name: 'Packaging renewal' })).toHaveAttribute(
      'href',
      '/app/crm/opportunities/o-renewal',
    )
    expect(screen.getByRole('region', { name: 'Won' })).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'Won' })).getByText('Closed in the last 30 days')).toBeInTheDocument()
  })

  it('moves a card to another stage', async () => {
    const { server, user } = setup()
    server.on('POST /opportunities/:id/stage', { body: anOpportunity({ stage: { id: 's-proposal', name: 'Proposal', kind: 'OPEN', probability: 50 }, version: 1 }) })
    await user.selectOptions(await screen.findByLabelText('Move Packaging renewal to'), 's-proposal')
    expect(server.callsTo('POST /opportunities/:id/stage')[0].body).toEqual({ stageId: 's-proposal', version: 0 })
    expect(await screen.findByText('Moved to Proposal.')).toBeInTheDocument()
    expect(server.callsTo('GET /crm/pipeline/board').length).toBeGreaterThan(1)
  })

  it('asks why a deal was lost', async () => {
    const { server, user } = setup()
    server.on('POST /opportunities/:id/stage', { body: anOpportunity({ status: 'LOST', version: 1 }) })
    await user.selectOptions(await screen.findByLabelText('Move Packaging renewal to'), 's-lost')
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Reason'), 'Chose a competitor')
    await user.click(within(dialog).getByRole('button', { name: 'Mark as lost' }))
    expect(server.callsTo('POST /opportunities/:id/stage')[0].body).toEqual({
      stageId: 's-lost',
      lostReason: 'Chose a competitor',
      version: 0,
    })
  })

  it('creates an opportunity for an account', async () => {
    const { server, user, router } = setup()
    server.on('POST /opportunities', { status: 201, body: anOpportunity({ id: 'o-new', name: 'Spice supply' }) })
    await user.click(await screen.findByRole('button', { name: 'New opportunity' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Name'), 'Spice supply')
    await user.selectOptions(await within(dialog).findByLabelText('Account'), 'p-acme')
    await user.type(within(dialog).getByLabelText('Amount'), '900')
    await user.type(within(dialog).getByLabelText('Currency'), 'inr')
    await user.click(within(dialog).getByRole('button', { name: 'Create opportunity' }))
    expect(server.callsTo('POST /opportunities')[0].body).toEqual({
      name: 'Spice supply',
      accountId: 'p-acme',
      contactId: null,
      stageId: 's-prospecting',
      amount: 900,
      currency: 'INR',
      expectedCloseOn: null,
      ownerId: null,
      description: null,
    })
    expect(router.state.location.pathname).toBe('/app/crm/opportunities/o-new')
  })

  it('requires an account before sending', async () => {
    const { server, user } = setup()
    await user.click(await screen.findByRole('button', { name: 'New opportunity' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Name'), 'X')
    await user.click(within(dialog).getByRole('button', { name: 'Create opportunity' }))
    expect(await within(dialog).findByText('Choose an account.')).toBeInTheDocument()
    expect(server.callsTo('POST /opportunities')).toHaveLength(0)
  })

  it('is read-only for readers', async () => {
    setup(['crm.opportunity.read'])
    await screen.findByRole('region', { name: 'Prospecting' })
    expect(screen.queryByLabelText('Move Packaging renewal to')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'New opportunity' })).not.toBeInTheDocument()
  })
})
```

`features/crm/OpportunityDetailPage.test.tsx`:

```tsx
import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ALL_TENANT_PERMISSIONS } from '@/features/auth/permissions'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { anOpportunity, defaultStages, pageOf } from '@/test/records'
import { renderApp } from '@/test/renderApp'
import type { OpportunityView } from '@/lib/api/types'

function setup(o: OpportunityView = anOpportunity(), permissions: string[] = [...ALL_TENANT_PERMISSIONS]) {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['CRM'], permissions }))
    .on('GET /opportunities/:id', { body: o })
    .on('GET /crm/pipeline/stages', { body: defaultStages() })
    .on('GET /activities', { body: pageOf([]) })
    .on('GET /documents', { body: [] })
    .on('GET /tasks', { body: pageOf([]) })
  return renderApp({ server, path: `/app/crm/opportunities/${o.id}` })
}

describe('OpportunityDetailPage', () => {
  it('shows the deal, its account and its record panels', async () => {
    const { server } = setup()
    expect(await screen.findByRole('heading', { name: 'Packaging renewal' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Acme' })).toHaveAttribute('href', '/app/crm/customers/p-acme')
    expect(screen.getByRole('link', { name: 'Grace Hopper' })).toHaveAttribute('href', '/app/directory/p-grace')
    expect(screen.getByText(/\$1,200\.00/)).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Activity' })).toBeInTheDocument()
    expect(server.callsTo('GET /activities')[0].query.get('subjectType')).toBe('OPPORTUNITY')
  })

  it('shows why a deal was lost and links its source lead', async () => {
    setup(anOpportunity({ status: 'LOST', stage: { id: 's-lost', name: 'Lost', kind: 'LOST', probability: 0 }, lostReason: 'Price', closedAt: '2026-10-06T00:00:00Z', leadId: 'l-grace' }))
    expect(await screen.findByText('Price')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Source lead' })).toHaveAttribute('href', '/app/crm/leads/l-grace')
  })

  it('links the account to the directory without customer access', async () => {
    setup(anOpportunity(), ['crm.opportunity.read', 'directory.party.read'])
    expect(await screen.findByRole('link', { name: 'Acme' })).toHaveAttribute('href', '/app/directory/p-acme')
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument()
  })
})
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd frontend && npx vitest run src/features/crm/PipelinePage.test.tsx src/features/crm/OpportunityDetailPage.test.tsx`
Expected: FAIL.

- [ ] **Step 3: Schema**

Append to `features/crm/schemas.ts`:

```ts
export const opportunitySchema = z.object({
  name: requiredText(200),
  accountId: z.string().min(1, 'Choose an account.'),
  contactId: z.string(),
  stageId: z.string(),
  amount: moneySchema,
  currency: currencySchema,
  expectedCloseOn: z.string(),
  ownerId: z.string(),
  description: z.string().max(5000, 'Use at most 5000 characters.'),
})
export type OpportunityValues = z.infer<typeof opportunitySchema>
```

- [ ] **Step 4: Move control**

`features/crm/MoveStageControl.tsx`:

```tsx
import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { NativeSelect } from '@/components/form/NativeSelect'
import { TextAreaField } from '@/components/form/TextAreaField'
import { useApi } from '@/lib/api/ApiContext'
import { problemMessage } from '@/lib/api/problems'
import type { OpportunityView, StageRef, StageView } from '@/lib/api/types'
import { reasonSchema } from './schemas'

/** "Move to…" for one opportunity (board card or detail page). Lost needs a reason (D6). */
export function MoveStageControl({
  opportunity,
  stages,
  onMoved,
}: {
  opportunity: { id: string; name: string; stage: StageRef; version: number }
  stages: StageView[]
  onMoved?: (o: OpportunityView) => void
}) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [busy, setBusy] = useState(false)
  const [losing, setLosing] = useState<StageView | null>(null)

  async function move(stage: StageView, lostReason?: string) {
    setBusy(true)
    try {
      const moved = await api.post<OpportunityView>(`/opportunities/${opportunity.id}/stage`, {
        stageId: stage.id,
        ...(lostReason ? { lostReason } : {}),
        version: opportunity.version,
      })
      queryClient.setQueryData(['opportunity', moved.id], moved)
      await queryClient.invalidateQueries({ queryKey: ['crm-board'] })
      await queryClient.invalidateQueries({ queryKey: ['opportunities'] })
      toast.success(`Moved to ${stage.name}.`)
      onMoved?.(moved)
      return true
    } catch (error) {
      toast.error(problemMessage(error))
      return false
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <NativeSelect
        aria-label={`Move ${opportunity.name} to`}
        value={opportunity.stage.id}
        disabled={busy}
        onChange={(e) => {
          const stage = stages.find((s) => s.id === e.target.value)
          if (!stage) return
          if (stage.kind === 'LOST') setLosing(stage)
          else void move(stage)
        }}
      >
        {stages.map((s) => (
          <option key={s.id} value={s.id}>
            {s.name}
          </option>
        ))}
      </NativeSelect>
      {losing && (
        <LostDialog
          onClose={() => setLosing(null)}
          onSubmit={async (reason) => {
            if (await move(losing, reason)) setLosing(null)
          }}
        />
      )}
    </>
  )
}

function LostDialog({ onClose, onSubmit }: { onClose: () => void; onSubmit: (reason: string) => Promise<void> }) {
  const form = useForm<{ reason: string }>({ resolver: zodResolver(reasonSchema), defaultValues: { reason: '' } })
  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
    >
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Mark as lost</DialogTitle>
          <DialogDescription>Why was this deal lost? It helps the next one.</DialogDescription>
        </DialogHeader>
        <form noValidate onSubmit={form.handleSubmit((v) => onSubmit(v.reason))} className="space-y-4">
          <TextAreaField form={form} name="reason" label="Reason" maxLength={500} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" variant="destructive" disabled={form.formState.isSubmitting}>
              Mark as lost
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
```

- [ ] **Step 5: Opportunity dialog**

`features/crm/OpportunityFormDialog.tsx`:

```tsx
import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { describedBy, Field } from '@/components/form/Field'
import { FormError } from '@/components/form/FormError'
import { NativeSelect } from '@/components/form/NativeSelect'
import { TextAreaField } from '@/components/form/TextAreaField'
import { TextField } from '@/components/form/TextField'
import { useApi } from '@/lib/api/ApiContext'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import type { OpportunityView, PartyRef, StageView } from '@/lib/api/types'
import { OwnerSelect } from './OwnerSelect'
import { PartyPicker } from './PartyPicker'
import { opportunitySchema, type OpportunityValues } from './schemas'

const FIELDS = [
  'name',
  'accountId',
  'contactId',
  'stageId',
  'amount',
  'currency',
  'expectedCloseOn',
  'ownerId',
  'description',
] as const

/** Create (in an open stage) or edit an opportunity. Stage moves use MoveStageControl. */
export function OpportunityFormDialog({
  opportunity,
  account,
  onClose,
  onSaved,
}: {
  opportunity?: OpportunityView
  account?: PartyRef
  onClose: () => void
  onSaved: (saved: OpportunityView) => void
}) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [formError, setFormError] = useState<string | null>(null)
  const stages = useQuery({ queryKey: ['crm-stages'], queryFn: () => api.get<StageView[]>('/crm/pipeline/stages') })
  const openStages = (stages.data ?? []).filter((s) => s.kind === 'OPEN')
  const form = useForm<OpportunityValues>({
    resolver: zodResolver(opportunitySchema),
    defaultValues: {
      name: opportunity?.name ?? '',
      accountId: opportunity?.account?.id ?? account?.id ?? '',
      contactId: opportunity?.contact?.id ?? '',
      stageId: '',
      amount: opportunity?.amount == null ? '' : String(opportunity.amount),
      currency: opportunity?.currency ?? '',
      expectedCloseOn: opportunity?.expectedCloseOn ?? '',
      ownerId: opportunity?.owner?.id ?? '',
      description: opportunity?.description ?? '',
    },
  })
  const errors = form.formState.errors
  const closeError = errors.expectedCloseOn?.message

  const submit = form.handleSubmit(async (values) => {
    setFormError(null)
    const body = {
      name: values.name,
      accountId: values.accountId,
      contactId: values.contactId || null,
      stageId: opportunity ? null : values.stageId || openStages[0]?.id || null,
      amount: values.amount === '' ? null : Number(values.amount),
      currency: values.currency === '' ? null : values.currency.toUpperCase(),
      expectedCloseOn: values.expectedCloseOn || null,
      ownerId: values.ownerId || null,
      description: values.description.trim() || null,
      ...(opportunity ? { version: opportunity.version } : {}),
    }
    try {
      const saved = opportunity
        ? await api.put<OpportunityView>(`/opportunities/${opportunity.id}`, body)
        : await api.post<OpportunityView>('/opportunities', body)
      queryClient.setQueryData(['opportunity', saved.id], saved)
      await queryClient.invalidateQueries({ queryKey: ['crm-board'] })
      await queryClient.invalidateQueries({ queryKey: ['opportunities'] })
      toast.success(opportunity ? 'Changes saved.' : `${saved.name} created.`)
      onSaved(saved)
    } catch (error) {
      if (!applyFieldErrors(error, form.setError, FIELDS)) setFormError(problemMessage(error))
    }
  })

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
    >
      <DialogContent className="max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{opportunity ? `Edit ${opportunity.name}` : 'New opportunity'}</DialogTitle>
          <DialogDescription>A deal with a person or organization in your directory.</DialogDescription>
        </DialogHeader>
        <form noValidate onSubmit={submit} className="space-y-4">
          <TextField form={form} name="name" label="Name" maxLength={200} />
          <PartyPicker
            id="field-accountId"
            label="Account"
            value={form.watch('accountId')}
            current={opportunity?.account ?? account}
            error={errors.accountId?.message}
            noneLabel="Choose…"
            onChange={(id) => form.setValue('accountId', id, { shouldValidate: form.formState.isSubmitted })}
          />
          <PartyPicker
            id="field-contactId"
            label="Contact"
            kind="PERSON"
            value={form.watch('contactId')}
            current={opportunity?.contact}
            error={errors.contactId?.message}
            onChange={(id) => form.setValue('contactId', id)}
          />
          {!opportunity && (
            <Field id="field-stageId" label="Stage" error={errors.stageId?.message}>
              <NativeSelect id="field-stageId" {...form.register('stageId')}>
                {openStages.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.name}
                  </option>
                ))}
              </NativeSelect>
            </Field>
          )}
          <div className="grid gap-3 sm:grid-cols-3">
            <TextField form={form} name="amount" label="Amount" />
            <TextField form={form} name="currency" label="Currency" maxLength={3} hint="Defaults to the workspace currency" />
            <Field id="field-expectedCloseOn" label="Expected close" error={closeError}>
              <Input
                id="field-expectedCloseOn"
                type="date"
                aria-invalid={closeError ? true : undefined}
                aria-describedby={describedBy('field-expectedCloseOn', closeError)}
                {...form.register('expectedCloseOn')}
              />
            </Field>
          </div>
          <OwnerSelect
            value={form.watch('ownerId')}
            onChange={(id) => form.setValue('ownerId', id)}
            current={opportunity?.owner}
            error={errors.ownerId?.message}
          />
          <TextAreaField form={form} name="description" label="Description" maxLength={5000} />
          <FormError message={formError} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={form.formState.isSubmitting}>
              {opportunity ? 'Save changes' : 'Create opportunity'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
```

The test selects the account in the `Account` select (label of `PartyPicker`'s select) and expects `stageId: 's-prospecting'` — the stage select's initial value is `''`, and the body falls back to the first open stage.

- [ ] **Step 6: Pipeline page**

`features/crm/PipelinePage.tsx`:

```tsx
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { NativeSelect } from '@/components/form/NativeSelect'
import { ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useApi } from '@/lib/api/ApiContext'
import type { BoardView } from '@/lib/api/types'
import { formatDate, formatMoney } from '@/lib/format'
import { toQuery } from '@/lib/query'
import { formatTotals } from './money'
import { MoveStageControl } from './MoveStageControl'
import { OpportunityFormDialog } from './OpportunityFormDialog'

/** The pipeline board (D16): a column per stage; Won and Lost show the last 30 days. Cards move with a menu. */
export function PipelinePage() {
  const api = useApi()
  const can = useCan()
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const owner = params.get('owner') ?? ''
  const [creating, setCreating] = useState(false)
  const canManage = can(PERMISSIONS.opportunityManage)
  const board = useQuery({
    queryKey: ['crm-board', owner],
    queryFn: () => api.get<BoardView>(`/crm/pipeline/board?${toQuery({ owner })}`),
    refetchOnMount: 'always',
  })
  const stages = board.data?.columns.map((c) => c.stage) ?? []

  return (
    <>
      <PageHeader
        title="Pipeline"
        description="Open deals by stage. Totals are per currency."
        actions={canManage && <Button onClick={() => setCreating(true)}>New opportunity</Button>}
      />
      <div className="mb-4 space-y-1.5">
        <Label htmlFor="board-owner">Show</Label>
        <NativeSelect
          id="board-owner"
          value={owner}
          onChange={(e) => setParams(e.target.value ? { owner: e.target.value } : {}, { replace: true })}
        >
          <option value="">Everyone's deals</option>
          <option value="me">My deals</option>
        </NativeSelect>
      </div>
      {board.isPending ? (
        <ListSkeleton />
      ) : board.isError ? (
        <ErrorState error={board.error} onRetry={() => void board.refetch()} />
      ) : (
        <div className="flex gap-3 overflow-x-auto pb-4">
          {board.data.columns.map((column) => (
            <section
              key={column.stage.id}
              aria-label={column.stage.name}
              className="w-72 shrink-0 space-y-2 rounded-lg border bg-muted/20 p-3"
            >
              <header className="space-y-0.5">
                <h2 className="font-semibold">{column.stage.name}</h2>
                <p className="text-xs text-muted-foreground">
                  {column.count === 1 ? '1 deal' : `${column.count} deals`}
                  {column.stage.kind === 'OPEN' ? ` · ${column.stage.probability}%` : ''}
                </p>
                <p className="text-sm">{formatTotals(column.totals)}</p>
                {column.stage.kind === 'OPEN' && column.weighted.length > 0 && (
                  <p className="text-xs text-muted-foreground">Weighted {formatTotals(column.weighted)}</p>
                )}
                {column.stage.kind !== 'OPEN' && (
                  <p className="text-xs text-muted-foreground">Closed in the last 30 days</p>
                )}
              </header>
              <ul className="space-y-2">
                {column.opportunities.map((o) => (
                  <li key={o.id} className="space-y-1 rounded-md border bg-background p-2 text-sm">
                    <Link to={`/app/crm/opportunities/${o.id}`} className="font-medium underline-offset-4 hover:underline">
                      {o.name}
                    </Link>
                    <p className="text-xs text-muted-foreground">{o.account?.name ?? 'Restricted record'}</p>
                    <p className="text-xs">
                      {o.amount != null && o.currency ? formatMoney(o.amount, o.currency) : 'No amount'}
                      {o.expectedCloseOn ? ` · closes ${formatDate(o.expectedCloseOn)}` : ''}
                    </p>
                    {canManage && <MoveStageControl opportunity={o} stages={stages} />}
                  </li>
                ))}
              </ul>
              {column.count > column.opportunities.length && (
                <p className="text-xs text-muted-foreground">
                  Showing {column.opportunities.length} of {column.count}.
                </p>
              )}
            </section>
          ))}
        </div>
      )}
      {creating && (
        <OpportunityFormDialog
          onClose={() => setCreating(false)}
          onSaved={(saved) => {
            setCreating(false)
            navigate(`/app/crm/opportunities/${saved.id}`)
          }}
        />
      )}
    </>
  )
}
```


- [ ] **Step 7: Opportunity detail page**

`features/crm/OpportunityDetailPage.tsx`:

```tsx
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { ActivityPanel } from '@/features/records/ActivityPanel'
import { DocumentsPanel } from '@/features/records/DocumentsPanel'
import { SubjectTasksPanel } from '@/features/records/SubjectTasksPanel'
import { useApi } from '@/lib/api/ApiContext'
import type { OpportunityView, StageView } from '@/lib/api/types'
import { formatDate, formatDateTime, formatMoney } from '@/lib/format'
import { OPPORTUNITY_STATUS_LABELS } from './labels'
import { MoveStageControl } from './MoveStageControl'
import { OpportunityFormDialog } from './OpportunityFormDialog'

export function OpportunityDetailPage() {
  const { opportunityId = '' } = useParams()
  const api = useApi()
  const can = useCan()
  const queryClient = useQueryClient()
  const [editing, setEditing] = useState(false)
  const opportunity = useQuery({
    queryKey: ['opportunity', opportunityId],
    queryFn: () => api.get<OpportunityView>(`/opportunities/${opportunityId}`),
  })
  const canManage = can(PERMISSIONS.opportunityManage)
  const stages = useQuery({
    queryKey: ['crm-stages'],
    queryFn: () => api.get<StageView[]>('/crm/pipeline/stages'),
    enabled: canManage,
  })
  const back = (
    <Link to="/app/crm/pipeline" className="text-sm underline-offset-4 hover:underline">
      ← Pipeline
    </Link>
  )
  if (opportunity.isPending)
    return (
      <>
        {back}
        <ListSkeleton />
      </>
    )
  if (opportunity.isError)
    return (
      <>
        {back}
        <ErrorState error={opportunity.error} onRetry={() => void opportunity.refetch()} />
      </>
    )
  const o = opportunity.data
  const accountPath = (id: string) => (can(PERMISSIONS.customerRead) ? `/app/crm/customers/${id}` : `/app/directory/${id}`)
  function stored(updated: OpportunityView) {
    queryClient.setQueryData(['opportunity', updated.id], updated)
  }

  return (
    <div className="space-y-6">
      {back}
      <PageHeader
        title={o.name}
        description={`${o.stage.name} · ${o.stage.probability}%`}
        actions={
          <>
            <Badge variant={o.status === 'WON' ? 'default' : o.status === 'LOST' ? 'outline' : 'secondary'}>
              {OPPORTUNITY_STATUS_LABELS[o.status]}
            </Badge>
            {canManage && stages.data && <MoveStageControl opportunity={o} stages={stages.data} onMoved={stored} />}
            {canManage && (
              <Button variant="outline" size="sm" onClick={() => setEditing(true)}>
                Edit
              </Button>
            )}
          </>
        }
      />
      <Card>
        <CardHeader>
          <CardTitle>Details</CardTitle>
        </CardHeader>
        <CardContent className="space-y-2 text-sm">
          {o.description && <p className="whitespace-pre-wrap">{o.description}</p>}
          <dl className="grid grid-cols-[9rem_1fr] gap-x-3 gap-y-2">
            <dt className="text-muted-foreground">Account</dt>
            <dd>
              {o.account ? (
                <Link to={accountPath(o.account.id)} className="underline-offset-4 hover:underline">
                  {o.account.name}
                </Link>
              ) : (
                '—'
              )}
            </dd>
            <dt className="text-muted-foreground">Contact</dt>
            <dd>
              {o.contact ? (
                <Link to={`/app/directory/${o.contact.id}`} className="underline-offset-4 hover:underline">
                  {o.contact.name}
                </Link>
              ) : (
                '—'
              )}
            </dd>
            <dt className="text-muted-foreground">Amount</dt>
            <dd>{o.amount != null && o.currency ? formatMoney(o.amount, o.currency) : '—'}</dd>
            <dt className="text-muted-foreground">Expected close</dt>
            <dd>{formatDate(o.expectedCloseOn)}</dd>
            <dt className="text-muted-foreground">Owner</dt>
            <dd>{o.owner?.name ?? 'Unassigned'}</dd>
            {o.closedAt && (
              <>
                <dt className="text-muted-foreground">Closed</dt>
                <dd>{formatDateTime(o.closedAt)}</dd>
              </>
            )}
            {o.lostReason && (
              <>
                <dt className="text-muted-foreground">Lost because</dt>
                <dd>{o.lostReason}</dd>
              </>
            )}
            {o.leadId && (
              <>
                <dt className="text-muted-foreground">Origin</dt>
                <dd>
                  <Link to={`/app/crm/leads/${o.leadId}`} className="underline-offset-4 hover:underline">
                    Source lead
                  </Link>
                </dd>
              </>
            )}
          </dl>
        </CardContent>
      </Card>
      <SubjectTasksPanel subjectType="OPPORTUNITY" subjectId={o.id} label={o.name} archived={false} />
      <ActivityPanel subjectType="OPPORTUNITY" subjectId={o.id} archived={false} />
      <DocumentsPanel subjectType="OPPORTUNITY" subjectId={o.id} archived={false} />
      {editing && (
        <OpportunityFormDialog
          opportunity={o}
          onClose={() => setEditing(false)}
          onSaved={(saved) => {
            stored(saved)
            setEditing(false)
          }}
        />
      )}
    </div>
  )
}
```

Wire both pages in `features/crm/routes.tsx` (`pipeline` → `<PipelinePage />`, `opportunities/:opportunityId` → `<OpportunityDetailPage />`).

- [ ] **Step 8: Run tests, lint, typecheck, commit**

Run: `cd frontend && npx vitest run src/features/crm && npm run lint && npm run typecheck`
Expected: PASS.

```bash
git add frontend/src/features/crm
git commit -m "feat(frontend): pipeline board with stage moves, opportunity dialog and detail page"
```

---

### Task 13: Customers, Customer 360, the related timeline and the CRM dashboard

**Files:**
- Create: `frontend/src/features/crm/CustomersPage.tsx`, `Customer360Page.tsx`, `CrmDashboardPage.tsx`, `CustomersPage.test.tsx`, `Customer360Page.test.tsx`, `CrmDashboardPage.test.tsx`
- Modify: `frontend/src/features/records/ActivityPanel.tsx` (+ `includeRelated`), `ActivityPanel.test.tsx` (+ 1 test), `features/crm/routes.tsx` (index, `customers`, `customers/:partyId`)

**Interfaces:**
- Consumes: fixtures `aCustomerRow`, `aCustomerSummary`, `aDashboard`, `anActivity`, `anOpportunity`; `formatTotals`; `SubjectLink`.
- Produces: `<ActivityPanel … includeRelated?: boolean />`.

- [ ] **Step 1: Write the failing tests**

`features/crm/CustomersPage.test.tsx`:

```tsx
import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ALL_TENANT_PERMISSIONS } from '@/features/auth/permissions'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aCustomerRow, pageOf } from '@/test/records'
import { renderApp } from '@/test/renderApp'

describe('CustomersPage', () => {
  it('lists customers with their open and won values per currency', async () => {
    const server = fakeServer()
    signedIn(server, testProfile({ modules: ['CRM'], permissions: [...ALL_TENANT_PERMISSIONS] })).on(
      'GET /crm/customers',
      { body: pageOf([aCustomerRow()]) },
    )
    const { user } = renderApp({ server, path: '/app/crm/customers' })
    const link = await screen.findByRole('link', { name: 'Acme' })
    expect(link).toHaveAttribute('href', '/app/crm/customers/p-acme')
    const row = link.closest('tr') as HTMLElement
    expect(within(row).getByText(/€50\.00 · \$300\.00/)).toBeInTheDocument()
    await user.type(screen.getByLabelText('Search customers'), 'acme')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    expect(server.callsTo('GET /crm/customers').at(-1)?.query.get('q')).toBe('acme')
  })
})
```

`features/crm/Customer360Page.test.tsx`:

```tsx
import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ALL_TENANT_PERMISSIONS } from '@/features/auth/permissions'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aCustomerSummary, anActivity, anOpportunity, aSummary, pageOf } from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup() {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['CRM'], permissions: [...ALL_TENANT_PERMISSIONS] }))
    .on('GET /crm/customers/:id', { body: aCustomerSummary() })
    .on('GET /parties', { body: pageOf([aSummary({ id: 'p-grace', kind: 'PERSON', name: 'Grace Hopper' })]) })
    .on('GET /opportunities', { body: pageOf([anOpportunity()]) })
    .on('GET /leads', { body: pageOf([]) })
    .on('GET /activities', {
      body: pageOf([
        anActivity({ summary: 'Pricing call', subjectType: 'OPPORTUNITY', subjectId: 'o-renewal', subject: { type: 'OPPORTUNITY', id: 'o-renewal', label: 'Packaging renewal', archived: false } }),
        anActivity({ id: 'a-2', summary: 'Kick-off', subject: { type: 'PARTY', id: 'p-acme', label: 'Acme', archived: false } }),
      ]),
    })
    .on('GET /documents', { body: [] })
    .on('GET /tasks', { body: pageOf([]) })
  return renderApp({ server, path: '/app/crm/customers/p-acme' })
}

describe('Customer360Page', () => {
  it('joins the customer, its people, deals and the whole timeline', async () => {
    const { server } = setup()
    expect(await screen.findByRole('heading', { name: 'Acme' })).toBeInTheDocument()
    expect(screen.getByText('Open deals').closest('div')).toHaveTextContent('1')
    expect(screen.getByRole('link', { name: 'Grace Hopper' })).toHaveAttribute('href', '/app/directory/p-grace')
    const deals = screen.getByRole('region', { name: 'Opportunities' })
    expect(within(deals).getByRole('link', { name: 'Packaging renewal' })).toBeInTheDocument()
    const activity = screen.getByRole('region', { name: 'Activity' })
    expect(within(activity).getByText('Pricing call')).toBeInTheDocument()
    expect(within(activity).getByRole('link', { name: 'Packaging renewal' })).toHaveAttribute(
      'href',
      '/app/crm/opportunities/o-renewal',
    )
    expect(server.callsTo('GET /activities')[0].query.get('includeRelated')).toBe('true')
    expect(server.callsTo('GET /opportunities')[0].query.get('accountId')).toBe('p-acme')
    expect(server.callsTo('GET /parties')[0].query.get('organizationId')).toBe('p-acme')
  })
})
```

`features/crm/CrmDashboardPage.test.tsx`:

```tsx
import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ALL_TENANT_PERMISSIONS } from '@/features/auth/permissions'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aDashboard } from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup(body = aDashboard()) {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['CRM'], permissions: [...ALL_TENANT_PERMISSIONS] })).on(
    'GET /crm/dashboard',
    { body },
  )
  return renderApp({ server, path: '/app/crm' })
}

describe('CrmDashboardPage', () => {
  it('shows lead and pipeline figures', async () => {
    setup()
    const leads = await screen.findByRole('region', { name: 'Leads' })
    expect(within(leads).getByText('New').nextElementSibling).toHaveTextContent('3')
    expect(within(leads).getByText('25%')).toBeInTheDocument()
    const pipeline = screen.getByRole('region', { name: 'Pipeline' })
    expect(within(pipeline).getByRole('row', { name: /Prospecting/ })).toHaveTextContent('$1,200.00')
    expect(screen.getByRole('region', { name: 'Won this month' })).toHaveTextContent('$300.00')
    expect(screen.getByRole('link', { name: 'Packaging renewal' })).toHaveAttribute('href', '/app/crm/opportunities/o-renewal')
  })

  it('filters to my deals and hides sections the user cannot read', async () => {
    const { server, user } = setup(aDashboard({ pipeline: null }))
    await screen.findByRole('region', { name: 'Leads' })
    expect(screen.queryByRole('region', { name: 'Pipeline' })).not.toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('Show'), 'me')
    expect(server.callsTo('GET /crm/dashboard').at(-1)?.query.get('owner')).toBe('me')
  })
})
```

Add to `features/records/ActivityPanel.test.tsx` (its `setup` renders `/app/directory/p-acme`, where the panel is on the party itself; the reply includes an activity on a related opportunity, as `includeRelated` replies do):

```tsx
  it('labels an activity that is on another record', async () => {
    const { server } = setup()
    server.on('GET /activities', {
      body: pageOf([
        anActivity({
          id: 'a-9',
          summary: 'Pricing call',
          subjectType: 'OPPORTUNITY',
          subjectId: 'o-renewal',
          subject: { type: 'OPPORTUNITY', id: 'o-renewal', label: 'Packaging renewal', archived: false },
        }),
      ]),
    })
    const panel = within(await screen.findByRole('region', { name: 'Activity' }))
    expect(await panel.findByRole('link', { name: 'Packaging renewal' })).toHaveAttribute(
      'href',
      '/app/crm/opportunities/o-renewal',
    )
    expect(panel.getByText(/^On/)).toBeInTheDocument()
  })
```

The override is registered after `renderApp`, but the panel's first fetch happens after the profile loads, so the later registration wins (`fakeServer` checks later routes first). If the first fetch races it, register the override inside a copy of `setup` before `renderApp`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd frontend && npx vitest run src/features/crm src/features/records`
Expected: FAIL.

- [ ] **Step 3: Related timeline in `ActivityPanel`**

In `features/records/ActivityPanel.tsx`:
- add prop `includeRelated = false` to the props type and destructuring;
- query key `['activities', subjectType, subjectId, includeRelated, page]` (keep the first three elements so existing invalidations still match);
- query string `toQuery({ subjectType, subjectId, includeRelated: includeRelated ? 'true' : '', page, size: 20 })`;
- under each item's summary line, when the activity is on another record, show it:

```tsx
                {activity.subject &&
                  (activity.subject.type !== subjectType || activity.subject.id !== subjectId) && (
                    <p className="mt-1 text-xs text-muted-foreground">
                      On <SubjectLink subject={activity.subject} />
                    </p>
                  )}
```

(import `SubjectLink` from `./SubjectLink`).

- [ ] **Step 4: Customers and Customer 360**

`features/crm/CustomersPage.tsx`:

```tsx
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import type { FormEvent } from 'react'
import { Link, useSearchParams } from 'react-router'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Pagination } from '@/components/Pagination'
import { EmptyState, ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { useApi } from '@/lib/api/ApiContext'
import type { CustomerRow, Page } from '@/lib/api/types'
import { toQuery } from '@/lib/query'
import { formatTotals } from './money'

export function CustomersPage() {
  const api = useApi()
  const [params, setParams] = useSearchParams()
  const q = params.get('q') ?? ''
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0)
  const customers = useQuery({
    queryKey: ['crm-customers', { q, page }],
    queryFn: () => api.get<Page<CustomerRow>>(`/crm/customers?${toQuery({ q, page, size: 20 })}`),
    placeholderData: keepPreviousData,
  })

  function onSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const value = new FormData(event.currentTarget).get('q')
    const text = typeof value === 'string' ? value.trim() : ''
    setParams(text ? { q: text } : {}, { replace: true })
  }

  return (
    <>
      <PageHeader title="Customers" description="People and organizations with an active customer role." />
      <form onSubmit={onSearch} className="mb-4 flex items-end gap-2" role="search">
        <div className="space-y-1.5">
          <Label htmlFor="customers-q">Search customers</Label>
          <Input id="customers-q" name="q" defaultValue={q} />
        </div>
        <Button type="submit" variant="outline">
          Search
        </Button>
      </form>
      {customers.isPending ? (
        <ListSkeleton />
      ) : customers.isError ? (
        <ErrorState error={customers.error} onRetry={() => void customers.refetch()} />
      ) : customers.data.items.length === 0 ? (
        <EmptyState
          title={q ? 'No customers match.' : 'No customers yet.'}
          description="Converting a lead or winning a deal makes the account a customer."
        />
      ) : (
        <>
          <div className="overflow-x-auto rounded-lg border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Name</TableHead>
                  <TableHead>Open deals</TableHead>
                  <TableHead>Open value</TableHead>
                  <TableHead>Won</TableHead>
                  <TableHead>Won value</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {customers.data.items.map((row) => (
                  <TableRow key={row.party.id}>
                    <TableCell>
                      <Link to={`/app/crm/customers/${row.party.id}`} className="font-medium underline-offset-4 hover:underline">
                        {row.party.name}
                      </Link>
                    </TableCell>
                    <TableCell>{row.openCount}</TableCell>
                    <TableCell>{formatTotals(row.openValue)}</TableCell>
                    <TableCell>{row.wonCount}</TableCell>
                    <TableCell>{formatTotals(row.wonValue)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <Pagination
            page={customers.data.page}
            size={customers.data.size}
            total={customers.data.total}
            onPage={(p) => setParams({ ...(q ? { q } : {}), page: String(p) }, { replace: true })}
          />
        </>
      )}
    </>
  )
}
```

`features/crm/Customer360Page.tsx`:

```tsx
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { ActivityPanel } from '@/features/records/ActivityPanel'
import { DocumentsPanel } from '@/features/records/DocumentsPanel'
import { SubjectTasksPanel } from '@/features/records/SubjectTasksPanel'
import { useApi } from '@/lib/api/ApiContext'
import type { CustomerSummary, LeadView, OpportunityView, Page, PartySummary } from '@/lib/api/types'
import { formatDate, formatMoney } from '@/lib/format'
import { toQuery } from '@/lib/query'
import { LEAD_STATUS_LABELS, OPPORTUNITY_STATUS_LABELS } from './labels'
import { formatTotals } from './money'
import { OpportunityFormDialog } from './OpportunityFormDialog'

/** D8: one page joining identity, deals, converted leads and the whole timeline (including deals' and leads'). */
export function Customer360Page() {
  const { partyId = '' } = useParams()
  const api = useApi()
  const can = useCan()
  const [creating, setCreating] = useState(false)
  const summary = useQuery({
    queryKey: ['crm-customer', partyId],
    queryFn: () => api.get<CustomerSummary>(`/crm/customers/${partyId}`),
  })
  const isOrganization = summary.data?.party.kind === 'ORGANIZATION'
  const contacts = useQuery({
    queryKey: ['parties', { organizationId: partyId }],
    queryFn: () => api.get<Page<PartySummary>>(`/parties?${toQuery({ organizationId: partyId, size: 50 })}`),
    enabled: isOrganization,
  })
  const deals = useQuery({
    queryKey: ['opportunities', { accountId: partyId }],
    queryFn: () => api.get<Page<OpportunityView>>(`/opportunities?${toQuery({ accountId: partyId, size: 50 })}`),
    enabled: can(PERMISSIONS.opportunityRead),
  })
  const leads = useQuery({
    queryKey: ['leads', { partyId }],
    queryFn: () => api.get<Page<LeadView>>(`/leads?${toQuery({ partyId, status: 'CONVERTED', size: 20 })}`),
    enabled: can(PERMISSIONS.leadRead),
  })
  const back = (
    <Link to="/app/crm/customers" className="text-sm underline-offset-4 hover:underline">
      ← Customers
    </Link>
  )
  if (summary.isPending)
    return (
      <>
        {back}
        <ListSkeleton />
      </>
    )
  if (summary.isError)
    return (
      <>
        {back}
        <ErrorState error={summary.error} onRetry={() => void summary.refetch()} />
      </>
    )
  const s = summary.data
  const party = s.party
  const archived = party.archivedAt !== null
  const stats: Array<[string, string, string]> = [
    ['Open deals', String(s.openCount), formatTotals(s.openValue)],
    ['Weighted pipeline', '', formatTotals(s.weightedValue)],
    ['Won', String(s.wonCount), formatTotals(s.wonValue)],
    ['Lost', String(s.lostCount), ''],
    ['Leads converted', String(s.leadCount), ''],
  ]

  return (
    <div className="space-y-6">
      {back}
      <PageHeader
        title={party.name}
        description={[party.kind === 'ORGANIZATION' ? 'Organization' : 'Person', party.email ?? party.domain]
          .filter(Boolean)
          .join(' · ')}
        actions={
          <>
            <Link to={`/app/directory/${party.id}`} className="text-sm underline">
              Open in directory
            </Link>
            {can(PERMISSIONS.opportunityManage) && !archived && (
              <Button size="sm" onClick={() => setCreating(true)}>
                New opportunity
              </Button>
            )}
          </>
        }
      />
      <div className="grid gap-3 sm:grid-cols-5">
        {stats.map(([label, count, money]) => (
          <div key={label} className="rounded-lg border p-3">
            <p className="text-xs text-muted-foreground">{label}</p>
            {count && <p className="text-2xl font-semibold">{count}</p>}
            {money && <p className="text-sm">{money}</p>}
          </div>
        ))}
      </div>
      {isOrganization && (
        <Card>
          <CardHeader>
            <CardTitle>People</CardTitle>
          </CardHeader>
          <CardContent className="text-sm">
            {contacts.data?.items.length ? (
              <ul className="space-y-1">
                {contacts.data.items.map((p) => (
                  <li key={p.id}>
                    <Link to={`/app/directory/${p.id}`} className="underline-offset-4 hover:underline">
                      {p.name}
                    </Link>
                    {p.email && <span className="text-muted-foreground"> · {p.email}</span>}
                  </li>
                ))}
              </ul>
            ) : (
              <p className="text-muted-foreground">No people linked yet.</p>
            )}
          </CardContent>
        </Card>
      )}
      {can(PERMISSIONS.opportunityRead) && (
        <section aria-label="Opportunities" className="space-y-2">
          <h2 className="text-lg font-semibold">Opportunities</h2>
          {deals.data?.items.length ? (
            <div className="overflow-x-auto rounded-lg border">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>Name</TableHead>
                    <TableHead>Stage</TableHead>
                    <TableHead>Status</TableHead>
                    <TableHead>Amount</TableHead>
                    <TableHead>Expected close</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {deals.data.items.map((o) => (
                    <TableRow key={o.id}>
                      <TableCell>
                        <Link to={`/app/crm/opportunities/${o.id}`} className="underline-offset-4 hover:underline">
                          {o.name}
                        </Link>
                      </TableCell>
                      <TableCell>{o.stage.name}</TableCell>
                      <TableCell>
                        <Badge variant="outline">{OPPORTUNITY_STATUS_LABELS[o.status]}</Badge>
                      </TableCell>
                      <TableCell>{o.amount != null && o.currency ? formatMoney(o.amount, o.currency) : '—'}</TableCell>
                      <TableCell>{formatDate(o.expectedCloseOn)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          ) : (
            <p className="text-sm text-muted-foreground">No opportunities yet.</p>
          )}
        </section>
      )}
      {can(PERMISSIONS.leadRead) && leads.data && leads.data.items.length > 0 && (
        <section aria-label="Converted leads" className="space-y-2">
          <h2 className="text-lg font-semibold">Converted leads</h2>
          <ul className="space-y-1 text-sm">
            {leads.data.items.map((l) => (
              <li key={l.id}>
                <Link to={`/app/crm/leads/${l.id}`} className="underline-offset-4 hover:underline">
                  {l.name}
                </Link>{' '}
                <span className="text-muted-foreground">{LEAD_STATUS_LABELS[l.status]}</span>
              </li>
            ))}
          </ul>
        </section>
      )}
      <SubjectTasksPanel subjectType="PARTY" subjectId={party.id} label={party.name} archived={archived} />
      <ActivityPanel subjectType="PARTY" subjectId={party.id} archived={archived} includeRelated />
      <DocumentsPanel subjectType="PARTY" subjectId={party.id} archived={archived} />
      {creating && (
        <OpportunityFormDialog
          account={{ id: party.id, name: party.name }}
          onClose={() => setCreating(false)}
          onSaved={() => setCreating(false)}
        />
      )}
    </div>
  )
}
```

(In `Customer360Page.test.tsx` the stat card is found via `getByText('Open deals').closest('div')`.)

- [ ] **Step 5: Dashboard**

`features/crm/CrmDashboardPage.tsx`:

```tsx
import { useQuery } from '@tanstack/react-query'
import { Fragment } from 'react'
import { Link, useSearchParams } from 'react-router'
import { Label } from '@/components/ui/label'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { NativeSelect } from '@/components/form/NativeSelect'
import { ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { useApi } from '@/lib/api/ApiContext'
import type { DashboardView } from '@/lib/api/types'
import { formatDate, formatMoney } from '@/lib/format'
import { toQuery } from '@/lib/query'
import { LEAD_STATUS_LABELS, OPEN_LEAD_STATUSES } from './labels'
import { formatTotals } from './money'

export function CrmDashboardPage() {
  const api = useApi()
  const [params, setParams] = useSearchParams()
  const owner = params.get('owner') ?? ''
  const dashboard = useQuery({
    queryKey: ['crm-dashboard', owner],
    queryFn: () => api.get<DashboardView>(`/crm/dashboard?${toQuery({ owner })}`),
    refetchOnMount: 'always',
  })

  return (
    <>
      <PageHeader title="Sales dashboard" description="Where leads and deals stand. Months follow the workspace time zone." />
      <div className="mb-4 space-y-1.5">
        <Label htmlFor="dashboard-owner">Show</Label>
        <NativeSelect
          id="dashboard-owner"
          value={owner}
          onChange={(e) => setParams(e.target.value ? { owner: e.target.value } : {}, { replace: true })}
        >
          <option value="">Everyone</option>
          <option value="me">Only mine</option>
        </NativeSelect>
      </div>
      {dashboard.isPending ? (
        <ListSkeleton />
      ) : dashboard.isError ? (
        <ErrorState error={dashboard.error} onRetry={() => void dashboard.refetch()} />
      ) : (
        <div className="grid gap-4 lg:grid-cols-2">
          {dashboard.data.leads && (
            <section aria-label="Leads" className="space-y-3 rounded-lg border p-4">
              <h2 className="font-semibold">Leads</h2>
              <dl className="grid grid-cols-[1fr_auto] gap-y-1 text-sm">
                {OPEN_LEAD_STATUSES.map((status) => (
                  <Fragment key={status}>
                    <dt>{LEAD_STATUS_LABELS[status]}</dt>
                    <dd className="text-right font-medium">{dashboard.data.leads?.open[status] ?? 0}</dd>
                  </Fragment>
                ))}
                <dt>New in the last 30 days</dt>
                <dd className="text-right font-medium">{dashboard.data.leads.newLast30Days}</dd>
                <dt>Conversion rate (90 days)</dt>
                <dd className="text-right font-medium">
                  {dashboard.data.leads.conversionRate == null
                    ? '—'
                    : `${Math.round(dashboard.data.leads.conversionRate * 100)}%`}
                </dd>
              </dl>
              <Link to="/app/crm/leads" className="text-sm underline">
                Open leads
              </Link>
            </section>
          )}
          {dashboard.data.pipeline && (
            <>
              <section aria-label="Pipeline" className="space-y-3 rounded-lg border p-4">
                <h2 className="font-semibold">Pipeline</h2>
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>Stage</TableHead>
                      <TableHead>Deals</TableHead>
                      <TableHead>Value</TableHead>
                      <TableHead>Weighted</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {dashboard.data.pipeline.stages.map((s) => (
                      <TableRow key={s.stage.id}>
                        <TableCell>{s.stage.name}</TableCell>
                        <TableCell>{s.count}</TableCell>
                        <TableCell>{formatTotals(s.totals)}</TableCell>
                        <TableCell>{formatTotals(s.weighted)}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </section>
              <section aria-label="Won this month" className="rounded-lg border p-4 text-sm">
                <h2 className="font-semibold">Won this month</h2>
                <p className="text-2xl font-semibold">{dashboard.data.pipeline.wonThisMonth.count}</p>
                <p>{formatTotals(dashboard.data.pipeline.wonThisMonth.totals)}</p>
                <p className="mt-2 text-muted-foreground">Lost this month: {dashboard.data.pipeline.lostThisMonth.count}</p>
              </section>
              <section aria-label="Closing soon" className="space-y-2 rounded-lg border p-4 text-sm">
                <h2 className="font-semibold">Closing in the next 30 days</h2>
                {dashboard.data.pipeline.closingSoon.length === 0 ? (
                  <p className="text-muted-foreground">Nothing due.</p>
                ) : (
                  <ul className="space-y-1">
                    {dashboard.data.pipeline.closingSoon.map((o) => (
                      <li key={o.id}>
                        <Link to={`/app/crm/opportunities/${o.id}`} className="underline-offset-4 hover:underline">
                          {o.name}
                        </Link>{' '}
                        <span className="text-muted-foreground">
                          {[o.account?.name, formatDate(o.expectedCloseOn),
                            o.amount != null && o.currency ? formatMoney(o.amount, o.currency) : null]
                            .filter(Boolean)
                            .join(' · ')}
                        </span>
                      </li>
                    ))}
                  </ul>
                )}
              </section>
            </>
          )}
        </div>
      )}
    </>
  )
}
```

Wire the pages in `features/crm/routes.tsx`: index → `<CrmDashboardPage />`, `customers` → `<CustomersPage />`, `customers/:partyId` → `<Customer360Page />`. Delete the now-unused `Pending` helper and the `EmptyState` import.

- [ ] **Step 6: Run tests, lint, typecheck, commit**

Run: `cd frontend && npm run lint && npm run typecheck && npm test`
Expected: PASS.

```bash
git add frontend/src
git commit -m "feat(frontend): customers, Customer 360 with the related timeline, and the sales dashboard"
```

---

### Task 14: Header search

**Files:**
- Create: `frontend/src/features/shell/GlobalSearch.tsx`, `GlobalSearch.test.tsx`
- Modify: `frontend/src/features/shell/AppLayout.tsx` (render `<GlobalSearch />` above `<Outlet />`)

**Interfaces:**
- Consumes: `GET /search?q=` → `SearchHit[]`; `subjectPath` from `@/features/records/SubjectLink`.
- Produces: `<GlobalSearch />` — input labelled "Search records"; `/` focuses it from anywhere outside a text field; results list labelled "Search results"; Escape clears.

- [ ] **Step 1: Write the failing test**

`features/shell/GlobalSearch.test.tsx`:

```tsx
import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'

function setup() {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['CRM'] })).on('GET /search', (req) => ({
    body:
      req.query.get('q') === 'konkan'
        ? [
            { type: 'PARTY', id: 'p-konkan', label: 'Konkan Logistics', detail: 'konkan.test', archived: false },
            { type: 'LEAD', id: 'l-konkan', label: 'Konkan Traders', detail: null, archived: false },
            { type: 'OPPORTUNITY', id: 'o-konkan', label: 'Konkan renewal', detail: 'Konkan Logistics', archived: false },
          ]
        : [],
  }))
  return renderApp({ server, path: '/app' })
}

describe('GlobalSearch', () => {
  it('searches as you type and links each result to its page', async () => {
    const { server, user } = setup()
    await user.type(await screen.findByLabelText('Search records'), 'konkan')
    const results = await screen.findByRole('list', { name: 'Search results' })
    expect(within(results).getByRole('link', { name: /Konkan Logistics/ })).toHaveAttribute('href', '/app/directory/p-konkan')
    expect(within(results).getByRole('link', { name: /Konkan Traders/ })).toHaveAttribute('href', '/app/crm/leads/l-konkan')
    expect(within(results).getByRole('link', { name: /Konkan renewal/ })).toHaveAttribute(
      'href',
      '/app/crm/opportunities/o-konkan',
    )
    expect(within(results).getByText('Lead')).toBeInTheDocument()
    expect(server.callsTo('GET /search').every((c) => (c.query.get('q') ?? '').length >= 2)).toBe(true)
  })

  it('says when nothing matches and clears on Escape', async () => {
    const { user } = setup()
    const input = await screen.findByLabelText('Search records')
    await user.type(input, 'zz')
    expect(await screen.findByText('No matches.')).toBeInTheDocument()
    await user.keyboard('{Escape}')
    expect(input).toHaveValue('')
    expect(screen.queryByText('No matches.')).not.toBeInTheDocument()
  })

  it('focuses with the slash key', async () => {
    const { user } = setup()
    const input = await screen.findByLabelText('Search records')
    await user.keyboard('/')
    expect(input).toHaveFocus()
    expect(input).toHaveValue('')
  })
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd frontend && npx vitest run src/features/shell/GlobalSearch.test.tsx`
Expected: FAIL.

- [ ] **Step 3: The component**

`features/shell/GlobalSearch.tsx`:

```tsx
import { useQuery } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router'
import { Input } from '@/components/ui/input'
import { subjectPath } from '@/features/records/SubjectLink'
import { useApi } from '@/lib/api/ApiContext'
import type { SearchHit } from '@/lib/api/types'
import { toQuery } from '@/lib/query'

const TYPE_LABELS: Record<string, string> = {
  PARTY: 'Directory',
  LEAD: 'Lead',
  OPPORTUNITY: 'Opportunity',
  PRODUCT: 'Product',
}

/** Workspace search (D12): 2+ characters, debounced; results are only the record types you may read. */
export function GlobalSearch() {
  const api = useApi()
  const input = useRef<HTMLInputElement>(null)
  const [text, setText] = useState('')
  const [query, setQuery] = useState('')

  useEffect(() => {
    const handle = setTimeout(() => setQuery(text.trim()), 250)
    return () => clearTimeout(handle)
  }, [text])

  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      const target = event.target as HTMLElement | null
      const typing =
        target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable)
      if (event.key === '/' && !typing) {
        event.preventDefault()
        input.current?.focus()
      }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [])

  const results = useQuery({
    queryKey: ['search', query],
    queryFn: () => api.get<SearchHit[]>(`/search?${toQuery({ q: query })}`),
    enabled: query.length >= 2,
  })

  function clear() {
    setText('')
    setQuery('')
  }

  return (
    <div className="relative mb-6 max-w-xl">
      <Input
        ref={input}
        type="search"
        aria-label="Search records"
        placeholder="Search people, leads, deals and products (press /)"
        autoComplete="off"
        value={text}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Escape') clear()
        }}
      />
      {query.length >= 2 && results.data && (
        <div className="absolute z-20 mt-1 w-full rounded-md border bg-background p-1 shadow-md">
          {results.data.length === 0 ? (
            <p className="px-2 py-1.5 text-sm text-muted-foreground">No matches.</p>
          ) : (
            <ul aria-label="Search results">
              {results.data.map((hit) => {
                const path = subjectPath(hit.type, hit.id)
                return (
                  <li key={`${hit.type}-${hit.id}`}>
                    {path && (
                      <Link
                        to={path}
                        onClick={clear}
                        className="flex items-baseline justify-between gap-3 rounded px-2 py-1.5 text-sm hover:bg-muted"
                      >
                        <span>
                          <span className="font-medium">{hit.label}</span>
                          {hit.detail && <span className="text-muted-foreground"> · {hit.detail}</span>}
                        </span>
                        <span className="text-xs text-muted-foreground">{TYPE_LABELS[hit.type] ?? hit.type}</span>
                      </Link>
                    )}
                  </li>
                )
              })}
            </ul>
          )}
        </div>
      )}
    </div>
  )
}
```

`Input` must forward refs (shadcn's React 19 `Input` accepts `ref` as a prop); if it doesn't, wrap the input in a `div` and query the element through `div.querySelector('input')` in the effect instead.

In `AppLayout.tsx`, inside `<main …>`, put `<GlobalSearch />` before `<Outlet />`.

- [ ] **Step 4: Run tests, lint, typecheck, commit**

Run: `cd frontend && npm run lint && npm run typecheck && npm test`
Expected: PASS. (Any existing test that counts `textbox`es or `list`s on `/app/*` pages may need scoping to `main`; fix the test's query, not the component.)

```bash
git add frontend/src/features/shell
git commit -m "feat(frontend): workspace search in the header"
```

---

### Task 15: End-to-end journey and README

**Files:**
- Create: `frontend/e2e/crm.spec.ts`
- Modify: `README.md`

**Interfaces:**
- Consumes: the whole feature; e2e helpers `newWorkspace`, `signUpAndVerify`, `signIn` from `./support/workspace`.

- [ ] **Step 1: Write the journey**

`frontend/e2e/crm.spec.ts`:

```ts
import { expect, test } from '@playwright/test'
import { newWorkspace, signIn, signUpAndVerify } from './support/workspace'

test('a lead becomes a customer without a duplicate, and the deal is won', async ({ page }) => {
  const ws = newWorkspace('crm')
  await signUpAndVerify(page, ws)
  await signIn(page, ws, ws.email)
  const nav = page.getByRole('navigation', { name: 'Workspace' })

  // Enable CRM.
  await page.goto('/app/settings/modules')
  await page.getByRole('switch', { name: 'CRM' }).check()
  await expect(page.getByText('CRM enabled.')).toBeVisible()

  // An organization that already exists under a slightly different name.
  await nav.getByRole('link', { name: 'Directory' }).click()
  await page.getByRole('button', { name: 'New organization' }).click()
  let dialog = page.getByRole('dialog')
  await dialog.getByLabel('Name').fill('Acme Robotics Ltd')
  await dialog.getByRole('button', { name: 'Create organization' }).click()
  await expect(page.getByRole('heading', { name: 'Acme Robotics Ltd' })).toBeVisible()

  // A lead, worked and qualified.
  await nav.getByRole('link', { name: 'CRM' }).click()
  await page.getByRole('navigation', { name: 'CRM' }).getByRole('link', { name: 'Leads' }).click()
  await page.getByRole('button', { name: 'New lead' }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByLabel('First name').fill('Grace')
  await dialog.getByLabel('Last name').fill('Hopper')
  await dialog.getByLabel('Company').fill('Acme Robotics')
  await dialog.getByLabel('Email').fill('grace@acme-robotics.test')
  await dialog.getByLabel('Estimated value').fill('5000')
  await dialog.getByRole('button', { name: 'Create lead' }).click()
  await expect(page.getByRole('heading', { name: 'Grace Hopper' })).toBeVisible()
  const activity = page.getByRole('region', { name: 'Activity' })
  await activity.getByLabel('Summary').fill('Intro call with Grace')
  await activity.getByRole('button', { name: 'Log activity' }).click()
  await expect(activity.getByText('Intro call with Grace')).toBeVisible()
  await page.getByRole('button', { name: 'Mark qualified' }).click()
  await expect(page.getByText('Qualified', { exact: true })).toBeVisible()

  // Converting proposes a new "Acme Robotics": refused as a probable duplicate, then linked.
  await page.getByRole('button', { name: 'Convert' }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByRole('button', { name: 'Convert lead' }).click()
  await expect(dialog.getByText('This looks like a record that already exists.')).toBeVisible()
  await dialog.getByRole('button', { name: 'Use Acme Robotics Ltd' }).click()
  await dialog.getByRole('button', { name: 'Convert lead' }).click()
  await expect(page.getByText(/This lead was converted/)).toBeVisible()

  // The opportunity moves to Won.
  await page.getByRole('link', { name: 'Open the opportunity' }).click()
  await expect(page.getByRole('heading', { name: 'Acme Robotics' })).toBeVisible()
  await page.getByLabel('Move Acme Robotics to').selectOption({ label: 'Proposal' })
  await expect(page.getByText('Moved to Proposal.')).toBeVisible()
  await page.getByLabel('Move Acme Robotics to').selectOption({ label: 'Won' })
  await expect(page.getByText('Moved to Won.')).toBeVisible()

  // The account is a customer with the won value; its 360 timeline includes the lead's note.
  await page.getByRole('navigation', { name: 'CRM' }).getByRole('link', { name: 'Customers' }).click()
  const row = page.getByRole('row', { name: /Acme Robotics Ltd/ })
  await expect(row).toContainText('$5,000.00')
  await row.getByRole('link', { name: 'Acme Robotics Ltd' }).click()
  await expect(page.getByRole('heading', { name: 'Acme Robotics Ltd' })).toBeVisible()
  await expect(page.getByRole('region', { name: 'Activity' }).getByText('Intro call with Grace')).toBeVisible()

  // Header search finds the lead.
  await page.getByLabel('Search records').fill('Grace')
  const results = page.getByRole('list', { name: 'Search results' })
  await expect(results.getByRole('link', { name: /Grace Hopper.*Lead/ })).toBeVisible()
})
```

If the workspace currency of a new signup isn't USD, change `$5,000.00` to the matching formatted value (check `tenants.currency`'s default in `V1__tenancy.sql`).

- [ ] **Step 2: Run the journey**

Run: `make up-all` (or the stack the existing E2E uses), then `cd frontend && npx playwright test e2e/crm.spec.ts`
Expected: PASS. Then the whole E2E suite: `npx playwright test` — Expected: PASS (5 tests).

- [ ] **Step 3: README**

In `README.md`:
- Under the design links add: `- CRM (Phase 5): \`docs/superpowers/specs/2026-10-07-crm-mvp-design.md\``.
- After the "Records (Phase 4)" section add:

```markdown
## CRM (Phase 5)
Switch the module on under **Settings → Modules**; every CRM permission switches off with it (ADR-0010).
- **Leads:** prospects as you heard of them, entered by hand or imported from a CSV file (up to 500 rows; all or
  nothing, with per-row errors). New → Contacted → Qualified, or Disqualified with a reason.
- **Conversion:** links or creates the canonical person and organization (the directory's duplicate check applies),
  makes the account a customer, and can open an opportunity.
- **Pipeline:** your own stages (Settings → Pipeline) with probabilities; a board with per-currency totals; Lost needs
  a reason; Won makes the account a customer.
- **Customers and Customer 360:** people, deals, converted leads and one timeline that includes the deals' and leads'
  activity. **Dashboard:** open leads, 90-day conversion rate, pipeline by stage, won and lost this month.
- **Search:** the box at the top of every page (press `/`) finds people, organizations, leads, deals and products you
  may see.
```

- [ ] **Step 4: Commit**

```bash
git add frontend/e2e/crm.spec.ts README.md
git commit -m "test(e2e): CRM journey from lead to won customer; README for Phase 5"
```

---

## Self-review notes (plan author)

- **Spec coverage:** D1 (Task 1 package, Task 8 ModularityTest), D2–D3 (Task 2), D4 (Task 4), D5 (Task 1), D6 (Task 3), D7 (Tasks 3–4 `ensureCustomer`), D8 (Task 6 + 13), D9 (Tasks 2–3 resolvers), D10 (Task 7), D11 (Task 5), D12 (Task 7 + 14), D13 (Task 1), D14 (Task 6 + 13), D15 (Task 1), D16 (Tasks 9–14). Security §6 (Task 8), testing §7 (every task + Task 15).
- **Review Focus lines** are pinned by: `aCompanyOnlyLeadIsNamedAfterTheCompany` (Task 2), `anArchivedAccountDoesNotBlockEditingOrMoving` (Task 3), `importsAnExcelStyleFile` (Task 5), `movingToAnUnknownStageIsAFieldError` (Task 3), `boardTotalsArePerCurrency` (Task 3) and `customerTotalsArePerCurrency` (Task 6).
