package server.agents.capabilities.partyquest.epq;

import client.Character;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.runtime.AgentRuntimeEntry;

/** EPQ facade: independent member work plus one short party coordination lease. */
public final class AgentEpqRuntime {
    private static final long COORDINATOR_LEASE_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.epq.AgentEpqRuntime.COORDINATOR_LEASE_MS");
    private AgentEpqRuntime() { }

    public static boolean tick(AgentRuntimeEntry entry, Character agent, long nowMs) {
        AgentEpqSession session = AgentEpqSessionRegistry.forMember(agent.getId());
        if (session == null) {
            AgentEpqLobbyAdmissionRuntime.tick(agent.getId(), nowMs);
            return AgentPartyQuestLifecycleRuntime.tick(agent.getId(), nowMs);
        }
        if (session.paused()) return true;
        AgentEpqCoordinator.tickMember(session, entry, agent, nowMs);
        if (session.claimExecutionTick(agent.getId(), nowMs, COORDINATOR_LEASE_MS)) {
            AgentEpqCoordinator.tickSession(session, nowMs);
        }
        if (session.terminal()) AgentEpqTerminationService.release(
                session, session.failure(), nowMs,
                session.phase() == AgentEpqSession.Phase.FAILED);
        return true;
    }

    /**
     * True while the run owns this member's moves and words on the golem's map: while it rests from the golem
     * (AgentEpqCoordinator.bossRest, its fight from the rest point included), and in a companion run past the clear
     * (AgentEpqCompanionRun.afterClearMember: the add fight, the marble errand or a stand), and on stage one, whose
     * grind the run gives every member again on every pass (AgentEpqCoordinator.stageOne): the generic stop could not
     * hold, and its "walking to you" was said by a member that went on grinding. The generic out-of-potions grind stop
     * then stays quiet. Memory only.
     */
    public static boolean ownsPotionRest(int id, int mapId) {
        if (mapId != AgentEpqDefinition.BOSS_MAP && mapId != AgentEpqDefinition.STAGE_ONE_MAP) return false;
        AgentEpqSession session = AgentEpqSessionRegistry.forMember(id);
        AgentEpqMemberState member = session == null ? null : session.member(id);
        if (member == null) return false;
        if (mapId == AgentEpqDefinition.STAGE_ONE_MAP) return true;
        return member.bossResting()
                || (session.companionAdopted() && AgentEpqTerminationService.pastTheClear(session.eventInstance()));
    }

    public static boolean requestStop(int id, String reason, long nowMs) {
        AgentEpqSession session = AgentEpqSessionRegistry.forMember(id);
        if (session == null) {
            AgentEpqLobbyAdmissionRuntime.releaseTracking(id);
            return AgentPartyQuestLifecycleRuntime.requestStop(id, reason, nowMs);
        }
        if (!session.terminal()) return false;
        AgentEpqTerminationService.release(session, reason, nowMs, false);
        return true;
    }

    public static void forceStop(int id, String reason, long nowMs) {
        AgentEpqSession session = AgentEpqSessionRegistry.forMember(id);
        if (session == null) {
            AgentEpqLobbyAdmissionRuntime.releaseTracking(id);
            AgentPartyQuestLifecycleRuntime.forceStop(id, reason, nowMs);
        }
        else AgentEpqTerminationService.release(session, reason, nowMs, true);
    }

    public static void runtimeRemoved(int id, long nowMs) {
        forceStop(id, "Agent runtime removed", nowMs);
    }
}
