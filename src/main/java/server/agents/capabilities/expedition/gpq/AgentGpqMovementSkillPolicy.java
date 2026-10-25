package server.agents.capabilities.expedition.gpq;

import client.Character;
import server.agents.capabilities.expedition.boss.AgentBossRunRegistry;
import server.agents.capabilities.expedition.boss.AgentBossRunSession;

/**
 * GPQ-owned exception for the Room of Justice (990000440, GPQ plan G0, 2026-10-25), as CWKPQ's stage-5 rooms are. Two
 * of its four altar towers are out of walking and jumping reach, even hasted to 140/120: spear1's climb needs a 133 px
 * running jump onto a rope from the -658 ledge, and spear4's column rises 140 px from floor to floor (y 274 to 134).
 * Players climb them with Teleport or Flash Jump, which the room allows (no MOVEMENTSKILLS field limit). The room's
 * graph gets Teleport and Flash Jump edges; only a member of a GPQ run in the room's event routes over them.
 */
public final class AgentGpqMovementSkillPolicy {
    /** The Room of Justice: the four altar towers of stage 2. */
    public static final int ROOM_OF_JUSTICE_MAP_ID = 990_000_440;

    private static final boolean ENABLE_ROOM_SKILL_NAVIGATION = config.AgentTuning.booleanValue(
            "server.agents.capabilities.expedition.gpq.AgentGpqMovementSkillPolicy.ENABLE_ROOM_SKILL_NAVIGATION");

    private AgentGpqMovementSkillPolicy() {
    }

    /** Whether this map's navigation graph gets Teleport edges: the Room of Justice. */
    public static boolean authorsTeleportEdges(int mapId) {
        return ENABLE_ROOM_SKILL_NAVIGATION && mapId == ROOM_OF_JUSTICE_MAP_ID;
    }

    /** Whether this map's navigation graph gets Flash Jump edges: the Room of Justice. */
    public static boolean authorsFlashJumpEdges(int mapId) {
        return ENABLE_ROOM_SKILL_NAVIGATION && mapId == ROOM_OF_JUSTICE_MAP_ID;
    }

    /** Whether this Agent may route over Teleport edges now: a member of a GPQ run, in the room's event. */
    public static boolean allowsActiveTeleport(Character agent) {
        return agent != null && authorsTeleportEdges(agent.getMapId()) && runMember(agent);
    }

    /** Whether this Agent may route over Flash Jump edges now: a member of a GPQ run, in the room's event. */
    public static boolean allowsActiveFlashJump(Character agent) {
        return agent != null && authorsFlashJumpEdges(agent.getMapId()) && runMember(agent);
    }

    private static boolean runMember(Character agent) {
        if (agent.getEventInstance() == null) {
            return false;
        }
        AgentBossRunSession session = AgentBossRunRegistry.forMember(agent.getId());
        return session != null && AgentGpqRun.KEY.equals(session.definition().key())
                && session.event() == agent.getEventInstance();
    }
}
