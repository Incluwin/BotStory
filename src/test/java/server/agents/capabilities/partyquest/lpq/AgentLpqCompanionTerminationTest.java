package server.agents.capabilities.partyquest.lpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.capabilities.partyquest.pace.AgentPqPace;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P6d LPQ step 1 (C6), how an LPQ run is released and what it says: exitAgentsOnly takes only a companion run's
 * companions out of the event; every release logs 'LPQ session released:' and every failure 'LPQ session failure:'
 * (all modes); a companion run also logs its companions' potions ('LPQ potions: ... start= end=') and hands its pace
 * to the host. None of the new lines can stop a release.
 */
class AgentLpqCompanionTerminationTest {
    private final LpqRunFixture run = new LpqRunFixture();
    private final Character ben = run.human(1, "Ben");
    private final Character mira = run.companion(2, "Mira");
    private final Character kes = run.companion(3, "Kes");
    private final Character pel = run.companion(4, "Pel");
    private final Character hal = run.companion(5, "Hal");
    private final Character cas = run.companion(6, "Cas");

    @AfterEach
    void tearDown() {
        run.close();
    }

    private AgentLpqSession companionRun(AgentLpqSession.Phase phase) {
        return run.session(AgentLpqSession.Mode.PRODUCTION, true, phase, ben, mira, kes, pel, hal, cas);
    }

    private AgentLpqSession stockRun(AgentLpqSession.Phase phase) {
        return run.session(AgentLpqSession.Mode.PRODUCTION, false, phase, ben, mira, kes, pel, hal, cas);
    }

    @Test
    void exitAgentsOnlyTakesTheCompanionsOutAndLeavesTheEventAndItsHuman() {
        AgentLpqSession session = companionRun(AgentLpqSession.Phase.BONUS);
        doThrow(new IllegalStateException("not in the event")).when(run.event).exitPlayer(kes);

        AgentLpqTerminationService.exitAgentsOnly(session);

        for (Character companion : new Character[]{mira, kes, pel, hal, cas}) {
            verify(run.event).exitPlayer(companion);
        }
        verify(run.event, never()).exitPlayer(ben);
        verify(run.event, never()).dispose();
        assertEquals(AgentLpqSession.Phase.BONUS, session.phase(), "exitAgentsOnly releases nothing");
    }

    @Test
    void aCompanionFailureAfterTheClearExitsTheCompanionsKeepsThePartyAndDisposesNothing() {
        AgentLpqSession session = companionRun(AgentLpqSession.Phase.CLAIMING_REWARD);
        session.freezeRewardEligibility();

        AgentLpqTerminationService.fail(session, "The LPQ reward instance ended before all claims were resolved",
                5_000L);

        verify(run.event).exitPlayer(cas);
        verify(run.event, never()).exitPlayer(ben);
        verify(run.event, never()).dispose();
        verify(run.party, never()).leaveCurrentParty(any());
    }

    @Test
    void aCompanionFailureBeforeTheClearDisposesAsToday() {
        AgentLpqSession session = companionRun(AgentLpqSession.Phase.STAGE_4);

        AgentLpqTerminationService.fail(session, "The LPQ event timer expired", 5_000L);

        verify(run.event).exitPlayer(ben);
        verify(run.event).dispose();
    }

    @Test
    void aStockFailureAfterTheClearDisposesAsToday() {
        AgentLpqSession session = stockRun(AgentLpqSession.Phase.BONUS);
        session.freezeRewardEligibility();

        AgentLpqTerminationService.fail(session, "The LPQ event leader left the bound instance map", 5_000L);

        verify(run.event).exitPlayer(ben);
        verify(run.event).dispose();
    }

    @Test
    void everyReleaseIsLoggedAndEveryFailureToo() {
        AgentLpqSession failed = stockRun(AgentLpqSession.Phase.STAGE_3);
        try (LpqLogLines log = new LpqLogLines(AgentLpqTerminationService.class)) {
            AgentLpqTerminationService.fail(failed, "The LPQ event timer expired", 5_000L);

            assertEquals(List.of("LPQ session failure: session=" + failed.sessionId() + " phase=STAGE_3"
                            + " reason=The LPQ event timer expired pastTheClear=false companionRun=false members=6"),
                    log.startingWith("LPQ session failure: "));
            assertEquals(List.of("LPQ session released: session=" + failed.sessionId()
                            + " outcome=The LPQ event timer expired members=6 companionRun=false"),
                    log.startingWith("LPQ session released: "));
            assertEquals(List.of(), log.startingWith("LPQ potions: "), "a stock run logs no potions");
        }
    }

