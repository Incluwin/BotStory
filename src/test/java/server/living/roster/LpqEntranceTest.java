package server.living.roster;

import client.Character;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.partyquest.hpq.AgentHpqAdmissionService;
import server.agents.capabilities.partyquest.hpq.AgentHpqPaceProfile;
import server.agents.capabilities.partyquest.kpq.AgentKpqAdmissionService;
import server.agents.capabilities.partyquest.kpq.AgentKpqPaceProfile;
import server.agents.capabilities.partyquest.lpq.AgentLpqAdmissionService;
import server.agents.capabilities.partyquest.lpq.AgentLpqMemberState;
import server.agents.capabilities.partyquest.lpq.AgentLpqPaceProfile;
import server.agents.capabilities.partyquest.lpq.AgentLpqSession;
import server.agents.capabilities.partyquest.lpq.AgentLpqSessionRegistry;
import server.life.NPC;
import server.maps.MapleMap;

import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.Point;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

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
 * LPQ's entrance as companion runs go in by it (P6g LPQ C5.2; integration rule 15): the Red Sign's, on the 101st floor
 * of Eos Tower. Its admission is LPQ's own with the leader as operator and event leader, its registry is LPQ's (the
 * operator and the members looked up apart), its pace profile is LPQ's, no background LPQ population ever runs, it seats
 * five or six (her own choice six), the sign speaks in its own notices, and the extras wait on the steps below the
 * sign's ledge (each spot over a step of Map.wz 221024500) while an owner on the ledge is at the sign.
 */
class LpqEntranceTest {
    private static final long SEED = 42L;
    private static final long NOW = 5_000L;
    private static final int RECRUIT_MAP = 221_024_500;
    private static final Point RED_SIGN_AT = new Point(189, 1664);

    private final Character leader = mock(Character.class);
    private final List<Character> members = List.of(leader, mock(Character.class), mock(Character.class),
            mock(Character.class), mock(Character.class));

    // ---- admission ---------------------------------------------------------------------------

    @Test
    void lpqAsksOnlyLpqsAdmissionWithTheLeaderAsOperatorAndEventLeader() {
        AgentLpqSession session = new AgentLpqSession(AgentLpqSession.Mode.PRODUCTION, SEED, 30, 5, NOW);
        try (MockedStatic<AgentLpqAdmissionService> lpq = mockStatic(AgentLpqAdmissionService.class);
             MockedStatic<AgentKpqAdmissionService> kpq = mockStatic(AgentKpqAdmissionService.class);
             MockedStatic<AgentHpqAdmissionService> hpq = mockStatic(AgentHpqAdmissionService.class)) {
            lpq.when(() -> AgentLpqAdmissionService.admit(leader, leader, members, SEED, NOW))
                    .thenReturn(new AgentLpqAdmissionService.AdmissionResult(true, "LPQ party admitted", session, null));

            PqEntryPort.Admitted admitted = PqEntryPort.STOCK.admit("lpq", leader, members, SEED, NOW);

            assertTrue(admitted.success());
            assertEquals("LPQ party admitted", admitted.message());
            assertSame(session, admitted.session());
            lpq.verify(() -> AgentLpqAdmissionService.admit(leader, leader, members, SEED, NOW));
            lpq.verifyNoMoreInteractions();
            kpq.verifyNoInteractions();
            hpq.verifyNoInteractions();
        }
    }

    @Test
    void aRefusalComesBackInLpqsAdmissionWords() {
        try (MockedStatic<AgentLpqAdmissionService> lpq = mockStatic(AgentLpqAdmissionService.class)) {
            lpq.when(() -> AgentLpqAdmissionService.admit(leader, leader, members, SEED, NOW))
                    .thenReturn(new AgentLpqAdmissionService.AdmissionResult(false, "Mira must be level 35-50", null,
                            null));

            PqEntryPort.Admitted refused = PqEntryPort.STOCK.admit(" LPQ ", leader, members, SEED, NOW);

            assertFalse(refused.success());
            assertEquals("Mira must be level 35-50", refused.message());
            assertNull(refused.session());
        }
    }

