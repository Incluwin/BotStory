package server.agents.capabilities.partyquest.kpq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.partyquest.pace.AgentPqMemberPower;
import server.agents.capabilities.partyquest.pace.AgentPqPaceCalibration;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimator;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfiles;
import server.maps.MapItem;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.kpq.RerollRunFixture.POLICY;
import static server.agents.capabilities.partyquest.kpq.RerollRunFixture.THIRTY_FIVES_LINE;

/**
 * Whether a companion run offers its human leader a stage-1 re-roll (round 2b, R1). Human parties leave a run when two
 * or more of them drew Cloto's 35; a companion run does the same, judged by the expected saving once the pace data is
 * warm, and never when the leader declined this run, after two re-rolls of his in a row, once a pass exists, or in a
 * stock or all-Agent run. The decision is pure (AgentKpqRerollPolicy.decide); the coordinator reads the draw once
 * every collector has its number and logs one line.
 */
class RerollDecisionTest {
    /** Map.wz and Mob.wz read directly, as AgentKpqPaceProfileTest reads them. */
    private static final AgentKpqPaceProfile PROFILE = AgentKpqPaceProfile.load(AgentKpqPaceProfile.Census.WZ);
    /** Three collectors' mean kills: 3 x 153 / 7. */
    private static final double FRESH_MEAN = 3.0d * 153.0d / 7.0d;

    private RerollRunFixture fx;

    @BeforeEach
    void setUp() {
        fx = new RerollRunFixture();
    }

    @AfterEach
    void tearDown() {
        fx.close();
    }

    // ---- the pure decision ------------------------------------------------------------------------------

    @Test
    void twoThirtyFivesWithAColdProfileProposeByTheRuleOfThumb() {
        AgentKpqRerollPolicy.Decision decision = POLICY.decide(cold(35, 35, 10));

        assertTrue(decision.proposed());
        assertEquals(AgentKpqRerollPolicy.Rule.THIRTY_FIVES, decision.rule());
        assertEquals("two-or-more-35s", decision.reason());
        assertTrue(Double.isNaN(decision.savingMs()));
        assertTrue(POLICY.decide(cold(35, 35, 35)).proposed());
    }

    @Test
    void oneThirtyFiveWithAColdProfileDoesNotProposeHoweverHeavy() {
        AgentKpqRerollPolicy.Decision decision = POLICY.decide(cold(35, 30, 30));

        assertFalse(decision.proposed());
        assertEquals("fewer-than-two-35s", decision.reason());
        assertFalse(POLICY.decide(cold(35, 8)).proposed());
    }

    @Test
    void theEstimateProposesAHeavyDrawWithoutTwoThirtyFivesWhenTheSavingIsWorthIt() {
        // 95 kills at 8.2 s each against a fresh 65.6 and the 75 s restart: about 166 s saved.
        AgentKpqRerollPolicy.Decision decision = POLICY.decide(warm(8.2d, 35, 30, 30));

        assertTrue(decision.proposed());
        assertEquals(AgentKpqRerollPolicy.Rule.ESTIMATE, decision.rule());
        assertEquals("saving-reaches-minimum", decision.reason());
        assertEquals((95.0d - FRESH_MEAN) * 8_200.0d - 75_000.0d, decision.savingMs(), 1e-6);
    }

    @Test
    void theEstimateDeclinesTwoThirtyFivesWhoseSavingIsUnderTheMinimumWhenThePartyIsFast() {
        // 80 kills at 2 s each: the fresh draw and the restart cost more than they save.
        AgentKpqRerollPolicy.Decision decision = POLICY.decide(warm(2.0d, 35, 35, 10));

        assertFalse(decision.proposed());
        assertEquals(AgentKpqRerollPolicy.Rule.ESTIMATE, decision.rule());
        assertEquals("saving-under-minimum", decision.reason());
        assertEquals((80.0d - FRESH_MEAN) * 2_000.0d - 75_000.0d, decision.savingMs(), 1e-6);
        // Even three 35s save under a minute at that pace.
        assertFalse(POLICY.decide(warm(2.0d, 35, 35, 35)).proposed());
    }

