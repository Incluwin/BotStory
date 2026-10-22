package server.agents.capabilities.partyquest.ppq;

import client.Character;
import org.junit.jupiter.api.Test;

import java.awt.Point;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.BEN;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.MIRA;

/**
 * PPQ hidden side rooms 925100202 and 925100302 (PQ retrospective, 2026-10-17). An Agent there used to stand idle for
 * good; now it fights beside a leader who stands in the room while its mobs live and otherwise goes back to the deck.
 * Companions are not sent in: the rooms' Kru and Captains (9300117/9300118) have Mob.wz removeAfter 10, so they are
 * gone 10 s after the first entry spawns them, long before companions finishing the deck could get there (live,
 * 2026-10-18). Nor is the leader told the room is worth a visit.
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
    void companionsOnAClearedDeckGoOnWhileTheLeaderIsInItsHiddenRoom() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.DECK_ONE_MAP)) {
            Character mira = run.character(MIRA);
            Character ben = run.character(BEN);
            run.onMap(BEN, AgentPpqDefinition.SIDE_ONE_MAP);
            when(run.actions.liveMonsterCount(eq(mira), any())).thenReturn(0);
            when(run.actions.liveMonsterCount(eq(ben), any())).thenReturn(9);
            int next = AgentPpqDefinition.nextPortalId(AgentPpqDefinition.DECK_ONE_MAP);
            Point next00 = new Point(2133, 240);
            when(mira.getPosition()).thenReturn(next00);
            when(run.actions.portalPosition(mira, next)).thenReturn(next00);

            AgentPpqCoordinator.tickMember(run.session, run.entries.get(MIRA), mira, 5_000L);

            verify(run.partyQuest).enterPortal(mira, next);
            verify(run.partyQuest, never()).enterPortal(mira, 3);
        }
    }

    @Test
    void theLeaderIsNotToldAboutTheHiddenRoom() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.SIDE_ONE_MAP)) {
            run.tick(5_000L);
            run.tick(6_000L);

            verify(run.party, never()).sendPartyChat(any(), contains("hidden room"));
        }
    }
}
