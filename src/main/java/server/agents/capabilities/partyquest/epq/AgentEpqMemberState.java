package server.agents.capabilities.partyquest.epq;

import java.util.HashSet;
import java.util.Set;

/** Mutable work state owned by exactly one EPQ participant. */
public final class AgentEpqMemberState {
    public enum MemberType { AGENT, HUMAN }
    private final int characterId;
    private final MemberType memberType;
    private long nextActionAtMs;
    private int committedObjectId;
    private int stageTwoTaggedObjectId;
    private int stageTwoBestTreeDistance = Integer.MAX_VALUE;
    private long stageTwoLastLureProgressAtMs;
    private boolean stageTwoReturningToTree;
    private boolean topUpActive;
    private int topUpMapId;
    private long nextTopUpAtMs;
    private int lastTopUpHp = -1;
    private int topUpDrinks;
    private int topUpSpentMapId;
    private boolean bossResting;
    private java.awt.Point bossRestPoint;
    private int handOverItemId;
    private long handOverStartedAtMs;
    private boolean handOverToNpc;
    private boolean handOverRecollect;
    private int handOverRecollectCount;
    private int heldBefore;
    private final Set<String> announcements = new HashSet<>();

    AgentEpqMemberState(int characterId, MemberType memberType) {
        if (characterId <= 0 || memberType == null) throw new IllegalArgumentException("valid EPQ member required");
        this.characterId = characterId;
        this.memberType = memberType;
    }

    public int characterId() { return characterId; }
    public MemberType memberType() { return memberType; }
    public long nextActionAtMs() { return nextActionAtMs; }
    public int committedObjectId() { return committedObjectId; }
    public void deferUntil(long value) { nextActionAtMs = Math.max(0L, value); }
    public void commitObject(int value) { committedObjectId = Math.max(0, value); }
    public void clearObject() { committedObjectId = 0; }
    public int stageTwoTaggedObjectId() { return stageTwoTaggedObjectId; }
    public void tagStageTwoObject(int objectId) {
        stageTwoTaggedObjectId = Math.max(0, objectId);
    }
    public void tagStageTwoObject(int objectId, int treeDistance, long nowMs) {
        stageTwoTaggedObjectId = Math.max(0, objectId);
        stageTwoBestTreeDistance = Math.max(0, treeDistance);
        stageTwoLastLureProgressAtMs = Math.max(0L, nowMs);
    }
    public void observeStageTwoLureProgress(int treeDistance, long nowMs, int threshold) {
        int distance = Math.max(0, treeDistance);
        if (distance + Math.max(1, threshold) < stageTwoBestTreeDistance) {
            stageTwoBestTreeDistance = distance;
            stageTwoLastLureProgressAtMs = Math.max(0L, nowMs);
        }
    }
    public boolean stageTwoLureStalled(long nowMs, long timeoutMs) {
        return stageTwoTaggedObjectId > 0 && stageTwoLastLureProgressAtMs > 0L
                && nowMs - stageTwoLastLureProgressAtMs >= Math.max(1L, timeoutMs);
    }
    public void clearStageTwoTag() {
        stageTwoTaggedObjectId = 0;
        stageTwoBestTreeDistance = Integer.MAX_VALUE;
        stageTwoLastLureProgressAtMs = 0L;
    }
    public boolean stageTwoReturningToTree() { return stageTwoReturningToTree; }
    public void beginStageTwoTreeReturn() { stageTwoReturningToTree = true; }
    public void finishStageTwoTreeReturn() { stageTwoReturningToTree = false; }
    public boolean claimAnnouncement(String key) {
        return key != null && !key.isBlank() && announcements.add(key);
    }

    // The HP top-up episode (EPQ base fix 6), owned by this member's own tick.

    /** Whether a top-up episode is open. */
    public boolean topUpActive() { return topUpActive; }

    /** The map the open (or last) episode runs on. */
    public int topUpMapId() { return topUpMapId; }

    /** When the next drink of the open episode may be taken. */
    public long nextTopUpAtMs() { return nextTopUpAtMs; }

    /** The HP seen at the episode's last drink, or -1 before its first. */
    public int lastTopUpHp() { return lastTopUpHp; }

    /** The drinks the episode has taken. */
    public int topUpDrinks() { return topUpDrinks; }

    /** Opens an episode on this map. */
    void startTopUp(int mapId) {
        topUpActive = true;
        topUpMapId = mapId;
        nextTopUpAtMs = 0L;
        lastTopUpHp = -1;
        topUpDrinks = 0;
    }

    /** One drink tried at {@code hp}; the next may follow at {@code nextAtMs}. */
    void recordTopUpDrink(int hp, long nextAtMs, boolean drank) {
        lastTopUpHp = hp;
        nextTopUpAtMs = Math.max(0L, nextAtMs);
        if (drank) topUpDrinks++;
    }

    /**
     * Closes the open episode; a spent one (its drinks raised no HP, or no potion could be chosen) also ends the
     * top-ups on its map for this member, so it never drinks without end.
     */
    void endTopUp(boolean spent) {
        topUpActive = false;
        if (spent) topUpSpentMapId = topUpMapId;
    }

    /** Whether an episode on this map was spent. */
    boolean topUpSpentOn(int mapId) { return topUpSpentMapId != 0 && topUpSpentMapId == mapId; }

    // The golem-fight rest (EPQ base fix 7), owned by this member's own tick.

    /** Whether this member rests out of the golem's reach. */
    public boolean bossResting() { return bossResting; }

    /** Where it rests, or null. */
    public java.awt.Point bossRestPoint() { return bossRestPoint == null ? null : new java.awt.Point(bossRestPoint); }

    /** Rests at this point. */
    void restAt(java.awt.Point point) {
        bossResting = true;
        bossRestPoint = point == null ? null : new java.awt.Point(point);
    }

    /** Back in the fight, the event cleared, or the member left the golem's map. */
    void clearBossRest() {
        bossResting = false;
        bossRestPoint = null;
    }

    // A companion run's hand-over at the leader's feet (Step 2.7), owned by this member's own tick.

    /** The item of the open hand-over, or 0. */
    int handOverItemId() { return handOverItemId; }

    /** When the open hand-over began, or 0 when none is open. */
    long handOverStartedAtMs() { return handOverStartedAtMs; }

    /** Whether the open hand-over goes to the stage's NPC (the leader could not be reached in time). */
    boolean handOverToNpc() { return handOverToNpc; }

    /** Whether the open hand-over carries a stack picked back up from the floor. */
    boolean handOverRecollect() { return handOverRecollect; }

    /** How often the carried stack's lineage was picked back up, this time included; 0 for a fresh stack. */
    int handOverRecollectCount() { return handOverRecollectCount; }

    /** What it held as it set out to pick a stack back up. */
    int heldBefore() { return heldBefore; }

    void startHandOver(int itemId, long nowMs, boolean recollect, int recollectCount) {
        handOverItemId = itemId;
        handOverStartedAtMs = Math.max(1L, nowMs);
        handOverToNpc = false;
        handOverRecollect = recollect;
        handOverRecollectCount = Math.max(0, recollectCount);
    }

    void sendHandOverToNpc() { handOverToNpc = true; }

    void endHandOver() {
        handOverItemId = 0;
        handOverStartedAtMs = 0L;
        handOverToNpc = false;
        handOverRecollect = false;
        handOverRecollectCount = 0;
    }

    void setHeldBefore(int held) { heldBefore = Math.max(0, held); }
}
