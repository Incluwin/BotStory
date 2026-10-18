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
import server.agents.capabilities.partyquest.opq.AgentOpqAdmissionService;
import server.agents.capabilities.partyquest.opq.AgentOpqMemberState;
import server.agents.capabilities.partyquest.opq.AgentOpqPaceProfile;
import server.agents.capabilities.partyquest.opq.AgentOpqSession;
import server.agents.capabilities.partyquest.opq.AgentOpqSessionRegistry;
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
 * OPQ's entrance as companion runs go in by it (P6h OPQ step 5): Wonky's, at the foot of the Tower of Goddess. Its
 * admission is OPQ's own with the leader as operator and event leader, its registry is OPQ's (the operator and the
 * members looked up apart), its pace profile is OPQ's, no background OPQ population ever runs, it seats five or six
 * (her own choice six), Wonky speaks in her own words, the extras wait on the floor past the right end of her ledge
 * (each spot over a floor foothold of Map.wz 200080101 and none over the ledge), and an owner on her ledge has his
 * companions stand clear of it.
 */
class OpqEntranceTest {
    private static final long SEED = 42L;
    private static final long NOW = 5_000L;
    private static final int RECRUIT_MAP = 200_080_101;
    private static final Point WONKY_AT = new Point(198, -47);

    private final Character leader = mock(Character.class);
    private final List<Character> members = List.of(leader, mock(Character.class), mock(Character.class),
            mock(Character.class), mock(Character.class), mock(Character.class));

    // ---- admission ---------------------------------------------------------------------------

    @Test
    void opqAsksOnlyOpqsAdmissionWithTheLeaderAsOperatorAndEventLeader() {
        AgentOpqSession session = new AgentOpqSession(AgentOpqSession.Mode.HUMAN_ASSISTED, SEED, 30, 6, NOW);
        try (MockedStatic<AgentOpqAdmissionService> opq = mockStatic(AgentOpqAdmissionService.class);
             MockedStatic<AgentEpqAdmissionService> epq = mockStatic(AgentEpqAdmissionService.class);
             MockedStatic<AgentKpqAdmissionService> kpq = mockStatic(AgentKpqAdmissionService.class);
             MockedStatic<AgentHpqAdmissionService> hpq = mockStatic(AgentHpqAdmissionService.class)) {
            opq.when(() -> AgentOpqAdmissionService.admit(leader, leader, members, SEED, NOW))
                    .thenReturn(new AgentOpqAdmissionService.AdmissionResult(true, "OPQ party admitted", session, null));

            PqEntryPort.Admitted admitted = PqEntryPort.STOCK.admit("opq", leader, members, SEED, NOW);

            assertTrue(admitted.success());
            assertEquals("OPQ party admitted", admitted.message());
            assertSame(session, admitted.session());
            opq.verify(() -> AgentOpqAdmissionService.admit(leader, leader, members, SEED, NOW));
            opq.verifyNoMoreInteractions();
            epq.verifyNoInteractions();
            kpq.verifyNoInteractions();
            hpq.verifyNoInteractions();
        }
    }

    @Test
    void aRefusalComesBackInOpqsAdmissionWords() {
        try (MockedStatic<AgentOpqAdmissionService> opq = mockStatic(AgentOpqAdmissionService.class)) {
            opq.when(() -> AgentOpqAdmissionService.admit(leader, leader, members, SEED, NOW))
                    .thenReturn(new AgentOpqAdmissionService.AdmissionResult(false, "Mira must be level 51-70", null,
                            null));

            PqEntryPort.Admitted refused = PqEntryPort.STOCK.admit(" OPQ ", leader, members, SEED, NOW);

            assertFalse(refused.success());
            assertEquals("Mira must be level 51-70", refused.message());
            assertNull(refused.session());
        }
    }

