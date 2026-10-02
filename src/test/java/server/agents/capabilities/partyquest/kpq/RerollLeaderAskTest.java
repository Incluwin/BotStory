package server.agents.capabilities.partyquest.kpq;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.life.Monster;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.kpq.RerollDecisionTest.rerollLines;
import static server.agents.capabilities.partyquest.kpq.RerollRunFixture.GRIND_IT_OUT_LINE;
import static server.agents.capabilities.partyquest.kpq.RerollRunFixture.POLICY;
import static server.agents.capabilities.partyquest.kpq.RerollRunFixture.THIRTY_FIVES_LINE;

/**
 * How the re-roll question reaches a human leader who is busy (round 2b review): the companion's window holds his
 * input until he answers it, so it opens only at a lull in his fight (on his feet, out of his combat stance, no
 * Ligator within REROLL_WINDOW_CLEAR_PX), and his answer is timed from the window once it is on screen. While the
 * question is out it is the only thing asked of him, and a bare "ok" he types right after another companion line
 * asked him something (L2's coupon ask) is no answer to it: only the words that can only mean it count then.
 */
class RerollLeaderAskTest {
    private static final long ASKED_AT = 2_000L;

    private RerollRunFixture fx;

    @BeforeEach
    void setUp() {
        fx = new RerollRunFixture();
    }

    @AfterEach
    void tearDown() {
        fx.close();
    }

    // ---- one question at a time ---------------------------------------------------------------------------

    @Test
    void theQuestionAndTheCouponAskAreNeverSaidTogether() {
        AgentKpqSession session = withLeaderCoupons(fx.companionRun(35, 35, 10));

        AgentKpqCoordinator.tick(session, ASKED_AT);

        assertEquals(List.of("Mira: " + THIRTY_FIVES_LINE), fx.said().stream()
                .filter(line -> line.contains("run it again") || line.contains("drop them by me")).toList());
    }

    @Test
    void aBareOkRightAfterTheCouponAskIsNoAnswerButLetsGoAgainIs() {
        AgentKpqSession session = withLeaderCoupons(fx.companionRun(35, 35, 10));
        AgentKpqMemberState tobi = session.member(4);
        tobi.setCouponTarget(0); // still on his way to Cloto
        AgentKpqCoordinator.tick(session, ASKED_AT - 1_000L);
        assertEquals(1, couponAsks().size(), "Mira asked Ben for his coupons");
        tobi.setCouponTarget(10);
        AgentKpqCoordinator.tick(session, ASKED_AT);
        assertEquals(List.of("Mira: " + THIRTY_FIVES_LINE), rerollLines(fx.said()));

        AgentKpqHumanDialogueRuntime.observeChat(fx.ben, "ok", ASKED_AT + 500L); // meant for the coupons
        AgentKpqCoordinator.tick(session, ASKED_AT + 1_000L);

        verify(fx.run.event, never()).dispose();
        assertEquals(AgentKpqSession.Phase.STAGE_1, session.phase());
        assertTrue(session.rerollPending(), "the question still awaits him");

        AgentKpqHumanDialogueRuntime.observeChat(fx.ben, "let's go again", ASKED_AT + 1_500L);
        AgentKpqCoordinator.tick(session, ASKED_AT + 2_000L);

        verify(fx.run.event).dispose();
    }

    @Test
    void aBareNahRightAfterTheCouponAskIsNoAnswerEither() {
        AgentKpqSession session = withLeaderCoupons(fx.companionRun(35, 35, 10));
        session.member(4).setCouponTarget(0);
        AgentKpqCoordinator.tick(session, ASKED_AT - 1_000L);
        session.member(4).setCouponTarget(10);
        AgentKpqCoordinator.tick(session, ASKED_AT);

        AgentKpqHumanDialogueRuntime.observeChat(fx.ben, "nah", ASKED_AT + 500L);
        AgentKpqCoordinator.tick(session, ASKED_AT + 1_000L);

        assertEquals(List.of("Mira: " + THIRTY_FIVES_LINE), rerollLines(fx.said()), "no grind-it-out yet");
        assertTrue(session.rerollPending());
    }

    @Test
    void aCouponAskLongBeforeTheQuestionLeavesTheBareOkItsAnswer() {
        AgentKpqSession session = withLeaderCoupons(fx.companionRun(35, 35, 10));
        session.member(4).setCouponTarget(0);
        AgentKpqCoordinator.tick(session, ASKED_AT);
        assertEquals(1, couponAsks().size());
        session.member(4).setCouponTarget(10);
        long askedAt = ASKED_AT + POLICY.answerMs(); // the coupon ask is that long past
        AgentKpqCoordinator.tick(session, askedAt);
        assertEquals(List.of("Mira: " + THIRTY_FIVES_LINE), rerollLines(fx.said()));

        AgentKpqHumanDialogueRuntime.observeChat(fx.ben, "ok", askedAt + 500L);
        AgentKpqCoordinator.tick(session, askedAt + 1_000L);

        verify(fx.run.event).dispose();
    }

    // ---- the window, at a lull ----------------------------------------------------------------------------

