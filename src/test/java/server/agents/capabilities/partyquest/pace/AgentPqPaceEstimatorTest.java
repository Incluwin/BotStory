package server.agents.capabilities.partyquest.pace;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static server.agents.capabilities.partyquest.pace.PaceStubProfile.BOSS;
import static server.agents.capabilities.partyquest.pace.PaceStubProfile.MOB_A;
import static server.agents.capabilities.partyquest.pace.PaceStubProfile.MOB_B;
import static server.agents.capabilities.partyquest.pace.PaceStubProfile.MOB_C;
import static server.agents.capabilities.partyquest.pace.PaceStubProfile.companion;
import static server.agents.capabilities.partyquest.pace.PaceStubProfile.humanLeader;

/**
 * The clear-time estimate: party damage shortens the kill terms (the quota by less than it grows, because seeking a
 * mob costs the same however hard a member hits), the leader's and the companions' fixed times stay as they are, the
 * slowest of parallel branches decides, and the shared efficiency spread widens the range as a lognormal does.
 */
class AgentPqPaceEstimatorTest {
    private static final List<AgentPqMemberPower> PARTY = List.of(humanLeader(1),
            companion(2, 100, 50, 200, 80), companion(3, 50, 100, 50, 40));

    @Test
    void killTermsFollowTheHandWorkedTheory() {
        AgentPqPaceEstimate estimate = estimate(PaceStubProfile.standard(), PARTY, calibration(0.0d, Map.of()));

        assertNotNull(estimate);
        assertTrue(estimate.clearable());
        // Water-fill: member 2 on C (200) and member 3 on B (100); C dies at 5 s, then both on B's last 500 HP at 150.
        assertEquals((5.0d + 500.0d / 150.0d) / 0.2d, estimate.segmentMeanS().get("normals"), 1e-9);
        assertEquals(4_000.0d / 120.0d / 0.25d, estimate.segmentMeanS().get("boss"), 1e-9);
        // 40 kills on average at 1/30 + 1/50 kills a second (seek 10 s plus 1000 HP at half of 100 or 50 dps).
        assertEquals(40.0d / (1.0d / 30.0d + 1.0d / 50.0d), estimate.segmentMeanS().get("grind"), 1e-6);
        // Checks after the first: 0-3 at 9 s each.
        assertEquals(1.5d * 9.0d, estimate.segmentMeanS().get("checks"), 1e-9);
        assertEquals(3.0d + 41.6667d + 133.3333d + 20.0d, estimate.segmentMeanS().get("race"), 1e-3);
        assertEquals(Map.of(MOB_A, 150.0d, MOB_B, 150.0d, MOB_C, 250.0d, BOSS, 120.0d), estimate.partyDpsByMob());
        assertEquals(AgentPqPaceCalibration.Source.PRIOR, estimate.source());
    }

    @Test
    void doublingDamageHalvesKillTermsOnlyWithoutSeekAndLeavesFixedTermsAlone() {
        List<AgentPqMemberPower> doubled = PARTY.stream().map(member -> member.withDpsScaled(2.0d)).toList();
        AgentPqPaceCalibration noSeek = calibration(0.25d, Map.of("grind.seek", 0.0d));

        AgentPqPaceEstimate base = estimate(PaceStubProfile.standard(), PARTY, noSeek);
        AgentPqPaceEstimate strong = estimate(PaceStubProfile.standard(), doubled, noSeek);
        for (String key : List.of("grind", "normals", "boss")) {
            assertEquals(0.5d, strong.segmentMeanS().get(key) / base.segmentMeanS().get(key), 1e-9, key);
        }
        for (String key : List.of("open", "handin", "entry", "checks", "move", "carry", "climb", "talks")) {
            assertEquals(base.segmentMeanS().get(key), strong.segmentMeanS().get(key), 1e-9, key);
        }

        AgentPqPaceCalibration withSeek = calibration(0.25d, Map.of());
        double grind = estimate(PaceStubProfile.standard(), PARTY, withSeek).segmentMeanS().get("grind");
        double strongGrind = estimate(PaceStubProfile.standard(), doubled, withSeek).segmentMeanS().get("grind");
        // Per kill 30 s and 50 s become 20 s and 30 s: the grind falls to 0.64 of itself, not to half.
        assertEquals((1.0d / 30.0d + 1.0d / 50.0d) / (1.0d / 20.0d + 1.0d / 30.0d), strongGrind / grind, 1e-9);
        assertTrue(strongGrind / grind > 0.55d, "seek must keep the grind above half: " + strongGrind / grind);
    }

