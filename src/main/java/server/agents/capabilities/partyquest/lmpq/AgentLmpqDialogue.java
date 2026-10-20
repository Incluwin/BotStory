package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.dialogue.AgentChatTextSanitizer;
import server.agents.integration.AgentPartyGatewayRuntime;

/**
 * A companion run's LMPQ chat (P6h Step 2): party chat, since the party is spread over the maze's rooms and the leader
 * hears no map chat from another one (the plan's decision), sanitized as a player's line; every line is logged at info
 * as 'LMPQ line:' with its key, so a run's wording reads straight from the log. Companions speak only while the run's
 * PQ guidance is on (COMPANION_PQ_GUIDANCE, through the session's coordination chat switch). Never throws.
 */
final class AgentLmpqDialogue {
    private static final Logger log = LoggerFactory.getLogger(AgentLmpqDialogue.class);

    private AgentLmpqDialogue() {
    }

    /** Says the line now while the run's guidance is on; false when it is off, nothing is to be said, or it failed. */
    static boolean say(AgentLmpqSession session, String key, Character speaker, String message) {
        if (session == null || speaker == null || message == null || !session.memberCoordinationChatEnabled()) {
            return false;
        }
        try {
            String text = AgentChatTextSanitizer.sanitize(message);
            log.info("LMPQ line: session={} key={} speaker={} text={}", session.sessionId(), key, speaker.getName(),
                    text);
            return AgentPartyGatewayRuntime.party().sendPartyChat(speaker, text);
        } catch (RuntimeException failure) {
            log.warn("LMPQ line failed: session={} key={}", session.sessionId(), key, failure);
            return false;
        }
    }

    /** Says the line once per run: false when the key was already used, guidance is off, or there is no line. */
    static boolean narrate(AgentLmpqSession session, String key, Character speaker, String message) {
        if (session == null || speaker == null || message == null || !session.memberCoordinationChatEnabled()
                || !session.claimNote("line-" + key)) {
            return false;
        }
        return say(session, key, speaker, message);
    }
}
