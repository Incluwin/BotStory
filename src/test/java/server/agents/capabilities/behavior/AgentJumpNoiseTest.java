package server.agents.capabilities.behavior;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.navigation.AgentNavigationGraph;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.state.AgentCapabilityStateRegistry;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * The jump-quest noise seam (AgentJumpNoise, docs/BOT_CONCEPT_PLAN.md F5b) on its own: with no plan every jump is
 * today's; a drawn jump that comes down well below its launch is one miss, a landing on its target is none; the plan is
 * asked about a fall below the map only while it is installed; one seed picks the same launches. The draws on real maps
 * are JumpQuestRoutesTest's.
 */
class AgentJumpNoiseTest {
    private final AgentRuntimeEntry entry = mock(AgentRuntimeEntry.class);
    private final AgentNavigationGraph graph = mock(AgentNavigationGraph.class);
    private final AgentNavigationGraph.Edge edge = edge(AgentNavigationGraph.EdgeType.JUMP);
    private MockedStatic<AgentRuntimeIdentityRuntime> identity;
    private int belowMapAsked;

    @BeforeEach
    void setUp() {
        when(entry.capabilityStates()).thenReturn(new AgentCapabilityStateRegistry());
        AgentNavigationGraph.Region from = mock(AgentNavigationGraph.Region.class);
        when(graph.getRegion(anyInt())).thenReturn(from);
        identity = mockStatic(AgentRuntimeIdentityRuntime.class);
    }

    @AfterEach
    void tearDown() {
        identity.close();
    }

    private AgentJumpNoise.Plan plan(double sigma) {
        return new AgentJumpNoise.Plan() {
            @Override
            public double sigmaAt(int launchY) {
                return sigma;
            }

            @Override
            public boolean belowMap(Character agent) {
                belowMapAsked++;
                return true;
            }
        };
    }

    @Test
    void withNoPlanEveryJumpIsTodays() {
        assertFalse(AgentJumpNoise.active(entry));
        assertEquals(104, AgentJumpNoise.launchX(entry, graph, edge, 104));
        assertNull(AgentJumpNoise.readyAtDraw(entry, edge, new Point(104, 200)));
        assertFalse(AgentJumpNoise.drawn(entry, edge, 104));
        AgentJumpNoise.launched(entry, edge, new Point(104, 200));
        AgentJumpNoise.landed(entry, new Point(104, 400));
        assertEquals(new AgentJumpNoise.Counts(0, 0, 0), AgentJumpNoise.counts(entry));
        assertFalse(AgentJumpNoise.belowMap(entry, mock(Character.class)), "the recovery teleport runs as always");
        assertEquals(0, belowMapAsked);
    }

    @Test
    void aDrawnJumpThatComesDownWellBelowItsLaunchIsOneMiss() {
        AgentJumpNoise.install(entry, plan(0.0d), 7L); // exact presses: the draw is the pick itself
        assertTrue(AgentJumpNoise.active(entry));
        assertEquals(104, AgentJumpNoise.launchX(entry, graph, edge, 104));
        assertTrue(AgentJumpNoise.drawn(entry, edge, 104), "navigation's cache keeps the drawn launch");
        assertNull(AgentJumpNoise.readyAtDraw(entry, edge, new Point(104, 200)), "inside the window: navigation decides");
        AgentJumpNoise.launched(entry, edge, new Point(104, 200));
        AgentJumpNoise.landed(entry, new Point(150, 140)); // up on the next platform
        assertEquals(new AgentJumpNoise.Counts(1, 0, 0), AgentJumpNoise.counts(entry));
        AgentJumpNoise.launchX(entry, graph, edge, 104); // the next try at the edge draws again
        AgentJumpNoise.launched(entry, edge, new Point(104, 200));
        AgentJumpNoise.landed(entry, new Point(130, 200 + AgentJumpNoise.MISS_DROP_PX + 30)); // down a platform
        assertEquals(new AgentJumpNoise.Counts(2, 1, 0), AgentJumpNoise.counts(entry));
        AgentJumpNoise.landed(entry, new Point(130, 600)); // a landing after no drawn launch counts nothing
        assertEquals(1, AgentJumpNoise.counts(entry).misses());
    }

