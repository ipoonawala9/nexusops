package com.nexusops.directory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexusops.directory.domain.OrganizationDetails;
import com.nexusops.directory.domain.Party;
import com.nexusops.directory.domain.PartyRepository;
import com.nexusops.directory.domain.PartyRole;
import com.nexusops.directory.domain.PartyRoleRepository;
import com.nexusops.directory.domain.PersonDetails;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestTenants;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfigureMockMvc
class DirectoryPersistenceIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired PartyRepository parties;
    @Autowired PartyRoleRepository roles;
    @Autowired TransactionTemplate tx;

    UUID tenantA;
    UUID tenantB;

    @BeforeEach
    void tenants() throws Exception {
        tenantA = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("dira")).tenantId();
        tenantB = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("dirb")).tenantId();
    }

    private UUID saveOrganization(UUID tenant, String name) {
        UUID id = Ids.newId();
        TenantContext.runAs(tenant, () -> tx.executeWithoutResult(s -> parties.save(
                Party.organization(id, new OrganizationDetails(name, "acme.com", null, null, null)))));
        return id;
    }

    @Test
    void personAndOrganizationRoundTripWithTenantStampedAndKeysComputed() {
        UUID org = saveOrganization(tenantA, "ACME, Inc.");
        UUID person = Ids.newId();
        TenantContext.runAs(tenantA, () -> tx.executeWithoutResult(s -> {
            parties.save(Party.person(person, new PersonDetails("Ada", "Lovelace", "CTO", org, "ada@acme.com", null)));
            roles.save(new PartyRole(Ids.newId(), person, PartyRoleType.CUSTOMER));
        }));
        TenantContext.runAs(tenantA, () -> tx.executeWithoutResult(s -> {
            Party loaded = parties.findById(person).orElseThrow();
            assertThat(loaded.getTenantId()).isEqualTo(tenantA);
            assertThat(loaded.getKind()).isEqualTo(PartyKind.PERSON);
            assertThat(loaded.getName()).isEqualTo("Ada Lovelace");
            assertThat(loaded.getNameKey()).isEqualTo("ada lovelace");
            assertThat(loaded.getOrganizationId()).isEqualTo(org);
            assertThat(parties.findById(org).orElseThrow().getNameKey()).isEqualTo("acme");
            assertThat(parties.findByKindAndEmail(PartyKind.PERSON, "ada@acme.com")).hasSize(1);
            assertThat(parties.findByKindAndDomain(PartyKind.ORGANIZATION, "acme.com")).hasSize(1);
            assertThat(roles.findByPartyIdOrderByRoleAsc(person)).extracting(PartyRole::getRole)
                    .containsExactly(PartyRoleType.CUSTOMER);
        }));
        TenantContext.runAs(tenantB, () -> tx.executeWithoutResult(s ->
                assertThat(parties.findById(person)).isEmpty()));
    }

    @Test
    void rawAppConnectionSeesOnlyItsOwnTenantsParties() {
        UUID orgB = saveOrganization(tenantB, "Beta");
        var appAsA = OwnerJdbc.tenantScoped("nexusops_app", APP_PASSWORD, tenantA.toString());
        var appNoTenant = OwnerJdbc.tenantScoped("nexusops_app", APP_PASSWORD, "");
        assertThat(appAsA.queryForObject("select count(*) from parties where id = ?", Long.class, orgB)).isZero();
        assertThat(appNoTenant.queryForObject("select count(*) from parties", Long.class)).isZero();
        assertThat(appNoTenant.queryForObject("select count(*) from party_roles", Long.class)).isZero();
    }

    @Test
    void aPersonCannotReferenceAnotherTenantsOrganization() {
        UUID orgB = saveOrganization(tenantB, "Beta");
        Timestamp now = Timestamp.from(Instant.now());
        assertThatThrownBy(() -> OwnerJdbc.ownerAs(tenantA).update("""
                insert into parties (id, tenant_id, kind, name, name_key, first_name, organization_id, created_at, updated_at)
                values (?, ?, 'PERSON', 'Eve', 'eve', 'Eve', ?, ?, ?)""", Ids.newId(), tenantA, orgB, now, now))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void kindSpecificColumnsAreEnforcedByTheDatabase() {
        Timestamp now = Timestamp.from(Instant.now());
        assertThatThrownBy(() -> OwnerJdbc.ownerAs(tenantA).update("""
                insert into parties (id, tenant_id, kind, name, name_key, first_name, created_at, updated_at)
                values (?, ?, 'ORGANIZATION', 'Acme', 'acme', 'Ada', ?, ?)""", Ids.newId(), tenantA, now, now))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> OwnerJdbc.ownerAs(tenantA).update("""
                insert into parties (id, tenant_id, kind, name, name_key, domain, first_name, created_at, updated_at)
                values (?, ?, 'PERSON', 'Ada', 'ada', 'acme.com', 'Ada', ?, ?)""", Ids.newId(), tenantA, now, now))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void onlyEmployeeRolesCarryANumber() {
        UUID org = saveOrganization(tenantA, "Acme");
        Timestamp now = Timestamp.from(Instant.now());
        String insert = """
                insert into party_roles (id, tenant_id, party_id, role, status, since, employee_number, created_at, updated_at)
                values (?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?)""";
        assertThatThrownBy(() -> OwnerJdbc.ownerAs(tenantA).update(insert, Ids.newId(), tenantA, org, "CUSTOMER",
                java.sql.Date.valueOf(LocalDate.now()), "E-1", now, now))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
