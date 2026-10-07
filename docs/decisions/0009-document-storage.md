# ADR-0009: Document storage behind a port, PostgreSQL first

- **Status:** Accepted
- **Date:** 2026-10-06

## Context
Phase 4 introduces documents. The blueprint names S3 (MinIO locally) for storage, but cloud deployment is Phase 14.
Running an object store locally and in CI now would add another service and another credential set, plus orphan-object
handling, while the platform isn't deployed anywhere yet.

## Decision
- Document bytes go through a `DocumentStorage` port. The first adapter stores them in `document_contents`
  (bytea), which is RLS-protected and written in the same transaction as the metadata, so no orphan can exist.
- There's a 10 MB limit per file, enforced in the service, by Spring multipart limits and by nginx
  (`client_max_body_size 11m`, on the upload route only). Each plan has a storage quota (`plans.limits.maxStorageMb`), checked under a
  per-tenant lock.
- Downloads are served by the API, never from a public URL. They are always `Content-Disposition: attachment` with
  `X-Content-Type-Options: nosniff` and `Cache-Control: no-store, private`, whatever type the client declared. The
  file name is sanitized, and the SHA-256 of the content is recorded and audited.

## Consequences
- Database size grows with documents, bounded by the quotas. Backups include documents.
- The S3 adapter (Phase 14) must:
  - key objects as `tenants/{tenantId}/documents/{documentId}`;
  - write the object before the metadata commits, and delete it after the metadata row is removed;
  - provide a sweeper for objects left by failed transactions.
- Malware scanning is deferred to production hardening (Phase 13).
