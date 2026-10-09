package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P6h LMPQ step 1, C5: how a companion run is let go, through the runtime entry that ends it. It keeps its owner's
 * party whatever the outcome; past the clear (the event's own cleared flag, never the phase) only its companions leave
 * and the event is disposed only once nobody is left in it; before the clear it is disposed whoever ends it, a stop
 * request included (its party is kept, so nobody else would end the event). A completed one disposes nothing.
 */
class AgentLmpqTerminationPolicyTest {
    private final LmpqRunFixture run = new LmpqRunFixture();
    private final Character ben = run.human(1, "Ben");
    private final Character mira = run.companion(2, "Mira");
    private final Character kes = run.companion(3, "Kes");
    private final Character pel = run.companion(4, "Pel");

    @AfterEach
    void tearDown() {
        run.close();
    }

    private AgentLmpqSession companionRun(AgentLmpqSession.Phase phase) {
        return run.session(AgentLmpqSession.Mode.HUMAN_LEADER, true, phase, ben, mira, kes, pel);
    }

    private void verifyNoPartyChange() {
        verify(run.party, never()).leaveCurrentParty(any());
    }

    @Test
    void aCompanionRunKeepsItsPartyAndOtherRunsLeaveIt() {
        assertTrue(AgentLmpqTerminationService.keepPartyAfterRelease(true));
        assertFalse(AgentLmpqTerminationService.keepPartyAfterRelease(false));
    }

    @Test
    void theClearIsTheClearedEventNeverThePhase() {
        AgentLmpqSession session = companionRun(AgentLmpqSession.Phase.REWARD);
        session.fail("LMPQ stalled in REWARD", 5_000L);
        assertFalse(AgentLmpqTerminationService.pastTheClear(session), "a failed run in REWARD is not cleared");
        when(run.event.isEventCleared()).thenReturn(true);
        assertTrue(AgentLmpqTerminationService.pastTheClear(session));
        session.clearEventInstance();
        assertFalse(AgentLmpqTerminationService.pastTheClear(session), "no event, no clear");
        assertFalse(AgentLmpqTerminationService.pastTheClear(null));
    }

    @Test
    void aCompletedCompanionRunIsReleasedWithoutADisposeAndKeepsItsParty() {
        AgentLmpqSession session = companionRun(AgentLmpqSession.Phase.REWARD);
        when(run.event.isEventCleared()).thenReturn(true);
        session.complete(5_000L);

        AgentLmpqRuntime.tick(run.entry(mira), mira, 6_000L);

        verify(run.event, never()).dispose();
        verify(run.event, never()).exitPlayer(any());
        verifyNoPartyChange();
        assertEquals(AgentLmpqSession.Phase.COMPLETED, session.phase());
        assertNull(AgentLmpqSessionRegistry.forMember(mira.getId()));
    }

    @Test
    void aCompanionRunFailingAfterTheClearLetsOnlyItsCompanionsGo() {
        AgentLmpqSession session = companionRun(AgentLmpqSession.Phase.REWARD);
        when(run.event.isEventCleared()).thenReturn(true);
        run.livePlayers(ben, mira, kes, pel);
        session.fail("LMPQ stalled in REWARD", 5_000L);

        AgentLmpqRuntime.tick(run.entry(mira), mira, 6_000L);

        for (Character companion : List.of(mira, kes, pel)) verify(run.event).exitPlayer(companion);
        verify(run.event, never()).exitPlayer(ben);
        verify(run.event, never()).dispose();
        verifyNoPartyChange();
        assertEquals(AgentLmpqSession.Phase.FAILED, session.phase());
        assertNull(AgentLmpqSessionRegistry.forMember(ben.getId()));
    }

    @Test
    void aCompanionRunFailingBeforeTheClearIsDisposedForEveryone() {
        AgentLmpqSession session = companionRun(AgentLmpqSession.Phase.FARMING);
        session.fail("LMPQ stalled in FARMING", 5_000L);

        AgentLmpqRuntime.tick(run.entry(kes), kes, 6_000L);

        for (Character member : List.of(ben, mira, kes, pel)) verify(run.event).exitPlayer(member);
        verify(run.event).dispose();
        verifyNoPartyChange();
    }

    @Test
    void aStopRequestDisposesAFailedCompanionRunBeforeTheClear() {
        AgentLmpqSession session = companionRun(AgentLmpqSession.Phase.CLEARING);
        session.fail("LMPQ stalled in CLEARING", 5_000L);

        assertTrue(AgentLmpqRuntime.requestStop(pel.getId(), "owner logged out", 6_000L));

        verify(run.event).exitPlayer(ben);
        verify(run.event).dispose();
        verifyNoPartyChange();
        assertNull(AgentLmpqSessionRegistry.forMember(pel.getId()));
    }

    @Test
    void aStopRequestAfterTheClearTakesOnlyTheCompanionsOut() {
        AgentLmpqSession session = companionRun(AgentLmpqSession.Phase.REWARD);
        when(run.event.isEventCleared()).thenReturn(true);
        run.livePlayers(ben, mira, kes, pel);
        session.fail("LMPQ reward stalled", 5_000L);

        assertTrue(AgentLmpqRuntime.requestStop(pel.getId(), "owner logged out", 6_000L));

        verify(run.event).exitPlayer(pel);
        verify(run.event, never()).exitPlayer(ben);
        verify(run.event, never()).dispose();
    }

    @Test
    void aForcedStopRoutesACompanionRunByTheClear() {
        AgentLmpqSession before = companionRun(AgentLmpqSession.Phase.CLEARING);
        AgentLmpqRuntime.forceStop(kes.getId(), "Agent runtime removed", 6_000L);
        verify(run.event).exitPlayer(ben);
        verify(run.event).dispose();
        assertEquals("Agent runtime removed", before.failure());
        verifyNoPartyChange();
    }

    @Test
    void aForcedStopAfterTheClearTakesOnlyTheCompanionsOut() {
        companionRun(AgentLmpqSession.Phase.REWARD);
        when(run.event.isEventCleared()).thenReturn(true);
        run.livePlayers(ben, mira, kes, pel);

        AgentLmpqRuntime.forceStop(kes.getId(), "Agent runtime removed", 6_000L);

        verify(run.event).exitPlayer(kes);
        verify(run.event, never()).exitPlayer(ben);
        verify(run.event, never()).dispose();
    }

    @Test
    void failAfterClearDoesNothingOnceAReleaseHasBegun() {
        AgentLmpqSession session = companionRun(AgentLmpqSession.Phase.REWARD);
        when(run.event.isEventCleared()).thenReturn(true);
        assertTrue(session.beginTermination()); // another release is under way

        AgentLmpqTerminationService.failAfterClear(session, "stalled after the clear", 5_000L);
        AgentLmpqTerminationService.failAfterClear(null, "no session", 5_000L);

        verify(run.event, never()).exitPlayer(any());
        verify(run.event, never()).dispose();
        assertEquals(AgentLmpqSession.Phase.REWARD, session.phase());
    }

    @Test
    void aRunIsReleasedOnceAfterTheClear() {
        AgentLmpqSession session = companionRun(AgentLmpqSession.Phase.REWARD);
        when(run.event.isEventCleared()).thenReturn(true);

        AgentLmpqTerminationService.failAfterClear(session, "stalled after the clear", 5_000L);
        AgentLmpqTerminationService.failAfterClear(session, "stalled again", 6_000L);
        AgentLmpqTerminationService.finish(session, "and again", 7_000L, true);

        verify(run.event, times(1)).exitPlayer(mira);
        assertEquals("stalled after the clear", session.failure());
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(
                anyString(), anyBoolean(), anyString(), anyLong(), any()), times(1));
    }

    @Test
    void anEventLeftEmptyAfterTheClearIsDisposedAndOneWithAPlayerIsNot() {
        AgentLmpqSession empty = companionRun(AgentLmpqSession.Phase.REWARD);
        when(run.event.isEventCleared()).thenReturn(true);
        List<Character> held = run.livePlayers(mira, kes, pel); // Ben already claimed and left

        AgentLmpqTerminationService.failAfterClear(empty, "LMPQ reward stalled", 5_000L);

        assertTrue(held.isEmpty());
        verify(run.event, times(1)).dispose();
    }

    @Test
    void anEventStillHoldingAHumanIsLeftToHim() {
        AgentLmpqSession session = companionRun(AgentLmpqSession.Phase.REWARD);
        when(run.event.isEventCleared()).thenReturn(true);
        List<Character> held = run.livePlayers(ben, mira, kes, pel);

        AgentLmpqTerminationService.failAfterClear(session, "LMPQ reward stalled", 5_000L);

        assertEquals(List.of(ben), held);
        verify(run.event, never()).dispose();
    }
}
