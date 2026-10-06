package com.nexusops.platform.cli;

import com.nexusops.platform.application.PlatformUserAdmin;
import com.nexusops.platform.application.PlatformUserAdmin.Enrollment;
import com.nexusops.platform.domain.PlatformRole;
import com.nexusops.platform.domain.PlatformUserStatus;
import com.nexusops.platform.totp.Totp;
import com.nexusops.shared.web.ApiProblem;
import java.time.Clock;
import java.util.Arrays;
import java.util.OptionalLong;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Platform account CLI (decision 1, ADR-0007). Active only when started with
 * {@code --nexusops.cli.command=...} (see NexusOpsApplication and the Makefile's platform-* targets).
 * Exit codes: 0 done, 1 failed (nothing saved), 2 usage.
 */
@Component
@ConditionalOnProperty(name = "nexusops.cli.command")
public class PlatformCli implements ApplicationRunner, ExitCodeGenerator {

    static final int OK = 0;
    static final int FAILED = 1;
    static final int USAGE = 2;
    private static final int CODE_ATTEMPTS = 3;

    private final PlatformUserAdmin admin;
    private final Terminal terminal;
    private final String command;
    private final String email;
    private final String role;
    private final Clock clock;
    private int exitCode = FAILED;

    @Autowired
    PlatformCli(PlatformUserAdmin admin, @Value("${nexusops.cli.command}") String command,
            @Value("${nexusops.cli.email:}") String email, @Value("${nexusops.cli.role:PLATFORM_ADMIN}") String role) {
        this(admin, new ConsoleTerminal(), command, email, role, Clock.systemUTC());
    }

    public PlatformCli(PlatformUserAdmin admin, Terminal terminal, String command, String email, String role, Clock clock) {
        this.admin = admin;
        this.terminal = terminal;
        this.command = command;
        this.email = email;
        this.role = role;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        exitCode = execute();
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    public int execute() {
        if (email == null || email.isBlank()) {
            usage();
            return USAGE;
        }
        try {
            return switch (command) {
                case "create-platform-admin" -> create();
                case "reset-platform-totp" -> resetTotp();
                case "reset-platform-password" -> resetPassword();
                case "disable-platform-user" -> {
                    admin.setStatus(email, PlatformUserStatus.DISABLED);
                    terminal.println("Disabled " + email + ". Their platform sessions end on their next request.");
                    yield OK;
                }
                case "enable-platform-user" -> {
                    admin.setStatus(email, PlatformUserStatus.ACTIVE);
                    terminal.println("Enabled " + email + ".");
                    yield OK;
                }
                default -> {
                    usage();
                    yield USAGE;
                }
            };
        } catch (ApiProblem problem) {
            terminal.println("Error: " + describe(problem));
            return FAILED;
        } catch (IllegalStateException e) { // e.g. ConsoleTerminal without an interactive terminal: no stack trace
            terminal.println("Error: " + e.getMessage());
            return FAILED;
        }
    }

    private int create() {
        PlatformRole platformRole = parseRole();
        if (platformRole == null) {
            terminal.println("Error: role must be PLATFORM_ADMIN or PLATFORM_SUPPORT.");
            return USAGE;
        }
        admin.requireNew(email);
        String password = newPassword();
        if (password == null) {
            return FAILED;
        }
        Enrollment enrollment = admin.newEnrollment(email);
        long confirmedStep = enrol(enrollment);
        if (confirmedStep < 0) {
            return FAILED;
        }
        admin.create(email, platformRole, password, enrollment.secret(), confirmedStep);
        terminal.println("Created " + platformRole + " " + enrollment.email()
                + ". Sign in at /platform/login with your password and a code from your app.");
        return OK;
    }

    private int resetTotp() {
        admin.requireExisting(email);
        Enrollment enrollment = admin.newEnrollment(email);
        long confirmedStep = enrol(enrollment);
        if (confirmedStep < 0) {
            return FAILED;
        }
        admin.resetTotp(email, enrollment.secret(), confirmedStep);
        terminal.println("New authenticator enrolled for " + enrollment.email() + "; all their sessions are signed out.");
        return OK;
    }

    private int resetPassword() {
        String password = newPassword();
        if (password == null) {
            return FAILED;
        }
        admin.resetPassword(email, password);
        terminal.println("Password changed for " + email + "; all their sessions are signed out.");
        return OK;
    }

    private String newPassword() {
        char[] first = terminal.readSecret("New password (at least 12 characters): ");
        char[] second = terminal.readSecret("Repeat the password: ");
        try {
            if (first == null || second == null || !Arrays.equals(first, second)) {
                terminal.println("Error: the passwords don't match. Nothing was saved.");
                return null;
            }
            String password = new String(first);
            admin.checkPassword(password, email);
            return password;
        } finally {
            if (first != null) Arrays.fill(first, '\0');
            if (second != null) Arrays.fill(second, '\0');
        }
    }

    /**
     * Shows the secret once and requires a correct code before anything is saved. Returns the confirmed code's step
     * (stored as already used, so the code typed here can't sign in), or -1 if enrolment was not confirmed.
     */
    private long enrol(Enrollment enrollment) {
        terminal.println("Scan this QR code with your authenticator app (1Password, Google Authenticator, Authy, ...):");
        terminal.println(QrCodes.render(enrollment.otpauthUri()));
        terminal.println("Or enter this secret manually: " + grouped(enrollment.secretBase32()));
        terminal.println("otpauth URI: " + enrollment.otpauthUri());
        for (int attempt = 1; attempt <= CODE_ATTEMPTS; attempt++) {
            String code = terminal.readLine("Enter the 6-digit code your app shows: ");
            OptionalLong step = Totp.verify(enrollment.secret(), code, clock.instant(), 0);
            if (step.isPresent()) {
                return step.getAsLong();
            }
            terminal.println("That code doesn't match. Check the time on your phone and try again.");
        }
        terminal.println("Error: enrolment not confirmed; nothing was saved.");
        return -1;
    }

    private PlatformRole parseRole() {
        try {
            return PlatformRole.valueOf(role == null ? "PLATFORM_ADMIN" : role.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void usage() {
        terminal.println("""
                Usage: --nexusops.cli.command=<command> --nexusops.cli.email=<email> [--nexusops.cli.role=<role>]
                Commands: create-platform-admin, reset-platform-totp, reset-platform-password,
                          disable-platform-user, enable-platform-user
                Roles:    PLATFORM_ADMIN (default), PLATFORM_SUPPORT""");
    }

    private static String grouped(String base32) {
        return base32.replaceAll("(.{4})(?!$)", "$1 ");
    }

    private static String describe(ApiProblem problem) {
        return problem.errors().isEmpty() ? problem.getMessage()
                : problem.errors().stream().map(ApiProblem.FieldError::message).collect(Collectors.joining(" "));
    }
}
