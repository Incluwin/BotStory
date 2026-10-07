package server.agents.capabilities.partyquest.epq;

import server.agents.capabilities.partyquest.pace.AgentPqMemberPower;

import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * When each boundary of one companion run's pace was first seen, for {@link AgentEpqPaceReport} (the shape of
 * AgentHpqPaceMarks): the party inside (the bind, with each member's power then), each stage's first phase, the
 * twentieth marble at the leader's feet (or in his bag), the purple stone at his feet (or in his bag), Poison Golem
 * 9300180 first alive and the event's clear; and what happened to each member on the way (each time down, a tap-out).
 * Only marks: the report turns them into spans. Each boundary keeps its first sighting. Owned by its session; every
 * method locks this object, since the run's release may read it from another thread.
 */
final class AgentEpqPaceMarks {
    private long enteredAtMs;
    private final Map<AgentEpqSession.Phase, Long> phaseAtMs = new EnumMap<>(AgentEpqSession.Phase.class);
    private long marblesAtMs;
    private long stoneAtMs;
    private long golemAtMs;
    private long clearedAtMs;
    private List<AgentPqMemberPower> members = List.of();
    private List<Integer> entrants = List.of();
    private final Map<Integer, Integer> deaths = new TreeMap<>();
    private final Set<Integer> tappedOut = new TreeSet<>();

    /** The party stands in the event: who entered, and their power from the warmed profile (empty while cold). */
    synchronized void markEntered(List<AgentPqMemberPower> power, Collection<Integer> entered, long nowMs) {
        if (enteredAtMs != 0L) return;
        enteredAtMs = Math.max(1L, nowMs);
        members = power == null ? List.of() : List.copyOf(power);
        entrants = entered == null ? List.of() : List.copyOf(entered);
    }

    /** The run first reached this phase. */
    synchronized void markPhase(AgentEpqSession.Phase phase, long nowMs) {
        if (phase != null) phaseAtMs.putIfAbsent(phase, Math.max(1L, nowMs));
    }

    /** The twentieth monster marble lies at the leader's feet, or he first holds twenty. */
    synchronized void markMarbles(long nowMs) {
        if (marblesAtMs == 0L) marblesAtMs = Math.max(1L, nowMs);
    }

    /** The purple stone lies at the leader's feet, or he first holds it. */
    synchronized void markStone(long nowMs) {
        if (stoneAtMs == 0L) stoneAtMs = Math.max(1L, nowMs);
    }

    /** Poison Golem 9300180 first alive. */
    synchronized void markGolem(long nowMs) {
        if (golemAtMs == 0L) golemAtMs = Math.max(1L, nowMs);
    }

    /** The event's clear. */
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
        return new Snapshot(enteredAtMs, Map.copyOf(phaseAtMs), marblesAtMs, stoneAtMs, golemAtMs, clearedAtMs,
                members, entrants, Map.copyOf(deaths), Set.copyOf(tappedOut));
    }

    /** The marks at one moment; 0 (or no entry) is a boundary not seen. */
    record Snapshot(long enteredAtMs, Map<AgentEpqSession.Phase, Long> phaseAtMs, long marblesAtMs, long stoneAtMs,
                    long golemAtMs, long clearedAtMs, List<AgentPqMemberPower> members, List<Integer> entrants,
                    Map<Integer, Integer> deaths, Set<Integer> tappedOut) {
        long phase(AgentEpqSession.Phase phase) {
            return phaseAtMs.getOrDefault(phase, 0L);
        }
    }
}
