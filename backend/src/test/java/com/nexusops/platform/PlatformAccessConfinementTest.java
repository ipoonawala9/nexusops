package com.nexusops.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Spec §4.2: only the platform module may turn on cross-tenant visibility, and only through PlatformAccess. */
class PlatformAccessConfinementTest {

    private static final Path MAIN = Path.of("src/main/java");

    @Test
    void onlyThePlatformModuleMentionsPlatformAccess() throws IOException {
        assertThat(filesContaining("app.platform_access"))
                .allMatch(path -> path.contains("com/nexusops/platform/"), "lives in com/nexusops/platform/");
    }

    @Test
    void onlyPlatformAccessSetsTheFlag() throws IOException {
        assertThat(filesContaining("set_config('app.platform_access'"))
                .containsExactly("src/main/java/com/nexusops/platform/internal/PlatformAccess.java");
    }

    private static List<String> filesContaining(String needle) throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> read(p).contains(needle))
                    .map(p -> p.toString().replace('\\', '/'))
                    .toList();
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
