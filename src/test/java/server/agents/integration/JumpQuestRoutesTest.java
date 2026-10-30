package server.agents.integration;

import client.Character;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import server.agents.capabilities.behavior.AgentJumpNoise;
import server.agents.capabilities.combat.AgentFallDamageCalculator;
import server.agents.capabilities.movement.AgentGroundingService;
import server.agents.capabilities.movement.AgentJumpActionService;
import server.agents.capabilities.movement.AgentJumpProbeService;
import server.agents.capabilities.movement.AgentMoveTargetStateRuntime;
import server.agents.capabilities.movement.AgentMovementPhysicsStateRuntime;
import server.agents.capabilities.movement.AgentMovementProfile;
import server.agents.capabilities.movement.AgentMovementStateRuntime;
import server.agents.capabilities.movement.AgentPostLandingJump;
import server.agents.capabilities.navigation.AgentNavigationGraph;
import server.agents.capabilities.navigation.AgentNavigationGraphService;
import server.agents.capabilities.navigation.AgentNavigationMapLoader;
import server.agents.runtime.AgentRuntimeEntry;
import server.living.population.JumpQuestCourses;
import server.living.population.JumpQuestCourses.Course;
import server.living.population.JumpQuestCourses.Leg;
import server.living.population.JumpQuestCourses.Stages;
import server.living.population.JumpWindows;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The jump quests citizens climb (docs/BOT_CONCEPT_PLAN.md F5b, JumpQuestCourses) on the real maps and nav graphs: every
 * leg start to top through its stage checkpoints with exact jumps; noisy climbs whose misses land on lower footholds
 * and climb on, never below the map; each stage's measured chance near the citizen's skill; and the courses' NPCs and
 * portals pinned to the WZ data.
 */
class JumpQuestRoutesTest {
    /** At a checkpoint: as CitizenJumpQuests.REACH_PX, and on its platform. */
    private static final int REACH_PX = 45;
    /** A move this far in one 50 ms tick is a teleport (a fall moves about 35 px a tick at most). */
    private static final int TELEPORT_PX = 150;

    /**
     * Every leg but course B's first: on 101000102 the bot climbs the first rope and stops on its top, then loses the
     * zig-zag (a jump down off each rope's top to the next rope) and loops back on the start's rope. Course B is left
     * out of JQ_COURSES until it climbs (the rest of B is proven here).
     */
    static Stream<Arguments> legs() {
        List<Arguments> out = new ArrayList<>();
        for (Course course : JumpQuestCourses.SHIPPED) {
            for (int leg = 0; leg < course.legs().size(); leg++) {
                if (!(course.key().equals("fop_b") && leg == 0)) {
                    out.add(Arguments.of(course.key(), leg));
                }
            }
        }
        return out.stream();
    }

    @Test
    void courseBStaysOutOfTheDefaultCoursesUntilItsFirstMapIsClimbed() {
        assertEquals("pwr,fop_a", new config.PopulationConfig().JQ_COURSES);
    }

    @ParameterizedTest(name = "{0} leg {1}")
    @MethodSource("legs")
    void everyLegIsClimbedStartToTopWithExactJumps(String key, int legIndex) {
        Leg leg = leg(key, legIndex);
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(leg.mapId());
        Stages stages = JumpQuestCourses.cut(leg, map);
        assertTrue(stages.count() >= 1);
        Climb climb = climb(map, leg, stages, null, 0L, 4_000);
        assertEquals(-1, climb.stalledAt, key + " leg " + legIndex + " stalled at stage " + climb.stalledAt + ": "
                + String.join("\n", climb.trace));
        assertEquals(0, climb.teleports, "no teleport on the way up");
        Point top = ground(map, leg.top());
        assertEquals(top, stages.checkpoints().get(stages.count() - 1), "the last checkpoint is the leg's top");
    }

