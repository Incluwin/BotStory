package server.agents.capabilities.expedition.cwkpq;

import client.Character;
import server.agents.capabilities.expedition.boss.AgentBossRunCatalog;
import server.agents.capabilities.expedition.boss.AgentBossRunRegistry;
import server.agents.capabilities.expedition.boss.AgentBossRunSession;

/**
 * CWKPQ-owned exceptions for two stage-5 rooms (C9 live 2026-10-22), as RJPQ's Dark Lab 2 Teleport is. The magician
 * room's (610030521) monsters stand on platforms that walking and jumping never reach from where the room's portal lands
 * (the offline graph's arrival region reaches 17 of its 65 regions, none with a spawn): its graph gets Teleport edges.
 * Three of the thief room's (610030530) five eyes sit above platforms no walk or jump reaches, even hasted to 160/120;
 * with Flash Jump every platform is reached from the spawn: its graph gets Flash Jump edges. Only a member of a CWKPQ
 * run in the room routes over them.
 */
public final class AgentCwkpqMovementSkillPolicy {
    private static final boolean ENABLE_ROOM_SKILL_NAVIGATION = config.AgentTuning.booleanValue(
            "server.agents.capabilities.expedition.cwkpq.AgentCwkpqMovementSkillPolicy.ENABLE_ROOM_SKILL_NAVIGATION");

    private AgentCwkpqMovementSkillPolicy() {
    }

    /** Whether this map's navigation graph gets Teleport edges: the magician room. */
    public static boolean authorsTeleportEdges(int mapId) {
        return ENABLE_ROOM_SKILL_NAVIGATION && mapId == AgentCwkpqRun.Stage.MAGICIAN_ROOM.mapId();
    }

    /** Whether this map's navigation graph gets Flash Jump edges: the thief room. */
    public static boolean authorsFlashJumpEdges(int mapId) {
        return ENABLE_ROOM_SKILL_NAVIGATION && mapId == AgentCwkpqRun.Stage.THIEF_ROOM.mapId();
    }

    /** Whether this Agent may route over Teleport edges now: a member of a CWKPQ run, in the magician room's event. */
    public static boolean allowsActiveTeleport(Character agent) {
        return agent != null && authorsTeleportEdges(agent.getMapId()) && runMember(agent);
    }

    /** Whether this Agent may route over Flash Jump edges now: a member of a CWKPQ run, in the thief room's event. */
    public static boolean allowsActiveFlashJump(Character agent) {
        return agent != null && authorsFlashJumpEdges(agent.getMapId()) && runMember(agent);
    }

    private static boolean runMember(Character agent) {
        if (agent.getEventInstance() == null) {
            return false;
        }
        AgentBossRunSession session = AgentBossRunRegistry.forMember(agent.getId());
        return session != null && session.definition() == AgentBossRunCatalog.CWKPQ;
    }
}
