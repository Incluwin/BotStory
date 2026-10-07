package server.agents.capabilities.partyquest.epq;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.looting.AgentLootEligibility;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.MapItem;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Step 2.7: the loot rule for a companion run's hand-over stacks. A stack a companion dropped for the leader is his:
 * only the companion that claimed it back may loot it; a natural drop and every other run keep today's int rule.
 * Golem's Marble (no exclusive item) stays free outside a companion run, so an Agent-led run's work Agent still loots it
 * after the clear. The bots' loot path asks the rule with the drop in hand.
 */
class AgentEpqLootRuleTest {
    private final EpqRunFixture run = new EpqRunFixture();
    private final Character ben = run.human(1, "Ben", AgentEpqDefinition.STAGE_FOUR_MAP);
    private final Character mira = run.companion(2, "Mira", AgentEpqDefinition.STAGE_FOUR_MAP);
    private final Character tobi = run.companion(3, "Tobi", AgentEpqDefinition.STAGE_FOUR_MAP);
    private final Character una = run.companion(4, "Una", AgentEpqDefinition.STAGE_FOUR_MAP);
    private final Character pia = run.companion(5, "Pia", AgentEpqDefinition.BOSS_MAP);

    @AfterEach
    void tearDown() {
        run.close();
    }

    private AgentEpqSession session(boolean companionRun) {
        return run.session(AgentEpqSession.Mode.HUMAN_ASSISTED, companionRun, AgentEpqSession.Phase.STAGE_FOUR,
                ben, mira, tobi, una);
    }

    @Test
    void aHandOverStackIsOnlyItsRecollectorsToLoot() {
        AgentEpqSession session = session(true);
        MapItem stack = run.drop(AgentEpqDefinition.MONSTER_MARBLE, 6, new Point(0, 0), true, mira.getId(), 1_000L);

        assertFalse(AgentEpqSessionRegistry.canLootExclusive(mira, stack, 2_000L), "nobody claimed it: his");
        assertFalse(AgentEpqSessionRegistry.canLootExclusive(tobi, stack, 2_000L));
        assertTrue(session.beginRecollect(stack.getObjectId(), mira.getId()));

        assertTrue(AgentEpqSessionRegistry.canLootExclusive(mira, stack, 2_000L));
        assertFalse(AgentEpqSessionRegistry.canLootExclusive(tobi, stack, 2_000L));
        assertFalse(session.beginRecollect(stack.getObjectId(), tobi.getId()), "the first claimer wins");
    }

    @Test
    void pinANaturalMarbleAndAStockRunKeepTheIntRule() {
        AgentEpqSession stock = session(false);
        MapItem stack = run.drop(AgentEpqDefinition.MONSTER_MARBLE, 6, new Point(0, 0), true, mira.getId(), 1_000L);
        stock.beginRecollect(stack.getObjectId(), mira.getId());

        // The int rule: stage-4 marbles are the event leader's (a human here), whoever dropped them.
        assertFalse(AgentEpqSessionRegistry.canLootExclusive(mira, stack, 2_000L));
        assertFalse(AgentEpqSessionRegistry.canLootExclusive(mira, AgentEpqDefinition.MONSTER_MARBLE));
    }

    @Test
    void pinGolemsMarbleStaysFreeOutsideACompanionRun() {
        MapItem marble = run.drop(AgentEpqDefinition.GOLEMS_MARBLE, 1, new Point(0, 0), false, 0, 1_000L);
        assertTrue(AgentEpqSessionRegistry.canLootExclusive(pia, marble, 2_000L), "no EPQ session");

        Character lead = run.companion(6, "Lead", AgentEpqDefinition.BOSS_MAP);
        AgentEpqSession agentLed = run.session(AgentEpqSession.Mode.AUTONOMOUS, false, AgentEpqSession.Phase.BOSS,
                lead, mira, tobi, una);
        assertTrue(AgentEpqSessionRegistry.canLootExclusive(lead, marble, 2_000L),
                "an Agent-led run's work Agent takes it for the fountain");
        assertTrue(agentLed.workAgentId() == lead.getId());
    }

    @Test
    void golemsMarbleInACompanionRunIsTheWorkAgentsOnTheGolemsMapAfterTheLeadersFirstClaim() {
        AgentEpqSession session = session(true);
        run.onMap(mira, AgentEpqDefinition.BOSS_MAP);
        run.onMap(tobi, AgentEpqDefinition.BOSS_MAP);
        MapItem marble = run.drop(AgentEpqDefinition.GOLEMS_MARBLE, 1, new Point(0, 0), false, 0, 1_000L);

        assertFalse(AgentEpqSessionRegistry.canLootExclusive(mira, marble, 15_999L), "the leader's first claim");
        assertTrue(AgentEpqSessionRegistry.canLootExclusive(mira, marble, 16_000L));
        assertFalse(AgentEpqSessionRegistry.canLootExclusive(tobi, marble, 16_000L), "the work Agent only");
        run.onMap(mira, AgentEpqDefinition.REWARD_MAP);
        assertFalse(AgentEpqSessionRegistry.canLootExclusive(mira, marble, 16_000L), "on the golem's map only");
        run.onMap(mira, AgentEpqDefinition.BOSS_MAP);
        session.decideLeave(16_500L, "wait");
        assertFalse(AgentEpqSessionRegistry.canLootExclusive(mira, marble, 17_000L), "never once they leave");
    }

    @Test
    void theBotsLootPathAsksTheRuleWithTheDropInHand() {
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(88);
        when(bot.needQuestItem(anyInt(), anyInt())).thenReturn(true);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(bot, mock(Character.class), null);
        for (int itemId : new int[] {AgentEpqDefinition.MONSTER_MARBLE, AgentEpqDefinition.GOLEMS_MARBLE}) {
            MapItem drop = mock(MapItem.class);
            when(drop.getItemId()).thenReturn(itemId);
            when(drop.canBePickedBy(bot)).thenReturn(true);
            for (boolean answer : new boolean[] {true, false}) {
                try (MockedStatic<AgentEpqSessionRegistry> epq = mockStatic(AgentEpqSessionRegistry.class,
                        CALLS_REAL_METHODS)) {
                    epq.when(() -> AgentEpqSessionRegistry.canLootExclusive(bot, drop)).thenReturn(answer);

                    assertEquals(answer, AgentLootEligibility.canBotReceiveAssignedLoot(entry, bot, drop),
                            "item " + itemId + " follows the rule's answer for the drop");
                }
            }
        }
    }
}
