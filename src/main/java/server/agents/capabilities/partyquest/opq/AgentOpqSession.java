package server.agents.capabilities.partyquest.opq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.partyquest.AgentCompanionRunSession;
import server.agents.capabilities.partyquest.AgentPartyQuestCatalog;
import server.agents.capabilities.partyquest.pace.AgentPqPaceCalibration;

import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntPredicate;
import java.util.function.Supplier;
import java.util.LinkedHashSet;
import java.util.Set;

/** OPQ aggregate root. All mutable party/room/item ownership is session scoped. */
public final class AgentOpqSession implements AgentCompanionRunSession {
    private static final Logger log = LoggerFactory.getLogger(AgentOpqSession.class);
    public enum Mode { TEST_OBSERVATION, AUTONOMOUS, HUMAN_ASSISTED }
    public enum Phase {
        PREPARING, ENTERING, ENTRANCE, SPLIT_ROOMS, RESTORING_STATUE,
        GARDEN, RESTORING_MINERVA, CLAIMING_REWARD, EXITING, COMPLETED, FAILED
    }
    private final String sessionId = UUID.randomUUID().toString();
    private final Mode mode;
    private final long seed;
    private final int operatorId;
    private final int requestedPartySize;
    private final long startedAtMs;
    private final Map<Integer, AgentOpqMemberState> members = new LinkedHashMap<>();
    private final AgentOpqRoomLedger rooms = new AgentOpqRoomLedger();
    private final AgentOpqLootLedger loot = new AgentOpqLootLedger();
    private final AgentOpqPortalRoute loungeRoute = new AgentOpqPortalRoute(2, 3);
    private final AgentOpqPortalRoute wayUpRoute = new AgentOpqPortalRoute(16, 4);
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
    private int sealedAttempt;
    private long sealedCheckedAtMs;
    private List<AgentOpqSealedSearch.Split> sealedCandidates = AgentOpqSealedSearch.SPLITS;
    private AgentOpqSealedSearch.Split sealedGuess;
    private Boolean sealedVerdict;
    private int sealedUnheard;
    private int wayUpLeverAttempt;
    private final Set<Integer> completedLoungeSubrooms = new LinkedHashSet<>();
    private boolean papaPixieEngaged;
    private boolean papaPixieDefeated;
    private boolean companionAdopted;
    private boolean memberCoordinationChatEnabled;
    private AgentPqPaceCalibration paceCalibration;
    private String potionsAtEntry;
    private long clearSeenAtMs;
    private final Map<Phase, Long> firstEntries = new EnumMap<>(Phase.class);
    private final Set<Integer> departed = new LinkedHashSet<>();
    private final Set<Integer> seenHeld = new LinkedHashSet<>();
    private final Set<Integer> departuresInFlight = new LinkedHashSet<>();
    private final Map<Integer, Long> heldSinceMs = new LinkedHashMap<>();
    private final Set<Integer> handBacks = new LinkedHashSet<>();
    private final Set<Integer> handedBack = new LinkedHashSet<>();
    private long boundAtMs;
    // 2026-10-18, a human-led run (AgentOpqConductor): the lines said once, the line windows, the stacks claimed back
    // or claimed for the statue (object id -> Agent) and their re-collects, and the human's puzzle checks.
    private final Set<String> narrated = new LinkedHashSet<>();
    private final Map<String, Long> lineWindows = new HashMap<>();
    private final Map<Integer, Integer> claims = new HashMap<>();
    private final Map<Integer, Integer> recollectCounts = new HashMap<>();
    private HumanCheck<AgentOpqSealedSearch.Split> humanSealedCheck;
    private List<AgentOpqLeverSearch.Pair> leverCandidates = AgentOpqLeverSearch.PAIRS;
    private AgentOpqLeverSearch.Pair leverGuess;
    private HumanCheck<AgentOpqLeverSearch.Pair> humanLeverCheck;
    private int leverChecks;

