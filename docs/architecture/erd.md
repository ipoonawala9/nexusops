# ERD — Phases 1–4

```mermaid
erDiagram
  plans ||--o{ tenants : "plan_code"
  tenants ||--o{ tenant_modules : has
  modules ||--o{ tenant_modules : enabled
  tenants ||--o{ users : owns
  tenants ||--o{ roles : owns
  modules ||--o{ permissions : "module_code (nullable)"
  roles ||--o{ role_permissions : grants
  permissions ||--o{ role_permissions : in
  users ||--o{ user_roles : has
  platform_users ||--o{ platform_refresh_tokens : has
  roles ||--o{ user_roles : assigned
  users ||--o{ refresh_tokens : holds
  users ||--o{ email_verifications : verifies
  roles ||--o{ invitations : "invited as"
  tenants ||--o{ audit_events : records

  tenants { uuid id PK
    text slug UK
    text name
    text subdomain
    text status
    text plan_code FK
    text timezone
    text locale
    char currency
    timestamptz created_at
    timestamptz updated_at
    bigint version }
  users { uuid id PK
    uuid tenant_id FK
    text email
    text password_hash
    text first_name
    text last_name
    text status
    timestamptz email_verified_at
    int token_version
    timestamptz last_login_at }
  roles { uuid id PK
    uuid tenant_id FK
    text name
    text description
    bool system }
  permissions { text code PK
    text module_code FK
    text description }
  refresh_tokens { uuid id PK
    uuid tenant_id FK
    uuid user_id FK
    uuid family_id
    text token_hash UK
    timestamptz expires_at
    timestamptz revoked_at
    uuid replaced_by }
  invitations { uuid id PK
    uuid tenant_id FK
    text email
    uuid role_id FK
    text token_hash UK
    timestamptz expires_at
    timestamptz accepted_at }
  audit_events { uuid id PK
    uuid tenant_id
    text actor_type
    uuid actor_id
    text action
    text entity_type
    text entity_id
    timestamptz occurred_at
    text request_id
    text correlation_id
    jsonb before
    jsonb after }
  platform_users { uuid id PK
    text email UK
    text password_hash
    text totp_secret_enc
    bigint totp_last_step
    text role
    text status
    int token_version
    int failed_totp_attempts
    timestamptz last_login_at
    timestamptz created_at
    timestamptz updated_at
    bigint version }
  platform_refresh_tokens { uuid id PK
    uuid platform_user_id FK
    uuid family_id
    char token_hash UK
    timestamptz expires_at
    timestamptz revoked_at
    text revoke_reason
    uuid replaced_by
    int token_version
    text created_ip
    text user_agent
    timestamptz created_at }
```

Tables with RLS: every table that has `tenant_id`, except `tenants` itself, which is global and accessed only through services.

`platform_users` and `platform_refresh_tokens` are not tenant tables. They have FORCE RLS and are visible only while the
platform module has set `app.platform_access = 'on'` for the transaction (ADR-0007). The same flag also enables
`FOR SELECT` `platform_read` policies on `users`, `roles` and `user_roles`.

## Phase 4 — canonical data model

```mermaid
erDiagram
    TENANTS ||--o{ PARTIES : owns
    PARTIES ||--o{ PARTIES : "employs (organization_id)"
    PARTIES ||--o{ PARTY_ROLES : plays
    TENANTS ||--o{ PRODUCTS : owns
    TENANTS ||--o{ ACTIVITIES : owns
    TENANTS ||--o{ TASKS : owns
    TENANTS ||--o{ DOCUMENTS : owns
    DOCUMENTS ||--|| DOCUMENT_CONTENTS : stores
    USERS ||--o{ TASKS : "assigned (assignee_id)"
    USERS ||--o{ ACTIVITIES : authored
    PARTIES {
        uuid id PK
        uuid tenant_id FK
        text kind "PERSON | ORGANIZATION"
        text name
        text name_key "duplicate match key"
        uuid organization_id "FK (tenant_id, organization_id)"
        text email
        text domain
        text duplicate_reason
        timestamptz archived_at
    }
    PARTY_ROLES {
        uuid id PK
        uuid party_id "FK (tenant_id, party_id)"
        text role "CUSTOMER | SUPPLIER | EMPLOYEE"
        text status "ACTIVE | INACTIVE"
        text employee_number
    }
    PRODUCTS {
        uuid id PK
        text sku "unique per tenant, ci"
        text kind "GOODS | SERVICE"
        numeric list_price
        text currency
        timestamptz archived_at
    }
    ACTIVITIES {
        uuid id PK
        text subject_type "PARTY | PRODUCT | ..."
        uuid subject_id
        text type "NOTE | CALL | EMAIL | MEETING"
        text summary
    }
    TASKS {
        uuid id PK
        text title
        text status
        text priority
        date due_on
        uuid assignee_id FK
        text subject_type
        uuid subject_id
    }
    DOCUMENTS {
        uuid id PK
        text subject_type
        uuid subject_id
        text file_name
        bigint size_bytes
        text sha256
    }
```
Activities, tasks and documents reference their subject without a foreign key (ADR-0008). Every table carries
`tenant_id` with FORCE RLS.
