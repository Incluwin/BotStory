package server.agents.capabilities.partyquest.lmpq;

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

/** LMPQ aggregate root; all routing and lease state is isolated per event. */
public final class AgentLmpqSession implements AgentCompanionRunSession {
    private static final Logger log = LoggerFactory.getLogger(AgentLmpqSession.class);
    public enum Mode { TEST_OBSERVATION, AUTONOMOUS, HUMAN_MEMBER, HUMAN_LEADER }
    public enum Phase { PREPARING, ENTERING, FARMING, REGROUPING, CLEARING, REWARD, EXITING, COMPLETED, FAILED }

    private final String sessionId = UUID.randomUUID().toString();
    private final Mode mode;
    private final long seed;
    private final int operatorId;
    private final int requestedPartySize;
    private final long startedAtMs;
    private final Map<Integer, AgentLmpqMemberState> members = new LinkedHashMap<>();
    private final AgentLmpqRoomLedger rooms = new AgentLmpqRoomLedger();
    private int rendezvousRoom = AgentLmpqDefinition.RENDEZVOUS_ROOM;
    private Phase phase = Phase.PREPARING;
    private int eventLeaderId;
    private int executionAgentId;
    private long executionLeaseUntilMs;
    private long lastProgressAtMs;
    private long phaseEnteredAtMs;
    private long progressSignature = Long.MIN_VALUE;
    private boolean paused;
    private boolean terminating;
    private String failure = "";
    private EventInstanceManager eventInstance;
    private long boundAtMs;
    private boolean companionAdopted;
    private boolean memberCoordinationChatEnabled;
    private AgentPqPaceCalibration paceCalibration;
    private String potionsAtEntry;
    private int couponsAtClearing;
    private final Map<Phase, Long> firstEntries = new EnumMap<>(Phase.class);
    private final java.util.Set<Integer> departed = new java.util.LinkedHashSet<>();
    private final java.util.Set<Integer> seenHeld = new java.util.LinkedHashSet<>();
    private final Set<Integer> departuresInFlight = new java.util.LinkedHashSet<>();
    private final Map<Integer, Long> heldSinceMs = new LinkedHashMap<>();
    private final Set<String> notes = new java.util.HashSet<>();
    private final java.util.concurrent.atomic.AtomicBoolean coordinating =
            new java.util.concurrent.atomic.AtomicBoolean();
    // P6h Steps 2-4: a companion run's own state (lines, hand-over claims, full rate, downs, pace).
    private final Map<String, Long> lineWindows = new java.util.HashMap<>();
    private final Map<Integer, Integer> claims = new java.util.HashMap<>();
    private final Map<Integer, Integer> recollectCounts = new java.util.HashMap<>();
    private final Map<Integer, Boolean> fullRateHeld = new LinkedHashMap<>();
    private final Map<Integer, Long> downSinceMs = new java.util.HashMap<>();
    private final Map<Integer, Guide> guides = new java.util.HashMap<>();
    private int speakerTurn;
    private long leaderArrivedAtMs;
    private boolean failureClaimed;
    private String floorDeparture;
    private int couponsAtRegroup;
    private List<server.agents.capabilities.partyquest.pace.AgentPqMemberPower> powersAtEntry = List.of();

    public AgentLmpqSession(Mode mode, long seed, int operatorId, int requestedPartySize, long nowMs) {
        if (mode == null || operatorId <= 0 || requestedPartySize < AgentLmpqDefinition.MIN_PARTY_SIZE
                || requestedPartySize > AgentLmpqDefinition.MAX_PARTY_SIZE || nowMs < 0L) {
            throw new IllegalArgumentException("valid LMPQ session required");
        }
        this.mode = mode;
        this.seed = seed;
        this.operatorId = operatorId;
        this.requestedPartySize = requestedPartySize;
        this.startedAtMs = nowMs;
        this.lastProgressAtMs = nowMs;
        this.phaseEnteredAtMs = nowMs;
    }

