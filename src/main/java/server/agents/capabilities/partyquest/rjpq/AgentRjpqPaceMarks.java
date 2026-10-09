package server.agents.capabilities.partyquest.rjpq;

import server.agents.capabilities.partyquest.pace.AgentPqMemberPower;

import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * When each boundary of one RJPQ companion run's pace was first seen, for {@link AgentRjpqPaceReport} (the shape of
 * AgentEpqPaceMarks): the party inside (the bind, with each member's power then), each stage's first phase, both Dark
 * Lab doors open, both files in the leader's bag, the files handed in, every companion out of the Lab Units, the leader
 * out of his, and the event's clear; and each member's times down. Only marks: the report turns them into spans. Each
 * boundary keeps its first sighting. Owned by its session; every method locks this object, since the run's release
 * may read it from another thread.
 */
final class AgentRjpqPaceMarks {
    enum Boundary { DOORS_OPEN, FILES_HELD, FILES_IN, COMPANIONS_OUT, LEADER_OUT, CLEARED }

    private long enteredAtMs;
    private final Map<AgentRjpqSession.Phase, Long> phaseAtMs = new EnumMap<>(AgentRjpqSession.Phase.class);
    private final Map<Boundary, Long> boundaryAtMs = new EnumMap<>(Boundary.class);
    private List<AgentPqMemberPower> members = List.of();
    private List<Integer> entrants = List.of();
    private final Map<Integer, Integer> deaths = new TreeMap<>();

    /** The party stands in the event: who entered, and their power from the warmed profile (empty while cold). */
    synchronized void markEntered(List<AgentPqMemberPower> power, Collection<Integer> entered, long nowMs) {
        if (enteredAtMs != 0L) {
            return;
        }
        enteredAtMs = Math.max(1L, nowMs);
        members = power == null ? List.of() : List.copyOf(power);
        entrants = entered == null ? List.of() : List.copyOf(entered);
    }

    /** The run first reached this phase. */
    synchronized void markPhase(AgentRjpqSession.Phase phase, long nowMs) {
        if (phase != null) {
            phaseAtMs.putIfAbsent(phase, Math.max(1L, nowMs));
        }
    }

    synchronized void mark(Boundary boundary, long nowMs) {
        if (boundary != null) {
            boundaryAtMs.putIfAbsent(boundary, Math.max(1L, nowMs));
        }
    }

    /** A member went down (once per time down). */
    synchronized void markDown(int characterId) {
        deaths.merge(characterId, 1, Integer::sum);
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(enteredAtMs, Map.copyOf(phaseAtMs), Map.copyOf(boundaryAtMs), members, entrants,
                Map.copyOf(deaths));
    }

    /** The marks at one moment; 0 (or no entry) is a boundary not seen. */
    record Snapshot(long enteredAtMs, Map<AgentRjpqSession.Phase, Long> phaseAtMs, Map<Boundary, Long> boundaryAtMs,
                    List<AgentPqMemberPower> members, List<Integer> entrants, Map<Integer, Integer> deaths) {
        long phase(AgentRjpqSession.Phase phase) {
            return phaseAtMs.getOrDefault(phase, 0L);
        }

        long at(Boundary boundary) {
            return boundaryAtMs.getOrDefault(boundary, 0L);
        }
    }
}
