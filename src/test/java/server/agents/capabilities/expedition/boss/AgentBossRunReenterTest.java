package server.agents.capabilities.expedition.boss;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P12 G6, the re-enter death mode (Scarga, Horntail): a member that died and came to outside stays in the run,
 * REENTERING, while the host walks it back to the recruit NPC; the run does not end meanwhile. Standing in the event
 * again it is back inside. It gives up (out DIED, following its owner) after REENTER_TIMEOUT_MS, at the clear, or when
 * its anchor is no player of the event. A wait-outside run keeps P7's path: the same death is out DIED at once.
 */
class AgentBossRunReenterTest {
    private static final int OWNER = 1;
    private static final int THANE = 10;
    private static final long NOW = 1_000_000L;
    private static final int TOWN = 240_000_000;
    private static final int ENTRY_MAP = 240_060_000;
    private static final int BATTLE_MAP = 240_060_200;
    private static final int RECRUIT_MAP = 240_050_400;
    private static final int RECRUIT_NPC = 2_083_004;

    private final FakeBossRunPorts ports = new FakeBossRunPorts();
    private final FakeBossRunPorts.Host host = new FakeBossRunPorts.Host(ports.calls);
    private final FakeBossRunPorts.Fight fight = new FakeBossRunPorts.Fight();
    private EventInstanceManager event;

    @BeforeEach
    void setUp() {
        event = mock(EventInstanceManager.class);
        when(event.getPlayerCount()).thenReturn(2);
        when(event.getPlayerById(anyInt())).thenReturn(null);
        Character owner = ports.human(OWNER);
        when(event.getPlayers()).thenReturn(List.of(owner));
        when(event.getPlayerById(OWNER)).thenReturn(owner);
    }

    @AfterEach
    void tearDown() {
        AgentBossRunRegistry.resetForTest();
    }

    @Test
    void aDeadMemberOfAReenterRunStaysInItAndIsBackInsideOnceInTheEventAgain() {
        AgentBossRunSession session = fighting(AgentBossRunDefinition.DeathMode.REENTER);

        diesAndComesToInTown(session);

        assertEquals(AgentBossRunMember.Phase.REENTERING, session.member(THANE).phase());
        assertTrue(ports.calls.contains("reentering " + THANE), ports.calls.toString());
        assertEquals(AgentBossRunSession.Phase.FIGHTING, session.phase(), "the run waits for it");
        assertFalse(AgentBossRunRegistry.inside(THANE), "outside the event it is no inside member");

        ports.place(THANE, ENTRY_MAP, 0, 0, 120);
        ports.eventOf.put(THANE, event);
        session.tick(ports, host, NOW + 20_000L);

        AgentBossRunMember member = session.member(THANE);
        assertEquals(AgentBossRunMember.Phase.INSIDE, member.phase());
        assertFalse(member.seenDead(), "its death is behind it");
        assertEquals(1, member.deaths());
        assertNull(member.out());
    }

    @Test
    void aReenteringMemberGivesUpAfterTheTimeoutAndTheRunEnds() {
        AgentBossRunSession session = fighting(AgentBossRunDefinition.DeathMode.REENTER);
        diesAndComesToInTown(session);

        session.tick(ports, host, NOW + 10_000L + AgentBossRunSession.reenterTimeoutMs());

        assertEquals(AgentBossRunHost.Out.DIED, session.member(THANE).out());
        assertTrue(ports.calls.contains("follow " + THANE), "it follows its owner again: " + ports.calls);
        assertEquals(AgentBossRunSession.Phase.ENDED, session.phase());
    }

    @Test
    void aReenteringMemberGivesUpWhenItsAnchorIsNoPlayerOfTheEvent() {
        AgentBossRunSession session = fighting(AgentBossRunDefinition.DeathMode.REENTER);
        diesAndComesToInTown(session);
        when(event.getPlayerById(OWNER)).thenReturn(null);

        session.tick(ports, host, NOW + 20_000L);

        assertEquals(AgentBossRunHost.Out.DIED, session.member(THANE).out());
    }

