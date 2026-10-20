package server.agents.capabilities.navigation;

import client.Character;
import client.Skill;
import client.SkillFactory;
import constants.skills.FPWizard;
import constants.skills.Hermit;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.StatEffect;
import server.agents.capabilities.movement.AgentGroundingService;
import server.agents.capabilities.movement.AgentMovementProfile;
import server.agents.capabilities.movement.AgentMovementSkillConfig;
import server.maps.FieldLimit;
import server.maps.Foothold;
import server.maps.MapleMap;

import java.awt.Point;
import java.awt.Rectangle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * CWKPQ Step C0 offline nav probe (docs/CWKPQ_C0_RESULTS.md). Throwaway: not part of the suite. Copy into
 * src/test/java/server/agents/capabilities/navigation/ and run alone:
 * ./mvnw -o test -Dtest=CwkpqC0NavigationProbe -Dsurefire.failIfNoSpecifiedTests=false
 * It switches FLASH_JUMP_MODE and TELEPORT_MODE to ACTIVE in the in-memory tuning before the movement-skill config
 * loads, so skill edges are authored; a profile without the skill cannot use them. Writes target/cwkpq-c0-nav.txt.
 */
class CwkpqC0NavigationProbe {
    static {
        Map<String, String> tuning = config.AgentYamlConfig.config.tuning;
        tuning.put("server.agents.capabilities.movement.AgentMovementSkillConfig.FLASH_JUMP_MODE", "ACTIVE");
        tuning.put("server.agents.capabilities.movement.AgentMovementSkillConfig.TELEPORT_MODE", "ACTIVE");
    }

    private record Profile(String name, int speed, int jump, int skillId) {
    }

    private static final List<Profile> PROFILES = List.of(
            new Profile("base 100/100", 100, 100, 0),
            new Profile("gear 120/110", 120, 110, 0),
            new Profile("haste 140/120", 140, 120, 0),
            new Profile("flashjump 100/100", 100, 100, Hermit.FLASH_JUMP),
            new Profile("flashjump+haste 140/120", 140, 120, Hermit.FLASH_JUMP),
            new Profile("teleport 100/100", 100, 100, FPWizard.TELEPORT),
            new Profile("teleport+haste 140/120", 140, 120, FPWizard.TELEPORT));

    private final StringBuilder out = new StringBuilder();
    private final Map<String, AgentNavigationGraph> graphs = new LinkedHashMap<>();

