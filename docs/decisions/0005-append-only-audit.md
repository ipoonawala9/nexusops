# ADR-0005: Append-only audit log written in the same transaction

- Status: Accepted
- Date: 2026-10-04

## Context
§18 requires audit records that ordinary tenant users cannot edit. The event backbone (outbox and Kafka) arrives only in Phase 10.

## Decision
- `audit_events` is written by `AuditService` **inside the same transaction** as the business change, so an audit record exists if and only if the change committed.
- The database role `nexusops_app` gets only INSERT and SELECT on the table. A trigger rejects UPDATE and DELETE.
- RLS scopes reads to the current tenant.
- Sensitive fields (password and token hashes, TOTP secrets) are excluded from before/after snapshots.
- The API is read-only and requires `audit.event.read`.

## Consequences
- Audit records are reliable and tamper-resistant at the application level.
- Some events are not tied to a business commit, such as a failed login. Those are written in their own `REQUIRES_NEW` transaction.
- In Phase 10, audit can also be fed from domain events. This ADR will be revisited then.