    @Test
    void theSavingMustReachTheMinimumExactly() {
        // A saving of exactly REROLL_MIN_SAVING_MS proposes; a millisecond less does not.
        double secondsPerKill = 135.0d / (95.0d - FRESH_MEAN);
        AgentKpqRerollPolicy.Decision exact = POLICY.decide(warm(secondsPerKill, 35, 30, 30));
        assertEquals(60_000.0d, exact.savingMs(), 1e-6);
        assertTrue(POLICY.decide(warm(secondsPerKill + 1e-6, 35, 30, 30)).proposed());
        assertFalse(POLICY.decide(warm(secondsPerKill - 1e-3, 35, 30, 30)).proposed());
    }

    @Test
    void aDeclinedRunAStreakAPassAStockRunOrTheSwitchOffNeverPropose() {
        List<Integer> heavy = List.of(35, 35, 10);
        assertEquals("declined", POLICY.decide(new AgentKpqRerollPolicy.Draw(true, true, 0, false, heavy,
                Double.NaN, Double.NaN)).reason());
        assertEquals("streak", POLICY.decide(new AgentKpqRerollPolicy.Draw(true, false, 2, false, heavy,
                Double.NaN, Double.NaN)).reason());
        assertEquals("pass-exists", POLICY.decide(new AgentKpqRerollPolicy.Draw(true, false, 0, true, heavy,
                Double.NaN, Double.NaN)).reason());
        assertEquals("not-a-companion-run", POLICY.decide(new AgentKpqRerollPolicy.Draw(false, false, 0, false, heavy,
                Double.NaN, Double.NaN)).reason());
        assertEquals("disabled", POLICY.off().decide(cold(35, 35, 10)).reason());
        for (AgentKpqRerollPolicy.Draw gated : List.of(
                new AgentKpqRerollPolicy.Draw(true, true, 0, false, heavy, 8.2d, FRESH_MEAN),
                new AgentKpqRerollPolicy.Draw(true, false, 2, false, heavy, 8.2d, FRESH_MEAN),
                new AgentKpqRerollPolicy.Draw(true, false, 0, true, heavy, 8.2d, FRESH_MEAN),
                new AgentKpqRerollPolicy.Draw(false, false, 0, false, heavy, 8.2d, FRESH_MEAN))) {
            assertFalse(POLICY.decide(gated).proposed(), gated.toString());
        }
        // One re-roll in a row still leaves the second.
        assertTrue(POLICY.decide(new AgentKpqRerollPolicy.Draw(true, false, 1, false, heavy, Double.NaN, Double.NaN))
                .proposed());
    }

    @Test
    void theSavingPricesOnlyTheCouponsStillNeeded() {
        // 95 drawn, 20 of them picked up while the last number came: the 75 left save little against a fresh draw.
        AgentKpqRerollPolicy.Decision decision = POLICY.decide(new AgentKpqRerollPolicy.Draw(true, false, 0, false,
                List.of(35, 30, 30), 75, 8.2d, FRESH_MEAN));

        assertEquals((75.0d - FRESH_MEAN) * 8_200.0d - 75_000.0d, decision.savingMs(), 1e-6);
        assertFalse(decision.proposed());
        assertEquals("saving-under-minimum", decision.reason());
        assertTrue(POLICY.decide(warm(8.2d, 35, 30, 30)).proposed(), "the same draw untouched is worth it");
        // The players' rule of thumb reads the numbers, not the progress.
        assertTrue(POLICY.decide(new AgentKpqRerollPolicy.Draw(true, false, 0, false, List.of(35, 35, 10), 20,
                Double.NaN, Double.NaN)).proposed());
    }

    @Test
    void theWindowWaitsForALull() {
        java.awt.Point ben = new java.awt.Point(0, 0);

        assertTrue(POLICY.lull(ben, true, false, List.of()));
        assertTrue(POLICY.lull(ben, true, false, List.of(new java.awt.Point(251, 0))));
        assertFalse(POLICY.lull(ben, true, false, List.of(new java.awt.Point(250, 0))), "a Ligator beside him");
        assertFalse(POLICY.lull(ben, true, false, List.of(new java.awt.Point(150, -200))), "within 250 px");
        assertFalse(POLICY.lull(ben, false, false, List.of()), "in the air or on a rope");
        assertFalse(POLICY.lull(ben, true, true, List.of()), "in his combat stance");
        assertFalse(POLICY.lull(null, true, false, List.of()));
    }

