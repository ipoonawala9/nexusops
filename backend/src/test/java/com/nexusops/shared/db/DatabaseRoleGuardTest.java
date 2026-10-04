package com.nexusops.shared.db;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DatabaseRoleGuardTest {

    private static DatabaseRoleGuard.RoleInfo role(
            String name, boolean superuser, boolean bypassRls, boolean memberOfPrivileged,
            boolean schemaOwner, boolean canCreate) {
        return new DatabaseRoleGuard.RoleInfo(name, superuser, bypassRls, memberOfPrivileged, schemaOwner, canCreate);
    }

    @Test
    void acceptsUnprivilegedRole() {
        assertThatCode(() -> DatabaseRoleGuard.check(role("nexusops_app", false, false, false, false, false)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsSuperuser() {
        assertThatThrownBy(() -> DatabaseRoleGuard.check(role("postgres", true, true, false, true, true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("postgres")
                .hasMessageContaining("Row-Level Security");
    }

    @Test
    void rejectsBypassRlsRole() {
        assertThatThrownBy(() -> DatabaseRoleGuard.check(role("svc", false, true, false, false, false)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsSchemaOwnerBecauseOwnersCanDisableRls() {
        assertThatThrownBy(() -> DatabaseRoleGuard.check(role("nexusops_owner", false, false, false, true, true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nexusops_owner");
    }

    @Test
    void rejectsRoleThatCanCreateObjects() {
        assertThatThrownBy(() -> DatabaseRoleGuard.check(role("svc", false, false, false, false, true)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsMemberOfPrivilegedRole() {
        assertThatThrownBy(() -> DatabaseRoleGuard.check(role("svc", false, false, true, false, false)))
                .isInstanceOf(IllegalStateException.class);
    }
}
