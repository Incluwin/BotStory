package server.living.roster;

import client.Character;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.partyquest.epq.AgentEpqAdmissionService;
import server.agents.capabilities.partyquest.rjpq.AgentRjpqAdmissionService;
import server.agents.capabilities.partyquest.rjpq.AgentRjpqMemberState;
import server.agents.capabilities.partyquest.rjpq.AgentRjpqPaceProfile;
import server.agents.capabilities.partyquest.rjpq.AgentRjpqSession;
import server.agents.capabilities.partyquest.rjpq.AgentRjpqSessionRegistry;
import server.agents.capabilities.partyquest.rjpq.AgentRjpqSide;
import server.life.NPC;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Romeo and Juliet PQ's entrances as companion runs go in by them (plan W9; integration rule 15), one per side: Romeo's
 * hidden room ("rjpq") and Juliet's ("rjpqj"). Admission is RJPQ's own with the leader as operator and event leader,
 * the registry is RJPQ's, the pace profile is the side's own, no background RJPQ runs, exactly four are seated, each
 * NPC speaks in its own words, the extras wait on the room's floor off the NPC's step, and an owner by the NPC has his
 * companions stand clear.
 */
class RjpqEntranceTest {
    private static final long SEED = 42L;
    private static final long NOW = 5_000L;

    private final Character leader = mock(Character.class);
    private final List<Character> members = List.of(leader, mock(Character.class), mock(Character.class),
            mock(Character.class));

    @Test
    void eachSideAsksOnlyRjpqsAdmissionWithTheLeaderAsOperatorAndEventLeader() {
        for (String key : List.of("rjpq", "rjpqj")) {
            AgentRjpqSession session = new AgentRjpqSession(AgentRjpqSide.fromKey(key), SEED, 30, NOW);
            try (MockedStatic<AgentRjpqAdmissionService> rjpq = mockStatic(AgentRjpqAdmissionService.class);
                 MockedStatic<AgentEpqAdmissionService> epq = mockStatic(AgentEpqAdmissionService.class)) {
                rjpq.when(() -> AgentRjpqAdmissionService.admit(leader, leader, members, SEED, NOW))
                        .thenReturn(new AgentRjpqAdmissionService.AdmissionResult(true, "RJPQ party admitted", session,
                                null));

                PqEntryPort.Admitted admitted = PqEntryPort.STOCK.admit(key, leader, members, SEED, NOW);

                assertTrue(admitted.success(), key);
                assertSame(session, admitted.session());
                rjpq.verify(() -> AgentRjpqAdmissionService.admit(leader, leader, members, SEED, NOW));
                epq.verifyNoInteractions();
            }
        }
    }

    @Test
    void eachDeskChecksItsMembersAgainstItsOwnSide() {
        Character mira = mock(Character.class);
        when(mira.getName()).thenReturn("Mira");
        when(mira.getLevel()).thenReturn(75);

        when(mira.getMapId()).thenReturn(261_000_000);
        assertEquals("Mira is not beside Romeo.", PqEntrance.of("rjpq").memberProblem(mira, false));
        assertEquals("Mira is not beside Juliet.", PqEntrance.of("rjpqj").memberProblem(mira, false));

        when(mira.getMapId()).thenReturn(AgentRjpqSide.ZENUMIST.recruitMapId());
        assertEquals("", PqEntrance.of("rjpq").memberProblem(mira, false));
        assertEquals("Mira is not beside Juliet.", PqEntrance.of("rjpqj").memberProblem(mira, true),
                "Romeo's room is not Juliet's: refused at the desk, before the leader says yes");

        when(mira.getMapId()).thenReturn(AgentRjpqSide.ALCADNO.recruitMapId());
        assertEquals("Mira is not beside Romeo.", PqEntrance.of("rjpq").memberProblem(mira, false));
        assertEquals("", PqEntrance.of("rjpqj").memberProblem(mira, false));
    }

    @Test
    void theirRegistryLooksUpARunsOperatorAndItsMembersApart() {
        AgentRjpqSession session = new AgentRjpqSession(AgentRjpqSide.ALCADNO, 1L, 60, 1_000L);
        session.addMember(60, AgentRjpqMemberState.MemberType.HUMAN);
        for (int id = 960; id <= 962; id++) {
            session.addMember(id, AgentRjpqMemberState.MemberType.AGENT);
        }
        session.setLeadership(60, 960);
        AgentRjpqSessionRegistry.registerComplete(session);
        try {
            PqEntrance rjpqj = PqEntrance.of("rjpqj");

            assertEquals(new PqEntrance.LiveSession(session.sessionId(), 60), rjpqj.session(60, session.sessionId()));
            assertNull(rjpqj.session(960, session.sessionId()), "a member is not the run's operator");
            assertTrue(rjpqj.holds(960, session.sessionId()));
            assertFalse(PqEntrance.of("epq").holds(960, session.sessionId()), "EPQ's registry is not RJPQ's");
        } finally {
            AgentRjpqSessionRegistry.remove(session);
        }
    }

