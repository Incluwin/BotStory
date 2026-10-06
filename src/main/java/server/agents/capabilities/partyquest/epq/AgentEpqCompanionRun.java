package server.agents.capabilities.partyquest.epq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;

/**
 * A companion run's side effects (a player leading his own companions, adopted through AgentEpqAdmissionService.admit),
 * called from the coordinator at its gated points: who speaks, what the companions say, and how the run waits, carries
 * on and ends. Stock, Agent-led and GM-observed runs never come here. Nothing here holds the session monitor while it
 * moves a character, drops, chats or logs.
 */
final class AgentEpqCompanionRun {
    private static final Logger log = LoggerFactory.getLogger(AgentEpqCompanionRun.class);

    private AgentEpqCompanionRun() {
    }

    // ---- who speaks ----

    /** True when the line was said now; each key is said once a run, and a missing speaker leaves it unclaimed. */
    static boolean narrate(AgentEpqSession session, Character speaker, String key, String line) {
        if (speaker == null || line == null || !session.narrateOnce(key)) return false;
        AgentEpqDialogue.sayMapNow(session.sessionId(), key, speaker, line);
        return true;
    }

    /** A companion run's coordination line, while its host has the members' coordination chat on. */
    static boolean coordinate(AgentEpqSession session, Character speaker, String key, String line) {
        if (!session.companionAdopted() || !session.memberCoordinationChatEnabled()) return false;
        return narrate(session, speaker, key, line);
    }

    /**
     * Who says a line the leader should hear: the first companion (session order) that is online, up, not departed, in
     * the run's bound event and on the leader's map; null when none is (the line waits for a later pass).
     */
    static Character narrator(AgentEpqSession session, Character leader) {
        if (leader == null) return null;
        for (AgentEpqMemberState member : session.members()) {
            Character agent = standing(session, member);
            if (agent != null && agent.getMapId() == leader.getMapId()) return agent;
        }
        return null;
    }

    /** The first companion standing in the run's bound event, on any map; null when none is. */
    static Character anyStanding(AgentEpqSession session) {
        for (AgentEpqMemberState member : session.members()) {
            Character agent = standing(session, member);
            if (agent != null) return agent;
        }
        return null;
    }

    /** A companion of the run that is online, up, not departed and in the run's bound event; null otherwise. */
    static Character standing(AgentEpqSession session, AgentEpqMemberState member) {
        if (member == null || member.memberType() != AgentEpqMemberState.MemberType.AGENT
                || session.departed(member.characterId())) {
            return null;
        }
        Character agent = AgentEpqCoordinator.character(member.characterId());
        return agent != null && agent.getHp() > 0 && inBoundEvent(session, agent) ? agent : null;
    }

    /** This character stands in the run's bound event instance. */
    static boolean inBoundEvent(AgentEpqSession session, Character character) {
        EventInstanceManager bound = session.eventInstance();
        return character != null && bound != null && character.getEventInstance() == bound;
    }
}
