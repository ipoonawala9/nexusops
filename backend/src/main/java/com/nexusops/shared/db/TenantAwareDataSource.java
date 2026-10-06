package com.nexusops.shared.db;

import com.nexusops.shared.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Writes the current tenant into the session setting {@code app.tenant_id} every time a connection
 * is checked out, so PostgreSQL RLS policies see it (ADR-0002). With no tenant bound, the setting is
 * the empty string, which the policies treat as NULL: zero rows (fail closed). Because EVERY checkout
 * overwrites the value, a pooled connection never leaks a previous tenant.
 *
 * <p>The same round trip clears {@code app.platform_access} at session level, so even a (forbidden) session-level
 * platform flag can't outlive one checkout. Checkout happens before a transaction begins, so PlatformAccess's
 * transaction-local setting is unaffected (ADR-0007). This class only ever clears the flag.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    private static final String SET_TENANT =
            "select set_config('app.tenant_id', ?, false), set_config('app.platform_access', '', false)";

    public TenantAwareDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return bind(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return bind(super.getConnection(username, password));
    }

    private static Connection bind(Connection connection) throws SQLException {
        String tenant = TenantContext.tenantId().map(UUID::toString).orElse("");
        try (PreparedStatement statement = connection.prepareStatement(SET_TENANT)) {
            statement.setString(1, tenant);
            statement.execute();
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return connection;
    }
}
