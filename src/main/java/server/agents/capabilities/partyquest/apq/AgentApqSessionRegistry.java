package server.agents.capabilities.partyquest.apq;

import client.Character;
import client.status.MonsterStatus;
import server.life.Monster;
import server.maps.MapItem;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** APQ-only session indexes. */
public final class AgentApqSessionRegistry {
    private static final Map<String, AgentApqSession> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<Integer, String> BY_MEMBER = new ConcurrentHashMap<>();
    private static final Map<Integer, String> BY_OPERATOR = new ConcurrentHashMap<>();

    private AgentApqSessionRegistry() {
    }

    /** Publishes a complete session: exactly six members (AmoriaPQ.js minPlayers and maxPlayers). */
    public static synchronized void registerComplete(AgentApqSession session) {
        if (session == null || session.memberCount() != AgentApqDefinition.PARTY_SIZE) {
            throw new IllegalArgumentException("complete six-member APQ session required");
        }
        if (BY_OPERATOR.containsKey(session.operatorId())) throw new IllegalStateException("operator already owns APQ");
        for (AgentApqMemberState member : session.members()) {
            if (BY_MEMBER.containsKey(member.characterId())) throw new IllegalStateException("APQ member already reserved");
        }
        SESSIONS.put(session.sessionId(), session);
        BY_OPERATOR.put(session.operatorId(), session.sessionId());
        session.members().forEach(member -> BY_MEMBER.put(member.characterId(), session.sessionId()));
    }

    public static AgentApqSession forMember(int id) {
        String key = BY_MEMBER.get(id);
        return key == null ? null : SESSIONS.get(key);
    }

    public static AgentApqSession forOperator(int id) {
        String key = BY_OPERATOR.get(id);
        return key == null ? null : SESSIONS.get(key);
    }

    public static boolean active(int id) {
        return forMember(id) != null;
    }

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

    /** Stops routing one member to the session; the session keeps its record. */
    public static synchronized void unindexMember(AgentApqSession session, int characterId) {
        if (session != null) BY_MEMBER.remove(characterId, session.sessionId());
    }

    public static Collection<AgentApqSession> sessions() {
        return List.copyOf(SESSIONS.values());
    }

    public static synchronized void remove(AgentApqSession session) {
        if (session == null || !SESSIONS.remove(session.sessionId(), session)) return;
        BY_OPERATOR.remove(session.operatorId(), session.sessionId());
        session.members().forEach(member -> BY_MEMBER.remove(member.characterId(), session.sessionId()));
    }

    /**
     * Whether this bot may loot this drop of an APQ objective item (APQ plan A4): a member of the run's event picks up
     * the Wing Hammer, the Mirror's shard, the Cupid Code Pieces and the Geist Fang where they fell, to bring them to
     * the leader (only he hands them in); a stack a companion dropped at his feet is his, and only the companion that
     * claimed it back before it can expire may take it again.
     */
    public static boolean canLootExclusive(Character character, MapItem drop) {
        if (character == null || drop == null || !AgentApqDefinition.EXCLUSIVE_ITEMS.contains(drop.getItemId())) {
            return false;
        }
        AgentApqSession session = forMember(character.getId());
        if (session == null || session.eventInstance() == null
                || character.getEventInstance() != session.eventInstance()) {
            return false;
        }
        if (character.getId() == session.eventLeaderId()) return true;
        if (drop.isPlayerDrop()) return session.recollectorOf(drop.getObjectId()) == character.getId();
        return true;
    }

    /**
     * Whether an APQ companion must leave {@code monster} alone now (the boss gate's rule, APQ plan A4 S6): a member of
     * a run hitting a monster of its own event that is immune to its damage type or reflects it. Geist Balrog's last
     * phase casts weapon and magic immunity; a physical member switches to another target or holds while it lasts.
     */
    public static boolean refusesTarget(Character agent, Monster monster) {
        if (agent == null || monster == null || monster.getMap() == null) return false;
        AgentApqSession session = forMember(agent.getId());
        if (session == null || session.eventInstance() == null
                || monster.getMap().getEventInstance() != session.eventInstance()
                || agent.getEventInstance() != session.eventInstance()) {
            return false;
        }
        boolean magic = agent.getJob() != null && agent.getJob().getJobNiche() == 2;
        return monster.isBuffed(magic ? MonsterStatus.MAGIC_IMMUNITY : MonsterStatus.WEAPON_IMMUNITY)
                || monster.isBuffed(magic ? MonsterStatus.MAGIC_REFLECT : MonsterStatus.WEAPON_REFLECT);
    }
}
