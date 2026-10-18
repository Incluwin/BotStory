package server.agents.capabilities.partyquest.opq;

import client.Character;
import server.maps.MapItem;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** OPQ-only complete session indexes plus global loot boundary hooks. */
public final class AgentOpqSessionRegistry {
    private static final Map<String, AgentOpqSession> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<Integer, String> BY_MEMBER = new ConcurrentHashMap<>();
    private static final Map<Integer, String> BY_OPERATOR = new ConcurrentHashMap<>();
    private AgentOpqSessionRegistry() { }

    /**
     * Publishes a session that holds every member it was opened for: six for every stock and fixture run, five or six
     * for a companion run.
     */
    public static synchronized void registerComplete(AgentOpqSession session) {
        if (session == null || session.memberCount() != session.requestedPartySize()) {
            throw new IllegalArgumentException(session == null
                    || session.requestedPartySize() == AgentOpqDefinition.PARTY_SIZE
                    ? "complete six-member OPQ session required" : "complete OPQ session required");
        }
        if (BY_OPERATOR.containsKey(session.operatorId())) throw new IllegalStateException("operator already owns OPQ");
        for (AgentOpqMemberState member : session.members()) if (BY_MEMBER.containsKey(member.characterId())) {
            throw new IllegalStateException("OPQ member already reserved");
        }
        SESSIONS.put(session.sessionId(), session);
        BY_OPERATOR.put(session.operatorId(), session.sessionId());
        session.members().forEach(member -> BY_MEMBER.put(member.characterId(), session.sessionId()));
    }

    /**
     * What already holds this operator or one of these characters in an OPQ session, in HPQ's codes:
     * "operator-session", else "member-" and the id of the first held member, else "". A host checks a party with it
     * before an admit.
     */
    public static synchronized String registrationBlocker(int operatorId, Collection<Integer> characterIds) {
        if (BY_OPERATOR.containsKey(operatorId)) return "operator-session";
        if (characterIds != null) {
            for (int characterId : characterIds) {
                if (BY_MEMBER.containsKey(characterId)) return "member-" + characterId;
            }
        }
        return "";
    }

    /** Forgets one member's index only; the session and its other members stay as they are. */
    public static synchronized void unindexMember(AgentOpqSession session, int characterId) {
        if (session != null) BY_MEMBER.remove(characterId, session.sessionId());
    }

    public static AgentOpqSession forMember(int id) {
        String sessionId = BY_MEMBER.get(id);
        return sessionId == null ? null : SESSIONS.get(sessionId);
    }
    public static AgentOpqSession forOperator(int id) {
        String sessionId = BY_OPERATOR.get(id);
        return sessionId == null ? null : SESSIONS.get(sessionId);
    }
    public static boolean active(int id) { return forMember(id) != null; }

    /**
     * 2026-10-17: Eak's answer to a wrong sealed-room check, called by 2013001.js as he says it: true for "One of the
     * platforms has the right number of players", false for "All of the platforms have the wrong amount". Kept for the
     * run of the player who asked ({@code characterId}); nothing outside a run. Only what Eak tells the party.
     */
    public static void recordSealedVerdict(int characterId, boolean onePlatformRight) {
        recordSealedVerdict(characterId, onePlatformRight, null);
    }

    /**
     * 2026-10-18: {@link #recordSealedVerdict(int, boolean)} with the players Eak counted on the left, middle and right
     * platform ({@code observed}, "111"; 2013001.js joins its count). In a companion run its human leader leads, his
     * check is kept with that split for the next coordinator pass (AgentOpqConductor), so a check made while the
     * holders were still moving prunes the search right; every other talker's answer is kept as before.
     */
    public static void recordSealedVerdict(int characterId, boolean onePlatformRight, String observed) {
        AgentOpqSession session = forMember(characterId);
        if (session == null) return;
        if (AgentOpqConductor.humanLeaderCheck(session, characterId)) {
            session.recordHumanSealedCheck(AgentOpqSealedSearch.Split.parse(observed), onePlatformRight);
        } else {
            session.recordSealedVerdict(onePlatformRight);
        }
    }

