package server.agents.capabilities.shop;

import client.BuffStat;
import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.Shop;
import server.ShopItem;
import server.StatEffect;
import server.agents.capabilities.inventory.AgentUseItemClassificationPolicy;
import server.agents.capabilities.supplies.AgentPotionInventoryPolicy;
import server.agents.capabilities.supplies.AgentPotionService;
import server.agents.capabilities.supplies.AgentSupplyReadiness;
import server.agents.capabilities.supplies.AgentSupplyReadinessRuntime;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.integration.AgentShopGatewayRuntime;
import server.agents.integration.InventoryGateway;
import server.agents.integration.ShopGateway;
import server.agents.runtime.AgentRuntimeEntry;
import testutil.Items;

import java.awt.Point;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * The readiness floor of a points standard (user decision 2, 2026-10-08: BOSS_RECOVERY_STANDARDS, Easy Balrog's
 * 130,000 HP / 32,000 MP): the counter turns what the companion's recovery lacks into a count of the potion it picks
 * for the kind, rounded up, so a short companion buys enough to reach the standard; within the floor's limits
 * (AgentPotionPurchasePolicy.floorQuantity), where a gap of more than MAX_CARRIED_QUANTITY potions still buys in one
 * visit. A purse that stops it short leaves it short. The store is Ant Tunnel Park's 24 Hr Mobile Store (1061001:
 * Unagi 1,100 and Pure Water 1,650, 102-shopitems-data.sql); a Victoria store sells Red, White and Blue Potions.
 */
class AgentShopServiceRecoveryFloorTest {
    private static final int RED = 2_000_000;
    /** Fried Chicken: 200 HP for 220 mesos at Ant Tunnel Park's store too (102-shopitems-data.sql). */
    private static final int FRIED_CHICKEN = 2_020_001;
    private static final int WHITE = 2_000_002;
    private static final int BLUE = 2_000_003;
    private static final int PURE_WATER = 2_022_000;
    private static final int UNAGI = 2_022_003;
    private static final AgentSupplyReadiness.Activity EASY_BALROG =
            AgentSupplyReadiness.Activity.partyQuest("easy_balrog");

    private final Map<Integer, StatEffect> effects = new HashMap<>();
    private final Map<Integer, Integer> prices = new HashMap<>();
    private final List<String> transactions = new ArrayList<>();
    private final ShopGateway gateway = mock(ShopGateway.class);
    /** The counter's inventory gateway: a stack of potions holds 100. */
    private final InventoryGateway slotMax = mock(InventoryGateway.class);
    private int purse = 200_000;
    private Character ingrid;
    private Inventory use;
    private MockedStatic<AgentUseItemClassificationPolicy> classification;
    private MockedStatic<AgentPotionService> potions;
    private MockedStatic<AgentShopGatewayRuntime> shops;
    private MockedStatic<AgentShopRuntime> speech;

    @BeforeEach
    void ingridKeepsEasyBalrogsStandard() {
        effects.put(RED, effect(50, 0));
        effects.put(WHITE, effect(300, 0));
        effects.put(BLUE, effect(0, 100));
        effects.put(PURE_WATER, effect(0, 800));
        effects.put(UNAGI, effect(1_000, 0));
        effects.put(FRIED_CHICKEN, effect(200, 0));
        prices.put(RED, 50);
        prices.put(WHITE, 320);
        prices.put(BLUE, 200);
        prices.put(PURE_WATER, 1_650);
        prices.put(UNAGI, 1_100);
        prices.put(FRIED_CHICKEN, 220);
        ingrid = mock(Character.class);
        when(ingrid.getId()).thenReturn(1_009);
        when(ingrid.getJob()).thenReturn(client.Job.DRAGONKNIGHT);
        when(ingrid.getCurrentMaxHp()).thenReturn(5_000);
        when(ingrid.getCurrentMaxMp()).thenReturn(1_500);
        when(ingrid.getMeso()).thenAnswer(inv -> purse);
        when(ingrid.getBuffedValue(any(BuffStat.class))).thenReturn(null);
        carry();

        classification = mockStatic(AgentUseItemClassificationPolicy.class);
        classification.when(() -> AgentUseItemClassificationPolicy.isRecoveryPotion(anyInt()))
                .thenAnswer(inv -> effects.containsKey((int) inv.getArgument(0)));
        classification.when(() -> AgentUseItemClassificationPolicy.isRecoveryPotion(any(StatEffect.class)))
                .thenAnswer(inv -> inv.getArgument(0) != null);
        classification.when(() -> AgentUseItemClassificationPolicy.itemEffect(anyInt()))
                .thenAnswer(inv -> effects.get((int) inv.getArgument(0)));
        potions = mockStatic(AgentPotionService.class);
        potions.when(() -> AgentPotionService.countPotions(ingrid)).thenAnswer(inv ->
                AgentPotionInventoryPolicy.countRecoveryPotions(use.list(), effects::get));
        shops = mockStatic(AgentShopGatewayRuntime.class);
        shops.when(AgentShopGatewayRuntime::shop).thenReturn(gateway);
        when(gateway.buy(any(), any(), anyShort(), anyInt(), anyShort())).thenAnswer(inv -> {
            int itemId = inv.getArgument(3);
            int quantity = (short) inv.getArgument(4);
            if (purse < prices.get(itemId) * quantity) {
                return Shop.TransactionResult.NOT_ENOUGH_MESO;
            }
            purse -= prices.get(itemId) * quantity;
            transactions.add(quantity + " x " + itemId);
            int left = quantity; // onto the stacks it carries first, as the live shop does
            for (Item stack : use.listById(itemId)) {
                int onto = Math.min(left, 100 - stack.getQuantity());
                stack.setQuantity((short) (stack.getQuantity() + onto));
                left -= onto;
            }
            for (; left > 0; left -= 100) {
                use.addItem(Items.itemWithQuantity(itemId, Math.min(left, 100)));
            }
            return Shop.TransactionResult.SUCCESS;
        });
        speech = mockStatic(AgentShopRuntime.class);
        when(slotMax.getSlotMax(any(Character.class), anyInt())).thenReturn((short) 100);
    }