    @Test
    void quantilesAreOrderedAndWidenWithTheSpread() {
        for (double spread : new double[]{0.0d, 0.15d, 0.3d, 0.45d}) {
            AgentPqPaceEstimate estimate = estimate(PaceStubProfile.standard(), PARTY, calibration(spread, Map.of()));
            assertTrue(estimate.p10S() <= estimate.p50S() && estimate.p50S() <= estimate.p90S(), "spread " + spread);
            assertTrue(estimate.p10S() < estimate.p90S(), "spread " + spread);
        }
        // With the kill count's own gaps (10 kills apart) the width is only monotone once the spread dominates them;
        // the single-clear lognormal check below pins the quadrature's monotonicity exactly.
        AgentPqPaceEstimate narrow = estimate(PaceStubProfile.standard(), PARTY, calibration(0.15d, Map.of()));
        AgentPqPaceEstimate wide = estimate(PaceStubProfile.standard(), PARTY, calibration(0.45d, Map.of()));
        assertTrue(wide.p90S() - wide.p10S() > narrow.p90S() - narrow.p10S());
        assertTrue(wide.p10S() < narrow.p10S() && wide.p90S() > narrow.p90S());
    }

    @Test
    void theSharedMultiplierTracksALognormal() {
        // One clear of 1000 s at efficiency 1: its quantiles are 1000 exp(z sigma) for the normal quantiles z.
        PaceStubProfile single = PaceStubProfile.standard().with(List.of(
                new AgentPqPaceSegment.Clear("only", Map.of(MOB_B, 1), Map.of(MOB_B, 100_000), PaceStubProfile.GREEDY,
                        false)), Map.of("only", 1.0d));
        double sigma = 0.3d;
        AgentPqPaceEstimate estimate = estimate(single, List.of(companion(2, 0, 100, 0, 0), companion(3, 0, 0, 0, 0)),
                new AgentPqPaceCalibration("stub", 1, Map.of("only", 1.0d), Map.of(), sigma, 0,
                        AgentPqPaceCalibration.Source.PRIOR, Map.of()));

        assertEquals(Math.log(1_000.0d * Math.exp(-1.2816d * sigma)), Math.log(estimate.p10S()), 0.06d);
        assertEquals(Math.log(1_000.0d), Math.log(estimate.p50S()), 0.01d);
        assertEquals(Math.log(1_000.0d * Math.exp(1.2816d * sigma)), Math.log(estimate.p90S()), 0.06d);
        assertEquals(1_000.0d * Math.exp(sigma * sigma / 2.0d), estimate.meanS(), 1.0d);

        double previousP10 = Double.MAX_VALUE;
        double previousP90 = 0.0d;
        for (double spread : new double[]{0.0d, 0.1d, 0.2d, 0.3d, 0.4d}) {
            AgentPqPaceEstimate wider = estimate(single, List.of(companion(2, 0, 100, 0, 0), companion(3, 0, 0, 0, 0)),
                    new AgentPqPaceCalibration("stub", 1, Map.of("only", 1.0d), Map.of(), spread, 0,
                            AgentPqPaceCalibration.Source.PRIOR, Map.of()));
            assertTrue(wider.p10S() < previousP10 && wider.p90S() > previousP90, "spread " + spread);
            previousP10 = wider.p10S();
            previousP90 = wider.p90S();
        }
    }

    @Test
    void theSlowerParallelBranchDecides() {
        Map<String, Double> priors = new LinkedHashMap<>(Map.of("a", 100_000.0d, "b", 40_000.0d));
        List<AgentPqPaceSegment> race = List.of(new AgentPqPaceSegment.Parallel("race", List.of(
                List.of(new AgentPqPaceSegment.Fixed("a", AgentPqPaceSegment.Side.COMPANION)),
                List.of(new AgentPqPaceSegment.Fixed("b", AgentPqPaceSegment.Side.LEADER)))));
        PaceStubProfile profile = PaceStubProfile.standard().with(race, priors);

        assertEquals(100.0d, estimate(profile, PARTY, AgentPqPaceCalibration.prior(profile)).segmentMeanS().get("race"),
                1e-9);
        priors.put("a", 30_000.0d);
        priors.put("b", 70_000.0d);
        PaceStubProfile swapped = PaceStubProfile.standard().with(race, priors);
        AgentPqPaceEstimate estimate = estimate(swapped, PARTY, AgentPqPaceCalibration.prior(swapped));
        assertEquals(70.0d, estimate.segmentMeanS().get("race"), 1e-9);
        assertEquals(70.0d, estimate.p50S(), 2.5d);
    }

