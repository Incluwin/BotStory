package server.agents.capabilities.expedition.boss;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.navigation.AgentRouteOutcome;
import server.agents.capabilities.navigation.AgentRouteStatus;
import server.agents.capabilities.supplies.AgentSupplyReadiness;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.runtime.AgentRuntimeEntry;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.longThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P15, decision 9: a companion that died in the Papulatus fight comes to in Ludibrium, walks back to the gate on
 * foot and waits there for its owner. It walks only while it is down outside and its owner is still in the run's
 * event, and it stops on the gate, with no route, or after TIMEOUT_MS. A boss with no wait map keeps the old rule.
 */
class AgentBossDeathWalkTest {
    private static final int OWNER = 1;
    private static final int THANE = 10;
    private static final int LUDIBRIUM = 220_000_000;
    private static final int GATE = 220_080_000;
    private static final int KAMPUNG = 551_000_000;
    private static final int SCARGA_GATE = 551_030_100;
    private static final int ALDOL = 9_270_047;
    /** A party-instance run with SCARGA's maps, recruit NPC and REENTER deaths (its own needs an expedition). */
    private static final AgentBossRunDefinition REENTER_RUN = new AgentBossRunDefinition("scarga", null, SCARGA_GATE,
            551_030_200, -1, ALDOL, List.of(), 9_201_134, -1, FakeBossRunPorts.Fight::new, true, List.of(), false, true,
            AgentBossRunDefinition.DeathMode.REENTER);
    private static final int LEAFRE = 240_000_000;
    private static final int CAVE_ENTRANCE = 240_050_400;
    private static final int MARK = 2_083_004;
    /** A party-instance run with HORNTAIL's key, maps, recruit NPC and REENTER deaths. */
    private static final AgentBossRunDefinition HORNTAIL_RUN = new AgentBossRunDefinition("horntail", null,
            CAVE_ENTRANCE, 240_060_200, -1, MARK, List.of(), 2_083_002, -1, FakeBossRunPorts.Fight::new, true,
            List.of(), false, true, AgentBossRunDefinition.DeathMode.REENTER);

    private final PrimitiveCapabilityGateway gateway = mock(PrimitiveCapabilityGateway.class);
    private final FakeBossRunPorts.Fight fight = new FakeBossRunPorts.Fight();
    private MockedStatic<AgentPrimitiveCapabilityGatewayRuntime> gateways;
    private EventInstanceManager event;
    private Character owner;

    @BeforeEach
    void setUp() {
        gateways = mockStatic(AgentPrimitiveCapabilityGatewayRuntime.class);
        gateways.when(AgentPrimitiveCapabilityGatewayRuntime::gateway).thenReturn(gateway);
        event = mock(EventInstanceManager.class);
        owner = mock(Character.class);
        when(event.getPlayerById(OWNER)).thenReturn(owner);
        when(gateway.travelTo(any(), any(), anyInt(), anyLong()))
                .thenReturn(new AgentRouteOutcome(AgentRouteStatus.MOVING, LUDIBRIUM, 220_000_300, GATE, false));
    }

    @AfterEach
    void tearDown() {
        gateways.close();
        AgentBossDeathWalk.resetForTest();
        AgentBossRunRegistry.resetForTest();
    }

    @Test
    void papulatusNamesItsGateAsTheWaitMap() {
        assertEquals(GATE, AgentBossRules.deathWaitMap("papulatus"));
        assertEquals(0, AgentBossRules.deathWaitMap("latanica"), "every other boss waits where it comes to");
        assertEquals(0, AgentBossRules.deathWaitMap("zakum"));
    }

    @Test
    void aMemberDownInLudibriumWalksToTheGateAndSpendsItsTick() {
        Character thane = downOutside(AgentBossRunCatalog.PAPULATUS, LUDIBRIUM);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(thane, null, null);

        assertTrue(AgentBossDeathWalk.tick(entry, thane, 1_000L));
        assertTrue(AgentBossDeathWalk.walking(THANE));
        verify(gateway).travelTo(entry, thane, GATE, 1_000L);
    }

