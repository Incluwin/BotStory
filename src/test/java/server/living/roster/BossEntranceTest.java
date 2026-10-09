package server.living.roster;

import config.LivingWorldConfig;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.expedition.boss.AgentBossRunCatalog;
import server.life.NPC;
import server.living.LivingWorld;
import server.maps.GenericPortal;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A boss's entrance for companions: Mu Young's room (105100100) for Easy Balrog. A companion waiting to sign up
 * stands at its own spot beside her (she stands at 617,246 in the WZ): 50 px to her left for the first, 30 px further
 * for each next one, on the ground below. A bench ally (Part B) comes in at the top of her stairs, where players
 * coming down land (top00), and leaves by the room's way out to Perion (outPerrion). P8: Zakum's entrance is Adobis's
 * room behind the Door to Zakum (211042400); its companions top up at Glibber on Ice Valley II (211040200) on the way,
 * and allies come in and leave by west00, the way to the door. While the switch lists zakum, Ice Valley II keeps
 * Zakum's standard and Adobis's room holds a companion's trip out (CompanionBossRuns.holdsTripsAt); with it off,
 * neither does, and Easy Balrog's maps keep what they had.
 */
class BossEntranceTest {
    private static final int MU_YOUNG = 1061014;
    private static final int ADOBIS = 2030013;

    @Test
    void easyBalrogsEntranceIsMuYoungsRoom() {
        assertSame(AgentBossRunCatalog.EASY_BALROG, BossEntrance.EASY_BALROG.definition());
        assertSame(BossEntrance.EASY_BALROG, BossEntrance.atRecruitMap(105100100));
        assertNull(BossEntrance.atRecruitMap(105100400), "the battle map is no entrance");
        assertNull(BossEntrance.atRecruitMap(103000000), "Lakelis's is a party quest's");
        assertSame(BossEntrance.EASY_BALROG, BossEntrance.of(" Easy_Balrog "));
        assertNull(BossEntrance.of("balrog"));
        assertNull(BossEntrance.of(null));
        assertFalse(BossEntrance.EASY_BALROG.holdsTripsAtEntry(), "a trip out of Mu Young's room stays as it is");
    }

    @Test
    void easyBalrogsApproachWithAStoreIsAntTunnelPark() {
        assertEquals(105070001, BossEntrance.EASY_BALROG.approachMapId());
        assertSame(BossEntrance.EASY_BALROG, BossEntrance.atApproachMap(105070001));
        assertNull(BossEntrance.atApproachMap(105100100), "Mu Young's room is her entrance, not its approach");
        assertNull(BossEntrance.atApproachMap(105070000), "the field next to the park");
        assertNull(BossEntrance.atApproachMap(0));
    }

    @Test
    void theApproachsStoreIsTheTwentyFourHourMobileStoreAsThePotionSupplierCatalogListsIt() {
        assertEquals(1061001, BossEntrance.EASY_BALROG.approachStoreNpcId(), "Ant Tunnel Park's 24 Hr Mobile Store");
    }

    @Test
    void eachOrdinalWaitsThirtyPixelsFurtherLeftOnTheGroundBelow() {
        MapleMap room = muYoungsRoom(new Point(617, 246));
        when(room.getPointBelow(any(Point.class))).thenAnswer(inv -> new Point(((Point) inv.getArgument(0)).x, 252));

        List<Point> spots = new ArrayList<>();
        for (int ordinal = 0; ordinal < 5; ordinal++) {
            spots.add(BossEntrance.EASY_BALROG.spot(room, ordinal));
        }

        assertEquals(List.of(new Point(567, 252), new Point(537, 252), new Point(507, 252), new Point(477, 252),
                new Point(447, 252)), spots);
        verify(room, times(1)).getNPCById(MU_YOUNG); // looked for once per map, not on every tick
    }

