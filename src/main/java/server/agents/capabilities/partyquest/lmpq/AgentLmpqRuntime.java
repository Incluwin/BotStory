package server.agents.capabilities.partyquest.lmpq;

import client.Character;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;

/** LMPQ facade over independent member work and one short coordinator lease. */
public final class AgentLmpqRuntime {
    private static final long COORDINATOR_LEASE_MS = 3_000L;
    private AgentLmpqRuntime() { }

    public static boolean tick(AgentRuntimeEntry entry, Character agent, long nowMs) {
        AgentLmpqSession session = AgentLmpqSessionRegistry.forMember(agent.getId());
        if (session == null) {
            AgentLmpqLobbyAdmissionRuntime.tick(agent.getId(), nowMs);
            return AgentPartyQuestLifecycleRuntime.tick(agent.getId(), nowMs);
        }
        if (session.paused()) return true;
        AgentLmpqCoordinator.tickMember(session, entry, agent, nowMs);
        if (session.claimExecutionTick(agent.getId(), nowMs, COORDINATOR_LEASE_MS, AgentLmpqRuntime::holderAlive)) {
            AgentLmpqCoordinator.tickSession(session, nowMs);
        }
        if (session.terminal()) AgentLmpqTerminationService.finish(
                session, session.failure(), nowMs, session.phase() == AgentLmpqSession.Phase.FAILED);
        return true;
    }

    /**
     * Whether an LMPQ coordinator lease holder can still drive the run: its Agent is loaded and has HP left (the
     * watchdog's own live test). A dead or unloaded holder hands the lease over at once.
     */
    static boolean holderAlive(int characterId) {
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(characterId);
        Character holder = entry == null ? null : AgentRuntimeIdentityRuntime.bot(entry);
        return holder != null && holder.getHp() > 0;
    }

    /** The coordinator lease an Agent's tick takes, for the watchdog's claim. */
    static long coordinatorLeaseMs() {
        return COORDINATOR_LEASE_MS;
    }

    public static boolean requestStop(int id, String reason, long nowMs) {
        AgentLmpqSession session = AgentLmpqSessionRegistry.forMember(id);
        if (session == null) {
            AgentLmpqLobbyAdmissionRuntime.releaseTracking(id);
            return AgentPartyQuestLifecycleRuntime.requestStop(id, reason, nowMs);
        }
        if (!session.terminal()) return false;
        AgentLmpqTerminationService.finish(session, reason, nowMs, false);
        return true;
    }

    public static void forceStop(int id, String reason, long nowMs) {
        AgentLmpqSession session = AgentLmpqSessionRegistry.forMember(id);
        if (session == null) {
            AgentLmpqLobbyAdmissionRuntime.releaseTracking(id);
            AgentPartyQuestLifecycleRuntime.forceStop(id, reason, nowMs);
        } else AgentLmpqTerminationService.finish(session, reason, nowMs, true);
    }

    public static void runtimeRemoved(int id, long nowMs) {
        forceStop(id, "Agent runtime removed", nowMs);
    }
}
