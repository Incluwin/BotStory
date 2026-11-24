package server.living.population;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import config.PopulationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import server.agents.capabilities.shop.AgentFreeMarketStallService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * What a citizen's stall lists with the scroll switches (docs/ECONOMY_PLAN.md 10.14). Both off: the lines, bundles and
 * asks of the rule before them, computed here from the same random stream. The cap cuts scroll lines only, the errands
 * only split scroll stacks, and each works without the other.
 */
class ScrollStallSwitchesTest {
    private static final int MERCHANT = 77;
    private static final int[] SCROLLS = {2040804, 2040805, 2043802, 2049100, 2040817, 2040029};
    private static final int ETC_FIRST = 4000000;

    private final PopulationConfig config = new PopulationConfig();
    private final Population population = mock(Population.class);
    private final Character merchant = mock(Character.class);
    private final List<Item> use = new ArrayList<>();
    private final List<Item> etc = new ArrayList<>();
    private MockedStatic<Market> names;

    @BeforeEach
    void fill() {
        CitizenStore store = mock(CitizenStore.class);
        when(population.config()).thenReturn(config);
        when(population.store()).thenReturn(store);
        List<int[]> prices = new ArrayList<>();
        for (int i = 0; i < SCROLLS.length; i++) {
            use.add(new Item(SCROLLS[i], (short) (i + 1), (short) 12));
            prices.add(new int[]{SCROLLS[i], 100_000 + 10_000 * i, 1});
        }
        for (int i = 0; i < 8; i++) {
            etc.add(new Item(ETC_FIRST + i, (short) (i + 1), (short) 50));
            prices.add(new int[]{ETC_FIRST + i, 100 + i, 1});
        }
        when(store.prices()).thenReturn(prices);
        when(merchant.getId()).thenReturn(MERCHANT);
        bag(InventoryType.USE, use);
        bag(InventoryType.ETC, etc);
        bag(InventoryType.EQUIP, List.of());
        names = mockStatic(Market.class,
                call -> "itemName".equals(call.getMethod().getName()) ? "an item" : call.callRealMethod());
    }

    @AfterEach
    void close() {
        names.close();
    }

    private void bag(InventoryType type, List<Item> items) {
        Inventory inventory = mock(Inventory.class);
        when(inventory.list()).thenReturn(items);
        when(merchant.getInventory(type)).thenReturn(inventory);
    }

    /** {@code useful}: the scroll a citizen of some job could use. */
    private List<AgentFreeMarketStallService.Listing> listed(int useful) {
        try (MockedConstruction<CitizenScrolling> ignored = mockConstruction(CitizenScrolling.class,
                (scrolling, context) -> when(scrolling.useful(anyInt())).thenAnswer(call -> (int) call.getArgument(0) == useful))) {
            return new Market(population, itemId -> 1L).stock(merchant).shopListings();
        }
    }

    private static long scrollLines(List<AgentFreeMarketStallService.Listing> lines) {
        return lines.stream().filter(line -> line.inventoryType() == InventoryType.USE).count();
    }

    @Test
    void bothOffAreTheLinesBundlesAndAsksOfBefore() {
        List<AgentFreeMarketStallService.Listing> lines = listed(0);
        assertEquals(8, lines.size());
        Random same = new Random(config.SEED ^ 0x3a44e7L);
        for (int i = 0; i < lines.size(); i++) {
            boolean scroll = i < SCROLLS.length;
            int which = scroll ? SCROLLS.length - 1 - i : 7 - (i - SCROLLS.length);
            long unit = scroll ? 100_000 + 10_000L * which : 100 + which;
            int perBundle = scroll ? 10 : 50;
            long ask = Math.round(unit * perBundle * (0.9d + same.nextDouble() * 0.3d));
            assertEquals(new AgentFreeMarketStallService.Listing(scroll ? InventoryType.USE : InventoryType.ETC,
                    (scroll ? use : etc).get(which).getPosition(), (short) perBundle, (short) 1, (int) ask), lines.get(i));
        }
    }

    @Test
    void theCapAloneListsTwoScrollStacksInBundlesAndMoreOtherGoods() {
        config.MARKET_STALL_SCROLL_CAP_ENABLED = true;
        List<AgentFreeMarketStallService.Listing> lines = listed(SCROLLS[0]);
        assertEquals(2, scrollLines(lines));
        assertEquals(8, lines.size(), "six ETC lines instead of two");
        List<Short> slots = lines.stream().filter(l -> l.inventoryType() == InventoryType.USE).map(l -> l.slot()).toList();
        assertEquals(List.of((short) 6, (short) 1), slots, "the dearest, and the one a citizen could use");
        assertTrue(lines.stream().filter(l -> l.inventoryType() == InventoryType.USE).allMatch(l -> l.perBundle() == 10));
    }

    @Test
    void theErrandsAloneSplitScrollStacksAndKeepEveryLine() {
        config.SCROLL_ERRANDS_ENABLED = true;
        List<AgentFreeMarketStallService.Listing> lines = listed(0);
        assertEquals(SCROLLS.length, scrollLines(lines));
        assertTrue(lines.stream().filter(l -> l.inventoryType() == InventoryType.USE)
                .allMatch(l -> l.perBundle() == 1 && l.bundles() == 12));
        assertTrue(lines.stream().filter(l -> l.inventoryType() == InventoryType.ETC).allMatch(l -> l.perBundle() == 50));
    }
}