    @Test
    void aMembersProblemIsLpqAdmissionsOwnWords() {
        Character mira = mock(Character.class);
        try (MockedStatic<AgentLpqAdmissionService> lpq = mockStatic(AgentLpqAdmissionService.class);
             MockedStatic<AgentHpqAdmissionService> hpq = mockStatic(AgentHpqAdmissionService.class)) {
            lpq.when(() -> AgentLpqAdmissionService.memberProblem(mira)).thenReturn("Mira is not at LPQ");

            assertEquals("Mira is not at LPQ", PqEntrance.of("lpq").memberProblem(mira, false));
            assertEquals("Mira is not at LPQ", PqEntrance.of("lpq").memberProblem(mira, true));
            hpq.verifyNoInteractions();
        }
    }

    // ---- its registry ------------------------------------------------------------------------

    @Test
    void theRedSignLooksUpItsRunsOperatorAndItsMembersApart() {
        AgentLpqSession session = new AgentLpqSession(AgentLpqSession.Mode.PRODUCTION, 1L, 60, 5, 1_000L); // 60 runs it
        session.addMember(61, AgentLpqMemberState.MemberType.HUMAN);
        session.addMember(960, AgentLpqMemberState.MemberType.AGENT);
        session.setLeadership(61, 960);
        AgentLpqSessionRegistry.registerComplete(session);
        try {
            PqEntrance lpq = PqEntrance.of("lpq");

            assertEquals(new PqEntrance.LiveSession(session.sessionId(), 61), lpq.session(60, session.sessionId()));
            assertNull(lpq.session(960, session.sessionId()), "a member is not the run's operator");
            assertNull(lpq.session(60, "another-session"));
            assertTrue(lpq.holds(960, session.sessionId()));
            assertFalse(lpq.holds(60, session.sessionId()), "the operator is not one of its members");
            assertFalse(PqEntrance.of("hpq").holds(960, session.sessionId()), "HPQ's registry is not LPQ's");
            assertEquals("operator-session", lpq.registrationBlocker(60, List.of(70)));
            assertEquals("member-960", lpq.registrationBlocker(70, List.of(71, 960)));
            assertEquals("", lpq.registrationBlocker(70, List.of(71)));
        } finally {
            AgentLpqSessionRegistry.remove(session);
        }
    }

    // ---- its pace profile and background -------------------------------------------------------

    @Test
    void lpqsEntranceBuildsLpqsPaceProfileOnlyAndNoBackgroundRunsIt() {
        AgentLpqPaceProfile profile = mock(AgentLpqPaceProfile.class);
        try (MockedStatic<AgentLpqPaceProfile> lpq = mockStatic(AgentLpqPaceProfile.class);
             MockedStatic<AgentKpqPaceProfile> kpq = mockStatic(AgentKpqPaceProfile.class);
             MockedStatic<AgentHpqPaceProfile> hpq = mockStatic(AgentHpqPaceProfile.class)) {
            lpq.when(() -> AgentLpqPaceProfile.load()).thenReturn(profile);

            assertSame(profile, PqEntrance.of("lpq").paceLoader().get());
            lpq.verify(() -> AgentLpqPaceProfile.load());
            lpq.verifyNoMoreInteractions();
            kpq.verifyNoInteractions();
            hpq.verifyNoInteractions();
        }
        assertFalse(PqEntrance.of("lpq").backgroundRuns(), "no background LPQ population exists");
        assertFalse(PqEntrance.of("lpq").admitsOnIo(), "admission prepares each companion: on companion-ops");
    }

    // ---- the Red Sign ------------------------------------------------------------------------

