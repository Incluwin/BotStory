package server.agents.capabilities.partyquest.apq;

import server.agents.capabilities.partyquest.pace.AgentPqMemberPower;

import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * When each boundary of one APQ run's pace was first seen, for {@link AgentApqPaceReport} (the shape of
 * AgentPpqPaceMarks): the party inside (the bind, with each member's power then), the leader's first step onto Heart
 * Strings, the Twisted Switcher, Last Man Standing (and fifty pieces in his bag), Fluttering Hearts and Love Hurts,
 * Geist Balrog down, and the event's clear; each combination stage's checks; each member's times down. Only marks:
 * the report turns them into spans. Owned by its session; every method locks this object.
 */
final class AgentApqPaceMarks {
    enum Boundary { ROPES, PLATFORMS, PIECES, PIECES_FULL, GATES, BOSS, BOSS_DOWN, CLEARED }

    private long enteredAtMs;
    private final Map<Boundary, Long> boundaryAtMs = new EnumMap<>(Boundary.class);
    private List<AgentPqMemberPower> members = List.of();
    private List<Integer> entrants = List.of();
    private final Map<Integer, Integer> deaths = new TreeMap<>();
    private final Map<String, Integer> checks = new HashMap<>();

    /** The party stands in the event: who entered, and their power from the warmed profile (empty while cold). */
    synchronized void markEntered(List<AgentPqMemberPower> power, Collection<Integer> entered, long nowMs) {
        if (enteredAtMs != 0L) return;
        enteredAtMs = Math.max(1L, nowMs);
        members = power == null ? List.of() : List.copyOf(power);
        entrants = entered == null ? List.of() : List.copyOf(entered);
    }

    synchronized void mark(Boundary boundary, long nowMs) {
        if (boundary != null) boundaryAtMs.putIfAbsent(boundary, Math.max(1L, nowMs));
    }

    /** A member went down (once per time down). */
    synchronized void markDown(int characterId) {
        deaths.merge(characterId, 1, Integer::sum);
    }

    /** A combination stage's judged checks so far, the accepted one counted. */
    synchronized void noteChecks(String key, int count) {
        checks.merge(key, count, Math::max);
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(enteredAtMs, Map.copyOf(boundaryAtMs), members, entrants, Map.copyOf(deaths),
                Map.copyOf(checks));
    }

    /** The marks at one moment; 0 (or no entry) is a boundary not seen. */
    record Snapshot(long enteredAtMs, Map<Boundary, Long> boundaryAtMs, List<AgentPqMemberPower> members,
                    List<Integer> entrants, Map<Integer, Integer> deaths, Map<String, Integer> checks) {
        long at(Boundary boundary) {
            return boundaryAtMs.getOrDefault(boundary, 0L);
        }
    }
}
