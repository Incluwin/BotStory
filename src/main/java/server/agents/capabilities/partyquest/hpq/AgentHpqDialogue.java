package server.agents.capabilities.partyquest.hpq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.dialogue.AgentChatTextSanitizer;
import server.agents.integration.AgentPacketGatewayRuntime;

/**
 * A companion run's HPQ chat: sanitized map chat (party chat leaves out the speaker and anyone watching from outside the
 * party), and every line logged at info as 'HPQ line:' so a run's wording reads straight from the log.
 */
final class AgentHpqDialogue {
    private static final Logger log = LoggerFactory.getLogger(AgentHpqDialogue.class);

    private AgentHpqDialogue() {
    }

    static void sayMapNow(String sessionId, String key, Character speaker, String message) {
        if (speaker == null || message == null) return;
        String text = AgentChatTextSanitizer.sanitize(message);
        log.info("HPQ line: session={} key={} speaker={} text={}", sessionId, key, speaker.getName(), text);
        AgentPacketGatewayRuntime.packets().broadcastChatText(speaker, text, false, 0);
    }
}
