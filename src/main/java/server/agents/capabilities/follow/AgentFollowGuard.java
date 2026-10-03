package server.agents.capabilities.follow;

import client.Character;

import java.awt.Point;

/**
 * Per-Agent follow seam. The Agent layer stays owner-free: follow asks this guard, and the host (the living
 * world) installs the rule that knows which Agents it keeps off follow, where its own followers come into
 * a map, and where they wait instead of crowding a spot it keeps clear. With nothing installed every Agent
 * follows as before.
 */
public final class AgentFollowGuard {
    /** The installed follow rule. */
    public interface Rule {
        /**
         * True while the host keeps this loaded Agent character off follow (on its way over, waiting outside a
         * run, away on an errand): no follow command starts it following, and follow never warps it.
         */
        boolean holdsOffFollow(int agentCharacterId);

        /**
         * How this following Agent character comes into the map its anchor just changed to: where the anchor
         * came in, and not before when. Null keeps the placement beside the anchor.
         */
        Crossing crossing(int agentCharacterId, Character anchor);

        /**
         * Where this following Agent character waits instead of beside its anchor, while the anchor stands at a
         * spot the host keeps clear (an entry NPC whose ledge followers would crowd until they hid her). Asked on
         * the Agent's tick, from memory. Null follows beside the anchor as before.
         */
        default Point waitsAt(int agentCharacterId, Character anchor) {
            return null;
        }
    }

    /** A follower comes into its anchor's map at {@code at}, once {@code notBeforeMs} has passed. */
    public record Crossing(Point at, long notBeforeMs) {
    }

    private static volatile Rule rule;

    private AgentFollowGuard() {
    }

    public static void install(Rule installed) {
        rule = installed;
    }

    public static boolean holdsOffFollow(int agentCharacterId) {
        Rule current = rule;
        return current != null && current.holdsOffFollow(agentCharacterId);
    }

    public static Crossing crossing(int agentCharacterId, Character anchor) {
        Rule current = rule;
        return current == null ? null : current.crossing(agentCharacterId, anchor);
    }

    /** {@link Rule#waitsAt}: null with no rule installed or no anchor. */
    public static Point waitsAt(int agentCharacterId, Character anchor) {
        Rule current = rule;
        return current == null || anchor == null ? null : current.waitsAt(agentCharacterId, anchor);
    }
}
