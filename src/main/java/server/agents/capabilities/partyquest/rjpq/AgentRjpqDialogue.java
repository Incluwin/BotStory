package server.agents.capabilities.partyquest.rjpq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.dialogue.AgentChatTextSanitizer;
import server.agents.integration.AgentPacketGatewayRuntime;

/**
 * An RJPQ run's chat: a companion says its line as sanitized map chat, as a player would, and every line is logged at
 * info as 'RJPQ line:' with its key, so a run's wording reads straight from the log.
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

    /** Says the line once per run: false when the key was already used, or there is no speaker or line. */
    static boolean narrate(AgentRjpqSession session, String key, Character speaker, String message) {
        if (session == null || speaker == null || message == null || !session.narrateOnce(key)) {
            return false;
        }
        sayMapNow(session, key, speaker, message);
        return true;
    }
}