    /** A session of the stock six members (every Agent-run and fixture run). */
    public AgentOpqSession(Mode mode, long seed, int operatorId, long nowMs) {
        this(mode, seed, operatorId, AgentOpqDefinition.PARTY_SIZE, nowMs);
    }

    /** A session of {@code requestedPartySize} members, within the catalog's OPQ party sizes (five or six). */
    public AgentOpqSession(Mode mode, long seed, int operatorId, int requestedPartySize, long nowMs) {
        if (mode == null || operatorId <= 0 || nowMs < 0L
                || !AgentPartyQuestCatalog.require("opq").acceptsPartySize(requestedPartySize)) {
            throw new IllegalArgumentException("valid OPQ session required");
        }
        this.mode = mode;
        this.seed = seed;
        this.operatorId = operatorId;
        this.requestedPartySize = requestedPartySize;
        this.startedAtMs = nowMs;
        this.lastProgressAtMs = nowMs;
        this.phaseEnteredAtMs = nowMs;
    }

    public synchronized void addMember(int id, AgentOpqMemberState.MemberType type) {
        if (members.size() >= requestedPartySize || members.putIfAbsent(id, new AgentOpqMemberState(id, type)) != null) {
            throw new IllegalStateException("invalid or duplicate OPQ member");
        }
    }
    public synchronized void setLeadership(int leaderId, int executionId) {
        if (!members.containsKey(leaderId) || !members.containsKey(executionId)) throw new IllegalArgumentException("OPQ leaders must be members");
        eventLeaderId = leaderId;
        executionAgentId = executionId;
    }
    public synchronized boolean claimExecutionTick(int id, long nowMs, long leaseMs) {
        return claimExecutionTick(id, nowMs, leaseMs, holder -> true);
    }

    /**
     * As {@link #claimExecutionTick(int, long, long)}, except that a holder for which {@code holderAlive} is false
     * (dead, or no longer loaded) loses its lease at once: a dead Agent's tick never reaches the OPQ runtime, so its
     * lease would otherwise freeze every coordinator pass until it ran out. A live holder keeps it until it expires. A
     * departed member (handed back, no longer the run's) never claims the lease and never keeps it.
     */
    public synchronized boolean claimExecutionTick(int id, long nowMs, long leaseMs, IntPredicate holderAlive) {
        if (departed.contains(id)) return false;
        if (id != executionAgentId && nowMs < executionLeaseUntilMs && holderStands(holderAlive)) return false;
        executionAgentId = id;
        executionLeaseUntilMs = nowMs + Math.max(1L, leaseMs);
        return true;
    }

    /**
     * The watchdog's claim: an Agent member takes the lease only when it has expired or its holder is dead, with the
     * same dead-holder rule as {@link #claimExecutionTick(int, long, long, IntPredicate)}.
     */
    public synchronized boolean claimExpiredExecutionTick(int id, long nowMs, long leaseMs,
                                                          IntPredicate holderAlive) {
        AgentOpqMemberState member = members.get(id);
        if (member == null || member.memberType() != AgentOpqMemberState.MemberType.AGENT || departed.contains(id)
                || (nowMs < executionLeaseUntilMs && holderStands(holderAlive))) return false;
        executionAgentId = id;
        executionLeaseUntilMs = nowMs + Math.max(1L, leaseMs);
        return true;
    }
    /** The lease holder can still drive the run: alive by {@code holderAlive}, and not departed (P6h review). */
    private boolean holderStands(IntPredicate holderAlive) {
        return !departed.contains(executionAgentId) && holderAlive.test(executionAgentId);
    }

    public synchronized void transition(Phase next, long nowMs) {
        if (next == null || terminal() || next.ordinal() < phase.ordinal()) return;
        if (next != phase) {
            firstEntries.putIfAbsent(next, nowMs);
            logPhaseChange(next, nowMs);
            phase = next;
            phaseEnteredAtMs = nowMs;
        }
        markProgress(nowMs);
    }
    public synchronized void markProgress(long nowMs) { lastProgressAtMs = Math.max(lastProgressAtMs, nowMs); }
    public synchronized boolean beginTermination() { if (terminating) return false; terminating = true; return true; }

