package server.agents.capabilities.partyquest.rjpq;

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
 * One Romeo and Juliet PQ run of a human leader and his companions. Every RJPQ session is a companion run: the public
 * admit is the only way one is made. Everything another thread reads is here, behind this monitor; nothing under it
 * moves a character, drops, chats, logs, runs an NPC or reads the event.
 */
public final class AgentRjpqSession implements AgentCompanionRunSession {
    /** The run's stages in order; a run only moves forward. */
    public enum Phase {
        PREPARING, ENTERING, BOOKS, TUNNEL, BEAKERS, KEYS, LABS, HANDIN, OFFICE, UNITS, CENTER, CLAIMING, EXITING,
        COMPLETED, FAILED
    }

    /** A phase change: the phase left and the one entered, the time spent in the one left, and the run's time. */
    record Change(Phase from, Phase to, long phaseMs, long runMs) {
    }

    /**
     * What {@link #reserveDeparture} answered a dead member: leave now (reserved until {@link #endDeparture}) or stay
     * down. heldMs is how long it was held before leaving, or -1 when it was not; firstHold is true only for the first
     * answer of a hold.
     */
    record Departure(boolean proceed, boolean firstHold, long heldMs, int registered, int othersInFlight) {
    }

    /** One companion walking to a book until the claim runs out. */
    private record BookClaim(int agentId, long untilMs) {
    }

    private final String sessionId = UUID.randomUUID().toString();
    private final AgentRjpqSide side;
    private final long seed;
    private final int operatorId;
    private final long startedAtMs;
    private final Map<Integer, AgentRjpqMemberState> members = new LinkedHashMap<>();
    private final Set<String> narrated = new HashSet<>();
    private final Set<String> logMarks = new HashSet<>();
    private final Set<Integer> departuresInFlight = new LinkedHashSet<>();
    private final Map<Integer, Long> heldSinceMs = new LinkedHashMap<>();
    private final Map<Integer, Long> downSinceMs = new LinkedHashMap<>();
    private final Map<Integer, Boolean> fullRateHeld = new LinkedHashMap<>();
    private final Set<Integer> checkedBooks = new HashSet<>();
    private final Map<Integer, BookClaim> bookClaims = new LinkedHashMap<>();
    private int beakerTarget = -1;
    private Phase phase = Phase.PREPARING;
    private int eventLeaderId;
    private int executionAgentId;
    private long executionLeaseUntilMs;
    private long lastProgressAtMs;
    private long phaseEnteredAtMs;
    private long enteredEventAtMs;
    private long clearedAtMs;
    private boolean paused;
    private boolean terminating;
    private boolean failureClaimed;
    private boolean leaderWentDown;
    private String failure = "";
    private EventInstanceManager eventInstance;
    private boolean companionAdopted;
    private boolean memberCoordinationChatEnabled;
    private AgentPqPaceCalibration paceCalibration;
    private String potionsAtEntry = "";

    public AgentRjpqSession(AgentRjpqSide side, long seed, int operatorId, long nowMs) {
        if (side == null || operatorId <= 0 || nowMs < 0L) {
            throw new IllegalArgumentException("valid RJPQ session required");
        }
        this.side = side;
        this.seed = seed;
        this.operatorId = operatorId;
        this.startedAtMs = nowMs;
        this.lastProgressAtMs = nowMs;
        this.phaseEnteredAtMs = nowMs;
    }

    public synchronized void addMember(int id, AgentRjpqMemberState.MemberType type) {
        if (members.size() >= AgentRjpqSide.PARTY_SIZE
                || members.putIfAbsent(id, new AgentRjpqMemberState(id, type)) != null) {
            throw new IllegalStateException("invalid or duplicate RJPQ member");
        }
    }

    public synchronized void setLeadership(int leaderId, int executionId) {
        if (!members.containsKey(leaderId) || !members.containsKey(executionId)) {
            throw new IllegalArgumentException("RJPQ leaders must be members");
        }
        eventLeaderId = leaderId;
        executionAgentId = executionId;
    }

    /** The coordinator lease: one Agent at a time runs the session's tick. */
    public synchronized boolean claimExecutionTick(int id, long nowMs, long leaseMs) {
        if (id != executionAgentId && nowMs < executionLeaseUntilMs) {
            return false;
        }
        executionAgentId = id;
        executionLeaseUntilMs = nowMs + Math.max(1L, leaseMs);
        return true;
    }

