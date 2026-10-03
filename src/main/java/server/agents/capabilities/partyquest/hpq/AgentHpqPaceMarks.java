package server.agents.capabilities.partyquest.hpq;

import server.agents.capabilities.partyquest.pace.AgentPqMemberPower;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * When each boundary of one companion run's pace was first seen, for {@link AgentHpqPaceReport}: the party inside on
 * Primrose Hill (COLLECTING_SEEDS, with each member's power then), the Moon Bunny out (DEFENDING_BUNNY), its tenth
 * rice cake (the event's bunnyCake count first at REQUIRED_RICE_CAKES), and Growlie's clear (BONUS_DECISION); and what
 * happened to each member on the way (each time down, a tap-out). Only marks: the report turns them into spans. Each
 * boundary keeps its first sighting. Owned by its session; every method locks this object, since the run's release
 * may read it from another thread.
 */
final class AgentHpqPaceMarks {
    private long enteredAtMs;
    private long bunnyAtMs;
    private long tenthCakeAtMs;
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

    /** Every moonflower is up and the Moon Bunny is out. */
    synchronized void markBunny(long nowMs) {
        if (bunnyAtMs == 0L) bunnyAtMs = Math.max(1L, nowMs);
    }

    /** The Moon Bunny's cakes so far (the event's bunnyCake); the first count at the required ten is the mark. */
    synchronized void observeBunnyCakes(int cakes, long nowMs) {
        if (tenthCakeAtMs == 0L && cakes >= AgentHpqDefinition.REQUIRED_RICE_CAKES) {
            tenthCakeAtMs = Math.max(1L, nowMs);
        }
    }

    /** Growlie took the cakes. */
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
        return new Snapshot(enteredAtMs, bunnyAtMs, tenthCakeAtMs, clearedAtMs, members, entrants, Map.copyOf(deaths),
                Set.copyOf(tappedOut));
    }

    /** The marks at one moment; 0 is a boundary not seen. */
    record Snapshot(long enteredAtMs, long bunnyAtMs, long tenthCakeAtMs, long clearedAtMs,
                    List<AgentPqMemberPower> members, List<Integer> entrants, Map<Integer, Integer> deaths,
                    Set<Integer> tappedOut) {
    }
}
