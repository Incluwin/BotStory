package server.living.townlife;

import provider.Data;
import provider.DataProvider;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import server.agents.capabilities.navigation.AgentNavigationGraph;
import server.agents.capabilities.navigation.AgentNavigationGraphService;
import server.agents.capabilities.navigation.AgentNavigationMapLoader;
import server.maps.MapFactory;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** One town map loaded offline: WZ geometry and nav graph plus the seats, NPCs and names TownLife profiles refer to. */
public record TownMapData(int mapId,
                          String mapName,
                          boolean swim,
                          MapleMap map,
                          AgentNavigationGraph graph,
                          List<Portal> portals,
                          List<Seat> seats,
                          List<Npc> npcs,
                          Map<Integer, Target> targets) {
    private static final DataProvider MAPS = DataProviderFactory.getDataProvider(WZFiles.MAP);
    private static final Data NPC_STRINGS = DataProviderFactory.getDataProvider(WZFiles.STRING).getData("Npc.img");
    private static final int NO_MAP = 999999999;
    private static final int GLOBAL_TOWN_COUNT = 5;
    private static final int MIN_LANE_HALF_WIDTH = 45;
    private static final int MAX_LANE_HALF_WIDTH = 100;
    private static final Pattern TOWN_LIFE_NPC = Pattern.compile(
            "<string name=\"type\" value=\"n\"/>\\s*<string name=\"id\" value=\"(\\d+)\"/>");
    private static final Pattern NPC_LINK = Pattern.compile("<string name=\"link\" value=\"(\\d+)\"/>");
    private static final Pattern NPC_STAND = Pattern.compile(
            "<imgdir name=\"stand\">\\s*<canvas name=\"0\" width=\"(\\d+)\"[^>]*>\\s*<vector name=\"origin\" x=\"(-?\\d+)\"");
    private static Map<Integer, Integer> townAppearances;

    public record Seat(int id, Point point) {
    }

    /**
     * A map NPC. {@code global} marks the event and service NPCs placed in many towns (and the per-town Maple TVs,
     * event assistants and love fairies), which say nothing about this town; {@code laneHalfWidth} is half the
     * sprite width, so the NPC lane covers what is drawn.
     */
    public record Npc(int id, int x, int y, int cy, int fh, boolean hidden, String name, String func,
                      boolean global, int laneHalfWidth) {
    }

    /** A portal destination: whether WZ flags it as a town, its String.wz name and the maps its own portals lead to. */
    public record Target(boolean town, String name, Set<Integer> exits) {
    }

    public static TownMapData load(int mapId) {
        MapleMap map = AgentNavigationMapLoader.loadMapGeometry(mapId);
        AgentNavigationGraph graph = AgentNavigationGraphService.getGraph(map);
        Data ownData = MAPS.getData(path(mapId));
        boolean swim = DataTool.getIntConvert("info/swim", ownData, 0) != 0;
        Data mapData = linked(ownData);
        List<Seat> seats = new ArrayList<>();
        Data seatData = mapData.getChildByPath("seat");
        if (seatData != null) {
            for (Data seat : seatData) {
                seats.add(new Seat(Integer.parseInt(seat.getName()), new Point(DataTool.getPoint(seat))));
            }
        }
        List<Npc> npcs = new ArrayList<>();
        Data lifeData = mapData.getChildByPath("life");
        if (lifeData != null) {
            for (Data life : lifeData) {
                if (!"n".equals(DataTool.getString("type", life, ""))) {
                    continue;
                }
                int id = Integer.parseInt(DataTool.getString("id", life, "0"));
                Data strings = NPC_STRINGS == null ? null : NPC_STRINGS.getChildByPath(String.valueOf(id));
                String name = strings == null ? "" : DataTool.getString("name", strings, "").trim();
                String func = strings == null ? "" : DataTool.getString("func", strings, "").trim();
                boolean global = townAppearances().getOrDefault(id, 0) >= GLOBAL_TOWN_COUNT
                        || func.equals("Event Assistant") || func.equals("Love Fairy") || name.endsWith("Maple TV");
                npcs.add(new Npc(id, DataTool.getInt("x", life, 0), DataTool.getInt("y", life, 0),
                        DataTool.getInt("cy", life, 0), DataTool.getInt("fh", life, 0),
                        DataTool.getInt("hide", life, 0) == 1, name, func, global, laneHalfWidth(id)));
            }
        }
        List<Portal> portals = map.getPortals().stream().sorted(Comparator.comparingInt(Portal::getId)).toList();
        Map<Integer, Target> targets = new TreeMap<>();
        for (Portal portal : portals) {
            int target = portal.getTargetMapId();
            if (target > 0 && target != mapId && !targets.containsKey(target)) {
                Data targetData = MAPS.getData(path(target));
                if (targetData != null) {
                    Set<Integer> exits = new TreeSet<>();
                    Data targetPortals = linked(targetData).getChildByPath("portal");
                    if (targetPortals != null) {
                        for (Data targetPortal : targetPortals) {
                            int exit = DataTool.getInt("tm", targetPortal, NO_MAP);
                            if (exit != NO_MAP && exit != target) {
                                exits.add(exit);
                            }
                        }
                    }
                    targets.put(target, new Target(DataTool.getIntConvert("info/town", targetData, 0) != 0,
                            MapFactory.loadPlaceName(target), exits));
                }
            }
        }
        return new TownMapData(mapId, MapFactory.loadPlaceName(mapId), swim, map, graph, portals,
                List.copyOf(seats), List.copyOf(npcs), Map.copyOf(targets));
    }

    public Npc npc(int npcId) {
        return npcs.stream().filter(npc -> npc.id() == npcId).findFirst().orElse(null);
    }

    private static Data linked(Data mapData) {
        String link = DataTool.getString("info/link", mapData, "");
        return link.isEmpty() ? mapData : MAPS.getData(path(Integer.parseInt(link)));
    }

    private static String path(int mapId) {
        return "Map/Map" + (mapId / 100000000) + "/" + String.format("%09d", mapId) + ".img";
    }

    /** Half the NPC's stand/0 sprite width around its origin (following info/link), clamped to 45..100 px. */
    private static int laneHalfWidth(int npcId) {
        try {
            String xml = npcXml(npcId);
            Matcher link = NPC_LINK.matcher(xml);
            if (link.find()) {
                xml = npcXml(Integer.parseInt(link.group(1)));
            }
            Matcher stand = NPC_STAND.matcher(xml);
            if (stand.find()) {
                int width = Integer.parseInt(stand.group(1));
                int originX = Integer.parseInt(stand.group(2));
                return Math.clamp(Math.max(originX, width - originX), MIN_LANE_HALF_WIDTH, MAX_LANE_HALF_WIDTH);
            }
        } catch (IOException ignored) {
            // no sprite file: the default lane below
        }
        return MIN_LANE_HALF_WIDTH;
    }

    private static String npcXml(int npcId) throws IOException {
        return Files.readString(WZFiles.NPC.getFile().resolve(String.format("%07d.img.xml", npcId)), StandardCharsets.UTF_8);
    }

    /** NPC id -> number of town-flagged maps it stands in; one scan of Map.wz per JVM (a few seconds). */
    private static synchronized Map<Integer, Integer> townAppearances() {
        if (townAppearances == null) {
            Map<Integer, Integer> counts = new HashMap<>();
            try (Stream<Path> files = Files.walk(WZFiles.MAP.getFile().resolve("Map"), 2)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".img.xml")).toList()) {
                    String xml = Files.readString(file, StandardCharsets.UTF_8);
                    if (!xml.contains("<int name=\"town\" value=\"1\"/>")) {
                        continue;
                    }
                    Set<Integer> ids = new HashSet<>();
                    Matcher life = TOWN_LIFE_NPC.matcher(xml);
                    while (life.find()) {
                        ids.add(Integer.parseInt(life.group(1)));
                    }
                    ids.forEach(id -> counts.merge(id, 1, Integer::sum));
                }
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
            townAppearances = Map.copyOf(counts);
        }
        return townAppearances;
    }
}
