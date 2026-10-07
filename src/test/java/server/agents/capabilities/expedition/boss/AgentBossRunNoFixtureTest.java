package server.agents.capabilities.expedition.boss;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boss runs adopt the owner's own companions as they are: no fixture rewrite, no failure that unloads them, no GM
 * lobby, and no way out that leaks the instance. The package also never names the living world.
 */
class AgentBossRunNoFixtureTest {
    private static final Path BOSS = Path.of("src", "main", "java", "server", "agents", "capabilities",
            "expedition", "boss");
    private static final List<String> FORBIDDEN = List.of(
            "AgentBalrogTestFixtureService",
            "prepareMember(",
            "exitPlayer(",
            "disconnect(",
            "AgentCombatVariationRuntime",
            "AgentExpeditionLobbyService",
            "server.living");

    @Test
    void bossRunSourcesNeverReachTheFixtureContractTheGmLobbyOrTheLivingWorld() throws Exception {
        assertTrue(Files.isDirectory(BOSS), () -> BOSS + " moved; update this guard");
        List<String> violations = new ArrayList<>();
        int sources = 0;
        try (Stream<Path> files = Files.walk(BOSS)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                sources++;
                String source = Files.readString(file);
                for (String forbidden : FORBIDDEN) {
                    if (source.contains(forbidden)) {
                        violations.add(file.getFileName() + " names " + forbidden);
                    }
                }
            }
        }
        assertTrue(sources >= 8, "the boss-run package lost sources: " + sources);
        assertEquals(List.of(), violations);
    }
}
