package server.agents.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The Agent engine never reaches into the living world: server.living builds on server.agents (hosts install rules
 * and hooks), never the other way round, so no server.agents source names a server.living type.
 */
class AgentLivingImportBoundaryTest {
    private static final Path AGENTS = Path.of("src", "main", "java", "server", "agents");
    private static final Pattern LIVING = Pattern.compile("\\bserver\\.living\\.");

    @Test
    void noAgentSourceNamesTheLivingWorld() throws Exception {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(AGENTS)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String[] lines = Files.readString(file).split("\n");
                for (int index = 0; index < lines.length; index++) {
                    if (LIVING.matcher(lines[index]).find()) {
                        violations.add(file.toString().replace('\\', '/') + ":" + (index + 1));
                    }
                }
            }
        }
        assertEquals(List.of(), violations);
    }
}