    public synchronized void addMember(int id, AgentLmpqMemberState.MemberType type) {
        if (members.size() >= requestedPartySize
                || members.putIfAbsent(id, new AgentLmpqMemberState(id, type)) != null) {
            throw new IllegalStateException("invalid or duplicate LMPQ member");
        }
    }

    public synchronized void setLeadership(int leaderId, int executionId) {
        AgentLmpqMemberState execution = members.get(executionId);
        if (!members.containsKey(leaderId) || execution == null
                || execution.memberType() != AgentLmpqMemberState.MemberType.AGENT) {
            throw new IllegalArgumentException("LMPQ event leader and Agent executor must be members");
        }
        eventLeaderId = leaderId;
        executionAgentId = executionId;
    }

    public synchronized boolean claimExecutionTick(int id, long nowMs, long leaseMs) {
        return claimExecutionTick(id, nowMs, leaseMs, holder -> true);
    }

    /**
     * As {@link #claimExecutionTick(int, long, long)}, except that a holder for which {@code holderAlive} is false
     * (dead, or no longer loaded) loses its lease at once (LMPQ base fix 8): a dead Agent's tick never reaches the
     * LMPQ runtime, so its lease would otherwise freeze every coordinator pass until it ran out. A live holder keeps
     * it until it expires.
     */
    public synchronized boolean claimExecutionTick(int id, long nowMs, long leaseMs, IntPredicate holderAlive) {
        AgentLmpqMemberState member = members.get(id);
        if (member == null || member.memberType() != AgentLmpqMemberState.MemberType.AGENT) return false;
        if (id != executionAgentId && nowMs < executionLeaseUntilMs && holderAlive.test(executionAgentId)) {
            return false;
        }
        executionAgentId = id;
        executionLeaseUntilMs = nowMs + Math.max(250L, leaseMs);
        return true;
    }

    /**
     * The watchdog's claim: only a lease that has run out, or whose holder is no longer alive, as in
     * {@link #claimExecutionTick(int, long, long, IntPredicate)}.
     */
    public synchronized boolean claimExpiredExecutionTick(int id, long nowMs, long leaseMs,
                                                          IntPredicate holderAlive) {
        AgentLmpqMemberState member = members.get(id);
        if (member == null || member.memberType() != AgentLmpqMemberState.MemberType.AGENT
                || (nowMs < executionLeaseUntilMs && holderAlive.test(executionAgentId))) return false;
        executionAgentId = id;
        executionLeaseUntilMs = nowMs + Math.max(250L, leaseMs);
        return true;
    }

    public synchronized void transition(Phase next, long nowMs) {
        if (next == null || terminal() || next.ordinal() < phase.ordinal()) return;
        if (next != phase) {
            firstEntries.putIfAbsent(next, nowMs);
            logPhase(next, nowMs);
            phase = next;
            phaseEnteredAtMs = nowMs;
            markProgress(nowMs);
        }
    }

    public synchronized void observeProgressSignature(long signature, long nowMs) {
        if (progressSignature != signature) {
            progressSignature = signature;
            markProgress(nowMs);
        }
    }

    public synchronized void markProgress(long nowMs) { lastProgressAtMs = Math.max(lastProgressAtMs, nowMs); }
    public synchronized void fail(String reason, long nowMs) {
        failure = reason == null || reason.isBlank() ? "LMPQ failed" : reason;
        if (phase != Phase.FAILED) firstEntries.putIfAbsent(Phase.FAILED, nowMs);
        if (!terminal()) logPhase(Phase.FAILED, nowMs);
        phase = Phase.FAILED;
        markProgress(nowMs);
    }
    public synchronized void complete(long nowMs) {
        if (phase != Phase.COMPLETED) firstEntries.putIfAbsent(Phase.COMPLETED, nowMs);
        if (!terminal()) logPhase(Phase.COMPLETED, nowMs);
        phase = Phase.COMPLETED;
        markProgress(nowMs);
    }
    public synchronized boolean terminal() { return phase == Phase.COMPLETED || phase == Phase.FAILED; }
    public synchronized boolean beginTermination() { if (terminating) return false; terminating = true; return true; }