    @Test
    void probe() throws IOException {
        assertTrue(AgentMovementSkillConfig.FLASH_JUMP_MODE.active());
        assertTrue(AgentMovementSkillConfig.TELEPORT_MODE.active());
        Skill skill = mock(Skill.class);
        StatEffect effect = mock(StatEffect.class);
        when(skill.getEffect(anyInt())).thenReturn(effect);
        when(effect.getMpCon()).thenReturn((short) 10);
        try (MockedStatic<SkillFactory> skills = mockStatic(SkillFactory.class)) {
            skills.when(() -> SkillFactory.getSkill(Hermit.FLASH_JUMP)).thenReturn(skill);
            skills.when(() -> SkillFactory.getSkill(FPWizard.TELEPORT)).thenReturn(skill);

            stageThree();
            stageFour();
            stageFive();
            warriorRoom();
            valley();
        }
        Path file = Path.of("target", "cwkpq-c0-nav.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, out);
        System.out.println(out);
    }

    private void stageThree() {
        int mapId = 610_030_300;
        Map<String, Point> targets = new LinkedHashMap<>();
        targets.put("sigil 6109000 (857,1431)", new Point(857, 1431));
        targets.put("sigil 6109001 (240,531)", new Point(240, 531));
        targets.put("sigil 6109002 (322,-789)", new Point(322, -789));
        targets.put("sigil 6109003 (982,-1209)", new Point(982, -1209));
        targets.put("sigil 6109004 (-480,-1269)", new Point(-480, -1269));
        targets.put("top portal next00 (215,-1107)", new Point(215, -1107));
        Map<String, Point> starts = new LinkedHashMap<>();
        starts.put("spawn sp (-483,1470)", new Point(-483, 1470));
        starts.put("bottom right (1300,1535)", new Point(1300, 1500));
        probeMap(mapId, starts, targets, true);
    }

    private void stageFour() {
        int mapId = 610_030_400;
        Map<String, Point> targets = new LinkedHashMap<>();
        targets.put("spot A (1181,-189) row0", new Point(1181, -189));
        targets.put("spot B (-799,-668) row1", new Point(-799, -668));
        targets.put("spot C (7,-969) row2", new Point(7, -969));
        targets.put("spot D (374,-969) row3", new Point(374, -969));
        targets.put("spot E (998,-847) row4", new Point(998, -847));
        targets.put("spot F (1175,-547) row5", new Point(1175, -547));
        targets.put("spot G (-804,52) row6", new Point(-804, 52));
        targets.put("spot H (1176,172) row7", new Point(1176, 172));
        targets.put("exit next00 (-433,635)", new Point(-433, 635));
        Map<String, Point> starts = new LinkedHashMap<>();
        starts.put("spawn sp (-841,584)", new Point(-841, 584));
        probeMap(mapId, starts, targets, false);
    }

    private void stageFive() {
        int mapId = 610_030_500;
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(mapId);
        map.generateMapDropRangeCache();
        line("");
        line("## " + mapId + " fieldLimit=0x" + Integer.toHexString(map.getFieldLimit()) + " relic drop spots");
        Map<String, Rectangle> relics = new LinkedHashMap<>();
        // Reactor.wz 61090xx state 0 lt/rb added to the map position (Reactor.getArea).
        relics.put("6109005 warrior 4001259 @(781,-777)", area(781, -777, 312, 112, 512, 323));
        relics.put("6109006 bowman 4001258 @(1249,-281)", area(1249, -281, 31, -172, 195, -35));
        relics.put("6109007 mage 4001257 @(794,-782)", area(794, -782, 1, -177, 200, -49));
        relics.put("6109008 thief 4001256 @(1332,-835)", area(1332, -835, 11, -192, 202, -25));
        relics.put("6109009 pirate 4001260 @(1297,-484)", area(1297, -484, -31, -123, 183, -22));
        Map<String, Point> starts = new LinkedHashMap<>();
        starts.put("spawn sp (1215,342)", new Point(1215, 342));
        starts.put("in01 warrior return (-25,153)", new Point(-25, 153));
        starts.put("in02 (-26,-327)", new Point(-26, -327));
        starts.put("in03 (-22,-809)", new Point(-22, -809));
        starts.put("in04 (2454,153)", new Point(2454, 153));
        starts.put("in05 (2456,-326)", new Point(2456, -326));
        for (Profile profile : List.of(PROFILES.get(0), PROFILES.get(2))) {
            AgentNavigationGraph graph = graph(map, profile);
            Character agent = agent(map, profile, 1);
            for (Map.Entry<String, Rectangle> relic : relics.entrySet()) {
                Rectangle area = relic.getValue();
                List<Point> spots = dropSpots(map, area);
                line(String.format("%s | %s | area x %d..%d y %d..%d | standing spots whose drop lands inside: %d %s",
                        profile.name, relic.getKey(), area.x, area.x + area.width, area.y, area.y + area.height,
                        spots.size(), summarize(spots)));
                for (Map.Entry<String, Point> start : starts.entrySet()) {
                    Point from = ground(map, start.getValue());
                    String best = "none";
                    for (Point spot : spots) {
                        AgentNavigationPathService.MovementPathSelection selection =
                                route(graph, map, agent, from, spot, edge -> true);
                        if (selection.completeness() == AgentNavigationPathService.RouteCompleteness.COMPLETE) {
                            best = "COMPLETE to " + xy(spot) + " landing " + xy(map.calcDropPos(new Point(spot), new Point(spot)))
                                    + " " + pathSummary(selection);
                            break;
                        }
                    }
                    line("    from " + start.getKey() + " ground " + xy(from) + ": " + best);
                }
            }
        }
    }

    private void warriorRoom() {
        int mapId = 610_030_510;
        Map<String, Point> targets = new LinkedHashMap<>();
        targets.put("master 9201107 (117,-2259)", new Point(117, -2259));
        Map<String, Point> starts = new LinkedHashMap<>();
        starts.put("spawn sp (0,0)", new Point(0, 0));
        probeMap(mapId, starts, targets, true);
    }

    private void valley() {
        Map<String, Point> targets = new LinkedHashMap<>();
        targets.put("CM1_E (1882,-1476)", new Point(1882, -1476));
        Map<String, Point> starts = new LinkedHashMap<>();
        starts.put("spawn sp (-1672,-32)", new Point(-1672, -32));
        starts.put("from Forgotten Path CM1_AA (-1810,83)", new Point(-1810, 83));
        probeMap(610_020_000, starts, targets, false);

        targets = new LinkedHashMap<>();
        targets.put("CM2_F (1784,-1655)", new Point(1784, -1655));
        starts = new LinkedHashMap<>();
        starts.put("arrival CM2_A (-2085,-817)", new Point(-2085, -817));
        starts.put("spawn sp (-2008,-943)", new Point(-2008, -943));
        probeMap(610_020_001, starts, targets, false);

        targets = new LinkedHashMap<>();
        targets.put("CM3_2 to Cavern of Pain (-1196,-938)", new Point(-1196, -938));
        starts = new LinkedHashMap<>();
        starts.put("arrival CM3_1 (164,24)", new Point(164, 24));
        probeMap(610_020_002, starts, targets, false);
    }

    private void probeMap(int mapId, Map<String, Point> starts, Map<String, Point> targets, boolean noDownJumpByWz) {
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(mapId);
        boolean cannotJumpDown = FieldLimit.CANNOTJUMPDOWN.check(map.getFieldLimit());
        boolean movementSkillsLimited = FieldLimit.MOVEMENTSKILLS.check(map.getFieldLimit());
        line("");
        line("## " + mapId + " fieldLimit=0x" + Integer.toHexString(map.getFieldLimit())
                + " CANNOTJUMPDOWN=" + cannotJumpDown + " MOVEMENTSKILLS=" + movementSkillsLimited
                + " (bots then move at base 100/100: AgentMovementProfile.fromCharacter)");
        // A CANNOTJUMPDOWN map forbids the down-jump (DROP with launchStepX 0); a walk-off drop stays allowed.
        Predicate<AgentNavigationGraph.Edge> allowed = cannotJumpDown
                ? edge -> !(edge.type == AgentNavigationGraph.EdgeType.DROP && edge.launchStepX == 0)
                : edge -> true;
        for (Profile profile : PROFILES) {
            AgentNavigationGraph graph = graph(map, profile);
            Character agent = agent(map, profile, 2);
            for (Map.Entry<String, Point> start : starts.entrySet()) {
                Point from = ground(map, start.getValue());
                int fromRegion = graph.findRegionId(map, from);
                int highest = highestReachableY(graph, fromRegion, allowed, profile, agent);
                line(String.format("%s | from %s ground %s region %d | highest reachable ground y=%d",
                        profile.name, start.getKey(), xy(from), fromRegion, highest));
                for (Map.Entry<String, Point> target : targets.entrySet()) {
                    Point to = ground(map, target.getValue());
                    AgentNavigationPathService.MovementPathSelection selection =
                            route(graph, map, agent, from, to, allowed);
                    String extra = "";
                    if (selection.completeness() != AgentNavigationPathService.RouteCompleteness.COMPLETE) {
                        AgentNavigationGraph.Region last = graph.getRegion(selection.outcome().finalRegionId());
                        extra = " closest region " + selection.outcome().finalRegionId()
                                + (last == null ? "" : " x " + last.minX + ".." + last.maxX + " y " + last.minY);
                    }
                    line(String.format("    -> %s ground %s (dy %d): %s %s%s", target.getKey(), xy(to),
                            to == null ? 0 : to.y - target.getValue().y, selection.completeness(),
                            pathSummary(selection), extra));
                }
            }
        }
    }

    private int highestReachableY(AgentNavigationGraph graph, int fromRegion,
                                  Predicate<AgentNavigationGraph.Edge> allowed, Profile profile, Character agent) {
        if (fromRegion < 0) return Integer.MAX_VALUE;
        Set<Integer> seen = new HashSet<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(fromRegion);
        seen.add(fromRegion);
        int best = Integer.MAX_VALUE;
        while (!queue.isEmpty()) {
            int id = queue.poll();
            AgentNavigationGraph.Region region = graph.getRegion(id);
            if (region != null && !region.isRopeRegion) best = Math.min(best, region.minY);
            for (AgentNavigationGraph.Edge edge : graph.getOutgoing(id)) {
                if (!allowed.test(edge)) continue;
                if (AgentMovementSkillPolicy.isSkillEdge(edge)
                        && !AgentMovementSkillPolicy.canUseActivePath(agent, edge)) continue;
                if (seen.add(edge.toRegionId)) queue.add(edge.toRegionId);
            }
        }
        return best;
    }

    private AgentNavigationGraph graph(MapleMap map, Profile profile) {
        String key = map.getId() + ":" + profile.speed + ":" + profile.jump;
        return graphs.computeIfAbsent(key, ignored -> AgentNavigationGraphService.rebuildGraph(map,
                new AgentMovementProfile(profile.speed, profile.jump)));
    }

    private static Character agent(MapleMap map, Profile profile, int salt) {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(90_000 + salt * 100 + PROFILES.indexOf(profile));
        when(agent.getMap()).thenReturn(map);
        when(agent.getMapId()).thenReturn(map.getId());
        when(agent.getTotalMoveSpeedStat()).thenReturn(profile.speed);
        when(agent.getTotalJumpStat()).thenReturn(profile.jump);
        when(agent.getMaxMp()).thenReturn(10_000);
        when(agent.getMp()).thenReturn(10_000);
        if (profile.skillId != 0) {
            when(agent.getSkillLevel(profile.skillId)).thenReturn(20);
        }
        return agent;
    }

    private static AgentNavigationPathService.MovementPathSelection route(
            AgentNavigationGraph graph, MapleMap map, Character agent, Point from, Point to,
            Predicate<AgentNavigationGraph.Edge> allowed) {
        return AgentNavigationPathService.findNextEdgeSelectionVaried(graph, agent, from,
                graph.findRegionId(map, from), graph.findRegionId(map, to), to, null, allowed);
    }

    private static List<Point> dropSpots(MapleMap map, Rectangle area) {
        List<Point> spots = new ArrayList<>();
        for (int x = area.x - 20; x <= area.x + area.width + 20; x += 5) {
            for (int y = area.y - 200; y <= area.y + area.height + 200; y += 10) {
                Point ground = AgentGroundingService.findGroundPoint(map, new Point(x, y));
                if (ground == null || Math.abs(ground.y - y) > 10) continue;
                Point landing = map.calcDropPos(new Point(ground), new Point(ground));
                if (landing != null && area.contains(landing) && !spots.contains(ground)) {
                    spots.add(ground);
                }
            }
        }
        return spots;
    }

    private static Rectangle area(int x, int y, int ltX, int ltY, int rbX, int rbY) {
        return new Rectangle(x + ltX, y + ltY, rbX - ltX, rbY - ltY);
    }

    private static Point ground(MapleMap map, Point point) {
        Point ground = AgentGroundingService.findGroundPoint(map, point);
        if (ground != null) return ground;
        Foothold below = map.getFootholds().findBelow(point);
        return below == null ? point : new Point(point.x, below.getY1());
    }

    private static String pathSummary(AgentNavigationPathService.MovementPathSelection selection) {
        Map<AgentNavigationGraph.EdgeType, Integer> counts = new EnumMap<>(AgentNavigationGraph.EdgeType.class);
        int maxY = Integer.MIN_VALUE;
        for (AgentNavigationGraph.Edge edge : selection.path()) {
            counts.merge(edge.type, 1, Integer::sum);
            maxY = Math.max(maxY, Math.max(edge.startPoint.y, edge.endPoint.y));
        }
        return "edges=" + selection.path().size() + " " + counts + (selection.path().isEmpty() ? "" : " lowestY=" + maxY)
                + " cost=" + selection.outcome().cost() + (selection.outcome().capped() ? " CAPPED" : "");
    }

    private static String summarize(List<Point> spots) {
        if (spots.isEmpty()) return "";
        int minX = spots.stream().mapToInt(p -> p.x).min().orElse(0);
        int maxX = spots.stream().mapToInt(p -> p.x).max().orElse(0);
        Set<Integer> ys = new java.util.TreeSet<>();
        spots.forEach(p -> ys.add(p.y));
        return "x " + minX + ".." + maxX + " ground y " + ys;
    }

    private static String xy(Point point) {
        return point == null ? "null" : "(" + point.x + "," + point.y + ")";
    }

    private void line(String text) {
        out.append(text).append('\n');
    }
}
