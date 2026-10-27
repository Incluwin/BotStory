package server.agents.capabilities.movement;

import server.agents.integration.AgentMapGatewayRuntime;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.integration.MapGateway;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.MapleMap;

public final class AgentMapEnvironmentService {
    private AgentMapEnvironmentService() {
    }

    public static boolean isSwimMap(AgentRuntimeEntry entry) {
        return isSwimMap(entry, AgentMapGatewayRuntime.map());
    }

    public static boolean isSwimMap(AgentRuntimeEntry entry, MapGateway maps) {
        return maps.isSwimMap(AgentRuntimeIdentityRuntime.bot(entry));
    }

    /**
     * Water an Agent swims through, or air it flies through (the dragon flights to the Temple of Time, fly=1): both
     * are crossed with the swim integrator, since the flight maps have no ground path between their portals.
     */
    public static boolean swims(MapleMap map) {
        return map != null && (map.isSwim() || map.isFly());
    }
}
