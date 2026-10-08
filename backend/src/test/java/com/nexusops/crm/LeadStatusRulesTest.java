package com.nexusops.crm;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexusops.shared.web.ApiProblem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LeadStatusRulesTest {

    @ParameterizedTest
    @CsvSource({"NEW,CONTACTED", "CONTACTED,QUALIFIED", "QUALIFIED,NEW", "NEW,DISQUALIFIED", "QUALIFIED,DISQUALIFIED",
            "DISQUALIFIED,NEW"})
    void allowed(LeadStatus from, LeadStatus to) {
        assertThatCode(() -> LeadStatus.check(from, to)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource({"DISQUALIFIED,CONTACTED,409", "DISQUALIFIED,QUALIFIED,409", "CONVERTED,NEW,409",
            "CONVERTED,DISQUALIFIED,409", "NEW,CONVERTED,400", "QUALIFIED,CONVERTED,400"})
    void refused(LeadStatus from, LeadStatus to, int status) {
        assertThatThrownBy(() -> LeadStatus.check(from, to)).isInstanceOfSatisfying(ApiProblem.class,
                p -> org.assertj.core.api.Assertions.assertThat(p.status().value()).isEqualTo(status));
    }

    @Test
    void openStatuses() {
        org.assertj.core.api.Assertions.assertThat(LeadStatus.NEW.isOpen()).isTrue();
        org.assertj.core.api.Assertions.assertThat(LeadStatus.QUALIFIED.isOpen()).isTrue();
        org.assertj.core.api.Assertions.assertThat(LeadStatus.DISQUALIFIED.isOpen()).isFalse();
        org.assertj.core.api.Assertions.assertThat(LeadStatus.CONVERTED.isOpen()).isFalse();
    }
}
