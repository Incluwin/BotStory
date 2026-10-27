package server.agents.capabilities.navigation;

import client.Character;
import server.agents.capabilities.behavior.AgentTellProbe;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.runtime.AgentRuntimeEntry;

import java.awt.Point;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A walk to a target on foot that a host started (docs/CITIZEN_PORTAL_TRAVEL_PLAN.md 2.3): through the portals the
 * route graphs know ({@code travelTo} on each tick) until the Agent stands on the target map, then up to an NPC or a
 * portal there when the target names one, into the portal when it is to be entered. It runs on the Agent's own tick
 * before follow (AgentTickPreflightRuntime), as the boss death walk does: it sets the walk's next move and the tick goes
 * on, so the movement step makes it. The host starts and stops walks from any thread and reads their status; the walk
 * never decides where to go next. A walk ends ARRIVED, NO_ROUTE, or STALLED once it has stood on one map for the host's
 * hop timeout; a downed walker waits, and its time down does not count. Memory only.
 */
public final class AgentMapWalk {
    /** Agent id -> its walk, while one runs or until the host reads how it ended. */
    private static final Map<Integer, Walk> WALKS = new ConcurrentHashMap<>();

    public enum Status { WALKING, ARRIVED, NO_ROUTE, STALLED }

    /** What a walk does on its target map: nothing (it is there), walk up to an NPC, or walk to a portal. */
    public enum Finish { MAP, NPC, ENTER_PORTAL, REACH_PORTAL }

    /** Where a walk goes: {@code mapId}, then the NPC or portal {@code id} on it (0 for {@link Finish#MAP}). */
    public record Target(int mapId, Finish finish, int id) {
        public static Target map(int mapId) {
            return new Target(mapId, Finish.MAP, 0);
        }

        public static Target npc(int mapId, int npcId) {
            return new Target(mapId, Finish.NPC, npcId);
        }

        /** Walks to the portal and goes through it: the walk has ARRIVED once the portal took it elsewhere. */
        public static Target enter(int mapId, int portalId) {
            return new Target(mapId, Finish.ENTER_PORTAL, portalId);
        }

        /** Walks to the portal and stands at it. */
        public static Target reach(int mapId, int portalId) {
            return new Target(mapId, Finish.REACH_PORTAL, portalId);
        }
    }

    /** One walk of one Agent runtime; compared by identity, as two starts are two walks. */
    public static final class Walk {
        private final AgentRuntimeEntry entry;
        private final Target target;
        private final long hopTimeoutMs;
        private volatile Status status = Status.WALKING;
        private volatile int lastMapId;
        private volatile long sinceMs;
        // the route it walked, for the tell audit (AgentTellProbe); the Agent's tick only
        private final int fromMapId;
        private long routeHash;
        private int tracedMapId;
        private AgentNavigationGraph.Edge tracedEdge;

        private Walk(AgentRuntimeEntry entry, Target target, long hopTimeoutMs, int mapId, long nowMs) {
            this.entry = entry;
            this.target = target;
            this.hopTimeoutMs = hopTimeoutMs;
            this.lastMapId = mapId;
            this.sinceMs = nowMs;
            this.fromMapId = mapId;
            this.routeHash = mapId;
            this.tracedMapId = mapId;
        }

        /** Folds the map it stands on and the graph edge it walks into its route, when either changed. */
        private void trace(int mapId) {
            AgentNavigationGraph.Edge edge = entry.navigationEdgeState().activeEdge();
            if (mapId != tracedMapId) {
                routeHash = routeHash * 31 + mapId;
            }
            if (edge != null && edge != tracedEdge) {
                routeHash = ((routeHash * 31 + edge.type.ordinal()) * 31 + edge.fromRegionId) * 31 + edge.toRegionId;
            }
            tracedMapId = mapId;
            tracedEdge = edge;
        }

        public Target target() {
            return target;
        }

        public Status status() {
            return status;
        }
    }

    private AgentMapWalk() {
    }

    /**
     * Starts {@code entry}'s walk to {@code target}; a walk it had is replaced. {@code hopTimeoutMs}: the walk is STALLED
     * once it has stood on one map this long (any map, the target map's NPC or portal walk too).
     */
    public static Walk start(AgentRuntimeEntry entry, Character agent, Target target, long hopTimeoutMs, long nowMs) {
        Walk walk = new Walk(entry, target, Math.max(1L, hopTimeoutMs), agent.getMapId(), nowMs);
        WALKS.put(agent.getId(), walk);
        return walk;
    }