    @Test
    void noGroundBelowKeepsHerHeightAndNoMuYoungIsNoSpot() {
        MapleMap room = muYoungsRoom(new Point(617, 246));
        assertEquals(new Point(567, 246), BossEntrance.EASY_BALROG.spot(room, 0));

        assertNull(BossEntrance.EASY_BALROG.spot(mock(MapleMap.class), 0));
        assertNull(BossEntrance.EASY_BALROG.spot(null, 0));
    }

    @Test
    void arrivalIsTop00AndExitIsOutPerrion() throws IOException {
        assertEquals("top00", BossEntrance.EASY_BALROG.arrivalPortal());
        assertEquals("outPerrion", BossEntrance.EASY_BALROG.exitPortal());
        String wz = Files.readString(Path.of("wz/Map.wz/Map/Map1/105100100.img.xml"));
        assertTrue(wzPortal("top00", -65, -264).matcher(wz).find(), "the WZ names her stairs' landing top00");
        assertTrue(wzPortal("outPerrion", -691, 246).matcher(wz).find(), "the WZ names the way out outPerrion");

        MapleMap room = mock(MapleMap.class);
        Portal top = portal("top00", -65, -264);
        when(room.getPortal("top00")).thenReturn(top);
        when(room.getPortal("outPerrion")).thenReturn(portal("outPerrion", -691, 246));

        assertEquals(new Point(-65, -264), BossEntrance.EASY_BALROG.arrivalAt(room));
        assertEquals(new Point(-691, 246), BossEntrance.EASY_BALROG.exitAt(room));
        BossEntrance.EASY_BALROG.arrivalAt(room).translate(10, 10);
        assertEquals(new Point(-65, -264), top.getPosition(), "a copy: the portal never moves");

        assertNull(BossEntrance.EASY_BALROG.arrivalAt(mock(MapleMap.class)), "no such portal on that map");
        assertNull(BossEntrance.EASY_BALROG.exitAt(mock(MapleMap.class)), "no such portal on that map");
        assertNull(BossEntrance.EASY_BALROG.arrivalAt(null));
        assertNull(BossEntrance.EASY_BALROG.exitAt(null));
    }

    // ---- P8: Zakum, signed up with Adobis -----------------------------------------------------------

    @Test
    void zakumsEntranceIsAdobissRoomBehindTheDoor() {
        assertSame(AgentBossRunCatalog.ZAKUM, BossEntrance.ZAKUM.definition());
        assertSame(BossEntrance.ZAKUM, BossEntrance.atRecruitMap(211042400));
        assertNull(BossEntrance.atRecruitMap(211042300), "the Door to Zakum is on the way, not her room");
        assertNull(BossEntrance.atRecruitMap(280030000), "the altar is no entrance");
        assertSame(BossEntrance.ZAKUM, BossEntrance.of(" Zakum "));
        assertTrue(BossEntrance.ZAKUM.holdsTripsAtEntry(), "no shop is in reach of her room: trips out are held");
    }

    @Test
    void zakumsApproachWithAStoreIsIceValleyTwoWithGlibber() {
        assertEquals(211040200, BossEntrance.ZAKUM.approachMapId());
        assertSame(BossEntrance.ZAKUM, BossEntrance.atApproachMap(211040200));
        assertNull(BossEntrance.atApproachMap(211042400), "Adobis's room is the entrance, not its approach");
        assertNull(BossEntrance.atApproachMap(211000000), "El Nath town is not the approach");
        assertEquals(2030009, BossEntrance.ZAKUM.approachStoreNpcId(), "Glibber on Ice Valley II");
    }

