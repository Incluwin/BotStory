package server.living.roster;

import client.Character;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import server.agents.capabilities.partyquest.epq.AgentEpqAdmissionService;
import server.agents.capabilities.partyquest.kpq.AgentKpqPaceProfile;
import server.agents.capabilities.partyquest.opq.AgentOpqAdmissionService;
import server.agents.capabilities.partyquest.opq.AgentOpqPaceProfile;
import server.agents.capabilities.partyquest.ppq.AgentPpqAdmissionService;
import server.agents.capabilities.partyquest.ppq.AgentPpqMemberState;
import server.agents.capabilities.partyquest.ppq.AgentPpqPaceProfile;
import server.agents.capabilities.partyquest.ppq.AgentPpqSession;
import server.agents.capabilities.partyquest.ppq.AgentPpqSessionRegistry;
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
 * PPQ's entrance as companion runs go in by it (P6i PPQ step 6, plan 11.2): Guon's, over the pirate ship. Its admission
 * is PPQ's own with the leader as operator and event leader, its registry is PPQ's (the operator and the members looked
 * up apart), its pace profile is PPQ's, no background PPQ population ever runs, it seats four to six (his own choice
 * six), Guon speaks in his own words, the extras wait on the floor left of his platform (each spot read against the
 * real footholds of Map.wz 251010404: the floor at y 242 below, nothing of his platform or the slope between), and an
 * owner on his platform has his companions stand clear of it, never on the floor below.
 */
class PpqEntranceTest {
    private static final long SEED = 42L;
    private static final long NOW = 5_000L;
    private static final int RECRUIT_MAP = 251_010_404;
    private static final Point GUON_AT = new Point(-1_277, 28);

    private final Character leader = mock(Character.class);
    private final List<Character> members = List.of(leader, mock(Character.class), mock(Character.class),
            mock(Character.class));

    // ---- admission ---------------------------------------------------------------------------

    @Test
    void ppqAsksOnlyPpqsAdmissionWithTheLeaderAsOperatorAndEventLeader() {
        AgentPpqSession session = new AgentPpqSession(AgentPpqSession.Mode.HUMAN_LEADER, SEED, 30, false, NOW);
        try (MockedStatic<AgentPpqAdmissionService> ppq = mockStatic(AgentPpqAdmissionService.class);
             MockedStatic<AgentOpqAdmissionService> opq = mockStatic(AgentOpqAdmissionService.class);
             MockedStatic<AgentEpqAdmissionService> epq = mockStatic(AgentEpqAdmissionService.class)) {
            ppq.when(() -> AgentPpqAdmissionService.admit(leader, leader, members, SEED, NOW))
                    .thenReturn(new AgentPpqAdmissionService.AdmissionResult(true, "PPQ party admitted", session));

            PqEntryPort.Admitted admitted = PqEntryPort.STOCK.admit("ppq", leader, members, SEED, NOW);

            assertTrue(admitted.success());
            assertEquals("PPQ party admitted", admitted.message());
            assertSame(session, admitted.session());
            ppq.verify(() -> AgentPpqAdmissionService.admit(leader, leader, members, SEED, NOW));
            ppq.verifyNoMoreInteractions();
            opq.verifyNoInteractions();
            epq.verifyNoInteractions();
        }
    }

    @Test
    void aRefusalComesBackInPpqsAdmissionWords() {
        try (MockedStatic<AgentPpqAdmissionService> ppq = mockStatic(AgentPpqAdmissionService.class)) {
            ppq.when(() -> AgentPpqAdmissionService.admit(leader, leader, members, SEED, NOW))
                    .thenReturn(new AgentPpqAdmissionService.AdmissionResult(false, "Mira must be level 55-100",
                            null));

            PqEntryPort.Admitted refused = PqEntryPort.STOCK.admit(" PPQ ", leader, members, SEED, NOW);

            assertFalse(refused.success());
            assertEquals("Mira must be level 55-100", refused.message());
            assertNull(refused.session());
        }
    }

