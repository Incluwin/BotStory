package server.agents.capabilities.follow;

import client.Character;
import scripting.event.EventInstanceManager;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Agent-owned cross-map follow synchronization rule.
 */
public final class AgentFollowMapSyncService {
    @FunctionalInterface
    public interface ChangeMapAction {
        void changeMap(Character agent, MapleMap map, Point position);
    }

    public record FollowMapSyncHooks(BiFunction<MapleMap, Point, Point> groundPointFinder,
                                     BiConsumer<AgentRuntimeEntry, Character> idleOnGround,
                                     ChangeMapAction changeMap,
                                     Consumer<AgentRuntimeEntry> resetEntryState,
                                     BiConsumer<AgentRuntimeEntry, Character> holdInPlace) {
    }

    private AgentFollowMapSyncService() {
    }

    public static boolean syncFollowMap(AgentRuntimeEntry entry,
                                        Character agent,
                                        Character followAnchor,
                                        FollowMapSyncHooks hooks) {
        if (!AgentModeStateRuntime.following(entry)
                || followAnchor == null
                || followAnchor.isChangingMaps()
                || agent.getMapId() == followAnchor.getMapId()) {
            return false;
        }

        MapleMap targetMap = followAnchor.getMap();
        if (AgentFollowGuard.holdsOffFollow(agent.getId()) || !mayWarpInto(agent, followAnchor, targetMap)) {
            // Stay on this map: the host keeps it off follow, or the anchor is out of reach (an instance
            // entrance, or the old channel) until it is reachable again.
            hooks.holdInPlace().accept(entry, agent);
            return true;
        }
        Point anchorPosition = followAnchor.getPosition();
        AgentFollowGuard.Crossing crossing = AgentFollowGuard.crossing(agent.getId(), followAnchor);
        if (crossing != null) {
            if (System.currentTimeMillis() < crossing.notBeforeMs()) {
                hooks.holdInPlace().accept(entry, agent); // the followers ahead of it come through first
                return true;
            }
            anchorPosition = crossing.at(); // where the anchor came in, not where it stands now
        }
        Point spawn = hooks.groundPointFinder().apply(targetMap, new Point(anchorPosition.x, anchorPosition.y - 1));
        if (spawn == null) {
            spawn = anchorPosition;
        }
        hooks.idleOnGround().accept(entry, agent);
        hooks.changeMap().changeMap(agent, targetMap, spawn);
        hooks.resetEntryState().accept(entry);
        return true;
    }

    /**
     * The warp re-resolves the anchor's map id through the Agent's own channel and event instance
     * (Character.changeMap -> getWarpMap), so it lands beside the anchor only when both are the anchor's.
     * An Agent that is not registered in the anchor's instance would land in the ordinary copy of the map,
     * and one still registered in an instance the anchor has left would land in that instance's copy unless
     * the event script happens to let it out; it holds until the instance ends and unregisters it.
     */
    static boolean mayWarpInto(Character agent, Character followAnchor, MapleMap targetMap) {
        if (followAnchor.isAwayFromWorld()) {
            return false; // changing channel, or in the cash shop
        }
        MapleMap agentMap = agent.getMap();
        if (agentMap != null && agentMap.getChannelServer() != targetMap.getChannelServer()) {
            return false;
        }
        EventInstanceManager instance = targetMap.getEventInstance();
        return instance == agent.getEventInstance();
    }
}
