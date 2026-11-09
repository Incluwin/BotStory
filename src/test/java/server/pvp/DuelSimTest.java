package server.pvp;

import config.PvpConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The V1.4 Monte Carlo, and through it the plan's done-when numbers for the damage model. */
class DuelSimTest {
    private static final int H_REF = 4000;
    private static final double RAW_DPS = 8000;

    /** A median fighter: max HP at the reference, the model's coefficient for its own raw dps, no defenses. */
    private static DuelSim.Fighter median(String name, int level, int magicGuard, PvpConfig config) {
        double coefficient = H_REF / (config.PVP_TTK_SECONDS * RAW_DPS);
        return new DuelSim.Fighter(name, level, 112, false, H_REF, 3000, RAW_DPS, coefficient, 200, 0, 0, 0, 1d,
                magicGuard, 1d);
    }

    @Test
    void aMirrorMatchIsEvenAndLastsWithinThePlansBand() {
        PvpConfig config = new PvpConfig();
        DuelSim.Fighter fighter = median("a", 70, 0, config);

        DuelSim.Summary summary = DuelSim.run(List.of(fighter), List.of(median("b", 70, 0, config)), 4000, config,
                new Random(7));

        assertEquals(0.5d, summary.aWinRate(), 0.04d);
        assertTrue(summary.medianSeconds() >= 10 && summary.medianSeconds() <= 25,
                "median duel " + summary.medianSeconds() + " s");
    }

    @Test
    void noAttackerKnocksOutAFullHpVictimInUnderFourAttacks() {
        PvpConfig config = new PvpConfig();
        DuelSim.Fighter brute = new DuelSim.Fighter("brute", 70, 112, false, H_REF, 3000, RAW_DPS, 100d, 200, 0, 0, 0,
                1d, 0, 1d);
        DuelSim.Fighter harmless = new DuelSim.Fighter("harmless", 70, 112, false, H_REF, 3000, RAW_DPS, 0d, 200, 0, 0,
                0, 1d, 0, 1d);
        Random random = new Random(11);

        for (int i = 0; i < 500; i++) {
            DuelSim.Outcome outcome = DuelSim.round(brute, harmless, config, random);
            assertEquals(0, outcome.winner());
            // four attacks are three intervals apart at the fastest (0.9 x 0.75 s each)
            assertTrue(outcome.seconds() >= 3 * DuelSim.ATTACK_INTERVAL_S * 0.9d - 1e-9,
                    "knocked out after " + outcome.seconds() + " s");
        }
    }

    /** A median fighter of a class: its HP and the class ratio together, with the class's defense skill. */
    private static DuelSim.Fighter ofClass(String name, double ratio, double reduce, int magicGuard, PvpConfig config) {
        double coefficient = H_REF / (config.PVP_TTK_SECONDS * RAW_DPS);
        return new DuelSim.Fighter(name, 70, 112, false, (int) Math.round(H_REF * ratio), 10_000, RAW_DPS, coefficient,
                200, 0, 0, 0, reduce, magicGuard, ratio);
    }

    @Test
    void evenClassesMakeAMedianWarriorAndAMedianMagicianAnEvenMatch() {
        PvpConfig config = new PvpConfig();
        DuelSim.Fighter warrior = ofClass("warrior", 1.78d, 0.7d, 0, config);   // Achilles
        DuelSim.Fighter magician = ofClass("magician", 0.50d, 1d, 80, config);  // Magic Guard

        DuelSim.Summary summary = DuelSim.run(List.of(warrior), List.of(magician), 4000, config, new Random(13));

        assertEquals(0.5d, summary.aWinRate(), 0.05d);
        assertTrue(summary.medianSeconds() >= 10 && summary.medianSeconds() <= 25,
                "median duel " + summary.medianSeconds() + " s");
    }

    @Test
    void withoutEvenClassesTheWarriorAlmostAlwaysWins() {
        PvpConfig config = new PvpConfig();
        config.PVP_EVEN_CLASSES = false;
        DuelSim.Fighter warrior = ofClass("warrior", 1.78d, 0.7d, 0, config);
        DuelSim.Fighter magician = ofClass("magician", 0.50d, 1d, 80, config);

        DuelSim.Summary summary = DuelSim.run(List.of(warrior), List.of(magician), 2000, config, new Random(17));

        assertTrue(summary.aWinRate() > 0.9d, "warrior wins " + summary.aWinRate());
    }

    @Test
    void magicGuardAtHalfShareStillMakesAFighterLastLonger() {
        PvpConfig config = new PvpConfig();
        config.PVP_EVEN_CLASSES = false; // with even classes Magic Guard sits out
        DuelSim.Fighter attacker = median("attacker", 70, 0, config);

        DuelSim.Summary plain = DuelSim.run(List.of(attacker), List.of(median("plain", 70, 0, config)), 2000, config,
                new Random(3));
        DuelSim.Summary guarded = DuelSim.run(List.of(attacker), List.of(median("guarded", 70, 80, config)), 2000,
                config, new Random(3));

        assertTrue(guarded.aWinRate() < plain.aWinRate() - 0.1d,
                "guarded " + guarded.aWinRate() + " vs plain " + plain.aWinRate());
    }

    /**
     * Model-only rounds have little luck in them (about 20 attacks average out the rolls), so a 5-level edge, +10%
     * damage dealt and -10% taken, wins nearly every one (1.0 on 2026-11-08). Upsets have to come from play: misses,
     * movement and reaction (V2's practice duels measure them).
     */
    @Test
    void theHigherLevelWins() {
        PvpConfig config = new PvpConfig();

        DuelSim.Summary summary = DuelSim.run(List.of(median("high", 75, 0, config)),
                List.of(median("low", 70, 0, config)), 4000, config, new Random(5));

        assertTrue(summary.aWinRate() > 0.9d, "high wins " + summary.aWinRate());
    }

    @Test
    void aOneLevelEdgeIsNotALock() {
        PvpConfig config = new PvpConfig();

        DuelSim.Summary summary = DuelSim.run(List.of(median("high", 71, 0, config)),
                List.of(median("low", 70, 0, config)), 4000, config, new Random(9));

        System.out.println("PvP sim, a one-level edge: " + summary);
        assertTrue(summary.aWinRate() > 0.5d, "high wins " + summary.aWinRate());
    }

    @Test
    void nobodyHurtingAnybodyIsADrawAtTheBell() {
        PvpConfig config = new PvpConfig();
        DuelSim.Fighter harmless = new DuelSim.Fighter("harmless", 70, 112, false, H_REF, 3000, RAW_DPS, 0d, 200, 0, 0,
                0, 1d, 0, 1d);

        DuelSim.Outcome outcome = DuelSim.round(harmless, harmless, config, new Random(1));

        assertEquals(-1, outcome.winner());
        assertEquals(config.PVP_ROUND_SECONDS, outcome.seconds(), 1e-9);
    }
}
