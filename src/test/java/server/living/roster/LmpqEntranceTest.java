package server.living.roster;

import client.Character;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import server.agents.capabilities.partyquest.epq.AgentEpqAdmissionService;
import server.agents.capabilities.partyquest.epq.AgentEpqPaceProfile;
import server.agents.capabilities.partyquest.hpq.AgentHpqAdmissionService;
import server.agents.capabilities.partyquest.kpq.AgentKpqAdmissionService;
import server.agents.capabilities.partyquest.kpq.AgentKpqPaceProfile;
import server.agents.capabilities.partyquest.lmpq.AgentLmpqAdmissionService;
import server.agents.capabilities.partyquest.lmpq.AgentLmpqMemberState;
import server.agents.capabilities.partyquest.lmpq.AgentLmpqPaceProfile;
import server.agents.capabilities.partyquest.lmpq.AgentLmpqSession;
import server.agents.capabilities.partyquest.lmpq.AgentLmpqSessionRegistry;
import server.agents.capabilities.partyquest.opq.AgentOpqAdmissionService;
import server.agents.capabilities.partyquest.opq.AgentOpqPaceProfile;
import server.life.NPC;
import server.maps.MapleMap;

import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.Point;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * LMPQ's entrance as companion runs go in by it (P6h Step 5): Rolly's, on his sky platform above Ludibrium. Its
 * admission is LMPQ's own with the leader as operator and event leader, its registry is LMPQ's (the operator and the
 * members looked up apart), its pace profile is LMPQ's, no background LMPQ population ever runs, it seats three to six
 * (his own choice six, user decision D3), Rolly speaks in his own words, the extras wait on his platform to his left
 * (each spot over foothold 251 of Map.wz 220000000, off his click spot, and far from the hidden portal ph00_1 the party
 * arrives by at x 242), and an owner at Rolly has his companions stand clear of him.
 */
class LmpqEntranceTest {
    private static final long SEED = 42L;
    private static final long NOW = 5_000L;
    private static final int RECRUIT_MAP = 220_000_000;
    private static final Point ROLLY_AT = new Point(76, -712);
    /** The hidden portal ph00_1 of Map.wz 220000000, where a party comes up onto his platform. */
    private static final int PORTAL_X = 242;

    private final Character leader = mock(Character.class);
    private final List<Character> members = List.of(leader, mock(Character.class), mock(Character.class),
            mock(Character.class), mock(Character.class), mock(Character.class));

    // ---- admission ---------------------------------------------------------------------------

    @Test
    void lmpqAsksOnlyLmpqsAdmissionWithTheLeaderAsOperatorAndEventLeader() {
        AgentLmpqSession session = new AgentLmpqSession(AgentLmpqSession.Mode.HUMAN_LEADER, SEED, 30, 6, NOW);
        try (MockedStatic<AgentLmpqAdmissionService> lmpq = mockStatic(AgentLmpqAdmissionService.class);
             MockedStatic<AgentOpqAdmissionService> opq = mockStatic(AgentOpqAdmissionService.class);
             MockedStatic<AgentEpqAdmissionService> epq = mockStatic(AgentEpqAdmissionService.class);
             MockedStatic<AgentKpqAdmissionService> kpq = mockStatic(AgentKpqAdmissionService.class);
             MockedStatic<AgentHpqAdmissionService> hpq = mockStatic(AgentHpqAdmissionService.class)) {
            lmpq.when(() -> AgentLmpqAdmissionService.admit(leader, leader, members, SEED, NOW))
                    .thenReturn(new AgentLmpqAdmissionService.AdmissionResult(true, "LMPQ party admitted", session,
                            null));

            PqEntryPort.Admitted admitted = PqEntryPort.STOCK.admit("lmpq", leader, members, SEED, NOW);

            assertTrue(admitted.success());
            assertEquals("LMPQ party admitted", admitted.message());
            assertSame(session, admitted.session());
            lmpq.verify(() -> AgentLmpqAdmissionService.admit(leader, leader, members, SEED, NOW));
            lmpq.verifyNoMoreInteractions();
            opq.verifyNoInteractions();
            epq.verifyNoInteractions();
            kpq.verifyNoInteractions();
            hpq.verifyNoInteractions();
        }
    }

    @Test
    void aRefusalComesBackInLmpqsAdmissionWords() {
        try (MockedStatic<AgentLmpqAdmissionService> lmpq = mockStatic(AgentLmpqAdmissionService.class)) {
            lmpq.when(() -> AgentLmpqAdmissionService.admit(leader, leader, members, SEED, NOW))
                    .thenReturn(new AgentLmpqAdmissionService.AdmissionResult(false, "Mira must be level 51-70", null,
                            null));

            PqEntryPort.Admitted refused = PqEntryPort.STOCK.admit(" LMPQ ", leader, members, SEED, NOW);

            assertFalse(refused.success());
            assertEquals("Mira must be level 51-70", refused.message());
            assertNull(refused.session());
        }
    }

