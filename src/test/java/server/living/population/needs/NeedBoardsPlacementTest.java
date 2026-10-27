package server.living.population.needs;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The needs board's Bulletin Boards (docs/BOT_CONCEPT_PLAN.md F1, population/needs.yaml): the FM entrance and nine main
 * towns, one board a map, each over a foothold within reach (it drops onto it, as NeedBoards spawns it), and none on top
 * of a stock NPC standing on the same ground, read from the maps' own WZ data.
 */
class NeedBoardsPlacementTest {
    private static final Path FILE = Path.of("scripts/config/population").resolve(NeedBoards.FILE);
    private static final int REACH = 300;
    private static final int NPC_GAP = 60;

    @Test
    void theFreeMarketAndTheMainTownsGetOneBoardEach() {
        List<NeedBoards.Placement> boards = NeedBoards.load(FILE);
        Set<Integer> maps = new HashSet<>();
        for (NeedBoards.Placement board : boards) {
            assertTrue(maps.add(board.mapId()), "one board a map: " + board.mapId());
        }
        assertTrue(maps.contains(910000000), "the FM entrance");
        assertTrue(maps.containsAll(Set.of(100000000, 101000000, 102000000, 103000000, 104000000)), "Victoria's towns");
        assertEquals(10, boards.size());
    }

    @Test
    void everyBoardStandsOnGroundAndOnNoStockNpc() throws Exception {
        List<String> wrong = new ArrayList<>();
        for (NeedBoards.Placement board : NeedBoards.load(FILE)) {
            Path file = Path.of("wz/Map.wz/Map/Map" + board.mapId() / 100_000_000 + "/" + board.mapId() + ".img.xml");
            assertTrue(Files.exists(file), file.toString());
            Document map = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile());
            Integer ground = groundBelow(map, board.x(), board.y());
            if (ground == null || ground - board.y() > REACH) {
                wrong.add(board + ": no foothold within " + REACH + " px below");
                continue;
            }
            for (int[] npc : npcs(map)) {
                if (Math.abs(npc[0] - board.x()) < NPC_GAP && Math.abs(npc[1] - ground) < 30) {
                    wrong.add(board + ": on the stock NPC at " + npc[0] + "," + npc[1]);
                }
            }
        }
        assertEquals(List.of(), wrong);
    }

    /** The highest foothold under (x, y): its y at x; null when none. */
    private static Integer groundBelow(Document map, int x, int y) {
        Integer best = null;
        for (Element foothold : section(map, "foothold")) {
            Map<String, Integer> values = ints(foothold);
            if (!values.containsKey("x1") || !values.containsKey("x2")) {
                continue;
            }
            int x1 = values.get("x1");
            int x2 = values.get("x2");
            int y1 = values.get("y1");
            int y2 = values.get("y2");
            if (x1 == x2 || x < Math.min(x1, x2) || x > Math.max(x1, x2)) {
                continue;
            }
            int at = y1 + (int) Math.round((y2 - y1) * (x - x1) / (double) (x2 - x1));
            if (at >= y && (best == null || at < best)) {
                best = at;
            }
        }
        return best;
    }

    private static List<int[]> npcs(Document map) {
        List<int[]> out = new ArrayList<>();
        for (Element life : children(topLevel(map, "life"))) {
            Map<String, String> values = strings(life);
            if ("n".equals(values.get("type"))) {
                out.add(new int[]{Integer.parseInt(values.get("x")), Integer.parseInt(values.get("cy"))});
            }
        }
        return out;
    }

    /** Every leaf imgdir under a top-level section (footholds sit three levels down: layer, group, id). */
    private static List<Element> section(Document map, String name) {
        List<Element> out = new ArrayList<>();
        collectLeaves(topLevel(map, name), out);
        return out;
    }

    private static void collectLeaves(Element node, List<Element> out) {
        if (node == null) {
            return;
        }
        List<Element> dirs = children(node);
        if (dirs.isEmpty()) {
            out.add(node);
        }
        for (Element dir : dirs) {
            collectLeaves(dir, out);
        }
    }

    private static Element topLevel(Document map, String name) {
        for (Element dir : children(map.getDocumentElement())) {
            if (name.equals(dir.getAttribute("name"))) {
                return dir;
            }
        }
        return null;
    }

    private static List<Element> children(Element node) {
        List<Element> out = new ArrayList<>();
        if (node == null) {
            return out;
        }
        NodeList nodes = node.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node child = nodes.item(i);
            if (child instanceof Element element && "imgdir".equals(element.getTagName())) {
                out.add(element);
            }
        }
        return out;
    }

    private static Map<String, Integer> ints(Element node) {
        Map<String, Integer> out = new HashMap<>();
        for (Map.Entry<String, String> entry : strings(node).entrySet()) {
            try {
                out.put(entry.getKey(), Integer.parseInt(entry.getValue()));
            } catch (NumberFormatException ignored) {
                // not a number
            }
        }
        return out;
    }

    private static Map<String, String> strings(Element node) {
        Map<String, String> out = new HashMap<>();
        NodeList nodes = node.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element element && !"imgdir".equals(element.getTagName())) {
                out.put(element.getAttribute("name"), element.getAttribute("value"));
            }
        }
        return out;
    }
}
