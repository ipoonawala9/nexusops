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
- CRM routes check the record's state (404, stale 409) before validating the body (400); this deviates from the platform's 400→403→404→409 order and is accepted for consistency across CRM.
- Several pipelines, line items, merging duplicates and AI summaries are later work (spec §1 non-goals).
