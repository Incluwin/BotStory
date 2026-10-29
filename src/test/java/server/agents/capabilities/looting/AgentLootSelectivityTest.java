package server.agents.capabilities.looting;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.life.Monster;
import server.maps.MapItem;

import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * E4 pickup selectivity (docs/ECONOMY_PLAN.md E4): the host may leave a monster's equip drop, and only that: never
 * meso, another kind of item, a cash or quest equip, a player's drop, or anything inside an event instance.
 */
class AgentLootSelectivityTest {
    private static final int SWORD = 1302000;
    private static final int QUEST_EQUIP = 1002080;
    private static final int SNAIL_SHELL = 4000000;
    private static final IntPredicate QUEST_OR_CASH = itemId -> itemId == QUEST_EQUIP;
    private static final AgentLootPolicy.Selectivity LEAVES_ALL = (agent, drop) -> true;

    private final Character bot = mock(Character.class);

    @AfterEach
    void off() {
        AgentLootPolicy.installSelectivity(null);
    }

    @Test
    void theHostLeavesOnlyAMonstersEquipDrop() {
        assertTrue(AgentLootPolicy.leavesEquip(LEAVES_ALL, bot, drop(SWORD, 0, mock(Monster.class)), QUEST_OR_CASH));
        assertFalse(AgentLootPolicy.leavesEquip(LEAVES_ALL, bot, drop(SNAIL_SHELL, 0, mock(Monster.class)), QUEST_OR_CASH));
        assertFalse(AgentLootPolicy.leavesEquip(LEAVES_ALL, bot, drop(0, 500, mock(Monster.class)), QUEST_OR_CASH));
        assertFalse(AgentLootPolicy.leavesEquip(LEAVES_ALL, bot, drop(QUEST_EQUIP, 0, mock(Monster.class)), QUEST_OR_CASH));
        assertFalse(AgentLootPolicy.leavesEquip(LEAVES_ALL, bot, drop(SWORD, 0, mock(Character.class)), QUEST_OR_CASH),
                "a player's drop");
        assertFalse(AgentLootPolicy.leavesEquip(null, bot, drop(SWORD, 0, mock(Monster.class)), QUEST_OR_CASH),
                "nothing installed");
        assertFalse(AgentLootPolicy.leavesEquip((agent, drop) -> false, bot, drop(SWORD, 0, mock(Monster.class)),
                QUEST_OR_CASH), "the host keeps it");
    }

    @Test
    void insideAnEventInstanceTheHostIsNotAsked() {
        AgentLootPolicy.installSelectivity(LEAVES_ALL);
        when(bot.getEventInstance()).thenReturn(mock(EventInstanceManager.class));
        assertTrue(AgentLootPolicy.permits(bot, drop(SWORD, 0, mock(Monster.class))),
                "a party quest's or boss run's own loot rules decide there");
    }

    private static MapItem drop(int itemId, int meso, Object dropper) {
        MapItem drop = mock(MapItem.class);
        when(drop.getItemId()).thenReturn(itemId);
        when(drop.getMeso()).thenReturn(meso);
        when(drop.getDropper()).thenReturn((server.maps.MapObject) dropper);
        return drop;
    }
}
