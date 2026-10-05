package com.nexusops.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class TenantContextTest {

    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();

    @AfterEach
    void reset() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void emptyByDefault() {
        assertThat(TenantContext.tenantId()).isEmpty();
        assertThatThrownBy(TenantContext::requireTenantId).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void openBindsAndCloseRestoresPreviousIncludingMdc() {
        UUID user = UUID.randomUUID();
        try (var outer = TenantContext.open(a, user)) {
            assertThat(TenantContext.requireTenantId()).isEqualTo(a);
            assertThat(TenantContext.userId()).contains(user);
            assertThat(MDC.get("tenant_id")).isEqualTo(a.toString());
            try (var inner = TenantContext.open(b, null)) {
                assertThat(TenantContext.requireTenantId()).isEqualTo(b);
                assertThat(TenantContext.userId()).isEmpty();
            }
            assertThat(TenantContext.requireTenantId()).isEqualTo(a);
            assertThat(MDC.get("user_id")).isEqualTo(user.toString());
        }
        assertThat(TenantContext.tenantId()).isEmpty();
        assertThat(MDC.get("tenant_id")).isNull();
    }

    @Test
    void callAsReturnsValueAndUnbinds() {
        assertThat(TenantContext.callAs(a, TenantContext::requireTenantId)).isEqualTo(a);
        assertThat(TenantContext.tenantId()).isEmpty();
    }

    @Test
    void refusesToSwitchTenantInsideActiveTransaction() {
        try (var scope = TenantContext.open(a, null)) {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            assertThatThrownBy(() -> TenantContext.open(b, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("transaction");
            assertThat(TenantContext.requireTenantId()).isEqualTo(a);
        }
    }

    @Test
    void refusesToBindFirstTenantInsideActiveTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> TenantContext.open(a, null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void allowsReopeningSameTenantInsideTransaction() {
        try (var scope = TenantContext.open(a, null)) {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try (var same = TenantContext.open(a, UUID.randomUUID())) {
                assertThat(TenantContext.requireTenantId()).isEqualTo(a);
            }
        }
    }
}