    /** True once a release has begun (a companion run's tick reads it: a run on its way out is not ticked). */
    public synchronized boolean terminationStarted() { return terminating; }
    public synchronized void fail(String reason, long nowMs) {
        failure = reason == null ? "OPQ failed" : reason;
        firstEntries.putIfAbsent(Phase.FAILED, nowMs);
        if (phase != Phase.FAILED) logPhaseChange(Phase.FAILED, nowMs);
        phase = Phase.FAILED;
        markProgress(nowMs);
    }
    public synchronized void complete(long nowMs) {
        firstEntries.putIfAbsent(Phase.COMPLETED, nowMs);
        if (phase != Phase.COMPLETED) logPhaseChange(Phase.COMPLETED, nowMs);
        phase = Phase.COMPLETED;
        markProgress(nowMs);
    }

    /**
     * The phase line every PQ logs (HPQ/KPQ shape), for stock and companion runs alike: once per phase change, the time
     * spent in the phase it leaves and in the run so far. Logging only; it never throws into the state machine.
     */
    private void logPhaseChange(Phase next, long nowMs) {
        try {
            log.info("OPQ phase: session={} {}->{} phaseMs={} runMs={} companionRun={}", sessionId, phase, next,
                    Math.max(0L, nowMs - phaseEnteredAtMs), Math.max(0L, nowMs - startedAtMs), companionAdopted);
        } catch (RuntimeException ignored) {
            // a log line never changes a run
        }
    }

    /** When the run first entered {@code phase} (a later entry keeps the first), or 0 when it never did. */
    synchronized long firstEnteredAtMs(Phase phase) {
        Long at = phase == null ? null : firstEntries.get(phase);
        return at == null ? 0L : at;
    }
    public synchronized boolean terminal() { return phase == Phase.COMPLETED || phase == Phase.FAILED; }

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
    public synchronized void setPaused(boolean value) { paused = value; }
    public synchronized String failure() { return failure; }
    public synchronized EventInstanceManager eventInstance() { return eventInstance; }
    public synchronized void bindEventInstance(EventInstanceManager value) { eventInstance = value; }
    public synchronized void clearEventInstance() { eventInstance = null; }
    public synchronized AgentOpqMemberState member(int id) { return members.get(id); }
    public synchronized Collection<AgentOpqMemberState> members() { return List.copyOf(members.values()); }
    public synchronized int memberCount() { return members.size(); }
    public AgentOpqRoomLedger rooms() { return rooms; }
    public AgentOpqLootLedger loot() { return loot; }
    public AgentOpqPortalRoute loungeRoute() { return loungeRoute; }
    public AgentOpqPortalRoute wayUpRoute() { return wayUpRoute; }
    public synchronized int sealedAttempt() { return sealedAttempt; }
    /**
     * 2026-10-17: the split the sealed room's holders stand in for the next check (AgentOpqSealedSearch), chosen from
     * the splits that agree with every answer of Eak's so far, weighted by AgentOpqSealedSplitMemory.
     */
    public synchronized AgentOpqSealedSearch.Split sealedGuess() {
        if (sealedGuess == null) {
            AgentOpqSealedSplitMemory memory = AgentOpqSealedSplitMemory.current();
            sealedGuess = AgentOpqSealedSearch.next(sealedCandidates, memory::weight);
        }
        return sealedGuess;
    }

    /** The splits still possible after Eak's answers so far. */
    public synchronized int sealedCandidateCount() { return sealedCandidates.size(); }

