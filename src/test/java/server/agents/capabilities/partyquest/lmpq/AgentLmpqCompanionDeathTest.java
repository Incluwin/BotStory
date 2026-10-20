package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import server.agents.capabilities.partyquest.AgentPartyQuestHandback;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P6h Step 3 at the run's level: a companion that went down and left the maze above the floor is tapped out by its
 * host (or, kept, named by another companion); a human who left is dropped with a line; a companion held dead at the
 * floor before the clearing is let out after MEMBER_RECOVERY_TIMEOUT_MS; and the run's early end is said once in the
 * companions' words (GONE, DEFEATED, LEFT, WENT_DOWN, TIMEOUT, TOO_LONG), never after the clear, never in a stock run.
 * After the clear a companion run never fails for its leader.
 */
class AgentLmpqCompanionDeathTest {
    private final LmpqRunFixture run = new LmpqRunFixture();
    private final Character ben = run.human(40, "Ben");
    private final Character ann = run.human(43, "Ann");
    private final Character mira = run.companion(41, "Mira");
    private final Character kes = run.companion(42, "Kes");
    private final List<AgentPartyQuestHandback.TapOut> tapOuts = new ArrayList<>();
    private final AtomicBoolean hostTakesThem = new AtomicBoolean(true);

    AgentLmpqCompanionDeathTest() {
        AgentPartyQuestHandback.install(new AgentPartyQuestHandback.Rule() {
            @Override
            public AgentPartyQuestHandback.Handback handback(int agentCharacterId, String questKey) {
                return AgentPartyQuestHandback.Handback.FOLLOW_LEADER;
            }

            @Override
            public boolean tapOut(AgentPartyQuestHandback.TapOut request) {
                tapOuts.add(request);
                return hostTakesThem.get();
            }
        });
    }

    @AfterEach
    void close() {
        AgentPartyQuestHandback.install(null);
        run.close();
    }

