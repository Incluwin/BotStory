package server.agents.capabilities.partyquest.lmpq;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.Point;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P6h Step 2: what an LMPQ companion says and decides, pure. The directions name, for every maze room, the portal the
 * directed graph takes first toward Room 16 (and toward Room 9), in words a player uses; every line is lower case but
 * for names, with no counters, arrows or markup; a human hears one line per room and one reminder; the hand-over table
 * and its timings; the out-of-potions rest; and the Room 16 waiting spots, checked against Map.wz 809050015.
 */
class AgentLmpqCompanionPlayTest {
    private static final String BEN = "Ben";

    @Test
    void everyRoomsDirectionNamesTheFirstPortalOfTheShortestWay() {
        for (int target : new int[]{AgentLmpqDefinition.CLEAR_ROOM, AgentLmpqDefinition.RENDEZVOUS_ROOM}) {
            for (int room = 1; room <= 15; room++) {
                if (room == target) continue;
                int portal = AgentLmpqDefinition.nextPortalId(room, target);
                String word = switch (portal) {
                    case 2 -> "left";
                    case 3 -> "middle";
                    case 4 -> "right";
                    default -> throw new AssertionError("room " + room + " has no way to " + target);
                };
                String line = AgentLmpqCompanionPlay.direction(BEN, room, portal, target, false);
                assertTrue(line.startsWith("Ben, "), line);
                assertTrue(line.contains("the " + word + " portal"), room + " -> " + target + ": " + line);
                assertTrue(line.contains("room " + room), line);
                String again = AgentLmpqCompanionPlay.direction(BEN, room, portal, target, true);
                assertTrue(again.contains("still in room " + room) && again.contains("the " + word + " one"), again);
            }
        }
        assertEquals("Ben, room 9 now. the middle portal goes straight to pietri.",
                AgentLmpqCompanionPlay.direction(BEN, 9, 3, AgentLmpqDefinition.CLEAR_ROOM, false));
        assertEquals("Ben, take the portal back to room 9.",
                AgentLmpqCompanionPlay.direction(BEN, 16, 2, AgentLmpqDefinition.RENDEZVOUS_ROOM, false));
    }

    @Test
    void consecutiveRoomsAreNotToldInTheSameWords() {
        List<String> shapes = new ArrayList<>();
        for (int room = 1; room <= 3; room++) {
            shapes.add(AgentLmpqCompanionPlay.direction(BEN, room, 2, 16, false)
                    .replace(String.valueOf(room), "#"));
        }
        assertEquals(3, shapes.stream().distinct().count(), shapes.toString());
    }

    @Test
    void everyLineSoundsLikeAPlayer() {
        List<String> lines = new ArrayList<>(List.of(
                AgentLmpqCompanionPlay.plan(16), AgentLmpqCompanionPlay.plan(9),
                AgentLmpqCompanionPlay.leaderArrived(16), AgentLmpqCompanionPlay.leaderArrived(9),
                AgentLmpqCompanionPlay.regroup(16, false), AgentLmpqCompanionPlay.regroup(16, true),
                AgentLmpqCompanionPlay.atYourFeet(BEN, true), AgentLmpqCompanionPlay.atYourFeet(BEN, false),
                AgentLmpqCompanionPlay.droppedHere(BEN), AgentLmpqCompanionPlay.stillOnTheFloor(BEN),
                AgentLmpqCompanionPlay.pickedBackUp(), AgentLmpqCompanionPlay.pietri(),
                AgentLmpqCompanionPlay.shortOfCoupons(), AgentLmpqCompanionPlay.skippingRoomSix(),
                AgentLmpqCompanionPlay.bullLoose(), AgentLmpqCompanionPlay.resting(),
                AgentLmpqCompanionPlay.cleared(), AgentLmpqCompanionPlay.humanLeft(BEN),
                AgentLmpqCompanionPlay.companionWent("Mira")));
        for (int room = 1; room <= 16; room++) {
            lines.add(AgentLmpqCompanionPlay.direction(BEN, room, 3, 16, false));
            lines.add(AgentLmpqCompanionPlay.direction(BEN, room, 3, 16, true));
        }
        for (String line : lines) {
            String withoutNames = line.replace(BEN, "").replace("Mira", "");
            assertEquals(withoutNames.toLowerCase(Locale.ROOT), withoutNames, "lower case but for names: " + line);
            for (String marker : List.of("#", "[", "<", "->", "  ")) {
                assertFalse(line.contains(marker), marker + " in " + line);
            }
            assertTrue(line.length() <= 110, "short enough for one chat line: " + line);
            assertFalse(line.contains("Pietri") || line.contains("Rolly"), "npc names lower case: " + line);
        }
    }

