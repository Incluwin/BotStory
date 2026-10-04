package server.agents.capabilities.shop;

import client.BuffStat;
import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.WeaponType;
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
import server.agents.integration.AgentShopGatewayRuntime;
import server.agents.integration.InventoryGateway;
import server.agents.integration.ShopGateway;
import server.agents.runtime.AgentRuntimeEntry;
import testutil.Items;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * The shop's readiness floor (AgentShopService.buyPotionStock under AgentSupplyReadiness): a companion whose reserve
 * bars are met by a few strong potions still buys by count, first up to its standing duty's standard (20 HP / 25 MP,
 * what the KPQ desk counts) and, with what is left after the reserve bars, toward its target (40 HP / 50 MP); without a
 * duty the same visit buys nothing. A purse that covers the standard always reaches it (review 2026-10-02: holding
 * back the mana potions' whole target gap left HP short and sent the companion home broke). With its Use tab nearly
 * full it buys onto a stack it carries. And plannedAmmoSpend, the readiness trip's ammo estimate, is exactly what the
 * visit's ammo steps spend.
 */
class AgentShopServicePotionFloorTest {
    private static final int RED = 2_000_000;
    private static final int WHITE = 2_000_002;
    private static final int BLUE = 2_000_003;
    private static final int POWER_ELIXIR = 2_000_005;
    private static final int ARROW = 2_060_000;
    private static final int SUBI = 2_070_000;

    private final Map<Integer, StatEffect> effects = new HashMap<>();
    private final Map<Integer, Integer> prices = new HashMap<>();
    private final List<String> transactions = new ArrayList<>();
    private final Set<Short> recharged = new HashSet<>();
    private final ShopGateway gateway = mock(ShopGateway.class);
    /** The visit's inventory gateway: a stack of potions holds 100. */
    private final InventoryGateway slotMax = mock(InventoryGateway.class);
    private int purse = 50_000;
    private Character bot;
    private Inventory use;
    private MockedStatic<AgentUseItemClassificationPolicy> classification;
    private MockedStatic<AgentPotionService> potions;
    private MockedStatic<AgentShopGatewayRuntime> shops;
    private MockedStatic<AgentShopRuntime> speech;

    @BeforeEach
    void aHunterWithFifteenPowerElixirs() {
        effects.put(RED, effect(50, 0, 0.0d, 0.0d));
        effects.put(WHITE, effect(300, 0, 0.0d, 0.0d));
        effects.put(BLUE, effect(0, 100, 0.0d, 0.0d));
        effects.put(POWER_ELIXIR, effect(0, 0, 1.0d, 1.0d));
        prices.put(RED, 50);
        prices.put(WHITE, 320);
        prices.put(BLUE, 200);
        prices.put(ARROW, 1);
        prices.put(SUBI, 500);
        bot = mock(Character.class);
        when(bot.getId()).thenReturn(77);
        when(bot.getJob()).thenReturn(client.Job.HUNTER);
        when(bot.getCurrentMaxHp()).thenReturn(1_060);
        when(bot.getCurrentMaxMp()).thenReturn(500);
        when(bot.getMeso()).thenAnswer(inv -> purse);
        when(bot.getBuffedValue(any(BuffStat.class))).thenReturn(null);
        use = new Inventory(bot, InventoryType.USE, (byte) 24);
        use.addItem(Items.itemWithQuantity(POWER_ELIXIR, 15)); // 15 bars of HP and MP: every reserve is met
        when(bot.getInventory(InventoryType.USE)).thenReturn(use);

        classification = mockStatic(AgentUseItemClassificationPolicy.class);
        classification.when(() -> AgentUseItemClassificationPolicy.isRecoveryPotion(anyInt()))
                .thenAnswer(inv -> effects.containsKey((int) inv.getArgument(0)));
        classification.when(() -> AgentUseItemClassificationPolicy.isRecoveryPotion(any(StatEffect.class)))
                .thenAnswer(inv -> inv.getArgument(0) != null);
        classification.when(() -> AgentUseItemClassificationPolicy.itemEffect(anyInt()))
                .thenAnswer(inv -> effects.get((int) inv.getArgument(0)));
        potions = mockStatic(AgentPotionService.class);
        potions.when(() -> AgentPotionService.countPotions(bot)).thenAnswer(inv ->
                AgentPotionInventoryPolicy.countRecoveryPotions(use.list(), effects::get));
        shops = mockStatic(AgentShopGatewayRuntime.class);
        shops.when(AgentShopGatewayRuntime::shop).thenReturn(gateway);
        when(gateway.buy(any(), any(), anyShort(), anyInt(), anyShort())).thenAnswer(inv -> {
            int itemId = inv.getArgument(3);
            short quantity = inv.getArgument(4);
            purse -= prices.get(itemId) * quantity;
            use.addItem(Items.itemWithQuantity(itemId, quantity));
            transactions.add(quantity + " x " + itemId);
            return Shop.TransactionResult.SUCCESS;
        });
        when(gateway.recharge(any(), any(), anyShort(), anyInt())).thenAnswer(inv -> {
            recharged.add(inv.getArgument(2));
            return Shop.TransactionResult.SUCCESS;
        });
        speech = mockStatic(AgentShopRuntime.class);
        when(slotMax.getSlotMax(any(Character.class), anyInt())).thenReturn((short) 100);
    }

    @AfterEach
    void close() {
        AgentSupplyReadiness.install(null);
        speech.close();
        shops.close();
        potions.close();
        classification.close();
    }

    @Test
    void withTheBarsMetAStandingDutyStillBuysToFortyAndFiftyByCount() {
        AgentSupplyReadiness.install(id -> AgentSupplyReadiness.Activity.PARTY_QUEST);

        visit(List.of(RED, WHITE, BLUE));

        assertArrayEquals(new int[]{40, 50}, AgentPotionService.countPotions(bot), transactions.toString());
        assertEquals(List.of("5 x " + WHITE, "10 x " + BLUE, "20 x " + WHITE, "25 x " + BLUE), transactions,
                "what the reserve picks, by count: to the standard, then to the target");
    }

    @Test
    void aReadinessTripWhoseDutyCannotBeReadAtTheCounterStillBuysToItsStandard() {
        // live 2026-10-04: travel had cleared the follow target the duty is read from, so the floor never ran and the
        // trip came back "without increasing the required supply", three times in a row
        // fifteen Power Elixirs meet every reserve bar: only the readiness floor buys anything
        try (MockedStatic<AgentSupplyReadiness> readiness =
                     mockStatic(AgentSupplyReadiness.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            readiness.when(() -> AgentSupplyReadiness.standing(anyInt())).thenReturn(null);
            readiness.when(() -> AgentSupplyReadiness.shoppingActivity(any(), any()))
                    .thenReturn(AgentSupplyReadiness.Activity.PARTY_QUEST);

            visit(List.of(RED, WHITE, BLUE));
        }

        int[] after = AgentPotionService.countPotions(bot);
        assertTrue(after[0] >= 20 && after[1] >= 25, after[0] + "/" + after[1] + " " + transactions);
    }

    @Test
    void withoutADutyTheSameVisitBuysNothing() {
        visit(List.of(RED, WHITE, BLUE));

        assertEquals(List.of(), transactions);
        assertArrayEquals(new int[]{15, 15}, AgentPotionService.countPotions(bot));
    }

    @Test
    void aPurseShortOfTheTargetReachesTheStandardFirstAndTopsUpWithWhatIsLeft() {
        AgentSupplyReadiness.install(id -> AgentSupplyReadiness.Activity.PARTY_QUEST);
        purse = 25 * 50 + 35 * 200; // the standard costs 5 White + 10 Blue = 3600; the rest goes to the top-up

        visit(List.of(RED, WHITE, BLUE));

        assertEquals(List.of("5 x " + WHITE, "10 x " + BLUE, "23 x " + BLUE), transactions,
                "the HP top-up waits for the mana it still wants; mana takes what is left");
        assertArrayEquals(new int[]{20, 48}, AgentPotionService.countPotions(bot));
        assertEquals(50, purse);
    }

    @Test
    void aPurseThatCoversTheStandardReachesIt() {
        AgentSupplyReadiness.install(id -> AgentSupplyReadiness.Activity.PARTY_QUEST);
        carry(10, 10);
        purse = 5_000; // the standard costs 10 x 50 + 15 x 200 = 3500

        visit(List.of(RED, WHITE, BLUE));

        int[] after = AgentPotionService.countPotions(bot);
        assertTrue(after[0] >= 20 && after[1] >= 25, after[0] + "/" + after[1] + " " + transactions);
        assertEquals("10 x " + RED, transactions.getFirst(), "White x 10 would eat the mana potions' money");
    }

    @Test
    void onlyHpShortWithAModestPurseReachesTheHpStandard() {
        AgentSupplyReadiness.install(id -> AgentSupplyReadiness.Activity.PARTY_QUEST);
        carry(10, 30); // mana already at the standard, short of the target
        purse = 3_000;

        visit(List.of(RED, WHITE, BLUE));

        int[] after = AgentPotionService.countPotions(bot);
        assertTrue(after[0] >= 20, after[0] + "/" + after[1] + " " + transactions);
        assertEquals("10 x " + RED, transactions.getFirst());
    }

    @Test
    void aDutyNeverLeavesACriticalHpTripWithFewerHpPotionsThanWithoutIt() {
        carry(5, 30);
        purse = 3_000;
        visit(List.of(RED, WHITE, BLUE));
        int withoutDuty = AgentPotionService.countPotions(bot)[0];

        AgentSupplyReadiness.install(id -> AgentSupplyReadiness.Activity.PARTY_QUEST);
        transactions.clear();
        carry(5, 30);
        purse = 3_000;
        visit(List.of(RED, WHITE, BLUE));

        int withDuty = AgentPotionService.countPotions(bot)[0];
        assertTrue(withDuty >= 20 && withDuty >= withoutDuty, withDuty + " vs " + withoutDuty + " " + transactions);
    }

    @Test
    void fennaGivenExactlyWhatHerVerdictSaidSheLackedComesBackReady() {
        AgentSupplyReadiness.install(id -> AgentSupplyReadiness.Activity.PARTY_QUEST);
        carry(1, 30);
        purse = 155 + (19 * 50 - 155); // her 155 and her owner's coin

        visit(List.of(RED, WHITE, BLUE));

        assertEquals(List.of("19 x " + RED), transactions);
        assertEquals(20, AgentPotionService.countPotions(bot)[0]);
    }

    @Test
    void withItsUseTabNearlyFullItBuysOntoTheStackItCarries() {
        AgentSupplyReadiness.install(id -> AgentSupplyReadiness.Activity.PARTY_QUEST);
        use = new Inventory(bot, InventoryType.USE, (byte) 5);
        use.addItem(Items.itemWithQuantity(RED, 1));
        use.addItem(Items.itemWithQuantity(BLUE, 30));
        use.addItem(Items.itemWithQuantity(POWER_ELIXIR, 15)); // its reserve bars are met
        use.addItem(Items.itemWithQuantity(ARROW, 1_000)); // one slot left, which a run keeps for its items
        when(bot.getInventory(InventoryType.USE)).thenReturn(use);

        visit(List.of(RED, WHITE, BLUE), slotMax);

        // (this test's shop adds every purchase as a stack of its own; the live one fills the carried stack first)
        assertEquals(List.of("4 x " + RED, "20 x " + RED, "5 x " + BLUE), transactions,
                "no White: a new kind would take the last slot");
        assertTrue(AgentPotionService.countPotions(bot)[0] >= 20, transactions.toString());
    }

    @Test
    void itCarriesAStackWithRoomOnlyOfAPotionThisShopSells() {
        carry(10, 0);
        try (MockedStatic<server.agents.integration.AgentInventoryGatewayRuntime> inventories =
                     mockStatic(server.agents.integration.AgentInventoryGatewayRuntime.class)) {
            inventories.when(server.agents.integration.AgentInventoryGatewayRuntime::inventory).thenReturn(slotMax);
            assertTrue(AgentShopService.carriesStackWithRoom(bot, shop(List.of(RED, BLUE)), true));
            assertFalse(AgentShopService.carriesStackWithRoom(bot, shop(List.of(WHITE, BLUE)), true), "not sold here");
            assertFalse(AgentShopService.carriesStackWithRoom(bot, shop(List.of(RED, BLUE)), false), "no mana stack");
            carry(100, 0); // a full stack
            assertFalse(AgentShopService.carriesStackWithRoom(bot, shop(List.of(RED, BLUE)), true));
        }
    }

    @Test
    void theAmmoEstimateIsWhatTheBowAndClawStepsSpend() {
        use.addItem(Items.itemWithQuantity(ARROW, 1_000));
        Shop shop = shop(List.of(ARROW, SUBI, RED));
        InventoryGateway inventory = mock(InventoryGateway.class);

        long planned = AgentShopService.plannedAmmoSpend(bot, shop, WeaponType.BOW, inventory, itemId -> 0.0d);
        int before = purse;
        AgentShopService.buyAmmo(bot, shop, WeaponType.BOW, 0);

        assertEquals(4_000L, planned, "arrows up to the 5000 target at 1 meso");
        assertEquals(planned, before - purse);

        Character claw = mock(Character.class);
        when(claw.getBuffedValue(any(BuffStat.class))).thenReturn(null);
        when(claw.getMeso()).thenAnswer(inv -> purse);
        Inventory stars = new Inventory(claw, InventoryType.USE, (byte) 24);
        when(claw.getInventory(InventoryType.USE)).thenReturn(stars);
        planned = AgentShopService.plannedAmmoSpend(claw, shop, WeaponType.CLAW, inventory, itemId -> 0.0d);
        before = purse;
        AgentShopService.buySeedRechargeAmmo(claw, shop, WeaponType.CLAW, 0);
        assertEquals(500L, planned, "a seed set of stars when it has none");
        assertEquals(planned, before - purse);
    }

    @Test
    void theRechargeEstimatePricesExactlyTheStacksTheRechargeStepRefills() {
        Character claw = mock(Character.class);
        when(claw.getBuffedValue(any(BuffStat.class))).thenReturn(null);
        Inventory stars = new Inventory(claw, InventoryType.USE, (byte) 24);
        stars.addItem(Items.itemWithQuantity(SUBI, 300));
        stars.addItem(Items.itemWithQuantity(SUBI, 100));
        when(claw.getInventory(InventoryType.USE)).thenReturn(stars);
        InventoryGateway inventory = mock(InventoryGateway.class);
        when(inventory.getSlotMax(any(Character.class), anyInt())).thenReturn((short) 800);
        when(inventory.getProjectileWeaponAttack(SUBI)).thenReturn(15);
        Shop shop = shop(List.of(SUBI));

        long planned = AgentShopService.plannedAmmoSpend(claw, shop, WeaponType.CLAW, inventory, itemId -> 0.5d);
        AgentShopService.doRecharge(claw, shop, WeaponType.CLAW, inventory, 0);

        assertEquals((long) Math.ceil(0.5d * 500) + (long) Math.ceil(0.5d * 700), planned);
        Set<Short> slots = new HashSet<>();
        for (Item item : stars.list()) {
            slots.add(item.getPosition());
        }
        assertEquals(slots, recharged, "both partial stacks, as estimated");
        assertTrue(planned > 0L);
    }

    private void visit(List<Integer> itemIds) {
        visit(itemIds, mock(InventoryGateway.class));
    }

    private void visit(List<Integer> itemIds, InventoryGateway inventory) {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(bot, null, null);
        AgentShopService.buyPotionStock(new AgentShopPurchaseSequence<>(entry, bot, inventory,
                new Point(), List.of(), new ArrayList<>(), null), shop(itemIds), 0);
    }

    /** It carries {@code red} Red and {@code blue} Blue potions, nothing else. */
    private void carry(int red, int blue) {
        use = new Inventory(bot, InventoryType.USE, (byte) 24);
        if (red > 0) {
            use.addItem(Items.itemWithQuantity(RED, red));
        }
        if (blue > 0) {
            use.addItem(Items.itemWithQuantity(BLUE, blue));
        }
        when(bot.getInventory(InventoryType.USE)).thenReturn(use);
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

    private static StatEffect effect(int hp, int mp, double hpRate, double mpRate) {
        StatEffect effect = mock(StatEffect.class);
        when(effect.getHp()).thenReturn((short) hp);
        when(effect.getMp()).thenReturn((short) mp);
        when(effect.getHpRate()).thenReturn(hpRate);
        when(effect.getMpRate()).thenReturn(mpRate);
        return effect;
    }
}
