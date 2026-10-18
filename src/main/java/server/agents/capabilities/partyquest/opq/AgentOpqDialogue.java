package server.agents.capabilities.partyquest.opq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.dialogue.AgentChatTextSanitizer;
import server.agents.integration.AgentPacketGatewayRuntime;
import server.agents.integration.AgentPartyGatewayRuntime;

/**
 * 2026-10-18: a human-led OPQ run's chat. A companion says its line in map chat when the leader stands on its map and
 * in party chat when he is in another room (decision O4 of the OPQ plan: he cannot hear map chat from there), as a
 * player would; every line is logged at info as 'OPQ line:' with its key and channel. Companions speak only while the
 * run's PQ guidance is on (COMPANION_PQ_GUIDANCE, through the session's coordination chat switch). Never throws.
 */
final class AgentOpqDialogue {
    private static final Logger log = LoggerFactory.getLogger(AgentOpqDialogue.class);

    private AgentOpqDialogue() {
    }

    /** Says the line now while the run's guidance is on; false when it is off or nothing is to be said. */
    static boolean say(AgentOpqSession session, String key, Character speaker, Character leader, String message) {
        if (session == null || speaker == null || message == null || !session.memberCoordinationChatEnabled()) {
            return false;
        }
        boolean party = leader != null && leader.getMapId() != speaker.getMapId();
        try {
            String text = AgentChatTextSanitizer.sanitize(message);
            log.info("OPQ line: session={} key={} speaker={} channel={} text={}", session.sessionId(), key,
                    speaker.getName(), party ? "party" : "map", text);
            if (party) AgentPartyGatewayRuntime.party().sendPartyChat(speaker, text);
            else AgentPacketGatewayRuntime.packets().broadcastChatText(speaker, text, false, 0);
            return true;
        } catch (RuntimeException failure) {
            log.warn("OPQ line failed: session={} key={}", session.sessionId(), key, failure);
            return false;
        }
    }

    /** Says the line once per run: false when the key was already used, guidance is off, or there is no line. */
    static boolean narrate(AgentOpqSession session, String key, Character speaker, Character leader, String message) {
        if (session == null || speaker == null || message == null || !session.memberCoordinationChatEnabled()
                || !session.narrateOnce(key)) {
            return false;
        }
        return say(session, key, speaker, leader, message);
    }
}
