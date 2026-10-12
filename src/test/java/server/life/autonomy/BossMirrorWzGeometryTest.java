package server.life.autonomy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.Point;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P12: the boss mirror's geometry for the Pianus, Horntail and Pink Bean ids matches their WZ ranges. For each attack
 * the action catalog's box (range/lt and rb, or sp and r) and its regions (start, areaCount, attackCount) are the raw
 * Mob.wz values, and the mirror hits a point inside the box (or inside region 0) and misses one beyond its far edge.
 */
class BossMirrorWzGeometryTest {
    private static final Point ORIGIN = new Point(0, 0);

    @ParameterizedTest
    @ValueSource(ints = {8_510_000, 8_520_000,
            8_810_000, 8_810_001, 8_810_002, 8_810_003, 8_810_004, 8_810_005, 8_810_006, 8_810_008, 8_810_009,
            8_810_019, 8_810_020, 8_810_021, 8_810_022, 8_810_023,
            8_820_001, 8_820_002, 8_820_003, 8_820_004, 8_820_005, 8_820_006, 8_820_007,
            8_820_015, 8_820_016, 8_820_017, 8_820_018})
    void theCatalogAndTheMirrorReadEachAttackAsWzStoresIt(int mobId) throws Exception {
        Element root = load(mobId).getDocumentElement();
        boolean noFlip = intAt(child(root, "info"), "noFlip", 0) != 0;
        List<BossAction.OrdinaryAttack> attacks = ServerMobActionCatalog.forMob(mobId).attacks();
        assertFalse(attacks.isEmpty(), mobId + " has attacks in WZ");

        for (BossAction.OrdinaryAttack attack : attacks) {
            String where = mobId + " attack" + attack.actionNumber();
            Element range = child(child(child(root, "attack" + attack.actionNumber()), "info"), "range");
            assertNotNull(range, where + " has a range");
            Point lt = vector(range, "lt");
            Point rb = vector(range, "rb");
            if (lt == null || rb == null) {
                Point centre = vector(range, "sp");
                int radius = Math.abs(intAt(range, "r", 0));
                lt = new Point(centre.x - radius, centre.y - radius);
                rb = new Point(centre.x + radius, centre.y + radius);
            }
            assertEquals(lt, attack.lt(), where + " lt");
            assertEquals(rb, attack.rb(), where + " rb");
            assertEquals(intAt(range, "start", 0), attack.areaStart(), where + " start");
            assertEquals(intAt(range, "areaCount", 0), attack.areaCount(), where + " areaCount");
            assertEquals(intAt(range, "attackCount", 0), attack.selectedAreaCount(), where + " attackCount");

            boolean regions = attack.areaCount() > 0 && attack.selectedAreaCount() > 0;
            int firstX = regions ? (attack.areaStart()) * 100 : 0;
            Point inside = new Point(ORIGIN.x + firstX + (lt.x + rb.x) / 2,
                    ORIGIN.y + (regions ? rb.y : (lt.y + rb.y) / 2));
            Point beyond = new Point(ORIGIN.x + firstX + lt.x - 50, inside.y);
            List<Integer> struck = regions ? List.of(0) : List.of();
            assertTrue(BossMirrorGeometry.hits(attack, ORIGIN, inside, noFlip, true, struck), where + " inside");
            assertFalse(BossMirrorGeometry.hits(attack, ORIGIN, beyond, noFlip, true, struck), where + " beyond");
        }
    }

    private static Document load(int mobId) throws Exception {
        Path file = Path.of("wz/Mob.wz/" + mobId + ".img.xml");
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile());
    }

    /** The direct child of {@code parent} whose name attribute is {@code name}, or null. */
    private static Element child(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getAttribute("name"))) {
                return element;
            }
        }
        return null;
    }

    private static Point vector(Element parent, String name) {
        Element vector = child(parent, name);
        return vector == null ? null
                : new Point(Integer.parseInt(vector.getAttribute("x")), Integer.parseInt(vector.getAttribute("y")));
    }

    private static int intAt(Element parent, String name, int fallback) {
        Element value = child(parent, name);
        return value == null ? fallback : Integer.parseInt(value.getAttribute("value"));
    }
}