    @Test
    void aOneWordAnswerCountsOnlyWhenNothingElseWasJustAsked() {
        for (String word : List.of("ok", "yes", "sure", "yeah", "Yep.", "okay")) {
            assertEquals(Boolean.TRUE, AgentKpqRerollPolicy.typedAnswer(word, true), word);
            assertNull(AgentKpqRerollPolicy.typedAnswer(word, false), word);
        }
        for (String word : List.of("no", "nah", "nope")) {
            assertEquals(Boolean.FALSE, AgentKpqRerollPolicy.typedAnswer(word, true), word);
            assertNull(AgentKpqRerollPolicy.typedAnswer(word, false), word);
        }
        assertEquals(Boolean.TRUE, AgentKpqRerollPolicy.typedAnswer("let's go again", false));
        assertEquals(Boolean.TRUE, AgentKpqRerollPolicy.typedAnswer("ok let's go again", false));
        assertEquals(Boolean.FALSE, AgentKpqRerollPolicy.typedAnswer("push through", false));
    }

    @Test
    void theConfiguredPolicyIsTheYamls() {
        assertEquals(POLICY, AgentKpqRerollPolicy.configured());
        assertEquals(35, AgentKpqRerollPolicy.highestAnswer());
    }

    @Test
    void aTypedAnswerIsTheWholeLine() {
        for (String yes : List.of("yes", "Yes!", "ok", "OK.", "sure", "go again", "let's go again", "yeah")) {
            assertEquals(Boolean.TRUE, AgentKpqRerollPolicy.typedAnswer(yes), yes);
        }
        for (String no : List.of("no", "nah", "Nah!", "push through", "let's push through", "nope")) {
            assertEquals(Boolean.FALSE, AgentKpqRerollPolicy.typedAnswer(no), no);
        }
        for (String other : List.of("ok where is cloto", "no way that's a lot", "lol", "", "35?")) {
            assertNull(AgentKpqRerollPolicy.typedAnswer(other), other);
        }
    }

    // ---- the coordinator's one decision -----------------------------------------------------------------

    @Test
    void twoCollectorsOnThirtyFiveInACompanionRunWithAColdProfileProposeARerollOnce() {
        AgentKpqSession session = fx.companionRun(35, 35, 10);

        AgentKpqCoordinator.tick(session, 2_000L);
        AgentKpqCoordinator.tick(session, 3_000L);

        assertEquals(List.of("Mira: " + THIRTY_FIVES_LINE), rerollLines(fx.said()),
                "the highest draw asks, the first of the party on a tie, once");
        assertEquals(1, fx.asked.size());
        assertEquals(new server.agents.capabilities.partyquest.AgentPartyQuestHandback.RerollAsk("kpq",
                session.sessionId(), 1, 2, "thirty-fives", THIRTY_FIVES_LINE), fx.asked.getFirst());
        assertTrue(AgentKpqSessionRegistry.rerollAsked(session.sessionId()));
        assertEquals(List.of("KPQ stage 1 draw: session=" + session.sessionId()
                        + " targets=[35, 35, 10] total=80 remaining=80 rule=thirty-fives savingMs=- proposed=true"
                        + " reason=two-or-more-35s"),
                fx.coordinatorLog.startingWith("KPQ stage 1 draw: "));
        assertEquals(AgentKpqSession.Phase.STAGE_1, session.phase(), "the collectors carry on meanwhile");
    }

    @Test
    void oneCollectorOnThirtyFiveIsDecidedOnceAndNotAsked() {
        AgentKpqSession session = fx.companionRun(35, 20, 10);

        AgentKpqCoordinator.tick(session, 2_000L);
        AgentKpqCoordinator.tick(session, 3_000L);

        assertEquals(List.of(), rerollLines(fx.said()));
        assertEquals(List.of(), fx.asked);
        assertEquals(List.of("KPQ stage 1 draw: session=" + session.sessionId()
                        + " targets=[35, 20, 10] total=65 remaining=65 rule=thirty-fives savingMs=- proposed=false"
                        + " reason=fewer-than-two-35s"),
                fx.coordinatorLog.startingWith("KPQ stage 1 draw: "));
    }