    @Test
    void eachSideBuildsItsOwnPaceProfileAndNoBackgroundRunsIt() {
        AgentRjpqPaceProfile zenumist = mock(AgentRjpqPaceProfile.class);
        AgentRjpqPaceProfile alcadno = mock(AgentRjpqPaceProfile.class);
        try (MockedStatic<AgentRjpqPaceProfile> pace = mockStatic(AgentRjpqPaceProfile.class)) {
            pace.when(() -> AgentRjpqPaceProfile.load(AgentRjpqSide.ZENUMIST)).thenReturn(zenumist);
            pace.when(() -> AgentRjpqPaceProfile.load(AgentRjpqSide.ALCADNO)).thenReturn(alcadno);

            assertSame(zenumist, PqEntrance.of("rjpq").paceLoader().get());
            assertSame(alcadno, PqEntrance.of("rjpqj").paceLoader().get());
        }
        for (String key : List.of("rjpq", "rjpqj")) {
            assertFalse(PqEntrance.of(key).backgroundRuns(), "no background RJPQ population exists");
            assertFalse(PqEntrance.of(key).admitsOnIo(), "admission prepares each companion: on companion-ops");
        }
    }

    @Test
    void romeoAndJulietSeatExactlyFourAndSpeakInTheirOwnWords() {
        PqEntrance romeo = PqEntrance.of("rjpq");
        PqEntrance juliet = PqEntrance.of("rjpqj");

        assertEquals(new PqEntrance.SeatRules(4, 4, 4), romeo.seatRules());
        assertEquals(new PqEntrance.SeatRules(4, 4, 4), PqEntrance.seats("rjpqj"));
        assertEquals(2_112_004, romeo.entryNpcId());
        assertEquals(2_112_003, juliet.entryNpcId());
        assertEquals("rjpq", PqEntrance.keyAt(261_000_011), "companions at Romeo restock to the rjpq standard");
        assertEquals("rjpqj", PqEntrance.keyAt(261_000_021));
        assertArrayEquals(new int[] {9_300_145, 9_300_146, 9_300_147, 9_300_148, 9_300_143}, romeo.dpsMobIds());
        assertSame(RjpqLines.ROMEO, romeo.voice());
        assertSame(RjpqLines.JULIET, juliet.voice());
        assertTrue(PqEntrance.keys().containsAll(List.of("rjpq", "rjpqj")));
    }

    @Test
    void theExtrasWaitOnTheFloorOffRomeosStepAndJulietsLedge() {
        MapleMap romeosRoom = room(2_112_004, new Point(158, 132));
        MapleMap julietsRoom = room(2_112_003, new Point(10, 28));
        when(romeosRoom.getPointBelow(any())).thenReturn(null);
        when(julietsRoom.getPointBelow(any())).thenReturn(null);
        for (int i = 0; i < 3; i++) {
            Point byRomeo = PqEntrance.of("rjpq").beside(romeosRoom, i);
            Point byJuliet = PqEntrance.of("rjpqj").beside(julietsRoom, i);
            assertEquals(new Point(48 - 30 * i, 132), byRomeo);
            assertTrue(byRomeo.x < 128 && byRomeo.x >= -401, "on the floor, off his step (x 128-194)");
            assertEquals(new Point(-120 - 30 * i, 28), byJuliet);
            assertTrue(byJuliet.x < -40 && byJuliet.x >= -400, "on the floor, off her ledge (x -40 to 53)");
        }
    }

    @Test
    void anOwnerByTheNpcHasHisCompanionsStandClear() {
        MapleMap romeosRoom = room(2_112_004, new Point(158, 132));
        MapleMap julietsRoom = room(2_112_003, new Point(10, 28));

        assertNotNull(PqEntrance.of("rjpq").keptClearAt(romeosRoom, new Point(158, 182)), "on the floor under him");
        assertNull(PqEntrance.of("rjpq").keptClearAt(romeosRoom, new Point(-300, 182)), "far off");
        assertNotNull(PqEntrance.of("rjpqj").keptClearAt(julietsRoom, new Point(10, 148)), "on the floor under her");
        assertNull(PqEntrance.of("rjpqj").keptClearAt(julietsRoom, new Point(300, -119)), "up on the right");
    }

    private static MapleMap room(int npcId, Point at) {
        MapleMap map = mock(MapleMap.class);
        NPC npc = mock(NPC.class);
        when(npc.getPosition()).thenReturn(at);
        when(map.getNPCById(npcId)).thenReturn(npc);
        return map;
    }
}
