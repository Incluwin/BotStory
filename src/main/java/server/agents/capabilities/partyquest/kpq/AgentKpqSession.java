package server.agents.capabilities.partyquest.kpq;

import scripting.event.EventInstanceManager;

import java.awt.Point;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Party-level KPQ state. All mutation is synchronized through this aggregate. */
public final class AgentKpqSession {
    private static final boolean DEFAULT_MEMBER_COORDINATION_CHAT_ENABLED =
            config.AgentTuning.booleanValue(
                    "server.agents.capabilities.partyquest.kpq.AgentKpqSession.MEMBER_COORDINATION_CHAT_ENABLED");
    public enum Mode { PRODUCTION, BACKGROUND_POPULATION, TEST_OBSERVATION }
    public enum PartyOwnership { EXTERNAL, KPQ_OWNED }
    public enum Phase {
        PREPARING, ENTERING, STAGE_1, STAGE_2, STAGE_3, STAGE_4,
        STAGE_5, CLAIMING_REWARDS, EXITING, COMPLETED, FAILED
    }

    private final String sessionId;
    private final Mode mode;
    private final long seed;
    private final long startedAtMs;
    private final int operatorId;
    private final int requestedPartySize;
    private PartyOwnership partyOwnership = PartyOwnership.EXTERNAL;
    private final Map<Integer, AgentKpqMemberState> members = new LinkedHashMap<>();
    private Phase phase = Phase.PREPARING;
    private int eventLeaderId;
    /** Agent currently holding the central execution lease; this is not a visible party role. */
    private int executionAgentId;
    private int formationCallerId;
    private int stageStep;
    private int attemptIndex;
    private int attemptId;
    private List<Integer> combination = List.of();
    private long phaseEnteredAtMs;
    private long lastProgressAtMs;
    private long readyAtMs;
    private long lastExecutionTickMs = Long.MIN_VALUE;
    private boolean paused;
    private boolean memberCoordinationChatEnabled = DEFAULT_MEMBER_COORDINATION_CHAT_ENABLED;
    private boolean companionAdopted;
    private String failure = "";
    private final LinkedHashSet<String> narrationKeys = new LinkedHashSet<>();
    private final Map<String, Long> dialogueTimes = new LinkedHashMap<>();
    private final Map<String, Long> dialogueWaitStartedAtMs = new LinkedHashMap<>();
    private int squishyShoesWinnerId;
    private int requestedCheckpointStage = 1;
    private int puzzleValidationRevision;
    private int consumedPuzzleValidationRevision;
    private int puzzleValidationStage;
    private boolean puzzleValidationAccepted;
    private int formationReadyAttemptId = -1;
    private int earlyPuzzleRejections;
    private long couponSweepStartedAtMs;
    private long nextCouponSweepAtMs;
    private int stageOneHelperCollectorId;
    private Point stageOneHelperAnchor;
    private long missingPassSinceMs;
    private long puzzleCheckAtMs;
    private int puzzleCheckLoggedAttemptId = -1;
    private long squishyShoesSeenAtMs;
    private boolean squishyShoesResolved;
    private String blockerKey = "";
    private long blockerSinceMs;
    private int blockerAttempts;
    private boolean terminationStarted;
    private EventInstanceManager eventInstance;
    private int stage5LastMobCount = -1;
    private int stage5LastPassCount = -1;
    private long stage5BossDefeatedAtMs;
    private long stage5LootNotBeforeMs;
    private long stage5CleanupStartedAtMs;
    private long stage5CleanupDeadlineMs;
    private long stage5BossCombatStartedAtMs;
    private boolean stage5BossCombatReported;
    private int stage5MobsSeen = -1;
    private long stage5ReachedAtMs;
    private boolean stageOneSummarized;
    private boolean rewardEligibilityFrozen;

    public AgentKpqSession(Mode mode, long seed, int operatorId, int requestedPartySize, long nowMs) {
        if (requestedPartySize < 3 || requestedPartySize > 4) {
            throw new IllegalArgumentException("KPQ party size must be 3 or 4");
        }
        this.sessionId = "kpq-" + UUID.randomUUID();
        this.mode = mode;
        this.seed = seed;
        this.startedAtMs = nowMs;
        this.operatorId = operatorId;
        this.requestedPartySize = requestedPartySize;
        this.phaseEnteredAtMs = nowMs;
        this.lastProgressAtMs = nowMs;
    }

