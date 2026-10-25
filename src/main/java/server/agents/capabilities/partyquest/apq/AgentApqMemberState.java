package server.agents.capabilities.partyquest.apq;

/** Mutable work state owned by one APQ participant. */
public final class AgentApqMemberState {
    public enum MemberType { AGENT, HUMAN }

    private final int characterId;
    private final MemberType memberType;
    private final int gender;
    private long nextActionAtMs;
    private int committedReactorObjectId;
    private int handOverItemId;
    private long handOverStartedAtMs;
    private int handOverRecollects;

    AgentApqMemberState(int characterId, MemberType memberType, int gender) {
        if (characterId <= 0 || memberType == null) throw new IllegalArgumentException("valid APQ member required");
        this.characterId = characterId;
        this.memberType = memberType;
        this.gender = gender;
    }

    public int characterId() { return characterId; }

    public MemberType memberType() { return memberType; }

    /** The member's gender as gendergo.js reads it at admission: 0 a boy, 1 a girl. */
    public int gender() { return gender; }

    public synchronized long nextActionAtMs() { return nextActionAtMs; }

    public synchronized void deferUntil(long value) { nextActionAtMs = Math.max(0L, value); }

    /** The reactor (a stage-5 gate, a bonus box) this member works on; 0 for none. */
    public synchronized int committedReactorObjectId() { return committedReactorObjectId; }

    public synchronized void commitReactor(int id) { committedReactorObjectId = Math.max(0, id); }

    public synchronized void clearReactor() { committedReactorObjectId = 0; }

    /** A hand-over of {@code itemId} to the leader opens: {@code recollects} is its lineage's re-collects so far. */
    synchronized void startHandOver(int itemId, long nowMs, int recollects) {
        handOverItemId = itemId;
        handOverStartedAtMs = Math.max(1L, nowMs);
        handOverRecollects = Math.max(0, recollects);
    }

    synchronized void endHandOver() {
        handOverItemId = 0;
        handOverStartedAtMs = 0L;
        handOverRecollects = 0;
    }

    synchronized int handOverItemId() { return handOverItemId; }

    synchronized long handOverStartedAtMs() { return handOverStartedAtMs; }

    synchronized int handOverRecollects() { return handOverRecollects; }
}
