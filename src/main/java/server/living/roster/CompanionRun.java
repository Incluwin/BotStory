package server.living.roster;

import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One party-quest run a companion lineup went into from its entry NPC (P6, flow 3; Lakelis's KPQ, Tory's HPQ), in
 * memory only: nothing of it survives a restart (boot reconciliation parks whoever it held). Its key is its quest's and
 * its session's ("pq:kpq:..."), since the event instance's name (and its lobby) repeats across runs; its lineup
 * companions and its extras are committed under that key until it ends.
 */
final class CompanionRun {
    final String key;
    /** Its quest ("kpq", "hpq"): its entrance, its records' activity, its session's registry. */
    final String pqKey;
    final String sessionId;
    final int leaderId;
    /** The lineup, humans too: who went in. */
    final Set<Integer> memberIds;
    /** Lineup companion id -> its owner. */
    final Map<Integer, Integer> ownerByCompanion = new HashMap<>();
    /** Lineup companion id -> its persona id, for its record (CompanionRunRecorder). */
    final Map<Integer, String> personaByCompanion = new HashMap<>();
    /** Extra id -> its owner: the companions that wait outside the run. */
    final Map<Integer, Integer> ownerByExtra = new HashMap<>();
    /** The owners of both: their holds rejoin once the run is over. */
    final Set<Integer> owners = new HashSet<>();
    final int partySize;
    final long startedAtMs;
    /**
     * The entry NPC's clear-time estimate of the lineup at the Yes (null: none was made, the pace data cold or the kill
     * switch off), kept for the run's clear-time record beside how long it took (PqPaceLedger).
     */
    final AgentPqPaceEstimate estimateAtYes;
    /** The event instance started; a run that never got in has nothing to record. */
    volatile boolean started;
    /** The party cleared stage 5 (EventInstanceManager.clearPQ): its companions' records count it cleared. */
    volatile boolean cleared;
    /**
     * The leader chose to leave in stage 1 and run it again (the KPQ re-roll, AgentPartyQuestHandback.REROLLED): no
     * clear and no failure, so its companions' records skip it, and its clear-time record learns its opening only.
     */
    volatile boolean rerolled;
    /** Companions asked to tap out, marked before their hold runs: recovery asks the handback meanwhile. */
    final Set<Integer> tappingOut = ConcurrentHashMap.newKeySet();
    /** Companions whose tap-out hold ran before the stage-5 clear: their records count it tapped out. */
    final Set<Integer> tappedOut = ConcurrentHashMap.newKeySet();
    /** Deaths inside the run, per member. */
    final Map<Integer, Integer> deaths = new ConcurrentHashMap<>();
    /** Owners who logged off mid-run and whose companion said once that it sees the run through (decision 8b). */
    final Set<Integer> handoffLineSaid = ConcurrentHashMap.newKeySet();
    /**
     * Each lineup companion's HP and MP potions ({HP, MP}) as the run was let in, for the potion line logged as it
     * ends (CompanionRunRecorder): the per-quest potion standard is retuned from these counts.
     */
    final Map<Integer, int[]> potionsAtYes = new ConcurrentHashMap<>();
    /** When the lost-run sweep first found its session gone (0: it has not); read and written on companion-ops only. */
    long goneSinceMs;

    CompanionRun(String sessionId, LineupPlan plan, long nowMs) {
        this.key = "pq:" + plan.pqKey() + ":" + sessionId;
        this.pqKey = plan.pqKey();
        this.sessionId = sessionId;
        this.leaderId = plan.leaderId();
        this.memberIds = Set.copyOf(plan.memberIds());
        for (LineupPlan.Seat seat : plan.lineup()) {
            if (!seat.human()) {
                ownerByCompanion.put(seat.characterId(), seat.ownerId());
                personaByCompanion.put(seat.characterId(), seat.personaId());
            }
        }
        for (LineupPlan.Seat seat : plan.extras()) {
            ownerByExtra.put(seat.characterId(), seat.ownerId());
        }
        owners.addAll(ownerByCompanion.values());
        owners.addAll(ownerByExtra.values());
        this.partySize = plan.lineup().size();
        this.startedAtMs = nowMs;
        this.estimateAtYes = plan.estimate();
    }

    /** Every character the run holds: its lineup and its extras. */
    Set<Integer> characterIds() {
        Set<Integer> ids = new HashSet<>(memberIds);
        ids.addAll(ownerByExtra.keySet());
        return ids;
    }
}
