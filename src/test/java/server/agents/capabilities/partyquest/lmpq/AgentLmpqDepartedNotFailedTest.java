package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LMPQ base fix 3 (all modes, the EPQ departed-not-failed rule): a member the bound event no longer holds has
 * departed; it is logged once, its room lease is released and the run goes on (LudiMazePQ.js itself ends the event
 * when a departure leaves it at its floor). A dead member the event still holds is down, not missing. A member in
 * another instance still fails the run; an event that ended before the clear, or a leader who left, ends it at once
 * with the honest reason. The run counts, for its rendezvous, its coupons and its progress, only the members the
 * event holds that have not departed.
 */
class AgentLmpqDepartedNotFailedTest {
    private final LmpqRunFixture run = new LmpqRunFixture();
    private final Character ben = run.human(1, "Ben");
    private final Character mira = run.companion(2, "Mira");
    private final Character kes = run.companion(3, "Kes");
    private final Character pel = run.companion(4, "Pel");
    private final Character ann = run.human(5, "Ann");

    @AfterEach
    void tearDown() {
        run.close();
    }

    private AgentLmpqSession farming() {
        return run.session(AgentLmpqSession.Mode.HUMAN_LEADER, AgentLmpqSession.Phase.FARMING,
                ben, mira, kes, pel, ann);
    }

    /** A first pass while the event holds every member (each has entered the maze). */
    private static void heldOnce(AgentLmpqSession session) {
        AgentLmpqCoordinator.tickSession(session, 9_000L);
    }

    /** Kes died and his respawn took him to the exit map: the event unregistered him. */
    private void kesRespawnedOutside() {
        when(kes.getMapId()).thenReturn(AgentLmpqDefinition.EXIT_MAP);
        when(kes.getEventInstance()).thenReturn(null);
    }

    @Test
    void aMemberTheEventNoLongerHoldsDepartsOnceAndTheRunGoesOn() {
        AgentLmpqSession session = farming();
        session.rooms().reserve(6, kes.getId(), 5_000L);
        session.member(kes.getId()).assignTargetRoom(6);
        heldOnce(session);
        kesRespawnedOutside();
        run.livePlayers(ben, mira, pel, ann);
        try (LmpqLogLines lines = new LmpqLogLines(AgentLmpqCoordinator.class)) {
            AgentLmpqCoordinator.tickSession(session, 10_000L);
            AgentLmpqCoordinator.tickSession(session, 11_000L);

            assertFalse(session.terminal(), session.failure());
            assertTrue(session.departed(kes.getId()));
            assertEquals(List.of("LMPQ departed: session=" + session.sessionId() + " member=Kes(3) type=AGENT"
                            + " map=809050017 phase=FARMING registered=4 companionRun=false reason=left"),
                    lines.startingWith("LMPQ departed:"));
            assertEquals(AgentLmpqRoomLedger.State.AVAILABLE, session.rooms().room(6).state(), "its lease is free");
            assertEquals(0, session.member(kes.getId()).targetRoom());
        }
    }

    @Test
    void aDisconnectedHumanDepartsToo() {
        AgentLmpqSession session = farming();
        heldOnce(session);
        when(run.characters.findOnlineCharacterById(ann.getId())).thenReturn(null);
        run.livePlayers(ben, mira, kes, pel);
        try (LmpqLogLines lines = new LmpqLogLines(AgentLmpqCoordinator.class)) {
            AgentLmpqCoordinator.tickSession(session, 10_000L);

            assertFalse(session.terminal(), session.failure());
            assertEquals(List.of("LMPQ departed: session=" + session.sessionId() + " member=-(5) type=HUMAN map=-"
                            + " phase=FARMING registered=4 companionRun=false reason=left"),
                    lines.startingWith("LMPQ departed:"));
        }
    }

    @Test
    void aDeadMemberTheEventStillHoldsIsDownNotMissing() {
        AgentLmpqSession session = farming();
        when(pel.getHp()).thenReturn(0);

        AgentLmpqCoordinator.tickSession(session, 10_000L);

        assertFalse(session.terminal(), session.failure());
        assertFalse(session.departed(pel.getId()));
    }

