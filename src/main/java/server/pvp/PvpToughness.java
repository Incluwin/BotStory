package server.pvp;

import config.PvpConfig;

import java.nio.file.Path;
import java.util.List;

/**
 * Learned toughness (the user's call 2026-11-11, "Learn toughness"; docs/PVP_PLAN.md V2). The learned rates (PD16)
 * even out what each class deals; this evens out what each class takes. Check run 20 (857 duels): every attacker class
 * took 8.3-8.6% of a warrior's max HP a second and 7.0-7.5% of a magician's, at the same landed hits a second, through
 * three victim-side pieces at once (the fixed class HP table, weapon hits on magicians dodged 6-9% against 1-5% on
 * warriors, and the 4x clamp on warriors' MDEF evening), and magicians won 64%.
 *
 * <p>Each hit adds to its victim what it would have taken of a reference victim of the victim's level and class (HP
 * {@code class_hp_ratio x H_ref}, no defense, never dodging): {@code raw x factor x level factor x level HP share /
 * H_ref}; a dodged attack counts too. What the hit really took of the victim's own max HP, with the toughness already
 * applied divided back out, adds to its bare share. After the duel each victim whose hits came to
 * {@link #MIN_NEUTRAL_SHARE} reports {@code log(neutral / bare)}, and the class's and job path and band's running means
 * move toward it with the learned rates' steps, priors and Huber cap ({@link PvpRateLearner#learn}). Hits on the victim
 * are multiplied by the mean: a class that dodges, defends or carries HP beyond the tables goes down at the reference
 * pace, while a victim geared above its class's typical one still lasts longer. The bare share has the learned value
 * divided out, so what is measured never depends on what was learned (only the per-attack HP cap still binds on it).
 */
public final class PvpToughness {
    public static final Path FILE = Path.of("logs", "pvp", "learned-toughness.txt");
    /** A victim counts once the hits it met would have taken this share of a reference victim's HP. */
    static final double MIN_NEUTRAL_SHARE = 0.5d;

    private static volatile PvpRateLearner instance;

    private PvpToughness() {
    }

    static PvpRateLearner learner() {
        PvpRateLearner learner = instance;
        if (learner == null) {
            synchronized (PvpToughness.class) {
                if (instance == null) {
                    PvpRateLearner loaded = new PvpRateLearner(FILE,
                            "# PvP learned toughness (server.pvp.PvpToughness): key, mean log toughness, fighters");
                    loaded.load();
                    instance = loaded;
                }
                learner = instance;
            }
        }
        return learner;
    }

    /** What hits on a victim of this job and level are multiplied by: its band's mean, else its class's, else 1. */
    public static double multiplier(int jobId, int level, PvpConfig config) {
        return learner().multiplier(jobId, level, config);
    }

    /** True while duels both even the classes and learn toughness. */
    public static boolean on(PvpConfig config) {
        return config.PVP_EVEN_CLASSES && config.PVP_LEARN_TOUGHNESS;
    }

    /**
     * What a hit would take of a reference victim: {@code raw} scaled by the attack's {@code factor} (coefficient and
     * area share), the level factor and the level HP share, over the reference HP of the victim's level.
     */
    public static double neutralShare(long raw, double factor, double levelFactor, double levelHpShare, double hRef) {
        return hRef > 0 ? raw * factor * levelFactor * levelHpShare / hRef : 0d;
    }

    /** One victim's log toughness, clipped as the rates are; NaN when it met too few hits or none landed. */
    static double observed(double neutralShare, double bareShare) {
        if (!Double.isFinite(neutralShare) || !Double.isFinite(bareShare)
                || neutralShare < MIN_NEUTRAL_SHARE || bareShare <= 0d) {
            return Double.NaN;
        }
        return Math.log(Math.max(PvpRateLearner.MIN_EFFICIENCY,
                Math.min(PvpRateLearner.MAX_EFFICIENCY, neutralShare / bareShare)));
    }

    /** A finished duel's victims (players and bots alike); one who left does not count. How many counted. */
    public static int observeAll(List<DuelLog.Row> rows, PvpConfig config) {
        return observeAll(learner(), rows, config);
    }

    static int observeAll(PvpRateLearner learner, List<DuelLog.Row> rows, PvpConfig config) {
        int counted = 0;
        for (DuelLog.Row row : rows) {
            int jobClass = row.jobId() / 100 % 10;
            double observed = observed(row.neutralShare(), row.bareShare());
            if (row.left() || jobClass < 1 || jobClass > 5 || Double.isNaN(observed)) {
                continue;
            }
            learner.learn(row.jobId(), row.level(), observed, config);
            counted++;
        }
        if (counted > 0) {
            learner.save();
        }
        return counted;
    }

    /** One line per class and band: its multiplier and how many victims it has heard. */
    public static List<String> summary() {
        return learner().summary();
    }

    public static void reset() {
        learner().reset();
    }
}
