-- Opportunities (D6). Status is the stage's kind; closed_at is set while the stage is WON or LOST.
CREATE TABLE opportunities (
    id                 uuid PRIMARY KEY,
    tenant_id          uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    name               text NOT NULL CHECK (name = btrim(name) AND length(name) BETWEEN 1 AND 200),
    account_id         uuid NOT NULL,
    contact_id         uuid,
    stage_id           uuid NOT NULL,
    amount             numeric(19, 4) CHECK (amount IS NULL OR amount >= 0),
    currency           text CHECK (currency IS NULL OR currency ~ '^[A-Z]{3}$'),
    expected_close_on  date,
    owner_id           uuid REFERENCES users (id) ON DELETE SET NULL,
    lead_id            uuid,
    description        text CHECK (description IS NULL OR length(description) <= 5000),
    lost_reason        text CHECK (lost_reason IS NULL OR length(lost_reason) BETWEEN 1 AND 500),
    closed_at          timestamptz,
    created_by         uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL,
    version            bigint NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    CHECK ((amount IS NULL) = (currency IS NULL)),
    CHECK (lost_reason IS NULL OR closed_at IS NOT NULL),
    FOREIGN KEY (tenant_id, account_id) REFERENCES parties (tenant_id, id),
    FOREIGN KEY (tenant_id, contact_id) REFERENCES parties (tenant_id, id),
    FOREIGN KEY (tenant_id, stage_id) REFERENCES pipeline_stages (tenant_id, id),
    FOREIGN KEY (tenant_id, lead_id) REFERENCES leads (tenant_id, id)
);
CREATE INDEX opportunities_stage_idx ON opportunities (tenant_id, stage_id);
CREATE INDEX opportunities_account_idx ON opportunities (tenant_id, account_id);
CREATE INDEX opportunities_contact_idx ON opportunities (tenant_id, contact_id);
CREATE INDEX opportunities_close_idx ON opportunities (tenant_id, expected_close_on);
CREATE INDEX opportunities_owner_idx ON opportunities (tenant_id, owner_id);

ALTER TABLE opportunities ENABLE ROW LEVEL SECURITY;
ALTER TABLE opportunities FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON opportunities
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE leads ADD FOREIGN KEY (tenant_id, converted_opportunity_id) REFERENCES opportunities (tenant_id, id);