    /** Moves the run forward to {@code next}; null when nothing changed (no phase, a terminal run, not forward). */
    synchronized Change advance(Phase next, long nowMs) {
        if (next == null || terminal() || next.ordinal() <= phase.ordinal()
                || next == Phase.COMPLETED || next == Phase.FAILED) {
            return null;
        }
        Change change = new Change(phase, next, nowMs - phaseEnteredAtMs, nowMs - startedAtMs);
        phase = next;
        phaseEnteredAtMs = nowMs;
        markProgress(nowMs);
        return change;
    }

    public synchronized void markProgress(long nowMs) {
        lastProgressAtMs = Math.max(lastProgressAtMs, nowMs);
    }

    public synchronized boolean paused() {
        return paused;
    }

    public synchronized void setPaused(boolean value) {
        paused = value;
    }

    /** Takes the coordinator lease only when it has expired (the watchdog's failover). */
    synchronized boolean claimExpiredExecutionTick(int id, long nowMs, long leaseMs) {
        if (nowMs < executionLeaseUntilMs) {
            return false;
        }
        executionAgentId = id;
        executionLeaseUntilMs = nowMs + Math.max(1L, leaseMs);
        return true;
    }

    public synchronized boolean beginTermination() {
        if (terminating) {
            return false;
        }
        terminating = true;
        return true;
    }

    /** Whether the run's release has begun (it can begin before the run is terminal). */
    public synchronized boolean terminationStarted() {
        return terminating;
    }

    /** True for the first of any number of threads that want to fail this run; every later one does nothing. */
    synchronized boolean claimFailure() {
        if (failureClaimed) {
            return false;
        }
        failureClaimed = true;
        return true;
    }

    public synchronized void fail(String reason, long nowMs) {
        failure = reason == null || reason.isBlank() ? "RJPQ failed" : reason;
        phase = Phase.FAILED;
        markProgress(nowMs);
    }

    public synchronized void complete(long nowMs) {
        phase = Phase.COMPLETED;
        markProgress(nowMs);
    }

    public synchronized boolean terminal() {
        return phase == Phase.COMPLETED || phase == Phase.FAILED;
    }

    @Override
    public String sessionId() {
        return sessionId;
    }

    public AgentRjpqSide side() {
        return side;
    }

    public long seed() {
        return seed;
    }

    public int operatorId() {
        return operatorId;
    }

    public long startedAtMs() {
        return startedAtMs;
    }

    public synchronized Phase phase() {
        return phase;
    }

    @Override
    public synchronized int eventLeaderId() {
        return eventLeaderId;
    }

    public synchronized int executionAgentId() {
        return executionAgentId;
    }

    public synchronized long lastProgressAtMs() {
        return lastProgressAtMs;
    }

    public synchronized long phaseEnteredAtMs() {
        return phaseEnteredAtMs;
    }

    public synchronized String failure() {
        return failure;
    }

    public synchronized EventInstanceManager eventInstance() {
        return eventInstance;
    }

    /** Forgets the event at the release. */
    synchronized void clearEventInstance() {
        eventInstance = null;
    }

    /** Binds the run's event once; the first bind is when the party entered it. */
    synchronized boolean bindEventInstance(EventInstanceManager event, long nowMs) {
        if (event == null || eventInstance != null) {
            return false;
        }
        eventInstance = event;
        enteredEventAtMs = nowMs;
        return true;
    }

    public synchronized long enteredEventAtMs() {
        return enteredEventAtMs;
    }

    /** The first time the run saw its event cleared; 0 before. */
    synchronized long observeCleared(long nowMs) {
        if (clearedAtMs == 0L) {
            clearedAtMs = nowMs;
            markProgress(nowMs);
        }
        return clearedAtMs;
    }

    public synchronized long clearedAtMs() {
        return clearedAtMs;
    }

    public synchronized AgentRjpqMemberState member(int id) {
        return members.get(id);
    }

    public synchronized Collection<AgentRjpqMemberState> members() {
        return List.copyOf(members.values());
    }

    public synchronized int memberCount() {
        return members.size();
    }

    /** Takes a member out of the run; true when it was one. */
    public synchronized boolean removeMember(int id) {
        downSinceMs.remove(id);
        fullRateHeld.remove(id);
        return members.remove(id) != null;
    }

    @Override
    public synchronized boolean companionAdopted() {
        return companionAdopted;
    }

    /** Set only by the public admit, before the session is published. */
    synchronized void setCompanionAdopted(boolean adopted) {
        companionAdopted = adopted;
    }

    synchronized boolean memberCoordinationChatEnabled() {
        return memberCoordinationChatEnabled;
    }

    @Override
    public synchronized void setMemberCoordinationChatEnabled(boolean enabled) {
        memberCoordinationChatEnabled = enabled;
    }

