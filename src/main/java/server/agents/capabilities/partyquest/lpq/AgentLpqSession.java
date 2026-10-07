package server.agents.capabilities.partyquest.lpq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.partyquest.AgentCompanionRunSession;
import server.agents.capabilities.partyquest.pace.AgentPqPaceCalibration;

import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntPredicate;
import java.util.function.Supplier;

/** Isolated party-level LPQ state machine. */
public final class AgentLpqSession implements AgentCompanionRunSession {
    private static final Logger log = LoggerFactory.getLogger(AgentLpqSession.class);

    public enum Mode { PRODUCTION, BACKGROUND_POPULATION, TEST_OBSERVATION }
    public enum PartyOwnership { EXTERNAL, LPQ_OWNED }
    public enum BonusMode { SKIP, ENTER, HUMAN_CHOICE }
    public enum HumanRolePreference { DEFAULT, TELEPORT, DARK_SIGHT, RANGED }
    public enum Phase {
        PREPARING, ENTERING,
        STAGE_1, STAGE_2, STAGE_3, STAGE_4, STAGE_5,
        STAGE_6, STAGE_7, STAGE_8, STAGE_9,
        BONUS, CLAIMING_REWARD, EXITING, COMPLETED, FAILED
    }

    private final String sessionId = UUID.randomUUID().toString();
    private final Mode mode;
    private final long seed;
    private final int operatorId;
    private final int requestedPartySize;
    private final long startedAtMs;
    private final Map<Integer, AgentLpqMemberState> members = new LinkedHashMap<>();
    private final AgentLpqRoomAssignment rooms = new AgentLpqRoomAssignment();
    private final AgentLpqPortalMazeState maze = new AgentLpqPortalMazeState();
    private int couponRegroupStage;
    private Phase mainMapRallyPhase;
    private int mainMapRallyMapId;
    private long mainMapRallyStartedAtMs;
    private long bossEdgeRegroupStartedAtMs;
    private long stageNineBossHp = -1L;
    private Phase phase = Phase.PREPARING;
    private int eventLeaderId;
    private int executionAgentId;
    private long executionLeaseUntilMs;
    private long lastProgressAtMs;
    private long phaseEnteredAtMs;
    private boolean paused;
    private boolean terminating;
    private String failure = "";
    private EventInstanceManager eventInstance;
    private PartyOwnership partyOwnership = PartyOwnership.EXTERNAL;
    private BonusMode bonusMode = BonusMode.ENTER;
    private List<List<Integer>> stage8Order = List.of();
    private int stage8Attempt;
    private int stage8AnnouncedAttempt = -1;
    private boolean stage8AssignmentChatEnabled;
    private final Map<Integer, Integer> stage8PlatformByMember = new LinkedHashMap<>();
    private Phase passRecoveryPhase;
    private int passRecoveryObservedCount = -1;
    private int passRecoveryObservedLiveMobs = -1;
    private int passRecoveryObservedActiveReactors = -1;
    private long passRecoveryLowestLiveMobHp = Long.MAX_VALUE;
    private long passRecoveryObservedAtMs;
    private boolean passRecoveryMobSweepAttempted;
    private boolean passRecoveryPassesAwarded;
    private Phase passHandoffRecoveryPhase;
    private long passHandoffRecoveryStartedAtMs;
    private Phase postClearTransitionPhase;
    private long postClearTransitionStartedAtMs;
    private Phase submissionReadyPhase;
    private long submissionReadyAtMs;
    private Phase loosePassPhase;
    private int loosePassObservedCount = -1;
    private long loosePassObservedAtMs;
    private boolean stage6SequenceAnnounced;
    private boolean stage2ScoutPlanAnnounced;
    private boolean stage2TrapClearAnnounced;
    private Phase assignmentsCalculatedPhase;
    private long bonusDrainedAtMs;
    private long readyAtMs;
    private long stage7CombatClearedAtMs;
    private int stage7LootSweepIndex;
    private boolean stage7ForceLootAttempted;
    private boolean rewardEligibilityFrozen;
    private int preferredHumanId;
    private HumanRolePreference humanRolePreference = HumanRolePreference.DEFAULT;
    private final Set<String> claimedFlavorChatKeys = new java.util.LinkedHashSet<>();
    private boolean companionAdopted;
    private boolean memberCoordinationChatEnabled;
    private AgentPqPaceCalibration paceCalibration;
    private String potionsAtEntry;
    private final Map<Phase, Long> firstEntries = new EnumMap<>(Phase.class);
    private final Set<Integer> departuresInFlight = new java.util.LinkedHashSet<>();
    private final Map<Integer, Long> heldSinceMs = new LinkedHashMap<>();
    private final Set<String> narratedKeys = new java.util.LinkedHashSet<>();
    private final Set<String> withheldNotes = new java.util.LinkedHashSet<>();
    private int recollectStackObjectId;
    private int recollectorId;
    private int recollectHeldBefore;
    private int recollects;
    private int keyCarrierId;
    private int leaderClimbBestRow = -1;
    private long stageSixAllAtTopSinceMs;
    private long leaderKeySinceMs;
    private long clearedAtMs;
    private boolean leaderReleased;
    private final Set<Integer> companionsOfRun = new java.util.LinkedHashSet<>();
    private long enteredEventAtMs;

