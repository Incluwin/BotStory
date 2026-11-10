package server.pvp;

import config.PvpConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Learned rates (PD16, the user's call 2026-11-09; docs/PVP_PLAN.md V2). A duel's normalisation divides by the rate a
 * median attacker of the job deals; one practice run's sample set those rates, and the next run's citizens dealt up to
 * 30% more or less, which a duel with little luck turned into 90/10 class splits. So after every duel each fighter
 * with {@link DuelLog#MIN_ENGAGED_SECONDS} of fighting and {@link DuelLog#MIN_PACKETS} attacks reports its efficiency
 * (raw damage per second over the reference rate for its job, band and gear), and a running mean of its log per class
 * and per job path and band moves toward it. {@link PvpDamageModel#rate} multiplies the reference rate by the mean.
 *
 * <p>Raw damage is rolled before any PvP scaling, so what the learner measures never depends on what it has learned:
 * there is no loop. The reference table stays the prior: a class starts at it (worth {@link #CLASS_PRIOR_WEIGHT}
 * fighters), a band starts at its class (worth {@link #JOB_PRIOR_WEIGHT}), and a learned rate stays within
 * {@link #MIN_MULTIPLIER}-{@link #MAX_MULTIPLIER} of the table's. One fighter's efficiency is clipped to
 * {@link #MIN_EFFICIENCY}-{@link #MAX_EFFICIENCY} first, so a bot that never got its skills cannot drag a rate far.
 */
public final class PvpRateLearner {
    private static final Logger log = LoggerFactory.getLogger(PvpRateLearner.class);
    public static final Path FILE = Path.of("logs", "pvp", "learned-rates.txt");
    static final double MIN_EFFICIENCY = 1d / 3d;
    static final double MAX_EFFICIENCY = 3d;
    static final double MIN_MULTIPLIER = 0.4d;
    static final double MAX_MULTIPLIER = 2.5d;
    /** A class's learned rate counts from this many fighters; below it the table's stands. */
    static final int MIN_CLASS_FIGHTERS = 3;
    static final double CLASS_PRIOR_WEIGHT = 5d;
    static final double JOB_PRIOR_WEIGHT = 3d;
    /** The most one fighter moves a mean, in log efficiency (about +/-20%): a Huber step, near the median. */
    static final double RESIDUAL_CAP = 0.2d;

    private static volatile PvpRateLearner instance;

    /** A running mean of log efficiency over this many fighters. */
    record Mean(double logMean, int fighters) {
        double multiplier() {
            return Math.exp(Math.max(Math.log(MIN_MULTIPLIER), Math.min(Math.log(MAX_MULTIPLIER), logMean)));
        }
    }

    private final Path file;
    private final Map<String, Mean> means = new ConcurrentHashMap<>();

    PvpRateLearner(Path file) {
        this.file = file;
    }

    public static PvpRateLearner get() {
        PvpRateLearner learner = instance;
        if (learner == null) {
            synchronized (PvpRateLearner.class) {
                if (instance == null) {
                    PvpRateLearner loaded = new PvpRateLearner(FILE);
                    loaded.load();
                    instance = loaded;
                }
                learner = instance;
            }
        }
        return learner;
    }

    static String classKey(int jobId) {
        return "c" + (jobId / 100 % 10);
    }

    /** What the reference rate is multiplied by: the band's mean once it has enough fighters, else the class's, else 1. */
    public double multiplier(int jobId, int level, PvpConfig config) {
        Mean job = means.get(PvpReference.key(jobId, level));
        if (job != null && job.fighters() >= config.PVP_LEARN_JOB_MIN_FIGHTERS) {
            return job.multiplier();
        }
        Mean jobClass = means.get(classKey(jobId));
        return jobClass != null && jobClass.fighters() >= MIN_CLASS_FIGHTERS ? jobClass.multiplier() : 1d;
    }

    /** One fighter's duel; false when it does not count (no class, no gear, no damage). */
    public synchronized boolean observe(int jobId, int level, long gearBase, double rawPerSecond,
                                        PvpReference reference, PvpConfig config) {
        int jobClass = jobId / 100 % 10;
        if (jobClass < 1 || jobClass > 5 || gearBase <= 0 || !(rawPerSecond > 0) || !Double.isFinite(rawPerSecond)) {
            return false;
        }
        PvpDamageModel.Attacker attacker = new PvpDamageModel.Attacker(level, jobId, gearBase, jobClass == 2);
        long median = reference.gearMedian(jobId, level);
        double medianBase = median > 0 ? median : gearBase;
        double expected = PvpDamageModel.priorRate(attacker, reference) * gearBase / medianBase;
        if (!(expected > 0)) {
            return false;
        }
        double observed = Math.log(Math.max(MIN_EFFICIENCY, Math.min(MAX_EFFICIENCY, rawPerSecond / expected)));
        String classKey = classKey(jobId);
        Mean classMean = step(classKey, means.getOrDefault(classKey, new Mean(0d, 0)), observed,
                config.PVP_LEARN_CLASS_STEP, CLASS_PRIOR_WEIGHT);
        String jobKey = PvpReference.key(jobId, level);
        step(jobKey, means.getOrDefault(jobKey, new Mean(classMean.logMean(), 0)), observed,
                config.PVP_LEARN_JOB_STEP, JOB_PRIOR_WEIGHT);
        return true;
    }

    /**
     * A running mean: the prior counts as {@code priorWeight} fighters, a step is never smaller than {@code step}, and
     * one fighter's pull is capped at {@link #RESIDUAL_CAP} (log), so the mean sits near the typical fighter rather
     * than below it. Practice runs five and six: Thieves' median efficiency was 0.85 but their plain log mean 0.75,
     * pulled down by Assassins without stars; that 12% would have gone to every typical Thief as extra damage.
     */
    private Mean step(String key, Mean mean, double observed, double step, double priorWeight) {
        double rate = Math.max(step, 1d / (mean.fighters() + 1 + priorWeight));
        double residual = Math.max(-RESIDUAL_CAP, Math.min(RESIDUAL_CAP, observed - mean.logMean()));
        Mean next = new Mean(mean.logMean() + rate * residual, mean.fighters() + 1);
        means.put(key, next);
        return next;
    }

    /** A finished duel's fighters (players and bots alike); a fighter who left, or barely fought, does not count. */
    public synchronized int observeAll(List<DuelLog.Row> rows, PvpReference reference, PvpConfig config) {
        int counted = 0;
        for (DuelLog.Row row : rows) {
            if (!row.left() && DuelLog.measurable(row.engaged(), row.stats().packets.get(),
                    row.stats().noSkillThinks.get(), row.stats().thinks.get())
                    && observe(row.jobId(), row.level(), row.gearBase(), row.stats().rawRolled.get() / row.engaged(),
                    reference, config)) {
                counted++;
            }
        }
        if (counted > 0) {
            save();
        }
        return counted;
    }

    /** Feeds a duel CSV's rows (logs/pvp, {@link DuelLog#HEADER}) through {@link #observe}; how many counted. */
    public synchronized int replay(List<String> lines, PvpReference reference, PvpConfig config) {
        int counted = 0;
        for (String line : lines) {
            String[] f = line.split(",", -1);
            if (f.length < 30 || f[0].equals("endedAt")) {
                continue;
            }
            try {
                int packets = Integer.parseInt(f[21]);
                double engaged = Double.parseDouble(f[28]);
                if (f[14].equals("1")
                        || !DuelLog.measurable(engaged, packets, Integer.parseInt(f[20]), Integer.parseInt(f[15]))) {
                    continue;
                }
                if (observe(Integer.parseInt(f[8]), Integer.parseInt(f[9]), Long.parseLong(f[12]),
                        Long.parseLong(f[25]) / engaged, reference, config)) {
                    counted++;
                }
            } catch (NumberFormatException skipped) {
                // a row from another version of the file
            }
        }
        if (counted > 0) {
            save();
        }
        return counted;
    }

    public synchronized void reset() {
        means.clear();
        try {
            Files.deleteIfExists(file);
        } catch (IOException failure) {
            log.warn("PvP: could not delete {}", file, failure);
        }
    }

    /** Every mean, by key ("c1".."c5" for classes, "<jobId/10>-<band>" for bands). */
    public Map<String, Mean> means() {
        return new TreeMap<>(means);
    }

    /** One line per class and band: its multiplier and how many fighters it has heard. */
    public List<String> summary() {
        List<String> lines = new ArrayList<>();
        means().forEach((key, mean) -> lines.add(String.format(Locale.ROOT, "%s x%.2f (n=%d)", key, mean.multiplier(),
                mean.fighters())));
        return lines;
    }

    void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] f = line.trim().split("\\s+");
                if (f.length != 3 || line.startsWith("#")) {
                    continue;
                }
                try {
                    means.put(f[0], new Mean(Double.parseDouble(f[1]), Integer.parseInt(f[2])));
                } catch (NumberFormatException skipped) {
                    // a hand-edited line
                }
            }
        } catch (IOException failure) {
            log.warn("PvP: could not read {}; learned rates start over", file, failure);
        }
    }

    void save() {
        List<String> lines = new ArrayList<>();
        lines.add("# PvP learned rates (server.pvp.PvpRateLearner): key, mean log efficiency, fighters");
        means().forEach((key, mean) -> lines.add(String.format(Locale.ROOT, "%s %.6f %d", key, mean.logMean(),
                mean.fighters())));
        try {
            Path parent = file.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path temp = Files.createTempFile(parent, "learned-rates", ".tmp");
            Files.write(temp, lines, StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            log.warn("PvP: could not write {}", file, failure);
        }
    }
}
