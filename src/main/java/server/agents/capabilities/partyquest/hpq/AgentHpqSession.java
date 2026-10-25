package server.agents.capabilities.partyquest.hpq;

import scripting.event.EventInstanceManager;
import server.agents.capabilities.partyquest.AgentCompanionRunSession;
import server.agents.capabilities.partyquest.pace.AgentPqPaceCalibration;

import java.awt.Point;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Isolated party-level HPQ state machine aggregate. */
public final class AgentHpqSession implements AgentCompanionRunSession {
    public enum Mode { PRODUCTION, BACKGROUND_POPULATION, TEST_OBSERVATION }
    public enum PartyOwnership { EXTERNAL, HPQ_OWNED }
    public enum BonusMode { SKIP, ENTER, HUMAN_CHOICE }
    public enum Phase {
        PREPARING, ENTERING, COLLECTING_SEEDS, PLANTING_SEEDS, DEFENDING_BUNNY,
        DELIVERING_CAKES, BONUS_DECISION, BONUS_FARMING, CLAIMING_REWARD,
        EXITING, COMPLETED, FAILED
    }

    private final String sessionId = UUID.randomUUID().toString();
    private final Mode mode;
    private final long seed;
    private final int operatorId;
    private final int requestedPartySize;
    private final long startedAtMs;
    private final Map<Integer, AgentHpqMemberState> members = new LinkedHashMap<>();
    private final Set<Integer> companionIds = new HashSet<>();
    private Phase phase = Phase.PREPARING;
    private int eventLeaderId;
    private int executionAgentId;
    private long executionLeaseUntilMs;
    private long lastProgressAtMs;
    private long phaseEnteredAtMs;
    private long readyAtMs;
    private boolean defenseHostilesPresent;
    private int defenseWaveOrdinal;
    private boolean paused;
    private boolean terminating;
    private String failure = "";
    private EventInstanceManager eventInstance;
    private PartyOwnership partyOwnership = PartyOwnership.EXTERNAL;
    private BonusMode bonusMode = BonusMode.SKIP;
    private boolean rewardEligibilityFrozen;
    private boolean companionAdopted;
    private boolean citizenRun;
    private boolean memberCoordinationChatEnabled;
    private final Set<Integer> recollectDue = new HashSet<>();
    // A companion run's play (AgentHpqCoordinator); read and written under the session's lock.
    private final Set<String> narrationKeys = new HashSet<>();
    private final Map<Integer, Long> padPlantedAtMs = new HashMap<>();
    private final Map<Integer, Map<Integer, Long>> humanSeedSinceMs = new HashMap<>();
    private long clearedAtMs;
    private boolean bunnySeen;
    private boolean bunnyLost;
    private Point bunnyAnchor;
    private long bunnyLastHp = -1L;
    private int bunnyHurtLines;
    private long bunnyHurtLineAtMs;
    private int cakeCollectorId;
    private long handOverStartedAtMs;
    private boolean handOverToGrowlie;
    private boolean handOverRecollect;
    private int handOvers;
    private int recollects;
    private int recollectStackObjectId;
    private int recollectHeldBefore;
    private boolean leaderToldGrowlie;
    private String potionsAtEntry = "";
    private long enteredEventAtMs;
    private boolean leaderReleased;
    private final AgentHpqPaceMarks pace = new AgentHpqPaceMarks();

    public AgentHpqSession(Mode mode, long seed, int operatorId, int requestedPartySize, long nowMs) {
        if (mode == null || operatorId <= 0 || requestedPartySize < 3 || requestedPartySize > 6
                || nowMs < 0L) {
            throw new IllegalArgumentException("valid HPQ session values are required");
        }
        this.mode = mode;
        this.seed = seed;
        this.operatorId = operatorId;
        this.requestedPartySize = requestedPartySize;
        this.startedAtMs = nowMs;
        this.lastProgressAtMs = nowMs;
        this.phaseEnteredAtMs = nowMs;
    }

    public synchronized void addMember(int characterId, AgentHpqMemberState.MemberType type) {
        if (members.size() >= requestedPartySize && !members.containsKey(characterId)) {
            throw new IllegalStateException("HPQ session is full");
        }
        AgentHpqMemberState member = members.computeIfAbsent(characterId, id -> new AgentHpqMemberState(id, type));
        if (member.memberType() == AgentHpqMemberState.MemberType.AGENT) companionIds.add(characterId);
    }

    /**
     * A companion that has been a member of this run, also after it was taken out of it (removeMember): the cake
     * stacks it dropped stay the run's to nudge about and pick back up.
     */
    public synchronized boolean companionOfRun(int characterId) { return companionIds.contains(characterId); }

