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
}
