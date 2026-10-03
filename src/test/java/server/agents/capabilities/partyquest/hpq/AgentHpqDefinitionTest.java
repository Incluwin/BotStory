package server.agents.capabilities.partyquest.hpq;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentHpqDefinitionTest {
    @Test
    void mapsEverySeedToItsOwnMoonflowerReactor() {
        assertEquals(List.of(4_001_095, 4_001_096, 4_001_097, 4_001_098, 4_001_099, 4_001_100),
                AgentHpqDefinition.seedBeds().stream()
                        .map(AgentHpqDefinition.SeedBed::seedItemId).toList());
        assertEquals(List.of(9_108_000, 9_108_001, 9_108_002, 9_108_003, 9_108_004, 9_108_005),
                AgentHpqDefinition.seedBeds().stream()
                        .map(AgentHpqDefinition.SeedBed::reactorId).toList());
        assertEquals("moonflower1", AgentHpqDefinition.seedBed(4_001_095).reactorName());
        assertTrue(AgentHpqDefinition.isSeed(4_001_100));
        assertThrows(IllegalArgumentException.class,
                () -> AgentHpqDefinition.seedBed(AgentHpqDefinition.RICE_CAKE));
    }

    /** reactordrops: each primrose 9102002..9102007 drops exactly one seed colour, 4001095..4001100 in order. */
    @Test
    void everySeedNamesThePrimroseThatDropsIt() throws Exception {
        String drops = Files.readString(Path.of("src/main/resources/db/data/131-reactordrops-data.sql"));
        Matcher row = Pattern.compile("\\((910200[2-7]), (\\d+), \\d+, -?\\d+\\)").matcher(drops);
        int rows = 0;
        while (row.find()) {
            int reactor = Integer.parseInt(row.group(1));
            int seed = Integer.parseInt(row.group(2));
            assertEquals(reactor, AgentHpqDefinition.sourceReactorFor(seed), "seed " + seed);
            assertTrue(AgentHpqDefinition.SEED_SOURCE_REACTORS.contains(reactor));
            rows++;
        }
        assertEquals(6, rows);
        assertEquals(9_102_002, AgentHpqDefinition.sourceReactorFor(4_001_095));
        assertEquals(9_102_007, AgentHpqDefinition.sourceReactorFor(4_001_100));
        assertThrows(IllegalArgumentException.class,
                () -> AgentHpqDefinition.sourceReactorFor(AgentHpqDefinition.RICE_CAKE));
    }
}
