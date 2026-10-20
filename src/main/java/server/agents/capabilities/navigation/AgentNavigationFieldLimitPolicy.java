package server.agents.capabilities.navigation;

import client.Character;
import server.maps.FieldLimit;

/**
 * A map's no-jump-down limit (FieldLimit.CANNOTJUMPDOWN, 0x20000): the client never lets a player down-jump there, and
 * the server never reads the flag. The graph authors down-jumps on every map (a DROP edge with no launch step), so route
 * selection leaves them out where the map forbids them, as it leaves out movement-skill edges on a MOVEMENTSKILLS map;
 * walking off a ledge stays. Kept out of the graph build so the cached graphs (one GRAPH_VERSION for every map) stand.
 * CWKPQ plan B4: the Test of Agility 610030300 and the warrior and pirate rooms 610030510 / 610030550 carry it.
 */
public final class AgentNavigationFieldLimitPolicy {
    private AgentNavigationFieldLimitPolicy() {
    }

    /** True when {@code edge} is a down-jump and {@code agent}'s map forbids down-jumps. */
    public static boolean forbids(Character agent, AgentNavigationGraph.Edge edge) {
        return edge != null && isDownJump(edge) && agent != null && agent.getMap() != null
                && FieldLimit.CANNOTJUMPDOWN.check(agent.getMap().getFieldLimit());
    }

    /** A down-jump: a drop through the floor from standing, not a walk off its end (which has a launch step). */
    static boolean isDownJump(AgentNavigationGraph.Edge edge) {
        return edge.type == AgentNavigationGraph.EdgeType.DROP && edge.launchStepX == 0;
    }
}