    public synchronized void addMember(int characterId, AgentKpqMemberState.MemberType type) {
        members.putIfAbsent(characterId, new AgentKpqMemberState(characterId, type, nextPartyNumber()));
        if (eventLeaderId == 0) {
            eventLeaderId = characterId;
            executionAgentId = characterId;
            formationCallerId = characterId;
            members.get(characterId).setRole(AgentKpqMemberState.Role.EVENT_LEADER);
        }
    }

    public synchronized void removeMember(int characterId) {
        members.remove(characterId);
    }

    public synchronized void setLeadership(int eventLeaderId, int executionAgentId) {
        AgentKpqMemberState eventLeader = members.get(eventLeaderId);
        AgentKpqMemberState executionAgent = members.get(executionAgentId);
        if (eventLeader == null || executionAgent == null
                || executionAgent.memberType() != AgentKpqMemberState.MemberType.AGENT) {
            throw new IllegalArgumentException(
                    "KPQ leadership requires a member leader and an Agent execution owner");
        }
        members.values().stream()
                .filter(member -> member.role() == AgentKpqMemberState.Role.EVENT_LEADER)
                .forEach(member -> member.setRole(AgentKpqMemberState.Role.WAITING));
        this.eventLeaderId = eventLeaderId;
        this.executionAgentId = executionAgentId;
        this.formationCallerId = eventLeader.memberType() == AgentKpqMemberState.MemberType.AGENT
                ? eventLeaderId
                : 0;
        eventLeader.setRole(AgentKpqMemberState.Role.EVENT_LEADER);
    }

    static boolean defaultMemberCoordinationChatEnabled() {
        return DEFAULT_MEMBER_COORDINATION_CHAT_ENABLED;
    }

    public synchronized boolean memberCoordinationChatEnabled() {
        return memberCoordinationChatEnabled;
    }

    public synchronized void setMemberCoordinationChatEnabled(boolean enabled) {
        memberCoordinationChatEnabled = enabled;
    }

    /**
     * A human-led run of owner-bound companions. Its host sets this after admission; a downed companion then waits
     * for its respawn and taps out instead of failing the run, and a non-leader human who leaves is dropped.
     */
    public synchronized boolean companionAdopted() {
        return companionAdopted;
    }

    public synchronized void setCompanionAdopted(boolean adopted) {
        companionAdopted = adopted;
    }

    /**
     * Forgets the puzzle formation so the next stage tick assigns the remaining members again. The attempt is kept,
     * so no puzzle attempt is burnt.
     */
    public synchronized void resetFormation() {
        combination = List.of();
        puzzleCheckAtMs = 0L;
        formationReadyAttemptId = -1;
    }

    /** Restarts every blocker clock that kept running while a downed member paused a companion run's stage. */
    synchronized void restartBlockerClocks() {
        clearBlocker();
        members.values().forEach(AgentKpqMemberState::clearBlocker);
    }

    private int nextPartyNumber() {
        for (int number = 1; number <= 6; number++) {
            int candidate = number;
            if (members.values().stream().noneMatch(member -> member.partyNumber() == candidate)) return number;
        }
        throw new IllegalStateException("No KPQ party number is available");
    }

    public synchronized boolean claimExecutionTick(int characterId, long nowMs) {
        return claimExecutionTick(characterId, nowMs, 3_000L);
    }

    public synchronized boolean claimExecutionTick(int characterId, long nowMs, long leaseMs) {
        AgentKpqMemberState candidate = members.get(characterId);
        if (candidate == null || candidate.memberType() != AgentKpqMemberState.MemberType.AGENT
                || nowMs == lastExecutionTickMs) {
            return false;
        }
        boolean current = characterId == executionAgentId;
        boolean expired = lastExecutionTickMs == Long.MIN_VALUE
                || nowMs - lastExecutionTickMs >= Math.max(1L, leaseMs);
        if (!current && !expired) return false;
        if (!current) {
            executionAgentId = characterId;
        }
        lastExecutionTickMs = nowMs;
        return true;
    }

