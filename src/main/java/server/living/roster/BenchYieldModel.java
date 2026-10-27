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
 *
 * <p>Citizens learn where to train (docs/BOT_CONCEPT_PLAN.md F2.1): a loaded citizen's TRAIN stint is booked the same
 * way in rows of its own (source 'citizen', {@link #recordCitizen}), which the citizens' spot picker reads
 * ({@link #citizenMeasured}). Citizen data also sharpens a companion's EXP and death priors: before a companion's own
 * measurement is shrunk toward the prior, the prior is shrunk toward what citizens measured there, a citizen slice
 * counting for CITIZEN_YIELD_WEIGHT of a companion's. Citizen stints book no meso or items (their looting is not a
 * companion's), so those rates keep the ledger's prior.</p>
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
    /** What loaded citizens' TRAIN stints measured (source 'citizen'), read with the companions' rows. */
    private final Map<Key, Rates> citizenRows = new ConcurrentHashMap<>();
    private volatile boolean citizensLoaded;

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
    public static double shrink(double samples, double measured, double prior, double priorSlices) {
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
        loadCitizens();
    }

    /** Reads the citizens' rows; a failed read leaves them unread, asked again on the next citizen read or record. */
    private void loadCitizens() {
        List<YieldRow> read;
        try {
            read = world.store().citizenYields();
        } catch (LivingStore.ReadFailed failure) {
            log.warn("citizen yields could not be read; asked again later", failure);
            return;
        }
        for (YieldRow row : read) {
            citizenRows.put(new Key(row.jobBranch(), row.levelBand(), row.campMapId()), new Rates(row.samples(),
                    row.expPerHour(), row.mesoPerHour(), row.itemsPerHour(), row.deathsPerHour()));
        }
        citizensLoaded = true;
    }

    /** What loaded citizens measured at {@code key}, or null when none trained there yet. */
    public Rates citizenMeasured(Key key) {
        if (!citizensLoaded) {
            synchronized (this) {
                if (!citizensLoaded) {
                    loadCitizens();
                }
            }
        }
        return key == null ? null : citizenRows.get(key);
    }

    /**
     * Adds one citizen TRAIN stint to its key's citizen rates (on companion-ops, as {@link #record}): its EXP and deaths,
     * weighted like a bench slice by the share of BENCH_LIVE_SLICE_MS it ran. A stint that ran no time is not a sample.
     */
    public void recordCitizen(Key key, Sample sample) {
        if (key == null || sample == null || sample.hours() <= 0.0d) {
            return;
        }
        Rates known = citizenMeasured(key);
        if (!citizensLoaded) {
            log.warn("a citizen stint at {} was not recorded: the citizen yields could not be read", key);
            return;
        }
        Rates now = add(known, sample);
        citizenRows.put(key, now);
        world.store().saveCitizenYield(new YieldRow(key.jobBranch(), key.levelBand(), key.campMapId(), now.samples(),
                now.expPerHour(), now.mesoPerHour(), now.itemsPerHour(), now.deathsPerHour()));
    }

    /** {@code known} (null: nothing yet) with one more sample, weighted by the share of a full live slice it ran. */
    private Rates add(Rates known, Sample sample) {
        double sliceHours = Math.max(1.0d / 60.0d, world.config().BENCH_LIVE_SLICE_MS / 3_600_000.0d);
        double weight = sample.hours() / sliceHours;
        return known == null
                ? new Rates(weight, sample.exp() / sample.hours(), sample.meso() / sample.hours(),
                sample.items() / sample.hours(), sample.deaths() / sample.hours())
                : new Rates(known.samples() + weight,
                mean(known.samples(), known.expPerHour(), weight, sample.exp() / sample.hours()),
                mean(known.samples(), known.mesoPerHour(), weight, sample.meso() / sample.hours()),
                mean(known.samples(), known.itemsPerHour(), weight, sample.items() / sample.hours()),
                mean(known.samples(), known.deathsPerHour(), weight, sample.deaths() / sample.hours()));
    }

    /**
     * {@code prior} shrunk toward what citizens measured at {@code key} ({@code rate} of their rows), a citizen slice
     * counting for CITIZEN_YIELD_WEIGHT of a companion's: the prior a companion's own measurement is shrunk toward.
     */
    private double sharpened(Key key, double prior, java.util.function.ToDoubleFunction<Rates> rate) {
        double weight = world.config().CITIZEN_YIELD_WEIGHT;
        Rates citizens = weight <= 0.0d ? null : citizenMeasured(key);
        return citizens == null ? prior
                : shrink(citizens.samples() * weight, rate.applyAsDouble(citizens), prior, world.config().BENCH_YIELD_PRIOR_SLICES);
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
        Rates known = measured(key);
        if (!loaded) {
            log.warn("a bench slice at {} was not recorded: companion_yield could not be read", key);
            return; // never write a row over from this one slice
        }
        Rates now = add(known, sample);
        rows.put(key, now);
        world.store().saveCompanionYield(new YieldRow(key.jobBranch(), key.levelBand(), key.campMapId(), now.samples(),
                now.expPerHour(), now.mesoPerHour(), now.itemsPerHour(), now.deathsPerHour()));
    }

    private static double mean(double weight, double value, double addedWeight, double added) {
        return (weight * value + addedWeight * added) / (weight + addedWeight);
    }

    /**
     * EXP per hour at {@code key}: the measured rate shrunk toward {@code prior} sharpened by the citizens' rate there
     * (that prior alone where no slice ran).
     */
    public double expPerHour(Key key, double prior) {
        Rates rates = measured(key);
        double sharp = sharpened(key, prior, Rates::expPerHour);
        return rates == null ? sharp : shrink(rates.samples(), rates.expPerHour(), sharp, world.config().BENCH_YIELD_PRIOR_SLICES);
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

    /** Deaths per hour at {@code key}, shrunk toward {@code prior} sharpened by the citizens' deaths there. */
    public double deathsPerHour(Key key, double prior) {
        Rates rates = measured(key);
        double sharp = sharpened(key, prior, Rates::deathsPerHour);
        return rates == null ? sharp : shrink(rates.samples(), rates.deathsPerHour(), sharp, world.config().BENCH_YIELD_PRIOR_SLICES);
    }
}