    private AgentLmpqSession farming(boolean companionRun) {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, companionRun,
                AgentLmpqSession.Phase.FARMING, ben, mira, kes, ann);
        session.setMemberCoordinationChatEnabled(true);
        AgentLmpqCoordinator.tickSession(session, 2_100L); // the event is seen holding everyone
        return session;
    }

    private List<String> partyLines() {
        ArgumentCaptor<String> lines = ArgumentCaptor.forClass(String.class);
        verify(run.party, atLeastOnce()).sendPartyChat(any(), lines.capture());
        return lines.getAllValues();
    }

    @Test
    void aCompanionThatLeftTheMazeIsTappedOutByItsHost() {
        AgentLmpqSession session = farming(true);
        when(kes.getMapId()).thenReturn(AgentLmpqDefinition.EXIT_MAP);
        run.livePlayers(ben, mira, ann);

        AgentLmpqCoordinator.tickSession(session, 3_000L);

        assertEquals(List.of(new AgentPartyQuestHandback.TapOut("lmpq", session.sessionId(), 42,
                AgentLmpqDefinition.EXIT_MAP, 3_000L)), tapOuts);
        assertTrue(session.departed(42));
        assertFalse(partyLines().stream().anyMatch(line -> line.contains("Kes")), "its host has it say its own line");
        assertFalse(session.terminal(), "the run goes on with three");
    }

    @Test
    void aCompanionItsHostKeptIsNamedByAnother() {
        hostTakesThem.set(false);
        AgentLmpqSession session = farming(true);
        when(kes.getMapId()).thenReturn(AgentLmpqDefinition.EXIT_MAP);
        run.livePlayers(ben, mira, ann);

        AgentLmpqCoordinator.tickSession(session, 3_000L);

        verify(run.party).sendPartyChat(mira, "Kes had to go. we'll keep going.");
    }

    @Test
    void aHumanWhoLeftIsDroppedWithOneLine() {
        AgentLmpqSession session = farming(true);
        run.livePlayers(ben, mira, kes);

        AgentLmpqCoordinator.tickSession(session, 3_000L);
        AgentLmpqCoordinator.tickSession(session, 3_500L);

        assertTrue(session.departed(43));
        assertEquals(1, partyLines().stream().filter("Ann left. we'll keep going."::equals).count());
        assertEquals(List.of(), tapOuts);
    }

    @Test
    void aStockRunTapsNobodyOutAndSaysNothing() {
        AgentLmpqSession session = farming(false);
        when(kes.getMapId()).thenReturn(AgentLmpqDefinition.EXIT_MAP);
        run.livePlayers(ben, mira, ann);

        AgentLmpqCoordinator.tickSession(session, 3_000L);

        assertTrue(session.departed(42));
        assertEquals(List.of(), tapOuts);
        verify(run.party, never()).sendPartyChat(any(), anyString());
    }

    @Test
    void aCompanionHeldDeadAtTheFloorIsLetOutAfterTheRecoveryTimeout() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, true,
                AgentLmpqSession.Phase.FARMING, ben, mira, kes);
        session.setMemberCoordinationChatEnabled(true);
        when(kes.getHp()).thenReturn(0);
        when(kes.getMapId()).thenReturn(AgentLmpqDefinition.mapForRoom(16));
        AgentLmpqDeathDepartureGate.EventView event = view(Set.of(40, 41, 42));
        AtomicBoolean respawned = new AtomicBoolean();

        assertFalse(AgentLmpqDeathDepartureGate.respawnOrHold(session, 42, "Kes",
                AgentLmpqDefinition.mapForRoom(16), event, () -> respawned.set(true), 3_000L), "held at three");
        AgentLmpqCoordinator.tickSession(session, 3_000L);
        assertEquals(3_000L, session.downSinceMs(42));
        AgentLmpqCoordinator.tickSession(session, 3_000L + AgentLmpqCompanionRun.MEMBER_RECOVERY_TIMEOUT_MS);
        assertTrue(session.holdExpired(42));

        assertTrue(AgentLmpqDeathDepartureGate.respawnOrHold(session, 42, "Kes", AgentLmpqDefinition.mapForRoom(16),
                event, () -> respawned.set(true), 3_100L + AgentLmpqCompanionRun.MEMBER_RECOVERY_TIMEOUT_MS));
        assertTrue(respawned.get());
        assertEquals("Kes", session.floorDeparture(), "the event ends with this departure");

        when(run.event.isEventDisposed()).thenReturn(true);
        AgentLmpqCoordinator.tickSession(session, 4_000L + AgentLmpqCompanionRun.MEMBER_RECOVERY_TIMEOUT_MS);
        assertEquals(AgentLmpqSession.Phase.FAILED, session.phase());
        verify(run.party).sendPartyChat(mira, "Kes went down and we're too few now. let's head out.");
    }

    private static AgentLmpqDeathDepartureGate.EventView view(Set<Integer> registered) {
        return new AgentLmpqDeathDepartureGate.EventView() {
            @Override
            public boolean disposed() { return false; }

            @Override
            public boolean cleared() { return false; }

            @Override
            public int leaderId() { return 40; }

            @Override
            public Set<Integer> registeredIds() { return registered; }
        };
    }

    @Test
    void theLeaderGoneOfflineIsNamedOnce() {
        AgentLmpqSession session = farming(true);
        when(run.characters.findOnlineCharacterById(40)).thenReturn(null);

        AgentLmpqCoordinator.tickSession(session, 3_000L);
        AgentLmpqCoordinator.tickSession(session, 3_500L);

        assertEquals(AgentLmpqSession.Phase.FAILED, session.phase());
        assertEquals(1, partyLines().stream().filter("Ben's gone. we'll head back."::equals).count());
    }

    @Test
    void theLeaderDownBeforeTheClearEndsItInTheirWords() {
        AgentLmpqSession session = farming(true);
        when(ben.getHp()).thenReturn(0);

        AgentLmpqCoordinator.tickSession(session, 3_000L);

        assertEquals(AgentLmpqSession.Phase.FAILED, session.phase());
        verify(run.party).sendPartyChat(any(), org.mockito.ArgumentMatchers.eq(
                "Ben, we can't finish without you. let's head back."));
    }

    @Test
    void afterTheClearACompanionRunNeverFailsForItsLeader() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, true,
                AgentLmpqSession.Phase.REWARD, ben, mira, kes);
        session.setMemberCoordinationChatEnabled(true);
        when(run.event.isEventCleared()).thenReturn(true);
        when(ben.getHp()).thenReturn(0);
        when(ben.getMapId()).thenReturn(AgentLmpqDefinition.REWARD_MAP);
        when(mira.getMapId()).thenReturn(AgentLmpqDefinition.REWARD_MAP);

        AgentLmpqCoordinator.tickSession(session, 3_000L);

        assertEquals(AgentLmpqSession.Phase.REWARD, session.phase());
        verify(run.party, never()).sendPartyChat(any(), anyString());
    }

    @Test
    void afterTheClearALeaderGoneOfflineIsNoFailureAndTheRunCompletesOnceItsCompanionsAreOut() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, true,
                AgentLmpqSession.Phase.REWARD, ben, mira, kes);
        session.setMemberCoordinationChatEnabled(true);
        when(run.event.isEventCleared()).thenReturn(true);
        when(run.characters.findOnlineCharacterById(40)).thenReturn(null);
        when(mira.getMapId()).thenReturn(AgentLmpqDefinition.REWARD_MAP);
        when(kes.getMapId()).thenReturn(AgentLmpqDefinition.EXIT_MAP);

        AgentLmpqCoordinator.tickSession(session, 3_000L);

        assertEquals(AgentLmpqSession.Phase.REWARD, session.phase(), "Mira is still claiming at Rolly");
        when(mira.getMapId()).thenReturn(AgentLmpqDefinition.EXIT_MAP);

        AgentLmpqCoordinator.tickSession(session, 3_500L);

        assertEquals(AgentLmpqSession.Phase.COMPLETED, session.phase(), "cleared, not 'LMPQ leader left'");
        verify(run.party, never()).sendPartyChat(any(), anyString());
    }

    @Test
    void afterTheClearWithItsLeaderOfflineACompanionStuckAtRollyStillEndsTheRunAsAStall() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, true,
                AgentLmpqSession.Phase.REWARD, ben, mira, kes);
        when(run.event.isEventCleared()).thenReturn(true);
        when(run.characters.findOnlineCharacterById(40)).thenReturn(null);
        when(mira.getMapId()).thenReturn(AgentLmpqDefinition.REWARD_MAP);
        when(kes.getMapId()).thenReturn(AgentLmpqDefinition.REWARD_MAP);

        AgentLmpqCoordinator.tickSession(session, 3_000L);
        AgentLmpqCoordinator.tickSession(session, 3_000L + 120_000L);

        assertEquals(AgentLmpqSession.Phase.FAILED, session.phase());
        assertEquals("LMPQ reward stalled", session.failure(), "REWARD_STALL_MS still bounds it");
        verify(run.party, never()).sendPartyChat(any(), anyString());
    }

    @Test
    void theLeaderWalkingOutOfTheMazeIsTold() {
        AgentLmpqSession session = farming(true);
        when(ben.getMapId()).thenReturn(AgentLmpqDefinition.RECRUIT_MAP);

        AgentLmpqCoordinator.tickSession(session, 3_000L);

        verify(run.party).sendPartyChat(any(), org.mockito.ArgumentMatchers.eq(
                "Ben, you left the maze, so that's it for this one."));
    }

    @Test
    void theTimerIsSaidInItsWords() {
        AgentLmpqSession session = farming(true);
        AgentLmpqCoordinator.tickSession(session, 1_000_000L);
        verify(run.party).sendPartyChat(any(), org.mockito.ArgumentMatchers.eq(
                "out of time... the maze kicked us out."));
    }

    @Test
    void aStallIsSaidInItsWords() {
        AgentLmpqSession stalled = run.unregistered(AgentLmpqSession.Mode.HUMAN_LEADER, true,
                AgentLmpqSession.Phase.FARMING, ben, mira, kes);
        stalled.transition(AgentLmpqSession.Phase.CLEARING, 2_000L);
        stalled.setMemberCoordinationChatEnabled(true);
        AgentLmpqSessionRegistry.registerComplete(stalled);
        AgentLmpqCoordinator.tickSession(stalled, 2_100L);
        AgentLmpqCoordinator.tickSession(stalled, 2_100L + AgentLmpqCoordinator.humanIdleLimitMs());
        assertEquals("LMPQ stalled in CLEARING", stalled.failure());
        verify(run.party).sendPartyChat(any(), org.mockito.ArgumentMatchers.eq("we're stuck. let's call it."));
    }

    @Test
    void aStockRunEndsSilentlyAsBefore() {
        AgentLmpqSession session = farming(false);
        when(ben.getHp()).thenReturn(0);

        AgentLmpqCoordinator.tickSession(session, 3_000L);

        assertEquals(AgentLmpqSession.Phase.FAILED, session.phase());
        verify(run.party, never()).sendPartyChat(any(), anyString());
    }
}
