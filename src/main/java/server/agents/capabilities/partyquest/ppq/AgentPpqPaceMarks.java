package server.agents.capabilities.partyquest.ppq;

import server.agents.capabilities.partyquest.pace.AgentPqMemberPower;

import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * When each boundary of one PPQ companion run's pace was first seen, for {@link AgentPpqPaceReport} (P6i Step 5, the
 * shape of AgentRjpqPaceMarks): the party inside (the bind, with each member's power then), the leader's first step
 * onto the medal map, each wave's twenty in his bag and its hand-in (the event's stage2 counter), his first step onto
 * the second deck, the door map and the boss map, the Lord Pirate down, and the event's clear; whether he went into a
 * deck's chest or hidden room (that deck's span then holds more than its clear); each medal wave's counted kills;
 * each member's times down and who left the run. Only marks: the report turns them into spans. Each boundary keeps its
 * first sighting. Owned by its session; every method locks this object, since the run's release may read it from
 * another thread.
 */
final class AgentPpqPaceMarks {
    enum Boundary {
        MEDALS, WAVE_ONE_FULL, WAVE_ONE_IN, WAVE_TWO_FULL, WAVE_TWO_IN, WAVE_THREE_FULL, WAVE_THREE_IN, DECK_TWO, DOORS,
        BOSS, BOSS_DOWN, CLEARED
    }

    private long enteredAtMs;
    private final Map<Boundary, Long> boundaryAtMs = new EnumMap<>(Boundary.class);
    private final Set<Integer> detourDecks = new LinkedHashSet<>();
    private List<AgentPqMemberPower> members = List.of();
    private List<Integer> entrants = List.of();
    private final Map<Integer, Integer> deaths = new TreeMap<>();
    private final Set<Integer> departed = new LinkedHashSet<>();
    /** Each medal wave's (0-2) counted kills, and the wave pirates last seen alive on the medal map. */
    private final Map<Integer, Integer> waveKills = new TreeMap<>();
    private int waveLast = -1;
    private int waveLive = -1;

    /** The party stands in the event: who entered, and their power from the warmed profile (empty while cold). */
    synchronized void markEntered(List<AgentPqMemberPower> power, Collection<Integer> entered, long nowMs) {
        if (enteredAtMs != 0L) {
            return;
        }
        enteredAtMs = Math.max(1L, nowMs);
        members = power == null ? List.of() : List.copyOf(power);
        entrants = entered == null ? List.of() : List.copyOf(entered);
    }

    synchronized void mark(Boundary boundary, long nowMs) {
        if (boundary != null) {
            boundaryAtMs.putIfAbsent(boundary, Math.max(1L, nowMs));
        }
    }

    /** The leader went into this deck's chest or hidden room. */
    synchronized void markDetour(int deckMapId) {
        detourDecks.add(deckMapId);
    }

    /** A member went down (once per time down). */
    synchronized void markDown(int characterId) {
        deaths.merge(characterId, 1, Integer::sum);
    }

    /** A member left the run before its end (a companion the gate let go, a human who walked out). */
    synchronized void markDeparted(int characterId) {
        departed.add(characterId);
    }

    /**
     * The wave's pirates alive on the medal map at this pass: each drop since the last pass of the same wave counts as
     * kills (a respawn only raises the count; kills and a respawn within one pass are missed, so it is a lower bound).
     */
    synchronized void observeWave(int wave, int live) {
        if (wave < 0 || live < 0) return;
        if (wave == waveLast && waveLive >= 0 && live < waveLive) waveKills.merge(wave, waveLive - live, Integer::sum);
        waveLast = wave;
        waveLive = live;
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(enteredAtMs, Map.copyOf(boundaryAtMs), Set.copyOf(detourDecks), members, entrants,
                Map.copyOf(deaths), Set.copyOf(departed), Map.copyOf(waveKills));
    }

    /** The marks at one moment; 0 (or no entry) is a boundary not seen. */
    record Snapshot(long enteredAtMs, Map<Boundary, Long> boundaryAtMs, Set<Integer> detourDecks,
                    List<AgentPqMemberPower> members, List<Integer> entrants, Map<Integer, Integer> deaths,
                    Set<Integer> departed, Map<Integer, Integer> waveKills) {
        long at(Boundary boundary) {
            return boundaryAtMs.getOrDefault(boundary, 0L);
        }
    }
}
