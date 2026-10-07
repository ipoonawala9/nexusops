package com.nexusops.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexusops.shared.TenantContext;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestMembers;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Workspace;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class MembersIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers testMembers;
    @Autowired Members members;

    private static UUID userId(UUID tenant, String email) {
        return OwnerJdbc.ownerAs(tenant).queryForObject("select id from users where email = ?", UUID.class, email);
    }

    @Test
    void findsActiveMembersOfTheCurrentTenantOnly() throws Exception {
        Workspace a = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("mem"));
        Workspace b = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("mem"));
        UUID owner = userId(a.tenantId(), a.email());
        UUID disabled = userId(a.tenantId(), testMembers.create(a.tenantId(), Set.of()).email());
        OwnerJdbc.ownerAs(a.tenantId()).update("update users set status = 'DISABLED' where id = ?", disabled);
        UUID other = userId(b.tenantId(), b.email());

        TenantContext.runAs(a.tenantId(), () -> {
            assertThat(members.findActive(owner)).get().satisfies(m -> {
                assertThat(m.name()).isEqualTo("Ada Owner");
                assertThat(m.email()).isEqualTo(a.email());
                assertThat(m.active()).isTrue();
            });
            assertThat(members.findActive(disabled)).isEmpty();
            assertThat(members.findActive(other)).isEmpty();
            assertThat(members.findAll(List.of(owner, disabled, other))).containsOnlyKeys(owner, disabled);
            assertThat(members.findAll(List.of(disabled)).get(disabled).active()).isFalse();
            assertThat(members.searchActive(null, 20)).extracting(Members.Member::id).containsExactly(owner);
            assertThat(members.searchActive("OWNER", 20)).extracting(Members.Member::id).containsExactly(owner);
            assertThat(members.searchActive("nobody", 20)).isEmpty();
        });
    }
}
