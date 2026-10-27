package server.living.population;

import client.Character;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeEntry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** A citizen that is online right now: loaded as a character, or virtual (scheduled online but on the ledger). */
public final class CitizenSession {
    /**
     * JOB: on its way to its instructor for its next job (CitizenJobErrands); never rolled by a cohort's weights. PLAY:
     * omok or match cards in a town that allows minigames (CitizenMiniGames).
     */
    public enum Activity { TRAIN, TOWN, PQ, MARKET, AFK, JOB, PLAY }

    public final int characterId;
    public volatile Citizen citizen;
    public volatile AgentRuntimeEntry entry;
    public volatile Activity activity;
    public volatile int mapId;
    public volatile int channel;
    public final long startedAtMs;
    public volatile long endsAtMs;
    public volatile long lastPlanAtMs;
    public volatile long afkUntilMs;
    public volatile long ledgerAtMs;
    public volatile boolean stallOpen;
    /** Sales of its open stall it has remembered the buyers of (Market.rememberSales); 0 when the stall opens. */
    volatile int stallSalesSeen;
    /** The job it is going to its instructor for (activity JOB); null otherwise. */
    public volatile client.Job errandJob;
    /** Its instructor refused it this session: no second try before its next session (CitizenJobErrands). */
    public volatile boolean errandFailed;
    public volatile int partyId;
    public final Journal journal = new Journal();
    /** channel -> last time this citizen spoke on it (chatter cooldowns). */
    public final Map<String, Long> lastSpokeAtMs = new ConcurrentHashMap<>();
    public final java.util.Deque<String> recentIntents = new java.util.ArrayDeque<>();
    /** Virtual sessions have no character loaded; they exist for the ledger and social bookkeeping. */
    public volatile boolean virtual;
    /** The latest move asked of it (PresenceScheduler.travel); compared by identity, as two asks are two moves. */
    volatile Move latestMove;
    /** A move that waited for its own supply trip, made at the first presence sweep after the trip; null when none. */
    final AtomicReference<Move> waitingMove = new AtomicReference<>();
    /** The move it is making on foot because someone can see it (CitizenTravel); null when none. */
    volatile CitizenTravel.Trip trip;
    /** Its pending retry of a move to a full map (population.MAP_VISIBLE_MAX); a newer move clears it; null: none. */
    volatile Object capRetry;
    /** The field it died on beside a field boss (PopulationHooks.died), avoided until {@link #avoidUntilMs}; 0: none. */
    volatile int avoidMapId;
    volatile long avoidUntilMs;
    /** Its open TRAIN stint on a field (CitizenSpots, F2.1), booked when it ends; null when none. */
    volatile CitizenSpots.Stint stint;

    /** A move to another map and the activity it starts there. */
    record Move(Activity activity, int mapId) {
    }

    CitizenSession(Citizen citizen, long startedAtMs, long endsAtMs, boolean virtual) {
        this.characterId = citizen.characterId();
        this.citizen = citizen;
        this.startedAtMs = startedAtMs;
        this.endsAtMs = endsAtMs;
        this.lastPlanAtMs = startedAtMs;
        this.ledgerAtMs = startedAtMs;
        this.virtual = virtual;
        this.channel = citizen.channel();
    }

    public Character agent() {
        AgentRuntimeEntry current = entry;
        return current == null ? null : AgentRuntimeIdentityRuntime.bot(current);
    }

    public boolean loaded() {
        return !virtual && agent() != null;
    }

    public String name() {
        return citizen.name();
    }
}
