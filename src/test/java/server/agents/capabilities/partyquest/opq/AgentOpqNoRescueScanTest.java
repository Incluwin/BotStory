package server.agents.capabilities.partyquest.opq;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P6h OPQ step 1 (C8): OPQ claims no rescue or assist, and in a companion run there must be none (DECISIONS IN FORCE:
 * rescues and assists off in companion runs). The OPQ sources, comments stripped, contain no staging, no kill with
 * drops, no event registration, no removal of a ground item, no item grant and no loot vacuum anywhere; a map change
 * and a team warp only in the GM harness (the fixture's return to Wonky before the run, and a companion run's
 * checkpoint).
 */
class AgentOpqNoRescueScanTest {
    private static final Path OQ = Path.of("src/main/java/server/agents/capabilities/partyquest/opq");
    private static final List<String> NOWHERE = List.of(
            "stagePosition(", "killMonsterWithDrops(", "registerPlayer(", "removeItemDrop(",
            "inventory().addItem(", "lootNearby(");
    private static final List<String> HARNESS_ONLY = List.of("changeMap", "warpEventTeam");
    private static final String HARNESS = "AgentOpqTestService.java";

    @Test
    void noRescuePrimitiveAnywhereAndNoMapChangeOutsideTheHarness() throws Exception {
        Map<String, String> sources = sources();
        assertTrue(sources.size() >= 15, "the scan read the OPQ package: " + sources.keySet());
        List<String> found = new ArrayList<>();
        sources.forEach((file, code) -> {
            for (String token : NOWHERE) if (code.contains(token)) found.add(file + " " + token);
            if (!file.equals(HARNESS)) {
                for (String token : HARNESS_ONLY) if (code.contains(token)) found.add(file + " " + token);
            }
        });
        assertEquals(List.of(), found);
    }

    @Test
    void theScanSeesCodeAndNotComments() throws Exception {
        Map<String, String> sources = sources();
        // The harness really calls both (the fixture's return to Wonky, the checkpoint's team warp) ...
        assertTrue(sources.get(HARNESS).contains(".changeMapNear("));
        assertTrue(sources.get(HARNESS).contains("event.warpEventTeam("));
        // ... and the coordinator's class comment names changeMap as something it never does.
        String raw = Files.readString(OQ.resolve("AgentOpqCoordinator.java"));
        assertTrue(raw.contains("changeMap"));
        assertFalse(sources.get("AgentOpqCoordinator.java").contains("changeMap"));
    }

    private static Map<String, String> sources() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        try (var files = Files.list(OQ)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                String source = Files.readString(file).replace("\r\n", "\n");
                assertFalse(source.contains("\"\"\""), file + ": the scan does not read text blocks");
                sources.put(file.getFileName().toString(), stripComments(source));
            }
        }
        return sources;
    }

    /** The source with every comment blanked; string and character literals are kept as they are. */
    static String stripComments(String source) {
        StringBuilder code = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                while (i < source.length() && source.charAt(i) != '\n') {
                    code.append(' ');
                    i++;
                }
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2) + 2;
                for (; i < end; i++) code.append(source.charAt(i) == '\n' ? '\n' : ' ');
            } else if (c == '"' || c == '\'') {
                code.append(c);
                i++;
                while (source.charAt(i) != c) {
                    if (source.charAt(i) == '\\') {
                        code.append(source, i, i + 2);
                        i += 2;
                        continue;
                    }
                    code.append(source.charAt(i));
                    i++;
                }
                code.append(c);
                i++;
            } else {
                code.append(c);
                i++;
            }
        }
        return code.toString();
    }
}
