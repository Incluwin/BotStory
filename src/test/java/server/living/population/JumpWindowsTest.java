package server.living.population;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The jump-quest noise's calibration (JumpWindows, docs/BOT_CONCEPT_PLAN.md F5b): sigma for a stage's chance. */
class JumpWindowsTest {
    /** A Pet-Walking Road-like stage: graph windows of 20-40 px, real landing windows a little wider. */
    private static final JumpWindows.Jump[] STAGE = {
            new JumpWindows.Jump(-2113, -2087, -2118, -2080),
            new JumpWindows.Jump(-2024, -2003, -2030, -1998),
            new JumpWindows.Jump(-1953, -1915, -1960, -1909),
            new JumpWindows.Jump(-959, -938, -964, -931),
            new JumpWindows.Jump(-893, -876, -899, -872),
            new JumpWindows.Jump(-893, -853, -897, -848),
            new JumpWindows.Jump(-983, -943, -989, -940)};

    @Test
    void aSigmaIsFoundForEveryChanceInTheRangeAndGivesThatChance() {
        for (double target : new double[]{0.3d, 0.5d, 0.7d}) {
            double sigma = JumpWindows.sigmaFor(STAGE, target);
            assertTrue(sigma > JumpWindows.SIGMA_MIN && sigma < JumpWindows.SIGMA_MAX, "sigma " + sigma);
            assertEquals(target, JumpWindows.stageSuccess(STAGE, sigma), 0.01d);
        }
        assertTrue(JumpWindows.sigmaFor(STAGE, 0.3d) > JumpWindows.sigmaFor(STAGE, 0.7d), "a worse jumper presses wider");
    }

    @Test
    void theMathMatchesDrawnPresses() {
        Random random = new Random(11L);
        for (double target : new double[]{0.3d, 0.5d, 0.7d}) {
            double sigma = JumpWindows.sigmaFor(STAGE, target);
            int cleared = 0;
            int stages = 20_000;
            for (int s = 0; s < stages; s++) {
                boolean all = true;
                for (JumpWindows.Jump jump : STAGE) {
                    int width = jump.pickHigh() - jump.pickLow();
                    int margin = Math.min(width / 2, JumpWindows.PICK_MARGIN_PX);
                    int pick = jump.pickLow() + margin + random.nextInt(Math.max(0, width - 2 * margin) + 1);
                    int launch = pick + (int) Math.round(random.nextGaussian() * sigma); // as AgentJumpNoise.launchX
                    all &= jump.landsFrom(launch);
                }
                cleared += all ? 1 : 0;
            }
            assertEquals(target, cleared / (double) stages, 0.03d, "sigma " + sigma);
        }
    }

    @Test
    void aNarrowJumpFailsMoreOftenThanAWideOne() {
        JumpWindows.Jump narrow = new JumpWindows.Jump(0, 4, -3, 7);
        JumpWindows.Jump wide = new JumpWindows.Jump(0, 60, -10, 70);
        assertTrue(JumpWindows.jumpSuccess(narrow, 6.0d) < JumpWindows.jumpSuccess(wide, 6.0d));
        assertEquals(1.0d, JumpWindows.jumpSuccess(wide, 0.0d), 1e-9, "no noise: every press lands");
    }

    @Test
    void aStageThatCannotBeFailedGetsTheWidestSpreadAndAnUnreachableOneTheNarrowest() {
        JumpWindows.Jump[] stacked = {new JumpWindows.Jump(0, 100, -400, 500)};
        assertEquals(JumpWindows.SIGMA_MAX, JumpWindows.sigmaFor(stacked, 0.5d));
        assertEquals(JumpWindows.SIGMA_MAX, JumpWindows.sigmaFor(new JumpWindows.Jump[0], 0.5d));
        JumpWindows.Jump[] impossible = {new JumpWindows.Jump(0, 0, 50, 40)};
        assertEquals(JumpWindows.SIGMA_MIN, JumpWindows.sigmaFor(impossible, 0.5d));
    }

