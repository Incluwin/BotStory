package server.agents.capabilities.partyquest.rjpq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.runtime.AgentRuntimeEntry;

/** RJPQ facade: each companion's own work plus one short coordination lease for the run. */
public final class AgentRjpqRuntime {
    private static final Logger log = LoggerFactory.getLogger(AgentRjpqRuntime.class);
    private static final long COORDINATOR_LEASE_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqRuntime.COORDINATOR_LEASE_MS");

    private AgentRjpqRuntime() {
    }

    public static boolean tick(AgentRuntimeEntry entry, Character agent, long nowMs) {
        AgentRjpqSession session = AgentRjpqSessionRegistry.forMember(agent.getId());
        if (session == null) {
            return AgentPartyQuestLifecycleRuntime.tick(agent.getId(), nowMs);
        }
        if (session.paused()) {
            return true;
        }
        AgentRjpqCoordinator.tickMember(session, entry, agent, nowMs);
        if (session.claimExecutionTick(agent.getId(), nowMs, COORDINATOR_LEASE_MS)) {
            AgentRjpqCoordinator.tickSession(session, nowMs);
        }
        if (session.terminal()) {
            AgentRjpqTerminationService.release(session, session.failure(), nowMs,
                    session.phase() == AgentRjpqSession.Phase.FAILED);
        }
        return true;
    }

    public static boolean requestStop(int id, String reason, long nowMs) {
        AgentRjpqSession session = AgentRjpqSessionRegistry.forMember(id);
        if (session == null) {
            return AgentPartyQuestLifecycleRuntime.requestStop(id, reason, nowMs);
        }
        if (!session.terminal()) {
            return false;
        }
        AgentRjpqTerminationService.release(session, reason, nowMs, false);
        return true;
    }

    /** Stops the member's whole run: the host stops a run by force-stopping its first companion. */
    public static void forceStop(int id, String reason, long nowMs) {
        AgentRjpqSession session = AgentRjpqSessionRegistry.forMember(id);
        if (session == null) {
            AgentPartyQuestLifecycleRuntime.forceStop(id, reason, nowMs);
        } else {
            AgentRjpqTerminationService.release(session, reason, nowMs, true);
        }
    }

    /**
     * One companion's runtime is gone (it logged out or was unloaded): it leaves the run, not the whole instance. The
     * run ends only when it was the event leader or no companion is left; the stock event decides for itself what a
     * leaver means for it.
     */
    public static void runtimeRemoved(int id, long nowMs) {
        AgentRjpqSession session = AgentRjpqSessionRegistry.forMember(id);
        if (session == null) {
            AgentPartyQuestLifecycleRuntime.runtimeRemoved(id, "Agent runtime removed", nowMs);
            return;
        }
        boolean lastCompanion = session.members().stream()
                .filter(AgentRjpqMemberState::agent)
                .allMatch(member -> member.characterId() == id);
        if (id == session.eventLeaderId() || lastCompanion) {
            forceStop(id, "Agent runtime removed", nowMs);
            return;
        }
        session.releaseHold(id, nowMs);
        session.removeMember(id);
        AgentRjpqSessionRegistry.unindexMember(session, id);
        AgentPartyQuestEngagementRegistry.removeAndUnindexMember(
                AgentPartyQuestEngagementRegistry.forMember(id), id, nowMs);
        log.info("RJPQ departed: session={} side={} member={} reason=runtime-removed", session.sessionId(),
                session.side().key(), id);
    }
}