    /**
     * Eak's answer to a wrong check, as he said it (2013001.js through AgentOpqSessionRegistry.recordSealedVerdict):
     * true for one platform right, false for all of them wrong. The next {@link #advanceSealedAttempt} uses it.
     */
    public synchronized void recordSealedVerdict(boolean onePlatformRight) { sealedVerdict = onePlatformRight; }

    /**
     * After a check that did not seal the room: with Eak's answer heard, the guess and every split that disagrees with
     * it are dropped and the next guess is chosen ("one-right" / "all-wrong"). Not heard (he only asked for exactly
     * three players, or the script did not answer), the same split is checked once more ("unheard"); not heard twice,
     * it is dropped alone ("unheard-dropped"). Returns which of those it was.
     */
    public synchronized String advanceSealedAttempt(long nowMs) {
        Boolean heard = sealedVerdict;
        sealedVerdict = null;
        sealedCheckedAtMs = nowMs;
        markProgress(nowMs);
        if (heard == null && ++sealedUnheard < 2) return "unheard";
        sealedUnheard = 0;
        sealedCandidates = AgentOpqSealedSearch.afterWrongCheck(sealedCandidates, sealedGuess(), heard);
        sealedGuess = null;
        sealedAttempt = Math.min(9, sealedAttempt + 1);
        return heard == null ? "unheard-dropped" : heard ? "one-right" : "all-wrong";
    }
    public synchronized long sealedCheckedAtMs() { return sealedCheckedAtMs; }
    public synchronized void markSealedChecked(long nowMs) { sealedCheckedAtMs = nowMs; }
    public synchronized int wayUpLeverAttempt() { return wayUpLeverAttempt; }
    public synchronized void advanceWayUpLeverAttempt(long nowMs) { wayUpLeverAttempt = Math.min(9, wayUpLeverAttempt + 1); markProgress(nowMs); }

    /** A puzzle check the human leader made at Eak as 2013001.js reported it: what stood (null: unreadable), his answer. */
    record HumanCheck<T>(T observed, boolean oneRight) { }

    /** True the first time {@code key} is asked for in this run: a line said once. */
    synchronized boolean narrateOnce(String key) { return key != null && narrated.add(key); }

    /**
     * True when no line of this kind was said within {@code windowMs}; the window then starts now. Companions converging
     * on the leader say one line between them, and the calls come one at a time.
     */
    synchronized boolean claimLineWindow(String key, long nowMs, long windowMs) {
        if (key == null) return false;
        Long last = lineWindows.get(key);
        if (last != null && nowMs - last < windowMs) return false;
        lineWindows.put(key, nowMs);
        return true;
    }

    /** Whether this id is an Agent of the run. */
    synchronized boolean agentOfRun(int id) {
        AgentOpqMemberState member = members.get(id);
        return member != null && member.memberType() == AgentOpqMemberState.MemberType.AGENT;
    }

    /**
     * Claims a stack lying on the floor for this Agent: fragments it dropped for the leader, picked back up before they
     * expire, or a statue piece he dropped in the centre, taken to the statue. False when another Agent already did.
     */
    synchronized boolean beginClaim(int objectId, int agentId) {
        if (objectId == 0 || agentId <= 0 || claims.containsKey(objectId)) return false;
        claims.put(objectId, agentId);
        return true;
    }

    /** The Agent that claimed this stack, or 0. */
    synchronized int claimerOf(int objectId) {
        Integer agent = claims.get(objectId);
        return agent == null ? 0 : agent;
    }

    /** Ends a stack's claim (picked up, or gone); answers how often its lineage had been picked back up before. */
    synchronized int endClaim(int objectId) {
        claims.remove(objectId);
        Integer count = recollectCounts.remove(objectId);
        return count == null ? 0 : count;
    }

    /** How often this stack's lineage has been picked back up. */
    synchronized int recollectCount(int objectId) {
        Integer count = recollectCounts.get(objectId);
        return count == null ? 0 : count;
    }

    /** Carries a lineage's re-collect count to the stack dropped again. */
    synchronized void setRecollectCount(int objectId, int count) {
        if (objectId != 0 && count > 0) recollectCounts.put(objectId, count);
    }

