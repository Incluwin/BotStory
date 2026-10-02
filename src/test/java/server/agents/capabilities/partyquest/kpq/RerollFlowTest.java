package server.agents.capabilities.partyquest.kpq;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.AgentPartyQuestHandback;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;

import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.kpq.RerollDecisionTest.rerollLines;
import static server.agents.capabilities.partyquest.kpq.RerollRunFixture.GO_AGAIN_LINE;
import static server.agents.capabilities.partyquest.kpq.RerollRunFixture.GRIND_IT_OUT_LINE;
import static server.agents.capabilities.partyquest.kpq.RerollRunFixture.POLICY;
import static server.agents.capabilities.partyquest.kpq.RerollRunFixture.THIRTY_FIVES_LINE;

/**
 * A stage-1 re-roll as the party lives it (round 2b, R2 and R3): the companion with the highest draw asks once, in its
 * own words, and the leader gets its window; the collectors never stop meanwhile. His answer is only recorded where it
 * comes (his I/O thread, never the session's lock) and acted on at the next tick: "Let's go again." ends the run
 * through the stock exit path (every player exits the event, which is disposed) as a re-roll, not a failure; "push
 * through", or no answer within REROLL_ANSWER_MS, carries on with one "alright, let's grind it out.".
 */
class RerollFlowTest {
    private static final long ASKED_AT = 2_000L;
    private static final Pattern BARE_COUNTER = Pattern.compile("\\d+\\s*/\\s*\\d+");

    private RerollRunFixture fx;
    private AgentKpqSession session;

    @BeforeEach
    void benAskedAboutTwoThirtyFives() {
        fx = new RerollRunFixture();
        when(fx.run.event.getPlayers()).thenReturn(List.of(fx.ben, fx.mira, fx.pip, fx.tobi));
        session = fx.companionRun(35, 35, 10);
        AgentKpqCoordinator.tick(session, ASKED_AT);
        assertEquals(List.of("Mira: " + THIRTY_FIVES_LINE), rerollLines(fx.said()));
        assertEquals(1, fx.asked.size());
    }

    @AfterEach
    void tearDown() {
        fx.close();
    }

    @Test
    void theQuestionIsSaidOnceAndAskedOnceWhileTheCollectorsCarryOn() {
        AgentKpqCoordinator.tick(session, ASKED_AT + 1_000L);
        AgentKpqCoordinator.tick(session, ASKED_AT + 5_000L);

        assertEquals(List.of("Mira: " + THIRTY_FIVES_LINE), rerollLines(fx.said()));
        assertEquals(1, fx.asked.size());
        assertEquals(AgentKpqSession.Phase.STAGE_1, session.phase());
        assertEquals(AgentKpqMemberState.Role.COUPON_COLLECTOR, session.member(2).role());
        verify(fx.run.event, never()).dispose();
    }

    @Test
    void letsGoAgainEndsTheRunThroughTheStockExitAsARerollNotAFailure() {
        assertTrue(AgentKpqSessionRegistry.answerReroll(session.sessionId(), 1, true));
        assertEquals(AgentKpqSession.Phase.STAGE_1, session.phase(), "recorded only, until the next tick");
        verify(fx.run.event, never()).dispose();

        AgentKpqCoordinator.tick(session, ASKED_AT + 4_000L);

        assertEquals(List.of("Mira: " + THIRTY_FIVES_LINE, "Mira: " + GO_AGAIN_LINE), rerollLines(fx.said()));
        // Through closeEvent: the stock playerExit for every player, then the event's disposal.
        for (client.Character player : List.of(fx.ben, fx.mira, fx.pip, fx.tobi)) {
            verify(fx.run.event).exitPlayer(player);
        }
        verify(fx.run.event).dispose();
        fx.run.lifecycle.verify(() -> AgentPartyQuestLifecycleRuntime.childFinished(eq(session.sessionId()),
                eq(false), eq(AgentPartyQuestHandback.REROLLED), eq(ASKED_AT + 4_000L), any()));
        assertNull(AgentKpqSessionRegistry.forMember(2), "the session is let go");
        assertEquals("rerolled", session.failure());
        assertEquals(List.of(), fx.coordinatorLog.startingWith("KPQ session failure"), "no failure is logged");
        assertEquals(List.of("KPQ stage 1 re-roll answer: session=" + session.sessionId()
                + " answer=go-again waitedMs=4000"), fx.coordinatorLog.startingWith("KPQ stage 1 re-roll answer: "));
        assertTrue(fx.terminationLog.startingWith("KPQ session released: ").getFirst()
                .contains(" outcome=rerolled "));
        assertEquals(1, AgentKpqRerollStreaks.current(1, ASKED_AT + 5_000L, POLICY.streakResetMs()));
        assertFalse(AgentKpqSessionRegistry.rerollAsked(session.sessionId()));
    }

