package com.nexusops.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.platform.application.PlatformUserAdmin;
import com.nexusops.platform.cli.PlatformCli;
import com.nexusops.platform.cli.Terminal;
import com.nexusops.platform.domain.PlatformRole;
import com.nexusops.platform.totp.Totp;
import com.nexusops.platform.totp.TotpSecretCipher;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.TestPlatformUsers;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@AutoConfigureMockMvc
class PlatformCliIT extends IntegrationTestSupport {

    static final Instant NOW = Instant.parse("2026-10-06T10:00:00Z");
    static final String STRONG = "a long platform passphrase 42";
    static final Pattern SECRET_LINE = Pattern.compile("Or enter this secret manually: ([A-Z2-7 ]+)");

    @Autowired PlatformUserAdmin admin;
    @Autowired TotpSecretCipher cipher;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired MockMvc mvc;

    String email;

    @BeforeEach
    void freshEmail() {
        email = "ops-" + UUID.randomUUID().toString().substring(0, 8) + "@nexusops.test";
    }

    /** Answers secrets from a queue; answers code prompts with the code for the secret it printed (or a fixed wrong one). */
    static final class ScriptedTerminal implements Terminal {
        final Deque<String> secrets = new ArrayDeque<>();
        final List<String> output = new ArrayList<>();
        boolean answerCorrectly = true;
        Instant now = NOW;

        ScriptedTerminal secrets(String... values) {
            secrets.addAll(List.of(values));
            return this;
        }

        @Override
        public String readLine(String prompt) {
            output.add(prompt);
            if (!answerCorrectly) {
                return "000000".equals(correctCode()) ? "111111" : "000000";
            }
            return correctCode();
        }

        @Override
        public char[] readSecret(String prompt) {
            output.add(prompt);
            return secrets.isEmpty() ? null : secrets.removeFirst().toCharArray();
        }

        @Override
        public void println(String line) {
            output.add(line);
        }

        byte[] printedSecret() {
            for (int i = output.size() - 1; i >= 0; i--) {
                var m = SECRET_LINE.matcher(output.get(i));
                if (m.find()) {
                    return TestPlatformUsers.base32Decode(m.group(1));
                }
            }
            throw new AssertionError("no secret printed");
        }

        String correctCode() {
            return Totp.code(printedSecret(), Totp.step(now));
        }

        String all() {
            return String.join("\n", output);
        }
    }

    private int run(ScriptedTerminal terminal, String command, String role) {
        return new PlatformCli(admin, terminal, command, email, role, Clock.fixed(terminal.now, ZoneOffset.UTC)).execute();
    }

    private ResultActions login(String password, String code) throws Exception {
        return mvc.perform(post("/api/v1/platform/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"%s","password":"%s","code":"%s"}""".formatted(email, password, code)));
    }

    private Map<String, Object> row() {
        return TestPlatformUsers.platformJdbc().queryForMap("select * from platform_users where email = ?", email);
    }

    private long count() {
        return TestPlatformUsers.platformJdbc().queryForObject(
                "select count(*) from platform_users where email = ?", Long.class, email);
    }

    @Test
    void createsAnAdminOnlyAfterTheEnrolmentCodeIsConfirmed() {
        var terminal = new ScriptedTerminal().secrets(STRONG, STRONG);
        assertThat(run(terminal, "create-platform-admin", "PLATFORM_ADMIN")).isZero();

        Map<String, Object> row = row();
        UUID id = (UUID) row.get("id");
        assertThat(row.get("role")).isEqualTo("PLATFORM_ADMIN");
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        assertThat(cipher.decrypt((String) row.get("totp_secret_enc"), id)).isEqualTo(terminal.printedSecret());
        assertThat(passwordEncoder.matches(STRONG, (String) row.get("password_hash"))).isTrue();
        assertThat(row.get("totp_last_step")).isEqualTo(Totp.step(NOW)); // the confirmation code is consumed
        assertThat(terminal.all()).contains("otpauth://totp/NexusOps:").doesNotContain(STRONG);
        assertThat(OwnerJdbc.superuser().queryForObject("""
                select count(*) from audit_events where action = 'PlatformUserCreated' and entity_id = ?
                  and tenant_id is null and actor_type = 'SYSTEM'""", Long.class, id.toString())).isOne();
    }

    @Test
    void threeWrongCodesSaveNothing() {
        var terminal = new ScriptedTerminal().secrets(STRONG, STRONG);
        terminal.answerCorrectly = false;
        assertThat(run(terminal, "create-platform-admin", "PLATFORM_SUPPORT")).isEqualTo(1);
        assertThat(count()).isZero();
        assertThat(terminal.all()).contains("nothing was saved");
    }

