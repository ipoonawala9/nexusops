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
