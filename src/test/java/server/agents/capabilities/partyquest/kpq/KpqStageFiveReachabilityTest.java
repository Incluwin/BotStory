package server.agents.capabilities.partyquest.kpq;

import client.Character;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.movement.AgentMovementProfile;
import server.agents.capabilities.movement.AgentMovementStateRuntime;
import server.agents.capabilities.navigation.AgentNavigationGraphService;
import server.agents.capabilities.navigation.AgentNavigationMapLoader;
import server.agents.integration.cosmic.CosmicPrimitiveCapabilityGateway;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.MapleMap;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Stage 5's arrival ledge on the real map (2026-10-01, run pace1b). Everyone arrives at st00 on the left top island;
 * Cloto stands on the right one, 114 px across a gap no jump clears, so the ledge has no way in. Both companions
 * holding the passes stood still below for the whole hand-over, their route to the leader on the ledge empty, until
 * the overdue drop left the passes 2,300 px under him and the event timer ran out.
 */
class KpqStageFiveReachabilityTest {
    private static final Point KING_SLIMES_FLOOR = new Point(162, -451);
    private static final Point ST00 = new Point(-210, -2721);
    private static final Point BY_NELLA = new Point(-100, -2717);
    private static final Point CLOTO_APPROACH = new Point(260, -2717);

    private static MapleMap map;

    @BeforeAll
    static void loadStageFive() {
        map = AgentNavigationMapLoader.loadMapGeometry(AgentKpqDefinition.STAGE_5_MAP);
        AgentNavigationGraphService.getGraph(map, AgentMovementProfile.base());
    }

    @Test
    void nobodyBelowCanReachALeaderOnTheArrivalLedge() {
        assertFalse(canReach(KING_SLIMES_FLOOR, ST00));
        assertFalse(canReach(KING_SLIMES_FLOOR, BY_NELLA));
    }

    @Test
    void clotoIsReachableFromBelowAndFromTheLedge() {
        assertTrue(canReach(KING_SLIMES_FLOOR, CLOTO_APPROACH));
        // The leader's own way: down off the ledge, then up the two ladders.
        assertTrue(canReach(ST00, CLOTO_APPROACH));
    }

    private static boolean canReach(Point from, Point to) {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(4_242);
        when(agent.getMap()).thenReturn(map);
        when(agent.getPosition()).thenReturn(new Point(from));
        AgentRuntimeEntry entry = new AgentRuntimeEntry(agent, mock(Character.class), null);
        AgentMovementStateRuntime.setMovementProfile(entry, AgentMovementProfile.base());
        return CosmicPrimitiveCapabilityGateway.INSTANCE.canReach(entry, agent, to);
    }
}
