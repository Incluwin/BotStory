package server.agents.capabilities.partyquest.kpq;

import java.awt.Point;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Mutable per-member state owned by one party-level KPQ session. */
public final class AgentKpqMemberState {
    public enum MemberType { AGENT, HUMAN }
    public enum Role {
        EVENT_LEADER, COUPON_COLLECTOR, PASS_DELIVERER, COMBAT_HELPER,
        PUZZLE_PARTICIPANT, STAGE5_PASS_COLLECTOR, STAGE5_PASS_CARRIER, SQUISHY_SHOES_COLLECTOR, WAITING
    }
    public enum RewardState { PENDING, CLAIMING, CLAIMED, FORFEITED }

    private final int characterId;
    private final MemberType memberType;
    private final int partyNumber;
    private Role role = Role.WAITING;
    private int couponTarget = -1;
    private boolean questionRequested;
    private boolean passCreated;
    private boolean passDelivered;
    private RewardState rewardState = RewardState.PENDING;
    private int assignedPosition;
    private long stableSinceMs;
    private long actionNotBeforeMs;
    private int couponMilestone = 0;
    private int reportedPassCount;
    private int fidgetedAttemptId = -1;
    private Point fidgetTarget;
    private long fidgetUntilMs;
    private String blocker = "";
    private long blockerSinceMs;
    private int blockerAttempts;
    private boolean blockerRecoveryAttempted;
    private long nextRetryAtMs;
    private int enteredStage;
    private long stageMovementNotBeforeMs;
    private long stage5BossBaselineAttacks = -1L;
    private long stage5BossBaselineHitLines = -1L;
    private long stage5BossBaselineMissLines = -1L;
    private long stage5BossBaselineDamage = -1L;
    private boolean stage1RopeObserved;
    private boolean stage1DescendingRopeObserved;
    private int stage1LastRopeY;
    private Point stage1LandingSafetyTarget;
    private long stage1LandingSafetyDeadlineMs;
    private boolean stage1ManagedDarkSight;
    private AgentKpqSession.HelperTerritory stage1ClusterTerritory;
    private List<Point> stage1FiringArea;
    private Point stage1FiringSpot;
    private long downSinceMs;
    private boolean tapOutRequested;
    private long revivedSeenAtMs;
    private CombatCounts stage1CombatBaseline = new CombatCounts(0L, 0L, 0L, 0L);
    private final EnumMap<Role, Long> stage1RoleMs = new EnumMap<>(Role.class);
    private long stage1RoleTalliedAtMs;
    private int stage1CouponsHeld;
    private int stage1CouponsLooted;
    private long stage1PassAtMs;
    private long stage1PassChaseFromMs;
    private Role stage1CombatRole;
    private CombatCounts stage1RoleCombatBaseline;
    private final EnumMap<Role, CombatCounts> stage1RoleCombat = new EnumMap<>(Role.class);
    private final Map<String, Long> stage1FootingMs = new TreeMap<>();
    private final Map<String, Long> stage1SimulationMs = new TreeMap<>();
    private long stage1SampledAtMs;
    private Map<String, Long> stage1PostureBaseline = Map.of();
    private Map<Integer, Long> stage1SkillBaseline = Map.of();
    private long stage1DegenerateBaseline;
    private long stage5ChaseFromMs;
    private boolean stage5LeaderUnreachable;
    private boolean stage5MeetsLeaderAtCloto;
    private long stage5LeaderReachableSinceMs;
    private boolean stage5PassesLeftBehind;
    private boolean stage5DroppedOnLeadersSpot;
    private int stage5PassesSeen;
    private boolean fullRateHeld;
    private boolean fullRateWasRequired;

    public AgentKpqMemberState(int characterId, MemberType memberType, int partyNumber) {
        this.characterId = characterId;
        this.memberType = memberType;
        this.partyNumber = partyNumber;
    }

    public int characterId() { return characterId; }
    public MemberType memberType() { return memberType; }
    public int partyNumber() { return partyNumber; }
    public Role role() { return role; }
    public void setRole(Role role) { this.role = role; }
    public int couponTarget() { return couponTarget; }
    public void setCouponTarget(int couponTarget) { this.couponTarget = couponTarget; }
    public boolean questionRequested() { return questionRequested; }
    public void markQuestionRequested() { questionRequested = true; }
    public boolean passCreated() { return passCreated; }
    public void markPassCreated() { passCreated = true; }
    /** As {@link #markPassCreated()}, and the first time it is seen is when stage 1's summary says the pass came. */
    void markPassCreated(long nowMs) {
        if (!passCreated) stage1PassAtMs = nowMs;
        passCreated = true;
    }
    public boolean passDelivered() { return passDelivered; }
    public void markPassDelivered() { passDelivered = true; }