    /** Forest of Patience B (101000102) jumps 360-420 px down off each rope's top: nine of them counted as falls. */
    @Test
    void aJumpPlannedDownIsAMissOnlyBelowItsPlannedLanding() {
        AgentNavigationGraph.Edge down = new AgentNavigationGraph.Edge(1, 2, AgentNavigationGraph.EdgeType.JUMP,
                new Point(100, 200), new Point(160, 620), 90, 110, 6, -1, 0, 0, 0, 10);
        AgentJumpNoise.install(entry, plan(0.0d), 7L);
        AgentJumpNoise.launchX(entry, graph, down, 104);
        AgentJumpNoise.launched(entry, down, new Point(104, 200));
        AgentJumpNoise.landed(entry, new Point(160, 620)); // where it was meant to land
        assertEquals(new AgentJumpNoise.Counts(1, 0, 0), AgentJumpNoise.counts(entry));
        AgentJumpNoise.launchX(entry, graph, down, 104);
        AgentJumpNoise.launched(entry, down, new Point(104, 200));
        AgentJumpNoise.landed(entry, new Point(170, 620 + AgentJumpNoise.MISS_DROP_PX + 30)); // past it, a platform down
        assertEquals(new AgentJumpNoise.Counts(2, 1, 0), AgentJumpNoise.counts(entry));
    }

    @Test
    void theAttemptsHostDecidesAFallBelowTheMapOnlyWhileItsPlanIsOn() {
        AgentJumpNoise.install(entry, plan(5.0d), 7L);
        assertTrue(AgentJumpNoise.belowMap(entry, mock(Character.class)));
        assertEquals(1, belowMapAsked);
        AgentJumpNoise.clear(entry);
        assertFalse(AgentJumpNoise.active(entry));
        assertFalse(AgentJumpNoise.belowMap(entry, mock(Character.class)));
        assertEquals(1, belowMapAsked);
        assertEquals(104, AgentJumpNoise.launchX(entry, graph, edge, 104), "cleared: exact again");
    }

    @Test
    void oneSeedPicksTheSameLaunchesSoAClimbReplays() {
        AgentRuntimeEntry other = mock(AgentRuntimeEntry.class);
        when(other.capabilityStates()).thenReturn(new AgentCapabilityStateRegistry());
        AgentJumpNoise.install(entry, plan(5.0d), 43L);
        AgentJumpNoise.install(other, plan(5.0d), 43L);
        for (int i = 0; i < 50; i++) {
            int pick = AgentJumpNoise.pickLaunchX(entry, 90, 110);
            assertEquals(pick, AgentJumpNoise.pickLaunchX(other, 90, 110), "pick " + i);
            assertTrue(pick >= 90 && pick <= 110);
        }
        AgentJumpNoise.clear(entry);
        for (int i = 0; i < 50; i++) {
            int pick = AgentJumpNoise.pickLaunchX(entry, 90, 110); // no plan: any x in the window, as always
            assertTrue(pick >= 90 && pick <= 110);
        }
    }

    @Test
    void dropsAndOtherEdgesGetNoNoise() {
        AgentJumpNoise.install(entry, plan(30.0d), 7L);
        assertEquals(104, AgentJumpNoise.launchX(entry, graph, edge(AgentNavigationGraph.EdgeType.DROP), 104));
        assertEquals(104, AgentJumpNoise.launchX(entry, graph, edge(AgentNavigationGraph.EdgeType.CLIMB), 104));
    }

    /** An edge from region 1 to 2 taking off at (100, 200), its launch window 90..110. */
    private static AgentNavigationGraph.Edge edge(AgentNavigationGraph.EdgeType type) {
        return new AgentNavigationGraph.Edge(1, 2, type, new Point(100, 200), new Point(160, 140), 90, 110, 6, -1, 0, 0,
                0, 10);
    }
}
