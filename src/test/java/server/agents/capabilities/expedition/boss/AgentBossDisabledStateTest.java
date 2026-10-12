package server.agents.capabilities.expedition.boss;

import client.Character;
import client.Disease;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.movement.AgentMovementStateRuntime;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.expeditions.Expedition;
import server.life.Monster;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P12 G5: in companion boss scope a stunned member stands still, a seduced one walks to the nearest boss part, a
 * confused one walks the other way, and a banished one plans again from where it landed. Its run's fight gives a
 * disabled member no orders meanwhile. Out of scope (EPQ's Poison Golem confuse included) nothing changes.
 */
class AgentBossDisabledStateTest {
    private static final int OWNER = 1;
    private static final int THANE = 10;
    private static final int ISOLDE = 11;

    private final PrimitiveCapabilityGateway gateway = mock(PrimitiveCapabilityGateway.class);
    private final FakeBossRunPorts.Fight fight = new FakeBossRunPorts.Fight();
    private final List<Monster> monsters = new ArrayList<>();
    private MockedStatic<AgentPrimitiveCapabilityGatewayRuntime> gateways;
    private EventInstanceManager event;
    private MapleMap battle;
    private AgentBossRunSession session;

    @BeforeEach
    void setUp() {
        gateways = mockStatic(AgentPrimitiveCapabilityGatewayRuntime.class);
        gateways.when(AgentPrimitiveCapabilityGatewayRuntime::gateway).thenReturn(gateway);
        event = mock(EventInstanceManager.class);
        Expedition expedition = mock(Expedition.class);
        when(event.getExpedition()).thenReturn(expedition);
        battle = mock(MapleMap.class);
        when(battle.getEventInstance()).thenReturn(event);
        when(battle.getAllMonsters()).thenAnswer(invocation -> List.copyOf(monsters));
        session = new AgentBossRunSession(1L, AgentBossRunCatalog.ZAKUM, expedition, fight, 0L);
        AgentBossRunRegistry.put(session);
    }

    @AfterEach
    void tearDown() {
        gateways.close();
        AgentBossDisabledState.resetForTest();
        AgentBossRunRegistry.resetForTest();
        AgentRuntimeRegistry.clear();
    }

    @Test
    void aStunnedMemberOnTheGroundStopsAndSpendsItsTick() {
        Character thane = inside(THANE, new Point(0, 0), Disease.STUN);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(thane, null, null);

        assertTrue(AgentBossDisabledState.holds(entry, thane));
        verify(gateway).stop(entry);
        assertTrue(AgentBossDisabledState.disabled(thane));
    }

    @Test
    void aStunnedMemberInTheAirStopsButKeepsItsTickSoItFalls() {
        Character thane = inside(THANE, new Point(0, -50), Disease.STUN);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(thane, null, null);
        AgentMovementStateRuntime.setInAir(entry, true);

        assertFalse(AgentBossDisabledState.holds(entry, thane));
        verify(gateway).stop(entry);
    }

    @Test
    void aSeducedMemberWalksToTheNearestBossPartOncePerState() {
        boss(new Point(400, 0));
        boss(new Point(-150, 0));
        Character thane = inside(THANE, new Point(0, 0), Disease.SEDUCE);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(thane, null, null);

        assertFalse(AgentBossDisabledState.holds(entry, thane), "its tick goes on, so it walks");
        assertFalse(AgentBossDisabledState.holds(entry, thane));

        verify(gateway, times(1)).navigate(entry, new Point(-150, 0), false);
    }

    @Test
    void aConfusedMemberWalksAwayFromTheNearestBossPart() {
        boss(new Point(300, 0));
        Character thane = inside(THANE, new Point(100, 0), Disease.CONFUSE);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(thane, null, null);

        assertFalse(AgentBossDisabledState.holds(entry, thane));

        verify(gateway).navigate(entry, new Point(100 - AgentBossDisabledState.confusedWalkPx(), 0), false);
    }

    @Test
    void anAgentOutOfScopeKeepsTodaysPathWhateverItsState() {
        // EPQ's Poison Golem confuses: an EPQ Agent is no member of any boss run
        Character epq = agent(ISOLDE, new Point(0, 0), Disease.CONFUSE);
        boss(new Point(300, 0));
        Character stunned = agent(ISOLDE + 1, new Point(0, 0), Disease.STUN);

        assertFalse(AgentBossDisabledState.holds(new AgentRuntimeEntry(epq, null, null), epq));
        assertFalse(AgentBossDisabledState.holds(new AgentRuntimeEntry(stunned, null, null), stunned));
        verify(gateway, never()).stop(any());
        verify(gateway, never()).navigate(any(), any(), anyBoolean());
    }

    @Test
    void aBanishedMemberDropsItsPathOnItsNextTickOnce() {
        Character thane = inside(THANE, new Point(0, 0));
        AgentRuntimeEntry entry = new AgentRuntimeEntry(thane, null, null);
        AgentRuntimeRegistry.registerEntry(OWNER, entry);

        AgentBossDisabledState.banished(thane);

        assertFalse(AgentBossDisabledState.holds(entry, thane), "it plans again on the same tick");
        assertFalse(AgentBossDisabledState.holds(entry, thane));
        verify(gateway, times(1)).stop(entry);
    }

    @Test
    void theRunsFightGivesADisabledMemberNoOrders() {
        FakeBossRunPorts ports = new FakeBossRunPorts();
        FakeBossRunPorts.Host host = new FakeBossRunPorts.Host(ports.calls);
        Character owner = ports.human(OWNER);
        when(event.getPlayers()).thenReturn(List.of(owner));
        when(event.getPlayerById(OWNER)).thenReturn(owner);
        AgentBossRunSession run = new AgentBossRunSession(2L, AgentBossRunCatalog.EASY_BALROG,
                mock(Expedition.class), fight, 0L);
        when(event.getExpedition()).thenReturn(run.expedition());
        AgentBossRunRegistry.put(run);
        int battleMap = AgentBossRunCatalog.EASY_BALROG.battleMapId();
        for (int id : List.of(THANE, ISOLDE)) {
            run.addMember(id, OWNER, 0L).registered();
            ports.place(id, battleMap, 100, 0, 70);
            ports.eventOf.put(id, event);
        }
        // the Start: both are adopted in the event on the battle map
        when(run.expedition().isRegistering()).thenReturn(false);
        when(run.expedition().getStartTime()).thenReturn(0L);
        run.tick(ports, host, 1_000L);
        run.tick(ports, host, 1_500L);
        fight.calls.clear();
        Character isolde = ports.agent(ISOLDE);
        when(isolde.getDiseasesSize()).thenReturn(1);
        when(isolde.hasDisease(Disease.STUN)).thenReturn(true);

        run.tick(ports, host, 2_000L);

        assertEquals(List.of("tick [" + THANE + "]"), fight.calls);
    }

    private Character inside(int agentId, Point at, Disease... states) {
        Character agent = agent(agentId, at, states);
        AgentBossRunMember member = session.addMember(agentId, OWNER, 0L);
        member.registered();
        member.adopted();
        return agent;
    }

    private Character agent(int agentId, Point at, Disease... states) {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(agentId);
        when(agent.getMap()).thenReturn(battle);
        when(agent.getEventInstance()).thenReturn(event);
        when(agent.getPosition()).thenReturn(at);
        when(agent.getDiseasesSize()).thenReturn(states.length);
        for (Disease state : states) {
            when(agent.hasDisease(state)).thenReturn(true);
        }
        return agent;
    }

    private void boss(Point at) {
        Monster boss = mock(Monster.class);
        when(boss.isAlive()).thenReturn(true);
        when(boss.isBoss()).thenReturn(true);
        when(boss.getPosition()).thenReturn(at);
        monsters.add(boss);
    }
}
