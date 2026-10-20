package server.agents.capabilities.partyquest.lmpq;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Mutable LMPQ work state owned by one participant. */
public final class AgentLmpqMemberState {
    public enum MemberType { AGENT, HUMAN }
    private final int characterId;
    private final MemberType memberType;
    private int targetRoom;
    private int initialRoom;
    private int lastObservedRoom;
    private final List<Integer> route = new ArrayList<>();
    private final List<Integer> assignments = new ArrayList<>();
    private int committedReactorObjectId;
    private int markedSourceRoom;
    private int markedPortalId;
    private long nextActionAtMs;
    private int rewardAttempts;
    private final Set<String> announcements = new HashSet<>();

    AgentLmpqMemberState(int characterId, MemberType memberType) {
        if (characterId <= 0 || memberType == null) throw new IllegalArgumentException("valid LMPQ member required");
        this.characterId = characterId;
        this.memberType = memberType;
    }

    public int characterId() { return characterId; }
    public MemberType memberType() { return memberType; }
    public synchronized int targetRoom() { return targetRoom; }
    public synchronized void assignTargetRoom(int room) {
        targetRoom = room;
        if (room > 0 && (assignments.isEmpty() || assignments.get(assignments.size() - 1) != room)) {
            assignments.add(room);
        }
    }
    public synchronized void clearTargetRoom() { targetRoom = 0; committedReactorObjectId = 0; }
    public synchronized int committedReactorObjectId() { return committedReactorObjectId; }
    public synchronized void commitReactor(int objectId) { committedReactorObjectId = Math.max(0, objectId); }
    public synchronized void clearReactor() { committedReactorObjectId = 0; }
    public synchronized boolean portalMarked(int sourceRoom, int portalId) {
        return markedSourceRoom == sourceRoom && markedPortalId == portalId;
    }
    public synchronized void markPortal(int sourceRoom, int portalId) {
        markedSourceRoom = sourceRoom; markedPortalId = portalId;
    }
    public synchronized void clearPortalMarker() { markedSourceRoom = 0; markedPortalId = 0; }
    public synchronized long nextActionAtMs() { return nextActionAtMs; }
    public synchronized void deferUntil(long value) { nextActionAtMs = Math.max(0L, value); }
    public synchronized boolean claimAnnouncement(String key) {
        return key != null && !key.isBlank() && announcements.add(key);
    }
    public synchronized void observeRoom(int room) {
        if (room <= 0 || room == lastObservedRoom) return;
        if (initialRoom == 0) initialRoom = room;
        lastObservedRoom = room;
        route.add(room);
    }
    /** The claims this Agent made at the reward Rolly while it stayed on the reward map. */
    public synchronized int rewardAttempts() { return rewardAttempts; }
    public synchronized void noteRewardAttempt() { rewardAttempts++; }
    // ---- companion run (P6h Step 2) -------------------------------------------------------------------------------
    private long handOverStartedAtMs;
    private int handOverRecollectCount;
    private int claimObjectId;
    private int heldBefore;
    private boolean resting;
    private final Set<Integer> avoidedRooms = new HashSet<>();

    /** Starts a hand-over of this companion's coupons to the leader ({@code recollectCount}: the lineage's re-collects). */
    synchronized void startHandOver(long nowMs, int recollectCount) {
        handOverStartedAtMs = Math.max(1L, nowMs);
        handOverRecollectCount = Math.max(0, recollectCount);
    }

    synchronized void endHandOver() {
        handOverStartedAtMs = 0L;
        handOverRecollectCount = 0;
    }

    synchronized long handOverStartedAtMs() { return handOverStartedAtMs; }
    synchronized int handOverRecollectCount() { return handOverRecollectCount; }

    /** Sets out to pick a stack back up, holding {@code held} coupons before it. */
    synchronized void claimStack(int objectId, int held) {
        claimObjectId = objectId;
        heldBefore = Math.max(0, held);
    }

    synchronized void clearClaim() { claimObjectId = 0; }
    synchronized int claimObjectId() { return claimObjectId; }
    synchronized int heldBefore() { return heldBefore; }

    /** Whether this companion rests out of potions now. */
    synchronized boolean resting() { return resting; }
    synchronized void setResting(boolean value) { resting = value; }

    /** A room this companion keeps out of (a Tauromacis loose in it, above its level); true the first time. */
    synchronized boolean avoidRoom(int room) { return room > 0 && avoidedRooms.add(room); }
    synchronized boolean avoids(int room) { return avoidedRooms.contains(room); }

    public synchronized int initialRoom() { return initialRoom; }
    public synchronized List<Integer> route() { return List.copyOf(route); }
    public synchronized List<Integer> assignments() { return List.copyOf(assignments); }
}
