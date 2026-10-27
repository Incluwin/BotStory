package server.agents.capabilities.navigation;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.behavior.AgentTellProbe;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.runtime.AgentRuntimeEntry;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The host-driven walk a citizen someone can see makes to another map (docs/CITIZEN_PORTAL_TRAVEL_PLAN.md 2.3): it walks
 * through the portals while travelTo moves it, and on the target map it is there, walks up to an NPC, or walks to a
 * portal and goes through it. It ends ARRIVED, NO_ROUTE or STALLED, and never walks on a tick of another runtime.
 */
class AgentMapWalkTest {
    private static final int AGENT = 77;
    private static final int HENESYS = 100_000_000;
    private static final int FIELD = 100_010_000;
    private static final int STATION = 101_000_300;
    private static final int CHERRY = 1_032_008;
    private static final int PORTAL = 21;

    private final PrimitiveCapabilityGateway gateway = mock(PrimitiveCapabilityGateway.class);
    private MockedStatic<AgentPrimitiveCapabilityGatewayRuntime> gateways;
    private Character agent;
    private AgentRuntimeEntry entry;

    @BeforeEach
    void setUp() {
        gateways = mockStatic(AgentPrimitiveCapabilityGatewayRuntime.class);
        gateways.when(AgentPrimitiveCapabilityGatewayRuntime::gateway).thenReturn(gateway);
        when(gateway.travelTo(any(), any(), anyInt(), anyLong()))
                .thenReturn(new AgentRouteOutcome(AgentRouteStatus.MOVING, HENESYS, FIELD, FIELD, false));
        agent = mock(Character.class);
        when(agent.getId()).thenReturn(AGENT);
        when(agent.isAlive()).thenReturn(true);
        standOn(HENESYS, new Point(0, 0));
        entry = new AgentRuntimeEntry(agent, null, null);
    }

    @AfterEach
    void tearDown() {
        AgentMapWalk.resetForTest();
        gateways.close();
    }

    @Test
    void itWalksWhileTravelMovesItAndArrivesOnTheTargetMap() {
        AgentMapWalk.Walk walk = AgentMapWalk.start(entry, agent, AgentMapWalk.Target.map(FIELD), 60_000L, 1_000L);

        assertTrue(AgentMapWalk.tick(entry, agent, 1_100L), "the movement step makes the walk's move");
        verify(gateway).travelTo(entry, agent, FIELD, 1_100L);
        assertEquals(AgentMapWalk.Status.WALKING, walk.status());
        assertTrue(AgentMapWalk.walking(AGENT));

        standOn(FIELD, new Point(-500, 200)); // the portal took it over
        assertFalse(AgentMapWalk.tick(entry, agent, 2_000L));
        assertEquals(AgentMapWalk.Status.ARRIVED, walk.status());
        assertFalse(AgentMapWalk.walking(AGENT));
        assertFalse(AgentMapWalk.tick(entry, agent, 2_100L), "an ended walk does nothing");
    }

    @Test
    void onItsTargetMapItWalksUpToTheNpcAndStopsBesideIt() {
        standOn(STATION, new Point(0, 0));
        when(gateway.npcPosition(agent, CHERRY)).thenReturn(new Point(400, 0));
        AgentMapWalk.Walk walk = AgentMapWalk.start(entry, agent, AgentMapWalk.Target.npc(STATION, CHERRY), 60_000L, 0L);

        assertTrue(AgentMapWalk.tick(entry, agent, 100L));
        verify(gateway).navigate(entry, new Point(400, 0), true);

        when(agent.getPosition()).thenReturn(new Point(330, 0)); // within NPC_REACH_PX (100)
        assertFalse(AgentMapWalk.tick(entry, agent, 200L));
        assertEquals(AgentMapWalk.Status.ARRIVED, walk.status());
        verify(gateway).stop(entry);
        verify(gateway).facePosition(agent, new Point(400, 0));
        verify(gateway, never()).travelTo(any(), any(), anyInt(), anyLong());
    }

    @Test
    void anEnterTargetGoesThroughThePortalOnceItStandsAtIt() {
        when(gateway.portalPosition(agent, PORTAL)).thenReturn(new Point(30, 0)); // within PORTAL_REACH_PX (45)
        when(gateway.enterPortal(agent, PORTAL)).thenReturn(true);
        AgentMapWalk.Walk walk = AgentMapWalk.start(entry, agent, AgentMapWalk.Target.enter(HENESYS, PORTAL), 60_000L, 0L);

        assertFalse(AgentMapWalk.tick(entry, agent, 100L));

        verify(gateway).enterPortal(agent, PORTAL);
        assertEquals(AgentMapWalk.Status.ARRIVED, walk.status());
    }

