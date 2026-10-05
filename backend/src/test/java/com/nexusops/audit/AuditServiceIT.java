package com.nexusops.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexusops.shared.TenantContext;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

class AuditServiceIT extends IntegrationTestSupport {

    @Autowired AuditService audit;
    @Autowired TransactionTemplate tx;

    UUID tenant;

    @BeforeEach
    void createTenant() {
        tenant = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        OwnerJdbc.jdbc().update("insert into tenants (id, slug, name, status, plan_code, created_at, updated_at) "
                + "values (?, ?, 'Audit', 'ACTIVE', 'FREE', ?, ?)", tenant, "aud-" + tenant.toString().substring(0, 8), now, now);
    }

    private Map<String, Object> onlyRow(String action) {
        return OwnerJdbc.superuser().queryForMap(
                "select * from audit_events where action = ? and (tenant_id = ? or tenant_id is null) order by occurred_at desc limit 1",
                action, tenant);
    }

    @Test
    void recordRequiresAnExistingTransaction() {
        assertThatThrownBy(() -> audit.record(AuditEntry.of("Orphan", "Thing", "1")))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void recordsTenantActorAndPayload() {
        UUID user = UUID.randomUUID();
        try (var scope = TenantContext.open(tenant, user)) {
            tx.executeWithoutResult(s -> audit.record(AuditEntry.of("ThingChanged", "Thing", 42)
                    .withBefore(Map.of("name", "old"))
                    .withAfter(Map.of("name", "new"))));
        }
        Map<String, Object> row = onlyRow("ThingChanged");
        assertThat(row.get("tenant_id")).isEqualTo(tenant);
        assertThat(row.get("actor_id")).isEqualTo(user);
        assertThat(row.get("actor_type")).isEqualTo("USER");
        assertThat(row.get("entity_id")).isEqualTo("42");
        assertThat(row.get("before").toString()).contains("old");
        assertThat(row.get("after").toString()).contains("new");
    }

    @Test
    void independentRecordSurvivesCallerRollback() {
        try (var scope = TenantContext.open(tenant, null)) {
            tx.executeWithoutResult(s -> {
                audit.recordIndependently(AuditEntry.of("LoginFailed", "User", "x"));
                s.setRollbackOnly();
            });
        }
        assertThat(onlyRow("LoginFailed").get("actor_type")).isEqualTo("ANONYMOUS");
    }

    @Test
    void preTenantEventsAreStoredWithNullTenant() {
        String action = "UnknownWorkspace" + tenant.toString().replace("-", "").replaceAll("[0-9]", "");
        audit.recordIndependently(AuditEntry.of(action, "Tenant", null));
        Long count = OwnerJdbc.superuser().queryForObject(
                "select count(*) from audit_events where action = ? and tenant_id is null", Long.class, action);
        assertThat(count).isOne();
    }

    @Test
    void sensitiveKeysAreNeverPersisted() {
        try (var scope = TenantContext.open(tenant, null)) {
            tx.executeWithoutResult(s -> audit.record(AuditEntry.of("Scrubbed", "User", "1")
                    .withAfter(Map.of("email", "a@b.test", "passwordHash", "$argon2id$x", "refreshToken", "t", "apiSecret", "s"))));
        }
        String after = onlyRow("Scrubbed").get("after").toString();
        assertThat(after).contains("a@b.test").doesNotContain("argon2id").doesNotContain("passwordHash")
                .doesNotContain("refreshToken").doesNotContain("apiSecret");
    }

    @Test
    void recordsAPlatformActorWithOrWithoutATenant() {
        UUID operator = UUID.randomUUID();
        tx.executeWithoutResult(s -> audit.record(
                AuditEntry.of("PlatformThingDone", "PlatformUser", operator).asPlatformActor(operator)));
        Map<String, Object> global = OwnerJdbc.superuser().queryForMap(
                "select * from audit_events where action = 'PlatformThingDone' order by occurred_at desc limit 1");
        assertThat(global.get("tenant_id")).isNull();
        assertThat(global.get("actor_type")).isEqualTo("PLATFORM");
        assertThat(global.get("actor_id")).isEqualTo(operator);

        UUID boundUser = UUID.randomUUID();
        try (var scope = TenantContext.open(tenant, boundUser)) {
            tx.executeWithoutResult(s -> audit.record(
                    AuditEntry.of("PlatformTenantThingDone", "Tenant", tenant).asPlatformActor(operator)));
        }
        Map<String, Object> scoped = onlyRow("PlatformTenantThingDone");
        assertThat(scoped.get("tenant_id")).isEqualTo(tenant);
        assertThat(scoped.get("actor_type")).isEqualTo("PLATFORM");
        assertThat(scoped.get("actor_id")).isEqualTo(operator); // the operator, not whoever TenantContext names
    }
}