    @Test
    void theRedSignSeatsFiveOrSixAndSpeaksInItsOwnNotices() {
        PqEntrance lpq = PqEntrance.of("lpq");

        assertEquals("lpq", lpq.key());
        assertEquals(new PqEntrance.SeatRules(5, 6, 6), lpq.seatRules());
        assertEquals(new PqEntrance.SeatRules(5, 6, 6), PqEntrance.seats("lpq"));
        assertEquals(2_040_034, lpq.entryNpcId());
        assertEquals(RECRUIT_MAP, lpq.definition().recruitMapId());
        assertEquals("lpq", PqEntrance.keyAt(RECRUIT_MAP), "companions at the sign restock to the lpq standard");
        assertSame(lpq, PqEntrance.keepingClearOn(RECRUIT_MAP));
        assertArrayEquals(new int[] {9_300_010, 9_300_012}, lpq.dpsMobIds(), "Rombard and Alishar");
        assertSame(RedSignLines.VOICE, lpq.voice());
        assertTrue(PqEntrance.keys().contains("lpq"));
    }

    @Test
    void theExtrasWaitOnTheStepsBelowTheSignsLedgeNeverOnIt() throws Exception {
        List<int[]> footholds = flatFootholds();
        MapleMap map = signMap();
        when(map.getPointBelow(any())).thenReturn(null); // the spot as reckoned, before it is grounded
        PqEntrance lpq = PqEntrance.of("lpq");
        int[] xs = {79, 39, -1, -41, -81};
        for (int i = 0; i < xs.length; i++) {
            Point spot = lpq.beside(map, i);
            assertEquals(new Point(xs[i], RED_SIGN_AT.y), spot, "extra " + i);
            assertTrue(spot.x < 82, "off the sign's ledge (x 82-265): " + spot);
            int x = spot.x;
            assertTrue(footholds.stream().anyMatch(fh -> fh[0] <= x && x <= fh[1] && fh[2] > RED_SIGN_AT.y),
                    "a step below extra " + i + " at x " + x);
        }
        Point ground = new Point(79, 1714);
        when(map.getPointBelow(new Point(79, 1664))).thenReturn(ground);
        assertEquals(ground, lpq.beside(map, 0), "grounded on the step");
    }

    @Test
    void anOwnerOnTheSignsLedgeIsAtTheSignAndOneOnTheStepBelowIsNot() {
        MapleMap map = signMap();
        PqEntrance lpq = PqEntrance.of("lpq");

        assertEquals(RED_SIGN_AT, lpq.keptClearAt(map, new Point(189, 1670)), "on the ledge");
        assertEquals(RED_SIGN_AT, lpq.keptClearAt(map, new Point(299, 1670)), "110 px along it");
        assertEquals(RED_SIGN_AT, lpq.keptClearAt(map, new Point(189, 1564)), "a jump on the ledge");
        assertEquals(RED_SIGN_AT, lpq.keptClearAt(map, new Point(189, 1704)));
        assertNull(lpq.keptClearAt(map, new Point(300, 1670)), "past 110 px");
        assertNull(lpq.keptClearAt(map, new Point(189, 1563)));
        assertNull(lpq.keptClearAt(map, new Point(79, 1714)), "the first step is not the sign");
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** The recruit map with the Red Sign where Map.wz puts it. */
    private static MapleMap signMap() {
        MapleMap map = mock(MapleMap.class);
        NPC sign = mock(NPC.class);
        when(sign.getPosition()).thenReturn(RED_SIGN_AT);
        when(map.getNPCById(2_040_034)).thenReturn(sign);
        return map;
    }

    /** The flat footholds of Map.wz 221024500, each {min x, max x, y}. */
    private static List<int[]> flatFootholds() throws Exception {
        Element root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new File("wz/Map.wz/Map/Map2/221024500.img.xml")).getDocumentElement();
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
        assertTrue(flat.size() > 20, "the map's footholds were read: " + flat.size());
        return flat;
    }
}
