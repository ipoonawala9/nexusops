-- Leads (D2, D3): raw contact details of a prospect; conversion links them to canonical parties (D4).
CREATE TABLE leads (
    id                         uuid PRIMARY KEY,
    tenant_id                  uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    first_name                 text CHECK (first_name IS NULL OR length(first_name) BETWEEN 1 AND 80),
    last_name                  text CHECK (last_name IS NULL OR length(last_name) BETWEEN 1 AND 80),
    company_name               text CHECK (company_name IS NULL OR length(company_name) BETWEEN 1 AND 200),
    job_title                  text CHECK (job_title IS NULL OR length(job_title) <= 100),
    email                      text CHECK (email IS NULL OR length(email) <= 254),
    phone                      text CHECK (phone IS NULL OR length(phone) <= 40),
    source                     text NOT NULL CHECK (source IN ('WEBSITE', 'REFERRAL', 'WALK_IN', 'PHONE', 'EMAIL',
                                                               'SOCIAL', 'EVENT', 'OTHER')),
    status                     text NOT NULL CHECK (status IN ('NEW', 'CONTACTED', 'QUALIFIED', 'DISQUALIFIED',
                                                               'CONVERTED')),
    owner_id                   uuid REFERENCES users (id) ON DELETE SET NULL,
    estimated_value            numeric(19, 4) CHECK (estimated_value IS NULL OR estimated_value >= 0),
    currency                   text CHECK (currency IS NULL OR currency ~ '^[A-Z]{3}$'),
    description                text CHECK (description IS NULL OR length(description) <= 5000),
    disqualify_reason          text CHECK (disqualify_reason IS NULL OR length(disqualify_reason) BETWEEN 1 AND 500),
    disqualified_at            timestamptz,
    converted_at               timestamptz,
    converted_person_id        uuid,
    converted_organization_id  uuid,
    converted_opportunity_id   uuid,
    created_by                 uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at                 timestamptz NOT NULL,
    updated_at                 timestamptz NOT NULL,
    version                    bigint NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    CHECK (first_name IS NOT NULL OR last_name IS NOT NULL OR company_name IS NOT NULL),
    CHECK ((estimated_value IS NULL) = (currency IS NULL)),
    CHECK ((status = 'DISQUALIFIED') = (disqualify_reason IS NOT NULL)),
    CHECK ((status = 'DISQUALIFIED') = (disqualified_at IS NOT NULL)),
    CHECK ((status = 'CONVERTED') = (converted_at IS NOT NULL)),
    CHECK (status <> 'CONVERTED' OR converted_person_id IS NOT NULL OR converted_organization_id IS NOT NULL),
    FOREIGN KEY (tenant_id, converted_person_id) REFERENCES parties (tenant_id, id),
    FOREIGN KEY (tenant_id, converted_organization_id) REFERENCES parties (tenant_id, id)
);
CREATE INDEX leads_status_idx ON leads (tenant_id, status, created_at);
CREATE INDEX leads_owner_idx ON leads (tenant_id, owner_id);
CREATE INDEX leads_converted_person_idx ON leads (tenant_id, converted_person_id);
CREATE INDEX leads_converted_organization_idx ON leads (tenant_id, converted_organization_id);

ALTER TABLE leads ENABLE ROW LEVEL SECURITY;
ALTER TABLE leads FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON leads
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