    /**
     * A clumsy citizen (30%) for six minutes of game time: it misses, every miss comes down on a lower foothold of the
     * map, never below it, never teleported, never far enough to hurt, and it never stands still two minutes (the
     * user's no-long-idle rule). In the activity it gives up after JQ_FALLS_MAX.
     */
    @ParameterizedTest(name = "{0} leg {1}")
    @MethodSource("noisyLegs")
    void clumsyClimbsFallToLowerFootholdsNeverBelowTheMapAndNeverStandStill(String key, int legIndex) {
        Leg leg = leg(key, legIndex);
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(leg.mapId());
        Stages stages = JumpQuestCourses.cut(leg, map);
        AtomicInteger belowMap = new AtomicInteger();
        Climb climb = climb(map, leg, stages, plan(stages, 0.3d, belowMap), 41L + legIndex, 9_000, 7_200);
        assertTrue(climb.misses >= 1, "a clumsy climb misses some jumps: " + climb.misses + " of " + climb.jumps);
        assertEquals(climb.misses, climb.lowerLandings, "every miss came down on a lower foothold of the map");
        assertEquals(0, belowMap.get(), "no fall below the map");
        assertEquals(0, climb.teleports, "no recovery teleport");
        assertTrue(climb.longestFall < AgentFallDamageCalculator.FALL_DIST_THRESHOLD_PX, "fell " + climb.longestFall);
        assertTrue(climb.longestStillTicks < 2_400, "stood still " + climb.longestStillTicks * 50 / 1000 + " s: "
                + String.join("\n", climb.trace));
    }

    /** A good climber (70%) misses now and then and still makes the top, the same guarantees on the way. */
    @ParameterizedTest(name = "{0} leg {1}")
    @MethodSource("noisyLegs")
    void skilledClimbsMissNowAndThenAndMakeTheTop(String key, int legIndex) {
        Leg leg = leg(key, legIndex);
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(leg.mapId());
        Stages stages = JumpQuestCourses.cut(leg, map);
        AtomicInteger belowMap = new AtomicInteger();
        Climb climb = climb(map, leg, stages, plan(stages, 0.7d, belowMap), 43L + legIndex, 9_000, Integer.MAX_VALUE);
        assertEquals(-1, climb.stalledAt, key + " leg " + legIndex + " stalled at stage " + climb.stalledAt + " after "
                + climb.misses + " misses: " + String.join("\n", climb.trace));
        assertEquals(climb.misses, climb.lowerLandings, "every miss came down on a lower foothold of the map");
        assertEquals(0, belowMap.get(), "no fall below the map");
        assertEquals(0, climb.teleports, "no recovery teleport");
        assertTrue(climb.longestFall < AgentFallDamageCalculator.FALL_DIST_THRESHOLD_PX, "fell " + climb.longestFall);
    }

    /**
     * Seed 15's skilled climb of fop_a's second map once missed off the r60->r59 jump into the void left of the map
     * (1,700 px down, past the fall-damage distance) and the out-of-bounds recovery put it on its checkpoint in one
     * tick. Now its misses come down on footholds like the rest.
     */
    @Test
    void theSkilledClimbThatOnceFellOffTheMapMissesOntoItsFootholds() {
        Leg leg = leg("fop_a", 1);
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(leg.mapId());
        Stages stages = JumpQuestCourses.cut(leg, map);
        AtomicInteger belowMap = new AtomicInteger();
        Climb climb = climb(map, leg, stages, plan(stages, 0.7d, belowMap), 15L, 9_000, Integer.MAX_VALUE);
        assertEquals(-1, climb.stalledAt, "stalled at stage " + climb.stalledAt + ": " + String.join("\n", climb.trace));
        assertTrue(climb.misses >= 1, "it still misses: " + climb.misses + " of " + climb.jumps);
        assertEquals(climb.misses, climb.lowerLandings, "every miss came down on a lower foothold of the map");
        assertEquals(0, belowMap.get(), "no fall below the map");
        assertEquals(0, climb.teleports, "no recovery teleport");
        assertTrue(climb.longestFall < AgentFallDamageCalculator.FALL_DIST_THRESHOLD_PX, "fell " + climb.longestFall);
    }

