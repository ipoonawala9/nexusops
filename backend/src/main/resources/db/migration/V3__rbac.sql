CREATE TABLE permissions (
    code         text PRIMARY KEY CHECK (code ~ '^[a-z]+(\.[a-z]+){2}$'),
    module_code  text REFERENCES modules (code),
    description  text NOT NULL
);
INSERT INTO permissions (code, module_code, description) VALUES
    ('tenant.settings.read',     NULL, 'View workspace settings'),
    ('tenant.settings.update',   NULL, 'Change workspace settings'),
    ('tenant.modules.manage',    NULL, 'Enable or disable modules'),
    ('identity.user.read',       NULL, 'View users'),
    ('identity.user.invite',     NULL, 'Invite users'),
    ('identity.user.update',     NULL, 'Update users'),
    ('identity.user.disable',    NULL, 'Disable users'),
    ('authorization.role.read',  NULL, 'View roles'),
    ('authorization.role.manage',NULL, 'Create and edit roles'),
    ('authorization.role.assign',NULL, 'Assign roles to users'),
    ('audit.event.read',         NULL, 'View the audit log'),
    ('crm.customer.read',        'CRM', 'View customers'),
    ('crm.customer.create',      'CRM', 'Create customers'),
    ('crm.customer.update',      'CRM', 'Update customers'),
    ('crm.customer.delete',      'CRM', 'Delete customers'),
    ('inventory.product.read',   'INVENTORY', 'View products'),
    ('inventory.stock.adjust',   'INVENTORY', 'Adjust stock'),
    ('helpdesk.ticket.read',     'HELPDESK', 'View tickets'),
    ('helpdesk.ticket.assign',   'HELPDESK', 'Assign tickets'),
    ('helpdesk.ticket.resolve',  'HELPDESK', 'Resolve tickets'),
    ('hr.employee.read',         'HRMS', 'View employees'),
    ('hr.employee.update',       'HRMS', 'Update employees');
REVOKE INSERT, UPDATE, DELETE ON permissions FROM nexusops_app;

CREATE TABLE roles (
    id          uuid PRIMARY KEY,
    tenant_id   uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    name        text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 60),
    description text,
    system      boolean NOT NULL DEFAULT false,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    version     bigint NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX roles_tenant_name_uq ON roles (tenant_id, lower(name));
ALTER TABLE roles ENABLE ROW LEVEL SECURITY;
ALTER TABLE roles FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON roles
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Join tables carry no tenant_id: a row is visible/insertable only if every referenced row is
-- visible under the current tenant (RLS on roles/users applies inside the policy subqueries).
CREATE TABLE role_permissions (
    role_id          uuid NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    permission_code  text NOT NULL REFERENCES permissions (code),
    PRIMARY KEY (role_id, permission_code)
);
ALTER TABLE role_permissions ENABLE ROW LEVEL SECURITY;
ALTER TABLE role_permissions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON role_permissions
    USING (EXISTS (SELECT 1 FROM roles r WHERE r.id = role_id))
    WITH CHECK (EXISTS (SELECT 1 FROM roles r WHERE r.id = role_id));

CREATE TABLE user_roles (
    user_id  uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role_id  uuid NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);
CREATE INDEX user_roles_role_idx ON user_roles (role_id);
ALTER TABLE user_roles ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_roles FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON user_roles
    USING (EXISTS (SELECT 1 FROM users u WHERE u.id = user_id) AND EXISTS (SELECT 1 FROM roles r WHERE r.id = role_id))
    WITH CHECK (EXISTS (SELECT 1 FROM users u WHERE u.id = user_id) AND EXISTS (SELECT 1 FROM roles r WHERE r.id = role_id));
