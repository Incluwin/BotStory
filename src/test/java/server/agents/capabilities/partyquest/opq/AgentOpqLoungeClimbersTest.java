package server.agents.capabilities.partyquest.opq;

import client.BuffStat;
import client.Character;
import client.SkillFactory;
import constants.skills.Assassin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.combat.AgentCombatBuffRuntime;
import server.agents.capabilities.partyquest.AgentPartyQuestHaste;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 2026-10-20 live (OPQ run 1): Sadie, a Fighter with a base jump of 100, ran the lounge and stood at its door for 43
 * minutes. Three of its four subrooms sit up ledges 84-96 px apart that only a jump of LOUNGE_JUMP (115) climbs; Jinx,
 * whose Haste lifts her jump to 119, waited in the centre. A runner that climbs (its jump with its Haste) takes any
 * subroom and casts its Haste before the ledges; one that cannot is given only the low subroom down the rope.
 */
class AgentOpqLoungeClimbersTest {
    private final OpqRunFixture run = new OpqRunFixture();
    private final MockedStatic<SkillFactory> skills = mockStatic(SkillFactory.class);
    private final Character lead = run.companion(11, "Lead");
    private final Character mira = run.companion(2, "Mira");
    private final Character kes = run.companion(3, "Kes");
    private final Character pel = run.companion(4, "Pel");
    private final Character hal = run.companion(5, "Hal");
    private final Character ivy = run.companion(6, "Ivy");

    @AfterEach
    void tearDown() {
        skills.close();
        run.close();
    }

    /** An Agent-led fixture run past the sealed room: Pel and Ivy run the lounge. */
    private AgentOpqSession loungeRun() {
        AgentOpqSession session = run.session(AgentOpqSession.Mode.AUTONOMOUS, false,
                AgentOpqSession.Phase.SPLIT_ROOMS, lead, mira, kes, pel, hal, ivy);
        session.rooms().claim(AgentOpqDefinition.Room.SEALED, lead.getId(), 2_500L);
        session.rooms().advance(AgentOpqDefinition.Room.SEALED, lead.getId(), AgentOpqRoomLedger.State.COMPLETE,
                2_500L);
        return session;
    }

    @Test
    void aRunnerThatCannotClimbIsGivenOnlyTheLowSubroom() {
        when(pel.getTotalJumpStat()).thenReturn(100);
        AgentOpqSession session = loungeRun();

        AgentOpqCoordinator.tickSession(session, 3_000L);

        assertEquals(AgentOpqCoordinator.LOUNGE_LOW_SUBROOM, session.member(pel.getId()).assignedSubroomMapId());
        assertEquals(AgentOpqDefinition.LOUNGE_ROOM_MAPS.get(0), session.member(ivy.getId()).assignedSubroomMapId());
    }

    @Test
    void withTheLowSubroomTakenARunnerThatCannotClimbIsGivenNone() {
        when(pel.getTotalJumpStat()).thenReturn(100);
        when(ivy.getTotalJumpStat()).thenReturn(100);
        AgentOpqSession session = loungeRun();

        AgentOpqCoordinator.tickSession(session, 3_000L);

        assertEquals(AgentOpqCoordinator.LOUNGE_LOW_SUBROOM, session.member(pel.getId()).assignedSubroomMapId());
        assertEquals(0, session.member(ivy.getId()).assignedSubroomMapId());
    }

    @Test
    void haste20LiftsABaseJumpOverTheLedges() {
        when(pel.getTotalJumpStat()).thenReturn(100);
        when(pel.getSkillLevel(Assassin.HASTE)).thenReturn(20);
        when(pel.getBuffedValue(BuffStat.JUMP)).thenReturn(null);
        when(hal.getTotalJumpStat()).thenReturn(100);
        when(hal.getBuffedValue(BuffStat.JUMP)).thenReturn(null);

        assertEquals(120, AgentPartyQuestHaste.jumpWithHaste(pel));
        assertTrue(AgentOpqCoordinator.climbsLounge(pel.getId()));
        assertFalse(AgentOpqCoordinator.climbsLounge(hal.getId()));
    }

