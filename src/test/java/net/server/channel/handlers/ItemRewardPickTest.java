package net.server.channel.handlers;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.ItemInformationProvider;
import server.ItemInformationProvider.RewardItem;
import tools.DatabaseConnection;
import tools.Pair;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mockStatic;

/**
 * Reward boxes (live 2026-11-02): Item.wz probs run up to 5500, and the loader cast them to a byte, so box 2022503's
 * 5-day belt (prob 2285, 11.5 % of the box) read -19 and never dropped in 36 opened boxes. A box is one weighted pick.
 */
class ItemRewardPickTest {
    @Test
    void eachRowTakesItsProbOfTheRoll() {
        List<RewardItem> rows = List.of(row(1132010, 2285), row(2020015, 50), row(2041300, 450));

        assertEquals(1132010, ItemRewardHandler.pick(rows, 0).itemid);
        assertEquals(1132010, ItemRewardHandler.pick(rows, 2284).itemid);
        assertEquals(2020015, ItemRewardHandler.pick(rows, 2285).itemid);
        assertEquals(2020015, ItemRewardHandler.pick(rows, 2334).itemid);
        assertEquals(2041300, ItemRewardHandler.pick(rows, 2335).itemid);
        assertEquals(2041300, ItemRewardHandler.pick(rows, 2784).itemid);
        assertNull(ItemRewardHandler.pick(rows, 2785));
    }

    @Test
    void probsAbove127AreReadWhole() {
        ItemInformationProvider ii;
        try (MockedStatic<DatabaseConnection> database = mockStatic(DatabaseConnection.class)) {
            database.when(DatabaseConnection::getConnection).thenThrow(new SQLException("no database in this test"));
            ii = ItemInformationProvider.getInstance();
        }

        Pair<Integer, List<RewardItem>> box = ii.getItemReward(2022503);

        RewardItem belt = box.getRight().get(0);
        assertEquals(1132010, belt.itemid);
        assertEquals(2285, belt.prob);
        assertEquals(7200, belt.period);
        assertEquals(19864, box.getLeft());
        assertSame(belt, ItemRewardHandler.pick(box.getRight(), 2284));
    }

    private static RewardItem row(int itemId, int prob) {
        RewardItem row = new RewardItem();
        row.itemid = itemId;
        row.prob = (short) prob;
        row.quantity = 1;
        row.period = -1;
        return row;
    }
}
