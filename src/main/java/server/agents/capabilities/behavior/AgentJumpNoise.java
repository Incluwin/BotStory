package server.agents.capabilities.behavior;

import client.Character;
import server.agents.capabilities.combat.AgentFallDamageCalculator;
import server.agents.capabilities.movement.AgentJumpActionService;
import server.agents.capabilities.movement.AgentPostLandingJump;
import server.agents.capabilities.movement.AgentJumpProbeService;
import server.agents.capabilities.movement.AgentMovementKinematicsService;
import server.agents.capabilities.movement.AgentMovementPhysicsStateRuntime;
import server.agents.capabilities.movement.AgentMovementProfile;
import server.agents.capabilities.movement.AgentMovementStateRuntime;
import server.agents.capabilities.movement.AgentMoveTargetStateRuntime;
import server.agents.capabilities.navigation.AgentNavigationGraph;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.state.AgentCapabilityStateKey;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Jump-quest execution noise (docs/BOT_CONCEPT_PLAN.md F5b). While a citizen climbs a jump quest its jump presses come
 * a little early or late: the launch point of each JUMP to a platform is moved by a draw from N(0, sigma) pixels, where
 * the living world's plan gives sigma by height (tuned so each citizen clears a stage 30-70% of the time). A launch off
 * the edge's window misses and drops it to a lower foothold, as a person's would; it then climbs again by its route.
 * A draw is kept only when the jump probe lands it on a foothold that can still reach its move target, so a miss is
 * never a fall below the map. Misses do not count against the edge's reliability, and F8's town rope fumble stays off
 * while the noise is on (one source of falls, not two). With no plan installed every jump is exactly today's.
 */
public final class AgentJumpNoise {
    /** A drawn press stays this far inside its platform's ends, so walking to it never steps off. */
    public static final int EDGE_MARGIN_PX = 4;
    /** Ticks after a probed landing that it must still stand on its foothold. */
    static final int POST_LANDING_TICKS = 3;
    /** A landing this far below its launch (or a planned jump down's landing) is a miss (a drop to a lower foothold). */
    static final int MISS_DROP_PX = 30;
    /** Draws tried before the exact launch is used instead. */
    public static final int REDRAWS = 4;
    /** No press is ever this far off. */
    public static final int MAX_OFFSET_PX = 90;
    /** A miss never drops this close to the fall-damage distance (measured from its apex, about a jump above the launch). */
    static final int SAFE_DROP_MARGIN_PX = 150;

    /** What the living world installs for one jump-quest attempt. */
    public interface Plan {
        /** The launch spread (one sigma, in pixels) for a jump taking off at height {@code launchY}; 0 = exact. */
        double sigmaAt(int launchY);

        /** The launch spread for the jump taking off at {@code launch} (its edge's start); by its height unless told. */
        default double sigmaAt(Point launch) {
            return sigmaAt(launch.y);
        }

        /**
         * The Agent fell below the map anyway: true when the host dealt with it (it logs the citizen off in front of a
         * player instead of a teleport), false to let the recovery teleport run (nobody watching).
         */
        boolean belowMap(Character agent);
    }

    /** One attempt's noise and what it did. */
    public static final class State {
        public static final AgentCapabilityStateKey<State> STATE_KEY =
                new AgentCapabilityStateKey<>("behavior.jumpnoise", State.class, State::new);

        private volatile Plan plan;
        private Random random = new Random();
        private AgentNavigationGraph.Edge drawnEdge;
        private int drawnX = Integer.MIN_VALUE;
        private int drawnTolerance;
        private boolean moved;
        private int pendingLaunchY = Integer.MIN_VALUE;
        private int jumps;
        private int misses;
        private int exact;

        synchronized void install(Plan installed, long seed) {
            plan = installed;
            random = new Random(seed);
            drawnEdge = null;
            drawnX = Integer.MIN_VALUE;
            pendingLaunchY = Integer.MIN_VALUE;
            jumps = 0;
            misses = 0;
            exact = 0;
        }