    public AgentLpqSession(Mode mode, long seed, int operatorId, int requestedPartySize, long nowMs) {
        if (mode == null || operatorId <= 0
                || requestedPartySize < AgentLpqDefinition.MIN_PARTY_SIZE
                || requestedPartySize > AgentLpqDefinition.MAX_PARTY_SIZE || nowMs < 0L) {
            throw new IllegalArgumentException("valid LPQ session values are required");
        }
        this.mode = mode;
        this.seed = seed;
        this.operatorId = operatorId;
        this.requestedPartySize = requestedPartySize;
        this.startedAtMs = nowMs;
        this.lastProgressAtMs = nowMs;
        this.phaseEnteredAtMs = nowMs;
    }

    public synchronized void addMember(int characterId, AgentLpqMemberState.MemberType type) {
        if (members.size() >= requestedPartySize && !members.containsKey(characterId)) {
            throw new IllegalStateException("LPQ session is full");
        }
        members.computeIfAbsent(characterId, id -> new AgentLpqMemberState(id, type));
    }

    public synchronized void setLeadership(int eventLeaderId, int executionAgentId) {
        AgentLpqMemberState execution = members.get(executionAgentId);
        if (!members.containsKey(eventLeaderId) || execution == null
                || execution.memberType() != AgentLpqMemberState.MemberType.AGENT) {
            throw new IllegalArgumentException("LPQ leaders must be members and execution must be Agent-owned");
        }
        this.eventLeaderId = eventLeaderId;
        this.executionAgentId = executionAgentId;
        members.get(eventLeaderId).assign(AgentLpqMemberState.Role.EVENT_LEADER, 0);
    }

    public synchronized boolean claimExecutionTick(int characterId, long nowMs, long leaseMs) {
        return claimExecutionTick(characterId, nowMs, leaseMs, holder -> true);
    }

    /**
     * As {@link #claimExecutionTick(int, long, long)}, except that a holder for which {@code holderAlive} is false
     * (dead, or no longer loaded) loses its lease at once: a dead Agent's tick never reaches the LPQ runtime, so its
     * lease would otherwise freeze every coordinator pass until it ran out. A live holder keeps it until it expires.
     */
    public synchronized boolean claimExecutionTick(int characterId, long nowMs, long leaseMs,
                                                   IntPredicate holderAlive) {
        AgentLpqMemberState member = members.get(characterId);
        if (member == null || member.memberType() != AgentLpqMemberState.MemberType.AGENT) return false;
        if (executionAgentId != characterId && nowMs < executionLeaseUntilMs
                && holderAlive.test(executionAgentId)) return false;
        executionAgentId = characterId;
        executionLeaseUntilMs = nowMs + Math.max(250L, leaseMs);
        return true;
    }

    public synchronized boolean claimExpiredExecutionTick(int characterId, long nowMs, long leaseMs) {
        return claimExpiredExecutionTick(characterId, nowMs, leaseMs, holder -> true);
    }

    /**
     * The watchdog's claim of an expired lease, with the same dead-holder rule as
     * {@link #claimExecutionTick(int, long, long, IntPredicate)}.
     */
    public synchronized boolean claimExpiredExecutionTick(int characterId, long nowMs, long leaseMs,
                                                          IntPredicate holderAlive) {
        AgentLpqMemberState member = members.get(characterId);
        if (member == null || member.memberType() != AgentLpqMemberState.MemberType.AGENT
                || (nowMs < executionLeaseUntilMs && holderAlive.test(executionAgentId))) return false;
        executionAgentId = characterId;
        executionLeaseUntilMs = nowMs + Math.max(250L, leaseMs);
        return true;
    }

