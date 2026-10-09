package com.nexusops.shared;

import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-tenant document numbers (PO-00001, SO-00001, T-00001). Gap-free among committed records: UPDATE … RETURNING
 * holds the row lock until the transaction ends, and the increment rolls back with it.
 */
@Component
public class NumberSequences {

    private final JdbcTemplate jdbc;

    NumberSequences(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String next(String kind, String prefix) {
        UUID tenant = TenantContext.requireTenantId();
        jdbc.update("insert into number_sequences (tenant_id, kind, next_value) values (?, ?, 1) on conflict do nothing",
                tenant, kind);
        Long value = jdbc.queryForObject("update number_sequences set next_value = next_value + 1 "
                + "where tenant_id = ? and kind = ? returning next_value - 1", Long.class, tenant, kind);
        return prefix + String.format(Locale.ROOT, "%05d", value);
    }
}
