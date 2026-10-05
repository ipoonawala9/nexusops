package com.nexusops.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexusops.identity.domain.RefreshToken;
import com.nexusops.identity.domain.RefreshTokenRepository;
import com.nexusops.identity.domain.RevokeReason;
import com.nexusops.identity.domain.User;
import com.nexusops.identity.domain.UserRepository;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** Application-layer isolation (Hibernate @TenantId) on real entities, on top of RLS. */
class UserTenantScopingIT extends IntegrationTestSupport {

    @Autowired UserRepository users;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired TransactionTemplate tx;

    UUID tenantA;
    UUID tenantB;
    UUID userA;

    private static UUID newTenant() {
        UUID id = Ids.newId();
        Timestamp now = Timestamp.from(Instant.now());
        OwnerJdbc.jdbc().update("insert into tenants (id, slug, name, status, plan_code, created_at, updated_at) "
                + "values (?, ?, 'Scope', 'ACTIVE', 'FREE', ?, ?)", id, "sc-" + id.toString().substring(24), now, now);
        return id;
    }

    @BeforeEach
    void seed() {
        tenantA = newTenant();
        tenantB = newTenant();
        userA = Ids.newId();
        TenantContext.runAs(tenantA, () -> tx.executeWithoutResult(s -> users.save(
                User.registerOwner(userA, "owner@a.test", "hash", "Ada", "Owner", null))));
    }

    @Test
    void tenantIdIsStampedFromContext() {
        User loaded = TenantContext.callAs(tenantA, () -> users.findById(userA)).orElseThrow();
        assertThat(loaded.getTenantId()).isEqualTo(tenantA);
    }

    @Test
    void otherTenantCannotFindOrListTheUser() {
        TenantContext.runAs(tenantB, () -> {
            assertThat(users.findById(userA)).isEmpty();
            assertThat(users.findByEmail("owner@a.test")).isEmpty();
            assertThat(users.findAll()).noneMatch(u -> u.getId().equals(userA));
        });
    }

    @Test
    void sameEmailMayExistInDifferentTenants() {
        UUID userB = Ids.newId();
        TenantContext.runAs(tenantB, () -> tx.executeWithoutResult(s -> users.save(
                User.registerOwner(userB, "owner@a.test", "hash", "Bob", "Owner", null))));
        assertThat(TenantContext.callAs(tenantB, () -> users.findByEmail("owner@a.test")))
                .map(User::getId).contains(userB);
    }

    @Test
    void savingWithoutTenantContextFails() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> users.save(
                User.registerOwner(Ids.newId(), "x@x.test", "hash", "X", "X", null))))
                .hasStackTraceContaining("row-level security");
    }

    @Test
    void familyRevocationOnlyTouchesTheCurrentTenant() {
        UUID family = Ids.newId();
        Instant now = Instant.now();
        TenantContext.runAs(tenantA, () -> tx.executeWithoutResult(s -> refreshTokens.save(RefreshToken.issue(
                Ids.newId(), userA, family, "a".repeat(64), now.plus(Duration.ofDays(1)), 0, null, null))));
        int fromB = TenantContext.callAs(tenantB,
                () -> tx.execute(s -> refreshTokens.revokeFamily(family, RevokeReason.LOGOUT, now)));
        assertThat(fromB).isZero();
        int fromA = TenantContext.callAs(tenantA,
                () -> tx.execute(s -> refreshTokens.revokeFamily(family, RevokeReason.LOGOUT, now)));
        assertThat(fromA).isOne();
    }
}