    @Test
    void onTheGateItStopsAndWaits() {
        Character thane = downOutside(AgentBossRunCatalog.PAPULATUS, LUDIBRIUM);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(thane, null, null);
        assertTrue(AgentBossDeathWalk.tick(entry, thane, 1_000L));

        when(thane.getMapId()).thenReturn(GATE);
        assertFalse(AgentBossDeathWalk.tick(entry, thane, 2_000L));
        assertFalse(AgentBossDeathWalk.walking(THANE));
        when(thane.getMapId()).thenReturn(LUDIBRIUM);
        assertFalse(AgentBossDeathWalk.tick(entry, thane, 3_000L), "one walk per run");
        verify(gateway, times(1)).travelTo(any(), any(), anyInt(), anyLong());
    }

    @Test
    void itDoesNotWalkOnceItsOwnerLeftTheEvent() {
        Character thane = downOutside(AgentBossRunCatalog.PAPULATUS, LUDIBRIUM);
        when(event.getPlayerById(OWNER)).thenReturn(null);

        assertFalse(AgentBossDeathWalk.tick(new AgentRuntimeEntry(thane, null, null), thane, 1_000L));
        verify(gateway, never()).travelTo(any(), any(), anyInt(), anyLong());
    }

    @Test
    void aDeadMemberWaitsForItsRevive() {
        Character thane = downOutside(AgentBossRunCatalog.PAPULATUS, 220_080_001);
        when(thane.isAlive()).thenReturn(false);

        assertFalse(AgentBossDeathWalk.tick(new AgentRuntimeEntry(thane, null, null), thane, 1_000L));
        verify(gateway, never()).travelTo(any(), any(), anyInt(), anyLong());
    }

    @Test
    void noRouteStopsTheWalkForTheRestOfTheRun() {
        Character thane = downOutside(AgentBossRunCatalog.PAPULATUS, LUDIBRIUM);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(thane, null, null);
        when(gateway.travelTo(any(), any(), anyInt(), anyLong()))
                .thenReturn(new AgentRouteOutcome(AgentRouteStatus.NO_ROUTE, LUDIBRIUM, -1, GATE, false));

        assertFalse(AgentBossDeathWalk.tick(entry, thane, 1_000L));
        assertFalse(AgentBossDeathWalk.tick(entry, thane, 2_000L));
        verify(gateway, times(1)).travelTo(any(), any(), anyInt(), anyLong());
    }

    @Test
    void aWalkThatTakesTooLongStops() {
        Character thane = downOutside(AgentBossRunCatalog.PAPULATUS, LUDIBRIUM);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(thane, null, null);
        long timeout = config.AgentTuning.longValue(
                "server.agents.capabilities.expedition.boss.AgentBossDeathWalk.TIMEOUT_MS");

        assertTrue(AgentBossDeathWalk.tick(entry, thane, 1_000L));
        assertTrue(AgentBossDeathWalk.tick(entry, thane, timeout));
        assertFalse(AgentBossDeathWalk.tick(entry, thane, 1_000L + timeout));
        assertFalse(AgentBossDeathWalk.walking(THANE));
        verify(gateway, times(2)).travelTo(any(), any(), anyInt(), anyLong());
    }

    @Test
    void aBossWithNoWaitMapKeepsItsMemberWhereItCameTo() {
        Character thane = downOutside(AgentBossRunCatalog.LATANICA, 541_000_000);

        assertFalse(AgentBossDeathWalk.tick(new AgentRuntimeEntry(thane, null, null), thane, 1_000L));
        verify(gateway, never()).travelTo(any(), any(), anyInt(), anyLong());
    }

    @Test
    void anInsideMemberDoesNotWalk() {
        AgentBossRunSession session = new AgentBossRunSession(1L, AgentBossRunCatalog.PAPULATUS, event, fight, 0L);
        AgentBossRunRegistry.put(session);
        session.admit(THANE, OWNER, 0L, AgentBossRunMember.Kind.FOLLOWER).adopted();
        Character thane = agent(LUDIBRIUM);

        assertFalse(AgentBossDeathWalk.tick(new AgentRuntimeEntry(thane, null, null), thane, 1_000L));
        verify(gateway, never()).travelTo(any(), any(), anyInt(), anyLong());
    }

