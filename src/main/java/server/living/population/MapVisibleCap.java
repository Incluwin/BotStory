package server.living.population;

import config.PopulationConfig;
import server.maps.MapleMap;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * population.MAP_VISIBLE_MAX (docs/SCALE_PLATFORM_PLAN.md section 1): no map instance shows more visible citizens than
 * the cap. A citizen counts on the map instance its loaded character stands on now, so a move the engine makes on its
 * own (a supply trip) counts too, and on the map an admitted move is taking it to until it lands there (a
 * reservation). Admission is one locked count-and-reserve, so two moves never both take the last slot. A reservation
 * that is never landed or replaced (a dropped walk) lapses after {@link #RESERVATION_MS}; a walk on its way refreshes
 * its own at every check (CitizenTravel). Citizens only: Residents, companions and other Agents are not counted.
 */
final class MapVisibleCap {
    /** How long an admitted move holds its slot without a refresh: the longest cheap-move pause (90 s) and its checks. */
    static final long RESERVATION_MS = 180_000L;

    private record Reservation(int channel, int mapId, long expiresAtMs) {
    }

    /** Lifetime counters for the population status and the logs. */
    record Stats(long redirects, long virtualLogins, long deferrals, long nudges, long refusedArrivals) {
    }

    private final Supplier<PopulationConfig> config;
    private final Supplier<Collection<CitizenSession>> sessions;
    private final Function<CitizenSession, MapleMap> standsOn;
    private final Map<Integer, Reservation> inbound = new ConcurrentHashMap<>();
    private final Object admission = new Object();
    private final AtomicLong redirects = new AtomicLong();
    private final AtomicLong virtualLogins = new AtomicLong();
    private final AtomicLong deferrals = new AtomicLong();
    private final AtomicLong nudges = new AtomicLong();
    private final AtomicLong refusedArrivals = new AtomicLong();

    MapVisibleCap(Supplier<PopulationConfig> config,
                  Supplier<Collection<CitizenSession>> sessions,
                  Function<CitizenSession, MapleMap> standsOn) {
        this.config = config;
        this.sessions = sessions;
        this.standsOn = standsOn;
    }

    /** The map instance a loaded citizen's character stands on; null when it is virtual or not loaded. */
    static MapleMap standingMap(CitizenSession session) {
        if (session.virtual) {
            return null;
        }
        client.Character agent = session.agent();
        return agent == null ? null : agent.getMap();
    }

    boolean enabled() {
        return config.get().MAP_VISIBLE_CAP_ENABLED;
    }

    int cap() {
        return config.get().MAP_VISIBLE_MAX;
    }

    /**
     * Visible citizens on {@code map} (channel {@code channel}) other than {@code selfId}: those standing on it plus
     * those whose admitted move is still taking them there.
     */
    int visible(MapleMap map, int channel, int selfId, long now) {
        if (map == null) {
            return 0;
        }
        Set<Integer> standingHere = new HashSet<>();
        for (CitizenSession session : sessions.get()) {
            if (session.characterId != selfId && standsOn.apply(session) == map) {
                standingHere.add(session.characterId);
            }
        }
        int count = standingHere.size();
        // reservations, not sessions: a login reserves before its session is in the scheduler's map
        for (Map.Entry<Integer, Reservation> entry : inbound.entrySet()) {
            Reservation reservation = entry.getValue();
            if (entry.getKey() != selfId && !standingHere.contains(entry.getKey()) && reservation.expiresAtMs > now
                    && reservation.channel == channel && reservation.mapId == map.getId()) {
                count++;
            }
        }
        return count;
    }

    /** True when {@code map} has no room for one more citizen than {@code selfId} (always false while disabled). */
    boolean full(MapleMap map, int channel, int selfId, long now) {
        return enabled() && map != null && visible(map, channel, selfId, now) >= cap();
    }

    /**
     * Admits {@code session} to {@code map} and reserves its slot there; false when the map is full. A citizen already
     * standing on {@code map} is admitted without a reservation (it is counted where it stands). Always true, and
     * reserves nothing, while the cap is disabled.
     */
    boolean admit(CitizenSession session, MapleMap map, long now) {
        if (!enabled() || map == null) {
            return true;
        }
        synchronized (admission) {
            inbound.values().removeIf(reservation -> reservation.expiresAtMs <= now);
            if (standsOn.apply(session) == map) {
                inbound.remove(session.characterId);
                return true;
            }
            if (visible(map, session.channel, session.characterId, now) >= cap()) {
                return false;
            }
            inbound.put(session.characterId, new Reservation(session.channel, map.getId(), now + RESERVATION_MS));
            return true;
        }
    }

    /** True while {@code session} holds a live reservation for {@code map}. */
    boolean holds(CitizenSession session, MapleMap map, long now) {
        Reservation reservation = inbound.get(session.characterId);
        return reservation != null && map != null && reservation.expiresAtMs > now
                && reservation.channel == session.channel && reservation.mapId == map.getId();
    }

    /** True while an admitted move of {@code session}'s is still on its way somewhere. */
    boolean reserved(CitizenSession session) {
        return inbound.containsKey(session.characterId);
    }

    /** A walk still on its way keeps its slot (CitizenTravel checks). */
    void refresh(CitizenSession session, long now) {
        inbound.computeIfPresent(session.characterId,
                (id, reservation) -> new Reservation(reservation.channel, reservation.mapId, now + RESERVATION_MS));
    }

    /** The move landed, was dropped, or the session ended: its slot goes back. */
    void release(CitizenSession session) {
        inbound.remove(session.characterId);
    }

    /** Loaded citizens per map instance they stand on, for the sweep's over-cap check. */
    Map<MapleMap, List<CitizenSession>> standingByMap() {
        Map<MapleMap, List<CitizenSession>> byMap = new IdentityHashMap<>();
        for (CitizenSession session : sessions.get()) {
            MapleMap standing = standsOn.apply(session);
            if (standing != null) {
                byMap.computeIfAbsent(standing, ignored -> new java.util.ArrayList<>()).add(session);
            }
        }
        return byMap;
    }

    /** Map id -> visible citizens, the busiest {@code limit} maps (ops: !citizens / the population status). */
    Map<Integer, Integer> busiest(int limit) {
        Map<Integer, Integer> counts = new HashMap<>();
        standingByMap().forEach((map, standing) -> counts.merge(map.getId(), standing.size(), Integer::sum));
        Map<Integer, Integer> top = new java.util.LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed())
                .limit(limit)
                .forEach(entry -> top.put(entry.getKey(), entry.getValue()));
        return top;
    }

    void countRedirect() {
        redirects.incrementAndGet();
    }

    void countVirtualLogin() {
        virtualLogins.incrementAndGet();
    }

    void countDeferral() {
        deferrals.incrementAndGet();
    }

    void countNudge() {
        nudges.incrementAndGet();
    }

    void countRefusedArrival() {
        refusedArrivals.incrementAndGet();
    }

    Stats stats() {
        return new Stats(redirects.get(), virtualLogins.get(), deferrals.get(), nudges.get(), refusedArrivals.get());
    }
}
