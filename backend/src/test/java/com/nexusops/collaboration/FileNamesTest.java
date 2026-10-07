package com.nexusops.collaboration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FileNamesTest {

    @Test
    void keepsOnlyTheLastPathSegmentWithoutControlCharacters() {
        assertThat(FileNames.sanitize("../../etc/pass\nwd.txt")).isEqualTo("passwd.txt");
        assertThat(FileNames.sanitize("C:\\Users\\ada\\report \"final\".pdf")).isEqualTo("report final.pdf");
        assertThat(FileNames.sanitize("  notes.txt  ")).isEqualTo("notes.txt");
    }

    @Test
    void emptyOrDotNamesBecomeFile() {
        assertThat(FileNames.sanitize(null)).isEqualTo("file");
        assertThat(FileNames.sanitize("..")).isEqualTo("file");
        assertThat(FileNames.sanitize("dir/")).isEqualTo("file");
    }

    @Test
    void longNamesAreCutKeepingTheExtension() {
        String name = FileNames.sanitize("a".repeat(300) + ".pdf");
        assertThat(name).hasSize(255).endsWith(".pdf");
    }

    @Test
    void contentTypesAreValidatedAndStrippedOfParameters() {
        assertThat(FileNames.contentType("text/html; charset=utf-8")).isEqualTo("text/html");
        assertThat(FileNames.contentType("Application/PDF")).isEqualTo("application/pdf");
        assertThat(FileNames.contentType("not a type")).isEqualTo("application/octet-stream");
        assertThat(FileNames.contentType(null)).isEqualTo("application/octet-stream");
    }
}
