package server.living.roster;

import config.YamlConfig;
import org.junit.jupiter.api.Test;
import server.knowledge.KnowledgeTestKit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Travel times off the Victoria roads (docs/SERVER_KNOWLEDGE_PLAN.md 8.6): a trip server knowledge walks with no ride
 * (Orbis to El Nath) gets its town hops and decision 7's same-continent cap; a ship trip stays off-island; Victoria is as
 * before; without knowledge everything off the roads is off-island.
 */
class RecallTravelKnowledgeTest {
    private static final int ORBIS = 200000000;
    private static final int EL_NATH = 211000000;
    private static final int LUDIBRIUM = 220000000;
    private static final long OFF_ISLAND_MS = 180_000L;

    @Test
    void ossyriaOnFootIsAWalkAndAShipIsNot() throws Exception {
        try (AutoCloseable seed = KnowledgeTestKit.publishSeed()) {
            long walk = RecallTravel.etaMs(ORBIS, EL_NATH);
            assertTrue(walk < OFF_ISLAND_MS, "Orbis -> El Nath " + walk);
            assertEquals(60_000L, RecallTravel.etaMs(ORBIS, EL_NATH, 60_000L), "decision 7's same-continent cap");
            assertEquals(OFF_ISLAND_MS, RecallTravel.etaMs(ORBIS, LUDIBRIUM), "a ship ride");
            assertEquals(OFF_ISLAND_MS, RecallTravel.etaMs(ORBIS, LUDIBRIUM, 60_000L), "no cap off-island");
            assertEquals(60_000L, RecallTravel.etaMs(104000000, 100000000), "Victoria as in RecallTravelTest");
            assertEquals(40_000L, RecallTravel.etaMs(104000000, 104040000));
        }
    }

    @Test
    void withoutKnowledgeOffTheRoadsIsOffIsland() throws Exception {
        assertEquals(OFF_ISLAND_MS, RecallTravel.etaMs(ORBIS, EL_NATH), "nothing published");
        assertEquals(OFF_ISLAND_MS, RecallTravel.etaMs(ORBIS, EL_NATH, 60_000L));
        boolean decisions = YamlConfig.config.knowledge.DECISIONS;
        try (AutoCloseable seed = KnowledgeTestKit.publishSeed()) {
            YamlConfig.config.knowledge.DECISIONS = false;
            assertEquals(OFF_ISLAND_MS, RecallTravel.etaMs(ORBIS, EL_NATH), "DECISIONS off");
        } finally {
            YamlConfig.config.knowledge.DECISIONS = decisions;
        }
    }
}
