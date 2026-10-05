package com.nexusops.support;

import com.nexusops.identity.domain.User;
import com.nexusops.identity.domain.UserRepository;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Test-only: adds a verified, active member with chosen roles (before invitations exist in this plan). */
@Component
public class TestMembers {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final TransactionTemplate tx;

    TestMembers(UserRepository users, PasswordEncoder encoder, TransactionTemplate tx) {
        this.users = users;
        this.encoder = encoder;
        this.tx = tx;
    }

    public TestTenants.Workspace create(UUID tenantId, Set<UUID> roleIds) {
        String slug = OwnerJdbc.jdbc().queryForObject("select slug from tenants where id = ?", String.class, tenantId);
        UUID userId = Ids.newId();
        String email = "member-" + userId.toString().substring(24) + "@" + slug + ".test";
        String hash = encoder.encode(TestTenants.PASSWORD);
        TenantContext.runAs(tenantId, () -> tx.executeWithoutResult(s -> {
            User user = User.registerOwner(userId, email, hash, "Mem", "Ber", null);
            user.markEmailVerified(Instant.now());
            users.save(user);
        }));
        for (UUID roleId : roleIds) {
            OwnerJdbc.ownerAs(tenantId).update("insert into user_roles (user_id, role_id) values (?, ?)", userId, roleId);
        }
        return new TestTenants.Workspace(tenantId, slug, email, TestTenants.PASSWORD);
    }
}