    public synchronized void transition(Phase next, long nowMs) {
        if (next == null || terminal() || next == phase) return;
        firstEntries.putIfAbsent(next, nowMs);
        logPhaseEnd(next, nowMs);
        phase = next;
        phaseEnteredAtMs = nowMs;
        assignmentsCalculatedPhase = null;
        readyAtMs = 0L;
        resetPassRecovery();
        resetSubmissionRecovery();
        resetLoosePassRecovery();
        resetPostClearTransition();
        resetMainMapRally();
        bossEdgeRegroupStartedAtMs = 0L;
        stageNineBossHp = -1L;
        members.values().forEach(member -> {
            member.resetForStage(member.characterId() == eventLeaderId
                    ? AgentLpqMemberState.Role.EVENT_LEADER
                    : AgentLpqMemberState.Role.GENERAL);
        });
        resetStage7LootSweep();
        recollectStackObjectId = 0;
        recollectorId = 0;
        recollectHeldBefore = 0;
        recollects = 0;
        leaderClimbBestRow = -1;
        stageSixAllAtTopSinceMs = 0L;
        leaderKeySinceMs = 0L;
        rooms.reset();
        members.values().forEach(AgentLpqMemberState::clearReactorWork);
        int nextStage = stageNumber(next);
        if (nextStage != couponRegroupStage) {
            couponRegroupStage = 0;
        }
        if (next != Phase.STAGE_6) maze.reset();
        if (next != Phase.BONUS) bonusDrainedAtMs = 0L;
        markProgress(nowMs);
    }

    /**
     * Stage roles are deliberately recalculated once after every phase transition. The
     * transition itself first clears the previous stage atomically; the coordinator then
     * installs the new room/platform/combat ownership from the current party roster.
     */
    public synchronized boolean stageAssignmentsNeedRecalculation() {
        return stageNumber(phase) > 0 && assignmentsCalculatedPhase != phase;
    }

    public synchronized void markStageAssignmentsRecalculated(long nowMs) {
        if (stageNumber(phase) == 0) {
            throw new IllegalStateException("LPQ stage assignments require an active stage");
        }
        assignmentsCalculatedPhase = phase;
        markProgress(nowMs);
    }

    public synchronized void fail(String reason, long nowMs) {
        failure = reason == null ? "" : reason.trim();
        firstEntries.putIfAbsent(Phase.FAILED, nowMs);
        logPhaseEnd(Phase.FAILED, nowMs);
        phase = Phase.FAILED;
        phaseEnteredAtMs = nowMs;
        markProgress(nowMs);
    }

    public synchronized void complete(long nowMs) {
        firstEntries.putIfAbsent(Phase.COMPLETED, nowMs);
        logPhaseEnd(Phase.COMPLETED, nowMs);
        phase = Phase.COMPLETED;
        phaseEnteredAtMs = nowMs;
        markProgress(nowMs);
    }

    private void logPhaseEnd(Phase next, long nowMs) {
        log.info("LPQ phase timing: session={} phase={} durationMs={} next={}",
                sessionId, phase, Math.max(0L, nowMs - phaseEnteredAtMs), next);
        // The phase line every PQ logs (HPQ/KPQ shape), for stock and companion runs alike.
        log.info("LPQ phase: session={} {}->{} phaseMs={} runMs={} companionRun={}",
                sessionId, phase, next, Math.max(0L, nowMs - phaseEnteredAtMs), Math.max(0L, nowMs - startedAtMs),
                companionAdopted);
    }
    public synchronized boolean beginTermination() { if (terminating) return false; terminating = true; return true; }

    /** True once a release has begun (a companion run's tick reads it: a run on its way out is not ticked). */
    public synchronized boolean terminationStarted() { return terminating; }

    /** When the run first entered {@code phase} (a re-entry keeps the first), or 0 when it never did. */
    synchronized long firstEnteredAtMs(Phase phase) {
        Long at = phase == null ? null : firstEntries.get(phase);
        return at == null ? 0L : at;
    }
    public synchronized void markProgress(long nowMs) { lastProgressAtMs = Math.max(lastProgressAtMs, nowMs); }

    /**
     * Records Alishar's HP as the coordinator sees it; true when it fell since the previous observation. The first
     * observation and a rise only set the baseline; a phase transition starts a new one.
     */
    synchronized boolean observeStageNineBossHp(long hp) {
        boolean fell = stageNineBossHp >= 0L && hp < stageNineBossHp;
        stageNineBossHp = hp;
        return fell;
    }

    public synchronized long readyAtMs() { return readyAtMs; }

    public synchronized void setReadyAtMs(long readyAtMs) {
        this.readyAtMs = Math.max(0L, readyAtMs);
    }

    public synchronized long observePassRecovery(int partyPassCount, long nowMs) {
        if (passRecoveryPhase != phase || passRecoveryObservedCount != partyPassCount) {
            passRecoveryPhase = phase;
            passRecoveryObservedCount = partyPassCount;
            passRecoveryObservedAtMs = nowMs;
            passRecoveryMobSweepAttempted = false;
            return 0L;
        }
        return Math.max(0L, nowMs - passRecoveryObservedAtMs);
    }

