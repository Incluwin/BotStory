package server.agents.progression;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The walks back after a death in a boss fight (AgentBossDeathWalk): the world portal graph (worldRepository) walks
 * each one on foot, every hop a portal a player presses. P15, decision 9: from Ludibrium to the Papulatus gate. P17:
 * from Kampung to Aldol's map, to talk back into Scarlion and Targa.
 */
class BossDeathWalkRouteTest {
    @Test
    void ludibriumReachesThePapulatusGateOnFoot() {
        assertEquals(12, hops(220_000_000, 220_080_000));
    }

    @Test
    void kampungReachesAldolsMapOnFoot() {
        assertTrue(hops(551_000_000, 551_030_100) <= 8, "the plan counts 6 hops");
    }

    private static int hops(int from, int to) {
        Set<AgentVictoriaPortalRouteGraph.Edge> standard =
                Set.copyOf(AgentVictoriaPortalRouteGraphRepository.worldRepository().graph().edges());
        StringBuilder path = new StringBuilder().append(from);
        int at = from;
        int hops = 0;
        while (at != to && hops < 40) {
            Integer next = AgentVictoriaTrainingRouteCatalog.nextHop(at, to);
            assertNotNull(next, "no route on from " + at + " (" + path + ")");
            assertTrue(standard.contains(new AgentVictoriaPortalRouteGraph.Edge(at, next))
                    || AgentVictoriaTrainingRouteCatalog.scriptedPortalId(at, next) != null,
                    "no portal to press from " + at + " to " + next);
            at = next;
            hops++;
            path.append(" > ").append(at);
        }
        assertEquals(to, at, path.toString());
        return hops;
    }
}
