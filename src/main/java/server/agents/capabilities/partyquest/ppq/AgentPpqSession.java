package server.agents.capabilities.partyquest.ppq;

import scripting.event.EventInstanceManager;
import server.agents.capabilities.partyquest.AgentCompanionRunSession;
import server.agents.capabilities.partyquest.pace.AgentPqPaceCalibration;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Independent PPQ aggregate; no state is shared with another PQ implementation. A session the public companion admit
 * built is a companion run ({@link #companionAdopted}, set before it is published); every other mode keeps its rules.
 */
public final class AgentPpqSession implements AgentCompanionRunSession {
    public enum Mode { TEST_OBSERVATION, AUTONOMOUS, HUMAN_MEMBER, HUMAN_LEADER }
    public enum Phase { PREPARING, ENTERING, ACTIVE, CLEARING, EXITING, COMPLETED, FAILED }

    /** A phase change: the coordinator logs it, outside this monitor. */
    public record Change(Phase from, Phase to, long phaseMs, long runMs) {
    }

    /** The leader's stage changed: the coordinator logs it, outside this monitor. */
    public record StageChange(AgentPpqStage from, AgentPpqStage to, long stageMs, long runMs) {
    }

    /**
     * What {@link #reserveDeparture} answered a dead member: leave now (the departure is then reserved until
     * {@link #endDeparture}) or stay down. heldMs is how long it was held before leaving, or -1 when it was not held;
     * firstHold is true only for the first answer of a hold.
     */
    public record Departure(boolean proceed, boolean firstHold, long heldMs, int registered, int othersInFlight) {
    }
    private static final long MIN_LEASE_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.ppq.AgentPpqSession.MIN_LEASE_MS");
    private final String sessionId = UUID.randomUUID().toString();
    private final Mode mode;
    private final long seed;
    private final int operatorId;
    private boolean skipChestRooms;
    private final long startedAtMs;
    private final Map<Integer, AgentPpqMemberState> members = new LinkedHashMap<>();
    private Phase phase = Phase.PREPARING;
    private int eventLeaderId;
    private int executionAgentId;
    private long executionLeaseUntilMs;
    private long lastProgressAtMs;
    private boolean chestOneComplete;
    private boolean chestTwoComplete;
    private boolean paused;
    private boolean terminating;
    private String failure = "";
    private EventInstanceManager eventInstance;
    private long phaseEnteredAtMs;
    private AgentPpqStage leaderStage = AgentPpqStage.LOBBY;
    private long stageEnteredAtMs;
    private boolean companionAdopted;
    private boolean memberCoordinationChatEnabled;
    private AgentPqPaceCalibration paceCalibration;
    private boolean failureClaimed;
    private final Set<String> narrated = new HashSet<>();
    private final Set<Integer> departuresInFlight = new LinkedHashSet<>();
    private final Map<Integer, Long> heldSinceMs = new LinkedHashMap<>();

    public AgentPpqSession(Mode mode, long seed, int operatorId, boolean skipChestRooms, long nowMs) {
        if (mode == null || operatorId <= 0 || nowMs < 0L) throw new IllegalArgumentException("valid PPQ session required");
        this.mode = mode; this.seed = seed; this.operatorId = operatorId;
        this.skipChestRooms = skipChestRooms; this.startedAtMs = nowMs; this.lastProgressAtMs = nowMs;
        this.phaseEnteredAtMs = nowMs;
        this.stageEnteredAtMs = nowMs;
    }
    public synchronized void addMember(int id, AgentPpqMemberState.MemberType type) {
        if (members.size() >= AgentPpqDefinition.PARTY_SIZE
                || members.putIfAbsent(id, new AgentPpqMemberState(id, type)) != null) {
            throw new IllegalStateException("invalid or duplicate PPQ member");
        }
    }
    public synchronized void setLeadership(int leaderId, int executionId) {
        AgentPpqMemberState executor = members.get(executionId);
        if (!members.containsKey(leaderId) || executor == null
                || executor.memberType() != AgentPpqMemberState.MemberType.AGENT) {
            throw new IllegalArgumentException("PPQ leader and Agent executor must be members");
        }
        eventLeaderId = leaderId; executionAgentId = executionId;
    }
    public synchronized boolean claimExecutionTick(int id, long nowMs, long leaseMs) {
        AgentPpqMemberState member = members.get(id);
        if (member == null || member.memberType() != AgentPpqMemberState.MemberType.AGENT) return false;
        if (id != executionAgentId && nowMs < executionLeaseUntilMs) return false;
        executionAgentId = id; executionLeaseUntilMs = nowMs + Math.max(MIN_LEASE_MS, leaseMs); return true;
    }
    /** Moves to {@code next} when it is not behind; the change, or null when nothing changed. */
    public synchronized Change transition(Phase next, long nowMs) {
        if (next == null || terminal() || next.ordinal() < phase.ordinal()) return null;
        Change change = next == phase ? null
                : new Change(phase, next, nowMs - phaseEnteredAtMs, nowMs - startedAtMs);
        if (change != null) phaseEnteredAtMs = nowMs;
        phase = next; markProgress(nowMs);
        return change;
    }
    public synchronized void markChestComplete(int mapId, long nowMs) {
        boolean changed = false;
        if (mapId == AgentPpqDefinition.CHEST_ONE_MAP && !chestOneComplete) {
            chestOneComplete = true; changed = true;
        }
        if (mapId == AgentPpqDefinition.CHEST_TWO_MAP && !chestTwoComplete) {
            chestTwoComplete = true; changed = true;
        }
        if (changed) markProgress(nowMs);
    }
    public synchronized boolean chestCompleteForDeck(int mapId) {
        return mapId == AgentPpqDefinition.DECK_ONE_MAP ? chestOneComplete
                : mapId == AgentPpqDefinition.DECK_TWO_MAP && chestTwoComplete;
    }
    public synchronized boolean chestComplete(int mapId) {
        return mapId == AgentPpqDefinition.CHEST_ONE_MAP ? chestOneComplete
                : mapId == AgentPpqDefinition.CHEST_TWO_MAP && chestTwoComplete;
    }
    public synchronized void markProgress(long nowMs) { lastProgressAtMs = Math.max(lastProgressAtMs, nowMs); }
    public synchronized void fail(String reason, long nowMs) { failure = reason; phase = Phase.FAILED; markProgress(nowMs); }
    public synchronized void complete(long nowMs) { phase = Phase.COMPLETED; markProgress(nowMs); }
    public synchronized boolean terminal() { return phase == Phase.COMPLETED || phase == Phase.FAILED; }
    public synchronized boolean beginTermination() { if (terminating) return false; terminating = true; return true; }
    public String sessionId() { return sessionId; }
    public Mode mode() { return mode; }
    public long seed() { return seed; }
    public int operatorId() { return operatorId; }
    public synchronized boolean skipChestRooms() { return skipChestRooms; }

    /** Sets the chest-room choice of a companion run, before the session is published. */
    synchronized void setSkipChestRooms(boolean value) { skipChestRooms = value; }

    @Override
    public synchronized boolean companionAdopted() { return companionAdopted; }

    /** Marks a session the public companion admit built; set before registerComplete publishes it. */
    synchronized void setCompanionAdopted(boolean value) { companionAdopted = value; }

    @Override
    public synchronized void setMemberCoordinationChatEnabled(boolean enabled) {
        memberCoordinationChatEnabled = enabled;
    }

    synchronized boolean memberCoordinationChatEnabled() { return memberCoordinationChatEnabled; }

    @Override
    public synchronized void setPaceCalibration(AgentPqPaceCalibration calibration) { paceCalibration = calibration; }

    synchronized AgentPqPaceCalibration paceCalibration() { return paceCalibration; }

    /** Takes a member out of the session; true when it was one. */
    public synchronized boolean removeMember(int id) { return members.remove(id) != null; }

    /** True for the first of any number of threads that want to fail this run; every later one does nothing. */
    synchronized boolean claimFailure() {
        if (failureClaimed) return false;
        failureClaimed = true;
        return true;
    }

    /** True the first time a line's key is used in this run. */
    synchronized boolean narrateOnce(String key) { return key != null && !key.isBlank() && narrated.add(key); }

    /** Records the stage the leader stands on; the change, or null when it is the same. Marks no progress. */
    synchronized StageChange observeStage(AgentPpqStage stage, long nowMs) {
        if (stage == null || stage == leaderStage) return null;
        StageChange change = new StageChange(leaderStage, stage, nowMs - stageEnteredAtMs, nowMs - startedAtMs);
        leaderStage = stage;
        stageEnteredAtMs = nowMs;
        return change;
    }

    synchronized AgentPpqStage leaderStage() { return leaderStage; }

    /** Whether the run's release has begun. */
    public synchronized boolean terminationStarted() { return terminating; }

    /**
     * A dead member may leave the event while more than {@code floor} registered members would stay, every departure
     * still in flight counted as gone (PiratePQ.js ends the event when a member leaves at its floor).
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

    /** Ends a reserved departure (its respawn ran or threw). */
    synchronized void endDeparture(int id) { departuresInFlight.remove(id); }

    /** Forgets the hold of a member that leaves without the floor check: how long it was held, or -1. */
    synchronized long releaseHold(int id, long nowMs) {
        Long since = heldSinceMs.remove(id);
        return since == null ? -1L : Math.max(0L, nowMs - since);
    }

    /** Dead members the departure gate holds on the map now. */
    synchronized int heldDeadCount() { return heldSinceMs.size(); }
    public long startedAtMs() { return startedAtMs; }
    public synchronized Phase phase() { return phase; }
    public synchronized int eventLeaderId() { return eventLeaderId; }
    public synchronized int executionAgentId() { return executionAgentId; }
    public synchronized long lastProgressAtMs() { return lastProgressAtMs; }
    public synchronized boolean paused() { return paused; }
    public synchronized void setPaused(boolean value) { paused = value; }
    public synchronized String failure() { return failure; }
    public synchronized EventInstanceManager eventInstance() { return eventInstance; }
    public synchronized void bindEventInstance(EventInstanceManager value) { eventInstance = value; }
    public synchronized void clearEventInstance() { eventInstance = null; }
    public synchronized AgentPpqMemberState member(int id) { return members.get(id); }
    public synchronized Collection<AgentPpqMemberState> members() { return List.copyOf(members.values()); }
    public synchronized int memberCount() { return members.size(); }
}