    /**
     * A drawn press is taken anywhere its readiness accepts (launchTolerance either side), so every launch there must
     * land on the map: r60->r59 on 101000101 has its window end a pixel from the void (-548 lands on r59, -547 falls off
     * the map's left edge), which a probe every 3 px stepped over. Each draw the noise keeps there, over many seeds.
     */
    @Test
    void everyLaunchAroundAKeptDrawLandsOnTheMap() {
        Leg leg = leg("fop_a", 1);
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(leg.mapId());
        Stages stages = JumpQuestCourses.cut(leg, map);
        AgentMovementProfile profile = AgentMovementProfile.base();
        AgentNavigationGraph graph = AgentNavigationGraphService.getGraph(map, profile);
        AgentNavigationGraph.Region from = graph.getRegion(graph.findRegionId(map, new Point(-500, -1162)));
        int to = graph.findRegionId(map, new Point(-620, -1222));
        AgentNavigationGraph.Edge edge = graph.getOutgoing(from.id).stream()
                .filter(e -> e.toRegionId == to && e.type == AgentNavigationGraph.EdgeType.JUMP)
                .findFirst().orElseThrow();
        assertEquals(new Point(-558, -1162), edge.startPoint, "the jump seed 15 missed");
        BotMovementSimulationLab lab = BotMovementSimulationLab.fromMap(map);
        AgentRuntimeEntry entry = lab.spawnBot("JQ", 77, map, from.pointAt(-500));
        lab.setMoveTarget("JQ", new Point(-563, -1522), true); // the stage's checkpoint, as in that climb
        int stepX = AgentJumpActionService.resolveAirVelocityX(map, profile, edge.launchStepX);
        int tolerance = AgentJumpNoise.launchTolerance(map, AgentMovementStateRuntime.movementProfile(entry));
        assertTrue(tolerance >= 3, "a walk step's half: " + tolerance);
        int kept = 0;
        for (long seed = 0; seed < 300; seed++) {
            AgentJumpNoise.install(entry, plan(stages, 0.7d, new AtomicInteger()), seed);
            int x = AgentJumpNoise.launchX(entry, graph, edge, edge.startPoint.x);
            if (x == edge.startPoint.x) {
                continue; // navigation's own pick: its window, as always
            }
            kept++;
            AgentMovementPhysicsStateRuntime.setHorizontalSpeed(entry, -3.0d); // walking: it presses on the draw
            assertEquals(Boolean.TRUE, AgentJumpNoise.readyAtDraw(entry, edge, new Point(x + 1, -1162)));
            assertEquals(Boolean.FALSE, AgentJumpNoise.readyAtDraw(entry, edge, new Point(x + 2, -1162)));
            AgentMovementPhysicsStateRuntime.setHorizontalSpeed(entry, 0.0d); // at rest: anywhere in the band
            assertEquals(Boolean.FALSE, AgentJumpNoise.readyAtDraw(entry, edge, new Point(x - tolerance - 1, -1162)));
            assertEquals(Boolean.FALSE, AgentJumpNoise.readyAtDraw(entry, edge, new Point(x + tolerance + 1, -1162)));
            for (int at = x - tolerance; at <= x + tolerance; at++) {
                assertEquals(Boolean.TRUE, AgentJumpNoise.readyAtDraw(entry, edge, new Point(at, -1162)), "ready at " + at);
                Point origin = from.pointAt(Math.max(from.minX, Math.min(from.maxX, at)));
                AgentPostLandingJump landing = AgentJumpProbeService.simulateJumpLandingWithPostLandingTicks(map, origin,
                        stepX, profile, 3);
                assertTrue(landing != null && landing.landing() != null && !landing.lostGround()
                                && graph.findRegionId(map, landing.finalPoint()) >= 0,
                        "seed " + seed + ": draw " + x + ", a launch at " + at + " falls off the map");
            }
        }
        assertTrue(kept >= 100, "draws kept off navigation's pick: " + kept);
    }

