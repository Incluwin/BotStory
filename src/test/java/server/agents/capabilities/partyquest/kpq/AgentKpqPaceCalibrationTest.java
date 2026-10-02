package server.agents.capabilities.partyquest.kpq;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.pace.AgentPqMemberPower;
import server.agents.capabilities.partyquest.pace.AgentPqPace;
import server.agents.capabilities.partyquest.pace.AgentPqPaceCalibration;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimator;
import server.agents.capabilities.partyquest.pace.AgentPqPaceObservation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The KPQ pace priors in agent-engine.yaml against the recorded runs they must fit. Each fixture is a measured run
 * rebuilt as its pace record; a prior edit that misfits any of them fails here. Today the table holds one run, session
 * kpq-8353c615 (2026-10-01 12:42:33, ohboy leading SilentSin, Fenna and Faro723 under the scripted driver), from
 * logs/cosmic-log.log ('KPQ phase', 'KPQ Stage 1 summary', 'KPQ Stage 5 mobs died', 'KPQ King Slime combat',
 * 'KPQ human puzzle verdict') and the live probe. The companions' stage-5 figures were measured mostly throttled (no
 * player on the map yet), so they understate a quick leader's stage 5; the next timed run joins the table and the
 * pace epoch is bumped with priors set from both.
 */
class AgentKpqPaceCalibrationTest {
    /** Map.wz and Mob.wz read directly (LifeFactory needs the database for drops, which tests do not have). */
    private static final AgentKpqPaceProfile PROFILE = AgentKpqPaceProfile.load(AgentKpqPaceProfile.Census.WZ);
    private static final int LIGATOR = 9_300_001;
    private static final int NECKI = 9_300_000;
    private static final int CURSE_EYE = 9_300_002;
    private static final int KING_SLIME = 9_300_003;
    private static final int OHBOY = 2;

    // Session kpq-8353c615, ms from Lakelis's Yes (12:42:33.373).
    private static final long STAGE1 = 227L;
    private static final long GRIND = STAGE1 + 15_200L;
    private static final long PASSES = STAGE1 + 519_000L;
    private static final long STAGE2 = STAGE1 + 732_300L;
    private static final long STAGE3 = STAGE2 + 229_900L;
    private static final long STAGE4 = STAGE3 + 221_800L;
    private static final long ACCEPT4 = STAGE4 + 167_100L;
    private static final long ARRIVAL = ACCEPT4 + 2_660L;
    private static final long NORMALS_DEAD = ARRIVAL + 90_800L;
    private static final long KING_SLIME_DEAD = ARRIVAL + 205_450L;
    private static final long LEADER_ON_STAGE5 = ACCEPT4 + 166_100L;
    private static final long TIMER_END = 1_800_000L;

    /** Each member's expected-damage policy figures at run-time stats (hand-recomputed, scratchpad estdata). */
    private static final List<AgentPqMemberPower> MEMBERS = List.of(
            member(OHBOY, "ohboy", true, 26, 0, 0, 0, 0, 0, 0.01d, 0.01d, 0.01d, 0.01d, 0.0d),
            member(543, "SilentSin", false, 25, 100, 236, 205, 259, 120, 0.88d, 0.65d, 1.0d, 0.56d, 800.0d),
            member(336, "Fenna", false, 26, 300, 164, 205, 158, 95, 1.0d, 1.0d, 1.0d, 1.0d, 800.0d),
            member(744, "Faro723", false, 21, 400, 228, 322, 215, 84, 1.0d, 1.0d, 1.0d, 1.0d, 702.0d));

    private static final AgentPqPace KPQ_8353C615 = new AgentPqPace("kpq", "kpq-8353c615", OHBOY, 0L, 0L, List.of(
            span("yes", 0L, STAGE1, true, 0),
            span("s1.open", STAGE1, GRIND, true, 0),
            span("s1.grind", GRIND, PASSES, true, 63),
            span("s1.handin", PASSES, STAGE2, true, 0),
            span("s2.entry", STAGE2, STAGE2 + 155_700L, true, 0),
            span("s2.checks", STAGE2 + 155_700L, STAGE3 - 34_700L, true, 3),
            span("s2.accept", STAGE3 - 34_700L, STAGE3, true, 0),
            span("s3.entry", STAGE3, STAGE3 + 102_700L, true, 0),
            span("s3.checks", STAGE3 + 102_700L, STAGE4 - 21_900L, true, 6),
            span("s3.accept", STAGE4 - 21_900L, STAGE4, true, 0),
            span("s4.entry", STAGE4, STAGE4 + 90_200L, true, 0),
            span("s4.checks", STAGE4 + 90_200L, ACCEPT4, true, 5),
            span("s5.move", ACCEPT4, ARRIVAL, true, 0),
            span("s5.descent", ARRIVAL, ARRIVAL, false, 0),
            span("s5.normals", ARRIVAL, NORMALS_DEAD, true, 7_920),
            span("s5.boss", NORMALS_DEAD, KING_SLIME_DEAD, true, 8_000),
            span("s5.loot", KING_SLIME_DEAD, KING_SLIME_DEAD + 3_000L, true, 0),
            span("s5.carry", KING_SLIME_DEAD + 3_000L, TIMER_END, false, 0),
            span("s5.climb", ACCEPT4, LEADER_ON_STAGE5, true, 0),
            span("s5.talks", TIMER_END, TIMER_END, false, 0)),
            MEMBERS, List.of(
            new AgentPqPace.MemberRun(543, 0, false, 228, 26),
            new AgentPqPace.MemberRun(336, 0, false, 226, 17),
            new AgentPqPace.MemberRun(744, 1, true, 195, 22)),
            Map.of("s1.grind", 63, "s2.checks", 3, "s3.checks", 6, "s4.checks", 5));

