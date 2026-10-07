package server.agents.capabilities.partyquest.lpq;

import server.agents.capabilities.partyquest.pace.AgentPqMemberPower;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * When each boundary of one LPQ companion run's pace was first seen that the stage phases alone do not show, for
 * {@link AgentLpqPaceReport}: the party inside on stage 1 (with each member's power then), each pass stage's passes at
 * the leader's feet (his bag and the run's stacks lying by him reach the stage's count), the first judged Blue Balloon
 * check and how many were judged (the accepted one included), Alishar seen and Alishar down (with his HP), the key at
 * the leader's feet, and the clear; and what happened to each member on the way (each time down, a tap-out). Only
 * marks: the report turns them into spans. Each boundary keeps its first sighting. Owned by its session; every method
 * locks this object, since the stage-8 checks come from the NPC script thread and the release may read it from another.
 */
final class AgentLpqPaceMarks {
    private long enteredAtMs;
    private final Map<Integer, Long> passesAtFeetAtMs = new TreeMap<>();
    private long firstCheckAtMs;
    private int checks;
    private long alisharSeenAtMs;
    private long alisharHp;
    private long alisharDownAtMs;
    private long keyAtFeetAtMs;
    private long clearedAtMs;
    private List<AgentPqMemberPower> members = List.of();
    private List<Integer> entrants = List.of();
    private final Map<Integer, Integer> deaths = new TreeMap<>();
    private final Set<Integer> tappedOut = new TreeSet<>();

    /** The party stands on stage 1: who entered, and their power from the warmed profile (empty while cold). */
    synchronized void markEntered(List<AgentPqMemberPower> power, Collection<Integer> entered, long nowMs) {
        if (enteredAtMs != 0L) return;
        enteredAtMs = Math.max(1L, nowMs);
        members = power == null ? List.of() : List.copyOf(power);
        entrants = entered == null ? List.of() : List.copyOf(entered);
    }

    /** This pass stage's count first lies at the leader's feet or in his bag. */
    synchronized void markPassesAtFeet(int stage, long nowMs) {
        passesAtFeetAtMs.putIfAbsent(stage, Math.max(1L, nowMs));
    }

    /** The Blue Balloon judged a check (a wrong formation, or the right one). */
    synchronized void markJudgedCheck(long nowMs) {
        if (firstCheckAtMs == 0L) firstCheckAtMs = Math.max(1L, nowMs);
        checks++;
    }

    /** Alishar stands on stage 9, at this max HP. */
    synchronized void markAlisharSeen(long maxHp, long nowMs) {
        if (alisharSeenAtMs != 0L) return;
        alisharSeenAtMs = Math.max(1L, nowMs);
        alisharHp = Math.max(0L, maxHp);
    }

    /** Alishar is gone after he was seen. */
    synchronized void markAlisharDown(long nowMs) {
        if (alisharSeenAtMs != 0L && alisharDownAtMs == 0L) alisharDownAtMs = Math.max(1L, nowMs);
    }

    /** Alishar's key is in the leader's bag or lies at his feet. */
    synchronized void markKeyAtFeet(long nowMs) {
        if (keyAtFeetAtMs == 0L) keyAtFeetAtMs = Math.max(1L, nowMs);
    }

    /** The Violet Balloon took the key: the run is cleared. */
    synchronized void markCleared(long nowMs) {
        if (clearedAtMs == 0L) clearedAtMs = Math.max(1L, nowMs);
    }

    /** A member went down (once per time down). */
    synchronized void markDown(int characterId) {
        deaths.merge(characterId, 1, Integer::sum);
    }

    synchronized void markTappedOut(int characterId) {
        tappedOut.add(characterId);
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(enteredAtMs, Map.copyOf(passesAtFeetAtMs), firstCheckAtMs, checks, alisharSeenAtMs,
                alisharHp, alisharDownAtMs, keyAtFeetAtMs, clearedAtMs, members, entrants, Map.copyOf(deaths),
                Set.copyOf(tappedOut));
    }

    /** The marks at one moment; 0 is a boundary not seen. */
    record Snapshot(long enteredAtMs, Map<Integer, Long> passesAtFeetAtMs, long firstCheckAtMs, int checks,
                    long alisharSeenAtMs, long alisharHp, long alisharDownAtMs, long keyAtFeetAtMs, long clearedAtMs,
                    List<AgentPqMemberPower> members, List<Integer> entrants, Map<Integer, Integer> deaths,
                    Set<Integer> tappedOut) {
        long passesAtFeetAtMs(int stage) {
            return passesAtFeetAtMs.getOrDefault(stage, 0L);
        }
    }
}
