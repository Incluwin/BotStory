package server.agents.capabilities.partyquest.apq;

import client.Character;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.runtime.AgentRuntimeEntry;

/** APQ facade over independent member work and one short coordinator lease. */
public final class AgentApqRuntime {
    private static final long COORDINATOR_LEASE_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.apq.AgentApqRuntime.COORDINATOR_LEASE_MS");

    private AgentApqRuntime() {
    }

    public static boolean tick(AgentRuntimeEntry entry, Character agent, long nowMs) {
        AgentApqSession session = AgentApqSessionRegistry.forMember(agent.getId());
        if (session == null) {
            return AgentPartyQuestLifecycleRuntime.tick(agent.getId(), nowMs);
        }
        if (session.paused()) return true;
        AgentApqCoordinator.tickMember(session, entry, agent, nowMs);
        if (session.claimExecutionTick(agent.getId(), nowMs, COORDINATOR_LEASE_MS)) {
            AgentApqCoordinator.tickSession(session, nowMs);
        }
        if (session.terminal()) AgentApqTerminationService.release(session, session.failure(), nowMs);
        return true;
    }

    public static boolean requestStop(int id, String reason, long nowMs) {
        AgentApqSession session = AgentApqSessionRegistry.forMember(id);
        if (session == null) return AgentPartyQuestLifecycleRuntime.requestStop(id, reason, nowMs);
        if (!session.terminal()) return false;
        AgentApqTerminationService.release(session, reason, nowMs);
        return true;
    }

    public static void forceStop(int id, String reason, long nowMs) {
        AgentApqSession session = AgentApqSessionRegistry.forMember(id);
        if (session == null) AgentPartyQuestLifecycleRuntime.forceStop(id, reason, nowMs);
        else AgentApqTerminationService.release(session, reason, nowMs);
    }

    /**
     * One Agent's runtime went away (a logout, a cleanup): AmoriaPQ.js ends the event when a member disconnects below
     * six, so the whole run is released, in its companions' words when one is left to say it.
     */
    public static void runtimeRemoved(int id, long nowMs) {
        AgentApqSession session = AgentApqSessionRegistry.forMember(id);
        if (session != null && !session.terminal() && !session.terminationStarted()) {
            AgentApqCompanionRun.fail(session, "APQ member " + id + " runtime removed", AgentApqParting.Kind.LEFT,
                    null, nowMs);
        }
        forceStop(id, "Agent runtime removed", nowMs);
    }
}
