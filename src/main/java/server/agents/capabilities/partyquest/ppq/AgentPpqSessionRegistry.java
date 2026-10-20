package server.agents.capabilities.partyquest.ppq;

import client.Character;
import server.maps.MapItem;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** PPQ-only session indexes. */
public final class AgentPpqSessionRegistry {
    private static final Map<String, AgentPpqSession> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<Integer, String> BY_MEMBER = new ConcurrentHashMap<>();
    private static final Map<Integer, String> BY_OPERATOR = new ConcurrentHashMap<>();
    private AgentPpqSessionRegistry() { }
    /**
     * Publishes a complete session: six members for every stock mode, four to six for a companion run (its flag is set
     * before this call).
     */
    public static synchronized void registerComplete(AgentPpqSession session) {
        if (session == null) throw new IllegalArgumentException("complete six-member PPQ session required");
        if (session.companionAdopted()) {
            if (session.memberCount() < AgentPpqDefinition.COMPANION_MIN_PARTY_SIZE
                    || session.memberCount() > AgentPpqDefinition.COMPANION_MAX_PARTY_SIZE) {
                throw new IllegalArgumentException("a PPQ companion run needs four to six members");
            }
        } else if (session.memberCount() != AgentPpqDefinition.PARTY_SIZE) {
            throw new IllegalArgumentException("complete six-member PPQ session required");
        }
        if (BY_OPERATOR.containsKey(session.operatorId())) throw new IllegalStateException("operator already owns PPQ");
        for (AgentPpqMemberState member : session.members()) if (BY_MEMBER.containsKey(member.characterId())) {
            throw new IllegalStateException("PPQ member already reserved");
        }
        SESSIONS.put(session.sessionId(), session); BY_OPERATOR.put(session.operatorId(), session.sessionId());
        session.members().forEach(member -> BY_MEMBER.put(member.characterId(), session.sessionId()));
    }
    public static AgentPpqSession forMember(int id) { String key = BY_MEMBER.get(id); return key == null ? null : SESSIONS.get(key); }
    public static AgentPpqSession forOperator(int id) { String key = BY_OPERATOR.get(id); return key == null ? null : SESSIONS.get(key); }
    public static boolean active(int id) { return forMember(id) != null; }

    /**
     * What already holds an admission's operator or members: "operator-session", "member-<id>" for the first member
     * another session holds, or "" when nothing does.
     */
    public static synchronized String registrationBlocker(int operatorId, Collection<Integer> characterIds) {
        if (BY_OPERATOR.containsKey(operatorId)) return "operator-session";
        for (int id : characterIds) {
            if (BY_MEMBER.containsKey(id)) return "member-" + id;
        }
        return "";
    }

    /** Stops routing one member to the session (a departed or removed Agent); the session keeps its record. */
    public static synchronized void unindexMember(AgentPpqSession session, int characterId) {
        if (session != null) BY_MEMBER.remove(characterId, session.sessionId());
    }
    public static Collection<AgentPpqSession> sessions() { return List.copyOf(SESSIONS.values()); }
    public static synchronized void remove(AgentPpqSession session) {
        if (session == null || !SESSIONS.remove(session.sessionId(), session)) return;
        BY_OPERATOR.remove(session.operatorId(), session.sessionId());
        session.members().forEach(member -> BY_MEMBER.remove(member.characterId(), session.sessionId()));
    }
    /**
     * Whether this bot may loot this drop of a PPQ objective item. In a companion run the companions gather the medal
     * stage's current marks for the human leader (only he may hand them to Guon): a natural drop of the wave's mark is
     * theirs to pick up, a stack a companion dropped for him is his, and only the companion that claimed it back before
     * it can expire may take it. A companion never takes a chest key (decision D-b: only the leader's key opens a
     * chest). Everything else is {@link #canLootExclusive(Character, int)}, unchanged.
     */
    public static boolean canLootExclusive(Character character, MapItem drop) {
        if (character == null || drop == null) return false;
        int itemId = drop.getItemId();
        if (itemId == AgentPpqDefinition.CHEST_KEY) {
            AgentPpqSession session = forMember(character.getId());
            if (session != null && session.companionAdopted() && character.getId() != session.eventLeaderId()
                    && session.agentOfRun(character.getId())) {
                return false;
            }
        }
        if (AgentPpqDefinition.MEDALS.contains(itemId)) {
            AgentPpqSession session = forMember(character.getId());
            if (session != null && session.companionAdopted() && character.getId() != session.eventLeaderId()
                    && session.eventInstance() != null && character.getEventInstance() == session.eventInstance()) {
                if (drop.isPlayerDrop()) return session.recollectorOf(drop.getObjectId()) == character.getId();
                return itemId == AgentPpqCoordinator.expectedMedal(
                        AgentPpqCoordinator.character(session.eventLeaderId()));
            }
        }
        return canLootExclusive(character, itemId);
    }

    public static boolean canLootExclusive(Character character, int itemId) {
        if (character == null || !AgentPpqDefinition.EXCLUSIVE_ITEMS.contains(itemId)) return false;
        AgentPpqSession session = forMember(character.getId());
        if (session == null || session.eventInstance() == null
                || character.getEventInstance() != session.eventInstance()) return false;
        if (AgentPpqDefinition.MEDALS.contains(itemId)) return character.getId() == session.eventLeaderId();
        return true;
    }
}
