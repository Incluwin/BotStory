package server.agents.progression;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import server.mods.RepackMaps;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * world-portal-route-graph.json is what tools/game-catalog/Export-WorldPortalRouteGraph.ps1 derives from WZ:
 * every unscripted portal between two maps of one continent group, outside the excluded ride maps. A WZ edit
 * that changes a portal fails here until the catalog is exported again.
 */
class AgentWorldPortalRouteGraphFreshnessTest {
    private static final Path CATALOG = Path.of("src", "main", "resources", "agents", "catalogs",
            "world-portal-route-graph.json");
    private static final Path MAP_ROOT = Path.of("wz", "Map.wz", "Map");
    private static final Pattern PORTAL_BLOCK = Pattern.compile("(?s)<imgdir name=\"portal\">(.*?)\\n  </imgdir>");
    private static final Pattern PORTAL_ENTRY = Pattern.compile("(?s)<imgdir name=\"\\d+\">(.*?)</imgdir>");
    private static final Pattern TARGET_MAP = Pattern.compile("<int name=\"tm\" value=\"(-?\\d+)\"/>");
    private static final Pattern SCRIPT = Pattern.compile("<string name=\"script\" value=\"([^\"]*)\"/>");

    private record Range(String group, int first, int end) {
        boolean contains(int mapId) {
            return mapId >= first && mapId < end;
        }
    }

    @Test
    void catalogMatchesTheStandardPortalsInWz() throws IOException {
        JsonNode catalog = new ObjectMapper().readTree(CATALOG.toFile());
        List<Range> groups = new ArrayList<>();
        for (JsonNode group : catalog.path("groups")) {
            groups.add(new Range(group.path("id").asText(), group.path("firstMapId").asInt(),
                    group.path("endMapId").asInt()));
        }
        List<Range> excluded = new ArrayList<>();
        for (JsonNode range : catalog.path("excludedRanges")) {
            excluded.add(new Range("", range.path("firstMapId").asInt(), range.path("endMapId").asInt()));
        }
        assertTrue(groups.size() >= 7, "continent groups are part of the catalog");

        Set<Integer> known = new HashSet<>();
        Set<String> derived = new TreeSet<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(MAP_ROOT)) {
            files = walk.filter(path -> path.getFileName().toString().matches("\\d+\\.img\\.xml")).toList();
        }
        for (Path file : files) {
            known.add(mapId(file));
        }
        for (Path file : files) {
            int mapId = mapId(file);
            String group = group(mapId, groups, excluded);
            if (group == null) {
                continue;
            }
            Matcher block = PORTAL_BLOCK.matcher(Files.readString(file));
            if (!block.find()) {
                continue;
            }
            Matcher entry = PORTAL_ENTRY.matcher(block.group(1));
            while (entry.find()) {
                String body = entry.group(1);
                Matcher target = TARGET_MAP.matcher(body);
                if (!target.find()) {
                    continue;
                }
                int to = Integer.parseInt(target.group(1));
                Matcher script = SCRIPT.matcher(body);
                if (to == 999999999 || to == mapId || !known.contains(to)
                        || script.find() && !script.group(1).isBlank()
                        || !group.equals(group(to, groups, excluded))) {
                    continue;
                }
                derived.add(mapId + "->" + to);
            }
        }

        Set<String> committed = new TreeSet<>();
        for (JsonNode edge : catalog.path("edges")) {
            committed.add(edge.path("fromMapId").asInt() + "->" + edge.path("toMapId").asInt());
        }
        Set<String> missing = new TreeSet<>(derived);
        missing.removeAll(committed);
        // The Community Repack maps (server.mods.RepackMaps) join the catalog when the routes work exports it again;
        // until the catalog has one of their edges, theirs may be missing. Once it has one, all must be there.
        if (committed.stream().noneMatch(AgentWorldPortalRouteGraphFreshnessTest::touchesRepackMap)) {
            missing.removeIf(AgentWorldPortalRouteGraphFreshnessTest::touchesRepackMap);
        }
        Set<String> stale = new TreeSet<>(committed);
        stale.removeAll(derived);
        assertEquals(Set.of(), missing, "WZ portals missing from the catalog: run Export-WorldPortalRouteGraph.ps1");
        assertEquals(Set.of(), stale, "catalog edges WZ no longer has: run Export-WorldPortalRouteGraph.ps1");
    }

    private static boolean touchesRepackMap(String edge) {
        int arrow = edge.indexOf("->");
        return RepackMaps.listed(Integer.parseInt(edge.substring(0, arrow)))
                || RepackMaps.listed(Integer.parseInt(edge.substring(arrow + 2)));
    }

    private static int mapId(Path file) {
        String name = file.getFileName().toString();
        return Integer.parseInt(name.substring(0, name.indexOf('.')));
    }

    private static String group(int mapId, List<Range> groups, List<Range> excluded) {
        for (Range range : excluded) {
            if (range.contains(mapId)) {
                return null;
            }
        }
        for (Range range : groups) {
            if (range.contains(mapId)) {
                return range.group();
            }
        }
        return null;
    }
}
