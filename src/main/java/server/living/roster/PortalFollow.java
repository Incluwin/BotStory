package server.living.roster;

import server.agents.capabilities.follow.AgentFollowGuard;

import java.awt.Point;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

/**
 * Portal-true follow (plan "Follow guards"): where each owner came into the map they are on, and when each of their
 * following companions comes in after them. The first one comes through a moment after it notices the owner has
 * gone, each next one {@link #STAGGER_MIN_MS}-{@link #STAGGER_MAX_MS} after the one before it, all at the owner's
 * arrival point. Memory only; safe from any thread.
 */
final class PortalFollow {
    static final long STAGGER_MIN_MS = 300L;
    static final long STAGGER_MAX_MS = 900L;

    /** One owner's arrival on a map, and the turn each follower was given (by companion character id). */
    private static final class Arrival {
        final int mapId;
        final Point at;
        final Map<Integer, Long> dueAtMs = new HashMap<>();
        long lastDueAtMs;

        Arrival(int mapId, Point at) {
            this.mapId = mapId;
            this.at = at;
        }
    }

    private final Map<Integer, Arrival> byOwner = new ConcurrentHashMap<>();
    private final LongSupplier stagger;

    PortalFollow() {
        this(() -> ThreadLocalRandom.current().nextLong(STAGGER_MIN_MS, STAGGER_MAX_MS + 1));
    }

    PortalFollow(LongSupplier stagger) {
        this.stagger = stagger;
    }

    /** The owner came into {@code mapId} at {@code at}; unknown ({@code null}) leaves followers beside them. */
    void entered(int ownerId, int mapId, Point at) {
        if (at == null) {
            byOwner.remove(ownerId);
        } else {
            byOwner.put(ownerId, new Arrival(mapId, new Point(at)));
        }
    }

    /** The owner is gone (deleted): nothing of theirs is kept. */
    void forget(int ownerId) {
        byOwner.remove(ownerId);
    }

    /**
     * How companion {@code companionId} comes into {@code mapId} after its owner: at their arrival point, on its turn
     * (given the first time it asks, and kept). Null when the owner's arrival on that map is not known.
     */
    AgentFollowGuard.Crossing crossing(int ownerId, int mapId, int companionId, long nowMs) {
        Arrival arrival = byOwner.get(ownerId);
        if (arrival == null || arrival.mapId != mapId) {
            return null;
        }
        long dueAtMs;
        synchronized (arrival) {
            Long turn = arrival.dueAtMs.get(companionId);
            if (turn == null) {
                turn = Math.max(nowMs, arrival.lastDueAtMs) + stagger.getAsLong();
                arrival.lastDueAtMs = turn;
                arrival.dueAtMs.put(companionId, turn);
            }
            dueAtMs = turn;
        }
        return new AgentFollowGuard.Crossing(new Point(arrival.at), dueAtMs);
    }
}
