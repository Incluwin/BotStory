package server.agents.capabilities.partyquest.epq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * EPQ's run lines in the log: every line the coordinator says is logged at info as 'EPQ line:' with its key, its
 * speaker and its exact text, so a run's wording reads straight from the log.
 */
final class AgentEpqDialogue {
    private static final Logger log = LoggerFactory.getLogger(AgentEpqDialogue.class);

    private AgentEpqDialogue() {
    }

    static void logLine(String sessionId, String key, Character speaker, String text) {
        log.info("EPQ line: session={} key={} speaker={} text={}", sessionId, key,
                speaker == null ? "" : speaker.getName(), text);
    }
}