    @Test
    void aPortalThatRefusesItEndsTheWalkWithNoRoute() {
        when(gateway.portalPosition(agent, PORTAL)).thenReturn(new Point(30, 0));
        when(gateway.enterPortal(agent, PORTAL)).thenReturn(false);
        AgentMapWalk.Walk walk = AgentMapWalk.start(entry, agent, AgentMapWalk.Target.enter(HENESYS, PORTAL), 60_000L, 0L);

        assertFalse(AgentMapWalk.tick(entry, agent, 100L));

        assertEquals(AgentMapWalk.Status.NO_ROUTE, walk.status());
    }

    @Test
    void aReachTargetStandsAtThePortalWithoutGoingThrough() {
        when(gateway.portalPosition(agent, PORTAL)).thenReturn(new Point(300, 0));
        AgentMapWalk.Walk walk = AgentMapWalk.start(entry, agent, AgentMapWalk.Target.reach(HENESYS, PORTAL), 60_000L, 0L);

        assertTrue(AgentMapWalk.tick(entry, agent, 100L));
        verify(gateway).navigate(entry, new Point(300, 0), true);
        when(agent.getPosition()).thenReturn(new Point(290, 0));
        assertFalse(AgentMapWalk.tick(entry, agent, 200L));

        assertEquals(AgentMapWalk.Status.ARRIVED, walk.status());
        verify(gateway, never()).enterPortal(any(), anyInt());
    }

    @Test
    void noRouteEndsTheWalk() {
        when(gateway.travelTo(any(), any(), anyInt(), anyLong()))
                .thenReturn(AgentRouteOutcome.unavailable(HENESYS, FIELD));
        AgentMapWalk.Walk walk = AgentMapWalk.start(entry, agent, AgentMapWalk.Target.map(FIELD), 60_000L, 0L);

        assertFalse(AgentMapWalk.tick(entry, agent, 100L));

        assertEquals(AgentMapWalk.Status.NO_ROUTE, walk.status());
    }

    @Test
    void aWalkThatStandsOnOneMapPastItsHopTimeoutStalls() {
        AgentMapWalk.Walk walk = AgentMapWalk.start(entry, agent, AgentMapWalk.Target.map(FIELD), 10_000L, 0L);
        assertTrue(AgentMapWalk.tick(entry, agent, 9_000L));

        standOn(100_020_000, new Point(0, 0)); // it crossed a map: the hop clock starts again
        assertTrue(AgentMapWalk.tick(entry, agent, 12_000L));
        assertTrue(AgentMapWalk.tick(entry, agent, 21_000L));
        assertFalse(AgentMapWalk.tick(entry, agent, 22_000L));

        assertEquals(AgentMapWalk.Status.STALLED, walk.status());
        verify(gateway).stop(entry);
    }

    @Test
    void aDownedWalkerWaitsAndItsTimeDownDoesNotCount() {
        AgentMapWalk.Walk walk = AgentMapWalk.start(entry, agent, AgentMapWalk.Target.map(FIELD), 10_000L, 0L);
        when(agent.isAlive()).thenReturn(false);

        assertFalse(AgentMapWalk.tick(entry, agent, 30_000L));
        assertEquals(AgentMapWalk.Status.WALKING, walk.status());
        verify(gateway, never()).travelTo(any(), any(), anyInt(), anyLong());

        when(agent.isAlive()).thenReturn(true);
        assertTrue(AgentMapWalk.tick(entry, agent, 31_000L), "up again, it walks on");
        assertEquals(AgentMapWalk.Status.WALKING, walk.status());
    }

    @Test
    void aNewerWalkReplacesTheOldAndStopOnlyEndsItsOwn() {
        AgentMapWalk.Walk old = AgentMapWalk.start(entry, agent, AgentMapWalk.Target.map(FIELD), 60_000L, 0L);
        AgentMapWalk.Walk newer = AgentMapWalk.start(entry, agent, AgentMapWalk.Target.map(STATION), 60_000L, 0L);

        AgentMapWalk.stop(AGENT, old);
        assertTrue(AgentMapWalk.walking(AGENT), "the old walk's stop leaves the newer one");
        assertTrue(AgentMapWalk.tick(entry, agent, 100L));
        verify(gateway).travelTo(entry, agent, STATION, 100L);

        AgentMapWalk.stop(AGENT, newer);
        assertFalse(AgentMapWalk.walking(AGENT));
        assertFalse(AgentMapWalk.tick(entry, agent, 200L));
    }

