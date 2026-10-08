package server.agents.capabilities.partyquest.rjpq;

/**
 * One RJPQ member. Its fields beyond the identity are owned by that member's own Agent tick (an Agent's tickMember);
 * nothing else reads or writes them. What other threads read lives in {@link AgentRjpqSession}.
 */
public final class AgentRjpqMemberState {
    public enum MemberType { AGENT, HUMAN }

    private final int characterId;
    private final MemberType memberType;
    private long nextActionAtMs;

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
}