        synchronized void clear() {
            plan = null;
            drawnEdge = null;
            pendingLaunchY = Integer.MIN_VALUE;
        }
    }

    /** What an attempt's noise did so far: jumps taken with a draw, misses among them, draws replaced by the exact launch. */
    public record Counts(int jumps, int misses, int exact) {
    }

    private AgentJumpNoise() {
    }

    /** The jump-quest activity starts an attempt: its jumps follow {@code plan} from now on. */
    public static void install(AgentRuntimeEntry entry, Plan plan, long seed) {
        if (entry != null && plan != null) {
            entry.capabilityStates().require(State.STATE_KEY).install(plan, seed);
        }
    }

    /** The attempt is over: every jump exact again. */
    public static void clear(AgentRuntimeEntry entry) {
        State state = entry == null ? null : entry.capabilityStates().find(State.STATE_KEY).orElse(null);
        if (state != null) {
            state.clear();
        }
    }

    /** True while a jump-quest attempt's noise is on for this Agent. */
    public static boolean active(AgentRuntimeEntry entry) {
        State state = entry == null ? null : entry.capabilityStates().find(State.STATE_KEY).orElse(null);
        return state != null && state.plan != null;
    }

    public static Counts counts(AgentRuntimeEntry entry) {
        State state = entry == null ? null : entry.capabilityStates().find(State.STATE_KEY).orElse(null);
        if (state == null) {
            return new Counts(0, 0, 0);
        }
        synchronized (state) {
            return new Counts(state.jumps, state.misses, state.exact);
        }
    }

    /**
     * Navigation's own pick of a jump's launch x in {@code [minX, maxX]}, before any draw: from the attempt's seeded
     * random while a plan is on, so one seed replays one climb; otherwise any x in the window, as always.
     */
    public static int pickLaunchX(AgentRuntimeEntry entry, int minX, int maxX) {
        State state = entry == null ? null : entry.capabilityStates().find(State.STATE_KEY).orElse(null);
        if (state != null && state.plan != null) {
            synchronized (state) {
                return minX + state.random.nextInt(maxX - minX + 1);
            }
        }
        return ThreadLocalRandom.current().nextInt(minX, maxX + 1);
    }

    /**
     * The launch x navigation is about to commit to for {@code edge} ({@code selectedX}, inside its window): moved by
     * the attempt's draw, kept for this edge until it is taken. Unchanged with no plan, for a rope or flash jump, or
     * when no safe draw was found.
     */
    public static int launchX(AgentRuntimeEntry entry, AgentNavigationGraph graph, AgentNavigationGraph.Edge edge,
                              int selectedX) {
        State state = entry == null ? null : entry.capabilityStates().find(State.STATE_KEY).orElse(null);
        Plan plan = state == null ? null : state.plan;
        AgentNavigationGraph.Region from = graph == null || edge == null ? null : graph.getRegion(edge.fromRegionId);
        if (plan == null || from == null || from.isRopeRegion || edge.type != AgentNavigationGraph.EdgeType.JUMP) {
            return selectedX;
        }
        synchronized (state) {
            if (state.drawnEdge == edge) {
                return state.drawnX;
            }
            double sigma = Math.max(0.0d, plan.sigmaAt(edge.startPoint));
            MapleMap map = sigma > 0.0d ? AgentRuntimeIdentityRuntime.botMap(entry) : null; // only a draw needs them
            AgentMovementProfile profile = map != null ? AgentMovementStateRuntime.movementProfile(entry) : null;
            int tolerance = map != null ? launchTolerance(map, profile) : 0;
            int x = selectedX;
            boolean drawn = false;
            for (int attempt = 0; attempt < REDRAWS && sigma > 0.0d && map != null; attempt++) {
                int edgeMargin = from.maxX - from.minX > 2 * EDGE_MARGIN_PX ? EDGE_MARGIN_PX : 0;
                int offset = (int) Math.round(Math.max(-MAX_OFFSET_PX, Math.min(MAX_OFFSET_PX, state.random.nextGaussian() * sigma)));
                // Never past navigation's own pick: a window inside the edge margin (100000202's r61->r60 is the last 4
                // px of its platform) was clamped off itself on every draw, so each press there missed.
                int low = Math.min(from.minX + edgeMargin, selectedX);
                int high = Math.max(from.maxX - edgeMargin, selectedX);
                int candidate = Math.max(low, Math.min(high, selectedX + offset));
                if (candidate == selectedX // navigation's own pick: its rules as always; any other press is probed
                        || safeLaunch(entry, graph, map, profile, from, edge, candidate, tolerance)) {
                    x = candidate;
                    drawn = true;
                    break;
                }
            }
            if (!drawn && sigma > 0.0d) {
                state.exact++;
            }
            state.drawnEdge = edge;
            state.drawnX = x;
            state.drawnTolerance = tolerance;
            state.moved = x != selectedX;
            return x;
        }
    }