    /** Ends {@code walk} when it is still the Agent's; the host stops the movement it left. */
    public static void stop(int agentId, Walk walk) {
        if (walk != null) {
            WALKS.remove(agentId, walk);
        }
    }

    /** Ends whatever walk the Agent has (its runtime went away). */
    public static void stopAll(int agentId) {
        if (!WALKS.isEmpty()) {
            WALKS.remove(agentId);
        }
    }

    /** True while {@code agentId} has a walk that is still under way. */
    public static boolean walking(int agentId) {
        Walk walk = WALKS.isEmpty() ? null : WALKS.get(agentId);
        return walk != null && walk.status == Status.WALKING;
    }

    /** One Agent tick, before follow: true while it walks, so the movement step makes the move this step set. */
    public static boolean tick(AgentRuntimeEntry entry, Character agent, long nowMs) {
        if (WALKS.isEmpty() || entry == null || agent == null) {
            return false;
        }
        Walk walk = WALKS.get(agent.getId());
        if (walk == null || walk.status != Status.WALKING) {
            return false;
        }
        if (walk.entry != entry) { // left from an earlier load of the same character
            WALKS.remove(agent.getId(), walk);
            return false;
        }
        int mapId = agent.getMapId();
        if (!agent.isAlive() || mapId != walk.lastMapId) {
            walk.lastMapId = mapId;
            walk.sinceMs = nowMs; // a new map, or time down: neither counts toward the hop timeout
            if (!agent.isAlive()) {
                return false;
            }
        }
        PrimitiveCapabilityGateway gateway = AgentPrimitiveCapabilityGatewayRuntime.gateway();
        if (nowMs - walk.sinceMs >= walk.hopTimeoutMs) {
            gateway.stop(entry);
            walk.status = Status.STALLED;
            return false;
        }
        if (AgentTellProbe.on()) {
            walk.trace(mapId);
        }
        Target target = walk.target;
        if (mapId == target.mapId()) {
            return finish(entry, agent, walk, gateway);
        }
        AgentRouteOutcome outcome = gateway.travelTo(entry, agent, target.mapId(), nowMs);
        if (outcome.status() == AgentRouteStatus.NO_ROUTE) {
            walk.status = Status.NO_ROUTE;
            return false;
        }
        return true; // moving, settling after a crossing, or a refused portal it tries again or walks around
    }

    /** On the target map: done, or up to its NPC or portal (and through the portal when it is to be entered). */
    private static boolean finish(AgentRuntimeEntry entry, Character agent, Walk walk, PrimitiveCapabilityGateway gateway) {
        Target target = walk.target;
        if (target.finish() == Finish.MAP) {
            arrived(agent, walk);
            return false;
        }
        boolean npc = target.finish() == Finish.NPC;
        Point goal = npc ? gateway.npcPosition(agent, target.id()) : gateway.portalPosition(agent, target.id());
        Point at = agent.getPosition();
        if (goal == null || at == null) {
            walk.status = Status.NO_ROUTE;
            return false;
        }
        long reach = npc ? npcReachPx() : portalReachPx();
        if (at.distanceSq(goal) > reach * reach) {
            gateway.navigate(entry, goal, true);
            return true;
        }
        gateway.stop(entry);
        if (npc) {
            gateway.facePosition(agent, goal);
        } else if (target.finish() == Finish.ENTER_PORTAL && !gateway.enterPortal(agent, target.id())) {
            walk.status = Status.NO_ROUTE; // the portal refused it (closed, or its script said no)
            return false;
        }
        arrived(agent, walk);
        return false;
    }

    private static void arrived(Character agent, Walk walk) {
        walk.status = Status.ARRIVED;
        AgentTellProbe.routeWalked(agent.getId(), walk.fromMapId, walk.target.mapId(), walk.routeHash);
    }

    static void resetForTest() {
        WALKS.clear();
    }

    private static int portalReachPx() {
        return config.AgentTuning.intValue("server.agents.capabilities.navigation.AgentMapWalk.PORTAL_REACH_PX");
    }

    private static int npcReachPx() {
        return config.AgentTuning.intValue("server.agents.capabilities.navigation.AgentMapWalk.NPC_REACH_PX");
    }
}
