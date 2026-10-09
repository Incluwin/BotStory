package server.agents.capabilities.partyquest.opq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;

import java.awt.Point;
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
 * P6h OPQ step 1 (C5), the release policy of a companion run: its party is its owner's and is always kept; a failure
 * after the clear (the event cleared, Minerva freed, or the run claiming) takes only the companions out and leaves the
 * event and the human to the jail and the reward; any other failure is released as today. The tick, a stop request
 * and a force stop all route through it, and a run is released once. (OB5 later gave stock runs the same after-clear
 * rule.)
 */
class AgentOpqTerminationPolicyTest {
    private final OpqRunFixture run = new OpqRunFixture();
    private final Character ben = run.human(1, "Ben");
    private final Character mira = run.companion(2, "Mira");
    private final Character kes = run.companion(3, "Kes");
    private final Character pel = run.companion(4, "Pel");
    private final Character hal = run.companion(5, "Hal");
    private final Character gus = run.companion(6, "Gus");
    private final Character[] companions = {mira, kes, pel, hal, gus};

    @AfterEach
    void tearDown() {
        run.close();
    }

    private AgentOpqSession companionRun(AgentOpqSession.Phase phase) {
        return run.session(AgentOpqSession.Mode.HUMAN_ASSISTED, true, phase, ben, mira, kes, pel, hal, gus);
    }

    private AgentOpqSession stockRun(AgentOpqSession.Phase phase) {
        return run.session(AgentOpqSession.Mode.HUMAN_ASSISTED, false, phase, ben, mira, kes, pel, hal, gus);
    }

    private void minervaFreed() {
        when(run.event.getProperty("statusStg8")).thenReturn("1");
    }

    private void verifyCompanionsOnlyLeft() {
        for (Character companion : companions) verify(run.event).exitPlayer(companion);
        verify(run.event, never()).exitPlayer(ben);
        verify(run.event, never()).dispose();
    }

    private void verifyEveryoneLeftAndDisposed() {
        verify(run.event).exitPlayer(ben);
        for (Character companion : companions) verify(run.event).exitPlayer(companion);
        verify(run.event).dispose();
    }

    @Test
    void aCompanionRunKeepsItsPartyAndOtherRunsDoNot() {
        assertTrue(AgentOpqTerminationService.keepPartyAfterRelease(true));
        assertFalse(AgentOpqTerminationService.keepPartyAfterRelease(false));
    }

    @Test
    void theClearIsTheClearedEventMinervaFreedOrTheClaim() {
        AgentOpqSession session = companionRun(AgentOpqSession.Phase.RESTORING_MINERVA);
        // OrbisPQ's properties start at -1, and getIntProperty throws on a key that was never set.
        when(run.event.getIntProperty("statusStg8")).thenThrow(new NumberFormatException("null"));
        assertFalse(AgentOpqTerminationService.pastTheClear(session), "a missing statusStg8 is not the clear");
        when(run.event.getProperty("statusStg8")).thenReturn("-1");
        assertFalse(AgentOpqTerminationService.pastTheClear(session));
        minervaFreed();
        assertTrue(AgentOpqTerminationService.pastTheClear(session));
        when(run.event.getProperty("statusStg8")).thenReturn(null);
        when(run.event.isEventCleared()).thenReturn(true);
        assertTrue(AgentOpqTerminationService.pastTheClear(session));
        when(run.event.isEventCleared()).thenReturn(false);
        session.transition(AgentOpqSession.Phase.CLAIMING_REWARD, 9_000L);
        assertTrue(AgentOpqTerminationService.pastTheClear(session), "the claim is after the clear");
        session.clearEventInstance();
        assertTrue(AgentOpqTerminationService.pastTheClear(session));

        AgentOpqSession unbound = new AgentOpqSession(AgentOpqSession.Mode.HUMAN_ASSISTED, 7L, 99, 1_000L);
        unbound.transition(AgentOpqSession.Phase.GARDEN, 2_000L);
        assertFalse(AgentOpqTerminationService.pastTheClear(unbound), "no event bound and not claiming");
    }