    @Test
    void pushingThroughCarriesOnWithOneGrindItOut() {
        assertTrue(AgentKpqSessionRegistry.answerReroll(session.sessionId(), 1, false));

        AgentKpqCoordinator.tick(session, ASKED_AT + 3_000L);
        AgentKpqCoordinator.tick(session, ASKED_AT + 60_000L);

        assertEquals(List.of("Mira: " + THIRTY_FIVES_LINE, "Mira: " + GRIND_IT_OUT_LINE), rerollLines(fx.said()));
        assertEquals(AgentKpqSession.Phase.STAGE_1, session.phase());
        verify(fx.run.event, never()).dispose();
        assertFalse(AgentKpqSessionRegistry.answerReroll(session.sessionId(), 1, true), "a later answer is too late");
        AgentKpqCoordinator.tick(session, ASKED_AT + 61_000L);
        assertEquals(AgentKpqSession.Phase.STAGE_1, session.phase());
        assertEquals(1, fx.asked.size(), "never asked again this run");
        assertEquals(1, fx.coordinatorLog.startingWith("KPQ stage 1 draw: ").size(), "nor decided again");
        assertEquals(List.of("KPQ stage 1 re-roll answer: session=" + session.sessionId()
                + " answer=push-through waitedMs=3000"), fx.coordinatorLog.startingWith("KPQ stage 1 re-roll answer: "));
    }

    @Test
    void noAnswerInTimeCarriesOnTheSameWay() {
        AgentKpqCoordinator.tick(session, ASKED_AT + POLICY.answerMs() - 1L);
        assertEquals(List.of("Mira: " + THIRTY_FIVES_LINE), rerollLines(fx.said()), "still waiting");

        AgentKpqCoordinator.tick(session, ASKED_AT + POLICY.answerMs());

        assertEquals(List.of("Mira: " + THIRTY_FIVES_LINE, "Mira: " + GRIND_IT_OUT_LINE), rerollLines(fx.said()));
        assertFalse(AgentKpqSessionRegistry.answerReroll(session.sessionId(), 1, true));
        AgentKpqCoordinator.tick(session, ASKED_AT + POLICY.answerMs() + 1_000L);
        assertEquals(AgentKpqSession.Phase.STAGE_1, session.phase());
        assertEquals(List.of("KPQ stage 1 re-roll answer: session=" + session.sessionId() + " answer=none waitedMs="
                + POLICY.answerMs()), fx.coordinatorLog.startingWith("KPQ stage 1 re-roll answer: "));
    }

    @Test
    void theAnswerIsRecordedOnTheLeadersThreadWithoutTheSessionLockAndActedOnAtTheNextTick() throws Exception {
        AtomicBoolean recorded = new AtomicBoolean();
        String sessionId = session.sessionId(); // what his window holds
        Thread io = new Thread(() -> recorded.set(AgentKpqSessionRegistry.answerReroll(sessionId, 1, true)),
                "leader-io");
        synchronized (session) { // a tick holds this the whole time it runs
            io.start();
            io.join(5_000L);
            assertFalse(io.isAlive(), "the answer must not wait for the session");
        }
        assertTrue(recorded.get());
        assertFalse(AgentKpqSessionRegistry.answerReroll(session.sessionId(), 1, false), "the first answer stands");
        verify(fx.run.event, never()).dispose();

        AgentKpqCoordinator.tick(session, ASKED_AT + 1_000L);

        verify(fx.run.event).dispose();
    }

    @Test
    void aTypedYesInPartyChatCountsAndChatterDoesNot() {
        AgentKpqHumanDialogueRuntime.observeChat(fx.ben, "where is cloto", ASKED_AT + 500L);
        AgentKpqCoordinator.tick(session, ASKED_AT + 1_000L);
        verify(fx.run.event, never()).dispose();

        AgentKpqHumanDialogueRuntime.observeChat(fx.ben, "ok", ASKED_AT + 1_500L);
        AgentKpqCoordinator.tick(session, ASKED_AT + 2_000L);

        verify(fx.run.event).dispose();
        assertEquals(List.of("Mira: " + THIRTY_FIVES_LINE, "Mira: " + GO_AGAIN_LINE), rerollLines(fx.said()));
    }

