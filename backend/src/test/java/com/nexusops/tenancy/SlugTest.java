package com.nexusops.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexusops.shared.web.ApiProblem;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class SlugTest {

    @Test
    void trimsAndLowercases() {
        assertThat(Slug.normalize("  Acme-Corp ")).isEqualTo("acme-corp");
        assertThat(Slug.normalize("a1b")).isEqualTo("a1b");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "ab", "acme-", "-acme", "a--b", "acmé", "acme corp", "acme_corp",
            "abcdefghijabcdefghijabcdefghijabcdefghijk", "api", "API", "admin", "platform", "www"})
    void rejectsInvalidOrReservedSlugsWithAFieldError(String raw) {
        assertThatThrownBy(() -> Slug.normalize(raw))
                .isInstanceOfSatisfying(ApiProblem.class, p -> {
                    assertThat(p.status().value()).isEqualTo(400);
                    assertThat(p.errors()).singleElement().satisfies(e -> assertThat(e.field()).isEqualTo("slug"));
                });
        assertThat(Slug.tryNormalize(raw)).isEmpty();
    }

    @Test
    void acceptsFortyCharacters() {
        assertThat(Slug.normalize("abcdefghijabcdefghijabcdefghijabcdefghij")).hasSize(40);
    }
}