    public synchronized long observePassRecoveryProgress(
            int partyPassCount, int liveMobs, int activeReactors,
            long liveMobHp, long nowMs) {
        boolean newPhase = passRecoveryPhase != phase;
        boolean passProgress = !newPhase && passRecoveryObservedCount != partyPassCount;
        boolean mobCountProgress = !newPhase
                && passRecoveryObservedLiveMobs != liveMobs;
        boolean reactorProgress = !newPhase
                && passRecoveryObservedActiveReactors != activeReactors;
        boolean damageProgress = !newPhase && !mobCountProgress && !reactorProgress
                && liveMobHp < passRecoveryLowestLiveMobHp;
        if (newPhase || passProgress || mobCountProgress || reactorProgress || damageProgress) {
            passRecoveryPhase = phase;
            passRecoveryObservedCount = partyPassCount;
            passRecoveryObservedLiveMobs = liveMobs;
            passRecoveryObservedActiveReactors = activeReactors;
            passRecoveryLowestLiveMobHp = liveMobHp;
            passRecoveryObservedAtMs = nowMs;
            passRecoveryMobSweepAttempted = false;
            return 0L;
        }
        return Math.max(0L, nowMs - passRecoveryObservedAtMs);
    }

    public synchronized boolean passRecoveryMobSweepAttempted() {
        return passRecoveryMobSweepAttempted;
    }

    public synchronized void markPassRecoveryMobSweep(long nowMs) {
        passRecoveryMobSweepAttempted = true;
        passRecoveryObservedAtMs = nowMs;
        markProgress(nowMs);
    }

    public synchronized void markPassRecoveryConsolidation(long nowMs) {
        passRecoveryObservedAtMs = nowMs;
        markProgress(nowMs);
    }

    public synchronized boolean passRecoveryPassesAwarded() {
        return passRecoveryPassesAwarded;
    }

    public synchronized void markPassRecoveryPassesAwarded(long nowMs) {
        passRecoveryPassesAwarded = true;
        passRecoveryObservedAtMs = nowMs;
        markProgress(nowMs);
    }

    public synchronized boolean passHandoffRecoveryActive() {
        return passHandoffRecoveryPhase == phase;
    }

    public synchronized boolean beginPassHandoffRecovery(long nowMs) {
        if (passHandoffRecoveryPhase == phase) return false;
        passHandoffRecoveryPhase = phase;
        passHandoffRecoveryStartedAtMs = Math.max(0L, nowMs);
        markProgress(nowMs);
        return true;
    }

    public synchronized long passHandoffRecoveryElapsed(long nowMs) {
        if (!passHandoffRecoveryActive()) return 0L;
        return Math.max(0L, nowMs - passHandoffRecoveryStartedAtMs);
    }

    private void resetPassRecovery() {
        passRecoveryPhase = null;
        passRecoveryObservedCount = -1;
        passRecoveryObservedLiveMobs = -1;
        passRecoveryObservedActiveReactors = -1;
        passRecoveryLowestLiveMobHp = Long.MAX_VALUE;
        passRecoveryObservedAtMs = 0L;
        passRecoveryMobSweepAttempted = false;
        passRecoveryPassesAwarded = false;
        passHandoffRecoveryPhase = null;
        passHandoffRecoveryStartedAtMs = 0L;
    }

    public synchronized long beginOrObservePostClearTransition(long nowMs) {
        if (postClearTransitionPhase != phase) {
            postClearTransitionPhase = phase;
            postClearTransitionStartedAtMs = Math.max(0L, nowMs);
            markProgress(nowMs);
            return 0L;
        }
        return Math.max(0L, nowMs - postClearTransitionStartedAtMs);
    }

    private void resetPostClearTransition() {
        postClearTransitionPhase = null;
        postClearTransitionStartedAtMs = 0L;
    }

    public synchronized long observeSubmissionReady(boolean ready, long nowMs) {
        if (!ready) {
            resetSubmissionRecovery();
            return 0L;
        }
        if (submissionReadyPhase != phase) {
            submissionReadyPhase = phase;
            submissionReadyAtMs = nowMs;
            return 0L;
        }
        return Math.max(0L, nowMs - submissionReadyAtMs);
    }

    private void resetSubmissionRecovery() {
        submissionReadyPhase = null;
        submissionReadyAtMs = 0L;
    }

    /** Returns how long the same set of loose passes has remained uncollected. */
    public synchronized long observeLoosePasses(int loosePassCount, long nowMs) {
        if (loosePassCount <= 0) {
            resetLoosePassRecovery();
            return 0L;
        }
        if (loosePassPhase != phase || loosePassObservedCount != loosePassCount) {
            loosePassPhase = phase;
            loosePassObservedCount = loosePassCount;
            loosePassObservedAtMs = nowMs;
            return 0L;
        }
        return Math.max(0L, nowMs - loosePassObservedAtMs);
    }

    private void resetLoosePassRecovery() {
        loosePassPhase = null;
        loosePassObservedCount = -1;
        loosePassObservedAtMs = 0L;
    }
    public synchronized boolean stage6SequenceAnnounced() { return stage6SequenceAnnounced; }
    public synchronized boolean stage6SequenceChatReady(long nowMs) {
        return !stage6SequenceAnnounced;
    }
    public synchronized void markStage6SequenceAnnounced(long nowMs) {
        stage6SequenceAnnounced = true;
        markProgress(nowMs);
    }

