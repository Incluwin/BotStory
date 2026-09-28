package net.server.world;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Committed party changes for whoever listens (docs/BOT_MANAGEMENT_PLAN.md, "Threading and party-event model").
 * {@link World#updateParty(int, PartyOperation, PartyCharacter)} takes a pre-mutation {@link Snapshot} before it
 * changes the party and publishes a {@link PartyChangeEvent} after its broadcast, so every event is self-contained
 * (CHANGE_LEADER keeps the previous leader). The engine knows nothing about who listens: the living world installs
 * a {@link Listener} that queues the event for its own thread, so no listener runs party code inline.
 *
 * <p>{@code actorId} is who asked for the change (op 2, 5 and 6, and a logout), from {@link #actor(int)};
 * {@code cause} is the tag of the code that made it, from {@link CauseScope}, so it can recognise its own echoes.</p>
 */
public final class PartyEvents {
    private static final Logger log = LoggerFactory.getLogger(PartyEvents.class);
    private static final ThreadLocal<Integer> ACTOR = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> DISPATCHING = new ThreadLocal<>();
    private static volatile Listener listener;

    private PartyEvents() {
    }

    /** Member ids in join order and the leader id; EMPTY once a party is gone. */
    public record Snapshot(List<Integer> memberIds, int leaderId) {
        public static final Snapshot EMPTY = new Snapshot(List.of(), 0);

        public Snapshot {
            memberIds = List.copyOf(memberIds);
        }

        public boolean contains(int characterId) {
            return memberIds.contains(characterId);
        }
    }

    /**
     * One committed change. {@code targetId} is the operation's target (the joiner, the leaver, the new leader);
     * {@code actorId} 0 = not asked for by a player; {@code cause} null = not companion code. {@code targetOnline} is
     * the target's party record as the operation wrote it (a LOG_ONOFF says which way it went; the player may still
     * be in storage when a listener reads it).
     */
    public record PartyChangeEvent(int partyId, PartyOperation op, int targetId, int actorId, Snapshot before,
                                   Snapshot after, String cause, boolean targetOnline) {
    }

    /** Receives every committed event on the thread that made the change; it must hand the work off, not do it. */
    @FunctionalInterface
    public interface Listener {
        void partyChanged(PartyChangeEvent event);
    }

    /** A thread-local value in force until closed; closing restores the previous one. */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    /** Installs the listener (null removes it). */
    public static void install(Listener installed) {
        listener = installed;
    }

    /** Who asks for the party changes made on this thread until the scope closes. */
    public static Scope actor(int actorId) {
        Integer previous = ACTOR.get();
        ACTOR.set(actorId);
        return () -> restore(ACTOR, previous);
    }

    /** The actor in force on this thread, 0 when none. */
    public static int currentActor() {
        Integer actor = ACTOR.get();
        return actor == null ? 0 : actor;
    }

    public static Snapshot snapshot(Party party) {
        if (party == null) {
            return Snapshot.EMPTY;
        }
        List<Integer> ids = new ArrayList<>();
        for (PartyCharacter member : party.getMembers()) {
            ids.add(member.getId());
        }
        return new Snapshot(ids, party.getLeaderId());
    }

    /**
     * The pre-mutation snapshot for an operation about to run, or null when nothing will be published (no listener,
     * or a SILENT_UPDATE, which only refreshes a member's map and level).
     */
    static Snapshot before(Party party, PartyOperation operation) {
        return listener == null || operation == PartyOperation.SILENT_UPDATE ? null : snapshot(party);
    }

    /** Publishes the committed change; called after the broadcast. A no-op when {@link #before} returned null. */
    static void publish(Party party, PartyOperation operation, PartyCharacter target, Snapshot before) {
        Listener current = listener;
        if (before == null || current == null) {
            return;
        }
        if (Boolean.TRUE.equals(DISPATCHING.get())) {
            // a listener changed a party inline: that is the re-entry the async hand-off exists to prevent
            log.warn("party {} {} changed from inside a party listener; not published again", party.getId(), operation);
            return;
        }
        Snapshot after = operation == PartyOperation.DISBAND ? Snapshot.EMPTY : snapshot(party);
        PartyChangeEvent event = new PartyChangeEvent(party.getId(), operation, target == null ? 0 : target.getId(),
                currentActor(), before, after, CauseScope.current(), target != null && target.isOnline());
        DISPATCHING.set(Boolean.TRUE);
        try {
            current.partyChanged(event);
        } catch (RuntimeException failure) {
            log.warn("party listener failed for {}", event, failure);
        } finally {
            DISPATCHING.remove();
        }
    }

    static <T> void restore(ThreadLocal<T> local, T previous) {
        if (previous == null) {
            local.remove();
        } else {
            local.set(previous);
        }
    }
}
