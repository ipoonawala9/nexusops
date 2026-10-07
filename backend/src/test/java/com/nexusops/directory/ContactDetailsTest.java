package com.nexusops.directory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexusops.directory.domain.ContactDetails;
import com.nexusops.shared.web.ApiProblem;
import org.junit.jupiter.api.Test;

class ContactDetailsTest {

    @Test
    void blankValuesAreNull() {
        assertThat(ContactDetails.email(" ")).isNull();
        assertThat(ContactDetails.phone(null)).isNull();
        assertThat(ContactDetails.domain("")).isNull();
        assertThat(ContactDetails.website("  ")).isNull();
    }

    @Test
    void emailIsNormalized() {
        assertThat(ContactDetails.email("  Ada@Example.COM ")).isEqualTo("ada@example.com");
        assertThatThrownBy(() -> ContactDetails.email("nope")).isInstanceOf(ApiProblem.class);
    }

    @Test
    void domainStripsSchemeWwwPathPortAndCase() {
        assertThat(ContactDetails.domain("https://WWW.Acme.com/about?x=1")).isEqualTo("acme.com");
        assertThat(ContactDetails.domain("acme.co.uk.")).isEqualTo("acme.co.uk");
        assertThat(ContactDetails.domain("http://shop.acme.io:8080")).isEqualTo("shop.acme.io");
        assertThat(ContactDetails.domain("sales@acme.com")).isEqualTo("acme.com");
        assertThat(ContactDetails.domain("xn--80ak6aa92e.com")).isEqualTo("xn--80ak6aa92e.com");
    }

    @Test
    void invalidDomainIsAFieldError() {
        for (String bad : new String[] {"acme", "-acme.com", "acme..com", "acme.c", "ac me.com"}) {
            assertThatThrownBy(() -> ContactDetails.domain(bad)).as(bad).isInstanceOf(ApiProblem.class)
                    .satisfies(e -> assertThat(((ApiProblem) e).errors().getFirst().field()).isEqualTo("domain"));
        }
    }

    @Test
    void websiteGetsHttpsWhenSchemeless() {
        assertThat(ContactDetails.website("acme.com/team")).isEqualTo("https://acme.com/team");
        assertThat(ContactDetails.website("http://acme.com")).isEqualTo("http://acme.com");
    }

    @Test
    void websiteRejectsOtherSchemesAndJunk() {
        for (String bad : new String[] {"javascript:alert(1)", "ftp://acme.com", "https://", "x".repeat(260) + ".com"}) {
            assertThatThrownBy(() -> ContactDetails.website(bad)).as(bad).isInstanceOf(ApiProblem.class)
                    .satisfies(e -> assertThat(((ApiProblem) e).errors().getFirst().field()).isEqualTo("website"));
        }
    }

    @Test
    void phoneAllowsCommonPunctuationOnly() {
        assertThat(ContactDetails.phone(" +44 (20)  7946-0958 ")).isEqualTo("+44 (20) 7946-0958");
        for (String bad : new String[] {"12", "call me", "+1 555 0100 ext. 4"}) {
            assertThatThrownBy(() -> ContactDetails.phone(bad)).as(bad).isInstanceOf(ApiProblem.class)
                    .satisfies(e -> assertThat(((ApiProblem) e).errors().getFirst().field()).isEqualTo("phone"));
        }
    }
}