    public synchronized boolean stage2ScoutPlanAnnounced() { return stage2ScoutPlanAnnounced; }
    public synchronized void markStage2ScoutPlanAnnounced(long nowMs) {
        stage2ScoutPlanAnnounced = true;
        markProgress(nowMs);
    }

    public synchronized boolean stage2TrapClearAnnounced() { return stage2TrapClearAnnounced; }
    public synchronized void markStage2TrapClearAnnounced(long nowMs) {
        stage2TrapClearAnnounced = true;
        markProgress(nowMs);
    }

    /** Requires the bonus map to stay empty briefly so late reactor drops are not abandoned. */
    public synchronized long observeBonusDrained(boolean drained, long nowMs) {
        if (!drained) {
            bonusDrainedAtMs = 0L;
            return 0L;
        }
        if (bonusDrainedAtMs == 0L) {
            bonusDrainedAtMs = nowMs;
            return 0L;
        }
        return Math.max(0L, nowMs - bonusDrainedAtMs);
    }

    public synchronized long observeStage7CombatCleared(boolean cleared, long nowMs) {
        if (!cleared) {
            resetStage7LootSweep();
            return 0L;
        }
        if (stage7CombatClearedAtMs == 0L) {
            stage7CombatClearedAtMs = nowMs;
            return 0L;
        }
        return Math.max(0L, nowMs - stage7CombatClearedAtMs);
    }

    public synchronized int stage7LootSweepIndex() { return stage7LootSweepIndex; }
    public synchronized void advanceStage7LootSweep(long nowMs) {
        stage7LootSweepIndex++;
        markProgress(nowMs);
    }
    public synchronized boolean stage7ForceLootAttempted() { return stage7ForceLootAttempted; }
    public synchronized void markStage7ForceLootAttempted(long nowMs) {
        stage7ForceLootAttempted = true;
        markProgress(nowMs);
    }
    private void resetStage7LootSweep() {
        stage7CombatClearedAtMs = 0L;
        stage7LootSweepIndex = 0;
        stage7ForceLootAttempted = false;
    }
    public synchronized boolean terminal() { return phase == Phase.COMPLETED || phase == Phase.FAILED; }

    public synchronized boolean couponRegrouping(int stage) {
        return couponCollectionStage(stage) && couponRegroupStage == stage;
    }

    public synchronized void beginCouponRegroup(int stage, long nowMs) {
        if (!couponCollectionStage(stage) || phase != Phase.valueOf("STAGE_" + stage)
                || couponRegroupStage == stage) return;
        couponRegroupStage = stage;
        markProgress(nowMs);
    }

    private static boolean couponCollectionStage(int stage) {
        return stage >= 1 && stage <= 7 && stage != 6;
    }

    /**
     * Starts the balloon recovery grace only after every registered participant has returned
     * to the stage's main map. A room/trap occupant resets the clock, so time spent finishing
     * an authored exit can never consume the subsequent walk-to-balloon allowance.
     */
    public synchronized long observeMainMapRally(
            int mapId, boolean everyoneOnMainMap, long nowMs) {
        int activeStage = stageNumber(phase);
        boolean currentStageMainMap = activeStage > 0
                && AgentLpqDefinition.stage(activeStage).mapId() == mapId;
        if (!currentStageMainMap || nowMs < 0L || !everyoneOnMainMap) {
            resetMainMapRally();
            return 0L;
        }
        if (mainMapRallyPhase != phase || mainMapRallyMapId != mapId
                || mainMapRallyStartedAtMs == 0L) {
            mainMapRallyPhase = phase;
            mainMapRallyMapId = mapId;
            mainMapRallyStartedAtMs = nowMs;
            return 0L;
        }
        return Math.max(0L, nowMs - mainMapRallyStartedAtMs);
    }

    private void resetMainMapRally() {
        mainMapRallyPhase = null;
        mainMapRallyMapId = 0;
        mainMapRallyStartedAtMs = 0L;
    }

    public synchronized long observeBossEdgeRegroup(boolean required, long nowMs) {
        if (!required || phase != Phase.STAGE_9) {
            bossEdgeRegroupStartedAtMs = 0L;
            return 0L;
        }
        if (bossEdgeRegroupStartedAtMs == 0L) {
            bossEdgeRegroupStartedAtMs = nowMs;
            return 0L;
        }
        return Math.max(0L, nowMs - bossEdgeRegroupStartedAtMs);
    }

    private static int stageNumber(Phase phase) {
        return phase != null && phase.name().startsWith("STAGE_")
                ? Integer.parseInt(phase.name().substring("STAGE_".length())) : 0;
    }

