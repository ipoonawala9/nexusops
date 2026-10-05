package com.nexusops.shared.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexusops.support.IntegrationTestSupport;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

class AfterCommitIT extends IntegrationTestSupport {

    @Autowired TransactionTemplate tx;

    @Test
    void runsOnlyAfterCommit() {
        var runs = new AtomicInteger();
        tx.executeWithoutResult(s -> {
            AfterCommit.run(runs::incrementAndGet);
            assertThat(runs).hasValue(0);
        });
        assertThat(runs).hasValue(1);
    }

    @Test
    void neverRunsOnRollback() {
        var runs = new AtomicInteger();
        tx.executeWithoutResult(s -> {
            AfterCommit.run(runs::incrementAndGet);
            s.setRollbackOnly();
        });
        assertThat(runs).hasValue(0);
    }

    @Test
    void runsImmediatelyWithoutATransaction() {
        var runs = new AtomicInteger();
        AfterCommit.run(runs::incrementAndGet);
        assertThat(runs).hasValue(1);
    }
}
