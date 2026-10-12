package server.agents.capabilities.expedition.boss;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.recovery.AgentRespawnCoordinator;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.maps.MapleMap;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P12 G6, the revive-inside death gate (Pink Bean): a dead INSIDE member of a revive-inside run is revived as a player
 * is (the script asked first, then the respawn into the event's own instance of the battle map's return map), not sent
 * to town. Every other Agent, a member of a wait-outside run, and a member the script sends the other way respawn in
 * town as before.
 */
class AgentBossDeathDepartureGateTest {
    private static final int OWNER = 1;
    private static final int THANE = 10;
    /** Pink Bean's battle map and its return map, both inside the event. */
    private static final int BATTLE_MAP = 270_050_100;
    private static final int RETURN_MAP = 270_050_200;

    private final FakeBossRunPorts.Fight fight = new FakeBossRunPorts.Fight();
    private final int[] townRespawns = {0};
    private MockedStatic<AgentRespawnCoordinator> respawns;
    private EventInstanceManager event;
    private MapleMap battle;
    private MapleMap returnInstance;

    @BeforeEach
    void setUp() {
        respawns = mockStatic(AgentRespawnCoordinator.class);
        event = mock(EventInstanceManager.class);
        battle = mock(MapleMap.class);
        when(battle.getId()).thenReturn(BATTLE_MAP);
        when(battle.getReturnMapId()).thenReturn(RETURN_MAP);
        returnInstance = mock(MapleMap.class);
        when(returnInstance.getId()).thenReturn(RETURN_MAP);
        when(event.getMapInstance(RETURN_MAP)).thenReturn(returnInstance);
        when(event.revivePlayer(any())).thenReturn(true); // PinkBeanBattle.js playerRevive
    }

    @AfterEach
    void tearDown() {
        respawns.close();
        AgentBossRunRegistry.resetForTest();
        AgentRuntimeRegistry.clear();
    }

    @Test
    void aDeadMemberOfARevivingRunRevivesInsideTheEventNotInTown() {
        Character thane = insideMember(AgentBossRunDefinition.DeathMode.REVIVE_INSIDE);
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(THANE);

        assertTrue(AgentBossDeathDepartureGate.respawnOrHold(thane, () -> townRespawns[0]++, 0L));

        verify(event).revivePlayer(thane);
        respawns.verify(() -> AgentRespawnCoordinator.respawnInto(entry, thane, returnInstance));
        assertEquals(0, townRespawns[0]);
    }

    @Test
    void aWaitOutsideRunsMemberRespawnsInTownAsBefore() {
        Character thane = insideMember(AgentBossRunDefinition.DeathMode.WAIT_OUTSIDE);

        assertTrue(AgentBossDeathDepartureGate.respawnOrHold(thane, () -> townRespawns[0]++, 0L));

        assertEquals(1, townRespawns[0]);
        verify(event, never()).revivePlayer(any());
    }

    @Test
    void anAgentInNoBossRunRespawnsInTownAsBefore() {
        Character stranger = mock(Character.class);
        when(stranger.getId()).thenReturn(THANE + 1);

        assertTrue(AgentBossDeathDepartureGate.respawnOrHold(stranger, () -> townRespawns[0]++, 0L));

        assertEquals(1, townRespawns[0]);
    }

    @Test
    void aScriptThatSendsTheMemberTheOtherWayLeavesItToTheTownRespawn() {
        Character thane = insideMember(AgentBossRunDefinition.DeathMode.REVIVE_INSIDE);
        when(event.revivePlayer(any())).thenReturn(false);

        assertTrue(AgentBossDeathDepartureGate.respawnOrHold(thane, () -> townRespawns[0]++, 0L));

        assertEquals(1, townRespawns[0]);
        respawns.verify(() -> AgentRespawnCoordinator.respawnInto(any(), any(), any()), never());
    }

    /** Thane: a loaded INSIDE member of a run of {@code mode}, dead on the battle map in its event. */
    private Character insideMember(AgentBossRunDefinition.DeathMode mode) {
        AgentBossRunDefinition definition = new AgentBossRunDefinition("pinkbean_test", null, 270050000, BATTLE_MAP,
                -1, 0, List.of(), 0, -1, () -> fight, false, List.of(), false, false, mode);
        AgentBossRunSession session = new AgentBossRunSession(1L, definition, event, fight, 0L);
        AgentBossRunRegistry.put(session);
        AgentBossRunMember member = session.admit(THANE, OWNER, 0L, AgentBossRunMember.Kind.FOLLOWER);
        member.adopted();
        Character thane = mock(Character.class);
        when(thane.getId()).thenReturn(THANE);
        when(thane.getMap()).thenReturn(battle);
        when(thane.getEventInstance()).thenReturn(event);
        AgentRuntimeRegistry.registerEntry(OWNER, new AgentRuntimeEntry(thane, null, null));
        return thane;
    }
}
