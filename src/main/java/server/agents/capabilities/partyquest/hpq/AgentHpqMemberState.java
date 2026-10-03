package server.agents.capabilities.partyquest.hpq;

/** Per-participant HPQ state. It is never stored in another PQ's aggregate. */
public final class AgentHpqMemberState {
    public enum MemberType { AGENT, HUMAN }
    public enum Role { EVENT_LEADER, SEED_COLLECTOR, SEED_PLANTER, BUNNY_GUARD, CAKE_COLLECTOR }
    public enum RewardState { PENDING, CLAIMING, CLAIMED, FORFEITED }

    private final int characterId;
    private final MemberType memberType;
    private Role role;
    private int assignedSeedItemId;
    private long nextActionAtMs;
    private RewardState rewardState = RewardState.PENDING;
    private int claimFailures;
    private boolean fullRateHeld;
    private boolean fullRateWasRequired;
    private boolean retiredFromCakes;
    // A companion run's deaths and tap-outs (AgentHpqCoordinator.membersAlive), under the session's lock.
    private long downSinceMs;
    private boolean tapOutRequested;
    private long revivedSeenAtMs;
    private int cakesWhenDown;
    private long missingSinceMs;

    public AgentHpqMemberState(int characterId, MemberType memberType) {
        if (characterId <= 0 || memberType == null) {
            throw new IllegalArgumentException("valid HPQ member values are required");
        }
        this.characterId = characterId;
        this.memberType = memberType;
        this.role = Role.SEED_COLLECTOR;
    }

    public int characterId() { return characterId; }
    public MemberType memberType() { return memberType; }
    public Role role() { return role; }
    public int assignedSeedItemId() { return assignedSeedItemId; }
    public long nextActionAtMs() { return nextActionAtMs; }
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
        if (rewardState == RewardState.CLAIMING) {
            rewardState = RewardState.PENDING;
            claimFailures++;
        }
    }

    /** Claims Tory turned down (1012112.js cancels one when giveEventReward fails: a full inventory). */
    int claimFailures() { return claimFailures; }

    void forfeitReward() {
        if (rewardState == RewardState.PENDING) rewardState = RewardState.FORFEITED;
    }

    public void assign(Role role, int assignedSeedItemId) {
        if (role == null || (assignedSeedItemId != 0 && !AgentHpqDefinition.isSeed(assignedSeedItemId))) {
            throw new IllegalArgumentException("valid HPQ role assignment is required");
        }
        this.role = role;
        this.assignedSeedItemId = assignedSeedItemId;
    }

    public void deferUntil(long nextActionAtMs) {
        this.nextActionAtMs = Math.max(0L, nextActionAtMs);
    }

    /** A companion run's full-rate simulation hold on this member (AgentHpqFullRateSimulation). */
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

    /**
     * A companion run's companion that left its cakes (or had none) as it was about to go down: it collects nothing
     * while it stays in danger, and is back in line for the collector's post once it is out of it.
     */
    boolean retiredFromCakes() { return retiredFromCakes; }

    void retireFromCakes() { retiredFromCakes = true; }

    void returnToCakes() { retiredFromCakes = false; }

    /**
     * Marks this member down from its first sighting, with the rice cakes it held then (the stock respawn's unregister
     * takes them from it, so the count is kept for the ejection's log); a down sighting also breaks any revive streak.
     */
    void markDown(long nowMs, int cakesHeld) {
        if (downSinceMs == 0L) {
            downSinceMs = Math.max(1L, nowMs);
            cakesWhenDown = Math.max(0, cakesHeld);
        }
        revivedSeenAtMs = 0L;
    }

    void clearDown() {
        downSinceMs = 0L;
        tapOutRequested = false;
        revivedSeenAtMs = 0L;
        cakesWhenDown = 0;
    }

    long downSinceMs() { return downSinceMs; }
    boolean down() { return downSinceMs > 0L; }
    boolean tapOutRequested() { return tapOutRequested; }
    void markTapOutRequested() { tapOutRequested = true; }
    /** The rice cakes it held as it went down; 0 when it is not down. */
    int cakesWhenDown() { return cakesWhenDown; }

    /**
     * True from the second tick in a row that sees this downed member standing in the event again. The respawn
     * restores HP before it changes map, so a single sighting can be a companion on its way out.
     */
    boolean confirmRevived(long nowMs) {
        if (revivedSeenAtMs > 0L) return true;
        revivedSeenAtMs = Math.max(1L, nowMs);
        return false;
    }

    /** Since when this member has been missing (offline or unloaded), from its first sighting so. */
    long observeMissing(long nowMs) {
        if (missingSinceMs == 0L) missingSinceMs = Math.max(1L, nowMs);
        return missingSinceMs;
    }

    void clearMissing() { missingSinceMs = 0L; }
}
