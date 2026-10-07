package server.agents.capabilities.partyquest.epq;

import scripting.event.EventInstanceManager;
import server.agents.capabilities.partyquest.AgentCompanionRunSession;
import server.agents.capabilities.partyquest.pace.AgentPqPaceCalibration;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/** EPQ aggregate root; no state is shared with another party quest. */
public final class AgentEpqSession implements AgentCompanionRunSession {
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
    private final Set<String> narrations = new HashSet<>();
    private final Set<Integer> seenInEvent = new HashSet<>();
    private final Map<String, Long> leaderHints = new HashMap<>();
    private final Set<Integer> runCompanions = new HashSet<>();
    private final Map<Integer, Integer> recollectors = new HashMap<>();
    private final Map<Integer, Integer> recollectCounts = new HashMap<>();
    private final Set<Integer> dyingStacks = new HashSet<>();
    private int handOvers;
    private int stoneFinderId;
    private final Set<String> logMarks = new HashSet<>();
    private final Set<Integer> membersDown = new HashSet<>();
    private final Map<String, Integer> rescues = new LinkedHashMap<>();
    private final Set<Integer> departed = new LinkedHashSet<>();
    private final Set<Integer> departuresInFlight = new LinkedHashSet<>();
    private final Map<Integer, Long> heldSinceMs = new LinkedHashMap<>();
    private final Set<Integer> capturesInFlight = new HashSet<>();
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
    private boolean companionAdopted;
    private boolean memberCoordinationChatEnabled;
    private String potionsAtEntry = "";
    private long enteredEventAtMs;

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
        if (type == AgentEpqMemberState.MemberType.AGENT) runCompanions.add(id);
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
        advance(next, nowMs);
    }

    /**
     * Moves the run forward to {@code next} and says what changed, or null when nothing did: no phase, a terminal run,
     * or the same or an earlier phase (the run only goes forward).
     */
    synchronized Change advance(Phase next, long nowMs) {
        if (next == null || terminal() || next.ordinal() <= phase.ordinal()) return null;
        Change change = new Change(phase, next, nowMs - phaseEnteredAtMs, nowMs - startedAtMs);
        phase = next;
        phaseEnteredAtMs = nowMs;
        markProgress(nowMs);
        return change;
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

    /** Whether the run's release has begun (it can begin before the run is terminal). */
    public synchronized boolean terminationStarted() { return terminating; }

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
    /**
     * The Agent that carries the run's shared work (the stage-2 bottles, the stage-5 stone, the coordination lines): an
     * Agent event leader, else the lowest-id Agent that has not left the event (EPQ base fix 3) and is not down (EPQ
     * base fix 4: a corpse cannot carry anything); with every Agent gone or down, the lowest-id Agent as before, else
     * the execution Agent.
     */
    public synchronized int workAgentId() {
        AgentEpqMemberState leader = members.get(eventLeaderId);
        if (leader != null && leader.memberType() == AgentEpqMemberState.MemberType.AGENT) {
            return leader.characterId();
        }
        return members.values().stream()
                .filter(member -> member.memberType() == AgentEpqMemberState.MemberType.AGENT)
                .mapToInt(AgentEpqMemberState::characterId)
                .filter(id -> !departed.contains(id) && !membersDown.contains(id))
                .min()
                .orElseGet(() -> members.values().stream()
                        .filter(member -> member.memberType() == AgentEpqMemberState.MemberType.AGENT)
                        .mapToInt(AgentEpqMemberState::characterId).min().orElse(executionAgentId));
    }

    /**
     * Marks a member the bound event no longer holds (EPQ base fix 3: it pressed OK, or its respawn walked it out);
     * true only the first time.
     */
    synchronized boolean markDeparted(int id) { return departed.add(id); }

    /** Forgets a departure once the member is seen back in the bound event; true when it was marked. */
    synchronized boolean clearDeparted(int id) { return departed.remove(id); }

    /** Whether this member has left the bound event (and not come back). */
    synchronized boolean departed(int id) { return departed.contains(id); }

    /**
     * Takes back the "four bottles secured" mark: a bottle it counted is gone (a leaver's bottles are deleted by the
     * stock exit), so stage 2 counts again and secures them once four are reachable.
     */
    synchronized void reopenStageTwoBottles() { stageTwoBottlesSecured = false; }
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

    /** A companion run's line key, apart from the stock announcements: true only the first time it is claimed. */
    synchronized boolean narrateOnce(String key) {
        return key != null && !key.isBlank() && narrations.add(key);
    }

    /**
     * A human-led run of owner-bound companions, admitted by AgentEpqAdmissionService.admit. Every companion-run
     * behaviour is gated on it; stock and GM-observed runs leave it false.
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

    /** EPQ paces nothing by a leader's calibration, so it keeps none. */
    @Override
    public synchronized void setPaceCalibration(AgentPqPaceCalibration calibration) {
    }

    /** The companions' potions as the party left the entrance ('EPQ potions:'), or "". */
    synchronized String potionsAtEntry() { return potionsAtEntry; }

    synchronized void setPotionsAtEntry(String potions) { potionsAtEntry = potions == null ? "" : potions; }

    /**
     * When the run's leader first stood in its bound event (the bind): the party's entry. Only the first call counts;
     * true when it was the first.
     */
    synchronized boolean markEnteredEvent(long nowMs) {
        if (enteredEventAtMs > 0L) return false;
        enteredEventAtMs = Math.max(1L, nowMs);
        return true;
    }

    /** The party's entry (see {@link #markEnteredEvent}), or 0 before it. */
    synchronized long enteredEventAtMs() { return enteredEventAtMs; }

    /** Marks a member seen standing in the bound event; a companion run never treats it as absent again. */
    synchronized void markSeenInEvent(int id) { seenInEvent.add(id); }

    /** Whether this member was ever seen standing in the bound event. */
    synchronized boolean seenInEvent(int id) { return seenInEvent.contains(id); }

    /**
     * When a companion first waited on the leader for this hint kind (an exit, a stage item he holds): the first call
     * starts the clock and every later call reads it, until {@link #clearLeaderHint}.
     */
    synchronized long leaderHintSince(String key, long nowMs) {
        return leaderHints.computeIfAbsent(key, ignored -> nowMs);
    }

    /** Stops a hint clock: the leader did what the companions were waiting for. */
    synchronized void clearLeaderHint(String key) { leaderHints.remove(key); }

    // A companion run's hand-overs (Step 2.7): read by the companions' own ticks and the bots' loot path.

    /** Whether this character was ever one of the run's Agents (also after it was taken out of the run). */
    synchronized boolean companionOfRun(int characterId) { return runCompanions.contains(characterId); }

    /** The next hand-over's number, for its line key. */
    synchronized int nextHandOver() { return ++handOvers; }

    /**
     * Claims a stack lying on the floor (by its map object id) for this companion to pick back up; false when another
     * companion claimed it first or this one already picks up another.
     */
    synchronized boolean beginRecollect(int objectId, int agentId) {
        if (recollectors.containsValue(agentId)) return false;
        return recollectors.putIfAbsent(objectId, agentId) == null;
    }

    /** The companion picking this stack back up, or 0. */
    synchronized int recollectorOf(int objectId) { return recollectors.getOrDefault(objectId, 0); }

    /** The stack this companion picks back up, or 0. */
    synchronized int recollectOf(int agentId) {
        return recollectors.entrySet().stream().filter(entry -> entry.getValue() == agentId)
                .mapToInt(Map.Entry::getKey).findFirst().orElse(0);
    }

    /** Ends the claim on this stack (picked up or gone) and says how often its lineage was picked back up before. */
    synchronized int endRecollect(int objectId) {
        recollectors.remove(objectId);
        dyingStacks.remove(objectId);
        Integer count = recollectCounts.remove(objectId);
        return count == null ? 0 : count;
    }

    /** How often the lineage of the stack lying now was picked back up. */
    synchronized int recollectCount(int objectId) { return recollectCounts.getOrDefault(objectId, 0); }

    /** A re-dropped stack inherits its lineage's count. */
    synchronized void setRecollectCount(int objectId, int count) {
        if (objectId != 0 && count > 0) recollectCounts.put(objectId, count);
    }

    /**
     * Notes the first companion seen holding the purple stone on 930000500: the stone's finder, who coaches the leader
     * with it. True only for that first sighting.
     */
    synchronized boolean noteStoneFinder(int agentId) {
        if (stoneFinderId != 0 || agentId <= 0) return false;
        stoneFinderId = agentId;
        return true;
    }

    /** The companion that found the purple stone, or 0. */
    synchronized int stoneFinderId() { return stoneFinderId; }

    /** Marks a stack a carrier about to go down left where it stood: it is picked back up at once. */
    synchronized void markDyingStack(int objectId) { if (objectId != 0) dyingStacks.add(objectId); }

    /** Whether a carrier about to go down left this stack. */
    synchronized boolean dyingStack(int objectId) { return dyingStacks.contains(objectId); }

    /** Takes a member out of the run; true when it was one. */
    public synchronized boolean removeMember(int id) { return members.remove(id) != null; }

    // What the run's log lines have said so far. Only the members seen down are also read, by workAgentId (kept
    // current by the coordinator's logMembersDown every pass); nothing else decides by it.

    /** True only the first time this member is seen down since it was last seen up. */
    synchronized boolean markMemberDown(int id) { return membersDown.add(id); }

    synchronized void clearMemberDown(int id) { membersDown.remove(id); }

    /** A once-only mark for a log line, apart from the chat announcements. */
    synchronized boolean claimLogMark(String key) {
        return key != null && !key.isBlank() && logMarks.add(key);
    }

    /** Counts one of the coordinator's assists of this kind and returns how many there have been. */
    synchronized int countRescue(String kind) { return rescues.merge(kind, 1, Integer::sum); }

    /** The assists so far, by kind, in the order each kind first happened. */
    synchronized Map<String, Integer> rescueCounts() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(rescues));
    }

    /** A phase change: the phase left and the one entered, the time spent in the one left, and the run's time. */
    record Change(Phase from, Phase to, long phaseMs, long runMs) { }

    /**
     * What {@link #reserveDeparture} answered a dead member: leave now (the departure is then reserved until
     * {@link #endDeparture}) or stay down. heldMs is how long it was held before leaving, or -1 when it was not held;
     * firstHold is true only for the first answer of a hold.
     */
    record Departure(boolean proceed, boolean firstHold, long heldMs, int registered, int othersInFlight) { }

    /**
     * A dead member may leave the event while more than {@code floor} registered members would stay, every departure
     * still in flight counted as gone (EllinPQ.js ends the event when a member leaves at the floor). registeredIds is
     * read here, under this monitor, so a departure that has just finished is seen as gone; the event's read lock
     * nests inside this monitor, as when the coordinator reads the event's players, and nothing takes them the other
     * way round (the event calls no Agent code under its locks).
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

    /**
     * Claims a stage-4 flower (by its object id) for one Agent's catch; false while another Agent's catch of it is in
     * flight. The stock catch checks, kills and hands out the marble with no lock, so two throws at once would both
     * spend a purifier and both get a marble. The catch runs outside this monitor; {@link #endCapture} ends the claim.
     */
    synchronized boolean beginCapture(int objectId) { return capturesInFlight.add(objectId); }

    /** Ends a flower claim (its catch returned or threw). */
    synchronized void endCapture(int objectId) { capturesInFlight.remove(objectId); }
}
