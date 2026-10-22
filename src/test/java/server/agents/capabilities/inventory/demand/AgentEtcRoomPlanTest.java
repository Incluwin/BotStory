package server.agents.capabilities.inventory.demand;

import client.inventory.Item;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CWKPQ C9 (2026-10-22): a companion makes room in its ETC tab before the keep by selling whole stacks of monster drops
 * no quest asks for, the kind it holds most stacks of first; ores, crafting materials, game pieces and a drop a quest
 * asks for stay (user decision).
 */
class AgentEtcRoomPlanTest {
    private static final int SQUISHY_LIQUID = 4_000_022;
    private static final int PIG_RIBBON = 4_000_025;
    private static final int QUEST_DROP = 4_000_014;
    private static final int GOLD_ORE = 4_004_000;
    private static final int GAME_PIECE = 4_030_000;

    @Test
    void sellsTheMostStackedMonsterDropFirstSmallestStackFirst() {
        Item liquidFull = stack(SQUISHY_LIQUID, 1, 200);
        Item liquidSmall = stack(SQUISHY_LIQUID, 2, 1);
        Item liquidOther = stack(SQUISHY_LIQUID, 3, 200);
        Item ribbon = stack(PIG_RIBBON, 4, 71);

        List<Item> sales = AgentEtcRoomPlan.stacksToSell(List.of(liquidFull, liquidSmall, liquidOther, ribbon),
                itemId -> false, 2);

        assertEquals(List.of(liquidSmall, liquidFull), sales);
    }

    @Test
    void keepsOresGamePiecesAndDropsAQuestAsksFor() {
        Item ore = stack(GOLD_ORE, 1, 50);
        Item piece = stack(GAME_PIECE, 2, 1);
        Item questDrop = stack(QUEST_DROP, 3, 52);
        Item ribbon = stack(PIG_RIBBON, 4, 71);

        List<Item> sales = AgentEtcRoomPlan.stacksToSell(List.of(ore, piece, questDrop, ribbon),
                itemId -> itemId == QUEST_DROP, 4);

        assertEquals(List.of(ribbon), sales, "only what may go, even when fewer than the slots lacking");
    }

    private static Item stack(int itemId, int slot, int quantity) {
        return new Item(itemId, (short) slot, (short) quantity);
    }
}
