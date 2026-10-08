package server.agents.capabilities.partyquest.opq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.partyquest.AgentCompanionRunSession;
import server.agents.capabilities.partyquest.AgentPartyQuestCatalog;
import server.agents.capabilities.partyquest.pace.AgentPqPaceCalibration;

import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntPredicate;
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
     * lease would otherwise freeze every coordinator pass until it ran out. A live holder keeps it until it expires.
     */
    public synchronized boolean claimExecutionTick(int id, long nowMs, long leaseMs, IntPredicate holderAlive) {
        if (id != executionAgentId && nowMs < executionLeaseUntilMs && holderAlive.test(executionAgentId)) return false;
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
        if (member == null || member.memberType() != AgentOpqMemberState.MemberType.AGENT
                || (nowMs < executionLeaseUntilMs && holderAlive.test(executionAgentId))) return false;
        executionAgentId = id;
        executionLeaseUntilMs = nowMs + Math.max(1L, leaseMs);
        return true;
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
    public synchronized void advanceSealedAttempt(long nowMs) { sealedAttempt = Math.min(9, sealedAttempt + 1); sealedCheckedAtMs = nowMs; markProgress(nowMs); }
    public synchronized long sealedCheckedAtMs() { return sealedCheckedAtMs; }
    public synchronized void markSealedChecked(long nowMs) { sealedCheckedAtMs = nowMs; }
    public synchronized int wayUpLeverAttempt() { return wayUpLeverAttempt; }
    public synchronized void advanceWayUpLeverAttempt(long nowMs) { wayUpLeverAttempt = Math.min(9, wayUpLeverAttempt + 1); markProgress(nowMs); }
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

    /** Takes a member out of the run (a companion that tapped out); the event leader is never taken out. */
    public synchronized void removeMember(int characterId) {
        if (characterId == eventLeaderId) {
            throw new IllegalArgumentException("the OPQ event leader cannot be removed from his run");
        }
        members.remove(characterId);
    }
}
