package com.nexusops.shared.db;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DatabaseRoleGuardTest {

    @Test
    void acceptsUnprivilegedRole() {
        assertThatCode(() -> DatabaseRoleGuard.check(new DatabaseRoleGuard.RoleInfo("nexusops_app", false, false)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsSuperuser() {
        assertThatThrownBy(() -> DatabaseRoleGuard.check(new DatabaseRoleGuard.RoleInfo("postgres", true, true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("postgres")
                .hasMessageContaining("Row-Level Security");
    }

    @Test
    void rejectsBypassRlsRole() {
        assertThatThrownBy(() -> DatabaseRoleGuard.check(new DatabaseRoleGuard.RoleInfo("svc", false, true)))
                .isInstanceOf(IllegalStateException.class);
    }
}
