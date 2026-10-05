package com.nexusops.platform.internal;

import com.nexusops.shared.TenantContext;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The ONLY code that turns on {@code app.platform_access} (spec §4.2, ADR-0007). The setting is
 * transaction-local ({@code set_config(..., true)}), so it ends with the transaction and never reaches the
 * next user of a pooled connection. Platform visibility never mixes with tenant work: running inside a
 * tenant scope or inside an already-open transaction is refused.
 */
@Component
public class PlatformAccess {

    private static final String ENABLE = "select set_config('app.platform_access', 'on', true)";

    private final TransactionTemplate readWrite;
    private final TransactionTemplate readOnly;
    private final JdbcTemplate jdbc;

    PlatformAccess(PlatformTransactionManager transactions, JdbcTemplate jdbc) {
        this.readWrite = new TransactionTemplate(transactions);
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
        this.jdbc = jdbc;
    }

    public <T> T read(Supplier<T> work) {
        return run(readOnly, work);
    }

    public <T> T write(Supplier<T> work) {
        return run(readWrite, work);
    }

    public void writeWithoutResult(Runnable work) {
        write(() -> {
            work.run();
            return null;
        });
    }

    private <T> T run(TransactionTemplate template, Supplier<T> work) {
        if (TenantContext.tenantId().isPresent()) {
            throw new IllegalStateException("Platform access is never combined with a tenant scope");
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Platform access must start its own transaction");
        }
        return template.execute(status -> {
            jdbc.queryForObject(ENABLE, String.class);
            return work.get();
        });
    }
}