    @Test
    void aCompanionRunFailingAfterTheClearLetsOnlyItsCompanionsGo() {
        AgentOpqSession session = companionRun(AgentOpqSession.Phase.RESTORING_MINERVA);
        minervaFreed();

        AgentOpqTerminationService.fail(session, "No legal OPQ progress before timeout", 5_000L);

        verifyCompanionsOnlyLeft();
        verify(run.party, never()).leaveCurrentParty(any());
        assertEquals(AgentOpqSession.Phase.FAILED, session.phase());
        assertNull(AgentOpqSessionRegistry.forMember(ben.getId()));
    }

    @Test
    void aCompanionRunFailingBeforeTheClearIsReleasedAsTodayButKeepsItsParty() {
        AgentOpqSession session = companionRun(AgentOpqSession.Phase.GARDEN);

        AgentOpqTerminationService.fail(session, "OPQ leader unavailable", 5_000L);

        verifyEveryoneLeftAndDisposed();
        verify(run.party, never()).leaveCurrentParty(any());
    }

    @Test
    void aStockRunFailingAfterTheClearLetsOnlyItsAgentsGoButLeavesTheParty() {
        // OB5 (all modes): after the clear only the Agents leave the event; a stock run's Agents still leave the party.
        AgentOpqSession session = stockRun(AgentOpqSession.Phase.CLAIMING_REWARD);
        minervaFreed();
        when(run.event.isEventCleared()).thenReturn(true);

        AgentOpqTerminationService.fail(session, "No legal OPQ progress before timeout", 5_000L);

        verifyCompanionsOnlyLeft();
        verify(run.party).leaveCurrentParty(mira);
        verify(run.party, never()).leaveCurrentParty(ben);
    }

    @Test
    void aStockRunFailingBeforeTheClearIsReleasedAsToday() {
        AgentOpqSession session = stockRun(AgentOpqSession.Phase.SPLIT_ROOMS);

        AgentOpqTerminationService.fail(session, "No legal OPQ progress before timeout", 5_000L);

        verifyEveryoneLeftAndDisposed();
        verify(run.party).leaveCurrentParty(mira);
    }