    @AfterEach
    void close() {
        speech.close();
        shops.close();
        potions.close();
        classification.close();
    }

    @Test
    void aPointsGapOfAHundredThousandHpBuysAHundredUnagi() {
        carry(UNAGI, 30, PURE_WATER, 40); // 30,000 HP of 130,000; its mana is met

        AgentShopPurchaseSequence<AgentRuntimeEntry> counter = floor(store(), false);

        assertEquals(List.of("100 x " + UNAGI), transactions, "100,000 HP in Unagi");
        assertEquals(200_000 - 100 * 1_100, purse);
        assertTrue(AgentSupplyReadiness.shortfall(ingrid, EASY_BALROG).none(), "it reached the standard");
        assertEquals(null, counter.firstShortfall());
    }

    @Test
    void aGapOfMoreThanTheCarryLimitInWhitePotionsStillBuysTheStandardInOneVisit() {
        carry(PURE_WATER, 40); // no HP potion at all, at a Victoria store whose pick is the White Potion

        floor(victoriaStore(), false);

        assertEquals(List.of("100 x " + WHITE, "100 x " + WHITE, "100 x " + WHITE, "100 x " + WHITE,
                "34 x " + WHITE), transactions, "434 White Potions hold 130,200 HP: past MAX_CARRIED_QUANTITY 200");
        assertEquals(434, AgentPotionService.countPotions(ingrid)[0]);
        assertTrue(AgentSupplyReadiness.shortfall(ingrid, EASY_BALROG).none());
    }

    @Test
    void aPurseShortOfTheGapBuysWhatItCanAndStaysShort() {
        carry(UNAGI, 30, PURE_WATER, 40);
        purse = 50_000;

        AgentShopPurchaseSequence<AgentRuntimeEntry> counter = floor(store(), false);

        assertEquals(List.of("45 x " + UNAGI), transactions);
        assertEquals(new AgentShopBuyReport(UNAGI, 45, 100, AgentShopShortfallReason.NO_MESO),
                counter.firstShortfall(), "it says it ran out of mesos");
        assertEquals(new AgentSupplyReadiness.Shortfall(55_000, 0, 0),
                AgentSupplyReadiness.shortfall(ingrid, EASY_BALROG), "still short: its host's broke lines apply");
    }

    @Test
    void theTopUpBuysTowardTwiceThePointsWithinTheCarryLimit() {
        carry(UNAGI, 100, UNAGI, 30, PURE_WATER, 40); // the standard, exactly
        purse = 1_000_000;

        floor(store(), false);
        assertEquals(List.of(), transactions, "nothing short of the standard");
        floor(store(), true);

        assertEquals(List.of("70 x " + UNAGI, "40 x " + PURE_WATER), transactions,
                "toward 260,000 HP up to MAX_CARRIED_QUANTITY 200, and toward 64,000 MP");
        assertEquals(200, AgentPotionService.countPotions(ingrid)[0]);
        assertEquals(80, AgentPotionService.countPotions(ingrid)[1]);
    }

