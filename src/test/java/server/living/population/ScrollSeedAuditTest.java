package server.living.population;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The audit of 2026-11-24 (docs/ECONOMY_PLAN.md 10.12) over every scroll in the WZ data that no price table lists: its
 * formula seed stays within a factor of 2 of the range its table peers span (same kind of bonus, then weapon or armour,
 * then dark or not, then the nearest success rate). The peers here are worked out from the scrolls' names and bonuses
 * apart from ScrollSeeds, so a change to either side that parts them fails.
 */
class ScrollSeedAuditTest {
    private static final double BOUND = 2.0d;
    private static final Map<Integer, Map<String, Integer>> STATS = new HashMap<>();
    private static ScrollSeeds seeds;

    @BeforeAll
    static void readScrolls() throws Exception {
        Element root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(Path.of("wz/Item.wz/Consume/0204.img.xml").toFile()).getDocumentElement();
        for (Element item : children(root)) {
            Map<String, Integer> stats = new HashMap<>();
            for (Element block : children(item)) {
                if (block.getAttribute("name").equals("info")) {
                    for (Element value : children(block)) {
                        String name = value.getAttribute("name");
                        if (name.startsWith("inc") || name.equals("success") || name.equals("cursed")) {
                            stats.put(name.startsWith("inc") ? name.substring(3) : name,
                                    Integer.parseInt(value.getAttribute("value")));
                        }
                    }
                }
            }
            STATS.put(Integer.parseInt(item.getAttribute("name")), stats);
        }
        seeds = new ScrollSeeds(STATS::get, PriceSeeds::all);
    }

    private static List<Element> children(Element parent) {
        List<Element> out = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) {
                out.add(element);
            }
        }
        return out;
    }

    private static String kind(Map<String, Integer> stats) {
        String kind = null;
        double most = 0.0d;
        String[][] kinds = {{"ATT", "PAD", "MAD"}, {"MAIN", "STR", "DEX", "INT", "LUK"}, {"ACCEVA", "ACC", "EVA"},
                {"MOVE", "Speed", "Jump"}, {"DEF", "PDD", "MDD"}, {"HP", "MHP", "MMP"}};
        for (String[] group : kinds) {
            for (int i = 1; i < group.length; i++) {
                double bonus = stats.getOrDefault(group[i], 0) * (group[0].equals("HP") ? 0.1d : 1.0d);
                if (bonus > most) {
                    most = bonus;
                    kind = group[0];
                }
            }
        }
        return kind;
    }

    private static int[] order(int itemId, int peer) {
        boolean weapon = itemId >= 2_043_000 && itemId < 2_045_000;
        boolean peerWeapon = peer >= 2_043_000 && peer < 2_045_000;
        boolean dark = STATS.get(itemId).getOrDefault("cursed", 0) > 0;
        boolean peerDark = STATS.get(peer).getOrDefault("cursed", 0) > 0;
        return new int[]{weapon == peerWeapon ? 0 : 1, dark == peerDark ? 0 : 1,
                Math.abs(STATS.get(itemId).get("success") - STATS.get(peer).get("success"))};
    }

    private static List<Long> peerPrices(int itemId) {
        List<Long> prices = new ArrayList<>();
        int[] nearest = null;
        for (Map.Entry<Integer, Long> table : PriceSeeds.all().entrySet()) {
            Map<String, Integer> stats = STATS.get(table.getKey());
            if (stats == null || kind(stats) == null || !kind(stats).equals(kind(STATS.get(itemId)))) {
                continue;
            }
            int[] order = order(itemId, table.getKey());
            int compared = nearest == null ? -1 : java.util.Arrays.compare(order, nearest);
            if (compared < 0) {
                prices.clear();
                nearest = order;
            }
            if (compared <= 0) {
                prices.add(table.getValue());
            }
        }
        return prices;
    }

    @Test
    void noFormulaSeedIsMoreThanTheBoundOffItsTablePeers() {
        List<String> off = new ArrayList<>();
        int audited = 0;
        for (int itemId : STATS.keySet()) {
            long seed = PriceSeeds.of(itemId) > 0L ? 0L : seeds.seed(itemId);
            if (seed <= 0L) {
                continue; // listed, or with no bonus to price
            }
            List<Long> peers = peerPrices(itemId);
            audited++;
            double least = peers.stream().mapToLong(Long::longValue).min().orElse(0L) / BOUND;
            double most = peers.stream().mapToLong(Long::longValue).max().orElse(0L) * BOUND;
            if (seed < least - 1 || seed > most + 1) {
                off.add(itemId + " " + STATS.get(itemId) + " seed " + seed + " peers " + peers);
            }
        }
        assertTrue(audited > 600, "audited " + audited);
        assertEquals(List.of(), off);
    }

    /** What was wrong: one rate for every kind of bonus made an accuracy scroll dearer than its weapon's attack scroll. */
    @Test
    void anAccuracyScrollIsCheaperThanAnAttackScrollAndAPetScrollIsAboutItsTablePeers() {
        long attack = PriceSeeds.of(2044701); // claw ATT 60 %, the dearer of the tables' clean weapon scrolls is 1.6M
        for (int accuracy : new int[]{2043017, 2043112, 2044312, 2044412, 2044807}) {
            assertTrue(seeds.seed(accuracy) * 2 < attack, accuracy + ": " + seeds.seed(accuracy));
        }
        for (int pet : new int[]{2048000, 2048003}) { // +1 at 100 %; the tables' +2 at 60 % is 100k
            assertTrue(seeds.seed(pet) > 50_000L && seeds.seed(pet) <= 100_000L, pet + ": " + seeds.seed(pet));
        }
        assertEquals(551_210L, seeds.seed(2043002), 1.0d, "a 10 % attack scroll beside the dagger's and claw's 10 %");
    }
}