    @Test
    void aCompletedCompanionRunLogsItsPotionsAndNoFailure() {
        when(run.actions.hpPotionCount(mira)).thenReturn(12);
        when(run.actions.mpPotionCount(mira)).thenReturn(30);
        AgentLpqSession session = companionRun(AgentLpqSession.Phase.EXITING);
        session.setPotionsAtEntry("{Mira:40/50, Kes:40/50, Pel:40/50, Hal:40/50, Cas:40/50}");
        try (LpqLogLines log = new LpqLogLines(AgentLpqTerminationService.class)) {
            AgentLpqTerminationService.complete(session, 9_000L);

            assertEquals(List.of("LPQ potions: session=" + session.sessionId()
                            + " start={Mira:40/50, Kes:40/50, Pel:40/50, Hal:40/50, Cas:40/50}"
                            + " end={Mira:12/30, Kes:0/0, Pel:0/0, Hal:0/0, Cas:0/0}"),
                    log.startingWith("LPQ potions: "));
            assertEquals(List.of(), log.startingWith("LPQ session failure: "));
            assertEquals(List.of("LPQ session released: session=" + session.sessionId()
                            + " outcome=completed members=6 companionRun=true"),
                    log.startingWith("LPQ session released: "));
        }
    }

    @Test
    void aCompanionFailureAfterTheClearSaysSo() {
        AgentLpqSession session = companionRun(AgentLpqSession.Phase.BONUS);
        session.freezeRewardEligibility();
        try (LpqLogLines log = new LpqLogLines(AgentLpqTerminationService.class)) {
            AgentLpqTerminationService.fail(session, "No LPQ progress before phase timeout", 5_000L);

            assertEquals(List.of("LPQ session failure: session=" + session.sessionId() + " phase=BONUS"
                            + " reason=No LPQ progress before phase timeout pastTheClear=true companionRun=true"
                            + " members=6"),
                    log.startingWith("LPQ session failure: "));
            assertEquals(List.of("LPQ potions: session=" + session.sessionId()
                            + " start=null end={Mira:0/0, Kes:0/0, Pel:0/0, Hal:0/0, Cas:0/0}"),
                    log.startingWith("LPQ potions: "), "stage 1 never logged its start");
        }
    }

    @Test
    void aGatewayThatThrowsCannotStopTheRelease() {
        when(run.actions.hpPotionCount(any())).thenThrow(new IllegalStateException("inventory unavailable"));
        AgentLpqSession session = companionRun(AgentLpqSession.Phase.STAGE_5);
        try (LpqLogLines log = new LpqLogLines(AgentLpqTerminationService.class)) {
            AgentLpqTerminationService.fail(session, "The LPQ event timer expired", 5_000L);

            assertEquals(AgentLpqSession.Phase.FAILED, session.phase());
            assertNull(AgentLpqSessionRegistry.forMember(ben.getId()));
            verify(run.event).dispose();
            run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(eq(session.sessionId()),
                    eq(false), eq("The LPQ event timer expired"), eq(5_000L), any()));
            assertEquals(1, log.startingWith("LPQ cleanup failed but release will continue: session="
                    + session.sessionId() + " operation=log-potions").size());
            assertEquals(1, log.startingWith("LPQ session released: ").size());
        }
    }

    @Test
    void aCompanionRunHandsItsPaceToTheHostAndAStockRunNone() {
        AgentLpqSession companion = companionRun(AgentLpqSession.Phase.PREPARING);
        companion.transition(AgentLpqSession.Phase.ENTERING, 2_000L);
        companion.transition(AgentLpqSession.Phase.STAGE_1, 3_000L);
        companion.transition(AgentLpqSession.Phase.STAGE_2, 63_000L);
        AgentLpqTerminationService.fail(companion, "The LPQ event timer expired", 93_000L);

        ArgumentCaptor<AgentPqPace> pace = ArgumentCaptor.forClass(AgentPqPace.class);
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(eq(companion.sessionId()),
                eq(false), eq("The LPQ event timer expired"), eq(93_000L), pace.capture()));
        assertNotNull(pace.getValue());
        assertEquals("lpq", pace.getValue().questKey());
        assertEquals(List.of(new AgentPqPace.Span("stage1", 3_000L, 63_000L, 0.0d, true),
                        new AgentPqPace.Span("stage2", 63_000L, 93_000L, 0.0d, false)),
                pace.getValue().spans());

        AgentLpqSession stock = run.session(AgentLpqSession.Mode.PRODUCTION, false, AgentLpqSession.Phase.STAGE_2,
                mira, kes, pel, hal, cas, ben);
        AgentLpqTerminationService.fail(stock, "No live Agent remains", 95_000L);
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(eq(stock.sessionId()),
                eq(false), eq("No live Agent remains"), eq(95_000L), isNull()));
    }
}
