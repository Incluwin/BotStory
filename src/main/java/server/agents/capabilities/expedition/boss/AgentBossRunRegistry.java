package server.agents.capabilities.expedition.boss;

import scripting.event.EventInstanceManager;
import server.expeditions.Expedition;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every open boss run, by session id, by member and by expedition (identity). Writes are synchronized; reads are
 * memory only from any thread. A member stays indexed from its enlist until its run ends, refused or not.
 */
public final class AgentBossRunRegistry {
    private static final Map<Long, AgentBossRunSession> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> BY_MEMBER = new ConcurrentHashMap<>();
    private static final Map<Expedition, Long> BY_EXPEDITION = new ConcurrentHashMap<>();
    private static volatile int waiting;

    private AgentBossRunRegistry() {
    }

    static synchronized void put(AgentBossRunSession session) {
        if (session == null) {
            throw new IllegalArgumentException("a boss run session is required");
        }
        if (BY_EXPEDITION.containsKey(session.expedition())) {
            throw new IllegalStateException("the expedition already has a boss run");
        }
        SESSIONS.put(session.sessionId(), session);
        BY_EXPEDITION.put(session.expedition(), session.sessionId());
        recount();
    }

    /** Indexes one new member of {@code session}; called by the session under its own lock. */
    static synchronized void index(AgentBossRunSession session, int agentId) {
        if (SESSIONS.get(session.sessionId()) == session) {
            BY_MEMBER.put(agentId, session.sessionId());
            recount();
        }
    }

    static synchronized void remove(AgentBossRunSession session) {
        if (session == null || !SESSIONS.remove(session.sessionId(), session)) {
            return;
        }
        BY_EXPEDITION.remove(session.expedition(), session.sessionId());
        session.members().forEach(member -> BY_MEMBER.remove(member.agentId(), session.sessionId()));
        recount();
    }

    /** Recomputes the WAITING count behind {@link #anyWaiting()}; sessions call it after each tick. */
    static synchronized void recount() {
        waiting = SESSIONS.values().stream().mapToInt(AgentBossRunSession::waitingCount).sum();
    }

    public static AgentBossRunSession byId(long sessionId) {
        return SESSIONS.get(sessionId);
    }

    /** The open run this Agent was enlisted in (in any member phase), or null. */
    public static AgentBossRunSession forMember(int agentId) {
        Long sessionId = BY_MEMBER.get(agentId);
        return sessionId == null ? null : SESSIONS.get(sessionId);
    }

    /** The open run for this very expedition, or null. */
    public static AgentBossRunSession forExpedition(Expedition expedition) {
        Long sessionId = expedition == null ? null : BY_EXPEDITION.get(expedition);
        return sessionId == null ? null : SESSIONS.get(sessionId);
    }

    public static Collection<AgentBossRunSession> sessions() {
        return List.copyOf(SESSIONS.values());
    }

    /** True while any member of any run is WAITING; the fast path for per-tick callers. */
    public static boolean anyWaiting() {
        return waiting > 0;
    }

    /** The member's ordinal while it is WAITING to sign up; -1 otherwise. */
    public static int waitingOrdinal(int agentId) {
        if (waiting == 0) {
            return -1;
        }
        AgentBossRunSession session = forMember(agentId);
        AgentBossRunMember member = session == null ? null : session.member(agentId);
        return member != null && member.phase() == AgentBossRunMember.Phase.WAITING ? member.ordinal() : -1;
    }

    /** True while the Agent is an INSIDE member of an open run: adopted into the fight. Memory only, any thread. */
    public static boolean inside(int agentId) {
        AgentBossRunSession session = SESSIONS.isEmpty() ? null : forMember(agentId);
        AgentBossRunMember member = session == null ? null : session.member(agentId);
        return member != null && member.phase() == AgentBossRunMember.Phase.INSIDE;
    }

    /**
     * True when a player of {@code event} is a REGISTERED or INSIDE member of the open run for the event's expedition:
     * a companion signed up for this very fight. Memory only (the event's player lock aside), for any thread.
     */
    public static boolean signedUpIn(EventInstanceManager event) {
        AgentBossRunSession session = event == null || SESSIONS.isEmpty() ? null : forExpedition(event.getExpedition());
        if (session == null) {
            return false;
        }
        for (AgentBossRunMember member : session.members()) {
            AgentBossRunMember.Phase phase = member.phase();
            if ((phase == AgentBossRunMember.Phase.REGISTERED || phase == AgentBossRunMember.Phase.INSIDE)
                    && event.getPlayerById(member.agentId()) != null) {
                return true;
            }
        }
        return false;
    }

    /** Forgets every run; tests only. */
    static synchronized void resetForTest() {
        SESSIONS.clear();
        BY_MEMBER.clear();
        BY_EXPEDITION.clear();
        waiting = 0;
    }
}