    public synchronized boolean claimExpiredExecutionTick(int characterId, long nowMs, long leaseMs) {
        AgentKpqMemberState candidate = members.get(characterId);
        if (candidate == null || candidate.memberType() != AgentKpqMemberState.MemberType.AGENT
                || (lastExecutionTickMs != Long.MIN_VALUE
                && nowMs - lastExecutionTickMs < Math.max(1L, leaseMs))) {
            return false;
        }
        executionAgentId = characterId;
        lastExecutionTickMs = nowMs;
        return true;
    }

    public synchronized void transition(Phase next, long nowMs) {
        phase = next;
        phaseEnteredAtMs = nowMs;
        lastProgressAtMs = nowMs;
        stageStep = 0;
        attemptIndex = 0;
        attemptId = 0;
        combination = List.of();
        puzzleValidationRevision = 0;
        consumedPuzzleValidationRevision = 0;
        puzzleValidationStage = 0;
        puzzleValidationAccepted = false;
        formationReadyAttemptId = -1;
        earlyPuzzleRejections = 0;
        couponSweepStartedAtMs = 0L;
        nextCouponSweepAtMs = 0L;
        stageOneHelperCollectorId = 0;
        stageOneHelperAnchor = null;
        missingPassSinceMs = 0L;
        puzzleCheckAtMs = 0L;
        puzzleCheckLoggedAttemptId = -1;
        blockerKey = "";
        blockerSinceMs = 0L;
        blockerAttempts = 0;
        stage5LastMobCount = -1;
        stage5LastPassCount = -1;
        stage5BossDefeatedAtMs = 0L;
        stage5LootNotBeforeMs = 0L;
        stage5CleanupStartedAtMs = 0L;
        stage5CleanupDeadlineMs = 0L;
        // Companions fight King Slime while the leader still climbs stage 4; a fight under way is reported whole.
        boolean bossFightUnderWay = stage5BossCombatStartedAtMs > 0L && !stage5BossCombatReported;
        if (!bossFightUnderWay) {
            stage5BossCombatStartedAtMs = 0L;
            stage5BossCombatReported = false;
        }
        dialogueTimes.clear();
        dialogueWaitStartedAtMs.clear();
        members.values().forEach(member -> {
            member.setAssignedPosition(0);
            member.setStableSinceMs(0L);
            member.setActionNotBeforeMs(0L);
            member.setFidgetedAttemptId(-1);
            member.clearFidget();
            member.clearBlocker();
            if (!bossFightUnderWay) member.resetStage5BossCombat();
        });
    }

    public synchronized void fail(String reason, long nowMs) {
        failure = reason == null ? "unknown KPQ failure" : reason;
        transition(Phase.FAILED, nowMs);
    }

    public synchronized void complete(long nowMs) {
        transition(Phase.COMPLETED, nowMs);
    }

    public synchronized boolean beginTermination() {
        if (terminationStarted) return false;
        terminationStarted = true;
        return true;
    }

    public synchronized int observeBlocker(String key, long nowMs) {
        String next = key == null ? "unknown" : key;
        if (!next.equals(blockerKey)) {
            blockerKey = next;
            blockerSinceMs = nowMs;
            blockerAttempts = 1;
        } else {
            blockerAttempts++;
        }
        return blockerAttempts;
    }

    public synchronized void clearBlocker() {
        blockerKey = "";
        blockerSinceMs = 0L;
        blockerAttempts = 0;
    }

    public synchronized boolean narrateOnce(String key) {
        return key != null && narrationKeys.add(key);
    }

    public synchronized boolean claimDialogue(String key, long nowMs, long cooldownMs) {
        if (key == null) return false;
        Long previous = dialogueTimes.get(key);
        if (previous != null && nowMs - previous < Math.max(0L, cooldownMs)) return false;
        dialogueTimes.put(key, nowMs);
        return true;
    }