    @Test
    void aMembersProblemIsLmpqAdmissionsOwnWords() {
        Character mira = mock(Character.class);
        try (MockedStatic<AgentLmpqAdmissionService> lmpq = mockStatic(AgentLmpqAdmissionService.class);
             MockedStatic<AgentOpqAdmissionService> opq = mockStatic(AgentOpqAdmissionService.class)) {
            lmpq.when(() -> AgentLmpqAdmissionService.memberProblem(mira))
                    .thenReturn("Mira is not at the LMPQ entrance");

            assertEquals("Mira is not at the LMPQ entrance", PqEntrance.of("lmpq").memberProblem(mira, false));
            assertEquals("Mira is not at the LMPQ entrance", PqEntrance.of("lmpq").memberProblem(mira, true));
            opq.verifyNoInteractions();
        }
    }

    // ---- its registry ------------------------------------------------------------------------

    @Test
    void rollyLooksUpHisRunsOperatorAndItsMembersApart() {
        AgentLmpqSession session = new AgentLmpqSession(AgentLmpqSession.Mode.HUMAN_LEADER, 1L, 60, 4, 1_000L);
        session.addMember(61, AgentLmpqMemberState.MemberType.HUMAN); // 60 runs it
        for (int id = 960; id <= 962; id++) {
            session.addMember(id, AgentLmpqMemberState.MemberType.AGENT);
        }
        session.setLeadership(61, 960);
        AgentLmpqSessionRegistry.registerComplete(session);
        try {
            PqEntrance lmpq = PqEntrance.of("lmpq");

            assertEquals(new PqEntrance.LiveSession(session.sessionId(), 61), lmpq.session(60, session.sessionId()));
            assertNull(lmpq.session(960, session.sessionId()), "a member is not the run's operator");
            assertNull(lmpq.session(60, "another-session"));
            assertTrue(lmpq.holds(960, session.sessionId()));
            assertFalse(lmpq.holds(60, session.sessionId()), "the operator is not one of its members");
            assertFalse(PqEntrance.of("opq").holds(960, session.sessionId()), "OPQ's registry is not LMPQ's");
            assertEquals("operator-session", lmpq.registrationBlocker(60, List.of(70)));
            assertEquals("member-960", lmpq.registrationBlocker(70, List.of(71, 960)));
            assertEquals("", lmpq.registrationBlocker(70, List.of(71)));
        } finally {
            AgentLmpqSessionRegistry.remove(session);
        }
    }

    // ---- its pace profile and background -------------------------------------------------------

    @Test
    void lmpqsEntranceBuildsLmpqsPaceProfileOnlyAndNoBackgroundRunsIt() {
        AgentLmpqPaceProfile profile = mock(AgentLmpqPaceProfile.class);
        try (MockedStatic<AgentLmpqPaceProfile> lmpq = mockStatic(AgentLmpqPaceProfile.class);
             MockedStatic<AgentOpqPaceProfile> opq = mockStatic(AgentOpqPaceProfile.class);
             MockedStatic<AgentEpqPaceProfile> epq = mockStatic(AgentEpqPaceProfile.class);
             MockedStatic<AgentKpqPaceProfile> kpq = mockStatic(AgentKpqPaceProfile.class)) {
            lmpq.when(() -> AgentLmpqPaceProfile.load()).thenReturn(profile);

            assertSame(profile, PqEntrance.of("lmpq").paceLoader().get());
            lmpq.verify(() -> AgentLmpqPaceProfile.load());
            lmpq.verifyNoMoreInteractions();
            opq.verifyNoInteractions();
            epq.verifyNoInteractions();
            kpq.verifyNoInteractions();
        }
        assertFalse(PqEntrance.of("lmpq").backgroundRuns(), "no background LMPQ population exists");
        assertFalse(PqEntrance.of("lmpq").admitsOnIo(), "admission prepares each companion: on companion-ops");
        assertNull(PqEntrance.of("lmpq").branchPreference(), "only Wonky prefers job branches");
    }

    // ---- Rolly -------------------------------------------------------------------------------

    @Test
    void rollySeatsThreeToSixPrefersSixAndSpeaksInHisOwnWords() {
        PqEntrance lmpq = PqEntrance.of("lmpq");

        assertEquals("lmpq", lmpq.key());
        assertEquals("LudiMazePQ", lmpq.definition().eventManagerName());
        assertEquals(new PqEntrance.SeatRules(3, 6, 6), lmpq.seatRules(), "user decision D3: six");
        assertEquals(new PqEntrance.SeatRules(3, 6, 6), PqEntrance.seats("lmpq"));
        assertEquals(9_103_001, lmpq.entryNpcId());
        assertEquals(RECRUIT_MAP, lmpq.definition().recruitMapId());
        assertEquals("lmpq", PqEntrance.keyAt(RECRUIT_MAP), "companions at Rolly restock to the lmpq standard");
        assertSame(lmpq, PqEntrance.keepingClearOn(RECRUIT_MAP), "his click spot is kept clear");
        assertArrayEquals(new int[] {9_400_211, 9_400_213, 9_400_217}, lmpq.dpsMobIds(),
                "the maze's Dark Stone Golem, Dark Jr. Yeti and Flyeye");
        assertSame(RollyLines.VOICE, lmpq.voice());
        assertTrue(PqEntrance.keys().contains("lmpq"));
        assertTrue(config.CompanionPqKeys.known("lmpq"), "the kill switch may list it");
    }

