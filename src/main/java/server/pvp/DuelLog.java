package server.pvp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code logs/pvp/duels.csv}: one row per fighter per finished duel (docs/PVP_PLAN.md V2), and the per-job rates
 * {@code !pvp rates} reads back out of it. The rate is raw damage rolled per engaged second (first attack packet to the
 * end), before the PvP scaling and the dodge roll, which is what {@code d_job} in the reference table means.
 */
public final class DuelLog {
    private static final Logger log = LoggerFactory.getLogger(DuelLog.class);
    public static final Path FILE = Path.of("logs", "pvp", "duels.csv");
    static final String HEADER = "endedAt,duel,map,seconds,reason,winner,name,charId,jobId,level,kind,side,gearBase,"
            + "ko,left,thinks,buffs,sent,deferred,refused,noSkill,packets,whiffs,dodged,hits,raw,dealt,taken,engaged,"
            + "topRefusal";
    /**
     * A row counts toward a rate only with this many engaged seconds and attack packets. Below the 12 s target duel on
     * purpose: at 15 s (the long run, 2026-11-09) 56 of 59 crossbowman rows were skipped because they won fast, so the
     * learner never saw the jobs it most needed to correct.
     */
    static final double MIN_ENGAGED_SECONDS = 5d;
    static final int MIN_PACKETS = 5;
    /** A fighter that found no usable attack on more than this share of its thinks (no ammo) measures that, not its job. */
    static final double MAX_NO_SKILL_SHARE = 0.3d;

    /** Whether a fighter's row measures its job's rate (players and bots, the learner and {@code !pvp rates} alike). */
    static boolean measurable(double engaged, int packets, int noSkillThinks, int thinks) {
        return engaged >= MIN_ENGAGED_SECONDS && packets >= MIN_PACKETS
                && noSkillThinks <= MAX_NO_SKILL_SHARE * Math.max(1, thinks);
    }

    private DuelLog() {
    }

    /** One finished fighter, as a CSV row. */
    public record Row(long endedAt, long duel, int map, long seconds, String reason, int winner, String name,
                      int charId, int jobId, int level, boolean human, int side, long gearBase, boolean ko,
                      boolean left, DuelStats stats, long dealt, long taken, double engaged) {
        String csv() {
            DuelStats s = stats;
            return String.join(",", List.of(Long.toString(endedAt), Long.toString(duel), Integer.toString(map),
                    Long.toString(seconds), reason.replace(',', ';'), Integer.toString(winner), name,
                    Integer.toString(charId), Integer.toString(jobId), Integer.toString(level), human ? "H" : "B",
                    Integer.toString(side), Long.toString(gearBase), ko ? "1" : "0", left ? "1" : "0",
                    s.thinks.toString(), s.buffsCast.toString(), s.swingsSent.toString(),
                    s.swingsDeferred.toString(), s.swingsRefused.toString(), s.noSkillThinks.toString(),
                    s.packets.toString(), s.whiffs.toString(), s.dodged.toString(), s.hits.toString(),
                    s.rawRolled.toString(), Long.toString(dealt), Long.toString(taken),
                    String.format(java.util.Locale.ROOT, "%.1f", engaged), s.topRefusal().replace(',', ';')));
        }
    }

    static void append(List<Row> rows) {
        try {
            Files.createDirectories(FILE.getParent());
            List<String> lines = new ArrayList<>();
            if (!Files.exists(FILE)) {
                lines.add(HEADER);
            }
            rows.forEach(row -> lines.add(row.csv()));
            Files.write(FILE, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException failure) {
            log.warn("PvP: could not append {}", FILE, failure);
        }
    }

    /** A measured rate: job path and band ({@link PvpReference#key}), median raw dps, how many fighters. */
    public record Rate(String key, long medianRawDps, int fighters) { }

    /** The bots' rates from CSV lines (the header is skipped), sorted by key; humans do not count. */
    public static List<Rate> rates(List<String> lines) {
        Map<String, List<Double>> byKey = new LinkedHashMap<>();
        for (String line : lines) {
            String[] f = line.split(",", -1);
            if (f.length < 30 || f[0].equals("endedAt") || !f[10].equals("B")) {
                continue;
            }
            try {
                int jobId = Integer.parseInt(f[8]);
                int level = Integer.parseInt(f[9]);
                int packets = Integer.parseInt(f[21]);
                long raw = Long.parseLong(f[25]);
                double engaged = Double.parseDouble(f[28]);
                if (!measurable(engaged, packets, Integer.parseInt(f[20]), Integer.parseInt(f[15]))) {
                    continue;
                }
                byKey.computeIfAbsent(PvpReference.key(jobId, level), k -> new ArrayList<>()).add(raw / engaged);
            } catch (NumberFormatException skipped) {
                // a row from another version of the file
            }
        }
        List<Rate> rates = new ArrayList<>();
        byKey.forEach((key, values) -> {
            values.sort(Double::compare);
            rates.add(new Rate(key, Math.round(values.get(values.size() / 2)), values.size()));
        });
        rates.sort((a, b) -> a.key().compareTo(b.key()));
        return rates;
    }

    /**
     * A class's measured rate against the model: the median over its bots of raw damage per engaged second over the
     * model's rate for that bot (its own gear base x the current rate per base). {@code suggestedRatePerBase} is the
     * current rate times that median, the value {@code class_dps_per_base} wants for the model to match.
     */
    public record ClassScale(int jobClass, double medianRatio, int fighters, double currentRatePerBase,
                             double suggestedRatePerBase) { }

    public static List<ClassScale> classScales(List<String> lines, PvpReference reference) {
        Map<Integer, List<Double>> ratios = new java.util.TreeMap<>();
        Map<Integer, Double> current = new java.util.HashMap<>();
        for (String line : lines) {
            String[] f = line.split(",", -1);
            if (f.length < 30 || f[0].equals("endedAt") || !f[10].equals("B")) {
                continue;
            }
            try {
                int jobId = Integer.parseInt(f[8]);
                long gearBase = Long.parseLong(f[12]);
                int packets = Integer.parseInt(f[21]);
                long raw = Long.parseLong(f[25]);
                double engaged = Double.parseDouble(f[28]);
                int jobClass = jobId / 100 % 10;
                if (!measurable(engaged, packets, Integer.parseInt(f[20]), Integer.parseInt(f[15])) || gearBase <= 0
                        || jobClass < 1) {
                    continue;
                }
                double perBase = reference.ratePerBase(jobId, jobClass == 2);
                current.put(jobClass, perBase);
                ratios.computeIfAbsent(jobClass, c -> new ArrayList<>()).add(raw / engaged / (gearBase * perBase));
            } catch (NumberFormatException skipped) {
                // a row from another version of the file
            }
        }
        List<ClassScale> scales = new ArrayList<>();
        ratios.forEach((jobClass, values) -> {
            values.sort(Double::compare);
            double median = values.get(values.size() / 2);
            double perBase = current.get(jobClass);
            scales.add(new ClassScale(jobClass, median, values.size(), perBase, perBase * median));
        });
        return scales;
    }

    public static List<String> lines() throws IOException {
        return Files.exists(FILE) ? Files.readAllLines(FILE, StandardCharsets.UTF_8) : List.of();
    }

    public static List<Rate> rates() throws IOException {
        return Files.exists(FILE) ? rates(Files.readAllLines(FILE, StandardCharsets.UTF_8)) : List.of();
    }
}
