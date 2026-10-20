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
import server.maps.MapleMap;

import java.awt.Point;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
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
 * CWKPQ Step C0 stage-3 follow-up (docs/CWKPQ_C0_RESULTS.md): a speed/jump sweep on 610030300, a control with the
 * down-jump allowed, graph reachability next to the A* result, and the edges into the top portal's platform.
 * Throwaway; run alone like CwkpqC0NavigationProbe. Writes target/cwkpq-c0-stage3.txt.
 */
class CwkpqC0StageThreeProbe {
    static {
        Map<String, String> tuning = config.AgentYamlConfig.config.tuning;
        tuning.put("server.agents.capabilities.movement.AgentMovementSkillConfig.FLASH_JUMP_MODE", "ACTIVE");
        tuning.put("server.agents.capabilities.movement.AgentMovementSkillConfig.TELEPORT_MODE", "ACTIVE");
    }

    private record Profile(String name, int speed, int jump, int skillId) {
    }

    private static final int MAP = 610_030_300;
    private final StringBuilder out = new StringBuilder();
    private final Map<String, AgentNavigationGraph> graphs = new LinkedHashMap<>();

    @Test
    void probe() throws IOException {
        assertTrue(AgentMovementSkillConfig.FLASH_JUMP_MODE.active());
        Skill skill = mock(Skill.class);
        StatEffect effect = mock(StatEffect.class);
        when(skill.getEffect(anyInt())).thenReturn(effect);
        when(effect.getMpCon()).thenReturn((short) 10);
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(MAP);
        Map<String, Point> targets = new LinkedHashMap<>();
        targets.put("6109000", new Point(857, 1431));
        targets.put("6109001", new Point(240, 531));
        targets.put("6109002", new Point(322, -789));
        targets.put("6109003", new Point(982, -1209));
        targets.put("6109004", new Point(-480, -1269));
        targets.put("next00", new Point(215, -1107));
        targets.put("next00-floor-x100", new Point(100, -1110));
        targets.put("next00-floor-x310", new Point(310, -1110));
        Map<String, Point> starts = new LinkedHashMap<>();
        starts.put("sp", new Point(-483, 1470));
        starts.put("floor left end", new Point(-590, 1530));
        starts.put("floor right end", new Point(950, 1530));
        Predicate<AgentNavigationGraph.Edge> noDownJump =
                edge -> !(edge.type == AgentNavigationGraph.EdgeType.DROP && edge.launchStepX == 0);
        List<Profile> sweep = Boolean.getBoolean("cwkpq.c0.short")
                ? List.of(new Profile("100/100", 100, 100, 0), new Profile("120/120", 120, 120, 0),
                new Profile("145/110", 145, 110, 0), new Profile("Haste20 145/120", 145, 120, 0))
                : List.of(
                new Profile("100/100", 100, 100, 0), new Profile("100/110", 100, 110, 0),
                new Profile("100/120", 100, 120, 0), new Profile("120/115", 120, 115, 0),
                new Profile("120/120", 120, 120, 0), new Profile("145/100", 145, 100, 0),
                new Profile("145/110", 145, 110, 0), new Profile("145/115", 145, 115, 0),
                new Profile("Haste20 145/120", 145, 120, 0), new Profile("160/120", 160, 120, 0),
                new Profile("FJ 100/100", 100, 100, Hermit.FLASH_JUMP),
                new Profile("FJ+Haste 145/120", 145, 120, Hermit.FLASH_JUMP),
                new Profile("Teleport 100/100", 100, 100, FPWizard.TELEPORT));
        try (MockedStatic<SkillFactory> skills = mockStatic(SkillFactory.class)) {
            skills.when(() -> SkillFactory.getSkill(Hermit.FLASH_JUMP)).thenReturn(skill);
            skills.when(() -> SkillFactory.getSkill(FPWizard.TELEPORT)).thenReturn(skill);
            line("## 610030300 sweep: A* (findNextEdgeSelectionVaried) result per target / graph BFS reach, "
                    + "down-jump forbidden (CANNOTJUMPDOWN) | with down-jump allowed (control)");
            for (Profile profile : sweep) {
                AgentNavigationGraph graph = graph(map, profile);
                Character agent = agent(map, profile);
                for (Map.Entry<String, Point> start : starts.entrySet()) {
                    Point from = ground(map, start.getValue());
                    int fromRegion = graph.findRegionId(map, from);
                    StringBuilder row = new StringBuilder(profile.name + " from " + start.getKey() + " " + xy(from)
                            + " r" + fromRegion + ":");
                    for (Map.Entry<String, Point> target : targets.entrySet()) {
                        Point to = ground(map, target.getValue());
                        int toRegion = graph.findRegionId(map, to);
                        String strict = shortResult(route(graph, map, agent, from, to, noDownJump));
                        boolean bfs = reach(graph, agent, fromRegion, noDownJump).contains(toRegion);
                        String loose = shortResult(route(graph, map, agent, from, to, edge -> true));
                        row.append(' ').append(target.getKey()).append('=').append(strict)
                                .append(bfs ? "/bfs-yes" : "/bfs-no").append('|').append(loose);
                    }
                    line(row.toString());
                }
            }
            if (!Boolean.getBoolean("cwkpq.c0.short")) {
                diagnose(map, new Profile("Haste20 145/120", 145, 120, 0), noDownJump);
                diagnose(map, new Profile("100/100", 100, 100, 0), noDownJump);
            }
            for (Point floor : List.of(new Point(100, -1110), new Point(310, -1110), new Point(215, -1107))) {
                Point to = ground(map, floor);
                line("ground " + xy(floor) + " -> " + xy(to) + " region " + graph(map, sweep.get(0)).findRegionId(map, to)
                        + " foothold " + AgentGroundingService.findGroundFoothold(map, to).getId());
            }
        }
        Path file = Path.of("target", Boolean.getBoolean("cwkpq.c0.short") ? "cwkpq-c0-stage3-floor.txt" : "cwkpq-c0-stage3.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, out);
    }

    /** The top portal's platform and sigil 6109002's platform: bounds, and every edge into them. */
    private void diagnose(MapleMap map, Profile profile, Predicate<AgentNavigationGraph.Edge> allowed) {
        AgentNavigationGraph graph = graph(map, profile);
        Character agent = agent(map, profile);
        Set<Integer> reached = reach(graph, agent, graph.findRegionId(map, ground(map, new Point(-483, 1470))), allowed);
        line("");
        line("## diagnose " + profile.name + " (regions reached from sp: " + reached.size() + " of "
                + graph.regions.size() + ")");
        for (Point target : List.of(new Point(215, -1107), new Point(322, -789), new Point(982, -1209))) {
            Point to = ground(map, target);
            int toRegion = graph.findRegionId(map, to);
            AgentNavigationGraph.Region region = graph.getRegion(toRegion);
            line("target " + xy(target) + " ground " + xy(to) + " region " + toRegion
                    + (region == null ? "" : " x " + region.minX + ".." + region.maxX + " y " + region.minY + ".."
                    + region.maxY + " rope=" + region.isRopeRegion) + " reached=" + reached.contains(toRegion));
            for (AgentNavigationGraph.Region source : graph.regions) {
                for (AgentNavigationGraph.Edge edge : graph.getOutgoing(source.id)) {
                    if (edge.toRegionId != toRegion) continue;
                    line("    in  " + edge.type + " from r" + source.id + " x " + source.minX + ".." + source.maxX
                            + " y " + source.minY + " start " + xy(edge.startPoint) + " end " + xy(edge.endPoint)
                            + " step " + edge.launchStepX + " allowed=" + allowed.test(edge)
                            + " sourceReached=" + reached.contains(source.id));
                }
            }
            for (AgentNavigationGraph.Edge edge : graph.getOutgoing(toRegion)) {
                AgentNavigationGraph.Region dest = graph.getRegion(edge.toRegionId);
                line("    out " + edge.type + " to r" + edge.toRegionId
                        + (dest == null ? "" : " x " + dest.minX + ".." + dest.maxX + " y " + dest.minY)
                        + " start " + xy(edge.startPoint) + " end " + xy(edge.endPoint) + " step " + edge.launchStepX);
            }
        }
        // The highest reached platforms, to see how close the climb gets.
        reached.stream().map(graph::getRegion).filter(r -> r != null && !r.isRopeRegion)
                .sorted((a, b) -> Integer.compare(a.minY, b.minY)).limit(8)
                .forEach(r -> line("    reached high: r" + r.id + " x " + r.minX + ".." + r.maxX + " y " + r.minY));
    }

    private static Set<Integer> reach(AgentNavigationGraph graph, Character agent, int from,
                                      Predicate<AgentNavigationGraph.Edge> allowed) {
        Set<Integer> seen = new HashSet<>();
        if (from < 0) return seen;
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(from);
        seen.add(from);
        while (!queue.isEmpty()) {
            for (AgentNavigationGraph.Edge edge : graph.getOutgoing(queue.poll())) {
                if (!allowed.test(edge)) continue;
                if (AgentMovementSkillPolicy.isSkillEdge(edge)
                        && !AgentMovementSkillPolicy.canUseActivePath(agent, edge)) continue;
                if (seen.add(edge.toRegionId)) queue.add(edge.toRegionId);
            }
        }
        return seen;
    }

    private AgentNavigationGraph graph(MapleMap map, Profile profile) {
        return graphs.computeIfAbsent(profile.speed + ":" + profile.jump, ignored ->
                AgentNavigationGraphService.rebuildGraph(map, new AgentMovementProfile(profile.speed, profile.jump)));
    }

    private static Character agent(MapleMap map, Profile profile) {
        Character agent = mock(Character.class);
        when(agent.getId()).thenReturn(95_000 + profile.speed * 1000 + profile.jump + profile.skillId % 7);
        when(agent.getMap()).thenReturn(map);
        when(agent.getMapId()).thenReturn(map.getId());
        when(agent.getTotalMoveSpeedStat()).thenReturn(profile.speed);
        when(agent.getTotalJumpStat()).thenReturn(profile.jump);
        when(agent.getMaxMp()).thenReturn(10_000);
        when(agent.getMp()).thenReturn(10_000);
        if (profile.skillId != 0) when(agent.getSkillLevel(profile.skillId)).thenReturn(20);
        return agent;
    }

    private static AgentNavigationPathService.MovementPathSelection route(
            AgentNavigationGraph graph, MapleMap map, Character agent, Point from, Point to,
            Predicate<AgentNavigationGraph.Edge> allowed) {
        return AgentNavigationPathService.findNextEdgeSelectionVaried(graph, agent, from,
                graph.findRegionId(map, from), graph.findRegionId(map, to), to, null, allowed);
    }

    private static String shortResult(AgentNavigationPathService.MovementPathSelection selection) {
        return switch (selection.completeness()) {
            case COMPLETE -> "OK";
            case PARTIAL -> "partial";
            case UNREACHABLE -> "NO";
        };
    }

    private static Point ground(MapleMap map, Point point) {
        Point ground = AgentGroundingService.findGroundPoint(map, point);
        return ground != null ? ground : point;
    }

    private static String xy(Point point) {
        return point == null ? "null" : "(" + point.x + "," + point.y + ")";
    }

    private void line(String text) {
        out.append(text).append('\n');
    }
}
