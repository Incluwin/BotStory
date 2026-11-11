package server.agents.capabilities.inventory;

import client.Character;
import client.inventory.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Which looted Use stacks a potion visit gives up for room, and in what order (2026-11-11): scrolls and books, the
 * whole stack worth least by its host's market first, nothing at all without a host that lets the Agent sell loot.
 */
class AgentUseRoomLootTest {
    private static final int HELMET_DEF_60 = 2_040_001;
    private static final int GLOVE_ATT_10 = 2_040_804;
    private static final int CHAOS = 2_049_100;
    private static final int MASTERY_BOOK = 2_290_010;
    private static final int SKILL_BOOK = 2_280_013;
    private static final int ELIXIR = 2_000_004;
    private static final int ARROW = 2_060_000;
    private static final int WHITE_SCROLL = 2_340_000;

    @AfterEach
    void uninstall() {
        AgentUseRoomLoot.install(null);
    }

    @Test
    void scrollsAndBooksAreLootAndNothingElseIs() {
        assertTrue(AgentUseRoomLoot.isLoot(HELMET_DEF_60));
        assertTrue(AgentUseRoomLoot.isLoot(CHAOS));
        assertTrue(AgentUseRoomLoot.isLoot(MASTERY_BOOK));
        assertTrue(AgentUseRoomLoot.isLoot(SKILL_BOOK));
        assertFalse(AgentUseRoomLoot.isLoot(ELIXIR), "potions are what the room is for");
        assertFalse(AgentUseRoomLoot.isLoot(ARROW), "ammo has its own sale");
        assertFalse(AgentUseRoomLoot.isLoot(WHITE_SCROLL), "a white scroll is no equipment scroll");
        assertTrue(AgentUseRoomLoot.isBook(MASTERY_BOOK));
        assertFalse(AgentUseRoomLoot.isBook(HELMET_DEF_60));
    }

    @Test
    void theWholeStackWorthLeastGoesFirstAndTiesGoBySlot() {
        Map<Integer, Long> index = Map.of(HELMET_DEF_60, 25_000L, GLOVE_ATT_10, 2_984_993L, MASTERY_BOOK, 5_000_000L,
                CHAOS, 40_000L);
        Item glove = stack(GLOVE_ATT_10, 1, 1);
        Item helmets = stack(HELMET_DEF_60, 3, 2); // 75,000 as a stack
        Item chaos = stack(CHAOS, 1, 3); // 40,000
        Item book = stack(MASTERY_BOOK, 1, 4);
        Item otherChaos = stack(CHAOS, 1, 5);

        assertEquals(List.of(chaos, otherChaos, helmets, glove, book),
                AgentUseRoomLoot.worthLeastFirst(List.of(glove, helmets, chaos, book, otherChaos), index::get));
    }

    @Test
    void aStackWorthMoreThanALongHoldsIsWorthTheMost() {
        Item priceless = stack(HELMET_DEF_60, 3, 1);
        Item cheap = stack(GLOVE_ATT_10, 1, 2);

        assertEquals(List.of(cheap, priceless), AgentUseRoomLoot.worthLeastFirst(List.of(priceless, cheap),
                itemId -> itemId == HELMET_DEF_60 ? Long.MAX_VALUE / 2 : 10L));
    }

    @Test
    void withoutAHostNoAgentSellsLootAndNothingHasAPrice() {
        Character agent = mock(Character.class);

        assertFalse(AgentUseRoomLoot.sellsLoot(agent));
        assertEquals(Long.MAX_VALUE, AgentUseRoomLoot.worth(HELMET_DEF_60));
    }

    @Test
    void theHostSaysWhoSellsAndAFailingHostSellsNothing() {
        Character citizen = mock(Character.class);
        Character player = mock(Character.class);
        AgentUseRoomLoot.install(new AgentUseRoomLoot.Rule() {
            @Override
            public boolean sellsLoot(Character agent) {
                if (agent == null) {
                    throw new IllegalStateException("never asked for nobody");
                }
                return agent == citizen;
            }

            @Override
            public long worth(int itemId) {
                if (itemId == CHAOS) {
                    throw new IllegalStateException("no price");
                }
                return itemId == HELMET_DEF_60 ? -5L : 25_000L;
            }
        });

        assertTrue(AgentUseRoomLoot.sellsLoot(citizen));
        assertFalse(AgentUseRoomLoot.sellsLoot(player));
        assertFalse(AgentUseRoomLoot.sellsLoot(null));
        assertEquals(25_000L, AgentUseRoomLoot.worth(GLOVE_ATT_10));
        assertEquals(0L, AgentUseRoomLoot.worth(HELMET_DEF_60), "never below nothing");
        assertEquals(Long.MAX_VALUE, AgentUseRoomLoot.worth(CHAOS), "a failed price is sold last");
    }

    private static Item stack(int itemId, int quantity, int slot) {
        Item item = new Item(itemId, (short) slot, (short) quantity);
        return item;
    }
}
