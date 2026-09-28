package server.living.roster;

import net.server.world.CauseScope;
import net.server.world.PartyEvents;
import net.server.world.PartyEvents.PartyChangeEvent;
import net.server.world.PartyOperation;
import server.living.LivingWorld;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Keeps the roster in step with the party window (docs/BOT_MANAGEMENT_PLAN.md, "CompanionPartySync"). Every
 * committed party change reaches {@link #onPartyChanged} on companion-ops, after the fact, and is reconciled from
 * the state as it is now:
 * <ul>
 *   <li>a companion expelled by its owner is benched; by another leader, it is benched with returnWhenRoom and that
 *       leader's party lowers its owner's cap (the leader cap), so the seat is not refilled in a loop;</li>
 *   <li>an owner who leaves or is expelled takes the crew along: it re-forms around the owner, and the owner's guests
 *       step out; the humans left behind may have room for companions waiting for it;</li>
 *   <li>a disband re-forms each human's crew around its own owner;</li>
 *   <li>a human joining rebalances to fair share: guests step out first, then companions past their owner's cap are
 *       benched with returnWhenRoom;</li>
 *   <li>a leader change or a log-on changes nothing (a log-off may give the others room).</li>
 * </ul>
 * Events carrying a companion cause token are this layer's own echoes: CompanionSlots takes them into its seat
 * caches and nothing is reconciled. A circuit breaker skips a party's reconciliation after {@link #BREAKER_OPS}
 * corrections within {@link #BREAKER_WINDOW_MS} and logs {@code companion.drift}; while a member is committed to an
 * event instance its party's events wait (P2a's commitment is being in an event instance).
 *
 * <p>It also carries out the two ways a human's accept is routed here (P0 probes 1 and 12): the merge of an owner's
 * own party into a friend's, and make-room when the friend's party is full.</p>
 *
 * <p>All state is read and changed through {@link Crew}, the game in production ({@code LiveCrew}) and fakes in the
 * tests. Everything runs on companion-ops, so none of it is synchronized.</p>
 */
public final class CompanionPartySync {
    /** The cause token of the sync's own corrections. */
    public static final String CAUSE = "companion.sync";
    /** Every companion cause token starts with this; an event carrying one is an echo. */
    public static final String CAUSE_PREFIX = "companion.";
    public static final int BREAKER_OPS = 6;
    public static final long BREAKER_WINDOW_MS = 60_000L;
    /** The whisper of a citizen guest stepping out for its host's crew. */
    public static final String GUEST_LINE = "you've got a full crew, catch you later!";
    /** Events kept per party while a member is committed; the oldest go first (reconciliation reads the state as it is). */
    private static final int DEFERRED_PER_PARTY = 32;

    /** Why a companion stands down: its words to its owner, the event logged, and whether it comes back by itself. */
    public enum Reason {
        /** Its owner, leading, expelled it, or it left the party on its own: an ordinary bench. */
        BENCHED(null, "companion.bench", false),
        /** Another leader expelled it: it returns once its owner has room outside that party. */
        EXPELLED("the leader asked me to step out. i'll be at camp.", "companion.expel", true),
        /** Past its owner's fair share after a human joined, or no room for it after a merge or a re-form. */
        OVERFLOW("party's full. i'll train at camp till there's room.", "companion.overflow", true),
        /** Stepping out so a friend can join (make-room). */
        MAKE_ROOM("i'll step out for your friend.", "companion.make_room", true);

        private final String line;
        private final String event;
        private final boolean returnWhenRoom;

        Reason(String line, String event, boolean returnWhenRoom) {
            this.line = line;
            this.event = event;
            this.returnWhenRoom = returnWhenRoom;
        }

        /** What it whispers its owner; null for its usual bench words. */
        public String line() {
            return line;
        }

        public String event() {
            return event;
        }

        public boolean returnWhenRoom() {
            return returnWhenRoom;
        }
    }

    /**
     * What the sync reads and changes. Reads are the state now, not the event's; the changes run under the sync's
     * cause token, so the events they make come back as echoes.
     */
    public interface Crew {
        /** True for a human player's character that is online. */
        boolean onlineHuman(int characterId);

        /** The CompanionSlots key: the persona id of a companion or Resident, else {@link CompanionSlots#characterKey}. */
        String keyOf(int characterId);

        /** The roster owner of a key, 0 when it is nobody's companion. */
        int ownerOf(String key);

        /** The host of a citizen guest, 0 when it is not one. */
        int hostOf(int characterId);

        /** The character of a key while it is loaded, else 0. */
        int loaded(String key);

        /** The party a character is in, 0 for none. */
        int partyOf(int characterId);

        /** A party's members in join order; empty once it is gone. */
        List<Integer> members(int partyId);

        /** An own party: the only human in it is its companions' owner (CompanionDirectory.isCompanionParty). */
        boolean companionParty(int partyId);

        /** The party as the seat arithmetic sees it. */
        CompanionSlots.View view(int partyId);

        /** Seats the owner may still fill, in their party or in one they would make (CompanionSlots.free). */
        int free(int ownerId);

        /** The owner's ACTIVE companions, usual lineup first. */
        List<String> active(int ownerId);

        /**
         * Companions waiting for room, usual lineup first: ACTIVE ones not loaded (a restore that found no seat),
         * then benched ones with returnWhenRoom that may come back into {@code partyId}.
         */
        List<String> waiting(int ownerId, int partyId);

        /** Committed to an event instance: its party's events wait. */
        boolean committed(int characterId);

        /** Benches the companion for {@code reason}; {@code partyId} is the party it stood down from. */
        void standDown(int ownerId, String key, Reason reason, int partyId);

        /** A citizen guest leaves its host's party with {@link #GUEST_LINE}; its seat goes back. */
        void guestLeaves(int hostId, int guestId);

        /** Gives back a seat held for someone not in the party yet. */
        void releaseSeat(String key);

        /** The character leaves its party (a human as by op 2). */
        void leave(int characterId);

        /** A loaded companion joins its owner's party (made when the owner has none), leaving any other first. */
        boolean seat(int ownerId, String key);

        /** A human joins a party. */
        boolean join(int humanId, int partyId);

        /** A roster call: the companion comes back (it may travel). */
        void recall(int ownerId, String key);

        void recordLeaderExpel(int partyId, int ownerId);

        /** The stock refusal of an accept: the party is full, or the player is already in one. */
        void refuse(int humanId, boolean partyFull);

        /** Logs {@code companion.drift}. */
        void drift(int partyId, String detail);
    }

    private final Crew crew;
    private final LongSupplier clock;
    /** party id -> when its recent corrections ran, oldest first. */
    private final Map<Integer, Deque<Long>> corrections = new HashMap<>();
    /** party id -> events waiting while a member is committed, in order. */
    private final Map<Integer, List<PartyChangeEvent>> deferred = new LinkedHashMap<>();

    public CompanionPartySync(Crew crew) {
        this(crew, System::currentTimeMillis);
    }

    public CompanionPartySync(Crew crew, LongSupplier clock) {
        this.crew = crew;
        this.clock = clock;
    }

    /** The sync over the living world's game world. */
    public static CompanionPartySync live(LivingWorld world) {
        return new CompanionPartySync(new LiveCrew(world));
    }

    // ---- party events ---------------------------------------------------------------------

    /** A committed party change, on companion-ops. */
    public void onPartyChanged(PartyChangeEvent event) {
        String cause = event.cause();
        if (cause != null && cause.startsWith(CAUSE_PREFIX)) {
            return; // an echo of companion code: CompanionSlots took it into the seat caches, nothing to reconcile
        }
        resumeDeferred();
        reconcile(List.of(event));
    }

    /**
     * Reconciles the waiting events of every party none of whose members is committed any more; each party's batch
     * is one catch-up, so it counts once toward the circuit breaker.
     */
    public void resumeDeferred() {
        if (deferred.isEmpty()) {
            return;
        }
        List<List<PartyChangeEvent>> ready = new ArrayList<>();
        deferred.entrySet().removeIf(waiting -> {
            for (PartyChangeEvent event : waiting.getValue()) {
                if (committed(event)) {
                    return false;
                }
            }
            ready.add(waiting.getValue());
            return true;
        });
        ready.forEach(this::reconcile);
    }

    /** Events waiting for a commitment to end (for the ops endpoints and the tests). */
    public int deferredCount() {
        int count = 0;
        for (List<PartyChangeEvent> events : deferred.values()) {
            count += events.size();
        }
        return count;
    }

    /** Events of one party, in order; any that corrected something counts as one correction. */
    private void reconcile(List<PartyChangeEvent> events) {
        int partyId = events.get(0).partyId();
        long now = clock.getAsLong();
        boolean corrected = false;
        boolean disbanded = false;
        for (PartyChangeEvent event : events) {
            if (committed(event)) {
                List<PartyChangeEvent> waiting = deferred.computeIfAbsent(partyId, id -> new ArrayList<>());
                if (waiting.size() >= DEFERRED_PER_PARTY) {
                    waiting.remove(0);
                }
                waiting.add(event);
                continue;
            }
            if (tripped(partyId, now)) {
                crew.drift(partyId, "circuit breaker open (" + BREAKER_OPS + " corrections within "
                        + BREAKER_WINDOW_MS / 1000 + " s): skipped " + event.op() + " of " + event.targetId());
                continue;
            }
            try (PartyEvents.Scope cause = CauseScope.open(CAUSE)) {
                corrected |= switch (event.op()) {
                    case JOIN -> joined(event);
                    case LEAVE, EXPEL -> left(event);
                    case DISBAND -> disbanded(event);
                    case LOG_ONOFF -> loggedOnOrOff(event);
                    case CHANGE_LEADER, SILENT_UPDATE -> false;
                };
            }
            disbanded |= event.op() == PartyOperation.DISBAND;
        }
        if (disbanded) {
            corrections.remove(partyId); // a party id is never used again
        } else if (corrected) {
            corrections.computeIfAbsent(partyId, id -> new ArrayDeque<>()).addLast(now);
        }
    }

    private boolean tripped(int partyId, long now) {
        Deque<Long> recent = corrections.get(partyId);
        if (recent == null) {
            return false;
        }
        while (!recent.isEmpty() && recent.peekFirst() <= now - BREAKER_WINDOW_MS) {
            recent.pollFirst();
        }
        if (recent.isEmpty()) {
            corrections.remove(partyId);
            return false;
        }
        return recent.size() >= BREAKER_OPS;
    }

    private boolean committed(PartyChangeEvent event) {
        if (crew.committed(event.targetId())) {
            return true;
        }
        for (PartyEvents.Snapshot snapshot : List.of(event.before(), event.after())) {
            for (int member : snapshot.memberIds()) {
                if (crew.committed(member)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean joined(PartyChangeEvent event) {
        int joiner = event.targetId();
        if (crew.onlineHuman(joiner)) {
            boolean changed = rebalance(event.partyId());
            return regroup(joiner) | changed;
        }
        String key = crew.keyOf(joiner);
        int owner = crew.ownerOf(key);
        if (owner == 0 || crew.partyOf(joiner) != event.partyId() || crew.members(event.partyId()).contains(owner)) {
            return false; // a guest, a citizen, an Agent, or a companion joining its owner
        }
        crew.drift(event.partyId(), key + " joined a party without its owner " + owner);
        crew.leave(joiner);
        regroup(owner);
        return true;
    }

    private boolean left(PartyChangeEvent event) {
        int target = event.targetId();
        int partyId = event.partyId();
        boolean changed = false;
        for (int member : crew.members(partyId)) {
            if (crew.hostOf(member) == target) {
                crew.guestLeaves(target, member); // its host is gone (even offline, expelled): it does not follow
                changed = true;
            }
        }
        if (crew.onlineHuman(target)) {
            changed |= regroup(target);
            for (int member : crew.members(partyId)) {
                if (crew.onlineHuman(member)) {
                    changed |= recallWaiting(member); // one human fewer: a bigger share for the others
                }
            }
            return changed;
        }
        String key = crew.keyOf(target);
        int owner = crew.ownerOf(key);
        if (owner == 0 || !crew.onlineHuman(owner) || !crew.active(owner).contains(key)) {
            return changed; // not a companion, its owner logged off, or the roster benched it already
        }
        int companion = crew.loaded(key);
        int at = companion == 0 ? 0 : crew.partyOf(companion);
        if (at != 0 && at == crew.partyOf(owner)) {
            return changed; // back with its owner already
        }
        boolean byLeader = event.op() == PartyOperation.EXPEL && event.actorId() != 0 && event.actorId() != owner;
        if (byLeader) {
            crew.recordLeaderExpel(partyId, owner);
        } else if (event.op() == PartyOperation.LEAVE) {
            crew.drift(partyId, key + " left its owner's party on its own");
        }
        crew.standDown(owner, key, byLeader ? Reason.EXPELLED : Reason.BENCHED, partyId);
        return true;
    }

    private boolean disbanded(PartyChangeEvent event) {
        Set<Integer> owners = new LinkedHashSet<>();
        for (int member : event.before().memberIds()) {
            int owner = crew.onlineHuman(member) ? member : crew.ownerOf(crew.keyOf(member));
            if (owner != 0 && crew.onlineHuman(owner)) {
                owners.add(owner);
            }
        }
        boolean changed = false;
        for (int owner : owners) {
            changed |= regroup(owner);
        }
        return changed;
    }

    /** Nothing destructive: a human coming online keeps what room there is; one going offline leaves the others room. */
    private boolean loggedOnOrOff(PartyChangeEvent event) {
        if (crew.onlineHuman(event.targetId())) {
            return false;
        }
        boolean changed = false;
        for (int member : crew.members(event.partyId())) {
            if (crew.onlineHuman(member)) {
                changed |= recallWaiting(member);
            }
        }
        return changed;
    }

    // ---- accept routing (P0 probes 1 and 12) ------------------------------------------------

    /**
     * A human accepted an invite into {@code partyId} (op 3) that the stock join cannot take. When the party is full,
     * make-room steps someone out first (guests, then companions past their owner's share). An owner in an own party
     * merges: they leave it and join, and their companions follow up to their fair share there; the rest stand
     * down with returnWhenRoom. Refused with the stock message when the player's party is shared or committed, or
     * when nobody can step out.
     */
    public void accept(int humanId, int partyId) {
        List<Integer> target = crew.members(partyId);
        int own = crew.partyOf(humanId);
        if (!crew.onlineHuman(humanId) || target.isEmpty() || own == partyId) {
            return;
        }
        boolean merge = own != 0;
        if (merge && (!crew.companionParty(own) || anyCommitted(crew.members(own)))) {
            crew.refuse(humanId, false);
            return;
        }
        boolean full = target.size() >= CompanionSlots.PARTY_CAP;
        if (full && anyCommitted(target)) {
            crew.refuse(humanId, true);
            return;
        }
        try (PartyEvents.Scope cause = CauseScope.open(CAUSE)) {
            if (full) {
                CompanionSlots.MakeRoom room = CompanionSlots.makeRoom(crew.view(partyId), humanId);
                if (room.outcome() != CompanionSlots.RoomOutcome.STEP_OUT) {
                    crew.refuse(humanId, true);
                    return;
                }
                stepOut(room.stepOut(), partyId, Reason.MAKE_ROOM);
            }
            if (merge) {
                crew.leave(humanId); // their own party: as its leader this disbands it, and the echo is ignored
            }
            if (!crew.join(humanId, partyId)) {
                regroup(humanId); // the party filled or went meanwhile: the crew re-forms as before
                return;
            }
            rebalance(partyId);
            regroup(humanId);
        }
    }

    /**
     * The Crew window's "Make room for a friend..." (P0 probe 12: a full party cannot invite): the companion steps out
     * with returnWhenRoom. False when it is not out with its owner.
     */
    public boolean makeRoom(int ownerId, String key) {
        if (!crew.active(ownerId).contains(key) || crew.loaded(key) == 0) {
            return false;
        }
        try (PartyEvents.Scope cause = CauseScope.open(CAUSE)) {
            crew.standDown(ownerId, key, Reason.MAKE_ROOM, crew.partyOf(ownerId));
        }
        return true;
    }

    // ---- corrections ------------------------------------------------------------------------

    /** Fair share in {@code partyId}: every claim past its owner's cap steps out, guests first. */
    private boolean rebalance(int partyId) {
        boolean changed = false;
        for (CompanionSlots.Claim claim : CompanionSlots.allocate(crew.view(partyId)).overflow()) {
            stepOut(claim, partyId, Reason.OVERFLOW);
            changed = true;
        }
        return changed;
    }

    private void stepOut(CompanionSlots.Claim claim, int partyId, Reason reason) {
        if (claim.guest()) {
            if (claim.member()) {
                crew.guestLeaves(claim.ownerId(), claim.characterId());
            } else {
                crew.releaseSeat(claim.key()); // a guest that has not joined yet
            }
            return;
        }
        if (!claim.member()) {
            crew.releaseSeat(claim.key()); // a seat held for a companion on its way, or away
        }
        crew.standDown(claim.ownerId(), claim.key(), reason, partyId);
    }

    /**
     * Brings the owner's loaded ACTIVE companions into the owner's party (made when the owner has none), usual
     * lineup first; one with no seat left stands down with returnWhenRoom. Then the waiting ones come back.
     */
    private boolean regroup(int ownerId) {
        if (!crew.onlineHuman(ownerId)) {
            return false;
        }
        boolean changed = false;
        for (String key : crew.active(ownerId)) {
            int companion = crew.loaded(key);
            if (companion == 0) {
                continue; // not out yet: a restore still waiting for room
            }
            int at = crew.partyOf(companion);
            if (at != 0 && at == crew.partyOf(ownerId)) {
                continue;
            }
            changed = true;
            if (crew.free(ownerId) <= 0 || !crew.seat(ownerId, key)) {
                crew.standDown(ownerId, key, Reason.OVERFLOW, at);
            }
        }
        return recallWaiting(ownerId) | changed;
    }

    /** The owner's companions waiting for room come back while the owner has seats for them. */
    private boolean recallWaiting(int ownerId) {
        boolean changed = false;
        for (String key : crew.waiting(ownerId, crew.partyOf(ownerId))) {
            if (crew.free(ownerId) <= 0) {
                break;
            }
            crew.recall(ownerId, key);
            changed = true;
        }
        return changed;
    }

    private boolean anyCommitted(List<Integer> members) {
        for (int member : members) {
            if (crew.committed(member)) {
                return true;
            }
        }
        return false;
    }
}
