package server.agents.capabilities.partyquest.apq;

import scripting.event.EventInstanceManager;
import server.agents.capabilities.partyquest.AgentCompanionRunSession;
import server.agents.capabilities.partyquest.pace.AgentPqPaceCalibration;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One Amorian Challenge companion run: a human leader and his companions, admitted by Amos's desk or the GM harness
 * (APQ plan A3). APQ is played only as a companion run: no Agent-led or Agents-only mode exists, so the session is
 * always {@link #companionAdopted}. AmoriaPQ.js needs all six to the end: a member who leaves, disconnects or presses
 * OK after dying ends the event for everyone (decision 3, stock), so the session holds no departure gate.
 */
public final class AgentApqSession implements AgentCompanionRunSession {
    public enum Phase { PREPARING, ENTERING, ACTIVE, CLEARING, EXITING, COMPLETED, FAILED }

    /** A phase change: the coordinator logs it, outside this monitor. */
    public record Change(Phase from, Phase to, long phaseMs, long runMs) { }

    /** The leader's stage changed: the coordinator logs it, outside this monitor. */
    public record StageChange(AgentApqStage from, AgentApqStage to, long stageMs, long runMs) { }

    private static final long MIN_LEASE_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.apq.AgentApqSession.MIN_LEASE_MS");
    private final String sessionId = UUID.randomUUID().toString();
    private final long seed;
    private final int operatorId;
    private final long startedAtMs;
    private final Map<Integer, AgentApqMemberState> members = new LinkedHashMap<>();
    private Phase phase = Phase.PREPARING;
    private int eventLeaderId;
    private int executionAgentId;
    private long executionLeaseUntilMs;
    private long lastProgressAtMs;
    private boolean paused;
    private boolean terminating;
    private String failure = "";
    private EventInstanceManager eventInstance;
    private long phaseEnteredAtMs;
    private AgentApqStage leaderStage = AgentApqStage.LOBBY;
    private long stageEnteredAtMs;
    private String potionsAtEntry = "{}";
    private boolean memberCoordinationChatEnabled;
    private AgentPqPaceCalibration paceCalibration;
    private boolean failureClaimed;
    private final Set<String> narrated = new HashSet<>();
    private final Set<String> logMarks = new HashSet<>();
    private final Set<Integer> seenHeld = new HashSet<>();
    private long clearedAtMs = -1L;
    private long atExitSinceMs = -1L;
    /** When the run first saw each stage's "stageclear" flag (stage -> ms). */
    private final Map<Integer, Long> stageClearSeenMs = new HashMap<>();
    private long enteredAtMs = -1L;
    private Map<Integer, Integer> lastMobCounts = Map.of();
    private Map<String, Integer> lastItemCounts = Map.of();
    private Map<String, Integer> lastReactorStates = Map.of();
    /** Item stacks a companion dropped for the leader and claimed back before they expire: stack object id -> Agent. */
    private final Map<Integer, Integer> recollectors = new HashMap<>();
    private final Map<Integer, Integer> recollectCounts = new HashMap<>();
    /** When a line of this kind was last said, so companions converging on the leader say one between them. */
    private final Map<String, Long> lineWindows = new HashMap<>();
    /** Reminders said per objective item, for the nudge's back-off. */
    private final Map<Integer, Integer> nudges = new HashMap<>();
    /** Each companion's full-rate hold, with whether full rate was already required before it. */
    private final Map<Integer, Boolean> fullRateHeld = new LinkedHashMap<>();
    /** When each Agent member was first seen unloaded (id -> ms). */
    private final Map<Integer, Long> missingSinceMs = new HashMap<>();
    /** The stage combination being worked, by its map (Heart Strings, the Twisted Switcher). */
    private final Map<Integer, AgentApqPuzzle> puzzles = new HashMap<>();
    private final AgentApqPaceMarks pace = new AgentApqPaceMarks();
    /** The member last seen down in the bound event, for the parting said when its OK ends the event. */
    private String downName;
    private boolean downCompanion;
    private boolean leaderDown;
    private String eventLeaderName;
    private long lastTimeLeftMs = Long.MAX_VALUE;
    private int lastRegistered = -1;

    public AgentApqSession(long seed, int operatorId, long nowMs) {
        if (operatorId <= 0 || nowMs < 0L) throw new IllegalArgumentException("valid APQ session required");
        this.seed = seed;
        this.operatorId = operatorId;
        this.startedAtMs = nowMs;
        this.lastProgressAtMs = nowMs;
        this.phaseEnteredAtMs = nowMs;
        this.stageEnteredAtMs = nowMs;
    }

    public synchronized void addMember(int id, AgentApqMemberState.MemberType type, int gender) {
        if (members.size() >= AgentApqDefinition.PARTY_SIZE
                || members.putIfAbsent(id, new AgentApqMemberState(id, type, gender)) != null) {
            throw new IllegalStateException("invalid or duplicate APQ member");
        }
    }

    public synchronized void setLeadership(int leaderId, int executionId) {
        AgentApqMemberState executor = members.get(executionId);
        if (!members.containsKey(leaderId) || executor == null
                || executor.memberType() != AgentApqMemberState.MemberType.AGENT) {
            throw new IllegalArgumentException("APQ leader and Agent executor must be members");
        }
        eventLeaderId = leaderId;
        executionAgentId = executionId;
    }

    public synchronized boolean claimExecutionTick(int id, long nowMs, long leaseMs) {
        AgentApqMemberState member = members.get(id);
        if (member == null || member.memberType() != AgentApqMemberState.MemberType.AGENT) return false;
        if (id != executionAgentId && nowMs < executionLeaseUntilMs) return false;
        executionAgentId = id;
        executionLeaseUntilMs = nowMs + Math.max(MIN_LEASE_MS, leaseMs);
        return true;
    }

    /** Moves to {@code next} when it is not behind; the change, or null when nothing changed. */
    public synchronized Change transition(Phase next, long nowMs) {
        if (next == null || terminal() || next.ordinal() <= phase.ordinal()) return null;
        Change change = new Change(phase, next, nowMs - phaseEnteredAtMs, nowMs - startedAtMs);
        phaseEnteredAtMs = nowMs;
        phase = next;
        markProgress(nowMs);
        return change;
    }

    public synchronized void markProgress(long nowMs) { lastProgressAtMs = Math.max(lastProgressAtMs, nowMs); }

    public synchronized void fail(String reason, long nowMs) {
        failure = reason;
        phase = Phase.FAILED;
        markProgress(nowMs);
    }

    public synchronized void complete(long nowMs) {
        phase = Phase.COMPLETED;
        markProgress(nowMs);
    }

    public synchronized boolean terminal() { return phase == Phase.COMPLETED || phase == Phase.FAILED; }

    public synchronized boolean beginTermination() {
        if (terminating) return false;
        terminating = true;
        return true;
    }

    public synchronized boolean terminationStarted() { return terminating; }

    @Override
    public String sessionId() { return sessionId; }

    public long seed() { return seed; }

    public int operatorId() { return operatorId; }

    /** Always: APQ runs only as a human-led companion run. */
    @Override
    public boolean companionAdopted() { return true; }

    @Override
    public synchronized void setMemberCoordinationChatEnabled(boolean enabled) {
        memberCoordinationChatEnabled = enabled;
    }

    synchronized boolean memberCoordinationChatEnabled() { return memberCoordinationChatEnabled; }

    @Override
    public synchronized void setPaceCalibration(AgentPqPaceCalibration calibration) { paceCalibration = calibration; }

    synchronized AgentPqPaceCalibration paceCalibration() { return paceCalibration; }

    /** True for the first of any number of threads that want to fail this run; every later one does nothing. */
    synchronized boolean claimFailure() {
        if (failureClaimed) return false;
        failureClaimed = true;
        return true;
    }

    /** True the first time a line's key is used in this run. */
    synchronized boolean narrateOnce(String key) { return key != null && !key.isBlank() && narrated.add(key); }

    /** True the first time a log key is used in this run. */
    synchronized boolean claimLogMark(String key) { return key != null && logMarks.add(key); }

    /** True when no line of this kind was said within {@code windowMs}; the window then starts now. */
    synchronized boolean claimLineWindow(String key, long nowMs, long windowMs) {
        if (key == null) return false;
        Long last = lineWindows.get(key);
        if (last != null && nowMs - last < windowMs) return false;
        lineWindows.put(key, nowMs);
        return true;
    }

    /** How often the leader was reminded to pick up this item in this run. */
    synchronized int nudgesSaid(int itemId) { return nudges.getOrDefault(itemId, 0); }

    synchronized void noteNudge(int itemId) { nudges.merge(itemId, 1, Integer::sum); }

    /** Whether this id is an Agent of the run (a companion). */
    synchronized boolean agentOfRun(int id) {
        AgentApqMemberState member = members.get(id);
        return member != null && member.memberType() == AgentApqMemberState.MemberType.AGENT;
    }

    /** Claims a stack lying for the leader back for this Agent; false when another Agent already did. */
    synchronized boolean beginRecollect(int objectId, int agentId) {
        if (objectId == 0 || agentId <= 0 || recollectors.containsKey(objectId)) return false;
        recollectors.put(objectId, agentId);
        return true;
    }

    /** The Agent that claimed this stack back, or 0. */
    synchronized int recollectorOf(int objectId) {
        Integer agent = recollectors.get(objectId);
        return agent == null ? 0 : agent;
    }

    /** Ends a stack's claim (picked up, or gone); answers how often its lineage had been picked back up before. */
    synchronized int endRecollect(int objectId) {
        recollectors.remove(objectId);
        Integer count = recollectCounts.remove(objectId);
        return count == null ? 0 : count;
    }

    synchronized int recollectCount(int objectId) {
        Integer count = recollectCounts.get(objectId);
        return count == null ? 0 : count;
    }

    /** Carries a lineage's re-collect count to the stack dropped again. */
    synchronized void setRecollectCount(int objectId, int count) {
        if (objectId != 0 && count > 0) recollectCounts.put(objectId, count);
    }

    /**
     * One pass's readings against the last pass's: progress when a map's live mob count dropped (a kill), a member's
     * objective item count changed, or a reactor changed state. True when it marked progress.
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
    synchronized StageChange observeStage(AgentApqStage stage, long nowMs) {
        if (stage == null || stage == leaderStage) return null;
        StageChange change = new StageChange(leaderStage, stage, nowMs - stageEnteredAtMs, nowMs - startedAtMs);
        leaderStage = stage;
        stageEnteredAtMs = nowMs;
        return change;
    }

    synchronized AgentApqStage leaderStage() { return leaderStage; }

    /** The puzzle of the combination stage on {@code mapId}, made on first use; null for any other map. */
    synchronized AgentApqPuzzle puzzle(int mapId) {
        AgentApqPuzzle puzzle = puzzles.get(mapId);
        if (puzzle == null) {
            puzzle = AgentApqPuzzle.forMap(mapId);
            if (puzzle != null) puzzles.put(mapId, puzzle);
        }
        return puzzle;
    }

    synchronized String potionsAtEntry() { return potionsAtEntry; }

    synchronized void setPotionsAtEntry(String potions) { potionsAtEntry = potions == null ? "{}" : potions; }

    /** The members the bound event holds now; once seen held, a member it no longer holds has left it. */
    synchronized void noteHeld(Set<Integer> held) {
        for (int id : held) {
            if (members.containsKey(id)) seenHeld.add(id);
        }
    }

    synchronized boolean seenHeld(int id) { return seenHeld.contains(id); }

    /** When the run first saw its clear: set by the first call, the same time on every later one. */
    synchronized long observeCleared(long nowMs) {
        if (clearedAtMs < 0L) clearedAtMs = nowMs;
        return clearedAtMs;
    }

    /** When the run first saw stage {@code stage} cleared: set by the first call, the same time on every later one. */
    synchronized long observeStageClear(int stage, long nowMs) {
        return stageClearSeenMs.computeIfAbsent(stage, ignored -> nowMs);
    }

    /** When a companion of the run first stood in the exit room: set by the first call, the same time on every later one. */
    synchronized long observeAtExit(long nowMs) {
        if (atExitSinceMs < 0L) atExitSinceMs = nowMs;
        return atExitSinceMs;
    }

    /** When the run first saw its clear, or -1 before. */
    synchronized long clearedAtMs() { return clearedAtMs; }

    /** Records a companion's full-rate hold; false when it already had one. */
    synchronized boolean holdFullRate(int id, boolean requiredBefore) {
        return fullRateHeld.putIfAbsent(id, requiredBefore) == null;
    }

    /** Ends a companion's full-rate hold: whether full rate was required before it, or null when it had none. */
    synchronized Boolean releaseFullRate(int id) { return fullRateHeld.remove(id); }

    synchronized Set<Integer> fullRateHeld() { return Set.copyOf(fullRateHeld.keySet()); }

    /** How long this Agent member has been unloaded, counting from the first call that saw it so. */
    synchronized long missingFor(int id, long nowMs) {
        Long since = missingSinceMs.putIfAbsent(id, nowMs);
        return since == null ? 0L : Math.max(0L, nowMs - since);
    }

    synchronized void clearMissing(int id) { missingSinceMs.remove(id); }

    /** A member lies dead in the bound event: true the first time it is seen so this time. */
    synchronized boolean observeDown(String name, boolean companion) {
        boolean first = name != null && !name.equals(downName);
        if (name != null) {
            downName = name;
            downCompanion = companion;
        }
        return first;
    }

    /** The member last seen down, for the parting; null when nobody went down. */
    synchronized String downName() { return downName; }

    synchronized boolean downCompanion() { return downCompanion; }

    /** The leader is down in the bound event (or up again): true the first time he is seen down this time. */
    synchronized boolean observeLeaderDown(boolean down) {
        boolean first = down && !leaderDown;
        leaderDown = down;
        return first;
    }

    synchronized boolean leaderDown() { return leaderDown; }

    /** The run's pace marks; they lock themselves. */
    AgentApqPaceMarks pace() { return pace; }

    synchronized void noteLeaderName(String name) {
        if (name != null && !name.isBlank()) eventLeaderName = name;
    }

    synchronized String eventLeaderName() { return eventLeaderName; }

    /** The bound event as the last pass read it, outside this monitor: its time left and how many it held. */
    synchronized void observeEvent(long timeLeftMs, int registered) {
        lastTimeLeftMs = timeLeftMs;
        lastRegistered = registered;
    }

    synchronized long lastTimeLeftMs() { return lastTimeLeftMs; }

    synchronized int lastRegistered() { return lastRegistered; }

    public long startedAtMs() { return startedAtMs; }

    public synchronized Phase phase() { return phase; }

    @Override
    public synchronized int eventLeaderId() { return eventLeaderId; }

    public synchronized int executionAgentId() { return executionAgentId; }

    public synchronized long lastProgressAtMs() { return lastProgressAtMs; }

    public synchronized boolean paused() { return paused; }

    public synchronized void setPaused(boolean value) { paused = value; }

    public synchronized String failure() { return failure; }

    public synchronized EventInstanceManager eventInstance() { return eventInstance; }

    /** Binds the run's event as its leader enters it (the coordinator binds once); the cap counts from here. */
    public synchronized void bindEventInstance(EventInstanceManager value, long nowMs) {
        eventInstance = value;
        enteredAtMs = nowMs;
    }

    /** When the run entered its event; the start for a session not bound yet. */
    public synchronized long enteredAtMs() { return enteredAtMs >= 0L ? enteredAtMs : startedAtMs; }

    public synchronized void clearEventInstance() { eventInstance = null; }

    public synchronized AgentApqMemberState member(int id) { return members.get(id); }

    public synchronized Collection<AgentApqMemberState> members() { return List.copyOf(members.values()); }

    public synchronized int memberCount() { return members.size(); }
}