    @Test
    void iceValleyTwoKeepsZakumsStandardAndAdobissRoomHoldsTripsOnlyWhileTheSwitchListsZakum() {
        LivingWorld world = mock(LivingWorld.class);
        LivingWorldConfig config = new LivingWorldConfig();
        when(world.config()).thenReturn(config);
        CompanionBossRuns runs = new CompanionBossRuns(world);

        config.COMPANION_BOSS_ENABLED_TYPES = "easy_balrog,zakum";
        assertEquals("zakum", runs.approachKeyAt(211040200), "Glibber's field on the way");
        assertEquals("zakum", runs.restockKeyAt(211042400), "Adobis's room");
        assertTrue(runs.holdsTripsAt(211042400), "no trip leaves Adobis's room");
        assertFalse(runs.holdsTripsAt(211040200), "Ice Valley II is the approach: its store is right there");
        assertFalse(runs.holdsTripsAt(211042300), "the Door to Zakum is no entrance");
        assertEquals("easy_balrog", runs.approachKeyAt(105070001), "Ant Tunnel Park unchanged");
        assertFalse(runs.holdsTripsAt(105100100), "a trip out of Mu Young's room stays as it is");

        config.COMPANION_BOSS_ENABLED_TYPES = "easy_balrog";
        assertNull(runs.approachKeyAt(211040200), "the switch off");
        assertNull(runs.restockKeyAt(211042400));
        assertFalse(runs.holdsTripsAt(211042400), "the switch off: no hold");
        assertEquals("easy_balrog", runs.approachKeyAt(105070001));
        assertEquals("easy_balrog", runs.restockKeyAt(105100100));

        config.COMPANION_BOSS_ENABLED_TYPES = "zakum";
        assertNull(runs.approachKeyAt(105070001), "Easy Balrog's own switch");
        assertTrue(runs.holdsTripsAt(211042400));
    }

    @Test
    void zakumsCompanionsWaitLeftOfAdobisThirtyPixelsApart() {
        MapleMap room = mock(MapleMap.class);
        NPC adobis = mock(NPC.class);
        when(adobis.getPosition()).thenReturn(new Point(-800, -236));
        when(room.getNPCById(ADOBIS)).thenReturn(adobis);
        when(room.getPointBelow(any(Point.class))).thenAnswer(inv -> new Point(((Point) inv.getArgument(0)).x, -230));

        List<Point> spots = new ArrayList<>();
        for (int ordinal = 0; ordinal < 3; ordinal++) {
            spots.add(BossEntrance.ZAKUM.spot(room, ordinal));
        }

        assertEquals(List.of(new Point(-850, -230), new Point(-880, -230), new Point(-910, -230)), spots);
    }

    @Test
    void zakumsAlliesComeInAndLeaveByWest00() throws IOException {
        assertEquals("west00", BossEntrance.ZAKUM.arrivalPortal());
        assertEquals("west00", BossEntrance.ZAKUM.exitPortal());
        String wz = Files.readString(Path.of("wz/Map.wz/Map/Map2/211042400.img.xml"));
        assertTrue(wzPortal("west00", -1601, -339).matcher(wz).find(), "the WZ names the way to the door west00");
        assertTrue(Pattern.compile("<string name=\"id\" value=\"" + ADOBIS + "\"/>\\s*<int name=\"x\" value=\"-800\"/>"
                + "\\s*<int name=\"y\" value=\"-236\"/>").matcher(wz).find(), "Adobis stands at -800,-236");
    }

    private static Pattern wzPortal(String name, int x, int y) {
        return Pattern.compile("<string name=\"pn\" value=\"" + name + "\"/>\\s*<int name=\"pt\" value=\"\\d+\"/>"
                + "\\s*<int name=\"x\" value=\"" + x + "\"/>\\s*<int name=\"y\" value=\"" + y + "\"/>");
    }

    private static Portal portal(String name, int x, int y) {
        GenericPortal portal = new GenericPortal(Portal.MAP_PORTAL);
        portal.setName(name);
        portal.setPosition(new Point(x, y));
        return portal;
    }

    private static MapleMap muYoungsRoom(Point at) {
        MapleMap room = mock(MapleMap.class);
        NPC muYoung = mock(NPC.class);
        when(muYoung.getPosition()).thenReturn(at);
        when(room.getNPCById(MU_YOUNG)).thenReturn(muYoung);
        return room;
    }
}