    @Test
    void wonkysBlessingDoesNotHideTheJumpHasteWouldGive() {
        // 2026-10-24 live (run 4): every companion carried Wonky's blessing (item 2022093, jump +10). Read as "Haste
        // already on", Jinx (Haste 19) counted as jump 110, the lounge went to a Fighter and she never cast Haste.
        when(pel.getSkillLevel(Assassin.HASTE)).thenReturn(19);
        when(pel.getBuffedValue(BuffStat.JUMP)).thenReturn(10);
        when(pel.getTotalJumpStat()).thenReturn(110);

        assertEquals(119, AgentPartyQuestHaste.jumpWithHaste(pel));
        assertTrue(AgentOpqCoordinator.climbsLounge(pel.getId()));
        assertTrue(AgentPartyQuestHaste.needsHaste(pel, AgentOpqCoordinator.LOUNGE_JUMP));

        when(pel.getBuffedValue(BuffStat.JUMP)).thenReturn(19); // its Haste on: the strongest jump buff is in force
        when(pel.getTotalJumpStat()).thenReturn(119);
        assertEquals(119, AgentPartyQuestHaste.jumpWithHaste(pel));
        assertFalse(AgentPartyQuestHaste.needsHaste(pel, AgentOpqCoordinator.LOUNGE_JUMP));
    }

    @Test
    void aBlessingAloneIsNoClimber() {
        when(hal.getBuffedValue(BuffStat.JUMP)).thenReturn(10);
        when(hal.getTotalJumpStat()).thenReturn(110);

        assertEquals(110, AgentPartyQuestHaste.jumpWithHaste(hal));
        assertFalse(AgentOpqCoordinator.climbsLounge(hal.getId()));
        assertFalse(AgentPartyQuestHaste.needsHaste(hal, AgentOpqCoordinator.LOUNGE_JUMP));
    }

    @Test
    void aBlessedClimberCastsItsHasteInTheLounge() {
        when(pel.getTotalJumpStat()).thenReturn(110);
        when(pel.getSkillLevel(Assassin.HASTE)).thenReturn(19);
        when(pel.getBuffedValue(BuffStat.JUMP)).thenReturn(10);
        when(pel.getMapId()).thenReturn(AgentOpqDefinition.LOUNGE_MAP);
        when(pel.getPosition()).thenReturn(new Point(-45, -728));
        when(run.map.getItems()).thenReturn(List.of());
        AgentOpqSession session = loungeRun();
        AgentOpqCoordinator.tickSession(session, 3_000L);
        assertEquals(AgentOpqDefinition.LOUNGE_ROOM_MAPS.get(0), session.member(pel.getId()).assignedSubroomMapId());

        try (MockedStatic<AgentCombatBuffRuntime> buffs = mockStatic(AgentCombatBuffRuntime.class)) {
            buffs.when(() -> AgentCombatBuffRuntime.tryCastExplicitUtilityBuff(any(), any(), anyInt()))
                    .thenReturn(true);

            AgentOpqCoordinator.tickMember(session, run.entries.get(pel.getId()), pel, 3_500L);

            buffs.verify(() -> AgentCombatBuffRuntime.tryCastExplicitUtilityBuff(
                    run.entries.get(pel.getId()), pel, Assassin.HASTE));
        }
    }

    @Test
    void aClimberCastsItsHasteInTheLoungeBeforeHeadingForAHighSubroom() {
        when(pel.getTotalJumpStat()).thenReturn(100);
        when(pel.getSkillLevel(Assassin.HASTE)).thenReturn(19);
        when(pel.getBuffedValue(BuffStat.JUMP)).thenReturn(null);
        when(pel.getMapId()).thenReturn(AgentOpqDefinition.LOUNGE_MAP);
        when(pel.getPosition()).thenReturn(new Point(-45, -728));
        when(run.map.getItems()).thenReturn(List.of());
        AgentOpqSession session = loungeRun();
        AgentOpqCoordinator.tickSession(session, 3_000L);
        assertEquals(AgentOpqDefinition.LOUNGE_ROOM_MAPS.get(0), session.member(pel.getId()).assignedSubroomMapId());

        try (MockedStatic<AgentCombatBuffRuntime> buffs = mockStatic(AgentCombatBuffRuntime.class)) {
            buffs.when(() -> AgentCombatBuffRuntime.tryCastExplicitUtilityBuff(any(), any(), anyInt()))
                    .thenReturn(true);

            AgentOpqCoordinator.tickMember(session, run.entries.get(pel.getId()), pel, 3_500L);

            buffs.verify(() -> AgentCombatBuffRuntime.tryCastExplicitUtilityBuff(
                    run.entries.get(pel.getId()), pel, Assassin.HASTE));
        }
    }
}