    public synchronized void removeMember(int characterId) {
        members.remove(characterId);
    }

    public synchronized void setLeadership(int eventLeaderId, int executionAgentId) {
        if (!members.containsKey(eventLeaderId) || !members.containsKey(executionAgentId)
                || members.get(executionAgentId).memberType() != AgentHpqMemberState.MemberType.AGENT) {
            throw new IllegalArgumentException("HPQ leaders must be registered members and execution must be Agent-owned");
        }
        this.eventLeaderId = eventLeaderId;
        this.executionAgentId = executionAgentId;
        members.get(eventLeaderId).assign(AgentHpqMemberState.Role.EVENT_LEADER, 0);
    }

    public synchronized boolean claimExecutionTick(int characterId, long nowMs, long leaseMs) {
        AgentHpqMemberState member = members.get(characterId);
        if (member == null || member.memberType() != AgentHpqMemberState.MemberType.AGENT) return false;
        if (executionAgentId != characterId && nowMs < executionLeaseUntilMs) return false;
        executionAgentId = characterId;
        executionLeaseUntilMs = nowMs + Math.max(250L, leaseMs);
        return true;
    }

    public synchronized boolean claimExpiredExecutionTick(int characterId, long nowMs, long leaseMs) {
        AgentHpqMemberState member = members.get(characterId);
        if (member == null || member.memberType() != AgentHpqMemberState.MemberType.AGENT
                || nowMs < executionLeaseUntilMs) return false;
        executionAgentId = characterId;
        executionLeaseUntilMs = nowMs + Math.max(250L, leaseMs);
        return true;
    }

    public synchronized void transition(Phase phase, long nowMs) {
        if (phase == null || terminal()) return;
        this.phase = phase;
        this.phaseEnteredAtMs = nowMs;
        markProgress(nowMs);
    }

    public synchronized void fail(String reason, long nowMs) {
        failure = reason == null ? "" : reason.trim();
        phase = Phase.FAILED;
        markProgress(nowMs);
    }

    public synchronized void complete(long nowMs) {
        phase = Phase.COMPLETED;
        markProgress(nowMs);
    }

    public synchronized boolean beginTermination() {
        if (terminating) return false;
        terminating = true;
        return true;
    }

    /** True once a release has begun (it lets go of the full-rate holds before the phase turns terminal). */
    public synchronized boolean terminationStarted() { return terminating; }

    public synchronized void markProgress(long nowMs) {
        lastProgressAtMs = Math.max(lastProgressAtMs, nowMs);
    }

    public synchronized boolean terminal() {
        return phase == Phase.COMPLETED || phase == Phase.FAILED;
    }

    @Override
    public String sessionId() { return sessionId; }
    public Mode mode() { return mode; }
    public long seed() { return seed; }
    public int operatorId() { return operatorId; }
    public int requestedPartySize() { return requestedPartySize; }
    public long startedAtMs() { return startedAtMs; }
    public synchronized Phase phase() { return phase; }
    public synchronized int eventLeaderId() { return eventLeaderId; }
    public synchronized int executionAgentId() { return executionAgentId; }
    public synchronized long lastProgressAtMs() { return lastProgressAtMs; }
    public synchronized long phaseEnteredAtMs() { return phaseEnteredAtMs; }
    public synchronized long readyAtMs() { return readyAtMs; }
    public synchronized void setReadyAtMs(long readyAtMs) {
        this.readyAtMs = Math.max(0L, readyAtMs);
    }
    public synchronized boolean observeDefenseHostiles(boolean present) {
        boolean newWave = present && !defenseHostilesPresent;
        defenseHostilesPresent = present;
        if (newWave) defenseWaveOrdinal++;
        return newWave;
    }
    public synchronized int defenseWaveOrdinal() { return defenseWaveOrdinal; }
    public synchronized boolean paused() { return paused; }
    public synchronized void setPaused(boolean paused) { this.paused = paused; }
    public synchronized String failure() { return failure; }
    public synchronized EventInstanceManager eventInstance() { return eventInstance; }
    public synchronized void bindEventInstance(EventInstanceManager eventInstance) {
        this.eventInstance = eventInstance;
    }
    public synchronized void clearEventInstance() { eventInstance = null; }
    public synchronized PartyOwnership partyOwnership() { return partyOwnership; }
    public synchronized void setPartyOwnership(PartyOwnership partyOwnership) {
        if (partyOwnership == null) throw new IllegalArgumentException("HPQ party ownership is required");
        this.partyOwnership = partyOwnership;
    }
    public synchronized BonusMode bonusMode() { return bonusMode; }
    public synchronized void setBonusMode(BonusMode bonusMode) {
        if (bonusMode == null) throw new IllegalArgumentException("HPQ bonus mode is required");
        this.bonusMode = bonusMode;
    }
    public synchronized AgentHpqMemberState member(int characterId) { return members.get(characterId); }
    public synchronized Collection<AgentHpqMemberState> members() { return java.util.List.copyOf(members.values()); }
    public synchronized int memberCount() { return members.size(); }

