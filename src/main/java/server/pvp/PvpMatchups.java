package server.pvp;

import config.PvpConfig;

import java.nio.file.Path;
import java.util.List;

/**
 * Learned matchups (the user's call 2026-11-13, "Learn matchups"; docs/PVP_PLAN.md V2). The learned rates even out what
 * each class deals against everyone and the learned toughness what each class takes from everyone; neither sees what
 * one class does to one other class. Check run 24 (909 duels): magicians took 8.5% of a warrior's real HP a second of
 * the duel and warriors 7.1% of a magician's, from a caster opening from range while the warrior walks in (1.1 s
 * against 1.6 s to the first attack), weapon swings dodged 6.2% against spells 2.2%, and more landed hits a second, and
 * warriors went 54-77 against magicians while every class won 45-59% overall.
 *
 * <p>Each attacker adds up its pace: what its hits took of the victim's max HP, with the matchup correction already
 * applied, its own gear edge ({@code G^gamma}) and the level factor divided out (gear and levels are meant to help, so
 * they are not the class pair's). After a one-against-one duel of two classes in which both fighters really fought
 * ({@link DuelLog#measurable}), the pair's running mean of {@code log(pace of the lower class / pace of the higher)}
 * moves toward the duel's value (clipped as the rates are) with the class step, prior and Huber cap of
 * {@link PvpRateLearner}. The duel's seconds are the same for both sides, so a slow walk-in, dodges, landed hits a
 * second and hit size all count. Hits split the difference: the lower class's hits on the higher are multiplied by
 * {@code exp(-m / 2)} and the higher's on the lower by {@code exp(m / 2)}, so the pair's pace evens out while their
 * shared pace stays. The pace has the correction divided out, so what is measured never depends on what was learned.
 */
public final class PvpMatchups {
    public static final Path FILE = Path.of("logs", "pvp", "learned-matchups.txt");
    /** A pair's correction counts once this many duels have taught it. */
    static final int MIN_DUELS = 5;

    private static volatile PvpRateLearner instance;

    private PvpMatchups() {
    }

    static PvpRateLearner learner() {
        PvpRateLearner learner = instance;
        if (learner == null) {
            synchronized (PvpMatchups.class) {
                if (instance == null) {
                    PvpRateLearner loaded = new PvpRateLearner(FILE,
                            "# PvP learned matchups (server.pvp.PvpMatchups): pair, mean log pace ratio, duels");
                    loaded.load();
                    instance = loaded;
                }
                learner = instance;
            }
        }
        return learner;
    }

    /** True while duels both even the classes and learn matchups. */
    public static boolean on(PvpConfig config) {
        return config.PVP_EVEN_CLASSES && config.PVP_LEARN_MATCHUPS;
    }

    /** A job's class, 1-5, or 0 for beginners and the rest. */
    static int jobClass(int jobId) {
        int jobClass = jobId / 100 % 10;
        return jobClass >= 1 && jobClass <= 5 ? jobClass : 0;
    }

    /** The pair's key, lower class first ("p14": warriors and thieves); null for one class or a class outside 1-5. */
    static String key(int classA, int classB) {
        if (classA < 1 || classA > 5 || classB < 1 || classB > 5 || classA == classB) {
            return null;
        }
        return "p" + Math.min(classA, classB) + Math.max(classA, classB);
    }

    /** What hits by an attacker of {@code attackerJobId} on a victim of {@code victimJobId} are multiplied by. */
    public static double multiplier(int attackerJobId, int victimJobId) {
        return multiplier(learner(), jobClass(attackerJobId), jobClass(victimJobId));
    }

    static double multiplier(PvpRateLearner learner, int attackerClass, int victimClass) {
        String key = key(attackerClass, victimClass);
        PvpRateLearner.Mean mean = key == null ? null : learner.mean(key);
        if (mean == null || mean.fighters() < MIN_DUELS) {
            return 1d;
        }
        // the mean's own limits (0.4-2.5x) bound the gap; each side takes half of it
        double half = Math.sqrt(mean.multiplier());
        return attackerClass < victimClass ? 1d / half : half;
    }

    /** One duel's log pace ratio, lower class first, clipped as the rates are; NaN when a side's pace is unusable. */
    static double observed(double lowerPace, double higherPace) {
        if (!Double.isFinite(lowerPace) || !Double.isFinite(higherPace) || lowerPace < 0d || higherPace < 0d
                || lowerPace + higherPace <= 0d) {
            return Double.NaN;
        }
        double ratio = higherPace <= 0d ? PvpRateLearner.MAX_EFFICIENCY : lowerPace / higherPace;
        return Math.log(Math.max(PvpRateLearner.MIN_EFFICIENCY, Math.min(PvpRateLearner.MAX_EFFICIENCY, ratio)));
    }

    /** A finished duel's rows; true when it taught its pair (one fighter a side, two classes, both really fought). */
    public static boolean observeAll(List<DuelLog.Row> rows, PvpConfig config) {
        return observeAll(learner(), rows, config);
    }

    static boolean observeAll(PvpRateLearner learner, List<DuelLog.Row> rows, PvpConfig config) {
        if (rows.size() != 2) {
            return false;
        }
        DuelLog.Row a = rows.get(0);
        DuelLog.Row b = rows.get(1);
        int classA = jobClass(a.jobId());
        int classB = jobClass(b.jobId());
        String key = key(classA, classB);
        if (key == null || a.side() == b.side() || a.left() || b.left() || !fought(a) || !fought(b)) {
            return false;
        }
        DuelLog.Row lower = classA < classB ? a : b;
        DuelLog.Row higher = lower == a ? b : a;
        double observed = observed(lower.paceShare(), higher.paceShare());
        if (Double.isNaN(observed)) {
            return false;
        }
        learner.learnKey(key, observed, config.PVP_LEARN_CLASS_STEP, PvpRateLearner.CLASS_PRIOR_WEIGHT);
        learner.save();
        return true;
    }

    private static boolean fought(DuelLog.Row row) {
        return DuelLog.measurable(row.engaged(), row.stats().packets.get(), row.stats().noSkillThinks.get(),
                row.stats().thinks.get());
    }

    /** One line per class pair: the lower class's multiplier on the higher (the higher's is its inverse), and duels. */
    public static List<String> summary() {
        return learner().means().entrySet().stream()
                .map(e -> String.format(java.util.Locale.ROOT, "%s x%.2f (n=%d)", e.getKey(),
                        1d / Math.sqrt(e.getValue().multiplier()), e.getValue().fighters()))
                .toList();
    }

    public static void reset() {
        learner().reset();
    }
}