    /**
     * Whether its stage-1 delivery to the leader has run for {@code boundMs}; the first call, as it sets out, starts
     * that clock.
     */
    boolean stage1PassChaseOverdue(long nowMs, long boundMs) {
        if (stage1PassChaseFromMs == 0L) {
            stage1PassChaseFromMs = Math.max(1L, nowMs);
            return false;
        }
        return nowMs - stage1PassChaseFromMs >= boundMs;
    }
    public RewardState rewardState() { return rewardState; }
    public boolean rewardClaimed() { return rewardState == RewardState.CLAIMED; }
    public boolean rewardResolved() {
        return rewardState == RewardState.CLAIMED || rewardState == RewardState.FORFEITED;
    }
    boolean beginRewardClaim() {
        if (rewardState != RewardState.PENDING) return false;
        rewardState = RewardState.CLAIMING;
        return true;
    }
    boolean completeRewardClaim() {
        if (rewardState != RewardState.CLAIMING) return false;
        rewardState = RewardState.CLAIMED;
        return true;
    }
    void cancelRewardClaim() {
        if (rewardState == RewardState.CLAIMING) rewardState = RewardState.PENDING;
    }
    void forfeitReward() {
        if (rewardState == RewardState.PENDING) rewardState = RewardState.FORFEITED;
    }
    public void markRewardClaimed() {
        if (rewardState == RewardState.PENDING || rewardState == RewardState.CLAIMING) {
            rewardState = RewardState.CLAIMED;
        }
    }
    public int assignedPosition() { return assignedPosition; }
    public void setAssignedPosition(int assignedPosition) {
        if (this.assignedPosition != assignedPosition) stableSinceMs = 0L;
        this.assignedPosition = assignedPosition;
    }
    public long stableSinceMs() { return stableSinceMs; }
    public void setStableSinceMs(long stableSinceMs) { this.stableSinceMs = stableSinceMs; }
    public long actionNotBeforeMs() { return actionNotBeforeMs; }
    public void setActionNotBeforeMs(long actionNotBeforeMs) { this.actionNotBeforeMs = actionNotBeforeMs; }
    public int couponMilestone() { return couponMilestone; }
    public void setCouponMilestone(int couponMilestone) { this.couponMilestone = Math.max(0, Math.min(100, couponMilestone)); }
    public int reportedPassCount() { return reportedPassCount; }
    public void setReportedPassCount(int reportedPassCount) { this.reportedPassCount = Math.max(0, reportedPassCount); }
    public int fidgetedAttemptId() { return fidgetedAttemptId; }
    public void setFidgetedAttemptId(int attemptId) { fidgetedAttemptId = attemptId; }
    public Point fidgetTarget() { return fidgetTarget == null ? null : new Point(fidgetTarget); }
    public long fidgetUntilMs() { return fidgetUntilMs; }
    public void beginFidget(Point target, long untilMs) {
        fidgetTarget = target == null ? null : new Point(target);
        fidgetUntilMs = target == null ? 0L : Math.max(0L, untilMs);
    }
    public void clearFidget() {
        fidgetTarget = null;
        fidgetUntilMs = 0L;
    }
    public String blocker() { return blocker; }
    public void setBlocker(String blocker) { this.blocker = blocker == null ? "" : blocker; }
    public long blockerSinceMs() { return blockerSinceMs; }
    public int blockerAttempts() { return blockerAttempts; }
    public int observeBlocker(String reason, long nowMs) {
        String next = reason == null ? "unknown" : reason;
        if (!next.equals(blocker)) {
            blocker = next;
            blockerSinceMs = nowMs;
            blockerAttempts = 1;
            blockerRecoveryAttempted = false;
        } else {
            blockerAttempts++;
        }
        return blockerAttempts;
    }
    public boolean blockerRecoveryAttempted() { return blockerRecoveryAttempted; }
    public void markBlockerRecoveryAttempted() { blockerRecoveryAttempted = true; }
    public void clearBlocker() {
        blocker = "";
        blockerSinceMs = 0L;
        blockerAttempts = 0;
        blockerRecoveryAttempted = false;
        nextRetryAtMs = 0L;
    }
    public long nextRetryAtMs() { return nextRetryAtMs; }
    public void setNextRetryAtMs(long value) { nextRetryAtMs = Math.max(0L, value); }
    public int enteredStage() { return enteredStage; }
    public long stageMovementNotBeforeMs() { return stageMovementNotBeforeMs; }
    public void beginStageMovement(int stage, long notBeforeMs) {
        if (enteredStage == stage) return;
        enteredStage = stage;
        stageMovementNotBeforeMs = Math.max(0L, notBeforeMs);
    }

