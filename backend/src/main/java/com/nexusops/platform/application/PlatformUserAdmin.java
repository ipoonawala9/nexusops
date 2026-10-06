package com.nexusops.platform.application;

import com.nexusops.audit.ActorType;
import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.platform.PlatformProperties;
import com.nexusops.platform.domain.PlatformRole;
import com.nexusops.platform.domain.PlatformUser;
import com.nexusops.platform.domain.PlatformUserRepository;
import com.nexusops.platform.domain.PlatformUserStatus;
import com.nexusops.platform.internal.PlatformAccess;
import com.nexusops.platform.totp.Totp;
import com.nexusops.platform.totp.TotpSecretCipher;
import com.nexusops.shared.Emails;
import com.nexusops.shared.Ids;
import com.nexusops.shared.security.PasswordPolicy;
import com.nexusops.shared.web.ApiProblem;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** Platform account administration, used only by the CLI (decision 1, ADR-0007). Every change is audited. */
@Service
public class PlatformUserAdmin {

    static final String NOT_FOUND = "No platform user with that email.";

    public record Enrollment(String email, byte[] secret, String otpauthUri) {
        public String secretBase32() {
            return Totp.base32(secret);
        }
    }

    private final PlatformAccess access;
    private final PlatformUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final TotpSecretCipher cipher;
    private final AuditService audit;
    private final PlatformProperties properties;

    PlatformUserAdmin(PlatformAccess access, PlatformUserRepository users, PasswordEncoder passwordEncoder,
            PasswordPolicy passwordPolicy, TotpSecretCipher cipher, AuditService audit, PlatformProperties properties) {
        this.access = access;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.cipher = cipher;
        this.audit = audit;
        this.properties = properties;
    }

    /** A fresh secret for the operator to scan; nothing is stored until create/resetTotp. */
    public Enrollment newEnrollment(String rawEmail) {
        String email = Emails.normalize(rawEmail);
        byte[] secret = Totp.newSecret();
        return new Enrollment(email, secret, Totp.otpauthUri(properties.totpIssuer(), email, secret));
    }

    public void checkPassword(String password, String rawEmail) {
        passwordPolicy.check(password, Emails.normalize(rawEmail));
    }

    /** {@code confirmedStep} is the TOTP step the operator confirmed at enrolment; it is stored as already used. */
    public UUID create(String rawEmail, PlatformRole role, String password, byte[] secret, long confirmedStep) {
        String email = Emails.normalize(rawEmail);
        passwordPolicy.check(password, email);
        String hash = passwordEncoder.encode(password);
        UUID id = Ids.newId();
        return access.write(() -> {
            if (users.findByEmail(email).isPresent()) {
                throw ApiProblem.conflictField("email", "A platform user with this email already exists.");
            }
            users.saveAndFlush(PlatformUser.create(id, email, hash, cipher.encrypt(secret, id), role, confirmedStep));
            audit.record(AuditEntry.of("PlatformUserCreated", "PlatformUser", id)
                    .withMetadata(Map.of("email", email, "role", role.name()))
                    .asActor(ActorType.SYSTEM));
            return id;
        });
    }

    public void resetTotp(String rawEmail, byte[] secret, long confirmedStep) {
        access.writeWithoutResult(() -> {
            PlatformUser user = find(rawEmail);
            user.replaceTotp(cipher.encrypt(secret, user.getId()), confirmedStep);
            audit.record(AuditEntry.of("PlatformTotpReset", "PlatformUser", user.getId()).asActor(ActorType.SYSTEM));
        });
    }

    public void resetPassword(String rawEmail, String password) {
        String email = Emails.normalize(rawEmail);
        passwordPolicy.check(password, email);
        String hash = passwordEncoder.encode(password);
        access.writeWithoutResult(() -> {
            PlatformUser user = find(email);
            user.replacePassword(hash);
            audit.record(AuditEntry.of("PlatformPasswordReset", "PlatformUser", user.getId()).asActor(ActorType.SYSTEM));
        });
    }

    public void setStatus(String rawEmail, PlatformUserStatus status) {
        access.writeWithoutResult(() -> {
            PlatformUser user = find(rawEmail);
            if (status == PlatformUserStatus.DISABLED) {
                user.disable();
            } else {
                user.enable();
            }
            String action = status == PlatformUserStatus.DISABLED ? "PlatformUserDisabled" : "PlatformUserEnabled";
            audit.record(AuditEntry.of(action, "PlatformUser", user.getId()).asActor(ActorType.SYSTEM));
        });
    }

    private PlatformUser find(String rawEmail) {
        return users.findByEmail(Emails.normalize(rawEmail)).orElseThrow(() -> ApiProblem.notFound(NOT_FOUND));
    }
}
