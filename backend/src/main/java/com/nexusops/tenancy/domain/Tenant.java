package com.nexusops.tenancy.domain;

import com.nexusops.shared.db.BaseEntity;
import com.nexusops.tenancy.TenantSettings;
import com.nexusops.tenancy.TenantStatus;
import com.nexusops.tenancy.TenantSummary;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** Global (non-RLS) table; access is restricted by TenantDirectory to the current tenant or pre-auth lookups. */
@Entity
@Table(name = "tenants")
public class Tenant extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TenantStatus status;

    @Column(name = "plan_code", nullable = false)
    private String planCode;

    @Column(nullable = false)
    private String timezone;

    @Column(nullable = false)
    private String locale;

    @Column(nullable = false, columnDefinition = "bpchar")
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Tenant() {}

    public static Tenant register(UUID id, String slug, String name) {
        Tenant tenant = new Tenant();
        tenant.initId(id);
        tenant.slug = slug;
        tenant.name = name;
        tenant.status = TenantStatus.PENDING_VERIFICATION;
        tenant.planCode = "FREE";
        tenant.timezone = "UTC";
        tenant.locale = "en";
        tenant.currency = "USD";
        tenant.createdAt = Instant.now();
        tenant.updatedAt = tenant.createdAt;
        return tenant;
    }

    public void activate() {
        if (status == TenantStatus.PENDING_VERIFICATION) {
            status = TenantStatus.ACTIVE;
            updatedAt = Instant.now();
        }
    }

    /** ACTIVE → SUSPENDED. TenantDirectory checks the current status first and answers 409 otherwise. */
    public void suspend() {
        if (status != TenantStatus.ACTIVE) {
            throw new IllegalStateException("Only an active tenant can be suspended");
        }
        status = TenantStatus.SUSPENDED;
        updatedAt = Instant.now();
    }

    /** SUSPENDED → ACTIVE (never PENDING_VERIFICATION → ACTIVE: that requires email verification). */
    public void reactivate() {
        if (status != TenantStatus.SUSPENDED) {
            throw new IllegalStateException("Only a suspended tenant can be reactivated");
        }
        status = TenantStatus.ACTIVE;
        updatedAt = Instant.now();
    }

    public void applySettings(String newName, String newTimezone, String newLocale, String newCurrency) {
        if (newName != null) name = newName;
        if (newTimezone != null) timezone = newTimezone;
        if (newLocale != null) locale = newLocale;
        if (newCurrency != null) currency = newCurrency;
        updatedAt = Instant.now();
    }

    public TenantSummary toSummary() {
        return new TenantSummary(getId(), slug, name, status, planCode);
    }

    public TenantSettings toSettings() {
        return new TenantSettings(getId(), slug, name, status, planCode, timezone, locale, currency);
    }
}