    /**
     * The phase line every PQ logs (HPQ/KPQ shape), for stock and companion runs alike: one per phase change, none for
     * a same-phase transition or for a fail or complete of a run that already ended. Logging only.
     */
    private void logPhase(Phase next, long nowMs) {
        log.info("LMPQ phase: session={} {}->{} phaseMs={} runMs={} companionRun={}", sessionId, phase, next,
                Math.max(0L, nowMs - phaseEnteredAtMs), Math.max(0L, nowMs - startedAtMs), companionAdopted);
    }

    /**
     * Claims the run's coordinator pass: false while another pass is under way. An Agent's tick and the watchdog can
     * both hold the lease at once (the lease refuses only another holder), and a pass is not reentrant.
     */
    boolean tryBeginCoordinating() { return coordinating.compareAndSet(false, true); }

    /** Ends the pass claimed by {@link #tryBeginCoordinating()}. */
    void endCoordinating() { coordinating.set(false); }

    /** True once a release has begun (it is claimed by beginTermination). */
    public synchronized boolean terminationStarted() { return terminating; }

    /** When the run first entered {@code phase} (a re-entry keeps the first), or 0 when it never did. */
    synchronized long firstEnteredAtMs(Phase phase) {
        Long at = phase == null ? null : firstEntries.get(phase);
        return at == null ? 0L : at;
    }

    @Override
    public String sessionId() { return sessionId; }
    public Mode mode() { return mode; }
    public long seed() { return seed; }
    public int operatorId() { return operatorId; }
    public int requestedPartySize() { return requestedPartySize; }
    public long startedAtMs() { return startedAtMs; }
    public AgentLmpqRoomLedger rooms() { return rooms; }
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

    /** Binds the run to its event at {@code nowMs} (the coordinator, on its leader's entry). */
    synchronized void bindEventInstance(EventInstanceManager value, long nowMs) {
        eventInstance = value;
        boundAtMs = nowMs;
    }

    /**
     * When the coordinator bound the run to its event; for a run bound without a time, its first FARMING entry, or
     * its admission.
     */
    synchronized long boundAtMs() {
        if (boundAtMs > 0L) return boundAtMs;
        Long farming = firstEntries.get(Phase.FARMING);
        return farming != null ? farming : startedAtMs;
    }
    public synchronized void clearEventInstance() { eventInstance = null; }
    public synchronized int rendezvousRoom() { return rendezvousRoom; }
    public synchronized void setRendezvousRoom(int room) {
        if (room != AgentLmpqDefinition.RENDEZVOUS_ROOM && room != AgentLmpqDefinition.CLEAR_ROOM) {
            throw new IllegalArgumentException("LMPQ rendezvous room must be 9 or 16");
        }
        if (phase.ordinal() >= Phase.FARMING.ordinal()) {
            throw new IllegalStateException("LMPQ rendezvous room is fixed after entry");
        }
        rendezvousRoom = room;
    }
    public synchronized AgentLmpqMemberState member(int id) { return members.get(id); }
    public synchronized Collection<AgentLmpqMemberState> members() { return List.copyOf(members.values()); }
    public synchronized int memberCount() { return members.size(); }

    /**
     * A human-led run of owner-bound companions, admitted by AgentLmpqAdmissionService.admit. Every companion-run
     * behaviour is gated on it; stock, background and GM fixture runs leave it false.
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

    /** The companions' potions as 'LMPQ potions:' logged them on entry; null when that line was never logged. */
    synchronized String potionsAtEntry() { return potionsAtEntry; }

    /** Keeps the companions' potions on entry; the first caller wins and later ones are refused (false). */
    synchronized boolean recordPotionsAtEntry(String potions) {
        if (potionsAtEntry != null || potions == null) return false;
        potionsAtEntry = potions;
        return true;
    }

    /** True the first time the run claims {@code key}: for a log line the run writes once, whoever ticks it. */
    synchronized boolean claimNote(String key) {
        return key != null && notes.add(key);
    }

    /**
     * Marks a member the bound event no longer holds as departed (LMPQ base fix 3): true the first time only, and
     * never for someone who is not a member.
     */
    synchronized boolean markDeparted(int characterId) {
        return members.containsKey(characterId) && departed.add(characterId);
    }