    /**
     * The human leader's wrong sealed check (2013001.js through AgentOpqSessionRegistry.recordSealedVerdict): the split
     * that stood on the platforms (null when it was not readable) and Eak's answer. The next coordinator pass applies it.
     */
    synchronized void recordHumanSealedCheck(AgentOpqSealedSearch.Split observed, boolean onePlatformRight) {
        humanSealedCheck = new HumanCheck<>(observed, onePlatformRight);
    }

    /**
     * Applies the human leader's last wrong sealed check: the candidates are pruned with the split that stood (the
     * guess when it was not readable), so a check made while the holders were still moving prunes right too, and the
     * next guess is chosen. Returns the check with the split it pruned by, or null when there was none.
     */
    synchronized HumanCheck<AgentOpqSealedSearch.Split> applyHumanSealedCheck(long nowMs) {
        HumanCheck<AgentOpqSealedSearch.Split> check = humanSealedCheck;
        humanSealedCheck = null;
        if (check == null) return null;
        AgentOpqSealedSearch.Split checked = check.observed() == null ? sealedGuess() : check.observed();
        sealedCandidates = AgentOpqSealedSearch.afterWrongCheck(sealedCandidates, checked, check.oneRight());
        sealedGuess = null;
        sealedVerdict = null;
        sealedAttempt = Math.min(9, sealedAttempt + 1);
        sealedCheckedAtMs = nowMs;
        markProgress(nowMs);
        return new HumanCheck<>(checked, check.oneRight());
    }

    /** The pair of levers the way up's runner pushes for the next check (AgentOpqLeverSearch). */
    synchronized AgentOpqLeverSearch.Pair leverGuess() {
        if (leverGuess == null) leverGuess = AgentOpqLeverSearch.next(leverCandidates);
        return leverGuess;
    }

    /** The pairs still possible after Eak's answers so far. */
    synchronized int leverCandidateCount() { return leverCandidates.size(); }

    /** The human leader's wrong lever checks applied so far. */
    synchronized int leverChecks() { return leverChecks; }

    /** The human leader's wrong lever check, as {@link #recordHumanSealedCheck} keeps a sealed one. */
    synchronized void recordHumanLeverCheck(AgentOpqLeverSearch.Pair observed, boolean oneRight) {
        humanLeverCheck = new HumanCheck<>(observed, oneRight);
    }

    /** Applies the human leader's last wrong lever check as {@link #applyHumanSealedCheck} applies a sealed one. */
    synchronized HumanCheck<AgentOpqLeverSearch.Pair> applyHumanLeverCheck(long nowMs) {
        HumanCheck<AgentOpqLeverSearch.Pair> check = humanLeverCheck;
        humanLeverCheck = null;
        if (check == null) return null;
        AgentOpqLeverSearch.Pair checked = check.observed() == null ? leverGuess() : check.observed();
        leverCandidates = AgentOpqLeverSearch.afterWrongCheck(leverCandidates, checked, check.oneRight());
        leverGuess = null;
        leverChecks++;
        markProgress(nowMs);
        return new HumanCheck<>(checked, check.oneRight());
    }
    public synchronized void completeLoungeSubroom(int mapId, long nowMs) {
        if (AgentOpqDefinition.LOUNGE_ROOM_MAPS.contains(mapId) && completedLoungeSubrooms.add(mapId)) markProgress(nowMs);
    }
    public synchronized boolean loungeSubroomComplete(int mapId) { return completedLoungeSubrooms.contains(mapId); }
    public synchronized Set<Integer> completedLoungeSubrooms() { return Set.copyOf(completedLoungeSubrooms); }
    public synchronized void observePapaPixie(boolean alive, long nowMs) {
        if (alive) {
            if (!papaPixieEngaged) markProgress(nowMs);
            papaPixieEngaged = true;
            return;
        }
        if (papaPixieEngaged && !papaPixieDefeated) {
            papaPixieDefeated = true;
            markProgress(nowMs);
        }
    }
    public synchronized boolean papaPixieEngaged() { return papaPixieEngaged; }
    public synchronized boolean papaPixieDefeated() { return papaPixieDefeated; }