    @Test
    void mismatchedOrWeakPasswordsSaveNothing() {
        var mismatch = new ScriptedTerminal().secrets(STRONG, STRONG + "!");
        assertThat(run(mismatch, "create-platform-admin", "PLATFORM_ADMIN")).isEqualTo(1);
        assertThat(mismatch.all()).contains("the passwords don't match");

        var weak = new ScriptedTerminal().secrets("short", "short");
        assertThat(run(weak, "create-platform-admin", "PLATFORM_ADMIN")).isEqualTo(1);
        assertThat(weak.all()).contains("Use at least 12 characters.");
        assertThat(count()).isZero();
    }

    @Test
    void refusesADuplicateEmailAndAnUnknownRole() {
        createExisting();
        var again = new ScriptedTerminal().secrets(STRONG, STRONG);
        assertThat(run(again, "create-platform-admin", "PLATFORM_ADMIN")).isEqualTo(1);
        assertThat(again.all()).contains("already exists")
                .doesNotContain("New password").doesNotContain("Or enter this secret manually").doesNotContain("otpauth://");
        assertThat(run(new ScriptedTerminal(), "create-platform-admin", "ROOT")).isEqualTo(2);
    }

    @Test
    void resetTotpReplacesTheSecretAndEndsSessions() {
        createExisting();
        var terminal = new ScriptedTerminal();
        assertThat(run(terminal, "reset-platform-totp", null)).isZero();
        Map<String, Object> row = row();
        assertThat(cipher.decrypt((String) row.get("totp_secret_enc"), (UUID) row.get("id"))).isEqualTo(terminal.printedSecret());
        assertThat(row.get("token_version")).isEqualTo(1);
        assertThat(row.get("totp_last_step")).isEqualTo(Totp.step(NOW)); // the confirmation code is consumed
    }

    @Test
    void theEnrolmentCodeCannotBeUsedAgainToSignIn() throws Exception {
        var terminal = new ScriptedTerminal().secrets(STRONG, STRONG);
        terminal.now = Instant.now();
        assertThat(run(terminal, "create-platform-admin", "PLATFORM_ADMIN")).isZero();
        String enrolmentCode = terminal.correctCode();
        login(STRONG, enrolmentCode).andExpect(status().isUnauthorized());
        login(STRONG, Totp.code(terminal.printedSecret(), Totp.step(terminal.now) + 1)).andExpect(status().isOk());
    }

    @Test
    void resetTotpForAnUnknownEmailFailsBeforeShowingASecret() {
        var terminal = new ScriptedTerminal();
        assertThat(run(terminal, "reset-platform-totp", null)).isEqualTo(1);
        assertThat(terminal.all()).contains("No platform user with that email.")
                .doesNotContain("Or enter this secret manually").doesNotContain("otpauth://");
        assertThat(count()).isZero();
    }

    @Test
    void resetPasswordDisableAndEnable() {
        createExisting();
        assertThat(run(new ScriptedTerminal().secrets(STRONG, STRONG), "reset-platform-password", null)).isZero();
        assertThat(passwordEncoder.matches(STRONG, (String) row().get("password_hash"))).isTrue();
        assertThat(run(new ScriptedTerminal(), "disable-platform-user", null)).isZero();
        assertThat(row().get("status")).isEqualTo("DISABLED");
        assertThat(row().get("token_version")).isEqualTo(2);
        assertThat(run(new ScriptedTerminal(), "enable-platform-user", null)).isZero();
        assertThat(row().get("status")).isEqualTo("ACTIVE");
        assertThat(run(new ScriptedTerminal(), "disable-platform-user", null)).isZero();
        email = "nobody-" + email;
        var missing = new ScriptedTerminal();
        assertThat(run(missing, "disable-platform-user", null)).isEqualTo(1);
        assertThat(missing.all()).contains("No platform user with that email.");
    }

    @Test
    void unknownCommandOrMissingEmailPrintsUsage() {
        assertThat(run(new ScriptedTerminal(), "make-coffee", null)).isEqualTo(2);
        email = " ";
        var terminal = new ScriptedTerminal();
        assertThat(run(terminal, "create-platform-admin", "PLATFORM_ADMIN")).isEqualTo(2);
        assertThat(terminal.all()).contains("Usage:");
    }

    private void createExisting() {
        admin.create(email, PlatformRole.PLATFORM_ADMIN, TestPlatformUsers.PASSWORD, Totp.newSecret(), 0);
    }
}
