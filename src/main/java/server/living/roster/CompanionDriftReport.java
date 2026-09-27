package server.living.roster;

import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Read-only comparison of one owner's roster rows with the owner's party and the Agent registry
 * (BOT_MANAGEMENT_PLAN.md P0, the zero-drift gate). Pure: the caller snapshots the server state
 * into these records, so the classification is testable without a server and never mutates
 * anything.
 */
public final class CompanionDriftReport {
    public enum Code {
        /**
         * ACTIVE row, companion loaded, but not a member of the owner's party, or listed in it while
         * its own party pointer (what EXP share and party HP read) names another party or none.
         */
        ACTIVE_NOT_IN_PARTY,
        /** ACTIVE row, companion character not loaded. */
        ACTIVE_NOT_LOADED,
        /** BENCHED or AWAY row, yet the companion is a member of the owner's party. */
        IN_PARTY_NOT_ACTIVE,
        /**
         * ACTIVE row, companion loaded, but its runtime is registered to someone else (or to nobody);
         * or a BENCHED or AWAY row whose loaded runtime is still registered to the owner (the despawn
         * failed, so it keeps following the owner and holding a scheduler slot).
         */
        LEADER_MISMATCH,
        /** ACTIVE row, companion loaded on another channel than its owner. */
        CHANNEL_MISMATCH,
        /** An Agent registered under the owner that has no roster row (a hand-spawned bot); informational. */
        UNROSTERED_AGENT;

        public boolean informational() {
            return this == UNROSTERED_AGENT;
        }
    }

    public record Member(int id, String name, boolean bot) { }

    /** The owner as seen right now; {@code partyId} 0 means no party. */
    public record Owner(int id, String name, int channel, int mapId, int partyId, List<Member> party) {
        /** True when the character is listed in the owner's party (online or not). */
        public boolean inParty(int characterId) {
            if (partyId <= 0 || characterId <= 0) {
                return false;
            }
            for (Member member : party) {
                if (member.id() == characterId) {
                    return true;
                }
            }
            return false;
        }
    }

    /** One living_roster row; {@code characterId} is the companion's character (0 when never provisioned). */
    public record Row(String personaId, String name, RosterState state, Assignment assignment, int campMapId, int characterId) { }

    /**
     * The companion's live state; {@code registeredLeaderId} is the registry cohort (the leader's
     * character id for leader-registered Agents), -1 when no Agent runtime holds the character.
     */
    public record Live(boolean loaded, int channel, int mapId, int hp, int maxHp, int partyId, long registeredLeaderId) {
        public static Live unloaded(long registeredLeaderId) {
            return new Live(false, -1, 0, 0, 0, 0, registeredLeaderId);
        }
    }

    public record Companion(Row row, Live live) { }

    public record AgentRef(int characterId, String name) { }

    public record Drift(Code code, String personaId, int characterId, String detail) { }

    private final Owner owner;
    private final int freeSlots;
    private final List<Companion> companions;
    private final List<AgentRef> unrostered;
    private final List<Drift> drift;

    private CompanionDriftReport(Owner owner, int freeSlots, List<Companion> companions, List<AgentRef> unrostered, List<Drift> drift) {
        this.owner = owner;
        this.freeSlots = freeSlots;
        this.companions = List.copyOf(companions);
        this.unrostered = List.copyOf(unrostered);
        this.drift = List.copyOf(drift);
    }

    /** Classifies the owner's companions and the Agents registered under the owner. */
    public static CompanionDriftReport of(Owner owner, int freeSlots, List<Companion> companions, List<AgentRef> registeredToOwner) {
        List<Drift> drift = new ArrayList<>();
        Set<Integer> rostered = new HashSet<>();
        for (Companion companion : companions) {
            if (companion.row().characterId() > 0) {
                rostered.add(companion.row().characterId());
            }
            classify(owner, companion.row(), companion.live(), drift);
        }
        List<AgentRef> unrostered = new ArrayList<>();
        for (AgentRef agent : registeredToOwner) {
            if (!rostered.contains(agent.characterId())) {
                unrostered.add(agent);
                drift.add(new Drift(Code.UNROSTERED_AGENT, null, agent.characterId(), agent.name() + " is registered to the owner without a roster row"));
            }
        }
        return new CompanionDriftReport(owner, freeSlots, companions, unrostered, drift);
    }

    private static void classify(Owner owner, Row row, Live live, List<Drift> out) {
        boolean inParty = owner.inParty(row.characterId());
        if (row.state() != RosterState.ACTIVE) {
            if (inParty) {
                out.add(drift(Code.IN_PARTY_NOT_ACTIVE, row, "row is " + row.state() + " but the companion is in the owner's party"));
            }
            // A live bench session registers under the companion's own id, so it does not trip this.
            if (live.loaded() && live.registeredLeaderId() == owner.id()) {
                out.add(drift(Code.LEADER_MISMATCH, row, "row is " + row.state() + " but its runtime is still registered to the owner"));
            }
            return;
        }
        if (!live.loaded()) {
            out.add(drift(Code.ACTIVE_NOT_LOADED, row, "row is ACTIVE but the companion is not loaded"));
            return;
        }
        if (!inParty) {
            out.add(drift(Code.ACTIVE_NOT_IN_PARTY, row, live.partyId() == 0 ? "companion has no party" : "companion is in party " + live.partyId()));
        } else if (live.partyId() != owner.partyId()) {
            out.add(drift(Code.ACTIVE_NOT_IN_PARTY, row, "party lists the companion but "
                    + (live.partyId() == 0 ? "it has no party" : "its party is " + live.partyId())));
        }
        if (live.registeredLeaderId() != owner.id()) {
            out.add(drift(Code.LEADER_MISMATCH, row, live.registeredLeaderId() <= 0 ? "no Agent runtime holds the companion"
                    : "registered to leader " + live.registeredLeaderId()));
        }
        if (live.channel() != owner.channel()) {
            out.add(drift(Code.CHANNEL_MISMATCH, row, "companion on channel " + live.channel() + ", owner on " + owner.channel()));
        }
    }

    private static Drift drift(Code code, Row row, String detail) {
        return new Drift(code, row.personaId(), row.characterId(), detail);
    }

    public Owner owner() {
        return owner;
    }

    public int freeSlots() {
        return freeSlots;
    }

    public List<Companion> companions() {
        return companions;
    }

    public List<AgentRef> unrostered() {
        return unrostered;
    }

    public List<Drift> drift() {
        return drift;
    }

    /** Drift entries that break the zero-drift gate (informational codes excluded). */
    public int driftCount() {
        int count = 0;
        for (Drift entry : drift) {
            if (!entry.code().informational()) {
                count++;
            }
        }
        return count;
    }

    public boolean clean() {
        return driftCount() == 0;
    }
}
