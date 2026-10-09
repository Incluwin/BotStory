package server.agents.capabilities.partyquest.rjpq;

import client.Character;
import server.maps.MapItem;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** RJPQ's complete session indexes, both sides together (a session knows its side). */
public final class AgentRjpqSessionRegistry {
    private static final Map<String, AgentRjpqSession> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<Integer, String> BY_MEMBER = new ConcurrentHashMap<>();
    private static final Map<Integer, String> BY_OPERATOR = new ConcurrentHashMap<>();

    private AgentRjpqSessionRegistry() {
    }

    /** Publishes a session of exactly four members whose operator and members hold no other RJPQ session. */
    public static synchronized void registerComplete(AgentRjpqSession session) {
        if (session == null || session.memberCount() != AgentRjpqSide.PARTY_SIZE) {
            throw new IllegalArgumentException("complete four-member RJPQ session required");
        }
        if (BY_OPERATOR.containsKey(session.operatorId())) {
            throw new IllegalStateException("operator already owns RJPQ");
        }
        for (AgentRjpqMemberState member : session.members()) {
            if (BY_MEMBER.containsKey(member.characterId())) {
                throw new IllegalStateException("RJPQ member already reserved");
            }
        }
        SESSIONS.put(session.sessionId(), session);
        BY_OPERATOR.put(session.operatorId(), session.sessionId());
        session.members().forEach(member -> BY_MEMBER.put(member.characterId(), session.sessionId()));
    }

    /**
     * What already holds this operator or one of these characters in an RJPQ session, in HPQ's codes:
     * "operator-session", else "member-" and the first held member's id, else "".
     */
    public static synchronized String registrationBlocker(int operatorId, Collection<Integer> characterIds) {
        if (BY_OPERATOR.containsKey(operatorId)) {
            return "operator-session";
        }
        if (characterIds != null) {
            for (int characterId : characterIds) {
                if (BY_MEMBER.containsKey(characterId)) {
                    return "member-" + characterId;
                }
            }
        }
        return "";
    }

    /** Forgets one member's index only; the session and its other members stay as they are. */
    public static synchronized void unindexMember(AgentRjpqSession session, int characterId) {
        if (session != null) {
            BY_MEMBER.remove(characterId, session.sessionId());
        }
    }

    public static AgentRjpqSession forMember(int id) {
        String sessionId = BY_MEMBER.get(id);
        return sessionId == null ? null : SESSIONS.get(sessionId);
    }

    public static AgentRjpqSession forOperator(int id) {
        String sessionId = BY_OPERATOR.get(id);
        return sessionId == null ? null : SESSIONS.get(sessionId);
    }

    /**
     * The RJPQ refuse-only loot clause (plan 7.7) the shared loot eligibility asks once the wiring lands: false only
     * when a companion standing in its run's bound event may not take this drop by the run's rules
     * ({@link AgentRjpqLootRule}); true for everyone and everything else, which the stock rules then decide.
     */
    public static boolean canLoot(Character bot, MapItem drop) {
        if (bot == null || drop == null) {
            return true;
        }
        AgentRjpqSession session = forMember(bot.getId());
        AgentRjpqMemberState member = session == null ? null : session.member(bot.getId());
        if (member == null || !member.agent() || session.eventInstance() == null
                || bot.getEventInstance() != session.eventInstance()) {
            return true;
        }
        return AgentRjpqLootRule.allows(AgentRjpqLootRule.facts(session, bot, drop));
    }

    public static boolean active(int id) {
        return forMember(id) != null;
    }

    public static Collection<AgentRjpqSession> sessions() {
        return List.copyOf(SESSIONS.values());
    }

    public static synchronized void remove(AgentRjpqSession session) {
        if (session == null || !SESSIONS.remove(session.sessionId(), session)) {
            return;
        }
        BY_OPERATOR.remove(session.operatorId(), session.sessionId());
        session.members().forEach(member -> BY_MEMBER.remove(member.characterId(), session.sessionId()));
    }
}
