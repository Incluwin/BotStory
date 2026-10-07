package server.maps;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Keeps foothold geometry out of Mockito. The inline mock maker instruments a mocked class for the rest of
 * the test JVM, and navigation graph builds call Foothold and FootholdTree getters millions of times: one
 * mock(Foothold.class) made every later graph build in the suite 11-15x slower (10-20 s became 150-220 s).
 * Both classes are plain value objects; build real ones instead.
 */
class FootholdMockingGuardTest {
    private static final Path TEST_SOURCES = Path.of("src", "test", "java");
    private static final List<Pattern> MOCKED_FOOTHOLD = List.of(
            Pattern.compile("(?:mock|spy)\\(\\s*(?:server\\.maps\\.)?Foothold(?:Tree)?\\.class"),
            Pattern.compile("spy\\(\\s*new\\s+(?:server\\.maps\\.)?Foothold(?:Tree)?\\("),
            Pattern.compile("@(?:Mock|Spy)\\b[^;=]*\\bFoothold(?:Tree)?\\s+\\w+\\s*;"));

    @Test
    void testsBuildRealFootholdsInsteadOfMockingThem() throws IOException {
        Path self = TEST_SOURCES.resolve(Path.of("server", "maps", "FootholdMockingGuardTest.java"));
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(TEST_SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (file.equals(self)) {
                    continue;
                }
                String source = Files.readString(file);
                if (MOCKED_FOOTHOLD.stream().anyMatch(pattern -> pattern.matcher(source).find())) {
                    offenders.add(file.toString());
                }
            }
        }
        assertEquals(List.of(), offenders,
                "mocking Foothold/FootholdTree slows every later navigation graph build in the suite; use real objects");
    }
}
