# ADR-0008: Canonical business identity (party model)

- **Status:** Accepted
- **Date:** 2026-10-06

## Context
The blueprint (§5.1) requires one identity per real-world customer, product and employee. "CRM Customer #101" and
"HelpDesk Customer #884" must not be two records for the same company. Phases 5–8 each need customers, suppliers or
employees, and must share them.

## Decision
- **Party pattern.**
  - A single `parties` table holds people and organizations (`kind`).
  - The business roles a party plays (CUSTOMER, SUPPLIER, EMPLOYEE) are rows in `party_roles`, not separate entities.
  - A company that both buys and sells is one party with two roles.
  - Module-specific data (a CRM segment, an HR contract) will hang off the party id in that module's own tables.
- **Duplicates are refused unless explained.** A create or identity-changing edit that matches existing parties
  returns 409 with the candidates. A party matches when it has:
  - the same email (people);
  - the same normalized name within the same organization (people);
  - the same domain (organizations);
  - the same name ignoring case, accents, punctuation and legal suffixes (organizations).

  The caller may proceed by giving a `duplicateReason`, which is stored and audited. Checks run under a per-tenant
  advisory lock, so concurrent creates can't both slip through.
- **Archive, never delete.** Parties and products are referenced across modules, and through polymorphic subjects
  that have no foreign key. Archiving keeps every reference valid. Archived records take no new roles or attachments.
- **Employees are a guarded role.** Seeing or managing EMPLOYEE roles needs separate permissions, so a sales role with
  directory access doesn't see HR facts.
- **Subjects.** Activities, tasks and documents attach to `(subject_type, subject_id)`. Each owning module implements
  `SubjectResolver`, which names the read permission that guards attachments. Collaboration depends on no business
  module.

## Consequences
- Matching is deliberately simple and explainable. Fuzzy matching and merging of existing duplicates are deferred to
  CRM (Phase 5), and the stored reasons give that work its review queue.
- A polymorphic subject has no database FK. Integrity is checked at write time and kept by the archive-only rule.
