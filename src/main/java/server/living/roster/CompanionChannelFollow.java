package server.living.roster;

import client.Character;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterEntry;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Companions follow their owner through a channel change (docs/BOT_MANAGEMENT_PLAN.md flow 6, "CompanionChannelFollow")
 * and wait out a Cash Shop visit. {@link #changing} runs on the packet thread before the transition (Client.changeChannel,
 * or the Cash Shop's way out, a change to the same channel) and only takes a note; on companion-ops the owner's ACTIVE
 * and TRAVELLING companions loaded then are marked. The old session's logout is then no logout (no goodbye, no PARKED,
 * no lineup save). Once the owner is in on the new channel each marked companion moves there, 1-3 s apart
 * (RosterService.followChannel), and {@link #FALLBACK_MS} later any still not there is moved the hard way. An owner
 * who is not in again {@link #LOGIN_WAIT_MS} after the change logged off after all. Live bench slices stay where they
 * are. In the Cash Shop companions idle where they stand; a visit longer than {@code LEADER_OFFLINE_BENCH_MS} takes
 * the logout path.
 */
final class CompanionChannelFollow {
    static final long STAGGER_MIN_MS = 1_000L;
    static final long STAGGER_MAX_MS = 3_000L;
    /** A marked companion not on its owner's channel this long after they logged in there is moved the hard way. */
    static final long FALLBACK_MS = 30_000L;
    /** An owner who has not logged in on the new channel this long after changing logged off after all. */
    static final long LOGIN_WAIT_MS = 60_000L;

    /** One channel change of one owner. Its sets and flags are written on companion-ops, in roster order. */
    private static final class Transfer {
        final Character owner;
        final Set<String> marked = Collections.synchronizedSet(new LinkedHashSet<>());
        /** Marked ones not moved yet. */
        final Set<String> pending = Collections.synchronizedSet(new LinkedHashSet<>());
        volatile boolean logoutSeen;
        volatile boolean arrived;

        Transfer(Character owner) {
            this.owner = owner;
        }
    }

    private final LivingWorld world;
    private final RosterService roster;
    /** Owner id -> the channel change under way; kept until its fallback, so a late old-session logout still matches. */
    private final Map<Integer, Transfer> transfers = new ConcurrentHashMap<>();
    /** Owner id -> the Cash Shop visit under way. */
    private final Map<Integer, Long> cashShop = new ConcurrentHashMap<>();
    private final AtomicLong visits = new AtomicLong();

    CompanionChannelFollow(LivingWorld world, RosterService roster) {
        this.world = world;
        this.roster = roster;
    }

    /**
     * The owner is about to change channel, or to leave the Cash Shop (on the packet thread, memory only): their
     * companions out now are marked on companion-ops, ahead of the old session's logout.
     */
    void changing(Character owner, int channel) {
        int ownerId = owner.getId();
        cashShop.remove(ownerId);
        Transfer transfer = new Transfer(owner);
        transfers.put(ownerId, transfer);
        world.executor().submit("roster.channel_change " + ownerId + " -> " + channel, () -> mark(ownerId, transfer));
        roster.later("roster.channel_wait " + ownerId, () -> expire(ownerId, transfer), LOGIN_WAIT_MS);
    }

    private void mark(int ownerId, Transfer transfer) {
        if (transfers.get(ownerId) != transfer) {
            return;
        }
        for (RosterEntry entry : roster.store().roster(ownerId)) {
            if (CompanionSeatState.partyMember(entry.state()) && roster.companion(entry) != null) {
                transfer.marked.add(entry.personaId());
                transfer.pending.add(entry.personaId());
            }
        }
    }

    /**
     * The owner's logout (on companion-ops): true for the old session's own, which is no logout. Any other is real, and
     * the caller ends whatever was under way ({@link #forget}).
     */
    boolean logoutIsTransition(int ownerId) {
        Transfer transfer = transfers.get(ownerId);
        if (transfer == null || transfer.logoutSeen) {
            return false;
        }
        transfer.logoutSeen = true;
        return true;
    }

    /** The owner logged off for real: a channel change or Cash Shop visit under way ends with it. */
    void forget(int ownerId) {
        transfers.remove(ownerId);
        cashShop.remove(ownerId);
    }

    /** True while the persona's companion is marked to move to its owner's new channel and has not yet. */
    boolean pending(String personaId) {
        for (Transfer transfer : transfers.values()) {
            if (transfer.pending.contains(personaId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The owner is in on the new channel (on companion-ops, at login): each marked companion moves over, 1-3 s apart,
     * and the fallback is due {@link #FALLBACK_MS} later. One loaded on the old channel after the change was noted (a
     * call or a restore queued behind it, which still saw the old session) is marked now. Returns the marked persona
     * ids (empty with no change under way).
     */
    Set<String> ownerArrived(Character owner) {
        int ownerId = owner.getId();
        Transfer transfer = transfers.get(ownerId);
        if (transfer == null) {
            return Set.of();
        }
        if (!transfer.arrived) {
            transfer.arrived = true;
            for (RosterEntry entry : roster.store().roster(ownerId)) {
                Character bot = CompanionSeatState.partyMember(entry.state()) ? roster.companion(entry) : null;
                if (bot != null && !RosterService.sameChannel(owner, bot)) {
                    transfer.marked.add(entry.personaId());
                    transfer.pending.add(entry.personaId());
                }
            }
            long delayMs = 0L;
            for (String personaId : List.copyOf(transfer.marked)) {
                delayMs += ThreadLocalRandom.current().nextLong(STAGGER_MIN_MS, STAGGER_MAX_MS + 1L);
                roster.later("roster.channel_follow " + personaId, () -> move(ownerId, transfer, personaId), delayMs);
            }
            roster.later("roster.channel_fallback " + ownerId, () -> fallback(ownerId, transfer), FALLBACK_MS);
        }
        return Set.copyOf(transfer.marked);
    }

    private void move(int ownerId, Transfer transfer, String personaId) {
        if (transfers.get(ownerId) != transfer || !transfer.pending.contains(personaId)) {
            return;
        }
        Character owner = roster.onlineCharacter(ownerId);
        Persona persona = world.content().persona(personaId);
        if (owner == null || persona == null) {
            return;
        }
        long waitMs = world.budget().loadDelayMs(owner, System.currentTimeMillis());
        if (waitMs > 0L) { // loads are spaced per owner: this one goes once the slot frees
            roster.later("roster.channel_follow " + personaId, () -> move(ownerId, transfer, personaId), waitMs);
            return;
        }
        if (roster.followChannel(owner, persona)) {
            transfer.pending.remove(personaId);
        }
    }

    private void fallback(int ownerId, Transfer transfer) {
        if (!transfers.remove(ownerId, transfer)) {
            return;
        }
        Character owner = roster.onlineCharacter(ownerId);
        for (String personaId : List.copyOf(transfer.pending)) {
            Persona persona = world.content().persona(personaId);
            if (owner != null && persona != null) {
                roster.followChannelFallback(owner, persona);
            }
        }
        transfer.pending.clear();
    }

    /** No login on the new channel in time: after the old session's logout, that was a logout after all. */
    private void expire(int ownerId, Transfer transfer) {
        if (transfer.arrived || !transfers.remove(ownerId, transfer)) {
            return;
        }
        transfer.pending.clear();
        if (transfer.logoutSeen) {
            roster.onOwnerLogout(transfer.owner, null);
        }
    }

    /**
     * The owner went into the Cash Shop (on the packet thread, memory only): their companions idle where they stand.
     * Still there {@code LEADER_OFFLINE_BENCH_MS} later, they log off as at a logout; the way out (a channel change to
     * the same channel) or a logout ends the visit.
     */
    void enteredCashShop(Character owner) {
        int ownerId = owner.getId();
        long visit = visits.incrementAndGet();
        cashShop.put(ownerId, visit);
        roster.later("roster.cash_shop " + ownerId, () -> {
            if (!cashShop.remove(ownerId, visit)) {
                return;
            }
            Character now = roster.onlineCharacter(ownerId);
            if (now != null && now.getCashShop() != null && now.getCashShop().isOpened()) {
                roster.onOwnerLogout(now, null);
            }
        }, world.config().LEADER_OFFLINE_BENCH_MS);
    }
}
