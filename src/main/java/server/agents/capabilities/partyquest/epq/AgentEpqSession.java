package server.agents.capabilities.partyquest.epq;

import scripting.event.EventInstanceManager;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;

/** EPQ aggregate root; no state is shared with another party quest. */
public final class AgentEpqSession {
    public enum Mode { TEST_OBSERVATION, AUTONOMOUS, HUMAN_ASSISTED }
    public enum Phase {
        PREPARING, ENTERING, STAGE_ONE, STAGE_TWO, STAGE_THREE, STAGE_FOUR,
        STAGE_FIVE, BOSS, REWARD, EXITING, COMPLETED, FAILED
    }

    private final String sessionId = UUID.randomUUID().toString();
    private final Mode mode;
    private final long seed;
    private final int operatorId;
    private final long startedAtMs;
    private final Map<Integer, AgentEpqMemberState> members = new LinkedHashMap<>();
    private final Set<String> announcements = new HashSet<>();
    private Phase phase = Phase.PREPARING;
    private int eventLeaderId;
    private int executionAgentId;
    private long executionLeaseUntilMs;
    private long lastProgressAtMs;
    private long phaseEnteredAtMs;
    private boolean paused;
    private boolean terminating;
    private boolean rewardHit;
    private boolean stageTwoBottlesSecured;
    private boolean stageFiveStoneHandedOff;
    private long bossClearedAtMs;
    private long progressSignature = Long.MIN_VALUE;
    private String failure = "";
    private EventInstanceManager eventInstance;

    public AgentEpqSession(Mode mode, long seed, int operatorId, long nowMs) {
        if (mode == null || operatorId <= 0 || nowMs < 0L) throw new IllegalArgumentException("valid EPQ session required");
        this.mode = mode;
        this.seed = seed;
        this.operatorId = operatorId;
        this.startedAtMs = nowMs;
        this.lastProgressAtMs = nowMs;
        this.phaseEnteredAtMs = nowMs;
    }

    public synchronized void addMember(int id, AgentEpqMemberState.MemberType type) {
        if (members.size() >= AgentEpqDefinition.MAX_PARTY_SIZE
                || members.putIfAbsent(id, new AgentEpqMemberState(id, type)) != null) {
            throw new IllegalStateException("invalid or duplicate EPQ member");
        }
    }
    public synchronized void setLeadership(int leaderId, int executionId) {
        if (!members.containsKey(leaderId) || !members.containsKey(executionId)) {
            throw new IllegalArgumentException("EPQ leaders must be members");
        }
        eventLeaderId = leaderId;
        executionAgentId = executionId;
    }
    public synchronized boolean claimExecutionTick(int id, long nowMs, long leaseMs) {
        if (id != executionAgentId && nowMs < executionLeaseUntilMs) return false;
        executionAgentId = id;
        executionLeaseUntilMs = nowMs + Math.max(1L, leaseMs);
        return true;
    }
    public synchronized void transition(Phase next, long nowMs) {
        if (next == null || terminal() || next.ordinal() < phase.ordinal()) return;
        if (next != phase) {
            phase = next;
            phaseEnteredAtMs = nowMs;
            markProgress(nowMs);
        }
    }
    public synchronized void markProgress(long nowMs) { lastProgressAtMs = Math.max(lastProgressAtMs, nowMs); }
    public synchronized void observeProgressSignature(long signature, long nowMs) {
        if (progressSignature != signature) {
            progressSignature = signature;
            markProgress(nowMs);
        }
    }
    public synchronized boolean beginTermination() { if (terminating) return false; terminating = true; return true; }
    public synchronized void fail(String reason, long nowMs) { failure = reason == null ? "EPQ failed" : reason; phase = Phase.FAILED; markProgress(nowMs); }
    public synchronized void complete(long nowMs) { phase = Phase.COMPLETED; markProgress(nowMs); }
    public synchronized boolean terminal() { return phase == Phase.COMPLETED || phase == Phase.FAILED; }

    public String sessionId() { return sessionId; }
    public Mode mode() { return mode; }
    public long seed() { return seed; }
    public int operatorId() { return operatorId; }
    public long startedAtMs() { return startedAtMs; }
    public synchronized Phase phase() { return phase; }
    public synchronized int eventLeaderId() { return eventLeaderId; }
    public synchronized int executionAgentId() { return executionAgentId; }
    public synchronized long lastProgressAtMs() { return lastProgressAtMs; }
    public synchronized long phaseEnteredAtMs() { return phaseEnteredAtMs; }
    public synchronized boolean paused() { return paused; }
    public synchronized void setPaused(boolean value) { paused = value; }
    public synchronized String failure() { return failure; }
    public synchronized EventInstanceManager eventInstance() { return eventInstance; }
    public synchronized void bindEventInstance(EventInstanceManager value) { eventInstance = value; }
    public synchronized void clearEventInstance() { eventInstance = null; }
    public synchronized AgentEpqMemberState member(int id) { return members.get(id); }
    public synchronized Collection<AgentEpqMemberState> members() { return List.copyOf(members.values()); }
    public synchronized int memberCount() { return members.size(); }
    public synchronized boolean rewardHit() { return rewardHit; }
    public synchronized void markRewardHit(long nowMs) { rewardHit = true; markProgress(nowMs); }
    public synchronized boolean stageTwoBottlesSecured() { return stageTwoBottlesSecured; }
    public synchronized void secureStageTwoBottles(long nowMs) {
        if (!stageTwoBottlesSecured) {
            stageTwoBottlesSecured = true;
            markProgress(nowMs);
        }
    }
    public synchronized int workAgentId() {
        AgentEpqMemberState leader = members.get(eventLeaderId);
        if (leader != null && leader.memberType() == AgentEpqMemberState.MemberType.AGENT) {
            return leader.characterId();
        }
        return members.values().stream()
                .filter(member -> member.memberType() == AgentEpqMemberState.MemberType.AGENT)
                .mapToInt(AgentEpqMemberState::characterId).min().orElse(executionAgentId);
    }
    public synchronized boolean stageFiveStoneHandedOff() { return stageFiveStoneHandedOff; }
    public synchronized void markStageFiveStoneHandedOff(long nowMs) {
        if (!stageFiveStoneHandedOff) {
            stageFiveStoneHandedOff = true;
            markProgress(nowMs);
        }
    }
    public synchronized long observeBossCleared(long nowMs) {
        if (bossClearedAtMs == 0L) { bossClearedAtMs = nowMs; markProgress(nowMs); }
        return bossClearedAtMs;
    }
    public synchronized boolean claimAnnouncement(String key) {
        return key != null && !key.isBlank() && announcements.add(key);
    }
}