    @Test
    void aMembersProblemIsPpqAdmissionsOwnWords() {
        Character mira = mock(Character.class);
        try (MockedStatic<AgentPpqAdmissionService> ppq = mockStatic(AgentPpqAdmissionService.class);
             MockedStatic<AgentOpqAdmissionService> opq = mockStatic(AgentOpqAdmissionService.class)) {
            ppq.when(() -> AgentPpqAdmissionService.memberProblem(mira)).thenReturn("Mira is not at PPQ");

            assertEquals("Mira is not at PPQ", PqEntrance.of("ppq").memberProblem(mira, false));
            assertEquals("Mira is not at PPQ", PqEntrance.of("ppq").memberProblem(mira, true));
            opq.verifyNoInteractions();
        }
    }

    // ---- its registry ------------------------------------------------------------------------

    @Test
    void guonLooksUpHisRunsOperatorAndItsMembersApart() {
        AgentPpqSession session = new AgentPpqSession(AgentPpqSession.Mode.HUMAN_LEADER, 1L, 60, false, 1_000L);
        session.addMember(61, AgentPpqMemberState.MemberType.HUMAN); // 60 runs it
        for (int id = 960; id <= 964; id++) {
            session.addMember(id, AgentPpqMemberState.MemberType.AGENT);
        }
        session.setLeadership(61, 960);
        AgentPpqSessionRegistry.registerComplete(session);
        try {
            PqEntrance ppq = PqEntrance.of("ppq");

            assertEquals(new PqEntrance.LiveSession(session.sessionId(), 61), ppq.session(60, session.sessionId()));
            assertNull(ppq.session(960, session.sessionId()), "a member is not the run's operator");
            assertNull(ppq.session(60, "another-session"));
            assertTrue(ppq.holds(960, session.sessionId()));
            assertFalse(ppq.holds(60, session.sessionId()), "the operator is not one of its members");
            assertFalse(PqEntrance.of("opq").holds(960, session.sessionId()), "OPQ's registry is not PPQ's");
            assertEquals("operator-session", ppq.registrationBlocker(60, List.of(70)));
            assertEquals("member-960", ppq.registrationBlocker(70, List.of(71, 960)));
            assertEquals("", ppq.registrationBlocker(70, List.of(71)));
        } finally {
            AgentPpqSessionRegistry.remove(session);
        }
    }

    // ---- its pace profile and background -------------------------------------------------------

    @Test
    void ppqsEntranceBuildsPpqsPaceProfileOnlyAndNoBackgroundRunsIt() {
        AgentPpqPaceProfile profile = mock(AgentPpqPaceProfile.class);
        try (MockedStatic<AgentPpqPaceProfile> ppq = mockStatic(AgentPpqPaceProfile.class);
             MockedStatic<AgentOpqPaceProfile> opq = mockStatic(AgentOpqPaceProfile.class);
             MockedStatic<AgentKpqPaceProfile> kpq = mockStatic(AgentKpqPaceProfile.class)) {
            ppq.when(() -> AgentPpqPaceProfile.load()).thenReturn(profile);

            assertSame(profile, PqEntrance.of("ppq").paceLoader().get());
            ppq.verify(() -> AgentPpqPaceProfile.load());
            ppq.verifyNoMoreInteractions();
            opq.verifyNoInteractions();
            kpq.verifyNoInteractions();
        }
        assertFalse(PqEntrance.of("ppq").backgroundRuns(), "no background PPQ population exists");
        assertFalse(PqEntrance.of("ppq").admitsOnIo(), "admission prepares each companion: on companion-ops");
    }

    // ---- Guon --------------------------------------------------------------------------------

    @Test
    void guonSeatsFourToSixAndSpeaksInHisOwnWords() {
        PqEntrance ppq = PqEntrance.of("ppq");

        assertEquals("ppq", ppq.key());
        assertEquals("PiratePQ", ppq.definition().eventManagerName());
        assertEquals(new PqEntrance.SeatRules(4, 6, 6), ppq.seatRules());
        assertEquals(new PqEntrance.SeatRules(4, 6, 6), PqEntrance.seats("ppq"));
        assertEquals(2_094_000, ppq.entryNpcId());
        assertEquals(RECRUIT_MAP, ppq.definition().recruitMapId());
        assertEquals("ppq", PqEntrance.keyAt(RECRUIT_MAP), "companions at Guon restock to the ppq standard");
        assertSame(ppq, PqEntrance.keepingClearOn(RECRUIT_MAP), "his platform is kept clear");
        assertArrayEquals(new int[] {9_300_114, 9_300_115, 9_300_116, 9_300_124, 9_300_125}, ppq.dpsMobIds(),
                "the three waves' medal pirates and the decks' two");
        assertSame(GuonLines.VOICE, ppq.voice());
        assertTrue(PqEntrance.keys().contains("ppq"));
    }

