package server.pvp.probe;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sustained single-target damage per second, per character, from real attacks (V0.5, the {@code D_job} input of
 * docs/PVP_PLAN.md section 2). An attack's damage is its best target's lines summed, so an AoE on a crowd counts once.
 * It is credited over the time until the same character's next attack; a gap longer than {@link #ENGAGED_GAP_MS}
 * (walking to the next mob, resting) is left out with the attack before it.
 */
public final class DamageMeter {
    static final long ENGAGED_GAP_MS = 2500;
    /** A character joins the report after this much engaged time. */
    static final long MIN_ENGAGED_MS = 60_000;

    private static final class Tally {
        int jobId;
        int level;
        boolean human;
        long lastAtMs = -1;
        long lastDamage;
        long damage;
        long engagedMs;
        int attacks;
    }

    /** One row of the report: a job path ({@code jobId / 10}), a level band ({@code level / 10}), who was measured. */
    public record Row(int jobPath, int band, boolean human, int characters, long medianDps, long p25Dps, long p75Dps) { }

    private final Map<Integer, Tally> tallies = new ConcurrentHashMap<>();
    private volatile boolean running;

    public boolean running() {
        return running;
    }

    public void start() {
        tallies.clear();
        running = true;
    }

    public void stop() {
        running = false;
    }

    public int measured() {
        return tallies.size();
    }

    public void record(int characterId, int jobId, int level, boolean human, long damage, long nowMs) {
        if (!running) {
            return;
        }
        Tally tally = tallies.computeIfAbsent(characterId, id -> new Tally());
        synchronized (tally) {
            if (tally.lastAtMs >= 0 && nowMs - tally.lastAtMs <= ENGAGED_GAP_MS && tally.jobId == jobId
                    && tally.level == level) {
                tally.engagedMs += nowMs - tally.lastAtMs;
                tally.damage += tally.lastDamage;
            }
            tally.jobId = jobId;
            tally.level = level;
            tally.human = human;
            tally.lastAtMs = nowMs;
            tally.lastDamage = damage;
            tally.attacks++;
        }
    }

    public List<Row> report() {
        Map<String, List<Long>> groups = new TreeMap<>();
        Map<String, int[]> keys = new TreeMap<>();
        for (Tally tally : tallies.values()) {
            synchronized (tally) {
                if (tally.engagedMs < MIN_ENGAGED_MS) {
                    continue;
                }
                int path = tally.jobId / 10;
                int band = tally.level / 10;
                String key = String.format("%04d-%02d-%s", path, band, tally.human ? "H" : "B");
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(tally.damage * 1000 / tally.engagedMs);
                keys.put(key, new int[] {path, band, tally.human ? 1 : 0});
            }
        }
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, List<Long>> group : groups.entrySet()) {
            List<Long> dps = group.getValue();
            dps.sort(null);
            int[] key = keys.get(group.getKey());
            rows.add(new Row(key[0], key[1], key[2] == 1, dps.size(), GapStats.percentile(dps, 0.5),
                    GapStats.percentile(dps, 0.25), GapStats.percentile(dps, 0.75)));
        }
        return rows;
    }
}
