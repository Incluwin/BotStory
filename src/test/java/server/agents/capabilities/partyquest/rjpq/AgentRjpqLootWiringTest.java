package server.agents.capabilities.partyquest.rjpq;

import client.Character;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.looting.AgentLootEligibility;
import server.agents.runtime.AgentRuntimeEntry;
import server.maps.MapItem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/**
 * The wiring of the RJPQ loot clause (plan W3): the bots' shared loot path asks it for an RJPQ item or for any drop
 * a member of an RJPQ run looks at, and follows its answer; a bot outside any RJPQ run is never asked about another
 * item.
 */
class AgentRjpqLootWiringTest {
    private static Character bot() {
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(88);
        when(bot.needQuestItem(anyInt(), anyInt())).thenReturn(true);
        return bot;
    }

    private static MapItem drop(Character bot, int itemId) {
        MapItem drop = mock(MapItem.class);
        when(drop.getItemId()).thenReturn(itemId);
        when(drop.canBePickedBy(bot)).thenReturn(true);
        return drop;
    }

    @Test
    void theSharedLootPathFollowsTheClauseForAnRjpqItemOrAnyDropOfARunMember() {
        Character bot = bot();
        AgentRuntimeEntry entry = new AgentRuntimeEntry(bot, mock(Character.class), null);
        MapItem liquid = drop(bot, AgentRjpqDefinition.LIQUID_ITEM_ID);
        MapItem pill = drop(bot, 2_022_000);
        for (boolean answer : new boolean[] {true, false}) {
            try (MockedStatic<AgentRjpqSessionRegistry> rjpq = mockStatic(AgentRjpqSessionRegistry.class,
                    CALLS_REAL_METHODS)) {
                rjpq.when(() -> AgentRjpqSessionRegistry.canLoot(any(), any())).thenReturn(answer);
                assertEquals(answer, AgentLootEligibility.canBotReceiveAssignedLoot(entry, bot, liquid), "liquid");

                rjpq.when(() -> AgentRjpqSessionRegistry.forMember(88)).thenReturn(mock(AgentRjpqSession.class));
                assertEquals(answer, AgentLootEligibility.canBotReceiveAssignedLoot(entry, bot, pill), "member");
            }
        }
    }

    @Test
    void aBotOutsideAnyRjpqRunIsNeverAskedAboutAnotherItem() {
        Character bot = bot();
        AgentRuntimeEntry entry = new AgentRuntimeEntry(bot, mock(Character.class), null);
        MapItem pill = drop(bot, 2_022_000);
        try (MockedStatic<AgentRjpqSessionRegistry> rjpq = mockStatic(AgentRjpqSessionRegistry.class,
                CALLS_REAL_METHODS)) {
            AgentLootEligibility.canBotReceiveAssignedLoot(entry, bot, pill);
            rjpq.verify(() -> AgentRjpqSessionRegistry.canLoot(any(), any()), never());
        }
    }
}
