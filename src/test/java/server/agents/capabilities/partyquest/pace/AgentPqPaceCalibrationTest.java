package server.agents.capabilities.partyquest.pace;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static server.agents.capabilities.partyquest.pace.PaceStubProfile.MOB_A;
import static server.agents.capabilities.partyquest.pace.PaceStubProfile.companion;
import static server.agents.capabilities.partyquest.pace.PaceStubProfile.humanLeader;

/**
 * Learning a quest's pace from recorded runs: each term moves from its prior in log space as runs come in (three
 * pseudo-runs for the companions, two for a leader), newer runs weigh more, only complete spans of the current epoch
 * count, and one leader's habits never reach another leader's estimate before three leaders have runs, nor an excluded
 * leader's at all.
 */
class AgentPqPaceCalibrationTest {
    private static final PaceStubProfile PROFILE = PaceStubProfile.standard();
    private static final List<AgentPqMemberPower> PARTY = List.of(humanLeader(1),
            companion(2, 100, 50, 200, 80), companion(3, 50, 100, 50, 40));
    /** The party's water-fill over B and C: member 2 on C, member 3 on B, then both on B. */
    private static final double NORMALS_THEORY_S = 5.0d + 500.0d / 150.0d;

    @Test
    void noRowsGiveThePrior() {
        AgentPqPaceCalibration prior = AgentPqPaceCalibration.prior(PROFILE);
        AgentPqPaceCalibration fit = AgentPqPaceCalibration.fit(PROFILE, List.of(), 1, Set.of());

        assertEquals(prior, fit);
        assertEquals(AgentPqPaceCalibration.Source.PRIOR, fit.source());
        assertEquals(0, fit.records());
        assertEquals(0.25d, fit.logSpread(), 1e-12);
        assertEquals(Set.of("handin", "p.entry", "p.check", "p.move", "climb", "talks"), fit.leader().keySet());
    }

    @Test
    void aRunMovesATermInLogSpaceWithThreePseudoRunsAndSetsTheSpreadFromItsResidual() {
        long normalsMs = Math.round(NORMALS_THEORY_S / 0.4d * 1000.0d);
        double observed = NORMALS_THEORY_S / (normalsMs / 1000.0d);

        AgentPqPaceCalibration fit = AgentPqPaceCalibration.fit(PROFILE,
                List.of(observation(0, 1, pace(1, span("normals", 0L, normalsMs, true)))), 1, Set.of());

        double expected = Math.exp((3.0d * Math.log(0.2d) + Math.log(observed)) / 4.0d);
        assertEquals(expected, fit.companion().get("normals"), 1e-12);
        double residual = Math.log(expected / observed);
        assertEquals(Math.sqrt((3.0d * 0.0625d + residual * residual) / 4.0d), fit.logSpread(), 1e-12);
        assertEquals(1, fit.records());
        assertEquals(AgentPqPaceCalibration.Source.RECORDS, fit.source());
        assertEquals(AgentPqPaceCalibration.prior(PROFILE).leader(), fit.leader());
    }

    @Test
    void anUnseenFixedSegmentFoldedIntoTheNextSpanIsSubtracted() {
        long normalsMs = 3_000L + Math.round(NORMALS_THEORY_S / 0.4d * 1000.0d);
        AgentPqPaceCalibration folded = AgentPqPaceCalibration.fit(PROFILE, List.of(observation(0, 1, pace(1,
                span("move", 0L, 0L, false), span("normals", 0L, normalsMs, true)))), 1, Set.of());
        AgentPqPaceCalibration plain = AgentPqPaceCalibration.fit(PROFILE, List.of(observation(0, 1, pace(1,
                span("normals", 0L, normalsMs - 3_000L, true)))), 1, Set.of());

        assertEquals(plain.companion().get("normals"), folded.companion().get("normals"), 1e-12);
        assertEquals(3_000.0d, folded.companion().get("move"), 1e-12);
        assertEquals(Map.of(), folded.fitNotes());
    }

    @Test
    void theSeekBisectionMatchesAHandComputation() {
        AgentPqPaceSegment.Quota quota = (AgentPqPaceSegment.Quota) PROFILE.segments(2).get(1);
        List<AgentPqMemberPower> one = List.of(humanLeader(1), companion(2, 100, 0, 0, 0));

        // 1000 HP at half of 100 dps is 20 s a kill; 0.04 kills a second leaves 5 s of seek.
        assertEquals(5_000.0d, AgentPqPaceCalibration.solveSeekMs(quota, one, 0.5d, 0.04d, 120_000.0d), 1e-6);

        AgentPqPaceCalibration fit = AgentPqPaceCalibration.fit(PROFILE,
                List.of(observation(0, 1, pace(1, one, List.of(), span("grind", 0L, 1_200_000L, true, 48.0d)))),
                1, Set.of());
        assertEquals((3.0d * 10_000.0d + 5_000.0d) / 4.0d, fit.companion().get("grind.seek"), 1e-6);
        assertEquals(0.5d, fit.companion().get("grind.combat"), 1e-12);

        AgentPqPaceCalibration fast = AgentPqPaceCalibration.fit(PROFILE,
                List.of(observation(0, 1, pace(1, one, List.of(), span("grind", 0L, 1_000_000L, true, 60.0d)))),
                1, Set.of());
        assertEquals(3.0d * 10_000.0d / 4.0d, fast.companion().get("grind.seek"), 1e-6);
        assertEquals(Map.of("grind.seekAtZeroBound", 1), fast.fitNotes());
    }