    /** The activity's noise for a citizen of {@code skill}: each stage's spread for its scaled chance. */
    private static AgentJumpNoise.Plan plan(Stages stages, double skill, AtomicInteger belowMap) {
        double[] sigmas = new double[stages.count()];
        for (int s = 0; s < sigmas.length; s++) {
            JumpWindows.Jump[] jumps = stages.jumps().get(s);
            sigmas[s] = JumpWindows.sigmaFor(jumps, JumpWindows.targetFor(skill, jumps.length));
        }
        return new AgentJumpNoise.Plan() {
            @Override
            public double sigmaAt(int launchY) {
                return sigmas[stages.stageAt(launchY)];
            }

            @Override
            public double sigmaAt(Point launch) {
                return sigmas[stages.stageAt(launch)];
            }

            @Override
            public boolean belowMap(Character agent) {
                belowMap.incrementAndGet();
                return false;
            }
        };
    }

    static Stream<Arguments> noisyLegs() {
        return Stream.of(Arguments.of("pwr", 0), Arguments.of("fop_a", 0), Arguments.of("fop_a", 1));
    }

    /**
     * Each stage's chance through the noise's own draws (AgentJumpNoise.launchX: navigation's seeded pick, the draw kept
     * on the platform and never past the pick, a press its probe refuses drawn again) and the probe's landings is the
     * calibration's (JumpWindows), stage by stage, on the courses citizens climb: so a 30% citizen clears about 30% of
     * seven-jump stretches and a 70% one about 70%, or what the stage allows at most. On Pet-Walking Road they once
     * disagreed: 0.98 where 0.70 was asked (a one-pixel hole cut a landing run short), 0.00 where 0.78 was (a window in
     * its platform's last pixels was clamped off itself), and refused misses let a 30% climber miss one jump in fourteen.
     */
    @ParameterizedTest(name = "{0} leg {1}")
    @MethodSource("noisyLegs")
    void eachStagesChanceThroughTheNoisesOwnDrawsIsTheCalibrations(String key, int legIndex) {
        Leg leg = leg(key, legIndex);
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(leg.mapId());
        Stages stages = JumpQuestCourses.cut(leg, map);
        List<JumpQuestCourses.JumpGroup> groups = JumpQuestCourses.groups(leg, map);
        assertEquals(stages.count(), groups.size());
        AgentNavigationGraph graph = AgentNavigationGraphService.getGraph(map, AgentMovementProfile.base());
        BotMovementSimulationLab lab = BotMovementSimulationLab.fromMap(map);
        AgentRuntimeEntry entry = lab.spawnBot("JQ", 77, map, ground(map, leg.start()));
        List<String> report = new ArrayList<>();
        int worst = 0;
        for (double skill : new double[]{0.3d, 0.7d}) {
            AgentJumpNoise.Plan plan = plan(stages, skill, new AtomicInteger());
            for (int s = 0; s < stages.count(); s++) {
                JumpWindows.Jump[] jumps = stages.jumps().get(s);
                if (jumps.length == 0) {
                    continue; // a walk to the top
                }
                double target = JumpWindows.targetFor(skill, jumps.length);
                double calibrated = JumpWindows.stageSuccess(jumps, JumpWindows.sigmaFor(jumps, target));
                lab.setMoveTarget("JQ", stages.checkpoints().get(s), true); // the stage's checkpoint, as in a climb
                double drawn = 1.0d;
                for (AgentNavigationGraph.Edge edge : groups.get(s).jumps()) {
                    drawn *= drawnChance(map, graph, entry, plan, edge, 200);
                }
                worst = Math.max(worst, (int) Math.round(Math.abs(drawn - calibrated) * 100));
                report.add(String.format("%s/%d/%d p=%.1f jumps=%d target=%.2f calibrated=%.2f drawn=%.2f", key, legIndex,
                        s, skill, jumps.length, target, calibrated, drawn));
            }
        }
        assertTrue(worst <= 10, "a stage's drawn chance is off its calibration by " + worst + " points:\n"
                + String.join("\n", report));
    }