    /**
     * How far from its drawn x a moved press may still be taken (readyAtDraw): half a walk step, by an Agent that came
     * to rest that close (a precise approach to a jump coasts to rest on its launch, a pixel either way).
     */
    public static int launchTolerance(MapleMap map, AgentMovementProfile profile) {
        return (AgentMovementKinematicsService.walkStep(map, profile) + 1) / 2;
    }

    /** True when {@code x} is this Agent's drawn launch for {@code edge} (navigation's cache keeps it). */
    public static boolean drawn(AgentRuntimeEntry entry, AgentNavigationGraph.Edge edge, int x) {
        State state = entry == null ? null : entry.capabilityStates().find(State.STATE_KEY).orElse(null);
        if (state == null || state.plan == null) {
            return false;
        }
        synchronized (state) {
            return state.drawnEdge == edge && state.drawnX == x;
        }
    }

    /**
     * Readiness for a drawn launch the noise moved off navigation's pick (a press too early or too late): null when the
     * edge has no such draw (navigation decides as always), else whether the Agent is on it (a pixel either way), or at
     * rest within the draw's launchTolerance of it. So the takeoff is the draw, as the calibration (JumpWindows) asks.
     * Taken at the first position near it instead, it came on the approach side every time (navigation looks every
     * 100 ms, two walk steps): 1-7 px early within a whole step, and a 70% climber cleared about 30% of 101000100's
     * stages; 2-3 px early within half a step, still about 45%.
     */
    public static Boolean readyAtDraw(AgentRuntimeEntry entry, AgentNavigationGraph.Edge edge, Point position) {
        State state = entry == null || position == null ? null : entry.capabilityStates().find(State.STATE_KEY).orElse(null);
        if (state == null || state.plan == null) {
            return null;
        }
        synchronized (state) {
            if (state.drawnEdge != edge || !state.moved) {
                return null;
            }
            int off = Math.abs(position.x - state.drawnX);
            return off <= 1 || off <= state.drawnTolerance && AgentMovementPhysicsStateRuntime.horizontalSpeed(entry) == 0.0;
        }
    }

    /** Navigation launched {@code edge}'s jump from {@code position}: its landing tells a hit from a miss. */
    public static void launched(AgentRuntimeEntry entry, AgentNavigationGraph.Edge edge, Point position) {
        State state = entry == null || position == null ? null : entry.capabilityStates().find(State.STATE_KEY).orElse(null);
        if (state == null || state.plan == null) {
            return;
        }
        synchronized (state) {
            if (state.drawnEdge == edge) {
                state.jumps++;
                // A jump planned down to a lower platform is no fall: Forest of Patience B's first map (101000102)
                // jumps 360-420 px down off each rope's top, and nine such hits counted as falls (JQ_FALLS_MAX 8).
                state.pendingLaunchY = Math.max(position.y, edge.endPoint.y);
                state.drawnEdge = null; // the next try at this edge draws again
            }
        }
    }

