package server.living.roster;

import client.Character;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.partyquest.apq.AgentApqAdmissionService;
import server.agents.capabilities.partyquest.apq.AgentApqDefinition;
import server.agents.capabilities.partyquest.apq.AgentApqSession;
import server.life.NPC;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * APQ's entrance as companion runs go in by it (APQ plan A7): Amos's, at the Entrance of the Amorian Challenge. Its
 * admission is APQ's own with the leader as operator and event leader, it seats exactly six, Amos speaks in his own
 * words (married, both genders, the stock start's texts), the extras wait on the floor left of him (Map.wz 670010100's
 * footholds), and a lineup of six with nobody of one gender is kept out before Yes, humans counted.
 */
class ApqEntranceTest {
    private static final long SEED = 42L;
    private static final long NOW = 5_000L;
    private static final int BEN = 900;

    @Test
    void apqAsksOnlyApqsAdmissionWithTheLeaderAsOperatorAndEventLeader() {
        Character leader = mock(Character.class);
        List<Character> members = List.of(leader, mock(Character.class));
        AgentApqSession session = new AgentApqSession(SEED, 30, NOW);
        try (MockedStatic<AgentApqAdmissionService> apq = mockStatic(AgentApqAdmissionService.class)) {
            apq.when(() -> AgentApqAdmissionService.admit(leader, leader, members, SEED, NOW)).thenReturn(
                    new AgentApqAdmissionService.AdmissionResult(true, "APQ party admitted", session, null));

            PqEntryPort.Admitted admitted = PqEntrance.of("apq").admit(leader, members, SEED, NOW);

            assertTrue(admitted.success());
            assertSame(session, admitted.session());
            apq.verify(() -> AgentApqAdmissionService.admit(any(), any(), any(), anyLong(), anyLong()));
        }
    }

    @Test
    void amosSeatsExactlySixOnHisOwnMap() {
        PqEntrance amos = PqEntrance.of("apq");
        assertEquals(new PqEntrance.SeatRules(6, 6, 6), amos.seatRules());
        assertEquals(AgentApqDefinition.ENTRY_NPC, amos.entryNpcId());
        assertEquals("apq", PqEntrance.keyAt(AgentApqDefinition.RECRUIT_MAP));
        assertFalse(amos.admitsOnIo());
        assertFalse(amos.backgroundRuns());
        assertSame(AmosLines.VOICE, amos.voice());
    }

    @Test
    void theExtrasWaitOnTheFloorLeftOfAmos() {
        MapleMap map = mock(MapleMap.class);
        NPC amos = mock(NPC.class);
        when(amos.getPosition()).thenReturn(ApqFixtureEntrance.AMOS_AT);
        when(map.getNPCById(AgentApqDefinition.ENTRY_NPC)).thenReturn(amos);
        ApqFixtureEntrance floor = new ApqFixtureEntrance();
        when(map.getPointBelow(any())).thenAnswer(call -> floor.groundBelow(call.getArgument(0)));

        for (int index = 0; index < 5; index++) {
            Point spot = PqEntrance.of("apq").beside(map, index);
            assertEquals(776 - 30 * index, spot.x, "spot " + index);
            assertEquals(2_100, spot.y, "spot " + index + " is on the floor, not the platform above (x 503-632)");
        }
    }

    @Test
    void anOwnerAtAmosHasHisCompanionsStandClear() {
        MapleMap map = mock(MapleMap.class);
        NPC amos = mock(NPC.class);
        when(amos.getPosition()).thenReturn(ApqFixtureEntrance.AMOS_AT);
        when(map.getNPCById(AgentApqDefinition.ENTRY_NPC)).thenReturn(amos);
        PqEntrance entrance = PqEntrance.of("apq");

        assertEquals(ApqFixtureEntrance.AMOS_AT, entrance.keptClearAt(map, new Point(900, 2_100)));
        assertNull(entrance.keptClearAt(map, new Point(700, 2_100)), "farther along the floor");
    }

    @Test
    void amosSaysWhoIsUnmarriedAndWhichGenderIsMissing() {
        DeskVoice amos = AmosLines.VOICE;
        assertEquals("Una isn't married. The challenge is for married adventurers.",
                amos.forAdmit("Una is not married", "Ben"));
        assertEquals("You need to be married for the challenge.", amos.forAdmit("Ben is not married", "Ben"));
        assertEquals(AmosLines.NEEDS_A_WOMAN, amos.forAdmit("The APQ party needs a woman", "Ben"));
        assertEquals(AmosLines.NEEDS_A_MAN, amos.forAdmit("The APQ party needs a man", "Ben"));
        assertEquals(AmosLines.SIZE_RULE, amos.forAdmit("The Amorian Challenge takes exactly six members", "Ben"));
        assertEquals(AmosLines.UNMARRIED, amos.reason("Una is not married", 55));
        assertEquals("Lv35, " + AmosLines.NOT_READY, amos.reason("Una must be level 40-255", 35));
    }