    /**
     * A human-led run of owner-bound companions, admitted by AgentHpqAdmissionService.admit. Every companion-run
     * behaviour is gated on it; stock, background and GM-observed runs leave it false.
     */
    @Override
    public synchronized boolean companionAdopted() { return companionAdopted; }

    /** Set only by the public admit, before the session is published. */
    synchronized void setCompanionAdopted(boolean adopted) { companionAdopted = adopted; }

    /**
     * A party of the server's ambient citizens, led by a citizen, that went in on its own: with a human member who took
     * a citizen's invite, or none (AgentHpqAdmissionService.admitCitizens, before the session is published). It runs the
     * stock Agent-led path, with what a run with players in it needs: its Agents hold full rate in the instance, it says
     * no status lines, a downed citizen taps out through its host while three stand, and a human is never waited for at
     * Tory. None of a companion run's human-leader play applies.
     */
    public synchronized boolean citizenRun() { return citizenRun; }

    synchronized void setCitizenRun(boolean citizen) { citizenRun = citizen; }

    public synchronized boolean memberCoordinationChatEnabled() { return memberCoordinationChatEnabled; }

    @Override
    public synchronized void setMemberCoordinationChatEnabled(boolean enabled) {
        memberCoordinationChatEnabled = enabled;
    }

    /** HPQ paces nothing by a leader's calibration, so it keeps none. */
    @Override
    public synchronized void setPaceCalibration(AgentPqPaceCalibration calibration) {
    }

    /**
     * True while this companion-run member may take back a rice-cake stack a player dropped (its own hand-over that
     * the leader left lying until it would expire); otherwise such a stack is the leader's to pick up.
     */
    public synchronized boolean recollectDue(int characterId) { return recollectDue.contains(characterId); }

    synchronized void setRecollectDue(int characterId, boolean due) {
        if (due) recollectDue.add(characterId);
        else recollectDue.remove(characterId);
    }

    /** True when the line was not said yet this run; each key is said once. */
    synchronized boolean narrateOnce(String key) { return key != null && narrationKeys.add(key); }

    /** When the run was cleared (the leader reached the clear map or the event cleared); 0 before. */
    synchronized long clearedAtMs() { return clearedAtMs; }
    synchronized void markCleared(long nowMs) { if (clearedAtMs == 0L) clearedAtMs = Math.max(1L, nowMs); }

    /** When an Agent last dropped a seed on this moonflower (by reactor id); 0 when none did. */
    synchronized long padPlantedAtMs(int reactorId) { return padPlantedAtMs.getOrDefault(reactorId, 0L); }
    synchronized void markPadPlanted(int reactorId, long nowMs) { padPlantedAtMs.put(reactorId, nowMs); }

    /** Since when this human member has carried this needed seed, as first seen now. */
    synchronized long humanSeedHeldSinceMs(int characterId, int seedItemId, long nowMs) {
        return humanSeedSinceMs.computeIfAbsent(characterId, id -> new HashMap<>())
                .computeIfAbsent(seedItemId, seed -> nowMs);
    }

    /** Forgets every seed this human member no longer carries (or is no longer needed). */
    synchronized void keepHumanSeeds(int characterId, Collection<Integer> carried) {
        Map<Integer, Long> held = humanSeedSinceMs.get(characterId);
        if (held != null) held.keySet().retainAll(carried);
    }

    synchronized boolean bunnySeen() { return bunnySeen; }
    synchronized void markBunnySeen() { bunnySeen = true; }
    /** The Moon Bunny was seen and is gone before the clear: the run's failure names it. */
    synchronized boolean bunnyLost() { return bunnyLost; }
    synchronized void markBunnyLost() { bunnyLost = true; }
    synchronized Point bunnyAnchor() { return bunnyAnchor == null ? null : new Point(bunnyAnchor); }
    synchronized void setBunnyAnchor(Point anchor) { bunnyAnchor = anchor == null ? null : new Point(anchor); }
    synchronized long bunnyLastHp() { return bunnyLastHp; }
    synchronized void setBunnyLastHp(long hp) { bunnyLastHp = hp; }
    synchronized int bunnyHurtLines() { return bunnyHurtLines; }
    synchronized long bunnyHurtLineAtMs() { return bunnyHurtLineAtMs; }
    synchronized void noteBunnyHurtLine(long nowMs) {
        bunnyHurtLines++;
        bunnyHurtLineAtMs = nowMs;
    }

