package server.living.roster;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One materialization lease per character id (docs/BOT_MANAGEMENT_PLAN.md, "CompanionLeases"):
 * whoever loads a character takes its lease first, and a character already leased for something
 * else is refused, never silently removed. In memory only; nothing survives a restart.
 */
public final class CompanionLeases {
    /**
     * BENCH_LIVE, BENCH_TOWN and COMMERCE are a benched companion's live slice (P5, BenchDutyDispatcher): at its field
     * camp, resting in town, or at a Free Market stall. LOAN is a companion its owner lent to a friend, charged to the
     * friend (CompanionLoans).
     */
    public enum Kind { COMPANION, BENCH_LIVE, RESIDENT, POPULATION, PQ_BACKGROUND, EXPEDITION, BENCH_TOWN, COMMERCE, LOAN }

    /** What a character is loaded for, and the owner it is charged to (0 = nobody). */
    public record Lease(Kind kind, int ownerId) { }

    private final Map<Integer, Lease> leases = new ConcurrentHashMap<>();

    /**
     * Takes the character's lease. True when it was free or is already held for the same kind and
     * owner; false while any other lease holds it.
     */
    public boolean tryAcquire(int characterId, Kind kind, int ownerId) {
        Lease wanted = new Lease(kind, ownerId);
        Lease held = leases.putIfAbsent(characterId, wanted);
        return held == null || held.equals(wanted);
    }

    /** Drops the character's lease if it is held for this kind; false when it was not. */
    public boolean release(int characterId, Kind kind) {
        boolean[] released = {false};
        leases.computeIfPresent(characterId, (id, held) -> {
            if (held.kind() != kind) {
                return held;
            }
            released[0] = true;
            return null;
        });
        return released[0];
    }

    /**
     * Moves the character's lease from {@code from} to {@code to} in one step (a loan's hand-over and its return), so
     * nothing can take the character in between. False, and nothing changes, unless {@code from} holds it.
     */
    public boolean transfer(int characterId, Lease from, Lease to) {
        return from != null && to != null && leases.replace(characterId, from, to);
    }

    /** The lease holding the character, or null when it is free. */
    public Lease holder(int characterId) {
        return leases.get(characterId);
    }

    /** Characters leased for this kind. */
    public int count(Kind kind) {
        int count = 0;
        for (Lease lease : leases.values()) {
            if (lease.kind() == kind) {
                count++;
            }
        }
        return count;
    }
}
