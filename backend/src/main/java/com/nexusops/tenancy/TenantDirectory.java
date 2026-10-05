package com.nexusops.tenancy;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.web.ApiProblem;
import com.nexusops.tenancy.domain.Tenant;
import com.nexusops.tenancy.domain.TenantModuleRepository;
import com.nexusops.tenancy.domain.TenantRepository;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Currency;
import java.util.IllformedLocaleException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The tenancy module's API. The tenants table is global (no RLS), so this class is the guard:
 * apart from the explicit pre-auth lookups, it only ever touches the tenant bound in TenantContext.
 */
@Service
public class TenantDirectory {

    private final TenantRepository tenants;
    private final TenantModuleRepository modules;
    private final AuditService audit;

    TenantDirectory(TenantRepository tenants, TenantModuleRepository modules, AuditService audit) {
        this.tenants = tenants;
        this.modules = modules;
        this.audit = audit;
    }

    /** Pre-auth: resolve a workspace by (leniently normalized) slug. Invalid input simply finds nothing. */
    @Transactional(readOnly = true)
    public Optional<TenantSummary> findBySlug(String rawSlug) {
        return Slug.tryNormalize(rawSlug).flatMap(tenants::findBySlug).map(Tenant::toSummary);
    }

    /** Pre-auth: create a workspace in PENDING_VERIFICATION on the Free plan. */
    @Transactional
    public TenantSummary register(UUID id, String rawSlug, String rawName) {
        String slug = Slug.normalize(rawSlug);
        String name = requireName(rawName, "workspaceName");
        if (tenants.existsBySlug(slug)) {
            throw slugTaken();
        }
        try {
            return tenants.saveAndFlush(Tenant.register(id, slug, name)).toSummary();
        } catch (DataIntegrityViolationException race) {
            throw slugTaken();
        }
    }

    @Transactional(readOnly = true)
    public TenantSummary current() {
        return currentTenant().toSummary();
    }

    @Transactional
    public void activateCurrent() {
        currentTenant().activate();
    }

    @Transactional(readOnly = true)
    public TenantSettings currentSettings() {
        return currentTenant().toSettings();
    }

    @Transactional
    public TenantSettings updateSettings(UpdateTenantSettings command) {
        Tenant tenant = currentTenant();
        TenantSettings before = tenant.toSettings();
        tenant.applySettings(
                command.name() == null ? null : requireName(command.name(), "name"),
                command.timezone() == null ? null : validTimezone(command.timezone()),
                command.locale() == null ? null : validLocale(command.locale()),
                command.currency() == null ? null : validCurrency(command.currency()));
        tenants.flush();
        TenantSettings after = tenant.toSettings();
        audit.record(AuditEntry.of("TenantSettingsUpdated", "Tenant", tenant.getId())
                .withBefore(settingsMap(before))
                .withAfter(settingsMap(after)));
        return after;
    }

    @Transactional(readOnly = true)
    public List<String> enabledModules() {
        TenantContext.requireTenantId();
        return modules.findEnabledCodes();
    }

    private Tenant currentTenant() {
        UUID id = TenantContext.requireTenantId();
        return tenants.findById(id).orElseThrow(() -> ApiProblem.notFound("Workspace not found."));
    }

    private static ApiProblem slugTaken() {
        return ApiProblem.conflictField("slug", "This workspace URL is already taken.");
    }

    private static String requireName(String raw, String field) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty() || name.length() > 120) {
            throw ApiProblem.badRequestField(field, "Enter a name between 1 and 120 characters.");
        }
        return name;
    }

    private static String validTimezone(String raw) {
        try {
            return ZoneId.of(raw.strip()).getId();
        } catch (DateTimeException e) {
            throw ApiProblem.badRequestField("timezone", "Unknown time zone.");
        }
    }

    private static String validLocale(String raw) {
        try {
            Locale locale = new Locale.Builder().setLanguageTag(raw.strip()).build();
            if (locale.getLanguage().isEmpty()) {
                throw new IllformedLocaleException("no language");
            }
            return locale.toLanguageTag();
        } catch (IllformedLocaleException e) {
            throw ApiProblem.badRequestField("locale", "Use a language tag such as en or en-IN.");
        }
    }

    private static String validCurrency(String raw) {
        try {
            String code = raw.strip().toUpperCase(Locale.ROOT);
            Currency.getInstance(code);
            return code;
        } catch (IllegalArgumentException e) {
            throw ApiProblem.badRequestField("currency", "Use an ISO 4217 currency code such as USD or INR.");
        }
    }

    private static Map<String, Object> settingsMap(TenantSettings s) {
        return Map.of("name", s.name(), "timezone", s.timezone(), "locale", s.locale(), "currency", s.currency());
    }
}
