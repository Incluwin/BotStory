package server.living.mentor;

import client.Job;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import server.living.roster.JobInstructors;

import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.Point;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mentor's road (docs/BOT_CONCEPT_PLAN.md F4.3), checked against the client's own map data in {@code wz/}: no
 * route graph covers Maple Island, so MentorRoute's facts are the only map of it the mentor has. Each hop of the island
 * road leaves by a walking portal to the next map and lands on a portal there; the first four have no way back, the
 * rest do; Shanks stands on Southperry, the ship lands on Lith Harbor's deck, whose way into town is the hidden portal
 * AgentLithHarborArrivalRouteRuntime names, the taxi stands in town, and each branch's instructor stands where
 * JobInstructors says. The walk on each map is MentorRouteWalkTest's.
 */
class MentorRouteTest {
    private static final Path WZ_MAPS = Path.of("wz", "Map.wz", "Map");

    record WzPortal(int id, String name, int type, Point at, int targetMap, String targetName) { }

    record WzMap(int id, List<WzPortal> portals, List<int[]> npcs) {
        List<WzPortal> to(int mapId) {
            return portals.stream().filter(portal -> portal.targetMap() == mapId).toList();
        }

        WzPortal named(String name) {
            return portals.stream().filter(portal -> portal.name().equals(name)).findFirst().orElse(null);
        }

        int[] npc(int npcId) {
            return npcs.stream().filter(npc -> npc[0] == npcId).findFirst().orElse(null);
        }
    }

    @Test
    void eachIslandHopLeavesByAWalkingPortalAndLandsOnAPortalOfTheNextMap() {
        List<Integer> road = MentorRoute.ISLAND_ROAD;
        assertEquals(MentorRoute.MUSHROOM_TOWN, road.get(0));
        assertEquals(MentorRoute.SOUTHPERRY, road.get(road.size() - 1));
        for (int i = 0; i + 1 < road.size(); i++) {
            int from = road.get(i);
            int to = road.get(i + 1);
            assertEquals(to, MentorRoute.nextOnIsland(from));
            WzMap here = map(from);
            List<WzPortal> exits = here.to(to);
            assertEquals(1, exits.size(), from + " has one portal to " + to + ": " + exits);
            WzPortal exit = exits.get(0);
            assertEquals(2, exit.type(), "a visible walking portal on " + from + ": " + exit);
            assertNotNull(map(to).named(exit.targetName()), from + "'s " + exit.name() + " lands on " + to + "'s "
                    + exit.targetName());
        }
        assertEquals(0, MentorRoute.nextOnIsland(MentorRoute.SOUTHPERRY), "Shanks's boat goes on from here");
        assertEquals(0, MentorRoute.nextOnIsland(30_001), "a side room is off the road");
    }

    @Test
    void theFirstFourHopsHaveNoWayBackAndTheRestDo() {
        List<Integer> road = MentorRoute.ISLAND_ROAD;
        for (int i = 0; i + 1 < road.size(); i++) {
            int from = road.get(i);
            int to = road.get(i + 1);
            boolean back = !map(to).to(from).isEmpty();
            assertEquals(!MentorRoute.oneWayFrom(from), back, from + " -> " + to + (back ? " has" : " has no")
                    + " way back");
        }
        assertTrue(MentorRoute.oneWayFrom(10_000));
        assertTrue(MentorRoute.oneWayFrom(40_000));
        assertFalse(MentorRoute.oneWayFrom(50_000), "the Dangerous Forest's east portal is two-way");
    }

    @Test
    void shanksStandsOnSouthperryAndTheShipLandsOnLithsDeckWithTheHiddenWayIntoTown() {
        assertNotNull(map(MentorRoute.SOUTHPERRY).npc(MentorRoute.SHANKS), "Shanks on Southperry's beach");
        WzMap lith = map(MentorRoute.LITH_HARBOR);
        WzPortal arrival = lith.portals().stream().filter(portal -> portal.id() == 0).findFirst().orElseThrow();
        assertEquals("sp", arrival.name(), "22000.js warps to portal 0 of Lith Harbor: the ship deck");
        WzPortal down = lith.portals().stream().filter(portal -> portal.id() == 30).findFirst().orElseThrow();
        assertEquals(MentorRoute.LITH_HARBOR, down.targetMap(), "the deck's hidden way into town stays on the map");
        assertNotNull(lith.named(down.targetName()), "and lands on " + down.targetName());
        assertNotNull(lith.npc(MentorRoute.LITH_TAXI), "the Regular Cab in town");
    }

