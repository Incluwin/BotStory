package server.agents.capabilities.partyquest.opq;

import java.awt.Point;

/** Per-member OPQ state; no room worker may mutate another member's action state. */
public final class AgentOpqMemberState {
    public enum MemberType { AGENT, HUMAN }
    public enum Role {
        LEADER, ENTRANCE_COLLECTOR, SEALED_PLATFORM, WALKWAY_COLLECTOR,
        STORAGE_RUNNER, LOBBY_RUNNER, LOUNGE_RUNNER, WAY_UP_RUNNER,
        PIECE_CARRIER, GARDEN_SEEDER, BOSS_ATTACKER, BOSS_SUMMON_CLEARER, IDLE
    }
    private final int characterId;
    private final MemberType memberType;
    private Role role = Role.IDLE;
    private AgentOpqDefinition.Room assignedRoom;
    private int assignedSubroomMapId;
    private int assignedPlatform;
    private long nextActionAtMs;
    private int committedReactorMapId;
    private int committedReactorObjectId;
    private Point lastPosition;
    private long lastMovementProgressAtMs;
    private int portalRow;
    private int portalChoice;
    private int loungeBaselineMapId;
    private int loungeBaselineCount;
    private int bossObjectId;
    private int bossHp = -1;
    private int rewardRefusals;
    private boolean rewardAskPending;
    private Point wayUpStagingPosition;
    private int wayUpStagingStillTicks;
    private boolean wayUpStagingSettled;

    public AgentOpqMemberState(int characterId, MemberType memberType) {
        if (characterId <= 0 || memberType == null) throw new IllegalArgumentException("valid OPQ member required");
        this.characterId = characterId;
        this.memberType = memberType;
    }

    public int characterId() { return characterId; }
    public MemberType memberType() { return memberType; }
    public Role role() { return role; }
    public AgentOpqDefinition.Room assignedRoom() { return assignedRoom; }
    public int assignedSubroomMapId() { return assignedSubroomMapId; }
    public int assignedPlatform() { return assignedPlatform; }
    public long nextActionAtMs() { return nextActionAtMs; }
    public int committedReactorMapId() { return committedReactorMapId; }
    public int committedReactorObjectId() { return committedReactorObjectId; }
    public int portalRow() { return portalRow; }
    public int portalChoice() { return portalChoice; }

    public void assign(Role role, AgentOpqDefinition.Room room, int subroomMapId) {
        if (role == null || subroomMapId < 0) throw new IllegalArgumentException("valid OPQ assignment required");
        if (assignedRoom != room || assignedSubroomMapId != subroomMapId) {
            clearLocalWork();
            // A new subroom counts its ten afresh. A stall refresh (clearLocalWork alone) keeps the count: re-taken at
            // the runner's current bag, a half-done subroom could never reach ten (2026-10-24, run 4 review).
            loungeBaselineMapId = 0;
            loungeBaselineCount = 0;
        }
        this.role = role;
        this.assignedRoom = room;
        this.assignedSubroomMapId = subroomMapId;
    }

    public void assignPlatform(int platform) {
        if (platform < 0 || platform > 2) throw new IllegalArgumentException("OPQ sealed platform must be 0-2");
        assignedPlatform = platform;
    }

    public void deferUntil(long timeMs) { nextActionAtMs = Math.max(0L, timeMs); }
    public void commitReactor(int mapId, int objectId) {
        if (mapId <= 0 || objectId <= 0) throw new IllegalArgumentException("valid OPQ reactor commitment required");
        committedReactorMapId = mapId;
        committedReactorObjectId = objectId;
    }
    public void clearReactor() { committedReactorMapId = 0; committedReactorObjectId = 0; }
    public void advancePortalChoice() { portalChoice++; }
    public void solvePortalRow() { portalRow++; portalChoice = 0; }
    public void beginLoungeCollection(int mapId, int currentCount) {
        if (loungeBaselineMapId != mapId) {
            loungeBaselineMapId = mapId;
            loungeBaselineCount = Math.max(0, currentCount);
        }
    }
    public int loungeCollected(int mapId, int currentCount) {
        return loungeBaselineMapId == mapId ? Math.max(0, currentCount - loungeBaselineCount) : 0;
    }