    /** One jump's chance over {@code draws} attempts: navigation's pick, the noise's press, the probe's landing. */
    private static double drawnChance(MapleMap map, AgentNavigationGraph graph, AgentRuntimeEntry entry,
                                      AgentJumpNoise.Plan plan, AgentNavigationGraph.Edge edge, int draws) {
        AgentMovementProfile profile = AgentMovementProfile.base();
        AgentNavigationGraph.Region region = graph.getRegion(edge.fromRegionId);
        int min = Math.max(edge.launchMinX, region.minX);
        int max = Math.min(edge.launchMaxX, region.maxX);
        if (min > max) {
            min = edge.launchMinX;
            max = edge.launchMaxX;
        }
        int margin = Math.min((max - min) / 2, 12); // AgentNavigationWaypointService.selectJumpLaunchX: two walk steps
        int stepX = AgentJumpActionService.resolveAirVelocityX(map, profile, edge.launchStepX);
        int hits = 0;
        for (int i = 0; i < draws; i++) {
            AgentJumpNoise.install(entry, plan, 7_919L * i + edge.fromRegionId);
            int pick = AgentJumpNoise.pickLaunchX(entry, min + margin, max - margin);
            int x = AgentJumpNoise.launchX(entry, graph, edge, pick);
            AgentPostLandingJump landing = AgentJumpNoise.probe(map,
                    region.pointAt(Math.max(region.minX, Math.min(region.maxX, x))), stepX, profile);
            if (landing != null && landing.landing() != null && landing.landing().foothold() != null
                    && !landing.lostGround() && graph.findRegionId(map, landing.finalPoint()) == edge.toRegionId) {
                hits++;
            }
        }
        return hits / (double) draws;
    }

    @Test
    void theCoursesNpcsAndPortalsAreWhereTheWzSays() throws IOException {
        assertNpc(100000202, 1012006); // Bartos at the foot
        assertNpc(100000202, 1012007); // Frod at the top
        assertNpc(101000000, 1032003); // Shane's gate in Ellinia
        assertNpc(105040300, 1061005); // Sabitrama in Sleepywood: quests 2050 and 2051
        for (Course course : JumpQuestCourses.SHIPPED) {
            for (int index = 0; index < course.legs().size(); index++) {
                Leg leg = course.legs().get(index);
                if (course.exitNpcId() > 0) {
                    assertNpc(leg.mapId(), course.exitNpcId()); // every Forest map has its way back to Ellinia
                }
                if (leg.exit() == JumpQuestCourses.Exit.NPC) {
                    assertNpc(leg.mapId(), leg.npcId());
                }
                if (leg.portal() == null) {
                    continue;
                }
                MapleMap map = AgentNavigationMapLoader.loadMapGeometry(leg.mapId());
                Portal portal = map.getPortal(leg.portal());
                assertNotNull(portal, course.key() + " leg " + index + ": no portal " + leg.portal() + " on " + leg.mapId());
                if (leg.exit() == JumpQuestCourses.Exit.PORTAL) {
                    Leg next = course.legs().get(index + 1);
                    int target = portal.getTargetMapId() == 999999999 ? leg.mapId() : portal.getTargetMapId();
                    assertEquals(next.mapId(), target, course.key() + " leg " + index + " portal " + leg.portal());
                    MapleMap nextMap = next.mapId() == leg.mapId() ? map : AgentNavigationMapLoader.loadMapGeometry(next.mapId());
                    Portal arrival = nextMap.getPortal(portal.getTarget());
                    assertNotNull(arrival, "arrival portal " + portal.getTarget() + " on " + next.mapId());
                    assertTrue(arrival.getPosition().distance(next.start()) <= 150,
                            "the next leg starts where its portal lands: " + arrival.getPosition() + " vs " + next.start());
                }
            }
        }
    }

    private static void assertNpc(int mapId, int npcId) throws IOException {
        Path xml = Path.of("wz", "Map.wz", "Map", "Map" + (mapId / 100_000_000), mapId + ".img.xml");
        String text = Files.readString(xml);
        assertTrue(text.contains("name=\"id\" value=\"" + npcId + "\""), "NPC " + npcId + " on map " + mapId);
    }

    private static Leg leg(String key, int index) {
        for (Course course : JumpQuestCourses.SHIPPED) {
            if (course.key().equals(key)) {
                return course.legs().get(index);
            }
        }
        throw new IllegalArgumentException(key);
    }