    /**
     * A human-led run of owner-bound companions, admitted by AgentOpqAdmissionService.admit. Every companion-run
     * behaviour is gated on it; stock, background and '!opqtest start' fixture runs leave it false.
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

    /** The companions' potions as 'OPQ potions:' logged them at the entrance; null when that line was never logged. */
    synchronized String potionsAtEntry() { return potionsAtEntry; }

    synchronized void setPotionsAtEntry(String potions) { potionsAtEntry = potions; }

    /** When the coordinator first saw the clear (Minerva freed or the event cleared), or 0 when it never did. */
    synchronized long clearSeenAtMs() { return clearSeenAtMs; }

    /** Records the first tick that saw the clear; a later one keeps the first. */
    synchronized void markClearSeen(long nowMs) {
        if (clearSeenAtMs == 0L && nowMs > 0L) clearSeenAtMs = nowMs;
    }

    /**
     * Marks a member the bound event no longer holds as departed (P6h OB7): true the first time only, and never for
     * someone who is not a member.
     */
    synchronized boolean markDeparted(int characterId) {
        return members.containsKey(characterId) && departed.add(characterId);
    }

    /** True once the member was marked departed; the run no longer counts it. */
    synchronized boolean departed(int characterId) { return departed.contains(characterId); }

    /**
     * When the run was first seen bound to its event (the coordinator's bind, or its first look at a bound event):
     * the first call keeps {@code nowMs} and every later one answers it.
     */
    synchronized long noteBoundAt(long nowMs) {
        if (boundAtMs == 0L && nowMs > 0L) boundAtMs = nowMs;
        return boundAtMs;
    }

    /** Queues a departed Agent for its hand-back, which runs outside this monitor. */
    synchronized void queueHandBack(int characterId) { handBacks.add(characterId); }

    /** Records that a departed member's hand-back ran: it belongs to its owner's activity from now on. */
    synchronized void markHandedBack(int characterId) {
        if (departed.contains(characterId)) handedBack.add(characterId);
    }

    /** True once a departed member was handed back; the release leaves its Agent's activity alone. */
    synchronized boolean handedBack(int characterId) { return handedBack.contains(characterId); }

    /** The departed Agents queued for their hand-back since the last call, each once. */
    synchronized List<Integer> takeHandBacks() {
        List<Integer> taken = List.copyOf(handBacks);
        handBacks.clear();
        return taken;
    }

    /** Notes the members the bound event holds now: each of them has been held from then on. */
    synchronized void noteHeld(Collection<Integer> ids) {
        for (int id : ids) if (members.containsKey(id)) seenHeld.add(id);
    }

    /**
     * True once the bound event was seen holding the member. OrbisPQ.js registers and warps a party one member at a
     * time, so a member not seen held yet is still entering, not leaving.
     */
    synchronized boolean seenHeld(int characterId) { return seenHeld.contains(characterId); }

    /**
     * What {@link #reserveDeparture} answered a dead member: leave now (the departure is then reserved until
     * {@link #endDeparture}) or stay down. heldMs is how long it was held before leaving, or -1 when it was not held;
     * firstHold is true only for the first answer of a hold.
     */
    record Departure(boolean proceed, boolean firstHold, long heldMs, int registered, int othersInFlight) { }

    /**
     * A dead member may leave the event while more than {@code floor} registered members would stay, every departure
     * still in flight counted as gone (OrbisPQ.js ends the event when a member leaves at the floor). registeredIds is
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
            throw new IllegalArgumentException("the OPQ event leader cannot be removed from his run");
        }
        members.remove(characterId);
    }
}
