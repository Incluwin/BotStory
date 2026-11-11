package server.pvp;

import client.Character;
import net.server.Server;
import net.server.world.World;
import server.combat.CombatFormulaProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Class-evened hit chance (PD17, the user's call 2026-11-09). Accuracy and avoid come mostly from DEX and LUK, the
 * stats bowmen, thieves and pirates build, so a warrior swinging at them sat on the miss cap (practice round eight:
 * 25-29% of a warrior's attacks dodged, nobody dodged a warrior). Under {@code PVP_EVEN_CLASSES} each fighter's
 * accuracy is multiplied by its class's factor and its avoid by its class's factor, so a median fighter of every class
 * at a level band has that band's class-balanced accuracy and avoid. Gear still counts inside the class: an Assassin
 * with more avoid than other Assassins still dodges more.
 *
 * <p>Defense is evened the same way, with one more step (the user's call 2026-11-11): a median victim of every class
 * meets the band's common defense, the mean of its class-balanced WDEF and MDEF, against weapon hits and spells
 * alike. Check run 14 (2026-11-10): most gear carries far more WDEF than MDEF, so spells kept several percent more
 * of every hit than weapons did, and magicians won 68% cross-class.
 *
 * <p>The medians come from the characters online (players and citizens, GMs left out), refreshed every
 * {@link #REFRESH_MS}: accuracy is magic accuracy for magicians, physical for the others, as each attacks.
 */
public final class PvpHitBalance {
    static final long REFRESH_MS = 10 * 60_000L;
    /** A class and band needs this many characters for its own median; fewer, its ratio from the nearest band. */
    static final int MIN_SAMPLES = 3;
    /** Wide on purpose: a warrior's avoid can sit at a quarter of a thief's, and a narrow cap would keep that gap. */
    static final double MIN_FACTOR = 0.25d;
    static final double MAX_FACTOR = 4.0d;

    /** One character: level, class (1 warrior .. 5 pirate), the accuracy it attacks with, its avoid, WDEF and MDEF. */
    public record Sample(int level, int jobClass, int accuracy, int avoid, int wdef, int mdef) {
        /** No defense known: the sample counts toward accuracy and avoid only. */
        public Sample(int level, int jobClass, int accuracy, int avoid) {
            this(level, jobClass, accuracy, avoid, 0, 0);
        }
    }

    private static volatile PvpHitBalance current;
    private static volatile long builtAtMs;

    /** class -> band -> median over the band's class-balanced median (defense: over the band's common defense). */
    private final Map<Integer, TreeMap<Integer, Double>> accuracyRatio;
    private final Map<Integer, TreeMap<Integer, Double>> avoidRatio;
    private final Map<Integer, TreeMap<Integer, Double>> wdefRatio;
    private final Map<Integer, TreeMap<Integer, Double>> mdefRatio;
    private final int samples;

    private PvpHitBalance(Map<Integer, TreeMap<Integer, Double>> accuracyRatio,
                          Map<Integer, TreeMap<Integer, Double>> avoidRatio,
                          Map<Integer, TreeMap<Integer, Double>> wdefRatio,
                          Map<Integer, TreeMap<Integer, Double>> mdefRatio, int samples) {
        this.accuracyRatio = accuracyRatio;
        this.avoidRatio = avoidRatio;
        this.wdefRatio = wdefRatio;
        this.mdefRatio = mdefRatio;
        this.samples = samples;
    }

    public static PvpHitBalance build(List<Sample> samples) {
        Map<Integer, Map<Integer, List<Integer>>> accuracy = new TreeMap<>();
        Map<Integer, Map<Integer, List<Integer>>> avoid = new TreeMap<>();
        Map<Integer, Map<Integer, List<Integer>>> wdef = new TreeMap<>();
        Map<Integer, Map<Integer, List<Integer>>> mdef = new TreeMap<>();
        int used = 0;
        for (Sample sample : samples) {
            if (sample.jobClass() < 1 || sample.jobClass() > 5) {
                continue;
            }
            int band = sample.level() / 10;
            add(accuracy, band, sample.jobClass(), sample.accuracy());
            add(avoid, band, sample.jobClass(), sample.avoid());
            if (sample.wdef() > 0 || sample.mdef() > 0) {
                add(wdef, band, sample.jobClass(), sample.wdef());
                add(mdef, band, sample.jobClass(), sample.mdef());
            }
            used++;
        }
        Map<Integer, TreeMap<Integer, Double>> wdefRatio = new HashMap<>();
        Map<Integer, TreeMap<Integer, Double>> mdefRatio = new HashMap<>();
        for (int band : wdef.keySet()) {
            Map<Integer, Double> w = classMedians(wdef.get(band));
            Map<Integer, Double> m = classMedians(mdef.get(band));
            if (w == null || m == null) {
                continue;
            }
            // one value for both kinds, so a spell and a weapon hit meet the same defense on a median victim
            double common = (mean(w) + mean(m)) / 2d;
            if (common > 0) {
                put(wdefRatio, band, w, common);
                put(mdefRatio, band, m, common);
            }
        }
        return new PvpHitBalance(ratios(accuracy), ratios(avoid), wdefRatio, mdefRatio, used);
    }

    private static void add(Map<Integer, Map<Integer, List<Integer>>> into, int band, int jobClass, int value) {
        into.computeIfAbsent(band, b -> new TreeMap<>()).computeIfAbsent(jobClass, c -> new ArrayList<>()).add(value);
    }

    /** Per band, each class's median over the mean of the class medians (none for a band of fewer than two classes). */
    private static Map<Integer, TreeMap<Integer, Double>> ratios(Map<Integer, Map<Integer, List<Integer>>> byBand) {
        Map<Integer, TreeMap<Integer, Double>> ratios = new HashMap<>();
        for (Map.Entry<Integer, Map<Integer, List<Integer>>> band : byBand.entrySet()) {
            Map<Integer, Double> medians = classMedians(band.getValue());
            if (medians != null && mean(medians) > 0) {
                put(ratios, band.getKey(), medians, mean(medians));
            }
        }
        return ratios;
    }

    /** Each class's median (classes with at least {@link #MIN_SAMPLES}); null with fewer than two such classes. */
    private static Map<Integer, Double> classMedians(Map<Integer, List<Integer>> byClass) {
        if (byClass == null) {
            return null;
        }
        Map<Integer, Double> medians = new TreeMap<>();
        for (Map.Entry<Integer, List<Integer>> jobClass : byClass.entrySet()) {
            List<Integer> values = jobClass.getValue();
            if (values.size() >= MIN_SAMPLES) {
                values.sort(null);
                medians.put(jobClass.getKey(), median(values));
            }
        }
        return medians.size() < 2 ? null : medians;
    }

    private static double mean(Map<Integer, Double> medians) {
        return medians.values().stream().mapToDouble(Double::doubleValue).average().orElse(0d);
    }

    private static void put(Map<Integer, TreeMap<Integer, Double>> ratios, int band, Map<Integer, Double> medians,
                            double reference) {
        medians.forEach((jobClass, median) -> ratios.computeIfAbsent(jobClass, c -> new TreeMap<>())
                .put(band, Math.max(1d, median) / reference));
    }

    private static double median(List<Integer> sorted) {
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2d;
    }

    /** Multiplies the accuracy a fighter of {@code jobId} at {@code level} attacks with. */
    public double accuracyFactor(int jobId, int level) {
        return factor(accuracyRatio, jobId, level);
    }

    /** Multiplies the avoid of a fighter of {@code jobId} at {@code level}. */
    public double avoidFactor(int jobId, int level) {
        return factor(avoidRatio, jobId, level);
    }

    /** Multiplies the WDEF a victim of {@code jobId} at {@code level} meets a weapon hit with. */
    public double wdefFactor(int jobId, int level) {
        return factor(wdefRatio, jobId, level);
    }

    /** Multiplies the MDEF a victim of {@code jobId} at {@code level} meets a spell with. */
    public double mdefFactor(int jobId, int level) {
        return factor(mdefRatio, jobId, level);
    }

    /** 1 over the class's ratio at the band, or at the nearest band it has one; 1 for a beginner or no data. */
    private static double factor(Map<Integer, TreeMap<Integer, Double>> ratios, int jobId, int level) {
        TreeMap<Integer, Double> byBand = ratios.get(jobId / 100 % 10);
        if (byBand == null || byBand.isEmpty()) {
            return 1d;
        }
        int band = level / 10;
        Double ratio = byBand.get(band);
        if (ratio == null) {
            Map.Entry<Integer, Double> below = byBand.floorEntry(band);
            Map.Entry<Integer, Double> above = byBand.ceilingEntry(band);
            Map.Entry<Integer, Double> nearest = below == null ? above : above == null ? below
                    : band - below.getKey() <= above.getKey() - band ? below : above;
            ratio = nearest.getValue();
        }
        return Math.max(MIN_FACTOR, Math.min(MAX_FACTOR, 1d / ratio));
    }

    public int samples() {
        return samples;
    }

    /** Class -> band -> ratio, for the GM report. */
    public String report() {
        return "accuracy " + accuracyRatio + ", avoid " + avoidRatio + ", wdef " + wdefRatio + ", mdef " + mdefRatio
                + " (class -> band -> median / band median, n=" + samples + ")";
    }

    /** The balance from the characters online, rebuilt when older than {@link #REFRESH_MS}. */
    public static PvpHitBalance current() {
        PvpHitBalance balance = current;
        if (balance == null || System.currentTimeMillis() - builtAtMs > REFRESH_MS) {
            balance = refresh();
        }
        return balance;
    }

    public static synchronized PvpHitBalance refresh() {
        List<Sample> samples = new ArrayList<>();
        CombatFormulaProvider formulas = CombatFormulaProvider.getInstance();
        for (World world : Server.getInstance().getWorlds()) {
            for (Character chr : world.getPlayerStorage().getAllCharacters()) {
                if (!chr.isGM()) {
                    samples.add(sample(chr, formulas));
                }
            }
        }
        PvpHitBalance balance = build(samples);
        current = balance;
        builtAtMs = System.currentTimeMillis();
        return balance;
    }

    static Sample sample(Character chr, CombatFormulaProvider formulas) {
        int jobClass = chr.getJob().getId() / 100 % 10;
        int accuracy = jobClass == 2 ? formulas.getTotalMagicAccuracy(chr) : formulas.getTotalAccuracy(chr);
        return new Sample(chr.getLevel(), jobClass, accuracy, formulas.getTotalAvoidability(chr), chr.getTotalWdef(),
                chr.getTotalMdef());
    }
}
