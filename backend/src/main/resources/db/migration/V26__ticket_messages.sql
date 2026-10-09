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
