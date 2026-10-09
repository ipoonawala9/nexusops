# Design Spec — NexusOps HelpDesk MVP (Phase 7)

- **Status:** Accepted (product decisions taken on the owner's standing instruction to proceed without re-asking)
- **Date:** 2026-10-09
- **Source:** blueprint §2.2 (deeper HelpDesk problem: a ticket treated as an isolated record), §9 (process
  intelligence: first response, resolution time, SLA breach rate, reopen rate), §15 (HelpDesk entities), §16
  (Inventory → HelpDesk flow), §5.4 (rules first, then ML, human approval), Phase 7 in the roadmap. The conventions of
  the platform, canonical data model, CRM and Inventory specs all apply (isolation, audit, errors 400 → 403 → 404 → 409,
  paging, full-replacement edits with `version`, archived-never-deleted records, module-gated permissions).

## 1. Intent

### Outcome
A small business can take a customer's problem — a phone call, an email, a walk-in — and turn it into a **ticket that
carries its context**: who the customer is, which product and which order it concerns, the customer's previous tickets
and the knowledge articles that might answer it. Each ticket has a priority, an owner and a **service-level clock**
(first response and resolution targets) that pauses while the business waits for the customer. Agents reply publicly
(the customer is emailed) or leave internal notes, attach files, and move the ticket through a clear status workflow. A
knowledge base holds the answers the team gives again and again. Managers see what is breaching, how fast the team
responds and resolves, and how often tickets come back.

### Success criteria (blueprint Phase 7 exit)
1. Tickets, categories, priorities, SLA policies, assignment, the conversation (public replies and internal notes),
   attachments, the status workflow, email notifications and a knowledge base work end to end (database, API, UI),
   tenant isolated at both layers like every earlier table.
2. **A ticket is not an isolated record:** it links to the canonical requester (person or organisation), optionally a
   catalog product and any record the user may read (e.g. a sales order), and shows the requester's previous tickets,
   possible duplicates and suggested knowledge articles.
3. **SLA is explicit and honest:** every ticket shows its first-response and resolution due times, which are computed
   by a deterministic rule from its priority, pause while waiting on the customer, and are marked met, at risk or
   breached.
4. The process-intelligence measures of blueprint §9 are recorded per ticket (created, first response, resolved, closed,
   reopen count) and summarised on a dashboard.
5. Everything HelpDesk switches off with the module: every new permission belongs to module `HELPDESK`. Every write is
   audited and permission-gated.

### Non-goals (deferred)
- AI classification, routing, duplicate detection with language models, response drafting, RAG: Phase 12. This phase
  supplies the deterministic baselines they will improve on (category → default assignee routing, full-text duplicate
  candidates and article suggestions).
- Inbound email (mailbox polling) and a customer self-service portal: later. Customers don't sign in; agents log
  customer messages on their behalf.
- Business-hours and holiday calendars for SLA: later. SLA clocks run on calendar time in the workspace's time zone.
- Automatic closing of resolved tickets after N days and scheduled breach notifications: Phase 9 (workflow engine).
- Teams/queues, skills-based routing, macros, satisfaction surveys (CSAT): later.
- Markdown rendering of articles: articles are plain text with line breaks.

## 2. Approach

How the SLA clock is kept was the main design question:

| Option | Verdict |
|---|---|
| **A. Due times stored on the ticket, recomputed on every status change (pause/resume shifts the resolution due time by the paused duration)** | **Chosen.** Reads and filters ("breached", "due in the next hour") are plain SQL comparisons with `now()`; no scheduler is needed; the rule is easy to test. |
| B. A scheduler that ticks every ticket | Needs background jobs (Phase 9) and gets "breached" wrong between ticks. |
| C. Compute everything from the event history on read | Correct but expensive to filter and sort lists by. |

For "possible duplicates" and "suggested articles", PostgreSQL full-text search (`tsvector` + GIN index, `websearch`
queries) was chosen over trigram similarity (needs an extension the runtime role can't create) and over an embedding
index (Phase 12).

## 3. Decisions

| # | Topic | Decision |
|---|---|---|
| D1 | Module | New Modulith module `helpdesk`. Depends on `directory` (requester parties, `ensureRole` not used — a requester needn't be a customer), `catalog` (product lookups), `collaboration` (SubjectResolver/SubjectRelations SPIs and `Subjects` for linked records), `identity` (`Members`), `notifications` (`MailRequested`), `tenancy`, `audit`, `shared`. Nothing depends on `helpdesk`. |
| D2 | Ticket numbers | `T-00001` per workspace, from the shared per-tenant number sequence: `number_sequences` (V18) gains kind `TICKET`; the sequence helper moves from `inventory` to `shared` so both modules use it. |
| D3 | Ticket | Subject (1–200), description (≤ 10 000, required), requester (a non-archived party; person or organisation; required), optional product (catalog, any kind, not archived when chosen), optional **linked record** (`linkedType`+`linkedId`: any collaboration subject type the user may read, e.g. `SALES_ORDER`, `OPPORTUNITY`; validated through `Subjects`), category (optional), priority, channel (`PHONE`, `EMAIL`, `WALK_IN`, `WEB`, `OTHER`), assignee (an active member, optional), status, SLA fields (D8), timestamps (D10), `reopen_count`, `version`. Editable fields via full `PUT` with `version`: subject, description, requester, product, linked record, category, priority, channel. Status and assignee change through their own actions. |
| D4 | Priorities | Fixed enum `LOW`, `NORMAL`, `HIGH`, `URGENT` (default NORMAL). Changing priority recomputes the SLA due times from the ticket's creation (keeping paused time). |
| D5 | Categories | Per workspace: name (1–80, unique ignoring case), optional description, optional **default assignee** (an active member; deterministic routing baseline), position; archive/restore (archived categories can't be chosen for new tickets; existing tickets keep them). A new ticket without an explicit assignee gets its category's default assignee. Seeded per workspace: "General", "Billing", "Product issue", "Delivery" (migration for existing workspaces, `WorkspaceRegistered` for new ones). |
| D6 | Status workflow | `NEW` → `OPEN` → `PENDING` (waiting on the customer) → `OPEN` …; `NEW`/`OPEN`/`PENDING` → `RESOLVED` (resolution note required, 1–2 000) → `CLOSED`; `RESOLVED` → `OPEN` is a **reopen** (`reopen_count + 1`); `CLOSED` is final. Assigning a NEW ticket or posting the first public reply moves it to OPEN. Invalid transitions → `409`. Moves: `POST tickets/{id}/status {status, note?, version}`. |
| D7 | Assignment | `POST tickets/{id}/assign {assigneeId|null, version}` (an active member or unassigned). Assigning someone else emails them after commit ("You've been assigned T-00012: …" with a link). "Mine", "Unassigned" and per-agent filters. |
| D8 | SLA policies | One policy row per priority per workspace: first-response target and resolution target in minutes (> 0, ≤ 60 days), seeded: URGENT 60 / 240, HIGH 240 / 1 440, NORMAL 480 / 2 880, LOW 1 440 / 7 200. Editable in Settings. On create: `first_response_due_at = created_at + first target`, `resolution_due_at = created_at + resolution target`. **Pause:** entering PENDING stores `paused_at`; leaving it adds the paused duration to `resolution_due_at` (first-response due is not paused). `first_responded_at` is set by the first public reply; `resolved_at` by RESOLVED (cleared on reopen, which also restarts the resolution clock from the reopen time with the full target). SLA state per target: `MET` (done before due), `BREACHED` (done after due, or not done and `now > due`), `AT_RISK` (not done, not paused, less than 25 % of the target left), `ON_TRACK`, `PAUSED`. Policy changes apply to new tickets and to tickets whose priority changes afterwards. |
| D9 | Conversation | `ticket_messages`: kind `PUBLIC_REPLY` (agent to customer), `INTERNAL_NOTE` (agents only), `CUSTOMER_MESSAGE` (logged by an agent on the customer's behalf; puts a PENDING ticket back to OPEN); body 1–10 000; author member; `created_at`. Messages are append-only (no edit/delete). A public reply emails the requester's address (person email, or the organisation's email) when one exists, subject "[T-00012] {subject}", from the workspace name; otherwise the reply is still recorded and the UI notes that no email was sent. |
| D10 | Process timestamps | `created_at`, `first_responded_at`, `resolved_at`, `closed_at`, `reopen_count`, and every status/assignment/priority change as an audit event (`TicketCreated`, `TicketUpdated`, `TicketAssigned`, `TicketStatusChanged`, `TicketReplied`, `TicketNoteAdded`, `TicketCustomerMessage`) — the event history blueprint §9 analyses later. |
| D11 | Attachments, tasks, activity | Tickets are collaboration subjects (type `TICKET`), so the existing Documents, Tasks and Activity panels work on them unchanged. |
| D12 | Context on the ticket page | Requester card (name, email, phone, link), **previous tickets** of the same requester (latest 10, with status), the product and linked record, **possible duplicates** (open tickets of the same requester whose subject+description match this ticket's subject by full-text search, best 5), and **suggested articles** (published articles matching subject+description, best 5). |
| D13 | Knowledge base | `kb_articles`: title (1–200), body (1–50 000, plain text), category (optional, same categories), status `DRAFT`/`PUBLISHED`/`ARCHIVED`, author, `published_at`, `version`. Full-text search over title (weight A) and body (B). Agents insert an article's link into a reply from the ticket page. |
| D14 | Collaboration | `TICKET` (label `T-00012 · subject`, search by number and subject) and `KB_ARTICLE` (label title) subjects; a party's timeline includes its tickets (SubjectRelations). Search order gains `TICKET`, `KB_ARTICLE` after the order types. |
| D15 | Dashboard | `GET helpdesk/dashboard`: open tickets by status and by priority, unassigned count, breached and at-risk counts, and for tickets created in the last 30 days: average and median first-response minutes, average resolution minutes, SLA met rate (first response and resolution), reopen rate. |
| D16 | Permissions | Module `HELPDESK`: `helpdesk.ticket.read` (kept), `helpdesk.ticket.manage` (create, edit, reply, note, log customer message — new), `helpdesk.ticket.assign` (kept), `helpdesk.ticket.resolve` (kept: resolve, close, reopen, pending/open moves), `helpdesk.settings.manage` (categories, SLA policies — new), `helpdesk.article.read` (new), `helpdesk.article.manage` (new). Choosing a requester needs `directory.party.read`; choosing a product needs `catalog.product.read`; linking a record needs that record type's read permission. System roles get the new codes by migration. |
| D17 | UI | HelpDesk nav entry (replacing "coming in Phase 7") with tabs: Dashboard, Tickets, Knowledge base. Ticket list with filters (status set: open/pending/resolved/closed/all, priority, assignee incl. mine/unassigned, category, SLA breached/at risk, search) and SLA badges; a ticket page with the conversation, reply/note/customer-message composer, status and assignment actions, SLA panel and the context of D12, plus Tasks, Documents and Activity panels. Settings → HelpDesk (categories, SLA policies). Party and Customer 360 pages get a Tickets panel; product pages too. |
| D18 | Messages | Verbatim: `"Record not found."`, `"This record is archived."`, `"This record was changed by someone else. Reload and try again."`, `"You do not have permission to perform this action."`, `"Reload the record and try again."`, `"A closed ticket can't be changed."`, `"Add a resolution note."`, `"Choose a person or organization in this workspace."`, `"Choose an active team member."`, `"Choose a category in this workspace."`. |

## 4. Data model (Flyway V24–V27; V23 is the password-reset table)

All tables are tenant-owned with `ENABLE`/`FORCE ROW LEVEL SECURITY` and the standard `tenant_isolation` policy in
`USING` and `WITH CHECK`, created in the same migration, `UNIQUE (tenant_id, id)` for composite FKs, member references
`REFERENCES users (id) ON DELETE SET NULL`.

| Migration | Contents |
|---|---|
| V24 helpdesk base | Permissions (D16); `number_sequences` CHECK gains `TICKET`; `ticket_categories(id, tenant_id, name, name_key, description, default_assignee_id, position, archived_at, …, version)` UNIQUE (tenant_id, name_key); `sla_policies(id, tenant_id, priority, first_response_minutes, resolution_minutes, updated_at, version)` UNIQUE (tenant_id, priority); seeds for existing workspaces. |
| V25 tickets | `tickets(id, tenant_id, number, subject, description, requester_id, product_id, linked_type, linked_id, category_id, priority, channel, assignee_id, status, first_response_due_at, resolution_due_at, paused_at, first_responded_at, resolved_at, closed_at, resolution_note, reopen_count, created_by, created_at, updated_at, version, search tsvector GENERATED)` UNIQUE (tenant_id, number), composite FKs to parties, products, categories; CHECKs on status/priority/channel and status↔timestamps; indexes (tenant, status, priority), (tenant, assignee, status), (tenant, requester), (tenant, resolution_due_at) partial on open, GIN (search). |
| V26 ticket messages | `ticket_messages(id, tenant_id, ticket_id, kind, body, author_id, emailed_to, created_at)`; app role SELECT/INSERT only (append-only like `stock_movements`). |
| V27 knowledge base | `kb_articles(id, tenant_id, title, body, category_id, status, author_id, published_at, created_at, updated_at, version, search tsvector GENERATED)` GIN (search). |

## 5. API (`/api/v1`)

| Route | Authorization |
|---|---|
| `GET helpdesk/tickets?q=&status=&priority=&assigneeId=|me|unassigned&categoryId=&requesterId=&productId=&sla=breached|at_risk&page=&size=`, `GET helpdesk/tickets/{id}` | `helpdesk.ticket.read` |
| `GET helpdesk/tickets/{id}/context` (previous tickets, duplicates, suggested articles — D12) | `helpdesk.ticket.read` (articles only with `helpdesk.article.read`) |
| `POST helpdesk/tickets`, `PUT helpdesk/tickets/{id}`, `POST …/{id}/messages {kind, body}` | `helpdesk.ticket.manage` (+ `directory.party.read` / `catalog.product.read` / the linked type's read permission for references) |
| `POST …/{id}/assign` | `helpdesk.ticket.assign` |
| `POST …/{id}/status` | `helpdesk.ticket.resolve` |
| `GET helpdesk/tickets/{id}/messages` | `helpdesk.ticket.read` |
| `GET helpdesk/categories?archived=`, `GET helpdesk/sla-policies` | `helpdesk.ticket.read` |
| `POST/PUT helpdesk/categories…`, `POST …/{id}/archive|restore`, `PUT helpdesk/sla-policies/{priority}` | `helpdesk.settings.manage` |
| `GET helpdesk/articles?q=&status=&categoryId=`, `GET helpdesk/articles/{id}` | `helpdesk.article.read` (drafts and archived also need `helpdesk.article.manage`) |
| `POST/PUT helpdesk/articles…`, `POST …/{id}/publish|unpublish|archive` | `helpdesk.article.manage` |
| `GET helpdesk/dashboard` | `helpdesk.ticket.read` |

Errors follow the established order. Unknown or other-tenant references in bodies are 400 field errors; path ids are
404.

## 6. Security and isolation

- The 5 new tables join `RlsCoverageIT.EXPECTED_TENANT_TABLES`; a raw-JDBC test proves tenant A sees none of B's rows,
  an unbound connection sees none, cross-tenant inserts are rejected, and `ticket_messages` can't be updated or deleted
  by the app role.
- `CrossTenantApiIT` covers every new id-bearing route; a module-gate test covers every HelpDesk handler.
- Internal notes are never emailed and never shown through any customer-facing path (there is none yet); the public
  reply email contains only that reply.
- Full-text queries use `websearch_to_tsquery('simple', :q)` with bound parameters, plus the explicit tenant predicate.

## 7. Testing

- **Unit:** the SLA clock (due times per priority, pause/resume, reopen, priority change, states met/at risk/breached,
  25 % threshold), the status transition table.
- **Integration:** every route's happy path and 400/403/404/409 branches; default-assignee routing; first public reply
  sets `first_responded_at` and moves NEW → OPEN; customer message reopens PENDING → OPEN; resolve needs a note; reopen
  increments the count; closed is final; public reply emails the requester (and notes don't); assignment emails the
  assignee; duplicates and suggested articles by full-text search; dashboard figures; module-off 403s.
- **Isolation:** RLS coverage, raw-JDBC isolation, cross-tenant API suite, handler-level permission gate.
- **Frontend:** Vitest page tests against `fakeServer`.
- **E2E:** enable HelpDesk → write and publish an article → a customer calls: create a ticket for the canonical customer,
  linked to their sales order → the category routes it to an agent (email in Mailpit) → the ticket page suggests the
  article → reply publicly with it (customer email in Mailpit, ticket OPEN, first response met) → set PENDING → log the
  customer's answer (back to OPEN) → resolve with a note → dashboard shows one resolved ticket within SLA.
