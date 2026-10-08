package server.agents.capabilities.partyquest.lpq;

import client.Character;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;

/** Activity facade for the isolated LPQ aggregate. */
public final class AgentLpqRuntime {
    private static final long LEASE_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.lpq.AgentLpqRuntime.COORDINATOR_LEASE_MS");
    private AgentLpqRuntime() { }

    /**
     * True while the member's companion LPQ run says itself that it ran dry on stage 7 (S7d, with the members'
     * coordination chat on: it held a ratz lane there, or nobody loaded can reach the live ratz), so its generic in-run
     * line stays quiet (AgentPartyQuestSystem.ownsDryLine); false again once its S7d could not be said. Memory only.
     */
    public static boolean ownsDryLine(int characterId, int mapId) {
        return AgentLpqCoordinator.ownsDryLine(characterId, mapId);
    }

    public static boolean tick(AgentRuntimeEntry entry, Character agent, long nowMs) {
        AgentLpqSession session = AgentLpqSessionRegistry.forMember(agent.getId());
        if (session == null) {
            AgentLpqLobbyAdmissionRuntime.tick(agent.getId(), nowMs);
            return AgentPartyQuestLifecycleRuntime.tick(agent.getId(), nowMs);
        }
        if (session.paused()) return true;
        // A completed split-room occupant owns a local portal traversal, not a
        // party-wide decision. Advance it from that Agent's own runtime tick so
        // the route cannot stop when the last combat owner releases the central
        // coordinator lease.
        if (AgentLpqCoordinator.tickStageFourCompletedRoomExit(
                session, entry, agent, nowMs)) {
            return true;
        }
        if (AgentLpqCoordinator.tickStageFiveRoomContinuation(
                session, entry, agent, nowMs)) {
            return true;
        }
        if (!session.claimExecutionTick(agent.getId(), nowMs, LEASE_MS, AgentLpqRuntime::holderAlive)) return true;
        AgentLpqCoordinator.tick(session, nowMs);
        return true;
    }

    /**
     * Whether an LPQ coordinator lease holder can still drive the party: its Agent is loaded and has HP left (the
     * watchdog's own live test). A dead or unloaded holder hands the lease over at once.
     */
    static boolean holderAlive(int characterId) {
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(characterId);
        Character holder = entry == null ? null : AgentRuntimeIdentityRuntime.bot(entry);
        return holder != null && holder.getHp() > 0;
    }

    public static boolean requestStop(int id, String reason, long nowMs) {
        AgentLpqSession session = AgentLpqSessionRegistry.forMember(id);
        if (session == null) {
            AgentLpqLobbyAdmissionRuntime.releaseTracking(id);
            return AgentPartyQuestLifecycleRuntime.requestStop(id, reason, nowMs);
        }
        if (!session.terminal()) return false;
        if (session.phase() == AgentLpqSession.Phase.COMPLETED) AgentLpqTerminationService.complete(session, nowMs);
        else AgentLpqTerminationService.fail(session, reason, nowMs);
        return true;
    }

    public static void forceStop(int id, String reason, long nowMs) {
        AgentLpqSession session = AgentLpqSessionRegistry.forMember(id);
        if (session == null) {
            AgentLpqLobbyAdmissionRuntime.releaseTracking(id);
            AgentPartyQuestLifecycleRuntime.forceStop(id, reason, nowMs);
        }
        else AgentLpqTerminationService.fail(session, reason, nowMs);
    }

    public static void runtimeRemoved(int id, long nowMs) { forceStop(id, "Agent runtime removed", nowMs); }
}
