package com.nexusops.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexusops.shared.web.ApiProblem;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class LeadCsvTest {

    private static List<LeadCsv.Row> parse(String text) {
        return LeadCsv.parse(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void refused(byte[] bytes, String message) {
        assertThatThrownBy(() -> LeadCsv.parse(bytes)).isInstanceOfSatisfying(ApiProblem.class, p -> {
            assertThat(p.errors()).extracting(ApiProblem.FieldError::field).containsExactly("file");
            assertThat(p.errors().getFirst().message()).contains(message);
        });
    }

    @Test
    void readsHeadersInAnyOrderAndCase() {
        List<LeadCsv.Row> rows = parse("Company,First Name,EMAIL\nAcme,Grace,g@acme.test\n");
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().row()).isEqualTo(2);
        assertThat(rows.getFirst().values()).containsEntry("company", "Acme").containsEntry("first_name", "Grace")
                .containsEntry("email", "g@acme.test");
    }

    @Test
    void handlesExcelOutput() {
        String text = "﻿first_name,company,description\r\n"
                + "Grace,\"Acme, Inc\",\"Said \"\"call me\"\"\"\r\n"
                + "Anjali,Deccan,\"two\r\nlines\"\r\n"
                + "\r\n";
        List<LeadCsv.Row> rows = parse(text);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).values()).containsEntry("company", "Acme, Inc").containsEntry("description", "Said \"call me\"");
        assertThat(rows.get(1).values()).containsEntry("description", "two\r\nlines");
        assertThat(rows.get(1).row()).isEqualTo(3);
    }

    @Test
    void flagsRowsWithMoreValuesThanTheHeader() {
        assertThat(parse("company\nA,B\n").getFirst().tooManyValues()).isTrue();
        assertThat(parse("company,email\nA\n").getFirst().values()).containsEntry("company", "A")
                .doesNotContainKey("email");
    }

    @Test
    void ignoresTrailingEmptyColumns() {
        List<LeadCsv.Row> rows = parse("company,email,,\r\nAcme,a@acme.test,,\r\nBeta,,,\r\n");
        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst().values()).containsOnlyKeys("company", "email");
        assertThat(rows.getFirst().tooManyValues()).isFalse();
        assertThat(rows.get(1).tooManyValues()).isFalse();
        assertThat(parse("company\nAcme,\n").getFirst().tooManyValues()).isFalse();
    }

    @Test
    void stillFlagsExtraNonBlankValuesAndMiddleBlankHeaders() {
        assertThat(parse("company,email,,\nAcme,a@acme.test,,x\n").getFirst().tooManyValues()).isTrue();
        refused("company,,email,,\nA,,b,,\n".getBytes(StandardCharsets.UTF_8), "(blank)");
        refused("company,,,email\nA,,,b\n".getBytes(StandardCharsets.UTF_8), "(blank)");
    }

    @Test
    void refusesBadFiles() {
        refused("".getBytes(StandardCharsets.UTF_8), "empty");
        refused("company,colour,size\nA,red,L\n".getBytes(StandardCharsets.UTF_8), "colour, size");
        refused("email,phone\na@b.test,1\n".getBytes(StandardCharsets.UTF_8), "first_name, last_name or company");
        refused("company,company\nA,B\n".getBytes(StandardCharsets.UTF_8), "more than once");
        refused("company\n\"Acme\n".getBytes(StandardCharsets.UTF_8), "quoted value");
        refused(new byte[] {'c', 'o', 'm', 'p', 'a', 'n', 'y', '\n', (byte) 0xC3, (byte) 0x28}, "UTF-8");
        refused(("company\n" + "A\n".repeat(501)).getBytes(StandardCharsets.UTF_8), "500");
        refused(new byte[LeadCsv.MAX_BYTES + 1], "256 KB");
    }
}
