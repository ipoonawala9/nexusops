package com.nexusops;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NexusOpsApplicationTest {

    @Test
    void recognisesACliInvocation() {
        assertThat(NexusOpsApplication.isCliCommand(new String[] {"--nexusops.cli.command=create-platform-admin"})).isTrue();
        assertThat(NexusOpsApplication.isCliCommand(new String[] {"--server.port=8081"})).isFalse();
        assertThat(NexusOpsApplication.isCliCommand(new String[0])).isFalse();
    }
}