    public synchronized long dialogueWaitElapsedMs(String key, long nowMs) {
        if (key == null) return 0L;
        Long startedAtMs = dialogueWaitStartedAtMs.putIfAbsent(key, nowMs);
        return startedAtMs == null ? 0L : Math.max(0L, nowMs - startedAtMs);
    }

    /**
     * Cloto judges whoever stands on the puzzle at the moment the human leader clicks her. Live (2026-09-30) the
     * leader clicked while the companions were still climbing to their spots; her "wrong" waited until they stood
     * still, then dropped a formation nobody had checked, and Stage 2 alone took 10 minutes. A rejection therefore
     * counts only while the coordinator holds this attempt's formation ready, or when {@code placementHeld} says she
     * saw it anyway: every participant on its own spot and nobody else on one, as read while she judged. Waiting out
     * the hold for that cost every wrong check of a companion run about a second. An early one is dropped, and the
     * leader is asked again. An acceptance always counts, since Cloto has already cleared the stage.
     *
     * @return how the verdict was taken, for the log
     */
    public synchronized String recordHumanPuzzleValidation(int stage, boolean accepted, boolean placementHeld) {
        boolean formationReady = formationReadyAttemptId == attemptId;
        if (!accepted && !formationReady && !placementHeld) {
            earlyPuzzleRejections++;
            return "dropped-early";
        }
        puzzleValidationStage = stage;
        puzzleValidationAccepted = accepted;
        puzzleValidationRevision++;
        return accepted ? "accepted" : formationReady ? "rejected" : "rejected-as-placed";
    }

    public synchronized PuzzleValidation consumeHumanPuzzleValidation(int stage) {
        if (puzzleValidationRevision <= consumedPuzzleValidationRevision
                || puzzleValidationStage != stage) {
            return null;
        }
        consumedPuzzleValidationRevision = puzzleValidationRevision;
        return new PuzzleValidation(puzzleValidationRevision, puzzleValidationAccepted);
    }

    /** Takes a counted rejection without waiting for the formation to hold; an acceptance is left for later. */
    synchronized boolean consumeHumanPuzzleRejection(int stage) {
        if (puzzleValidationAccepted) return false;
        return consumeHumanPuzzleValidation(stage) != null;
    }

    /** Every participant of the current attempt stands in place and has held still for the settle time. */
    synchronized void markFormationReady() {
        formationReadyAttemptId = attemptId;
    }

    synchronized void clearFormationReady() {
        formationReadyAttemptId = -1;
    }

    /** Rejections dropped because Cloto gave them before the formation was ready. */
    synchronized int earlyPuzzleRejections() {
        return earlyPuzzleRejections;
    }

