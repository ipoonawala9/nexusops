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