    @Test
    void aWalkOfAnEarlierRuntimeOfTheCharacterIsDroppedNotWalked() {
        AgentMapWalk.start(entry, agent, AgentMapWalk.Target.map(FIELD), 60_000L, 0L);
        AgentRuntimeEntry reloaded = new AgentRuntimeEntry(agent, null, null);

        assertFalse(AgentMapWalk.tick(reloaded, agent, 100L));

        assertFalse(AgentMapWalk.walking(AGENT));
        verify(gateway, never()).travelTo(any(), any(), anyInt(), anyLong());
    }

    /**
     * The tick order the walk relies on: after the supply trip (which moves the Agent first) and the boss death walk, a
     * walking Agent leaves the tick going, so the movement step makes the move, and the world director plans nothing.
     */
    @Test
    void thePreflightRunsTheWalkAfterTheDeathWalkAndLeavesTheTickGoing() throws java.io.IOException {
        String preflight = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/server/agents/runtime/AgentTickPreflightRuntime.java"));
        int supply = preflight.indexOf("AgentSupplyProcurementRuntime.tickIfSelfSustaining(");
        int deathWalk = preflight.indexOf("AgentBossDeathWalk.tick(entry, result.agent(), nowMs)", supply);
        int walk = preflight.indexOf("if (AgentMapWalk.tick(entry, result.agent(), nowMs)) {", deathWalk);
        int director = preflight.indexOf("AgentWorldDirectorObserveRuntime.tick(", walk);
        assertTrue(supply >= 0 && deathWalk > supply && walk > deathWalk && director > walk);
        assertTrue(preflight.substring(walk, director).contains("return result;"), "walking: not consumed");
    }

    @Test
    void withoutWalksTheTickIsANoOp() {
        assertFalse(AgentMapWalk.tick(entry, agent, 100L));
        verify(gateway, never()).travelTo(any(), any(), anyInt(), anyLong());
        gateways.verify(AgentPrimitiveCapabilityGatewayRuntime::gateway, never());
    }

    @Test
    void anArrivedWalkReportsTheRouteItWalkedToTheTellAudit() {
        java.util.List<Long> routes = new java.util.ArrayList<>();
        AgentTellProbe.install(new AgentTellProbe.Sink() {
            @Override public void combatReaction(int agentId, long reactionMs) { }
            @Override public void chatReply(long delayMs) { }
            @Override public void potionPoint(int alertPercent) { }
            @Override public void buffRecast(int percentOfDuration) { }
            @Override public void routeWalked(int agentId, int fromMapId, int toMapId, long routeHash) {
                assertEquals(AGENT, agentId);
                assertEquals(HENESYS, fromMapId);
                assertEquals(FIELD, toMapId);
                routes.add(routeHash);
            }
        });
        try {
            for (int walkNumber = 0; walkNumber < 3; walkNumber++) {
                standOn(HENESYS, new Point(0, 0));
                AgentMapWalk.start(entry, agent, AgentMapWalk.Target.map(FIELD), 60_000L, 1_000L);
                entry.navigationEdgeState().setActiveEdge(walkNumber == 2 ? new AgentNavigationGraph.Edge(1, 2,
                        AgentNavigationGraph.EdgeType.JUMP, new Point(0, 0), new Point(40, -60), 0, 0, 1, -1, 0, 0, 0, 1)
                        : null);
                AgentMapWalk.tick(entry, agent, 1_100L);
                standOn(FIELD, new Point(-500, 200));
                AgentMapWalk.tick(entry, agent, 2_000L);
            }
        } finally {
            AgentTellProbe.install(null);
        }
        assertEquals(3, routes.size());
        assertEquals(routes.get(0), routes.get(1), "the same maps and edges: the same route");
        assertFalse(routes.get(0).equals(routes.get(2)), "a jump on the way: another route");
    }

    private void standOn(int mapId, Point position) {
        when(agent.getMapId()).thenReturn(mapId);
        when(agent.getPosition()).thenReturn(position);
    }
}
