package server.living.population;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import config.PopulationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.inventory.AgentStallHolds;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * List or vendor (docs/ECONOMY_PLAN.md E3): a merchant citizen keeps for its stall the equips the price index values
 * well over the shop's price, the best few, and vendors the rest; its stall lists the most valuable stacks first; and a
 * line nobody bought moves the index down, so gear that never sells goes back to the shop in time.
 */
class MarketListOrVendorTest {
    private static final int MERCHANT = 5001;
    private static final int TOWNIE = 5002;
    private static final int HIRED = 5003;
    private static final int HAT = 1002094;   // shop 85k
    private static final int GLOVES = 1082098; // shop 70k
    private static final int SHOES = 1072210;  // shop 55k
    private static final int SHELL = 4000019;  // ETC

    private static final Map<Integer, Long> SHOP = Map.of(HAT, 85_000L, GLOVES, 70_000L, SHOES, 55_000L);

    private final Population population = mock(Population.class);
    private final CitizenStore store = mock(CitizenStore.class);
    private final PopulationConfig config = new PopulationConfig();
    private final Market market;

    MarketListOrVendorTest() {
        when(population.config()).thenReturn(config);
        when(population.store()).thenReturn(store);
        List<int[]> rows = new ArrayList<>();
        rows.add(new int[] {HAT, 300_000, 4});    // 3.5x the shop
        rows.add(new int[] {GLOVES, 90_000, 4});  // 1.3x: not worth a stall slot
        rows.add(new int[] {SHOES, 200_000, 4});  // 3.6x
        rows.add(new int[] {SHELL, 10, 4});
        when(store.prices()).thenReturn(rows);
        when(population.citizen(MERCHANT)).thenReturn(citizen(MERCHANT, Set.of(Citizen.FLAG_MERCHANT)));
        when(population.citizen(TOWNIE)).thenReturn(citizen(TOWNIE, Set.of()));
        when(population.citizen(HIRED)).thenReturn(citizen(HIRED, Set.of(Citizen.FLAG_MERCHANT, Citizen.FLAG_HIRED)));
        market = new Market(population, itemId -> SHOP.getOrDefault(itemId, 0L));
    }

    @AfterEach
    void uninstall() {
        AgentStallHolds.install(null);
    }

    @Test
    void aMerchantKeepsWhatTheMarketPaysWellForAndVendorsTheRest() {
        Item hat = new Item(HAT, (short) 1, (short) 1);
        Item gloves = new Item(GLOVES, (short) 2, (short) 1);
        Item shoes = new Item(SHOES, (short) 3, (short) 1);
        assertEquals(Set.of(hat, shoes), market.keepForStall(character(MERCHANT), List.of(hat, gloves, shoes)),
                "gloves at 1.3x their shop price go to the shop");
    }

    @Test
    void theHoldKeepsTheBestOnlySoBetterLootPushesOutWorse() {
        config.MARKET_HOLD_MERCHANT = 1;
        Item hat = new Item(HAT, (short) 1, (short) 1);
        Item shoes = new Item(SHOES, (short) 3, (short) 1);
        assertEquals(Set.of(hat), market.keepForStall(character(MERCHANT), List.of(shoes, hat)),
                "the hat's 215k over the shop beats the shoes' 145k");
    }

    @Test
    void onlyAMerchantOnItsOwnHoldsWithTheSwitchOn() {
        List<Item> loot = List.of(new Item(HAT, (short) 1, (short) 1));
        assertEquals(Set.of(), market.keepForStall(character(TOWNIE), loot), "MARKET_HOLD_OTHERS is 0");
        assertEquals(Set.of(), market.keepForStall(character(HIRED), loot), "a player's companion now");
        config.MARKET_HOLD_FOR_STALL = false;
        assertEquals(Set.of(), market.keepForStall(character(MERCHANT), loot));
        config.MARKET_HOLD_FOR_STALL = true;
        config.MARKET_HOLD_OTHERS = 2;
        assertEquals(1, market.keepForStall(character(TOWNIE), loot).size());
    }