    @Test
    void aHumanHearsOneLinePerRoomAndOneReminder() {
        long repeat = 45_000L;
        assertTrue(AgentLmpqCompanionPlay.directionDue(null, 4, 0L, repeat), "the first room");
        AgentLmpqSession.Guide told = new AgentLmpqSession.Guide(4, 1_000L, 1);
        assertFalse(AgentLmpqCompanionPlay.directionDue(told, 4, 45_999L, repeat), "not yet");
        assertTrue(AgentLmpqCompanionPlay.directionDue(told, 4, 46_000L, repeat), "the reminder");
        AgentLmpqSession.Guide reminded = new AgentLmpqSession.Guide(4, 46_000L, 2);
        assertFalse(AgentLmpqCompanionPlay.directionDue(reminded, 4, 500_000L, repeat), "never a third");
        assertTrue(AgentLmpqCompanionPlay.directionDue(reminded, 8, 46_100L, repeat), "a new room");
    }

    @Test
    void theSessionCountsTheLinesOfOneVisit() {
        AgentLmpqSession session = new AgentLmpqSession(AgentLmpqSession.Mode.HUMAN_LEADER, 1L, 1, 3, 0L);
        session.noteGuide(7, 4, 1_000L);
        session.noteGuide(7, 4, 50_000L);
        assertEquals(new AgentLmpqSession.Guide(4, 50_000L, 2), session.guide(7));
        session.noteGuide(7, 8, 60_000L);
        assertEquals(new AgentLmpqSession.Guide(8, 60_000L, 1), session.guide(7));
    }

    @Test
    void theHandOverTable() {
        Point leader = new Point(-200, 104);
        assertEquals(AgentLmpqHandOver.HandOver.WALK, AgentLmpqHandOver.handOver(new Point(100, 145), leader,
                true, true, null, true, 1_000L, 30_000L, 60));
        assertEquals(AgentLmpqHandOver.HandOver.DROP_AT_FEET, AgentLmpqHandOver.handOver(new Point(-180, 145),
                leader, true, true, new Point(-180, 110), true, 1_000L, 30_000L, 60));
        assertEquals(AgentLmpqHandOver.HandOver.DROP_BESIDE, AgentLmpqHandOver.handOver(new Point(-180, 145),
                leader, true, true, new Point(-110, 120), true, 1_000L, 30_000L, 60));
        assertEquals(AgentLmpqHandOver.HandOver.WALK, AgentLmpqHandOver.handOver(new Point(-180, 145),
                leader, true, true, new Point(-180, 300), true, 1_000L, 30_000L, 60), "it would fall away");
        assertEquals(AgentLmpqHandOver.HandOver.WALK, AgentLmpqHandOver.handOver(new Point(-180, 145),
                leader, false, true, null, true, 1_000L, 30_000L, 60), "in the air: not yet");
        assertEquals(AgentLmpqHandOver.HandOver.DROP_HERE, AgentLmpqHandOver.handOver(new Point(100, 145),
                leader, true, true, null, false, 1_000L, 30_000L, 60), "he cannot be reached");
        assertEquals(AgentLmpqHandOver.HandOver.DROP_HERE, AgentLmpqHandOver.handOver(new Point(100, 145),
                leader, true, true, null, true, 30_001L, 30_000L, 60), "the walk took too long");
        assertEquals(AgentLmpqHandOver.Place.FEET, AgentLmpqHandOver.place(AgentLmpqHandOver.HandOver.DROP_AT_FEET,
                null, leader, 60));
        assertEquals(AgentLmpqHandOver.Place.BESIDE, AgentLmpqHandOver.place(AgentLmpqHandOver.HandOver.DROP_HERE,
                new Point(-170, 110), leader, 60), "a fallback drop that lands by him");
        assertEquals(AgentLmpqHandOver.Place.HERE, AgentLmpqHandOver.place(AgentLmpqHandOver.HandOver.DROP_HERE,
                new Point(100, 145), leader, 60));
        assertEquals("Ben, coupons are at your feet.",
                AgentLmpqHandOver.line(BEN, AgentLmpqHandOver.Place.FEET, false));
        assertEquals(AgentLmpqCompanionPlay.pickedBackUp(),
                AgentLmpqHandOver.line(BEN, AgentLmpqHandOver.Place.FEET, true));
    }

