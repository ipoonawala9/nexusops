package com.nexusops.helpdesk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FullTextTermsTest {

    @Test
    void turnsTextIntoOrEdLowercaseWords() {
        assertThat(FullTextTerms.orQuery("Printer NOT printing — paper jams", 10))
                .isEqualTo("printer | not | printing | paper | jams");
    }

    @Test
    void operatorsAndPunctuationNeverReachTheQuery() {
        assertThat(FullTextTerms.orQuery("can't print: error 0x80!! a & b | c <-> (d)", 10))
                .isEqualTo("can | print | error | 0x80");
        assertThat(FullTextTerms.orQuery("!!! & | ' :*", 10)).isEmpty();
        assertThat(FullTextTerms.orQuery(null, 10)).isEmpty();
    }

    @Test
    void keepsOtherScriptsWholeDropsDuplicatesAndCaps() {
        assertThat(FullTextTerms.orQuery("प्रिंटर काम नहीं कर रहा", 10)).isEqualTo("प्रिंटर | काम | नहीं | कर | रहा");
        assertThat(FullTextTerms.orQuery("jam jam JAM paper", 10)).isEqualTo("jam | paper");
        assertThat(FullTextTerms.orQuery("one two three four five", 3)).isEqualTo("one | two | three");
    }
}
