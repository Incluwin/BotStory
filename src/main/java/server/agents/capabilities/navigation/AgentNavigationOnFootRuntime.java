package server.agents.capabilities.navigation;

import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.state.AgentCapabilityStateKey;

import java.awt.Point;

/**
 * An Agent asked to arrive on foot. The route search counts an in-map portal as free, so from far off it sends an
 * Agent through a town shortcut (Kerning City's hidden ph03, Henesys' hp00) and the Agent reappears thousands of
 * pixels on, beside whoever it was walking to. Until it first comes within {@code reachPx} of the point it navigates
 * to, an on-foot Agent's routes take no in-map portal. The request ends there, on any other map than the one it was
 * made on, or when no route on foot reaches the target, so a target that only a portal reaches is still reached.
 * No other Agent's routing changes.
 */
public final class AgentNavigationOnFootRuntime {
    private static final AgentCapabilityStateKey<State> STATE_KEY = new AgentCapabilityStateKey<>(
            "navigation.on-foot", State.class, State::new);

    private AgentNavigationOnFootRuntime() {
    }

    /** Asks the Agent, standing on map {@code mapId}, to walk until it is within {@code reachPx} of its target. */
    public static void arriveOnFoot(AgentRuntimeEntry entry, int mapId, int reachPx) {
        if (entry != null && reachPx > 0) {
            entry.capabilityStates().require(STATE_KEY).request = new Request(mapId, reachPx);
        }
    }

    /** Whether the Agent still arrives on foot. */
    public static boolean onFoot(AgentRuntimeEntry entry) {
        return request(entry) != null;
    }

    /** Ends the request once the Agent, at {@code position} on {@code mapId}, is on another map or near its target. */
    static void observe(AgentRuntimeEntry entry, int mapId, Point position, Point target) {
        Request request = request(entry);
        if (request == null) {
            return;
        }
        long reach = request.reachPx();
        if (mapId != request.mapId()
                || position != null && target != null && position.distanceSq(target) <= reach * reach) {
            end(entry);
        }
    }

    /** Whether an on-foot route may take {@code edge}: anything but an in-map portal. */
    static boolean walks(AgentNavigationGraph.Edge edge) {
        return edge == null || edge.type != AgentNavigationGraph.EdgeType.PORTAL;
    }

    static void end(AgentRuntimeEntry entry) {
        if (entry != null) {
            entry.capabilityStates().remove(STATE_KEY);
        }
    }

    private static Request request(AgentRuntimeEntry entry) {
        return entry == null ? null
                : entry.capabilityStates().find(STATE_KEY).map(state -> state.request).orElse(null);
    }

    private record Request(int mapId, int reachPx) {
    }

    private static final class State {
        private volatile Request request;
    }
}
