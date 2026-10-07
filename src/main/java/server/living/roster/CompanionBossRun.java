package server.living.roster;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One owner's part of a boss run with companions (P7, flow 4; Mu Young's Easy Balrog), in memory only: nothing of it
 * survives a restart (boot reconciliation parks whoever it held). The Agent layer's boss run (AgentBossRunSession)
 * drives the companions; this keeps what the living world needs for them: its key ("boss:easy_balrog:<session>:
 * <owner>"), which its companions are committed under from their seat to the run's end, each companion's persona and
 * potions as it signed up, for its record and the potion line, and which lines were said once per owner already.
 */
final class CompanionBossRun {
    final String key;
    /** Its boss ("easy_balrog"): its records' activity, its lines. */
    final String bossKey;
    final long sessionId;
    final int ownerId;
    /** Companion id -> its persona id, noted as it signs up, for its record (companion_performance). */
    final Map<Integer, String> personaByCompanion = new ConcurrentHashMap<>();
    /**
     * Each companion's HP and MP potions ({HP, MP}) as it signed up, for the potion line logged as the run ends: what
     * it took into the fight, after any shop trip at the entrance. The boss potion standard is retuned from these
     * counts.
     */
    final Map<Integer, int[]> potionsAtSignUp = new ConcurrentHashMap<>();
    /** The companions on the entry NPC's list now. */
    final Set<Integer> signedUp = ConcurrentHashMap.newKeySet();
    /** Once per owner: "we're all signed up", "let's regroup outside", the logout handoff line, "gg". */
    volatile boolean signedUpSaid;
    volatile boolean regroupSaid;
    volatile boolean handoffSaid;
    volatile boolean ggSaid;
    /** When the lost-run sweep first found its session gone (0: it has not); read and written on companion-ops only. */
    long goneSinceMs;

    CompanionBossRun(String bossKey, long sessionId, int ownerId) {
        this.key = key(bossKey, sessionId, ownerId);
        this.bossKey = bossKey;
        this.sessionId = sessionId;
        this.ownerId = ownerId;
    }

    /** The commitment key of {@code ownerId}'s companions in boss run {@code sessionId}. */
    static String key(String bossKey, long sessionId, int ownerId) {
        return "boss:" + bossKey + ":" + sessionId + ":" + ownerId;
    }
}