    @Test
    void aMembersProblemIsOpqAdmissionsOwnWords() {
        Character mira = mock(Character.class);
        try (MockedStatic<AgentOpqAdmissionService> opq = mockStatic(AgentOpqAdmissionService.class);
             MockedStatic<AgentEpqAdmissionService> epq = mockStatic(AgentEpqAdmissionService.class)) {
            opq.when(() -> AgentOpqAdmissionService.memberProblem(mira)).thenReturn("Mira is not at OPQ");

            assertEquals("Mira is not at OPQ", PqEntrance.of("opq").memberProblem(mira, false));
            assertEquals("Mira is not at OPQ", PqEntrance.of("opq").memberProblem(mira, true));
            epq.verifyNoInteractions();
        }
    }

    // ---- its registry ------------------------------------------------------------------------

    @Test
    void wonkyLooksUpHerRunsOperatorAndItsMembersApart() {
        AgentOpqSession session = new AgentOpqSession(AgentOpqSession.Mode.HUMAN_ASSISTED, 1L, 60, 5, 1_000L);
        session.addMember(61, AgentOpqMemberState.MemberType.HUMAN); // 60 runs it
        for (int id = 960; id <= 963; id++) {
            session.addMember(id, AgentOpqMemberState.MemberType.AGENT);
        }
        session.setLeadership(61, 960);
        AgentOpqSessionRegistry.registerComplete(session);
        try {
            PqEntrance opq = PqEntrance.of("opq");

            assertEquals(new PqEntrance.LiveSession(session.sessionId(), 61), opq.session(60, session.sessionId()));
            assertNull(opq.session(960, session.sessionId()), "a member is not the run's operator");
            assertNull(opq.session(60, "another-session"));
            assertTrue(opq.holds(960, session.sessionId()));
            assertFalse(opq.holds(60, session.sessionId()), "the operator is not one of its members");
            assertFalse(PqEntrance.of("epq").holds(960, session.sessionId()), "EPQ's registry is not OPQ's");
            assertEquals("operator-session", opq.registrationBlocker(60, List.of(70)));
            assertEquals("member-960", opq.registrationBlocker(70, List.of(71, 960)));
            assertEquals("", opq.registrationBlocker(70, List.of(71)));
        } finally {
            AgentOpqSessionRegistry.remove(session);
        }
    }

    // ---- its pace profile and background -------------------------------------------------------

    @Test
    void opqsEntranceBuildsOpqsPaceProfileOnlyAndNoBackgroundRunsIt() {
        AgentOpqPaceProfile profile = mock(AgentOpqPaceProfile.class);
        try (MockedStatic<AgentOpqPaceProfile> opq = mockStatic(AgentOpqPaceProfile.class);
             MockedStatic<AgentEpqPaceProfile> epq = mockStatic(AgentEpqPaceProfile.class);
             MockedStatic<AgentKpqPaceProfile> kpq = mockStatic(AgentKpqPaceProfile.class)) {
            opq.when(() -> AgentOpqPaceProfile.load()).thenReturn(profile);

            assertSame(profile, PqEntrance.of("opq").paceLoader().get());
            opq.verify(() -> AgentOpqPaceProfile.load());
            opq.verifyNoMoreInteractions();
            epq.verifyNoInteractions();
            kpq.verifyNoInteractions();
        }
        assertFalse(PqEntrance.of("opq").backgroundRuns(), "no background OPQ population exists");
        assertFalse(PqEntrance.of("opq").admitsOnIo(), "admission prepares each companion: on companion-ops");
    }

    // ---- Wonky -------------------------------------------------------------------------------

    @Test
    void wonkySeatsFiveOrSixAndSpeaksInHerOwnWords() {
        PqEntrance opq = PqEntrance.of("opq");

        assertEquals("opq", opq.key());
        assertEquals("OrbisPQ", opq.definition().eventManagerName());
        assertEquals(new PqEntrance.SeatRules(5, 6, 6), opq.seatRules());
        assertEquals(new PqEntrance.SeatRules(5, 6, 6), PqEntrance.seats("opq"));
        assertEquals(2_013_000, opq.entryNpcId());
        assertEquals(RECRUIT_MAP, opq.definition().recruitMapId());
        assertEquals("opq", PqEntrance.keyAt(RECRUIT_MAP), "companions at Wonky restock to the opq standard");
        assertSame(opq, PqEntrance.keepingClearOn(RECRUIT_MAP), "her ledge is kept clear");
        assertArrayEquals(new int[] {9_300_045, 9_300_041, 9_300_048}, opq.dpsMobIds(),
                "the walkway's Lunar Pixie, the lounge's Cellion and the garden's Nependeath");
        assertSame(WonkyLines.VOICE, opq.voice());
        assertTrue(PqEntrance.keys().contains("opq"));
    }

