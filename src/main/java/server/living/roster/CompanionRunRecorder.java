package server.living.roster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.living.LivingWorld;
import server.living.store.LivingStore;
import server.living.store.LivingStore.CompanionRunRow;
import server.living.store.LivingStore.CompanionRunStats;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The companions' party-quest records (P6, companion_performance): as a run that got in ends, one row per lineup
 * companion, its activity the run's quest ("kpq", "hpq"), and each companion's runs and clears in each quest kept in
 * memory for that quest's lineup, which shows a companion's clear record and ranks by it once it has three runs
 * (LineupPlan). Read once at boot and written on companion-ops; read from any thread. A failed read leaves that
 * quest's records empty until the next boot, so its lineup ranks by level and job alone meanwhile.
 */
public final class CompanionRunRecorder {
    private static final Logger log = LoggerFactory.getLogger(CompanionRunRecorder.class);
    /** companion_performance.activity of a KPQ run. */
    static final String KPQ = "kpq";
    static final CompanionRunStats NONE = new CompanionRunStats(0, 0);

    private final LivingWorld world;
    /** Quest key -> companion character id -> its record in that quest. */
    private final Map<String, Map<Integer, CompanionRunStats>> stats = new ConcurrentHashMap<>();

    CompanionRunRecorder(LivingWorld world) {
        this.world = world;
    }

    /**
     * Reads every entrance's records (LivingWorld.start, on companion-ops), switched on or not, so a quest switched on
     * at the next restart ranks by them from its first lineup; a failed read leaves that quest's empty.
     */
    public void load() {
        for (String pqKey : PqEntrance.keys()) {
            try {
                stats.computeIfAbsent(pqKey, key -> new ConcurrentHashMap<>())
                        .putAll(world.store().companionRunStats(pqKey));
            } catch (LivingStore.ReadFailed failure) {
                log.warn("companion {} records could not be read; lineups rank without them",
                        pqKey.toUpperCase(Locale.ROOT), failure);
            }
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
        if (!run.started) {
            return;
        }
        logPotions(run);
        if (run.rerolled) {
            return;
        }
        int durationS = (int) Math.max(0L, (nowMs - run.startedAtMs) / 1000L);
        for (Map.Entry<Integer, Integer> companion : run.ownerByCompanion.entrySet()) {
            int id = companion.getKey();
            String outcome = run.tappedOut.contains(id) ? "tapped_out" : run.cleared ? "cleared" : "failed";
            CompanionRunRow row = new CompanionRunRow(companion.getValue(), id, run.personaByCompanion.get(id),
                    run.pqKey, outcome, durationS, run.deaths.getOrDefault(id, 0), run.partySize);
            if (world.store().saveCompanionRun(row)) {
                stats.computeIfAbsent(run.pqKey, key -> new ConcurrentHashMap<>()).merge(id,
                        new CompanionRunStats(1, outcome.equals("cleared") ? 1 : 0), (known, added) ->
                        new CompanionRunStats(known.runs() + added.runs(), known.clears() + added.clears()));
            }
        }
    }

    /** One line for the run: each lineup companion's HP and MP potions as it was let in, and as it ends. */
    private static void logPotions(CompanionRun run) {
        List<String> companions = new ArrayList<>();
        for (Map.Entry<Integer, String> companion : run.personaByCompanion.entrySet()) {
            int id = companion.getKey();
            companions.add(potionPart(companion.getValue() + "(" + id + ")", run.potionsAtYes.get(id),
                    CompanionPartyQuestService.potionsOf(id)));
        }
        String outcome = run.rerolled ? "rerolled" : run.cleared ? "cleared" : "failed";
        log.info(potionLine(run.sessionId, run.pqKey, outcome, companions));
    }

    /** 'PQ potions: session= pq= outcome= companions=[...]', the line the per-quest potion standard is tuned from. */
    static String potionLine(String sessionId, String pqKey, String outcome, List<String> companions) {
        return "PQ potions: session=" + sessionId + " pq=" + pqKey + " outcome=" + outcome
                + " companions=" + companions;
    }

    /** '{@code who} hp=AT>END mp=AT>END', each count '?' when it was not read. */
    static String potionPart(String who, int[] atYes, int[] atEnd) {
        return who + " hp=" + count(atYes, 0) + ">" + count(atEnd, 0) + " mp=" + count(atYes, 1) + ">" + count(atEnd, 1);
    }

    private static String count(int[] potions, int index) {
        return potions == null || potions.length <= index ? "?" : String.valueOf(potions[index]);
    }

    /** The companion's KPQ record ({@link #NONE} before its first run). */
    CompanionRunStats stats(int companionId) {
        return stats(KPQ, companionId);
    }

    /** The companion's record in {@code pqKey} ({@link #NONE} before its first run of it). */
    CompanionRunStats stats(String pqKey, int companionId) {
        Map<Integer, CompanionRunStats> quest = stats.get(pqKey);
        return quest == null ? NONE : quest.getOrDefault(companionId, NONE);
    }
}
