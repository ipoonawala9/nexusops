package com.nexusops.shared;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexusops.support.IntegrationTestSupport;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** Guards against the mailExecutor bean making Boot back off its app-wide applicationTaskExecutor. */
class TaskExecutorIT extends IntegrationTestSupport {

    @Autowired ApplicationContext context;

    @Test
    void applicationTaskExecutorSurvivesAlongsideMailExecutor() {
        assertThat(context.containsBean("applicationTaskExecutor")).isTrue();
        assertThat(context.getBean("applicationTaskExecutor", Executor.class))
                .isNotSameAs(context.getBean("mailExecutor", Executor.class));
    }
}
