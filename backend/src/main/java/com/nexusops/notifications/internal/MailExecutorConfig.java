package com.nexusops.notifications.internal;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Mail is delivered off the request thread: SMTP latency never holds a DB connection or reveals, by
 * timing, whether a public endpoint (e.g. resend-verification) actually sent mail.
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
public class MailExecutorConfig {

    @Bean(name = "mailExecutor")
    public Executor mailExecutor(@Value("${nexusops.mail.async:true}") boolean async) {
        if (!async) {
            return new SyncTaskExecutor();
        }
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("mail-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
