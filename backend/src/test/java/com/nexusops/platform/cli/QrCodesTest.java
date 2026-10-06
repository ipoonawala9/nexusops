package com.nexusops.platform.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class QrCodesTest {

    @Test
    void rendersASquareBlackOnWhiteBlockOfHalfBlocks() {
        List<String> lines = QrCodes.render("otpauth://totp/NexusOps:ops%40nexusops.test?secret=GEZDGNBV").lines().toList();
        assertThat(lines).hasSizeGreaterThan(10);
        assertThat(lines).allSatisfy(line -> assertThat(line).startsWith("\u001b[30;47m").endsWith("\u001b[0m"));
        assertThat(lines.stream().map(String::length).distinct()).hasSize(1);
        assertThat(String.join("", lines)).contains("█");
    }
}
