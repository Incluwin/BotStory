package server.pvp;

import client.Character;
import net.server.Server;
import net.server.world.World;
import server.pvp.probe.PvpCalibration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Live gear medians (the user's call 2026-11-13, "Refresh gear medians"; docs/PVP_PLAN.md V2). A fighter's gear edge
 * ({@code G^gamma}) is its gear base over its job path and band's median, and the coefficient is set so a median
 * attacker knocks out a median victim in the target time. The medians in {@code reference.yaml} were measured on
 * 2026-11-08; by check run 25 (2026-11-13) warriors' typical gear sat at 1.22x of them and thieves' about the same,
 * magicians' at 1.05x and bowmen's at 1.0x, so a typical warrior or thief dealt about 12% more than a typical magician
 * or bowman, and no learner corrected it (gear is meant to be personal, so all of them divide it out).
 *
 * <p>So the median a fighter is measured against is now taken from the characters online (players and citizens, GMs
 * left out), refreshed every {@link #REFRESH_MS} as {@link PvpHitBalance} is: a job path and band's own median once it
 * has {@link #MIN_SAMPLES} characters, else the reference median times its class's median ratio to the reference, else
 * the reference median. The reference medians stay the yardstick of the measured rates ({@code d_job}) and of the
 * learned rates' efficiency, so what those learned keeps its meaning.
 */
public final class PvpGearMedians {
    static final long REFRESH_MS = 10 * 60_000L;
    /** A job path and band needs this many characters online for its own median. */
    static final int MIN_SAMPLES = 3;

    private static volatile PvpGearMedians current;
    private static volatile long builtAtMs;

    /** "<jobId/10>-<band>" -> live median gear base, for keys with enough characters. */
    private final Map<String, Long> medians;
    /** class (1-5) -> median of gear base over the reference median, over its characters with a reference median. */
    private final Map<Integer, Double> classRatio;
    private final int samples;

    private PvpGearMedians(Map<String, Long> medians, Map<Integer, Double> classRatio, int samples) {
        this.medians = medians;
        this.classRatio = classRatio;
        this.samples = samples;
    }

    public static PvpGearMedians build(List<PvpCalibration.Sample> samples, PvpReference reference) {
        Map<String, List<Long>> byKey = new TreeMap<>();
        Map<Integer, List<Double>> ratios = new TreeMap<>();
        int used = 0;
        for (PvpCalibration.Sample sample : samples) {
            int jobClass = sample.jobClass();
            if (jobClass < 1 || jobClass > 5 || sample.gearBase() <= 0) {
                continue;
            }
            used++;
            byKey.computeIfAbsent(PvpReference.key(sample.jobId(), sample.level()), k -> new ArrayList<>())
                    .add(sample.gearBase());
            long referenceMedian = reference.gearMedian(sample.jobId(), sample.level());
            if (referenceMedian > 0) {
                ratios.computeIfAbsent(jobClass, c -> new ArrayList<>())
                        .add(sample.gearBase() / (double) referenceMedian);
            }
        }
        Map<String, Long> medians = new HashMap<>();
        byKey.forEach((key, bases) -> {
            if (bases.size() >= MIN_SAMPLES) {
                bases.sort(null);
                medians.put(key, bases.get((bases.size() - 1) / 2));
            }
        });
        Map<Integer, Double> classRatio = new TreeMap<>();
        ratios.forEach((jobClass, values) -> {
            if (values.size() >= MIN_SAMPLES) {
                values.sort(null);
                classRatio.put(jobClass, values.get((values.size() - 1) / 2));
            }
        });
        return new PvpGearMedians(medians, classRatio, used);
    }

    /**
     * The median gear base a fighter of this job and level is measured against: its job path and band's live median,
     * else the reference median times its class's live ratio, else the reference median (-1 when there is none).
     */
    public long median(int jobId, int level, PvpReference reference) {
        Long live = medians.get(PvpReference.key(jobId, level));
        if (live != null && live > 0) {
            return live;
        }
        long referenceMedian = reference.gearMedian(jobId, level);
        Double ratio = classRatio.get(jobId / 100 % 10);
        if (referenceMedian > 0 && ratio != null && ratio > 0) {
            return Math.max(1L, Math.round(referenceMedian * ratio));
        }
        return referenceMedian;
    }

    /** One line: each class's typical gear over the reference medians, and how many keys have their own median. */
    public String report() {
        StringBuilder line = new StringBuilder("gear over reference medians by class");
        classRatio.forEach((jobClass, ratio) -> line.append(String.format(java.util.Locale.ROOT, " c%d x%.2f", jobClass,
                ratio)));
        return line.append(" (").append(medians.size()).append(" job/band keys with their own median, n=")
                .append(samples).append(')').toString();
    }

    /** The medians from the characters online, rebuilt when older than {@link #REFRESH_MS}. */
    public static PvpGearMedians current() {
        PvpGearMedians gear = current;
        if (gear == null || System.currentTimeMillis() - builtAtMs > REFRESH_MS) {
            gear = refresh();
        }
        return gear;
    }

    public static synchronized PvpGearMedians refresh() {
        List<PvpCalibration.Sample> samples = new ArrayList<>();
        for (World world : Server.getInstance().getWorlds()) {
            for (Character chr : world.getPlayerStorage().getAllCharacters()) {
                if (!chr.isGM() && chr.getJob() != null) {
                    samples.add(PvpCalibration.of(chr));
                }
            }
        }
        PvpGearMedians gear = build(samples, PvpReference.get());
        current = gear;
        builtAtMs = System.currentTimeMillis();
        return gear;
    }
}