    /**
     * A landing run with a one-pixel hole (100000202's r105->r93 lands from -1743..-1681 and -1679..-1648) is as wide as
     * both runs, less the pixel: cut at the hole it read 0.70 where the probe measured 0.98.
     */
    @Test
    void aOnePixelHoleInALandingRunCostsOnlyThatPixel() {
        JumpWindows.Jump cut = new JumpWindows.Jump(-1743, -1648, -1743, -1681);
        JumpWindows.Jump holed = jump(-1743, -1648, -1743 - 90, -1648 + 90, Integer.MIN_VALUE, Integer.MAX_VALUE,
                x -> x >= -1743 && x <= -1648 && x != -1680, x -> true);
        double sigma = 2.46d;
        assertEquals(0.70d, JumpWindows.jumpSuccess(cut, sigma), 0.02d, "the old cut run");
        assertTrue(JumpWindows.jumpSuccess(holed, sigma) > 0.95d, "both runs: " + JumpWindows.jumpSuccess(holed, sigma));
    }

    /**
     * A window in the platform's last pixels (100000202's r61->r60 picks -2104..-2103 on a platform ending at -2102, its
     * edge margin from -2106): a press is never moved past navigation's own pick, so one that comes late is the pick.
     */
    @Test
    void aPressComingLateAtThePlatformsEndIsThePick() {
        JumpWindows.Jump end = jump(-2105, -2102, -2150, -2102, -2146, -2106,
                x -> x >= -2105 && x <= -2102, x -> true);
        double sigma = 2.63d;
        double late = JumpWindows.jumpSuccess(end, sigma);
        assertTrue(late > 0.6d && late < 0.9d, "early presses miss, late ones are the pick: " + late);
    }

    /**
     * A press the noise refuses (its probe falls off the map, or lands where the citizen cannot climb on) is drawn again,
     * up to REDRAWS times, then navigation's own pick: a jump whose misses are all refused cannot be failed.
     */
    @Test
    void refusedPressesAreDrawnAgainAndThenThePickIsTaken() {
        JumpWindows.Jump allRefused = jump(0, 20, -90, 110, Integer.MIN_VALUE, Integer.MAX_VALUE,
                x -> x >= 0 && x <= 20, x -> x >= 0 && x <= 20);
        JumpWindows.Jump allKept = new JumpWindows.Jump(0, 20, 0, 20);
        assertEquals(1.0d, JumpWindows.jumpSuccess(allRefused, 10.0d), 1e-9);
        assertTrue(JumpWindows.jumpSuccess(allKept, 10.0d) < 0.8d);
        JumpWindows.Jump halfRefused = jump(0, 20, -90, 110, Integer.MIN_VALUE, Integer.MAX_VALUE,
                x -> x >= 0 && x <= 20, x -> x >= -30);
        double kept = JumpWindows.jumpSuccess(allKept, 10.0d);
        double half = JumpWindows.jumpSuccess(halfRefused, 10.0d);
        assertTrue(half > kept && half < 1.0d, "early misses refused, late ones kept: " + half + " vs " + kept);
    }

    private static JumpWindows.Jump jump(int pickLow, int pickHigh, int first, int last, int pressLow, int pressHigh,
                                         java.util.function.IntPredicate lands, java.util.function.IntPredicate kept) {
        boolean[] landing = new boolean[last - first + 1];
        boolean[] safe = new boolean[last - first + 1];
        for (int x = first; x <= last; x++) {
            landing[x - first] = lands.test(x);
            safe[x - first] = kept.test(x);
        }
        return new JumpWindows.Jump(pickLow, pickHigh, pressLow, pressHigh, first, landing, safe);
    }

    @Test
    void phiIsTheNormalDistribution() {
        assertEquals(0.5d, JumpWindows.phi(0.0d), 1e-7);
        assertEquals(0.841345d, JumpWindows.phi(1.0d), 1e-5);
        assertEquals(0.022750d, JumpWindows.phi(-2.0d), 1e-5);
    }
}