    @Test
    void combatEfficiencyComesFromThePolicysAttacksPerKillOverTheMeasured() {
        AgentPqMemberPower silentSin = new AgentPqMemberPower(543, "SilentSin", false, false, 25, 100,
                Map.of(MOB_A, 236.0d), Map.of(MOB_A, 0.88d), Map.of(MOB_A, 800.0d), 10.0d);
        AgentPqMemberPower faro = new AgentPqMemberPower(744, "Faro723", false, false, 21, 400,
                Map.of(MOB_A, 228.0d), Map.of(MOB_A, 1.0d), Map.of(MOB_A, 702.0d), 10.0d);
        AgentPqPace run = pace(2, List.of(humanLeader(2), silentSin, faro),
                List.of(new AgentPqPace.MemberRun(543, 0, false, 228, 26), new AgentPqPace.MemberRun(744, 1, true, 195,
                        22)));
        AgentPqPaceSegment.Quota quota = (AgentPqPaceSegment.Quota) PROFILE.segments(3).get(1);

        double silent = (1_000.0d / (236.0d * 0.8d)) / (228.0d / 26.0d);
        double faroEfficiency = (1_000.0d / (228.0d * 0.702d)) / (195.0d / 22.0d);
        double weighted = (26.0d * silent + 22.0d * faroEfficiency) / 48.0d;
        assertEquals(weighted, AgentPqPaceCalibration.runCombatEfficiency(quota, run), 1e-12);

        AgentPqPaceCalibration fit = AgentPqPaceCalibration.fit(PROFILE, List.of(observation(0, 1, run)), 2, Set.of());
        assertEquals(Math.exp((3.0d * Math.log(0.5d) + Math.log(weighted)) / 4.0d), fit.companion().get("grind.combat"),
                1e-12);
    }

    @Test
    void otherEpochsAndIncompleteSpansAreIgnored() {
        AgentPqPaceCalibration prior = AgentPqPaceCalibration.prior(PROFILE);
        long normalsMs = Math.round(NORMALS_THEORY_S / 0.4d * 1000.0d);

        AgentPqPaceCalibration otherEpoch = AgentPqPaceCalibration.fit(PROFILE,
                List.of(observation(0, 2, pace(1, span("normals", 0L, normalsMs, true)))), 1, Set.of());
        assertEquals(prior.companion(), otherEpoch.companion());
        assertEquals(0, otherEpoch.records());

        AgentPqPaceCalibration incomplete = AgentPqPaceCalibration.fit(PROFILE,
                List.of(observation(0, 1, pace(1, span("normals", 0L, normalsMs, false)))), 1, Set.of());
        assertEquals(prior.companion(), incomplete.companion());
        assertEquals(Map.of("normals.incomplete", 1), incomplete.fitNotes());
        assertEquals(AgentPqPaceCalibration.Source.PRIOR, incomplete.source());
    }

    @Test
    void olderRunsWeighHalfAfterTheHalfLife() {
        long fastMs = Math.round(NORMALS_THEORY_S / 0.4d * 1000.0d);
        long slowMs = Math.round(NORMALS_THEORY_S / 0.1d * 1000.0d);
        double fast = NORMALS_THEORY_S / (fastMs / 1000.0d);
        double slow = NORMALS_THEORY_S / (slowMs / 1000.0d);

        AgentPqPaceCalibration fit = AgentPqPaceCalibration.fit(PROFILE, List.of(
                observation(10, 1, pace(1, span("normals", 0L, slowMs, true))),
                observation(0, 1, pace(1, span("normals", 0L, fastMs, true)))), 1, Set.of());

        assertEquals(Math.exp((3.0d * Math.log(0.2d) + Math.log(fast) + 0.5d * Math.log(slow)) / 4.5d),
                fit.companion().get("normals"), 1e-12);
    }