    public synchronized void initializeStage8Order() {
        if (stage8Order.isEmpty()) stage8Order = AgentLpqCombinationOrder.fiveOfNine();
    }

    public synchronized List<Integer> stage8Combination() {
        initializeStage8Order();
        return stage8Order.get(Math.min(stage8Attempt, stage8Order.size() - 1));
    }

    public synchronized void advanceStage8(long nowMs) {
        initializeStage8Order();
        if (stage8Attempt + 1 < stage8Order.size()) stage8Attempt++;
        markProgress(nowMs);
    }

    public synchronized boolean stage8AssignmentAnnounced() {
        return stage8AnnouncedAttempt == stage8Attempt;
    }

    public synchronized boolean stage8AssignmentChatEnabled() {
        return stage8AssignmentChatEnabled;
    }

    public synchronized void setStage8AssignmentChatEnabled(boolean enabled) {
        if (enabled && !stage8AssignmentChatEnabled) stage8AnnouncedAttempt = -1;
        stage8AssignmentChatEnabled = enabled;
    }

    public synchronized void setHumanRolePreference(
            int characterId, HumanRolePreference preference) {
        AgentLpqMemberState member = members.get(characterId);
        if (member == null || member.memberType() != AgentLpqMemberState.MemberType.HUMAN) {
            throw new IllegalArgumentException("preferred LPQ role requires a human session member");
        }
        preferredHumanId = characterId;
        humanRolePreference = preference == null ? HumanRolePreference.DEFAULT : preference;
    }

    public synchronized int preferredHumanId() { return preferredHumanId; }
    public synchronized HumanRolePreference humanRolePreference() { return humanRolePreference; }
    public synchronized boolean hasHumanMember() {
        return members.values().stream().anyMatch(
                member -> member.memberType() == AgentLpqMemberState.MemberType.HUMAN);
    }
    public synchronized boolean claimFlavorChat(String key) {
        return key != null && !key.isBlank() && claimedFlavorChatKeys.add(key);
    }

    public synchronized void markStage8AssignmentAnnounced(long nowMs) {
        stage8AnnouncedAttempt = stage8Attempt;
        markProgress(nowMs);
    }

    /** Keeps shared box owners fixed while moving only members whose next formation changed. */
    public synchronized Map<Integer, Integer> stage8Assignments(List<Integer> participantIds) {
        if (participantIds == null || participantIds.size() < 5) {
            throw new IllegalArgumentException("five LPQ Stage 8 participants are required");
        }
        List<Integer> participants = participantIds.stream().limit(5).toList();
        List<Integer> target = stage8Combination();
        stage8PlatformByMember.keySet().removeIf(id -> !participants.contains(id));
        if (stage8PlatformByMember.isEmpty()) {
            for (int index = 0; index < 5; index++) {
                stage8PlatformByMember.put(participants.get(index), target.get(index));
            }
            return Map.copyOf(stage8PlatformByMember);
        }
        stage8PlatformByMember.entrySet().removeIf(entry -> !target.contains(entry.getValue()));
        Set<Integer> used = new java.util.LinkedHashSet<>(stage8PlatformByMember.values());
        List<Integer> freeMembers = participants.stream()
                .filter(id -> !stage8PlatformByMember.containsKey(id)).toList();
        List<Integer> freePlatforms = target.stream().filter(platform -> !used.contains(platform)).toList();
        for (int index = 0; index < Math.min(freeMembers.size(), freePlatforms.size()); index++) {
            stage8PlatformByMember.put(freeMembers.get(index), freePlatforms.get(index));
        }
        return Map.copyOf(stage8PlatformByMember);
    }

