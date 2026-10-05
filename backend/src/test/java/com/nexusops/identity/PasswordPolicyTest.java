package com.nexusops.identity;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexusops.identity.application.PasswordPolicy;
import com.nexusops.shared.web.ApiProblem;
import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    final PasswordPolicy policy = new PasswordPolicy();

    @Test
    void acceptsAStrongPassword() {
        assertThatCode(() -> policy.check("correct horse battery staple", "owner@acme.test")).doesNotThrowAnyException();
    }

    @Test
    void rejectsShortLongCommonAndEmailPasswords() {
        assertRejected("short1!", "a@b.test");
        assertRejected("x".repeat(129), "a@b.test");
        assertRejected("Password1234", "a@b.test");
        assertRejected("owner@acme.test", "owner@acme.test");
        assertRejected(null, "a@b.test");
    }

    private void assertRejected(String password, String email) {
        assertThatThrownBy(() -> policy.check(password, email))
                .isInstanceOfSatisfying(ApiProblem.class,
                        p -> org.assertj.core.api.Assertions.assertThat(p.errors().getFirst().field()).isEqualTo("password"));
    }
}
