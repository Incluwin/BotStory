package server.living.roster;

import client.Character;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import server.agents.capabilities.partyquest.epq.AgentEpqAdmissionService;
import server.agents.capabilities.partyquest.epq.AgentEpqMemberState;
import server.agents.capabilities.partyquest.epq.AgentEpqPaceProfile;
import server.agents.capabilities.partyquest.epq.AgentEpqSession;
import server.agents.capabilities.partyquest.epq.AgentEpqSessionRegistry;
import server.agents.capabilities.partyquest.hpq.AgentHpqAdmissionService;
import server.agents.capabilities.partyquest.hpq.AgentHpqPaceProfile;
import server.agents.capabilities.partyquest.kpq.AgentKpqAdmissionService;
import server.agents.capabilities.partyquest.kpq.AgentKpqPaceProfile;
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
 * EPQ's entrance as companion runs go in by it (P6g EPQ step 6.2; integration rule 15): Ellin's, at the edge of the
 * Forest of Poison Haze. Its admission is EPQ's own with the leader as operator and event leader, its registry is EPQ's
 * (the operator and the members looked up apart), its pace profile is EPQ's, no background EPQ population ever runs, it
 * seats four to six (her own choice six), Ellin speaks in her own words, and the extras wait on the lobby floor left
 * of her ledge (each spot over a floor foothold of Map.wz 300030100); nothing is kept clear around her.
 */
class EpqEntranceTest {
    private static final long SEED = 42L;
    private static final long NOW = 5_000L;
    private static final int RECRUIT_MAP = 300_030_100;
    private static final Point ELLIN_AT = new Point(314, -63);

    private final Character leader = mock(Character.class);
    private final List<Character> members = List.of(leader, mock(Character.class), mock(Character.class),
            mock(Character.class), mock(Character.class));

    // ---- admission ---------------------------------------------------------------------------

    @Test
    void epqAsksOnlyEpqsAdmissionWithTheLeaderAsOperatorAndEventLeader() {
        AgentEpqSession session = new AgentEpqSession(AgentEpqSession.Mode.HUMAN_ASSISTED, SEED, 30, NOW);
        try (MockedStatic<AgentEpqAdmissionService> epq = mockStatic(AgentEpqAdmissionService.class);
             MockedStatic<AgentKpqAdmissionService> kpq = mockStatic(AgentKpqAdmissionService.class);
             MockedStatic<AgentHpqAdmissionService> hpq = mockStatic(AgentHpqAdmissionService.class)) {
            epq.when(() -> AgentEpqAdmissionService.admit(leader, leader, members, SEED, NOW))
                    .thenReturn(new AgentEpqAdmissionService.AdmissionResult(true, "EPQ party admitted", session, null));

            PqEntryPort.Admitted admitted = PqEntryPort.STOCK.admit("epq", leader, members, SEED, NOW);

            assertTrue(admitted.success());
            assertEquals("EPQ party admitted", admitted.message());
            assertSame(session, admitted.session());
            epq.verify(() -> AgentEpqAdmissionService.admit(leader, leader, members, SEED, NOW));
            epq.verifyNoMoreInteractions();
            kpq.verifyNoInteractions();
            hpq.verifyNoInteractions();
        }
    }

    @Test
    void aRefusalComesBackInEpqsAdmissionWords() {
        try (MockedStatic<AgentEpqAdmissionService> epq = mockStatic(AgentEpqAdmissionService.class)) {
            epq.when(() -> AgentEpqAdmissionService.admit(leader, leader, members, SEED, NOW))
                    .thenReturn(new AgentEpqAdmissionService.AdmissionResult(false, "Mira must be level 44-55", null,
                            null));

            PqEntryPort.Admitted refused = PqEntryPort.STOCK.admit(" EPQ ", leader, members, SEED, NOW);

            assertFalse(refused.success());
            assertEquals("Mira must be level 44-55", refused.message());
            assertNull(refused.session());
        }
    }

    @Test
    void aMembersProblemIsEpqAdmissionsOwnWords() {
        Character mira = mock(Character.class);
        try (MockedStatic<AgentEpqAdmissionService> epq = mockStatic(AgentEpqAdmissionService.class);
             MockedStatic<AgentHpqAdmissionService> hpq = mockStatic(AgentHpqAdmissionService.class)) {
            epq.when(() -> AgentEpqAdmissionService.memberProblem(mira)).thenReturn("Mira is not at EPQ");

            assertEquals("Mira is not at EPQ", PqEntrance.of("epq").memberProblem(mira, false));
            assertEquals("Mira is not at EPQ", PqEntrance.of("epq").memberProblem(mira, true));
            hpq.verifyNoInteractions();
        }
    }

    // ---- its registry ------------------------------------------------------------------------

    @Test
    void ellinLooksUpHerRunsOperatorAndItsMembersApart() {
        AgentEpqSession session = new AgentEpqSession(AgentEpqSession.Mode.HUMAN_ASSISTED, 1L, 60, 1_000L); // 60 runs it
        session.addMember(61, AgentEpqMemberState.MemberType.HUMAN);
        for (int id = 960; id <= 962; id++) {
            session.addMember(id, AgentEpqMemberState.MemberType.AGENT);
        }
        session.setLeadership(61, 960);
        AgentEpqSessionRegistry.registerComplete(session);
        try {
            PqEntrance epq = PqEntrance.of("epq");

            assertEquals(new PqEntrance.LiveSession(session.sessionId(), 61), epq.session(60, session.sessionId()));
            assertNull(epq.session(960, session.sessionId()), "a member is not the run's operator");
            assertNull(epq.session(60, "another-session"));
            assertTrue(epq.holds(960, session.sessionId()));
            assertFalse(epq.holds(60, session.sessionId()), "the operator is not one of its members");
            assertFalse(PqEntrance.of("hpq").holds(960, session.sessionId()), "HPQ's registry is not EPQ's");
            assertEquals("operator-session", epq.registrationBlocker(60, List.of(70)));
            assertEquals("member-960", epq.registrationBlocker(70, List.of(71, 960)));
            assertEquals("", epq.registrationBlocker(70, List.of(71)));
        } finally {
            AgentEpqSessionRegistry.remove(session);
        }
    }

