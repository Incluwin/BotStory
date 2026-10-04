package server.agents.progression;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The standard portals of the continents outside Victoria Island, walked like Victoria's. */
class AgentWorldPortalRouteGraphTest {
    @Test
    void walksFromEachPartyQuestDeskToItsTownPotionShop() {
        // LPQ: Eos Tower 101st floor -> 100th floor -> Eos Tower Entrance -> Ludibrium Village -> Ludibrium -> Pharmacy.
        assertEquals(5, AgentVictoriaTrainingRouteCatalog.distance(221024500, 220000002));
        assertEquals(221024400, AgentVictoriaTrainingRouteCatalog.nextHop(221024500, 220000002));
        // LMPQ: Ludibrium -> Ludibrium Pharmacy.
        assertEquals(1, AgentVictoriaTrainingRouteCatalog.distance(220000000, 220000002));
        // OPQ: The Unknown Tower -> Entrance to Orbis Tower -> Orbis -> Orbis Department Store.
        assertEquals(3, AgentVictoriaTrainingRouteCatalog.distance(200080101, 200000002));
        // EPQ: Deep Fairy Forest -> Eastern Region of Mossy Tree Forest -> Altaire Camp.
        assertEquals(2, AgentVictoriaTrainingRouteCatalog.distance(300030100, 300000000));
        // PPQ: Over the Pirate Ship, back through the herb gardens to Herb Town.
        assertEquals(7, AgentVictoriaTrainingRouteCatalog.distance(251010404, 251000000));
    }

    @Test
    void walksTheRoadsThatJoinOssyriasTowns() {
        assertTrue(AgentVictoriaTrainingRouteCatalog.canRoute(200000000, 211000000), "Orbis Tower down to El Nath");
        assertTrue(AgentVictoriaTrainingRouteCatalog.canRoute(211000000, 200000000), "El Nath up to Orbis");
        assertTrue(AgentVictoriaTrainingRouteCatalog.canRoute(200000000, 230000000), "Orbis Tower down to Aquarium");
        assertTrue(AgentVictoriaTrainingRouteCatalog.canRoute(220000000, 221000000), "Ludibrium to Omega Sector");
        assertTrue(AgentVictoriaTrainingRouteCatalog.canRoute(250000000, 251000000), "Mu Lung to Herb Town");
        assertTrue(AgentVictoriaTrainingRouteCatalog.canRoute(260000000, 261000000), "Ariant to Magatia");
    }

    @Test
    void continentsAreNotJoinedByWalking() {
        // Victoria and Ossyria meet only by ship: no portal leads across.
        assertEquals(-1, AgentVictoriaTrainingRouteCatalog.distance(100000000, 200000000));
        assertEquals(-1, AgentVictoriaTrainingRouteCatalog.distance(200000000, 101000000));
        // Orbis and Ludibrium are joined by the train, and Ludibrium and Korean Folk Town by the Helios Tower lift.
        assertFalse(AgentVictoriaTrainingRouteCatalog.canRoute(200000000, 220000000));
        assertFalse(AgentVictoriaTrainingRouteCatalog.canRoute(222000000, 220000000));
        // The ferry ride maps are never walked into or out of.
        assertEquals(-1, AgentVictoriaTrainingRouteCatalog.distance(200090030, 101000000));
    }

    @Test
    void victoriaKeepsItsOwnGraph() {
        assertEquals(626, AgentVictoriaPortalRouteGraphRepository.defaultRepository().graph().edges().size());
        for (AgentVictoriaPortalRouteGraph.Edge edge
                : AgentVictoriaPortalRouteGraphRepository.worldRepository().graph().edges()) {
            assertFalse(edge.fromMapId() >= 100000000 && edge.fromMapId() < 130000000, edge::toString);
            assertFalse(edge.toMapId() >= 100000000 && edge.toMapId() < 130000000, edge::toString);
        }
    }
}
