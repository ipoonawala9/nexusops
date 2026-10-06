package com.nexusops.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Spec §4.2: only the platform module may turn on cross-tenant visibility, and only through PlatformAccess.
 * TenantAwareDataSource may mention the flag only to clear it on every connection checkout (ADR-0007).
 */
class PlatformAccessConfinementTest {

    private static final Path MAIN_JAVA = Path.of("src/main/java");
    private static final Path MAIN_RESOURCES = Path.of("src/main/resources");
    private static final String FLAG = "app.platform_access";
    private static final String PLATFORM_ACCESS = "src/main/java/com/nexusops/platform/internal/PlatformAccess.java";
    private static final String DATA_SOURCE = "src/main/java/com/nexusops/shared/db/TenantAwareDataSource.java";
    /** The flag name followed closely by an 'on' value: set_config(..., 'on', ...), SET ... = 'on' / TO on. */
    private static final Pattern TURNS_ON =
            Pattern.compile("(?is)app\\.platform_access.{0,40}?('on'|\\bto\\s+on\\b|=\\s*on\\b)");

    @Test
    void onlyPlatformAccessAndTheDataSourceMentionTheFlag() throws IOException {
        assertThat(filesContaining(MAIN_JAVA, FLAG)).containsExactlyInAnyOrder(PLATFORM_ACCESS, DATA_SOURCE);
    }

    @Test
    void onlyPlatformAccessTurnsTheFlagOn() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN_JAVA)) {
            List<String> turningOn = files.filter(Files::isRegularFile)
                    .filter(p -> TURNS_ON.matcher(read(p)).find())
                    .map(PlatformAccessConfinementTest::name)
                    .toList();
            assertThat(turningOn).containsExactly(PLATFORM_ACCESS);
        }
        assertThat(read(Path.of(DATA_SOURCE))).doesNotContain("'on'")
                .contains("set_config('app.platform_access', '', false)");
    }

    @Test
    void amongResourcesOnlyV7MentionsTheFlag() throws IOException {
        assertThat(filesContaining(MAIN_RESOURCES, FLAG))
                .containsExactly("src/main/resources/db/migration/V7__platform.sql");
    }

    private static List<String> filesContaining(Path root, String needle) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> read(p).contains(needle))
                    .map(PlatformAccessConfinementTest::name)
                    .toList();
        }
    }

    private static String name(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (MalformedInputException e) {
            return ""; // binary resource
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
