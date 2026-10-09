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
