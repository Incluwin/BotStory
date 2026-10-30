package server.agents.capabilities.navigation;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.movement.AgentMovementProfile;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A jump off a rope that lands in its platform's last pixels slides off it (it lands with the speed it flew). The Forest
 * of Patience's course B (101000102) climbs ropes whose tops are 56 px platforms: rope x -361 jumped right from y -650
 * landed on -332, r96's end, slid to -328 and fell 840 px to the floor, and the climb looped from its first rope.
 */
class AgentNavigationRopeJumpExitTest {
    private static final int FOREST_OF_PATIENCE_B = 101000102;
    private static final Point SLIDING_JUMP = new Point(-361, -650);

    @Test
    void aRopeJumpThatSlidesOffItsLandingIsNoEdgeAndTheTopStepOffStays() {
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(FOREST_OF_PATIENCE_B);
        AgentNavigationGraph graph = AgentNavigationGraphService.getGraph(map, AgentMovementProfile.base());
        AgentNavigationGraph.Region rope = rope(graph, -361);
        int top = graph.findRegionId(map, new Point(-361, -682));

        assertTrue(AgentNavigationGraphService.slidesOffAfterRopeJump(map, SLIDING_JUMP, 6, AgentMovementProfile.base()));
        assertFalse(graph.getOutgoing(rope.id).stream().anyMatch(edge -> edge.startPoint.equals(SLIDING_JUMP)
                && edge.launchStepX > 0), "the sliding jump right from y -650");
        assertTrue(graph.getOutgoing(rope.id).stream().anyMatch(edge -> edge.toRegionId == top
                && edge.launchStepX == 0), "the top step-off onto r96");
    }

    @Test
    void aCachedGraphLosesItsSlidingRopeJumpsOnLoad() {
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(FOREST_OF_PATIENCE_B);
        AgentNavigationGraph built = AgentNavigationGraphService.getGraph(map, AgentMovementProfile.base());
        AgentNavigationGraph.Region rope = rope(built, -361);
        int top = built.findRegionId(map, new Point(-361, -682));
        Map<Integer, List<AgentNavigationGraph.Edge>> outgoing = new HashMap<>();
        for (AgentNavigationGraph.Region region : built.regions) {
            outgoing.put(region.id, new ArrayList<>(built.getOutgoing(region.id)));
        }
        int kept = outgoing.get(rope.id).size();
        outgoing.get(rope.id).add(new AgentNavigationGraph.Edge(rope.id, top, AgentNavigationGraph.EdgeType.CLIMB,
                SLIDING_JUMP, new Point(-332, -682), -361, -361, 6, 0, -361, -680, -355, 400)); // as v60 built it
        AgentNavigationGraph cached = new AgentNavigationGraph(built.mapId, built.version, built.movementProfile,
                built.regions, built.regionsById, built.regionIdByFootholdId, outgoing, Set.of(),
                built.collidableFromBelowIds);

        assertEquals(1, AgentNavigationGraphService.discardSlidingRopeJumpExits(cached, map));
        assertEquals(kept, cached.getOutgoing(rope.id).size());
    }

    private static AgentNavigationGraph.Region rope(AgentNavigationGraph graph, int x) {
        AgentNavigationGraph.Region rope = graph.regions.stream()
                .filter(region -> region.isRopeRegion && region.minX == x)
                .findFirst().orElse(null);
        assertNotNull(rope, "rope x " + x);
        return rope;
    }
}
