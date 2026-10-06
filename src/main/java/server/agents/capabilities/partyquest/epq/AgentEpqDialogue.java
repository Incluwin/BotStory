package server.agents.capabilities.partyquest.epq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.dialogue.AgentChatTextSanitizer;
import server.agents.integration.AgentPacketGatewayRuntime;

/**
 * EPQ's run lines in the log: every line the coordinator says is logged at info as 'EPQ line:' with its key, its
 * speaker and its exact text, so a run's wording reads straight from the log. A companion run's lines go out as
 * sanitized map chat only (party chat leaves out anyone watching from outside the party), as HPQ's do.
 */
final class AgentEpqDialogue {
    private static final Logger log = LoggerFactory.getLogger(AgentEpqDialogue.class);

    private AgentEpqDialogue() {
    }

    static void logLine(String sessionId, String key, Character speaker, String text) {
        log.info("EPQ line: session={} key={} speaker={} text={}", sessionId, key,
                speaker == null ? "" : speaker.getName(), text);
    }

    /** A companion run's line: sanitized, logged as 'EPQ line:', and said in map chat by its speaker; never party chat. */
    static void sayMapNow(String sessionId, String key, Character speaker, String message) {
        if (speaker == null || message == null) return;
        String text = AgentChatTextSanitizer.sanitize(message);
        logLine(sessionId, key, speaker, text);
        AgentPacketGatewayRuntime.packets().broadcastChatText(speaker, text, false, 0);
    }
}