    public synchronized String sessionId() { return sessionId; }
    public synchronized Mode mode() { return mode; }
    public synchronized PartyOwnership partyOwnership() { return partyOwnership; }
    public synchronized void setPartyOwnership(PartyOwnership ownership) {
        partyOwnership = ownership == null ? PartyOwnership.EXTERNAL : ownership;
    }
    public synchronized long seed() { return seed; }
    public synchronized long startedAtMs() { return startedAtMs; }
    public synchronized int operatorId() { return operatorId; }
    public synchronized int requestedPartySize() { return requestedPartySize; }
    public synchronized Phase phase() { return phase; }
    public synchronized int eventLeaderId() { return eventLeaderId; }
    public synchronized int executionAgentId() { return executionAgentId; }
    public synchronized void setExecutionAgentId(int id) { executionAgentId = id; }
    public synchronized int formationCallerId() { return formationCallerId; }
    public synchronized void setFormationCallerId(int id) { formationCallerId = id; }
    public synchronized List<AgentKpqMemberState> members() { return new ArrayList<>(members.values()); }
    public synchronized AgentKpqMemberState member(int id) { return members.get(id); }
    public synchronized int memberCount() { return members.size(); }
    public synchronized int stageStep() { return stageStep; }
    public synchronized void setStageStep(int stageStep) { this.stageStep = stageStep; }
    public synchronized int attemptIndex() { return attemptIndex; }
    public synchronized void setAttemptIndex(int attemptIndex) { this.attemptIndex = attemptIndex; }
    public synchronized int nextAttemptId() { return ++attemptId; }
    public synchronized int attemptId() { return attemptId; }
    public synchronized List<Integer> combination() { return combination; }
    public synchronized void setCombination(List<Integer> combination) { this.combination = List.copyOf(combination); }
    public synchronized long phaseEnteredAtMs() { return phaseEnteredAtMs; }
    public synchronized long lastProgressAtMs() { return lastProgressAtMs; }
    public synchronized void markProgress(long nowMs) { lastProgressAtMs = nowMs; }
    public synchronized long readyAtMs() { return readyAtMs; }
    public synchronized void setReadyAtMs(long readyAtMs) { this.readyAtMs = readyAtMs; }
    public synchronized boolean paused() { return paused; }
    public synchronized void setPaused(boolean paused) { this.paused = paused; }
    public synchronized String failure() { return failure; }
    public synchronized int squishyShoesWinnerId() { return squishyShoesWinnerId; }
    public synchronized void setSquishyShoesWinnerId(int id) { squishyShoesWinnerId = id; }
    public synchronized int requestedCheckpointStage() { return requestedCheckpointStage; }
    public synchronized void setRequestedCheckpointStage(int stage) {
        if (stage < 1 || stage > 5) throw new IllegalArgumentException("KPQ checkpoint must be 1-5");
        requestedCheckpointStage = stage;
    }
    public synchronized long couponSweepStartedAtMs() { return couponSweepStartedAtMs; }
    public synchronized void setCouponSweepStartedAtMs(long value) {
        couponSweepStartedAtMs = Math.max(0L, value);
    }
    public synchronized long nextCouponSweepAtMs() { return nextCouponSweepAtMs; }
    public synchronized void setNextCouponSweepAtMs(long value) {
        nextCouponSweepAtMs = Math.max(0L, value);
    }
    synchronized int stageOneHelperCollectorId() { return stageOneHelperCollectorId; }
    synchronized Point stageOneHelperAnchor() { return stageOneHelperAnchor; }
    synchronized void setStageOneHelperAnchor(int collectorId, Point anchor) {
        stageOneHelperCollectorId = Math.max(0, collectorId);
        stageOneHelperAnchor = anchor;
    }
    public synchronized long missingPassSinceMs() { return missingPassSinceMs; }
    public synchronized void setMissingPassSinceMs(long value) {
        missingPassSinceMs = Math.max(0L, value);
    }
    public synchronized long puzzleCheckAtMs() { return puzzleCheckAtMs; }
    public synchronized void setPuzzleCheckAtMs(long value) { puzzleCheckAtMs = Math.max(0L, value); }
    public synchronized boolean markPuzzleCheckLogged(int currentAttemptId) {
        if (puzzleCheckLoggedAttemptId == currentAttemptId) return false;
        puzzleCheckLoggedAttemptId = currentAttemptId;
        return true;
    }
    public synchronized long squishyShoesSeenAtMs() { return squishyShoesSeenAtMs; }
    public synchronized void setSquishyShoesSeenAtMs(long value) {
        squishyShoesSeenAtMs = Math.max(0L, value);
    }
    public synchronized boolean squishyShoesResolved() { return squishyShoesResolved; }
    public synchronized void markSquishyShoesResolved() { squishyShoesResolved = true; }
    public synchronized String blockerKey() { return blockerKey; }
    public synchronized long blockerSinceMs() { return blockerSinceMs; }
    public synchronized int blockerAttempts() { return blockerAttempts; }
    public synchronized boolean terminationStarted() { return terminationStarted; }
    public synchronized EventInstanceManager eventInstance() { return eventInstance; }
    public synchronized void bindEventInstance(EventInstanceManager event) { eventInstance = event; }
    public synchronized void clearEventInstance() { eventInstance = null; }
    public synchronized void freezeRewardEligibility() { rewardEligibilityFrozen = true; }
    public synchronized boolean rewardEligibilityFrozen() { return rewardEligibilityFrozen; }
    public synchronized boolean beginRewardClaim(int characterId) {
        AgentKpqMemberState member = members.get(characterId);
        return rewardEligibilityFrozen && member != null && member.beginRewardClaim();
    }
    public synchronized boolean completeRewardClaim(int characterId) {
        AgentKpqMemberState member = members.get(characterId);
        return member != null && member.completeRewardClaim();
    }
    public synchronized void cancelRewardClaim(int characterId) {
        AgentKpqMemberState member = members.get(characterId);
        if (member != null) member.cancelRewardClaim();
    }
    public synchronized void forfeitReward(int characterId) {
        AgentKpqMemberState member = members.get(characterId);
        if (member != null) member.forfeitReward();
    }
    public synchronized boolean allRewardsResolved() {
        return rewardEligibilityFrozen && members.values().stream()
                .allMatch(AgentKpqMemberState::rewardResolved);
    }
    public synchronized boolean observeStage5Progress(int mobCount, int passCount) {
        boolean changed = stage5LastMobCount < 0 || mobCount < stage5LastMobCount
                || passCount > stage5LastPassCount;
        stage5LastMobCount = mobCount;
        stage5LastPassCount = passCount;
        return changed;
    }