    @Test
    void aDrawnBranchRacesAFixedOneByItsDistribution() {
        // Checks after the first: 0, 10, 20 or 30 s, each a quarter, raced against a fixed 15 s: 15, 15, 20, 30.
        List<AgentPqPaceSegment> race = List.of(new AgentPqPaceSegment.Parallel("race", List.of(
                List.of(new AgentPqPaceSegment.Puzzle("checks", 4, 0.0d, "p.check", "p.move")),
                List.of(new AgentPqPaceSegment.Fixed("wait", AgentPqPaceSegment.Side.LEADER)))));
        PaceStubProfile profile = PaceStubProfile.standard().with(race,
                Map.of("p.check", 10_000.0d, "p.move", 0.0d, "wait", 15_000.0d));

        AgentPqPaceEstimate estimate = estimate(profile, PARTY, AgentPqPaceCalibration.prior(profile));
        assertEquals((15.0d + 15.0d + 20.0d + 30.0d) / 4.0d, estimate.segmentMeanS().get("race"), 1e-9);
        assertEquals(15.0d, estimate.p10S(), 1e-9);
        assertEquals(30.0d, estimate.p90S(), 1e-9);
    }

    @Test
    void aBossNobodyCanHurtMakesTheLineupUnclearableWithoutNaN() {
        List<AgentPqMemberPower> noBossDamage = List.of(humanLeader(1), companion(2, 100, 50, 200, 0),
                companion(3, 50, 100, 50, 0));

        AgentPqPaceEstimate estimate = estimate(PaceStubProfile.standard(), noBossDamage, calibration(0.25d, Map.of()));

        assertFalse(estimate.clearable());
        assertTrue(estimate.weaknesses().contains(
                new AgentPqPaceEstimate.Weakness(0, BOSS, "boss", AgentPqPaceEstimate.Kind.UNHITTABLE)));
        for (double value : new double[]{estimate.p10S(), estimate.p50S(), estimate.p90S(), estimate.meanS()}) {
            assertFalse(Double.isNaN(value));
            assertEquals(3_600.0d, value, 1e-9);
        }
        assertEquals(1.0d, estimate.overTimerChance(), 1e-12);
        estimate.segmentMeanS().values().forEach(value -> assertFalse(Double.isNaN(value) || Double.isInfinite(value)));
    }

    @Test
    void aQuotaNobodyCanKillIsUnhittableToo() {
        AgentPqPaceEstimate estimate = estimate(PaceStubProfile.standard(), List.of(humanLeader(1),
                companion(2, 0, 50, 200, 80), companion(3, 0, 100, 50, 40)), calibration(0.25d, Map.of()));

        assertFalse(estimate.clearable());
        assertTrue(estimate.weaknesses().contains(
                new AgentPqPaceEstimate.Weakness(0, MOB_A, "grind", AgentPqPaceEstimate.Kind.UNHITTABLE)));
    }

    @Test
    void coldProfilesMissingCalibrationsAndOddSizesGiveNoEstimate() {
        PaceStubProfile profile = PaceStubProfile.standard();
        assertNull(estimate(profile.cold(), PARTY, calibration(0.25d, Map.of())));
        assertNull(AgentPqPaceEstimator.estimate(profile, PARTY, null));
        assertNull(estimate(profile, List.of(humanLeader(1)), calibration(0.25d, Map.of())));
    }

