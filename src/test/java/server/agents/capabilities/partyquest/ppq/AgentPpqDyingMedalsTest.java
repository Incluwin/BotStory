package server.agents.capabilities.partyquest.ppq;

import client.Character;
import client.inventory.InventoryType;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.plans.AgentScriptItemActionService;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.agents.capabilities.partyquest.ppq.PpqRunFixture.MIRA;

/**
 * P6i Step 2, plan 7.4 DYING: a companion at or under MEDAL_DYING_HP_PERCENT of its HP with no HP potion left drops
 * the wave's medals it holds at its feet (its death would delete them: they are the event's exclusive items) and says
 * so once a wave ('PPQ medals left:'); while dying it gathers no more. With a potion, or above the line, it keeps them.
 */
class AgentPpqDyingMedalsTest {
    private static final int ROOKIE = AgentPpqDefinition.ROOKIE_MEDAL;

    @Test
    void theDyingRuleNeedsLowHpAndNoPotion() {
        assertTrue(AgentPpqCompanionPlay.dying(250, 1_000, 0, 25));
        assertFalse(AgentPpqCompanionPlay.dying(251, 1_000, 0, 25));
        assertFalse(AgentPpqCompanionPlay.dying(100, 1_000, 3, 25), "it still has a potion");
        assertFalse(AgentPpqCompanionPlay.dying(0, 1_000, 0, 25), "already down");
    }

    @Test
    void aDyingCompanionLeavesItsMedalsAtItsFeetOnce() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.MEDAL_MAP);
             MockedStatic<AgentScriptItemActionService> drops = mockStatic(AgentScriptItemActionService.class);
             PpqLogLines lines = new PpqLogLines(AgentPpqCoordinator.class)) {
            Character mira = dying(run, 6);
            drops.when(() -> AgentScriptItemActionService.dropItem(run.entries.get(MIRA), InventoryType.ETC, ROOKIE,
                    (short) 6)).thenReturn(true);

            AgentPpqCoordinator.tickMember(run.session, run.entries.get(MIRA), mira, 5_000L);

            drops.verify(() -> AgentScriptItemActionService.dropItem(run.entries.get(MIRA), InventoryType.ETC, ROOKIE,
                    (short) 6));
            verify(run.packets).broadcastChatText(mira, "almost out of pots, leaving my medals here. grab them.",
                    false, 0);
            assertEquals(List.of("PPQ medals left: session=" + run.session.sessionId() + " member=c11 item=" + ROOKIE
                    + " count=6 hp=100"), lines.startingWith("PPQ medals left:"));
        }
    }

    @Test
    void aDyingCompanionHoldingNoMedalsGathersNoneAndFights() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.MEDAL_MAP);
             MockedStatic<AgentScriptItemActionService> drops = mockStatic(AgentScriptItemActionService.class)) {
            Character mira = dying(run, 0);
            when(run.actions.liveMonsterCount(any(), any())).thenReturn(4);

            AgentPpqCoordinator.tickMember(run.session, run.entries.get(MIRA), mira, 5_000L);

            drops.verifyNoInteractions();
            verify(run.actions).grind(run.entries.get(MIRA), AgentPpqDefinition.COMBAT_MOBS);
            verify(run.actions, never()).navigate(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        }
    }

    @Test
    void withAPotionLeftItKeepsItsMedals() {
        try (PpqRunFixture run = new PpqRunFixture(true, AgentPpqDefinition.MEDAL_MAP);
             MockedStatic<AgentScriptItemActionService> drops = mockStatic(AgentScriptItemActionService.class)) {
            Character mira = dying(run, 6);
            when(run.actions.hpPotionCount(mira)).thenReturn(2);
            when(run.actions.liveMonsterCount(any(), any())).thenReturn(4);

            AgentPpqCoordinator.tickMember(run.session, run.entries.get(MIRA), mira, 5_000L);

            drops.verifyNoInteractions();
        }
    }

    /** Mira at 100 of 1000 HP, no potion, holding {@code medals} Rookie medals far from Ben. */
    private static Character dying(PpqRunFixture run, int medals) {
        run.properties.put("stage2", "0");
        Character mira = run.character(MIRA);
        when(mira.getHp()).thenReturn(100);
        when(mira.getCurrentMaxHp()).thenReturn(1_000);
        when(mira.getPosition()).thenReturn(new Point(900, 50));
        when(run.character(PpqRunFixture.BEN).getPosition()).thenReturn(new Point(100, 50));
        when(run.actions.itemCount(mira, ROOKIE)).thenReturn(medals);
        return mira;
    }
}