    public synchronized boolean stage5ReviveGraceActive(
            int bossCount, long nowMs, long graceMs) {
        if (bossCount > 0) {
            stage5BossDefeatedAtMs = 0L;
            return false;
        }
        if (stage5BossDefeatedAtMs == 0L) {
            stage5BossDefeatedAtMs = nowMs;
        }
        return nowMs - stage5BossDefeatedAtMs < Math.max(0L, graceMs);
    }

    public synchronized void beginStage5LootDelayIfBossDefeated(
            int bossCount, long nowMs, long delayMs) {
        if (bossCount == 0 && stage5BossCombatStartedAtMs > 0L && stage5LootNotBeforeMs == 0L) {
            stage5LootNotBeforeMs = nowMs + Math.max(0L, delayMs);
        }
    }

    public synchronized boolean stage5LootDelayActive(long nowMs) {
        return stage5LootNotBeforeMs > 0L && nowMs < stage5LootNotBeforeMs;
    }

    public synchronized boolean beginStage5Cleanup(long nowMs, long durationMs) {
        if (stage5CleanupStartedAtMs > 0L) return false;
        stage5CleanupStartedAtMs = Math.max(1L, nowMs);
        stage5CleanupDeadlineMs = stage5CleanupStartedAtMs + Math.max(0L, durationMs);
        return true;
    }

    public synchronized boolean stage5CleanupStarted() {
        return stage5CleanupStartedAtMs > 0L;
    }

    public synchronized boolean stage5CleanupActive(long nowMs) {
        return stage5CleanupStartedAtMs > 0L && nowMs < stage5CleanupDeadlineMs;
    }

    public synchronized long stage5CleanupDeadlineMs() {
        return stage5CleanupDeadlineMs;
    }

    public synchronized boolean beginStage5BossCombat(long nowMs) {
        if (stage5BossCombatStartedAtMs > 0L) return false;
        stage5BossCombatStartedAtMs = Math.max(1L, nowMs);
        return true;
    }

    public synchronized long stage5BossCombatStartedAtMs() {
        return stage5BossCombatStartedAtMs;
    }

    public synchronized boolean claimStage5BossCombatReport() {
        if (stage5BossCombatStartedAtMs <= 0L || stage5BossCombatReported) return false;
        stage5BossCombatReported = true;
        return true;
    }

    /**
     * Records the live stage-5 monster count a member there sees, in or before the STAGE_5 phase, and returns how many
     * died since the last sighting. The first sighting is when the run reached stage 5; no phase change resets it.
     */
    synchronized int observeStage5Mobs(int alive, long nowMs) {
        if (stage5MobsSeen < 0) stage5ReachedAtMs = nowMs;
        int died = stage5MobsSeen < 0 ? 0 : Math.max(0, stage5MobsSeen - alive);
        stage5MobsSeen = alive;
        return died;
    }

    synchronized long stage5ReachedAtMs() {
        return stage5ReachedAtMs;
    }

    synchronized boolean claimStageOneSummary() {
        if (stageOneSummarized) return false;
        stageOneSummarized = true;
        return true;
    }

    public record PuzzleValidation(int revision, boolean accepted) {
    }
}