    /** The Agent landed at {@code position}: a drawn jump that came down well below its launch (or planned landing) missed. */
    public static void landed(AgentRuntimeEntry entry, Point position) {
        State state = entry == null || position == null ? null : entry.capabilityStates().find(State.STATE_KEY).orElse(null);
        if (state == null) {
            return;
        }
        synchronized (state) {
            if (state.pendingLaunchY != Integer.MIN_VALUE && position.y > state.pendingLaunchY + MISS_DROP_PX) {
                state.misses++;
            }
            state.pendingLaunchY = Integer.MIN_VALUE;
        }
    }

    /** The Agent is below its map: true when the attempt's host dealt with it, so no recovery teleport runs. */
    public static boolean belowMap(AgentRuntimeEntry entry, Character agent) {
        State state = entry == null ? null : entry.capabilityStates().find(State.STATE_KEY).orElse(null);
        Plan plan = state == null ? null : state.plan;
        return plan != null && plan.belowMap(agent);
    }

    /**
     * A press the noise moved: every launch within {@code tolerance} of it lands on a foothold that can still reach the
     * Agent's move target (its target or a lower one: a miss), and
     * no farther down than a fall that hurts (a jump quest's misses cost a climb, never HP: no deaths from them).
     */
    private static boolean safeLaunch(AgentRuntimeEntry entry, AgentNavigationGraph graph, MapleMap map,
                                      AgentMovementProfile profile, AgentNavigationGraph.Region from,
                                      AgentNavigationGraph.Edge edge, int x, int tolerance) {
        int stepX = AgentJumpActionService.resolveAirVelocityX(map, profile, edge.launchStepX);
        Point target = AgentMoveTargetStateRuntime.moveTarget(entry);
        int targetRegion = target == null ? -1 : graph.findRegionId(map, target);
        // It takes off anywhere within the tolerance of the drawn x (readyAtDraw): every launch in that band must be
        // safe. Every pixel: one pixel past a window's end can be the one that clears the platform's lip or misses it
        // into a void (101000101's r60->r59: -548 lands on r59, -547 falls off the map's left edge).
        for (int at = x - tolerance; at <= x + tolerance; at++) {
            if (!safePress(map, graph, profile, from, stepX, at, targetRegion)) {
                return false;
            }
        }
        return true;
    }

    /**
     * One take-off pixel of a moved press (safeLaunch; JumpQuestCourses calibrates on it): the probe lands it on a
     * foothold of the map that can still reach {@code targetRegion} (-1: any), and where it stands a few ticks after
     * landing (a landing near a platform's end can slide off) is what counts, no farther down than a fall that hurts.
     */
    public static boolean safePress(MapleMap map, AgentNavigationGraph graph, AgentMovementProfile profile,
                                    AgentNavigationGraph.Region from, int stepX, int at, int targetRegion) {
        Point origin = from.pointAt(Math.max(from.minX, Math.min(from.maxX, at)));
        return origin != null && safeLanding(map, graph, origin, probe(map, origin, stepX, profile), targetRegion);
    }

    /** The jump probe's landing from {@code origin}, and where it stands POST_LANDING_TICKS later. */
    public static AgentPostLandingJump probe(MapleMap map, Point origin, int stepX, AgentMovementProfile profile) {
        return AgentJumpProbeService.simulateJumpLandingWithPostLandingTicks(map, origin, stepX, profile, POST_LANDING_TICKS);
    }

    /** safePress on a landing already probed from {@code origin}. */
    public static boolean safeLanding(MapleMap map, AgentNavigationGraph graph, Point origin, AgentPostLandingJump landing,
                                      int targetRegion) {
        if (landing == null || landing.landing() == null || landing.landing().foothold() == null || landing.lostGround()
                || landing.finalPoint().y - origin.y > AgentFallDamageCalculator.FALL_DIST_THRESHOLD_PX - SAFE_DROP_MARGIN_PX) {
            return false;
        }
        int landedRegion = graph.findRegionId(map, landing.finalPoint());
        return landedRegion >= 0 && (targetRegion < 0 || graph.canReach(landedRegion, targetRegion));
    }
}