    @Test
    void failAfterClearClaimsFirstAndARunIsReleasedOnce() {
        AgentOpqSession session = companionRun(AgentOpqSession.Phase.CLAIMING_REWARD);

        AgentOpqTerminationService.failAfterClear(session, "stalled after the clear", 5_000L);
        AgentOpqTerminationService.failAfterClear(session, "stalled again", 6_000L);
        AgentOpqTerminationService.fail(session, "and again", 7_000L);
        AgentOpqRuntime.forceStop(mira.getId(), "Agent runtime removed", 8_000L);

        verify(run.event, times(1)).exitPlayer(mira);
        verify(run.event, never()).dispose();
        assertEquals("stalled after the clear", session.failure());
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(
                anyString(), anyBoolean(), anyString(), anyLong(), any()), times(1));
    }

    @Test
    void failAfterClearDoesNothingOnceAReleaseHasBegun() {
        AgentOpqSession session = companionRun(AgentOpqSession.Phase.CLAIMING_REWARD);
        assertTrue(session.beginTermination()); // another release is under way

        AgentOpqTerminationService.failAfterClear(session, "stalled after the clear", 5_000L);
        AgentOpqTerminationService.failAfterClear(null, "no session", 5_000L);

        verify(run.event, never()).exitPlayer(any());
        assertEquals(AgentOpqSession.Phase.CLAIMING_REWARD, session.phase());
    }

    @Test
    void failAfterClearDisposesAnEventItLeavesWithNoPlayer() {
        AgentOpqSession session = companionRun(AgentOpqSession.Phase.CLAIMING_REWARD);
        // Ben has already claimed and left; once his companions are out, nobody is left.
        when(run.event.getPlayers()).thenReturn(List.of(companions), List.of());

        AgentOpqTerminationService.fail(session, "OPQ leader unavailable", 5_000L);

        for (Character companion : companions) verify(run.event).exitPlayer(companion);
        verify(run.event).dispose();
        verify(run.party, never()).leaveCurrentParty(any());
    }

    @Test
    void aStopRequestEndsATerminalCompanionRunAsACompanionRun() {
        AgentOpqSession beforeTheClear = companionRun(AgentOpqSession.Phase.SPLIT_ROOMS);
        beforeTheClear.fail("OPQ leader unavailable", 4_000L); // failed, not yet released

        assertTrue(AgentOpqRuntime.requestStop(mira.getId(), "activity admitted", 5_000L));

        verifyEveryoneLeftAndDisposed();
        verify(run.party, never()).leaveCurrentParty(any());
        assertNull(AgentOpqSessionRegistry.forMember(mira.getId()));
    }

    @Test
    void aStopRequestAfterTheClearLetsOnlyTheCompanionsGo() {
        AgentOpqSession session = companionRun(AgentOpqSession.Phase.RESTORING_MINERVA);
        minervaFreed();
        session.fail("stalled", 4_000L);

        assertTrue(AgentOpqRuntime.requestStop(kes.getId(), "activity admitted", 5_000L));

        verifyCompanionsOnlyLeft();
    }

    @Test
    void aStopRequestReleasesACompletedCompanionRunWithoutAnExit() {
        AgentOpqSession session = companionRun(AgentOpqSession.Phase.CLAIMING_REWARD);
        session.complete(4_000L);

        assertTrue(AgentOpqRuntime.requestStop(kes.getId(), "activity admitted", 5_000L));

        verify(run.event, never()).exitPlayer(any());
        verify(run.event, never()).dispose();
        verify(run.party, never()).leaveCurrentParty(any());
        assertEquals(AgentOpqSession.Phase.COMPLETED, session.phase());
        assertFalse(AgentOpqRuntime.requestStop(kes.getId(), "again", 6_000L), "nothing is left to stop");
    }

    @Test
    void aForceStopRoutesACompanionRun() {
        AgentOpqSession after = companionRun(AgentOpqSession.Phase.RESTORING_MINERVA);
        minervaFreed();

        AgentOpqRuntime.forceStop(pel.getId(), "Agent runtime removed", 5_000L);

        verifyCompanionsOnlyLeft();
        assertEquals(AgentOpqSession.Phase.FAILED, after.phase());
        assertEquals("Agent runtime removed", after.failure());
    }

    @Test
    void theTickLeavesACompanionRunOnItsWayOutAlone() {
        AgentOpqSession leaving = companionRun(AgentOpqSession.Phase.PREPARING);
        AgentOpqSession stock = run.session(AgentOpqSession.Mode.AUTONOMOUS, false, AgentOpqSession.Phase.PREPARING,
                run.companion(21, "Ann"), run.companion(22, "Bo"), run.companion(23, "Cy"),
                run.companion(24, "Di"), run.companion(25, "Ed"), run.companion(26, "Fy"));
        leaving.beginTermination();
        stock.beginTermination();

        AgentOpqCoordinator.tickSession(leaving, 3_000L);
        AgentOpqCoordinator.tickSession(stock, 3_000L);

        assertEquals(AgentOpqSession.Phase.PREPARING, leaving.phase(), "a leaving companion run is not ticked");
        assertEquals(AgentOpqSession.Phase.ENTERING, stock.phase(), "a stock run is ticked as before");
    }

    @Test
    void theMemberTickLeavesACompanionRunOnItsWayOutAlone() {
        AgentOpqSession leaving = companionRun(AgentOpqSession.Phase.ENTRANCE);
        when(mira.getPosition()).thenReturn(new Point(0, 0));
        leaving.member(mira.getId()).observeMovement(new Point(0, 0), 1L);

        AgentOpqCoordinator.tickMember(leaving, run.entries.get(mira.getId()), mira, 10_000_000L);
        verify(run.actions).refreshNavigation(run.entries.get(mira.getId()), mira); // a stalled worker, ticked

        leaving.beginTermination();
        leaving.member(mira.getId()).observeMovement(new Point(0, 0), 1L); // stalled again
        AgentOpqCoordinator.tickMember(leaving, run.entries.get(mira.getId()), mira, 20_000_000L);
        verify(run.actions, times(1)).refreshNavigation(any(), any());
    }

    @Test
    void theTicksStallAfterTheClearLetsOnlyTheCompanionsGo() {
        AgentOpqSession session = companionRun(AgentOpqSession.Phase.RESTORING_MINERVA);
        minervaFreed();

        // No progress since 2 000 ms, one millisecond past the coordinator's phase timeout.
        AgentOpqCoordinator.tickSession(session, 2_000L + AgentOpqCoordinator.phaseTimeoutMs() + 1L);

        assertEquals("No legal OPQ progress before timeout", session.failure());
        verifyCompanionsOnlyLeft();
        verify(run.party, never()).leaveCurrentParty(any());
    }
}