    @Test
    void theExtrasWaitOnHisPlatformToHisLeftOffHisClickSpotAndFarFromThePortal() throws Exception {
        List<int[]> footholds = footholds();
        MapleMap map = rollysMap();
        when(map.getPointBelow(any())).thenReturn(null); // the spot as reckoned, before it is grounded
        PqEntrance lmpq = PqEntrance.of("lmpq");
        int[] xs = {-24, -50, -76, -102, -128, -154};
        for (int i = 0; i < xs.length; i++) {
            Point spot = lmpq.beside(map, i);
            assertEquals(new Point(xs[i], ROLLY_AT.y), spot, "extra " + i);
            int x = spot.x;
            assertTrue(footholds.stream().anyMatch(fh -> under(fh, x) && atY(fh, x) == -705),
                    "his platform (foothold 251) below extra " + i + " at x " + x);
            assertTrue(footholds.stream().noneMatch(fh -> under(fh, x) && atY(fh, x) > -780 && atY(fh, x) < -705),
                    "nothing between extra " + i + " and his platform");
            assertTrue(x < 26, "off his click spot (x 26 to 126): " + x);
            assertTrue(PORTAL_X - x > 200, "far from the portal the party comes up by: " + x);
            assertTrue(lmpq.keptClearAt(map, new Point(x, -705)) == null, "outside the kept-clear zone: " + x);
        }
        Point ground = new Point(-24, -705);
        when(map.getPointBelow(new Point(-24, -712))).thenReturn(ground);
        assertEquals(ground, lmpq.beside(map, 0), "grounded on his platform");
    }

    @Test
    void anOwnerAtRollyHasHisCompanionsStandClearAndNowhereElse() {
        MapleMap map = rollysMap();
        PqEntrance lmpq = PqEntrance.of("lmpq");

        assertEquals(ROLLY_AT, lmpq.keptClearAt(map, new Point(76, -705)), "at him");
        assertEquals(ROLLY_AT, lmpq.keptClearAt(map, new Point(26, -705)), "his click spot's left end");
        assertEquals(ROLLY_AT, lmpq.keptClearAt(map, new Point(150, -737)), "on the steps beside him");
        assertEquals(ROLLY_AT, lmpq.keptClearAt(map, new Point(100, -780)), "on the spawn ledge above him");
        assertNull(lmpq.keptClearAt(map, new Point(PORTAL_X, -705)), "at the portal the party comes up by");
        assertNull(lmpq.keptClearAt(map, new Point(-50, -705)), "at the extras' spots");
        assertNull(lmpq.keptClearAt(map, new Point(76, -211)), "on the ground far below him");
        assertNull(lmpq.keptClearAt(map, new Point(367, 45)), "at the portal's foot in town");
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** The recruit map with Rolly where Map.wz puts him. */
    private static MapleMap rollysMap() {
        MapleMap map = mock(MapleMap.class);
        NPC rolly = mock(NPC.class);
        when(rolly.getPosition()).thenReturn(ROLLY_AT);
        when(map.getNPCById(9_103_001)).thenReturn(rolly);
        return map;
    }

    private static boolean under(int[] fh, int x) {
        return Math.min(fh[0], fh[2]) <= x && x <= Math.max(fh[0], fh[2]) && fh[0] != fh[2];
    }

    /** The foothold's y at {@code x}. */
    private static int atY(int[] fh, int x) {
        return fh[1] + (fh[3] - fh[1]) * (x - fh[0]) / (fh[2] - fh[0]);
    }

    /** Every non-vertical foothold of Map.wz 220000000, each {x1, y1, x2, y2}. */
    private static List<int[]> footholds() throws Exception {
        Element root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new File("wz/Map.wz/Map/Map2/220000000.img.xml")).getDocumentElement();
        Element footholdRoot = null;
        NodeList top = root.getChildNodes();
        for (int i = 0; i < top.getLength(); i++) {
            if (top.item(i) instanceof Element e && "foothold".equals(e.getAttribute("name"))) {
                footholdRoot = e;
            }
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
            if (found == 4 && xy[0] != xy[2]) {
                all.add(xy);
            }
        }
        assertTrue(all.size() >= 100, "the map's footholds were read: " + all.size());
        return all;
    }
}