    @Test
    void namesMissingFragileAndSlowMembersOnlyAmongCompanions() {
        AgentPqMemberPower misses = new AgentPqMemberPower(4, "misses", false, false, 21, 100,
                Map.of(MOB_A, 10.0d, MOB_B, 10.0d, MOB_C, 10.0d, BOSS, 10.0d),
                Map.of(MOB_A, 0.5d, MOB_B, 0.3d, MOB_C, 0.9d, BOSS, 0.2d),
                Map.of(), 5.0d);
        AgentPqMemberPower blindLeader = new AgentPqMemberPower(1, "leader", true, true, 26, 0,
                Map.of(), Map.of(MOB_A, 0.01d), Map.of(), 2.0d);

        AgentPqPaceEstimate estimate = estimate(PaceStubProfile.standard(), List.of(blindLeader, misses),
                calibration(0.25d, Map.of()));

        assertEquals(List.of(
                new AgentPqPaceEstimate.Weakness(4, MOB_A, "grind", AgentPqPaceEstimate.Kind.MISSES),
                new AgentPqPaceEstimate.Weakness(4, MOB_B, "normals", AgentPqPaceEstimate.Kind.MISSES),
                new AgentPqPaceEstimate.Weakness(4, BOSS, "boss", AgentPqPaceEstimate.Kind.MISSES),
                new AgentPqPaceEstimate.Weakness(4, BOSS, "boss", AgentPqPaceEstimate.Kind.FRAGILE),
                new AgentPqPaceEstimate.Weakness(0, MOB_A, "grind", AgentPqPaceEstimate.Kind.SLOW),
                // The lone fighter takes B first (a tie goes to the lower id), so C holds the clear up longest.
                new AgentPqPaceEstimate.Weakness(0, MOB_C, "normals", AgentPqPaceEstimate.Kind.SLOW),
                new AgentPqPaceEstimate.Weakness(0, BOSS, "boss", AgentPqPaceEstimate.Kind.SLOW)),
                estimate.weaknesses());
    }

    @Test
    void theTimerChanceCountsOnlyTimesPastTheTimer() {
        Map<String, Double> priors = Map.of("only", 100_000.0d);
        List<AgentPqPaceSegment> fixed = List.of(new AgentPqPaceSegment.Fixed("only", AgentPqPaceSegment.Side.LEADER));
        PaceStubProfile tight = PaceStubProfile.standard().with(fixed, priors).withTimer(90_000L);
        PaceStubProfile loose = PaceStubProfile.standard().with(fixed, priors).withTimer(200_000L);
        PaceStubProfile unknown = PaceStubProfile.standard().with(fixed, priors).withTimer(0L);

        assertEquals(1.0d, estimate(tight, PARTY, AgentPqPaceCalibration.prior(tight)).overTimerChance(), 1e-12);
        assertEquals(0.0d, estimate(loose, PARTY, AgentPqPaceCalibration.prior(loose)).overTimerChance(), 1e-12);
        assertEquals(0.0d, estimate(unknown, PARTY, AgentPqPaceCalibration.prior(unknown)).overTimerChance(), 1e-12);
    }

    @Test
    void theoryOfOneRecordedSpanUsesItsRealisedWork() {
        PaceStubProfile profile = PaceStubProfile.standard();
        AgentPqPaceCalibration prior = AgentPqPaceCalibration.prior(profile);

        assertEquals(60.0d / (1.0d / 30.0d + 1.0d / 50.0d),
                AgentPqPaceEstimator.theorySeconds(profile, PARTY, prior, "grind", 60.0d), 1e-9);
        assertEquals((5.0d + 500.0d / 150.0d) / 0.2d,
                AgentPqPaceEstimator.theorySeconds(profile, PARTY, prior, "normals", 0.0d), 1e-9);
        assertEquals(2.0d * 9.0d, AgentPqPaceEstimator.theorySeconds(profile, PARTY, prior, "checks", 3.0d), 1e-9);
        assertEquals(40.0d, AgentPqPaceEstimator.theorySeconds(profile, PARTY, prior, "climb", 0.0d), 1e-9);
        assertEquals(3.0d + (5.0d + 500.0d / 150.0d) / 0.2d + 4_000.0d / 120.0d / 0.25d + 20.0d,
                AgentPqPaceEstimator.theorySeconds(profile, PARTY, prior, "race", 0.0d), 1e-9);
    }

    private static AgentPqPaceEstimate estimate(AgentPqPaceProfile profile, List<AgentPqMemberPower> members,
                                                AgentPqPaceCalibration calibration) {
        return AgentPqPaceEstimator.estimate(profile, members, calibration);
    }

    /** The stub's priors with these companion terms replaced and this spread. */
    private static AgentPqPaceCalibration calibration(double spread, Map<String, Double> companionOverrides) {
        AgentPqPaceCalibration prior = AgentPqPaceCalibration.prior(PaceStubProfile.standard());
        Map<String, Double> companion = new LinkedHashMap<>(prior.companion());
        companion.putAll(companionOverrides);
        return new AgentPqPaceCalibration(prior.questKey(), prior.epoch(), companion, prior.leader(), spread, 0,
                AgentPqPaceCalibration.Source.PRIOR, Map.of());
    }
}
