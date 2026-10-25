package server.agents.capabilities.partyquest.apq;

import client.Character;
import server.agents.capabilities.partyquest.AgentPartyQuestCatalog;
import server.agents.capabilities.partyquest.AgentPartyQuestDefinition;
import server.agents.capabilities.partyquest.AgentPartyQuestSessionView;
import server.agents.capabilities.partyquest.AgentPartyQuestSystem;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.activity.session.AgentActivityAdmissionResult;

/**
 * The party-quest router's view of APQ. There is no lobby queue (every APQ run is a companion run made by the desk's
 * admit for a human leader), so entry requests are refused, as RJPQ's are.
 */
public final class AgentApqSystem implements AgentPartyQuestSystem {
    static final String QUEST_KEY = "apq";
    static final String NO_QUEUE = "APQ runs only as a companion run";

    @Override
    public AgentPartyQuestDefinition definition() {
        return AgentPartyQuestCatalog.require(QUEST_KEY);
    }

    @Override
    public boolean sessionActive(int characterId) {
        return AgentApqSessionRegistry.active(characterId);
    }

    @Override
    public boolean companionRun(int characterId) {
        AgentApqSession session = AgentApqSessionRegistry.forMember(characterId);
        return session != null && session.companionAdopted();
    }

    @Override
    public boolean tick(AgentRuntimeEntry entry, Character agent, long nowMs) {
        return AgentApqRuntime.tick(entry, agent, nowMs);
    }

    @Override
    public boolean requestStop(int characterId, String reason, long nowMs) {
        return AgentApqRuntime.requestStop(characterId, reason, nowMs);
    }

    @Override
    public void forceStop(int characterId, String reason, long nowMs) {
        AgentApqRuntime.forceStop(characterId, reason, nowMs);
    }

    @Override
    public void runtimeRemoved(int characterId, long nowMs) {
        AgentApqRuntime.runtimeRemoved(characterId, nowMs);
    }

    @Override
    public AgentPartyQuestSessionView sessionView(int characterId) {
        AgentApqSession session = AgentApqSessionRegistry.forMember(characterId);
        if (session == null) {
            return null;
        }
        AgentPartyQuestSessionView.Phase phase = switch (session.phase()) {
            case EXITING -> AgentPartyQuestSessionView.Phase.DRAINING;
            case COMPLETED -> AgentPartyQuestSessionView.Phase.COMPLETED;
            case FAILED -> AgentPartyQuestSessionView.Phase.FAILED;
            default -> session.paused() ? AgentPartyQuestSessionView.Phase.SUSPENDED
                    : AgentPartyQuestSessionView.Phase.ACTIVE;
        };
        return new AgentPartyQuestSessionView(QUEST_KEY, session.sessionId(), phase,
                session.executionAgentId(), session.memberCount(), "COMPANION",
                session.failure(), session.startedAtMs(), session.lastProgressAtMs());
    }

    @Override
    public boolean pause(int characterId) {
        AgentApqSession session = AgentApqSessionRegistry.forMember(characterId);
        if (session == null || session.paused()) {
            return false;
        }
        session.setPaused(true);
        return true;
    }

    @Override
    public boolean resumeExact(int characterId, String sessionId, long nowMs) {
        AgentApqSession session = AgentApqSessionRegistry.forMember(characterId);
        if (session == null || !session.sessionId().equals(sessionId) || !session.paused()) {
            return false;
        }
        session.setPaused(false);
        session.markProgress(nowMs);
        return true;
    }

    @Override
    public AgentActivityAdmissionResult requestEntry(AgentRuntimeEntry entry, Character agent, String scenarioId,
                                                     int partySize, int maximumRuns, long nowMs) {
        return AgentActivityAdmissionResult.rejected(NO_QUEUE);
    }

    @Override
    public String entryBlocker(Character agent, String scenarioId, int partySize, int maximumRuns) {
        return NO_QUEUE;
    }
}
