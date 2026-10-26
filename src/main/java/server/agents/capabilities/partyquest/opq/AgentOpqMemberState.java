package server.agents.capabilities.partyquest.opq;

import java.awt.Point;

/**
 * Per-member OPQ state; no room worker may mutate another member's action state. Every access is synchronized: the
 * coordinator pass (under the session lock, on the lease holder's tick) assigns rooms and clears local work while the
 * member's own tick observes movement and commits reactors, and in central-sharded mode those are two threads
 * (docs/SCALE_PLATFORM_PLAN.md section 6, race H2).
 */
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

    public synchronized int characterId() { return characterId; }
    public synchronized MemberType memberType() { return memberType; }
    public synchronized Role role() { return role; }
    public synchronized AgentOpqDefinition.Room assignedRoom() { return assignedRoom; }
    public synchronized int assignedSubroomMapId() { return assignedSubroomMapId; }
    public synchronized int assignedPlatform() { return assignedPlatform; }
    public synchronized long nextActionAtMs() { return nextActionAtMs; }
    public synchronized int committedReactorMapId() { return committedReactorMapId; }
    public synchronized int committedReactorObjectId() { return committedReactorObjectId; }
    public synchronized int portalRow() { return portalRow; }
    public synchronized int portalChoice() { return portalChoice; }

    public synchronized void assign(Role role, AgentOpqDefinition.Room room, int subroomMapId) {
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

    public synchronized void assignPlatform(int platform) {
        if (platform < 0 || platform > 2) throw new IllegalArgumentException("OPQ sealed platform must be 0-2");
        assignedPlatform = platform;
    }

    public synchronized void deferUntil(long timeMs) { nextActionAtMs = Math.max(0L, timeMs); }
    public synchronized void commitReactor(int mapId, int objectId) {
        if (mapId <= 0 || objectId <= 0) throw new IllegalArgumentException("valid OPQ reactor commitment required");
        committedReactorMapId = mapId;
        committedReactorObjectId = objectId;
    }
    public synchronized void clearReactor() { committedReactorMapId = 0; committedReactorObjectId = 0; }
    public synchronized void advancePortalChoice() { portalChoice++; }
    public synchronized void solvePortalRow() { portalRow++; portalChoice = 0; }
    public synchronized void beginLoungeCollection(int mapId, int currentCount) {
        if (loungeBaselineMapId != mapId) {
            loungeBaselineMapId = mapId;
            loungeBaselineCount = Math.max(0, currentCount);
        }
    }
    public synchronized int loungeCollected(int mapId, int currentCount) {
        return loungeBaselineMapId == mapId ? Math.max(0, currentCount - loungeBaselineCount) : 0;
    }

    public synchronized boolean observeBossCombat(int objectId, int hp) {
        if (objectId <= 0 || hp < 0) throw new IllegalArgumentException("valid OPQ boss observation required");
        boolean progress = bossObjectId != objectId || bossHp < 0 || hp < bossHp;
        bossObjectId = objectId;
        bossHp = hp;
        return progress;
    }

    public synchronized void clearBossCombat() { bossObjectId = 0; bossHp = -1; }

    public synchronized boolean observeMovement(Point position, long nowMs) {
        if (position == null || nowMs < 0L) return false;
        if (lastPosition == null || lastPosition.distanceSq(position) >= 16L * 16L) {
            lastPosition = new Point(position);
            lastMovementProgressAtMs = nowMs;
            return true;
        }
        return false;
    }
    public synchronized long movementStalledFor(long nowMs) {
        return lastMovementProgressAtMs == 0L ? 0L : Math.max(0L, nowMs - lastMovementProgressAtMs);
    }

    /**
     * Observes the way-up runner at its launch staging; true once it stood on the same spot for two ticks (one AI
     * tick's worth, in the background and in the foreground), and from then on until clearWayUpStaging (it left the
     * staging window).
     */
    public synchronized boolean settledAtWayUpStaging(Point position) {
        if (!wayUpStagingSettled) {
            wayUpStagingStillTicks = position.equals(wayUpStagingPosition) ? wayUpStagingStillTicks + 1 : 0;
            wayUpStagingPosition = new Point(position);
            wayUpStagingSettled = wayUpStagingStillTicks >= 2;
        }
        return wayUpStagingSettled;
    }
    public synchronized void clearWayUpStaging() {
        wayUpStagingPosition = null;
        wayUpStagingStillTicks = 0;
        wayUpStagingSettled = false;
    }

    public synchronized void clearLocalWork() {
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
    synchronized void startHandOver(int itemId, long nowMs, int recollectCount) {
        handOverItemId = itemId;
        handOverStartedAtMs = Math.max(1L, nowMs);
        handOverByEak = false;
        handOverRecollectCount = Math.max(0, recollectCount);
    }
    synchronized void endHandOver() {
        handOverItemId = 0;
        handOverStartedAtMs = 0L;
        handOverByEak = false;
        handOverRecollectCount = 0;
    }
    /** The walk to the leader timed out, or he cannot be reached: the stack goes by Eak instead. */
    synchronized void sendHandOverByEak() { handOverByEak = true; }
    synchronized int handOverItemId() { return handOverItemId; }
    synchronized long handOverStartedAtMs() { return handOverStartedAtMs; }
    synchronized boolean handOverByEak() { return handOverByEak; }
    synchronized int handOverRecollectCount() { return handOverRecollectCount; }

    /** This Agent set out to pick a stack up (back for the leader, or for the statue) while it held {@code held}. */
    synchronized void claimStack(int objectId, int held) {
        claimObjectId = objectId;
        heldBefore = Math.max(0, held);
    }
    synchronized void clearClaim() { claimObjectId = 0; }
    synchronized int claimObjectId() { return claimObjectId; }
    synchronized int heldBefore() { return heldBefore; }

    /** Counts one more refusal of this member's reward (still on the reward map after Minerva ran); the new count. */
    synchronized int recordRewardRefusal() { return ++rewardRefusals; }
    synchronized int rewardRefusals() { return rewardRefusals; }

    /** Minerva ran for this member and it was still on the reward map: the next check after its retry window judges it. */
    synchronized void markRewardAsked() { rewardAskPending = true; }
    synchronized void clearRewardAsk() { rewardAskPending = false; }
    synchronized boolean rewardAskPending() { return rewardAskPending; }
}