    @Test
    void theNudgeAndTheRecollectTimings() {
        assertFalse(AgentLmpqHandOver.nudgeDue(10_000L, 49_999L, 40_000L));
        assertTrue(AgentLmpqHandOver.nudgeDue(10_000L, 50_000L, 40_000L));
        assertFalse(AgentLmpqHandOver.recollectDue(0L, 149_999L, 150_000L, 0, 2));
        assertTrue(AgentLmpqHandOver.recollectDue(0L, 150_000L, 150_000L, 1, 2));
        assertFalse(AgentLmpqHandOver.recollectDue(0L, 900_000L, 150_000L, 2, 2), "at most twice");
        assertEquals(170_000L, AgentLmpqHandOver.recollectAfterMs(200_000L, 170_000L), "never past the expiry");
        assertEquals(150_000L, AgentLmpqHandOver.recollectAfterMs(150_000L, 170_000L));
        assertTrue(AgentLmpqHandOver.RECOLLECT_MS < 180_000L, "the shipped value is saved before the item expiry");
    }

    @Test
    void aCompanionOutOfPotionsRestsUntilItHasHealed() {
        assertTrue(AgentLmpqCompanionPlay.resting(false, 0, 10, 399, 1_000, 0.4, 0.7));
        assertFalse(AgentLmpqCompanionPlay.resting(false, 0, 10, 400, 1_000, 0.4, 0.7), "not low yet");
        assertFalse(AgentLmpqCompanionPlay.resting(false, 10, 10, 100, 1_000, 0.4, 0.7), "it still has potions");
        assertTrue(AgentLmpqCompanionPlay.resting(true, 0, 10, 699, 1_000, 0.4, 0.7), "still healing");
        assertFalse(AgentLmpqCompanionPlay.resting(true, 0, 10, 700, 1_000, 0.4, 0.7), "healed");
        assertFalse(AgentLmpqCompanionPlay.resting(true, -1, 10, 1, 1_000, 0.4, 0.7), "an unknown count never rests");
        assertTrue(AgentLmpqCompanionPlay.facesTauromacis(65, 65));
        assertFalse(AgentLmpqCompanionPlay.facesTauromacis(64, 65));
    }

    @Test
    void theRoomSixteenSpotsAreOnItsFloorRightOfPietrisStepsAndClearOfThePortal() throws Exception {
        List<int[]> footholds = footholds("wz/Map.wz/Map/Map8/809050015.img.xml");
        int stepsEnd = footholds.stream().filter(fh -> fh[1] == fh[3] && fh[1] >= 100 && fh[1] < 140)
                .mapToInt(fh -> Math.max(fh[0], fh[2])).max().orElseThrow();
        assertEquals(-159, stepsEnd, "Pietri's steps (footholds 3-5) end at x -159");
        List<Integer> seen = new ArrayList<>();
        for (int i = 0; i < AgentLmpqDefinition.CLEAR_ROOM_WAIT_XS.size(); i++) {
            Point spot = AgentLmpqCompanionPlay.waitSpot(i);
            assertTrue(footholds.stream().anyMatch(fh -> fh[1] == spot.y && fh[3] == spot.y
                    && Math.min(fh[0], fh[2]) <= spot.x && spot.x <= Math.max(fh[0], fh[2])), "on the floor: " + spot);
            assertTrue(spot.x > stepsEnd + 20, "right of the steps: " + spot);
            assertTrue(Math.abs(spot.x - (-23)) >= 40, "clear of the portal back to Room 9 (t000, x -23): " + spot);
            assertFalse(seen.contains(spot.x), "a spot each");
            seen.add(spot.x);
        }
        assertEquals(AgentLmpqCompanionPlay.waitSpot(0), AgentLmpqCompanionPlay.waitSpot(5), "a sixth shares");
    }

    /** Every non-vertical foothold of a Map.wz map, each {x1, y1, x2, y2}. */
    static List<int[]> footholds(String path) throws Exception {
        Element root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new File(path))
                .getDocumentElement();
        Element footholdRoot = null;
        NodeList top = root.getChildNodes();
        for (int i = 0; i < top.getLength(); i++) {
            if (top.item(i) instanceof Element e && "foothold".equals(e.getAttribute("name"))) footholdRoot = e;
        }
        List<int[]> all = new ArrayList<>();
        NodeList pieces = footholdRoot.getElementsByTagName("imgdir");
        for (int i = 0; i < pieces.getLength(); i++) {
            Element fh = (Element) pieces.item(i);
            int[] xy = new int[4];
            int found = 0;
            NodeList values = fh.getChildNodes();
            for (int j = 0; j < values.getLength(); j++) {
                if (values.item(j) instanceof Element v && "int".equals(v.getTagName())) {
                    int at = List.of("x1", "y1", "x2", "y2").indexOf(v.getAttribute("name"));
                    if (at >= 0) {
                        xy[at] = Integer.parseInt(v.getAttribute("value"));
                        found++;
                    }
                }
            }
            if (found == 4 && xy[0] != xy[2]) all.add(xy);
        }
        assertTrue(all.size() >= 5, "the map's footholds were read: " + all.size());
        return all;
    }
}
