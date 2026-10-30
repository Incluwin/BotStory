package server.agents.progression;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * world-portal-route-graph.json is what tools/game-catalog/Export-WorldPortalRouteGraph.ps1 derives from WZ:
 * every unscripted portal between two maps of one continent group, outside the excluded ride maps, and the authored
 * scripted portals as WZ and their scripts have them. A WZ edit that changes a portal fails here until the catalog is
 * exported again.
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
        Set<String> stale = new TreeSet<>(committed);
        stale.removeAll(derived);
        assertEquals(Set.of(), missing, "WZ portals missing from the catalog: run Export-WorldPortalRouteGraph.ps1");
        assertEquals(Set.of(), stale, "catalog edges WZ no longer has: run Export-WorldPortalRouteGraph.ps1");
    }

    /**
     * Each scripted portal's script, run for a traveller standing on its source map (AgentTravelLegScriptTest's
     * recording stub), sends it to the destination once the gate's quests are done, and not before: scripts that work
     * the target or the quest out from the map id (timeQuest.js on the Temple of Time's roads) are checked too.
     */
    @Test
    void everyScriptedPortalRunsItsScriptToItsDestination() throws Exception {
        JsonNode portals = new ObjectMapper().readTree(CATALOG.toFile()).path("scriptedPortals");
        assertTrue(portals.size() >= 5, "the Time Gate, An Empty House's door and the Temple of Time's flights");
        for (JsonNode portal : portals) {
            int source = portal.path("sourceMapId").asInt();
            int destination = portal.path("destinationMapId").asInt();
            String name = portal.path("portalName").asText();
            String script = portal.path("script").asText();
            Path file = MAP_ROOT.resolve("Map" + source / 100_000_000).resolve(String.format("%09d.img.xml", source));
            Matcher block = PORTAL_BLOCK.matcher(Files.readString(file));
            assertTrue(block.find(), () -> source + " has portals");
            Matcher entry = Pattern.compile("(?s)<imgdir name=\"" + portal.path("portalId").asInt() + "\">(.*?)</imgdir>")
                    .matcher(block.group(1));
            assertTrue(entry.find(), () -> source + " has portal " + portal.path("portalId").asInt());
            assertTrue(entry.group(1).contains("<string name=\"pn\" value=\"" + name + "\"/>"),
                    () -> source + ": the portal id names " + name);
            assertTrue(entry.group(1).contains("<string name=\"script\" value=\"" + script + "\"/>"),
                    () -> source + ": " + name + " runs " + script);
            JsonNode gate = portal.path("gate");
            AgentTravelLegScriptTest.Conversation open = new AgentTravelLegScriptTest.Conversation(source, 0, "true");
            gate.path("completedAny").forEach(quest -> open.completedQuests.add(quest.asInt()));
            gate.path("activeAny").forEach(quest -> open.startedQuests.add(quest.asInt()));
            AgentTravelLegScriptTest.enter(script, open);
            assertEquals(destination, open.warpedTo,
                    () -> source + ": " + script + " sends the traveller to " + destination);
            for (JsonNode quest : gate.path("completedAny")) {
                AgentTravelLegScriptTest.Conversation one = new AgentTravelLegScriptTest.Conversation(source, 0, "true");
                one.completedQuests.add(quest.asInt());
                AgentTravelLegScriptTest.enter(script, one);
                assertEquals(destination, one.warpedTo, () -> source + ": " + script + " opens for " + quest + " done");
            }
            for (JsonNode quest : gate.path("activeAny")) {
                AgentTravelLegScriptTest.Conversation one = new AgentTravelLegScriptTest.Conversation(source, 0, "true");
                one.startedQuests.add(quest.asInt());
                AgentTravelLegScriptTest.enter(script, one);
                assertEquals(destination, one.warpedTo, () -> source + ": " + script + " opens for " + quest + " taken");
            }
            if (!gate.isMissingNode()) {
                AgentTravelLegScriptTest.Conversation shut = new AgentTravelLegScriptTest.Conversation(source, 0, "true");
                AgentTravelLegScriptTest.enter(script, shut);
                assertNotEquals(destination, shut.warpedTo, () -> source + ": " + script + " is shut without the quests");
            }
        }
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
