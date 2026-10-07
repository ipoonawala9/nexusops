package com.nexusops.directory;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexusops.directory.domain.PartyNames;
import org.junit.jupiter.api.Test;

class PartyNamesTest {

    @Test
    void personKeyIgnoresCaseAndSpacing() {
        assertThat(PartyNames.personKey("  ada ", "  LOVELACE ")).isEqualTo(PartyNames.personKey("Ada", "Lovelace"));
        assertThat(PartyNames.personKey("Ada", null)).isEqualTo("ada");
        assertThat(PartyNames.personKey("Ada", "Lovelace")).isNotEqualTo(PartyNames.personKey("Ada", "Byron"));
    }

    @Test
    void personKeyIgnoresAccentsAndPunctuation() {
        assertThat(PartyNames.personKey("Renée", "Dupont")).isEqualTo(PartyNames.personKey("Renee", "Dupont"));
        assertThat(PartyNames.personKey("Seán", "O’Brien")).isEqualTo(PartyNames.personKey("Sean", "O'Brien"));
        assertThat(PartyNames.personKey("Mary-Jane", "Ng")).isEqualTo(PartyNames.personKey("Mary Jane", "Ng"));
        assertThat(PartyNames.personKey("Mary-Jane", "Ng")).isEqualTo("mary jane ng");
    }

    @Test
    void personKeyOfOnlyPunctuationFallsBackToTheLowerCasedName() {
        assertThat(PartyNames.personKey("  -- ", null)).isEqualTo("--");
    }

    @Test
    void fullNameJoinsOptionalLastName() {
        assertThat(PartyNames.fullName("Ada", "Lovelace")).isEqualTo("Ada Lovelace");
        assertThat(PartyNames.fullName("Plato", null)).isEqualTo("Plato");
    }

    @Test
    void organizationKeyIgnoresCasePunctuationAccentsAndLegalSuffixes() {
        String acme = PartyNames.organizationKey("Acme");
        assertThat(PartyNames.organizationKey("ACME, Inc.")).isEqualTo(acme);
        assertThat(PartyNames.organizationKey("A.C.M.E. Ltd")).isEqualTo(acme);
        assertThat(PartyNames.organizationKey("Acme GmbH & Co. KG")).isEqualTo(acme);
        assertThat(PartyNames.organizationKey("Ácme LLC")).isEqualTo(acme);
        assertThat(PartyNames.organizationKey("Acme Robotics")).isNotEqualTo(acme);
    }

    @Test
    void organizationKeyKeepsALoneSuffixWordAndFallsBackForSymbols() {
        assertThat(PartyNames.organizationKey("Company")).isEqualTo("company");
        assertThat(PartyNames.organizationKey("!!!")).isEqualTo("!!!");
    }
}
