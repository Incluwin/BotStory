package server.pvp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The reference numbers the damage model normalises against (docs/PVP_PLAN.md section 2), from
 * {@code scripts/config/pvp/reference.yaml} as {@code !pvp calibrate} writes it:
 * <ul>
 *   <li>{@code h_ref}: level band (30, 40, ...) to the class-balanced median max HP;</li>
 *   <li>{@code gear_base}: {@code "<jobId / 10>-<level / 10>"} to the median weapon (or spell) base;</li>
 *   <li>{@code d_job}: the same keys to the median sustained single-target damage per second (the damage meter);</li>
 *   <li>{@code dps_per_base}: {@code physical} and {@code magic}, damage per second per point of base, for a job path
 *       and band the meter has not measured.</li>
 * </ul>
 * Anything missing falls back: {@code H_ref} to the V0.5 fit {@code 34 * L^1.11}, the gear median to the attacker's
 * own base (gear ratio 1), the rates to 4.6 (physical) and 80 (magic).
 */
public final class PvpReference {
    public static final Path FILE = Path.of("scripts", "config", "pvp", "reference.yaml");
    static final double FALLBACK_PHYSICAL_RATE = 4.6d;
    static final double FALLBACK_MAGIC_RATE = 80d;

    private final Map<Integer, Long> hRefByBand;
    private final Map<String, Long> gearBase;
    private final Map<String, Long> dJob;
    private final double physicalRate;
    private final double magicRate;

    PvpReference(Map<Integer, Long> hRefByBand, Map<String, Long> gearBase, Map<String, Long> dJob,
                 double physicalRate, double magicRate) {
        this.hRefByBand = hRefByBand;
        this.gearBase = gearBase;
        this.dJob = dJob;
        this.physicalRate = physicalRate;
        this.magicRate = magicRate;
    }

    private static volatile PvpReference current;

    /** The table loaded at first use (or the fallbacks alone when the file is missing). */
    public static PvpReference get() {
        PvpReference loaded = current;
        if (loaded == null) {
            loaded = load(FILE);
            current = loaded;
        }
        return loaded;
    }

    /** Re-reads the file (after {@code !pvp calibrate} wrote a new one and it was copied in place). */
    public static PvpReference reload() {
        current = load(FILE);
        return current;
    }

    static PvpReference load(Path file) {
        try {
            return parse(Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (IOException missing) {
            return parse(List.of());
        }
    }

    /** A small reader for the four flat sections; the file is ours, so no general YAML is needed. */
    static PvpReference parse(List<String> lines) {
        Map<Integer, Long> hRef = new HashMap<>();
        Map<String, Long> gear = new HashMap<>();
        Map<String, Long> dps = new HashMap<>();
        double physical = FALLBACK_PHYSICAL_RATE;
        double magic = FALLBACK_MAGIC_RATE;
        String section = "";
        for (String raw : lines) {
            String line = raw.contains("#") ? raw.substring(0, raw.indexOf('#')) : raw;
            if (line.isBlank()) {
                continue;
            }
            if (!Character.isWhitespace(line.charAt(0))) {
                section = line.trim().replace(":", "");
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).trim().replace("\"", "");
            String value = line.substring(colon + 1).trim();
            try {
                switch (section) {
                    case "h_ref" -> hRef.put(Integer.parseInt(key) / 10, Long.parseLong(value));
                    case "gear_base" -> gear.put(key, Long.parseLong(value));
                    case "d_job" -> dps.put(key, Long.parseLong(value));
                    case "dps_per_base" -> {
                        if (key.equals("physical")) {
                            physical = Double.parseDouble(value);
                        } else if (key.equals("magic")) {
                            magic = Double.parseDouble(value);
                        }
                    }
                    default -> { }
                }
            } catch (NumberFormatException ignored) {
                // a hand-edited line that is not a number is skipped, the fallback stands
            }
        }
        return new PvpReference(hRef, gear, dps, physical, magic);
    }

    static String key(int jobId, int level) {
        return (jobId / 10) + "-" + (level / 10);
    }

    /** The V0.5 fit of the class-balanced median max HP: within about 5% from Lv 30 to 129. */
    static long fittedHp(int level) {
        return Math.round(34d * Math.pow(Math.max(1, level), 1.11d));
    }

    public long hRef(int level) {
        Long band = hRefByBand.get(level / 10);
        return band != null && band > 0 ? band : fittedHp(level);
    }

    /** The job path's median gear base at this level band, or -1 when unknown. */
    public long gearMedian(int jobId, int level) {
        Long base = gearBase.get(key(jobId, level));
        return base != null && base > 0 ? base : -1L;
    }

    /** The job path's measured sustained damage per second at this level band, or -1 when unmeasured. */
    public long measuredRate(int jobId, int level) {
        Long rate = dJob.get(key(jobId, level));
        return rate != null && rate > 0 ? rate : -1L;
    }

    public double ratePerBase(boolean magic) {
        return magic ? magicRate : physicalRate;
    }
}
