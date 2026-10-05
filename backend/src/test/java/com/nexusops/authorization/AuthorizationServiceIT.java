package com.nexusops.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AuthorizationServiceIT extends IntegrationTestSupport {

    @Autowired AuthorizationService authorization;

    UUID tenantA;
    UUID tenantB;

    private static UUID newTenant() {
        UUID id = Ids.newId();
        Timestamp now = Timestamp.from(Instant.now());
        OwnerJdbc.jdbc().update("insert into tenants (id, slug, name, status, plan_code, created_at, updated_at) "
                + "values (?, ?, 'Authz', 'ACTIVE', 'FREE', ?, ?)", id, "az-" + id.toString().substring(24), now, now);
        return id;
    }

    @BeforeEach
    void tenants() {
        tenantA = newTenant();
        tenantB = newTenant();
    }

    @Test
    void ownerHasEveryFoundationPermissionButModulePermissionsNeedTheModule() {
        UUID owner = TenantContext.callAs(tenantA, authorization::createSystemRoles);
        Set<String> withoutModules = TenantContext.callAs(tenantA,
                () -> authorization.effectivePermissions(List.of(owner), List.of()));
        assertThat(withoutModules)
                .contains("tenant.settings.read", "tenant.settings.update", "identity.user.invite",
                        "authorization.role.manage", "audit.event.read")
                .doesNotContain("crm.customer.read", "hr.employee.read");

        Set<String> withCrm = TenantContext.callAs(tenantA,
                () -> authorization.effectivePermissions(List.of(owner), List.of("CRM")));
        assertThat(withCrm).contains("crm.customer.read", "crm.customer.delete").doesNotContain("hr.employee.read");
    }

    @Test
    void systemRolesAreStoredAndFlagged() {
        TenantContext.runAs(tenantA, authorization::createSystemRoles);
        List<String> names = OwnerJdbc.ownerAs(tenantA).queryForList(
                "select name from roles where system order by name", String.class);
        assertThat(names).containsExactly(SystemRoles.ADMIN, SystemRoles.OWNER);
    }

    @Test
    void rolesOfAnotherTenantContributeNothing() {
        UUID ownerOfB = TenantContext.callAs(tenantB, authorization::createSystemRoles);
        Set<String> seenFromA = TenantContext.callAs(tenantA,
                () -> authorization.effectivePermissions(List.of(ownerOfB), List.of("CRM", "HRMS")));
        assertThat(seenFromA).isEmpty();
    }

    @Test
    void noRolesMeansNoPermissions() {
        assertThat(TenantContext.callAs(tenantA, () -> authorization.effectivePermissions(List.of(), List.of("CRM"))))
                .isEmpty();
    }
}
