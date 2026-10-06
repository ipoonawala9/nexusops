package com.nexusops.directory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestRoles;
import com.nexusops.support.TestTenants;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.test.web.servlet.MockMvc;

/** V9's grant_to_system_roles: how migrations hand new permissions to existing workspaces' system roles. */
@AutoConfigureMockMvc
class PermissionGrantIT extends IntegrationTestSupport {

    static final List<String> DIRECTORY = List.of("directory.party.read", "directory.party.manage",
            "directory.employee.read", "directory.employee.manage");

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;

    private List<String> permissionsOf(UUID tenant, UUID role) {
        return OwnerJdbc.ownerAs(tenant).queryForList(
                "select permission_code from role_permissions where role_id = ?", String.class, role);
    }

    @Test
    void newWorkspacesSystemRolesHoldTheDirectoryPermissions() throws Exception {
        UUID tenant = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("grant")).tenantId();
        assertThat(permissionsOf(tenant, TestRoles.system(tenant, "TENANT_OWNER"))).containsAll(DIRECTORY);
        assertThat(permissionsOf(tenant, TestRoles.system(tenant, "TENANT_ADMIN"))).containsAll(DIRECTORY);
    }

    @Test
    void theGrantFunctionRestoresCodesToEverySystemRoleOfEveryTenantOnly() throws Exception {
        UUID one = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("grant")).tenantId();
        UUID two = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("grant")).tenantId();
        UUID custom = UUID.randomUUID();
        OwnerJdbc.ownerAs(one).update("insert into roles (id, tenant_id, name, system, created_at, updated_at) "
                + "values (?, ?, 'Custom', false, now(), now())", custom, one);
        for (UUID tenant : List.of(one, two)) {
            OwnerJdbc.ownerAs(tenant).update(
                    "delete from role_permissions where permission_code = 'directory.party.read'");
        }

        OwnerJdbc.jdbc().queryForList("select grant_to_system_roles(array['directory.party.read'])");

        for (UUID tenant : List.of(one, two)) {
            assertThat(permissionsOf(tenant, TestRoles.system(tenant, "TENANT_OWNER"))).contains("directory.party.read");
            assertThat(permissionsOf(tenant, TestRoles.system(tenant, "TENANT_ADMIN"))).contains("directory.party.read");
        }
        assertThat(permissionsOf(one, custom)).doesNotContain("directory.party.read");
    }

    @Test
    void theRuntimeRoleCannotCallTheGrantFunction() {
        assertThatThrownBy(() -> OwnerJdbc.rawApp().queryForList(
                "select grant_to_system_roles(array['directory.party.read'])"))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("permission denied");
    }
}