    /**
     * P17: a REENTERING member of a re-enter run (Scarlion and Targa) walks from Kampung to the recruit map, then up to
     * Aldol, and stops beside him; its run then talks it back in. A new death walks again.
     */
    @Test
    void aReenteringMemberWalksBackToTheRecruitNpcOncePerDeath() {
        AgentBossRunSession session = new AgentBossRunSession(1L, REENTER_RUN, event, fight, 0L);
        AgentBossRunRegistry.put(session);
        AgentBossRunMember member = session.admit(THANE, OWNER, 0L, AgentBossRunMember.Kind.FOLLOWER);
        member.adopted();
        member.died();
        member.reentering(0L);
        Character thane = agent(KAMPUNG);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(thane, null, null);

        assertTrue(AgentBossDeathWalk.tick(entry, thane, 1_000L));
        verify(gateway).travelTo(entry, thane, SCARGA_GATE, 1_000L);

        when(thane.getMapId()).thenReturn(SCARGA_GATE);
        when(thane.getPosition()).thenReturn(new Point(-400, 426));
        when(gateway.npcPosition(thane, ALDOL)).thenReturn(new Point(1693, 353));
        assertTrue(AgentBossDeathWalk.tick(entry, thane, 2_000L), "on the map, it walks up to Aldol");
        verify(gateway).navigate(entry, new Point(1693, 353), true);

        when(thane.getPosition()).thenReturn(new Point(1690, 353));
        assertFalse(AgentBossDeathWalk.tick(entry, thane, 3_000L), "beside him it stops and waits");
        verify(gateway).stop(entry);
        assertFalse(AgentBossDeathWalk.walking(THANE));

        member.backInside();
        member.died();
        member.reentering(4_000L);
        when(thane.getMapId()).thenReturn(KAMPUNG);
        assertTrue(AgentBossDeathWalk.tick(entry, thane, 5_000L), "a new death walks again");
    }

    /**
     * P18, plan decision 11a: no route reaches Horntail's recruit map (240050400). A REENTERING member that comes to in
     * Leafre stands there REENTRY_WARP_AFTER_MS, is moved to the recruit map once, then walks up to the Mark of the
     * Squad as for Scarga. Elsewhere it walks to Leafre first, never to the unreachable recruit map.
     */
    @Test
    void aReenteringHorntailMemberIsMovedFromLeafreToTheCaveEntranceAndWalksUpToTheMark() {
        List<Integer> movedTo = new ArrayList<>();
        AgentBossDeathWalk.moverForTest((agent, mapId) -> movedTo.add(mapId));
        reentering(HORNTAIL_RUN);
        Character thane = agent(LEAFRE);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(thane, null, null);
        long after = config.AgentTuning.longValue(
                "server.agents.capabilities.expedition.boss.AgentBossDeathWalk.REENTRY_WARP_AFTER_MS");

        assertTrue(AgentBossDeathWalk.tick(entry, thane, 1_000L), "it stands in Leafre first");
        assertTrue(AgentBossDeathWalk.tick(entry, thane, 1_000L + after - 1));
        assertTrue(movedTo.isEmpty());
        assertTrue(AgentBossDeathWalk.tick(entry, thane, 1_000L + after));
        assertEquals(List.of(CAVE_ENTRANCE), movedTo);
        verify(gateway, never()).travelTo(any(), any(), anyInt(), anyLong());

        when(thane.getMapId()).thenReturn(CAVE_ENTRANCE);
        when(thane.getPosition()).thenReturn(new Point(4, 175));
        when(gateway.npcPosition(thane, MARK)).thenReturn(new Point(898, 260));
        assertTrue(AgentBossDeathWalk.tick(entry, thane, 2_000L + after), "it walks up to the Mark");
        verify(gateway).navigate(entry, new Point(898, 260), true);
        assertEquals(List.of(CAVE_ENTRANCE), movedTo, "moved once");
    }

    @Test
    void aReenteringHorntailMemberAwayFromLeafreWalksToLeafre() {
        reentering(HORNTAIL_RUN);
        Character thane = agent(240_010_000);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(thane, null, null);

        assertTrue(AgentBossDeathWalk.tick(entry, thane, 1_000L));
        verify(gateway).travelTo(entry, thane, LEAFRE, 1_000L);
    }

