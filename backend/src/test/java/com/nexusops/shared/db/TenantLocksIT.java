package com.nexusops.shared.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexusops.shared.TenantContext;
import com.nexusops.support.IntegrationTestSupport;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

class TenantLocksIT extends IntegrationTestSupport {

    @Autowired TenantLocks locks;
    @Autowired TransactionTemplate tx;

    @Test
    void requiresATransaction() {
        UUID tenant = UUID.randomUUID();
        assertThatThrownBy(() -> TenantContext.runAs(tenant, () -> locks.lock("owners")))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void serializesTransactionsOfTheSameTenantAndScope() throws Exception {
        UUID tenant = UUID.randomUUID();
        var firstHolds = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var secondAcquiredAt = new AtomicLong();
        var firstReleasedAt = new AtomicLong();
        var pool = Executors.newFixedThreadPool(2);
        pool.submit(() -> TenantContext.runAs(tenant, () -> tx.executeWithoutResult(s -> {
            locks.lock("owners");
            firstHolds.countDown();
            await(releaseFirst);
            firstReleasedAt.set(System.nanoTime());
        })));
        firstHolds.await(5, TimeUnit.SECONDS);
        var second = pool.submit(() -> TenantContext.runAs(tenant, () -> tx.executeWithoutResult(s -> {
            locks.lock("owners");
            secondAcquiredAt.set(System.nanoTime());
        })));
        Thread.sleep(200);
        assertThat(secondAcquiredAt.get()).as("second must wait").isZero();
        releaseFirst.countDown();
        second.get(5, TimeUnit.SECONDS);
        assertThat(secondAcquiredAt.get()).isGreaterThan(firstReleasedAt.get());
        pool.shutdown();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