    @Test
    void anItemWithNoShopPriceIsNeverHeld() {
        assertEquals(Set.of(), Market.worthListing(List.of(new Item(HAT, (short) 1, (short) 1)), 8, 1.5d, id -> 0L, id -> 500L));
    }

    @Test
    void theInstalledRuleReachesTheAgentsAndFailsSafe() {
        Item hat = new Item(HAT, (short) 1, (short) 1);
        AgentStallHolds.install(market::keepForStall);
        assertEquals(Set.of(hat), AgentStallHolds.keep(character(MERCHANT), List.of(hat)));
        AgentStallHolds.install((agent, candidates) -> {
            throw new IllegalStateException("index not ready");
        });
        assertEquals(Set.of(), AgentStallHolds.keep(character(MERCHANT), List.of(hat)), "a failing rule sells as before");
        AgentStallHolds.install(null);
        assertEquals(Set.of(), AgentStallHolds.keep(character(MERCHANT), List.of(hat)));
    }

    @Test
    void theStallListsTheMostValuableStacksFirst() {
        Character merchant = character(MERCHANT);
        Item shells = new Item(SHELL, (short) 1, (short) 100);
        Item hat = new Item(HAT, (short) 1, (short) 1);
        Item shoes = new Item(SHOES, (short) 2, (short) 1);
        inventory(merchant, InventoryType.ETC, List.of(shells));
        inventory(merchant, InventoryType.USE, List.of());
        inventory(merchant, InventoryType.EQUIP, List.of(shoes, hat));
        List<Integer> listed = Market.stallStacks(merchant, market::price).stream().map(Item::getItemId).toList();
        assertEquals(List.of(HAT, SHOES, SHELL), listed, "300k, 200k, then 100 shells at 10");
    }

    @Test
    void theEquipsOpenGearErrandsWantLeadTheStall() {
        Character merchant = character(MERCHANT);
        List<Item> equips = new ArrayList<>();
        for (short slot = 1; slot <= 8; slot++) {
            equips.add(new Item(HAT, slot, (short) 1));
        }
        equips.add(new Item(SHOES, (short) 9, (short) 1));
        equips.add(new Item(GLOVES, (short) 10, (short) 1));
        inventory(merchant, InventoryType.ETC, List.of(new Item(SHELL, (short) 1, (short) 100)));
        inventory(merchant, InventoryType.USE, List.of());
        inventory(merchant, InventoryType.EQUIP, equips);
        Map<Integer, Integer> wanted = Map.of(GLOVES, 1, SHOES, 4);

        List<Integer> listed = Market.stallStacks(merchant, market::price, item -> false,
                itemId -> wanted.getOrDefault(itemId, 0)).stream().map(Item::getItemId).toList();

        assertEquals(8, listed.size());
        assertEquals(List.of(SHOES, GLOVES), listed.subList(0, 2), "four errands want the shoes, one the gloves");
        assertTrue(listed.subList(2, 8).stream().allMatch(id -> id == HAT), "then the rest by value: " + listed);
    }

    @Test
    void aLineNobodyBoughtMovesTheIndexDownNotUp() {
        market.unsold(HAT, 330_000); // asked 1.1x the index
        assertEquals(297_600L, market.price(HAT), "300k + (270k - 300k) x 0.08");
        verify(store).savePrice(HAT, 297_600L, 4);
        market.unsold(SHOES, 100_000); // asked under the index
        assertTrue(market.price(SHOES) < 200_000L);
    }

    private static Character character(int id) {
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(id);
        return character;
    }

    private static void inventory(Character character, InventoryType type, List<Item> stacks) {
        Inventory inventory = mock(Inventory.class);
        when(inventory.list()).thenReturn(stacks);
        when(character.getInventory(type)).thenReturn(inventory);
    }

    private static Citizen citizen(int characterId, Set<String> flags) {
        return new Citizen(characterId, "Mira", "evening", 0, 0, "victoria", "hunter", "relaxed-v1", "default", 1.0d, 0, 1,
                new LinkedHashSet<>(flags), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
    }
}
