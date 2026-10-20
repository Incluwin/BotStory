package server.agents.capabilities.partyquest.ppq;

import client.Character;
import org.junit.jupiter.api.Test;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.BEN;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.MIRA;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.TOBI;

/**
 * P6i Step 2, plan 7.10 (decision D-f'): after the clear a companion run's companions wait by Wu Yang while their
 * leader is still in his room, the first one there saying so once, and take his Feather exit only once he has left or
 * COMPANION_AFTER_CLEAR_WAIT_MS has passed: leaving before him, the hand-back's follow would pull them back into a room
 * with no exit. A stock run's Agents take the exit at once, as before.
 */
class AgentPpqWuYangWaitTest {
    private static final Point WU_YANG = new Point(698, -32);

    private static Character byWuYang(PpqRunFixture run, int id) {
        Character agent = run.character(id);
        when(agent.getPosition()).thenReturn(new Point(WU_YANG.x - 20, WU_YANG.y));
        when(run.actions.npcPosition(agent, AgentPpqDefinition.RESCUE_NPC)).thenReturn(WU_YANG);
        PpqAdmitFixture.room(agent, client.inventory.InventoryType.ETC, 1);
        return agent;
    }

    @Test
    void theCompanionsWaitByWuYangWhileTheLeaderIsStillInHisRoom() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.CLEAR_MAP)) {
            Character mira = byWuYang(run, MIRA);
            Character tobi = byWuYang(run, TOBI);

            AgentPpqCoordinator.tickMember(run.session, run.entries.get(MIRA), mira, 5_000L);
            AgentPpqCoordinator.tickMember(run.session, run.entries.get(TOBI), tobi, 5_100L);
            AgentPpqCoordinator.tickMember(run.session, run.entries.get(MIRA), mira, 6_000L);

            verify(run.partyQuest, never()).runNpc(any(), anyInt(), anyInt());
            verify(run.partyQuest, never()).runNpc(any(), anyInt());
            verify(run.packets, times(1)).broadcastChatText(mira, "nice! wu yang has a feather for each of us.",
                    false, 0);
            verify(run.packets, never()).broadcastChatText(tobi, "nice! wu yang has a feather for each of us.",
                    false, 0);
        }
    }

    @Test
    void theyTakeHisExitOnceTheLeaderLeft() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.CLEAR_MAP)) {
            Character mira = byWuYang(run, MIRA);
            run.onMap(BEN, AgentPpqDefinition.RECRUIT_MAP);
            when(run.partyQuest.runNpc(mira, AgentPpqDefinition.RESCUE_NPC, 0)).thenReturn(true);

            AgentPpqCoordinator.tickMember(run.session, run.entries.get(MIRA), mira, 5_000L);

            verify(run.partyQuest).runNpc(mira, AgentPpqDefinition.RESCUE_NPC, 0);
        }
    }

    @Test
    void theyStopWaitingOnceTheWaitHasPassedSinceTheClear() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.CLEAR_MAP)) {
            Character mira = byWuYang(run, MIRA);
            when(run.event.isEventCleared()).thenReturn(true);
            run.tick(2_000L); // the run sees its clear
            assertTrue(run.session.clearedAtMs() >= 0L);

            AgentPpqCoordinator.tickMember(run.session, run.entries.get(MIRA), mira,
                    2_000L + AgentPpqCoordinator.COMPANION_AFTER_CLEAR_WAIT_MS - 1);
            verify(run.partyQuest, never()).runNpc(any(), anyInt(), anyInt());

            AgentPpqCoordinator.tickMember(run.session, run.entries.get(MIRA), mira,
                    2_000L + AgentPpqCoordinator.COMPANION_AFTER_CLEAR_WAIT_MS);
            verify(run.partyQuest).runNpc(mira, AgentPpqDefinition.RESCUE_NPC, 0);
            assertFalse(run.session.terminal(), "the run itself is bounded by AFTER_CLEAR_LIMIT_MS, still later");
        }
    }

    @Test
    void aStockRunsAgentsTakeTheExitAtOnce() {
        try (PpqRunFixture run = new PpqRunFixture(false, AgentPpqDefinition.CLEAR_MAP)) {
            Character mira = byWuYang(run, MIRA);

            AgentPpqCoordinator.tickMember(run.session, run.entries.get(MIRA), mira, 5_000L);

            verify(run.partyQuest).runNpc(mira, AgentPpqDefinition.RESCUE_NPC, 0);
        }
    }

    @Test
    void theWaitIsShorterThanTheRunsOwnAfterClearLimit() {
        assertTrue(AgentPpqCoordinator.COMPANION_AFTER_CLEAR_WAIT_MS < 300_000L,
                "the companions leave before AFTER_CLEAR_LIMIT_MS takes them out without a Feather");
        assertTrue(AgentPpqCompanionPlay.waitByWuYang(true, 0L, 1_000L));
        assertFalse(AgentPpqCompanionPlay.waitByWuYang(false, 0L, 1_000L));
        assertFalse(AgentPpqCompanionPlay.waitByWuYang(true, 1_000L, 1_000L));
    }
}