    @Test
    void nothingIsDecidedUntilTheLastCollectorHasItsNumber() {
        AgentKpqSession session = fx.companionRun(35, 35, 10);
        AgentKpqMemberState tobi = session.member(4);
        tobi.setCouponTarget(0);

        AgentKpqCoordinator.tick(session, 2_000L);
        assertEquals(List.of(), fx.coordinatorLog.startingWith("KPQ stage 1 draw: "));
        assertEquals(List.of(), fx.asked);

        tobi.setCouponTarget(10);
        AgentKpqCoordinator.tick(session, 3_000L);
        assertEquals(1, fx.asked.size());
    }

    @Test
    void aPassThatExistsAlreadyKeepsTheRunAsDrawn() {
        AgentKpqSession session = fx.companionRun(35, 35, 10);
        session.member(4).markPassCreated();

        AgentKpqCoordinator.tick(session, 2_000L);

        assertEquals(List.of(), fx.asked);
        assertEquals(List.of(), rerollLines(fx.said()));
        assertTrue(fx.coordinatorLog.startingWith("KPQ stage 1 draw: ").getFirst()
                .endsWith("proposed=false reason=pass-exists"));
    }

    @Test
    void afterTwoRerollsInARowTheLeaderIsNotAskedAgainUntilTheStreakResets() {
        AgentKpqRerollStreaks.rerolled(1, 1_000L, POLICY.streakResetMs());
        AgentKpqRerollStreaks.rerolled(1, 1_500L, POLICY.streakResetMs());
        AgentKpqSession session = fx.companionRun(35, 35, 10);

        AgentKpqCoordinator.tick(session, 2_000L);

        assertEquals(List.of(), fx.asked);
        assertTrue(fx.coordinatorLog.startingWith("KPQ stage 1 draw: ").getFirst()
                .endsWith("proposed=false reason=streak"));
        assertEquals(0, AgentKpqRerollStreaks.current(1, 1_500L + POLICY.streakResetMs(), POLICY.streakResetMs()));
    }

    @Test
    void aStockRunNeverDecides() {
        AgentKpqSession stock = fx.stageOne(false, fx.ben, 35, 35, 10);

        AgentKpqCoordinator.tick(stock, 2_000L);
        AgentKpqCoordinator.tick(stock, 3_000L);

        assertEquals(List.of(), fx.asked);
        assertEquals(List.of(), fx.coordinatorLog.startingWith("KPQ stage 1 draw: "));
        assertEquals(List.of(), rerollLines(fx.said()));
        assertFalse(AgentKpqSessionRegistry.rerollAsked(stock.sessionId()));
    }

    @Test
    void anAllAgentRunNeverDecidesEvenAdopted() {
        fx.run.leaveParty(fx.ben);
        fx.run.leadParty(fx.mira);
        AgentKpqSession allAgent = fx.run.session(true, AgentKpqSession.Phase.STAGE_1, fx.mira, fx.pip, fx.tobi);
        allAgent.setStageOneLevers(AgentKpqStageOneLevers.NONE);
        allAgent.setRerollPolicy(POLICY);
        RerollRunFixture.collecting(allAgent, fx.pip, 35);
        RerollRunFixture.collecting(allAgent, fx.tobi, 35);

        AgentKpqCoordinator.tick(allAgent, 2_000L);
        AgentKpqCoordinator.tick(allAgent, 3_000L);

        assertEquals(List.of(), fx.asked);
        assertEquals(List.of(), fx.coordinatorLog.startingWith("KPQ stage 1 draw: "));
        assertEquals(List.of(), rerollLines(fx.said()));
        assertFalse(AgentKpqSessionRegistry.rerollAsked(allAgent.sessionId()));
    }