    // ---- its pace profile and background -------------------------------------------------------

    @Test
    void epqsEntranceBuildsEpqsPaceProfileOnlyAndNoBackgroundRunsIt() {
        AgentEpqPaceProfile profile = mock(AgentEpqPaceProfile.class);
        try (MockedStatic<AgentEpqPaceProfile> epq = mockStatic(AgentEpqPaceProfile.class);
             MockedStatic<AgentKpqPaceProfile> kpq = mockStatic(AgentKpqPaceProfile.class);
             MockedStatic<AgentHpqPaceProfile> hpq = mockStatic(AgentHpqPaceProfile.class)) {
            epq.when(() -> AgentEpqPaceProfile.load()).thenReturn(profile);

            assertSame(profile, PqEntrance.of("epq").paceLoader().get());
            epq.verify(() -> AgentEpqPaceProfile.load());
            epq.verifyNoMoreInteractions();
            kpq.verifyNoInteractions();
            hpq.verifyNoInteractions();
        }
        assertFalse(PqEntrance.of("epq").backgroundRuns(), "no background EPQ population exists");
        assertFalse(PqEntrance.of("epq").admitsOnIo(), "admission prepares each companion: on companion-ops");
    }

    // ---- Ellin -------------------------------------------------------------------------------

    @Test
    void ellinSeatsFourToSixAndSpeaksInHerOwnWords() {
        PqEntrance epq = PqEntrance.of("epq");

        assertEquals("epq", epq.key());
        assertEquals(new PqEntrance.SeatRules(4, 6, 6), epq.seatRules());
        assertEquals(new PqEntrance.SeatRules(4, 6, 6), PqEntrance.seats("epq"));
        assertEquals(2_133_000, epq.entryNpcId());
        assertEquals(RECRUIT_MAP, epq.definition().recruitMapId());
        assertEquals("epq", PqEntrance.keyAt(RECRUIT_MAP), "companions at Ellin restock to the epq standard");
        assertNull(PqEntrance.keepingClearOn(RECRUIT_MAP), "nobody can stand by her: nothing is kept clear");
        assertArrayEquals(new int[] {9_300_172, 9_300_175, 9_300_182}, epq.dpsMobIds(),
                "the Poisoned Lord Tree, the Poison Flower and the Super-Charged Poison Golem");
        assertSame(EllinLines.VOICE, epq.voice());
        assertTrue(PqEntrance.keys().contains("epq"));
    }

    @Test
    void theExtrasWaitOnTheLobbyFloorLeftOfHerLedgeNeverOnIt() throws Exception {
        List<int[]> footholds = flatFootholds();
        MapleMap map = ellinsMap();
        when(map.getPointBelow(any())).thenReturn(null); // the spot as reckoned, before it is grounded
        PqEntrance epq = PqEntrance.of("epq");
        int[] xs = {164, 134, 104, 74, 44};
        for (int i = 0; i < xs.length; i++) {
            Point spot = epq.beside(map, i);
            assertEquals(new Point(xs[i], ELLIN_AT.y), spot, "extra " + i);
            assertTrue(spot.x < 178, "off her ledge (x 178-398): " + spot);
            int x = spot.x;
            assertTrue(footholds.stream().anyMatch(fh -> fh[0] <= x && x <= fh[1] && fh[2] == 150),
                    "the lobby floor below extra " + i + " at x " + x);
        }
        Point ground = new Point(164, 150);
        when(map.getPointBelow(new Point(164, -63))).thenReturn(ground);
        assertEquals(ground, epq.beside(map, 0), "grounded on the floor");
    }

    @Test
    void anOwnerByEllinIsNeverToldToStandClear() {
        MapleMap map = ellinsMap();
        PqEntrance epq = PqEntrance.of("epq");

        assertNull(epq.keptClearAt(map, new Point(314, -42)), "on her ledge");
        assertNull(epq.keptClearAt(map, new Point(314, 150)), "on the floor below her");
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** The recruit map with Ellin where Map.wz puts her. */
    private static MapleMap ellinsMap() {
        MapleMap map = mock(MapleMap.class);
        NPC ellin = mock(NPC.class);
        when(ellin.getPosition()).thenReturn(ELLIN_AT);
        when(map.getNPCById(2_133_000)).thenReturn(ellin);
        return map;
    }

    /** The flat footholds of Map.wz 300030100, each {min x, max x, y}. */
    private static List<int[]> flatFootholds() throws Exception {
        Element root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new File("wz/Map.wz/Map/Map3/300030100.img.xml")).getDocumentElement();
        Element footholdRoot = null;
        NodeList top = root.getChildNodes();
        for (int i = 0; i < top.getLength(); i++) {
            if (top.item(i) instanceof Element e && "foothold".equals(e.getAttribute("name"))) {
                footholdRoot = e;
            }
        }
        List<int[]> flat = new ArrayList<>();
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
            if (found == 4 && xy[1] == xy[3] && xy[0] != xy[2]) {
                flat.add(new int[] {Math.min(xy[0], xy[2]), Math.max(xy[0], xy[2]), xy[1]});
            }
        }
        assertTrue(flat.size() >= 10, "the map's footholds were read: " + flat.size());
        return flat;
    }
}