    /** Marks this member down from its first sighting; a down sighting also breaks any revive streak. */
    void markDown(long nowMs) {
        if (downSinceMs == 0L) downSinceMs = Math.max(1L, nowMs);
        revivedSeenAtMs = 0L;
    }
    void clearDown() {
        downSinceMs = 0L;
        tapOutRequested = false;
        revivedSeenAtMs = 0L;
    }
    long downSinceMs() { return downSinceMs; }
    boolean down() { return downSinceMs > 0L; }
    boolean tapOutRequested() { return tapOutRequested; }
    void markTapOutRequested() { tapOutRequested = true; }
    /**
     * True from the second tick in a row that sees this downed member standing in the event again. The respawn
     * restores HP before it changes map, so a single sighting can be a companion on its way out.
     */
    boolean confirmRevived(long nowMs) {
        if (revivedSeenAtMs > 0L) return true;
        revivedSeenAtMs = Math.max(1L, nowMs);
        return false;
    }

    boolean stage1RopeObserved() { return stage1RopeObserved; }
    boolean stage1DescendingRopeObserved() { return stage1DescendingRopeObserved; }
    int stage1LastRopeY() { return stage1LastRopeY; }
    void observeStage1Rope(int y, boolean descending) {
        stage1RopeObserved = true;
        stage1DescendingRopeObserved |= descending;
        stage1LastRopeY = y;
    }
    void clearStage1RopeObservation() {
        stage1RopeObserved = false;
        stage1DescendingRopeObserved = false;
        stage1LastRopeY = 0;
    }
    Point stage1LandingSafetyTarget() {
        return stage1LandingSafetyTarget == null ? null : new Point(stage1LandingSafetyTarget);
    }
    long stage1LandingSafetyDeadlineMs() { return stage1LandingSafetyDeadlineMs; }
    boolean stage1ManagedDarkSight() { return stage1ManagedDarkSight; }
    void beginStage1LandingSafety(Point target, long deadlineMs, boolean managedDarkSight) {
        stage1LandingSafetyTarget = target == null ? null : new Point(target);
        stage1LandingSafetyDeadlineMs = target == null ? 0L : Math.max(0L, deadlineMs);
        stage1ManagedDarkSight = target != null && managedDarkSight;
    }
    void clearStage1LandingSafety() {
        stage1LandingSafetyTarget = null;
        stage1LandingSafetyDeadlineMs = 0L;
        stage1ManagedDarkSight = false;
    }
    /** The cluster territory this collector camps in stage 1, kept equal until it expires. */
    AgentKpqSession.HelperTerritory stage1ClusterTerritory() { return stage1ClusterTerritory; }
    void setStage1ClusterTerritory(AgentKpqSession.HelperTerritory territory) { stage1ClusterTerritory = territory; }
    /** Whether the firing spot of this area is worked out already (it may be none). */
    boolean stage1FiringSpotKnownFor(List<Point> area) { return area != null && area.equals(stage1FiringArea); }
    Point stage1FiringSpot() { return stage1FiringSpot == null ? null : new Point(stage1FiringSpot); }
    void setStage1FiringSpot(List<Point> area, Point spot) {
        stage1FiringArea = area == null ? null : List.copyOf(area);
        stage1FiringSpot = spot == null ? null : new Point(spot);
    }

    public boolean hasStage5BossCombatBaseline() {
        return stage5BossBaselineAttacks >= 0L;
    }