    @Test
    void withWarmPaceDataTheEstimateDecidesAtTheRateTheLineupWasPricedWith() {
        try (MockedStatic<AgentPqPaceProfiles> profiles = mockStatic(AgentPqPaceProfiles.class)) {
            profiles.when(() -> AgentPqPaceProfiles.cached("kpq")).thenReturn(PROFILE);
            // One 35 and two 30s: the rule of thumb would keep it, the estimate wants it gone for this slow party.
            AgentKpqSession slow = fx.companionRun(35, 30, 30);
            List<AgentPqMemberPower> power = party(100.0d);
            slow.pace().beginStageOne(power, List.of(2, 3, 4));
            double secondsPerKill = AgentPqPaceEstimator.theorySeconds(PROFILE, power,
                    AgentPqPaceCalibration.prior(PROFILE), AgentKpqPaceProfile.S1_GRIND, 1.0d);

            AgentKpqCoordinator.tick(slow, 2_000L);

            assertEquals(List.of("Mira: " + AgentKpqRerollPolicy.LOT_OF_COUPONS_LINE), rerollLines(fx.said()));
            long saving = Math.round((95.0d - FRESH_MEAN) * secondsPerKill * 1_000.0d - 75_000.0d);
            assertEquals(List.of("KPQ stage 1 draw: session=" + slow.sessionId() + " targets=[35, 30, 30] total=95"
                            + " remaining=95 rule=estimate savingMs=" + saving + " proposed=true reason=saving-reaches-minimum"),
                    fx.coordinatorLog.startingWith("KPQ stage 1 draw: "));
            assertEquals("estimate", fx.asked.getFirst().rule());
        }
    }

    @Test
    void aCalibrationThatLearnedAQuickGrindKeepsTheSameDraw() {
        try (MockedStatic<AgentPqPaceProfiles> profiles = mockStatic(AgentPqPaceProfiles.class)) {
            profiles.when(() -> AgentPqPaceProfiles.cached("kpq")).thenReturn(PROFILE);
            AgentKpqSession session = fx.companionRun(35, 30, 30);
            session.pace().beginStageOne(party(100.0d), List.of(2, 3, 4));
            AgentPqPaceCalibration prior = AgentPqPaceCalibration.prior(PROFILE);
            Map<String, Double> quick = new TreeMap<>(prior.companion());
            quick.put("s1.grind.seek", 0.0d);
            quick.put("s1.grind.combat", 1.0d);
            session.setPaceCalibration(new AgentPqPaceCalibration("kpq", PROFILE.epoch(), quick, prior.leader(),
                    prior.logSpread(), 5, AgentPqPaceCalibration.Source.RECORDS, Map.of()));

            AgentKpqCoordinator.tick(session, 2_000L);

            assertEquals(List.of(), fx.asked);
            String line = fx.coordinatorLog.startingWith("KPQ stage 1 draw: ").getFirst();
            assertTrue(line.contains(" rule=estimate ") && line.endsWith("proposed=false reason=saving-under-minimum"),
                    line);
        }
    }

    @Test
    void aHumanCollectorsNumberIsClotosAndWaitsUntilHeHasOne() {
        Character ann = annCollecting();
        AgentKpqSession session = annsRun(ann, 35, 10);
        when(fx.run.event.gridCheck(ann)).thenReturn(-1); // he has not asked Cloto yet

        AgentKpqCoordinator.tick(session, 2_000L);
        assertEquals(List.of(), fx.coordinatorLog.startingWith("KPQ stage 1 draw: "), "his number is not known");

        when(fx.run.event.gridCheck(ann)).thenReturn(1); // Cloto asked him for 10
        AgentKpqCoordinator.tick(session, 3_000L);

        assertEquals(List.of("KPQ stage 1 draw: session=" + session.sessionId()
                        + " targets=[35, 10, 10] total=55 remaining=55 rule=thirty-fives savingMs=- proposed=false"
                        + " reason=fewer-than-two-35s"),
                fx.coordinatorLog.startingWith("KPQ stage 1 draw: "));
        assertEquals(List.of(), fx.asked);
    }

