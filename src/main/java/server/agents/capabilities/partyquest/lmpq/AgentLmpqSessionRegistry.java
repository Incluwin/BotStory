package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import server.maps.MapItem;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** LMPQ-only complete session and member indexes. */
public final class AgentLmpqSessionRegistry {
    private static final Map<String, AgentLmpqSession> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<Integer, String> BY_MEMBER = new ConcurrentHashMap<>();
    private static final Map<Integer, String> BY_OPERATOR = new ConcurrentHashMap<>();

    private AgentLmpqSessionRegistry() { }

    public static synchronized void registerComplete(AgentLmpqSession session) {
        if (session == null || session.memberCount() != session.requestedPartySize()) {
            throw new IllegalArgumentException("complete LMPQ session required");
        }
        if (BY_OPERATOR.containsKey(session.operatorId())) throw new IllegalStateException("operator already owns LMPQ");
        for (AgentLmpqMemberState member : session.members()) {
            if (BY_MEMBER.containsKey(member.characterId())) throw new IllegalStateException("LMPQ member already reserved");
        }
        SESSIONS.put(session.sessionId(), session);
        BY_OPERATOR.put(session.operatorId(), session.sessionId());
        session.members().forEach(member -> BY_MEMBER.put(member.characterId(), session.sessionId()));
    }

    public static synchronized String registrationBlocker(int operatorId, Collection<Integer> ids) {
        if (BY_OPERATOR.containsKey(operatorId)) return "operator-session";
        if (ids != null) for (int id : ids) if (BY_MEMBER.containsKey(id)) return "member-" + id;
        return "";
    }

    public static AgentLmpqSession forMember(int id) {
        String sessionId = BY_MEMBER.get(id);
        return sessionId == null ? null : SESSIONS.get(sessionId);
    }
    public static AgentLmpqSession forOperator(int id) {
        String sessionId = BY_OPERATOR.get(id);
        return sessionId == null ? null : SESSIONS.get(sessionId);
    }
    public static boolean active(int id) { return forMember(id) != null; }
    public static Collection<AgentLmpqSession> sessions() { return List.copyOf(SESSIONS.values()); }

    /** Drops one member's index to {@code session} (a member that left the run); other sessions' indexes stay. */
    public static synchronized void unindexMember(AgentLmpqSession session, int characterId) {
        if (session == null) return;
        BY_MEMBER.remove(characterId, session.sessionId());
    }

    public static synchronized void remove(AgentLmpqSession session) {
        if (session == null || !SESSIONS.remove(session.sessionId(), session)) return;
        BY_OPERATOR.remove(session.operatorId(), session.sessionId());
        session.members().forEach(member -> BY_MEMBER.remove(member.characterId(), session.sessionId()));
    }

    public static boolean canLootCoupon(Character character) {
        AgentLmpqSession session = character == null ? null : forMember(character.getId());
        return session != null && session.eventInstance() != null
                && character.getEventInstance() == session.eventInstance()
                && AgentLmpqDefinition.isEventMap(character.getMapId());
    }

    /**
     * Whether this bot leaves this drop alone: a farmer's 10-meso portal marker while the run farms, and (P6h Step 2d,
     * a companion run) a coupon stack one of the run's companions dropped for the leader, which only the companion
     * that claimed it back (before it expires) may take. AgentLootEligibility asks it for every drop.
     */
    public static boolean preservesPortalMarker(Character character, MapItem drop) {
        if (character == null || drop == null) return false;
        if (drop.getItemId() == AgentLmpqDefinition.COUPON) return keepsHandOverStack(character, drop);
        if (drop.getMeso() != AgentLmpqDefinition.ROOM_MARKER_MESOS) return false;
        AgentLmpqSession session = forMember(character.getId());
        return session != null && session.eventInstance() != null
                && character.getEventInstance() == session.eventInstance()
                && session.phase() == AgentLmpqSession.Phase.FARMING;
    }

    /** The hand-over clause of {@link #preservesPortalMarker}: a run Agent's coupon stack, claimed by someone else. */
    static boolean keepsHandOverStack(Character character, MapItem drop) {
        AgentLmpqSession session = forMember(character.getId());
        return session != null && session.companionAdopted() && drop.isPlayerDrop()
                && session.agentOfRun(drop.getOwnerId()) && session.eventInstance() != null
                && character.getEventInstance() == session.eventInstance()
                && session.claimerOf(drop.getObjectId()) != character.getId();
    }
}
