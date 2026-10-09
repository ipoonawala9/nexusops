# Phase 7 — HelpDesk MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Tickets that carry their context (requester, product, linked record, previous tickets, duplicates, suggested articles), a pausable SLA clock per priority, assignment with routing by category, a public/internal conversation with email notifications, a status workflow, a knowledge base and a dashboard — inside the HelpDesk module and isolated per tenant.

**Architecture:** A new Spring Modulith module `helpdesk` (`com.nexusops.helpdesk`) owns five tenant tables (Flyway V24–V27). SLA due times are stored on the ticket and recomputed by a pure `SlaClock` on every status or priority change, so "breached" and "at risk" are plain comparisons with `now()`. Tickets and articles are collaboration subjects, so tasks, documents and activity already work on them. Full-text search (PostgreSQL `tsvector` + GIN, `'simple'` config) supplies duplicate candidates and article suggestions. The React app gets a HelpDesk area, Settings → HelpDesk, and Tickets panels on party, Customer 360 and product pages.

**Tech Stack:** Java 25, Spring Boot 4.1 (Web MVC, Security, Data JPA, Modulith), Hibernate `@TenantId`, PostgreSQL 17 RLS and full-text search, Flyway, JUnit 5 + Testcontainers + MockMvc; React 19 + TypeScript + Vite, TanStack Query, React Hook Form + Zod 4, Tailwind/shadcn, Vitest + Testing Library, Playwright.

**Spec:** `docs/superpowers/specs/2026-10-09-helpdesk-mvp-design.md` (decisions D1–D18 are referenced below).

## Global Constraints

- Every new table: `tenant_id uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE`, `UNIQUE (tenant_id, id)`, `ENABLE` + `FORCE ROW LEVEL SECURITY`, policy `tenant_isolation` with `USING` and `WITH CHECK` `(tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)`, in the same migration as the table.
- References inside a tenant use composite FKs `(tenant_id, x_id) REFERENCES t (tenant_id, id)`; member references (`assignee_id`, `author_id`, `created_by`, `default_assignee_id`) are `uuid REFERENCES users (id) ON DELETE SET NULL`.
- Every HelpDesk permission has `module_code = 'HELPDESK'`: `helpdesk.ticket.read`, `helpdesk.ticket.manage`, `helpdesk.ticket.assign`, `helpdesk.ticket.resolve`, `helpdesk.settings.manage`, `helpdesk.article.read`, `helpdesk.article.manage`.
- Every handler has `@PreAuthorize`. Routes live under `/api/v1/helpdesk/…`.
- Error order: invalid body 400 → missing permission 403 → path id not found (incl. other tenant) 404 → state conflict 409 (stale version 409 may precede body 400 on edits, the codebase convention). Resolve every referenced id (400s) before any 403/409 state check, using resolve-then-check helpers like Inventory's. Body references to unknown or other-tenant ids are 400 field errors.
- Messages, verbatim: `"Record not found."`, `"This record is archived."`, `"This record was changed by someone else. Reload and try again."`, `"You do not have permission to perform this action."`, `"Reload the record and try again."`, `"A closed ticket can't be changed."`, `"Add a resolution note."`, `"Choose a person or organization in this workspace."`, `"Choose an active team member."`, `"Choose a category in this workspace."`.
- JDBC (non-JPA) queries always add an explicit `tenant_id = ?`/`:tenant` predicate on top of RLS. Full-text queries bind user text as parameters (never concatenated).
- Every state change of a ticket takes the ticket's optimistic lock (`@Version`) and flushes before side effects (emails are sent after commit by `MailRequested`).
- `ticket_messages` is append-only for the app role (no UPDATE/DELETE grant).
- Audit actions (verbatim): `TicketCategoryCreated`, `TicketCategoryUpdated`, `TicketCategoryArchived`, `TicketCategoryRestored`, `SlaPolicyUpdated`, `TicketCreated`, `TicketUpdated`, `TicketAssigned`, `TicketStatusChanged`, `TicketReplied`, `TicketNoteAdded`, `TicketCustomerMessage`, `ArticleCreated`, `ArticleUpdated`, `ArticlePublished`, `ArticleUnpublished`, `ArticleArchived`.
- Commits: conventional messages, **no `Co-Authored-By` or any AI attribution trailer** (repository owner's rule). Never push.
- Backend tests: `cd backend && ./gradlew test` (Docker running). Frontend: `cd frontend && npx prettier --write src e2e && npm run format:check && npm run lint && npm run typecheck && npm test`.
- Frontend tests: `setup()` helpers `return renderApp({ server, path })` (renderApp already returns `server`, `user`, `router`); after an action whose page refetches, register the new `GET` reply after the action button has appeared and before clicking it. Password-type inputs get a show/hide button whose accessible name contains the label, so Playwright uses `getByLabel(…, { exact: true })`.

## Review Focus

1. A ticket left waiting on the customer (PENDING) past its resolution due time shows **PAUSED, not BREACHED**, and when it returns to OPEN its resolution due time is later by exactly the paused duration (Task 2 `pausingMovesTheResolutionDueTimeByThePausedDuration`, Task 3 `waitingOnTheCustomerDoesNotBreach`).
2. Reopening a resolved ticket clears `resolved_at` and restarts the resolution clock from the reopen time with the full target; its first-response result is kept (Task 2 `reopenRestartsTheResolutionClockOnly`, Task 3 `reopeningRestartsResolutionAndCountsReopens`).
3. An internal note is never emailed and never counts as the first response, even when it is the first message (Task 4 `internalNotesAreNotEmailedAndDontCountAsAResponse`).
4. A requester with no email address: a public reply is still recorded and counts as the first response, and the response says nothing was emailed (Task 4 `aReplyToARequesterWithoutEmailIsStillRecorded`).
5. Ticket subjects with punctuation, operators or other scripts ("can't print: error 0x80!!", "a & b | c", "प्रिंटर") never make the context or search endpoints fail, and still find matches by their words (Task 5 `punctuationAndOtherScriptsDoNotBreakMatching`).

## File Structure

```
backend/src/main/resources/db/migration/
  V24__helpdesk_base.sql        permissions (D16), number_sequences kind TICKET, ticket_categories, sla_policies + seeds
  V25__tickets.sql              tickets (+ generated search tsvector, indexes)
  V26__ticket_messages.sql      ticket_messages (append-only)
  V27__kb_articles.sql          kb_articles (+ generated search tsvector)
backend/src/main/java/com/nexusops/
  shared/NumberSequences.java                       moved from inventory (kind + prefix)
  inventory/SequenceKind.java (prefix kept; NumberSequences call delegates)
  collaboration/Subjects.java                       + isKnownType(String)
  collaboration/SearchService.java                  ORDER gains TICKET, KB_ARTICLE
  helpdesk/package-info.java, HelpDeskPermissions.java, HelpDeskSetup.java
  helpdesk/CategoryCommand.java, CategoryView.java, CategoryService.java
  helpdesk/Priority.java, SlaPolicyView.java, SlaPolicyCommand.java, SlaPolicyService.java
  helpdesk/SlaClock.java, SlaState.java, SlaTargets.java, TicketStatus.java, Channel.java
  helpdesk/TicketCommand.java, TicketView.java, TicketSummary.java, TicketQuery.java, SlaView.java, TicketService.java,
      TicketSubjects.java, TicketRelations.java, AgentView.java
  helpdesk/MessageKind.java, MessageCommand.java, MessageView.java, TicketMessageService.java
  helpdesk/MessagePosted.java, TicketProductRef.java, LinkedRecord.java, CategoryRef.java
  helpdesk/ArticleStatus.java, ArticleCommand.java, ArticleView.java, ArticleSummary.java, ArticleService.java,
      ArticleSubjects.java, FullTextTerms.java, HelpDeskSearch.java (full-text queries), TicketContext.java,
      DashboardView.java, HelpDeskQueries.java (list filters and dashboard SQL)
  helpdesk/domain/…  TicketCategory, SlaPolicy, Ticket, TicketMessage, KbArticle (+ repositories)
  helpdesk/web/HelpDeskDtos.java, CategoryController.java, SlaPolicyController.java, TicketController.java,
      ArticleController.java, DashboardController.java
backend/src/test/java/com/nexusops/
  support/TestHelpDesk.java, support/PostgresAssertions.java (moved out of InventoryRlsIT)
  helpdesk/CategoryApiIT, SlaPolicyApiIT, SlaClockTest, TicketStatusTest, TicketApiIT, TicketMessageApiIT,
      ArticleApiIT, TicketContextIT, HelpDeskDashboardIT, HelpDeskModuleGateIT, FullTextTermsTest
  HelpDeskRlsIT; RlsCoverageIT, CrossTenantApiIT, OpenApiContractIT, collaboration/SearchApiIT (modified)
docs/decisions/0012-helpdesk-sla-clock-and-context.md; docs/api/openapi.json (re-exported)
frontend/src/
  lib/api/types.ts, features/auth/permissions.tsx, test/records.ts, features/records/SubjectLink.tsx,
  features/shell/GlobalSearch.tsx, routes.tsx, nav.ts, ComingSoonPage.test.tsx
  features/helpdesk/HelpDeskLayout.tsx, HelpDeskIndex.tsx, routes.tsx, labels.ts, sla.ts, invalidation.ts,
      SlaBadge.tsx, AgentSelect.tsx, CategorySelect.tsx, CatalogProductPicker.tsx, LinkedRecordPicker.tsx,
      TicketsPage.tsx, TicketFormDialog.tsx, TicketDetailPage.tsx, TicketConversation.tsx, MessageComposer.tsx,
      TicketActions.tsx, useTicketAction.ts, TicketContextPanel.tsx, ArticlesPage.tsx, ArticleFormDialog.tsx,
      ArticleDetailPage.tsx, HelpDeskDashboardPage.tsx, TicketsPanel.tsx (+ tests)
  features/settings/HelpDeskSettingsPage.tsx (+ test)
  features/directory/PartyDetailPage.tsx, features/crm/Customer360Page.tsx, features/products/ProductDetailPage.tsx
frontend/e2e/helpdesk.spec.ts, e2e/support/mailpit.ts (emailText); README.md
```

## Pre-flight rulings (made while writing this plan)

- Ticket numbers use the existing `number_sequences` table: the helper moves to `shared.NumberSequences.next(String kind, String prefix)` and inventory's `SequenceKind` passes its own name and prefix; V24 widens the table's CHECK to include `TICKET`.
- A ticket's SLA is computed from three stored values — `resolution_clock_started_at`, `paused_seconds` (accumulated) and `paused_at` (current pause) — so a priority change recomputes due times exactly, and reopening resets the clock start and paused total.
- `CLOSED` tickets are reported as archived collaboration subjects, so no new tasks, documents or activities attach to them (`409 "This record is archived."` from the collaboration module).
- Assignee choices come from `GET helpdesk/agents?q=` (active members, `helpdesk.ticket.read`), not the CRM owners route, so HelpDesk works without CRM.
- The product on a ticket may be any catalog product (goods or service, D3) — a new `CatalogProductPicker` in the HelpDesk feature, not Inventory's GOODS-only picker.
- Duplicate and article matching: the Java helper `FullTextTerms.orQuery(text, maxTerms)` turns free text into a safe `to_tsquery('simple', …)` string of OR-ed lowercase words (letters/digits of any script, length ≥ 2, at most `maxTerms` distinct); an empty result means "no suggestions" (no query is run). List search (`q`) uses `LIKE` on number and subject (tickets) and `websearch_to_tsquery('simple', :q)` (articles).
- Inserting a knowledge-base article into a reply (D13) inserts the article's title and text, not a link: there is no customer-facing knowledge base yet, so a link would mean nothing to the customer.
- The ticket list's assignee filter is the query parameter `assignee` (`me`, `unassigned` or a member id), not `assigneeId` as spec §5 writes it, because it carries values that aren't ids. The dashboard links use the same name.

---

### Task 1: HelpDesk permissions, shared ticket numbers, categories with default assignees, and SLA policies

**Files:**
- Create: `backend/src/main/resources/db/migration/V24__helpdesk_base.sql`
- Create: `backend/src/main/java/com/nexusops/shared/NumberSequences.java`; Delete: `backend/src/main/java/com/nexusops/inventory/NumberSequences.java`; Modify: callers in `inventory` (`PurchaseOrderService`, `SalesOrderService`) to call `numbers.next(SequenceKind.X.name(), SequenceKind.X.prefix())`
- Create: `backend/src/main/java/com/nexusops/helpdesk/package-info.java`, `HelpDeskPermissions.java`, `Priority.java`, `CategoryCommand.java`, `CategoryView.java`, `CategoryService.java`, `SlaPolicyCommand.java`, `SlaPolicyView.java`, `SlaPolicyService.java`, `HelpDeskSetup.java`
- Create: `backend/src/main/java/com/nexusops/helpdesk/domain/TicketCategory.java`, `TicketCategoryRepository.java`, `SlaPolicy.java`, `SlaPolicyRepository.java`
- Create: `backend/src/main/java/com/nexusops/helpdesk/web/HelpDeskDtos.java`, `CategoryController.java`, `SlaPolicyController.java`
- Test: `backend/src/test/java/com/nexusops/support/TestHelpDesk.java`, `backend/src/test/java/com/nexusops/helpdesk/CategoryApiIT.java`, `SlaPolicyApiIT.java`

**Interfaces:**
- Produces:
  - `shared.NumberSequences` (public `@Component`): `String next(String kind, String prefix)` — MANDATORY transaction, gap-free per tenant, `prefix + %05d` (`Locale.ROOT`).
  - `HelpDeskPermissions.{TICKET_READ, TICKET_MANAGE, TICKET_ASSIGN, TICKET_RESOLVE, SETTINGS_MANAGE, ARTICLE_READ, ARTICLE_MANAGE}`.
  - `enum Priority {LOW, NORMAL, HIGH, URGENT}`.
  - `CategoryView(UUID id, String name, String description, MemberRef defaultAssignee, int position, Instant archivedAt, long version)`; `CategoryCommand(String name, String description, UUID defaultAssigneeId)`.
  - `CategoryService` public `list(boolean archived)`, `create`, `update(UUID, CategoryCommand, Long)`, `archive(UUID)`, `restore(UUID)`, `seedDefaults()`; package-private `TicketCategory resolve(UUID id, String field)` (unknown → 400 field "Choose a category in this workspace."), `static void requireNotArchived(TicketCategory)` (409 ARCHIVED), `Map<UUID, TicketCategory> byIds(Collection<UUID>)`, `static CategoryRef ref(TicketCategory)` with `record CategoryRef(UUID id, String name)` (public, in `helpdesk`).
  - `SlaPolicyView(Priority priority, int firstResponseMinutes, int resolutionMinutes, long version)`; `SlaPolicyCommand(Integer firstResponseMinutes, Integer resolutionMinutes)`; `SlaPolicyService` public `list()`, `update(Priority, SlaPolicyCommand, Long)`, `seedDefaults()`; package-private `SlaTargets targets(Priority)` — `SlaTargets` is produced by Task 2; in this task add it as `public record SlaTargets(int firstResponseMinutes, int resolutionMinutes)` in `helpdesk` and Task 2 uses it.
  - Routes: `GET/POST /api/v1/helpdesk/categories`, `PUT /api/v1/helpdesk/categories/{id}`, `POST …/{id}/archive|restore`, `GET /api/v1/helpdesk/sla-policies`, `PUT /api/v1/helpdesk/sla-policies/{priority}`.
  - Test helper `TestHelpDesk.enable(Api owner)`, `TestHelpDesk.category(Api owner, String name) : UUID` (the id of a category by name).

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/com/nexusops/support/TestHelpDesk.java`:

```java
package com.nexusops.support;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

/** Enables the HelpDesk module and looks up its seeded records for tests. */
public final class TestHelpDesk {

    private TestHelpDesk() {}

    public static void enable(Api owner) throws Exception {
        owner.put("/api/v1/tenant/modules/HELPDESK", "{\"enabled\":true}").andExpect(status().isOk());
    }

    public static UUID category(Api owner, String name) throws Exception {
        List<String> ids = Api.read(owner.get("/api/v1/helpdesk/categories"), "$[?(@.name == '" + name + "')].id");
        return UUID.fromString(ids.getFirst());
    }
}
```

`backend/src/test/java/com/nexusops/helpdesk/CategoryApiIT.java`:

```java
package com.nexusops.helpdesk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestHelpDesk;
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
class CategoryApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("hdcat"));
        owner = Api.login(mvc, ws);
        TestHelpDesk.enable(owner);
    }

    private long audits(String action) {
        return OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select count(*) from audit_events where action = ?", Long.class, action);
    }

    @Test
    void everyWorkspaceStartsWithFourCategoriesAndTheDefaultSlaPolicies() throws Exception {
        owner.get("/api/v1/helpdesk/categories").andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name").value(Matchers.contains("General", "Billing", "Product issue", "Delivery")));
        owner.get("/api/v1/helpdesk/sla-policies")
                .andExpect(jsonPath("$[*].priority").value(Matchers.contains("URGENT", "HIGH", "NORMAL", "LOW")))
                .andExpect(jsonPath("$[0].firstResponseMinutes").value(60))
                .andExpect(jsonPath("$[0].resolutionMinutes").value(240))
                .andExpect(jsonPath("$[3].resolutionMinutes").value(7200));
    }

    @Test
    void createsRenamesArchivesAndRestoresWithADefaultAssignee() throws Exception {
        UUID ownerId = OwnerJdbc.ownerAs(ws.tenantId()).queryForObject("select id from users where email = ?", UUID.class,
                ws.email());
        UUID id = Api.id(owner.post("/api/v1/helpdesk/categories", "{\"name\":\"  Warranty   claims \","
                        + "\"description\":\"Repairs under warranty\",\"defaultAssigneeId\":\"" + ownerId + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Warranty claims"))
                .andExpect(jsonPath("$.defaultAssignee.id").value(ownerId.toString()))
                .andExpect(jsonPath("$.position").value(4)));
        owner.post("/api/v1/helpdesk/categories", "{\"name\":\"WARRANTY CLAIMS\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
        owner.put("/api/v1/helpdesk/categories/" + id, "{\"name\":\"Warranty\",\"version\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultAssignee").doesNotExist());
        owner.put("/api/v1/helpdesk/categories/" + id, "{\"name\":\"Warranty\",\"version\":0}")
                .andExpect(status().isConflict());
        owner.post("/api/v1/helpdesk/categories/" + id + "/archive", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").exists());
        owner.get("/api/v1/helpdesk/categories").andExpect(jsonPath("$[*].name", Matchers.not(Matchers.hasItem("Warranty"))));
        owner.get("/api/v1/helpdesk/categories?archived=true").andExpect(jsonPath("$[*].name").value(Matchers.contains("Warranty")));
        owner.post("/api/v1/helpdesk/categories/" + id + "/restore", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").doesNotExist());
        assertThat(audits("TicketCategoryCreated")).isEqualTo(1);
        assertThat(audits("TicketCategoryUpdated")).isEqualTo(1);
        assertThat(audits("TicketCategoryArchived")).isEqualTo(1);
        assertThat(audits("TicketCategoryRestored")).isEqualTo(1);
    }

    @Test
    void invalidCategoriesAreFieldErrors() throws Exception {
        String[][] cases = {
                {"{\"name\":\"\"}", "name"},
                {"{\"name\":\"" + "x".repeat(81) + "\"}", "name"},
                {"{\"name\":\"Ok\",\"description\":\"" + "x".repeat(501) + "\"}", "description"},
                {"{\"name\":\"Ok\",\"defaultAssigneeId\":\"" + UUID.randomUUID() + "\"}", "defaultAssigneeId"},
        };
        for (String[] c : cases) {
            owner.post("/api/v1/helpdesk/categories", c[0]).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value(c[1]));
        }
        owner.put("/api/v1/helpdesk/categories/" + UUID.randomUUID(), "{\"name\":\"X\",\"version\":0}")
                .andExpect(status().isNotFound());
    }

    @Test
    void settingsNeedTheirPermission() throws Exception {
        UUID readerRole = TestRoles.create(mvc, owner.session(), "Agent", "helpdesk.ticket.read");
        Api agent = Api.login(mvc, members.create(ws.tenantId(), Set.of(readerRole)));
        agent.get("/api/v1/helpdesk/categories").andExpect(status().isOk());
        agent.get("/api/v1/helpdesk/sla-policies").andExpect(status().isOk());
        agent.post("/api/v1/helpdesk/categories", "{\"name\":\"X\"}").andExpect(status().isForbidden());
        agent.put("/api/v1/helpdesk/sla-policies/LOW", "{\"firstResponseMinutes\":1,\"resolutionMinutes\":2,\"version\":0}")
                .andExpect(status().isForbidden());
    }
}
```

`backend/src/test/java/com/nexusops/helpdesk/SlaPolicyApiIT.java`:

```java
package com.nexusops.helpdesk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestHelpDesk;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class SlaPolicyApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;

    Workspace ws;
    Api owner;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("hdsla"));
        owner = Api.login(mvc, ws);
        TestHelpDesk.enable(owner);
    }

    @Test
    void updatesAPolicyWithItsVersion() throws Exception {
        owner.put("/api/v1/helpdesk/sla-policies/HIGH", "{\"firstResponseMinutes\":120,\"resolutionMinutes\":720,\"version\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priority").value("HIGH"))
                .andExpect(jsonPath("$.firstResponseMinutes").value(120))
                .andExpect(jsonPath("$.version").value(1));
        owner.put("/api/v1/helpdesk/sla-policies/HIGH", "{\"firstResponseMinutes\":60,\"resolutionMinutes\":720,\"version\":0}")
                .andExpect(status().isConflict());
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select count(*) from audit_events where action = 'SlaPolicyUpdated'", Long.class)).isEqualTo(1);
    }

    @Test
    void targetsMustBePositiveWithinSixtyDaysAndResolutionNotShorterThanFirstResponse() throws Exception {
        String[][] cases = {
                {"{\"firstResponseMinutes\":0,\"resolutionMinutes\":60,\"version\":0}", "firstResponseMinutes"},
                {"{\"resolutionMinutes\":60,\"version\":0}", "firstResponseMinutes"},
                {"{\"firstResponseMinutes\":60,\"resolutionMinutes\":86401,\"version\":0}", "resolutionMinutes"},
                {"{\"firstResponseMinutes\":120,\"resolutionMinutes\":60,\"version\":0}", "resolutionMinutes"},
                {"{\"firstResponseMinutes\":60,\"resolutionMinutes\":120}", "version"},
        };
        for (String[] c : cases) {
            owner.put("/api/v1/helpdesk/sla-policies/LOW", c[0]).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value(c[1]));
        }
        owner.put("/api/v1/helpdesk/sla-policies/CRITICAL", "{\"firstResponseMinutes\":1,\"resolutionMinutes\":2,\"version\":0}")
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && ./gradlew test --tests '*CategoryApiIT' --tests '*SlaPolicyApiIT'`
Expected: FAIL — `PUT /api/v1/tenant/modules/HELPDESK` works, but `/api/v1/helpdesk/categories` answers 404.

- [ ] **Step 3: Migration V24**

`backend/src/main/resources/db/migration/V24__helpdesk_base.sql`:

```sql
-- HelpDesk (Phase 7): module permissions (D16), ticket numbers, categories (D5) and SLA policies (D8).
INSERT INTO permissions (code, module_code, description) VALUES
    ('helpdesk.ticket.manage',   'HELPDESK', 'Create and edit tickets, reply, and add notes'),
    ('helpdesk.settings.manage', 'HELPDESK', 'Manage ticket categories and SLA policies'),
    ('helpdesk.article.read',    'HELPDESK', 'Read knowledge base articles'),
    ('helpdesk.article.manage',  'HELPDESK', 'Write, publish and archive knowledge base articles');
UPDATE permissions SET description = 'View tickets and the HelpDesk dashboard' WHERE code = 'helpdesk.ticket.read';
UPDATE permissions SET description = 'Assign tickets to team members' WHERE code = 'helpdesk.ticket.assign';
UPDATE permissions SET description = 'Move tickets between statuses: pending, resolved, closed, reopened'
WHERE code = 'helpdesk.ticket.resolve';
SELECT grant_to_system_roles(ARRAY['helpdesk.ticket.read', 'helpdesk.ticket.manage', 'helpdesk.ticket.assign',
                                   'helpdesk.ticket.resolve', 'helpdesk.settings.manage', 'helpdesk.article.read',
                                   'helpdesk.article.manage']);

-- Ticket numbers share the per-tenant document sequences (D2).
ALTER TABLE number_sequences DROP CONSTRAINT number_sequences_kind_check;
ALTER TABLE number_sequences ADD CONSTRAINT number_sequences_kind_check
    CHECK (kind IN ('PURCHASE_ORDER', 'SALES_ORDER', 'TICKET'));

CREATE TABLE ticket_categories (
    id                   uuid PRIMARY KEY,
    tenant_id            uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    name                 text NOT NULL CHECK (name = btrim(name) AND length(name) BETWEEN 1 AND 80),
    name_key             text NOT NULL,
    description          text CHECK (description IS NULL OR length(description) <= 500),
    default_assignee_id  uuid REFERENCES users (id) ON DELETE SET NULL,
    position             integer NOT NULL CHECK (position >= 0),
    archived_at          timestamptz,
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    version              bigint NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, name_key)
);
ALTER TABLE ticket_categories ENABLE ROW LEVEL SECURITY;
ALTER TABLE ticket_categories FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ticket_categories
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE sla_policies (
    id                      uuid PRIMARY KEY,
    tenant_id               uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    priority                text NOT NULL CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    first_response_minutes  integer NOT NULL CHECK (first_response_minutes BETWEEN 1 AND 86400),
    resolution_minutes      integer NOT NULL CHECK (resolution_minutes BETWEEN 1 AND 86400),
    updated_at              timestamptz NOT NULL,
    version                 bigint NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, priority),
    CHECK (resolution_minutes >= first_response_minutes)
);
ALTER TABLE sla_policies ENABLE ROW LEVEL SECURITY;
ALTER TABLE sla_policies FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sla_policies
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Existing workspaces get the default categories and policies (new ones get them from HelpDeskSetup).
DO $$
DECLARE
    t uuid;
BEGIN
    FOR t IN SELECT id FROM tenants LOOP
        PERFORM set_config('app.tenant_id', t::text, true);
        INSERT INTO ticket_categories (id, tenant_id, name, name_key, position, created_at, updated_at) VALUES
            (gen_random_uuid(), t, 'General', 'general', 0, now(), now()),
            (gen_random_uuid(), t, 'Billing', 'billing', 1, now(), now()),
            (gen_random_uuid(), t, 'Product issue', 'product issue', 2, now(), now()),
            (gen_random_uuid(), t, 'Delivery', 'delivery', 3, now(), now());
        INSERT INTO sla_policies (id, tenant_id, priority, first_response_minutes, resolution_minutes, updated_at) VALUES
            (gen_random_uuid(), t, 'URGENT', 60, 240, now()),
            (gen_random_uuid(), t, 'HIGH', 240, 1440, now()),
            (gen_random_uuid(), t, 'NORMAL', 480, 2880, now()),
            (gen_random_uuid(), t, 'LOW', 1440, 7200, now());
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END
$$;
```

(`number_sequences_kind_check` is the name PostgreSQL gave V18's inline CHECK — verified on the local database.)

- [ ] **Step 4: Shared number sequences**

`backend/src/main/java/com/nexusops/shared/NumberSequences.java` — the inventory class moved here, made public, keyed by plain kind and prefix:

```java
package com.nexusops.shared;

import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-tenant document numbers (PO-00001, SO-00001, T-00001). Gap-free among committed records: UPDATE … RETURNING
 * holds the row lock until the transaction ends, and the increment rolls back with it.
 */
@Component
public class NumberSequences {

    private final JdbcTemplate jdbc;

    NumberSequences(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String next(String kind, String prefix) {
        UUID tenant = TenantContext.requireTenantId();
        jdbc.update("insert into number_sequences (tenant_id, kind, next_value) values (?, ?, 1) on conflict do nothing",
                tenant, kind);
        Long value = jdbc.queryForObject("update number_sequences set next_value = next_value + 1 "
                + "where tenant_id = ? and kind = ? returning next_value - 1", Long.class, tenant, kind);
        return prefix + String.format(Locale.ROOT, "%05d", value);
    }
}
```

Delete `inventory/NumberSequences.java`; in `PurchaseOrderService` and `SalesOrderService` change the field type to `com.nexusops.shared.NumberSequences` and the calls to `numbers.next(SequenceKind.PURCHASE_ORDER.name(), SequenceKind.PURCHASE_ORDER.prefix())` / `numbers.next(SequenceKind.SALES_ORDER.name(), SequenceKind.SALES_ORDER.prefix())`. Run `./gradlew test --tests '*PurchaseOrderApiIT' --tests '*SalesOrderApiIT' --tests '*ModularityTest'` — Expected: PASS (numbers unchanged).

- [ ] **Step 5: Module, permissions, priority, SLA targets**

```java
/**
 * HelpDesk (Phase 7, ADR-0012): tickets with an SLA clock, categories with default assignees, a public/internal
 * conversation, a knowledge base and a dashboard. Every permission belongs to module HELPDESK.
 */
package com.nexusops.helpdesk;
```

```java
package com.nexusops.helpdesk;

/** Permission codes of module HELPDESK (D16). */
public final class HelpDeskPermissions {

    public static final String TICKET_READ = "helpdesk.ticket.read";
    public static final String TICKET_MANAGE = "helpdesk.ticket.manage";
    public static final String TICKET_ASSIGN = "helpdesk.ticket.assign";
    public static final String TICKET_RESOLVE = "helpdesk.ticket.resolve";
    public static final String SETTINGS_MANAGE = "helpdesk.settings.manage";
    public static final String ARTICLE_READ = "helpdesk.article.read";
    public static final String ARTICLE_MANAGE = "helpdesk.article.manage";

    private HelpDeskPermissions() {}
}
```

```java
package com.nexusops.helpdesk;

/** Ticket priorities (D4), most urgent first in lists. */
public enum Priority {
    LOW, NORMAL, HIGH, URGENT
}
```

```java
package com.nexusops.helpdesk;

/** One priority's SLA targets in minutes (D8). */
public record SlaTargets(int firstResponseMinutes, int resolutionMinutes) {}
```

- [ ] **Step 6: Domain**

`helpdesk/domain/TicketCategory.java`:

```java
package com.nexusops.helpdesk.domain;

import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ticket_categories")
public class TicketCategory extends TenantOwnedEntity {

    @Column(nullable = false)
    private String name;

    @Column(name = "name_key", nullable = false)
    private String nameKey;

    private String description;

    @Column(name = "default_assignee_id")
    private UUID defaultAssigneeId;

    @Column(nullable = false)
    private int position;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected TicketCategory() {}

    public TicketCategory(UUID id, String name, String nameKey, String description, UUID defaultAssigneeId,
            int position) {
        super(id);
        this.position = position;
        this.createdAt = Instant.now();
        apply(name, nameKey, description, defaultAssigneeId);
    }

    public void apply(String newName, String newKey, String newDescription, UUID newDefaultAssignee) {
        this.name = newName;
        this.nameKey = newKey;
        this.description = newDescription;
        this.defaultAssigneeId = newDefaultAssignee;
        this.updatedAt = Instant.now();
    }

    public void archive(Instant now) {
        this.archivedAt = now;
        this.updatedAt = now;
    }

    public void restore() {
        this.archivedAt = null;
        this.updatedAt = Instant.now();
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    public String getName() {
        return name;
    }

    public String getNameKey() {
        return nameKey;
    }

    public String getDescription() {
        return description;
    }

    public UUID getDefaultAssigneeId() {
        return defaultAssigneeId;
    }

    public int getPosition() {
        return position;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public long getVersion() {
        return version;
    }
}
```

`helpdesk/domain/TicketCategoryRepository.java`:

```java
package com.nexusops.helpdesk.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketCategoryRepository extends JpaRepository<TicketCategory, UUID> {

    List<TicketCategory> findByArchivedAtIsNullOrderByPositionAscNameAsc();

    List<TicketCategory> findByArchivedAtIsNotNullOrderByPositionAscNameAsc();

    boolean existsByNameKeyAndIdNot(String nameKey, UUID id);

    boolean existsByNameKey(String nameKey);
}
```

`helpdesk/domain/SlaPolicy.java`:

```java
package com.nexusops.helpdesk.domain;

import com.nexusops.helpdesk.Priority;
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
@Table(name = "sla_policies")
public class SlaPolicy extends TenantOwnedEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Priority priority;

    @Column(name = "first_response_minutes", nullable = false)
    private int firstResponseMinutes;

    @Column(name = "resolution_minutes", nullable = false)
    private int resolutionMinutes;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected SlaPolicy() {}

    public SlaPolicy(UUID id, Priority priority, int firstResponseMinutes, int resolutionMinutes) {
        super(id);
        this.priority = priority;
        apply(firstResponseMinutes, resolutionMinutes);
    }

    public void apply(int firstResponse, int resolution) {
        this.firstResponseMinutes = firstResponse;
        this.resolutionMinutes = resolution;
        this.updatedAt = Instant.now();
    }

    public Priority getPriority() {
        return priority;
    }

    public int getFirstResponseMinutes() {
        return firstResponseMinutes;
    }

    public int getResolutionMinutes() {
        return resolutionMinutes;
    }

    public long getVersion() {
        return version;
    }
}
```

`helpdesk/domain/SlaPolicyRepository.java`:

```java
package com.nexusops.helpdesk.domain;

import com.nexusops.helpdesk.Priority;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SlaPolicyRepository extends JpaRepository<SlaPolicy, UUID> {

    Optional<SlaPolicy> findByPriority(Priority priority);
}
```

- [ ] **Step 7: Services**

```java
package com.nexusops.helpdesk;

import java.util.UUID;

public record CategoryCommand(String name, String description, UUID defaultAssigneeId) {}
```

```java
package com.nexusops.helpdesk;

import com.nexusops.collaboration.MemberRef;
import java.time.Instant;
import java.util.UUID;

public record CategoryView(UUID id, String name, String description, MemberRef defaultAssignee, int position,
        Instant archivedAt, long version) {}
```

```java
package com.nexusops.helpdesk;

import java.util.UUID;

public record CategoryRef(UUID id, String name) {}
```

`helpdesk/CategoryService.java`:

```java
package com.nexusops.helpdesk;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.collaboration.MemberRef;
import com.nexusops.helpdesk.domain.TicketCategory;
import com.nexusops.helpdesk.domain.TicketCategoryRepository;
import com.nexusops.identity.Members;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.Text;
import com.nexusops.shared.db.TenantLocks;
import com.nexusops.shared.web.ApiProblem;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Ticket categories (D5): unique names ignoring case, an optional default assignee for routing, archive/restore. */
@Service
public class CategoryService {

    static final String NOT_FOUND = "Record not found.";
    static final String STALE = "This record was changed by someone else. Reload and try again.";
    static final String ARCHIVED = "This record is archived.";
    static final String UNKNOWN = "Choose a category in this workspace.";
    static final String NAME_TAKEN = "Another category already uses this name.";
    static final String NOT_A_MEMBER = "Choose an active team member.";
    private static final String LOCK = "helpdesk-categories";
    private static final List<String> DEFAULTS = List.of("General", "Billing", "Product issue", "Delivery");

    private final TicketCategoryRepository categories;
    private final Members members;
    private final TenantLocks locks;
    private final AuditService audit;

    CategoryService(TicketCategoryRepository categories, Members members, TenantLocks locks, AuditService audit) {
        this.categories = categories;
        this.members = members;
        this.locks = locks;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<CategoryView> list(boolean archived) {
        TenantContext.requireTenantId();
        List<TicketCategory> found = archived ? categories.findByArchivedAtIsNotNullOrderByPositionAscNameAsc()
                : categories.findByArchivedAtIsNullOrderByPositionAscNameAsc();
        return views(found);
    }

    @Transactional
    public CategoryView create(CategoryCommand command) {
        TenantContext.requireTenantId();
        String name = name(command.name());
        String description = Text.optional(command.description(), 500, "description");
        UUID assignee = assignee(command.defaultAssigneeId());
        locks.lock(LOCK);
        if (categories.existsByNameKey(key(name))) {
            throw ApiProblem.conflictField("name", NAME_TAKEN);
        }
        TicketCategory category = new TicketCategory(Ids.newId(), name, key(name), description, assignee,
                (int) categories.count());
        categories.saveAndFlush(category);
        audit.record(AuditEntry.of("TicketCategoryCreated", "TicketCategory", category.getId())
                .withAfter(snapshot(category)));
        return views(List.of(category)).getFirst();
    }

    @Transactional
    public CategoryView update(UUID id, CategoryCommand command, Long version) {
        TicketCategory category = find(id);
        checkVersion(category, version);
        if (category.isArchived()) {
            throw ApiProblem.conflict(ARCHIVED);
        }
        String name = name(command.name());
        String description = Text.optional(command.description(), 500, "description");
        UUID assignee = assignee(command.defaultAssigneeId());
        locks.lock(LOCK);
        if (categories.existsByNameKeyAndIdNot(key(name), id)) {
            throw ApiProblem.conflictField("name", NAME_TAKEN);
        }
        Map<String, Object> before = snapshot(category);
        category.apply(name, key(name), description, assignee);
        categories.flush();
        audit.record(AuditEntry.of("TicketCategoryUpdated", "TicketCategory", id).withBefore(before)
                .withAfter(snapshot(category)));
        return views(List.of(category)).getFirst();
    }

    @Transactional
    public CategoryView archive(UUID id) {
        TicketCategory category = find(id);
        if (!category.isArchived()) {
            category.archive(Instant.now());
            categories.flush();
            audit.record(AuditEntry.of("TicketCategoryArchived", "TicketCategory", id).withBefore(snapshot(category)));
        }
        return views(List.of(category)).getFirst();
    }

    @Transactional
    public CategoryView restore(UUID id) {
        TicketCategory category = find(id);
        if (category.isArchived()) {
            category.restore();
            categories.flush();
            audit.record(AuditEntry.of("TicketCategoryRestored", "TicketCategory", id).withAfter(snapshot(category)));
        }
        return views(List.of(category)).getFirst();
    }

    /** New workspaces (HelpDeskSetup): the default categories, once. */
    @Transactional
    public void seedDefaults() {
        TenantContext.requireTenantId();
        locks.lock(LOCK);
        if (categories.count() > 0) {
            return;
        }
        for (int i = 0; i < DEFAULTS.size(); i++) {
            String name = DEFAULTS.get(i);
            categories.save(new TicketCategory(Ids.newId(), name, key(name), null, null, i));
        }
        categories.flush();
    }

    /** A category referenced from a request body: unknown or other-tenant → 400 on {@code field}. */
    TicketCategory resolve(UUID id, String field) {
        if (id == null) {
            throw ApiProblem.badRequestField(field, UNKNOWN);
        }
        return categories.findById(id).orElseThrow(() -> ApiProblem.badRequestField(field, UNKNOWN));
    }

    static void requireNotArchived(TicketCategory category) {
        if (category.isArchived()) {
            throw ApiProblem.conflict(ARCHIVED);
        }
    }

    Map<UUID, TicketCategory> byIds(Collection<UUID> ids) {
        return categories.findAllById(ids).stream()
                .collect(Collectors.toMap(TicketCategory::getId, Function.identity()));
    }

    static CategoryRef ref(TicketCategory category) {
        return category == null ? null : new CategoryRef(category.getId(), category.getName());
    }

    private UUID assignee(UUID id) {
        if (id == null) {
            return null;
        }
        return members.findActive(id).map(Members.Member::id)
                .orElseThrow(() -> ApiProblem.badRequestField("defaultAssigneeId", NOT_A_MEMBER));
    }

    private List<CategoryView> views(List<TicketCategory> list) {
        Map<UUID, Members.Member> people = members.findAll(list.stream().map(TicketCategory::getDefaultAssigneeId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        return list.stream().map(c -> {
            Members.Member m = c.getDefaultAssigneeId() == null ? null : people.get(c.getDefaultAssigneeId());
            return new CategoryView(c.getId(), c.getName(), c.getDescription(),
                    m == null ? null : new MemberRef(m.id(), m.name()), c.getPosition(), c.getArchivedAt(),
                    c.getVersion());
        }).toList();
    }

    private TicketCategory find(UUID id) {
        TenantContext.requireTenantId();
        return categories.findById(id).orElseThrow(() -> ApiProblem.notFound(NOT_FOUND));
    }

    private static void checkVersion(TicketCategory category, Long version) {
        if (version == null) {
            throw ApiProblem.badRequestField("version", "Reload the record and try again.");
        }
        if (category.getVersion() != version) {
            throw ApiProblem.conflict(STALE);
        }
    }

    private static String name(String raw) {
        return Text.required(raw, 80, "name").replaceAll("\\s+", " ");
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static Map<String, Object> snapshot(TicketCategory c) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", c.getName());
        values.put("description", c.getDescription());
        values.put("defaultAssigneeId", c.getDefaultAssigneeId() == null ? null : c.getDefaultAssigneeId().toString());
        return values;
    }
}
```

(Import `java.util.Objects` and use `Objects::nonNull`. `Text.required` strips the ends; the `replaceAll` collapses inner whitespace, which the test's `"  Warranty   claims "` relies on.)

```java
package com.nexusops.helpdesk;

public record SlaPolicyCommand(Integer firstResponseMinutes, Integer resolutionMinutes) {}
```

```java
package com.nexusops.helpdesk;

public record SlaPolicyView(Priority priority, int firstResponseMinutes, int resolutionMinutes, long version) {}
```

`helpdesk/SlaPolicyService.java`:

```java
package com.nexusops.helpdesk;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.helpdesk.domain.SlaPolicy;
import com.nexusops.helpdesk.domain.SlaPolicyRepository;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.db.TenantLocks;
import com.nexusops.shared.web.ApiProblem;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One SLA policy per priority (D8). Targets are minutes: 1 to 60 days, resolution not shorter than first response. */
@Service
public class SlaPolicyService {

    static final int MAX_MINUTES = 60 * 24 * 60;
    static final Map<Priority, SlaTargets> DEFAULTS = new EnumMap<>(Map.of(
            Priority.URGENT, new SlaTargets(60, 240), Priority.HIGH, new SlaTargets(240, 1440),
            Priority.NORMAL, new SlaTargets(480, 2880), Priority.LOW, new SlaTargets(1440, 7200)));
    private static final String LOCK = "helpdesk-sla";

    private final SlaPolicyRepository policies;
    private final TenantLocks locks;
    private final AuditService audit;

    SlaPolicyService(SlaPolicyRepository policies, TenantLocks locks, AuditService audit) {
        this.policies = policies;
        this.locks = locks;
        this.audit = audit;
    }

    /** Most urgent first. */
    @Transactional(readOnly = true)
    public List<SlaPolicyView> list() {
        TenantContext.requireTenantId();
        return policies.findAll().stream()
                .sorted(Comparator.comparing(SlaPolicy::getPriority).reversed())
                .map(SlaPolicyService::view).toList();
    }

    @Transactional
    public SlaPolicyView update(Priority priority, SlaPolicyCommand command, Long version) {
        TenantContext.requireTenantId();
        int first = minutes(command.firstResponseMinutes(), "firstResponseMinutes");
        int resolution = minutes(command.resolutionMinutes(), "resolutionMinutes");
        if (resolution < first) {
            throw ApiProblem.badRequestField("resolutionMinutes", "Allow at least as long to resolve as to respond.");
        }
        if (version == null) {
            throw ApiProblem.badRequestField("version", "Reload the record and try again.");
        }
        SlaPolicy policy = policies.findByPriority(priority).orElseThrow(() -> ApiProblem.notFound("Record not found."));
        if (policy.getVersion() != version) {
            throw ApiProblem.conflict("This record was changed by someone else. Reload and try again.");
        }
        SlaPolicyView before = view(policy);
        policy.apply(first, resolution);
        policies.flush();
        audit.record(AuditEntry.of("SlaPolicyUpdated", "SlaPolicy", policy.getId())
                .withBefore(Map.of("firstResponseMinutes", before.firstResponseMinutes(),
                        "resolutionMinutes", before.resolutionMinutes()))
                .withAfter(Map.of("priority", priority.name(), "firstResponseMinutes", first,
                        "resolutionMinutes", resolution)));
        return view(policy);
    }

    /** The targets a ticket of this priority gets; the defaults if a workspace somehow lacks the row. */
    @Transactional(readOnly = true)
    SlaTargets targets(Priority priority) {
        return policies.findByPriority(priority)
                .map(p -> new SlaTargets(p.getFirstResponseMinutes(), p.getResolutionMinutes()))
                .orElse(DEFAULTS.get(priority));
    }

    @Transactional
    public void seedDefaults() {
        TenantContext.requireTenantId();
        locks.lock(LOCK);
        if (policies.count() > 0) {
            return;
        }
        DEFAULTS.forEach((priority, t) -> policies.save(
                new SlaPolicy(Ids.newId(), priority, t.firstResponseMinutes(), t.resolutionMinutes())));
        policies.flush();
    }

    private static int minutes(Integer value, String field) {
        if (value == null || value < 1) {
            throw ApiProblem.badRequestField(field, "Enter a number of minutes greater than 0.");
        }
        if (value > MAX_MINUTES) {
            throw ApiProblem.badRequestField(field, "Use at most 60 days.");
        }
        return value;
    }

    private static SlaPolicyView view(SlaPolicy p) {
        return new SlaPolicyView(p.getPriority(), p.getFirstResponseMinutes(), p.getResolutionMinutes(), p.getVersion());
    }
}
```

The test's case list hits body checks before the version check (`{"resolutionMinutes":60,"version":0}` → `firstResponseMinutes`; the version-less case is the last one). The order above (minutes, relation, version, find, stale) satisfies it.

`helpdesk/HelpDeskSetup.java`:

```java
package com.nexusops.helpdesk;

import com.nexusops.tenancy.WorkspaceRegistered;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Every new workspace starts with the default categories and SLA policies, in the signup transaction (D5, D8). */
@Component
class HelpDeskSetup {

    private final CategoryService categories;
    private final SlaPolicyService policies;

    HelpDeskSetup(CategoryService categories, SlaPolicyService policies) {
        this.categories = categories;
        this.policies = policies;
    }

    @EventListener
    void on(WorkspaceRegistered event) {
        categories.seedDefaults();
        policies.seedDefaults();
    }
}
```

- [ ] **Step 8: Web layer**

`helpdesk/web/HelpDeskDtos.java` (later tasks add their records here):

```java
package com.nexusops.helpdesk.web;

import com.nexusops.helpdesk.CategoryCommand;
import com.nexusops.helpdesk.SlaPolicyCommand;
import java.util.UUID;

final class HelpDeskDtos {

    private HelpDeskDtos() {}

    record CategoryRequest(String name, String description, UUID defaultAssigneeId, Long version) {
        CategoryCommand command() {
            return new CategoryCommand(name, description, defaultAssigneeId);
        }
    }

    record SlaPolicyRequest(Integer firstResponseMinutes, Integer resolutionMinutes, Long version) {
        SlaPolicyCommand command() {
            return new SlaPolicyCommand(firstResponseMinutes, resolutionMinutes);
        }
    }

    record VersionRequest(Long version) {}
}
```

`helpdesk/web/CategoryController.java`:

```java
package com.nexusops.helpdesk.web;

import com.nexusops.helpdesk.CategoryService;
import com.nexusops.helpdesk.CategoryView;
import com.nexusops.helpdesk.web.HelpDeskDtos.CategoryRequest;
import java.util.List;
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
@RequestMapping("/api/v1/helpdesk/categories")
class CategoryController {

    private final CategoryService categories;

    CategoryController(CategoryService categories) {
        this.categories = categories;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('helpdesk.ticket.read')")
    List<CategoryView> list(@RequestParam(defaultValue = "false") boolean archived) {
        return categories.list(archived);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('helpdesk.settings.manage')")
    CategoryView create(@RequestBody CategoryRequest request) {
        return categories.create(request.command());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('helpdesk.settings.manage')")
    CategoryView update(@PathVariable UUID id, @RequestBody CategoryRequest request) {
        return categories.update(id, request.command(), request.version());
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAuthority('helpdesk.settings.manage')")
    CategoryView archive(@PathVariable UUID id) {
        return categories.archive(id);
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAuthority('helpdesk.settings.manage')")
    CategoryView restore(@PathVariable UUID id) {
        return categories.restore(id);
    }
}
```

`helpdesk/web/SlaPolicyController.java`:

```java
package com.nexusops.helpdesk.web;

import com.nexusops.helpdesk.Priority;
import com.nexusops.helpdesk.SlaPolicyService;
import com.nexusops.helpdesk.SlaPolicyView;
import com.nexusops.helpdesk.web.HelpDeskDtos.SlaPolicyRequest;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/helpdesk/sla-policies")
class SlaPolicyController {

    private final SlaPolicyService policies;

    SlaPolicyController(SlaPolicyService policies) {
        this.policies = policies;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('helpdesk.ticket.read')")
    List<SlaPolicyView> list() {
        return policies.list();
    }

    @PutMapping("/{priority}")
    @PreAuthorize("hasAuthority('helpdesk.settings.manage')")
    SlaPolicyView update(@PathVariable Priority priority, @RequestBody SlaPolicyRequest request) {
        return policies.update(priority, request.command(), request.version());
    }
}
```

An unknown `{priority}` (`CRITICAL`) fails enum conversion; `GlobalExceptionHandler.handleTypeMismatch` already turns that into a 400.

- [ ] **Step 9: Run the tests to verify they pass**

Run: `cd backend && ./gradlew test --tests '*CategoryApiIT' --tests '*SlaPolicyApiIT' --tests '*ModularityTest' --tests '*EndpointAuthorizationCoverageTest' --tests '*PurchaseOrderApiIT' --tests '*SalesOrderApiIT'`
Expected: PASS. `RlsCoverageIT` will fail until the two new tables are in its list — add `"ticket_categories", "sla_policies"` to `EXPECTED_TENANT_TABLES` now and re-run it.

- [ ] **Step 10: Full suite, then commit**

Run: `cd backend && ./gradlew test` — Expected: PASS.

```bash
git add backend/src
git commit -m "feat(helpdesk): module permissions, shared ticket numbers, categories with default assignees and SLA policies"
```

---

### Task 2: The SLA clock and the ticket status workflow (pure rules)

**Files:**
- Create: `backend/src/main/java/com/nexusops/helpdesk/SlaState.java`, `SlaClock.java`, `TicketStatus.java`
- Test: `backend/src/test/java/com/nexusops/helpdesk/SlaClockTest.java`, `TicketStatusTest.java`

**Interfaces:**
- Consumes (Task 1): `SlaTargets(int firstResponseMinutes, int resolutionMinutes)`.
- Produces:
  - `enum SlaState {ON_TRACK, AT_RISK, PAUSED, MET, BREACHED}`.
  - `SlaClock` (package-private, static): `Instant firstResponseDue(Instant createdAt, SlaTargets t)`; `Instant resolutionDue(Instant clockStartedAt, long pausedSeconds, SlaTargets t)`; `long pausedSecondsAfterResume(long pausedSeconds, Instant pausedAt, Instant now)`; `SlaState state(Instant due, Instant doneAt, Instant now, int targetMinutes, Instant pausedAt)` (pausedAt null when not paused); constant `AT_RISK_FRACTION = 0.25`.
  - `enum TicketStatus {NEW, OPEN, PENDING, RESOLVED, CLOSED}` with `boolean isOpen()` (NEW/OPEN/PENDING) and `boolean canMoveTo(TicketStatus target)` per D6.

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/com/nexusops/helpdesk/SlaClockTest.java`:

```java
package com.nexusops.helpdesk;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SlaClockTest {

    static final Instant T0 = Instant.parse("2026-10-09T09:00:00Z");
    static final SlaTargets NORMAL = new SlaTargets(480, 2880);

    static Instant at(long minutes) {
        return T0.plus(Duration.ofMinutes(minutes));
    }

    @Test
    void dueTimesComeFromTheTargets() {
        assertThat(SlaClock.firstResponseDue(T0, NORMAL)).isEqualTo(at(480));
        assertThat(SlaClock.resolutionDue(T0, 0, NORMAL)).isEqualTo(at(2880));
        assertThat(SlaClock.resolutionDue(T0, 3600, NORMAL)).isEqualTo(at(2880 + 60));
    }

    @Test
    void pausingMovesTheResolutionDueTimeByThePausedDuration() {
        // paused at 1 h, resumed at 3 h 30 min: 2 h 30 min of waiting on the customer
        long paused = SlaClock.pausedSecondsAfterResume(0, at(60), at(210));
        assertThat(paused).isEqualTo(150 * 60);
        assertThat(SlaClock.resolutionDue(T0, paused, NORMAL)).isEqualTo(at(2880 + 150));
        // a second pause adds to the first
        assertThat(SlaClock.pausedSecondsAfterResume(paused, at(300), at(310))).isEqualTo(160 * 60);
        // a clock that went backwards never shortens the target
        assertThat(SlaClock.pausedSecondsAfterResume(paused, at(300), at(290))).isEqualTo(paused);
    }

    @Test
    void stateWhileRunning() {
        Instant due = at(480);
        assertThat(SlaClock.state(due, null, at(100), 480, null)).isEqualTo(SlaState.ON_TRACK);
        // 25 % of 480 = 120 minutes left is the at-risk line
        assertThat(SlaClock.state(due, null, at(359), 480, null)).isEqualTo(SlaState.ON_TRACK);
        assertThat(SlaClock.state(due, null, at(361), 480, null)).isEqualTo(SlaState.AT_RISK);
        assertThat(SlaClock.state(due, null, at(480), 480, null)).isEqualTo(SlaState.AT_RISK);
        assertThat(SlaClock.state(due, null, at(481), 480, null)).isEqualTo(SlaState.BREACHED);
    }

    @Test
    void doneIsMetOrBreachedForGood() {
        Instant due = at(480);
        assertThat(SlaClock.state(due, at(480), at(9999), 480, null)).isEqualTo(SlaState.MET);
        assertThat(SlaClock.state(due, at(481), at(481), 480, null)).isEqualTo(SlaState.BREACHED);
    }

    @Test
    void waitingOnTheCustomerIsPausedUnlessItWasAlreadyLate() {
        Instant due = at(2880);
        assertThat(SlaClock.state(due, null, at(5000), 2880, at(100))).isEqualTo(SlaState.PAUSED);
        assertThat(SlaClock.state(due, null, at(5000), 2880, at(2900))).isEqualTo(SlaState.BREACHED);
    }

    @Test
    void reopenRestartsTheResolutionClockOnly() {
        // resolved at 10 h within its 48 h target, reopened at 30 h: the new resolution due is 30 h + 48 h
        Instant reopenedAt = at(30 * 60);
        assertThat(SlaClock.resolutionDue(reopenedAt, 0, NORMAL)).isEqualTo(at(30 * 60 + 2880));
        // the first-response due time depends only on creation
        assertThat(SlaClock.firstResponseDue(T0, NORMAL)).isEqualTo(at(480));
    }
}
```

`backend/src/test/java/com/nexusops/helpdesk/TicketStatusTest.java`:

```java
package com.nexusops.helpdesk;

import static com.nexusops.helpdesk.TicketStatus.CLOSED;
import static com.nexusops.helpdesk.TicketStatus.NEW;
import static com.nexusops.helpdesk.TicketStatus.OPEN;
import static com.nexusops.helpdesk.TicketStatus.PENDING;
import static com.nexusops.helpdesk.TicketStatus.RESOLVED;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class TicketStatusTest {

    @Test
    void transitionsFollowTheWorkflow() {
        Map<TicketStatus, Set<TicketStatus>> allowed = Map.of(
                NEW, EnumSet.of(OPEN, PENDING, RESOLVED),
                OPEN, EnumSet.of(PENDING, RESOLVED),
                PENDING, EnumSet.of(OPEN, RESOLVED),
                RESOLVED, EnumSet.of(OPEN, CLOSED),
                CLOSED, EnumSet.noneOf(TicketStatus.class));
        for (TicketStatus from : TicketStatus.values()) {
            Set<TicketStatus> actual = Arrays.stream(TicketStatus.values()).filter(from::canMoveTo)
                    .collect(Collectors.toCollection(() -> EnumSet.noneOf(TicketStatus.class)));
            assertThat(actual).as(from.name()).isEqualTo(allowed.get(from));
        }
    }

    @Test
    void openMeansNotYetResolved() {
        assertThat(Arrays.stream(TicketStatus.values()).filter(TicketStatus::isOpen))
                .containsExactly(NEW, OPEN, PENDING);
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && ./gradlew test --tests '*SlaClockTest' --tests '*TicketStatusTest'`
Expected: FAIL — compilation errors, the types don't exist.

- [ ] **Step 3: Implement**

```java
package com.nexusops.helpdesk;

/** Where a ticket stands against one SLA target (D8). */
public enum SlaState {
    ON_TRACK, AT_RISK, PAUSED, MET, BREACHED
}
```

```java
package com.nexusops.helpdesk;

import java.time.Duration;
import java.time.Instant;

/**
 * The deterministic SLA rule (D8). First-response due = created + target. Resolution due = clock start (creation, or
 * the last reopen) + target + accumulated paused time; time spent waiting on the customer doesn't count.
 */
final class SlaClock {

    static final double AT_RISK_FRACTION = 0.25;

    private SlaClock() {}

    static Instant firstResponseDue(Instant createdAt, SlaTargets targets) {
        return createdAt.plus(Duration.ofMinutes(targets.firstResponseMinutes()));
    }

    static Instant resolutionDue(Instant clockStartedAt, long pausedSeconds, SlaTargets targets) {
        return clockStartedAt.plus(Duration.ofMinutes(targets.resolutionMinutes())).plusSeconds(pausedSeconds);
    }

    /** Accumulated pause after leaving PENDING; never negative, whatever the clocks did. */
    static long pausedSecondsAfterResume(long pausedSeconds, Instant pausedAt, Instant now) {
        return pausedSeconds + Math.max(0, Duration.between(pausedAt, now).toSeconds());
    }

    /**
     * {@code doneAt} null means not done yet; {@code pausedAt} null means the clock is running. A clock paused after its
     * due time had already passed stays breached: resuming can't give that time back.
     */
    static SlaState state(Instant due, Instant doneAt, Instant now, int targetMinutes, Instant pausedAt) {
        if (doneAt != null) {
            return doneAt.isAfter(due) ? SlaState.BREACHED : SlaState.MET;
        }
        if (pausedAt != null) {
            return pausedAt.isAfter(due) ? SlaState.BREACHED : SlaState.PAUSED;
        }
        if (now.isAfter(due)) {
            return SlaState.BREACHED;
        }
        long remainingSeconds = Duration.between(now, due).toSeconds();
        return remainingSeconds < targetMinutes * 60L * AT_RISK_FRACTION ? SlaState.AT_RISK : SlaState.ON_TRACK;
    }
}
```

`stateWhileRunning` pins the boundaries: at 359 minutes 121 minutes are left (not below 120 → ON_TRACK); at 361, 119 left → AT_RISK; at 480, 0 left → AT_RISK (not yet past due); at 481 → BREACHED.

```java
package com.nexusops.helpdesk;

/** The ticket workflow (D6). CLOSED is final; RESOLVED → OPEN is a reopen. */
public enum TicketStatus {
    NEW, OPEN, PENDING, RESOLVED, CLOSED;

    public boolean isOpen() {
        return this == NEW || this == OPEN || this == PENDING;
    }

    public boolean canMoveTo(TicketStatus target) {
        return switch (this) {
            case NEW -> target == OPEN || target == PENDING || target == RESOLVED;
            case OPEN -> target == PENDING || target == RESOLVED;
            case PENDING -> target == OPEN || target == RESOLVED;
            case RESOLVED -> target == OPEN || target == CLOSED;
            case CLOSED -> false;
        };
    }
}
```

- [ ] **Step 4: Run them to verify they pass**

Run: `cd backend && ./gradlew test --tests '*SlaClockTest' --tests '*TicketStatusTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/nexusops/helpdesk backend/src/test/java/com/nexusops/helpdesk
git commit -m "feat(helpdesk): a pausable SLA clock and the ticket status workflow"
```

---

### Task 3: Tickets — create, edit, route, assign, move through the workflow, filter by SLA

**Files:**
- Create: `backend/src/main/resources/db/migration/V25__tickets.sql`
- Create: `backend/src/main/java/com/nexusops/helpdesk/Channel.java`, `TicketCommand.java`, `TicketProductRef.java`, `LinkedRecord.java`, `SlaView.java`, `TicketView.java`, `TicketSummary.java`, `TicketQuery.java`, `AgentView.java`, `HelpDeskQueries.java`, `TicketService.java`, `TicketSubjects.java`
- Create: `backend/src/main/java/com/nexusops/helpdesk/domain/Ticket.java`, `TicketRepository.java`
- Create: `backend/src/main/java/com/nexusops/helpdesk/web/TicketController.java`; Modify: `web/HelpDeskDtos.java`
- Modify: `backend/src/main/java/com/nexusops/collaboration/Subjects.java` (`isKnownType`)
- Modify: `backend/src/test/java/com/nexusops/RlsCoverageIT.java` (`tickets`)
- Test: `backend/src/test/java/com/nexusops/helpdesk/TicketApiIT.java`

**Interfaces:**
- Consumes (Tasks 1–2): `shared.NumberSequences.next(String, String)`, `CategoryService.resolve/requireNotArchived/byIds/ref`, `CategoryRef`, `SlaPolicyService.targets(Priority)`, `SlaTargets`, `SlaClock`, `SlaState`, `TicketStatus`, `Priority`, `HelpDeskPermissions`, `TestHelpDesk`.
- Produces:
  - `enum Channel {PHONE, EMAIL, WALK_IN, WEB, OTHER}` (default PHONE).
  - `TicketCommand(String subject, String description, UUID requesterId, UUID productId, String linkedType, UUID linkedId, UUID categoryId, Priority priority, Channel channel, UUID assigneeId)` — `assigneeId` is used only on create.
  - `TicketProductRef(UUID id, String sku, String name)`; `LinkedRecord(String type, UUID id, String label)` (label null when the caller may not read that type).
  - `SlaView(Instant firstResponseDueAt, Instant firstRespondedAt, SlaState firstResponseState, Instant resolutionDueAt, Instant resolvedAt, SlaState resolutionState, Instant pausedAt)`.
  - `TicketView(UUID id, String number, String subject, String description, PartyRef requester, TicketProductRef product, LinkedRecord linked, CategoryRef category, Priority priority, Channel channel, MemberRef assignee, TicketStatus status, SlaView sla, String resolutionNote, int reopenCount, Instant closedAt, MemberRef createdBy, Instant createdAt, Instant updatedAt, long version)`.
  - `TicketSummary(UUID id, String number, String subject, PartyRef requester, CategoryRef category, Priority priority, TicketStatus status, MemberRef assignee, SlaView sla, Instant createdAt, Instant updatedAt)`.
  - `TicketQuery(String q, List<TicketStatus> statuses, Priority priority, String assignee /* uuid | "me" | "unassigned" | null */, UUID categoryId, UUID requesterId, UUID productId, String sla /* "breached" | "at_risk" | null */)`.
  - `AgentView(UUID id, String name, String email)`.
  - `TicketService` public `get(UUID)`, `list(TicketQuery, Integer, Integer)`, `create(TicketCommand)`, `update(UUID, TicketCommand, Long)`, `assign(UUID, UUID assigneeId, Long)`, `changeStatus(UUID, TicketStatus, String note, Long)`, `agents(String q)`; package-private `Ticket find(UUID)` (404), `TicketView view(Ticket)`, `SlaView sla(Ticket, Instant now)`, `Map<UUID, PartyRef> requesters(Collection<UUID>)`.
  - `Ticket` domain methods used by Task 4: `recordFirstResponse(Instant)`, `moveTo(TicketStatus, String note, SlaTargets, Instant)`, getters.
  - `HelpDeskQueries` (package-private `@Component`): `Page ticketPage(TicketQuery, UUID currentUser, int limit, long offset)` → `record Page(List<UUID> ids, long total)`; SQL fragments `OPEN_STATUSES`, `BREACHED`, `AT_RISK` (Task 6's dashboard reuses them).
  - `TicketSubjects` type `"TICKET"` (label `T-00001 · subject`, archived = CLOSED).
  - `Subjects.isKnownType(String) : boolean`.
  - Routes: `GET/POST /api/v1/helpdesk/tickets`, `GET/PUT /api/v1/helpdesk/tickets/{id}`, `POST …/{id}/assign {assigneeId, version}`, `POST …/{id}/status {status, note, version}`, `GET /api/v1/helpdesk/agents?q=`.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/nexusops/helpdesk/TicketApiIT.java`:

```java
package com.nexusops.helpdesk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestHelpDesk;
import com.nexusops.support.TestMembers;
import com.nexusops.support.TestRoles;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Workspace;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@AutoConfigureMockMvc
class TicketApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;
    UUID customer, ownerId;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("tkt"));
        owner = Api.login(mvc, ws);
        TestHelpDesk.enable(owner);
        customer = Api.id(owner.post("/api/v1/persons", "{\"firstName\":\"Meera\",\"lastName\":\"Iyer\","
                + "\"email\":\"meera@sahyadri.test\"}"));
        ownerId = jdbc().queryForObject("select id from users where email = ?", UUID.class, ws.email());
    }

    private JdbcTemplate jdbc() {
        return OwnerJdbc.ownerAs(ws.tenantId());
    }

    private String body(String extra) {
        return "{\"subject\":\"Printer not printing\",\"description\":\"Paper jams on every page\",\"requesterId\":\""
                + customer + "\"" + extra + "}";
    }

    private UUID ticket(String extra) throws Exception {
        return Api.id(owner.post("/api/v1/helpdesk/tickets", body(extra)).andExpect(status().isCreated()));
    }

    private ResultActions move(UUID id, String status, String note, long version) throws Exception {
        return owner.post("/api/v1/helpdesk/tickets/" + id + "/status", "{\"status\":\"" + status + "\""
                + (note == null ? "" : ",\"note\":\"" + note + "\"") + ",\"version\":" + version + "}");
    }

    private long version(UUID id) throws Exception {
        Integer v = Api.read(owner.get("/api/v1/helpdesk/tickets/" + id), "$.version");
        return v;
    }

    private static Instant instant(Object value) {
        return Instant.parse((String) value);
    }

    private long audits(String action) {
        return jdbc().queryForObject("select count(*) from audit_events where action = ?", Long.class, action);
    }

    @Test
    void createsNumberedTicketsWithSlaDueTimesFromThePriority() throws Exception {
        ResultActions created = owner.post("/api/v1/helpdesk/tickets", body(",\"priority\":\"URGENT\",\"channel\":\"EMAIL\""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.number").value("T-00001"))
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.priority").value("URGENT"))
                .andExpect(jsonPath("$.channel").value("EMAIL"))
                .andExpect(jsonPath("$.requester.name").value("Meera Iyer"))
                .andExpect(jsonPath("$.assignee").doesNotExist())
                .andExpect(jsonPath("$.sla.firstResponseState").value("ON_TRACK"))
                .andExpect(jsonPath("$.sla.resolutionState").value("ON_TRACK"));
        Instant createdAt = instant(Api.read(created, "$.createdAt"));
        assertThat(instant(Api.read(created, "$.sla.firstResponseDueAt"))).isEqualTo(createdAt.plus(Duration.ofMinutes(60)));
        assertThat(instant(Api.read(created, "$.sla.resolutionDueAt"))).isEqualTo(createdAt.plus(Duration.ofMinutes(240)));
        owner.post("/api/v1/helpdesk/tickets", body("")).andExpect(jsonPath("$.number").value("T-00002"))
                .andExpect(jsonPath("$.priority").value("NORMAL")).andExpect(jsonPath("$.channel").value("PHONE"));
        assertThat(audits("TicketCreated")).isEqualTo(2);
    }

    @Test
    void theCategoryRoutesToItsDefaultAssigneeWhoIsEmailed() throws Exception {
        var agent = members.create(ws.tenantId(), Set.of());
        UUID agentId = jdbc().queryForObject("select id from users where email = ?", UUID.class, agent.email());
        UUID billing = TestHelpDesk.category(owner, "Billing");
        owner.put("/api/v1/helpdesk/categories/" + billing, "{\"name\":\"Billing\",\"defaultAssigneeId\":\"" + agentId
                + "\",\"version\":0}").andExpect(status().isOk());
        owner.post("/api/v1/helpdesk/tickets", body(",\"categoryId\":\"" + billing + "\"")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.assignee.id").value(agentId.toString()))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.category.name").value("Billing"));
        assertThat(mail.sentTo(agent.email())).singleElement()
                .satisfies(m -> assertThat(m.subject()).isEqualTo("You've been assigned T-00001: Printer not printing"));
        // an explicit assignee wins over the category's
        owner.post("/api/v1/helpdesk/tickets", body(",\"categoryId\":\"" + billing + "\",\"assigneeId\":\"" + ownerId + "\""))
                .andExpect(jsonPath("$.assignee.id").value(ownerId.toString()));
    }

    @Test
    void invalidTicketsAreFieldErrors() throws Exception {
        UUID archivedCategory = TestHelpDesk.category(owner, "Delivery");
        owner.post("/api/v1/helpdesk/categories/" + archivedCategory + "/archive", "").andExpect(status().isOk());
        String[][] cases = {
                {"{\"description\":\"x\",\"requesterId\":\"" + customer + "\"}", "subject"},
                {"{\"subject\":\"" + "x".repeat(201) + "\",\"description\":\"x\",\"requesterId\":\"" + customer + "\"}", "subject"},
                {"{\"subject\":\"x\",\"requesterId\":\"" + customer + "\"}", "description"},
                {"{\"subject\":\"x\",\"description\":\"x\"}", "requesterId"},
                {body(",\"categoryId\":\"" + UUID.randomUUID() + "\""), "categoryId"},
                {body(",\"assigneeId\":\"" + UUID.randomUUID() + "\""), "assigneeId"},
                {body(",\"productId\":\"" + UUID.randomUUID() + "\""), "productId"},
                {body(",\"linkedType\":\"SPACESHIP\",\"linkedId\":\"" + UUID.randomUUID() + "\""), "linkedType"},
                {body(",\"linkedType\":\"PARTY\""), "linkedId"},
                {body(",\"linkedType\":\"PARTY\",\"linkedId\":\"" + UUID.randomUUID() + "\""), "linkedId"},
                {"{\"subject\":\"x\",\"description\":\"x\",\"requesterId\":\"" + UUID.randomUUID() + "\"}", "requesterId"},
        };
        for (String[] c : cases) {
            owner.post("/api/v1/helpdesk/tickets", c[0]).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value(c[1]));
        }
        owner.post("/api/v1/helpdesk/tickets", body(",\"categoryId\":\"" + archivedCategory + "\""))
                .andExpect(status().isConflict());
        owner.post("/api/v1/helpdesk/tickets", body(",\"priority\":\"CRITICAL\"")).andExpect(status().isBadRequest());
    }

    @Test
    void linksAProductAndAnyReadableRecord() throws Exception {
        UUID product = Api.id(owner.post("/api/v1/products", "{\"sku\":\"PRN-1\",\"name\":\"Laser printer\"}"));
        UUID org = Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Sahyadri Stores\"}"));
        UUID id = ticket(",\"productId\":\"" + product + "\",\"linkedType\":\"PARTY\",\"linkedId\":\"" + org + "\"");
        owner.get("/api/v1/helpdesk/tickets/" + id)
                .andExpect(jsonPath("$.product.sku").value("PRN-1"))
                .andExpect(jsonPath("$.linked.type").value("PARTY"))
                .andExpect(jsonPath("$.linked.label").value("Sahyadri Stores"));
    }

    @Test
    void editsWithItsVersionAndAPriorityChangeRecomputesTheDueTimes() throws Exception {
        UUID id = ticket("");
        Instant createdAt = instant(Api.read(owner.get("/api/v1/helpdesk/tickets/" + id), "$.createdAt"));
        owner.put("/api/v1/helpdesk/tickets/" + id, body(",\"priority\":\"HIGH\",\"version\":1"))
                .andExpect(status().isConflict());
        ResultActions edited = owner.put("/api/v1/helpdesk/tickets/" + id,
                        "{\"subject\":\"Printer jams\",\"description\":\"Every page\",\"requesterId\":\"" + customer
                                + "\",\"priority\":\"HIGH\",\"channel\":\"WALK_IN\",\"version\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("Printer jams"))
                .andExpect(jsonPath("$.priority").value("HIGH"))
                .andExpect(jsonPath("$.channel").value("WALK_IN"));
        assertThat(instant(Api.read(edited, "$.sla.firstResponseDueAt"))).isEqualTo(createdAt.plus(Duration.ofMinutes(240)));
        assertThat(instant(Api.read(edited, "$.sla.resolutionDueAt"))).isEqualTo(createdAt.plus(Duration.ofMinutes(1440)));
        owner.put("/api/v1/helpdesk/tickets/" + id, body("")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("version"));
        assertThat(audits("TicketUpdated")).isEqualTo(1);
    }

    @Test
    void assigningEmailsTheAssigneeAndOpensANewTicket() throws Exception {
        var agent = members.create(ws.tenantId(), Set.of());
        UUID agentId = jdbc().queryForObject("select id from users where email = ?", UUID.class, agent.email());
        UUID id = ticket("");
        owner.post("/api/v1/helpdesk/tickets/" + id + "/assign", "{\"assigneeId\":\"" + agentId + "\",\"version\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignee.name").value(Matchers.not(Matchers.emptyString())))
                .andExpect(jsonPath("$.status").value("OPEN"));
        assertThat(mail.sentTo(agent.email())).singleElement()
                .satisfies(m -> assertThat(m.textBody()).contains("T-00001", "/app/helpdesk/tickets/" + id));
        owner.post("/api/v1/helpdesk/tickets/" + id + "/assign", "{\"assigneeId\":\"" + UUID.randomUUID() + "\",\"version\":1}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("assigneeId"));
        owner.post("/api/v1/helpdesk/tickets/" + id + "/assign", "{\"assigneeId\":null,\"version\":1}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.assignee").doesNotExist())
                .andExpect(jsonPath("$.status").value("OPEN"));
        assertThat(audits("TicketAssigned")).isEqualTo(2);
    }

    @Test
    void theWorkflowRefusesInvalidMovesAndNeedsAResolutionNote() throws Exception {
        UUID id = ticket("");
        move(id, "CLOSED", null, 0).andExpect(status().isConflict());
        move(id, "RESOLVED", null, 0).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("note"));
        move(id, "RESOLVED", "Replaced the drum", 0).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolutionNote").value("Replaced the drum"))
                .andExpect(jsonPath("$.sla.resolvedAt").exists())
                .andExpect(jsonPath("$.sla.resolutionState").value("MET"));
        move(id, "CLOSED", null, 1).andExpect(status().isOk()).andExpect(jsonPath("$.closedAt").exists());
        move(id, "OPEN", null, 2).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("A closed ticket can't be changed."));
        owner.put("/api/v1/helpdesk/tickets/" + id, body(",\"version\":2")).andExpect(status().isConflict());
        // a closed ticket is an archived record for tasks, documents and activity
        owner.post("/api/v1/activities", "{\"subjectType\":\"TICKET\",\"subjectId\":\"" + id
                + "\",\"type\":\"NOTE\",\"summary\":\"Late note\"}").andExpect(status().isConflict());
        assertThat(audits("TicketStatusChanged")).isEqualTo(2);
    }

    @Test
    void waitingOnTheCustomerDoesNotBreach() throws Exception {
        UUID id = ticket("");
        move(id, "PENDING", null, 0).andExpect(status().isOk())
                .andExpect(jsonPath("$.sla.resolutionState").value("PAUSED"))
                .andExpect(jsonPath("$.sla.pausedAt").exists());
        // pretend: created 3 days ago and answered within the hour; resolution was due 1 day ago; waiting on the
        // customer since 2 days ago (before it was due)
        Instant now = Instant.now();
        Instant created = now.minus(Duration.ofDays(3));
        jdbc().update("""
                update tickets set created_at = ?, resolution_clock_started_at = ?, first_response_due_at = ?,
                       first_responded_at = ?, resolution_due_at = ?, paused_at = ? where id = ?""",
                ts(created), ts(created), ts(created.plus(Duration.ofMinutes(480))), ts(created.plus(Duration.ofHours(1))),
                ts(now.minus(Duration.ofDays(1))), ts(now.minus(Duration.ofDays(2))), id);
        owner.get("/api/v1/helpdesk/tickets/" + id).andExpect(jsonPath("$.sla.resolutionState").value("PAUSED"));
        owner.get("/api/v1/helpdesk/tickets?sla=breached").andExpect(jsonPath("$.items[*].id",
                Matchers.not(Matchers.hasItem(id.toString()))));
        ResultActions resumed = move(id, "OPEN", null, 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.sla.resolutionState").value("ON_TRACK"))
                .andExpect(jsonPath("$.sla.pausedAt").doesNotExist());
        // due = 1 day ago + 2 days of waiting = about 1 day from now
        Instant due = instant(Api.read(resumed, "$.sla.resolutionDueAt"));
        assertThat(due).isBetween(now.plus(Duration.ofHours(23)), now.plus(Duration.ofHours(25)));
    }

    @Test
    void reopeningRestartsResolutionAndCountsReopens() throws Exception {
        UUID id = ticket("");
        move(id, "RESOLVED", "Done", 0).andExpect(status().isOk());
        Instant before = Instant.now();
        ResultActions reopened = move(id, "OPEN", null, 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.reopenCount").value(1))
                .andExpect(jsonPath("$.sla.resolvedAt").doesNotExist())
                .andExpect(jsonPath("$.resolutionNote").doesNotExist())
                .andExpect(jsonPath("$.sla.firstRespondedAt").doesNotExist());
        Instant due = instant(Api.read(reopened, "$.sla.resolutionDueAt"));
        assertThat(due).isBetween(before.plus(Duration.ofMinutes(2880)).minusSeconds(5),
                Instant.now().plus(Duration.ofMinutes(2880)).plusSeconds(5));
    }

    @Test
    void listsAndFilters() throws Exception {
        UUID mine = ticket(",\"assigneeId\":\"" + ownerId + "\",\"priority\":\"HIGH\"");
        UUID unassigned = ticket("");
        UUID resolved = ticket("");
        move(resolved, "RESOLVED", "Fixed", 0).andExpect(status().isOk());
        owner.get("/api/v1/helpdesk/tickets").andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].number").value("T-00002"));
        owner.get("/api/v1/helpdesk/tickets?status=RESOLVED,CLOSED").andExpect(jsonPath("$.items[*].id")
                .value(Matchers.contains(resolved.toString())));
        owner.get("/api/v1/helpdesk/tickets?assignee=me").andExpect(jsonPath("$.items[*].id")
                .value(Matchers.contains(mine.toString())));
        owner.get("/api/v1/helpdesk/tickets?assignee=unassigned").andExpect(jsonPath("$.items[*].id")
                .value(Matchers.contains(unassigned.toString())));
        owner.get("/api/v1/helpdesk/tickets?priority=HIGH").andExpect(jsonPath("$.total").value(1));
        owner.get("/api/v1/helpdesk/tickets?q=00002").andExpect(jsonPath("$.items[*].id")
                .value(Matchers.contains(unassigned.toString())));
        owner.get("/api/v1/helpdesk/tickets?q=printer").andExpect(jsonPath("$.total").value(2));
        owner.get("/api/v1/helpdesk/tickets?requesterId=" + customer).andExpect(jsonPath("$.total").value(2));
        // first response overdue on one ticket: breached; another almost due: at risk
        jdbc().update("update tickets set first_response_due_at = now() - interval '1 minute' where id = ?", mine);
        jdbc().update("update tickets set created_at = now() - interval '470 minutes', "
                + "first_response_due_at = now() + interval '10 minutes' where id = ?", unassigned);
        owner.get("/api/v1/helpdesk/tickets?sla=breached").andExpect(jsonPath("$.items[*].id")
                .value(Matchers.contains(mine.toString())));
        owner.get("/api/v1/helpdesk/tickets?sla=at_risk").andExpect(jsonPath("$.items[*].id")
                .value(Matchers.contains(unassigned.toString())));
        owner.get("/api/v1/helpdesk/tickets/" + mine).andExpect(jsonPath("$.sla.firstResponseState").value("BREACHED"));
        owner.get("/api/v1/helpdesk/tickets/" + UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void agentsAndPermissions() throws Exception {
        owner.get("/api/v1/helpdesk/agents?q=" + ws.email().substring(0, 4))
                .andExpect(jsonPath("$[*].email").value(Matchers.hasItem(ws.email())));
        UUID id = ticket("");
        UUID readerRole = TestRoles.create(mvc, owner.session(), "Viewer", "helpdesk.ticket.read");
        Api reader = Api.login(mvc, members.create(ws.tenantId(), Set.of(readerRole)));
        reader.get("/api/v1/helpdesk/tickets/" + id).andExpect(status().isOk());
        reader.post("/api/v1/helpdesk/tickets", body("")).andExpect(status().isForbidden());
        reader.post("/api/v1/helpdesk/tickets/" + id + "/assign", "{\"assigneeId\":null,\"version\":0}")
                .andExpect(status().isForbidden());
        reader.post("/api/v1/helpdesk/tickets/" + id + "/status", "{\"status\":\"PENDING\",\"version\":0}")
                .andExpect(status().isForbidden());
        UUID blindRole = TestRoles.create(mvc, owner.session(), "Agent without directory", "helpdesk.ticket.read",
                "helpdesk.ticket.manage");
        Api blind = Api.login(mvc, members.create(ws.tenantId(), Set.of(blindRole)));
        blind.post("/api/v1/helpdesk/tickets", body("")).andExpect(status().isForbidden());
    }

    private static java.sql.Timestamp ts(Instant instant) {
        return java.sql.Timestamp.from(instant);
    }
}
```

(`RecordingMailSender.sentTo(email)` lists what was sent to an address; mail is delivered after commit and is already recorded when the request returns in tests, as `TaskApiIT` relies on. Each test agent has a fresh address, so `singleElement()` is exact.)

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && ./gradlew test --tests '*TicketApiIT'`
Expected: FAIL — 404 on `/api/v1/helpdesk/tickets`.

- [ ] **Step 3: Migration V25**

`backend/src/main/resources/db/migration/V25__tickets.sql`:

```sql
-- Tickets (D3–D8). SLA due times are stored and recomputed on change (spec §2); the search vector feeds D12.
CREATE TABLE tickets (
    id                           uuid PRIMARY KEY,
    tenant_id                    uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    number                       text NOT NULL CHECK (number ~ '^T-[0-9]{5,}$'),
    subject                      text NOT NULL CHECK (subject = btrim(subject) AND length(subject) BETWEEN 1 AND 200),
    description                  text NOT NULL CHECK (length(description) BETWEEN 1 AND 10000),
    requester_id                 uuid NOT NULL,
    product_id                   uuid,
    linked_type                  text CHECK (linked_type IS NULL OR linked_type ~ '^[A-Z_]{1,40}$'),
    linked_id                    uuid,
    category_id                  uuid,
    priority                     text NOT NULL CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    channel                      text NOT NULL CHECK (channel IN ('PHONE', 'EMAIL', 'WALK_IN', 'WEB', 'OTHER')),
    assignee_id                  uuid REFERENCES users (id) ON DELETE SET NULL,
    status                       text NOT NULL CHECK (status IN ('NEW', 'OPEN', 'PENDING', 'RESOLVED', 'CLOSED')),
    first_response_due_at        timestamptz NOT NULL,
    resolution_due_at            timestamptz NOT NULL,
    resolution_clock_started_at  timestamptz NOT NULL,
    paused_seconds               bigint NOT NULL DEFAULT 0 CHECK (paused_seconds >= 0),
    paused_at                    timestamptz,
    first_responded_at           timestamptz,
    resolved_at                  timestamptz,
    closed_at                    timestamptz,
    resolution_note              text CHECK (resolution_note IS NULL OR length(resolution_note) <= 2000),
    reopen_count                 integer NOT NULL DEFAULT 0 CHECK (reopen_count >= 0),
    created_by                   uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at                   timestamptz NOT NULL,
    updated_at                   timestamptz NOT NULL,
    version                      bigint NOT NULL DEFAULT 0,
    search                       tsvector GENERATED ALWAYS AS (
        setweight(to_tsvector('simple', subject), 'A') || setweight(to_tsvector('simple', description), 'B')) STORED,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, number),
    CHECK ((linked_type IS NULL) = (linked_id IS NULL)),
    CHECK ((status = 'PENDING') = (paused_at IS NOT NULL)),
    CHECK ((status IN ('RESOLVED', 'CLOSED')) = (resolved_at IS NOT NULL)),
    CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL)),
    FOREIGN KEY (tenant_id, requester_id) REFERENCES parties (tenant_id, id),
    FOREIGN KEY (tenant_id, product_id) REFERENCES products (tenant_id, id),
    FOREIGN KEY (tenant_id, category_id) REFERENCES ticket_categories (tenant_id, id)
);
CREATE INDEX tickets_status_idx ON tickets (tenant_id, status, priority);
CREATE INDEX tickets_assignee_idx ON tickets (tenant_id, assignee_id, status);
CREATE INDEX tickets_requester_idx ON tickets (tenant_id, requester_id, created_at);
CREATE INDEX tickets_product_idx ON tickets (tenant_id, product_id);
CREATE INDEX tickets_due_idx ON tickets (tenant_id, resolution_due_at) WHERE status IN ('NEW', 'OPEN', 'PENDING');
CREATE INDEX tickets_search_idx ON tickets USING gin (search);

ALTER TABLE tickets ENABLE ROW LEVEL SECURITY;
ALTER TABLE tickets FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tickets
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
```

Add `"tickets"` to `RlsCoverageIT.EXPECTED_TENANT_TABLES`.

- [ ] **Step 4: Types**

```java
package com.nexusops.helpdesk;

/** How the customer reached the business (D3). */
public enum Channel {
    PHONE, EMAIL, WALK_IN, WEB, OTHER
}
```

```java
package com.nexusops.helpdesk;

import java.util.UUID;

/** Raw input. {@code assigneeId} is honoured on create only (assignment has its own action); null priority → NORMAL, null channel → PHONE. */
public record TicketCommand(String subject, String description, UUID requesterId, UUID productId, String linkedType,
        UUID linkedId, UUID categoryId, Priority priority, Channel channel, UUID assigneeId) {}
```

```java
package com.nexusops.helpdesk;

import java.util.UUID;

public record TicketProductRef(UUID id, String sku, String name) {}
```

```java
package com.nexusops.helpdesk;

import java.util.UUID;

/** Any collaboration record the ticket concerns; {@code label} is null when the caller may not read that type. */
public record LinkedRecord(String type, UUID id, String label) {}
```

```java
package com.nexusops.helpdesk;

import java.time.Instant;

public record SlaView(Instant firstResponseDueAt, Instant firstRespondedAt, SlaState firstResponseState,
        Instant resolutionDueAt, Instant resolvedAt, SlaState resolutionState, Instant pausedAt) {}
```

```java
package com.nexusops.helpdesk;

import com.nexusops.collaboration.MemberRef;
import com.nexusops.directory.PartyRef;
import java.time.Instant;
import java.util.UUID;

public record TicketView(UUID id, String number, String subject, String description, PartyRef requester,
        TicketProductRef product, LinkedRecord linked, CategoryRef category, Priority priority, Channel channel,
        MemberRef assignee, TicketStatus status, SlaView sla, String resolutionNote, int reopenCount,
        Instant closedAt, MemberRef createdBy, Instant createdAt, Instant updatedAt, long version) {}
```

```java
package com.nexusops.helpdesk;

import com.nexusops.collaboration.MemberRef;
import com.nexusops.directory.PartyRef;
import java.time.Instant;
import java.util.UUID;

public record TicketSummary(UUID id, String number, String subject, PartyRef requester, CategoryRef category,
        Priority priority, TicketStatus status, MemberRef assignee, SlaView sla, Instant createdAt,
        Instant updatedAt) {}
```

```java
package com.nexusops.helpdesk;

import java.util.List;
import java.util.UUID;

/** {@code statuses} empty → the open ones; {@code assignee} is a member id, "me" or "unassigned"; {@code sla} "breached" or "at_risk". */
public record TicketQuery(String q, List<TicketStatus> statuses, Priority priority, String assignee, UUID categoryId,
        UUID requesterId, UUID productId, String sla) {}
```

```java
package com.nexusops.helpdesk;

import java.util.UUID;

public record AgentView(UUID id, String name, String email) {}
```

- [ ] **Step 5: Domain**

`helpdesk/domain/Ticket.java`:

```java
package com.nexusops.helpdesk.domain;

import com.nexusops.helpdesk.Channel;
import com.nexusops.helpdesk.Priority;
import com.nexusops.helpdesk.TicketStatus;
import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * A support ticket. SLA due times are stored; the service recomputes them through SlaClock whenever the priority or
 * the pause state changes (spec §2). The generated {@code search} column is not mapped.
 */
@Entity
@Table(name = "tickets")
public class Ticket extends TenantOwnedEntity {

    @Column(nullable = false, updatable = false)
    private String number;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false)
    private String description;

    @Column(name = "requester_id", nullable = false)
    private UUID requesterId;

    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "linked_type")
    private String linkedType;

    @Column(name = "linked_id")
    private UUID linkedId;

    @Column(name = "category_id")
    private UUID categoryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Priority priority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Channel channel;

    @Column(name = "assignee_id")
    private UUID assigneeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TicketStatus status;

    @Column(name = "first_response_due_at", nullable = false)
    private Instant firstResponseDueAt;

    @Column(name = "resolution_due_at", nullable = false)
    private Instant resolutionDueAt;

    @Column(name = "resolution_clock_started_at", nullable = false)
    private Instant resolutionClockStartedAt;

    @Column(name = "paused_seconds", nullable = false)
    private long pausedSeconds;

    @Column(name = "paused_at")
    private Instant pausedAt;

    @Column(name = "first_responded_at")
    private Instant firstRespondedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "resolution_note")
    private String resolutionNote;

    @Column(name = "reopen_count", nullable = false)
    private int reopenCount;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Ticket() {}

    /** A NEW ticket (OPEN when it starts with an assignee); the caller supplies the due times from SlaClock. */
    public Ticket(UUID id, String number, Priority priority, UUID assigneeId, UUID createdBy, Instant now,
            Instant firstResponseDueAt, Instant resolutionDueAt) {
        super(id);
        this.number = number;
        this.priority = priority;
        this.assigneeId = assigneeId;
        this.status = assigneeId == null ? TicketStatus.NEW : TicketStatus.OPEN;
        this.createdBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
        this.resolutionClockStartedAt = now;
        this.firstResponseDueAt = firstResponseDueAt;
        this.resolutionDueAt = resolutionDueAt;
    }

    public void applyDetails(String newSubject, String newDescription, UUID newRequester, UUID newProduct,
            String newLinkedType, UUID newLinkedId, UUID newCategory, Channel newChannel) {
        this.subject = newSubject;
        this.description = newDescription;
        this.requesterId = newRequester;
        this.productId = newProduct;
        this.linkedType = newLinkedType;
        this.linkedId = newLinkedId;
        this.categoryId = newCategory;
        this.channel = newChannel;
        this.updatedAt = Instant.now();
    }

    /** A new priority and its due times (computed by the caller from the ticket's own clock values). */
    public void reprioritize(Priority newPriority, Instant newFirstResponseDue, Instant newResolutionDue) {
        this.priority = newPriority;
        this.firstResponseDueAt = newFirstResponseDue;
        this.resolutionDueAt = newResolutionDue;
        this.updatedAt = Instant.now();
    }

    /** Assigning someone to a NEW ticket opens it (D6). */
    public void assign(UUID newAssignee, Instant now) {
        this.assigneeId = newAssignee;
        if (newAssignee != null && status == TicketStatus.NEW) {
            this.status = TicketStatus.OPEN;
        }
        this.updatedAt = now;
    }

    /** Leaves PENDING: the waiting time no longer counts against the resolution target. */
    public void resume(long newPausedSeconds, Instant newResolutionDue, Instant now) {
        this.pausedSeconds = newPausedSeconds;
        this.resolutionDueAt = newResolutionDue;
        this.pausedAt = null;
        this.updatedAt = now;
    }

    public void pause(Instant now) {
        this.pausedAt = now;
        this.status = TicketStatus.PENDING;
        this.updatedAt = now;
    }

    public void open(Instant now) {
        this.status = TicketStatus.OPEN;
        this.updatedAt = now;
    }

    public void resolve(String note, Instant now) {
        this.status = TicketStatus.RESOLVED;
        this.resolvedAt = now;
        this.resolutionNote = note;
        this.updatedAt = now;
    }

    public void close(Instant now) {
        this.status = TicketStatus.CLOSED;
        this.closedAt = now;
        this.updatedAt = now;
    }

    /** RESOLVED → OPEN: the resolution clock restarts with the full target (D8). */
    public void reopen(Instant newResolutionDue, Instant now) {
        this.status = TicketStatus.OPEN;
        this.reopenCount++;
        this.resolvedAt = null;
        this.resolutionNote = null;
        this.resolutionClockStartedAt = now;
        this.pausedSeconds = 0;
        this.resolutionDueAt = newResolutionDue;
        this.updatedAt = now;
    }

    /** The first public reply (D9); opens a NEW ticket. Later replies change nothing here. */
    public void recordFirstResponse(Instant now) {
        if (firstRespondedAt == null) {
            this.firstRespondedAt = now;
        }
        if (status == TicketStatus.NEW) {
            this.status = TicketStatus.OPEN;
        }
        this.updatedAt = now;
    }

    /** Bumps the version for changes that live in other tables (messages). */
    public void touch(Instant now) {
        this.updatedAt = now;
    }

    public String getNumber() { return number; }
    public String getSubject() { return subject; }
    public String getDescription() { return description; }
    public UUID getRequesterId() { return requesterId; }
    public UUID getProductId() { return productId; }
    public String getLinkedType() { return linkedType; }
    public UUID getLinkedId() { return linkedId; }
    public UUID getCategoryId() { return categoryId; }
    public Priority getPriority() { return priority; }
    public Channel getChannel() { return channel; }
    public UUID getAssigneeId() { return assigneeId; }
    public TicketStatus getStatus() { return status; }
    public Instant getFirstResponseDueAt() { return firstResponseDueAt; }
    public Instant getResolutionDueAt() { return resolutionDueAt; }
    public Instant getResolutionClockStartedAt() { return resolutionClockStartedAt; }
    public long getPausedSeconds() { return pausedSeconds; }
    public Instant getPausedAt() { return pausedAt; }
    public Instant getFirstRespondedAt() { return firstRespondedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public Instant getClosedAt() { return closedAt; }
    public String getResolutionNote() { return resolutionNote; }
    public int getReopenCount() { return reopenCount; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
```

(Write the getters in the multi-line style the other entities use; they are shown on one line here for brevity.)

`helpdesk/domain/TicketRepository.java`:

```java
package com.nexusops.helpdesk.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface TicketRepository extends JpaRepository<Ticket, UUID>, JpaSpecificationExecutor<Ticket> {}
```

- [ ] **Step 6: The list query (SQL, with the SLA filters)**

`helpdesk/HelpDeskQueries.java`:

```java
package com.nexusops.helpdesk;

import com.nexusops.shared.TenantContext;
import com.nexusops.shared.Text;
import com.nexusops.shared.web.ApiProblem;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * SQL read models for HelpDesk. The SLA fragments mirror SlaClock (D8) — keep the two in step: breached = a running
 * clock past its due time, or a pause that began after the due time; at risk = running, not breached, and less than
 * 25 % of the target left.
 */
@Component
class HelpDeskQueries {

    static final String OPEN_STATUSES = "t.status in ('NEW', 'OPEN', 'PENDING')";

    static final String FIRST_RESPONSE_BREACHED = "(t.first_responded_at is null and t.first_response_due_at < now())";
    static final String RESOLUTION_BREACHED = "((t.paused_at is null and t.resolution_due_at < now()) "
            + "or (t.paused_at is not null and t.paused_at > t.resolution_due_at))";
    static final String BREACHED = "(" + OPEN_STATUSES + " and (" + FIRST_RESPONSE_BREACHED + " or "
            + RESOLUTION_BREACHED + "))";
    static final String AT_RISK = "(" + OPEN_STATUSES + " and not " + BREACHED + " and ("
            + "(t.first_responded_at is null and t.first_response_due_at - now() < (t.first_response_due_at - t.created_at) * 0.25)"
            + " or (t.paused_at is null and t.resolution_due_at - now() < (t.resolution_due_at - t.resolution_clock_started_at"
            + " - make_interval(secs => t.paused_seconds)) * 0.25)))";

    record Page(List<UUID> ids, long total) {}

    private final NamedParameterJdbcTemplate jdbc;

    HelpDeskQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Page ticketPage(TicketQuery query, UUID currentUser, int limit, long offset) {
        MapSqlParameterSource params = new MapSqlParameterSource("tenant", TenantContext.requireTenantId());
        String where = where(query, currentUser, params);
        Long total = jdbc.queryForObject("select count(*) from tickets t where " + where, params, Long.class);
        params.addValue("limit", limit).addValue("offset", offset);
        List<UUID> ids = jdbc.queryForList("select t.id from tickets t where " + where
                + " order by t.created_at desc, t.id desc limit :limit offset :offset", params, UUID.class);
        return new Page(ids, total == null ? 0 : total);
    }

    private static String where(TicketQuery q, UUID currentUser, MapSqlParameterSource params) {
        StringBuilder w = new StringBuilder("t.tenant_id = :tenant");
        List<TicketStatus> statuses = q.statuses() == null || q.statuses().isEmpty()
                ? List.of(TicketStatus.NEW, TicketStatus.OPEN, TicketStatus.PENDING) : q.statuses();
        w.append(" and t.status in (:statuses)");
        params.addValue("statuses", statuses.stream().map(Enum::name).toList());
        if (q.priority() != null) {
            w.append(" and t.priority = :priority");
            params.addValue("priority", q.priority().name());
        }
        if (q.assignee() != null && !q.assignee().isBlank()) {
            switch (q.assignee()) {
                case "unassigned" -> w.append(" and t.assignee_id is null");
                case "me" -> {
                    w.append(" and t.assignee_id = :assignee");
                    params.addValue("assignee", currentUser);
                }
                default -> {
                    w.append(" and t.assignee_id = :assignee");
                    params.addValue("assignee", uuid(q.assignee(), "assignee"));
                }
            }
        }
        if (q.categoryId() != null) {
            w.append(" and t.category_id = :category");
            params.addValue("category", q.categoryId());
        }
        if (q.requesterId() != null) {
            w.append(" and t.requester_id = :requester");
            params.addValue("requester", q.requesterId());
        }
        if (q.productId() != null) {
            w.append(" and t.product_id = :product");
            params.addValue("product", q.productId());
        }
        String text = Text.optional(q.q(), 100, "q");
        if (text != null) {
            w.append(" and (lower(t.number) like :pattern escape '\\' or lower(t.subject) like :pattern escape '\\')");
            params.addValue("pattern", Text.containsPattern(text));
        }
        if (q.sla() != null && !q.sla().isBlank()) {
            switch (q.sla()) {
                case "breached" -> w.append(" and ").append(BREACHED);
                case "at_risk" -> w.append(" and ").append(AT_RISK);
                default -> throw ApiProblem.badRequestField("sla", "Use breached or at_risk.");
            }
        }
        return w.toString();
    }

    private static UUID uuid(String raw, String field) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw ApiProblem.badRequestField(field, "Use a member id, me or unassigned.");
        }
    }
}
```



- [ ] **Step 7: Collaboration: known types**

In `collaboration/Subjects.java` add:

```java
    /** Whether a subject type exists (a resolver is registered for it). */
    public boolean isKnownType(String type) {
        return type != null && resolvers.containsKey(type);
    }
```

- [ ] **Step 8: The service and the subject resolver**

`helpdesk/TicketService.java`:

```java
package com.nexusops.helpdesk;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.catalog.CatalogPermissions;
import com.nexusops.catalog.ProductBrief;
import com.nexusops.catalog.ProductService;
import com.nexusops.collaboration.MemberRef;
import com.nexusops.collaboration.SubjectRef;
import com.nexusops.collaboration.Subjects;
import com.nexusops.directory.DirectoryPermissions;
import com.nexusops.directory.PartyBrief;
import com.nexusops.directory.PartyRef;
import com.nexusops.directory.PartyService;
import com.nexusops.helpdesk.domain.Ticket;
import com.nexusops.helpdesk.domain.TicketCategory;
import com.nexusops.helpdesk.domain.TicketRepository;
import com.nexusops.identity.Members;
import com.nexusops.notifications.MailRequested;
import com.nexusops.notifications.OutgoingMail;
import com.nexusops.shared.Ids;
import com.nexusops.shared.NumberSequences;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.Text;
import com.nexusops.shared.security.CurrentAuthorities;
import com.nexusops.shared.web.ApiProblem;
import com.nexusops.shared.web.PageResponse;
import com.nexusops.shared.web.Paging;
import com.nexusops.tenancy.TenantDirectory;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tickets (D3–D8): context references, routing by category, assignment, the workflow and its SLA clock. */
@Service
public class TicketService {

    static final String NOT_FOUND = "Record not found.";
    static final String STALE = "This record was changed by someone else. Reload and try again.";
    static final String FORBIDDEN = "You do not have permission to perform this action.";
    static final String ARCHIVED = "This record is archived.";
    static final String CLOSED = "A closed ticket can't be changed.";
    static final String NOTE_NEEDED = "Add a resolution note.";
    static final String UNKNOWN_PARTY = "Choose a person or organization in this workspace.";
    static final String NOT_A_MEMBER = "Choose an active team member.";
    static final String NOT_ALLOWED = "This status change isn't allowed.";
    static final String SEQUENCE = "TICKET";

    private final TicketRepository tickets;
    private final NumberSequences numbers;
    private final CategoryService categories;
    private final SlaPolicyService policies;
    private final HelpDeskQueries queries;
    private final PartyService parties;
    private final ProductService products;
    private final Subjects subjects;
    private final Members members;
    private final TenantDirectory tenants;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final String appBaseUrl;

    TicketService(TicketRepository tickets, NumberSequences numbers, CategoryService categories,
            SlaPolicyService policies, HelpDeskQueries queries, PartyService parties, ProductService products,
            Subjects subjects, Members members, TenantDirectory tenants, AuditService audit,
            ApplicationEventPublisher events, @Value("${nexusops.app.base-url}") String appBaseUrl) {
        this.tickets = tickets;
        this.numbers = numbers;
        this.categories = categories;
        this.policies = policies;
        this.queries = queries;
        this.parties = parties;
        this.products = products;
        this.subjects = subjects;
        this.members = members;
        this.tenants = tenants;
        this.audit = audit;
        this.events = events;
        this.appBaseUrl = appBaseUrl;
    }

    /** Validated input: every reference resolved. */
    private record Draft(String subject, String description, UUID requesterId, UUID productId, String linkedType,
            UUID linkedId, UUID categoryId, Priority priority, Channel channel, UUID assignee) {}

    @Transactional(readOnly = true)
    public TicketView get(UUID id) {
        return view(find(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<TicketSummary> list(TicketQuery query, Integer page, Integer size) {
        TenantContext.requireTenantId();
        Pageable paging = Paging.of(page, size);
        HelpDeskQueries.Page found = queries.ticketPage(query, TenantContext.userId().orElse(null),
                paging.getPageSize(), paging.getOffset());
        Map<UUID, Ticket> byId = tickets.findAllById(found.ids()).stream()
                .collect(Collectors.toMap(Ticket::getId, Function.identity()));
        List<Ticket> ordered = found.ids().stream().map(byId::get).filter(Objects::nonNull).toList();
        return new PageResponse<>(summaries(ordered), paging.getPageNumber(), paging.getPageSize(), found.total());
    }

    @Transactional
    public TicketView create(TicketCommand command) {
        TenantContext.requireTenantId();
        Draft draft = validate(command, null);
        UUID assignee = draft.assignee();
        Instant now = Instant.now();
        SlaTargets targets = policies.targets(draft.priority());
        Ticket ticket = new Ticket(Ids.newId(), numbers.next(SEQUENCE, "T-"), draft.priority(), assignee,
                TenantContext.userId().orElse(null), now, SlaClock.firstResponseDue(now, targets),
                SlaClock.resolutionDue(now, 0, targets));
        apply(ticket, draft);
        tickets.saveAndFlush(ticket);
        audit.record(AuditEntry.of("TicketCreated", "Ticket", ticket.getId()).withAfter(snapshot(ticket)));
        notifyAssignee(ticket, null);
        return view(ticket);
    }

    @Transactional
    public TicketView update(UUID id, TicketCommand command, Long version) {
        Ticket ticket = find(id);
        checkVersion(ticket, version);
        requireNotClosed(ticket);
        Draft draft = validate(command, ticket);
        Map<String, Object> before = snapshot(ticket);
        apply(ticket, draft);
        if (draft.priority() != ticket.getPriority()) {
            SlaTargets targets = policies.targets(draft.priority());
            ticket.reprioritize(draft.priority(), SlaClock.firstResponseDue(ticket.getCreatedAt(), targets),
                    SlaClock.resolutionDue(ticket.getResolutionClockStartedAt(), ticket.getPausedSeconds(), targets));
        }
        tickets.flush();
        audit.record(AuditEntry.of("TicketUpdated", "Ticket", id).withBefore(before).withAfter(snapshot(ticket)));
        return view(ticket);
    }

    @Transactional
    public TicketView assign(UUID id, UUID assigneeId, Long version) {
        Ticket ticket = find(id);
        if (version == null) {
            throw ApiProblem.badRequestField("version", "Reload the record and try again.");
        }
        UUID assignee = assigneeId == null ? null : activeMember(assigneeId, "assigneeId");
        checkVersion(ticket, version);
        requireNotClosed(ticket);
        UUID previous = ticket.getAssigneeId();
        ticket.assign(assignee, Instant.now());
        tickets.flush();
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("assigneeId", assignee == null ? null : assignee.toString());
        after.put("status", ticket.getStatus().name());
        audit.record(AuditEntry.of("TicketAssigned", "Ticket", id)
                .withBefore(Collections.singletonMap("assigneeId", previous == null ? null : previous.toString()))
                .withAfter(after));
        notifyAssignee(ticket, previous);
        return view(ticket);
    }

    @Transactional
    public TicketView changeStatus(UUID id, TicketStatus target, String rawNote, Long version) {
        if (target == null) {
            throw ApiProblem.badRequestField("status", "Choose a status.");
        }
        String note = Text.optional(rawNote, 2000, "note");
        if (target == TicketStatus.RESOLVED && note == null) {
            throw ApiProblem.badRequestField("note", NOTE_NEEDED);
        }
        Ticket ticket = find(id);
        checkVersion(ticket, version);
        requireNotClosed(ticket);
        TicketStatus from = ticket.getStatus();
        if (!from.canMoveTo(target)) {
            throw ApiProblem.conflict(NOT_ALLOWED);
        }
        moveTo(ticket, target, note, Instant.now());
        tickets.flush();
        audit.record(AuditEntry.of("TicketStatusChanged", "Ticket", id)
                .withBefore(Map.of("status", from.name())).withAfter(Map.of("status", target.name())));
        return view(ticket);
    }

    /** Active members for the assignee picker. */
    @Transactional(readOnly = true)
    public List<AgentView> agents(String q) {
        return members.searchActive(q, 20).stream().map(m -> new AgentView(m.id(), m.name(), m.email())).toList();
    }

    /** The workflow's side effects on the SLA clock (D6, D8). Validation is the caller's. */
    void moveTo(Ticket ticket, TicketStatus target, String note, Instant now) {
        SlaTargets targets = policies.targets(ticket.getPriority());
        if (ticket.getStatus() == TicketStatus.PENDING) {
            long paused = SlaClock.pausedSecondsAfterResume(ticket.getPausedSeconds(), ticket.getPausedAt(), now);
            ticket.resume(paused, SlaClock.resolutionDue(ticket.getResolutionClockStartedAt(), paused, targets), now);
        }
        switch (target) {
            case PENDING -> ticket.pause(now);
            case RESOLVED -> ticket.resolve(note, now);
            case CLOSED -> ticket.close(now);
            case OPEN -> {
                if (ticket.getStatus() == TicketStatus.RESOLVED) {
                    ticket.reopen(SlaClock.resolutionDue(now, 0, targets), now);
                } else {
                    ticket.open(now);
                }
            }
            case NEW -> throw new IllegalStateException("No ticket moves back to NEW");
        }
    }

    Ticket find(UUID id) {
        TenantContext.requireTenantId();
        return tickets.findById(id).orElseThrow(() -> ApiProblem.notFound(NOT_FOUND));
    }

    static void checkVersion(Ticket ticket, Long version) {
        if (version == null) {
            throw ApiProblem.badRequestField("version", "Reload the record and try again.");
        }
        if (ticket.getVersion() != version) {
            throw ApiProblem.conflict(STALE);
        }
    }

    static void requireNotClosed(Ticket ticket) {
        if (ticket.getStatus() == TicketStatus.CLOSED) {
            throw ApiProblem.conflict(CLOSED);
        }
    }

    /**
     * Body checks, then references that need no permission (category, linked type), then those that may answer 403
     * (product, linked record, requester — last), then archived checks for new or changed references (R3/R4 order).
     */
    private Draft validate(TicketCommand c, Ticket current) {
        String subject = Text.required(c.subject(), 200, "subject").replaceAll("\\s+", " ");
        String description = Text.required(c.description(), 10_000, "description");
        Priority priority = c.priority() == null ? Priority.NORMAL : c.priority();
        Channel channel = c.channel() == null ? Channel.PHONE : c.channel();
        if (c.requesterId() == null) {
            throw ApiProblem.badRequestField("requesterId", UNKNOWN_PARTY);
        }
        if ((c.linkedType() == null) != (c.linkedId() == null)) {
            throw ApiProblem.badRequestField(c.linkedType() == null ? "linkedType" : "linkedId", "Choose a record.");
        }
        if (c.linkedType() != null && (!subjects.isKnownType(c.linkedType()) || "TICKET".equals(c.linkedType()))) {
            throw ApiProblem.badRequestField("linkedType", "Unknown record type.");
        }
        TicketCategory category = c.categoryId() == null ? null : categories.resolve(c.categoryId(), "categoryId");
        UUID explicitAssignee = c.assigneeId() != null && current == null ? activeMember(c.assigneeId(), "assigneeId") : null;
        ProductBrief product = null;
        if (c.productId() != null) {
            if (!CurrentAuthorities.has(CatalogPermissions.PRODUCT_READ)) {
                throw ApiProblem.forbidden(FORBIDDEN);
            }
            product = products.briefs(List.of(c.productId())).get(c.productId());
            if (product == null) {
                throw ApiProblem.badRequestField("productId", "Choose a product in this workspace.");
            }
        }
        if (c.linkedType() != null) {
            if (!subjects.canRead(c.linkedType())) {
                throw ApiProblem.forbidden(FORBIDDEN);
            }
            SubjectRef linked = subjects.labels(c.linkedType(), List.of(c.linkedId())).get(c.linkedId());
            if (linked == null) {
                throw ApiProblem.badRequestField("linkedId", "Choose a record in this workspace.");
            }
        }
        boolean requesterChanged = current == null || !c.requesterId().equals(current.getRequesterId());
        PartyBrief requester = null;
        if (requesterChanged) {
            if (!CurrentAuthorities.has(DirectoryPermissions.PARTY_READ)) {
                throw ApiProblem.forbidden(FORBIDDEN);
            }
            requester = parties.briefs(List.of(c.requesterId())).get(c.requesterId());
            if (requester == null) {
                throw ApiProblem.badRequestField("requesterId", UNKNOWN_PARTY);
            }
        }
        if (category != null && (current == null || !category.getId().equals(current.getCategoryId()))) {
            CategoryService.requireNotArchived(category);
        }
        if (product != null && product.archived() && (current == null || !product.id().equals(current.getProductId()))) {
            throw ApiProblem.conflict(ARCHIVED);
        }
        if (requester != null && requester.archived()) {
            throw ApiProblem.conflict(ARCHIVED);
        }
        return new Draft(subject, description, c.requesterId(), c.productId(), c.linkedType(), c.linkedId(),
                c.categoryId(), priority, channel,
                explicitAssignee != null ? explicitAssignee : category == null ? null : category.getDefaultAssigneeId());
    }

    private void apply(Ticket ticket, Draft d) {
        ticket.applyDetails(d.subject(), d.description(), d.requesterId(), d.productId(), d.linkedType(), d.linkedId(),
                d.categoryId(), d.channel());
    }

    private UUID activeMember(UUID id, String field) {
        return members.findActive(id).map(Members.Member::id)
                .orElseThrow(() -> ApiProblem.badRequestField(field, NOT_A_MEMBER));
    }

    /** Emails a new assignee (not the person who assigned themselves), after commit via MailRequested. */
    private void notifyAssignee(Ticket ticket, UUID previous) {
        UUID assignee = ticket.getAssigneeId();
        UUID actor = TenantContext.userId().orElse(null);
        if (assignee == null || assignee.equals(previous) || assignee.equals(actor)) {
            return;
        }
        Map<UUID, Members.Member> people = members.findAll(actor == null ? List.of(assignee) : List.of(assignee, actor));
        Members.Member to = people.get(assignee);
        if (to == null) {
            return;
        }
        Members.Member by = actor == null ? null : people.get(actor);
        events.publishEvent(new MailRequested(new OutgoingMail(to.email(),
                "You've been assigned " + ticket.getNumber() + ": " + ticket.getSubject(), """
                Hello %s,

                %s assigned you a ticket in %s:

                  %s · %s
                  Priority: %s
                  First response due: %s

                Open it: %s/app/helpdesk/tickets/%s
                """.formatted(to.name(), by == null ? "A teammate" : by.name(), tenants.current().name(),
                ticket.getNumber(), ticket.getSubject(), ticket.getPriority().name(),
                ticket.getFirstResponseDueAt(), appBaseUrl, ticket.getId()))));
    }

    SlaView sla(Ticket t, Instant now) {
        SlaTargets targets = policies.targets(t.getPriority());
        boolean paused = t.getStatus() == TicketStatus.PENDING;
        return new SlaView(t.getFirstResponseDueAt(), t.getFirstRespondedAt(),
                SlaClock.state(t.getFirstResponseDueAt(), t.getFirstRespondedAt(), now, targets.firstResponseMinutes(),
                        null),
                t.getResolutionDueAt(), t.getResolvedAt(),
                SlaClock.state(t.getResolutionDueAt(), t.getResolvedAt(), now, targets.resolutionMinutes(),
                        paused ? t.getPausedAt() : null),
                t.getPausedAt());
    }

    TicketView view(Ticket t) {
        Instant now = Instant.now();
        PartyRef requester = requesters(List.of(t.getRequesterId())).get(t.getRequesterId());
        TicketProductRef product = null;
        if (t.getProductId() != null) {
            ProductBrief p = products.briefs(List.of(t.getProductId())).get(t.getProductId());
            product = p == null ? null : new TicketProductRef(p.id(), p.sku(), p.name());
        }
        LinkedRecord linked = null;
        if (t.getLinkedType() != null) {
            SubjectRef ref = subjects.labels(t.getLinkedType(), List.of(t.getLinkedId())).get(t.getLinkedId());
            linked = new LinkedRecord(t.getLinkedType(), t.getLinkedId(), ref == null ? null : ref.label());
        }
        TicketCategory category = t.getCategoryId() == null ? null
                : categories.byIds(List.of(t.getCategoryId())).get(t.getCategoryId());
        Map<UUID, MemberRef> people = memberRefs(new HashSet<>(Arrays.asList(t.getAssigneeId(), t.getCreatedBy())));
        return new TicketView(t.getId(), t.getNumber(), t.getSubject(), t.getDescription(), requester, product, linked,
                CategoryService.ref(category), t.getPriority(), t.getChannel(), people.get(t.getAssigneeId()),
                t.getStatus(), sla(t, now), t.getResolutionNote(), t.getReopenCount(), t.getClosedAt(),
                people.get(t.getCreatedBy()), t.getCreatedAt(), t.getUpdatedAt(), t.getVersion());
    }

    List<TicketSummary> summaries(List<Ticket> list) {
        Instant now = Instant.now();
        Map<UUID, PartyRef> requesters = requesters(list.stream().map(Ticket::getRequesterId).collect(Collectors.toSet()));
        Map<UUID, TicketCategory> cats = categories.byIds(list.stream().map(Ticket::getCategoryId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<UUID, MemberRef> people = memberRefs(list.stream().map(Ticket::getAssigneeId).collect(Collectors.toSet()));
        return list.stream().map(t -> new TicketSummary(t.getId(), t.getNumber(), t.getSubject(),
                requesters.get(t.getRequesterId()), CategoryService.ref(cats.get(t.getCategoryId())), t.getPriority(),
                t.getStatus(), people.get(t.getAssigneeId()), sla(t, now), t.getCreatedAt(), t.getUpdatedAt())).toList();
    }

    /** Names of requesters (shown to anyone who may read the ticket, like an opportunity's account). */
    Map<UUID, PartyRef> requesters(Collection<UUID> ids) {
        return parties.briefs(ids).values().stream()
                .collect(Collectors.toMap(PartyBrief::id, p -> new PartyRef(p.id(), p.name())));
    }

    private Map<UUID, MemberRef> memberRefs(Set<UUID> ids) {
        Set<UUID> present = new HashSet<>(ids);
        present.remove(null);
        return members.findAll(present).values().stream()
                .collect(Collectors.toMap(Members.Member::id, m -> new MemberRef(m.id(), m.name())));
    }

    private static Map<String, Object> snapshot(Ticket t) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("number", t.getNumber());
        values.put("subject", t.getSubject());
        values.put("requesterId", t.getRequesterId().toString());
        values.put("productId", t.getProductId() == null ? null : t.getProductId().toString());
        values.put("linkedType", t.getLinkedType());
        values.put("linkedId", t.getLinkedId() == null ? null : t.getLinkedId().toString());
        values.put("categoryId", t.getCategoryId() == null ? null : t.getCategoryId().toString());
        values.put("priority", t.getPriority().name());
        values.put("channel", t.getChannel().name());
        values.put("status", t.getStatus().name());
        return values;
    }
}
```

(`applyDetails` doesn't touch the priority, so `update` can compare `draft.priority()` with the ticket's priority after applying the details. `CatalogPermissions.PRODUCT_READ` is `catalog.product.read`.)

`helpdesk/TicketSubjects.java`:

```java
package com.nexusops.helpdesk;

import com.nexusops.collaboration.SearchHit;
import com.nexusops.collaboration.SubjectRef;
import com.nexusops.collaboration.SubjectResolver;
import com.nexusops.helpdesk.domain.Ticket;
import com.nexusops.helpdesk.domain.TicketRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/** Tickets as collaboration subjects (type TICKET, D11): a closed ticket counts as archived. */
@Component
class TicketSubjects implements SubjectResolver {

    static final String TYPE = "TICKET";

    private final TicketRepository tickets;
    private final TicketService service;

    TicketSubjects(TicketRepository tickets, TicketService service) {
        this.tickets = tickets;
        this.service = service;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String readPermission() {
        return HelpDeskPermissions.TICKET_READ;
    }

    @Override
    public Optional<SubjectRef> find(UUID id) {
        return tickets.findById(id).map(TicketSubjects::ref);
    }

    @Override
    public Map<UUID, SubjectRef> findAll(Collection<UUID> ids) {
        return tickets.findAllById(ids).stream().collect(Collectors.toMap(Ticket::getId, TicketSubjects::ref));
    }

    @Override
    public List<SearchHit> search(String pattern, int limit) {
        Specification<Ticket> spec = (root, cq, cb) -> cb.or(cb.like(cb.lower(root.get("number")), pattern, '\\'),
                cb.like(cb.lower(root.get("subject")), pattern, '\\'));
        List<Ticket> found = tickets.findAll(spec,
                PageRequest.of(0, limit, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id")))).getContent();
        var names = service.requesters(found.stream().map(Ticket::getRequesterId).collect(Collectors.toSet()));
        return found.stream().map(t -> new SearchHit(TYPE, t.getId(), label(t),
                names.containsKey(t.getRequesterId()) ? names.get(t.getRequesterId()).name() : null,
                t.getStatus() == TicketStatus.CLOSED)).toList();
    }

    private static SubjectRef ref(Ticket t) {
        return new SubjectRef(TYPE, t.getId(), label(t), t.getStatus() == TicketStatus.CLOSED);
    }

    static String label(Ticket t) {
        return t.getNumber() + " · " + t.getSubject();
    }
}
```

- [ ] **Step 9: Web layer**

Add to `HelpDeskDtos`:

```java
    record TicketRequest(String subject, String description, UUID requesterId, UUID productId, String linkedType,
            UUID linkedId, UUID categoryId, Priority priority, Channel channel, UUID assigneeId, Long version) {
        TicketCommand command() {
            return new TicketCommand(subject, description, requesterId, productId, linkedType, linkedId, categoryId,
                    priority, channel, assigneeId);
        }
    }

    record AssignRequest(UUID assigneeId, Long version) {}

    record StatusRequest(TicketStatus status, String note, Long version) {}
```

(imports `com.nexusops.helpdesk.Channel`, `Priority`, `TicketCommand`, `TicketStatus`.)

`helpdesk/web/TicketController.java`:

```java
package com.nexusops.helpdesk.web;

import com.nexusops.helpdesk.AgentView;
import com.nexusops.helpdesk.Priority;
import com.nexusops.helpdesk.TicketQuery;
import com.nexusops.helpdesk.TicketService;
import com.nexusops.helpdesk.TicketStatus;
import com.nexusops.helpdesk.TicketSummary;
import com.nexusops.helpdesk.TicketView;
import com.nexusops.helpdesk.web.HelpDeskDtos.AssignRequest;
import com.nexusops.helpdesk.web.HelpDeskDtos.StatusRequest;
import com.nexusops.helpdesk.web.HelpDeskDtos.TicketRequest;
import com.nexusops.shared.web.PageResponse;
import java.util.List;
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
@RequestMapping("/api/v1/helpdesk")
class TicketController {

    private final TicketService tickets;

    TicketController(TicketService tickets) {
        this.tickets = tickets;
    }

    @GetMapping("/tickets")
    @PreAuthorize("hasAuthority('helpdesk.ticket.read')")
    PageResponse<TicketSummary> list(@RequestParam(required = false) String q,
            @RequestParam(required = false) List<TicketStatus> status, @RequestParam(required = false) Priority priority,
            @RequestParam(required = false) String assignee, @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) UUID requesterId, @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) String sla, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return tickets.list(new TicketQuery(q, status, priority, assignee, categoryId, requesterId, productId, sla),
                page, size);
    }

    @GetMapping("/tickets/{id}")
    @PreAuthorize("hasAuthority('helpdesk.ticket.read')")
    TicketView get(@PathVariable UUID id) {
        return tickets.get(id);
    }

    @PostMapping("/tickets")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('helpdesk.ticket.manage')")
    TicketView create(@RequestBody TicketRequest request) {
        return tickets.create(request.command());
    }

    @PutMapping("/tickets/{id}")
    @PreAuthorize("hasAuthority('helpdesk.ticket.manage')")
    TicketView update(@PathVariable UUID id, @RequestBody TicketRequest request) {
        return tickets.update(id, request.command(), request.version());
    }

    @PostMapping("/tickets/{id}/assign")
    @PreAuthorize("hasAuthority('helpdesk.ticket.assign')")
    TicketView assign(@PathVariable UUID id, @RequestBody AssignRequest request) {
        return tickets.assign(id, request.assigneeId(), request.version());
    }

    @PostMapping("/tickets/{id}/status")
    @PreAuthorize("hasAuthority('helpdesk.ticket.resolve')")
    TicketView status(@PathVariable UUID id, @RequestBody StatusRequest request) {
        return tickets.changeStatus(id, request.status(), request.note(), request.version());
    }

    @GetMapping("/agents")
    @PreAuthorize("hasAuthority('helpdesk.ticket.read')")
    List<AgentView> agents(@RequestParam(required = false) String q) {
        return tickets.agents(q);
    }
}
```

A JSON body with an unknown enum (`"priority":"CRITICAL"`) fails deserialization; `GlobalExceptionHandler` extends `ResponseEntityExceptionHandler`, which answers 400.

- [ ] **Step 10: Run the tests to verify they pass**

Run: `cd backend && ./gradlew test --tests '*TicketApiIT' --tests '*SlaClockTest' --tests '*TicketStatusTest' --tests '*ModularityTest' --tests '*EndpointAuthorizationCoverageTest' --tests '*RlsCoverageIT'`
Expected: PASS.

- [ ] **Step 11: Full suite, then commit**

Run: `cd backend && ./gradlew test` — Expected: PASS.

```bash
git add backend/src
git commit -m "feat(helpdesk): tickets with context, routing by category, assignment emails and an SLA-aware workflow"
```

---

### Task 4: The conversation — public replies (emailed), internal notes and customer messages

**Files:**
- Create: `backend/src/main/resources/db/migration/V26__ticket_messages.sql`
- Create: `backend/src/main/java/com/nexusops/helpdesk/MessageKind.java`, `MessageCommand.java`, `MessageView.java`, `MessagePosted.java`, `TicketMessageService.java`
- Create: `backend/src/main/java/com/nexusops/helpdesk/domain/TicketMessage.java`, `TicketMessageRepository.java`
- Modify: `backend/src/main/java/com/nexusops/helpdesk/domain/TicketRepository.java` (`findForUpdate`), `web/TicketController.java`, `web/HelpDeskDtos.java`, `backend/src/test/java/com/nexusops/RlsCoverageIT.java` (`ticket_messages`)
- Test: `backend/src/test/java/com/nexusops/helpdesk/TicketMessageApiIT.java`

**Interfaces:**
- Consumes (Task 3): `TicketService.find/view/moveTo/checkVersion/requireNotClosed`, `Ticket.recordFirstResponse/touch/getStatus/getRequesterId/getNumber/getSubject`, `TicketView`, `PartyService.get(UUID) : PartyView` (has `email()`), `Members`, `MailRequested`/`OutgoingMail(to, subject, textBody)`, `TenantDirectory.current().name()`.
- Produces:
  - `enum MessageKind {PUBLIC_REPLY, INTERNAL_NOTE, CUSTOMER_MESSAGE}`.
  - `MessageCommand(MessageKind kind, String body)`; `MessageView(UUID id, MessageKind kind, String body, MemberRef author, String emailedTo, Instant createdAt)`; `MessagePosted(MessageView message, TicketView ticket)`.
  - `TicketMessageService` public `list(UUID ticketId)` (oldest first), `post(UUID ticketId, MessageCommand)`.
  - `TicketRepository.findForUpdate(UUID)` (PESSIMISTIC_WRITE): message posts on one ticket serialize instead of failing each other with 409.
  - Routes: `GET /api/v1/helpdesk/tickets/{id}/messages` (`helpdesk.ticket.read`), `POST /api/v1/helpdesk/tickets/{id}/messages` (`helpdesk.ticket.manage`) → 201 `MessagePosted`.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/nexusops/helpdesk/TicketMessageApiIT.java`:

```java
package com.nexusops.helpdesk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestHelpDesk;
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
import org.springframework.test.web.servlet.ResultActions;

@AutoConfigureMockMvc
class TicketMessageApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;
    String customerEmail;
    UUID customer;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("msg"));
        owner = Api.login(mvc, ws);
        TestHelpDesk.enable(owner);
        customerEmail = "meera-" + ws.slug() + "@sahyadri.test";
        customer = Api.id(owner.post("/api/v1/persons", "{\"firstName\":\"Meera\",\"lastName\":\"Iyer\",\"email\":\""
                + customerEmail + "\"}"));
    }

    private UUID ticket(UUID requester) throws Exception {
        return Api.id(owner.post("/api/v1/helpdesk/tickets", "{\"subject\":\"Printer not printing\","
                + "\"description\":\"Paper jams\",\"requesterId\":\"" + requester + "\"}").andExpect(status().isCreated()));
    }

    private ResultActions post(UUID ticket, String kind, String body) throws Exception {
        return owner.post("/api/v1/helpdesk/tickets/" + ticket + "/messages",
                "{\"kind\":\"" + kind + "\",\"body\":\"" + body + "\"}");
    }

    private long audits(String action) {
        return OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select count(*) from audit_events where action = ?", Long.class, action);
    }

    @Test
    void aPublicReplyEmailsTheRequesterAndIsTheFirstResponse() throws Exception {
        UUID id = ticket(customer);
        post(id, "PUBLIC_REPLY", "Please switch it off and on again.").andExpect(status().isCreated())
                .andExpect(jsonPath("$.message.kind").value("PUBLIC_REPLY"))
                .andExpect(jsonPath("$.message.emailedTo").value(customerEmail))
                .andExpect(jsonPath("$.message.author.name").exists())
                .andExpect(jsonPath("$.ticket.status").value("OPEN"))
                .andExpect(jsonPath("$.ticket.sla.firstRespondedAt").exists())
                .andExpect(jsonPath("$.ticket.sla.firstResponseState").value("MET"));
        assertThat(mail.sentTo(customerEmail)).singleElement().satisfies(m -> {
            assertThat(m.subject()).isEqualTo("[T-00001] Printer not printing");
            assertThat(m.textBody()).contains("Please switch it off and on again.");
        });
        String firstAt = Api.read(owner.get("/api/v1/helpdesk/tickets/" + id), "$.sla.firstRespondedAt");
        post(id, "PUBLIC_REPLY", "Any luck?").andExpect(status().isCreated())
                .andExpect(jsonPath("$.ticket.sla.firstRespondedAt").value(firstAt));
        assertThat(audits("TicketReplied")).isEqualTo(2);
    }

    @Test
    void internalNotesAreNotEmailedAndDontCountAsAResponse() throws Exception {
        UUID id = ticket(customer);
        post(id, "INTERNAL_NOTE", "Customer is on the old firmware").andExpect(status().isCreated())
                .andExpect(jsonPath("$.message.emailedTo").doesNotExist())
                .andExpect(jsonPath("$.ticket.status").value("NEW"))
                .andExpect(jsonPath("$.ticket.sla.firstRespondedAt").doesNotExist());
        assertThat(mail.sentTo(customerEmail)).isEmpty();
        assertThat(audits("TicketNoteAdded")).isEqualTo(1);
    }

    @Test
    void aCustomerMessageResumesAPendingTicket() throws Exception {
        UUID id = ticket(customer);
        owner.post("/api/v1/helpdesk/tickets/" + id + "/status", "{\"status\":\"PENDING\",\"version\":0}")
                .andExpect(status().isOk());
        post(id, "CUSTOMER_MESSAGE", "It works after the update, but it is slow").andExpect(status().isCreated())
                .andExpect(jsonPath("$.message.kind").value("CUSTOMER_MESSAGE"))
                .andExpect(jsonPath("$.ticket.status").value("OPEN"))
                .andExpect(jsonPath("$.ticket.sla.pausedAt").doesNotExist())
                .andExpect(jsonPath("$.ticket.sla.firstRespondedAt").doesNotExist());
        assertThat(mail.sentTo(customerEmail)).isEmpty();
        assertThat(audits("TicketCustomerMessage")).isEqualTo(1);
    }

    @Test
    void aReplyToARequesterWithoutEmailIsStillRecorded() throws Exception {
        UUID shop = Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Walk-in Traders\"}"));
        UUID id = ticket(shop);
        post(id, "PUBLIC_REPLY", "Bring it to the counter on Monday").andExpect(status().isCreated())
                .andExpect(jsonPath("$.message.emailedTo").doesNotExist())
                .andExpect(jsonPath("$.ticket.sla.firstRespondedAt").exists())
                .andExpect(jsonPath("$.ticket.status").value("OPEN"));
    }

    @Test
    void anOrganisationsOwnEmailReceivesTheReply() throws Exception {
        UUID shop = Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Deccan Retail\",\"email\":\"care-"
                + ws.slug() + "@deccan.test\"}"));
        UUID id = ticket(shop);
        post(id, "PUBLIC_REPLY", "Replacement shipped").andExpect(jsonPath("$.message.emailedTo")
                .value("care-" + ws.slug() + "@deccan.test"));
    }

    @Test
    void theConversationReadsOldestFirstAndRefusesBadInput() throws Exception {
        UUID id = ticket(customer);
        post(id, "INTERNAL_NOTE", "First").andExpect(status().isCreated());
        post(id, "PUBLIC_REPLY", "Second").andExpect(status().isCreated());
        owner.get("/api/v1/helpdesk/tickets/" + id + "/messages")
                .andExpect(jsonPath("$[*].body").value(Matchers.contains("First", "Second")));
        post(id, "PUBLIC_REPLY", "").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("body"));
        owner.post("/api/v1/helpdesk/tickets/" + id + "/messages", "{\"body\":\"x\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("kind"));
        owner.post("/api/v1/helpdesk/tickets/" + UUID.randomUUID() + "/messages",
                "{\"kind\":\"PUBLIC_REPLY\",\"body\":\"x\"}").andExpect(status().isNotFound());
    }

    @Test
    void aClosedTicketTakesNoMessagesAndReadersCantWrite() throws Exception {
        UUID id = ticket(customer);
        owner.post("/api/v1/helpdesk/tickets/" + id + "/status", "{\"status\":\"RESOLVED\",\"note\":\"x\",\"version\":0}");
        owner.post("/api/v1/helpdesk/tickets/" + id + "/status", "{\"status\":\"CLOSED\",\"version\":1}");
        post(id, "PUBLIC_REPLY", "Late").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("A closed ticket can't be changed."));
        UUID readerRole = TestRoles.create(mvc, owner.session(), "Viewer", "helpdesk.ticket.read");
        Api reader = Api.login(mvc, members.create(ws.tenantId(), Set.of(readerRole)));
        reader.get("/api/v1/helpdesk/tickets/" + id + "/messages").andExpect(status().isOk());
        reader.post("/api/v1/helpdesk/tickets/" + id + "/messages", "{\"kind\":\"INTERNAL_NOTE\",\"body\":\"x\"}")
                .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && ./gradlew test --tests '*TicketMessageApiIT'`
Expected: FAIL — 404/405 on `/messages`.

- [ ] **Step 3: Migration V26**

`backend/src/main/resources/db/migration/V26__ticket_messages.sql`:

```sql
-- The ticket conversation (D9). Append-only for the app role, like stock_movements.
CREATE TABLE ticket_messages (
    id          uuid PRIMARY KEY,
    tenant_id   uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    ticket_id   uuid NOT NULL,
    kind        text NOT NULL CHECK (kind IN ('PUBLIC_REPLY', 'INTERNAL_NOTE', 'CUSTOMER_MESSAGE')),
    body        text NOT NULL CHECK (length(body) BETWEEN 1 AND 10000),
    author_id   uuid REFERENCES users (id) ON DELETE SET NULL,
    emailed_to  text CHECK (emailed_to IS NULL OR length(emailed_to) <= 320),
    created_at  timestamptz NOT NULL,
    UNIQUE (tenant_id, id),
    CHECK (kind = 'PUBLIC_REPLY' OR emailed_to IS NULL),
    FOREIGN KEY (tenant_id, ticket_id) REFERENCES tickets (tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX ticket_messages_ticket_idx ON ticket_messages (tenant_id, ticket_id, created_at);

ALTER TABLE ticket_messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE ticket_messages FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ticket_messages
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

REVOKE UPDATE, DELETE ON ticket_messages FROM nexusops_app;
```

Add `"ticket_messages"` to `RlsCoverageIT.EXPECTED_TENANT_TABLES`.

- [ ] **Step 4: Types and domain**

```java
package com.nexusops.helpdesk;

/** D9: what a message is and who sees it. Only PUBLIC_REPLY is emailed. */
public enum MessageKind {
    PUBLIC_REPLY, INTERNAL_NOTE, CUSTOMER_MESSAGE
}
```

```java
package com.nexusops.helpdesk;

public record MessageCommand(MessageKind kind, String body) {}
```

```java
package com.nexusops.helpdesk;

import com.nexusops.collaboration.MemberRef;
import java.time.Instant;
import java.util.UUID;

/** {@code emailedTo} is the address a public reply went to, null when none was sent. */
public record MessageView(UUID id, MessageKind kind, String body, MemberRef author, String emailedTo,
        Instant createdAt) {}
```

```java
package com.nexusops.helpdesk;

/** A posted message and the ticket as it is afterwards (status and SLA may have moved). */
public record MessagePosted(MessageView message, TicketView ticket) {}
```

`helpdesk/domain/TicketMessage.java`:

```java
package com.nexusops.helpdesk.domain;

import com.nexusops.helpdesk.MessageKind;
import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "ticket_messages")
public class TicketMessage extends TenantOwnedEntity {

    @Column(name = "ticket_id", nullable = false, updatable = false)
    private UUID ticketId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private MessageKind kind;

    @Column(nullable = false, updatable = false)
    private String body;

    @Column(name = "author_id", updatable = false)
    private UUID authorId;

    @Column(name = "emailed_to", updatable = false)
    private String emailedTo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TicketMessage() {}

    public TicketMessage(UUID id, UUID ticketId, MessageKind kind, String body, UUID authorId, String emailedTo,
            Instant createdAt) {
        super(id);
        this.ticketId = ticketId;
        this.kind = kind;
        this.body = body;
        this.authorId = authorId;
        this.emailedTo = emailedTo;
        this.createdAt = createdAt;
    }

    public UUID getTicketId() {
        return ticketId;
    }

    public MessageKind getKind() {
        return kind;
    }

    public String getBody() {
        return body;
    }

    public UUID getAuthorId() {
        return authorId;
    }

    public String getEmailedTo() {
        return emailedTo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
```

`helpdesk/domain/TicketMessageRepository.java`:

```java
package com.nexusops.helpdesk.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketMessageRepository extends JpaRepository<TicketMessage, UUID> {

    List<TicketMessage> findByTicketIdOrderByCreatedAtAscIdAsc(UUID ticketId);
}
```

In `TicketRepository` add:

```java
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Ticket t where t.id = :id")
    Optional<Ticket> findForUpdate(@Param("id") UUID id);
```

(imports `jakarta.persistence.LockModeType`, `java.util.Optional`, `org.springframework.data.jpa.repository.Lock`, `Query`, `org.springframework.data.repository.query.Param`.)

- [ ] **Step 5: The service**

`helpdesk/TicketMessageService.java`:

```java
package com.nexusops.helpdesk;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.collaboration.MemberRef;
import com.nexusops.directory.PartyService;
import com.nexusops.directory.PartyView;
import com.nexusops.helpdesk.domain.Ticket;
import com.nexusops.helpdesk.domain.TicketMessage;
import com.nexusops.helpdesk.domain.TicketMessageRepository;
import com.nexusops.helpdesk.domain.TicketRepository;
import com.nexusops.identity.Members;
import com.nexusops.notifications.MailRequested;
import com.nexusops.notifications.OutgoingMail;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.Text;
import com.nexusops.shared.web.ApiProblem;
import com.nexusops.tenancy.TenantDirectory;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The conversation (D9). A public reply is the first response and is emailed to the requester; a customer message
 * logged by an agent resumes a PENDING ticket; an internal note changes nothing but the record. Posts on one ticket
 * serialize on its row lock, so concurrent agents don't fail each other.
 */
@Service
public class TicketMessageService {

    private final TicketRepository tickets;
    private final TicketMessageRepository messages;
    private final TicketService ticketService;
    private final PartyService parties;
    private final Members members;
    private final TenantDirectory tenants;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    TicketMessageService(TicketRepository tickets, TicketMessageRepository messages, TicketService ticketService,
            PartyService parties, Members members, TenantDirectory tenants, AuditService audit,
            ApplicationEventPublisher events) {
        this.tickets = tickets;
        this.messages = messages;
        this.ticketService = ticketService;
        this.parties = parties;
        this.members = members;
        this.tenants = tenants;
        this.audit = audit;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<MessageView> list(UUID ticketId) {
        ticketService.find(ticketId);
        List<TicketMessage> found = messages.findByTicketIdOrderByCreatedAtAscIdAsc(ticketId);
        Map<UUID, Members.Member> authors = members.findAll(found.stream().map(TicketMessage::getAuthorId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        return found.stream().map(m -> view(m, authors.get(m.getAuthorId()))).toList();
    }

    @Transactional
    public MessagePosted post(UUID ticketId, MessageCommand command) {
        if (command.kind() == null) {
            throw ApiProblem.badRequestField("kind", "Choose a reply, a note or a customer message.");
        }
        String body = Text.required(command.body(), 10_000, "body");
        TenantContext.requireTenantId();
        Ticket ticket = tickets.findForUpdate(ticketId).orElseThrow(() -> ApiProblem.notFound(TicketService.NOT_FOUND));
        TicketService.requireNotClosed(ticket);
        Instant now = Instant.now();
        String emailedTo = null;
        switch (command.kind()) {
            case PUBLIC_REPLY -> {
                ticket.recordFirstResponse(now);
                emailedTo = requesterEmail(ticket);
            }
            case CUSTOMER_MESSAGE -> {
                if (ticket.getStatus() == TicketStatus.PENDING) {
                    ticketService.moveTo(ticket, TicketStatus.OPEN, null, now);
                } else {
                    ticket.touch(now);
                }
            }
            case INTERNAL_NOTE -> ticket.touch(now);
        }
        tickets.flush();
        UUID author = TenantContext.userId().orElse(null);
        TicketMessage message = messages.saveAndFlush(new TicketMessage(Ids.newId(), ticket.getId(), command.kind(),
                body, author, emailedTo, now));
        audit.record(AuditEntry.of(switch (command.kind()) {
            case PUBLIC_REPLY -> "TicketReplied";
            case INTERNAL_NOTE -> "TicketNoteAdded";
            case CUSTOMER_MESSAGE -> "TicketCustomerMessage";
        }, "Ticket", ticket.getId()).withAfter(Map.of("messageId", message.getId().toString(),
                "emailed", emailedTo != null)));
        Members.Member writer = author == null ? null : members.findAll(List.of(author)).get(author);
        if (emailedTo != null) {
            sendReply(ticket, emailedTo, body, writer);
        }
        return new MessagePosted(view(message, writer), ticketService.view(ticket));
    }

    /** The person's email, or the organisation's; null when the requester has none (D9). */
    private String requesterEmail(Ticket ticket) {
        PartyView requester = parties.get(ticket.getRequesterId());
        String email = requester.email();
        return email == null || email.isBlank() ? null : email;
    }

    private void sendReply(Ticket ticket, String to, String body, Members.Member writer) {
        String workspace = tenants.current().name();
        events.publishEvent(new MailRequested(new OutgoingMail(to,
                "[" + ticket.getNumber() + "] " + ticket.getSubject(), """
                %s

                —
                %s, %s
                Reference: %s
                """.formatted(body, writer == null ? "The support team" : writer.name(), workspace,
                ticket.getNumber()))));
    }

    private static MessageView view(TicketMessage m, Members.Member author) {
        return new MessageView(m.getId(), m.getKind(), m.getBody(),
                author == null ? null : new MemberRef(author.id(), author.name()), m.getEmailedTo(), m.getCreatedAt());
    }
}
```

`ticket.recordFirstResponse(now)` already opens a NEW ticket (Task 3's `Ticket`); `moveTo` handles the clock when leaving PENDING. `PartyService.get` returns the party regardless of the caller's directory permission (service methods don't check permissions; controllers do) — the reply goes to whoever the ticket is for.

- [ ] **Step 6: Web layer**

Add to `HelpDeskDtos`:

```java
    record MessageRequest(MessageKind kind, String body) {
        MessageCommand command() {
            return new MessageCommand(kind, body);
        }
    }
```

In `TicketController` (constructor gains `TicketMessageService messages`):

```java
    @GetMapping("/tickets/{id}/messages")
    @PreAuthorize("hasAuthority('helpdesk.ticket.read')")
    List<MessageView> messages(@PathVariable UUID id) {
        return messages.list(id);
    }

    @PostMapping("/tickets/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('helpdesk.ticket.manage')")
    MessagePosted post(@PathVariable UUID id, @RequestBody MessageRequest request) {
        return messages.post(id, request.command());
    }
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `cd backend && ./gradlew test --tests '*TicketMessageApiIT' --tests '*TicketApiIT' --tests '*RlsCoverageIT' --tests '*EndpointAuthorizationCoverageTest'`
Expected: PASS.

- [ ] **Step 8: Full suite, then commit**

Run: `cd backend && ./gradlew test` — Expected: PASS.

```bash
git add backend/src
git commit -m "feat(helpdesk): the ticket conversation — emailed public replies, internal notes, customer messages"
```

---

### Task 5: Knowledge base, and the ticket's context (previous tickets, possible duplicates, suggested articles)

**Files:**
- Create: `backend/src/main/resources/db/migration/V27__kb_articles.sql`
- Create: `backend/src/main/java/com/nexusops/helpdesk/ArticleStatus.java`, `ArticleCommand.java`, `ArticleView.java`, `ArticleSummary.java`, `ArticleService.java`, `ArticleSubjects.java`, `FullTextTerms.java`, `HelpDeskSearch.java`, `TicketContext.java`
- Create: `backend/src/main/java/com/nexusops/helpdesk/domain/KbArticle.java`, `KbArticleRepository.java`
- Create: `backend/src/main/java/com/nexusops/helpdesk/web/ArticleController.java`; Modify: `web/TicketController.java` (context route), `web/HelpDeskDtos.java`, `TicketService.java` (`context`), `backend/src/test/java/com/nexusops/RlsCoverageIT.java` (`kb_articles`)
- Test: `backend/src/test/java/com/nexusops/helpdesk/FullTextTermsTest.java`, `ArticleApiIT.java`, `TicketContextIT.java`

**Interfaces:**
- Consumes (Tasks 1–4): `CategoryService.resolve/requireNotArchived/byIds/ref`, `TicketService.find/summaries`, `Ticket`, `TicketSummary`, `HelpDeskPermissions`, `Members`.
- Produces:
  - `enum ArticleStatus {DRAFT, PUBLISHED, ARCHIVED}`.
  - `ArticleCommand(String title, String body, UUID categoryId)`; `ArticleView(UUID id, String title, String body, CategoryRef category, ArticleStatus status, MemberRef author, Instant publishedAt, Instant createdAt, Instant updatedAt, long version)`; `ArticleSummary(UUID id, String title, String excerpt, CategoryRef category, ArticleStatus status, Instant publishedAt, Instant updatedAt)`.
  - `ArticleService` public `list(String q, ArticleStatus status, UUID categoryId, Integer page, Integer size)`, `get(UUID)`, `create`, `update(UUID, ArticleCommand, Long)`, `publish(UUID, Long)`, `unpublish(UUID, Long)`, `archive(UUID, Long)`; package-private `List<ArticleSummary> summaries(List<UUID> idsInOrder)`.
  - `FullTextTerms.orQuery(String text, int maxTerms) : String` ("" when no usable word).
  - `HelpDeskSearch` (package-private `@Component`): `List<UUID> previousTickets(UUID requesterId, UUID exceptId, int limit)`, `List<UUID> duplicateCandidates(Ticket, int limit)`, `List<UUID> suggestedArticles(String text, int limit)`, `ArticlePage articlePage(String q, List<ArticleStatus>, UUID categoryId, int limit, long offset)` → `record ArticlePage(List<UUID> ids, long total)`.
  - `TicketContext(List<TicketSummary> previousTickets, List<TicketSummary> possibleDuplicates, List<ArticleSummary> suggestedArticles)`; `TicketService.context(UUID) : TicketContext` (articles empty without `helpdesk.article.read`).
  - `ArticleSubjects` type `"KB_ARTICLE"` (label = title, archived = ARCHIVED, search by title).
  - Routes: `GET /api/v1/helpdesk/tickets/{id}/context`; `GET/POST /api/v1/helpdesk/articles`, `GET/PUT /api/v1/helpdesk/articles/{id}`, `POST …/{id}/publish|unpublish|archive {version}`.

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/com/nexusops/helpdesk/FullTextTermsTest.java`:

```java
package com.nexusops.helpdesk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FullTextTermsTest {

    @Test
    void turnsTextIntoOrEdLowercaseWords() {
        assertThat(FullTextTerms.orQuery("Printer NOT printing — paper jams", 10))
                .isEqualTo("printer | not | printing | paper | jams");
    }

    @Test
    void operatorsAndPunctuationNeverReachTheQuery() {
        assertThat(FullTextTerms.orQuery("can't print: error 0x80!! a & b | c <-> (d)", 10))
                .isEqualTo("can | print | error | 0x80");
        assertThat(FullTextTerms.orQuery("!!! & | ' :*", 10)).isEmpty();
        assertThat(FullTextTerms.orQuery(null, 10)).isEmpty();
    }

    @Test
    void keepsOtherScriptsWholeDropsDuplicatesAndCaps() {
        assertThat(FullTextTerms.orQuery("प्रिंटर काम नहीं कर रहा", 10)).isEqualTo("प्रिंटर | काम | नहीं | कर | रहा");
        assertThat(FullTextTerms.orQuery("jam jam JAM paper", 10)).isEqualTo("jam | paper");
        assertThat(FullTextTerms.orQuery("one two three four five", 3)).isEqualTo("one | two | three");
    }
}
```

`backend/src/test/java/com/nexusops/helpdesk/ArticleApiIT.java`:

```java
package com.nexusops.helpdesk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestHelpDesk;
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
class ArticleApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("kb"));
        owner = Api.login(mvc, ws);
        TestHelpDesk.enable(owner);
    }

    private UUID article(String title, String body) throws Exception {
        return Api.id(owner.post("/api/v1/helpdesk/articles", "{\"title\":\"" + title + "\",\"body\":\"" + body + "\"}")
                .andExpect(status().isCreated()));
    }

    private long audits(String action) {
        return OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select count(*) from audit_events where action = ?", Long.class, action);
    }

    @Test
    void writesPublishesUnpublishesAndArchives() throws Exception {
        UUID product = TestHelpDesk.category(owner, "Product issue");
        UUID id = Api.id(owner.post("/api/v1/helpdesk/articles", "{\"title\":\"Clearing a paper jam\","
                        + "\"body\":\"Open the rear tray.\\nPull the paper out slowly.\",\"categoryId\":\"" + product + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.category.name").value("Product issue"))
                .andExpect(jsonPath("$.author.name").exists())
                .andExpect(jsonPath("$.publishedAt").doesNotExist()));
        owner.post("/api/v1/helpdesk/articles/" + id + "/publish", "{\"version\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED")).andExpect(jsonPath("$.publishedAt").exists());
        owner.put("/api/v1/helpdesk/articles/" + id, "{\"title\":\"Clearing paper jams\",\"body\":\"Open the rear tray.\","
                + "\"version\":1}").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.category").doesNotExist());
        owner.post("/api/v1/helpdesk/articles/" + id + "/unpublish", "{\"version\":2}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));
        owner.post("/api/v1/helpdesk/articles/" + id + "/archive", "{\"version\":3}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
        owner.post("/api/v1/helpdesk/articles/" + id + "/publish", "{\"version\":4}").andExpect(status().isConflict());
        owner.put("/api/v1/helpdesk/articles/" + id, "{\"title\":\"x\",\"body\":\"y\",\"version\":4}")
                .andExpect(status().isConflict());
        assertThat(audits("ArticleCreated")).isEqualTo(1);
        assertThat(audits("ArticlePublished")).isEqualTo(1);
        assertThat(audits("ArticleUpdated")).isEqualTo(1);
        assertThat(audits("ArticleUnpublished")).isEqualTo(1);
        assertThat(audits("ArticleArchived")).isEqualTo(1);
    }

    @Test
    void invalidArticlesAreFieldErrors() throws Exception {
        String[][] cases = {
                {"{\"body\":\"x\"}", "title"},
                {"{\"title\":\"" + "x".repeat(201) + "\",\"body\":\"x\"}", "title"},
                {"{\"title\":\"x\"}", "body"},
                {"{\"title\":\"x\",\"body\":\"x\",\"categoryId\":\"" + UUID.randomUUID() + "\"}", "categoryId"},
        };
        for (String[] c : cases) {
            owner.post("/api/v1/helpdesk/articles", c[0]).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value(c[1]));
        }
    }

    @Test
    void searchesTitleAndBodyRankingTitlesFirst() throws Exception {
        UUID inBody = article("Toner care", "A paper jam can come from old toner.");
        UUID inTitle = article("Paper jam in the rear tray", "Open the tray.");
        UUID unrelated = article("Refund policy", "Refunds take five days.");
        for (UUID id : new UUID[] {inBody, inTitle, unrelated}) {
            owner.post("/api/v1/helpdesk/articles/" + id + "/publish", "{\"version\":0}").andExpect(status().isOk());
        }
        owner.get("/api/v1/helpdesk/articles?q=paper jam").andExpect(jsonPath("$.items[*].id")
                .value(Matchers.contains(inTitle.toString(), inBody.toString())));
        owner.get("/api/v1/helpdesk/articles?q=\"rear tray\" OR refunds").andExpect(jsonPath("$.total").value(2));
        owner.get("/api/v1/helpdesk/articles?q=!!! ' :*").andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        owner.get("/api/v1/helpdesk/articles").andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items[0].excerpt").exists());
    }

    @Test
    void readersSeeOnlyPublishedArticles() throws Exception {
        UUID draft = article("Internal draft", "Not ready");
        UUID published = article("How to reset", "Hold the button.");
        owner.post("/api/v1/helpdesk/articles/" + published + "/publish", "{\"version\":0}").andExpect(status().isOk());
        UUID readerRole = TestRoles.create(mvc, owner.session(), "KB reader", "helpdesk.article.read");
        Api reader = Api.login(mvc, members.create(ws.tenantId(), Set.of(readerRole)));
        reader.get("/api/v1/helpdesk/articles").andExpect(jsonPath("$.items[*].id")
                .value(Matchers.contains(published.toString())));
        reader.get("/api/v1/helpdesk/articles?status=DRAFT").andExpect(jsonPath("$.total").value(0));
        reader.get("/api/v1/helpdesk/articles/" + draft).andExpect(status().isNotFound());
        reader.get("/api/v1/helpdesk/articles/" + published).andExpect(status().isOk());
        reader.post("/api/v1/helpdesk/articles", "{\"title\":\"x\",\"body\":\"y\"}").andExpect(status().isForbidden());
        owner.get("/api/v1/helpdesk/articles?status=DRAFT").andExpect(jsonPath("$.items[*].id")
                .value(Matchers.contains(draft.toString())));
    }
}
```

`backend/src/test/java/com/nexusops/helpdesk/TicketContextIT.java`:

```java
package com.nexusops.helpdesk;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestHelpDesk;
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
class TicketContextIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;
    UUID meera, arjun;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("ctx"));
        owner = Api.login(mvc, ws);
        TestHelpDesk.enable(owner);
        meera = Api.id(owner.post("/api/v1/persons", "{\"firstName\":\"Meera\",\"lastName\":\"Iyer\"}"));
        arjun = Api.id(owner.post("/api/v1/persons", "{\"firstName\":\"Arjun\",\"lastName\":\"Patil\"}"));
    }

    private UUID ticket(UUID requester, String subject, String description) throws Exception {
        return Api.id(owner.post("/api/v1/helpdesk/tickets", "{\"subject\":\"" + subject + "\",\"description\":\""
                + description + "\",\"requesterId\":\"" + requester + "\"}").andExpect(status().isCreated()));
    }

    private UUID published(String title, String body) throws Exception {
        UUID id = Api.id(owner.post("/api/v1/helpdesk/articles", "{\"title\":\"" + title + "\",\"body\":\"" + body + "\"}"));
        owner.post("/api/v1/helpdesk/articles/" + id + "/publish", "{\"version\":0}").andExpect(status().isOk());
        return id;
    }

    @Test
    void showsTheRequestersOtherTicketsDuplicatesAndArticles() throws Exception {
        UUID older = ticket(meera, "Invoice has the wrong GST number", "Please correct it");
        UUID duplicate = ticket(meera, "Printer jams on every page", "Since yesterday");
        UUID otherCustomers = ticket(arjun, "Printer jams too", "Same here");
        UUID article = published("Clearing a paper jam", "Open the rear tray and remove the paper.");
        published("Refund policy", "Refunds take five days.");
        UUID current = ticket(meera, "Printer jams with paper", "Paper is stuck in the rear tray");
        owner.get("/api/v1/helpdesk/tickets/" + current + "/context").andExpect(status().isOk())
                .andExpect(jsonPath("$.previousTickets[*].id")
                        .value(Matchers.contains(duplicate.toString(), older.toString())))
                .andExpect(jsonPath("$.possibleDuplicates[*].id").value(Matchers.contains(duplicate.toString())))
                .andExpect(jsonPath("$.suggestedArticles[*].id").value(Matchers.contains(article.toString())));
        // a resolved ticket is history, not a duplicate
        owner.post("/api/v1/helpdesk/tickets/" + duplicate + "/status", "{\"status\":\"RESOLVED\",\"note\":\"x\",\"version\":0}")
                .andExpect(status().isOk());
        owner.get("/api/v1/helpdesk/tickets/" + current + "/context")
                .andExpect(jsonPath("$.possibleDuplicates").isEmpty())
                .andExpect(jsonPath("$.previousTickets[*].id", Matchers.not(Matchers.hasItem(otherCustomers.toString()))));
    }

    @Test
    void punctuationAndOtherScriptsDoNotBreakMatching() throws Exception {
        UUID article = published("Error 0x80 when printing", "Reinstall the driver.");
        UUID weird = ticket(meera, "can't print: error 0x80!! a & b | c <-> (d)", "':* !");
        owner.get("/api/v1/helpdesk/tickets/" + weird + "/context").andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestedArticles[*].id").value(Matchers.contains(article.toString())));
        UUID hindi = ticket(meera, "प्रिंटर काम नहीं कर रहा", "कागज़ फँस गया");
        owner.get("/api/v1/helpdesk/tickets/" + hindi + "/context").andExpect(status().isOk());
        UUID nothing = ticket(arjun, "!!!", "???");
        owner.get("/api/v1/helpdesk/tickets/" + nothing + "/context").andExpect(status().isOk())
                .andExpect(jsonPath("$.possibleDuplicates").isEmpty())
                .andExpect(jsonPath("$.suggestedArticles").isEmpty());
    }

    @Test
    void articlesAreLeftOutForAgentsWhoCantReadThem() throws Exception {
        published("Clearing a paper jam", "Open the rear tray.");
        UUID current = ticket(meera, "Paper jam", "Stuck");
        UUID agentRole = TestRoles.create(mvc, owner.session(), "Ticket reader", "helpdesk.ticket.read");
        Api agent = Api.login(mvc, members.create(ws.tenantId(), Set.of(agentRole)));
        agent.get("/api/v1/helpdesk/tickets/" + current + "/context").andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestedArticles").isEmpty());
        owner.get("/api/v1/helpdesk/tickets/" + UUID.randomUUID() + "/context").andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && ./gradlew test --tests '*FullTextTermsTest' --tests '*ArticleApiIT' --tests '*TicketContextIT'`
Expected: FAIL — `FullTextTerms` doesn't compile; the article and context routes are 404.

- [ ] **Step 3: Migration V27**

`backend/src/main/resources/db/migration/V27__kb_articles.sql`:

```sql
-- Knowledge base (D13). Titles weigh more than bodies in search.
CREATE TABLE kb_articles (
    id            uuid PRIMARY KEY,
    tenant_id     uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    title         text NOT NULL CHECK (title = btrim(title) AND length(title) BETWEEN 1 AND 200),
    body          text NOT NULL CHECK (length(body) BETWEEN 1 AND 50000),
    category_id   uuid,
    status        text NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    author_id     uuid REFERENCES users (id) ON DELETE SET NULL,
    published_at  timestamptz,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    version       bigint NOT NULL DEFAULT 0,
    search        tsvector GENERATED ALWAYS AS (
        setweight(to_tsvector('simple', title), 'A') || setweight(to_tsvector('simple', body), 'B')) STORED,
    UNIQUE (tenant_id, id),
    CHECK ((status = 'PUBLISHED') = (published_at IS NOT NULL)),
    FOREIGN KEY (tenant_id, category_id) REFERENCES ticket_categories (tenant_id, id)
);
CREATE INDEX kb_articles_status_idx ON kb_articles (tenant_id, status, updated_at);
CREATE INDEX kb_articles_search_idx ON kb_articles USING gin (search);

ALTER TABLE kb_articles ENABLE ROW LEVEL SECURITY;
ALTER TABLE kb_articles FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON kb_articles
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
```

Add `"kb_articles"` to `RlsCoverageIT.EXPECTED_TENANT_TABLES`. Unpublishing clears `published_at` (the CHECK ties it to PUBLISHED).

- [ ] **Step 4: Full-text terms**

`helpdesk/FullTextTerms.java`:

```java
package com.nexusops.helpdesk;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Free text → a safe {@code to_tsquery('simple', …)} string: lower-case words of letters, combining marks and digits
 * (any script), at least 2 characters, distinct, at most {@code maxTerms}, OR-ed together. Operators and punctuation
 * can't survive the split, so user text never becomes tsquery syntax. Empty when nothing usable remains.
 */
final class FullTextTerms {

    private FullTextTerms() {}

    static String orQuery(String text, int maxTerms) {
        if (text == null) {
            return "";
        }
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{M}\\p{N}]+"))
                .filter(word -> word.codePointCount(0, word.length()) >= 2)
                .distinct()
                .limit(maxTerms)
                .collect(Collectors.joining(" | "));
    }
}
```

Run `./gradlew test --tests '*FullTextTermsTest'` — Expected: PASS. (`"can't"` splits into `can` and `t`; `t` is dropped as too short, which the test expects.)

- [ ] **Step 5: Articles — types, domain, service, subjects**

```java
package com.nexusops.helpdesk;

public enum ArticleStatus {
    DRAFT, PUBLISHED, ARCHIVED
}
```

```java
package com.nexusops.helpdesk;

import java.util.UUID;

public record ArticleCommand(String title, String body, UUID categoryId) {}
```

```java
package com.nexusops.helpdesk;

import com.nexusops.collaboration.MemberRef;
import java.time.Instant;
import java.util.UUID;

public record ArticleView(UUID id, String title, String body, CategoryRef category, ArticleStatus status,
        MemberRef author, Instant publishedAt, Instant createdAt, Instant updatedAt, long version) {}
```

```java
package com.nexusops.helpdesk;

import java.time.Instant;
import java.util.UUID;

/** {@code excerpt}: the first 200 characters of the body, on one line. */
public record ArticleSummary(UUID id, String title, String excerpt, CategoryRef category, ArticleStatus status,
        Instant publishedAt, Instant updatedAt) {}
```

`helpdesk/domain/KbArticle.java`:

```java
package com.nexusops.helpdesk.domain;

import com.nexusops.helpdesk.ArticleStatus;
import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** A knowledge base article. The generated {@code search} column is not mapped. */
@Entity
@Table(name = "kb_articles")
public class KbArticle extends TenantOwnedEntity {

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String body;

    @Column(name = "category_id")
    private UUID categoryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ArticleStatus status;

    @Column(name = "author_id", updatable = false)
    private UUID authorId;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected KbArticle() {}

    public KbArticle(UUID id, String title, String body, UUID categoryId, UUID authorId) {
        super(id);
        this.status = ArticleStatus.DRAFT;
        this.authorId = authorId;
        this.createdAt = Instant.now();
        apply(title, body, categoryId);
    }

    public void apply(String newTitle, String newBody, UUID newCategory) {
        this.title = newTitle;
        this.body = newBody;
        this.categoryId = newCategory;
        this.updatedAt = Instant.now();
    }

    public void publish(Instant now) {
        this.status = ArticleStatus.PUBLISHED;
        this.publishedAt = now;
        this.updatedAt = now;
    }

    public void unpublish(Instant now) {
        this.status = ArticleStatus.DRAFT;
        this.publishedAt = null;
        this.updatedAt = now;
    }

    public void archive(Instant now) {
        this.status = ArticleStatus.ARCHIVED;
        this.publishedAt = null;
        this.updatedAt = now;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public ArticleStatus getStatus() {
        return status;
    }

    public UUID getAuthorId() {
        return authorId;
    }

    public Instant getPublishedAt() {
        return publishedAt;
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

`helpdesk/domain/KbArticleRepository.java`:

```java
package com.nexusops.helpdesk.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface KbArticleRepository extends JpaRepository<KbArticle, UUID>, JpaSpecificationExecutor<KbArticle> {}
```

`helpdesk/HelpDeskSearch.java`:

```java
package com.nexusops.helpdesk;

import com.nexusops.helpdesk.domain.Ticket;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.Text;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** Full-text read models (D12, D13), 'simple' configuration, explicit tenant predicates, bound parameters only. */
@Component
class HelpDeskSearch {

    record ArticlePage(List<UUID> ids, long total) {}

    private final NamedParameterJdbcTemplate jdbc;

    HelpDeskSearch(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<UUID> previousTickets(UUID requesterId, UUID exceptId, int limit) {
        return jdbc.queryForList("""
                select t.id from tickets t
                where t.tenant_id = :tenant and t.requester_id = :requester and t.id <> :except
                order by t.created_at desc, t.id desc limit :limit
                """, params().addValue("requester", requesterId).addValue("except", exceptId).addValue("limit", limit),
                UUID.class);
    }

    /** Open tickets of the same requester whose text matches this ticket's subject words (D12). */
    List<UUID> duplicateCandidates(Ticket ticket, int limit) {
        String query = FullTextTerms.orQuery(ticket.getSubject(), 10);
        if (query.isEmpty()) {
            return List.of();
        }
        return jdbc.queryForList("""
                select t.id from tickets t
                where t.tenant_id = :tenant and t.requester_id = :requester and t.id <> :except
                  and t.status in ('NEW', 'OPEN', 'PENDING') and t.search @@ to_tsquery('simple', :q)
                order by ts_rank(t.search, to_tsquery('simple', :q)) desc, t.created_at desc limit :limit
                """, params().addValue("requester", ticket.getRequesterId()).addValue("except", ticket.getId())
                .addValue("q", query).addValue("limit", limit), UUID.class);
    }

    /** Published articles matching any word of the text, best first (D12). */
    List<UUID> suggestedArticles(String text, int limit) {
        String query = FullTextTerms.orQuery(text, 15);
        if (query.isEmpty()) {
            return List.of();
        }
        return jdbc.queryForList("""
                select a.id from kb_articles a
                where a.tenant_id = :tenant and a.status = 'PUBLISHED' and a.search @@ to_tsquery('simple', :q)
                order by ts_rank(a.search, to_tsquery('simple', :q)) desc, a.updated_at desc limit :limit
                """, params().addValue("q", query).addValue("limit", limit), UUID.class);
    }

    /** The article list: web-search syntax (quotes, OR, -) when {@code q} is given, ranked; otherwise newest first. */
    ArticlePage articlePage(String rawQ, List<ArticleStatus> statuses, UUID categoryId, int limit, long offset) {
        MapSqlParameterSource params = params().addValue("statuses", statuses.stream().map(Enum::name).toList());
        StringBuilder where = new StringBuilder("a.tenant_id = :tenant and a.status in (:statuses)");
        if (categoryId != null) {
            where.append(" and a.category_id = :category");
            params.addValue("category", categoryId);
        }
        String q = Text.optional(rawQ, 200, "q");
        String order = "a.updated_at desc, a.id desc";
        if (q != null) {
            where.append(" and a.search @@ websearch_to_tsquery('simple', :q)");
            params.addValue("q", q);
            order = "ts_rank(a.search, websearch_to_tsquery('simple', :q)) desc, " + order;
        }
        Long total = jdbc.queryForObject("select count(*) from kb_articles a where " + where, params, Long.class);
        params.addValue("limit", limit).addValue("offset", offset);
        List<UUID> ids = jdbc.queryForList("select a.id from kb_articles a where " + where + " order by " + order
                + " limit :limit offset :offset", params, UUID.class);
        return new ArticlePage(ids, total == null ? 0 : total);
    }

    private static MapSqlParameterSource params() {
        return new MapSqlParameterSource("tenant", TenantContext.requireTenantId());
    }
}
```

In `searchesTitleAndBodyRankingTitlesFirst`, `q=paper jam` ANDs both words (websearch syntax), matching the title-hit (weight A) and the body-hit (weight B); ts_rank puts the title first. `websearch_to_tsquery` never raises on odd input (`!!! ' :*` yields an empty query that matches nothing — verified on PostgreSQL 17; Devanagari words index and match too).

`helpdesk/ArticleService.java`:

```java
package com.nexusops.helpdesk;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.collaboration.MemberRef;
import com.nexusops.helpdesk.domain.KbArticle;
import com.nexusops.helpdesk.domain.KbArticleRepository;
import com.nexusops.helpdesk.domain.TicketCategory;
import com.nexusops.identity.Members;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.Text;
import com.nexusops.shared.security.CurrentAuthorities;
import com.nexusops.shared.web.ApiProblem;
import com.nexusops.shared.web.PageResponse;
import com.nexusops.shared.web.Paging;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Knowledge base (D13). Without helpdesk.article.manage only published articles exist. */
@Service
public class ArticleService {

    static final String NOT_FOUND = "Record not found.";
    static final String STALE = "This record was changed by someone else. Reload and try again.";
    static final String ARCHIVED = "This record is archived.";

    private final KbArticleRepository articles;
    private final HelpDeskSearch search;
    private final CategoryService categories;
    private final Members members;
    private final AuditService audit;

    ArticleService(KbArticleRepository articles, HelpDeskSearch search, CategoryService categories, Members members,
            AuditService audit) {
        this.articles = articles;
        this.search = search;
        this.categories = categories;
        this.members = members;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<ArticleSummary> list(String q, ArticleStatus status, UUID categoryId, Integer page,
            Integer size) {
        TenantContext.requireTenantId();
        List<ArticleStatus> statuses;
        if (!canManage()) {
            statuses = status == null || status == ArticleStatus.PUBLISHED ? List.of(ArticleStatus.PUBLISHED) : List.of();
        } else {
            statuses = status == null ? List.of(ArticleStatus.DRAFT, ArticleStatus.PUBLISHED) : List.of(status);
        }
        Pageable paging = Paging.of(page, size);
        if (statuses.isEmpty()) {
            return new PageResponse<>(List.of(), paging.getPageNumber(), paging.getPageSize(), 0);
        }
        HelpDeskSearch.ArticlePage found = search.articlePage(q, statuses, categoryId, paging.getPageSize(),
                paging.getOffset());
        return new PageResponse<>(summaries(found.ids()), paging.getPageNumber(), paging.getPageSize(), found.total());
    }

    @Transactional(readOnly = true)
    public ArticleView get(UUID id) {
        return view(find(id));
    }

    @Transactional
    public ArticleView create(ArticleCommand command) {
        TenantContext.requireTenantId();
        Fields f = validate(command, null);
        KbArticle article = new KbArticle(Ids.newId(), f.title(), f.body(), f.categoryId(),
                TenantContext.userId().orElse(null));
        articles.saveAndFlush(article);
        audit.record(AuditEntry.of("ArticleCreated", "KbArticle", article.getId()).withAfter(snapshot(article)));
        return view(article);
    }

    @Transactional
    public ArticleView update(UUID id, ArticleCommand command, Long version) {
        KbArticle article = find(id);
        checkVersion(article, version);
        requireNotArchived(article);
        Fields f = validate(command, article);
        Map<String, Object> before = snapshot(article);
        article.apply(f.title(), f.body(), f.categoryId());
        articles.flush();
        audit.record(AuditEntry.of("ArticleUpdated", "KbArticle", id).withBefore(before).withAfter(snapshot(article)));
        return view(article);
    }

    @Transactional
    public ArticleView publish(UUID id, Long version) {
        KbArticle article = find(id);
        checkVersion(article, version);
        requireNotArchived(article);
        if (article.getStatus() != ArticleStatus.PUBLISHED) {
            article.publish(Instant.now());
            articles.flush();
            audit.record(AuditEntry.of("ArticlePublished", "KbArticle", id).withAfter(Map.of("title", article.getTitle())));
        }
        return view(article);
    }

    @Transactional
    public ArticleView unpublish(UUID id, Long version) {
        KbArticle article = find(id);
        checkVersion(article, version);
        requireNotArchived(article);
        if (article.getStatus() == ArticleStatus.PUBLISHED) {
            article.unpublish(Instant.now());
            articles.flush();
            audit.record(AuditEntry.of("ArticleUnpublished", "KbArticle", id).withAfter(Map.of("title", article.getTitle())));
        }
        return view(article);
    }

    @Transactional
    public ArticleView archive(UUID id, Long version) {
        KbArticle article = find(id);
        checkVersion(article, version);
        if (article.getStatus() != ArticleStatus.ARCHIVED) {
            article.archive(Instant.now());
            articles.flush();
            audit.record(AuditEntry.of("ArticleArchived", "KbArticle", id).withBefore(snapshot(article)));
        }
        return view(article);
    }

    /** Summaries in the order of {@code ids} (search ranking). */
    List<ArticleSummary> summaries(List<UUID> ids) {
        Map<UUID, KbArticle> byId = articles.findAllById(ids).stream()
                .collect(Collectors.toMap(KbArticle::getId, Function.identity()));
        List<KbArticle> ordered = ids.stream().map(byId::get).filter(Objects::nonNull).toList();
        Map<UUID, TicketCategory> cats = categories.byIds(ordered.stream().map(KbArticle::getCategoryId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        return ordered.stream().map(a -> new ArticleSummary(a.getId(), a.getTitle(), excerpt(a.getBody()),
                CategoryService.ref(cats.get(a.getCategoryId())), a.getStatus(), a.getPublishedAt(), a.getUpdatedAt()))
                .toList();
    }

    private record Fields(String title, String body, UUID categoryId) {}

    private Fields validate(ArticleCommand c, KbArticle current) {
        String title = Text.required(c.title(), 200, "title").replaceAll("\\s+", " ");
        String body = Text.required(c.body(), 50_000, "body");
        if (c.categoryId() != null) {
            TicketCategory category = categories.resolve(c.categoryId(), "categoryId");
            if (current == null || !c.categoryId().equals(current.getCategoryId())) {
                CategoryService.requireNotArchived(category);
            }
        }
        return new Fields(title, body, c.categoryId());
    }

    /** Drafts and archived articles don't exist for readers without manage (404, not 403: no leak of their titles). */
    private KbArticle find(UUID id) {
        TenantContext.requireTenantId();
        KbArticle article = articles.findById(id).orElseThrow(() -> ApiProblem.notFound(NOT_FOUND));
        if (article.getStatus() != ArticleStatus.PUBLISHED && !canManage()) {
            throw ApiProblem.notFound(NOT_FOUND);
        }
        return article;
    }

    private static boolean canManage() {
        return CurrentAuthorities.has(HelpDeskPermissions.ARTICLE_MANAGE);
    }

    private static void checkVersion(KbArticle article, Long version) {
        if (version == null) {
            throw ApiProblem.badRequestField("version", "Reload the record and try again.");
        }
        if (article.getVersion() != version) {
            throw ApiProblem.conflict(STALE);
        }
    }

    private static void requireNotArchived(KbArticle article) {
        if (article.getStatus() == ArticleStatus.ARCHIVED) {
            throw ApiProblem.conflict(ARCHIVED);
        }
    }

    private ArticleView view(KbArticle a) {
        TicketCategory category = a.getCategoryId() == null ? null
                : categories.byIds(List.of(a.getCategoryId())).get(a.getCategoryId());
        Members.Member author = a.getAuthorId() == null ? null : members.findAll(List.of(a.getAuthorId())).get(a.getAuthorId());
        return new ArticleView(a.getId(), a.getTitle(), a.getBody(), CategoryService.ref(category), a.getStatus(),
                author == null ? null : new MemberRef(author.id(), author.name()), a.getPublishedAt(), a.getCreatedAt(),
                a.getUpdatedAt(), a.getVersion());
    }

    private static String excerpt(String body) {
        String flat = body.replaceAll("\\s+", " ").strip();
        return flat.length() <= 200 ? flat : flat.substring(0, 199) + "…";
    }

    private static Map<String, Object> snapshot(KbArticle a) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("title", a.getTitle());
        values.put("categoryId", a.getCategoryId() == null ? null : a.getCategoryId().toString());
        values.put("status", a.getStatus().name());
        return values;
    }
}
```

The update in `writesPublishesUnpublishesAndArchives` keeps the article PUBLISHED (editing a published article doesn't unpublish it) and drops its category (full replacement). The publish-after-archive (`version: 4`) is 409 from `requireNotArchived`.

`helpdesk/ArticleSubjects.java`:

```java
package com.nexusops.helpdesk;

import com.nexusops.collaboration.SearchHit;
import com.nexusops.collaboration.SubjectRef;
import com.nexusops.collaboration.SubjectResolver;
import com.nexusops.helpdesk.domain.KbArticle;
import com.nexusops.helpdesk.domain.KbArticleRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/** Articles as collaboration subjects (type KB_ARTICLE, D14). Search finds published articles only. */
@Component
class ArticleSubjects implements SubjectResolver {

    static final String TYPE = "KB_ARTICLE";

    private final KbArticleRepository articles;

    ArticleSubjects(KbArticleRepository articles) {
        this.articles = articles;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String readPermission() {
        return HelpDeskPermissions.ARTICLE_READ;
    }

    @Override
    public Optional<SubjectRef> find(UUID id) {
        return articles.findById(id).map(ArticleSubjects::ref);
    }

    @Override
    public Map<UUID, SubjectRef> findAll(Collection<UUID> ids) {
        return articles.findAllById(ids).stream().collect(Collectors.toMap(KbArticle::getId, ArticleSubjects::ref));
    }

    @Override
    public List<SearchHit> search(String pattern, int limit) {
        Specification<KbArticle> spec = (root, cq, cb) -> cb.and(
                cb.equal(root.get("status"), ArticleStatus.PUBLISHED),
                cb.like(cb.lower(root.get("title")), pattern, '\\'));
        return articles.findAll(spec, PageRequest.of(0, limit, Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.asc("id"))))
                .map(a -> new SearchHit(TYPE, a.getId(), a.getTitle(), null, false)).getContent();
    }

    private static SubjectRef ref(KbArticle a) {
        return new SubjectRef(TYPE, a.getId(), a.getTitle(), a.getStatus() == ArticleStatus.ARCHIVED);
    }
}
```

- [ ] **Step 6: The ticket context**

```java
package com.nexusops.helpdesk;

import java.util.List;

/** What the agent should see next to a ticket (D12). */
public record TicketContext(List<TicketSummary> previousTickets, List<TicketSummary> possibleDuplicates,
        List<ArticleSummary> suggestedArticles) {}
```

In `TicketService` (constructor gains `HelpDeskSearch search` and `ArticleService articles`; watch for a constructor cycle — `ArticleService` doesn't depend on `TicketService`, so there is none):

```java
    @Transactional(readOnly = true)
    public TicketContext context(UUID id) {
        Ticket ticket = find(id);
        List<TicketSummary> previous = ticketsInOrder(search.previousTickets(ticket.getRequesterId(), id, 10));
        List<TicketSummary> duplicates = ticketsInOrder(search.duplicateCandidates(ticket, 5));
        List<ArticleSummary> suggested = CurrentAuthorities.has(HelpDeskPermissions.ARTICLE_READ)
                ? articles.summaries(search.suggestedArticles(ticket.getSubject() + " " + ticket.getDescription(), 5))
                : List.of();
        return new TicketContext(previous, duplicates, suggested);
    }

    private List<TicketSummary> ticketsInOrder(List<UUID> ids) {
        Map<UUID, Ticket> byId = tickets.findAllById(ids).stream()
                .collect(Collectors.toMap(Ticket::getId, Function.identity()));
        return summaries(ids.stream().map(byId::get).filter(Objects::nonNull).toList());
    }
```

Replace the id → entity ordering in `list` with `ticketsInOrder(found.ids())` so the two share it.

In `TicketController`:

```java
    @GetMapping("/tickets/{id}/context")
    @PreAuthorize("hasAuthority('helpdesk.ticket.read')")
    TicketContext context(@PathVariable UUID id) {
        return tickets.context(id);
    }
```

- [ ] **Step 7: Articles web layer**

Add to `HelpDeskDtos`:

```java
    record ArticleRequest(String title, String body, UUID categoryId, Long version) {
        ArticleCommand command() {
            return new ArticleCommand(title, body, categoryId);
        }
    }
```

`helpdesk/web/ArticleController.java`:

```java
package com.nexusops.helpdesk.web;

import com.nexusops.helpdesk.ArticleService;
import com.nexusops.helpdesk.ArticleStatus;
import com.nexusops.helpdesk.ArticleSummary;
import com.nexusops.helpdesk.ArticleView;
import com.nexusops.helpdesk.web.HelpDeskDtos.ArticleRequest;
import com.nexusops.helpdesk.web.HelpDeskDtos.VersionRequest;
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
@RequestMapping("/api/v1/helpdesk/articles")
class ArticleController {

    private final ArticleService articles;

    ArticleController(ArticleService articles) {
        this.articles = articles;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('helpdesk.article.read')")
    PageResponse<ArticleSummary> list(@RequestParam(required = false) String q,
            @RequestParam(required = false) ArticleStatus status, @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return articles.list(q, status, categoryId, page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('helpdesk.article.read')")
    ArticleView get(@PathVariable UUID id) {
        return articles.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('helpdesk.article.manage')")
    ArticleView create(@RequestBody ArticleRequest request) {
        return articles.create(request.command());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('helpdesk.article.manage')")
    ArticleView update(@PathVariable UUID id, @RequestBody ArticleRequest request) {
        return articles.update(id, request.command(), request.version());
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAuthority('helpdesk.article.manage')")
    ArticleView publish(@PathVariable UUID id, @RequestBody VersionRequest request) {
        return articles.publish(id, request.version());
    }

    @PostMapping("/{id}/unpublish")
    @PreAuthorize("hasAuthority('helpdesk.article.manage')")
    ArticleView unpublish(@PathVariable UUID id, @RequestBody VersionRequest request) {
        return articles.unpublish(id, request.version());
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAuthority('helpdesk.article.manage')")
    ArticleView archive(@PathVariable UUID id, @RequestBody VersionRequest request) {
        return articles.archive(id, request.version());
    }
}
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `cd backend && ./gradlew test --tests '*FullTextTermsTest' --tests '*ArticleApiIT' --tests '*TicketContextIT' --tests '*TicketApiIT' --tests '*RlsCoverageIT' --tests '*EndpointAuthorizationCoverageTest' --tests '*ModularityTest'`
Expected: PASS.

- [ ] **Step 9: Full suite, then commit**

Run: `cd backend && ./gradlew test` — Expected: PASS.

```bash
git add backend/src
git commit -m "feat(helpdesk): knowledge base with full-text search, and ticket context — history, duplicates, suggested articles"
```

---

### Task 6: Dashboard, search and timelines, isolation proofs, the module gate and ADR-0012

**Files:**
- Create: `backend/src/main/java/com/nexusops/helpdesk/DashboardView.java`, `TicketRelations.java`, `web/DashboardController.java`; Modify: `HelpDeskQueries.java` (`dashboard`), `collaboration/SearchService.java` (`ORDER`)
- Create: `backend/src/test/java/com/nexusops/support/PostgresAssertions.java` (moved from `InventoryRlsIT`), `backend/src/test/java/com/nexusops/HelpDeskRlsIT.java`, `backend/src/test/java/com/nexusops/helpdesk/HelpDeskDashboardIT.java`, `HelpDeskModuleGateIT.java`
- Modify: `backend/src/test/java/com/nexusops/InventoryRlsIT.java` (use the moved helper), `CrossTenantApiIT.java`, `OpenApiContractIT.java`, `collaboration/SearchApiIT.java`, `helpdesk/TicketApiIT.java` (party timeline)
- Create: `docs/decisions/0012-helpdesk-sla-clock-and-context.md`; Modify: `docs/api/openapi.json` (re-exported)

**Interfaces:**
- Consumes (Tasks 1–5): `HelpDeskQueries.OPEN_STATUSES/BREACHED/AT_RISK`, `TicketSubjects.TYPE`, `ArticleSubjects.TYPE`, `TicketRepository`, every HelpDesk route.
- Produces:
  - `DashboardView(Map<TicketStatus, Long> openByStatus, Map<Priority, Long> openByPriority, long unassigned, long breached, long atRisk, Last30Days last30Days)` with `record Last30Days(long created, long resolved, Double averageFirstResponseMinutes, Double medianFirstResponseMinutes, Double averageResolutionMinutes, Double firstResponseMetRate, Double resolutionMetRate, Double reopenRate)` (rates 0–1; null when there is nothing to measure).
  - `GET /api/v1/helpdesk/dashboard` (`helpdesk.ticket.read`).
  - `TicketRelations`: a PARTY's and a PRODUCT's timelines include their tickets' activities.
  - `SearchService.ORDER = [PARTY, LEAD, OPPORTUNITY, PRODUCT, PURCHASE_ORDER, SALES_ORDER, TICKET, KB_ARTICLE]`.
  - `support.PostgresAssertions.assertDeniedByPostgres(ThrowingCallable, String)` (SQLState 42501).

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/com/nexusops/helpdesk/HelpDeskDashboardIT.java`:

```java
package com.nexusops.helpdesk;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestHelpDesk;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Workspace;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class HelpDeskDashboardIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;

    Workspace ws;
    Api owner;
    UUID customer;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("hddash"));
        owner = Api.login(mvc, ws);
        TestHelpDesk.enable(owner);
        customer = Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Deccan Retail\"}"));
    }

    private UUID ticket(String priority) throws Exception {
        return Api.id(owner.post("/api/v1/helpdesk/tickets", "{\"subject\":\"S\",\"description\":\"D\",\"requesterId\":\""
                + customer + "\",\"priority\":\"" + priority + "\"}").andExpect(status().isCreated()));
    }

    private JdbcTemplate jdbc() {
        return OwnerJdbc.ownerAs(ws.tenantId());
    }

    @Test
    void anEmptyHelpDeskHasZerosAndNoRates() throws Exception {
        owner.get("/api/v1/helpdesk/dashboard").andExpect(status().isOk())
                .andExpect(jsonPath("$.openByStatus.NEW").value(0))
                .andExpect(jsonPath("$.unassigned").value(0))
                .andExpect(jsonPath("$.last30Days.created").value(0))
                .andExpect(jsonPath("$.last30Days.averageFirstResponseMinutes").doesNotExist())
                .andExpect(jsonPath("$.last30Days.firstResponseMetRate").doesNotExist())
                .andExpect(jsonPath("$.last30Days.reopenRate").doesNotExist());
    }

    @Test
    void summarisesOpenWorkAndTheLastThirtyDays() throws Exception {
        UUID fast = ticket("NORMAL");
        UUID slow = ticket("HIGH");
        UUID pending = ticket("URGENT");
        ticket("LOW");
        // fast: answered after 30 min, resolved after 2 h, within targets
        jdbc().update("update tickets set status = 'RESOLVED', first_responded_at = created_at + interval '30 minutes', "
                + "resolved_at = created_at + interval '2 hours', resolution_note = 'x' where id = ?", fast);
        // slow: answered after 10 h (HIGH target 4 h: breached), reopened once, open again
        jdbc().update("update tickets set status = 'OPEN', first_responded_at = created_at + interval '10 hours', "
                + "reopen_count = 1 where id = ?", slow);
        jdbc().update("update tickets set status = 'PENDING', paused_at = now() where id = ?", pending);
        owner.get("/api/v1/helpdesk/dashboard")
                .andExpect(jsonPath("$.openByStatus.NEW").value(1))
                .andExpect(jsonPath("$.openByStatus.OPEN").value(1))
                .andExpect(jsonPath("$.openByStatus.PENDING").value(1))
                .andExpect(jsonPath("$.openByPriority.HIGH").value(1))
                .andExpect(jsonPath("$.openByPriority.NORMAL").value(0))
                .andExpect(jsonPath("$.unassigned").value(3))
                .andExpect(jsonPath("$.last30Days.created").value(4))
                .andExpect(jsonPath("$.last30Days.resolved").value(1))
                .andExpect(jsonPath("$.last30Days.averageFirstResponseMinutes").value(315.0))
                .andExpect(jsonPath("$.last30Days.medianFirstResponseMinutes").value(315.0))
                .andExpect(jsonPath("$.last30Days.averageResolutionMinutes").value(120.0))
                .andExpect(jsonPath("$.last30Days.firstResponseMetRate").value(0.5))
                .andExpect(jsonPath("$.last30Days.resolutionMetRate").value(1.0))
                .andExpect(jsonPath("$.last30Days.reopenRate").value(0.5));
    }
}
```

The expected figures: first responses 30 and 600 minutes → average and median 315; one resolution of 120 minutes; first response met on 1 of 2 → 0.5; reopen rate = tickets reopened ÷ tickets that were ever resolved (`fast` resolved, `slow` reopened once → 1 of 2 → 0.5). `ticket("LOW")` stays NEW.

`backend/src/test/java/com/nexusops/support/PostgresAssertions.java` — move `assertDeniedByPostgres` out of `InventoryRlsIT` (make it `public static`) and call it from both RLS tests:

```java
package com.nexusops.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.SQLException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.springframework.dao.DataAccessException;

/** Assertions about what PostgreSQL itself refuses. */
public final class PostgresAssertions {

    private PostgresAssertions() {}

    /** The call fails with SQLState 42501 (row-level security or a missing grant), not any other database error. */
    public static void assertDeniedByPostgres(ThrowingCallable call, String what) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).as(what).isInstanceOf(DataAccessException.class);
        Throwable cause = thrown;
        while (cause != null && !(cause instanceof SQLException)) {
            cause = cause.getCause();
        }
        assertThat(cause).as(what + " (SQL cause)").isNotNull();
        assertThat(((SQLException) cause).getSQLState()).as(what).isEqualTo("42501");
    }
}
```

`backend/src/test/java/com/nexusops/HelpDeskRlsIT.java`:

```java
package com.nexusops;

import static com.nexusops.support.PostgresAssertions.assertDeniedByPostgres;
import static org.assertj.core.api.Assertions.assertThat;

import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** The database alone isolates every Phase 7 table, independent of application code. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HelpDeskRlsIT extends IntegrationTestSupport {

    static final List<String> TABLES = List.of("ticket_categories", "sla_policies", "tickets", "ticket_messages",
            "kb_articles");

    final UUID tenantA = UUID.randomUUID();
    final UUID tenantB = UUID.randomUUID();
    final UUID party = UUID.randomUUID();
    final UUID category = UUID.randomUUID();
    final UUID policy = UUID.randomUUID();
    final UUID ticket = UUID.randomUUID();
    final UUID message = UUID.randomUUID();
    final UUID article = UUID.randomUUID();
    final AtomicInteger counter = new AtomicInteger();

    @Autowired JdbcTemplate contextStarted; // starts the application (and Flyway) before @BeforeAll runs

    @BeforeAll
    void seedTenantB() {
        Timestamp now = Timestamp.from(Instant.now());
        for (UUID t : new UUID[] {tenantA, tenantB}) {
            OwnerJdbc.jdbc().update("insert into tenants (id, slug, name, status, plan_code, created_at, updated_at) "
                    + "values (?, ?, 'T', 'ACTIVE', 'FREE', ?, ?)", t, "hd-" + t.toString().substring(0, 8), now, now);
        }
        JdbcTemplate b = OwnerJdbc.ownerAs(tenantB);
        b.update("insert into parties (id, tenant_id, kind, name, name_key, created_at, updated_at) "
                + "values (?, ?, 'ORGANIZATION', 'Beta', 'beta', ?, ?)", party, tenantB, now, now);
        b.update("insert into ticket_categories (id, tenant_id, name, name_key, position, created_at, updated_at) "
                + "values (?, ?, 'Beta cat', 'beta cat', 0, ?, ?)", category, tenantB, now, now);
        b.update("insert into sla_policies (id, tenant_id, priority, first_response_minutes, resolution_minutes, updated_at) "
                + "values (?, ?, 'LOW', 60, 120, ?)", policy, tenantB, now);
        b.update(TICKET_INSERT, ticket, tenantB, "T-00001", party, category, now, now, now, now, now);
        b.update("insert into ticket_messages (id, tenant_id, ticket_id, kind, body, created_at) "
                + "values (?, ?, ?, 'INTERNAL_NOTE', 'secret', ?)", message, tenantB, ticket, now);
        b.update("insert into kb_articles (id, tenant_id, title, body, status, created_at, updated_at) "
                + "values (?, ?, 'Beta article', 'Body', 'DRAFT', ?, ?)", article, tenantB, now, now);
    }

    static final String TICKET_INSERT = """
            insert into tickets (id, tenant_id, number, subject, description, requester_id, category_id, priority,
                                 channel, status, first_response_due_at, resolution_due_at,
                                 resolution_clock_started_at, created_at, updated_at)
            values (?, ?, ?, 'Beta ticket', 'Desc', ?, ?, 'LOW', 'PHONE', 'NEW', ?, ?, ?, ?, ?)""";

    private static JdbcTemplate app(String tenantSetting) {
        return OwnerJdbc.tenantScoped("nexusops_app", APP_PASSWORD, tenantSetting);
    }

    @Test
    void anotherTenantsContextAndNoContextSeeNothing() {
        for (String table : TABLES) {
            assertThat(app(tenantA.toString()).queryForObject("select count(*) from " + table, Long.class)).as(table)
                    .isZero();
            assertThat(app("").queryForObject("select count(*) from " + table, Long.class)).as(table).isZero();
            assertThat(app(tenantB.toString()).queryForObject("select count(*) from " + table, Long.class)).as(table)
                    .isPositive();
        }
    }

    /** Valid for tenant B (fresh keys each call), so a rejection for anyone else is RLS alone. */
    private void insertTenantBRow(JdbcTemplate as, String table) {
        Timestamp now = Timestamp.from(Instant.now());
        int n = counter.incrementAndGet();
        UUID id = UUID.randomUUID();
        switch (table) {
            case "ticket_categories" -> as.update("insert into ticket_categories (id, tenant_id, name, name_key, position, "
                    + "created_at, updated_at) values (?, ?, ?, ?, 1, ?, ?)", id, tenantB, "Cat " + n, "cat " + n, now, now);
            case "sla_policies" -> {
                String priority = List.of("NORMAL", "HIGH", "URGENT").get(n % 3);
                as.update("delete from sla_policies where tenant_id = ? and priority = ?", tenantB, priority);
                as.update("insert into sla_policies (id, tenant_id, priority, first_response_minutes, resolution_minutes, "
                        + "updated_at) values (?, ?, ?, 60, 120, ?)", id, tenantB, priority, now);
            }
            case "tickets" -> as.update(TICKET_INSERT, id, tenantB, "T-9" + String.format("%04d", n), party, category,
                    now, now, now, now, now);
            case "ticket_messages" -> as.update("insert into ticket_messages (id, tenant_id, ticket_id, kind, body, "
                    + "created_at) values (?, ?, ?, 'INTERNAL_NOTE', 'x', ?)", id, tenantB, ticket, now);
            case "kb_articles" -> as.update("insert into kb_articles (id, tenant_id, title, body, status, created_at, "
                    + "updated_at) values (?, ?, 'x', 'y', 'DRAFT', ?, ?)", id, tenantB, now, now);
            default -> throw new IllegalArgumentException(table);
        }
    }

    @Test
    void insertsIntoAnotherTenantAreRejectedByRowLevelSecurity() {
        for (String table : TABLES) {
            if (table.equals("sla_policies")) {
                // the insert's own delete would match nothing outside tenant B; only the insert can be refused
                assertDeniedByPostgres(() -> app(tenantA.toString()).update("insert into sla_policies (id, tenant_id, "
                        + "priority, first_response_minutes, resolution_minutes, updated_at) values (?, ?, 'HIGH', 1, 2, now())",
                        UUID.randomUUID(), tenantB), table + " as tenant A");
                assertDeniedByPostgres(() -> app("").update("insert into sla_policies (id, tenant_id, priority, "
                        + "first_response_minutes, resolution_minutes, updated_at) values (?, ?, 'HIGH', 1, 2, now())",
                        UUID.randomUUID(), tenantB), table + " without tenant context");
            } else {
                assertDeniedByPostgres(() -> insertTenantBRow(app(tenantA.toString()), table), table + " as tenant A");
                assertDeniedByPostgres(() -> insertTenantBRow(app(""), table), table + " without tenant context");
            }
            insertTenantBRow(app(tenantB.toString()), table); // positive control
        }
    }

    @Test
    void updatesAndDeletesOfAnotherTenantsRowsAffectNothing() {
        record Case(String update, String delete, UUID id) {}
        for (Case c : List.of(
                new Case("update ticket_categories set name = 'Evil' where id = ?",
                        "delete from ticket_categories where id = ?", category),
                new Case("update sla_policies set first_response_minutes = 1 where id = ?",
                        "delete from sla_policies where id = ?", policy),
                new Case("update tickets set subject = 'Evil' where id = ?", "delete from tickets where id = ?", ticket),
                new Case("update kb_articles set title = 'Evil' where id = ?", "delete from kb_articles where id = ?",
                        article))) {
            assertThat(app(tenantA.toString()).update(c.update(), c.id())).isZero();
            assertThat(app(tenantA.toString()).update(c.delete(), c.id())).isZero();
        }
        assertThat(OwnerJdbc.ownerAs(tenantB).queryForObject("select subject from tickets where id = ?", String.class,
                ticket)).isEqualTo("Beta ticket");
    }

    @Test
    void theConversationIsAppendOnlyEvenForItsOwnTenant() {
        assertDeniedByPostgres(() -> app(tenantB.toString()).update("update ticket_messages set body = 'edited' "
                + "where id = ?", message), "update ticket_messages");
        assertDeniedByPostgres(() -> app(tenantB.toString()).update("delete from ticket_messages where id = ?", message),
                "delete ticket_messages");
        assertThat(OwnerJdbc.ownerAs(tenantB).queryForObject("select body from ticket_messages where id = ?",
                String.class, message)).isEqualTo("secret");
    }
}
```

`backend/src/test/java/com/nexusops/helpdesk/HelpDeskModuleGateIT.java`:

```java
package com.nexusops.helpdesk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestHelpDesk;
import com.nexusops.support.TestTenants;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Disabling HelpDesk removes every HelpDesk permission, so every HelpDesk route answers 403 (criterion 5). */
@AutoConfigureMockMvc
class HelpDeskModuleGateIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mapping;

    @Test
    void everyHelpDeskHandlerRequiresAHelpDeskPermission() {
        List<String> violations = new ArrayList<>();
        Set<String> checked = new HashSet<>();
        mapping.getHandlerMethods().forEach((info, handler) -> {
            for (String path : info.getPatternValues()) {
                if (!path.startsWith("/api/v1/helpdesk/")) {
                    continue;
                }
                var preAuthorize = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), PreAuthorize.class);
                String expression = preAuthorize == null ? "" : preAuthorize.value().replace(" ", "");
                checked.add(path);
                if (!expression.startsWith("hasAuthority('helpdesk.")) {
                    violations.add(info.getMethodsCondition() + " " + path + " -> " + expression);
                }
            }
        });
        assertThat(checked).as("HelpDesk routes were found").hasSizeGreaterThanOrEqualTo(15);
        assertThat(violations).isEmpty();
    }

    @Test
    void everyHelpDeskRouteIsForbiddenWhileTheModuleIsOff() throws Exception {
        Api owner = Api.login(mvc, TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("hdgate")));
        TestHelpDesk.enable(owner);
        UUID category = TestHelpDesk.category(owner, "General");
        owner.put("/api/v1/tenant/modules/HELPDESK", "{\"enabled\":false}").andExpect(status().isOk());
        UUID any = UUID.randomUUID();
        owner.get("/api/v1/helpdesk/tickets").andExpect(status().isForbidden());
        owner.get("/api/v1/helpdesk/tickets/" + any).andExpect(status().isForbidden());
        owner.post("/api/v1/helpdesk/tickets", "{}").andExpect(status().isForbidden());
        owner.post("/api/v1/helpdesk/tickets/" + any + "/messages", "{}").andExpect(status().isForbidden());
        owner.post("/api/v1/helpdesk/tickets/" + any + "/status", "{}").andExpect(status().isForbidden());
        owner.post("/api/v1/helpdesk/tickets/" + any + "/assign", "{}").andExpect(status().isForbidden());
        owner.get("/api/v1/helpdesk/tickets/" + any + "/context").andExpect(status().isForbidden());
        owner.get("/api/v1/helpdesk/categories").andExpect(status().isForbidden());
        owner.post("/api/v1/helpdesk/categories/" + category + "/archive", "").andExpect(status().isForbidden());
        owner.get("/api/v1/helpdesk/sla-policies").andExpect(status().isForbidden());
        owner.get("/api/v1/helpdesk/articles").andExpect(status().isForbidden());
        owner.post("/api/v1/helpdesk/articles", "{}").andExpect(status().isForbidden());
        owner.get("/api/v1/helpdesk/dashboard").andExpect(status().isForbidden());
        owner.get("/api/v1/helpdesk/agents").andExpect(status().isForbidden());
        TestHelpDesk.enable(owner);
        owner.get("/api/v1/helpdesk/tickets").andExpect(status().isOk());
    }
}
```

The gate test anchors the expression (`startsWith("hasAuthority('helpdesk.")`): every HelpDesk handler uses a single `hasAuthority`.

Add to `collaboration/SearchApiIT.java`:

```java
    @Test
    void findsTicketsByNumberOrSubjectAndPublishedArticlesByTitle() throws Exception {
        TestHelpDesk.enable(owner);
        UUID org = Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Helpdesk Co\"}"));
        owner.post("/api/v1/helpdesk/tickets", "{\"subject\":\"Zephyr printer jam\",\"description\":\"x\","
                + "\"requesterId\":\"" + org + "\"}").andExpect(status().isCreated());
        UUID article = Api.id(owner.post("/api/v1/helpdesk/articles", "{\"title\":\"Zephyr setup guide\",\"body\":\"y\"}"));
        owner.get("/api/v1/search?q=zephyr").andExpect(jsonPath("$[*].type").value(Matchers.contains("TICKET")))
                .andExpect(jsonPath("$[0].label").value("T-00001 · Zephyr printer jam"))
                .andExpect(jsonPath("$[0].detail").value("Helpdesk Co"));
        owner.post("/api/v1/helpdesk/articles/" + article + "/publish", "{\"version\":0}").andExpect(status().isOk());
        owner.get("/api/v1/search?q=zephyr").andExpect(jsonPath("$[*].type")
                .value(Matchers.contains("TICKET", "KB_ARTICLE")));
    }
```

(imports `com.nexusops.support.TestHelpDesk` and `org.hamcrest.Matchers`; the class already has `owner` and `jsonPath`/`status`.)

Add to `helpdesk/TicketApiIT.java`:

```java
    @Test
    void theRequestersTimelineIncludesTheTicketsActivity() throws Exception {
        UUID id = ticket("");
        owner.post("/api/v1/activities", "{\"subjectType\":\"TICKET\",\"subjectId\":\"" + id
                + "\",\"type\":\"CALL\",\"summary\":\"Called Meera back\"}").andExpect(status().isCreated());
        owner.get("/api/v1/activities?subjectType=PARTY&subjectId=" + customer + "&includeRelated=true")
                .andExpect(jsonPath("$.items[*].summary").value(Matchers.hasItem("Called Meera back")));
    }
```

Extend `CrossTenantApiIT` (as Phase 6 did for Inventory): in `twoTenants()` enable HELPDESK for B and create, as B, a ticket (`ticketB`, requester `orgB`), a published article (`articleB`) and read B's `General` category id (`categoryB`); then add:

```java
    @Test
    void helpDeskRecordsOfAnotherTenantAreInvisibleAndUntouchable() throws Exception {
        as(ownerA, put("/api/v1/tenant/modules/HELPDESK").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}")).andExpect(status().isOk());
        as(ownerA, get("/api/v1/helpdesk/tickets/" + ticketB)).andExpect(status().isNotFound());
        as(ownerA, get("/api/v1/helpdesk/tickets/" + ticketB + "/context")).andExpect(status().isNotFound());
        as(ownerA, get("/api/v1/helpdesk/tickets/" + ticketB + "/messages")).andExpect(status().isNotFound());
        as(ownerA, json(put("/api/v1/helpdesk/tickets/" + ticketB), "{\"subject\":\"H\",\"description\":\"H\","
                + "\"requesterId\":\"" + orgB + "\",\"version\":0}")).andExpect(status().isNotFound());
        as(ownerA, json(post("/api/v1/helpdesk/tickets/" + ticketB + "/messages"),
                "{\"kind\":\"PUBLIC_REPLY\",\"body\":\"x\"}")).andExpect(status().isNotFound());
        as(ownerA, json(post("/api/v1/helpdesk/tickets/" + ticketB + "/assign"), "{\"assigneeId\":null,\"version\":0}"))
                .andExpect(status().isNotFound());
        as(ownerA, json(post("/api/v1/helpdesk/tickets/" + ticketB + "/status"), "{\"status\":\"PENDING\",\"version\":0}"))
                .andExpect(status().isNotFound());
        as(ownerA, json(put("/api/v1/helpdesk/categories/" + categoryB), "{\"name\":\"H\",\"version\":0}"))
                .andExpect(status().isNotFound());
        as(ownerA, post("/api/v1/helpdesk/categories/" + categoryB + "/archive")).andExpect(status().isNotFound());
        as(ownerA, get("/api/v1/helpdesk/articles/" + articleB)).andExpect(status().isNotFound());
        as(ownerA, json(post("/api/v1/helpdesk/articles/" + articleB + "/archive"), "{\"version\":1}"))
                .andExpect(status().isNotFound());
        as(ownerA, get("/api/v1/activities").param("subjectType", "TICKET").param("subjectId", ticketB.toString()))
                .andExpect(status().isNotFound());
        // references to another tenant's rows inside bodies are refused
        as(ownerA, json(post("/api/v1/helpdesk/tickets"), "{\"subject\":\"x\",\"description\":\"x\",\"requesterId\":\""
                + orgB + "\"}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("requesterId"));
        String orgA = JsonPath.read(as(ownerA, json(post("/api/v1/organizations"), "{\"name\":\"Alpha\"}"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        as(ownerA, json(post("/api/v1/helpdesk/tickets"), "{\"subject\":\"x\",\"description\":\"x\",\"requesterId\":\""
                + orgA + "\",\"categoryId\":\"" + categoryB + "\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("categoryId"));
        as(ownerA, json(post("/api/v1/helpdesk/tickets"), "{\"subject\":\"x\",\"description\":\"x\",\"requesterId\":\""
                + orgA + "\",\"linkedType\":\"TICKET\",\"linkedId\":\"" + ticketB + "\"}")).andExpect(status().isBadRequest());
        // lists, search and the dashboard never contain B's rows
        as(ownerA, get("/api/v1/helpdesk/tickets")).andExpect(jsonPath("$.total").value(0));
        as(ownerA, get("/api/v1/helpdesk/articles")).andExpect(jsonPath("$.total").value(0));
        as(ownerA, get("/api/v1/search").param("q", "beta")).andExpect(jsonPath("$[*].type",
                Matchers.not(Matchers.hasItems("TICKET", "KB_ARTICLE"))));
        as(ownerA, get("/api/v1/helpdesk/dashboard")).andExpect(jsonPath("$.last30Days.created").value(0));
        // tenant B is untouched
        as(ownerB, get("/api/v1/helpdesk/tickets/" + ticketB)).andExpect(jsonPath("$.status").value("NEW"));
    }
```

Name B's ticket subject and article title with the word "Beta" (`"Beta printer issue"`, `"Beta guide"`) so the search check has something to miss.

Add to `OpenApiContractIT`'s `contains(...)` list: `"/api/v1/helpdesk/tickets"`, `"/api/v1/helpdesk/tickets/{id}"`, `"/api/v1/helpdesk/tickets/{id}/assign"`, `"/api/v1/helpdesk/tickets/{id}/status"`, `"/api/v1/helpdesk/tickets/{id}/messages"`, `"/api/v1/helpdesk/tickets/{id}/context"`, `"/api/v1/helpdesk/agents"`, `"/api/v1/helpdesk/categories"`, `"/api/v1/helpdesk/categories/{id}"`, `"/api/v1/helpdesk/categories/{id}/archive"`, `"/api/v1/helpdesk/categories/{id}/restore"`, `"/api/v1/helpdesk/sla-policies"`, `"/api/v1/helpdesk/sla-policies/{priority}"`, `"/api/v1/helpdesk/articles"`, `"/api/v1/helpdesk/articles/{id}"`, `"/api/v1/helpdesk/articles/{id}/publish"`, `"/api/v1/helpdesk/articles/{id}/unpublish"`, `"/api/v1/helpdesk/articles/{id}/archive"`, `"/api/v1/helpdesk/dashboard"` (each quoted as the existing entries are).

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && ./gradlew test --tests '*HelpDeskDashboardIT' --tests '*SearchApiIT' --tests '*TicketApiIT'`
Expected: FAIL — `/dashboard` is 404; search returns no TICKET; the party timeline lacks the call. (`HelpDeskRlsIT`, the gate and cross-tenant tests may already pass — they prove existing behaviour.)

- [ ] **Step 3: Dashboard**

```java
package com.nexusops.helpdesk;

import java.util.Map;

/** D15. Rates are 0–1 and null when nothing in the window can be measured. */
public record DashboardView(Map<TicketStatus, Long> openByStatus, Map<Priority, Long> openByPriority, long unassigned,
        long breached, long atRisk, Last30Days last30Days) {

    public record Last30Days(long created, long resolved, Double averageFirstResponseMinutes,
            Double medianFirstResponseMinutes, Double averageResolutionMinutes, Double firstResponseMetRate,
            Double resolutionMetRate, Double reopenRate) {}
}
```

Add to `HelpDeskQueries`:

```java
    DashboardView dashboard() {
        MapSqlParameterSource params = new MapSqlParameterSource("tenant", TenantContext.requireTenantId());
        Map<TicketStatus, Long> byStatus = new EnumMap<>(TicketStatus.class);
        for (TicketStatus s : List.of(TicketStatus.NEW, TicketStatus.OPEN, TicketStatus.PENDING)) {
            byStatus.put(s, 0L);
        }
        Map<Priority, Long> byPriority = new EnumMap<>(Priority.class);
        for (Priority p : Priority.values()) {
            byPriority.put(p, 0L);
        }
        jdbc.query("select t.status, t.priority, count(*) as n from tickets t where t.tenant_id = :tenant and "
                + OPEN_STATUSES + " group by t.status, t.priority", params, rs -> {
                    long n = rs.getLong("n");
                    byStatus.merge(TicketStatus.valueOf(rs.getString("status")), n, Long::sum);
                    byPriority.merge(Priority.valueOf(rs.getString("priority")), n, Long::sum);
                });
        Map<String, Object> open = jdbc.queryForMap("select "
                + "count(*) filter (where t.assignee_id is null) as unassigned, "
                + "count(*) filter (where " + BREACHED + ") as breached, "
                + "count(*) filter (where " + AT_RISK + ") as at_risk "
                + "from tickets t where t.tenant_id = :tenant and " + OPEN_STATUSES, params);
        Map<String, Object> w = jdbc.queryForMap("""
                select count(*) as created,
                       count(*) filter (where t.resolved_at is not null) as resolved,
                       avg(extract(epoch from t.first_responded_at - t.created_at) / 60) as avg_first,
                       percentile_cont(0.5) within group (order by extract(epoch from t.first_responded_at - t.created_at) / 60)
                           filter (where t.first_responded_at is not null) as median_first,
                       avg(extract(epoch from t.resolved_at - t.created_at) / 60) as avg_resolution,
                       avg(case when t.first_responded_at <= t.first_response_due_at then 1.0 else 0.0 end)
                           filter (where t.first_responded_at is not null) as first_met,
                       avg(case when t.resolved_at <= t.resolution_due_at then 1.0 else 0.0 end)
                           filter (where t.resolved_at is not null) as resolution_met,
                       avg(case when t.reopen_count > 0 then 1.0 else 0.0 end)
                           filter (where t.resolved_at is not null or t.reopen_count > 0) as reopen_rate
                from tickets t
                where t.tenant_id = :tenant and t.created_at >= now() - interval '30 days'
                """, params);
        return new DashboardView(byStatus, byPriority, number(open.get("unassigned")), number(open.get("breached")),
                number(open.get("at_risk")), new DashboardView.Last30Days(number(w.get("created")),
                        number(w.get("resolved")), decimal(w.get("avg_first")), decimal(w.get("median_first")),
                        decimal(w.get("avg_resolution")), decimal(w.get("first_met")), decimal(w.get("resolution_met")),
                        decimal(w.get("reopen_rate"))));
    }

    private static long number(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    /** Rounded to 2 decimals; null stays null (nothing to measure). */
    private static Double decimal(Object value) {
        return value == null ? null : Math.round(((Number) value).doubleValue() * 100) / 100.0;
    }
```

(imports `java.util.EnumMap`, `java.util.Map`.) `avg(...)` over no rows is null in SQL, which is how "no rates yet" reaches the JSON as a missing value.

`helpdesk/web/DashboardController.java`:

```java
package com.nexusops.helpdesk.web;

import com.nexusops.helpdesk.DashboardView;
import com.nexusops.helpdesk.TicketService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/helpdesk/dashboard")
class DashboardController {

    private final TicketService tickets;

    DashboardController(TicketService tickets) {
        this.tickets = tickets;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('helpdesk.ticket.read')")
    DashboardView dashboard() {
        return tickets.dashboard();
    }
}
```

In `TicketService`:

```java
    @Transactional(readOnly = true)
    public DashboardView dashboard() {
        TenantContext.requireTenantId();
        return queries.dashboard();
    }
```

- [ ] **Step 4: Timelines and search order**

`helpdesk/TicketRelations.java`:

```java
package com.nexusops.helpdesk;

import com.nexusops.collaboration.SubjectKey;
import com.nexusops.collaboration.SubjectRelations;
import com.nexusops.helpdesk.domain.Ticket;
import com.nexusops.helpdesk.domain.TicketRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/** A party's and a product's timelines include their tickets (D14). */
@Component
class TicketRelations implements SubjectRelations {

    private static final int LIMIT = 100;
    private static final Sort ORDER = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"));

    private final TicketRepository tickets;

    TicketRelations(TicketRepository tickets) {
        this.tickets = tickets;
    }

    @Override
    public List<SubjectKey> related(String type, UUID id) {
        String field = switch (type) {
            case "PARTY" -> "requesterId";
            case "PRODUCT" -> "productId";
            default -> null;
        };
        if (field == null) {
            return List.of();
        }
        Specification<Ticket> spec = (root, cq, cb) -> cb.equal(root.get(field), id);
        return tickets.findAll(spec, PageRequest.of(0, LIMIT, ORDER)).stream()
                .map(t -> new SubjectKey(TicketSubjects.TYPE, t.getId())).toList();
    }
}
```

In `collaboration/SearchService.java`:

```java
    static final List<String> ORDER = List.of("PARTY", "LEAD", "OPPORTUNITY", "PRODUCT", "PURCHASE_ORDER", "SALES_ORDER",
            "TICKET", "KB_ARTICLE");
```

- [ ] **Step 5: Run the platform suites**

Run: `cd backend && ./gradlew test --tests '*HelpDeskDashboardIT' --tests '*HelpDeskRlsIT' --tests '*InventoryRlsIT' --tests '*RlsCoverageIT' --tests '*HelpDeskModuleGateIT' --tests '*CrossTenantApiIT' --tests '*OpenApiContractIT' --tests '*SearchApiIT' --tests '*TicketApiIT' --tests '*ModularityTest' --tests '*EndpointAuthorizationCoverageTest'`
Expected: PASS.

- [ ] **Step 6: ADR-0012**

`docs/decisions/0012-helpdesk-sla-clock-and-context.md`:

```markdown
# ADR-0012: HelpDesk — a stored, pausable SLA clock and tickets that carry their context

- Status: Accepted
- Date: 2026-10-09
- Spec: docs/superpowers/specs/2026-10-09-helpdesk-mvp-design.md

## Context
Phase 7 adds customer support. Blueprint §2.2: a ticket is usually treated as an isolated record, although resolving it
depends on the customer, the product, the order, previous tickets and known answers. Service levels must be visible and
honest — time spent waiting on the customer shouldn't count against the team — and the process measures of §9 (first
response, resolution time, SLA breach rate, reopen rate) must be recorded from day one.

## Decision
1. **The SLA clock is stored, not scheduled.** A ticket keeps its first-response and resolution due times, the start of
   its resolution clock and its accumulated paused time. A pure `SlaClock` recomputes them on every priority or status
   change: entering PENDING pauses, leaving it adds the paused time, reopening restarts the resolution clock with the
   full target. "Breached" and "at risk" are comparisons with `now()` — in Java for views and in SQL for filters and the
   dashboard, kept in step by tests on both. No background job exists or is needed.
2. **Policies are per priority and per workspace**, calendar time in the workspace's time zone; business hours are later
   work.
3. **A ticket links to canonical records**: the requester is a directory party, the product a catalog product, and any
   other record (a sales order, an opportunity) is a collaboration subject reference validated by its own module's read
   permission. HelpDesk depends on no business module.
4. **Context is deterministic for now**: previous tickets of the requester, possible duplicates (open tickets of the same
   requester whose text matches the subject) and suggested articles (published articles matching subject and
   description) come from PostgreSQL full-text search (`'simple'` configuration, GIN indexes). User text is reduced to
   OR-ed words before it reaches `to_tsquery`, so no input can become query syntax. These are the baselines the AI phase
   will improve on.
5. **The conversation is append-only** (no UPDATE/DELETE grant). Only public replies are emailed — to the requester's
   address, after commit — and the first public reply is the first response. Message posts on one ticket serialize on
   its row; every other state change takes its optimistic lock.
6. Routing is a category's default assignee; assignment emails the assignee. Every HelpDesk permission belongs to module
   HELPDESK.

## Consequences
- Lists can sort and filter by SLA state cheaply, and the numbers on screen are the numbers in the database.
- The SLA rule exists twice (Java, SQL); `SlaClockTest` and the list and dashboard tests pin both.
- Inbound email, a customer portal, business-hours calendars, automatic closing, teams and satisfaction surveys are later
  work (spec §1 non-goals).
```

- [ ] **Step 7: Re-export the OpenAPI document, run the whole suite, commit**

Run: `cd backend && ./gradlew test --tests '*OpenApiContractIT' -Dopenapi.export=true` — `git diff --stat docs/api/openapi.json` shows the new paths.

Run: `cd backend && ./gradlew test` — Expected: PASS.

```bash
git add backend/src docs/decisions/0012-helpdesk-sla-clock-and-context.md docs/api/openapi.json
git commit -m "feat(helpdesk): dashboard, search and timelines; isolation, module gate and cross-tenant proofs; ADR-0012"
```

---

### Task 7: Frontend foundations — types, permissions, the HelpDesk area, its dashboard and Settings → HelpDesk

**Files:**
- Modify: `frontend/src/lib/api/types.ts`, `frontend/src/features/auth/permissions.tsx`, `frontend/src/test/records.ts`
- Modify: `frontend/src/features/records/SubjectLink.tsx`, `frontend/src/features/shell/GlobalSearch.tsx`, `frontend/src/features/shell/routes.tsx`, `frontend/src/features/shell/nav.ts`, `frontend/src/features/shell/ComingSoonPage.test.tsx`
- Create: `frontend/src/features/helpdesk/labels.ts`, `sla.ts`, `sla.test.ts`, `invalidation.ts`, `SlaBadge.tsx`, `AgentSelect.tsx`, `CategorySelect.tsx`, `HelpDeskLayout.tsx`, `HelpDeskLayout.test.tsx`, `HelpDeskIndex.tsx`, `HelpDeskDashboardPage.tsx`, `HelpDeskDashboardPage.test.tsx`, `routes.tsx`
- Create: `frontend/src/features/settings/HelpDeskSettingsPage.tsx`, `HelpDeskSettingsPage.test.tsx`

**Interfaces:**
- Consumes (backend Tasks 1–6): every HelpDesk route and JSON shape. Java records become the TS interfaces below, field for field; `Instant` is an ISO string; `null` fields may also be absent.
- Produces (later frontend tasks rely on these exact names):
  - Types: `TicketStatus`, `TicketPriority`, `TicketChannel`, `SlaState`, `MessageKind`, `ArticleStatus`, `CategoryRef`, `CategoryView`, `SlaPolicyView`, `TicketProductRef`, `LinkedRecord`, `SlaView`, `TicketView`, `TicketSummary`, `MessageView`, `MessagePosted`, `ArticleView`, `ArticleSummary`, `TicketContext`, `HelpDeskDashboard`; `SubjectType` gains `'TICKET' | 'KB_ARTICLE'`. Agents use the existing `AssigneeView` (`id`, `name`, `email`).
  - `PERMISSIONS.ticketRead|ticketManage|ticketAssign|ticketResolve|helpdeskSettings|articleRead|articleManage`.
  - Fixtures: `anSla`, `aTicket`, `aTicketSummary`, `aCategory`, `defaultSlaPolicies`, `anAgent`, `aMessage`, `anArticle`, `anArticleSummary`, `aTicketContext`, `aHelpDeskDashboard`.
  - `features/helpdesk/labels.ts`: `TICKET_STATUS_LABELS`, `OPEN_STATUSES`, `PRIORITY_LABELS`, `PRIORITIES` (most urgent first), `CHANNEL_LABELS`, `SLA_STATE_LABELS`, `MESSAGE_KIND_LABELS`, `ARTICLE_STATUS_LABELS`.
  - `features/helpdesk/sla.ts`: `formatMinutes(minutes: number): string`, `currentTarget(ticket: { status: TicketStatus; sla: SlaView }): { target: 'First response' | 'Resolution'; state: SlaState; due: string }`, `formatRate(rate: number | null | undefined): string`.
  - `SlaBadge({ state, due?, target? })`.
  - `AgentSelect({ id, label, value, onChange, current?, error?, blankLabel? })` ('' = unassigned).
  - `CategorySelect({ id, label, value, onChange, current?, error?, blankLabel? })` (active categories; the current one stays listed even when archived).
  - `invalidateHelpDesk(queryClient)` in `features/helpdesk/invalidation.ts`. Every HelpDesk query key starts with `'helpdesk'`: `['helpdesk', 'dashboard']`, `['helpdesk', 'categories', archived]`, `['helpdesk', 'sla-policies']`, `['helpdesk', 'agents', q]`, `['helpdesk', 'tickets', filters]`, `['helpdesk', 'ticket', id]`, `['helpdesk', 'messages', id]`, `['helpdesk', 'context', id]`, `['helpdesk', 'articles', filters]`, `['helpdesk', 'article', id]`.
  - `HelpDeskLayout` with `TABS`; `helpdeskChildren: RouteObject[]` in `features/helpdesk/routes.tsx` that later tasks extend.

- [ ] **Step 1: Types**

Append to `frontend/src/lib/api/types.ts`:

```ts
export type TicketStatus = 'NEW' | 'OPEN' | 'PENDING' | 'RESOLVED' | 'CLOSED'
export type TicketPriority = 'LOW' | 'NORMAL' | 'HIGH' | 'URGENT'
export type TicketChannel = 'PHONE' | 'EMAIL' | 'WALK_IN' | 'WEB' | 'OTHER'
export type SlaState = 'ON_TRACK' | 'AT_RISK' | 'PAUSED' | 'MET' | 'BREACHED'
export type MessageKind = 'PUBLIC_REPLY' | 'INTERNAL_NOTE' | 'CUSTOMER_MESSAGE'
export type ArticleStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'

export interface CategoryRef {
  id: string
  name: string
}

export interface CategoryView extends CategoryRef {
  description: string | null
  defaultAssignee: MemberRef | null
  position: number
  archivedAt: string | null
  version: number
}

export interface SlaPolicyView {
  priority: TicketPriority
  firstResponseMinutes: number
  resolutionMinutes: number
  version: number
}

export interface TicketProductRef {
  id: string
  sku: string
  name: string
}

/** Any record the ticket concerns; `label` is null when the viewer may not read that type. */
export interface LinkedRecord {
  type: string
  id: string
  label: string | null
}

export interface SlaView {
  firstResponseDueAt: string
  firstRespondedAt: string | null
  firstResponseState: SlaState
  resolutionDueAt: string
  resolvedAt: string | null
  resolutionState: SlaState
  pausedAt: string | null
}

export interface TicketView {
  id: string
  number: string
  subject: string
  description: string
  requester: PartyRef | null
  product: TicketProductRef | null
  linked: LinkedRecord | null
  category: CategoryRef | null
  priority: TicketPriority
  channel: TicketChannel
  assignee: MemberRef | null
  status: TicketStatus
  sla: SlaView
  resolutionNote: string | null
  reopenCount: number
  closedAt: string | null
  createdBy: MemberRef | null
  createdAt: string
  updatedAt: string
  version: number
}

export interface TicketSummary {
  id: string
  number: string
  subject: string
  requester: PartyRef | null
  category: CategoryRef | null
  priority: TicketPriority
  status: TicketStatus
  assignee: MemberRef | null
  sla: SlaView
  createdAt: string
  updatedAt: string
}

export interface MessageView {
  id: string
  kind: MessageKind
  body: string
  author: MemberRef | null
  /** The address a public reply was emailed to; null when no email was sent. */
  emailedTo: string | null
  createdAt: string
}

/** A posted message and the ticket as it is afterwards (status and SLA may have moved). */
export interface MessagePosted {
  message: MessageView
  ticket: TicketView
}

export interface ArticleView {
  id: string
  title: string
  body: string
  category: CategoryRef | null
  status: ArticleStatus
  author: MemberRef | null
  publishedAt: string | null
  createdAt: string
  updatedAt: string
  version: number
}

export interface ArticleSummary {
  id: string
  title: string
  /** The first 200 characters of the body, on one line. */
  excerpt: string
  category: CategoryRef | null
  status: ArticleStatus
  publishedAt: string | null
  updatedAt: string
}

export interface TicketContext {
  previousTickets: TicketSummary[]
  possibleDuplicates: TicketSummary[]
  suggestedArticles: ArticleSummary[]
}

/** D15. Rates are 0–1; null (or absent) when nothing in the window can be measured. */
export interface HelpDeskDashboard {
  openByStatus: Partial<Record<TicketStatus, number>>
  openByPriority: Partial<Record<TicketPriority, number>>
  unassigned: number
  breached: number
  atRisk: number
  last30Days: {
    created: number
    resolved: number
    averageFirstResponseMinutes?: number | null
    medianFirstResponseMinutes?: number | null
    averageResolutionMinutes?: number | null
    firstResponseMetRate?: number | null
    resolutionMetRate?: number | null
    reopenRate?: number | null
  }
}
```

and change `SubjectType` to:

```ts
export type SubjectType =
  | 'PARTY'
  | 'PRODUCT'
  | 'LEAD'
  | 'OPPORTUNITY'
  | 'PURCHASE_ORDER'
  | 'SALES_ORDER'
  | 'TICKET'
  | 'KB_ARTICLE'
```

- [ ] **Step 2: Permissions, subject paths, search labels, nav and the settings tab**

In `PERMISSIONS` (after `reorderManage`), add:

```ts
  ticketRead: 'helpdesk.ticket.read',
  ticketManage: 'helpdesk.ticket.manage',
  ticketAssign: 'helpdesk.ticket.assign',
  ticketResolve: 'helpdesk.ticket.resolve',
  helpdeskSettings: 'helpdesk.settings.manage',
  articleRead: 'helpdesk.article.read',
  articleManage: 'helpdesk.article.manage',
```

and update its doc comment's catalog list to `(V3, V9–V24 catalog)`.

In `SubjectLink.tsx`'s `subjectPath`, before `return null`:

```ts
  if (type === 'TICKET') return `/app/helpdesk/tickets/${id}`
  if (type === 'KB_ARTICLE') return `/app/helpdesk/articles/${id}`
```

In `GlobalSearch.tsx`'s `TYPE_LABELS`, add `TICKET: 'Ticket', KB_ARTICLE: 'Article',`.

In `nav.ts`'s `SETTINGS_TABS`, append:

```ts
  { to: '/app/settings/helpdesk', label: 'HelpDesk', anyOf: [PERMISSIONS.helpdeskSettings] },
```

- [ ] **Step 3: Fixtures**

Add to `frontend/src/test/records.ts` (and the new type names to its `import type` list):

```ts
const MEERA = { id: 'p-meera', name: 'Meera Iyer' }
const ADA = { id: 'u-ada', name: 'Ada Lovelace' }

export function anSla(overrides: Partial<SlaView> = {}): SlaView {
  return {
    firstResponseDueAt: '2026-10-09T17:00:00Z',
    firstRespondedAt: null,
    firstResponseState: 'ON_TRACK',
    resolutionDueAt: '2026-10-11T09:00:00Z',
    resolvedAt: null,
    resolutionState: 'ON_TRACK',
    pausedAt: null,
    ...overrides,
  }
}

export function aTicket(overrides: Partial<TicketView> = {}): TicketView {
  return {
    id: 't-1',
    number: 'T-00001',
    subject: 'Printer jams on every page',
    description: 'Since Monday the printer jams on every page.',
    requester: MEERA,
    product: { id: 'pr-widget', sku: 'W-1', name: 'Widget' },
    linked: null,
    category: { id: 'c-general', name: 'General' },
    priority: 'NORMAL',
    channel: 'PHONE',
    assignee: null,
    status: 'NEW',
    sla: anSla(),
    resolutionNote: null,
    reopenCount: 0,
    closedAt: null,
    createdBy: ADA,
    createdAt: '2026-10-09T09:00:00Z',
    updatedAt: '2026-10-09T09:00:00Z',
    version: 0,
    ...overrides,
  }
}

export function aTicketSummary(overrides: Partial<TicketSummary> = {}): TicketSummary {
  return {
    id: 't-1',
    number: 'T-00001',
    subject: 'Printer jams on every page',
    requester: MEERA,
    category: { id: 'c-general', name: 'General' },
    priority: 'NORMAL',
    status: 'NEW',
    assignee: null,
    sla: anSla(),
    createdAt: '2026-10-09T09:00:00Z',
    updatedAt: '2026-10-09T09:00:00Z',
    ...overrides,
  }
}

export function aCategory(overrides: Partial<CategoryView> = {}): CategoryView {
  return {
    id: 'c-general',
    name: 'General',
    description: null,
    defaultAssignee: null,
    position: 0,
    archivedAt: null,
    version: 0,
    ...overrides,
  }
}

export function defaultSlaPolicies(): SlaPolicyView[] {
  return [
    { priority: 'LOW', firstResponseMinutes: 1440, resolutionMinutes: 7200, version: 0 },
    { priority: 'NORMAL', firstResponseMinutes: 480, resolutionMinutes: 2880, version: 0 },
    { priority: 'HIGH', firstResponseMinutes: 240, resolutionMinutes: 1440, version: 0 },
    { priority: 'URGENT', firstResponseMinutes: 60, resolutionMinutes: 240, version: 0 },
  ]
}

export function anAgent(overrides: Partial<AssigneeView> = {}): AssigneeView {
  return { id: 'u-ravi', name: 'Ravi Kumar', email: 'ravi@acme.test', ...overrides }
}

export function aMessage(overrides: Partial<MessageView> = {}): MessageView {
  return {
    id: 'msg-1',
    kind: 'PUBLIC_REPLY',
    body: 'Please try clearing the paper tray.',
    author: ADA,
    emailedTo: 'meera@deccan.test',
    createdAt: '2026-10-09T09:30:00Z',
    ...overrides,
  }
}

export function anArticle(overrides: Partial<ArticleView> = {}): ArticleView {
  return {
    id: 'a-1',
    title: 'Clearing a paper jam',
    body: 'Open the rear tray and pull the sheet out gently.',
    category: { id: 'c-general', name: 'General' },
    status: 'PUBLISHED',
    author: ADA,
    publishedAt: '2026-10-08T09:00:00Z',
    createdAt: '2026-10-08T08:00:00Z',
    updatedAt: '2026-10-08T09:00:00Z',
    version: 1,
    ...overrides,
  }
}

export function anArticleSummary(overrides: Partial<ArticleSummary> = {}): ArticleSummary {
  return {
    id: 'a-1',
    title: 'Clearing a paper jam',
    excerpt: 'Open the rear tray and pull the sheet out gently.',
    category: { id: 'c-general', name: 'General' },
    status: 'PUBLISHED',
    publishedAt: '2026-10-08T09:00:00Z',
    updatedAt: '2026-10-08T09:00:00Z',
    ...overrides,
  }
}

export function aTicketContext(overrides: Partial<TicketContext> = {}): TicketContext {
  return { previousTickets: [], possibleDuplicates: [], suggestedArticles: [], ...overrides }
}

export function aHelpDeskDashboard(overrides: Partial<HelpDeskDashboard> = {}): HelpDeskDashboard {
  return {
    openByStatus: { NEW: 2, OPEN: 3, PENDING: 1 },
    openByPriority: { LOW: 1, NORMAL: 3, HIGH: 1, URGENT: 1 },
    unassigned: 2,
    breached: 1,
    atRisk: 1,
    last30Days: {
      created: 9,
      resolved: 4,
      averageFirstResponseMinutes: 95,
      medianFirstResponseMinutes: 60,
      averageResolutionMinutes: 1500,
      firstResponseMetRate: 0.75,
      resolutionMetRate: 1,
      reopenRate: 0.25,
    },
    ...overrides,
  }
}
```

- [ ] **Step 4: Write the failing tests for the SLA helpers**

`frontend/src/features/helpdesk/sla.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import { anSla } from '@/test/records'
import { currentTarget, formatMinutes, formatRate } from './sla'

describe('formatMinutes', () => {
  it('uses the largest units that fit', () => {
    expect(formatMinutes(45)).toBe('45 min')
    expect(formatMinutes(60)).toBe('1 h')
    expect(formatMinutes(90)).toBe('1 h 30 min')
    expect(formatMinutes(1440)).toBe('1 d')
    expect(formatMinutes(1500)).toBe('1 d 1 h')
    expect(formatMinutes(7200)).toBe('5 d')
  })

  it('rounds fractions to whole minutes and never shows a negative', () => {
    expect(formatMinutes(315.4)).toBe('5 h 15 min')
    expect(formatMinutes(0.2)).toBe('0 min')
    expect(formatMinutes(-3)).toBe('0 min')
  })
})

describe('formatRate', () => {
  it('shows a percentage, or a dash when there is nothing to measure', () => {
    expect(formatRate(0.5)).toBe('50%')
    expect(formatRate(1)).toBe('100%')
    expect(formatRate(0.333)).toBe('33%')
    expect(formatRate(null)).toBe('—')
    expect(formatRate(undefined)).toBe('—')
  })
})

describe('currentTarget', () => {
  it('is the first response until someone has replied', () => {
    const sla = anSla({ firstResponseState: 'AT_RISK' })
    expect(currentTarget({ status: 'NEW', sla })).toEqual({
      target: 'First response',
      state: 'AT_RISK',
      due: sla.firstResponseDueAt,
    })
  })

  it('is the resolution once replied, and for resolved or closed tickets', () => {
    const replied = anSla({ firstRespondedAt: '2026-10-09T10:00:00Z', resolutionState: 'PAUSED' })
    expect(currentTarget({ status: 'PENDING', sla: replied })).toMatchObject({
      target: 'Resolution',
      state: 'PAUSED',
    })
    const resolved = anSla({ resolutionState: 'MET', resolvedAt: '2026-10-10T09:00:00Z' })
    expect(currentTarget({ status: 'RESOLVED', sla: resolved })).toMatchObject({
      target: 'Resolution',
      state: 'MET',
    })
  })
})
```

Run: `cd frontend && npx vitest run src/features/helpdesk/sla.test.ts` — Expected: FAIL (module not found).

- [ ] **Step 5: Labels, SLA helpers, badge, invalidation and selects**

`frontend/src/features/helpdesk/labels.ts`:

```ts
import type {
  ArticleStatus,
  MessageKind,
  SlaState,
  TicketChannel,
  TicketPriority,
  TicketStatus,
} from '@/lib/api/types'

export const TICKET_STATUS_LABELS: Record<TicketStatus, string> = {
  NEW: 'New',
  OPEN: 'Open',
  PENDING: 'Waiting on customer',
  RESOLVED: 'Resolved',
  CLOSED: 'Closed',
}

export const OPEN_STATUSES: TicketStatus[] = ['NEW', 'OPEN', 'PENDING']

export const PRIORITY_LABELS: Record<TicketPriority, string> = {
  LOW: 'Low',
  NORMAL: 'Normal',
  HIGH: 'High',
  URGENT: 'Urgent',
}

/** Most urgent first. */
export const PRIORITIES: TicketPriority[] = ['URGENT', 'HIGH', 'NORMAL', 'LOW']

export const CHANNEL_LABELS: Record<TicketChannel, string> = {
  PHONE: 'Phone',
  EMAIL: 'Email',
  WALK_IN: 'Walk-in',
  WEB: 'Web',
  OTHER: 'Other',
}

export const SLA_STATE_LABELS: Record<SlaState, string> = {
  ON_TRACK: 'On track',
  AT_RISK: 'At risk',
  PAUSED: 'Paused',
  MET: 'Met',
  BREACHED: 'Breached',
}

export const MESSAGE_KIND_LABELS: Record<MessageKind, string> = {
  PUBLIC_REPLY: 'Reply to customer',
  INTERNAL_NOTE: 'Internal note',
  CUSTOMER_MESSAGE: 'Customer message',
}

export const ARTICLE_STATUS_LABELS: Record<ArticleStatus, string> = {
  DRAFT: 'Draft',
  PUBLISHED: 'Published',
  ARCHIVED: 'Archived',
}
```

`frontend/src/features/helpdesk/sla.ts`:

```ts
import type { SlaState, SlaView, TicketStatus } from '@/lib/api/types'

/** 90 → "1 h 30 min", 1500 → "1 d 1 h": the two largest units that apply. */
export function formatMinutes(minutes: number): string {
  const total = Math.max(0, Math.round(minutes))
  const days = Math.floor(total / 1440)
  const hours = Math.floor((total % 1440) / 60)
  const mins = total % 60
  const parts = [
    days > 0 ? `${days} d` : null,
    hours > 0 ? `${hours} h` : null,
    mins > 0 && days === 0 ? `${mins} min` : null,
  ].filter((part): part is string => part !== null)
  return parts.length > 0 ? parts.join(' ') : '0 min'
}

/** 0.5 → "50%"; nothing to measure → "—". */
export function formatRate(rate: number | null | undefined): string {
  return rate == null ? '—' : `${Math.round(rate * 100)}%`
}

/** The target that matters now: the first response until someone has replied, then the resolution. */
export function currentTarget(ticket: { status: TicketStatus; sla: SlaView }): {
  target: 'First response' | 'Resolution'
  state: SlaState
  due: string
} {
  const { sla, status } = ticket
  if (sla.firstRespondedAt === null && status !== 'RESOLVED' && status !== 'CLOSED')
    return { target: 'First response', state: sla.firstResponseState, due: sla.firstResponseDueAt }
  return { target: 'Resolution', state: sla.resolutionState, due: sla.resolutionDueAt }
}
```

`frontend/src/features/helpdesk/SlaBadge.tsx`:

```tsx
import { Badge } from '@/components/ui/badge'
import type { SlaState } from '@/lib/api/types'
import { formatDateTime } from '@/lib/format'
import { SLA_STATE_LABELS } from './labels'

const VARIANTS: Record<SlaState, 'default' | 'secondary' | 'destructive' | 'outline'> = {
  BREACHED: 'destructive',
  AT_RISK: 'default',
  ON_TRACK: 'secondary',
  PAUSED: 'outline',
  MET: 'outline',
}

/** "First response: At risk", with the due time on hover. */
export function SlaBadge({ state, due, target }: { state: SlaState; due?: string; target?: string }) {
  const text = `${target ? `${target}: ` : ''}${SLA_STATE_LABELS[state]}`
  return (
    <Badge variant={VARIANTS[state]} title={due ? `Due ${formatDateTime(due)}` : undefined}>
      {text}
    </Badge>
  )
}
```

`frontend/src/features/helpdesk/invalidation.ts`:

```ts
import type { QueryClient } from '@tanstack/react-query'

/** Tickets, their SLA, context and the dashboard depend on each other: any HelpDesk write refreshes them all. */
export async function invalidateHelpDesk(queryClient: QueryClient): Promise<void> {
  await queryClient.invalidateQueries({ queryKey: ['helpdesk'] })
}
```

`frontend/src/features/helpdesk/AgentSelect.tsx`:

```tsx
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Input } from '@/components/ui/input'
import { describedBy, Field } from '@/components/form/Field'
import { NativeSelect } from '@/components/form/NativeSelect'
import { useApi } from '@/lib/api/ApiContext'
import type { AssigneeView, MemberRef } from '@/lib/api/types'
import { toQuery } from '@/lib/query'

/** An active team member from GET /helpdesk/agents. '' = unassigned; the current and picked agent stay listed. */
export function AgentSelect({
  id,
  label,
  value,
  onChange,
  current,
  error,
  blankLabel = 'Unassigned',
}: {
  id: string
  label: string
  value: string
  onChange: (id: string) => void
  current?: MemberRef | null
  error?: string
  blankLabel?: string
}) {
  const api = useApi()
  const [search, setSearch] = useState('')
  const [picked, setPicked] = useState<MemberRef | null>(null)
  const agents = useQuery({
    queryKey: ['helpdesk', 'agents', search.trim()],
    queryFn: () => api.get<AssigneeView[]>(`/helpdesk/agents?${toQuery({ q: search.trim() })}`),
  })
  const options = agents.data ?? []
  const pinned = [current, picked].filter(
    (m, i, all): m is MemberRef => !!m && all.findIndex((o) => o?.id === m.id) === i,
  )
  const searchId = `${id}-search`
  return (
    <div className="grid gap-3 sm:grid-cols-2">
      <Field id={searchId} label="Find a teammate">
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
            const chosen = e.target.value
            setPicked(
              options.find((m) => m.id === chosen) ?? pinned.find((m) => m.id === chosen) ?? null,
            )
            onChange(chosen)
          }}
        >
          <option value="">{blankLabel}</option>
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

`frontend/src/features/helpdesk/CategorySelect.tsx`:

```tsx
import { useQuery } from '@tanstack/react-query'
import { describedBy, Field } from '@/components/form/Field'
import { NativeSelect } from '@/components/form/NativeSelect'
import { useApi } from '@/lib/api/ApiContext'
import type { CategoryRef, CategoryView } from '@/lib/api/types'

/** Active ticket categories ('' = none). A ticket's current category stays listed even if archived since. */
export function CategorySelect({
  id,
  label,
  value,
  onChange,
  current,
  error,
  blankLabel = 'No category',
}: {
  id: string
  label: string
  value: string
  onChange: (id: string, category: CategoryView | null) => void
  current?: CategoryRef | null
  error?: string
  blankLabel?: string
}) {
  const api = useApi()
  const categories = useQuery({
    queryKey: ['helpdesk', 'categories', false],
    queryFn: () => api.get<CategoryView[]>('/helpdesk/categories'),
  })
  const options = categories.data ?? []
  const extra = current && !options.some((c) => c.id === current.id) ? [current] : []
  return (
    <Field id={id} label={label} error={error}>
      <NativeSelect
        id={id}
        value={value}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy(id, error)}
        onChange={(e) =>
          onChange(e.target.value, options.find((c) => c.id === e.target.value) ?? null)
        }
      >
        <option value="">{blankLabel}</option>
        {extra.map((c) => (
          <option key={c.id} value={c.id}>
            {c.name} (archived)
          </option>
        ))}
        {options.map((c) => (
          <option key={c.id} value={c.id}>
            {c.name}
          </option>
        ))}
      </NativeSelect>
    </Field>
  )
}
```

Run: `cd frontend && npx vitest run src/features/helpdesk/sla.test.ts` — Expected: PASS.

- [ ] **Step 6: Write the failing tests for the area, the dashboard and the settings page**

`frontend/src/features/helpdesk/HelpDeskLayout.test.tsx`:

```tsx
import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ALL_TENANT_PERMISSIONS } from '@/features/auth/permissions'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aHelpDeskDashboard } from '@/test/records'
import { renderApp } from '@/test/renderApp'

describe('HelpDeskLayout', () => {
  it('explains that HelpDesk is off when the module is disabled', async () => {
    const server = fakeServer()
    signedIn(server, testProfile({ modules: [] }))
    renderApp({ server, path: '/app/helpdesk' })
    expect(
      await screen.findByText('HelpDesk is not enabled for this workspace.'),
    ).toBeInTheDocument()
  })

  it('shows the sections the user may open', async () => {
    const server = fakeServer()
    signedIn(server, testProfile({ modules: ['HELPDESK'] })).on('GET /helpdesk/dashboard', {
      body: aHelpDeskDashboard(),
    })
    renderApp({ server, path: '/app/helpdesk' })
    const nav = await screen.findByRole('navigation', { name: 'HelpDesk' })
    expect(nav).toHaveTextContent('Dashboard')
    expect(nav).toHaveTextContent('Tickets')
    expect(nav).toHaveTextContent('Knowledge base')
  })

  it('has no sections without any HelpDesk permission', async () => {
    const server = fakeServer()
    signedIn(
      server,
      testProfile({
        modules: ['HELPDESK'],
        permissions: [...ALL_TENANT_PERMISSIONS].filter((p) => !p.startsWith('helpdesk.')),
      }),
    )
    renderApp({ server, path: '/app/helpdesk' })
    expect(await screen.findByText("You don't have access to this page")).toBeInTheDocument()
  })
})
```

(Task 10 adds the "article reader lands on the knowledge base" case once that page exists.)

`frontend/src/features/helpdesk/HelpDeskDashboardPage.test.tsx`:

```tsx
import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aHelpDeskDashboard } from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup(dashboard = aHelpDeskDashboard()) {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['HELPDESK'] })).on('GET /helpdesk/dashboard', {
    body: dashboard,
  })
  return renderApp({ server, path: '/app/helpdesk' })
}

describe('HelpDeskDashboardPage', () => {
  it('shows open work with links to the matching tickets', async () => {
    setup()
    const open = await screen.findByRole('region', { name: 'Open tickets' })
    for (const [name, href, count] of [
      ['Breached', '/app/helpdesk/tickets?sla=breached', '1'],
      ['At risk', '/app/helpdesk/tickets?sla=at_risk', '1'],
      ['Unassigned', '/app/helpdesk/tickets?assignee=unassigned', '2'],
    ]) {
      const link = within(open).getByRole('link', { name })
      expect(link).toHaveAttribute('href', href)
      expect(link.closest('div')).toHaveTextContent(`${name}${count}`)
    }
    expect(within(open).getByText('Waiting on customer')).toBeInTheDocument()
    const priority = screen.getByRole('region', { name: 'Open by priority' })
    expect(within(priority).getByText('Urgent')).toBeInTheDocument()
  })

  it('shows the last 30 days in plain units', async () => {
    setup()
    const month = await screen.findByRole('region', { name: 'Last 30 days' })
    expect(within(month).getByText('1 h 35 min')).toBeInTheDocument() // average first response
    expect(within(month).getByText('1 h')).toBeInTheDocument() // median first response
    expect(within(month).getByText('1 d 1 h')).toBeInTheDocument() // average resolution
    expect(within(month).getByText('75%')).toBeInTheDocument()
    expect(within(month).getByText('25%')).toBeInTheDocument()
  })

  it('shows dashes when nothing can be measured yet', async () => {
    setup(
      aHelpDeskDashboard({
        openByStatus: { NEW: 0, OPEN: 0, PENDING: 0 },
        unassigned: 0,
        breached: 0,
        atRisk: 0,
        last30Days: { created: 0, resolved: 0 },
      }),
    )
    const month = await screen.findByRole('region', { name: 'Last 30 days' })
    expect(within(month).getAllByText('—').length).toBeGreaterThanOrEqual(6)
  })
})
```

`frontend/src/features/settings/HelpDeskSettingsPage.test.tsx`:

```tsx
import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aCategory, anAgent, defaultSlaPolicies } from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup() {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['HELPDESK'] }))
    .on('GET /helpdesk/categories', (request) => ({
      body:
        request.query.get('archived') === 'true'
          ? [aCategory({ id: 'c-old', name: 'Old', archivedAt: '2026-10-01T00:00:00Z' })]
          : [
              aCategory(),
              aCategory({
                id: 'c-billing',
                name: 'Billing',
                position: 1,
                defaultAssignee: { id: 'u-ravi', name: 'Ravi Kumar' },
              }),
            ],
    }))
    .on('GET /helpdesk/sla-policies', { body: defaultSlaPolicies() })
    .on('GET /helpdesk/agents', { body: [anAgent()] })
  return renderApp({ server, path: '/app/settings/helpdesk' })
}

describe('HelpDeskSettingsPage', () => {
  it('lists categories with their default assignee, and archived ones to restore', async () => {
    setup()
    const categories = await screen.findByRole('region', { name: 'Ticket categories' })
    const billing = (await within(categories).findByText('Billing')).closest('tr') as HTMLElement
    expect(within(billing).getByText('Ravi Kumar')).toBeInTheDocument()
    expect(
      await within(categories).findByRole('button', { name: 'Restore Old' }),
    ).toBeInTheDocument()
  })

  it('creates a category that routes to a teammate', async () => {
    const { server, user } = setup()
    server.on('POST /helpdesk/categories', { status: 201, body: aCategory({ id: 'c-new' }) })
    await user.click(await screen.findByRole('button', { name: 'New category' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Name'), 'Warranty')
    await user.type(within(dialog).getByLabelText('Description'), 'Repairs under warranty')
    await user.selectOptions(within(dialog).getByLabelText('Default assignee'), 'u-ravi')
    await user.click(within(dialog).getByRole('button', { name: 'Create category' }))
    expect(server.callsTo('POST /helpdesk/categories')[0].body).toEqual({
      name: 'Warranty',
      description: 'Repairs under warranty',
      defaultAssigneeId: 'u-ravi',
    })
  })

  it('shows a duplicate name from the server on the field', async () => {
    const { server, user } = setup()
    server.on('POST /helpdesk/categories', {
      status: 400,
      body: {
        detail: 'Request validation failed.',
        errors: [{ field: 'name', message: 'A category with this name already exists.' }],
      },
    })
    await user.click(await screen.findByRole('button', { name: 'New category' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Name'), 'billing')
    await user.click(within(dialog).getByRole('button', { name: 'Create category' }))
    expect(
      await within(dialog).findByText('A category with this name already exists.'),
    ).toBeInTheDocument()
  })

  it('archives a category after confirmation', async () => {
    const { server, user } = setup()
    server.on('POST /helpdesk/categories/:id/archive', { body: aCategory({ id: 'c-billing' }) })
    await user.click(await screen.findByRole('button', { name: 'Archive Billing' }))
    await user.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Archive' }),
    )
    expect(server.callsTo('POST /helpdesk/categories/:id/archive')[0].params.id).toBe('c-billing')
  })

  it('shows SLA targets most urgent first and edits one', async () => {
    const { server, user } = setup()
    const sla = await screen.findByRole('region', { name: 'SLA policies' })
    const rows = await within(sla).findAllByRole('row')
    expect(rows[1]).toHaveTextContent('Urgent')
    expect(rows[1]).toHaveTextContent('1 h')
    expect(rows[1]).toHaveTextContent('4 h')
    expect(rows[4]).toHaveTextContent('Low')
    server.on('PUT /helpdesk/sla-policies/:priority', {
      body: { priority: 'URGENT', firstResponseMinutes: 30, resolutionMinutes: 240, version: 1 },
    })
    await user.click(within(sla).getByRole('button', { name: 'Edit Urgent targets' }))
    const dialog = await screen.findByRole('dialog')
    const first = within(dialog).getByLabelText('First response (minutes)')
    await user.clear(first)
    await user.type(first, '30')
    await user.click(within(dialog).getByRole('button', { name: 'Save targets' }))
    const call = server.callsTo('PUT /helpdesk/sla-policies/:priority')[0]
    expect(call.params.priority).toBe('URGENT')
    expect(call.body).toEqual({ firstResponseMinutes: 30, resolutionMinutes: 240, version: 0 })
  })

  it('refuses a target of zero minutes before calling the server', async () => {
    const { server, user } = setup()
    const sla = await screen.findByRole('region', { name: 'SLA policies' })
    await user.click(await within(sla).findByRole('button', { name: 'Edit Low targets' }))
    const dialog = await screen.findByRole('dialog')
    const first = within(dialog).getByLabelText('First response (minutes)')
    await user.clear(first)
    await user.type(first, '0')
    await user.click(within(dialog).getByRole('button', { name: 'Save targets' }))
    expect(
      await within(dialog).findByText('Enter a number of minutes greater than 0.'),
    ).toBeInTheDocument()
    expect(server.callsTo('PUT /helpdesk/sla-policies/:priority')).toHaveLength(0)
  })
})
```

Change `frontend/src/features/shell/ComingSoonPage.test.tsx` so the module cases use HRMS (HelpDesk is no longer "coming soon"): in the first two tests replace `modules: ['HELPDESK']` with `modules: ['HRMS']`, `/app/helpdesk` with `/app/hrms`, the heading `'HelpDesk'` with `'HRMS'`, `/Phase 7/` with `/Phase 8/`, and `'HelpDesk is not enabled for this workspace.'` with `'HRMS is not enabled for this workspace.'`.

Run: `cd frontend && npx vitest run src/features/helpdesk src/features/settings/HelpDeskSettingsPage.test.tsx src/features/shell/ComingSoonPage.test.tsx`
Expected: FAIL — the HelpDesk area and settings page don't exist (`ComingSoonPage.test` passes already).

- [ ] **Step 7: The area, its index and the dashboard**

`frontend/src/features/helpdesk/HelpDeskLayout.tsx`:

```tsx
import { NavLink, Outlet } from 'react-router'
import { NoAccess } from '@/components/states'
import { PERMISSIONS, useCan, type PermissionCode } from '@/features/auth/permissions'
import { useTenantSession } from '@/features/auth/tenantSession'
import { ComingSoonPage } from '@/features/shell/ComingSoonPage'
import { MODULE_PHASES } from '@/features/shell/nav'
import { cn } from '@/lib/utils'

export const TABS: Array<{ to: string; label: string; anyOf: PermissionCode[]; end?: boolean }> = [
  { to: '/app/helpdesk', label: 'Dashboard', anyOf: [PERMISSIONS.ticketRead], end: true },
  { to: '/app/helpdesk/tickets', label: 'Tickets', anyOf: [PERMISSIONS.ticketRead] },
  { to: '/app/helpdesk/articles', label: 'Knowledge base', anyOf: [PERMISSIONS.articleRead] },
]

/** The HelpDesk area: only when the module is enabled; tabs follow the user's HelpDesk permissions. */
export function HelpDeskLayout() {
  const session = useTenantSession()
  const can = useCan()
  const modules = session.state.status === 'authenticated' ? session.state.profile.modules : []
  if (!modules.includes('HELPDESK')) {
    const info = MODULE_PHASES.HELPDESK
    return (
      <ComingSoonPage
        title={info.label}
        phase={info.phase}
        description={info.description}
        module="HELPDESK"
      />
    )
  }
  const tabs = TABS.filter((tab) => can(...tab.anyOf))
  if (tabs.length === 0) return <NoAccess />
  return (
    <div className="space-y-6">
      <nav aria-label="HelpDesk" className="flex flex-wrap gap-1 border-b pb-2">
        {tabs.map((tab) => (
          <NavLink
            key={tab.to}
            to={tab.to}
            end={tab.end}
            className={({ isActive }) =>
              cn(
                'rounded-md px-3 py-1.5 text-sm hover:bg-muted',
                isActive && 'bg-muted font-medium',
              )
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

`frontend/src/features/helpdesk/HelpDeskIndex.tsx`:

```tsx
import { Navigate } from 'react-router'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { HelpDeskDashboardPage } from './HelpDeskDashboardPage'
import { TABS } from './HelpDeskLayout'

/** The dashboard for ticket readers; anyone else lands on the first section they may open. */
export function HelpDeskIndex() {
  const can = useCan()
  if (can(PERMISSIONS.ticketRead)) return <HelpDeskDashboardPage />
  const first = TABS.find((tab) => can(...tab.anyOf))
  return first ? <Navigate to={first.to} replace /> : null
}
```

`frontend/src/features/helpdesk/HelpDeskDashboardPage.tsx`:

```tsx
import { useQuery } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { Link } from 'react-router'
import { ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { useApi } from '@/lib/api/ApiContext'
import type { HelpDeskDashboard } from '@/lib/api/types'
import { OPEN_STATUSES, PRIORITIES, PRIORITY_LABELS, TICKET_STATUS_LABELS } from './labels'
import { formatMinutes, formatRate } from './sla'

function Stat({ label, value, to }: { label: string; value: ReactNode; to?: string }) {
  return (
    <div className="col-span-2 grid grid-cols-subgrid">
      <dt className="text-muted-foreground">
        {to ? (
          <Link to={to} className="underline-offset-4 hover:underline">
            {label}
          </Link>
        ) : (
          label
        )}
      </dt>
      <dd className="text-right font-medium tabular-nums">{value}</dd>
    </div>
  )
}

function minutes(value: number | null | undefined): string {
  return value == null ? '—' : formatMinutes(value)
}

/** D15: open work now, and how the last 30 days went. */
export function HelpDeskDashboardPage() {
  const api = useApi()
  const dashboard = useQuery({
    queryKey: ['helpdesk', 'dashboard'],
    queryFn: () => api.get<HelpDeskDashboard>('/helpdesk/dashboard'),
    refetchOnMount: 'always',
  })
  return (
    <>
      <PageHeader
        title="HelpDesk dashboard"
        description="Open tickets now, and how the last 30 days went against your SLA targets."
      />
      {dashboard.isPending ? (
        <ListSkeleton />
      ) : dashboard.isError ? (
        <ErrorState error={dashboard.error} onRetry={() => void dashboard.refetch()} />
      ) : (
        <div className="grid gap-4 lg:grid-cols-3">
          <section aria-label="Open tickets" className="space-y-3 rounded-lg border p-4">
            <h2 className="font-semibold">Open tickets</h2>
            <dl className="grid grid-cols-[1fr_auto] gap-x-3 gap-y-1 text-sm">
              {OPEN_STATUSES.map((s) => (
                <Stat
                  key={s}
                  label={TICKET_STATUS_LABELS[s]}
                  value={dashboard.data.openByStatus[s] ?? 0}
                />
              ))}
              <Stat
                label="Unassigned"
                value={dashboard.data.unassigned}
                to="/app/helpdesk/tickets?assignee=unassigned"
              />
              <Stat
                label="Breached"
                value={dashboard.data.breached}
                to="/app/helpdesk/tickets?sla=breached"
              />
              <Stat
                label="At risk"
                value={dashboard.data.atRisk}
                to="/app/helpdesk/tickets?sla=at_risk"
              />
            </dl>
          </section>
          <section aria-label="Open by priority" className="space-y-3 rounded-lg border p-4">
            <h2 className="font-semibold">Open by priority</h2>
            <dl className="grid grid-cols-[1fr_auto] gap-x-3 gap-y-1 text-sm">
              {PRIORITIES.map((p) => (
                <Stat
                  key={p}
                  label={PRIORITY_LABELS[p]}
                  value={dashboard.data.openByPriority[p] ?? 0}
                />
              ))}
            </dl>
          </section>
          <section aria-label="Last 30 days" className="space-y-3 rounded-lg border p-4">
            <h2 className="font-semibold">Last 30 days</h2>
            <dl className="grid grid-cols-[1fr_auto] gap-x-3 gap-y-1 text-sm">
              <Stat label="Created" value={dashboard.data.last30Days.created} />
              <Stat label="Resolved" value={dashboard.data.last30Days.resolved} />
              <Stat
                label="Average first response"
                value={minutes(dashboard.data.last30Days.averageFirstResponseMinutes)}
              />
              <Stat
                label="Median first response"
                value={minutes(dashboard.data.last30Days.medianFirstResponseMinutes)}
              />
              <Stat
                label="Average resolution"
                value={minutes(dashboard.data.last30Days.averageResolutionMinutes)}
              />
              <Stat
                label="First response within SLA"
                value={formatRate(dashboard.data.last30Days.firstResponseMetRate)}
              />
              <Stat
                label="Resolved within SLA"
                value={formatRate(dashboard.data.last30Days.resolutionMetRate)}
              />
              <Stat label="Reopened" value={formatRate(dashboard.data.last30Days.reopenRate)} />
            </dl>
          </section>
        </div>
      )}
    </>
  )
}
```

`frontend/src/features/helpdesk/routes.tsx`:

```tsx
import type { RouteObject } from 'react-router'
import { HelpDeskIndex } from './HelpDeskIndex'

/** /app/helpdesk/* children (Tasks 8–10 add theirs). */
export const helpdeskChildren: RouteObject[] = [{ index: true, element: <HelpDeskIndex /> }]
```

In `frontend/src/features/shell/routes.tsx`: import `HelpDeskLayout` and `helpdeskChildren`, replace `modulePage('helpdesk', 'HELPDESK'),` with

```tsx
  { path: 'helpdesk', element: <HelpDeskLayout />, children: helpdeskChildren },
```

and add the settings route before the catch-all in `settingsChildren`:

```tsx
  {
    path: 'helpdesk',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.helpdeskSettings]}>
        <HelpDeskSettingsPage />
      </RequirePermission>
    ),
  },
```

- [ ] **Step 8: Settings → HelpDesk**

`frontend/src/features/settings/HelpDeskSettingsPage.tsx`:

```tsx
import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { FormError } from '@/components/form/FormError'
import { TextAreaField } from '@/components/form/TextAreaField'
import { TextField } from '@/components/form/TextField'
import { ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { requiredText } from '@/features/auth/schemas'
import { AgentSelect } from '@/features/helpdesk/AgentSelect'
import { invalidateHelpDesk } from '@/features/helpdesk/invalidation'
import { PRIORITIES, PRIORITY_LABELS } from '@/features/helpdesk/labels'
import { formatMinutes } from '@/features/helpdesk/sla'
import { useApi } from '@/lib/api/ApiContext'
import { ApiError } from '@/lib/api/client'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import type { CategoryView, SlaPolicyView } from '@/lib/api/types'

const categorySchema = z.object({
  name: requiredText(80),
  description: z.string().trim().max(500, 'Use at most 500 characters.'),
  defaultAssigneeId: z.string(),
})
type CategoryValues = z.infer<typeof categorySchema>
const CATEGORY_FIELDS = ['name', 'description', 'defaultAssigneeId'] as const

const MAX_MINUTES = 86400
const minutesField = z
  .string()
  .trim()
  .refine((v) => /^\d+$/.test(v) && Number(v) >= 1, 'Enter a number of minutes greater than 0.')
  .refine((v) => Number(v) <= MAX_MINUTES, 'Use at most 60 days.')
const slaSchema = z
  .object({ firstResponseMinutes: minutesField, resolutionMinutes: minutesField })
  .refine((v) => Number(v.resolutionMinutes) >= Number(v.firstResponseMinutes), {
    path: ['resolutionMinutes'],
    message: "Resolution can't be shorter than the first response.",
  })
type SlaValues = z.infer<typeof slaSchema>
const SLA_FIELDS = ['firstResponseMinutes', 'resolutionMinutes'] as const

/** Settings → HelpDesk (D5, D8): ticket categories with routing, and SLA targets per priority. */
export function HelpDeskSettingsPage() {
  return (
    <>
      <PageHeader
        title="HelpDesk"
        description="Categories route new tickets to a teammate. SLA targets set how fast tickets are answered and resolved."
      />
      <div className="space-y-8">
        <CategoriesSection />
        <SlaPoliciesSection />
      </div>
    </>
  )
}

function CategoriesSection() {
  const api = useApi()
  const queryClient = useQueryClient()
  const active = useQuery({
    queryKey: ['helpdesk', 'categories', false],
    queryFn: () => api.get<CategoryView[]>('/helpdesk/categories'),
  })
  const archived = useQuery({
    queryKey: ['helpdesk', 'categories', true],
    queryFn: () => api.get<CategoryView[]>('/helpdesk/categories?archived=true'),
  })
  const [editing, setEditing] = useState<CategoryView | 'new' | null>(null)
  const [archiving, setArchiving] = useState<CategoryView | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function act(action: () => Promise<unknown>, success: string) {
    setBusy(true)
    setError(null)
    try {
      await action()
      await invalidateHelpDesk(queryClient)
      toast.success(success)
      return true
    } catch (e) {
      setError(problemMessage(e))
      return false
    } finally {
      setBusy(false)
    }
  }

  return (
    <section aria-label="Ticket categories" className="space-y-3">
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold">Ticket categories</h2>
        <Button onClick={() => setEditing('new')}>New category</Button>
      </div>
      <FormError message={archiving ? null : error} />
      {active.isPending ? (
        <ListSkeleton />
      ) : active.isError ? (
        <ErrorState error={active.error} onRetry={() => void active.refetch()} />
      ) : (
        <div className="overflow-x-auto rounded-lg border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Name</TableHead>
                <TableHead>Description</TableHead>
                <TableHead>Default assignee</TableHead>
                <TableHead className="text-right">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {[...active.data, ...(archived.data ?? [])].map((c) => (
                <TableRow key={c.id}>
                  <TableCell>
                    {c.name} {c.archivedAt && <Badge variant="outline">Archived</Badge>}
                  </TableCell>
                  <TableCell className="max-w-md whitespace-normal">
                    {c.description ?? '—'}
                  </TableCell>
                  <TableCell>{c.defaultAssignee?.name ?? '—'}</TableCell>
                  <TableCell className="space-x-2 text-right">
                    {c.archivedAt ? (
                      <Button
                        size="sm"
                        variant="outline"
                        disabled={busy}
                        aria-label={`Restore ${c.name}`}
                        onClick={() =>
                          void act(
                            () => api.post(`/helpdesk/categories/${c.id}/restore`),
                            `${c.name} restored.`,
                          )
                        }
                      >
                        Restore
                      </Button>
                    ) : (
                      <>
                        <Button
                          size="sm"
                          variant="outline"
                          aria-label={`Edit ${c.name}`}
                          onClick={() => setEditing(c)}
                        >
                          Edit
                        </Button>
                        <Button
                          size="sm"
                          variant="outline"
                          aria-label={`Archive ${c.name}`}
                          onClick={() => {
                            setError(null)
                            setArchiving(c)
                          }}
                        >
                          Archive
                        </Button>
                      </>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
      {editing && (
        <CategoryDialog
          category={editing === 'new' ? undefined : editing}
          onClose={() => setEditing(null)}
        />
      )}
      <ConfirmDialog
        open={archiving !== null}
        title={`Archive ${archiving?.name ?? ''}?`}
        description="New tickets can't use it. Tickets that already have it keep it."
        confirmLabel="Archive"
        busy={busy}
        error={error}
        onCancel={() => {
          setError(null)
          setArchiving(null)
        }}
        onConfirm={() => {
          if (!archiving) return
          void act(
            () => api.post(`/helpdesk/categories/${archiving.id}/archive`),
            `${archiving.name} archived.`,
          ).then((ok) => ok && setArchiving(null))
        }}
      />
    </section>
  )
}

function CategoryDialog({ category, onClose }: { category?: CategoryView; onClose: () => void }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [formError, setFormError] = useState<string | null>(null)
  const form = useForm<CategoryValues>({
    resolver: zodResolver(categorySchema),
    defaultValues: {
      name: category?.name ?? '',
      description: category?.description ?? '',
      defaultAssigneeId: category?.defaultAssignee?.id ?? '',
    },
  })
  const onSubmit = form.handleSubmit(async (values) => {
    setFormError(null)
    const body = {
      name: values.name.trim(),
      description: values.description.trim() || null,
      defaultAssigneeId: values.defaultAssigneeId || null,
      ...(category ? { version: category.version } : {}),
    }
    try {
      if (category) await api.put(`/helpdesk/categories/${category.id}`, body)
      else await api.post('/helpdesk/categories', body)
      await invalidateHelpDesk(queryClient)
      toast.success(category ? 'Changes saved.' : `${body.name} created.`)
      onClose()
    } catch (error) {
      if (category && error instanceof ApiError && error.status === 409)
        void invalidateHelpDesk(queryClient)
      if (!applyFieldErrors(error, form.setError, CATEGORY_FIELDS))
        setFormError(problemMessage(error))
    }
  })
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{category ? `Edit ${category.name}` : 'New category'}</DialogTitle>
          <DialogDescription>
            A new ticket in this category goes to its default assignee unless someone is chosen.
          </DialogDescription>
        </DialogHeader>
        <form noValidate onSubmit={(e) => void onSubmit(e)} className="space-y-4">
          <TextField form={form} name="name" label="Name" maxLength={80} />
          <TextAreaField form={form} name="description" label="Description" maxLength={500} />
          <AgentSelect
            id="field-defaultAssigneeId"
            label="Default assignee"
            blankLabel="Nobody"
            value={form.watch('defaultAssigneeId')}
            current={category?.defaultAssignee}
            error={form.formState.errors.defaultAssigneeId?.message}
            onChange={(id) => form.setValue('defaultAssigneeId', id)}
          />
          <FormError message={formError} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={form.formState.isSubmitting}>
              {category ? 'Save changes' : 'Create category'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

function SlaPoliciesSection() {
  const api = useApi()
  const policies = useQuery({
    queryKey: ['helpdesk', 'sla-policies'],
    queryFn: () => api.get<SlaPolicyView[]>('/helpdesk/sla-policies'),
  })
  const [editing, setEditing] = useState<SlaPolicyView | null>(null)
  const ordered = PRIORITIES.map((p) => policies.data?.find((x) => x.priority === p)).filter(
    (x): x is SlaPolicyView => !!x,
  )
  return (
    <section aria-label="SLA policies" className="space-y-3">
      <h2 className="text-lg font-semibold">SLA policies</h2>
      <p className="text-sm text-muted-foreground">
        Calendar time. Time waiting on the customer doesn&apos;t count towards resolution. Changes
        apply to new tickets and to tickets whose priority changes.
      </p>
      {policies.isPending ? (
        <ListSkeleton />
      ) : policies.isError ? (
        <ErrorState error={policies.error} onRetry={() => void policies.refetch()} />
      ) : (
        <div className="overflow-x-auto rounded-lg border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Priority</TableHead>
                <TableHead>First response</TableHead>
                <TableHead>Resolution</TableHead>
                <TableHead className="text-right">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {ordered.map((p) => (
                <TableRow key={p.priority}>
                  <TableCell>{PRIORITY_LABELS[p.priority]}</TableCell>
                  <TableCell>{formatMinutes(p.firstResponseMinutes)}</TableCell>
                  <TableCell>{formatMinutes(p.resolutionMinutes)}</TableCell>
                  <TableCell className="text-right">
                    <Button
                      size="sm"
                      variant="outline"
                      aria-label={`Edit ${PRIORITY_LABELS[p.priority]} targets`}
                      onClick={() => setEditing(p)}
                    >
                      Edit
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
      {editing && <SlaPolicyDialog policy={editing} onClose={() => setEditing(null)} />}
    </section>
  )
}

function SlaPolicyDialog({ policy, onClose }: { policy: SlaPolicyView; onClose: () => void }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [formError, setFormError] = useState<string | null>(null)
  const form = useForm<SlaValues>({
    resolver: zodResolver(slaSchema),
    defaultValues: {
      firstResponseMinutes: String(policy.firstResponseMinutes),
      resolutionMinutes: String(policy.resolutionMinutes),
    },
  })
  const label = PRIORITY_LABELS[policy.priority]
  const onSubmit = form.handleSubmit(async (values) => {
    setFormError(null)
    try {
      await api.put(`/helpdesk/sla-policies/${policy.priority}`, {
        firstResponseMinutes: Number(values.firstResponseMinutes),
        resolutionMinutes: Number(values.resolutionMinutes),
        version: policy.version,
      })
      await invalidateHelpDesk(queryClient)
      toast.success(`${label} targets saved.`)
      onClose()
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) void invalidateHelpDesk(queryClient)
      if (!applyFieldErrors(error, form.setError, SLA_FIELDS)) setFormError(problemMessage(error))
    }
  })
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{label} targets</DialogTitle>
          <DialogDescription>Minutes from when the ticket is created (1 to 86 400).</DialogDescription>
        </DialogHeader>
        <form noValidate onSubmit={(e) => void onSubmit(e)} className="space-y-4">
          <TextField
            form={form}
            name="firstResponseMinutes"
            label="First response (minutes)"
            inputMode="numeric"
          />
          <TextField
            form={form}
            name="resolutionMinutes"
            label="Resolution (minutes)"
            inputMode="numeric"
          />
          <FormError message={formError} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={form.formState.isSubmitting}>
              Save targets
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
```

(`TextField`'s `inputMode` already allows `'numeric'`.) Import `HelpDeskSettingsPage` in `features/shell/routes.tsx`.

- [ ] **Step 9: Run the tests, then the frontend checks**

Run: `cd frontend && npx vitest run src/features/helpdesk src/features/settings src/features/shell`
Expected: PASS.

Run: `cd frontend && npx prettier --write src && npm run format:check && npm run lint && npm run typecheck && npm test`
Expected: PASS (all suites).

- [ ] **Step 10: Commit**

```bash
git add frontend/src
git commit -m "feat(helpdesk-ui): the HelpDesk area, its dashboard and Settings → HelpDesk"
```

---

### Task 8: The ticket list, its filters, and the ticket form

**Files:**
- Create: `frontend/src/features/helpdesk/CatalogProductPicker.tsx`, `LinkedRecordPicker.tsx`, `TicketFormDialog.tsx`, `TicketsPage.tsx`, `TicketsPage.test.tsx`
- Modify: `frontend/src/features/helpdesk/routes.tsx`

**Interfaces:**
- Consumes (Task 7): types `TicketSummary`, `TicketView`, `TicketPriority`, `TicketChannel`, `PartyRef`, `TicketProductRef`, `LinkedRecord`, `SearchHit`, `AssigneeView`; `PERMISSIONS.ticketRead|ticketManage`; `TICKET_STATUS_LABELS`, `PRIORITY_LABELS`, `PRIORITIES`, `CHANNEL_LABELS`; `currentTarget`, `SlaBadge`, `AgentSelect`, `CategorySelect`, `invalidateHelpDesk`; `PartyPicker` (`features/records/PartyPicker`).
- Produces:
  - `CatalogProductPicker({ id, label, value, onChange, current?, error? })` — any catalog product, `onChange(id, product: TicketProductRef | null)`, '' = none.
  - `LinkedRecordPicker({ id, value, onChange, current?, error? })` — `value` is `'TYPE:uuid'` or '', `onChange(value: string)`; options come from `GET /search?q=` (2+ characters), excluding `TICKET` and `KB_ARTICLE`.
  - `TicketFormDialog({ ticket?, requester?, onClose, onSaved })` — create (POST, may choose an assignee) or edit (PUT with `version`); `requester` preselects the requester on create (Task 10's Tickets panel uses it); `onSaved(saved: TicketView)`.
  - `TicketsPage` at `/app/helpdesk/tickets`, URL filters `q`, `view` ('' open | `PENDING` | `RESOLVED` | `CLOSED` | `all`), `priority`, `assignee` ('' | `me` | `unassigned` | member id), `category`, `sla` ('' | `breached` | `at_risk`), `page`.

- [ ] **Step 1: Write the failing tests**

`frontend/src/features/helpdesk/TicketsPage.test.tsx`:

```tsx
import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import {
  aCategory,
  anAgent,
  anSla,
  aProduct,
  aSummary,
  aTicket,
  aTicketContext,
  aTicketSummary,
  pageOf,
} from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup(path = '/app/helpdesk/tickets', permissions?: string[]) {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['HELPDESK'], ...(permissions ? { permissions } : {}) }))
    .on('GET /helpdesk/tickets', {
      body: pageOf([
        aTicketSummary({
          priority: 'URGENT',
          sla: anSla({ firstResponseState: 'BREACHED' }),
        }),
        aTicketSummary({
          id: 't-2',
          number: 'T-00002',
          subject: 'Invoice shows the wrong GST number',
          status: 'PENDING',
          assignee: { id: 'u-ravi', name: 'Ravi Kumar' },
          sla: anSla({ firstRespondedAt: '2026-10-09T10:00:00Z', resolutionState: 'PAUSED' }),
        }),
      ]),
    })
    // the ticket page (Task 9) opens after a create
    .on('GET /helpdesk/tickets/:id', { body: aTicket({ id: 't-new' }) })
    .on('GET /helpdesk/tickets/:id/messages', { body: [] })
    .on('GET /helpdesk/tickets/:id/context', { body: aTicketContext() })
    .on('GET /activities', { body: pageOf([]) })
    .on('GET /documents', { body: [] })
    .on('GET /helpdesk/categories', { body: [aCategory()] })
    .on('GET /helpdesk/agents', { body: [anAgent()] })
    .on('GET /parties', { body: pageOf([aSummary({ id: 'p-meera', name: 'Meera Iyer' })]) })
    .on('GET /products', { body: pageOf([aProduct()]) })
    .on('GET /search', {
      body: [
        { type: 'SALES_ORDER', id: 'so-1', label: 'SO-00001', detail: 'Meera Iyer', archived: false },
        { type: 'TICKET', id: 't-9', label: 'T-00009 · Old', detail: null, archived: false },
      ],
    })
  return renderApp({ server, path })
}

async function fillRequired(dialog: HTMLElement, user: ReturnType<typeof setup>['user']) {
  await user.type(within(dialog).getByLabelText('Subject'), 'Printer jams')
  await user.type(within(dialog).getByLabelText('Description'), 'Jams on every page.')
  await user.selectOptions(within(dialog).getByLabelText('Requester'), 'p-meera')
}

describe('TicketsPage', () => {
  it('lists open tickets with requester, priority, status, assignee and the SLA that matters now', async () => {
    setup()
    const first = (await screen.findByRole('link', { name: 'T-00001' })).closest('tr') as HTMLElement
    expect(first).toHaveTextContent('Printer jams on every page')
    expect(first).toHaveTextContent('Meera Iyer')
    expect(within(first).getByText('Urgent')).toBeInTheDocument()
    expect(within(first).getByText('First response: Breached')).toBeInTheDocument()
    expect(within(first).getByText('Unassigned')).toBeInTheDocument()
    const second = screen.getByRole('link', { name: 'T-00002' }).closest('tr') as HTMLElement
    expect(within(second).getByText('Waiting on customer')).toBeInTheDocument()
    expect(within(second).getByText('Resolution: Paused')).toBeInTheDocument()
    expect(within(second).getByText('Ravi Kumar')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'T-00001' })).toHaveAttribute(
      'href',
      '/app/helpdesk/tickets/t-1',
    )
  })

  it('asks for open tickets by default and passes every filter to the server', async () => {
    const { server, user } = setup('/app/helpdesk/tickets?sla=breached&assignee=unassigned')
    await screen.findByRole('link', { name: 'T-00001' })
    const first = server.callsTo('GET /helpdesk/tickets')[0].query
    expect(first.get('status')).toBeNull()
    expect(first.get('sla')).toBe('breached')
    expect(first.get('assignee')).toBe('unassigned')
    await user.selectOptions(screen.getByLabelText('Show'), 'all')
    await waitFor(() =>
      expect(server.callsTo('GET /helpdesk/tickets').at(-1)?.query.get('status')).toBe(
        'NEW,OPEN,PENDING,RESOLVED,CLOSED',
      ),
    )
    await user.selectOptions(screen.getByLabelText('Priority'), 'URGENT')
    await user.selectOptions(screen.getByLabelText('Assignee'), 'me')
    await user.selectOptions(screen.getByLabelText('Category'), 'c-general')
    await user.type(screen.getByLabelText('Search tickets'), 'T-00002')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    await waitFor(() => {
      const last = server.callsTo('GET /helpdesk/tickets').at(-1)?.query
      expect(last?.get('priority')).toBe('URGENT')
      expect(last?.get('assignee')).toBe('me')
      expect(last?.get('categoryId')).toBe('c-general')
      expect(last?.get('q')).toBe('T-00002')
    })
  })

  it('creates a ticket for a requester with a product and a related order, then opens it', async () => {
    const { server, user, router } = setup()
    server.on('POST /helpdesk/tickets', { status: 201, body: aTicket({ id: 't-new' }) })
    await user.click(await screen.findByRole('button', { name: 'New ticket' }))
    const dialog = await screen.findByRole('dialog')
    await fillRequired(dialog, user)
    await user.selectOptions(within(dialog).getByLabelText('Product'), 'pr-widget')
    await user.type(within(dialog).getByLabelText('Find a related record'), 'SO-0')
    await within(dialog).findByRole('option', { name: 'Sales order · SO-00001' })
    expect(within(dialog).queryByRole('option', { name: /T-00009/ })).not.toBeInTheDocument()
    await user.selectOptions(within(dialog).getByLabelText('Related record'), 'SALES_ORDER:so-1')
    await user.selectOptions(within(dialog).getByLabelText('Category'), 'c-general')
    await user.selectOptions(within(dialog).getByLabelText('Priority'), 'HIGH')
    await user.selectOptions(within(dialog).getByLabelText('Channel'), 'EMAIL')
    await user.selectOptions(within(dialog).getByLabelText('Assignee'), 'u-ravi')
    await user.click(within(dialog).getByRole('button', { name: 'Create ticket' }))
    await waitFor(() => expect(server.callsTo('POST /helpdesk/tickets')).toHaveLength(1))
    expect(server.callsTo('POST /helpdesk/tickets')[0].body).toEqual({
      subject: 'Printer jams',
      description: 'Jams on every page.',
      requesterId: 'p-meera',
      productId: 'pr-widget',
      linkedType: 'SALES_ORDER',
      linkedId: 'so-1',
      categoryId: 'c-general',
      priority: 'HIGH',
      channel: 'EMAIL',
      assigneeId: 'u-ravi',
    })
    await waitFor(() =>
      expect(router.state.location.pathname).toBe('/app/helpdesk/tickets/t-new'),
    )
  })

  it('leaves optional fields empty as nulls and defaults to normal priority by phone', async () => {
    const { server, user } = setup()
    server.on('POST /helpdesk/tickets', { status: 201, body: aTicket({ id: 't-new' }) })
    await user.click(await screen.findByRole('button', { name: 'New ticket' }))
    const dialog = await screen.findByRole('dialog')
    await fillRequired(dialog, user)
    await user.click(within(dialog).getByRole('button', { name: 'Create ticket' }))
    await waitFor(() => expect(server.callsTo('POST /helpdesk/tickets')).toHaveLength(1))
    expect(server.callsTo('POST /helpdesk/tickets')[0].body).toMatchObject({
      productId: null,
      linkedType: null,
      linkedId: null,
      categoryId: null,
      priority: 'NORMAL',
      channel: 'PHONE',
      assigneeId: null,
    })
  })

  it('needs a subject, a description and a requester before calling the server', async () => {
    const { server, user } = setup()
    await user.click(await screen.findByRole('button', { name: 'New ticket' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Create ticket' }))
    expect(await within(dialog).findAllByText('Required.')).toHaveLength(2)
    expect(within(dialog).getByText('Choose who the ticket is for.')).toBeInTheDocument()
    expect(server.callsTo('POST /helpdesk/tickets')).toHaveLength(0)
  })

  it('shows a refused related record next to the related-record field', async () => {
    const { server, user } = setup()
    server.on('POST /helpdesk/tickets', {
      status: 400,
      body: {
        detail: 'Request validation failed.',
        errors: [{ field: 'linkedId', message: 'Choose a record in this workspace.' }],
      },
    })
    await user.click(await screen.findByRole('button', { name: 'New ticket' }))
    const dialog = await screen.findByRole('dialog')
    await fillRequired(dialog, user)
    await user.type(within(dialog).getByLabelText('Find a related record'), 'SO-0')
    await within(dialog).findByRole('option', { name: 'Sales order · SO-00001' })
    await user.selectOptions(within(dialog).getByLabelText('Related record'), 'SALES_ORDER:so-1')
    await user.click(within(dialog).getByRole('button', { name: 'Create ticket' }))
    expect(
      await within(dialog).findByText('Choose a record in this workspace.'),
    ).toBeInTheDocument()
    expect(within(dialog).getByLabelText('Related record')).toHaveAttribute('aria-invalid', 'true')
  })

  it('hides New ticket from readers', async () => {
    setup('/app/helpdesk/tickets', ['helpdesk.ticket.read'])
    await screen.findByRole('link', { name: 'T-00001' })
    expect(screen.queryByRole('button', { name: 'New ticket' })).not.toBeInTheDocument()
  })

  it('says what to do when nothing matches', async () => {
    const { server } = setup('/app/helpdesk/tickets?sla=breached')
    server.on('GET /helpdesk/tickets', { body: pageOf([]) })
    expect(await screen.findByText('No tickets match these filters.')).toBeInTheDocument()
  })
})
```

(`aProduct()` is the existing fixture with id `pr-widget`.)

- [ ] **Step 2: Run them to verify they fail**

Run: `cd frontend && npx vitest run src/features/helpdesk/TicketsPage.test.tsx`
Expected: FAIL — `/app/helpdesk/tickets` has no route.

- [ ] **Step 3: The two pickers**

`frontend/src/features/helpdesk/CatalogProductPicker.tsx`:

```tsx
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Input } from '@/components/ui/input'
import { describedBy, Field } from '@/components/form/Field'
import { NativeSelect } from '@/components/form/NativeSelect'
import { useApi } from '@/lib/api/ApiContext'
import type { Page, ProductView, TicketProductRef } from '@/lib/api/types'
import { toQuery } from '@/lib/query'

/** Any catalog product (goods or service): a search box over the first 20 matches plus a select. '' = none. */
export function CatalogProductPicker({
  id,
  label,
  value,
  onChange,
  current,
  error,
}: {
  id: string
  label: string
  value: string
  onChange: (id: string, product: TicketProductRef | null) => void
  current?: TicketProductRef | null
  error?: string
}) {
  const api = useApi()
  const [search, setSearch] = useState('')
  const products = useQuery({
    queryKey: ['catalog-product-picker', search.trim()],
    queryFn: () =>
      api.get<Page<ProductView>>(`/products?${toQuery({ q: search.trim(), size: 20 })}`),
  })
  const options: TicketProductRef[] = (products.data?.items ?? []).map((p) => ({
    id: p.id,
    sku: p.sku,
    name: p.name,
  }))
  const all = current && !options.some((p) => p.id === current.id) ? [current, ...options] : options
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
          onChange={(e) =>
            onChange(e.target.value, all.find((p) => p.id === e.target.value) ?? null)
          }
        >
          <option value="">None</option>
          {all.map((p) => (
            <option key={p.id} value={p.id}>
              {p.sku} · {p.name}
            </option>
          ))}
        </NativeSelect>
      </Field>
    </div>
  )
}
```

`frontend/src/features/helpdesk/LinkedRecordPicker.tsx`:

```tsx
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { Input } from '@/components/ui/input'
import { describedBy, Field } from '@/components/form/Field'
import { NativeSelect } from '@/components/form/NativeSelect'
import { useApi } from '@/lib/api/ApiContext'
import type { LinkedRecord, SearchHit } from '@/lib/api/types'
import { toQuery } from '@/lib/query'

const TYPE_LABELS: Record<string, string> = {
  PARTY: 'Directory',
  LEAD: 'Lead',
  OPPORTUNITY: 'Opportunity',
  PRODUCT: 'Product',
  PURCHASE_ORDER: 'Purchase order',
  SALES_ORDER: 'Sales order',
}
/** A ticket can't point at another ticket or at an article. */
const EXCLUDED = new Set(['TICKET', 'KB_ARTICLE'])

interface Option {
  key: string
  text: string
}

function optionOf(type: string, id: string, label: string | null): Option {
  return {
    key: `${type}:${id}`,
    text: `${TYPE_LABELS[type] ?? type} · ${label ?? 'Restricted record'}`,
  }
}

/** Any record the ticket concerns (D3), found with workspace search. Value 'TYPE:uuid', '' = none. */
export function LinkedRecordPicker({
  id,
  value,
  onChange,
  current,
  error,
}: {
  id: string
  value: string
  onChange: (value: string) => void
  current?: LinkedRecord | null
  error?: string
}) {
  const api = useApi()
  const [text, setText] = useState('')
  const [query, setQuery] = useState('')
  const [picked, setPicked] = useState<Option | null>(null)
  useEffect(() => {
    const handle = setTimeout(() => setQuery(text.trim()), 250)
    return () => clearTimeout(handle)
  }, [text])
  const hits = useQuery({
    queryKey: ['search', query],
    queryFn: () => api.get<SearchHit[]>(`/search?${toQuery({ q: query })}`),
    enabled: query.length >= 2,
    placeholderData: keepPreviousData,
  })
  const found = (query.length >= 2 ? (hits.data ?? []) : [])
    .filter((h) => !EXCLUDED.has(h.type))
    .map((h) => optionOf(h.type, h.id, h.label))
  const pinned = [current ? optionOf(current.type, current.id, current.label) : null, picked].filter(
    (o, i, all): o is Option => !!o && all.findIndex((x) => x?.key === o.key) === i,
  )
  const options = [...pinned, ...found.filter((o) => !pinned.some((p) => p.key === o.key))]
  const searchId = `${id}-search`
  return (
    <div className="grid gap-3 sm:grid-cols-2">
      <Field id={searchId} label="Find a related record" hint="An order, a deal, a lead…">
        <Input
          id={searchId}
          type="search"
          autoComplete="off"
          value={text}
          aria-describedby={`${searchId}-hint`}
          onChange={(e) => setText(e.target.value)}
        />
      </Field>
      <Field id={id} label="Related record" error={error}>
        <NativeSelect
          id={id}
          value={value}
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy(id, error)}
          onChange={(e) => {
            setPicked(options.find((o) => o.key === e.target.value) ?? null)
            onChange(e.target.value)
          }}
        >
          <option value="">None</option>
          {options.map((o) => (
            <option key={o.key} value={o.key}>
              {o.text}
            </option>
          ))}
        </NativeSelect>
      </Field>
    </div>
  )
}
```

(`Field` renders the hint as `<p id={`${id}-hint`}>` while there is no error.)

- [ ] **Step 4: The ticket form**

`frontend/src/features/helpdesk/TicketFormDialog.tsx`:

```tsx
import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
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
import { requiredText } from '@/features/auth/schemas'
import { PartyPicker } from '@/features/records/PartyPicker'
import { useApi } from '@/lib/api/ApiContext'
import { ApiError } from '@/lib/api/client'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import type { PartyRef, TicketChannel, TicketPriority, TicketView } from '@/lib/api/types'
import { AgentSelect } from './AgentSelect'
import { CatalogProductPicker } from './CatalogProductPicker'
import { CategorySelect } from './CategorySelect'
import { invalidateHelpDesk } from './invalidation'
import { CHANNEL_LABELS, PRIORITIES, PRIORITY_LABELS } from './labels'
import { LinkedRecordPicker } from './LinkedRecordPicker'

const schema = z.object({
  subject: requiredText(200),
  description: requiredText(10000),
  requesterId: z.string().min(1, 'Choose who the ticket is for.'),
  productId: z.string(),
  linked: z.string(),
  categoryId: z.string(),
  priority: z.enum(['LOW', 'NORMAL', 'HIGH', 'URGENT']),
  channel: z.enum(['PHONE', 'EMAIL', 'WALK_IN', 'WEB', 'OTHER']),
  assigneeId: z.string(),
})
type Values = z.infer<typeof schema>
const FIELDS = [
  'subject',
  'description',
  'requesterId',
  'productId',
  'categoryId',
  'priority',
  'channel',
  'assigneeId',
] as const

function splitLinked(value: string): { linkedType: string | null; linkedId: string | null } {
  const at = value.indexOf(':')
  return at < 0
    ? { linkedType: null, linkedId: null }
    : { linkedType: value.slice(0, at), linkedId: value.slice(at + 1) }
}

/** Create a ticket (D3; may pick an assignee — blank uses the category's default) or edit its details. */
export function TicketFormDialog({
  ticket,
  requester,
  onClose,
  onSaved,
}: {
  ticket?: TicketView
  requester?: PartyRef
  onClose: () => void
  onSaved: (saved: TicketView) => void
}) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [formError, setFormError] = useState<string | null>(null)
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: {
      subject: ticket?.subject ?? '',
      description: ticket?.description ?? '',
      requesterId: ticket?.requester?.id ?? requester?.id ?? '',
      productId: ticket?.product?.id ?? '',
      linked: ticket?.linked ? `${ticket.linked.type}:${ticket.linked.id}` : '',
      categoryId: ticket?.category?.id ?? '',
      priority: ticket?.priority ?? 'NORMAL',
      channel: ticket?.channel ?? 'PHONE',
      assigneeId: '',
    },
  })
  const errors = form.formState.errors
  const revalidate = { shouldValidate: form.formState.isSubmitted }

  const onSubmit = form.handleSubmit(async (values) => {
    setFormError(null)
    const body = {
      subject: values.subject.trim(),
      description: values.description.trim(),
      requesterId: values.requesterId,
      productId: values.productId || null,
      ...splitLinked(values.linked),
      categoryId: values.categoryId || null,
      priority: values.priority,
      channel: values.channel,
      ...(ticket ? { version: ticket.version } : { assigneeId: values.assigneeId || null }),
    }
    try {
      const saved = ticket
        ? await api.put<TicketView>(`/helpdesk/tickets/${ticket.id}`, body)
        : await api.post<TicketView>('/helpdesk/tickets', body)
      queryClient.setQueryData(['helpdesk', 'ticket', saved.id], saved)
      await invalidateHelpDesk(queryClient)
      toast.success(ticket ? 'Changes saved.' : `${saved.number} created.`)
      onSaved(saved)
    } catch (error) {
      // a conflict means the cached ticket is stale: reload it so the next save carries the current version
      if (ticket && error instanceof ApiError && error.status === 409)
        void queryClient.invalidateQueries({ queryKey: ['helpdesk', 'ticket', ticket.id] })
      const linkedError =
        error instanceof ApiError
          ? error.problem.errors?.find((e) => e.field === 'linkedType' || e.field === 'linkedId')
          : undefined
      if (linkedError) form.setError('linked', { type: 'server', message: linkedError.message })
      const applied = applyFieldErrors(error, form.setError, FIELDS)
      if (!applied && !linkedError) setFormError(problemMessage(error))
    }
  })

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{ticket ? `Edit ${ticket.number}` : 'New ticket'}</DialogTitle>
          <DialogDescription>
            Who is asking, about what, and anything it relates to — so whoever picks it up has the
            context.
          </DialogDescription>
        </DialogHeader>
        <form noValidate onSubmit={(e) => void onSubmit(e)} className="space-y-4">
          <TextField form={form} name="subject" label="Subject" maxLength={200} />
          <TextAreaField
            form={form}
            name="description"
            label="Description"
            rows={4}
            maxLength={10000}
          />
          <PartyPicker
            id="field-requesterId"
            label="Requester"
            value={form.watch('requesterId')}
            current={ticket?.requester ?? requester}
            error={errors.requesterId?.message}
            noneLabel="Choose…"
            onChange={(id) => form.setValue('requesterId', id, revalidate)}
          />
          <CatalogProductPicker
            id="field-productId"
            label="Product"
            value={form.watch('productId')}
            current={ticket?.product}
            error={errors.productId?.message}
            onChange={(id) => form.setValue('productId', id, revalidate)}
          />
          <LinkedRecordPicker
            id="field-linked"
            value={form.watch('linked')}
            current={ticket?.linked}
            error={errors.linked?.message}
            onChange={(value) => form.setValue('linked', value, revalidate)}
          />
          <div className="grid gap-3 sm:grid-cols-3">
            <CategorySelect
              id="field-categoryId"
              label="Category"
              value={form.watch('categoryId')}
              current={ticket?.category}
              error={errors.categoryId?.message}
              onChange={(id) => form.setValue('categoryId', id, revalidate)}
            />
            <Field id="field-priority" label="Priority" error={errors.priority?.message}>
              <NativeSelect id="field-priority" {...form.register('priority')}>
                {PRIORITIES.map((p: TicketPriority) => (
                  <option key={p} value={p}>
                    {PRIORITY_LABELS[p]}
                  </option>
                ))}
              </NativeSelect>
            </Field>
            <Field id="field-channel" label="Channel" error={errors.channel?.message}>
              <NativeSelect id="field-channel" {...form.register('channel')}>
                {(Object.keys(CHANNEL_LABELS) as TicketChannel[]).map((c) => (
                  <option key={c} value={c}>
                    {CHANNEL_LABELS[c]}
                  </option>
                ))}
              </NativeSelect>
            </Field>
          </div>
          {!ticket && (
            <AgentSelect
              id="field-assigneeId"
              label="Assignee"
              blankLabel="Category default"
              value={form.watch('assigneeId')}
              error={errors.assigneeId?.message}
              onChange={(id) => form.setValue('assigneeId', id, revalidate)}
            />
          )}
          <FormError message={formError} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={form.formState.isSubmitting}>
              {ticket ? 'Save changes' : 'Create ticket'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
```

- [ ] **Step 5: The list**

`frontend/src/features/helpdesk/TicketsPage.tsx`:

```tsx
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { NativeSelect } from '@/components/form/NativeSelect'
import { Pagination } from '@/components/Pagination'
import { EmptyState, ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useApi } from '@/lib/api/ApiContext'
import type { AssigneeView, CategoryView, Page, TicketSummary } from '@/lib/api/types'
import { formatDateTime } from '@/lib/format'
import { toQuery } from '@/lib/query'
import { PRIORITIES, PRIORITY_LABELS, TICKET_STATUS_LABELS } from './labels'
import { currentTarget } from './sla'
import { SlaBadge } from './SlaBadge'
import { TicketFormDialog } from './TicketFormDialog'

const SIZE = 20
const VIEWS: Array<{ value: string; label: string; status?: string }> = [
  { value: '', label: 'Open tickets' },
  { value: 'PENDING', label: 'Waiting on customer', status: 'PENDING' },
  { value: 'RESOLVED', label: 'Resolved', status: 'RESOLVED' },
  { value: 'CLOSED', label: 'Closed', status: 'CLOSED' },
  { value: 'all', label: 'All tickets', status: 'NEW,OPEN,PENDING,RESOLVED,CLOSED' },
]

export function TicketsPage() {
  const api = useApi()
  const can = useCan()
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const q = params.get('q') ?? ''
  const view = params.get('view') ?? ''
  const priority = params.get('priority') ?? ''
  const assignee = params.get('assignee') ?? ''
  const category = params.get('category') ?? ''
  const sla = params.get('sla') ?? ''
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0)
  const [creating, setCreating] = useState(false)
  const status = VIEWS.find((v) => v.value === view)?.status

  const tickets = useQuery({
    queryKey: ['helpdesk', 'tickets', { q, view, priority, assignee, category, sla, page }],
    queryFn: () =>
      api.get<Page<TicketSummary>>(
        `/helpdesk/tickets?${toQuery({
          q,
          status,
          priority,
          assignee,
          categoryId: category,
          sla,
          page,
          size: SIZE,
        })}`,
      ),
    placeholderData: keepPreviousData,
    refetchOnMount: 'always',
  })
  const agents = useQuery({
    queryKey: ['helpdesk', 'agents', ''],
    queryFn: () => api.get<AssigneeView[]>('/helpdesk/agents'),
  })
  const categories = useQuery({
    queryKey: ['helpdesk', 'categories', false],
    queryFn: () => api.get<CategoryView[]>('/helpdesk/categories'),
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

  const filtered = Boolean(q || view || priority || assignee || category || sla)

  return (
    <>
      <PageHeader
        title="Tickets"
        description="Customer issues with their SLA, newest first."
        actions={
          can(PERMISSIONS.ticketManage) && (
            <Button onClick={() => setCreating(true)}>New ticket</Button>
          )
        }
      />
      <div className="mb-4 flex flex-wrap items-end gap-3">
        <form onSubmit={onSearch} className="flex items-end gap-2" role="search">
          <div className="space-y-1.5">
            <Label htmlFor="ticket-q">Search tickets</Label>
            <Input id="ticket-q" name="q" defaultValue={q} placeholder="Number or subject" />
          </div>
          <Button type="submit" variant="outline">
            Search
          </Button>
        </form>
        <div className="space-y-1.5">
          <Label htmlFor="ticket-view">Show</Label>
          <NativeSelect
            id="ticket-view"
            value={view}
            onChange={(e) => update({ view: e.target.value, page: '' })}
          >
            {VIEWS.map((v) => (
              <option key={v.value} value={v.value}>
                {v.label}
              </option>
            ))}
          </NativeSelect>
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="ticket-priority">Priority</Label>
          <NativeSelect
            id="ticket-priority"
            value={priority}
            onChange={(e) => update({ priority: e.target.value, page: '' })}
          >
            <option value="">Any priority</option>
            {PRIORITIES.map((p) => (
              <option key={p} value={p}>
                {PRIORITY_LABELS[p]}
              </option>
            ))}
          </NativeSelect>
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="ticket-assignee">Assignee</Label>
          <NativeSelect
            id="ticket-assignee"
            value={assignee}
            onChange={(e) => update({ assignee: e.target.value, page: '' })}
          >
            <option value="">Anyone</option>
            <option value="me">Assigned to me</option>
            <option value="unassigned">Unassigned</option>
            {(agents.data ?? []).map((a) => (
              <option key={a.id} value={a.id}>
                {a.name}
              </option>
            ))}
          </NativeSelect>
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="ticket-category">Category</Label>
          <NativeSelect
            id="ticket-category"
            value={category}
            onChange={(e) => update({ category: e.target.value, page: '' })}
          >
            <option value="">Any category</option>
            {(categories.data ?? []).map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </NativeSelect>
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="ticket-sla">SLA</Label>
          <NativeSelect
            id="ticket-sla"
            value={sla}
            onChange={(e) => update({ sla: e.target.value, page: '' })}
          >
            <option value="">Any</option>
            <option value="breached">Breached</option>
            <option value="at_risk">At risk</option>
          </NativeSelect>
        </div>
      </div>
      {tickets.isPending ? (
        <ListSkeleton />
      ) : tickets.isError ? (
        <ErrorState error={tickets.error} onRetry={() => void tickets.refetch()} />
      ) : tickets.data.items.length === 0 ? (
        <EmptyState
          title={filtered ? 'No tickets match these filters.' : 'No open tickets.'}
          description="New tickets appear here as customers get in touch."
        />
      ) : (
        <>
          <div className="overflow-x-auto rounded-lg border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Number</TableHead>
                  <TableHead>Subject</TableHead>
                  <TableHead>Requester</TableHead>
                  <TableHead>Priority</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead>Assignee</TableHead>
                  <TableHead>SLA</TableHead>
                  <TableHead>Created</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {tickets.data.items.map((t) => {
                  const target = currentTarget(t)
                  return (
                    <TableRow key={t.id}>
                      <TableCell>
                        <Link
                          to={`/app/helpdesk/tickets/${t.id}`}
                          className="font-medium underline-offset-4 hover:underline"
                        >
                          {t.number}
                        </Link>
                      </TableCell>
                      <TableCell className="max-w-md min-w-48 whitespace-normal">
                        {t.subject}
                      </TableCell>
                      <TableCell>{t.requester?.name ?? '—'}</TableCell>
                      <TableCell>
                        <Badge variant={t.priority === 'URGENT' ? 'destructive' : 'outline'}>
                          {PRIORITY_LABELS[t.priority]}
                        </Badge>
                      </TableCell>
                      <TableCell>{TICKET_STATUS_LABELS[t.status]}</TableCell>
                      <TableCell>
                        {t.assignee?.name ?? (
                          <span className="text-muted-foreground">Unassigned</span>
                        )}
                      </TableCell>
                      <TableCell>
                        <SlaBadge state={target.state} due={target.due} target={target.target} />
                      </TableCell>
                      <TableCell>{formatDateTime(t.createdAt)}</TableCell>
                    </TableRow>
                  )
                })}
              </TableBody>
            </Table>
          </div>
          <Pagination
            page={tickets.data.page}
            size={tickets.data.size}
            total={tickets.data.total}
            onPage={(p) => update({ page: String(p) })}
          />
        </>
      )}
      {creating && (
        <TicketFormDialog
          onClose={() => setCreating(false)}
          onSaved={(saved) => {
            setCreating(false)
            navigate(`/app/helpdesk/tickets/${saved.id}`)
          }}
        />
      )}
    </>
  )
}
```

`Urgent` appears both as the priority badge and as the `Priority` filter's option; the test's `within(first)` scopes it to the row.

In `frontend/src/features/helpdesk/routes.tsx`, import `PERMISSIONS`, `RequirePermission` and `TicketsPage`, and add after the index route:

```tsx
  {
    path: 'tickets',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.ticketRead]}>
        <TicketsPage />
      </RequirePermission>
    ),
  },
```

- [ ] **Step 6: Run the tests, then the frontend checks**

Run: `cd frontend && npx vitest run src/features/helpdesk`
Expected: PASS.

Run: `cd frontend && npx prettier --write src && npm run format:check && npm run lint && npm run typecheck && npm test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add frontend/src
git commit -m "feat(helpdesk-ui): the ticket list with SLA badges and filters, and the ticket form"
```

---

### Task 9: The ticket page — conversation, composer, actions, SLA and context

**Files:**
- Create: `frontend/src/features/helpdesk/useTicketAction.ts`, `TicketActions.tsx`, `TicketConversation.tsx`, `MessageComposer.tsx`, `TicketContextPanel.tsx`, `TicketDetailPage.tsx`, `TicketDetailPage.test.tsx`
- Modify: `frontend/src/features/helpdesk/routes.tsx`

**Interfaces:**
- Consumes (Tasks 7–8): `TicketView`, `MessageView`, `MessagePosted`, `MessageKind`, `TicketContext`, `ArticleView`, `PartyView`; `PERMISSIONS.ticketManage|ticketAssign|ticketResolve|partyRead|articleRead`; labels; `SlaBadge`; `AgentSelect`; `TicketFormDialog`; `invalidateHelpDesk`; `SubjectLink`; `SubjectTasksPanel`, `ActivityPanel`, `DocumentsPanel` (subject type `TICKET`).
- Produces:
  - `useTicketAction(ticketId)` → `{ busy, error, setError, run(path, body, success): Promise<boolean> }`. `run` posts, stores the returned ticket under `['helpdesk', 'ticket', id]`, invalidates the HelpDesk queries and toasts. On failure it shows the problem and reloads the ticket.
  - `TicketDetailPage` at `/app/helpdesk/tickets/:ticketId`.
  - Ruling (pre-flight): "insert an article into a reply" (D13) inserts the article's **title and text** into the reply box, not a link. There is no customer-facing knowledge base yet, so a link would mean nothing to the customer.

- [ ] **Step 1: Write the failing tests**

`frontend/src/features/helpdesk/TicketDetailPage.test.tsx`:

```tsx
import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import {
  anAgent,
  anArticle,
  anArticleSummary,
  aMessage,
  anSla,
  aPerson,
  aTicket,
  aTicketContext,
  aTicketSummary,
  pageOf,
} from '@/test/records'
import { renderApp } from '@/test/renderApp'
import type { TicketView } from '@/lib/api/types'

function setup(ticket: TicketView = aTicket(), permissions?: string[]) {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['HELPDESK'], ...(permissions ? { permissions } : {}) }))
    .on('GET /helpdesk/tickets/:id', { body: ticket })
    .on('GET /helpdesk/tickets/:id/messages', {
      body: [
        aMessage({ id: 'm-1', body: 'Please clear the paper tray.' }),
        aMessage({
          id: 'm-2',
          kind: 'INTERNAL_NOTE',
          body: 'Same model failed last month.',
          emailedTo: null,
        }),
      ],
    })
    .on('GET /helpdesk/tickets/:id/context', {
      body: aTicketContext({
        previousTickets: [
          aTicketSummary({
            id: 't-0',
            number: 'T-00000',
            subject: 'Toner smudges',
            status: 'CLOSED',
          }),
        ],
        possibleDuplicates: [
          aTicketSummary({ id: 't-7', number: 'T-00007', subject: 'Printer jam again' }),
        ],
        suggestedArticles: [anArticleSummary()],
      }),
    })
    .on('GET /parties/:id', {
      body: aPerson({ id: 'p-meera', name: 'Meera Iyer', email: 'meera@deccan.test', phone: '+91 98' }),
    })
    .on('GET /helpdesk/articles/:id', { body: anArticle() })
    .on('GET /helpdesk/agents', { body: [anAgent()] })
    .on('GET /activities', { body: pageOf([]) })
    .on('GET /documents', { body: [] })
  return renderApp({ server, path: `/app/helpdesk/tickets/${ticket.id}` })
}

describe('TicketDetailPage', () => {
  it('shows the ticket, its SLA and the conversation', async () => {
    setup()
    expect(
      await screen.findByRole('heading', { name: 'T-00001 · Printer jams on every page' }),
    ).toBeInTheDocument()
    const sla = screen.getByRole('region', { name: 'SLA' })
    expect(within(sla).getByText('First response: On track')).toBeInTheDocument()
    expect(within(sla).getByText('Resolution: On track')).toBeInTheDocument()
    const conversation = screen.getByRole('region', { name: 'Conversation' })
    expect(await within(conversation).findByText('Please clear the paper tray.')).toBeInTheDocument()
    expect(within(conversation).getByText('Emailed to meera@deccan.test')).toBeInTheDocument()
    // the composer's type select also offers "Internal note": look in the message list only
    expect(within(within(conversation).getByRole('list')).getByText('Internal note')).toBeInTheDocument()
    const details = screen.getByRole('region', { name: 'Details' })
    expect(within(details).getByRole('link', { name: 'Meera Iyer' })).toHaveAttribute(
      'href',
      '/app/directory/p-meera',
    )
    expect(within(details).getByRole('link', { name: 'W-1 · Widget' })).toHaveAttribute(
      'href',
      '/app/products/pr-widget',
    )
  })

  it('shows a linked record the viewer may open, and a restricted one without a link', async () => {
    setup(aTicket({ linked: { type: 'SALES_ORDER', id: 'so-1', label: 'SO-00001' } }))
    const details = await screen.findByRole('region', { name: 'Details' })
    expect(within(details).getByRole('link', { name: 'SO-00001' })).toHaveAttribute(
      'href',
      '/app/inventory/sales-orders/so-1',
    )
  })

  it('sends a public reply and shows the ticket as the server returns it', async () => {
    const { server, user } = setup()
    const box = await screen.findByLabelText('Message')
    const answered = aTicket({
      status: 'OPEN',
      sla: anSla({ firstRespondedAt: '2026-10-09T09:40:00Z', firstResponseState: 'MET' }),
      version: 1,
    })
    server
      .on('POST /helpdesk/tickets/:id/messages', {
        status: 201,
        body: { message: aMessage({ id: 'm-3', body: 'We are sending a technician.' }), ticket: answered },
      })
      .on('GET /helpdesk/tickets/:id', { body: answered })
    await user.type(box, 'We are sending a technician.')
    await user.click(screen.getByRole('button', { name: 'Send reply' }))
    await waitFor(() => expect(server.callsTo('POST /helpdesk/tickets/:id/messages')).toHaveLength(1))
    expect(server.callsTo('POST /helpdesk/tickets/:id/messages')[0].body).toEqual({
      kind: 'PUBLIC_REPLY',
      body: 'We are sending a technician.',
    })
    expect(await screen.findByText('Reply emailed to meera@deccan.test.')).toBeInTheDocument()
    expect(await screen.findByText('First response: Met')).toBeInTheDocument()
    expect(screen.getByLabelText('Message')).toHaveValue('')
  })

  it('says so when a reply could not be emailed', async () => {
    const { server, user } = setup()
    const box = await screen.findByLabelText('Message')
    server.on('POST /helpdesk/tickets/:id/messages', {
      status: 201,
      body: { message: aMessage({ id: 'm-3', emailedTo: null }), ticket: aTicket({ status: 'OPEN' }) },
    })
    await user.type(box, 'Hello')
    await user.click(screen.getByRole('button', { name: 'Send reply' }))
    expect(
      await screen.findByText('Reply saved. No email was sent: the requester has no email address.'),
    ).toBeInTheDocument()
  })

  it('adds an internal note and logs a customer message', async () => {
    const { server, user } = setup()
    const box = await screen.findByLabelText('Message')
    server.on('POST /helpdesk/tickets/:id/messages', {
      status: 201,
      body: { message: aMessage({ id: 'm-3', kind: 'INTERNAL_NOTE' }), ticket: aTicket() },
    })
    await user.selectOptions(screen.getByLabelText('Message type'), 'INTERNAL_NOTE')
    await user.type(box, 'Check the warranty first.')
    await user.click(screen.getByRole('button', { name: 'Add note' }))
    await waitFor(() =>
      expect(server.callsTo('POST /helpdesk/tickets/:id/messages').at(-1)?.body).toEqual({
        kind: 'INTERNAL_NOTE',
        body: 'Check the warranty first.',
      }),
    )
    await user.selectOptions(screen.getByLabelText('Message type'), 'CUSTOMER_MESSAGE')
    expect(screen.getByRole('button', { name: 'Log customer message' })).toBeInTheDocument()
  })

  it('refuses an empty message before calling the server', async () => {
    const { server, user } = setup()
    await screen.findByLabelText('Message')
    await user.click(screen.getByRole('button', { name: 'Send reply' }))
    expect(await screen.findByText('Write a message.')).toBeInTheDocument()
    expect(server.callsTo('POST /helpdesk/tickets/:id/messages')).toHaveLength(0)
  })

  it('assigns the ticket to a teammate', async () => {
    const { server, user } = setup()
    const assigned = aTicket({
      status: 'OPEN',
      assignee: { id: 'u-ravi', name: 'Ravi Kumar' },
      version: 1,
    })
    server
      .on('POST /helpdesk/tickets/:id/assign', { body: assigned })
      .on('GET /helpdesk/tickets/:id', { body: assigned })
    await user.click(await screen.findByRole('button', { name: 'Assign…' }))
    const dialog = await screen.findByRole('dialog')
    await user.selectOptions(within(dialog).getByLabelText('Assignee'), 'u-ravi')
    await user.click(within(dialog).getByRole('button', { name: 'Assign' }))
    await waitFor(() => expect(server.callsTo('POST /helpdesk/tickets/:id/assign')).toHaveLength(1))
    expect(server.callsTo('POST /helpdesk/tickets/:id/assign')[0].body).toEqual({
      assigneeId: 'u-ravi',
      version: 0,
    })
    const details = screen.getByRole('region', { name: 'Details' })
    expect(await within(details).findByText('Ravi Kumar')).toBeInTheDocument()
  })

  it('assigns the ticket to the signed-in user in one click', async () => {
    const { server, user } = setup()
    const mine = aTicket({ assignee: { id: 'u-ada', name: 'Ada Lovelace' }, version: 1 })
    server
      .on('POST /helpdesk/tickets/:id/assign', { body: mine })
      .on('GET /helpdesk/tickets/:id', { body: mine })
    await user.click(await screen.findByRole('button', { name: 'Assign to me' }))
    await waitFor(() =>
      expect(server.callsTo('POST /helpdesk/tickets/:id/assign')[0]?.body).toEqual({
        assigneeId: 'u-ada',
        version: 0,
      }),
    )
  })

  it('needs a resolution note to resolve', async () => {
    const { server, user } = setup(aTicket({ status: 'OPEN' }))
    const resolved = aTicket({ status: 'RESOLVED', resolutionNote: 'Replaced the roller.', version: 1 })
    server
      .on('POST /helpdesk/tickets/:id/status', { body: resolved })
      .on('GET /helpdesk/tickets/:id', { body: resolved })
    await user.click(await screen.findByRole('button', { name: 'Resolve…' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Resolve' }))
    expect(await within(dialog).findByText('Add a resolution note.')).toBeInTheDocument()
    expect(server.callsTo('POST /helpdesk/tickets/:id/status')).toHaveLength(0)
    await user.type(within(dialog).getByLabelText('Resolution note'), 'Replaced the roller.')
    await user.click(within(dialog).getByRole('button', { name: 'Resolve' }))
    await waitFor(() =>
      expect(server.callsTo('POST /helpdesk/tickets/:id/status')[0]?.body).toEqual({
        status: 'RESOLVED',
        note: 'Replaced the roller.',
        version: 0,
      }),
    )
    expect(await screen.findByText('Replaced the roller.')).toBeInTheDocument()
  })

  it('offers the moves each status allows', async () => {
    const { server, user } = setup(aTicket({ status: 'PENDING', sla: anSla({ pausedAt: '2026-10-09T11:00:00Z', resolutionState: 'PAUSED', firstRespondedAt: '2026-10-09T10:00:00Z' }) }))
    expect(await screen.findByRole('button', { name: 'Resume' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Wait on customer' })).not.toBeInTheDocument()
    expect(screen.getByText(/Paused since/)).toBeInTheDocument()
    const resumed = aTicket({ status: 'OPEN', version: 1 })
    server
      .on('POST /helpdesk/tickets/:id/status', { body: resumed })
      .on('GET /helpdesk/tickets/:id', { body: resumed })
    await user.click(screen.getByRole('button', { name: 'Resume' }))
    await waitFor(() =>
      expect(server.callsTo('POST /helpdesk/tickets/:id/status')[0]?.body).toEqual({
        status: 'OPEN',
        note: null,
        version: 0,
      }),
    )
    expect(await screen.findByRole('button', { name: 'Wait on customer' })).toBeInTheDocument()
  })

  it('reopens or closes a resolved ticket', async () => {
    const { server, user } = setup(
      aTicket({ status: 'RESOLVED', resolutionNote: 'Fixed.', sla: anSla({ resolvedAt: '2026-10-10T09:00:00Z', resolutionState: 'MET' }) }),
    )
    expect(await screen.findByRole('button', { name: 'Reopen' })).toBeInTheDocument()
    const closedTicket = aTicket({ status: 'CLOSED', closedAt: '2026-10-10T10:00:00Z', version: 1 })
    server
      .on('POST /helpdesk/tickets/:id/status', { body: closedTicket })
      .on('GET /helpdesk/tickets/:id', { body: closedTicket })
    await user.click(screen.getByRole('button', { name: 'Close' }))
    await user.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Close ticket' }),
    )
    await waitFor(() =>
      expect(server.callsTo('POST /helpdesk/tickets/:id/status')[0]?.body).toEqual({
        status: 'CLOSED',
        note: null,
        version: 0,
      }),
    )
    expect(await screen.findByText('This ticket is closed. Its history stays here.')).toBeInTheDocument()
  })

  it('shows a closed ticket without composer or actions', async () => {
    setup(aTicket({ status: 'CLOSED', closedAt: '2026-10-10T10:00:00Z' }))
    expect(await screen.findByText('This ticket is closed. Its history stays here.')).toBeInTheDocument()
    expect(screen.queryByLabelText('Message')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Assign…' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument()
  })

  it('explains a conflict and reloads the ticket', async () => {
    const { server, user } = setup(aTicket({ status: 'OPEN' }))
    server.on('POST /helpdesk/tickets/:id/status', {
      status: 409,
      body: { detail: 'This record was changed by someone else. Reload and try again.' },
    })
    await user.click(await screen.findByRole('button', { name: 'Wait on customer' }))
    expect(
      await screen.findByText('This record was changed by someone else. Reload and try again.'),
    ).toBeInTheDocument()
    await waitFor(() =>
      expect(server.callsTo('GET /helpdesk/tickets/:id').length).toBeGreaterThan(1),
    )
  })

  it('shows the requester, previous tickets, possible duplicates and suggested articles', async () => {
    setup()
    const context = await screen.findByRole('region', { name: 'Context' })
    expect(await within(context).findByText('meera@deccan.test')).toBeInTheDocument()
    expect(within(context).getByRole('link', { name: 'T-00000' })).toHaveAttribute(
      'href',
      '/app/helpdesk/tickets/t-0',
    )
    expect(within(context).getByRole('link', { name: 'T-00007' })).toBeInTheDocument()
    expect(within(context).getByRole('link', { name: 'Clearing a paper jam' })).toHaveAttribute(
      'href',
      '/app/helpdesk/articles/a-1',
    )
  })

  it('inserts a suggested article into the reply', async () => {
    const { user } = setup()
    const context = await screen.findByRole('region', { name: 'Context' })
    await user.type(await screen.findByLabelText('Message'), 'Hi Meera,')
    await user.click(
      await within(context).findByRole('button', { name: 'Insert Clearing a paper jam into reply' }),
    )
    await waitFor(() =>
      expect(screen.getByLabelText('Message')).toHaveValue(
        'Hi Meera,\n\nClearing a paper jam\n\nOpen the rear tray and pull the sheet out gently.',
      ),
    )
    expect(screen.getByLabelText('Message type')).toHaveValue('PUBLIC_REPLY')
  })

  it('shows readers the ticket without composer or actions', async () => {
    setup(aTicket(), ['helpdesk.ticket.read', 'helpdesk.article.read', 'directory.party.read'])
    await screen.findByRole('heading', { name: 'T-00001 · Printer jams on every page' })
    expect(screen.queryByLabelText('Message')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Assign…' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Resolve…' })).not.toBeInTheDocument()
  })
})
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd frontend && npx vitest run src/features/helpdesk/TicketDetailPage.test.tsx`
Expected: FAIL — there is no ticket route yet.

- [ ] **Step 3: The action hook**

`frontend/src/features/helpdesk/useTicketAction.ts`:

```ts
import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { toast } from 'sonner'
import { useApi } from '@/lib/api/ApiContext'
import { problemMessage } from '@/lib/api/problems'
import type { TicketView } from '@/lib/api/types'
import { invalidateHelpDesk } from './invalidation'

/** Posts a ticket state change. On success it stores the returned ticket; on failure it explains and reloads the ticket. */
export function useTicketAction(ticketId: string) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const key = ['helpdesk', 'ticket', ticketId]

  async function run(path: string, body: unknown, success: string): Promise<boolean> {
    setBusy(true)
    setError(null)
    try {
      const updated = await api.post<TicketView>(path, body)
      queryClient.setQueryData(key, updated)
      await invalidateHelpDesk(queryClient)
      toast.success(success)
      return true
    } catch (e) {
      setError(problemMessage(e))
      // the version may have moved: show the current ticket
      void queryClient.invalidateQueries({ queryKey: key })
      return false
    } finally {
      setBusy(false)
    }
  }

  return { busy, error, setError, run }
}
```

`invalidateHelpDesk` also invalidates `['helpdesk', 'ticket', id]`, which has just been set from the response. Because the refetch returns the same data, the screen doesn't flicker.

- [ ] **Step 4: Actions**

`frontend/src/features/helpdesk/TicketActions.tsx`:

```tsx
import { useState } from 'react'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Textarea } from '@/components/ui/textarea'
import { describedBy, Field } from '@/components/form/Field'
import { FormError } from '@/components/form/FormError'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useTenantSession } from '@/features/auth/tenantSession'
import type { TicketStatus, TicketView } from '@/lib/api/types'
import { AgentSelect } from './AgentSelect'
import { TicketFormDialog } from './TicketFormDialog'
import { useTicketAction } from './useTicketAction'

type DialogName = 'edit' | 'assign' | 'resolve' | 'close' | null

/** Edit, assign and move a ticket (D6, D7). The buttons follow the user's permissions and the status. */
export function TicketActions({ ticket }: { ticket: TicketView }) {
  const can = useCan()
  const session = useTenantSession()
  const me = session.state.status === 'authenticated' ? session.state.profile.user : null
  const action = useTicketAction(ticket.id)
  const [dialog, setDialog] = useState<DialogName>(null)
  const path = `/helpdesk/tickets/${ticket.id}`
  const open = ticket.status === 'NEW' || ticket.status === 'OPEN' || ticket.status === 'PENDING'
  const closed = ticket.status === 'CLOSED'
  const canManage = can(PERMISSIONS.ticketManage)
  const canAssign = can(PERMISSIONS.ticketAssign)
  const canResolve = can(PERMISSIONS.ticketResolve)

  function show(next: DialogName) {
    action.setError(null)
    setDialog(next)
  }

  function move(status: TicketStatus, success: string) {
    return action.run(`${path}/status`, { status, note: null, version: ticket.version }, success)
  }

  if (closed) return null
  return (
    <div className="space-y-2">
      <div className="flex flex-wrap gap-2">
        {canManage && (
          <Button variant="outline" size="sm" onClick={() => show('edit')}>
            Edit
          </Button>
        )}
        {canAssign && (
          <>
            {me && ticket.assignee?.id !== me.id && (
              <Button
                variant="outline"
                size="sm"
                disabled={action.busy}
                onClick={() =>
                  void action.run(
                    `${path}/assign`,
                    { assigneeId: me.id, version: ticket.version },
                    `${ticket.number} is yours.`,
                  )
                }
              >
                Assign to me
              </Button>
            )}
            <Button variant="outline" size="sm" onClick={() => show('assign')}>
              Assign…
            </Button>
          </>
        )}
        {canResolve && (
          <>
            {ticket.status === 'NEW' && (
              <Button
                variant="outline"
                size="sm"
                disabled={action.busy}
                onClick={() => void move('OPEN', `${ticket.number} is open.`)}
              >
                Start work
              </Button>
            )}
            {(ticket.status === 'NEW' || ticket.status === 'OPEN') && (
              <Button
                variant="outline"
                size="sm"
                disabled={action.busy}
                onClick={() =>
                  void move('PENDING', `${ticket.number} is waiting on the customer.`)
                }
              >
                Wait on customer
              </Button>
            )}
            {ticket.status === 'PENDING' && (
              <Button
                variant="outline"
                size="sm"
                disabled={action.busy}
                onClick={() => void move('OPEN', `${ticket.number} is open again.`)}
              >
                Resume
              </Button>
            )}
            {open && (
              <Button size="sm" onClick={() => show('resolve')}>
                Resolve…
              </Button>
            )}
            {ticket.status === 'RESOLVED' && (
              <>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={action.busy}
                  onClick={() => void move('OPEN', `${ticket.number} reopened.`)}
                >
                  Reopen
                </Button>
                <Button variant="outline" size="sm" onClick={() => show('close')}>
                  Close
                </Button>
              </>
            )}
          </>
        )}
      </div>
      {!dialog && <FormError message={action.error} />}
      {dialog === 'edit' && (
        <TicketFormDialog
          ticket={ticket}
          onClose={() => setDialog(null)}
          onSaved={() => setDialog(null)}
        />
      )}
      {dialog === 'assign' && (
        <AssignDialog
          ticket={ticket}
          busy={action.busy}
          error={action.error}
          onClose={() => show(null)}
          onAssign={(assigneeId) =>
            action
              .run(
                `${path}/assign`,
                { assigneeId: assigneeId || null, version: ticket.version },
                assigneeId ? 'Ticket assigned.' : 'Ticket unassigned.',
              )
              .then((ok) => ok && setDialog(null))
          }
        />
      )}
      {dialog === 'resolve' && (
        <ResolveDialog
          ticket={ticket}
          busy={action.busy}
          error={action.error}
          onClose={() => show(null)}
          onResolve={(note) =>
            action
              .run(
                `${path}/status`,
                { status: 'RESOLVED', note, version: ticket.version },
                `${ticket.number} resolved.`,
              )
              .then((ok) => ok && setDialog(null))
          }
        />
      )}
      <ConfirmDialog
        open={dialog === 'close'}
        title={`Close ${ticket.number}?`}
        description="A closed ticket can't be reopened or changed. Its history stays here."
        confirmLabel="Close ticket"
        busy={action.busy}
        error={action.error}
        onCancel={() => show(null)}
        onConfirm={() =>
          void move('CLOSED', `${ticket.number} closed.`).then((ok) => ok && setDialog(null))
        }
      />
    </div>
  )
}

function AssignDialog({
  ticket,
  busy,
  error,
  onClose,
  onAssign,
}: {
  ticket: TicketView
  busy: boolean
  error: string | null
  onClose: () => void
  onAssign: (assigneeId: string) => Promise<unknown>
}) {
  const [value, setValue] = useState(ticket.assignee?.id ?? '')
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Assign {ticket.number}</DialogTitle>
          <DialogDescription>
            Whoever you choose gets an email with a link to the ticket.
          </DialogDescription>
        </DialogHeader>
        <AgentSelect
          id="field-assigneeId"
          label="Assignee"
          value={value}
          current={ticket.assignee}
          onChange={setValue}
        />
        <FormError message={error} />
        <DialogFooter>
          <Button type="button" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button disabled={busy} onClick={() => void onAssign(value)}>
            Assign
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function ResolveDialog({
  ticket,
  busy,
  error,
  onClose,
  onResolve,
}: {
  ticket: TicketView
  busy: boolean
  error: string | null
  onClose: () => void
  onResolve: (note: string) => Promise<unknown>
}) {
  const [note, setNote] = useState('')
  const [noteError, setNoteError] = useState<string | undefined>()
  const id = 'field-resolutionNote'
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Resolve {ticket.number}</DialogTitle>
          <DialogDescription>
            Say what fixed it. The next person with the same problem will thank you.
          </DialogDescription>
        </DialogHeader>
        <Field id={id} label="Resolution note" error={noteError}>
          <Textarea
            id={id}
            rows={4}
            maxLength={2000}
            value={note}
            aria-invalid={noteError ? true : undefined}
            aria-describedby={describedBy(id, noteError)}
            onChange={(e) => setNote(e.target.value)}
          />
        </Field>
        <FormError message={error} />
        <DialogFooter>
          <Button type="button" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button
            disabled={busy}
            onClick={() => {
              if (!note.trim()) {
                setNoteError('Add a resolution note.')
                return
              }
              setNoteError(undefined)
              void onResolve(note.trim())
            }}
          >
            Resolve
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
```

- [ ] **Step 5: Conversation and composer**

`frontend/src/features/helpdesk/TicketConversation.tsx`:

```tsx
import { cn } from '@/lib/utils'
import type { MessageView } from '@/lib/api/types'
import { formatDateTime } from '@/lib/format'
import { MESSAGE_KIND_LABELS } from './labels'

/** The ticket's messages, oldest first. Internal notes look different from what the customer saw. */
export function TicketConversation({ messages }: { messages: MessageView[] }) {
  if (messages.length === 0)
    return <p className="text-sm text-muted-foreground">No messages yet.</p>
  return (
    <ol className="space-y-3">
      {messages.map((m) => (
        <li
          key={m.id}
          className={cn(
            'rounded-lg border p-3 text-sm',
            m.kind === 'INTERNAL_NOTE' && 'border-dashed bg-muted/50',
          )}
        >
          <div className="mb-1 flex flex-wrap items-baseline gap-2">
            <span className="font-medium">{MESSAGE_KIND_LABELS[m.kind]}</span>
            <span className="text-muted-foreground">
              {m.author?.name ?? 'Former member'} · {formatDateTime(m.createdAt)}
            </span>
          </div>
          <p className="whitespace-pre-wrap">{m.body}</p>
          {m.kind === 'PUBLIC_REPLY' && (
            <p className="mt-2 text-xs text-muted-foreground">
              {m.emailedTo
                ? `Emailed to ${m.emailedTo}`
                : 'Not emailed: the requester has no email address.'}
            </p>
          )}
        </li>
      ))}
    </ol>
  )
}
```

`frontend/src/features/helpdesk/MessageComposer.tsx`:

```tsx
import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'
import { describedBy, Field } from '@/components/form/Field'
import { FormError } from '@/components/form/FormError'
import { NativeSelect } from '@/components/form/NativeSelect'
import { useApi } from '@/lib/api/ApiContext'
import { problemMessage } from '@/lib/api/problems'
import type { MessageKind, MessagePosted, TicketView } from '@/lib/api/types'
import { invalidateHelpDesk } from './invalidation'
import { MESSAGE_KIND_LABELS } from './labels'

const SUBMIT: Record<MessageKind, string> = {
  PUBLIC_REPLY: 'Send reply',
  INTERNAL_NOTE: 'Add note',
  CUSTOMER_MESSAGE: 'Log customer message',
}
const HINTS: Record<MessageKind, string> = {
  PUBLIC_REPLY: 'Emailed to the requester. The first reply is the first response.',
  INTERNAL_NOTE: 'Only your team sees notes. Nothing is emailed.',
  CUSTOMER_MESSAGE:
    'Something the customer told you by phone or in person. A ticket waiting on the customer opens again.',
}

/** D9. `kind` and `body` live in the page so a suggested article can be inserted into the reply. */
export function MessageComposer({
  ticket,
  kind,
  onKind,
  body,
  onBody,
}: {
  ticket: TicketView
  kind: MessageKind
  onKind: (kind: MessageKind) => void
  body: string
  onBody: (body: string) => void
}) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [busy, setBusy] = useState(false)
  const [bodyError, setBodyError] = useState<string | undefined>()
  const [error, setError] = useState<string | null>(null)
  const id = 'field-message'

  async function send() {
    if (!body.trim()) {
      setBodyError('Write a message.')
      return
    }
    setBodyError(undefined)
    setError(null)
    setBusy(true)
    try {
      const posted = await api.post<MessagePosted>(`/helpdesk/tickets/${ticket.id}/messages`, {
        kind,
        body: body.trim(),
      })
      queryClient.setQueryData(['helpdesk', 'ticket', ticket.id], posted.ticket)
      await invalidateHelpDesk(queryClient)
      onBody('')
      if (kind === 'PUBLIC_REPLY')
        toast.success(
          posted.message.emailedTo
            ? `Reply emailed to ${posted.message.emailedTo}.`
            : 'Reply saved. No email was sent: the requester has no email address.',
        )
      else toast.success(kind === 'INTERNAL_NOTE' ? 'Note added.' : 'Customer message logged.')
    } catch (e) {
      setError(problemMessage(e))
      void queryClient.invalidateQueries({ queryKey: ['helpdesk', 'ticket', ticket.id] })
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="space-y-3">
      <Field id="field-messageKind" label="Message type">
        <NativeSelect
          id="field-messageKind"
          value={kind}
          onChange={(e) => onKind(e.target.value as MessageKind)}
        >
          {(Object.keys(MESSAGE_KIND_LABELS) as MessageKind[]).map((k) => (
            <option key={k} value={k}>
              {MESSAGE_KIND_LABELS[k]}
            </option>
          ))}
        </NativeSelect>
      </Field>
      <Field id={id} label="Message" error={bodyError} hint={HINTS[kind]}>
        <Textarea
          id={id}
          rows={5}
          maxLength={10000}
          value={body}
          aria-invalid={bodyError ? true : undefined}
          aria-describedby={describedBy(id, bodyError, HINTS[kind])}
          onChange={(e) => onBody(e.target.value)}
        />
      </Field>
      <FormError message={error} />
      <Button disabled={busy} onClick={() => void send()}>
        {SUBMIT[kind]}
      </Button>
    </div>
  )
}
```

The messages query (`['helpdesk', 'messages', id]`) starts with `'helpdesk'`, so `invalidateHelpDesk` refetches the conversation after a post.

- [ ] **Step 6: Context panel**

`frontend/src/features/helpdesk/TicketContextPanel.tsx`:

```tsx
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useApi } from '@/lib/api/ApiContext'
import type { ArticleSummary, PartyView, TicketContext, TicketSummary, TicketView } from '@/lib/api/types'
import { TICKET_STATUS_LABELS } from './labels'

function TicketList({ label, tickets }: { label: string; tickets: TicketSummary[] }) {
  if (tickets.length === 0) return null
  return (
    <section aria-label={label} className="space-y-1">
      <h3 className="font-medium">{label}</h3>
      <ul className="space-y-1">
        {tickets.map((t) => (
          <li key={t.id} className="flex flex-wrap gap-x-2">
            <Link
              to={`/app/helpdesk/tickets/${t.id}`}
              className="underline-offset-4 hover:underline"
            >
              {t.number}
            </Link>
            <span>{t.subject}</span>
            <span className="text-muted-foreground">{TICKET_STATUS_LABELS[t.status]}</span>
          </li>
        ))}
      </ul>
    </section>
  )
}

/** D12: who is asking, what they asked before, what looks the same, and what the knowledge base already says. */
export function TicketContextPanel({
  ticket,
  onInsertArticle,
}: {
  ticket: TicketView
  /** null when the user can't reply (no manage permission, or the ticket is closed). */
  onInsertArticle: ((article: ArticleSummary) => void) | null
}) {
  const api = useApi()
  const can = useCan()
  const context = useQuery({
    queryKey: ['helpdesk', 'context', ticket.id],
    queryFn: () => api.get<TicketContext>(`/helpdesk/tickets/${ticket.id}/context`),
  })
  const requesterId = ticket.requester?.id
  const party = useQuery({
    queryKey: ['party', requesterId],
    queryFn: () => api.get<PartyView>(`/parties/${requesterId}`),
    enabled: !!requesterId && can(PERMISSIONS.partyRead),
  })
  const articles = context.data?.suggestedArticles ?? []
  return (
    <Card role="region" aria-label="Context">
      <CardHeader>
        <CardTitle>Context</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4 text-sm">
        {party.data && (
          <section aria-label="Requester" className="space-y-1">
            <h3 className="font-medium">Requester</h3>
            <p>
              <Link
                to={`/app/directory/${party.data.id}`}
                className="underline-offset-4 hover:underline"
              >
                {party.data.name}
              </Link>
              {party.data.organization && (
                <span className="text-muted-foreground"> · {party.data.organization.name}</span>
              )}
            </p>
            {party.data.email && <p>{party.data.email}</p>}
            {party.data.phone && <p>{party.data.phone}</p>}
            {!party.data.email && (
              <p className="text-muted-foreground">No email address: replies won&apos;t be emailed.</p>
            )}
          </section>
        )}
        {context.isError && (
          <p className="text-muted-foreground">The context couldn&apos;t be loaded.</p>
        )}
        <TicketList label="Previous tickets" tickets={context.data?.previousTickets ?? []} />
        <TicketList label="Possible duplicates" tickets={context.data?.possibleDuplicates ?? []} />
        {articles.length > 0 && (
          <section aria-label="Suggested articles" className="space-y-2">
            <h3 className="font-medium">Suggested articles</h3>
            <ul className="space-y-2">
              {articles.map((a) => (
                <li key={a.id} className="space-y-1">
                  <Link
                    to={`/app/helpdesk/articles/${a.id}`}
                    className="font-medium underline-offset-4 hover:underline"
                  >
                    {a.title}
                  </Link>
                  <p className="text-muted-foreground">{a.excerpt}</p>
                  {onInsertArticle && (
                    <Button
                      size="sm"
                      variant="outline"
                      aria-label={`Insert ${a.title} into reply`}
                      onClick={() => onInsertArticle(a)}
                    >
                      Insert into reply
                    </Button>
                  )}
                </li>
              ))}
            </ul>
          </section>
        )}
        {context.data &&
          context.data.previousTickets.length === 0 &&
          context.data.possibleDuplicates.length === 0 &&
          articles.length === 0 && (
            <p className="text-muted-foreground">
              No earlier tickets from this requester and no matching articles.
            </p>
          )}
      </CardContent>
    </Card>
  )
}
```

- [ ] **Step 7: The page**

`frontend/src/features/helpdesk/TicketDetailPage.tsx`:

```tsx
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { toast } from 'sonner'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { ActivityPanel } from '@/features/records/ActivityPanel'
import { DocumentsPanel } from '@/features/records/DocumentsPanel'
import { SubjectLink } from '@/features/records/SubjectLink'
import { SubjectTasksPanel } from '@/features/records/SubjectTasksPanel'
import { useApi } from '@/lib/api/ApiContext'
import { problemMessage } from '@/lib/api/problems'
import type { ArticleSummary, ArticleView, MessageKind, MessageView, TicketView } from '@/lib/api/types'
import { formatDateTime } from '@/lib/format'
import { CHANNEL_LABELS, PRIORITY_LABELS, TICKET_STATUS_LABELS } from './labels'
import { MessageComposer } from './MessageComposer'
import { SlaBadge } from './SlaBadge'
import { TicketActions } from './TicketActions'
import { TicketContextPanel } from './TicketContextPanel'
import { TicketConversation } from './TicketConversation'

function SlaCard({ ticket }: { ticket: TicketView }) {
  const { sla } = ticket
  return (
    <Card role="region" aria-label="SLA">
      <CardHeader>
        <CardTitle>SLA</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3 text-sm">
        <div className="space-y-1">
          <SlaBadge state={sla.firstResponseState} target="First response" />
          <p className="text-muted-foreground">
            {sla.firstRespondedAt
              ? `Answered ${formatDateTime(sla.firstRespondedAt)}`
              : `Due ${formatDateTime(sla.firstResponseDueAt)}`}
          </p>
        </div>
        <div className="space-y-1">
          <SlaBadge state={sla.resolutionState} target="Resolution" />
          <p className="text-muted-foreground">
            {sla.resolvedAt
              ? `Resolved ${formatDateTime(sla.resolvedAt)}`
              : `Due ${formatDateTime(sla.resolutionDueAt)}`}
          </p>
          {sla.pausedAt && (
            <p className="text-muted-foreground">
              Paused since {formatDateTime(sla.pausedAt)} — waiting on the customer doesn&apos;t
              count.
            </p>
          )}
        </div>
        {ticket.reopenCount > 0 && (
          <p className="text-muted-foreground">
            Reopened {ticket.reopenCount} {ticket.reopenCount === 1 ? 'time' : 'times'}
          </p>
        )}
      </CardContent>
    </Card>
  )
}

function DetailsCard({ ticket: t }: { ticket: TicketView }) {
  return (
    <Card role="region" aria-label="Details">
      <CardHeader>
        <CardTitle>Details</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3 text-sm">
        <p className="whitespace-pre-wrap">{t.description}</p>
        <dl className="grid grid-cols-[8rem_1fr] gap-x-3 gap-y-2">
          <dt className="text-muted-foreground">Requester</dt>
          <dd>
            {t.requester ? (
              <Link
                to={`/app/directory/${t.requester.id}`}
                className="underline-offset-4 hover:underline"
              >
                {t.requester.name}
              </Link>
            ) : (
              '—'
            )}
          </dd>
          <dt className="text-muted-foreground">Product</dt>
          <dd>
            {t.product ? (
              <Link
                to={`/app/products/${t.product.id}`}
                className="underline-offset-4 hover:underline"
              >
                {t.product.sku} · {t.product.name}
              </Link>
            ) : (
              '—'
            )}
          </dd>
          <dt className="text-muted-foreground">Related record</dt>
          <dd>
            <SubjectLink subject={t.linked ? { ...t.linked, archived: false } : null} />
          </dd>
          <dt className="text-muted-foreground">Category</dt>
          <dd>{t.category?.name ?? '—'}</dd>
          <dt className="text-muted-foreground">Channel</dt>
          <dd>{CHANNEL_LABELS[t.channel]}</dd>
          <dt className="text-muted-foreground">Assignee</dt>
          <dd>{t.assignee?.name ?? 'Unassigned'}</dd>
          <dt className="text-muted-foreground">Created</dt>
          <dd>
            {formatDateTime(t.createdAt)}
            {t.createdBy ? ` by ${t.createdBy.name}` : ''}
          </dd>
          {t.resolutionNote && (
            <>
              <dt className="text-muted-foreground">Resolution</dt>
              <dd className="whitespace-pre-wrap">{t.resolutionNote}</dd>
            </>
          )}
          {t.closedAt && (
            <>
              <dt className="text-muted-foreground">Closed</dt>
              <dd>{formatDateTime(t.closedAt)}</dd>
            </>
          )}
        </dl>
      </CardContent>
    </Card>
  )
}

export function TicketDetailPage() {
  const { ticketId = '' } = useParams()
  const api = useApi()
  const can = useCan()
  const queryClient = useQueryClient()
  const ticket = useQuery({
    queryKey: ['helpdesk', 'ticket', ticketId],
    queryFn: () => api.get<TicketView>(`/helpdesk/tickets/${ticketId}`),
  })
  const messages = useQuery({
    queryKey: ['helpdesk', 'messages', ticketId],
    queryFn: () => api.get<MessageView[]>(`/helpdesk/tickets/${ticketId}/messages`),
  })
  const [kind, setKind] = useState<MessageKind>('PUBLIC_REPLY')
  const [draft, setDraft] = useState('')

  async function insertArticle(summary: ArticleSummary) {
    try {
      const article = await queryClient.fetchQuery({
        queryKey: ['helpdesk', 'article', summary.id],
        queryFn: () => api.get<ArticleView>(`/helpdesk/articles/${summary.id}`),
      })
      const text = `${article.title}\n\n${article.body}`
      setKind('PUBLIC_REPLY')
      setDraft((current) => (current.trim() ? `${current.trimEnd()}\n\n${text}` : text))
    } catch (e) {
      toast.error(problemMessage(e))
    }
  }

  const back = (
    <Link to="/app/helpdesk/tickets" className="text-sm underline-offset-4 hover:underline">
      ← Tickets
    </Link>
  )
  if (ticket.isPending)
    return (
      <>
        {back}
        <ListSkeleton />
      </>
    )
  if (ticket.isError)
    return (
      <>
        {back}
        <ErrorState error={ticket.error} onRetry={() => void ticket.refetch()} />
      </>
    )
  const t = ticket.data
  const closed = t.status === 'CLOSED'
  const canReply = can(PERMISSIONS.ticketManage) && !closed

  return (
    <div className="space-y-6">
      {back}
      <PageHeader
        title={`${t.number} · ${t.subject}`}
        description={`${t.requester?.name ?? 'Requester'} · ${CHANNEL_LABELS[t.channel]}`}
        actions={
          <>
            <Badge variant={t.priority === 'URGENT' ? 'destructive' : 'outline'}>
              {PRIORITY_LABELS[t.priority]}
            </Badge>
            <Badge variant="secondary">{TICKET_STATUS_LABELS[t.status]}</Badge>
          </>
        }
      />
      <TicketActions ticket={t} />
      {closed && (
        <p className="text-sm text-muted-foreground">
          This ticket is closed. Its history stays here.
        </p>
      )}
      <div className="grid gap-6 lg:grid-cols-[2fr_1fr]">
        <div className="space-y-6">
          <DetailsCard ticket={t} />
          <Card role="region" aria-label="Conversation">
            <CardHeader>
              <CardTitle>Conversation</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              {messages.isPending ? (
                <ListSkeleton />
              ) : messages.isError ? (
                <ErrorState error={messages.error} onRetry={() => void messages.refetch()} />
              ) : (
                <TicketConversation messages={messages.data} />
              )}
              {canReply && (
                <MessageComposer
                  ticket={t}
                  kind={kind}
                  onKind={setKind}
                  body={draft}
                  onBody={setDraft}
                />
              )}
            </CardContent>
          </Card>
        </div>
        <div className="space-y-6">
          <SlaCard ticket={t} />
          <TicketContextPanel
            ticket={t}
            onInsertArticle={canReply ? (a) => void insertArticle(a) : null}
          />
        </div>
      </div>
      <SubjectTasksPanel
        subjectType="TICKET"
        subjectId={t.id}
        label={`${t.number} · ${t.subject}`}
        archived={closed}
      />
      <ActivityPanel subjectType="TICKET" subjectId={t.id} archived={closed} />
      <DocumentsPanel subjectType="TICKET" subjectId={t.id} archived={closed} />
    </div>
  )
}
```

The detail tests render the whole page through the app routes, so `GET /tasks` (from `signedIn`), `GET /activities` and `GET /documents` are registered in `setup()`.

In `frontend/src/features/helpdesk/routes.tsx` add:

```tsx
  {
    path: 'tickets/:ticketId',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.ticketRead]}>
        <TicketDetailPage />
      </RequirePermission>
    ),
  },
```

- [ ] **Step 8: Run the tests, then the frontend checks**

Run: `cd frontend && npx vitest run src/features/helpdesk`
Expected: PASS.

Run: `cd frontend && npx prettier --write src && npm run format:check && npm run lint && npm run typecheck && npm test`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add frontend/src
git commit -m "feat(helpdesk-ui): the ticket page with conversation, actions, SLA and context"
```

---

### Task 10: The knowledge base, and Tickets panels on party, Customer 360 and product pages

**Files:**
- Create: `frontend/src/features/helpdesk/ArticleFormDialog.tsx`, `ArticlesPage.tsx`, `ArticlesPage.test.tsx`, `ArticleDetailPage.tsx`, `ArticleDetailPage.test.tsx`, `TicketsPanel.tsx`, `TicketsPanel.test.tsx`
- Modify: `frontend/src/features/helpdesk/routes.tsx`, `HelpDeskLayout.test.tsx`
- Modify: `frontend/src/features/directory/PartyDetailPage.tsx`, `frontend/src/features/crm/Customer360Page.tsx`, `frontend/src/features/products/ProductDetailPage.tsx`

**Interfaces:**
- Consumes (Tasks 7–9): `ArticleView`, `ArticleSummary`, `ArticleStatus`, `TicketSummary`, `PartyRef`; `PERMISSIONS.articleRead|articleManage|ticketRead|ticketManage`; `ARTICLE_STATUS_LABELS`, `TICKET_STATUS_LABELS`, `PRIORITY_LABELS`; `CategorySelect`; `TicketFormDialog` (with `requester`); `currentTarget`, `SlaBadge`; `invalidateHelpDesk`; `DocumentsPanel` (subject type `KB_ARTICLE`).
- Produces:
  - `ArticlesPage` at `/app/helpdesk/articles`, with URL filters `q`, `status` (managers only), `category` and `page`.
  - `ArticleDetailPage` at `/app/helpdesk/articles/:articleId`. Publish, unpublish and archive follow D13: DRAFT ↔ PUBLISHED, either → ARCHIVED (final).
  - `TicketsPanel({ requester?: PartyRef; productId?: string })`. It lists the latest 10 tickets in any status for that requester or product, and only appears when HelpDesk is enabled and the user may read tickets. With `requester`, it offers "New ticket" for that requester.

- [ ] **Step 1: Write the failing tests**

`frontend/src/features/helpdesk/ArticlesPage.test.tsx`:

```tsx
import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aCategory, anArticle, anArticleSummary, pageOf } from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup(path = '/app/helpdesk/articles', permissions?: string[]) {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['HELPDESK'], ...(permissions ? { permissions } : {}) }))
    .on('GET /helpdesk/articles', {
      body: pageOf([
        anArticleSummary(),
        anArticleSummary({
          id: 'a-2',
          title: 'GST invoices explained',
          status: 'DRAFT',
          publishedAt: null,
          category: null,
        }),
      ]),
    })
    .on('GET /helpdesk/articles/:id', { body: anArticle({ id: 'a-new', status: 'DRAFT' }) })
    .on('GET /helpdesk/categories', { body: [aCategory()] })
    .on('GET /documents', { body: [] })
  return renderApp({ server, path })
}

describe('ArticlesPage', () => {
  it('lists articles with category, status and excerpt', async () => {
    setup()
    const first = (await screen.findByRole('link', { name: 'Clearing a paper jam' })).closest(
      'li',
    ) as HTMLElement
    expect(first).toHaveTextContent('General')
    expect(first).toHaveTextContent('Open the rear tray')
    const draft = screen.getByRole('link', { name: 'GST invoices explained' }).closest('li') as HTMLElement
    expect(within(draft).getByText('Draft')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Clearing a paper jam' })).toHaveAttribute(
      'href',
      '/app/helpdesk/articles/a-1',
    )
  })

  it('searches and filters by status and category', async () => {
    const { server, user } = setup()
    await screen.findByRole('link', { name: 'Clearing a paper jam' })
    await user.selectOptions(screen.getByLabelText('Status'), 'ARCHIVED')
    await user.selectOptions(screen.getByLabelText('Category'), 'c-general')
    await user.type(screen.getByLabelText('Search articles'), 'paper jam')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    await waitFor(() => {
      const last = server.callsTo('GET /helpdesk/articles').at(-1)?.query
      expect(last?.get('status')).toBe('ARCHIVED')
      expect(last?.get('categoryId')).toBe('c-general')
      expect(last?.get('q')).toBe('paper jam')
    })
  })

  it('writes a draft and opens it', async () => {
    const { server, user, router } = setup()
    server.on('POST /helpdesk/articles', {
      status: 201,
      body: anArticle({ id: 'a-new', status: 'DRAFT' }),
    })
    await user.click(await screen.findByRole('button', { name: 'New article' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Title'), 'Clearing a paper jam')
    await user.selectOptions(within(dialog).getByLabelText('Category'), 'c-general')
    await user.type(within(dialog).getByLabelText('Article'), 'Open the rear tray.')
    await user.click(within(dialog).getByRole('button', { name: 'Save draft' }))
    await waitFor(() => expect(server.callsTo('POST /helpdesk/articles')).toHaveLength(1))
    expect(server.callsTo('POST /helpdesk/articles')[0].body).toEqual({
      title: 'Clearing a paper jam',
      body: 'Open the rear tray.',
      categoryId: 'c-general',
    })
    await waitFor(() =>
      expect(router.state.location.pathname).toBe('/app/helpdesk/articles/a-new'),
    )
  })

  it('needs a title and a text', async () => {
    const { server, user } = setup()
    await user.click(await screen.findByRole('button', { name: 'New article' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Save draft' }))
    expect(await within(dialog).findAllByText('Required.')).toHaveLength(2)
    expect(server.callsTo('POST /helpdesk/articles')).toHaveLength(0)
  })

  it('shows readers published articles only, without status filter or New article', async () => {
    setup('/app/helpdesk/articles', ['helpdesk.article.read'])
    await screen.findByRole('link', { name: 'Clearing a paper jam' })
    expect(screen.queryByLabelText('Status')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'New article' })).not.toBeInTheDocument()
  })
})
```

`frontend/src/features/helpdesk/ArticleDetailPage.test.tsx`:

```tsx
import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aCategory, anArticle } from '@/test/records'
import { renderApp } from '@/test/renderApp'
import type { ArticleView } from '@/lib/api/types'

function setup(article: ArticleView = anArticle(), permissions?: string[]) {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['HELPDESK'], ...(permissions ? { permissions } : {}) }))
    .on('GET /helpdesk/articles/:id', { body: article })
    .on('GET /helpdesk/categories', { body: [aCategory()] })
    .on('GET /documents', { body: [] })
  return renderApp({ server, path: `/app/helpdesk/articles/${article.id}` })
}

describe('ArticleDetailPage', () => {
  it('shows the article', async () => {
    setup()
    expect(await screen.findByRole('heading', { name: 'Clearing a paper jam' })).toBeInTheDocument()
    expect(screen.getByText('Open the rear tray and pull the sheet out gently.')).toBeInTheDocument()
    expect(screen.getByText('Published')).toBeInTheDocument()
  })

  it('publishes a draft', async () => {
    const { server, user } = setup(anArticle({ status: 'DRAFT', publishedAt: null, version: 0 }))
    const published = anArticle({ version: 1 })
    server
      .on('POST /helpdesk/articles/:id/publish', { body: published })
      .on('GET /helpdesk/articles/:id', { body: published })
    await user.click(await screen.findByRole('button', { name: 'Publish' }))
    await waitFor(() =>
      expect(server.callsTo('POST /helpdesk/articles/:id/publish')[0]?.body).toEqual({ version: 0 }),
    )
    expect(await screen.findByRole('button', { name: 'Unpublish' })).toBeInTheDocument()
  })

  it('unpublishes a published article', async () => {
    const { server, user } = setup()
    const draft = anArticle({ status: 'DRAFT', publishedAt: null, version: 2 })
    server
      .on('POST /helpdesk/articles/:id/unpublish', { body: draft })
      .on('GET /helpdesk/articles/:id', { body: draft })
    await user.click(await screen.findByRole('button', { name: 'Unpublish' }))
    await waitFor(() =>
      expect(server.callsTo('POST /helpdesk/articles/:id/unpublish')[0]?.body).toEqual({
        version: 1,
      }),
    )
    expect(await screen.findByRole('button', { name: 'Publish' })).toBeInTheDocument()
  })

  it('archives after confirmation, and an archived article has no actions', async () => {
    const { server, user } = setup()
    const archived = anArticle({ status: 'ARCHIVED', version: 2 })
    server
      .on('POST /helpdesk/articles/:id/archive', { body: archived })
      .on('GET /helpdesk/articles/:id', { body: archived })
    await user.click(await screen.findByRole('button', { name: 'Archive' }))
    await user.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Archive article' }),
    )
    await waitFor(() =>
      expect(server.callsTo('POST /helpdesk/articles/:id/archive')[0]?.body).toEqual({
        version: 1,
      }),
    )
    expect(await screen.findByText('Archived')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Publish' })).not.toBeInTheDocument()
  })

  it('edits the article', async () => {
    const { server, user } = setup()
    const edited = anArticle({ title: 'Clearing any paper jam', version: 2 })
    server
      .on('PUT /helpdesk/articles/:id', { body: edited })
      .on('GET /helpdesk/articles/:id', { body: edited })
    await user.click(await screen.findByRole('button', { name: 'Edit' }))
    const dialog = await screen.findByRole('dialog')
    const title = within(dialog).getByLabelText('Title')
    await user.clear(title)
    await user.type(title, 'Clearing any paper jam')
    await user.click(within(dialog).getByRole('button', { name: 'Save changes' }))
    await waitFor(() =>
      expect(server.callsTo('PUT /helpdesk/articles/:id')[0]?.body).toEqual({
        title: 'Clearing any paper jam',
        body: 'Open the rear tray and pull the sheet out gently.',
        categoryId: 'c-general',
        version: 1,
      }),
    )
    expect(await screen.findByRole('heading', { name: 'Clearing any paper jam' })).toBeInTheDocument()
  })

  it('shows readers no actions', async () => {
    setup(anArticle(), ['helpdesk.article.read'])
    await screen.findByRole('heading', { name: 'Clearing a paper jam' })
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Unpublish' })).not.toBeInTheDocument()
  })
})
```

`frontend/src/features/helpdesk/TicketsPanel.test.tsx`:

```tsx
import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ALL_TENANT_PERMISSIONS } from '@/features/auth/permissions'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import {
  aCategory,
  aCustomerSummary,
  anAgent,
  aParty,
  aProduct,
  aTicket,
  aTicketContext,
  aTicketSummary,
  pageOf,
} from '@/test/records'
import { renderApp } from '@/test/renderApp'

function partyPage(modules: string[]) {
  const server = fakeServer()
  signedIn(server, testProfile({ modules }))
    .on('GET /parties/:id', { body: aParty() })
    .on('GET /parties', { body: pageOf([]) })
    .on('GET /activities', { body: pageOf([]) })
    .on('GET /documents', { body: [] })
    .on('GET /helpdesk/tickets', {
      body: pageOf([aTicketSummary({ status: 'RESOLVED', requester: { id: 'p-acme', name: 'Acme' } })]),
    })
    .on('GET /helpdesk/categories', { body: [aCategory()] })
    .on('GET /helpdesk/agents', { body: [anAgent()] })
    .on('GET /products', { body: pageOf([aProduct()]) })
    // the ticket page opens after a create
    .on('GET /helpdesk/tickets/:id', { body: aTicket({ id: 't-new' }) })
    .on('GET /helpdesk/tickets/:id/messages', { body: [] })
    .on('GET /helpdesk/tickets/:id/context', { body: aTicketContext() })
  return renderApp({ server, path: '/app/directory/p-acme' })
}

describe('TicketsPanel', () => {
  it("lists a party's tickets in every status", async () => {
    const { server } = partyPage(['HELPDESK'])
    const panel = await screen.findByRole('region', { name: 'Tickets' })
    expect(await within(panel).findByRole('link', { name: 'T-00001' })).toHaveAttribute(
      'href',
      '/app/helpdesk/tickets/t-1',
    )
    expect(within(panel).getByText('Resolved')).toBeInTheDocument()
    const query = server.callsTo('GET /helpdesk/tickets')[0].query
    expect(query.get('requesterId')).toBe('p-acme')
    expect(query.get('status')).toBe('NEW,OPEN,PENDING,RESOLVED,CLOSED')
  })

  it('opens a new ticket for the party', async () => {
    const { server, user } = partyPage(['HELPDESK'])
    server.on('POST /helpdesk/tickets', { status: 201, body: aTicket({ id: 't-new' }) })
    const panel = await screen.findByRole('region', { name: 'Tickets' })
    await user.click(within(panel).getByRole('button', { name: 'New ticket' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByLabelText('Requester')).toHaveValue('p-acme')
    await user.type(within(dialog).getByLabelText('Subject'), 'Late delivery')
    await user.type(within(dialog).getByLabelText('Description'), 'Order did not arrive.')
    await user.click(within(dialog).getByRole('button', { name: 'Create ticket' }))
    await waitFor(() =>
      expect(server.callsTo('POST /helpdesk/tickets')[0]?.body).toMatchObject({
        requesterId: 'p-acme',
        subject: 'Late delivery',
      }),
    )
  })

  it('is absent without HelpDesk', async () => {
    partyPage([])
    await screen.findByRole('heading', { name: 'Acme' })
    expect(screen.queryByRole('region', { name: 'Tickets' })).not.toBeInTheDocument()
  })

  it("lists a product's tickets", async () => {
    const server = fakeServer()
    signedIn(server, testProfile({ modules: ['HELPDESK'] }))
      .on('GET /products/:id', { body: aProduct() })
      .on('GET /activities', { body: pageOf([]) })
      .on('GET /documents', { body: [] })
      .on('GET /helpdesk/tickets', { body: pageOf([aTicketSummary()]) })
    renderApp({ server, path: '/app/products/pr-widget' })
    const panel = await screen.findByRole('region', { name: 'Tickets' })
    expect(await within(panel).findByRole('link', { name: 'T-00001' })).toBeInTheDocument()
    expect(within(panel).queryByRole('button', { name: 'New ticket' })).not.toBeInTheDocument()
    expect(server.callsTo('GET /helpdesk/tickets')[0].query.get('productId')).toBe('pr-widget')
  })

  it('appears on the Customer 360 page', async () => {
    const server = fakeServer()
    signedIn(
      server,
      testProfile({ modules: ['CRM', 'HELPDESK'], permissions: [...ALL_TENANT_PERMISSIONS] }),
    )
      .on('GET /crm/customers/:id', { body: aCustomerSummary() })
      .on('GET /parties', { body: pageOf([]) })
      .on('GET /opportunities', { body: pageOf([]) })
      .on('GET /leads', { body: pageOf([]) })
      .on('GET /activities', { body: pageOf([]) })
      .on('GET /documents', { body: [] })
      .on('GET /helpdesk/tickets', { body: pageOf([aTicketSummary()]) })
    renderApp({ server, path: '/app/crm/customers/p-acme' })
    const panel = await screen.findByRole('region', { name: 'Tickets' })
    expect(await within(panel).findByRole('link', { name: 'T-00001' })).toBeInTheDocument()
  })
})
```

Add to `HelpDeskLayout.test.tsx`:

```tsx
  it('opens the knowledge base for someone who may only read articles', async () => {
    const server = fakeServer()
    signedIn(
      server,
      testProfile({ modules: ['HELPDESK'], permissions: ['helpdesk.article.read'] }),
    ).on('GET /helpdesk/articles', { body: pageOf([]) })
    const { router } = renderApp({ server, path: '/app/helpdesk' })
    expect(await screen.findByRole('heading', { name: 'Knowledge base' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/app/helpdesk/articles')
  })
```

(and add `pageOf` to its `@/test/records` import).

- [ ] **Step 2: Run them to verify they fail**

Run: `cd frontend && npx vitest run src/features/helpdesk`
Expected: FAIL. The article routes and `TicketsPanel` don't exist yet.

- [ ] **Step 3: The article form**

`frontend/src/features/helpdesk/ArticleFormDialog.tsx`:

```tsx
import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
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
import { TextField } from '@/components/form/TextField'
import { requiredText } from '@/features/auth/schemas'
import { useApi } from '@/lib/api/ApiContext'
import { ApiError } from '@/lib/api/client'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import type { ArticleView } from '@/lib/api/types'
import { CategorySelect } from './CategorySelect'
import { invalidateHelpDesk } from './invalidation'

const schema = z.object({
  title: requiredText(200),
  body: requiredText(50000),
  categoryId: z.string(),
})
type Values = z.infer<typeof schema>
const FIELDS = ['title', 'body', 'categoryId'] as const

/** Write a new draft, or edit an article. Editing a published article keeps it published. */
export function ArticleFormDialog({
  article,
  onClose,
  onSaved,
}: {
  article?: ArticleView
  onClose: () => void
  onSaved: (saved: ArticleView) => void
}) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [formError, setFormError] = useState<string | null>(null)
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: {
      title: article?.title ?? '',
      body: article?.body ?? '',
      categoryId: article?.category?.id ?? '',
    },
  })
  const onSubmit = form.handleSubmit(async (values) => {
    setFormError(null)
    const body = {
      title: values.title.trim(),
      body: values.body.trim(),
      categoryId: values.categoryId || null,
      ...(article ? { version: article.version } : {}),
    }
    try {
      const saved = article
        ? await api.put<ArticleView>(`/helpdesk/articles/${article.id}`, body)
        : await api.post<ArticleView>('/helpdesk/articles', body)
      queryClient.setQueryData(['helpdesk', 'article', saved.id], saved)
      await invalidateHelpDesk(queryClient)
      toast.success(article ? 'Changes saved.' : 'Draft saved.')
      onSaved(saved)
    } catch (error) {
      if (article && error instanceof ApiError && error.status === 409)
        void queryClient.invalidateQueries({ queryKey: ['helpdesk', 'article', article.id] })
      if (!applyFieldErrors(error, form.setError, FIELDS)) setFormError(problemMessage(error))
    }
  })
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{article ? `Edit ${article.title}` : 'New article'}</DialogTitle>
          <DialogDescription>
            Plain text. Write it the way you&apos;d explain it to a customer on the phone.
          </DialogDescription>
        </DialogHeader>
        <form noValidate onSubmit={(e) => void onSubmit(e)} className="space-y-4">
          <TextField form={form} name="title" label="Title" maxLength={200} />
          <CategorySelect
            id="field-categoryId"
            label="Category"
            value={form.watch('categoryId')}
            current={article?.category}
            error={form.formState.errors.categoryId?.message}
            onChange={(id) => form.setValue('categoryId', id)}
          />
          <TextAreaField form={form} name="body" label="Article" rows={12} maxLength={50000} />
          <FormError message={formError} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={form.formState.isSubmitting}>
              {article ? 'Save changes' : 'Save draft'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
```

- [ ] **Step 4: The article list and page**

`frontend/src/features/helpdesk/ArticlesPage.tsx`:

```tsx
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { NativeSelect } from '@/components/form/NativeSelect'
import { Pagination } from '@/components/Pagination'
import { EmptyState, ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useApi } from '@/lib/api/ApiContext'
import type { ArticleStatus, ArticleSummary, CategoryView, Page } from '@/lib/api/types'
import { formatDate } from '@/lib/format'
import { toQuery } from '@/lib/query'
import { ArticleFormDialog } from './ArticleFormDialog'
import { ARTICLE_STATUS_LABELS } from './labels'

const SIZE = 20

/** D13. Readers see published articles; writers also see drafts, and archived ones on request. */
export function ArticlesPage() {
  const api = useApi()
  const can = useCan()
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const canManage = can(PERMISSIONS.articleManage)
  const q = params.get('q') ?? ''
  const status = canManage ? (params.get('status') ?? '') : ''
  const category = params.get('category') ?? ''
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0)
  const [creating, setCreating] = useState(false)

  const articles = useQuery({
    queryKey: ['helpdesk', 'articles', { q, status, category, page }],
    queryFn: () =>
      api.get<Page<ArticleSummary>>(
        `/helpdesk/articles?${toQuery({ q, status, categoryId: category, page, size: SIZE })}`,
      ),
    placeholderData: keepPreviousData,
    refetchOnMount: 'always',
  })
  const categories = useQuery({
    queryKey: ['helpdesk', 'categories', false],
    queryFn: () => api.get<CategoryView[]>('/helpdesk/categories'),
    enabled: can(PERMISSIONS.ticketRead),
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
        title="Knowledge base"
        description="Answers your team can reuse. Published articles are suggested on matching tickets."
        actions={canManage && <Button onClick={() => setCreating(true)}>New article</Button>}
      />
      <div className="mb-4 flex flex-wrap items-end gap-3">
        <form onSubmit={onSearch} className="flex items-end gap-2" role="search">
          <div className="space-y-1.5">
            <Label htmlFor="article-q">Search articles</Label>
            <Input id="article-q" name="q" defaultValue={q} placeholder="Words in the title or text" />
          </div>
          <Button type="submit" variant="outline">
            Search
          </Button>
        </form>
        {canManage && (
          <div className="space-y-1.5">
            <Label htmlFor="article-status">Status</Label>
            <NativeSelect
              id="article-status"
              value={status}
              onChange={(e) => update({ status: e.target.value, page: '' })}
            >
              <option value="">Drafts and published</option>
              {(Object.keys(ARTICLE_STATUS_LABELS) as ArticleStatus[]).map((s) => (
                <option key={s} value={s}>
                  {ARTICLE_STATUS_LABELS[s]}
                </option>
              ))}
            </NativeSelect>
          </div>
        )}
        {(categories.data ?? []).length > 0 && (
          <div className="space-y-1.5">
            <Label htmlFor="article-category">Category</Label>
            <NativeSelect
              id="article-category"
              value={category}
              onChange={(e) => update({ category: e.target.value, page: '' })}
            >
              <option value="">Any category</option>
              {(categories.data ?? []).map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </NativeSelect>
          </div>
        )}
      </div>
      {articles.isPending ? (
        <ListSkeleton />
      ) : articles.isError ? (
        <ErrorState error={articles.error} onRetry={() => void articles.refetch()} />
      ) : articles.data.items.length === 0 ? (
        <EmptyState
          title={q || status || category ? 'No articles match.' : 'No articles yet.'}
          description={
            canManage
              ? 'Write down the answers you give most often.'
              : 'Published articles will appear here.'
          }
        />
      ) : (
        <>
          <ul className="divide-y rounded-lg border">
            {articles.data.items.map((a) => (
              <li key={a.id} className="space-y-1 p-4">
                <div className="flex flex-wrap items-center gap-2">
                  <Link
                    to={`/app/helpdesk/articles/${a.id}`}
                    className="font-medium underline-offset-4 hover:underline"
                  >
                    {a.title}
                  </Link>
                  {a.status !== 'PUBLISHED' && (
                    <Badge variant="outline">{ARTICLE_STATUS_LABELS[a.status]}</Badge>
                  )}
                  {a.category && (
                    <span className="text-sm text-muted-foreground">{a.category.name}</span>
                  )}
                  <span className="text-sm text-muted-foreground">
                    Updated {formatDate(a.updatedAt.slice(0, 10))}
                  </span>
                </div>
                <p className="text-sm text-muted-foreground">{a.excerpt}</p>
              </li>
            ))}
          </ul>
          <Pagination
            page={articles.data.page}
            size={articles.data.size}
            total={articles.data.total}
            onPage={(p) => update({ page: String(p) })}
          />
        </>
      )}
      {creating && (
        <ArticleFormDialog
          onClose={() => setCreating(false)}
          onSaved={(saved) => {
            setCreating(false)
            navigate(`/app/helpdesk/articles/${saved.id}`)
          }}
        />
      )}
    </>
  )
}
```

`formatDate` takes a `YYYY-MM-DD` date (`lib/format.ts`), so the page passes `updatedAt.slice(0, 10)`. The category filter needs `GET /helpdesk/categories`, which requires `helpdesk.ticket.read`. Article-only readers don't get the filter.

`frontend/src/features/helpdesk/ArticleDetailPage.tsx`:

```tsx
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { toast } from 'sonner'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { FormError } from '@/components/form/FormError'
import { ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { DocumentsPanel } from '@/features/records/DocumentsPanel'
import { useApi } from '@/lib/api/ApiContext'
import { problemMessage } from '@/lib/api/problems'
import type { ArticleView } from '@/lib/api/types'
import { formatDateTime } from '@/lib/format'
import { ArticleFormDialog } from './ArticleFormDialog'
import { invalidateHelpDesk } from './invalidation'
import { ARTICLE_STATUS_LABELS } from './labels'

export function ArticleDetailPage() {
  const { articleId = '' } = useParams()
  const api = useApi()
  const can = useCan()
  const queryClient = useQueryClient()
  const key = ['helpdesk', 'article', articleId]
  const article = useQuery({
    queryKey: key,
    queryFn: () => api.get<ArticleView>(`/helpdesk/articles/${articleId}`),
  })
  const [dialog, setDialog] = useState<'edit' | 'archive' | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function run(action: 'publish' | 'unpublish' | 'archive', version: number, success: string) {
    setBusy(true)
    setError(null)
    try {
      const updated = await api.post<ArticleView>(`/helpdesk/articles/${articleId}/${action}`, {
        version,
      })
      queryClient.setQueryData(key, updated)
      await invalidateHelpDesk(queryClient)
      toast.success(success)
      return true
    } catch (e) {
      setError(problemMessage(e))
      void queryClient.invalidateQueries({ queryKey: key })
      return false
    } finally {
      setBusy(false)
    }
  }

  const back = (
    <Link to="/app/helpdesk/articles" className="text-sm underline-offset-4 hover:underline">
      ← Knowledge base
    </Link>
  )
  if (article.isPending)
    return (
      <>
        {back}
        <ListSkeleton />
      </>
    )
  if (article.isError)
    return (
      <>
        {back}
        <ErrorState error={article.error} onRetry={() => void article.refetch()} />
      </>
    )
  const a = article.data
  const archived = a.status === 'ARCHIVED'
  const canManage = can(PERMISSIONS.articleManage) && !archived

  return (
    <div className="space-y-6">
      {back}
      <PageHeader
        title={a.title}
        description={[
          a.category?.name,
          a.author ? `by ${a.author.name}` : null,
          a.publishedAt ? `published ${formatDateTime(a.publishedAt)}` : null,
        ]
          .filter(Boolean)
          .join(' · ')}
        actions={
          <>
            <Badge variant={a.status === 'PUBLISHED' ? 'secondary' : 'outline'}>
              {ARTICLE_STATUS_LABELS[a.status]}
            </Badge>
            {canManage && (
              <>
                <Button variant="outline" size="sm" onClick={() => setDialog('edit')}>
                  Edit
                </Button>
                {a.status === 'DRAFT' ? (
                  <Button
                    size="sm"
                    disabled={busy}
                    onClick={() => void run('publish', a.version, 'Article published.')}
                  >
                    Publish
                  </Button>
                ) : (
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={busy}
                    onClick={() => void run('unpublish', a.version, 'Article moved back to drafts.')}
                  >
                    Unpublish
                  </Button>
                )}
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => {
                    setError(null)
                    setDialog('archive')
                  }}
                >
                  Archive
                </Button>
              </>
            )}
          </>
        }
      />
      {!dialog && <FormError message={error} />}
      <Card>
        <CardContent className="pt-6">
          <p className="whitespace-pre-wrap">{a.body}</p>
        </CardContent>
      </Card>
      <p className="text-sm text-muted-foreground">Last updated {formatDateTime(a.updatedAt)}</p>
      <DocumentsPanel subjectType="KB_ARTICLE" subjectId={a.id} archived={archived} />
      {dialog === 'edit' && (
        <ArticleFormDialog
          article={a}
          onClose={() => setDialog(null)}
          onSaved={() => setDialog(null)}
        />
      )}
      <ConfirmDialog
        open={dialog === 'archive'}
        title={`Archive ${a.title}?`}
        description="It stops being suggested on tickets and can't be published again."
        confirmLabel="Archive article"
        busy={busy}
        error={error}
        onCancel={() => setDialog(null)}
        onConfirm={() =>
          void run('archive', a.version, 'Article archived.').then((ok) => ok && setDialog(null))
        }
      />
    </div>
  )
}
```

In `frontend/src/features/helpdesk/routes.tsx` add:

```tsx
  {
    path: 'articles',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.articleRead]}>
        <ArticlesPage />
      </RequirePermission>
    ),
  },
  {
    path: 'articles/:articleId',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.articleRead]}>
        <ArticleDetailPage />
      </RequirePermission>
    ),
  },
```

- [ ] **Step 5: The Tickets panel and where it appears**

`frontend/src/features/helpdesk/TicketsPanel.tsx`:

```tsx
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useTenantSession } from '@/features/auth/tenantSession'
import { useApi } from '@/lib/api/ApiContext'
import type { Page, PartyRef, TicketSummary } from '@/lib/api/types'
import { toQuery } from '@/lib/query'
import { TICKET_STATUS_LABELS } from './labels'
import { currentTarget } from './sla'
import { SlaBadge } from './SlaBadge'
import { TicketFormDialog } from './TicketFormDialog'

const ALL_STATUSES = 'NEW,OPEN,PENDING,RESOLVED,CLOSED'

/** The latest tickets of a requester or about a product (D17), when HelpDesk is on. */
export function TicketsPanel({ requester, productId }: { requester?: PartyRef; productId?: string }) {
  const session = useTenantSession()
  const can = useCan()
  const api = useApi()
  const navigate = useNavigate()
  const [creating, setCreating] = useState(false)
  const enabled =
    session.state.status === 'authenticated' &&
    session.state.profile.modules.includes('HELPDESK') &&
    can(PERMISSIONS.ticketRead)
  const filter = requester ? { requesterId: requester.id } : { productId }
  const tickets = useQuery({
    queryKey: ['helpdesk', 'tickets', filter],
    queryFn: () =>
      api.get<Page<TicketSummary>>(
        `/helpdesk/tickets?${toQuery({ ...filter, status: ALL_STATUSES, size: 10 })}`,
      ),
    enabled,
  })
  if (!enabled) return null
  const items = tickets.data?.items ?? []
  return (
    <Card role="region" aria-label="Tickets">
      <CardHeader className="flex flex-row items-center justify-between">
        <CardTitle>Tickets</CardTitle>
        {requester && can(PERMISSIONS.ticketManage) && (
          <Button size="sm" variant="outline" onClick={() => setCreating(true)}>
            New ticket
          </Button>
        )}
      </CardHeader>
      <CardContent className="text-sm">
        {items.length === 0 ? (
          <p className="text-muted-foreground">No tickets yet.</p>
        ) : (
          <ul className="space-y-1">
            {items.map((t) => {
              const target = currentTarget(t)
              return (
                <li key={t.id} className="flex flex-wrap items-center gap-2">
                  <Link
                    to={`/app/helpdesk/tickets/${t.id}`}
                    className="underline-offset-4 hover:underline"
                  >
                    {t.number}
                  </Link>
                  <span>{t.subject}</span>
                  <span className="text-muted-foreground">{TICKET_STATUS_LABELS[t.status]}</span>
                  {(t.status === 'NEW' || t.status === 'OPEN' || t.status === 'PENDING') && (
                    <SlaBadge state={target.state} due={target.due} target={target.target} />
                  )}
                </li>
              )
            })}
          </ul>
        )}
      </CardContent>
      {creating && requester && (
        <TicketFormDialog
          requester={requester}
          onClose={() => setCreating(false)}
          onSaved={(saved) => {
            setCreating(false)
            navigate(`/app/helpdesk/tickets/${saved.id}`)
          }}
        />
      )}
    </Card>
  )
}
```

The requester select only lists parties from `GET /parties`. `TicketFormDialog` passes `requester` as `current` to `PartyPicker`, which pins it as an option, so the preselected value is valid even when the search returns nothing.

Place the panel:
- `frontend/src/features/directory/PartyDetailPage.tsx`: after `<PartyOrdersPanel partyId={p.id} />`, add `<TicketsPanel requester={{ id: p.id, name: p.name }} />` (import from `@/features/helpdesk/TicketsPanel`).
- `frontend/src/features/crm/Customer360Page.tsx`: after `<PartyOrdersPanel partyId={party.id} />`, add `<TicketsPanel requester={{ id: party.id, name: party.name }} />`.
- `frontend/src/features/products/ProductDetailPage.tsx`: after `<ProductStockPanel productId={p.id} kind={p.kind} />`, add `<TicketsPanel productId={p.id} />`.

- [ ] **Step 6: Run the tests, then the frontend checks**

Run: `cd frontend && npx vitest run src/features/helpdesk src/features/directory src/features/crm src/features/products`
Expected: PASS.

Run: `cd frontend && npx prettier --write src && npm run format:check && npm run lint && npm run typecheck && npm test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add frontend/src
git commit -m "feat(helpdesk-ui): the knowledge base, and ticket panels on parties, customers and products"
```

---

### Task 11: The end-to-end journey and the README

**Files:**
- Modify: `frontend/e2e/support/mailpit.ts` (adds `emailText`)
- Create: `frontend/e2e/helpdesk.spec.ts`
- Modify: `README.md`

**Interfaces:**
- Consumes: every HelpDesk screen from Tasks 7–10, the seeded categories ("General", "Billing", "Product issue", "Delivery"), and the emails from Tasks 3–4. The assignment email's subject is `You've been assigned T-00001: <subject>`; a public reply's subject is `[T-00001] <subject>`.
- Produces: `emailText(to: string, subjectContains: string): Promise<string>` in `e2e/support/mailpit.ts`.

- [ ] **Step 1: A Mailpit helper for any email**

Add to `frontend/e2e/support/mailpit.ts`. Extend `Summary` with `Subject: string`, then append:

```ts
/** Polls Mailpit for the newest email to `to` whose subject contains `subjectContains`; returns its text body. */
export async function emailText(to: string, subjectContains: string): Promise<string> {
  const deadline = Date.now() + 30_000
  while (Date.now() < deadline) {
    const search = (await (
      await fetch(`${MAILPIT}/api/v1/search?query=${encodeURIComponent(`to:"${to}"`)}`)
    ).json()) as { messages?: Summary[] }
    const found = (search.messages ?? []).find((m) => m.Subject.includes(subjectContains))
    if (found) {
      const message = (await (
        await fetch(`${MAILPIT}/api/v1/message/${found.ID}`)
      ).json()) as Message
      return message.Text
    }
    await new Promise((resolve) => setTimeout(resolve, 500))
  }
  throw new Error(`No email to ${to} with "${subjectContains}" within 30s`)
}
```

- [ ] **Step 2: The journey (spec §7 E2E)**

`frontend/e2e/helpdesk.spec.ts`:

```ts
import { expect, test, type Page } from '@playwright/test'
import { emailText, tokenFromEmail } from './support/mailpit'
import { newWorkspace, PASSWORD, signIn, signUpAndVerify } from './support/workspace'

function helpdeskTab(page: Page, name: string) {
  return page.getByRole('navigation', { name: 'HelpDesk' }).getByRole('link', { name })
}

test('a customer call becomes a ticket with its context, is answered within SLA, waits, and is resolved', async ({
  page,
  browser,
}) => {
  const ws = newWorkspace('hd')
  const agentEmail = `ravi-${ws.slug}@e2e.test`
  const customerEmail = `meera-${ws.slug}@e2e.test`
  await signUpAndVerify(page, ws)
  await signIn(page, ws, ws.email)
  await expect(page.getByRole('heading', { name: 'Welcome, Ada' })).toBeVisible()
  const nav = page.getByRole('navigation', { name: 'Workspace' })

  // Enable HelpDesk and Inventory.
  await page.goto('/app/settings/modules')
  await page.getByRole('switch', { name: 'HelpDesk' }).click()
  await expect(page.getByText('HelpDesk enabled.')).toBeVisible()
  await page.getByRole('switch', { name: 'Inventory' }).click()
  await expect(page.getByText('Inventory enabled.')).toBeVisible()

  // An agent joins the team.
  await page
    .getByRole('navigation', { name: 'Settings' })
    .getByRole('link', { name: 'Users' })
    .click()
  await page.getByRole('button', { name: 'Invite people' }).click()
  let dialog = page.getByRole('dialog')
  await dialog.getByLabel('Email').fill(agentEmail)
  await dialog.getByLabel('Role').selectOption({ label: 'TENANT_ADMIN' })
  await dialog.getByRole('button', { name: 'Send invitation' }).click()
  await expect(page.getByText(`Invitation sent to ${agentEmail}.`)).toBeVisible()
  const token = await tokenFromEmail(agentEmail, '/invite/accept')
  const agentContext = await browser.newContext()
  const agent = await agentContext.newPage()
  await agent.goto(`/invite/accept?token=${encodeURIComponent(token)}`)
  await agent.getByLabel('First name').fill('Ravi')
  await agent.getByLabel('Last name').fill('Kumar')
  await agent.getByLabel('Password', { exact: true }).fill(PASSWORD)
  await agent.getByLabel('Repeat password', { exact: true }).fill(PASSWORD)
  await agent.getByRole('button', { name: `Join ${ws.name}` }).click()
  await expect(agent.getByRole('heading', { name: "You're in" })).toBeVisible()
  await agentContext.close()

  // Product issues go to Ravi.
  await page
    .getByRole('navigation', { name: 'Settings' })
    .getByRole('link', { name: 'HelpDesk' })
    .click()
  const categories = page.getByRole('region', { name: 'Ticket categories' })
  await categories.getByRole('button', { name: 'Edit Product issue' }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByLabel('Default assignee', { exact: true }).selectOption({ label: 'Ravi Kumar' })
  await dialog.getByRole('button', { name: 'Save changes' }).click()
  await expect(categories.getByRole('row', { name: /Product issue/ })).toContainText('Ravi Kumar')

  // The knowledge base already has the answer.
  await nav.getByRole('link', { name: 'HelpDesk' }).click()
  await helpdeskTab(page, 'Knowledge base').click()
  await page.getByRole('button', { name: 'New article' }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByLabel('Title').fill('Clearing a paper jam')
  await dialog.getByLabel('Article').fill('Open the rear tray and pull the jammed sheet out gently.')
  await dialog.getByRole('button', { name: 'Save draft' }).click()
  await expect(page.getByRole('heading', { name: 'Clearing a paper jam' })).toBeVisible()
  await page.getByRole('button', { name: 'Publish' }).click()
  await expect(page.getByText('Article published.')).toBeVisible()

  // The customer, a printer they bought, and their order.
  await nav.getByRole('link', { name: 'Products' }).click()
  await page.getByRole('button', { name: 'New product' }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByLabel('SKU').fill('PR-1')
  await dialog.getByLabel('Name').fill('Office printer')
  await dialog.getByLabel('List price').fill('15000')
  await dialog.getByLabel('Currency').fill('INR')
  await dialog.getByRole('button', { name: 'Create product' }).click()
  await expect(page.getByRole('heading', { name: 'Office printer' })).toBeVisible()
  await nav.getByRole('link', { name: 'Directory' }).click()
  await page.getByRole('button', { name: 'New person' }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByLabel('First name').fill('Meera')
  await dialog.getByLabel('Last name').fill('Iyer')
  await dialog.getByLabel('Email').fill(customerEmail)
  await dialog.getByRole('button', { name: 'Create person' }).click()
  await expect(page.getByRole('heading', { name: 'Meera Iyer' })).toBeVisible()
  const meeraUrl = page.url()
  await nav.getByRole('link', { name: 'Inventory' }).click()
  await page
    .getByRole('navigation', { name: 'Inventory' })
    .getByRole('link', { name: 'Sales orders' })
    .click()
  await page.getByRole('button', { name: 'New sales order' }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByLabel('Customer', { exact: true }).selectOption({ label: 'Meera Iyer' })
  await dialog.getByLabel('Warehouse').selectOption({ label: 'MAIN · Main warehouse' })
  await dialog
    .getByLabel('Line 1 product', { exact: true })
    .selectOption({ label: 'PR-1 · Office printer' })
  await dialog.getByLabel('Line 1 quantity').fill('1')
  await dialog.getByRole('button', { name: 'Create sales order' }).click()
  await expect(page.getByRole('heading', { name: 'SO-00001' })).toBeVisible()

  // Meera calls: a ticket from her page, about the printer and the order. The category routes it to Ravi.
  await page.goto(meeraUrl)
  await page
    .getByRole('region', { name: 'Tickets' })
    .getByRole('button', { name: 'New ticket' })
    .click()
  dialog = page.getByRole('dialog')
  await expect(dialog.getByLabel('Requester', { exact: true })).toHaveValue(/.+/)
  await dialog.getByLabel('Subject').fill('Printer jams on every page')
  await dialog
    .getByLabel('Description')
    .fill('Every page causes a paper jam since the printer was delivered.')
  await dialog.getByLabel('Product', { exact: true }).selectOption({ label: 'PR-1 · Office printer' })
  await dialog.getByLabel('Find a related record').fill('SO-00001')
  const order = dialog.getByLabel('Related record', { exact: true }).locator('option', {
    hasText: 'SO-00001',
  })
  await expect(order).toHaveCount(1)
  await dialog
    .getByLabel('Related record', { exact: true })
    .selectOption((await order.getAttribute('value')) ?? '')
  await dialog.getByLabel('Category').selectOption({ label: 'Product issue' })
  await dialog.getByLabel('Priority').selectOption({ label: 'High' })
  await dialog.getByRole('button', { name: 'Create ticket' }).click()
  await expect(
    page.getByRole('heading', { name: 'T-00001 · Printer jams on every page' }),
  ).toBeVisible()
  const details = page.getByRole('region', { name: 'Details' })
  await expect(details).toContainText('Ravi Kumar')
  await expect(details.getByRole('link', { name: /SO-00001/ })).toBeVisible()
  expect(await emailText(agentEmail, "You've been assigned T-00001")).toContain(
    'Printer jams on every page',
  )

  // The ticket page suggests the article; the agent replies with it.
  const context = page.getByRole('region', { name: 'Context' })
  await context.getByRole('button', { name: 'Insert Clearing a paper jam into reply' }).click()
  await expect(page.getByLabel('Message', { exact: true })).toHaveValue(/Open the rear tray/)
  await page.getByRole('button', { name: 'Send reply' }).click()
  await expect(page.getByText(`Reply emailed to ${customerEmail}.`)).toBeVisible()
  const sla = page.getByRole('region', { name: 'SLA' })
  await expect(sla.getByText('First response: Met')).toBeVisible()
  expect(await emailText(customerEmail, '[T-00001] Printer jams on every page')).toContain(
    'Open the rear tray',
  )

  // Waiting on Meera pauses the clock; her answer opens the ticket again.
  await page.getByRole('button', { name: 'Wait on customer' }).click()
  await expect(sla.getByText('Resolution: Paused')).toBeVisible()
  await page.getByLabel('Message type').selectOption('CUSTOMER_MESSAGE')
  await page.getByLabel('Message', { exact: true }).fill('Meera called back: the tray was the problem.')
  await page.getByRole('button', { name: 'Log customer message' }).click()
  await expect(page.getByText('Customer message logged.')).toBeVisible()
  await expect(sla.getByText('Resolution: On track')).toBeVisible()

  // Resolved with a note.
  await page.getByRole('button', { name: 'Resolve…' }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByLabel('Resolution note').fill('Cleared the rear tray; printing normally.')
  await dialog.getByRole('button', { name: 'Resolve' }).click()
  await expect(page.getByText('T-00001 resolved.')).toBeVisible()
  await expect(sla.getByText('Resolution: Met')).toBeVisible()

  // The dashboard counts one resolved ticket, within SLA.
  await helpdeskTab(page, 'Dashboard').click()
  const month = page.getByRole('region', { name: 'Last 30 days' })
  await expect(month.getByRole('definition').nth(0)).toHaveText('1') // created
  await expect(month.getByRole('definition').nth(1)).toHaveText('1') // resolved
  await expect(month.getByText('First response within SLA').locator('..')).toContainText('100%')
  await expect(month.getByText('Resolved within SLA').locator('..')).toContainText('100%')
})
```

Notes for the implementer:
- The invite dialog's Role select shows role names. The system admin role is `TENANT_ADMIN` (`SystemRoles.ADMIN`). V24 grants it every HelpDesk permission (`grant_to_system_roles`), so once Ravi accepts he is an active member who can be assigned.
- `Message` is matched with `exact: true` because "Message type" also starts with "Message".
- `Product` is matched with `exact: true` because "Find product" also contains it. `Requester` and `Related record` are exact for the same reason.
- Every `dd` in the dashboard has the `definition` role. Created and Resolved are the first two in "Last 30 days".

Run against a rebuilt stack: `make e2e`. Expected: all journeys pass, including `helpdesk.spec.ts`.

- [ ] **Step 3: README**

In `README.md`:
- Add `- HelpDesk (Phase 7): \`docs/superpowers/specs/2026-10-09-helpdesk-mvp-design.md\`` after the Inventory spec line.
- After the `## Inventory (Phase 6)` section, add:

```markdown
## HelpDesk (Phase 7)
Switch the module on under **Settings → Modules**. Every HelpDesk permission switches off with it (ADR-0011).

- **Tickets** (`T-00001`, per workspace) belong to a requester from the directory. They can name a catalog product and
  any related record you may read, such as a sales order, a deal or a lead. A category routes a new ticket to its
  default assignee, and assigning someone else emails them.
- **SLA** targets per priority live under **Settings → HelpDesk** (defaults: urgent 1 h / 4 h, high 4 h / 1 d, normal
  8 h / 2 d, low 1 d / 5 d). The first public reply is the first response. Time *waiting on the customer* doesn't
  count towards resolution, and reopening restarts the resolution clock (ADR-0012).
- **Conversation**: a reply to the customer is emailed to the requester's address. An internal note stays inside the
  team. Logging a customer message moves a waiting ticket back to open. Messages can't be edited or deleted.
- **Context** next to each ticket: the requester, their previous tickets, possible duplicates and suggested
  knowledge-base articles, found with PostgreSQL full-text search. An article can be inserted into a reply.
- **Knowledge base**: draft → published (suggested on tickets) → archived.
- **Dashboard**: open tickets by status and priority, unassigned, breached and at-risk tickets, and for the last 30
  days the first-response and resolution times, the SLA met rates and the reopen rate.
- Party, Customer 360 and product pages show their tickets. Tickets have tasks, files and activity like any record.
```

- [ ] **Step 4: Run every suite, then commit**

Run: `cd backend && ./gradlew test` — Expected: PASS.
Run: `cd frontend && npx prettier --write src e2e && npm run format:check && npm run lint && npm run typecheck && npm test` — Expected: PASS.
Run: `make e2e` — Expected: PASS.

```bash
git add frontend/e2e README.md
git commit -m "test(e2e): the HelpDesk journey from a customer call to a resolved ticket; README"
```

