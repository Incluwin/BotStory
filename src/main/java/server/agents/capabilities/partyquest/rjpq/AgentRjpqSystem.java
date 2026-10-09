package server.agents.capabilities.partyquest.rjpq;

import client.Character;
import server.agents.capabilities.partyquest.AgentPartyQuestDefinition;
import server.agents.capabilities.partyquest.AgentPartyQuestSessionView;
import server.agents.capabilities.partyquest.AgentPartyQuestSystem;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.activity.session.AgentActivityAdmissionResult;

/**
 * The party-quest router's view of one RJPQ side. Both sides share one session registry, so every answer is filtered
 * on the session's own side: an Alcadno run never answers for the Zenumist system. There is no lobby queue (every RJPQ
 * run is a companion run made by the public admit), so entry requests are refused.
 */
public final class AgentRjpqSystem implements AgentPartyQuestSystem {
    static final String NO_QUEUE = "RJPQ runs only as a companion run";

    private final AgentRjpqSide side;

    public AgentRjpqSystem(AgentRjpqSide side) {
        if (side == null) {
            throw new IllegalArgumentException("an RJPQ side is required");
        }
        this.side = side;
    }

    private AgentRjpqSession session(int characterId) {
        AgentRjpqSession session = AgentRjpqSessionRegistry.forMember(characterId);
        return session != null && session.side() == side ? session : null;
    }

    @Override
    public AgentPartyQuestDefinition definition() {
        return side.definition();
    }

    @Override
    public boolean sessionActive(int characterId) {
        return session(characterId) != null;
    }

    @Override
    public boolean companionRun(int characterId) {
        AgentRjpqSession session = session(characterId);
        return session != null && session.companionAdopted();
    }

    @Override
    public boolean tick(AgentRuntimeEntry entry, Character agent, long nowMs) {
        return AgentRjpqRuntime.tick(entry, agent, nowMs);
    }

    @Override
    public boolean requestStop(int characterId, String reason, long nowMs) {
        return AgentRjpqRuntime.requestStop(characterId, reason, nowMs);
    }

    @Override
    public void forceStop(int characterId, String reason, long nowMs) {
        AgentRjpqRuntime.forceStop(characterId, reason, nowMs);
    }

    @Override
    public void runtimeRemoved(int characterId, long nowMs) {
        AgentRjpqRuntime.runtimeRemoved(characterId, nowMs);
    }

    @Override
    public AgentPartyQuestSessionView sessionView(int characterId) {
        AgentRjpqSession session = session(characterId);
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
        return new AgentPartyQuestSessionView(side.key(), session.sessionId(), phase,
                session.executionAgentId(), session.memberCount(), "COMPANION",
                session.failure(), session.startedAtMs(), session.lastProgressAtMs());
    }

    @Override
    public boolean pause(int characterId) {
        AgentRjpqSession session = session(characterId);
        if (session == null || session.paused()) {
            return false;
        }
        session.setPaused(true);
        return true;
    }

    @Override
    public boolean resumeExact(int characterId, String sessionId, long nowMs) {
        AgentRjpqSession session = session(characterId);
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