    /** What one climb did. */
    private static final class Climb {
        int stalledAt = -1;
        int teleports;
        int misses;
        int jumps;
        int lowerLandings;
        /** The longest run of ticks it stayed within 8 px of one spot. */
        int longestStillTicks;
        /** The deepest it got below an airborne spell's highest point, landed or not (fall damage counts from there). */
        int longestFall;
        List<String> trace = List.of();
    }

    /** A bot from the leg's start through its checkpoints (re-targeted after a fall drops its move), noise or none. */
    private static Climb climb(MapleMap map, Leg leg, Stages stages, AgentJumpNoise.Plan plan, long seed, int ticksPerStage) {
        return climb(map, leg, stages, plan, seed, ticksPerStage, Integer.MAX_VALUE);
    }

    /** As above, stopping after {@code totalTicks} in all (not a stall: a clumsy climb may take that long). */
    private static Climb climb(MapleMap map, Leg leg, Stages stages, AgentJumpNoise.Plan plan, long seed, int ticksPerStage,
                               int totalTicks) {
        AgentNavigationGraph graph = AgentNavigationGraphService.getGraph(map, AgentMovementProfile.base());
        BotMovementSimulationLab lab = BotMovementSimulationLab.fromMap(map);
        AgentRuntimeEntry entry = lab.spawnBot("JQ", 77, map, ground(map, leg.start()));
        lab.primeMapState("JQ");
        lab.setAiAccumulator("JQ", 50);
        if (plan != null) {
            AgentJumpNoise.install(entry, plan, seed);
        }
        Climb climb = new Climb();
        Point last = lab.position("JQ");
        Point stillAt = last;
        int stillTicks = 0;
        int ticks = 0;
        int seenMisses = 0;
        int apexY = Integer.MAX_VALUE;
        for (int s = 0; s < stages.count() && ticks < totalTicks; s++) {
            Point checkpoint = stages.checkpoints().get(s);
            lab.setMoveTarget("JQ", checkpoint, true);
            boolean reached = false;
            for (int tick = 0; tick < ticksPerStage && !reached && ticks < totalTicks; tick++, ticks++) {
                lab.step(1);
                Point now = lab.position("JQ");
                if (now.distance(last) > TELEPORT_PX) {
                    climb.teleports++;
                }
                last = now;
                if (AgentMovementStateRuntime.inAir(entry)) {
                    apexY = Math.min(apexY, now.y);
                    climb.longestFall = Math.max(climb.longestFall, now.y - apexY);
                } else {
                    apexY = Integer.MAX_VALUE;
                }
                if (now.distance(stillAt) > 8) {
                    stillAt = now;
                    stillTicks = 0;
                } else {
                    climb.longestStillTicks = Math.max(climb.longestStillTicks, ++stillTicks);
                }
                int misses = AgentJumpNoise.counts(entry).misses();
                if (misses > seenMisses) {
                    seenMisses = misses; // it just landed after a miss: on a foothold of the map, below its launch
                    climb.lowerLandings += graph.findRegionId(map, now) >= 0
                            && AgentGroundingService.findGroundPoint(map, new Point(now.x, now.y - 5)) != null ? 1 : 0;
                }
                reached = Math.abs(now.x - checkpoint.x) <= REACH_PX && Math.abs(now.y - checkpoint.y) <= 20;
                if (!reached && tick % 20 == 0 && !AgentMoveTargetStateRuntime.hasMoveTarget(entry)) {
                    lab.setMoveTarget("JQ", checkpoint, true);
                }
            }
            if (!reached && ticks < totalTicks) {
                climb.stalledAt = s;
                climb.trace = lab.formatRecentTrace("JQ", 30);
                break;
            }
        }
        AgentJumpNoise.Counts counts = AgentJumpNoise.counts(entry);
        climb.misses = counts.misses();
        climb.jumps = counts.jumps();
        return climb;
    }

    private static Point ground(MapleMap map, Point at) {
        Point ground = AgentGroundingService.findGroundPoint(map, new Point(at.x, at.y - 5));
        return ground == null ? new Point(at) : ground;
    }
}
