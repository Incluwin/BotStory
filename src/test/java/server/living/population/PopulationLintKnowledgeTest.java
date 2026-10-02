package server.living.population;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import server.knowledge.KnowledgeTestKit;
import server.living.config.ContentLoader;
import server.living.config.LivingContent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The population lint with server knowledge (docs/SERVER_KNOWLEDGE_PLAN.md 9.4): the shipped population has no ERROR and
 * its PQ lobbies are where the PQs start; a lobby in town, an unknown or unfarmable basket good and an instanced training
 * field are reported. Each case edits one file of a copy of the shipped directory.
 */
class PopulationLintKnowledgeTest {
    private static final Path SHIPPED = Path.of("scripts/config/population");
    private static final String ZAKUM_HELMET = "{ itemId: 1002357, price: 25000000 }";

    @Test
    void shippedPopulationHasNoErrorsAndTheLobbiesAreRight() throws Exception {
        List<String> findings = lint(SHIPPED);

        assertTrue(findings.stream().noneMatch(f -> f.startsWith("ERROR")), findings.toString());
        assertTrue(findings.stream().noneMatch(f -> f.contains(" lobby ")), findings.toString());
    }

    @Test
    void aTownLobbyWarnsWithTheRealLobby(@TempDir Path dir) throws Exception {
        Path copy = copyWith(dir, "regions.yaml", "mapId: 200080101", "mapId: 200000000");

        List<String> lobby = lint(copy).stream().filter(f -> f.contains(" lobby ")).toList();

        assertEquals(1, lobby.size(), lobby.toString());
        assertTrue(lobby.get(0).startsWith("WARN regions.yaml orbis lobby opq: the PQ starts at 200080101"), lobby.get(0));
        assertTrue(lobby.get(0).contains("Wonky"), lobby.get(0));
    }

    @Test
    void unknownBasketItemIsAnErrorAndAnUnfarmableOneWarns(@TempDir Path dir) throws Exception {
        Path unknown = copyWith(dir.resolve("unknown"), "market.yaml", ZAKUM_HELMET, "{ itemId: 9999999, price: 1000 }");
        assertTrue(lint(unknown).contains("ERROR market.yaml basket: item 9999999 does not exist in the server WZ"));

        Path wing = copyWith(dir.resolve("wing"), "market.yaml", ZAKUM_HELMET, "{ itemId: 4003003, price: 1000 }");
        List<String> findings = lint(wing).stream().filter(f -> f.startsWith("WARN market.yaml basket:")).toList();
        assertEquals(1, findings.size(), findings.toString());
        assertTrue(findings.get(0).contains("item 4003003 Fairy Wing drops only from"), findings.get(0));
    }

    @Test
    void instancedTrainingFieldWarns(@TempDir Path dir) throws Exception {
        Path copy = copyWith(dir, "fields.yaml", "fields:\n", "fields:\n  - { mapId: 103000800, name: \"Kerning PQ\", "
                + "street: \"\", region: victoria, town: false, mobs: 3, mobLevel: 20, capacity: 5 }\n");

        List<String> findings = lint(copy).stream().filter(f -> f.contains("103000800")).toList();

        assertEquals(1, findings.size(), findings.toString());
        assertTrue(findings.get(0).startsWith("WARN fields.yaml: map 103000800 "), findings.get(0));
        assertTrue(findings.get(0).contains("sends players back to 103000890"), findings.get(0));
    }

    private static List<String> lint(Path population) throws Exception {
        try (AutoCloseable seed = KnowledgeTestKit.publishSeed()) {
            LivingContent living = ContentLoader.load(Path.of("scripts/config/living-world"));
            return PopulationLoader.load(population, living).warnings();
        }
    }

    /** A copy of the shipped population directory with one replacement in one file. */
    private static Path copyWith(Path dir, String file, String from, String to) throws IOException {
        try (Stream<Path> walk = Files.walk(SHIPPED)) {
            for (Path source : walk.toList()) {
                Path target = dir.resolve(SHIPPED.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target);
                }
            }
        }
        Path edited = dir.resolve(file);
        String text = Files.readString(edited, StandardCharsets.US_ASCII);
        assertTrue(text.contains(from), file + " has no '" + from + "'");
        Files.writeString(edited, text.replace(from, to), StandardCharsets.US_ASCII);
        return dir;
    }
}