    @Test
    void aHumanCollectorsThirtyFiveCountsTowardTheTwo() {
        Character ann = annCollecting();
        AgentKpqSession session = annsRun(ann, 35, 10);
        when(fx.run.event.gridCheck(ann)).thenReturn(2); // 35

        AgentKpqCoordinator.tick(session, 2_000L);

        assertEquals(List.of("Mira: " + THIRTY_FIVES_LINE), rerollLines(fx.said()));
        assertTrue(fx.coordinatorLog.startingWith("KPQ stage 1 draw: ").getFirst()
                .contains(" targets=[35, 10, 35] total=80 "));
    }

    @Test
    void aHumanCollectorWhosePassIsMadeKeepsTheRunAsDrawn() {
        Character ann = annCollecting();
        AgentKpqSession session = annsRun(ann, 35, 35);
        when(fx.run.event.gridCheck(ann)).thenReturn(0); // Cloto cleared his grid: his pass is made

        AgentKpqCoordinator.tick(session, 2_000L);

        assertEquals(List.of(), fx.asked);
        assertTrue(fx.coordinatorLog.startingWith("KPQ stage 1 draw: ").getFirst()
                .endsWith("proposed=false reason=pass-exists"));
    }

    @Test
    void aPassLyingOnTheFloorKeepsTheRunAsDrawn() {
        AgentKpqSession session = fx.companionRun(35, 35, 10);
        MapItem pass = mock(MapItem.class);
        when(pass.getItemId()).thenReturn(AgentKpqDefinition.PASS_ITEM);
        when(fx.run.map.getDroppedItems()).thenReturn(List.of(pass));

        AgentKpqCoordinator.tick(session, 2_000L);

        assertEquals(List.of(), fx.asked);
        assertEquals(List.of(), rerollLines(fx.said()));
        assertTrue(fx.coordinatorLog.startingWith("KPQ stage 1 draw: ").getFirst()
                .endsWith("proposed=false reason=pass-exists"));
    }

    @Test
    void aPassInTheLeadersBagKeepsTheRunAsDrawn() {
        AgentKpqSession session = fx.companionRun(35, 35, 10);
        when(fx.ben.getItemQuantity(AgentKpqDefinition.PASS_ITEM, false)).thenReturn(1);

        AgentKpqCoordinator.tick(session, 2_000L);

        assertEquals(List.of(), fx.asked);
        assertTrue(fx.coordinatorLog.startingWith("KPQ stage 1 draw: ").getFirst()
                .endsWith("proposed=false reason=pass-exists"));
    }

    @Test
    void theCoordinatorPricesTheCouponsStillNeededTheLeadersIncluded() {
        try (MockedStatic<AgentPqPaceProfiles> profiles = mockStatic(AgentPqPaceProfiles.class)) {
            profiles.when(() -> AgentPqPaceProfiles.cached("kpq")).thenReturn(PROFILE);
            AgentKpqSession session = fx.companionRun(35, 30, 30);
            List<AgentPqMemberPower> power = party(100.0d);
            session.pace().beginStageOne(power, List.of(2, 3, 4));
            double secondsPerKill = AgentPqPaceEstimator.theorySeconds(PROFILE, power,
                    AgentPqPaceCalibration.prior(PROFILE), AgentKpqPaceProfile.S1_GRIND, 1.0d);
            // Mira picked up 20 of her 35 while the last number came, and Ben 5 he will drop by a collector.
            when(fx.mira.getItemQuantity(AgentKpqDefinition.COUPON_ITEM, false)).thenReturn(20);
            when(fx.ben.getItemQuantity(AgentKpqDefinition.COUPON_ITEM, false)).thenReturn(5);

            AgentKpqCoordinator.tick(session, 2_000L);

            double saving = (70.0d - FRESH_MEAN) * secondsPerKill * 1_000.0d - 75_000.0d;
            String line = fx.coordinatorLog.startingWith("KPQ stage 1 draw: ").getFirst();
            assertTrue(line.contains(" targets=[35, 30, 30] total=95 remaining=70 rule=estimate savingMs="
                    + Math.round(saving) + " proposed=" + (saving >= 60_000.0d) + " "), line);
        }
    }