    @Test
    void anAgentWithNoInstanceOnAMazeMapDoesNotFailTheRun() {
        AgentLmpqSession session = farming();
        heldOnce(session);
        when(kes.getEventInstance()).thenReturn(null); // unregistered, its respawn has not moved it yet
        run.livePlayers(ben, mira, pel, ann);

        AgentLmpqCoordinator.tickMember(session, run.entry(kes), kes, 10_000L);
        AgentLmpqCoordinator.tickSession(session, 10_000L);

        assertFalse(session.terminal(), session.failure());
        assertTrue(session.departed(kes.getId()));
    }

    @Test
    void aMemberInAnotherInstanceStillFailsTheRun() {
        AgentLmpqSession session = farming();
        when(pel.getEventInstance()).thenReturn(mock(EventInstanceManager.class));

        AgentLmpqCoordinator.tickSession(session, 10_000L);

        assertEquals("An LMPQ participant left the active maze", session.failure());
    }

    @Test
    void anEventThatEndedOrALeaderWhoLeftEndsTheRunWithTheHonestReason() {
        AgentLmpqSession ended = farming();
        when(run.event.isEventDisposed()).thenReturn(true);
        AgentLmpqCoordinator.tickSession(ended, 10_000L);
        assertEquals("LMPQ event ended before the clear", ended.failure());
        AgentLmpqSessionRegistry.remove(ended);
        when(run.event.isEventDisposed()).thenReturn(false);

        AgentLmpqSession offline = farming();
        when(run.characters.findOnlineCharacterById(ben.getId())).thenReturn(null);
        AgentLmpqCoordinator.tickSession(offline, 10_000L);
        assertEquals("LMPQ leader left", offline.failure());
        AgentLmpqSessionRegistry.remove(offline);
        when(run.characters.findOnlineCharacterById(ben.getId())).thenReturn(ben);

        AgentLmpqSession walkedOut = farming();
        when(ben.getMapId()).thenReturn(AgentLmpqDefinition.RECRUIT_MAP);
        when(ben.getEventInstance()).thenReturn(null);
        AgentLmpqCoordinator.tickSession(walkedOut, 10_000L);
        assertEquals("LMPQ leader left", walkedOut.failure());
    }

    @Test
    void aDepartedMemberIsNotAwaitedAtTheRendezvous() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, AgentLmpqSession.Phase.REGROUPING,
                ben, mira, kes, pel, ann);
        for (Character member : List.of(ben, mira, pel, ann)) {
            when(member.getMapId()).thenReturn(AgentLmpqDefinition.mapForRoom(session.rendezvousRoom()));
        }
        when(ben.getItemQuantity(AgentLmpqDefinition.COUPON, false)).thenReturn(30);
        heldOnce(session);
        assertEquals(AgentLmpqSession.Phase.REGROUPING, session.phase(), "Kes is still in Room 3");
        kesRespawnedOutside();
        run.livePlayers(ben, mira, pel, ann);

        AgentLmpqCoordinator.tickSession(session, 10_000L);

        assertEquals(AgentLmpqSession.Phase.CLEARING, session.phase());
    }

    @Test
    void aDepartedAgentsCouponsAreNotCounted() {
        AgentLmpqSession session = farming();
        heldOnce(session);
        when(kes.getItemQuantity(AgentLmpqDefinition.COUPON, false)).thenReturn(40); // gone with him
        kesRespawnedOutside();
        run.livePlayers(ben, mira, pel, ann);

        AgentLmpqCoordinator.tickSession(session, 10_000L);

        assertEquals(AgentLmpqSession.Phase.FARMING, session.phase());
    }

    @Test
    void aMemberWhoLeavesAfterTheClearIsNotADeparture() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, AgentLmpqSession.Phase.REWARD,
                ben, mira, kes, pel, ann);
        when(run.event.isEventCleared()).thenReturn(true);
        kesRespawnedOutside(); // he claimed and left
        run.livePlayers(ben, mira, pel, ann);

        AgentLmpqCoordinator.tickSession(session, 10_000L);

        assertFalse(session.departed(kes.getId()));
        assertFalse(session.terminal());
    }
}