    @Override
    public String sessionId() { return sessionId; }
    public Mode mode() { return mode; }
    public long seed() { return seed; }
    public int operatorId() { return operatorId; }
    public int requestedPartySize() { return requestedPartySize; }
    public long startedAtMs() { return startedAtMs; }
    public synchronized Phase phase() { return phase; }
    @Override
    public synchronized int eventLeaderId() { return eventLeaderId; }
    public synchronized int executionAgentId() { return executionAgentId; }
    public synchronized long lastProgressAtMs() { return lastProgressAtMs; }
    public synchronized long phaseEnteredAtMs() { return phaseEnteredAtMs; }
    public synchronized boolean paused() { return paused; }
    public synchronized void setPaused(boolean paused) { this.paused = paused; }
    public synchronized String failure() { return failure; }
    public synchronized EventInstanceManager eventInstance() { return eventInstance; }
    public synchronized void bindEventInstance(EventInstanceManager eventInstance) { this.eventInstance = eventInstance; }
    public synchronized void clearEventInstance() { eventInstance = null; }
    public synchronized PartyOwnership partyOwnership() { return partyOwnership; }
    public synchronized void setPartyOwnership(PartyOwnership value) { partyOwnership = value; }
    public synchronized BonusMode bonusMode() { return bonusMode; }
    public synchronized void setBonusMode(BonusMode value) { bonusMode = value; }
    public AgentLpqRoomAssignment rooms() { return rooms; }
    public AgentLpqPortalMazeState maze() { return maze; }
    public synchronized int stage8Attempt() { return stage8Attempt; }
    public synchronized AgentLpqMemberState member(int id) { return members.get(id); }
    public synchronized Collection<AgentLpqMemberState> members() { return List.copyOf(members.values()); }
    public synchronized int memberCount() { return members.size(); }
    public synchronized void freezeRewardEligibility() { rewardEligibilityFrozen = true; }
    public synchronized boolean rewardEligibilityFrozen() { return rewardEligibilityFrozen; }
    public synchronized boolean beginRewardClaim(int characterId) {
        AgentLpqMemberState member = members.get(characterId);
        return rewardEligibilityFrozen && member != null && member.beginRewardClaim();
    }
    public synchronized boolean completeRewardClaim(int characterId) {
        AgentLpqMemberState member = members.get(characterId);
        return member != null && member.completeRewardClaim();
    }
    public synchronized void cancelRewardClaim(int characterId) {
        AgentLpqMemberState member = members.get(characterId);
        if (member != null) member.cancelRewardClaim();
    }
    public synchronized void forfeitReward(int characterId) {
        AgentLpqMemberState member = members.get(characterId);
        if (member != null) member.forfeitReward();
    }
    public synchronized boolean allRewardsResolved() {
        return rewardEligibilityFrozen && members.values().stream()
                .allMatch(AgentLpqMemberState::rewardResolved);
    }

    /**
     * A human-led run of owner-bound companions, admitted by AgentLpqAdmissionService.admit. Every companion-run
     * behaviour is gated on it; stock, background and GM-observed runs leave it false.
     */
    @Override
    public synchronized boolean companionAdopted() { return companionAdopted; }

    /** Set only by the public admit, before the session is published. */
    synchronized void setCompanionAdopted(boolean adopted) { companionAdopted = adopted; }

    synchronized boolean memberCoordinationChatEnabled() { return memberCoordinationChatEnabled; }

    @Override
    public synchronized void setMemberCoordinationChatEnabled(boolean enabled) {
        memberCoordinationChatEnabled = enabled;
    }

    /** The leader's clear-time calibration, kept for the run's pace; null until a host hands one. */
    synchronized AgentPqPaceCalibration paceCalibration() { return paceCalibration; }

    @Override
    public synchronized void setPaceCalibration(AgentPqPaceCalibration calibration) {
        paceCalibration = calibration;
    }

    /** The companions' potions as 'LPQ potions:' logged them at stage 1; null when that line was never logged. */
    synchronized String potionsAtEntry() { return potionsAtEntry; }

    synchronized void setPotionsAtEntry(String potions) { potionsAtEntry = potions; }

    /** A companion-run line key was already said this run. */
    synchronized boolean narrated(String key) { return narratedKeys.contains(key); }

    /** The stage's hand-over stack being picked back up (0 for none), who picks it up, and what it held before. */
    synchronized int recollectStackObjectId() { return recollectStackObjectId; }
    synchronized int recollectorId() { return recollectorId; }
    synchronized int recollectHeldBefore() { return recollectHeldBefore; }
    /** Re-collects done this stage (AgentLpqSession.transition resets them). */
    synchronized int recollects() { return recollects; }

    synchronized void beginRecollect(int collectorId, int stackObjectId, int heldBefore) {
        recollectorId = collectorId;
        recollectStackObjectId = stackObjectId;
        recollectHeldBefore = Math.max(0, heldBefore);
    }

    /** The re-collect ends: the recollector picked the stack up ({@code pickedUp}) or it is gone. */
    synchronized void endRecollect(boolean pickedUp) {
        if (pickedUp) recollects++;
        recollectorId = 0;
        recollectStackObjectId = 0;
        recollectHeldBefore = 0;
    }

    /** This member is picking up this hand-over stack now (the loot rule lets it). */
    public synchronized boolean recollecting(int characterId, int objectId) {
        return recollectStackObjectId != 0 && recollectorId == characterId && recollectStackObjectId == objectId;
    }

    /**
     * The leader reached a maze row higher than any before this stage (a companion run's stage-6 progress is his
     * climb as well as the companions').
     */
    synchronized boolean observeLeaderClimb(int row) {
        if (row <= leaderClimbBestRow) return false;
        leaderClimbBestRow = row;
        return true;
    }

