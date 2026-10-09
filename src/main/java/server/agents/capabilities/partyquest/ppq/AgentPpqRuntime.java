package server.agents.capabilities.partyquest.ppq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.runtime.AgentRuntimeEntry;

/** PPQ facade over independent member work and one short coordinator lease. */
public final class AgentPpqRuntime {
    private static final Logger log = LoggerFactory.getLogger(AgentPpqRuntime.class);
    private static final long COORDINATOR_LEASE_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.ppq.AgentPpqRuntime.COORDINATOR_LEASE_MS");
    private AgentPpqRuntime() { }
    public static boolean tick(AgentRuntimeEntry entry, Character agent, long nowMs) {
        AgentPpqSession session = AgentPpqSessionRegistry.forMember(agent.getId());
        if (session == null) {
            AgentPpqLobbyAdmissionRuntime.tick(agent.getId(), nowMs);
            return AgentPartyQuestLifecycleRuntime.tick(agent.getId(), nowMs);
        }
        if (session.paused()) return true;
        AgentPpqCoordinator.tickMember(session, entry, agent, nowMs);
        if (session.claimExecutionTick(agent.getId(), nowMs, COORDINATOR_LEASE_MS)) {
            AgentPpqCoordinator.tickSession(session, nowMs);
        }
        if (session.terminal()) AgentPpqTerminationService.release(
                session, session.failure(), nowMs, session.phase() == AgentPpqSession.Phase.FAILED);
        return true;
    }
    public static boolean requestStop(int id, String reason, long nowMs) {
        AgentPpqSession session = AgentPpqSessionRegistry.forMember(id);
        if (session == null) {
            AgentPpqLobbyAdmissionRuntime.releaseTracking(id);
            return AgentPartyQuestLifecycleRuntime.requestStop(id, reason, nowMs);
        }
        if (!session.terminal()) return false;
        AgentPpqTerminationService.release(session, reason, nowMs, false); return true;
    }
    public static void forceStop(int id, String reason, long nowMs) {
        AgentPpqSession session = AgentPpqSessionRegistry.forMember(id);
        if (session == null) {
            AgentPpqLobbyAdmissionRuntime.releaseTracking(id);
            AgentPartyQuestLifecycleRuntime.forceStop(id, reason, nowMs);
        } else AgentPpqTerminationService.release(session, reason, nowMs, true);
    }
    /**
     * One Agent's runtime went away (a logout, a cleanup): it leaves the run, and the run goes on without it. The
     * whole run is released only when that Agent was the event leader or the last Agent in it; forceStop stays the
     * whole-run release.
     */
    public static void runtimeRemoved(int id, long nowMs) {
        AgentPpqSession session = AgentPpqSessionRegistry.forMember(id);
        if (session == null || session.terminal() || session.terminationStarted() || !session.departAgent(id)) {
            forceStop(id, "Agent runtime removed", nowMs);
            return;
        }
        AgentPpqSessionRegistry.unindexMember(session, id);
        AgentPartyQuestEngagement engagement = AgentPartyQuestEngagementRegistry.forMember(id);
        if (engagement != null) AgentPartyQuestEngagementRegistry.removeAndUnindexMember(engagement, id, nowMs);
        log.info("PPQ member left: session={} member={} reason=runtime-removed members={} companionRun={}",
                session.sessionId(), id, session.memberCount(), session.companionAdopted());
    }
}
