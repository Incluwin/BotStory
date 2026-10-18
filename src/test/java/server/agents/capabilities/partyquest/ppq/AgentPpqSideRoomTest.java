package server.agents.capabilities.partyquest.ppq;

import client.Character;
import org.junit.jupiter.api.Test;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.BEN;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.MIRA;

/**
 * PPQ hidden side rooms 925100202 and 925100302 (PQ retrospective, 2026-10-17): optional rooms of Kru and Captains
 * (EXP, mesos, the Herb Town concentrates). An Agent there used to stand idle for good; now it fights beside a leader
 * who stands in the room while its mobs live and otherwise goes back to the deck, and companions on a cleared deck
 * join a human leader fighting in its hidden room.
 */
class AgentPpqSideRoomTest {
    private static final Point PORTAL = new Point(630, 235);

    @Test
    void anAgentFightsBesideTheLeaderInTheRoom() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.SIDE_ONE_MAP)) {
            Character mira = run.character(MIRA);
            when(run.actions.liveMonsterCount(eq(mira), any())).thenReturn(6);

            AgentPpqCoordinator.tickMember(run.session, run.entries.get(MIRA), mira, 5_000L);

            verify(run.actions).grind(run.entries.get(MIRA), AgentPpqDefinition.COMBAT_MOBS);
            verify(run.partyQuest, never()).enterPortal(any(), any(Integer.class));
        }
    }

    @Test
    void anAgentLeavesAClearedRoomOrOneTheLeaderIsNotIn() {
        for (boolean leaderHere : new boolean[] {true, false}) {
            try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.SIDE_TWO_MAP)) {
                Character mira = run.character(MIRA);
                when(mira.getPosition()).thenReturn(PORTAL);
                when(run.actions.portalPosition(mira, AgentPpqDefinition.SIDE_ROOM_EXIT_PORTAL)).thenReturn(PORTAL);
                when(run.actions.liveMonsterCount(eq(mira), any())).thenReturn(leaderHere ? 0 : 5);
                if (!leaderHere) run.onMap(BEN, AgentPpqDefinition.DECK_TWO_MAP);

                AgentPpqCoordinator.tickMember(run.session, run.entries.get(MIRA), mira, 5_000L);

                verify(run.partyQuest).enterPortal(mira, AgentPpqDefinition.SIDE_ROOM_EXIT_PORTAL);
            }
        }
    }

    @Test
    void companionsOnAClearedDeckJoinALeaderFightingInItsHiddenRoom() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.DECK_ONE_MAP)) {
            Character mira = run.character(MIRA);
            Character ben = run.character(BEN);
            run.onMap(BEN, AgentPpqDefinition.SIDE_ONE_MAP);
            when(run.actions.liveMonsterCount(eq(mira), any())).thenReturn(0);
            when(run.actions.liveMonsterCount(eq(ben), any())).thenReturn(9);
            Point in01 = new Point(1653, 235);
            when(mira.getPosition()).thenReturn(in01);
            when(run.actions.portalPosition(mira, AgentPpqDefinition.SIDE_ROOM_ENTRY_PORTAL)).thenReturn(in01);

            AgentPpqCoordinator.tickMember(run.session, run.entries.get(MIRA), mira, 5_000L);

            verify(run.partyQuest).enterPortal(mira, AgentPpqDefinition.SIDE_ROOM_ENTRY_PORTAL);
        }
    }

    @Test
    void aClearedHiddenRoomOrAStockRunSendsNobodyIn() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.DECK_ONE_MAP)) {
            run.onMap(BEN, AgentPpqDefinition.SIDE_ONE_MAP);
            when(run.actions.liveMonsterCount(any(), any())).thenReturn(0);
            assertFalse(AgentPpqCoordinator.leaderFightingInSideRoom(run.session, run.character(BEN),
                    AgentPpqDefinition.DECK_ONE_MAP));
        }
        try (PpqRunFixture run = new PpqRunFixture(false, AgentPpqDefinition.DECK_ONE_MAP)) {
            run.onMap(BEN, AgentPpqDefinition.SIDE_ONE_MAP);
            when(run.actions.liveMonsterCount(any(), any())).thenReturn(9);
            assertFalse(AgentPpqCoordinator.leaderFightingInSideRoom(run.session, run.character(BEN),
                    AgentPpqDefinition.DECK_ONE_MAP));
        }
    }

    @Test
    void eachDeckKnowsItsHiddenRoom() {
        assertEquals(AgentPpqDefinition.SIDE_ONE_MAP, AgentPpqDefinition.sideRoomOf(AgentPpqDefinition.DECK_ONE_MAP));
        assertEquals(AgentPpqDefinition.SIDE_TWO_MAP, AgentPpqDefinition.sideRoomOf(AgentPpqDefinition.DECK_TWO_MAP));
        assertEquals(0, AgentPpqDefinition.sideRoomOf(AgentPpqDefinition.MEDAL_MAP));
    }

    @Test
    void theLeaderIsToldTheRoomIsOptional() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.SIDE_ONE_MAP)) {
            run.tick(5_000L);
            run.tick(6_000L);

            verify(run.party).sendPartyChat(any(), eq("c865, this hidden room is optional: EXP, mesos and Herb Town "
                    + "concentrates. The deck timer keeps running."));
        }
    }
}