    @Test
    void aReenteringMemberIsOutWhenTheEventIsGone() {
        AgentBossRunSession session = fighting(AgentBossRunDefinition.DeathMode.REENTER);
        diesAndComesToInTown(session);
        when(event.isEventDisposed()).thenReturn(true);

        session.tick(ports, host, NOW + 20_000L);

        assertEquals(AgentBossRunHost.Out.DIED, session.member(THANE).out());
        assertEquals(AgentBossRunSession.Phase.ENDED, session.phase());
    }

    @Test
    void aWaitOutsideRunKeepsP7sPathTheSameDeathIsOutAtOnce() {
        AgentBossRunSession session = fighting(AgentBossRunDefinition.DeathMode.WAIT_OUTSIDE);

        diesAndComesToInTown(session);

        assertEquals(AgentBossRunHost.Out.DIED, session.member(THANE).out());
        assertFalse(ports.calls.contains("reentering " + THANE));
    }

    /**
     * P17: a REENTERING member alive beside the recruit NPC (AgentBossDeathWalk walked it there) talks to it to get
     * back in, at most every REENTER_TALK_EVERY_MS; one still walking up, out of reach, does not.
     */
    @Test
    void aReenteringMemberBesideTheRecruitNpcTalksBackInAtMostEveryInterval() {
        AgentBossRunSession session = fighting(AgentBossRunDefinition.DeathMode.REENTER);
        diesAndComesToInTown(session);
        ports.npcs.put(RECRUIT_NPC, new Point(500, 0));
        long reach = AgentBossRunSession.exitReachPx();
        String talk = "runNpc " + THANE + " " + RECRUIT_NPC + " ";

        ports.place(THANE, RECRUIT_MAP, 500 - (int) reach - 200, 0, 120);
        session.tick(ports, host, NOW + 12_000L);
        assertFalse(ports.calls.contains(talk), "still walking up: " + ports.calls);

        ports.place(THANE, RECRUIT_MAP, 500 - (int) reach / 2, 0, 120);
        session.tick(ports, host, NOW + 13_000L);
        session.tick(ports, host, NOW + 14_000L);
        assertEquals(1, ports.calls.stream().filter(talk::equals).count(), ports.calls.toString());

        session.tick(ports, host, NOW + 13_000L + AgentBossRunSession.reenterTalkEveryMs());
        assertEquals(2, ports.calls.stream().filter(talk::equals).count(), "again after the interval");
        assertEquals(AgentBossRunMember.Phase.REENTERING, session.member(THANE).phase());
    }

    /** A run of {@code mode} with Thane adopted on the battle map in the event and fighting. */
    private AgentBossRunSession fighting(AgentBossRunDefinition.DeathMode mode) {
        AgentBossRunDefinition definition = new AgentBossRunDefinition("reenter_test", null, RECRUIT_MAP, BATTLE_MAP,
                -1, RECRUIT_NPC, List.of(), 0, -1, () -> fight, false, List.of(), false, false, mode);
        AgentBossRunSession session = new AgentBossRunSession(1L, definition, event, fight, NOW);
        AgentBossRunRegistry.put(session);
        session.admit(THANE, OWNER, NOW, AgentBossRunMember.Kind.FOLLOWER);
        ports.place(THANE, BATTLE_MAP, 0, 0, 120);
        ports.eventOf.put(THANE, event);
        session.tick(ports, host, NOW + 500L);
        assertEquals(AgentBossRunMember.Phase.INSIDE, session.member(THANE).phase());
        ports.calls.clear();
        return session;
    }

    /** Thane falls in the fight, and the stock respawn brings it to in town, outside the event. */
    private void diesAndComesToInTown(AgentBossRunSession session) {
        ports.dead.add(THANE);
        session.tick(ports, host, NOW + 5_000L);
        ports.dead.remove(THANE);
        ports.eventOf.remove(THANE);
        ports.place(THANE, TOWN, 0, 0, 120);
        session.tick(ports, host, NOW + 10_000L);
    }
}
