package server.living.roster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.living.LivingWorld;
import server.living.store.LivingStore;
import server.living.store.LivingStore.CompanionRunRow;
import server.living.store.LivingStore.CompanionRunStats;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The companions' KPQ records (P6, companion_performance): as a run that got in ends, one row per lineup companion,
 * and each companion's runs and clears kept in memory for the lineup, which shows a companion's clear record and ranks
 * by it once it has three runs (LineupPlan). Read once at boot and written on companion-ops; read from any thread. A
 * failed read leaves the records empty until the next boot, so the lineup ranks by level and job alone meanwhile.
 */
public final class CompanionRunRecorder {
    private static final Logger log = LoggerFactory.getLogger(CompanionRunRecorder.class);
    /** companion_performance.activity of a KPQ run, the one party quest companions go into. */
    static final String KPQ = "kpq";
    static final CompanionRunStats NONE = new CompanionRunStats(0, 0);

    private final LivingWorld world;
    /** Companion character id -> its record. */
    private final Map<Integer, CompanionRunStats> stats = new ConcurrentHashMap<>();

    CompanionRunRecorder(LivingWorld world) {
        this.world = world;
    }

    /** Reads the records (LivingWorld.start, on companion-ops); a failed read leaves them empty. */
    public void load() {
        try {
            stats.putAll(world.store().companionRunStats(KPQ));
        } catch (LivingStore.ReadFailed failure) {
            log.warn("companion KPQ records could not be read; lineups rank without them", failure);
        }
    }

    /**
     * On companion-ops, as the run ends: a row for each lineup companion of a run that got in, tapped out (before the
     * clear), else cleared or failed with the run, added to its record once it is written. A run that never got in has
     * nothing to record, and neither has a re-rolled one (its leader left stage 1 to run it again): it is neither a
     * clear nor a failure of its companions, so "cleared X of Y" counts only the runs that went the distance, and the
     * re-roll itself is kept in the run's clear-time record (pq_run_pace, outcome rerolled).
     */
    void record(CompanionRun run, long nowMs) {
        if (!run.started || run.rerolled) {
            return;
        }
        int durationS = (int) Math.max(0L, (nowMs - run.startedAtMs) / 1000L);
        for (Map.Entry<Integer, Integer> companion : run.ownerByCompanion.entrySet()) {
            int id = companion.getKey();
            String outcome = run.tappedOut.contains(id) ? "tapped_out" : run.cleared ? "cleared" : "failed";
            CompanionRunRow row = new CompanionRunRow(companion.getValue(), id, run.personaByCompanion.get(id), KPQ,
                    outcome, durationS, run.deaths.getOrDefault(id, 0), run.partySize);
            if (world.store().saveCompanionRun(row)) {
                stats.merge(id, new CompanionRunStats(1, outcome.equals("cleared") ? 1 : 0), (known, added) ->
                        new CompanionRunStats(known.runs() + added.runs(), known.clears() + added.clears()));
            }
        }
    }

    /** The companion's record ({@link #NONE} before its first run). */
    CompanionRunStats stats(int companionId) {
        return stats.getOrDefault(companionId, NONE);
    }
}
