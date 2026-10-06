package com.nexusops.tenancy;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.db.TenantLocks;
import com.nexusops.shared.web.ApiProblem;
import com.nexusops.tenancy.domain.ModuleDefinitionRepository;
import com.nexusops.tenancy.domain.Tenant;
import com.nexusops.tenancy.domain.TenantModule;
import com.nexusops.tenancy.domain.TenantModuleRepository;
import com.nexusops.tenancy.domain.TenantRepository;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Currency;
import java.util.HashSet;
import java.util.IllformedLocaleException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The tenancy module's API. The tenants table is global (no RLS), so this class is the guard:
 * apart from the explicit pre-auth lookups, it only ever touches the tenant bound in TenantContext.
 */
@Service
public class TenantDirectory {

    static final String SUSPEND_NOT_ACTIVE = "Only an active workspace can be suspended.";
    static final String REACTIVATE_NOT_SUSPENDED = "Only a suspended workspace can be reactivated.";

    private final TenantRepository tenants;
    private final TenantModuleRepository modules;
    private final AuditService audit;
    private final ModuleDefinitionRepository catalog;
    private final ApplicationEventPublisher events;
    private final TenantLocks locks;

    TenantDirectory(
            TenantRepository tenants,
            TenantModuleRepository modules,
            AuditService audit,
            ModuleDefinitionRepository catalog,
            ApplicationEventPublisher events,
            TenantLocks locks) {
        this.tenants = tenants;
        this.modules = modules;
        this.audit = audit;
        this.catalog = catalog;
        this.events = events;
        this.locks = locks;
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

    @Transactional(readOnly = true)
    public PlanLimits currentLimits() {
        var row = tenants.findPlanLimits(TenantContext.requireTenantId());
        return new PlanLimits(parse(row.getMaxUsers()), parse(row.getMaxModules()));
    }

    @Transactional(readOnly = true)
    public List<ModuleState> modules() {
        TenantContext.requireTenantId();
        var enabled = new HashSet<>(modules.findEnabledCodes());
        return catalog.findAllByOrderByCodeAsc().stream()
                .map(m -> new ModuleState(m.getCode(), m.getName(), enabled.contains(m.getCode())))
                .toList();
    }

    @Transactional
    public ModuleState setModuleEnabled(String code, boolean enabled) {
        UUID tenantId = TenantContext.requireTenantId();
        var definition = catalog.findById(code).orElseThrow(() -> ApiProblem.notFound("Module not found."));
        locks.lock("modules");
        var module = modules.findByModuleCode(code)
                .orElseGet(() -> modules.save(new TenantModule(Ids.newId(), code, false)));
        if (module.isEnabled() == enabled) {
            return new ModuleState(code, definition.getName(), enabled);
        }
        if (enabled) {
            Integer max = currentLimits().maxModules();
            if (max != null && modules.countByEnabledTrue() >= max) {
                throw ApiProblem.conflict("Your plan allows " + max + " modules. Upgrade to enable more.");
            }
        }
        module.setEnabled(enabled);
        modules.flush();
        audit.record(AuditEntry.of(enabled ? "ModuleEnabled" : "ModuleDisabled", "Module", code));
        events.publishEvent(new ModulesChanged(tenantId));
        return new ModuleState(code, definition.getName(), enabled);
    }

    /**
     * Platform only (ADR-0007): suspends the tenant bound in TenantContext on behalf of a platform operator.
     * Members are blocked on their next request: TenantStatusChanged evicts the principal cache after commit.
     * Concurrent status changes fail on Tenant's @Version (409).
     */
    @Transactional
    public TenantSummary suspendCurrent(UUID platformUserId, String rawReason) {
        String reason = requireReason(rawReason);
        Tenant tenant = currentTenant();
        if (tenant.toSummary().status() != TenantStatus.ACTIVE) {
            throw ApiProblem.conflict(SUSPEND_NOT_ACTIVE);
        }
        tenant.suspend();
        return statusChanged(tenant, "TenantSuspended", platformUserId, reason);
    }

    /** Platform only (ADR-0007): reactivates the suspended tenant bound in TenantContext. */
    @Transactional
    public TenantSummary reactivateCurrent(UUID platformUserId, String rawReason) {
        String reason = requireReason(rawReason);
        Tenant tenant = currentTenant();
        if (tenant.toSummary().status() != TenantStatus.SUSPENDED) {
            throw ApiProblem.conflict(REACTIVATE_NOT_SUSPENDED);
        }
        tenant.reactivate();
        return statusChanged(tenant, "TenantReactivated", platformUserId, reason);
    }

    private TenantSummary statusChanged(Tenant tenant, String action, UUID platformUserId, String reason) {
        tenants.flush();
        audit.record(AuditEntry.of(action, "Tenant", tenant.getId())
                .withMetadata(Map.of("reason", reason))
                .asPlatformActor(platformUserId)
                .withoutClientDetails()); // the workspace sees the reason, not the operator's IP/UA (ADR-0007)
        events.publishEvent(new TenantStatusChanged(tenant.getId()));
        return tenant.toSummary();
    }

    private static String requireReason(String raw) {
        String reason = raw == null ? "" : raw.strip();
        if (reason.isEmpty() || reason.length() > 500) {
            throw ApiProblem.badRequestField("reason", "Enter a reason between 1 and 500 characters.");
        }
        return reason;
    }

    private static Integer parse(String value) {
        return value == null || value.isBlank() ? null : Integer.valueOf(value);
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
