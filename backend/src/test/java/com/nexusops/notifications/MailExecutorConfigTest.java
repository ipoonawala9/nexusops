package com.nexusops.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexusops.notifications.internal.MailExecutorConfig;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class MailExecutorConfigTest {

    @Test
    void asyncExecutorRunsOnBoundedMailThreads() throws Exception {
        Executor executor = new MailExecutorConfig().mailExecutor(true);
        assertThat(executor).isInstanceOf(ThreadPoolTaskExecutor.class);
        var pool = (ThreadPoolTaskExecutor) executor;
        assertThat(pool.getMaxPoolSize()).isEqualTo(4);
        assertThat(pool.getQueueCapacity()).isEqualTo(500);
        String thread = CompletableFuture.supplyAsync(() -> Thread.currentThread().getName(), executor)
                .get(5, TimeUnit.SECONDS);
        assertThat(thread).startsWith("mail-");
        pool.shutdown();
    }

    @Test
    void synchronousExecutorWhenAsyncIsDisabled() {
        assertThat(new MailExecutorConfig().mailExecutor(false)).isInstanceOf(SyncTaskExecutor.class);
    }

    @Test
    void dispatcherDeliversOnTheMailExecutor() throws Exception {
        var method = Class.forName("com.nexusops.notifications.internal.MailDispatcher")
                .getDeclaredMethod("on", MailRequested.class);
        var async = method.getAnnotation(org.springframework.scheduling.annotation.Async.class);
        assertThat(async).isNotNull();
        assertThat(async.value()).isEqualTo("mailExecutor");
    }
}
