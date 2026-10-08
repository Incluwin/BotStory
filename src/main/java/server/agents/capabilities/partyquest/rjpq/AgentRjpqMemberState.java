package server.agents.capabilities.partyquest.rjpq;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * One RJPQ member. Its fields beyond the identity are owned by that member's own Agent tick (an Agent's tickMember);
 * nothing else reads or writes them. What other threads read lives in {@link AgentRjpqSession}.
 */
public final class AgentRjpqMemberState {
    public enum MemberType { AGENT, HUMAN }

    private final int characterId;
    private final MemberType memberType;
    private long nextActionAtMs;
    private int triedUnitRow = -1;
    private int triedUnitColumn = -1;
    private int unitRow = -1;
    private long unitRowSinceMs;
    private boolean unitRefreshed;
    private final Map<Integer, Integer> claimTries = new HashMap<>();
    private final Set<Integer> claimsSkipped = new HashSet<>();

    AgentRjpqMemberState(int characterId, MemberType memberType) {
        if (characterId <= 0 || memberType == null) {
            throw new IllegalArgumentException("valid RJPQ member required");
        }
        this.characterId = characterId;
        this.memberType = memberType;
    }

    public int characterId() {
        return characterId;
    }

    public MemberType memberType() {
        return memberType;
    }

    public boolean agent() {
        return memberType == MemberType.AGENT;
    }

    long nextActionAtMs() {
        return nextActionAtMs;
    }

    void deferUntil(long atMs) {
        nextActionAtMs = atMs;
    }

    /** The Lab Unit portal this member just took (row, column), until its next tick reads where it landed. */
    void tryUnitPortal(int row, int column) {
        triedUnitRow = row;
        triedUnitColumn = column;
    }

    int triedUnitRow() {
        return triedUnitRow;
    }

    int triedUnitColumn() {
        return triedUnitColumn;
    }

    void clearUnitTry() {
        triedUnitRow = -1;
        triedUnitColumn = -1;
    }

    /** The Lab Unit row it stands on now; a change starts a new stuck spell. */
    void observeUnitRow(int row, long nowMs) {
        if (row != unitRow) {
            unitRow = row;
            unitRowSinceMs = nowMs;
            unitRefreshed = false;
        }
    }

    /** True once per stuck spell, when it has stood on the same row for {@code limitMs}. */
    boolean unitStuck(long nowMs, long limitMs) {
        if (unitRefreshed || unitRow < 0 || nowMs - unitRowSinceMs < limitMs) {
            return false;
        }
        unitRefreshed = true;
        return true;
    }

    /** Counts one more try at the claim NPC of this map; the count after it. */
    int claimTry(int mapId) {
        return claimTries.merge(mapId, 1, Integer::sum);
    }

    boolean claimSkipped(int mapId) {
        return claimsSkipped.contains(mapId);
    }

    void skipClaim(int mapId) {
        claimsSkipped.add(mapId);
    }
}
