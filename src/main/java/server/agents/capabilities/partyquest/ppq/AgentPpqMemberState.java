package server.agents.capabilities.partyquest.ppq;

import java.util.HashSet;
import java.util.Set;

/** Mutable work state owned by one PPQ participant. */
public final class AgentPpqMemberState {
    public enum MemberType { AGENT, HUMAN }
    private final int characterId;
    private final MemberType memberType;
    private long nextActionAtMs;
    private int committedReactorObjectId;
    private int pendingChestMapId;
    private final Set<String> announcements = new HashSet<>();
    private int handOverItemId;
    private long handOverStartedAtMs;
    private boolean handOverToNpc;
    private int handOverRecollectCount;
    private int recollectObjectId;
    private int heldBefore;

    AgentPpqMemberState(int characterId, MemberType memberType) {
        if (characterId <= 0 || memberType == null) throw new IllegalArgumentException("valid PPQ member required");
        this.characterId = characterId;
        this.memberType = memberType;
    }
    public int characterId() { return characterId; }
    public MemberType memberType() { return memberType; }
    public synchronized long nextActionAtMs() { return nextActionAtMs; }
    public synchronized void deferUntil(long value) { nextActionAtMs = Math.max(0L, value); }
    public synchronized int committedReactorObjectId() { return committedReactorObjectId; }
    public synchronized void commitReactor(int id) { committedReactorObjectId = Math.max(0, id); }
    public synchronized void clearReactor() { committedReactorObjectId = 0; }
    public synchronized boolean chestDropPending(int mapId) { return pendingChestMapId == mapId; }
    public synchronized void beginChestDrop(int mapId) { pendingChestMapId = mapId; }
    public synchronized void clearChestDrop() { pendingChestMapId = 0; }
    public synchronized boolean claimAnnouncement(String key) { return announcements.add(key); }

    /** A medal hand-over to the leader opens: {@code recollectCount} is its lineage's re-collects so far. */
    synchronized void startHandOver(int itemId, long nowMs, int recollectCount) {
        handOverItemId = itemId;
        handOverStartedAtMs = Math.max(1L, nowMs);
        handOverToNpc = false;
        handOverRecollectCount = Math.max(0, recollectCount);
    }
    synchronized void endHandOver() {
        handOverItemId = 0;
        handOverStartedAtMs = 0L;
        handOverToNpc = false;
        handOverRecollectCount = 0;
    }
    /** The walk to the leader timed out, or he cannot be reached: the medals go by Guon instead. */
    synchronized void sendHandOverToNpc() { handOverToNpc = true; }
    synchronized int handOverItemId() { return handOverItemId; }
    synchronized long handOverStartedAtMs() { return handOverStartedAtMs; }
    synchronized boolean handOverToNpc() { return handOverToNpc; }
    synchronized int handOverRecollectCount() { return handOverRecollectCount; }

    /** This Agent set out to pick a medal stack back up while it held {@code held} medals of that kind. */
    synchronized void claimRecollect(int objectId, int held) {
        recollectObjectId = objectId;
        heldBefore = Math.max(0, held);
    }
    synchronized void clearRecollect() { recollectObjectId = 0; }
    synchronized int recollectObjectId() { return recollectObjectId; }
    synchronized int heldBefore() { return heldBefore; }
}