    @Test
    void withThePolicyOffTheRunNeverDecidesSaysOrShowsAnything() {
        AgentKpqSession session = fx.companionRun(35, 35, 35);
        session.setRerollPolicy(POLICY.off());

        AgentKpqCoordinator.tick(session, 2_000L);
        AgentKpqCoordinator.tick(session, 3_000L);

        assertEquals(List.of(), fx.asked);
        assertEquals(List.of(), rerollLines(fx.said()));
        assertEquals(List.of(), fx.coordinatorLog.startingWith("KPQ stage 1 draw: "));
        assertFalse(AgentKpqSessionRegistry.rerollAsked(session.sessionId()));
    }

    @Test
    void theNextRunsDrawMeasuresWhatTheLastRerollCost() {
        AgentKpqRerollStreaks.rerolled(1, 400L, POLICY.streakResetMs()); // Ben's last run let go at 400
        AgentKpqSession session = fx.companionRun(35, 20, 10); // in stage 1 from 1000

        AgentKpqCoordinator.tick(session, 2_000L);
        AgentKpqCoordinator.tick(session, 3_000L);

        assertEquals(List.of("KPQ re-roll restart: session=" + session.sessionId()
                        + " leader=Ben toStageOneMs=600 toDrawMs=1600 assumedMs=75000"),
                fx.coordinatorLog.startingWith("KPQ re-roll restart: "));
    }

    @Test
    void aLeaderWithNoRecentRerollHasNoRestartToMeasure() {
        AgentKpqRerollStreaks.rerolled(1, 400L, POLICY.streakResetMs());
        AgentKpqSession session = fx.companionRun(35, 20, 10);

        AgentKpqCoordinator.tick(session, 400L + POLICY.streakResetMs());

        assertEquals(1, fx.coordinatorLog.startingWith("KPQ stage 1 draw: ").size());
        assertEquals(List.of(), fx.coordinatorLog.startingWith("KPQ re-roll restart: "));
    }

    /** Ann, a second human, collects beside Mira and Pip in Ben's run; Tobi stays out of it. */
    private Character annCollecting() {
        Character ann = RerollRunFixture.at(fx.run.human(5, "Ann"), 100, 0);
        fx.run.leaveParty(fx.tobi);
        return ann;
    }

    private AgentKpqSession annsRun(Character ann, int miraTarget, int pipTarget) {
        AgentKpqSession session = fx.run.session(true, AgentKpqSession.Phase.STAGE_1, fx.ben, fx.mira, fx.pip, ann);
        session.setStageOneLevers(AgentKpqStageOneLevers.NONE);
        session.setRerollPolicy(POLICY);
        RerollRunFixture.collecting(session, fx.mira, miraTarget);
        RerollRunFixture.collecting(session, fx.pip, pipTarget);
        return session;
    }

    /** The re-roll's own lines among those said on the map. */
    static List<String> rerollLines(List<String> said) {
        return said.stream().filter(line -> line.contains("run it again") || line.endsWith(RerollRunFixture.GO_AGAIN_LINE)
                || line.endsWith(RerollRunFixture.GRIND_IT_OUT_LINE)).toList();
    }

    /** Ben (a human leader who does not hunt) and three companions dealing {@code ligatorDps} to Ligators. */
    private static List<AgentPqMemberPower> party(double ligatorDps) {
        return List.of(member(1, true, 0.0d), member(2, false, ligatorDps), member(3, false, ligatorDps),
                member(4, false, ligatorDps));
    }

    private static AgentPqMemberPower member(int id, boolean human, double ligatorDps) {
        return new AgentPqMemberPower(id, "m" + id, human, human, 25, 100, Map.of(9_300_001, ligatorDps),
                Map.of(9_300_001, 1.0d), Map.of(9_300_001, 800.0d), 10.0d);
    }

    private static AgentKpqRerollPolicy.Draw cold(Integer... targets) {
        return new AgentKpqRerollPolicy.Draw(true, false, 0, false, List.of(targets), Double.NaN, Double.NaN);
    }

    private static AgentKpqRerollPolicy.Draw warm(double secondsPerKill, Integer... targets) {
        return new AgentKpqRerollPolicy.Draw(true, false, 0, false, List.of(targets), secondsPerKill, FRESH_MEAN);
    }
}