    @Test
    void shanksBoardsFromLevelSevenWithTheFareOrHisLetter() {
        assertFalse(MentorRoute.mayBoard(6, 10_000, false));
        assertFalse(MentorRoute.mayBoard(7, 149, false));
        assertTrue(MentorRoute.mayBoard(7, 150, false));
        assertTrue(MentorRoute.mayBoard(2, 0, true), "the letter: free, any level");
    }

    @Test
    void eachBranchHasATownOnTheTaxiAndAnInstructorStandingWhereJobInstructorsSays() {
        assertEquals(5, MentorRoute.BRANCHES.size());
        for (Job branch : MentorRoute.BRANCHES) {
            JobInstructors.Instructor instructor = JobInstructors.of(branch);
            assertNotNull(instructor, branch.name());
            assertNotNull(map(instructor.mapId()).npc(instructor.npcId()), instructor.name() + " on " + instructor.mapId());
            assertTrue(MentorRoute.townOf(branch) >= 100_000_000 && MentorRoute.townOf(branch) < 200_000_000, branch.name());
            assertEquals(MentorRoute.townOf(branch) / 1_000_000, instructor.mapId() / 1_000_000,
                    branch + "'s instructor is in its town");
        }
        assertEquals(0, MentorRoute.townOf(null));
        assertEquals(100_000_000, MentorRoute.HUB_TOWN);
    }

    @Test
    void theIslandIsEveryMapBelowVictoria() {
        assertTrue(MentorRoute.onIsland(10_000));
        assertTrue(MentorRoute.onIsland(2_000_001));
        assertFalse(MentorRoute.onIsland(MentorRoute.LITH_HARBOR));
        assertFalse(MentorRoute.onIsland(-1));
    }

    @Test
    void aBranchWordInChatIsAPickAndTwoBranchesAreNone() {
        assertEquals(Job.MAGICIAN, MentorRoute.branchIn("i wanna be a mage"));
        assertEquals(Job.THIEF, MentorRoute.branchIn("Thief!!"));
        assertEquals(Job.BOWMAN, MentorRoute.branchIn("archer sounds fun"));
        assertNull(MentorRoute.branchIn("warrior or pirate?"), "two branches: not a pick");
        assertNull(MentorRoute.branchIn("where are we going"));
        assertEquals(Job.WARRIOR, MentorRoute.branchIn("warriors are cool but magical"), "magical is not magic");        assertNull(MentorRoute.branchIn(null));
    }

    // ---- WZ ----------------------------------------------------------------------------------

    static WzMap map(int mapId) {
        Path file = WZ_MAPS.resolve("Map" + (mapId / 100_000_000)).resolve(String.format("%09d.img.xml", mapId));
        assertTrue(Files.exists(file), file + " is in wz/");
        try {
            Element root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile()).getDocumentElement();
            List<WzPortal> portals = new ArrayList<>();
            List<int[]> npcs = new ArrayList<>();
            for (Element section : children(root)) {
                if ("portal".equals(section.getAttribute("name"))) {
                    for (Element portal : children(section)) {
                        portals.add(new WzPortal(Integer.parseInt(portal.getAttribute("name")), string(portal, "pn"),
                                integer(portal, "pt"), new Point(integer(portal, "x"), integer(portal, "y")),
                                integer(portal, "tm"), string(portal, "tn")));
                    }
                } else if ("life".equals(section.getAttribute("name"))) {
                    for (Element life : children(section)) {
                        if ("n".equals(string(life, "type"))) {
                            npcs.add(new int[] {Integer.parseInt(string(life, "id")), integer(life, "x"), integer(life, "y")});
                        }
                    }
                }
            }
            return new WzMap(mapId, portals, npcs);
        } catch (Exception failure) {
            throw new AssertionError("could not read " + file, failure);
        }
    }

    private static List<Element> children(Element parent) {
        List<Element> out = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i).getNodeType() == Node.ELEMENT_NODE) {
                out.add((Element) nodes.item(i));
            }
        }
        return out;
    }

    private static String string(Element parent, String name) {
        for (Element child : children(parent)) {
            if (name.equals(child.getAttribute("name"))) {
                return child.getAttribute("value");
            }
        }
        return "";
    }

    private static int integer(Element parent, String name) {
        String value = string(parent, name);
        return value.isEmpty() ? 0 : Integer.parseInt(value);
    }
}
