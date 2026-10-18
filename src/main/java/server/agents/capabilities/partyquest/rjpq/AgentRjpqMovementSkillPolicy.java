package server.agents.capabilities.partyquest.rjpq;

import client.Character;

/**
 * RJPQ-owned exception for Dark Lab 2 (926100202 / 926110202, 2026-10-17): its boxes sit in walled cells, 78 px wall
 * pairs between 192 px cells and 135-145 px between floors, that walking and jumping never reach; players cross them
 * with Teleport. The lab's graph gets Teleport edges, and only the run's Lab 2 runner routes over them, as LPQ's
 * Stage 5 Teleport runner does.
 */
public final class AgentRjpqMovementSkillPolicy {
    private static final boolean ENABLE_TELEPORT_NAVIGATION = config.AgentTuning.booleanValue(
            "server.agents.capabilities.partyquest.rjpq.AgentRjpqMovementSkillPolicy.ENABLE_TELEPORT_NAVIGATION");

    private AgentRjpqMovementSkillPolicy() {
    }

    /** Whether this map's navigation graph gets Teleport edges: Dark Lab 2 on either side. */
    public static boolean authorsTeleportEdges(int mapId) {
        return ENABLE_TELEPORT_NAVIGATION && isLabTwo(mapId);
    }

    static boolean isLabTwo(int mapId) {
        AgentRjpqSide side = AgentRjpqSide.fromEventMap(mapId);
        return side != null && side.zenumistMap(mapId) == AgentRjpqDefinition.DARK_LAB_TWO_MAP;
    }

    /** Whether this Agent may route over Teleport edges now: the run's Lab 2 runner, in Lab 2, in its run's event. */
    public static boolean allowsActiveTeleport(Character agent) {
        if (agent == null || !authorsTeleportEdges(agent.getMapId())) {
            return false;
        }
        AgentRjpqSession session = AgentRjpqSessionRegistry.forMember(agent.getId());
        AgentRjpqMemberState member = session == null ? null : session.member(agent.getId());
        return session != null && member != null && member.agent()
                && session.eventInstance() != null && agent.getEventInstance() == session.eventInstance()
                && session.labRunner(AgentRjpqDarkLabs.LAB_TWO) == agent.getId();
    }
}
