package server.agents.capabilities.expedition.boss;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.navigation.AgentRouteOutcome;
import server.agents.capabilities.navigation.AgentRouteStatus;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.runtime.AgentRuntimeEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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
