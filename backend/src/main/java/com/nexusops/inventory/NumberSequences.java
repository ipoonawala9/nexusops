package com.nexusops.inventory;

import com.nexusops.shared.TenantContext;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Gap-free per tenant within committed orders: UPDATE … RETURNING takes the row lock until the transaction ends. */
@Component
class NumberSequences {

    private final JdbcTemplate jdbc;

    NumberSequences(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    String next(SequenceKind kind) {
        UUID tenant = TenantContext.requireTenantId();
        jdbc.update("insert into number_sequences (tenant_id, kind, next_value) values (?, ?, 1) on conflict do nothing",
                tenant, kind.name());
        Long value = jdbc.queryForObject("update number_sequences set next_value = next_value + 1 "
                + "where tenant_id = ? and kind = ? returning next_value - 1", Long.class, tenant, kind.name());
        return kind.prefix() + String.format("%05d", value);
    }
}
