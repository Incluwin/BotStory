package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.capabilities.partyquest.pace.AgentPqPace;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P6h LMPQ step 1, C5: what a release of a companion run says and hands over. exitAgentsOnly takes only the Agents out
 * through the event's own exit; a companion run logs its companions' potions at the end beside those on entry, every
 * run logs 'LMPQ session released:' last, a potion count that throws never stops the release, and only a companion
 * run hands the host its pace.
 */
class AgentLmpqCompanionTerminationTest {
    private final LmpqRunFixture run = new LmpqRunFixture();
    private final Character ben = run.human(1, "Ben");
    private final Character mira = run.companion(2, "Mira");
    private final Character kes = run.companion(3, "Kes");
    private final Character pel = run.companion(4, "Pel");

    @AfterEach
    void tearDown() {
        run.close();
    }

    @Test
    void exitAgentsOnlyTakesOnlyTheAgentsOutAndSurvivesOneThatCannotLeave() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, true,
                AgentLmpqSession.Phase.REWARD, ben, mira, kes, pel);
        doThrow(new IllegalStateException("exit script failed")).when(run.event).exitPlayer(kes);

        AgentLmpqTerminationService.exitAgentsOnly(session);

        verify(run.event).exitPlayer(mira);
        verify(run.event).exitPlayer(kes);
        verify(run.event).exitPlayer(pel);
        verify(run.event, never()).exitPlayer(ben);
        verify(run.event, never()).dispose();
        AgentLmpqTerminationService.exitAgentsOnly(null);
    }

    @Test
    void aCompanionReleaseLogsItsPotionsAndThenItsReleasedLine() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, true,
                AgentLmpqSession.Phase.FARMING, ben, mira, kes, pel);
        session.recordPotionsAtEntry("{Mira:40/50, Kes:30/30, Pel:20/25}");
        when(run.actions.hpPotionCount(mira)).thenReturn(12);
        when(run.actions.mpPotionCount(mira)).thenReturn(30);
        when(run.actions.hpPotionCount(kes)).thenReturn(5);
        when(run.actions.mpPotionCount(pel)).thenReturn(1);
        session.fail("LMPQ stalled in FARMING", 5_000L);
        try (LmpqLogLines lines = new LmpqLogLines(AgentLmpqTerminationService.class)) {
            AgentLmpqTerminationService.finish(session, "LMPQ stalled in FARMING", 6_000L, true);

            String id = session.sessionId();
            assertEquals(List.of(
                    "LMPQ potions: session=" + id + " start={Mira:40/50, Kes:30/30, Pel:20/25}"
                            + " end={Mira:12/30, Kes:5/0, Pel:0/1}",
                    "LMPQ session released: session=" + id + " outcome=LMPQ stalled in FARMING members=4"
                            + " companionRun=true pastTheClear=false"),
                    lines.startingWith("LMPQ "));
        }
    }

    @Test
    void aStockReleaseLogsNoPotionsButItsReleasedLine() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, false,
                AgentLmpqSession.Phase.REWARD, ben, mira, kes, pel);
        when(run.event.isEventCleared()).thenReturn(true);
        session.complete(5_000L);
        try (LmpqLogLines lines = new LmpqLogLines(AgentLmpqTerminationService.class)) {
            AgentLmpqTerminationService.finish(session, "", 6_000L, false);

            assertEquals(List.of("LMPQ session released: session=" + session.sessionId()
                            + " outcome=completed members=4 companionRun=false pastTheClear=true"),
                    lines.startingWith("LMPQ "));
        }
        verify(run.actions, never()).hpPotionCount(any());
    }

    @Test
    void aPotionCountThatThrowsNeverStopsTheRelease() {
        AgentLmpqSession session = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, true,
                AgentLmpqSession.Phase.FARMING, ben, mira, kes, pel);
        when(run.actions.hpPotionCount(any())).thenThrow(new IllegalStateException("inventory unavailable"));
        session.fail("LMPQ stalled in FARMING", 5_000L);
        try (LmpqLogLines lines = new LmpqLogLines(AgentLmpqTerminationService.class)) {
            AgentLmpqTerminationService.finish(session, "LMPQ stalled in FARMING", 6_000L, true);

            assertEquals(1, lines.startingWith("LMPQ session released:").size());
        }
        verify(run.event).dispose();
        assertNull(AgentLmpqSessionRegistry.forMember(mira.getId()));
        String id = session.sessionId();
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(
                eq(id), eq(false), eq("LMPQ stalled in FARMING"), eq(6_000L), any()));
    }

    @Test
    void onlyACompanionRunHandsTheHostItsPace() {
        AgentLmpqSession companion = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, true,
                AgentLmpqSession.Phase.REWARD, ben, mira, kes, pel);
        companion.complete(70_000L);
        AgentLmpqTerminationService.finish(companion, "", 71_000L, false);
        String companionId = companion.sessionId();
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(eq(companionId), eq(true), eq(""),
                eq(71_000L), argThat((AgentPqPace pace) -> pace != null && pace.questKey().equals("lmpq")
                        && pace.sessionId().equals(companionId) && pace.leaderId() == 1)));

        Character ann = run.human(11, "Ann");
        Character tom = run.companion(12, "Tom");
        Character liz = run.companion(13, "Liz");
        AgentLmpqSession stock = run.session(AgentLmpqSession.Mode.HUMAN_LEADER, false,
                AgentLmpqSession.Phase.REWARD, ann, tom, liz);
        stock.complete(70_000L);
        AgentLmpqTerminationService.finish(stock, "", 71_000L, false);
        String stockId = stock.sessionId();
        run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(eq(stockId), eq(true), eq(""),
                eq(71_000L), isNull()));
        assertTrue(companion.terminationStarted() && stock.terminationStarted());
    }
}