    @Test
    void theExtrasWaitOnTheFloorPastTheRightEndOfHerLedgeNeverOnIt() throws Exception {
        List<int[]> footholds = footholds();
        MapleMap map = wonkysMap();
        when(map.getPointBelow(any())).thenReturn(null); // the spot as reckoned, before it is grounded
        PqEntrance opq = PqEntrance.of("opq");
        int[] xs = {408, 438, 468, 498, 528};
        for (int i = 0; i < xs.length; i++) {
            Point spot = opq.beside(map, i);
            assertEquals(new Point(xs[i], WONKY_AT.y), spot, "extra " + i);
            int x = spot.x;
            assertTrue(footholds.stream().noneMatch(fh -> under(fh, x) && atY(fh, x) < 0),
                    "nothing of her ledge (x -9 to 396) under extra " + i + " at x " + x);
            assertTrue(footholds.stream().anyMatch(fh -> under(fh, x) && atY(fh, x) >= 152 && atY(fh, x) <= 179),
                    "the floor below extra " + i + " at x " + x);
        }
        Point ground = new Point(408, 178);
        when(map.getPointBelow(new Point(408, -47))).thenReturn(ground);
        assertEquals(ground, opq.beside(map, 0), "grounded on the floor");
    }

    @Test
    void anOwnerOnHerLedgeHasHisCompanionsStandClearAndNowhereElse() {
        MapleMap map = wonkysMap();
        PqEntrance opq = PqEntrance.of("opq");

        assertEquals(WONKY_AT, opq.keptClearAt(map, new Point(198, -47)), "at her");
        assertEquals(WONKY_AT, opq.keptClearAt(map, new Point(-9, -34)), "the ledge's left end, by the rope");
        assertEquals(WONKY_AT, opq.keptClearAt(map, new Point(396, -62)), "the ledge's right end");
        assertEquals(WONKY_AT, opq.keptClearAt(map, new Point(198, -140)), "a jump above her");
        assertNull(opq.keptClearAt(map, new Point(198, 170)), "on the floor below her");
        assertNull(opq.keptClearAt(map, new Point(30, 170)), "at the foot of the rope");
        assertNull(opq.keptClearAt(map, new Point(468, 170)), "at the extras' spots");
        assertNull(opq.keptClearAt(map, new Point(-233, 124)), "at the spawn point");
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** The recruit map with Wonky where Map.wz puts her. */
    private static MapleMap wonkysMap() {
        MapleMap map = mock(MapleMap.class);
        NPC wonky = mock(NPC.class);
        when(wonky.getPosition()).thenReturn(WONKY_AT);
        when(map.getNPCById(2_013_000)).thenReturn(wonky);
        return map;
    }

    private static boolean under(int[] fh, int x) {
        return Math.min(fh[0], fh[2]) <= x && x <= Math.max(fh[0], fh[2]) && fh[0] != fh[2];
    }

    /** The foothold's y at {@code x}. */
    private static int atY(int[] fh, int x) {
        return fh[1] + (fh[3] - fh[1]) * (x - fh[0]) / (fh[2] - fh[0]);
    }

    /** Every non-vertical foothold of Map.wz 200080101, each {x1, y1, x2, y2}. */
    private static List<int[]> footholds() throws Exception {
        Element root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new File("wz/Map.wz/Map/Map2/200080101.img.xml")).getDocumentElement();
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
        assertTrue(all.size() >= 25, "the map's footholds were read: " + all.size());
        return all;
    }
}