    @Test
    void theYamlPriorsFitTheRecordedCompanionSegments() {
        AgentPqPaceCalibration prior = AgentPqPaceCalibration.prior(PROFILE);
        List<Double> logErrors = new ArrayList<>();

        double stageOne = theory(prior, "s1.open", 0) + theory(prior, "s1.grind", 63);
        logErrors.add(assertWithinTenPercent("stage 1 to the last pass", stageOne, seconds("s1.open", "s1.grind")));
        double normals = theory(prior, "s5.descent", 0) + theory(prior, "s5.normals", 0);
        logErrors.add(assertWithinTenPercent("stage-5 normals from arrival", normals, seconds("s5.normals")));
        double kingSlime = theory(prior, "s5.boss", 0);
        logErrors.add(assertWithinTenPercent("King Slime", kingSlime, seconds("s5.boss")));

        logErrors.sort(Double::compare);
        assertTrue(logErrors.get(logErrors.size() / 2) < 0.15d, "median absolute log error " + logErrors);
    }

    @Test
    void thePriorsAreThisRunsImpliedValues() {
        AgentPqPaceCalibration prior = AgentPqPaceCalibration.prior(PROFILE);
        AgentPqPaceCalibration fit = AgentPqPaceCalibration.fit(PROFILE,
                List.of(new AgentPqPaceObservation(0, PROFILE.epoch(), KPQ_8353C615)), OHBOY, Set.of(OHBOY, 865));

        for (String term : List.of("s1.grind.combat", "s1.grind.seek", "s1.open", "s5.move", "s5.normals", "s5.boss",
                "s5.loot")) {
            assertWithinTenPercent(term, fit.companion().get(term), prior.companion().get(term));
        }
        // The scripted driver (ohboy) is excluded: none of his leader spans is learned.
        assertEquals(prior.leader(), fit.leader());
        assertEquals(1, fit.records());
    }

    @Test
    void theScriptedDriversLeaderTermsReproduceItsThirtyMinuteRuns() {
        // This run's own leader spans, unshrunk; the final talks are run 6's (112-116 s), since this run never got there.
        Map<String, Double> driver = new TreeMap<>(AgentPqPaceCalibration.prior(PROFILE).leader());
        driver.put("s1.handin", seconds("s1.handin") * 1000.0d);
        driver.put("puzzle.entry", (seconds("s2.entry") + seconds("s3.entry") + seconds("s4.entry")) / 3.0d * 1000.0d);
        driver.put("puzzle.check", (seconds("s2.checks") / 2.0d + seconds("s3.checks") / 5.0d
                + seconds("s4.checks") / 4.0d) / 3.0d * 1000.0d);
        driver.put("puzzle.accept", (seconds("s2.accept") + seconds("s3.accept")) / 2.0d * 1000.0d);
        driver.put("s5.climb", seconds("s5.climb") * 1000.0d);
        driver.put("s5.talks", 114_000.0d);
        AgentPqPaceCalibration prior = AgentPqPaceCalibration.prior(PROFILE);
        AgentPqPaceCalibration withDriver = new AgentPqPaceCalibration("kpq", prior.epoch(), prior.companion(), driver,
                prior.logSpread(), 0, AgentPqPaceCalibration.Source.PRIOR, Map.of());

        AgentPqPaceEstimate estimate = AgentPqPaceEstimator.estimate(PROFILE, MEMBERS, withDriver);
        AgentPqPaceEstimate quick = AgentPqPaceEstimator.estimate(PROFILE, MEMBERS, prior);

        double p50Minutes = estimate.p50S() / 60.0d;
        assertTrue(p50Minutes >= 25.0d && p50Minutes <= 35.0d, "driver p50 " + p50Minutes + " min");
        assertTrue(quick.p50S() < estimate.p50S() - 600.0d, "a quick leader saves over ten minutes");
        assertTrue(estimate.clearable() && quick.clearable());
    }

    private static double theory(AgentPqPaceCalibration calibration, String key, double work) {
        return AgentPqPaceEstimator.theorySeconds(PROFILE, MEMBERS, calibration, key, work);
    }

    private static double seconds(String... keys) {
        double total = 0.0d;
        for (String key : keys) total += KPQ_8353C615.span(key).durationMs() / 1000.0d;
        return total;
    }

    private static double assertWithinTenPercent(String what, double theory, double measured) {
        assertEquals(measured, theory, 0.10d * measured, what + ": theory " + theory + " vs measured " + measured);
        return Math.abs(Math.log(theory / measured));
    }

    private static AgentPqPace.Span span(String key, long fromMs, long toMs, boolean complete, double work) {
        return new AgentPqPace.Span(key, fromMs, toMs, work, complete);
    }

    private static AgentPqMemberPower member(int id, String name, boolean leader, int level, int jobId, double ligator,
                                             double necki, double curseEye, double kingSlime, double hitLigator,
                                             double hitNecki, double hitCurseEye, double hitKingSlime,
                                             double attackMs) {
        return new AgentPqMemberPower(id, name, leader, leader, level, jobId,
                Map.of(LIGATOR, ligator, NECKI, necki, CURSE_EYE, curseEye, KING_SLIME, kingSlime),
                Map.of(LIGATOR, hitLigator, NECKI, hitNecki, CURSE_EYE, hitCurseEye, KING_SLIME, hitKingSlime),
                Map.of(LIGATOR, attackMs, NECKI, attackMs, CURSE_EYE, attackMs, KING_SLIME, attackMs),
                Double.MAX_VALUE);
    }
}
