package com.nexusops.shared.db;

import com.nexusops.shared.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-tenant, per-scope transaction-level advisory locks: serialize check-then-act invariants that
 * span rows (plan limits, "at least one owner") without locking whole tables.
 */
@Component
public class TenantLocks {

    private final JdbcTemplate jdbc;

    TenantLocks(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(String scope) {
        String tenant = TenantContext.requireTenantId().toString();
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtext(? || ':' || ?))::text", String.class, scope, tenant);
    }
}