    @Test
    void theExtrasWaitOnTheFloorLeftOfHisPlatformOnTheRealMap() throws Exception {
        List<int[]> footholds = footholds();
        MapleMap map = guonsMap();
        when(map.getPointBelow(any())).thenReturn(null); // the spot as reckoned, before it is grounded
        PqEntrance ppq = PqEntrance.of("ppq");
        int[] xs = {-1_697, -1_727, -1_757, -1_787, -1_817, -1_847};
        for (int i = 0; i < xs.length; i++) {
            Point spot = ppq.beside(map, i);
            assertEquals(new Point(xs[i], GUON_AT.y), spot, "extra " + i);
            int x = spot.x;
            assertTrue(x >= -1_893 && x <= -1_560, "left of his platform and the rope, on the floor's span: " + x);
            Integer ground = groundBelow(footholds, x, spot.y);
            assertEquals(242, ground, "extra " + i + " at x " + x + " lands on the floor, nothing between");
            assertTrue(footholds.stream().noneMatch(fh -> under(fh, x) && atY(fh, x) >= 40 && atY(fh, x) <= 60),
                    "nothing at his platform's height (y 45-49) under extra " + i + " at x " + x);
        }
        Point floor = new Point(-1_697, 242);
        when(map.getPointBelow(new Point(-1_697, 28))).thenReturn(floor);
        assertEquals(floor, ppq.beside(map, 0), "grounded on the floor");
    }

    @Test
    void anOwnerOnHisPlatformHasHisCompanionsStandClearAndNowhereElse() {
        MapleMap map = guonsMap();
        PqEntrance ppq = PqEntrance.of("ppq");

        assertEquals(GUON_AT, ppq.keptClearAt(map, new Point(-1_277, 45)), "beside him");
        assertEquals(GUON_AT, ppq.keptClearAt(map, new Point(-1_466, 48)), "the platform's left end, by the rope");
        assertEquals(GUON_AT, ppq.keptClearAt(map, new Point(-1_152, 45)), "the platform's right end");
        assertEquals(GUON_AT, ppq.keptClearAt(map, new Point(-1_278, -2)), "the deck just above him");
        assertNull(ppq.keptClearAt(map, new Point(-1_277, 242)), "on the floor below him");
        assertNull(ppq.keptClearAt(map, new Point(-1_503, 242)), "at the foot of the rope");
        assertNull(ppq.keptClearAt(map, new Point(-1_757, 242)), "at the extras' spots");
        assertNull(ppq.keptClearAt(map, new Point(-1_717, 151)), "at the spawn point");
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** The recruit map with Guon where Map.wz puts him. */
    private static MapleMap guonsMap() {
        MapleMap map = mock(MapleMap.class);
        NPC guon = mock(NPC.class);
        when(guon.getPosition()).thenReturn(GUON_AT);
        when(map.getNPCById(2_094_000)).thenReturn(guon);
        return map;
    }

    /** The first foothold surface at or below {@code y} at {@code x}; null when none. */
    private static Integer groundBelow(List<int[]> footholds, int x, int y) {
        Integer best = null;
        for (int[] fh : footholds) {
            if (under(fh, x)) {
                int at = atY(fh, x);
                if (at >= y && (best == null || at < best)) best = at;
            }
        }
        return best;
    }

    private static boolean under(int[] fh, int x) {
        return Math.min(fh[0], fh[2]) <= x && x <= Math.max(fh[0], fh[2]) && fh[0] != fh[2];
    }

    /** The foothold's y at {@code x}. */
    private static int atY(int[] fh, int x) {
        return fh[1] + (fh[3] - fh[1]) * (x - fh[0]) / (fh[2] - fh[0]);
    }

    /** Every non-vertical foothold of Map.wz 251010404, each {x1, y1, x2, y2}. */
    private static List<int[]> footholds() throws Exception {
        Element root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new File("wz/Map.wz/Map/Map2/251010404.img.xml")).getDocumentElement();
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
        assertTrue(all.size() >= 20, "the map's footholds were read: " + all.size());
        return all;
    }
}
