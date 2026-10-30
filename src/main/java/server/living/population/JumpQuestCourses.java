package server.living.population;

import server.agents.capabilities.behavior.AgentJumpNoise;
import server.agents.capabilities.movement.AgentGroundingService;
import server.agents.capabilities.movement.AgentJumpActionService;
import server.agents.capabilities.movement.AgentMovementProfile;
import server.agents.capabilities.movement.AgentPostLandingJump;
import server.agents.capabilities.navigation.AgentNavigationGraph;
import server.agents.capabilities.navigation.AgentNavigationGraphService;
import server.agents.capabilities.navigation.AgentNavigationPathService;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The jump quests citizens run (docs/BOT_CONCEPT_PLAN.md F5b, decision D11): Pet-Walking Road and the two courses of the
 * Forest of Patience. Each course is legs from a start to a top on one map, left by a portal (the next map, or a hidden
 * one up the same map), the reward pile's NPC (it gives the prize and the quest item, then warps to Ellinia) or, on
 * Pet-Walking Road, nothing: Frod pays only someone with a pet out, which no bot has, so citizens walk it for fun. A leg
 * is cut into stages of {@link #STAGE_JUMPS} jumps along the navigation graph's path; the stages give the checkpoints a
 * citizen climbs to and the jump windows its noise is tuned on. JumpQuestRoutesTest proves every leg start-to-top.
 */
public final class JumpQuestCourses {
    /** Jumps in one stage: a rest platform about every seven jumps. */
    public static final int STAGE_JUMPS = 7;
    /** The take-off height of a stage with no jumps (a walk to the top): far from any map, so stageAt never picks it. */
    static final int NO_HEIGHT = 1_000_000;

    /** How a leg ends at its top. */
    public enum Exit {
        /** Nothing to do up there (Pet-Walking Road's top). */
        STAND,
        /** Through this portal: the next leg's map, or the next leg up the same map. */
        PORTAL,
        /** Talk to this NPC (a reward pile: prize, quest item, warp to Ellinia). */
        NPC
    }

    public record Leg(int mapId, Point start, Point top, Exit exit, String portal, int npcId) {
    }

    /**
     * A course: its level band, where a citizen goes for it (the road itself, or Ellinia for Shane's gate and his fee),
     * the quest Sabitrama gives for it (0 for none) and the item its pile gives for that quest, its legs, and the NPC at
     * every map's foot who sends a quitter back to Ellinia (0: the road has walkable ways out).
     */
    public record Course(String key, String spoken, int levelMin, int levelMax, int entryMapId, int gateNpcId, int fee,
                  int questId, int questItemId, int questLevel, List<Leg> legs, int exitNpcId) {
        public boolean fits(int level) {
            return level >= levelMin && level <= levelMax;
        }

        /** The leg on {@code mapId} at or after {@code from}, or -1. */
        public int legOn(int mapId, int from) {
            for (int i = Math.max(0, from); i < legs.size(); i++) {
                if (legs.get(i).mapId() == mapId) {
                    return i;
                }
            }
            return -1;
        }

        public boolean onCourse(int mapId) {
            return legOn(mapId, 0) >= 0;
        }
    }

    /**
     * One leg cut into stages: the checkpoint each stage climbs to, its jumps (graph window and real landing window, for
     * the noise's calibration), the heights its jumps take off from and each jump's take-off point (the edge's start).
     */
    public record Stages(List<Point> checkpoints, List<JumpWindows.Jump[]> jumps, List<int[]> heights,
                         List<Point[]> launches) {
        /** Stages known by height alone. */
        public Stages(List<Point> checkpoints, List<JumpWindows.Jump[]> jumps, List<int[]> heights) {
            this(checkpoints, jumps, heights, List.of());
        }

        public int count() {
            return checkpoints.size();
        }

        /**
         * The stage a jump taking off at {@code launch} belongs to: the stage that has that jump, else by height. Two
         * stages' heights can overlap (Pet-Walking Road's zig-zags): by height alone 100000202's r38->r33, stage 4's
         * first jump, took stage 3's spread, and a 30% climber cleared stage 4 at 0.70 where its calibration said 0.60.
         */
        public int stageAt(Point launch) {
            for (int s = 0; s < launches.size(); s++) {
                for (Point start : launches.get(s)) {
                    if (start.equals(launch)) {
                        return s;
                    }
                }
            }
            return stageAt(launch.y);
        }

        /** The stage a jump taking off at {@code launchY} belongs to: the one whose jumps take off nearest that height. */
        public int stageAt(int launchY) {
            int best = 0;
            int bestGap = Integer.MAX_VALUE;
            for (int s = 0; s < heights.size(); s++) {
                int[] range = heights.get(s);
                int gap = launchY < range[0] ? range[0] - launchY : launchY > range[1] ? launchY - range[1] : 0;
                if (gap < bestGap) {
                    best = s;
                    bestGap = gap;
                }
            }
            return best;
        }
    }

    /** Pet Park's hidden portal in01 leads up here to out00; Bartos waits at the foot, Frod at the top (no reward). */
    static final Course PWR = new Course("pwr", "pet walking road", 1, 200, 100000202, 0, 0, 0, 0, 0, List.of(
            new Leg(100000202, new Point(-2108, 236), new Point(-1593, -1586), Exit.STAND, "h005", 0)), 0);
    /** Shane's gate for lv25-49 (or quest 2050 started): 101000100, then 101000101 up to the pile 1043000. */
    static final Course FOP_A = new Course("fop_a", "forest of patience", 25, 49, 101000000, 1032003, 5000, 2050, 4031020,
            25, List.of(
            new Leg(101000100, new Point(-94, 938), new Point(94, -3019), Exit.PORTAL, "in00", 0),
            new Leg(101000101, new Point(-166, 398), new Point(-386, -3536), Exit.NPC, null, 1043000)), 1032004);
    /**
     * For lv50+ (or 2051 started): 101000102 up to its hidden portal h001 and on from h001_1, 101000103 (Super Jr.
     * Neckis on its platforms), 101000104 by its hidden portals h001 and h002 up to the pile 1043001.
     */
    static final Course FOP_B = new Course("fop_b", "forest of patience", 50, 200, 101000000, 1032003, 5000, 2051, 4031032,
            50, List.of(
            new Leg(101000102, new Point(-526, 137), new Point(-451, -1165), Exit.PORTAL, "h001", 0),
            new Leg(101000102, new Point(-376, -1589), new Point(265, -2406), Exit.PORTAL, "in00", 0),
            new Leg(101000103, new Point(371, 218), new Point(-452, -1509), Exit.PORTAL, "in00", 0),
            new Leg(101000104, new Point(-86, 338), new Point(124, 288), Exit.PORTAL, "h001", 0),
            new Leg(101000104, new Point(127, -113), new Point(-380, -525), Exit.PORTAL, "h002", 0),
            new Leg(101000104, new Point(-387, -925), new Point(139, -2962), Exit.NPC, null, 1043001)), 1032004);
    public static final List<Course> SHIPPED = List.of(PWR, FOP_A, FOP_B);
    /** Sabitrama, in front of the Sleepywood hotel: gives and takes quests 2050 and 2051. */
    static final int SABITRAMA = 1061005;
    static final int SLEEPYWOOD = 105040300;
    static final Point SABITRAMA_SPOT = new Point(765, -378);
    static final int ELLINIA = 101000000;

    private static final Map<String, Stages> STAGES = new ConcurrentHashMap<>();

    private JumpQuestCourses() {
    }

    static Course byKey(String key) {
        for (Course course : SHIPPED) {
            if (course.key().equalsIgnoreCase(key)) {
                return course;
            }
        }
        return null;
    }

    /** The course whose legs include {@code mapId}, or null. */
    static Course onMap(int mapId) {
        for (Course course : SHIPPED) {
            if (course.onCourse(mapId)) {
                return course;
            }
        }
        return null;
    }

    /** The leg's stages on {@code map} (its geometry), cut once and kept: the graph does not change while it runs. */
    static Stages stages(Course course, int legIndex, MapleMap map) {
        Leg leg = course.legs().get(legIndex);
        return STAGES.computeIfAbsent(course.key() + ":" + legIndex, key -> cut(leg, map));
    }

    /** One stage's jumps along the leg's path (platform jumps, not rope ones) and the checkpoint it climbs to. */
    public record JumpGroup(List<AgentNavigationGraph.Edge> jumps, Point checkpoint) {
    }

    /**
     * The leg's path in stages: a stage ends after STAGE_JUMPS jumps, or at a rope's top once it has a jump (a rest on
     * the small platform up there, as players stop; a jump straight off the top was never made: course B's first map
     * looped on its rope). The last stage ends at the top; with no jumps left after the last rest it is a walk there.
     * A path the graph cannot find is one stage to the top.
     */
    public static List<JumpGroup> groups(Leg leg, MapleMap map) {
        AgentNavigationGraph graph = map == null ? null : AgentNavigationGraphService.getGraph(map, AgentMovementProfile.base());
        Point start = map == null ? null : ground(map, leg.start());
        Point top = map == null ? leg.top() : ground(map, leg.top());
        List<AgentNavigationGraph.Edge> path = null;
        if (graph != null && start != null) {
            int from = graph.findRegionId(map, start);
            int to = graph.findRegionId(map, top);
            path = from < 0 || to < 0 ? null : AgentNavigationPathService.findPath(graph, map, start, from, to, top);
        }
        List<JumpGroup> out = new ArrayList<>();
        List<AgentNavigationGraph.Edge> stage = new ArrayList<>();
        for (AgentNavigationGraph.Edge edge : path == null ? List.<AgentNavigationGraph.Edge>of() : path) {
            AgentNavigationGraph.Region region = graph.getRegion(edge.fromRegionId);
            if (region == null) {
                continue;
            }
            AgentNavigationGraph.Region onto = graph.getRegion(edge.toRegionId);
            if (edge.type == AgentNavigationGraph.EdgeType.CLIMB && region.isRopeRegion && onto != null
                    && !onto.isRopeRegion && !stage.isEmpty()) {
                out.add(new JumpGroup(List.copyOf(stage), ground(map, edge.endPoint))); // a rest at the rope's top
                stage.clear();
                continue;
            }
            if (edge.type != AgentNavigationGraph.EdgeType.JUMP || region.isRopeRegion) {
                continue;
            }
            stage.add(edge);
            if (stage.size() == STAGE_JUMPS) {
                out.add(new JumpGroup(List.copyOf(stage), ground(map, edge.endPoint)));
                stage.clear();
            }
        }
        if (!stage.isEmpty() || out.isEmpty() || !out.get(out.size() - 1).checkpoint().equals(top)) {
            out.add(new JumpGroup(List.copyOf(stage), top));
        }
        return out;
    }

    /** The leg's stages (JumpQuestCourses.groups) with each jump's real landing window measured by the jump probe. */
    public static Stages cut(Leg leg, MapleMap map) {
        AgentNavigationGraph graph = map == null ? null : AgentNavigationGraphService.getGraph(map, AgentMovementProfile.base());
        List<Point> checkpoints = new ArrayList<>();
        List<JumpWindows.Jump[]> jumps = new ArrayList<>();
        List<int[]> heights = new ArrayList<>();
        List<Point[]> launches = new ArrayList<>();
        for (JumpGroup group : groups(leg, map)) {
            checkpoints.add(group.checkpoint());
            JumpWindows.Jump[] windows = new JumpWindows.Jump[group.jumps().size()];
            Point[] starts = new Point[windows.length];
            int low = NO_HEIGHT;
            int high = NO_HEIGHT;
            for (int i = 0; i < windows.length; i++) {
                AgentNavigationGraph.Edge edge = group.jumps().get(i);
                windows[i] = jump(graph, map, graph.getRegion(edge.fromRegionId), edge, group.checkpoint());
                starts[i] = new Point(edge.startPoint);
                low = i == 0 ? edge.startPoint.y : Math.min(low, edge.startPoint.y);
                high = i == 0 ? edge.startPoint.y : Math.max(high, edge.startPoint.y);
            }
            jumps.add(windows);
            heights.add(new int[]{low, high}); // a walk-only stage takes off nowhere: stageAt never picks it
            launches.add(starts);
        }
        return new Stages(List.copyOf(checkpoints), List.copyOf(jumps), List.copyOf(heights), List.copyOf(launches));
    }

    /**
     * A jump's windows: where navigation picks its launch (the graph window on its platform), where a press can go (the
     * platform less the noise's edge margin), and for every press within MAX_OFFSET_PX of the picks whether the jump
     * probe lands it on the target platform and keeps it there, and whether the noise would keep that press for a
     * citizen climbing to {@code checkpoint} (AgentJumpNoise.safePress over its readiness band). Every pixel: a landing
     * run can have a one-pixel hole (100000202's r105->r93 lands from -1743..-1681 and -1679..-1648, not -1680), and
     * a run cut at the hole made the stage look harder than it is.
     */
    static JumpWindows.Jump jump(AgentNavigationGraph graph, MapleMap map, AgentNavigationGraph.Region region,
                                 AgentNavigationGraph.Edge edge, Point checkpoint) {
        int pickLow = Math.max(edge.launchMinX, region.minX);
        int pickHigh = Math.min(edge.launchMaxX, region.maxX);
        if (pickLow > pickHigh) {
            pickLow = edge.launchMinX;
            pickHigh = edge.launchMaxX;
        }
        AgentMovementProfile profile = AgentMovementProfile.base();
        int stepX = AgentJumpActionService.resolveAirVelocityX(map, profile, edge.launchStepX);
        int edgeMargin = region.maxX - region.minX > 2 * AgentJumpNoise.EDGE_MARGIN_PX ? AgentJumpNoise.EDGE_MARGIN_PX : 0;
        int first = Math.min(pickLow, Math.max(region.minX, pickLow - AgentJumpNoise.MAX_OFFSET_PX));
        int last = Math.max(pickHigh, Math.min(region.maxX, pickHigh + AgentJumpNoise.MAX_OFFSET_PX));
        int tolerance = AgentJumpNoise.launchTolerance(map, profile);
        int targetRegion = checkpoint == null ? -1 : graph.findRegionId(map, checkpoint);
        boolean[] lands = new boolean[last - first + 1];
        boolean[] pixelSafe = new boolean[last - first + 1 + 2 * tolerance];
        boolean picksLand = false;
        for (int x = first - tolerance; x <= last + tolerance; x++) {
            Point origin = region.pointAt(Math.max(region.minX, Math.min(region.maxX, x)));
            AgentPostLandingJump landing = origin == null ? null : AgentJumpNoise.probe(map, origin, stepX, profile);
            pixelSafe[x - first + tolerance] = origin != null
                    && AgentJumpNoise.safeLanding(map, graph, origin, landing, targetRegion);
            if (x >= first && x <= last) {
                lands[x - first] = landing != null && landing.landing() != null && landing.landing().foothold() != null
                        && !landing.lostGround() && graph.findRegionId(map, landing.finalPoint()) == edge.toRegionId;
                picksLand |= lands[x - first] && x >= pickLow && x <= pickHigh;
            }
        }
        if (!picksLand) {
            for (int x = pickLow; x <= pickHigh; x++) {
                lands[x - first] = true; // the probe disagrees with every pick: the graph's window, as navigation trusts it
            }
        }
        boolean[] safe = new boolean[lands.length];
        for (int x = first; x <= last; x++) {
            boolean all = true;
            for (int at = x - tolerance; at <= x + tolerance && all; at++) {
                all = pixelSafe[at - first + tolerance];
            }
            safe[x - first] = all;
        }
        return new JumpWindows.Jump(pickLow, pickHigh, region.minX + edgeMargin, region.maxX - edgeMargin, first, lands,
                safe);
    }

    private static Point ground(MapleMap map, Point at) {
        Point ground = AgentGroundingService.findGroundPoint(map, new Point(at.x, at.y - 5));
        return ground == null ? new Point(at) : ground;
    }
}