    /** The companion on the centre post that collects the Moon Bunny's cakes; 0 for none. */
    synchronized int cakeCollectorId() { return cakeCollectorId; }
    synchronized void setCakeCollectorId(int characterId) { cakeCollectorId = Math.max(0, characterId); }

    /** When the collector set out with its stack for the leader (or Growlie); 0 while none is under way. */
    synchronized long handOverStartedAtMs() { return handOverStartedAtMs; }
    synchronized boolean handOverToGrowlie() { return handOverToGrowlie; }
    /** This hand-over brings back a stack the leader left lying (a re-collect). */
    synchronized boolean handOverRecollect() { return handOverRecollect; }
    synchronized void startHandOver(long nowMs, boolean recollect) {
        handOverStartedAtMs = Math.max(1L, nowMs);
        handOverToGrowlie = false;
        handOverRecollect = recollect;
    }
    synchronized void sendHandOverToGrowlie() { handOverToGrowlie = true; }
    /** Ends the hand-over under way; a dropped one counts. */
    synchronized void endHandOver(boolean dropped) {
        if (dropped) handOvers++;
        handOverStartedAtMs = 0L;
        handOverToGrowlie = false;
        handOverRecollect = false;
    }
    synchronized int handOvers() { return handOvers; }

    /** Re-collects begun so far, and the stack (by object id) the collector is sent to pick back up; 0 for none. */
    synchronized int recollects() { return recollects; }
    synchronized int recollectStackObjectId() { return recollectStackObjectId; }
    /** The cakes the collector held as it was sent back for the stack: more after it is gone means it picked it up. */
    synchronized int recollectHeldBefore() { return recollectHeldBefore; }
    synchronized void beginRecollect(int collectorId, int stackObjectId, int heldBefore) {
        recollectStackObjectId = stackObjectId;
        recollectHeldBefore = Math.max(0, heldBefore);
        setRecollectDue(collectorId, true);
    }
    /** The re-collect ends: the collector picked the stack up ({@code pickedUp}) or it is gone. */
    synchronized void endRecollect(boolean pickedUp) {
        if (pickedUp) recollects++;
        recollectStackObjectId = 0;
        recollectHeldBefore = 0;
        recollectDue.clear();
    }

    /** A hand-over line already told the leader to take the cakes to Growlie. */
    synchronized boolean leaderToldGrowlie() { return leaderToldGrowlie; }
    synchronized void markLeaderToldGrowlie() { leaderToldGrowlie = true; }

    /** When the leader was first seen standing in the event (the script's timer starts as he enters); 0 before. */
    synchronized long enteredEventAtMs() { return enteredEventAtMs; }
    synchronized void markEnteredEvent(long nowMs) {
        if (enteredEventAtMs == 0L) enteredEventAtMs = Math.max(1L, nowMs);
    }

    /**
     * After the clear the leader logged off, went down or is no longer the party's leader: the companions stop waiting
     * on him, claim and go. It is never a failure.
     */
    synchronized boolean leaderReleased() { return leaderReleased; }
    synchronized void markLeaderReleased() { leaderReleased = true; }

    /** The companion run's pace marks (AgentHpqCoordinator sets them; a stock run leaves them unseen). */
    AgentHpqPaceMarks pace() { return pace; }

    /** The companions' potions as the run entered the stage, for the release log line. */
    synchronized String potionsAtEntry() { return potionsAtEntry; }
    synchronized void setPotionsAtEntry(String potions) { potionsAtEntry = potions == null ? "" : potions; }

    public synchronized void freezeRewardEligibility() { rewardEligibilityFrozen = true; }
    public synchronized boolean rewardEligibilityFrozen() { return rewardEligibilityFrozen; }
    public synchronized boolean beginRewardClaim(int characterId) {
        AgentHpqMemberState member = members.get(characterId);
        return rewardEligibilityFrozen && member != null && member.beginRewardClaim();
    }
    public synchronized boolean completeRewardClaim(int characterId) {
        AgentHpqMemberState member = members.get(characterId);
        return member != null && member.completeRewardClaim();
    }
    public synchronized void cancelRewardClaim(int characterId) {
        AgentHpqMemberState member = members.get(characterId);
        if (member != null) member.cancelRewardClaim();
    }
    public synchronized void forfeitReward(int characterId) {
        AgentHpqMemberState member = members.get(characterId);
        if (member != null) member.forfeitReward();
    }
    public synchronized boolean allRewardsResolved() {
        return rewardEligibilityFrozen && members.values().stream()
                .allMatch(AgentHpqMemberState::rewardResolved);
    }
}