    /**
     * Review fix (2026-10-09): at the last free Use slot, with no room on its Unagi stack, the counter buys the points
     * gap onto the carried stack of a weaker potion the store sells (Fried Chicken, 200 HP), so the trip's gate counts
     * the gap in that potion. 20,000 HP is 20 Unagi, but 100 Fried Chicken, and the chicken stack has 50 places: the gate
     * asks for a slot past them and the run's one, so the trip does not go (NO_SPACE). Before, it counted 20 Unagi onto
     * those 50 places, let the trip go, and the counter bought 50 and said the bag was full.
     */
    @Test
    void atTheLastFreeSlotTheGateCountsTheGapInTheWeakerCarriedStackTheCounterBuysOnto() throws Exception {
        carryIn(4, UNAGI, 100, FRIED_CHICKEN, 50, PURE_WATER, 40); // 110,000 HP of 130,000; one Use slot free
        Shop store = shop(List.of(PURE_WATER, UNAGI, FRIED_CHICKEN));

        assertEquals(2, useSlotsNeeded(store), "a slot for the 50 chicken past the stack's room, and the run's one");
        assertTrue(use.getNumFreeSlot() < 2, "the trip does not go: NO_SPACE");

        floor(store, false); // what the counter would have done
        assertEquals(List.of("50 x " + FRIED_CHICKEN), transactions);
        assertEquals(new AgentSupplyReadiness.Shortfall(10_000, 0, 0),
                AgentSupplyReadiness.shortfall(ingrid, EASY_BALROG), "it would have come back short");
    }

    @Test
    void atTheLastFreeSlotAGapTheCarriedStackHoldsGoesAndReachesTheStandard() throws Exception {
        carryIn(5, UNAGI, 100, FRIED_CHICKEN, 100, FRIED_CHICKEN, 25, PURE_WATER, 40); // 125,000 HP; one slot free
        Shop store = shop(List.of(PURE_WATER, UNAGI, FRIED_CHICKEN));

        assertEquals(0, useSlotsNeeded(store), "25 chicken fit the 75 places of its stack");

        floor(store, false);
        assertEquals(List.of("25 x " + FRIED_CHICKEN), transactions);
        assertTrue(AgentSupplyReadiness.shortfall(ingrid, EASY_BALROG).none(), "it reached the standard");
        assertEquals(1, use.getNumFreeSlot(), "the run's slot stays free");
    }

    /** The readiness gate's real slot count for Ingrid's shortfall now (AgentSupplyReadinessRuntime.useSlotsNeeded). */
    private int useSlotsNeeded(Shop shop) throws Exception {
        Method gate = AgentSupplyReadinessRuntime.class.getDeclaredMethod("useSlotsNeeded", Character.class,
                Shop.class, AgentSupplyReadiness.Activity.class, AgentSupplyReadiness.Shortfall.class, long.class);
        gate.setAccessible(true);
        try (MockedStatic<AgentInventoryGatewayRuntime> inventories = mockStatic(AgentInventoryGatewayRuntime.class)) {
            inventories.when(AgentInventoryGatewayRuntime::inventory).thenReturn(slotMax);
            return (int) gate.invoke(null, ingrid, shop, EASY_BALROG,
                    AgentSupplyReadiness.shortfall(ingrid, EASY_BALROG), (long) purse);
        }
    }

    /** One readiness floor pass of a counter with no visit under way (AgentShopProvisioning's). */
    private AgentShopPurchaseSequence<AgentRuntimeEntry> floor(Shop shop, boolean topUp) {
        return AgentShopService.buyReadinessFloor(new AgentShopPurchaseSequence<>(null, ingrid, slotMax, new Point(),
                List.of(), new ArrayList<>(), null), shop, EASY_BALROG, 0, new int[]{0, 0}, topUp);
    }

    /** Ingrid's Use tab of 24 slots holds these {item, count} stacks. */
    private void carry(int... stacks) {
        carryIn(24, stacks);
    }

    /** Ingrid's Use tab of {@code slots} slots holds these {item, count} stacks. */
    private void carryIn(int slots, int... stacks) {
        use = new Inventory(ingrid, InventoryType.USE, (byte) slots);
        for (int i = 0; i + 1 < stacks.length; i += 2) {
            use.addItem(Items.itemWithQuantity(stacks[i], stacks[i + 1]));
        }
        when(ingrid.getInventory(InventoryType.USE)).thenReturn(use);
    }

    /** Ant Tunnel Park's 24 Hr Mobile Store: its potions in slot order. */
    private Shop store() {
        return shop(List.of(PURE_WATER, UNAGI));
    }

    private Shop victoriaStore() {
        return shop(List.of(RED, WHITE, BLUE));
    }

    private Shop shop(List<Integer> itemIds) {
        List<ShopItem> items = new ArrayList<>();
        for (int itemId : itemIds) {
            ShopItem item = mock(ShopItem.class);
            when(item.getItemId()).thenReturn(itemId);
            when(item.getPrice()).thenReturn(prices.get(itemId));
            items.add(item);
        }
        Shop shop = mock(Shop.class);
        when(shop.getItems()).thenReturn(items);
        return shop;
    }

    private static StatEffect effect(int hp, int mp) {
        StatEffect effect = mock(StatEffect.class);
        when(effect.getHp()).thenReturn((short) hp);
        when(effect.getMp()).thenReturn((short) mp);
        return effect;
    }
}