    /** Since when every standing companion waits at the top of the maze (0 while one is still climbing). */
    synchronized long observeStageSixAllAtTop(boolean allAtTop, long nowMs) {
        if (!allAtTop) {
            stageSixAllAtTopSinceMs = 0L;
            return 0L;
        }
        if (stageSixAllAtTopSinceMs == 0L) stageSixAllAtTopSinceMs = Math.max(1L, nowMs);
        return nowMs - stageSixAllAtTopSinceMs;
    }

    /** How long the leader has held Alishar's key (0 while he does not). */
    synchronized long observeLeaderKey(boolean holds, long nowMs) {
        if (!holds) {
            leaderKeySinceMs = 0L;
            return 0L;
        }
        if (leaderKeySinceMs == 0L) leaderKeySinceMs = Math.max(1L, nowMs);
        return nowMs - leaderKeySinceMs;
    }

    /** When a companion run first saw its clear (0 before); its after-clear limit counts from here. */
    synchronized long clearedAtMs() { return clearedAtMs; }

    synchronized void markCleared(long nowMs) { if (clearedAtMs == 0L) clearedAtMs = Math.max(1L, nowMs); }

    /**
     * After the clear the leader logged off, went down or no longer leads the party: the companions stop waiting on
     * him, claim and go. It is never a failure.
     */
    synchronized boolean leaderReleased() { return leaderReleased; }

    synchronized void markLeaderReleased() { leaderReleased = true; }

    /** The companion that took (or takes) Alishar's key to the leader; 0 before one is chosen. */
    public synchronized int keyCarrierId() { return keyCarrierId; }

    synchronized void setKeyCarrierId(int characterId) { keyCarrierId = characterId; }

    /** True the first time a withheld rescue (kind, site, member, phase) is noted in this run. */
    synchronized boolean claimWithheldNote(String key) { return key != null && withheldNotes.add(key); }

    /** True the first time a companion-run line key is used; each key is said once a run. */
    synchronized boolean narrateOnce(String key) { return key != null && narratedKeys.add(key); }

    /** When the companion run's leader first stood inside on stage 1 (0 before). */
    synchronized long enteredEventAtMs() { return enteredEventAtMs; }

    synchronized void markEnteredEvent(long nowMs) { if (enteredEventAtMs == 0L) enteredEventAtMs = nowMs; }

    /** Every companion ever admitted to this run (a departed one stays: its drops are still the run's). */
    synchronized boolean companionOfRun(int characterId) { return companionsOfRun.contains(characterId); }

    synchronized void addCompanionOfRun(int characterId) { companionsOfRun.add(characterId); }

    /**
     * What {@link #reserveDeparture} answered a dead member: leave now (the departure is then reserved until
     * {@link #endDeparture}) or stay down. heldMs is how long it was held before leaving, or -1 when it was not held;
     * firstHold is true only for the first answer of a hold.
     */
    record Departure(boolean proceed, boolean firstHold, long heldMs, int registered, int othersInFlight) { }

    /**
     * A dead member may leave the event while more than {@code floor} registered members would stay, every departure
     * still in flight counted as gone (LudiPQ.js ends the event when a member leaves at the floor). registeredIds is
     * read here, under this monitor, so a departure that has just finished is seen as gone; the event's read lock
     * nests inside this monitor, as when the coordinator reads the event's players.
     */
    synchronized Departure reserveDeparture(int id, Supplier<Set<Integer>> registeredIds, int floor, long nowMs) {
        Set<Integer> registered = registeredIds.get();
        int othersInFlight = (int) departuresInFlight.stream().filter(other -> other != id).count();
        long staying = registered.stream().filter(member -> !departuresInFlight.contains(member)).count();
        if (staying > floor) {
            departuresInFlight.add(id);
            Long since = heldSinceMs.remove(id);
            return new Departure(true, false, since == null ? -1L : Math.max(0L, nowMs - since),
                    registered.size(), othersInFlight);
        }
        Long since = heldSinceMs.putIfAbsent(id, nowMs);
        return new Departure(false, since == null, since == null ? 0L : Math.max(0L, nowMs - since),
                registered.size(), othersInFlight);
    }

    /** Ends a reserved departure (its respawn returned or threw). */
    synchronized void endDeparture(int id) { departuresInFlight.remove(id); }

    /** Forgets the hold of a member that leaves without the floor check: how long it was held, or -1 if it was not. */
    synchronized long releaseHold(int id, long nowMs) {
        Long since = heldSinceMs.remove(id);
        return since == null ? -1L : Math.max(0L, nowMs - since);
    }

    /** Dead members the departure gate holds on the map now. */
    synchronized int heldDeadCount() { return heldSinceMs.size(); }

    /** Takes a member out of the run (a companion that tapped out); the event leader is never taken out. */
    public synchronized void removeMember(int characterId) {
        if (characterId == eventLeaderId) {
            throw new IllegalArgumentException("the LPQ event leader cannot be removed from his run");
        }
        members.remove(characterId);
    }
}