    @Override
    public synchronized void setPaceCalibration(AgentPqPaceCalibration calibration) {
        paceCalibration = calibration;
    }

    synchronized AgentPqPaceCalibration paceCalibration() {
        return paceCalibration;
    }

    /** The companions' potions as the party entered ('RJPQ potions:'), or "". */
    synchronized String potionsAtEntry() {
        return potionsAtEntry;
    }

    synchronized void setPotionsAtEntry(String potions) {
        potionsAtEntry = potions == null ? "" : potions;
    }

    /** Remembers that one of the run's companions checked this book (its object id); true the first time. */
    synchronized boolean markBookChecked(int bookObjectId) {
        bookClaims.remove(bookObjectId);
        return checkedBooks.add(bookObjectId);
    }

    /** The books the run's companions have checked, as a player remembers the ones his party called out. */
    synchronized Set<Integer> checkedBooks() {
        return Set.copyOf(checkedBooks);
    }

    /**
     * Claims a book for one companion until {@code untilMs}, so two companions do not walk to the same one; true when
     * it is free, expired or already this companion's.
     */
    synchronized boolean claimBook(int bookObjectId, int agentId, long nowMs, long untilMs) {
        BookClaim claim = bookClaims.get(bookObjectId);
        if (claim != null && claim.agentId() != agentId && claim.untilMs() > nowMs) {
            return false;
        }
        bookClaims.put(bookObjectId, new BookClaim(agentId, untilMs));
        return true;
    }

    /** The books another companion has a live claim on. */
    synchronized Set<Integer> booksClaimedByOthers(int agentId, long nowMs) {
        Set<Integer> claimed = new HashSet<>();
        bookClaims.forEach((book, claim) -> {
            if (claim.agentId() != agentId && claim.untilMs() > nowMs) {
                claimed.add(book);
            }
        });
        return claimed;
    }

    /** Records the beaker the companions feed now; true when it changed (the first target counts as a change). */
    synchronized boolean observeBeakerTarget(int beaker) {
        if (beaker == beakerTarget) {
            return false;
        }
        beakerTarget = beaker;
        return true;
    }

    /** True only the first time a line key is used in this run. */
    synchronized boolean narrateOnce(String key) {
        return key != null && !key.isBlank() && narrated.add(key);
    }

    /** A once-only mark for a log line, apart from the spoken lines. */
    synchronized boolean claimLogMark(String key) {
        return key != null && !key.isBlank() && logMarks.add(key);
    }

    /** The leader went down in the event (he has not pressed OK); the run goes on where it can. */
    synchronized boolean markLeaderWentDown() {
        if (leaderWentDown) {
            return false;
        }
        leaderWentDown = true;
        return true;
    }

    public synchronized boolean leaderWentDown() {
        return leaderWentDown;
    }

    /** Marks a member down since {@code nowMs}; true only when it was up before. */
    synchronized boolean markDown(int id, long nowMs) {
        return downSinceMs.putIfAbsent(id, nowMs) == null;
    }

    /** Marks a member up again; true when it was down. */
    synchronized boolean clearDown(int id) {
        return downSinceMs.remove(id) != null;
    }

    /** Since when this member is down, or 0 when it is not. */
    synchronized long downSinceMs(int id) {
        Long since = downSinceMs.get(id);
        return since == null ? 0L : since;
    }

    /**
     * Records that this run holds the Agent at full rate, remembering whether something else already required it;
     * true the first time.
     */
    synchronized boolean holdFullRate(int id, boolean requiredBefore) {
        return fullRateHeld.putIfAbsent(id, requiredBefore) == null;
    }

    /** Forgets this run's full-rate hold: whether something else required full rate before it, or null if not held. */
    synchronized Boolean releaseFullRate(int id) {
        return fullRateHeld.remove(id);
    }

    /** The Agents this run holds at full rate. */
    synchronized Set<Integer> fullRateHeld() {
        return Set.copyOf(fullRateHeld.keySet());
    }

    /**
     * A dead member may leave the event while more than {@code floor} registered members would stay, departures in
     * flight counted as gone. RJPQ's floor is its size, so before the clear every death is held (MagatiaPQ_Z.js ends the
     * event when a member leaves at four).
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
    synchronized void endDeparture(int id) {
        departuresInFlight.remove(id);
    }

    /** Forgets the hold of a member that leaves without the floor check: how long it was held, or -1. */
    synchronized long releaseHold(int id, long nowMs) {
        Long since = heldSinceMs.remove(id);
        return since == null ? -1L : Math.max(0L, nowMs - since);
    }

    /** Dead members the departure gate holds on their maps now. */
    synchronized int heldDeadCount() {
        return heldSinceMs.size();
    }
}
