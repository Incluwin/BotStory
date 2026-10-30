package server.agents.capabilities.navigation;

import client.Character;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.movement.AgentClimbStateRuntime;
import server.agents.capabilities.movement.AgentMovementStateRuntime;
import server.agents.capabilities.movement.AgentMovementStateResetService;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.Foothold;
import server.maps.Rope;

import java.awt.Point;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AgentVerticalTraversalServiceTest {
    @Test
    void commitsEntryAndExitAcrossLiveTargetChangesUntilGroundedHandoff() {
        Fixture fixture = fixture();
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);
        Character bot = mock(Character.class);
        Point originalTarget = new Point(180, 100);

        assertTrue(AgentVerticalTraversalService.beginIfRopeEntry(
                fixture.graph, entry, bot, fixture.entryEdge, 3, originalTarget,
                (graph, candidate, startPosition, startRegionId, targetRegionId, targetPosition) -> fixture.exitEdge));

        AgentVerticalTraversalService.TraversalDirective approach = resolve(fixture, entry, bot, 1, true);
        assertSame(fixture.entryEdge, approach.edge());
        assertEquals(originalTarget, approach.targetPosition());
        assertEquals(-1, AgentVerticalTraversalService.committedClimbDirection(entry, 300));
        assertEquals(fixture.exitEdge.startPoint,
                AgentVerticalTraversalService.committedClimbExitPosition(entry));

        AgentClimbStateRuntime.setClimbingOnRope(entry, mock(Rope.class));
        AgentVerticalTraversalService.TraversalDirective climbing = resolve(fixture, entry, bot, 2, true);
        assertSame(fixture.exitEdge, climbing.edge());
        assertEquals(originalTarget, climbing.targetPosition(),
                "a new combat target must not replace the destination during the crossing");

        AgentClimbStateRuntime.setClimbingOnRope(entry, null);
        AgentMovementStateRuntime.setInAir(entry, true);
        assertSame(fixture.exitEdge, resolve(fixture, entry, bot, 3, false).edge());

        AgentMovementStateRuntime.setInAir(entry, false);
        AgentVerticalTraversalService.TraversalDirective grounded = resolve(fixture, entry, bot, 3, false);
        assertTrue(grounded.holdGroundedExit());
        assertTrue(resolve(fixture, entry, bot, 3, false).holdGroundedExit());
        assertNull(resolve(fixture, entry, bot, 3, true));
        assertFalse(AgentVerticalTraversalStateRuntime.active(entry));
        assertNull(AgentVerticalTraversalService.committedClimbExitPosition(entry));
        assertTrue(AgentVerticalTraversalService.blocksRecentInverseEntry(
                fixture.graph, entry, fixture.inverseEntryEdge, System.currentTimeMillis()));
        assertEquals(new Point(132, 100), AgentVerticalTraversalService.recentExitNudgeTarget(
                fixture.graph, entry, 3, -1, null, System.currentTimeMillis()));
        assertNull(AgentVerticalTraversalService.recentExitNudgeTarget(
                fixture.graph, entry, 3, 3, new Point(40, 100), System.currentTimeMillis()),
                "a target on the exit ground farther from the rope than the nudge is walked to instead");
        assertEquals(new Point(132, 100), AgentVerticalTraversalService.recentExitNudgeTarget(
                fixture.graph, entry, 3, 3, new Point(110, 100), System.currentTimeMillis()));
        assertFalse(AgentVerticalTraversalService.blocksRecentInverseEntry(
                fixture.graph, entry, fixture.unrelatedEntryEdge, System.currentTimeMillis()));
    }

    @Test
    void aLandingOnOtherGroundThanTheTargetsHoldsAtTheExitsLanding() {
        // 2026-10-25 live (OPQ walkway 920010200): the rope's top exit lands on a short ledge and the target is on the
        // platform a jump above it. Steered at the target during the hand-off, a background tick's five substeps walked
        // the Agent off the ledge's end before the next AI tick planned the jump, for five minutes.
        Fixture fixture = fixture();
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);
        Character bot = mock(Character.class);
        assertTrue(AgentVerticalTraversalService.beginIfRopeEntry(
                fixture.graph, entry, bot, fixture.entryEdge, 5, new Point(180, 40),
                (graph, candidate, startPosition, startRegionId, targetRegionId, targetPosition) -> fixture.exitEdge));
        AgentClimbStateRuntime.setClimbingOnRope(entry, mock(Rope.class));
        assertSame(fixture.exitEdge, resolve(fixture, entry, bot, 2, true).edge());
        AgentClimbStateRuntime.setClimbingOnRope(entry, null);

        AgentVerticalTraversalService.TraversalDirective landed = resolve(fixture, entry, bot, 3, true);
        assertTrue(landed.holdGroundedExit());
        assertEquals(fixture.exitEdge.endPoint, landed.targetPosition());
        assertEquals(5, landed.targetRegionId());
        assertEquals(fixture.exitEdge.endPoint, resolve(fixture, entry, bot, 3, false).targetPosition());
        assertNull(resolve(fixture, entry, bot, 3, true));
    }

    @Test
    void jumpOffExitNudgesNoCloserToTheRopeThanItsLanding() {
        // A rope jump-off landed 79 px from the rope (610020004). Its traversal completed on the
        // ledge, and the nudge to the rope's side (rope x + 32) walked the Agent back off it.
        Fixture fixture = fixture();
        AgentNavigationGraph.Edge jumpOff = new AgentNavigationGraph.Edge(
                2, 3, AgentNavigationGraph.EdgeType.CLIMB,
                new Point(100, 200), new Point(179, 100), 100, 100, 6, 0, 100, 100, 300, 100);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);
        Character bot = mock(Character.class);
        assertTrue(AgentVerticalTraversalService.beginIfRopeEntry(
                fixture.graph, entry, bot, fixture.entryEdge, 3, new Point(179, 100),
                (graph, candidate, startPosition, startRegionId, targetRegionId, targetPosition) -> jumpOff));
        AgentClimbStateRuntime.setClimbingOnRope(entry, mock(Rope.class));
        assertSame(jumpOff, resolve(fixture, entry, bot, 2, true).edge());
        AgentClimbStateRuntime.setClimbingOnRope(entry, null);

        assertTrue(resolve(fixture, entry, bot, 3, true).holdGroundedExit());
        assertNull(resolve(fixture, entry, bot, 3, true));
        assertEquals(new Point(179, 100), AgentVerticalTraversalService.recentExitNudgeTarget(
                fixture.graph, entry, 3, -1, null, System.currentTimeMillis()));
    }

    @Test
    void caughtDestinationRopeCompletesARopeToRopeExitOnTheNextAiTick() {
        RopeTransfer fixture = ropeTransfer();
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);
        Character bot = mock(Character.class);
        fixture.begin(entry, bot);

        AgentClimbStateRuntime.setClimbingOnRope(entry, fixture.sourceRope);
        assertSame(fixture.transfer, resolve(fixture.graph, entry, bot, 2, true).edge());
        AgentClimbStateRuntime.setClimbingOnRope(entry, fixture.destinationRope);
        assertSame(fixture.transfer, resolve(fixture.graph, entry, bot, 4, false).edge(),
                "an observation tick on the caught rope keeps the transfer");
        assertNull(resolve(fixture.graph, entry, bot, 4, true),
                "the AI tick on the caught rope completes the transfer instead of validating it again");
        assertFalse(AgentVerticalTraversalStateRuntime.active(entry));
        long nowMs = System.currentTimeMillis();
        assertTrue(AgentVerticalTraversalService.blocksRecentInverseEntry(
                fixture.graph, entry, fixture.jumpBack, nowMs));
        assertNull(AgentVerticalTraversalService.recentExitNudgeTarget(fixture.graph, entry, 4, -1, null, nowMs),
                "a rope has no step-off spot to nudge toward");
    }

    @Test
    void sourceRopeResolvedToTheDestinationRegionKeepsTheRopeToRopeExit() {
        // Overlapping ladder pairs resolve by the first rope within grab range, so the top of a
        // source ladder can resolve to the destination ladder (Tornado Corridor 610030011: ladder
        // x 479 at y -130 resolves to the x 480 ladder). The transfer is not done before the jump.
        RopeTransfer fixture = ropeTransfer();
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);
        Character bot = mock(Character.class);
        fixture.begin(entry, bot);
        AgentClimbStateRuntime.setClimbingOnRope(entry, fixture.sourceRope);

        assertSame(fixture.transfer, resolve(fixture.graph, entry, bot, 4, true).edge());
        assertTrue(AgentVerticalTraversalStateRuntime.active(entry));
        assertFalse(AgentVerticalTraversalService.blocksRecentInverseEntry(
                fixture.graph, entry, fixture.jumpBack, System.currentTimeMillis()));
    }

    @Test
    void caughtRopeResolvedToTheSourceRegionCompletesTheRopeToRopeExit() {
        // The reverse case: the whole of ladder x -201 on 300020000 resolves to rope x -200, so the
        // caught ladder reports the source region. Without completion the Agent held on the caught
        // ladder until the attempt timeout charged a failure to the transfer that had worked.
        RopeTransfer fixture = ropeTransfer();
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);
        Character bot = mock(Character.class);
        fixture.begin(entry, bot);
        AgentClimbStateRuntime.setClimbingOnRope(entry, fixture.destinationRope);

        assertNull(resolve(fixture.graph, entry, bot, 2, true));
        assertFalse(AgentVerticalTraversalStateRuntime.active(entry));
        assertTrue(AgentVerticalTraversalService.blocksRecentInverseEntry(
                fixture.graph, entry, fixture.jumpBack, System.currentTimeMillis()));
    }

    @Test
    void anotherRopeInTheExitRopesRegionClimbsTowardTheGroundExit() {
        // Overlapping ropes resolve by the first rope within grab range, so a rope beside the
        // exit's rope can report the exit rope's region (300020000: ladder x -201 resolves to rope
        // x -200). Without the exit anchor the climb held there until the attempt timeout charged
        // a failure to the exit. Climbed to the anchor, the exit runs from the rope beside.
        Fixture fixture = fixture();
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);
        Character bot = mock(Character.class);
        assertTrue(AgentVerticalTraversalService.beginIfRopeEntry(
                fixture.graph, entry, bot, fixture.entryEdge, 3, new Point(180, 100),
                (graph, candidate, startPosition, startRegionId, targetRegionId, targetPosition) -> fixture.exitEdge));
        Rope beside = new Rope(104, 100, 250, true);
        AgentClimbStateRuntime.setClimbingOnRope(entry, beside);

        assertEquals(fixture.exitEdge.startPoint,
                AgentVerticalTraversalService.committedClimbExitPosition(entry, beside));
        assertSame(fixture.exitEdge, resolve(fixture, entry, bot, 2, true).edge());
    }

    @Test
    void navigationResetCancelsTraversalTransaction() {
        Fixture fixture = fixture();
        AgentRuntimeEntry entry = new AgentRuntimeEntry(null, null, null);

        assertTrue(AgentVerticalTraversalService.beginIfRopeEntry(
                fixture.graph, entry, mock(Character.class), fixture.entryEdge, 3, new Point(180, 100),
                (graph, candidate, startPosition, startRegionId, targetRegionId, targetPosition) -> fixture.exitEdge));

        AgentMovementStateResetService.clearNavigationState(entry);

        assertFalse(AgentVerticalTraversalStateRuntime.active(entry));
    }

    private static AgentVerticalTraversalService.TraversalDirective resolve(Fixture fixture,
                                                                             AgentRuntimeEntry entry,
                                                                             Character bot,
                                                                             int currentRegionId,
                                                                             boolean runAiTick) {
        return resolve(fixture.graph, entry, bot, currentRegionId, runAiTick);
    }

    private static AgentVerticalTraversalService.TraversalDirective resolve(AgentNavigationGraph graph,
                                                                             AgentRuntimeEntry entry,
                                                                             Character bot,
                                                                             int currentRegionId,
                                                                             boolean runAiTick) {
        return AgentVerticalTraversalService.resolve(
                graph, entry, bot, currentRegionId, runAiTick,
                (activeGraph, candidate, edge) -> true);
    }

    private static Fixture fixture() {
        AgentNavigationGraph.Region lower = new AgentNavigationGraph.Region(1, List.of(
                new AgentNavigationGraph.Segment(new Foothold(new Point(0, 300), new Point(200, 300), 1))));
        AgentNavigationGraph.Region rope = new AgentNavigationGraph.Region(2, 100, 100, 300, false);
        AgentNavigationGraph.Region upper = new AgentNavigationGraph.Region(3, List.of(
                new AgentNavigationGraph.Segment(new Foothold(new Point(0, 100), new Point(200, 100), 2))));
        AgentNavigationGraph.Edge entryEdge = edge(1, 2, new Point(100, 300), new Point(100, 300));
        AgentNavigationGraph.Edge exitEdge = edge(2, 3, new Point(100, 100), new Point(120, 100));
        AgentNavigationGraph.Edge inverseEntryEdge = edge(3, 2, new Point(100, 100), new Point(100, 100));
        AgentNavigationGraph.Edge unrelatedEntryEdge = edge(1, 2, new Point(100, 300), new Point(100, 300));
        AgentNavigationGraph graph = new AgentNavigationGraph(
                910000300, 1,
                List.of(lower, rope, upper),
                Map.of(1, lower, 2, rope, 3, upper),
                Map.of(),
                Map.of(1, List.of(entryEdge), 2, List.of(exitEdge)),
                Set.of());
        return new Fixture(graph, entryEdge, exitEdge, inverseEntryEdge, unrelatedEntryEdge);
    }

    private static AgentNavigationGraph.Edge edge(int fromRegionId,
                                                  int toRegionId,
                                                  Point start,
                                                  Point end) {
        return new AgentNavigationGraph.Edge(
                fromRegionId, toRegionId, AgentNavigationGraph.EdgeType.CLIMB,
                start, end, start.x, start.x, 0, 0, 100, 100, 300, 100);
    }

    private static RopeTransfer ropeTransfer() {
        AgentNavigationGraph.Region ground = new AgentNavigationGraph.Region(1, List.of(
                new AgentNavigationGraph.Segment(new Foothold(new Point(0, 300), new Point(200, 300), 1))));
        AgentNavigationGraph.Region ropeA = new AgentNavigationGraph.Region(2, 100, 100, 300, false);
        AgentNavigationGraph.Region ropeB = new AgentNavigationGraph.Region(4, 120, 0, 150, false);
        AgentNavigationGraph.Edge entryEdge = edge(1, 2, new Point(100, 300), new Point(100, 300));
        AgentNavigationGraph.Edge transfer = edge(2, 4, new Point(100, 200), new Point(120, 150));
        AgentNavigationGraph.Edge jumpBack = edge(4, 2, new Point(120, 150), new Point(100, 200));
        AgentNavigationGraph graph = new AgentNavigationGraph(
                910000300, 1,
                List.of(ground, ropeA, ropeB),
                Map.of(1, ground, 2, ropeA, 4, ropeB),
                Map.of(),
                Map.of(1, List.of(entryEdge), 2, List.of(transfer), 4, List.of(jumpBack)),
                Set.of());
        return new RopeTransfer(graph, entryEdge, transfer, jumpBack,
                new Rope(100, 100, 300, false), new Rope(120, 0, 150, false));
    }

    private record RopeTransfer(AgentNavigationGraph graph,
                                AgentNavigationGraph.Edge entryEdge,
                                AgentNavigationGraph.Edge transfer,
                                AgentNavigationGraph.Edge jumpBack,
                                Rope sourceRope,
                                Rope destinationRope) {
        void begin(AgentRuntimeEntry entry, Character bot) {
            assertTrue(AgentVerticalTraversalService.beginIfRopeEntry(
                    graph, entry, bot, entryEdge, 4, new Point(120, 50),
                    (activeGraph, candidate, startPosition, startRegionId, targetRegionId, targetPosition) -> transfer));
        }
    }

    private record Fixture(AgentNavigationGraph graph,
                           AgentNavigationGraph.Edge entryEdge,
                           AgentNavigationGraph.Edge exitEdge,
                           AgentNavigationGraph.Edge inverseEntryEdge,
                           AgentNavigationGraph.Edge unrelatedEntryEdge) {
    }
}
