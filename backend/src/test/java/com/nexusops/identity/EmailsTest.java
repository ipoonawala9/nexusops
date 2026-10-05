package com.nexusops.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexusops.identity.application.Emails;
import com.nexusops.shared.web.ApiProblem;
import org.junit.jupiter.api.Test;

class EmailsTest {

    @Test
    void trimsAndLowercases() {
        assertThat(Emails.normalize("  Owner@Acme.COM ")).isEqualTo("owner@acme.com");
    }

    @Test
    void rejectsInvalidAddresses() {
        for (String bad : new String[] {null, "", "plain", "a@b", "a b@c.test", "@x.test", "a@" + "x".repeat(260) + ".test"}) {
            assertThatThrownBy(() -> Emails.normalize(bad)).as(String.valueOf(bad)).isInstanceOf(ApiProblem.class);
        }
    }
}
