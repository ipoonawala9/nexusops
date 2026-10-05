package com.nexusops.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.web.ApiProblem;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TenantSettingsIT extends IntegrationTestSupport {

    @Autowired TenantDirectory directory;

    UUID tenantId;
    String slug;

    @BeforeEach
    void register() {
        tenantId = Ids.newId();
        slug = "set-" + tenantId.toString().substring(28);
        directory.register(tenantId, "  " + slug.toUpperCase() + " ", "Settings Co");
    }

    @Test
    void registeredTenantIsPendingOnFreePlanWithDefaults() {
        TenantSettings settings = TenantContext.callAs(tenantId, directory::currentSettings);
        assertThat(settings.slug()).isEqualTo(slug);
        assertThat(settings.status()).isEqualTo(TenantStatus.PENDING_VERIFICATION);
        assertThat(settings.planCode()).isEqualTo("FREE");
        assertThat(settings.timezone()).isEqualTo("UTC");
        assertThat(settings.currency()).isEqualTo("USD");
        assertThat(TenantContext.callAs(tenantId, directory::enabledModules)).isEmpty();
    }

    @Test
    void duplicateSlugIsAConflictOnTheSlugField() {
        assertThatThrownBy(() -> directory.register(Ids.newId(), slug, "Other"))
                .isInstanceOfSatisfying(ApiProblem.class, p -> {
                    assertThat(p.status().value()).isEqualTo(409);
                    assertThat(p.errors().getFirst().field()).isEqualTo("slug");
                });
    }

    @Test
    void findsBySlugLenientlyAndIgnoresGarbage() {
        assertThat(directory.findBySlug(" " + slug.toUpperCase())).map(TenantSummary::id).contains(tenantId);
        assertThat(directory.findBySlug("not a slug!")).isEmpty();
        assertThat(directory.findBySlug(null)).isEmpty();
    }

    @Test
    void activateCurrentMakesTenantActive() {
        TenantContext.runAs(tenantId, directory::activateCurrent);
        assertThat(TenantContext.callAs(tenantId, directory::current).status()).isEqualTo(TenantStatus.ACTIVE);
    }

    @Test
    void updatesSettingsAndAuditsBeforeAndAfter() {
        TenantSettings updated = TenantContext.callAs(tenantId,
                () -> directory.updateSettings(new UpdateTenantSettings(" Renamed Co ", "Asia/Kolkata", "en-IN", "inr")));
        assertThat(updated.name()).isEqualTo("Renamed Co");
        assertThat(updated.timezone()).isEqualTo("Asia/Kolkata");
        assertThat(updated.locale()).isEqualTo("en-IN");
        assertThat(updated.currency()).isEqualTo("INR");
        String after = OwnerJdbc.superuser().queryForObject(
                "select after::text from audit_events where tenant_id = ? and action = 'TenantSettingsUpdated'", String.class, tenantId);
        assertThat(after).contains("Asia/Kolkata");
    }

    @Test
    void nullFieldsAreLeftUnchanged() {
        TenantSettings updated = TenantContext.callAs(tenantId,
                () -> directory.updateSettings(new UpdateTenantSettings(null, "Europe/Berlin", null, null)));
        assertThat(updated.name()).isEqualTo("Settings Co");
        assertThat(updated.timezone()).isEqualTo("Europe/Berlin");
    }

    @Test
    void invalidSettingsAreFieldErrors() {
        assertFieldError(new UpdateTenantSettings(null, "Mars/Olympus", null, null), "timezone");
        assertFieldError(new UpdateTenantSettings(null, null, "!!", null), "locale");
        assertFieldError(new UpdateTenantSettings(null, null, null, "XYZ"), "currency");
        assertFieldError(new UpdateTenantSettings("   ", null, null, null), "name");
    }

    private void assertFieldError(UpdateTenantSettings command, String field) {
        assertThatThrownBy(() -> TenantContext.callAs(tenantId, () -> directory.updateSettings(command)))
                .isInstanceOfSatisfying(ApiProblem.class, p -> assertThat(p.errors().getFirst().field()).isEqualTo(field));
    }

    @Test
    void currentTenantOperationsRequireATenant() {
        assertThatThrownBy(directory::currentSettings).isInstanceOf(IllegalStateException.class);
    }
}