    public boolean observeBossCombat(int objectId, int hp) {
        if (objectId <= 0 || hp < 0) throw new IllegalArgumentException("valid OPQ boss observation required");
        boolean progress = bossObjectId != objectId || bossHp < 0 || hp < bossHp;
        bossObjectId = objectId;
        bossHp = hp;
        return progress;
    }

    public void clearBossCombat() { bossObjectId = 0; bossHp = -1; }

    public boolean observeMovement(Point position, long nowMs) {
        if (position == null || nowMs < 0L) return false;
        if (lastPosition == null || lastPosition.distanceSq(position) >= 16L * 16L) {
            lastPosition = new Point(position);
            lastMovementProgressAtMs = nowMs;
            return true;
        }
        return false;
    }
    public long movementStalledFor(long nowMs) {
        return lastMovementProgressAtMs == 0L ? 0L : Math.max(0L, nowMs - lastMovementProgressAtMs);
    }

    /**
     * Observes the way-up runner at its launch staging; true once it stood on the same spot for two ticks (one AI
     * tick's worth, in the background and in the foreground), and from then on until clearWayUpStaging (it left the
     * staging window).
     */
    public boolean settledAtWayUpStaging(Point position) {
        if (!wayUpStagingSettled) {
            wayUpStagingStillTicks = position.equals(wayUpStagingPosition) ? wayUpStagingStillTicks + 1 : 0;
            wayUpStagingPosition = new Point(position);
            wayUpStagingSettled = wayUpStagingStillTicks >= 2;
        }
        return wayUpStagingSettled;
    }
    public void clearWayUpStaging() {
        wayUpStagingPosition = null;
        wayUpStagingStillTicks = 0;
        wayUpStagingSettled = false;
    }

    public void clearLocalWork() {
        nextActionAtMs = 0L;
        clearReactor();
        lastPosition = null;
        lastMovementProgressAtMs = 0L;
        clearWayUpStaging();
        portalRow = 0;
        portalChoice = 0;
        clearBossCombat();
    }

    // 2026-10-18, a human-led run's hand-over of fragments to the leader (AgentOpqHandOver), as PPQ's medals go.
    private int handOverItemId;
    private long handOverStartedAtMs;
    private boolean handOverByEak;
    private int handOverRecollectCount;
    private int claimObjectId;
    private int heldBefore;

    /** A hand-over of {@code itemId} to the leader opens: {@code recollectCount} is its lineage's re-collects so far. */
    void startHandOver(int itemId, long nowMs, int recollectCount) {
        handOverItemId = itemId;
        handOverStartedAtMs = Math.max(1L, nowMs);
        handOverByEak = false;
        handOverRecollectCount = Math.max(0, recollectCount);
    }
    void endHandOver() {
        handOverItemId = 0;
        handOverStartedAtMs = 0L;
        handOverByEak = false;
        handOverRecollectCount = 0;
    }
    /** The walk to the leader timed out, or he cannot be reached: the stack goes by Eak instead. */
    void sendHandOverByEak() { handOverByEak = true; }
    int handOverItemId() { return handOverItemId; }
    long handOverStartedAtMs() { return handOverStartedAtMs; }
    boolean handOverByEak() { return handOverByEak; }
    int handOverRecollectCount() { return handOverRecollectCount; }

    /** This Agent set out to pick a stack up (back for the leader, or for the statue) while it held {@code held}. */
    void claimStack(int objectId, int held) {
        claimObjectId = objectId;
        heldBefore = Math.max(0, held);
    }
    void clearClaim() { claimObjectId = 0; }
    int claimObjectId() { return claimObjectId; }
    int heldBefore() { return heldBefore; }

    /** Counts one more refusal of this member's reward (still on the reward map after Minerva ran); the new count. */
    int recordRewardRefusal() { return ++rewardRefusals; }
    int rewardRefusals() { return rewardRefusals; }

    /** Minerva ran for this member and it was still on the reward map: the next check after its retry window judges it. */
    void markRewardAsked() { rewardAskPending = true; }
    void clearRewardAsk() { rewardAskPending = false; }
    boolean rewardAskPending() { return rewardAskPending; }
}