    /** True once the member was marked departed; the run no longer counts it. */
    synchronized boolean departed(int characterId) { return departed.contains(characterId); }

    /** Notes the members the bound event holds now: each of them has been held from then on. */
    synchronized void noteHeld(java.util.Collection<Integer> ids) {
        for (int id : ids) if (members.containsKey(id)) seenHeld.add(id);
    }

    /**
     * True once the bound event was seen holding the member. LudiMazePQ.js registers and warps a party one member at
     * a time, so a member not seen held yet is still entering, not leaving.
     */
    synchronized boolean seenHeld(int characterId) { return seenHeld.contains(characterId); }

    /** The coupons the leader held when the run moved to Pietri (CLEARING); 0 until then. */
    synchronized int couponsAtClearing() { return couponsAtClearing; }

    synchronized void setCouponsAtClearing(int coupons) { couponsAtClearing = Math.max(0, coupons); }

    /**
     * What {@link #reserveDeparture} answered a dead member: leave now (the departure is then reserved until
     * {@link #endDeparture}) or stay down. heldMs is how long it was held before leaving, or -1 when it was not held;
     * firstHold is true only for the first answer of a hold; atFloor is true when the event cannot spare it (a member
     * that leaves at the floor ends the event).
     */
    record Departure(boolean proceed, boolean firstHold, long heldMs, int registered, int othersInFlight,
                     boolean atFloor) { }

