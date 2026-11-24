package server.agents.diagnostics.arena;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** adopt=off (docs/WORLD_SYSTEMS_PLAN.md I1.1): a comparison writes its readings and adopts nothing. */
class ArenaComparisonTest {
    @Test
    void adoptingStaysTheDefaultWithTheOldPairsAndLengths() {
        ArenaOptions options = ArenaOptions.parse(new String[]{"a", "policy=candidate"});
        assertTrue(options.adopt());
        assertEquals(AgentCombatArenaService.LEARN_EVAL_REPEATS, options.pairs());
        assertEquals(AgentCombatArenaService.LEARN_EVAL_SECONDS, options.evalSeconds());
    }

    @Test
    void aComparisonNamesItsPairsAndLength() {
        ArenaOptions options = ArenaOptions.parse(
                new String[]{"a", "policy=candidate", "adopt=off", "pairs=8", "evalseconds=20"});
        assertFalse(options.adopt());
        assertEquals(8, options.pairs());
        assertEquals(20, options.evalSeconds());
        assertFalse(ArenaOptions.parse(new String[]{"a", "policy=learn", "adopt=off"}).adopt());
    }

    @Test
    void aTournamentCannotBeAComparison() {
        assertThrows(IllegalArgumentException.class, () -> ArenaOptions.parse(new String[]{"a", "adopt=off"}));
        assertThrows(IllegalArgumentException.class,
                () -> ArenaOptions.parse(new String[]{"a", "policy=candidate", "pairs=1"}));
        assertThrows(IllegalArgumentException.class,
                () -> ArenaOptions.parse(new String[]{"a", "policy=candidate", "adopt=maybe"}));
    }

    @Test
    void readingsAreAppendedOneLineEach(@TempDir Path folder) throws Exception {
        Path file = folder.resolve("logs").resolve("c.jsonl");
        ArenaComparisonLog.Line line = new ArenaComparisonLog.Line("now", 111, "CRUSADER", 100, "a", "single", 20, 300,
                false, 0, List.of(10.0, 11.0), List.of(9.0, 12.0), "sword");
        ArenaComparisonLog.append(file, line);
        ArenaComparisonLog.append(file, line);

        List<String> lines = Files.readAllLines(file);
        assertEquals(2, lines.size());
        assertTrue(lines.getFirst().contains("\"candidateDps\":[10.0,11.0],\"liveDps\":[9.0,12.0]"));
    }
}
