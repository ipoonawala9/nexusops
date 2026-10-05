package com.nexusops.shared.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexusops.shared.TenantContext;
import com.nexusops.support.IntegrationTestSupport;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class TenantAwareDataSourceIT extends IntegrationTestSupport {

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;

    private String currentSetting() {
        return jdbc.queryForObject("select current_setting('app.tenant_id', true)", String.class);
    }

    @Test
    void applicationDataSourceIsTenantAware() {
        assertThat(dataSource).isInstanceOf(TenantAwareDataSource.class);
    }

    @Test
    void connectionCarriesTheBoundTenant() {
        UUID tenant = UUID.randomUUID();
        assertThat(TenantContext.callAs(tenant, this::currentSetting)).isEqualTo(tenant.toString());
    }

    @Test
    void connectionWithoutTenantIsResetEvenAfterPooledReuse() {
        UUID tenant = UUID.randomUUID();
        TenantContext.runAs(tenant, this::currentSetting);
        // The pool hands back a previously used connection; it must not still carry the old tenant.
        for (int i = 0; i < 20; i++) {
            assertThat(currentSetting()).isEmpty();
        }
    }
}