    @Test
    void aLineupOfSixWithoutALadyIsKeptOutBeforeYesHumansCounted() {
        Map<Integer, Character> people = new HashMap<>();
        Character ben = person(BEN, 0, people);
        List<LineupPlan.Seat> seats = new ArrayList<>();
        seats.add(seat(BEN, true));
        for (int id = 11; id <= 15; id++) {
            person(id, 0, people);
            seats.add(seat(id, false));
        }
        LineupPlan plan = new LineupPlan("apq", BEN, seats, List.of(), List.of(), List.of(), null, false, null);

        LineupPlan menOnly = LineupComposition.apply(plan, ben, PqEntrance.of("apq"), people::get, people::get);
        assertEquals(AmosLines.NEEDS_A_WOMAN, menOnly.blocker());

        when(people.get(13).getGender()).thenReturn(1);
        assertSame(plan, LineupComposition.apply(plan, ben, PqEntrance.of("apq"), people::get, people::get));

        // a human lady counts as much as a companion
        Character ann = person(901, 1, people);
        seats.set(5, seat(901, true));
        when(people.get(13).getGender()).thenReturn(0);
        LineupPlan withAnn = new LineupPlan("apq", BEN, seats, List.of(), List.of(), List.of(), null, false, null);
        assertSame(withAnn, LineupComposition.apply(withAnn, ben, PqEntrance.of("apq"), people::get,
                id -> id == 901 ? ann : null));
    }

    @Test
    void apqAdmissionNamesTheMissingGenderAndTheUnmarried() {
        assertEquals("The APQ party needs a woman", AgentApqAdmissionService.genderProblem(List.of(0, 0, 0)));
        assertEquals("The APQ party needs a man", AgentApqAdmissionService.genderProblem(List.of(1, 1)));
        assertEquals("", AgentApqAdmissionService.genderProblem(List.of(0, 1, 0)));
        Character una = mock(Character.class);
        when(una.getName()).thenReturn("Una");
        when(una.getMapId()).thenReturn(AgentApqDefinition.RECRUIT_MAP);
        when(una.getLevel()).thenReturn(55);
        assertEquals("Una is not married", AgentApqAdmissionService.memberProblem(una));
        when(una.isMarried()).thenReturn(true);
        assertEquals("", AgentApqAdmissionService.memberProblem(una));
        when(una.getMapId()).thenReturn(AgentApqDefinition.GATE_MAP);
        assertEquals("Una has no Entrance Ticket", AgentApqAdmissionService.memberProblem(una),
                "held outside Amos the Strong's gate");
        when(una.getItemQuantity(AgentApqDefinition.LIP_LOCK_KEY, false)).thenReturn(10);
        assertEquals("Una is not at APQ", AgentApqAdmissionService.memberProblem(una), "ten keys: she trades and comes");
        when(una.getItemQuantity(AgentApqDefinition.LIP_LOCK_KEY, false)).thenReturn(0);
        when(una.getItemQuantity(AgentApqDefinition.ENTRANCE_TICKET, false)).thenReturn(1);
        assertEquals("Una is not at APQ", AgentApqAdmissionService.memberProblem(una), "a ticket: on her way in");
    }

    @Test
    void amosNamesACompanionWithNoTicket() {
        DeskVoice amos = AmosLines.VOICE;
        assertEquals("Una has no Entrance Ticket. Ten Lip Lock Keys from Purplewood get one from Amos the Strong.",
                amos.forAdmit("Una has no Entrance Ticket", "Ben"));
        assertEquals("You need an Entrance Ticket from Amos the Strong first.",
                amos.forAdmit("Ben has no Entrance Ticket", "Ben"));
        assertEquals(AmosLines.NO_TICKET, amos.reason("Una has no Entrance Ticket", 55));
        assertEquals(AmosLines.NOT_AT_THE_ENTRANCE, amos.reason("Una is not at APQ", 55));
    }

    private static Character person(int id, int gender, Map<Integer, Character> people) {
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(id);
        when(character.getName()).thenReturn("c" + id);
        when(character.getGender()).thenReturn(gender);
        people.put(id, character);
        return character;
    }

    private static LineupPlan.Seat seat(int id, boolean human) {
        return new LineupPlan.Seat(id, "c" + id, 70, 110, "Fighter", human, human ? 0 : BEN, human ? null : "p" + id,
                AgentApqDefinition.RECRUIT_MAP, true, 0L, 0, 0);
    }
}