    @Test
    void aTypedNoCarriesOn() {
        AgentKpqHumanDialogueRuntime.observeChat(fx.ben, "nah", ASKED_AT + 500L);
        AgentKpqCoordinator.tick(session, ASKED_AT + 1_000L);

        assertEquals(List.of("Mira: " + THIRTY_FIVES_LINE, "Mira: " + GRIND_IT_OUT_LINE), rerollLines(fx.said()));
        verify(fx.run.event, never()).dispose();
    }

    @Test
    void onlyItsLeaderAnswersItsOwnQuestion() {
        assertFalse(AgentKpqSessionRegistry.answerReroll(session.sessionId(), 2, true), "Mira is not the leader");
        assertFalse(AgentKpqSessionRegistry.answerReroll("kpq-another-run", 1, true));
        AgentKpqHumanDialogueRuntime.observeChat(fx.mira, "yes", ASKED_AT + 500L);

        AgentKpqCoordinator.tick(session, ASKED_AT + 1_000L);

        verify(fx.run.event, never()).dispose();
        assertEquals(AgentKpqSession.Phase.STAGE_1, session.phase());
    }

    @Test
    void theRerolledRunIsNoFailureForTheHandbackOrTheLobby() {
        AgentPartyQuestHandback.RunEnded ended = new AgentPartyQuestHandback.RunEnded("kpq", session.sessionId(), 1,
                false, AgentPartyQuestHandback.REROLLED, 5_000L);
        assertTrue(ended.rerolled());
        assertFalse(new AgentPartyQuestHandback.RunEnded("kpq", session.sessionId(), 1, false, "KPQ stopped",
                5_000L).rerolled());
        assertFalse(new AgentPartyQuestHandback.RunEnded("kpq", session.sessionId(), 1, true, "completed",
                5_000L).rerolled());
    }

    @Test
    void aQuestionStillOutAtTheStageOneClearIsClosedWithoutALine() {
        // A human collector handed in and Cloto cleared the stage before Ben answered.
        when(fx.run.event.getProperty("1stageclear")).thenReturn("true");

        AgentKpqCoordinator.tick(session, ASKED_AT + 3_000L);

        assertFalse(session.rerollPending());
        assertFalse(AgentKpqSessionRegistry.rerollAsked(session.sessionId()));
        assertFalse(AgentKpqSessionRegistry.answerRerollInChat(1, "ok"), "his ok is chat again");
        assertFalse(AgentKpqSessionRegistry.answerReroll(session.sessionId(), 1, true), "a late pick is no answer");
        assertEquals(0, AgentKpqSessionRegistry.rerollQuestionsOut());
        assertEquals(List.of("Mira: " + THIRTY_FIVES_LINE), rerollLines(fx.said()), "nothing more is said of it");
        assertEquals(List.of("KPQ stage 1 re-roll answer: session=" + session.sessionId()
                + " answer=stage-cleared waitedMs=3000"), fx.coordinatorLog.startingWith("KPQ stage 1 re-roll answer: "));
        verify(fx.run.event, never()).dispose();
    }

    @Test
    void whileTheQuestionIsOutNoOtherLineAsksTheLeaderAnything() {
        // The stage-1 levers as production runs them, Ben holding coupons a collector could use.
        session.setStageOneLevers(new AgentKpqStageOneLevers(true, false, false, false));
        when(fx.ben.getItemQuantity(AgentKpqDefinition.COUPON_ITEM, false)).thenReturn(2);

        AgentKpqCoordinator.tick(session, ASKED_AT + 1_000L);
        AgentKpqCoordinator.tick(session, ASKED_AT + 10_000L);

        assertEquals(List.of(), couponAsks(), "the coupon ask waits for his answer");
        AgentKpqCoordinator.tick(session, ASKED_AT + POLICY.answerMs()); // he let it pass
        assertEquals(List.of("Mira: " + THIRTY_FIVES_LINE, "Mira: " + GRIND_IT_OUT_LINE), rerollLines(fx.said()));
        AgentKpqCoordinator.tick(session, ASKED_AT + POLICY.answerMs() + 1_000L);
        assertEquals(1, couponAsks().size(), "then it goes out");

        AgentKpqHumanDialogueRuntime.observeChat(fx.ben, "ok", ASKED_AT + POLICY.answerMs() + 1_500L);
        AgentKpqCoordinator.tick(session, ASKED_AT + POLICY.answerMs() + 2_000L);

        verify(fx.run.event, never()).dispose();
        assertEquals(AgentKpqSession.Phase.STAGE_1, session.phase(), "his ok to the coupons is no re-roll");
    }