    public void beginStage5BossCombat(long attacks, long hitLines, long missLines, long damage) {
        if (hasStage5BossCombatBaseline()) return;
        stage5BossBaselineAttacks = Math.max(0L, attacks);
        stage5BossBaselineHitLines = Math.max(0L, hitLines);
        stage5BossBaselineMissLines = Math.max(0L, missLines);
        stage5BossBaselineDamage = Math.max(0L, damage);
    }

    public BossCombatDelta stage5BossCombatDelta(
            long attacks, long hitLines, long missLines, long damage) {
        if (!hasStage5BossCombatBaseline()) return new BossCombatDelta(0L, 0L, 0L, 0L);
        return new BossCombatDelta(
                delta(attacks, stage5BossBaselineAttacks),
                delta(hitLines, stage5BossBaselineHitLines),
                delta(missLines, stage5BossBaselineMissLines),
                delta(damage, stage5BossBaselineDamage));
    }

    public void resetStage5BossCombat() {
        stage5BossBaselineAttacks = -1L;
        stage5BossBaselineHitLines = -1L;
        stage5BossBaselineMissLines = -1L;
        stage5BossBaselineDamage = -1L;
    }

    private static long delta(long value, long baseline) {
        return Math.max(0L, value - baseline);
    }

    public record BossCombatDelta(long attacks, long hitLines, long missLines, long damage) {
    }

    /** Stage 1 starts here: its summary counts from these totals and from this time. */
    void beginStage1Tally(CombatCounts baseline, long nowMs) {
        stage1CombatBaseline = baseline;
        stage1RoleTalliedAtMs = nowMs;
        stage1CombatRole = role;
        stage1RoleCombatBaseline = baseline;
        stage1SampledAtMs = nowMs;
    }

    /** And how it fought until then: milliseconds per combat posture, attacks per skill, degenerate swings. */
    void beginStage1Profile(Map<String, Long> postureMs, Map<Integer, Long> skillAttacks, long degenerateAttacks) {
        stage1PostureBaseline = Map.copyOf(postureMs);
        stage1SkillBaseline = Map.copyOf(skillAttacks);
        stage1DegenerateBaseline = degenerateAttacks;
    }

    /** Whether the role changed since its kills and attacks were last put down to one. */
    boolean stage1CombatRoleChanged() {
        return stage1RoleCombatBaseline != null && stage1CombatRole != role;
    }

    /** What it killed and swung since the last role change goes to the role it held, and the current role starts. */
    void closeStage1RoleCombat(CombatCounts now) {
        if (stage1RoleCombatBaseline == null || now == null) return;
        CombatCounts done = new CombatCounts(delta(now.kills(), stage1RoleCombatBaseline.kills()),
                delta(now.attacks(), stage1RoleCombatBaseline.attacks()),
                delta(now.hitLines(), stage1RoleCombatBaseline.hitLines()),
                delta(now.missLines(), stage1RoleCombatBaseline.missLines()));
        if (stage1CombatRole != null) stage1RoleCombat.merge(stage1CombatRole, done, CombatCounts::plus);
        stage1CombatRole = role;
        stage1RoleCombatBaseline = now;
    }

    /** Adds the time since the last sample to where it stood (ground, rope or air) and how it was simulated. */
    void sampleStage1(String footing, String simulationMode, long nowMs) {
        if (stage1SampledAtMs > 0L && nowMs > stage1SampledAtMs) {
            long elapsedMs = nowMs - stage1SampledAtMs;
            if (footing != null) stage1FootingMs.merge(footing, elapsedMs, Long::sum);
            if (simulationMode != null) stage1SimulationMs.merge(simulationMode, elapsedMs, Long::sum);
        }
        if (stage1SampledAtMs > 0L) stage1SampledAtMs = nowMs;
    }

    Map<Role, CombatCounts> stage1RoleCombat() { return Map.copyOf(stage1RoleCombat); }
    Map<String, Long> stage1FootingMs() { return Map.copyOf(stage1FootingMs); }
    Map<String, Long> stage1SimulationMs() { return Map.copyOf(stage1SimulationMs); }
    Map<String, Long> stage1PostureBaseline() { return stage1PostureBaseline; }
    Map<Integer, Long> stage1SkillBaseline() { return stage1SkillBaseline; }
    long stage1DegenerateBaseline() { return stage1DegenerateBaseline; }

