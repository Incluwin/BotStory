package server.living.roster;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One KPQ run a companion lineup went into from Lakelis (P6, flow 3), in memory only: nothing of it survives a restart
 * (boot reconciliation parks whoever it held). Its key is its KPQ session's, since the event instance's name ("Kerning"
 * and its lobby) repeats across runs; its lineup companions and its extras are committed under that key until it ends.
 */
final class CompanionRun {
    final String key;
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
    /** The event instance started; a run that never got in has nothing to record. */
    volatile boolean started;
    /** The party cleared stage 5 (EventInstanceManager.clearPQ): nobody taps out after it. */
    volatile boolean cleared;
    /** Companions asked to tap out, marked before their hold runs: recovery asks the handback meanwhile. */
    final Set<Integer> tappingOut = ConcurrentHashMap.newKeySet();
    /** Companions whose tap-out hold ran. */
    final Set<Integer> tappedOut = ConcurrentHashMap.newKeySet();
    /** Deaths inside the run, per member. */
    final Map<Integer, Integer> deaths = new ConcurrentHashMap<>();
    /** Owners who logged off mid-run and whose companion said once that it sees the run through (decision 8b). */
    final Set<Integer> handoffLineSaid = ConcurrentHashMap.newKeySet();
    /** When the lost-run sweep first found its session gone (0: it has not); read and written on companion-ops only. */
    long goneSinceMs;

    CompanionRun(String sessionId, LineupPlan plan, long nowMs) {
        this.key = "pq:" + plan.pqKey() + ":" + sessionId;
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
    }

    /** Every character the run holds: its lineup and its extras. */
    Set<Integer> characterIds() {
        Set<Integer> ids = new HashSet<>(memberIds);
        ids.addAll(ownerByExtra.keySet());
        return ids;
    }
}