    /**
     * P18 follow-up: a REENTERING Horntail member in Leafre keeps Horntail's standard there (its own restock may send
     * it to the store); away from Leafre, in another run, or still inside, it keeps none.
     */
    @Test
    void aReenteringHorntailMemberInLeafreKeepsHorntailsStandard() {
        reentering(HORNTAIL_RUN);
        assertEquals("horntail", AgentBossDeathWalk.reentryRestockKey(agent(LEAFRE)));
        assertNull(AgentBossDeathWalk.reentryRestockKey(agent(240_000_002)), "on the store map: no new trip");
        assertNull(AgentBossDeathWalk.reentryRestockKey(null));
        AgentBossRunRegistry.resetForTest();

        reentering(REENTER_RUN);
        assertNull(AgentBossDeathWalk.reentryRestockKey(agent(KAMPUNG)), "Scarga walks back with no move in");
        AgentBossRunRegistry.resetForTest();

        AgentBossRunSession session = new AgentBossRunSession(1L, HORNTAIL_RUN, event, fight, 0L);
        AgentBossRunRegistry.put(session);
        session.admit(THANE, OWNER, 0L, AgentBossRunMember.Kind.FOLLOWER).adopted();
        assertNull(AgentBossDeathWalk.reentryRestockKey(agent(LEAFRE)), "an INSIDE member");
    }

    /**
     * P18 follow-up: in Leafre the move in waits while the member's shop trip is under way, and while its restock still
     * decides on one, at most VERDICT_WAIT_MS of its walk.
     */
    @Test
    void theMoveInWaitsForTheMembersOwnRestock() {
        List<Integer> movedTo = new ArrayList<>();
        AgentBossDeathWalk.moverForTest((agent, mapId) -> movedTo.add(mapId));
        reentering(HORNTAIL_RUN);
        Character thane = agent(LEAFRE);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(thane, null, null);
        long after = config.AgentTuning.longValue(
                "server.agents.capabilities.expedition.boss.AgentBossDeathWalk.REENTRY_WARP_AFTER_MS");
        long verdictWait = AgentBossRunSession.verdictWaitMs();
        AgentSupplyReadiness.Status trip = new AgentSupplyReadiness.Status(AgentSupplyReadiness.StatusKind.RESTOCKING,
                60_000L, 0);

        try (MockedStatic<AgentSupplyReadiness> readiness = mockStatic(AgentSupplyReadiness.class)) {
            readiness.when(() -> AgentSupplyReadiness.status(entry, 1_000L + after)).thenReturn(trip);
            readiness.when(() -> AgentSupplyReadiness.status(eq(entry), longThat(t -> t != 1_000L + after)))
                    .thenReturn(AgentSupplyReadiness.Status.NONE);
            readiness.when(() -> AgentSupplyReadiness.restockPending(eq(entry), anyLong())).thenReturn(true);

            assertTrue(AgentBossDeathWalk.tick(entry, thane, 1_000L));
            assertTrue(AgentBossDeathWalk.tick(entry, thane, 1_000L + after), "a trip under way");
            assertTrue(AgentBossDeathWalk.tick(entry, thane, 1_000L + verdictWait - 1), "still deciding");
            assertTrue(movedTo.isEmpty());
            assertTrue(AgentBossDeathWalk.tick(entry, thane, 1_000L + Math.max(after, verdictWait)));
        }
        assertEquals(List.of(CAVE_ENTRANCE), movedTo, "the decision waited long enough");
    }

    private void reentering(AgentBossRunDefinition definition) {
        AgentBossRunSession session = new AgentBossRunSession(1L, definition, event, fight, 0L);
        AgentBossRunRegistry.put(session);
        AgentBossRunMember member = session.admit(THANE, OWNER, 0L, AgentBossRunMember.Kind.FOLLOWER);
        member.adopted();
        member.died();
        member.reentering(0L);
    }

    private Character downOutside(AgentBossRunDefinition definition, int mapId) {
        AgentBossRunSession session = new AgentBossRunSession(1L, definition, event, fight, 0L);
        AgentBossRunRegistry.put(session);
        AgentBossRunMember member = session.admit(THANE, OWNER, 0L, AgentBossRunMember.Kind.FOLLOWER);
        member.adopted();
        member.leftRun(AgentBossRunHost.Out.DIED);
        return agent(mapId);
    }

    private static Character agent(int mapId) {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(THANE);
        when(agent.getMapId()).thenReturn(mapId);
        when(agent.isAlive()).thenReturn(true);
        return agent;
    }
}
