package server.agents.capabilities.partyquest.lpq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.dialogue.AgentChatTextSanitizer;
import server.agents.integration.AgentPacketGatewayRuntime;

/**
 * A companion run's LPQ chat: sanitized map chat (party chat leaves out the speaker and anyone watching from outside the
 * party), and every line logged at info as 'LPQ line:' so a run's wording reads straight from the log.
 */
final class AgentLpqDialogue {
    private static final Logger log = LoggerFactory.getLogger(AgentLpqDialogue.class);

    private AgentLpqDialogue() {
    }

    static void sayMapNow(String sessionId, String key, Character speaker, String message) {
        if (speaker == null || message == null) return;
        String text = AgentChatTextSanitizer.sanitize(message);
        log.info("LPQ line: session={} key={} speaker={} text={}", sessionId, key, speaker.getName(), text);
        AgentPacketGatewayRuntime.packets().broadcastChatText(speaker, text, false, 0);
    }
}
