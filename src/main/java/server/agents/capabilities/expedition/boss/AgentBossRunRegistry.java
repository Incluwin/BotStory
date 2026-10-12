package server.agents.capabilities.expedition.boss;

import client.Character;
import scripting.event.EventInstanceManager;
import server.expeditions.Expedition;
import server.life.Monster;
import server.maps.MapItem;
import server.maps.MapleMap;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every open boss run, by session id, by member, by expedition (identity) and, for a party-instance run (P12 G7), by
 * its event (identity). Writes are synchronized; reads are memory only from any thread. A member stays indexed from its
 * enlist until its run ends, refused or not.
 */
public final class AgentBossRunRegistry {
    private static final Map<Long, AgentBossRunSession> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> BY_MEMBER = new ConcurrentHashMap<>();
    private static final Map<Expedition, Long> BY_EXPEDITION = new ConcurrentHashMap<>();
    private static final Map<EventInstanceManager, Long> BY_EVENT = new ConcurrentHashMap<>();
    private static volatile int waiting;

    private AgentBossRunRegistry() {
    }

    static synchronized void put(AgentBossRunSession session) {
        if (session == null) {
            throw new IllegalArgumentException("a boss run session is required");
        }
        Expedition expedition = session.expedition();
        if (expedition != null ? BY_EXPEDITION.containsKey(expedition) : BY_EVENT.containsKey(session.event())) {
            throw new IllegalStateException("the expedition or event already has a boss run");
        }
        SESSIONS.put(session.sessionId(), session);
        if (expedition != null) {
            BY_EXPEDITION.put(expedition, session.sessionId());
        } else {
            BY_EVENT.put(session.event(), session.sessionId());
        }
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
        if (session.expedition() != null) {
            BY_EXPEDITION.remove(session.expedition(), session.sessionId());
        } else {
            BY_EVENT.remove(session.event(), session.sessionId());
        }
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

    /**
     * The open run that fights in this very event, or null: a party-instance run keyed by it (P12 G7), else the run of
     * the event's expedition.
     */
    public static AgentBossRunSession forEvent(EventInstanceManager event) {
        if (event == null) {
            return null;
        }
        Long sessionId = BY_EVENT.get(event);
        return sessionId != null ? SESSIONS.get(sessionId) : forExpedition(event.getExpedition());
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

    /**
     * True while the Agent is an INSIDE or LEAVING member of an open run: adopted into the fight and not yet out of
     * its event (a leaving member still walks the battle map or the reward room). Memory only, any thread.
     */
    public static boolean inside(int agentId) {
        AgentBossRunSession session = SESSIONS.isEmpty() ? null : forMember(agentId);
        AgentBossRunMember member = session == null ? null : session.member(agentId);
        AgentBossRunMember.Phase phase = member == null ? null : member.phase();
        return phase == AgentBossRunMember.Phase.INSIDE || phase == AgentBossRunMember.Phase.LEAVING;
    }

    /**
     * P12 G11: true while the Agent is a REGISTERED, INSIDE or LEAVING member of an open run, the members whose map
     * may bar summons (the entry NPC's room, the battle map, the way out). Memory only, any thread.
     */
    public static boolean barsSummons(int agentId) {
        AgentBossRunSession session = SESSIONS.isEmpty() ? null : forMember(agentId);
        AgentBossRunMember member = session == null ? null : session.member(agentId);
        AgentBossRunMember.Phase phase = member == null ? null : member.phase();
        return phase == AgentBossRunMember.Phase.REGISTERED || phase == AgentBossRunMember.Phase.INSIDE
                || phase == AgentBossRunMember.Phase.LEAVING;
    }

    /**
     * True when a player of {@code event} is a REGISTERED or INSIDE member of the open run for the event (forEvent):
     * a companion signed up for this very fight. Memory only (the event's player lock aside), for any thread.
     */
    public static boolean signedUpIn(EventInstanceManager event) {
        AgentBossRunSession session = event == null || SESSIONS.isEmpty() ? null : forEvent(event);
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

    /**
     * True for a fake part (Zakum's body before its arms fall) in the event of an open run that never targets fakes:
     * a hit on it is thrown away, so the combat selector never takes it, not even as a secondary or projectile target.
     * Also true for a part the run's boss rules never let a member hit (AgentBossRules.neverTarget, P11: Normal
     * Balrog's helpers and seal, whose death would release a claw early). Memory only, any thread; false at once while
     * no run is open. The fake flag is read last, because it takes the monster's lock and every other read here is a
     * plain field or map read.
     */
    public static boolean neverTarget(Monster monster) {
        if (SESSIONS.isEmpty() || monster == null) {
            return false;
        }
        MapleMap map = monster.getMap();
        EventInstanceManager event = map == null ? null : map.getEventInstance();
        AgentBossRunSession session = event == null ? null : forEvent(event);
        if (session == null) {
            return false;
        }
        return AgentBossRules.neverTarget(session.definition().key(), monster.getId())
                || session.definition().neverTargetFakes() && monster.isFake();
    }

    /**
     * True when the drop is a marker item of the Agent's own run (Zakum's Eye of Fire, which the altar must take) and
     * the Agent is an INSIDE or LEAVING member: it leaves the drop on the floor. Memory only, any thread.
     */
    public static boolean preservesMarker(Character agent, MapItem drop) {
        if (SESSIONS.isEmpty() || agent == null || drop == null) {
            return false;
        }
        AgentBossRunSession session = forMember(agent.getId());
        AgentBossRunMember member = session == null ? null : session.member(agent.getId());
        AgentBossRunMember.Phase phase = member == null ? null : member.phase();
        return (phase == AgentBossRunMember.Phase.INSIDE || phase == AgentBossRunMember.Phase.LEAVING)
                && session.definition().markerItemIds().contains(drop.getItemId());
    }

    /** Forgets every run; tests only. */
    static synchronized void resetForTest() {
        SESSIONS.clear();
        BY_MEMBER.clear();
        BY_EXPEDITION.clear();
        BY_EVENT.clear();
        waiting = 0;
    }
}
