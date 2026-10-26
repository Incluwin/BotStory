package server.mods;

import net.server.Server;
import net.server.channel.Channel;
import net.server.world.World;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.life.LifeFactory;
import server.life.Monster;
import server.life.NPC;
import server.maps.MapFactory;
import server.maps.MapleMap;
import tools.DatabaseConnection;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * The Community Repack maps tools/repack-import exported into wz/ (docs/REPACK_IMPORT_PLAN.md): every listed map is
 * there with a name, loads through the server's own MapFactory, and spawns only NPCs, mobs and reactors the server
 * has; Arcane River spawns no mob.
 */
class RepackImportDataTest {
    private static final Pattern LIFE = Pattern.compile(
            "<string name=\"type\" value=\"([nm])\"/>\\s*<string name=\"id\" value=\"(\\d+)\"/>");
    private static final Pattern REACTOR = Pattern.compile(
            "<imgdir name=\"\\d+\">\\s*<string name=\"id\" value=\"(\\d+)\"/>");

    private static Path mapFile(int map) {
        String id = String.format("%09d", map);
        return Path.of("wz", "Map.wz", "Map", "Map" + id.charAt(0), id + ".img.xml");
    }

    private static String section(String xml, String name) {
        int start = xml.indexOf("\n  <imgdir name=\"" + name + "\">");
        if (start < 0) {
            return "";
        }
        return xml.substring(start, xml.indexOf("\n  </imgdir>", start));
    }

    @Test
    void everyListedMapIsInWzWithANameAndSpawnsOnlyTemplatesTheServerHas() throws IOException {
        List<String> problems = new ArrayList<>();
        Set<Integer> mobs = new TreeSet<>();
        for (Map.Entry<Integer, String> listed : RepackMaps.areas().entrySet()) {
            int map = listed.getKey();
            Path file = mapFile(map);
            if (!Files.isRegularFile(file)) {
                problems.add(map + ": no " + file);
                continue;
            }
            if (MapFactory.loadPlaceName(map).isBlank()) {
                problems.add(map + ": no String.wz name where MapFactory reads it");
            }
            String xml = Files.readString(file, StandardCharsets.UTF_8);
            Matcher life = LIFE.matcher(section(xml, "life"));
            while (life.find()) {
                int id = Integer.parseInt(life.group(2));
                boolean mob = life.group(1).equals("m");
                if (mob && listed.getValue().startsWith("arcane-")) {
                    problems.add(map + ": Arcane River is scenery, but mob " + id + " spawns");
                }
                if (mob) {
                    mobs.add(id);
                }
                Path template = Path.of("wz", mob ? "Mob.wz" : "Npc.wz", String.format("%07d.img.xml", id));
                if (!Files.isRegularFile(template)) {
                    problems.add(map + ": " + template + " is missing");
                }
            }
            Matcher reactor = REACTOR.matcher(section(xml, "reactor"));
            while (reactor.find()) {
                Path template = Path.of("wz", "Reactor.wz", String.format("%07d.img.xml", Integer.parseInt(reactor.group(1))));
                if (!Files.isRegularFile(template)) {
                    problems.add(map + ": " + template + " is missing");
                }
            }
        }
        assertEquals(List.of(), problems);
        assertTrue(mobs.contains(9500143), "Coke Town's Coke Snail spawns");
        assertTrue(mobs.size() > 50, "the huntable areas spawn their mobs: " + mobs.size());
    }

    @Test
    void everyListedMapLoadsThroughMapFactory() {
        List<String> failures = new ArrayList<>();
        Server server = mock(Server.class);
        World world = mock(World.class);
        when(server.getWorld(0)).thenReturn(world);
        when(world.getChannel(1)).thenReturn(mock(Channel.class));
        try (MockedStatic<DatabaseConnection> db = mockStatic(DatabaseConnection.class);
             MockedStatic<Server> servers = mockStatic(Server.class)) {
            db.when(DatabaseConnection::getConnection).thenThrow(new SQLException("no database in this test"));
            servers.when(Server::getInstance).thenReturn(server);
            for (int map : RepackMaps.areas().keySet()) {
                try {
                    MapleMap loaded = MapFactory.loadMapFromWz(map, 0, 1, null);
                    if (loaded.getPortals().isEmpty() && !RepackMaps.area(map).startsWith("chaos")) {
                        failures.add(map + ": no portals");
                    }
                } catch (RuntimeException e) {
                    failures.add(map + ": " + e);
                }
            }
        }
        assertEquals(List.of(), failures);
    }

    @Test
    void theImportedMobsAndNpcsLoadThroughLifeFactory() throws IOException {
        String report = Files.readString(Path.of("tools", "repack-import", "export-report.json"), StandardCharsets.UTF_8);
        List<String> failures = new ArrayList<>();
        for (int id : ids(report, "mobs")) {
            try {
                Monster mob = LifeFactory.getMonster(id);
                if (mob == null || mob.getStats().getLevel() <= 0) {
                    failures.add("mob " + id);
                }
            } catch (RuntimeException e) {
                failures.add("mob " + id + ": " + e);
            }
        }
        List<Integer> unnamed = ids(report, "unnamedNpcs");
        assertEquals(List.of(9010101, 9010102, 9010103, 9010104, 9010105), unnamed, "Lachelein's Dream Defender boards");
        for (int id : ids(report, "npcs")) {
            try {
                NPC npc = LifeFactory.getNPC(id);
                if (npc == null || !unnamed.contains(id) && (npc.getName() == null || npc.getName().isBlank())) {
                    failures.add("npc " + id + " has no name");
                }
            } catch (RuntimeException e) {
                failures.add("npc " + id + ": " + e);
            }
        }
        assertEquals(List.of(), failures);
    }

    @Test
    void theReportListsWhatTheExportImported() throws IOException {
        String report = Files.readString(Path.of("tools", "repack-import", "export-report.json"), StandardCharsets.UTF_8);
        assertEquals(65, ids(report, "mobs").size(), "64 the maps spawn or link, and one a summon skill calls");
        assertEquals(339, ids(report, "npcs").size());
        assertTrue(report.contains("\"maps\": 579"));
        assertFalse(ids(report, "reactors").isEmpty());
        assertNotNull(RepackMaps.area(219000000));
    }

    /** The ids of a list in export-report.json: under "imported" for npcs/mobs/reactors, else at the top. */
    private static List<Integer> ids(String report, String kind) {
        int imported = kind.equals("npcs") || kind.equals("mobs") || kind.equals("reactors") ? report.indexOf("\"imported\"") : 0;
        int start = report.indexOf("\"" + kind + "\": [", imported);
        int end = report.indexOf("]", start);
        List<Integer> out = new ArrayList<>();
        for (String word : report.substring(report.indexOf('[', start) + 1, end).split("[,\\s]+")) {
            if (!word.isBlank()) {
                out.add(Integer.parseInt(word.trim()));
            }
        }
        return out;
    }
}