    @Test
    void anAnswerIsKeptWithItsQuestionAndLeavesWithIt() {
        assertEquals(1, AgentKpqSessionRegistry.rerollQuestionsOut());
        assertTrue(AgentKpqSessionRegistry.answerReroll(session.sessionId(), 1, false));
        assertEquals(1, AgentKpqSessionRegistry.rerollQuestionsOut(), "one entry: the question and its answer");
        assertEquals(Boolean.FALSE, AgentKpqSessionRegistry.takeRerollAnswer(session.sessionId(), 1));
        assertEquals(0, AgentKpqSessionRegistry.rerollQuestionsOut(), "an answered question is over");
        assertFalse(AgentKpqSessionRegistry.answerReroll(session.sessionId(), 1, true));
        assertEquals(0, AgentKpqSessionRegistry.rerollQuestionsOut());
    }

    @Test
    void anAnswerThatRacesTheQuestionsCloseIsNeverLeftBehind() throws Exception {
        String sessionId = session.sessionId();
        for (int round = 0; round < 2_000; round++) {
            AgentKpqSessionRegistry.askReroll(sessionId, 1, ASKED_AT, true);
            CyclicBarrier start = new CyclicBarrier(2);
            AtomicBoolean recorded = new AtomicBoolean();
            Thread io = new Thread(() -> {
                await(start);
                recorded.set(AgentKpqSessionRegistry.answerReroll(sessionId, 1, true));
            }, "leader-io");
            io.start();
            await(start);
            AgentKpqSessionRegistry.closeReroll(sessionId);
            io.join(5_000L);
            assertEquals(0, AgentKpqSessionRegistry.rerollQuestionsOut(), "round " + round);
            assertNull(AgentKpqSessionRegistry.takeRerollAnswer(sessionId, 1), "round " + round);
        }
    }

    @Test
    void aRerollStreakStartsOverWhenHisNextRunEndsAnyOtherWay() {
        AgentKpqRerollStreaks.rerolled(1, 1_000L, POLICY.streakResetMs());
        assertEquals(1, AgentKpqRerollStreaks.current(1, ASKED_AT, POLICY.streakResetMs()));

        AgentKpqTerminationService.fail(session, "KPQ stopped", ASKED_AT + 1_000L);

        assertEquals(0, AgentKpqRerollStreaks.current(1, ASKED_AT + 2_000L, POLICY.streakResetMs()));
    }

    @Test
    void everyRerollLineIsPlayerChat() {
        List<String> lines = List.of(THIRTY_FIVES_LINE, AgentKpqRerollPolicy.askLine(List.of(35, 35, 35)),
                AgentKpqRerollPolicy.LOT_OF_COUPONS_LINE, GO_AGAIN_LINE, GRIND_IT_OUT_LINE,
                AgentKpqCoordinator.Parting.of(AgentKpqCoordinator.Parting.Kind.REROLL).line(2, false),
                AgentKpqCoordinator.Parting.of(AgentKpqCoordinator.Parting.Kind.REROLL).line(2, true));
        for (String line : lines) {
            assertFalse(line.contains("->"), line);
            assertFalse(BARE_COUNTER.matcher(line).find(), line);
            for (String engine : List.of("%", "~", "DPS", "estimate", "reroll", "re-roll", "session", "rule")) {
                assertFalse(line.toLowerCase().contains(engine.toLowerCase()), engine + " in " + line);
            }
            assertEquals(line.toLowerCase(), line, "a companion talks in lower case: " + line);
        }
        assertEquals("ugh, three of us got 35s. that's a long grind, wanna bail and run it again?", lines.get(1));
        assertEquals(GO_AGAIN_LINE, lines.get(5));
        assertEquals(GO_AGAIN_LINE, lines.get(6));
    }

    /** The L2 coupon asks said to Ben so far. */
    private List<String> couponAsks() {
        return fx.said().stream().filter(line -> line.contains("drop them by me")).toList();
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception interrupted) {
            throw new IllegalStateException(interrupted);
        }
    }
}
