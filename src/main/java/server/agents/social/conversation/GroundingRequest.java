package server.agents.social.conversation;

import java.util.Map;

/**
 * What a grounding provider sees for one dialogue turn: the answering Agent, who spoke and what (punctuation
 * intact), the generic intent, the map the Agent is on (0 when unknown), and the operational facts already
 * projected for it ({@code world.mapId}, {@code progression.level}, {@code progression.jobId}, ...).
 */
public record GroundingRequest(int agentCharacterId, int speakerCharacterId, String speakerName, String utterance,
                               String intentKey, int mapId, Map<String, String> operationalFacts) {
}
