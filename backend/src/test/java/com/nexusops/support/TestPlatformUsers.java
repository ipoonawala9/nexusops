package com.nexusops.support;

import com.nexusops.platform.application.PlatformUserAdmin;
import com.nexusops.platform.domain.PlatformRole;
import com.nexusops.platform.totp.Totp;
import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Creates platform operators through the real service; test-only access to the flag-gated platform tables. */
public final class TestPlatformUsers {

    public static final String PASSWORD = "platform horse battery staple";

    public record Operator(UUID id, String email, String password, byte[] secret) {
        public String codeAt(long step) {
            return Totp.code(secret, step);
        }

        public String currentCode() {
            return codeAt(Totp.step(Instant.now()));
        }
    }

    private TestPlatformUsers() {}

    public static Operator create(PlatformUserAdmin admin, PlatformRole role) {
        String email = "ops-" + UUID.randomUUID().toString().substring(0, 8) + "@nexusops.test";
        byte[] secret = Totp.newSecret();
        UUID id = admin.create(email, role, PASSWORD, secret);
        return new Operator(id, email, PASSWORD, secret);
    }

    /** Owner connections with app.platform_access on at session level: TEST-ONLY inspection/setup of platform tables. */
    public static JdbcTemplate platformJdbc() {
        var target = new DriverManagerDataSource(IntegrationTestSupport.POSTGRES.getJdbcUrl(), "nexusops_owner",
                IntegrationTestSupport.OWNER_PASSWORD);
        return new JdbcTemplate(new DelegatingDataSource(target) {
            @Override
            public Connection getConnection() throws SQLException {
                Connection connection = super.getConnection();
                try (var ps = connection.prepareStatement("select set_config('app.platform_access', 'on', false)")) {
                    ps.execute();
                } catch (SQLException | RuntimeException e) {
                    connection.close();
                    throw e;
                }
                return connection;
            }
        });
    }

    public static byte[] base32Decode(String encoded) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        var out = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : encoded.replace(" ", "").toCharArray()) {
            buffer = (buffer << 5) | alphabet.indexOf(c);
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