    /**
     * 2026-10-18: Eak's answer to a wrong lever check on the way up, called by 2013001.js as he says it: true for "One
     * of the pushed levers is correct", false for "Both of the pushed levers are wrong", with the five levers as they
     * stood ({@code observed}, "01010"). Kept only for a companion run its human leader leads, when he asked; the
     * runner then sets the next pair (AgentOpqConductor). Only what Eak tells the party.
     */
    public static void recordWayUpVerdict(int characterId, boolean oneLeverRight, String observed) {
        AgentOpqSession session = forMember(characterId);
        if (session != null && AgentOpqConductor.humanLeaderCheck(session, characterId)) {
            session.recordHumanLeverCheck(AgentOpqLeverSearch.Pair.parse(observed), oneLeverRight);
        }
    }
    public static Collection<AgentOpqSession> sessions() { return List.copyOf(SESSIONS.values()); }
    public static synchronized void remove(AgentOpqSession session) {
        if (session == null || !SESSIONS.remove(session.sessionId(), session)) return;
        BY_OPERATOR.remove(session.operatorId(), session.sessionId());
        session.members().forEach(member -> BY_MEMBER.remove(member.characterId(), session.sessionId()));
    }

    /**
     * 2026-10-18: whether this bot may loot this drop of an OPQ objective item. In a companion run its human leader
     * leads, a stack of walkway or lounge fragments a companion dropped for him is his, and so is a statue piece he
     * dropped himself: only the companion that claimed the stack (to pick it back up before it expires, or to take the
     * piece to the statue) may take it. Everything else is {@link #canLootExclusive(Character, int)}, unchanged.
     */
    public static boolean canLootExclusive(Character character, MapItem drop) {
        if (character == null || drop == null) return false;
        int itemId = drop.getItemId();
        AgentOpqSession session = forMember(character.getId());
        if (session != null && drop.isPlayerDrop() && AgentOpqConductor.conducts(session)
                && session.eventInstance() != null && character.getEventInstance() == session.eventInstance()) {
            boolean fragmentsForHim = (itemId == AgentOpqDefinition.WALKWAY_FRAGMENT
                    || itemId == AgentOpqDefinition.LOUNGE_FRAGMENT) && session.agentOfRun(drop.getOwnerId());
            boolean hisPiece = AgentOpqDefinition.STATUE_PIECES.contains(itemId)
                    && drop.getOwnerId() == session.eventLeaderId();
            if (fragmentsForHim || hisPiece) return session.claimerOf(drop.getObjectId()) == character.getId();
        }
        return canLootExclusive(character, itemId);
    }

    public static boolean canLootExclusive(Character character, int itemId) {
        if (character == null || !AgentOpqDefinition.EXCLUSIVE_ITEMS.contains(itemId)) return false;
        AgentOpqSession session = forMember(character.getId());
        if (session == null || session.eventInstance() == null
                || character.getEventInstance() != session.eventInstance()) return false;
        AgentOpqMemberState member = session.member(character.getId());
        if (member == null) return false;
        if (itemId == AgentOpqDefinition.CLOUD_PIECE) {
            return character.getMapId() == AgentOpqDefinition.ENTRANCE_MAP
                    && member.role() == AgentOpqMemberState.Role.ENTRANCE_COLLECTOR;
        }
        if (itemId == AgentOpqDefinition.WALKWAY_FRAGMENT) {
            return character.getMapId() == AgentOpqDefinition.WALKWAY_MAP
                    && member.assignedRoom() == AgentOpqDefinition.Room.WALKWAY;
        }
        if (itemId == AgentOpqDefinition.LOUNGE_FRAGMENT) {
            return AgentOpqDefinition.roomForMap(character.getMapId()) == AgentOpqDefinition.Room.LOUNGE
                    && member.assignedRoom() == AgentOpqDefinition.Room.LOUNGE;
        }
        return session.loot().canLoot(itemId, character.getId(), character.getMapId());
    }

    public static boolean preservesMarker(Character character, MapItem drop) {
        if (character == null || drop == null || drop.getMeso() != 10) return false;
        AgentOpqSession session = forMember(character.getId());
        return session != null && session.phase() == AgentOpqSession.Phase.SPLIT_ROOMS
                && character.getEventInstance() == session.eventInstance();
    }
}
