package server.living.roster;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One owner's part of a boss run with companions (P7, flow 4; Mu Young's Easy Balrog), in memory only: nothing of it
 * survives a restart (boot reconciliation parks whoever it held). The Agent layer's boss run (AgentBossRunSession)
 * drives the companions; this keeps what the living world needs for them: its key ("boss:easy_balrog:<session>:
 * <owner>"), which its companions are committed under from their seat to the run's end, each companion's persona and
 * potions as it signed up, for its record and the potion line, which lines were said once per owner already, and
 * which wordings its companions said for a line each of them says (no two refused for one reason say the same words).
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
    /**
     * Once per owner: "we're all signed up", "let's regroup outside", the logout handoff line, "gg", and (P8) "we're
     * coming too" when the owner left before the clear and the marker line ("drop the eye") as the first goes in.
     */
    volatile boolean signedUpSaid;
    volatile boolean regroupSaid;
    volatile boolean handoffSaid;
    volatile boolean ggSaid;
    volatile boolean ownerLeftSaid;
    /** The first of the owner's party companions went in: the marker line was said then, or never is. */
    volatile boolean eyeAsked;
    /** When the lost-run sweep first found its session gone (0: it has not); read and written on companion-ops only. */
    long goneSinceMs;
    /** Intent -> the wordings its companions said for it in this run, until every one was said (guarded by this). */
    private final Map<String, Set<String>> wordingsSaid = new HashMap<>();
    /** "anchor:intent" of each line its allies whispered (BossRunLines.tell): each once per run to each human. */
    private final Set<String> whispered = ConcurrentHashMap.newKeySet();

    CompanionBossRun(String bossKey, long sessionId, int ownerId) {
        this.key = key(bossKey, sessionId, ownerId);
        this.bossKey = bossKey;
        this.sessionId = sessionId;
        this.ownerId = ownerId;
    }

    /**
     * One of {@code wordings} (not empty) for {@code intent} that no companion of this run said yet, the first such one
     * from {@code turn} on; once each of them was said they all count as unsaid again. Any thread.
     */
    synchronized String wording(String intent, List<String> wordings, int turn) {
        Set<String> said = wordingsSaid.computeIfAbsent(intent, unused -> new HashSet<>());
        if (said.containsAll(wordings)) {
            said.removeAll(wordings);
        }
        for (int step = 0; step < wordings.size(); step++) {
            String wording = wordings.get(Math.floorMod(turn + step, wordings.size()));
            if (said.add(wording)) {
                return wording;
            }
        }
        return wordings.get(Math.floorMod(turn, wordings.size()));
    }

    /**
     * True the first time a companion of this run whispers {@code intent} to {@code anchorId}, false after: five allies
     * down in the fight are one whisper to their owner, not five. Any thread.
     */
    boolean firstWhisper(int anchorId, String intent) {
        return whispered.add(anchorId + ":" + intent);
    }

    /** The commitment key of {@code ownerId}'s companions in boss run {@code sessionId}. */
    static String key(String bossKey, long sessionId, int ownerId) {
        return "boss:" + bossKey + ":" + sessionId + ":" + ownerId;
    }
}
