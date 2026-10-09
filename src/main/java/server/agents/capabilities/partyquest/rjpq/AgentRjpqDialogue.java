package server.agents.capabilities.partyquest.rjpq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.dialogue.AgentChatTextSanitizer;
import server.agents.integration.AgentPacketGatewayRuntime;
import server.agents.integration.AgentPartyGatewayRuntime;

/**
 * An RJPQ run's chat: a companion says its line as sanitized map chat, as a player would (party chat only for the
 * leader's Lab Unit row, which he cannot hear from his own room), and every line is logged at info as 'RJPQ line:' with
 * its key, so a run's wording reads straight from the log. Companions speak only while the run's PQ guidance is on
 * (COMPANION_PQ_GUIDANCE, through the session's coordination chat switch).
 */
final class AgentRjpqDialogue {
    private static final Logger log = LoggerFactory.getLogger(AgentRjpqDialogue.class);

    private AgentRjpqDialogue() {
    }

    static void sayMapNow(AgentRjpqSession session, String key, Character speaker, String message) {
        if (session == null || speaker == null || message == null) {
            return;
        }
        String text = AgentChatTextSanitizer.sanitize(message);
        log.info("RJPQ line: session={} side={} key={} speaker={} text={}", session.sessionId(), session.side().key(),
                key, speaker.getName(), text);
        AgentPacketGatewayRuntime.packets().broadcastChatText(speaker, text, false, 0);
    }

    static void sayPartyNow(AgentRjpqSession session, String key, Character speaker, String message) {
        if (session == null || speaker == null || message == null) {
            return;
        }
        String text = AgentChatTextSanitizer.sanitize(message);
        log.info("RJPQ line: session={} side={} key={} speaker={} channel=party text={}", session.sessionId(),
                session.side().key(), key, speaker.getName(), text);
        AgentPartyGatewayRuntime.party().sendPartyChat(speaker, text);
    }

    /** Says the line now in map chat while the run's guidance is on; false when it is off or nothing is to be said. */
    static boolean say(AgentRjpqSession session, String key, Character speaker, String message) {
        if (session == null || speaker == null || message == null || !session.memberCoordinationChatEnabled()) {
            return false;
        }
        sayMapNow(session, key, speaker, message);
        return true;
    }

    /** Says the line once per run: false when the key was already used, guidance is off, or there is no line. */
    static boolean narrate(AgentRjpqSession session, String key, Character speaker, String message) {
        if (session == null || speaker == null || message == null || !session.memberCoordinationChatEnabled()
                || !session.narrateOnce(key)) {
            return false;
        }
        sayMapNow(session, key, speaker, message);
        return true;
    }

    /** {@link #narrate} in party chat. */
    static boolean narrateParty(AgentRjpqSession session, String key, Character speaker, String message) {
        if (session == null || speaker == null || message == null || !session.memberCoordinationChatEnabled()
                || !session.narrateOnce(key)) {
            return false;
        }
        sayPartyNow(session, key, speaker, message);
        return true;
    }
}