    /**
     * A dead member may leave the event while more than {@code floor} registered members would stay, every departure
     * still in flight counted as gone (LudiMazePQ.js ends the event when a member leaves at the floor). At the floor it
     * stays down only when {@code mayWait} (its body lies where it does not keep the team apart); otherwise it leaves
     * anyway, as before. registeredIds is read here, under this monitor, so a departure that has just finished is seen
     * as gone; the event's read lock nests inside this monitor, as when the coordinator reads the event's players.
     */
    synchronized Departure reserveDeparture(int id, Supplier<Set<Integer>> registeredIds, int floor, boolean mayWait,
                                            long nowMs) {
        Set<Integer> registered = registeredIds.get();
        int othersInFlight = (int) departuresInFlight.stream().filter(other -> other != id).count();
        long staying = registered.stream().filter(member -> !departuresInFlight.contains(member)).count();
        boolean atFloor = staying <= floor;
        if (!atFloor || !mayWait) {
            departuresInFlight.add(id);
            Long since = heldSinceMs.remove(id);
            return new Departure(true, false, since == null ? -1L : Math.max(0L, nowMs - since),
                    registered.size(), othersInFlight, atFloor);
        }
        Long since = heldSinceMs.putIfAbsent(id, nowMs);
        return new Departure(false, since == null, since == null ? 0L : Math.max(0L, nowMs - since),
                registered.size(), othersInFlight, true);
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

    // ---- companion run (P6h Steps 2-4) ----------------------------------------------------------------------------

    /**
     * A companion run that came up short in REGROUPING (a departed companion's coupons went with it) goes back to
     * FARMING: the only backward step a run takes. Logged as any phase change; the first entries keep the first
     * time each phase was entered. False in any other phase.
     */
    synchronized boolean resumeFarming(long nowMs) {
        if (phase != Phase.REGROUPING) return false;
        logPhase(Phase.FARMING, nowMs);
        phase = Phase.FARMING;
        phaseEnteredAtMs = nowMs;
        markProgress(nowMs);
        return true;
    }

    /**
     * True when no line of this kind was said within {@code windowMs}; the window then starts now. Companions handing
     * coupons over one after another say one line between them.
     */
    synchronized boolean claimLineWindow(String key, long nowMs, long windowMs) {
        if (key == null) return false;
        Long last = lineWindows.get(key);
        if (last != null && nowMs - last < windowMs) return false;
        lineWindows.put(key, nowMs);
        return true;
    }

    /** Whether this id is an Agent member of the run. */
    synchronized boolean agentOfRun(int id) {
        AgentLmpqMemberState member = members.get(id);
        return member != null && member.memberType() == AgentLmpqMemberState.MemberType.AGENT;
    }

    /** Claims a coupon stack lying for the leader, to pick it back up before it expires; false when one already did. */
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

    /** Records this run's full-rate hold on an Agent: true the first time, with what was required before it. */
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

    /** Marks a member down at {@code nowMs}; true the first time (until it is up again). */
    synchronized boolean markDown(int id, long nowMs) {
        return members.containsKey(id) && downSinceMs.putIfAbsent(id, Math.max(1L, nowMs)) == null;
    }

    /** When the member was first seen down, or 0 when it is not down. */
    synchronized long downSinceMs(int id) {
        Long since = downSinceMs.get(id);
        return since == null ? 0L : since;
    }

    /** The member is up again. */
    synchronized void clearDown(int id) { downSinceMs.remove(id); }

    /** Where a human the companions guide was last told the way: his room, when, and how many lines there. */
    record Guide(int room, long saidAtMs, int lines) { }

    /** The last direction said to this human, or null. */
    synchronized Guide guide(int id) { return guides.get(id); }

    /** Notes a direction said to this human in {@code room} now. */
    synchronized void noteGuide(int id, int room, long nowMs) {
        Guide previous = guides.get(id);
        int lines = previous != null && previous.room() == room ? previous.lines() + 1 : 1;
        guides.put(id, new Guide(room, nowMs, lines));
    }

    /** The next speaker's turn of {@code size} candidates, round-robin. */
    synchronized int nextSpeakerTurn(int size) {
        if (size <= 0) return 0;
        int turn = Math.floorMod(speakerTurn, size);
        speakerTurn++;
        return turn;
    }

    /** Notes the leader's first arrival in the rendezvous room after the maze entry (the walk's end); the first wins. */
    synchronized boolean noteLeaderArrived(long nowMs) {
        if (leaderArrivedAtMs > 0L) return false;
        leaderArrivedAtMs = Math.max(1L, nowMs);
        return true;
    }

    /** When the leader first stood in the rendezvous room after the entry, or 0. */
    synchronized long leaderArrivedAtMs() { return leaderArrivedAtMs; }

    /** Claims the run's one failure with a parting (the first caller wins). */
    synchronized boolean claimFailure() {
        if (failureClaimed) return false;
        failureClaimed = true;
        return true;
    }

    /** The death gate let a dead companion leave at the floor: its name, for the parting when the event ends. */
    synchronized void noteFloorDeparture(String name) {
        if (floorDeparture == null) floorDeparture = name == null ? "" : name;
    }

    /** The name of the companion whose departure at the floor ended the event; null when none did. */
    synchronized String floorDeparture() { return floorDeparture; }

    /** The coupons the run counted as secured when it regrouped (the farm span's work); 0 until then. */
    synchronized int couponsAtRegroup() { return couponsAtRegroup; }

    synchronized void setCouponsAtRegroup(int coupons) {
        if (couponsAtRegroup == 0) couponsAtRegroup = Math.max(0, coupons);
    }

    /** Each member's power as the run entered the maze (the pace's members); empty while unread. */
    synchronized List<server.agents.capabilities.partyquest.pace.AgentPqMemberPower> powersAtEntry() {
        return powersAtEntry;
    }

    /** Keeps the members' power at the entry; the first non-empty read wins. */
    synchronized void recordPowersAtEntry(List<server.agents.capabilities.partyquest.pace.AgentPqMemberPower> powers) {
        if (powersAtEntry.isEmpty() && powers != null) powersAtEntry = List.copyOf(powers);
    }

    /**
     * Takes a member out of the run (a companion that tapped out); the event leader is never taken out. The
     * requested party size stays as admitted, so memberCount() shrinks below it. Nothing calls it before Step 3.
     */
    public synchronized void removeMember(int characterId) {
        if (characterId == eventLeaderId) {
            throw new IllegalArgumentException("the LMPQ event leader cannot be removed from his run");
        }
        members.remove(characterId);
    }
}