    /**
     * The overdue clock of its stage-5 delivery restarts here: it runs only while it goes to the leader, so one who
     * waited by Cloto for him has its whole bound once it sets out for him.
     */
    void restartStage5Chase(long nowMs) { stage5ChaseFromMs = Math.max(stage5ChaseFromMs, nowMs); }
    long stage5ChaseFromMs() { return stage5ChaseFromMs; }
    boolean stage5LeaderUnreachable() { return stage5LeaderUnreachable; }

    /**
     * What its own graph said, where it stood on a foothold, about a route to the leader. Once it had none, it meets
     * him by Cloto, as it told him, until that is ended; since when a route has been there is kept for that bound.
     */
    void observeStage5LeaderReach(boolean reachable, long nowMs) {
        stage5LeaderUnreachable = !reachable;
        if (!reachable) {
            stage5MeetsLeaderAtCloto = true;
            stage5LeaderReachableSinceMs = 0L;
        } else if (stage5LeaderReachableSinceMs == 0L) {
            stage5LeaderReachableSinceMs = Math.max(1L, nowMs);
        }
    }

    boolean stage5MeetsLeaderAtCloto() { return stage5MeetsLeaderAtCloto; }
    void endStage5MeetAtCloto() { stage5MeetsLeaderAtCloto = false; }

    /** How long a route to the leader has been there without a break; 0 while there is none. */
    long stage5LeaderReachableForMs(long nowMs) {
        return stage5LeaderUnreachable || stage5LeaderReachableSinceMs == 0L
                ? 0L : Math.max(0L, nowMs - stage5LeaderReachableSinceMs);
    }
    /** It dropped its passes before going down and carries none from here on. */
    boolean stage5PassesLeftBehind() { return stage5PassesLeftBehind; }
    void markStage5PassesLeftBehind() { stage5PassesLeftBehind = true; }
    /** It dropped passes on the leader's own spot (the hand-over or the re-collect), not by Cloto or where it stood. */
    boolean stage5DroppedOnLeadersSpot() { return stage5DroppedOnLeadersSpot; }
    void markStage5DroppedOnLeadersSpot() { stage5DroppedOnLeadersSpot = true; }
    /** The passes it was last seen holding on stage 5; they go with it if it leaves the run. */
    int stage5PassesSeen() { return stage5PassesSeen; }
    void observeStage5Passes(int held) { stage5PassesSeen = Math.max(0, held); }

    /** The KPQ run keeps this Agent at full simulation rate; whether something else already did is kept. */
    boolean fullRateHeld() { return fullRateHeld; }
    boolean fullRateWasRequired() { return fullRateWasRequired; }
    void holdFullRate(boolean alreadyRequired) {
        fullRateHeld = true;
        fullRateWasRequired = alreadyRequired;
    }
    void releaseFullRate() {
        fullRateHeld = false;
        fullRateWasRequired = false;
    }

    /** Adds the time since the last tally to the role held over it. */
    void tallyStage1Role(long nowMs) {
        if (stage1RoleTalliedAtMs > 0L && nowMs > stage1RoleTalliedAtMs) {
            stage1RoleMs.merge(role, nowMs - stage1RoleTalliedAtMs, Long::sum);
        }
        stage1RoleTalliedAtMs = nowMs;
    }

    /** Counts coupons that came into the bag since the last look; a hand-in or a dropped spare counts none. */
    void tallyStage1Coupons(int held) {
        stage1CouponsLooted += Math.max(0, held - stage1CouponsHeld);
        stage1CouponsHeld = held;
    }

    CombatCounts stage1Combat(CombatCounts now) {
        return new CombatCounts(delta(now.kills(), stage1CombatBaseline.kills()),
                delta(now.attacks(), stage1CombatBaseline.attacks()),
                delta(now.hitLines(), stage1CombatBaseline.hitLines()),
                delta(now.missLines(), stage1CombatBaseline.missLines()));
    }

    Map<Role, Long> stage1RoleMs() { return Map.copyOf(stage1RoleMs); }
    int stage1CouponsLooted() { return stage1CouponsLooted; }
    long stage1PassAtMs() { return stage1PassAtMs; }

    public record CombatCounts(long kills, long attacks, long hitLines, long missLines) {
        CombatCounts plus(CombatCounts other) {
            return new CombatCounts(kills + other.kills, attacks + other.attacks,
                    hitLines + other.hitLines, missLines + other.missLines);
        }
    }

}
