package server.agents.progression;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P15, decision 9: a companion that died in the Papulatus fight comes to in Ludibrium and walks back to the gate
 * (AgentBossDeathWalk). The world portal graph (worldRepository) walks it there on foot, every hop a portal a player presses.
 */
class PapulatusGateRouteTest {
    private static final int LUDIBRIUM = 220_000_000;
    private static final int GATE = 220_080_000;

    @Test
    void ludibriumReachesTheGateOnFoot() {
        Set<AgentVictoriaPortalRouteGraph.Edge> standard =
                Set.copyOf(AgentVictoriaPortalRouteGraphRepository.worldRepository().graph().edges());
        StringBuilder path = new StringBuilder().append(LUDIBRIUM);
        int at = LUDIBRIUM;
        int hops = 0;
        while (at != GATE && hops < 40) {
            Integer next = AgentVictoriaTrainingRouteCatalog.nextHop(at, GATE);
            assertNotNull(next, "no route on from " + at + " (" + path + ")");
            assertTrue(standard.contains(new AgentVictoriaPortalRouteGraph.Edge(at, next))
                    || AgentVictoriaTrainingRouteCatalog.scriptedPortalId(at, next) != null,
                    "no portal to press from " + at + " to " + next);
            at = next;
            hops++;
            path.append(" > ").append(at);
        }
        assertEquals(GATE, at, path.toString());
        assertTrue(hops <= 15, "Ludibrium to the gate took " + hops + " hops: " + path);
    }
}