    @Test
    void oneLeaderNeverMovesAnothersTermsBeforeThreeLeadersHaveRuns() {
        List<AgentPqPaceObservation> leaderTen = List.of(observation(0, 1, pace(10,
                span("handin", 0L, 60_000L, true))));

        assertEquals(20_000.0d, AgentPqPaceCalibration.fit(PROFILE, leaderTen, 20, Set.of()).leader().get("handin"),
                1e-9);
        assertEquals(Math.exp((2.0d * Math.log(20_000.0d) + Math.log(60_000.0d)) / 3.0d),
                AgentPqPaceCalibration.fit(PROFILE, leaderTen, 10, Set.of()).leader().get("handin"), 1e-6);

        List<AgentPqPaceObservation> threeLeaders = List.of(
                observation(0, 1, pace(10, span("handin", 0L, 60_000L, true))),
                observation(1, 1, pace(11, span("handin", 0L, 30_000L, true))),
                observation(2, 1, pace(12, span("handin", 0L, 45_000L, true))));
        double w1 = Math.pow(0.5d, 0.1d);
        double w2 = Math.pow(0.5d, 0.2d);
        double pooled = Math.exp((2.0d * Math.log(20_000.0d) + Math.log(60_000.0d) + w1 * Math.log(30_000.0d)
                + w2 * Math.log(45_000.0d)) / (3.0d + w1 + w2));
        assertEquals(pooled, AgentPqPaceCalibration.fit(PROFILE, threeLeaders, 20, Set.of()).leader().get("handin"),
                1e-6);

        double othersForTen = Math.exp((2.0d * Math.log(20_000.0d) + w1 * Math.log(30_000.0d)
                + w2 * Math.log(45_000.0d)) / (2.0d + w1 + w2));
        assertEquals(Math.exp((2.0d * Math.log(othersForTen) + Math.log(60_000.0d)) / 3.0d),
                AgentPqPaceCalibration.fit(PROFILE, threeLeaders, 10, Set.of()).leader().get("handin"), 1e-6);
    }

    @Test
    void anExcludedLeaderIsNeverLearnedNorCountedButHisCompanionsAre() {
        long normalsMs = Math.round(NORMALS_THEORY_S / 0.4d * 1000.0d);
        List<AgentPqPaceObservation> runs = List.of(
                observation(0, 1, pace(865, span("handin", 0L, 60_000L, true), span("normals", 0L, normalsMs, true))),
                observation(1, 1, pace(10, span("handin", 0L, 60_000L, true))),
                observation(2, 1, pace(11, span("handin", 0L, 60_000L, true))));

        AgentPqPaceCalibration forDriver = AgentPqPaceCalibration.fit(PROFILE, runs, 865, Set.of(865));
        assertEquals(AgentPqPaceCalibration.prior(PROFILE).leader(), forDriver.leader());
        assertNotEquals(0.2d, forDriver.companion().get("normals"));

        // Two learnable leaders only: a third leader still starts from the prior.
        assertEquals(AgentPqPaceCalibration.prior(PROFILE).leader(),
                AgentPqPaceCalibration.fit(PROFILE, runs, 20, Set.of(865)).leader());
    }

    @Test
    void puzzleChecksTeachThePerCheckTimeAfterTheFirstCheck() {
        AgentPqPaceCalibration fit = AgentPqPaceCalibration.fit(PROFILE, List.of(observation(0, 1, pace(1,
                span("checks", 0L, 30_000L, true, 4.0d), span("entry", 0L, 20_000L, true)))), 1, Set.of());

        assertEquals(Math.exp((2.0d * Math.log(9_000.0d) + Math.log(10_000.0d)) / 3.0d), fit.leader().get("p.check"),
                1e-6);
        assertEquals(Math.exp((2.0d * Math.log(15_000.0d) + Math.log(20_000.0d)) / 3.0d), fit.leader().get("p.entry"),
                1e-6);
        assertEquals(4_000.0d, fit.leader().get("p.move"), 1e-12);

        AgentPqPaceCalibration firstTry = AgentPqPaceCalibration.fit(PROFILE, List.of(observation(0, 1, pace(1,
                span("checks", 0L, 0L, true, 1.0d)))), 1, Set.of());
        assertEquals(9_000.0d, firstTry.leader().get("p.check"), 1e-12);
    }

    @Test
    void companionFixedTimesAreLearnedLikeEveryOtherTerm() {
        AgentPqPaceCalibration fit = AgentPqPaceCalibration.fit(PROFILE,
                List.of(observation(0, 1, pace(1, span("carry", 0L, 40_000L, true)))), 1, Set.of());

        assertEquals(Math.exp((3.0d * Math.log(20_000.0d) + Math.log(40_000.0d)) / 4.0d), fit.companion().get("carry"),
                1e-6);
    }

    private static AgentPqPaceObservation observation(int ageRank, int epoch, AgentPqPace pace) {
        return new AgentPqPaceObservation(ageRank, epoch, pace);
    }

    private static AgentPqPace pace(int leaderId, AgentPqPace.Span... spans) {
        return pace(leaderId, PARTY, List.of(), spans);
    }

    private static AgentPqPace pace(int leaderId, List<AgentPqMemberPower> members, List<AgentPqPace.MemberRun> runs,
                                    AgentPqPace.Span... spans) {
        return new AgentPqPace("stub", "stub-" + leaderId, leaderId, 0L, 0L, new ArrayList<>(List.of(spans)), members,
                runs, Map.of());
    }

    private static AgentPqPace.Span span(String key, long fromMs, long toMs, boolean complete) {
        return span(key, fromMs, toMs, complete, 0.0d);
    }

    private static AgentPqPace.Span span(String key, long fromMs, long toMs, boolean complete, double work) {
        return new AgentPqPace.Span(key, fromMs, toMs, work, complete);
    }
}
