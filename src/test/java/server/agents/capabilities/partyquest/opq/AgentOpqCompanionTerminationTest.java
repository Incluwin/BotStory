package server.agents.capabilities.partyquest.opq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.capabilities.partyquest.pace.AgentPqPace;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P6h OPQ step 1 (C5), what a release leaves behind: the companions-only exit, and the lines every release writes
 * ('OPQ session failure:' with the live phase, a companion run's 'OPQ potions:' start and end, and 'OPQ session
 * released:' last), each written so that a failing line never stops the release. A companion run reports its pace to
 * the host; a stock run reports none.
 */
class AgentOpqCompanionTerminationTest {
    private final OpqRunFixture run = new OpqRunFixture();
    private final Character ben = run.human(1, "Ben");
    private final Character mira = run.companion(2, "Mira");
    private final Character kes = run.companion(3, "Kes");
    private final Character pel = run.companion(4, "Pel");
    private final Character hal = run.companion(5, "Hal");

    @AfterEach
    void tearDown() {
        run.close();
    }

    private AgentOpqSession fiveMemberRun(boolean companionRun, AgentOpqSession.Phase phase) {
        return run.session(AgentOpqSession.Mode.HUMAN_ASSISTED, companionRun, phase, ben, mira, kes, pel, hal);
    }

    @Test
    void exitAgentsOnlyTakesOutTheCompanionsStillInTheEventAndGoesOnPastAFailure() {
        AgentOpqSession session = fiveMemberRun(true, AgentOpqSession.Phase.RESTORING_MINERVA);
        when(run.event.getPlayers()).thenReturn(List.of(ben, mira, kes, hal)); // Pel already left
        doThrow(new IllegalStateException("map busy")).when(run.event).exitPlayer(kes);

        try (OpqLogLines lines = new OpqLogLines(AgentOpqTerminationService.class)) {
            AgentOpqTerminationService.exitAgentsOnly(session);

            assertEquals(List.of("OPQ companion exit failed: event=OrbisPQ_test member=3"),
                    lines.startingWith("OPQ companion exit failed:"));
        }
        verify(run.event).exitPlayer(mira);
        verify(run.event).exitPlayer(hal);
        verify(run.event, never()).exitPlayer(ben);
        verify(run.event, never()).exitPlayer(pel);
        verify(run.event, never()).dispose();
    }

    @Test
    void aCompanionRunsReleaseLogsItsFailureItsPotionsAndThenItsRelease() {
        AgentOpqSession session = fiveMemberRun(true, AgentOpqSession.Phase.GARDEN);
        session.setPotionsAtEntry("{Mira:40/50, Kes:40/50, Pel:40/50, Hal:40/50}");
        when(run.actions.hpPotionCount(any())).thenReturn(12);
        when(run.actions.mpPotionCount(any())).thenReturn(3);
        String id = session.sessionId();

        try (OpqLogLines lines = new OpqLogLines(AgentOpqTerminationService.class)) {
            AgentOpqTerminationService.fail(session, "OPQ leader unavailable", 5_000L);

            assertEquals(List.of(
                    "OPQ session failure: session=" + id + " phase=GARDEN reason=OPQ leader unavailable"
                            + " pastTheClear=false companionRun=true members=5",
                    "OPQ potions: session=" + id + " start={Mira:40/50, Kes:40/50, Pel:40/50, Hal:40/50}"
                            + " end={Mira:12/3, Kes:12/3, Pel:12/3, Hal:12/3}",
                    "OPQ session released: session=" + id + " outcome=OPQ leader unavailable members=5"
                            + " companionRun=true"),
                    lines.all().stream().filter(line -> line.startsWith("OPQ session") || line.startsWith("OPQ potions"))
                            .toList());
        }
    }

    @Test
    void aStockRunsReleaseLogsNoPotionsAndAStockCompletionNoFailure() {
        AgentOpqSession failed = run.session(AgentOpqSession.Mode.HUMAN_ASSISTED, false,
                AgentOpqSession.Phase.SPLIT_ROOMS, ben, mira, kes, pel, hal, run.companion(6, "Gus"));
        try (OpqLogLines lines = new OpqLogLines(AgentOpqTerminationService.class)) {
            AgentOpqTerminationService.fail(failed, "No legal OPQ progress before timeout", 5_000L);
            assertEquals(List.of(), lines.startingWith("OPQ potions:"));
            assertEquals(List.of("OPQ session failure: session=" + failed.sessionId() + " phase=SPLIT_ROOMS"
                    + " reason=No legal OPQ progress before timeout pastTheClear=false companionRun=false members=6"),
                    lines.startingWith("OPQ session failure:"));
            assertEquals(List.of("OPQ session released: session=" + failed.sessionId()
                            + " outcome=No legal OPQ progress before timeout members=6 companionRun=false"),
                    lines.startingWith("OPQ session released:"));
        }

        AgentOpqSession completed = fiveMemberRun(true, AgentOpqSession.Phase.CLAIMING_REWARD);
        completed.complete(6_000L);
        try (OpqLogLines lines = new OpqLogLines(AgentOpqTerminationService.class)) {
            AgentOpqRuntime.requestStop(mira.getId(), "activity admitted", 7_000L);
            assertEquals(List.of(), lines.startingWith("OPQ session failure:"));
            assertEquals(List.of("OPQ session released: session=" + completed.sessionId()
                            + " outcome=completed members=5 companionRun=true"),
                    lines.startingWith("OPQ session released:"));
        }
    }

    @Test
    void aPotionCountThatThrowsStillReleasesTheRun() {
        AgentOpqSession session = fiveMemberRun(true, AgentOpqSession.Phase.GARDEN);
        when(run.actions.hpPotionCount(any())).thenThrow(new IllegalStateException("inventory locked"));

        try (OpqLogLines lines = new OpqLogLines(AgentOpqTerminationService.class)) {
            AgentOpqTerminationService.fail(session, "OPQ leader unavailable", 5_000L);
            assertEquals(1, lines.startingWith("OPQ cleanup failed but release will continue:").size());
            assertEquals(1, lines.startingWith("OPQ session released:").size());
        }
        assertEquals(AgentOpqSession.Phase.FAILED, session.phase());
        assertNull(AgentOpqSessionRegistry.forMember(mira.getId()));
        verify(run.event).dispose();
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(
                eq(session.sessionId()), eq(false), eq("OPQ leader unavailable"), eq(5_000L), any()));
    }

    @Test
    void aCompanionRunReportsItsPaceAndAStockRunNone() {
        AtomicReference<AgentPqPace> reported = new AtomicReference<>();
        run.lifecycle.when(() -> AgentPartyQuestLifecycleRuntime.childFinished(
                anyString(), anyBoolean(), anyString(), anyLong(), any())).thenAnswer(call -> {
                    reported.set(call.getArgument(4));
                    return null;
                });
        AgentOpqSession companion = fiveMemberRun(true, AgentOpqSession.Phase.ENTRANCE);
        AgentOpqTerminationService.fail(companion, "OPQ leader unavailable", 9_000L);
        assertNotNull(reported.get(), "a companion run reports its pace");
        assertEquals("opq", reported.get().questKey());
        assertEquals(companion.sessionId(), reported.get().sessionId());

        reported.set(null);
        AgentOpqSession stock = run.session(AgentOpqSession.Mode.HUMAN_ASSISTED, false,
                AgentOpqSession.Phase.ENTRANCE, ben, mira, kes, pel, hal, run.companion(6, "Gus"));
        AgentOpqTerminationService.fail(stock, "OPQ leader unavailable", 9_000L);
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(
                eq(stock.sessionId()), eq(false), anyString(), anyLong(), isNull()));
        assertNull(reported.get(), "a stock run reports none");
    }

    @Test
    void exitAgentsOnlySkipsACompanionAClaimHasAlreadyTakenOut() {
        AgentOpqSession session = fiveMemberRun(true, AgentOpqSession.Phase.CLAIMING_REWARD);
        when(run.event.getPlayers()).thenReturn(List.of(ben, mira, kes, pel, hal)); // read before Kes's claim landed
        when(kes.getEventInstance()).thenReturn(null); // Minerva has warped Kes out since

        AgentOpqTerminationService.exitAgentsOnly(session);

        verify(run.event, never()).exitPlayer(kes);
        verify(run.event).exitPlayer(mira);
        verify(run.event).exitPlayer(pel);
        verify(run.event).exitPlayer(hal);
        verify(run.event, never()).exitPlayer(ben);
    }

    @Test
    void aMemberWhoseStopThrowsStillLetsTheRunBeReleased() {
        AgentOpqSession session = fiveMemberRun(true, AgentOpqSession.Phase.GARDEN);
        var kesEntry = run.entries.get(kes.getId());
        doThrow(new IllegalStateException("runtime gone")).when(run.actions).stop(kesEntry);

        try (OpqLogLines lines = new OpqLogLines(AgentOpqTerminationService.class)) {
            AgentOpqTerminationService.fail(session, "OPQ leader unavailable", 5_000L);

            assertEquals(List.of("OPQ cleanup failed but release will continue: session=" + session.sessionId()
                    + " operation=stop-3"), lines.startingWith("OPQ cleanup failed"));
        }
        verify(run.actions).stop(run.entries.get(pel.getId())); // the members after him are still stopped
        assertEquals(AgentOpqSession.Phase.FAILED, session.phase());
        assertNull(AgentOpqSessionRegistry.forMember(mira.getId()));
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(
                eq(session.sessionId()), eq(false), eq("OPQ leader unavailable"), eq(5_000L), any()));
    }

    @Test
    void aMemberWhosePartyLeaveThrowsStillLetsTheRunBeReleased() {
        AgentOpqSession session = run.session(AgentOpqSession.Mode.HUMAN_ASSISTED, false,
                AgentOpqSession.Phase.SPLIT_ROOMS, ben, mira, kes, pel, hal, run.companion(6, "Gus"));
        doThrow(new IllegalStateException("party busy")).when(run.party).leaveCurrentParty(kes);

        AgentOpqTerminationService.fail(session, "No legal OPQ progress before timeout", 5_000L);

        verify(run.party).leaveCurrentParty(pel); // the members after him still leave
        verify(run.party).leaveCurrentParty(hal);
        assertEquals(AgentOpqSession.Phase.FAILED, session.phase());
        assertNull(AgentOpqSessionRegistry.forMember(mira.getId()));
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(
                eq(session.sessionId()), eq(false), anyString(), eq(5_000L), any()));
    }
}
