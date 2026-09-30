package server.living.roster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.living.LivingWorld;
import server.living.store.LivingStore;
import server.living.store.LivingStore.YieldRow;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bench yields learned from live slices (docs/BOT_MANAGEMENT_PLAN.md P5, "BenchYieldModel / BenchDutyChooser"): every
 * live field slice is recorded per (job branch, level band, camp) in companion_yield, and a rate is predicted as the
 * measured one shrunk toward the ledger's prior (the BENCH_FARM_EFFICIENCY rates): (slices x measured + k x prior) /
 * (slices + k), k = BENCH_YIELD_PRIOR_SLICES, so the more a camp has been measured the closer the prediction is to
 * it. A slice counts for the share of BENCH_LIVE_SLICE_MS it ran, so each stored rate is the measured total over the
 * measured hours. The duty and camp bandit reads its arm statistics ({@link #measured}) from the same rows.
 * Recorded on companion-ops; read from any thread. Until companion_yield has been read, a slice is not recorded (its
 * row would be written over by that one slice), and a failed read is tried again on the next ask.
 */
public final class BenchYieldModel {
    private static final Logger log = LoggerFactory.getLogger(BenchYieldModel.class);
    /** One arm: a job branch (BEGINNER, WARRIOR, MAGICIAN, BOWMAN, THIEF, PIRATE or OTHER), a level band (level / 10), a camp. */
    public record Key(String jobBranch, int levelBand, int campMapId) {
        public static Key of(int jobId, int level, int campMapId) {
            return new Key(branch(jobId), band(level), campMapId);
        }
    }

    /** What the slices recorded at one key measured: full slices counted, then each rate per hour. */
    public record Rates(double samples, double expPerHour, double mesoPerHour, double itemsPerHour, double deathsPerHour) { }

    /** One live slice as it ended: how long it hunted, and the EXP, meso, items (a count) and deaths it took there. */
    public record Sample(double hours, long exp, long meso, long items, int deaths) { }

    private static final String[] BRANCHES = {"BEGINNER", "WARRIOR", "MAGICIAN", "BOWMAN", "THIEF", "PIRATE"};

    private final LivingWorld world;
    private final Map<Key, Rates> rows = new ConcurrentHashMap<>();
    private volatile boolean loaded;

    public BenchYieldModel(LivingWorld world) {
        this.world = world;
    }

    /** The class line of a job id: 0-99 BEGINNER, 100s WARRIOR ... 500s PIRATE (Cygnus and Aran by the same digit). */
    public static String branch(int jobId) {
        int line = jobId < 0 ? -1 : jobId / 100 % 10;
        return line >= 0 && line < BRANCHES.length ? BRANCHES[line] : "OTHER";
    }

    public static int band(int level) {
        return Math.max(0, level) / 10;
    }

    /** The prior-weighted estimate: {@code samples} measured slices against a prior worth {@code priorSlices}. */
    static double shrink(double samples, double measured, double prior, double priorSlices) {
        double weight = Math.max(0.0d, samples);
        double priorWeight = Math.max(0.0d, priorSlices);
        return weight + priorWeight <= 0.0d ? prior : (weight * measured + priorWeight * prior) / (weight + priorWeight);
    }

    /**
     * Reads companion_yield (LivingWorld.start, on companion-ops); a model asked before that reads it itself. A failed
     * read leaves it unread, so the next ask reads again (never taken for "nothing measured").
     */
    public void load() {
        List<YieldRow> read;
        try {
            read = world.store().companionYields();
        } catch (LivingStore.ReadFailed failure) {
            log.warn("bench yields could not be read; asked again later", failure);
            return;
        }
        for (YieldRow row : read) {
            rows.put(new Key(row.jobBranch(), row.levelBand(), row.campMapId()), new Rates(row.samples(), row.expPerHour(),
                    row.mesoPerHour(), row.itemsPerHour(), row.deathsPerHour()));
        }
        loaded = true;
    }

    /** What was measured at {@code key}, or null when no slice ran there yet. */
    public Rates measured(Key key) {
        if (!loaded) {
            synchronized (this) {
                if (!loaded) {
                    load();
                }
            }
        }
        return key == null ? null : rows.get(key);
    }

    /** Adds one live slice to its key's rates and keeps the row; a slice that ran no time is not a sample. */
    public void record(Key key, Sample sample) {
        if (key == null || sample == null || sample.hours() <= 0.0d) {
            return;
        }
        double sliceHours = Math.max(1.0d / 60.0d, world.config().BENCH_LIVE_SLICE_MS / 3_600_000.0d);
        double weight = sample.hours() / sliceHours;
        Rates known = measured(key);
        if (!loaded) {
            log.warn("a bench slice at {} was not recorded: companion_yield could not be read", key);
            return; // never write a row over from this one slice
        }
        Rates now = known == null
                ? new Rates(weight, sample.exp() / sample.hours(), sample.meso() / sample.hours(),
                sample.items() / sample.hours(), sample.deaths() / sample.hours())
                : new Rates(known.samples() + weight,
                mean(known.samples(), known.expPerHour(), weight, sample.exp() / sample.hours()),
                mean(known.samples(), known.mesoPerHour(), weight, sample.meso() / sample.hours()),
                mean(known.samples(), known.itemsPerHour(), weight, sample.items() / sample.hours()),
                mean(known.samples(), known.deathsPerHour(), weight, sample.deaths() / sample.hours()));
        rows.put(key, now);
        world.store().saveCompanionYield(new YieldRow(key.jobBranch(), key.levelBand(), key.campMapId(), now.samples(),
                now.expPerHour(), now.mesoPerHour(), now.itemsPerHour(), now.deathsPerHour()));
    }

    private static double mean(double weight, double value, double addedWeight, double added) {
        return (weight * value + addedWeight * added) / (weight + addedWeight);
    }

    /** EXP per hour at {@code key}: the measured rate shrunk toward {@code prior} (the prior alone where nothing ran). */
    public double expPerHour(Key key, double prior) {
        Rates rates = measured(key);
        return rates == null ? prior : shrink(rates.samples(), rates.expPerHour(), prior, world.config().BENCH_YIELD_PRIOR_SLICES);
    }

    /** Meso picked up per hour at {@code key}, shrunk toward {@code prior}. */
    public double mesoPerHour(Key key, double prior) {
        Rates rates = measured(key);
        return rates == null ? prior : shrink(rates.samples(), rates.mesoPerHour(), prior, world.config().BENCH_YIELD_PRIOR_SLICES);
    }

    /** Items picked up per hour at {@code key}, shrunk toward {@code prior}. */
    public double itemsPerHour(Key key, double prior) {
        Rates rates = measured(key);
        return rates == null ? prior : shrink(rates.samples(), rates.itemsPerHour(), prior, world.config().BENCH_YIELD_PRIOR_SLICES);
    }

    /** Deaths per hour at {@code key}, shrunk toward {@code prior}. */
    public double deathsPerHour(Key key, double prior) {
        Rates rates = measured(key);
        return rates == null ? prior : shrink(rates.samples(), rates.deathsPerHour(), prior, world.config().BENCH_YIELD_PRIOR_SLICES);
    }
}
