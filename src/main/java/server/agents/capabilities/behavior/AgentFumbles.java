package server.agents.capabilities.behavior;

import client.Character;
import server.agents.capabilities.combat.AgentCombatTargetEligibilityPolicy;
import server.agents.capabilities.movement.AgentGroundingService;
import server.agents.capabilities.movement.AgentJumpActionService;
import server.agents.perception.AgentMapPerception;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.state.AgentCapabilityStateKey;
import server.life.Monster;
import server.maps.MapleMap;
import server.maps.Rope;

import java.awt.Point;
import java.util.concurrent.ThreadLocalRandom;

/**
 * F8.5 fumbles in town (docs/BOT_CONCEPT_PLAN.md F8): a citizen or companion now and then jumps beside a rope without
 * catching it before the real grab, or walks a little past the NPC it went to and turns back. Only on a town map, with
 * no hostile mob near, through the imperfections gate's budget (ROPE_MISS, NPC_OVERSHOOT). On the Agent's own tick.
 */
public final class AgentFumbles {
    private static final String TUNING_PREFIX = "server.agents.capabilities.behavior.AgentFumbles.";
    /** A rope missed once is grabbed on the next try; the same rope may be missed again after this long. */
    static final long ROPE_AGAIN_MS = tuningLong("ROPE_AGAIN_MS");
    /** Walks OVERSHOOT_MIN_PX to OVERSHOOT_MIN_PX + OVERSHOOT_SPAN_PX - 1 past the NPC spot. */
    static final int OVERSHOOT_MIN_PX = tuningInt("OVERSHOOT_MIN_PX");
    static final int OVERSHOOT_SPAN_PX = tuningInt("OVERSHOOT_SPAN_PX");
    /** The turn-back point counts as reached this close; the walk past gives up after this long. */
    static final int OVERSHOOT_REACHED_PX = tuningInt("OVERSHOOT_REACHED_PX");
    static final long OVERSHOOT_MAX_MS = tuningLong("OVERSHOOT_MAX_MS");
    static final int OVERSHOOT_SAME_LEVEL_PX = tuningInt("OVERSHOOT_SAME_LEVEL_PX");
    /** No fumble with a hostile mob this close. */
    static final int CALM_RANGE_PX = tuningInt("CALM_RANGE_PX");

    /** Per-Agent fumble state. */
    public static final class State {
        public static final AgentCapabilityStateKey<State> STATE_KEY =
                new AgentCapabilityStateKey<>("behavior.fumbles", State.class, State::new);

        private long missedRope;
        private long missedRopeAtMs;
        private Point overshoot;
        private long overshootUntilMs;

        synchronized boolean ropeMissedRecently(long key, long nowMs) {
            return missedRope == key && nowMs - missedRopeAtMs < ROPE_AGAIN_MS;
        }

        synchronized void ropeMissed(long key, long nowMs) {
            missedRope = key;
            missedRopeAtMs = nowMs;
        }

        synchronized void overshoot(Point at, long untilMs) {
            overshoot = at == null ? null : new Point(at);
            overshootUntilMs = untilMs;
        }

        synchronized Point overshoot(long nowMs) {
            if (overshoot != null && nowMs >= overshootUntilMs) {
                overshoot = null;
            }
            return overshoot == null ? null : new Point(overshoot);
        }
    }

    private AgentFumbles() {
    }

    /**
     * The climb entry is about to grab {@code rope}: true when it jumps beside it instead (a plain jump, which cannot
     * catch a rope), once; on landing the entry runs again and grabs.
     */
    public static boolean missesRope(AgentRuntimeEntry entry, Character agent, Rope rope, long nowMs) {
        if (!AgentImperfections.on() || entry == null || agent == null || rope == null || !calmTown(agent)) {
            return false;
        }
        State state = entry.capabilityStates().require(State.STATE_KEY);
        long key = ((long) agent.getMapId() << 32) ^ (rope.x() * 31L + rope.topY());
        if (state.ropeMissedRecently(key, nowMs)
                || !AgentImperfections.slips(agent, AgentImperfections.Slip.ROPE_MISS)) {
            return false;
        }
        state.ropeMissed(key, nowMs);
        AgentJumpActionService.initiateJump(entry, agent, 0);
        return true;
    }

    /**
     * A town-life walk to an NPC spot begins: when the gate lets this one walk past (NPC_OVERSHOOT), it heads for a
     * point 80-150 px beyond {@code target} on the same level first ({@link #overshoot}).
     */
    public static void armOvershoot(AgentRuntimeEntry entry, Character agent, Point target, long nowMs) {
        if (!AgentImperfections.on() || entry == null || agent == null || target == null
                || agent.getPosition() == null || !calmTown(agent)) {
            return;
        }
        int direction = Integer.signum(target.x - agent.getPosition().x);
        if (direction == 0) {
            return;
        }
        int past = OVERSHOOT_MIN_PX + ThreadLocalRandom.current().nextInt(OVERSHOOT_SPAN_PX);
        Point ground = AgentGroundingService.findGroundPoint(agent.getMap(),
                new Point(target.x + direction * past, target.y - 1));
        if (ground == null || Math.abs(ground.y - target.y) > OVERSHOOT_SAME_LEVEL_PX
                || !AgentImperfections.slips(agent, AgentImperfections.Slip.NPC_OVERSHOOT)) {
            return;
        }
        entry.capabilityStates().require(State.STATE_KEY).overshoot(ground, nowMs + OVERSHOOT_MAX_MS);
    }

    /** While walking past: the point to walk to now; null once reached, timed out, or with none armed. */
    public static Point overshoot(AgentRuntimeEntry entry, Character agent, long nowMs) {
        if (!AgentImperfections.on() || entry == null || agent == null) {
            return null;
        }
        State state = entry.capabilityStates().find(State.STATE_KEY).orElse(null);
        Point at = state == null ? null : state.overshoot(nowMs);
        if (at == null) {
            return null;
        }
        Point here = agent.getPosition();
        if (here == null || Math.abs(here.x - at.x) <= OVERSHOOT_REACHED_PX && Math.abs(here.y - at.y) <= OVERSHOOT_SAME_LEVEL_PX) {
            state.overshoot(null, 0L);
            return null;
        }
        return at;
    }

    private static int tuningInt(String name) {
        return config.AgentTuning.intValue(TUNING_PREFIX + name);
    }

    private static long tuningLong(String name) {
        return config.AgentTuning.longValue(TUNING_PREFIX + name);
    }

    /** A town map with no hostile mob within CALM_RANGE_PX of the Agent: never a fumble in combat. */
    public static boolean calmTown(Character agent) {
        MapleMap map = agent.getMap();
        Point here = agent.getPosition();
        if (map == null || here == null || !map.isTown()) {
            return false;
        }
        for (Monster mob : AgentMapPerception.monsters(map)) {
            Point there = mob.getPosition();
            if (AgentCombatTargetEligibilityPolicy.isHostileLivingMonster(mob)
                    && (there == null || there.distanceSq(here) <= (double) CALM_RANGE_PX * CALM_RANGE_PX)) {
                return false;
            }
        }
        return true;
    }
}
