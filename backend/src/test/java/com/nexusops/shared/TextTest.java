package com.nexusops.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexusops.shared.web.ApiProblem;
import org.junit.jupiter.api.Test;

class TextTest {

    @Test
    void requiredTrimsAndChecksLength() {
        assertThat(Text.required("  Ada ", 10, "firstName")).isEqualTo("Ada");
        assertThatThrownBy(() -> Text.required("   ", 10, "firstName")).isInstanceOf(ApiProblem.class)
                .satisfies(e -> assertThat(((ApiProblem) e).errors().getFirst().field()).isEqualTo("firstName"));
        assertThatThrownBy(() -> Text.required("x".repeat(11), 10, "firstName")).isInstanceOf(ApiProblem.class);
        assertThatThrownBy(() -> Text.required(null, 10, "firstName")).isInstanceOf(ApiProblem.class);
    }

    @Test
    void optionalTurnsBlankIntoNull() {
        assertThat(Text.optional(null, 10, "jobTitle")).isNull();
        assertThat(Text.optional("  ", 10, "jobTitle")).isNull();
        assertThat(Text.optional(" CTO ", 10, "jobTitle")).isEqualTo("CTO");
        assertThatThrownBy(() -> Text.optional("x".repeat(11), 10, "jobTitle")).isInstanceOf(ApiProblem.class)
                .hasMessage("Request validation failed.");
    }

    @Test
    void containsPatternEscapesLikeWildcards() {
        assertThat(Text.containsPattern(" 50%_Off\\ ")).isEqualTo("%50\\%\\_off\\\\%");
    }
}
