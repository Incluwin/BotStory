package server.agents.capabilities.partyquest.opq;

import client.Character;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;

/** OPQ facade: central coordination lease plus independent per-member room work. */
public final class AgentOpqRuntime {
    private static final long COORDINATOR_LEASE_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.opq.AgentOpqRuntime.COORDINATOR_LEASE_MS");
    private AgentOpqRuntime() { }

    public static boolean tick(AgentRuntimeEntry entry, Character agent, long nowMs) {
        AgentOpqSession session = AgentOpqSessionRegistry.forMember(agent.getId());
        if (session == null) {
            AgentOpqLobbyAdmissionRuntime.tick(agent.getId(), nowMs);
            return AgentPartyQuestLifecycleRuntime.tick(agent.getId(), nowMs);
        }
        if (session.paused()) return true;
        AgentOpqCoordinator.tickMember(session, entry, agent, nowMs);
        if (session.claimExecutionTick(agent.getId(), nowMs, COORDINATOR_LEASE_MS, AgentOpqRuntime::holderAlive)) {
            AgentOpqCoordinator.tickSession(session, nowMs);
        }
        return true;
    }

    /**
     * Whether an OPQ coordinator lease holder can still drive the party: its Agent is loaded and has HP left (the
     * watchdog's own live test). A dead or unloaded holder hands the lease over at once.
     */
    static boolean holderAlive(int characterId) {
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(characterId);
        Character holder = entry == null ? null : AgentRuntimeIdentityRuntime.bot(entry);
        return holder != null && holder.getHp() > 0;
    }

    /** The coordinator lease every claim takes, the watchdog's included. */
    static long coordinatorLeaseMs() {
        return COORDINATOR_LEASE_MS;
    }

    public static boolean requestStop(int id, String reason, long nowMs) {
        AgentOpqSession session = AgentOpqSessionRegistry.forMember(id);
        if (session == null) {
            AgentOpqLobbyAdmissionRuntime.releaseTracking(id);
            return AgentPartyQuestLifecycleRuntime.requestStop(id, reason, nowMs);
        }
        if (!session.terminal()) return false;
        // A companion run that did not complete is ended as a companion run (its party kept; after the clear only
        // its companions leave); a completed run, and every stock run, are released as they always were.
        if (session.companionAdopted() && session.phase() != AgentOpqSession.Phase.COMPLETED) {
            AgentOpqTerminationService.fail(session, reason, nowMs);
        } else {
            AgentOpqTerminationService.release(session, reason, nowMs, false);
        }
        return true;
    }

    public static void forceStop(int id, String reason, long nowMs) {
        AgentOpqSession session = AgentOpqSessionRegistry.forMember(id);
        if (session == null) {
            AgentOpqLobbyAdmissionRuntime.releaseTracking(id);
            AgentPartyQuestLifecycleRuntime.forceStop(id, reason, nowMs);
        }
        else AgentOpqTerminationService.fail(session, reason, nowMs);
    }

    public static void runtimeRemoved(int id, long nowMs) {
        forceStop(id, "Agent runtime removed", nowMs);
    }
}
