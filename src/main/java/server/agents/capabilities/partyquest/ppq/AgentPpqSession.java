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
    private String potionsAtEntry = "{}";
    private long stageEnteredAtMs;
    private boolean companionAdopted;
    private boolean memberCoordinationChatEnabled;
    private AgentPqPaceCalibration paceCalibration;
    private boolean failureClaimed;
    private final Set<String> narrated = new HashSet<>();
    private final Set<Integer> departuresInFlight = new LinkedHashSet<>();
    private final Map<Integer, Long> heldSinceMs = new LinkedHashMap<>();
    private Map<Integer, Integer> lastMobCounts = Map.of();
    private long clearedAtMs = -1L;
    private Map<String, Integer> lastItemCounts = Map.of();
    private Map<String, Integer> lastReactorStates = Map.of();

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
    /**
     * Moves to {@code next} when it is not behind; the change, or null when nothing changed. Only a change is progress:
     * asking for the phase the run is already in (a Guon talk the lobby refused) marks none.
     */
    public synchronized Change transition(Phase next, long nowMs) {
        if (next == null || terminal() || next.ordinal() < phase.ordinal()) return null;
        if (next == phase) return null;
        Change change = new Change(phase, next, nowMs - phaseEnteredAtMs, nowMs - startedAtMs);
        phaseEnteredAtMs = nowMs;
        phase = next;
        markProgress(nowMs);
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

    /**
     * An Agent member leaves the run for good (its runtime went away): true when it left, false (nothing changed) for
     * the event leader, a human, a stranger or the last Agent. It never holds the coordinator lease again: a lease it
     * held passes to the next Agent at once.
     */
    public synchronized boolean departAgent(int id) {
        AgentPpqMemberState member = members.get(id);
        if (member == null || member.memberType() != AgentPpqMemberState.MemberType.AGENT || id == eventLeaderId) {
            return false;
        }
        Integer next = members.values().stream()
                .filter(other -> other.characterId() != id
                        && other.memberType() == AgentPpqMemberState.MemberType.AGENT)
                .map(AgentPpqMemberState::characterId).findFirst().orElse(null);
        if (next == null) return false;
        members.remove(id);
        departuresInFlight.remove(id);
        heldSinceMs.remove(id);
        if (executionAgentId == id) {
            executionAgentId = next;
            executionLeaseUntilMs = 0L;
        }
        return true;
    }

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

    /**
     * One pass's readings against the last pass's: progress when a map's live mob count dropped (a kill; a respawn is
     * not one), a member's medal or key count changed, or a reactor changed state. Only consecutive readings are
     * compared: the first pass, and a map or member the last pass did not read, only set the baseline. True when it
     * marked progress.
     */
    synchronized boolean observeProgress(Map<Integer, Integer> mobCounts, Map<String, Integer> itemCounts,
                                         Map<String, Integer> reactorStates, long nowMs) {
        boolean progress = mobCounts.entrySet().stream().anyMatch(count -> {
            Integer before = lastMobCounts.get(count.getKey());
            return before != null && count.getValue() < before;
        }) || changed(lastItemCounts, itemCounts) || changed(lastReactorStates, reactorStates);
        lastMobCounts = Map.copyOf(mobCounts);
        lastItemCounts = Map.copyOf(itemCounts);
        lastReactorStates = Map.copyOf(reactorStates);
        if (progress) markProgress(nowMs);
        return progress;
    }

    private static boolean changed(Map<String, Integer> before, Map<String, Integer> now) {
        return now.entrySet().stream().anyMatch(entry -> {
            Integer last = before.get(entry.getKey());
            return last != null && !last.equals(entry.getValue());
        });
    }

    /** Records the stage the leader stands on; the change, or null when it is the same. Marks no progress. */
    synchronized StageChange observeStage(AgentPpqStage stage, long nowMs) {
        if (stage == null || stage == leaderStage) return null;
        StageChange change = new StageChange(leaderStage, stage, nowMs - stageEnteredAtMs, nowMs - startedAtMs);
        leaderStage = stage;
        stageEnteredAtMs = nowMs;
        return change;
    }

    synchronized AgentPpqStage leaderStage() { return leaderStage; }

    /** The companions' potions as the run bound its event, for the release's 'PPQ potions:' line. */
    synchronized String potionsAtEntry() { return potionsAtEntry; }

    synchronized void setPotionsAtEntry(String potions) { potionsAtEntry = potions == null ? "{}" : potions; }

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

    /** When the run first saw its clear: set by the first call, the same time on every later one. */
    synchronized long observeCleared(long nowMs) {
        if (clearedAtMs < 0L) clearedAtMs = nowMs;
        return clearedAtMs;
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