    @Test
    void aLeaderWithALigatorBesideHimGetsNoWindowUntilItIsGone() {
        Monster ligator = fx.ligatorAt(120, 0);
        AgentKpqSession session = fx.companionRun(35, 35, 10);

        AgentKpqCoordinator.tick(session, ASKED_AT);
        AgentKpqCoordinator.tick(session, ASKED_AT + 1_000L);

        assertEquals(List.of("Mira: " + THIRTY_FIVES_LINE), rerollLines(fx.said()), "the question stands in chat");
        assertEquals(List.of(), fx.asked, "no window mid-fight");

        fx.killed(ligator);
        fx.ligatorAt(400, 0); // another, out of reach
        AgentKpqCoordinator.tick(session, ASKED_AT + 3_000L);

        assertEquals(1, fx.asked.size(), "at the lull");
        AgentKpqCoordinator.tick(session, ASKED_AT + 4_000L);
        assertEquals(1, fx.asked.size(), "once");
        assertEquals(List.of("KPQ stage 1 re-roll window shown: session=" + session.sessionId() + " afterMs=3000"),
                fx.coordinatorLog.startingWith("KPQ stage 1 re-roll window shown: "));
    }

    @Test
    void aLeaderInTheAirOrInHisCombatStanceGetsNoWindow() {
        AgentKpqSession session = fx.companionRun(35, 35, 10);
        when(fx.ben.getStance()).thenReturn(6); // jumping
        AgentKpqCoordinator.tick(session, ASKED_AT);
        when(fx.ben.getStance()).thenReturn(16); // on a rope
        AgentKpqCoordinator.tick(session, ASKED_AT + 1_000L);
        when(fx.ben.getStance()).thenReturn(9); // alert: he hit or was hit just now
        AgentKpqCoordinator.tick(session, ASKED_AT + 2_000L);
        assertEquals(List.of(), fx.asked);

        when(fx.ben.getStance()).thenReturn(4); // standing
        AgentKpqCoordinator.tick(session, ASKED_AT + 3_000L);

        assertEquals(1, fx.asked.size());
    }

    @Test
    void aWindowShownLateGetsItsFullAnswerTime() {
        Monster ligator = fx.ligatorAt(60, 0);
        AgentKpqSession session = fx.companionRun(35, 35, 10);
        AgentKpqCoordinator.tick(session, ASKED_AT);
        fx.killed(ligator);
        AgentKpqCoordinator.tick(session, ASKED_AT + 10_000L); // the window goes on screen

        AgentKpqCoordinator.tick(session, ASKED_AT + POLICY.answerMs());
        assertTrue(session.rerollPending(), "he has had it on screen for 15 s only");
        AgentKpqCoordinator.tick(session, ASKED_AT + 10_000L + POLICY.answerMs() - 1L);
        assertTrue(session.rerollPending());

        AgentKpqCoordinator.tick(session, ASKED_AT + 10_000L + POLICY.answerMs());

        assertFalse(session.rerollPending());
        assertEquals(List.of("Mira: " + THIRTY_FIVES_LINE, "Mira: " + GRIND_IT_OUT_LINE), rerollLines(fx.said()));
    }

    @Test
    void noLullWithinTheAnswerTimeLeavesTheQuestionToChatAndItPasses() {
        fx.ligatorAt(0, 0);
        AgentKpqSession session = fx.companionRun(35, 35, 10);
        AgentKpqCoordinator.tick(session, ASKED_AT);
        AgentKpqCoordinator.tick(session, ASKED_AT + POLICY.answerMs() - 1L);

        AgentKpqCoordinator.tick(session, ASKED_AT + POLICY.answerMs());

        assertEquals(List.of(), fx.asked);
        assertEquals(List.of("Mira: " + THIRTY_FIVES_LINE, "Mira: " + GRIND_IT_OUT_LINE), rerollLines(fx.said()));
    }

    @Test
    void aWindowRefusedOnHisScreenIsOfferedAgainAtTheNextLull() {
        fx.windowsOnScreen = false; // Cloto's window is open
        AgentKpqSession session = fx.companionRun(35, 35, 10);
        AgentKpqCoordinator.tick(session, ASKED_AT);
        AgentKpqCoordinator.tick(session, ASKED_AT + 1_000L);
        assertEquals(2, fx.asked.size());

        fx.windowsOnScreen = true;
        AgentKpqCoordinator.tick(session, ASKED_AT + 2_000L);
        AgentKpqCoordinator.tick(session, ASKED_AT + 3_000L);

        assertEquals(3, fx.asked.size(), "shown on the third offer, and not offered since");
    }

    @Test
    void aHostThatCannotShowTheWindowIsAskedOnceAndTheQuestionStandsInChat() {
        fx.hostShowsWindows = false;
        AgentKpqSession session = fx.companionRun(35, 35, 10);

        AgentKpqCoordinator.tick(session, ASKED_AT);
        AgentKpqCoordinator.tick(session, ASKED_AT + 1_000L);
        AgentKpqCoordinator.tick(session, ASKED_AT + 2_000L);

        assertEquals(1, fx.asked.size());
        assertEquals(1, fx.coordinatorLog.startingWith("KPQ stage 1 re-roll window not shown: ").size());
        AgentKpqHumanDialogueRuntime.observeChat(fx.ben, "go again", ASKED_AT + 2_500L);
        AgentKpqCoordinator.tick(session, ASKED_AT + 3_000L);
        verify(fx.run.event).dispose();
    }

    /** L2 on, as production runs it, with Ben holding two coupons a collector could use. */
    private AgentKpqSession withLeaderCoupons(AgentKpqSession session) {
        session.setStageOneLevers(new AgentKpqStageOneLevers(true, false, false, false));
        when(fx.ben.getItemQuantity(AgentKpqDefinition.COUPON_ITEM, false)).thenReturn(2);
        return session;
    }

    private List<String> couponAsks() {
        return fx.said().stream().filter(line -> line.contains("drop them by me")).toList();
    }
}
