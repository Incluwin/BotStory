package server.agents.capabilities.shop;

import server.agents.capabilities.supplies.AgentPotionService;

import server.agents.capabilities.combat.AgentAttackExecutionProvider;

import client.BuffStat;
import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.WeaponType;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.inventory.AgentInventoryItemPolicy;
import server.agents.capabilities.inventory.AgentInventorySellTrashService;
import server.agents.capabilities.inventory.demand.AgentQuestItemDispositionRuntime;
import server.agents.capabilities.shop.AgentShopRuntime;
import server.agents.integration.AgentShopGatewayRuntime;
import server.agents.integration.InventoryGateway;
import server.agents.integration.ShopGateway;
import server.Shop;
import server.ShopFactory;
import server.agents.runtime.AgentRuntimeEntry;
import server.life.NPC;
import server.maps.MapleMap;
import testutil.Items;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentShopServiceTest {
    @Test
    void affordableQuantityNeverSpendsTheReservedTaxiMesos() {
        assertEquals(40, AgentShopService.affordableQuantity(5_000, 1_000, 100, 50, 100));
        assertEquals(10, AgentShopService.affordableQuantity(5_000, 1_000, 100, 50, 10));
        assertEquals(0, AgentShopService.affordableQuantity(1_000, 1_000, 100, 50, 100));
        assertEquals(0, AgentShopService.affordableQuantity(5_000, 1_000, 0, 50, 100));
        assertEquals(25, AgentShopService.affordableQuantity(5_000, 1_000, 10, 100, 100, 250));
    }

    @Test
    void buffPotionsSpendOnlyTheirShareOfTheMesosAboveTheReserve() {
        int percent = config.AgentTuning.intValue(
                "server.agents.capabilities.shop.AgentShopService.BUFF_POTION_SPEND_PERCENT");

        assertEquals(4_000 * percent / 100, AgentShopService.buffPotionBudget(5_000, 1_000));
        assertEquals(0, AgentShopService.buffPotionBudget(900, 1_000));
    }

    @Test
    void sellTrashNoItemsReplyUsesAgentReplyAdapter() {
        Character bot = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        when(bot.getMap()).thenReturn(map);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(bot, null, null);

        try (MockedStatic<AgentInventorySellTrashService> inventories = mockStatic(AgentInventorySellTrashService.class);
             MockedStatic<AgentShopRuntime> replies = mockStatic(AgentShopRuntime.class)) {
            inventories.when(() -> AgentInventorySellTrashService.collectSellTrashEquips(
                            any(AgentRuntimeEntry.class), any(Character.class), any(InventoryGateway.class)))
                    .thenReturn(List.of());

            AgentShopService.requestSellTrashVisit(entry, bot, inventoryGateway());

            replies.verify(() -> AgentShopRuntime.replyNow(entry, "no trash equips worth selling"));
        }
    }

    /**
     * Live 2026-10-10: "sell my junk" while a visit to Luna was already open (a restock on arrival) joined that visit
     * and said nothing. It still joins it (the visit sells first), and now says so.
     */
    @Test
    void sellTrashWhileAVisitIsPendingJoinsItAndSaysSo() {
        Character bot = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        when(bot.getMap()).thenReturn(map);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(bot, null, null);
        AgentShopStateRuntime.startShopVisit(entry, new Point(20, 0), new Point(20, 0), 0, 1_000L);

        try (MockedStatic<AgentInventorySellTrashService> inventories = mockStatic(AgentInventorySellTrashService.class);
             MockedStatic<AgentShopRuntime> replies = mockStatic(AgentShopRuntime.class)) {
            inventories.when(() -> AgentInventorySellTrashService.collectSellTrashEquips(
                            any(AgentRuntimeEntry.class), any(Character.class), any(InventoryGateway.class)))
                    .thenReturn(List.of(mock(Item.class)));

            AgentShopService.requestSellTrashVisit(entry, bot, inventoryGateway());
            replies.verify(() -> AgentShopRuntime.replyNow(entry, "already heading to the shop, i'll sell the junk too."));

            // asked again on the same trip: it is already selling there
            AgentShopService.requestSellTrashVisit(entry, bot, inventoryGateway());
            replies.verify(() -> AgentShopRuntime.replyNow(entry, "already on it, heading to the shop now."));
        }

        assertTrue(AgentShopStateRuntime.shopVisitPending(entry));
        assertTrue(AgentShopStateRuntime.shopSellTrashPending(entry));
        assertEquals(new Point(20, 0), AgentShopStateRuntime.shopNpcPosition(entry));
    }

    /**
     * Deploy review 2026-10-10: "sell junk" once the visit stood at the counter past its sell step (read once, as the
     * transaction opens) promised "i'll sell the junk too" and sold nothing: the visit ended and cleared the flag. It
     * now says it sells after this, and sells at that counter before the visit ends.
     */
    @Test
    void sellTrashAfterTheVisitsSellStepSellsAtTheCounterBeforeTheVisitEnds() {
        Character bot = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(bot, null, null);
        NPC npc = shopNpc(new Point(20, 0));
        Shop shop = mock(Shop.class);
        ShopGateway gateway = mock(ShopGateway.class);
        Item junk = mock(Item.class);
        AtomicBoolean carried = new AtomicBoolean(true);
        when(bot.getMap()).thenReturn(map);
        when(bot.getPosition()).thenReturn(new Point(0, 0));
        when(bot.getInventory(InventoryType.USE)).thenReturn(new Inventory(bot, InventoryType.USE, (byte) 24));
        when(bot.getBuffedValue(any(BuffStat.class))).thenReturn(null);
        when(map.getMapObjectsInRange(any(Point.class), anyDouble(), any())).thenReturn(List.of(npc));
        when(shop.getItems()).thenReturn(List.of());
        when(gateway.findForNpc(npc.getId())).thenReturn(shop);
        when(gateway.sell(any(), any(), any(), anyShort(), anyShort())).thenAnswer(sale -> {
            carried.set(false);
            return null;
        });
        InventoryGateway inventory = inventoryGateway();
        List<Runnable> scheduled = new ArrayList<>();

        try (MockedStatic<AgentAttackExecutionProvider> attacks =
                     mockStatic(AgentAttackExecutionProvider.class, org.mockito.Mockito.CALLS_REAL_METHODS);
             MockedStatic<AgentPotionService> potions = mockStatic(AgentPotionService.class);
             MockedStatic<AgentShopGatewayRuntime> shops = mockStatic(AgentShopGatewayRuntime.class);
             MockedStatic<AgentShopRuntime> runtime = mockStatic(AgentShopRuntime.class);
             MockedStatic<AgentInventorySellTrashService> trash = mockStatic(AgentInventorySellTrashService.class);
             MockedStatic<AgentQuestItemDispositionRuntime> etc = mockStatic(AgentQuestItemDispositionRuntime.class);
             MockedStatic<AgentInventoryItemPolicy> items =
                     mockStatic(AgentInventoryItemPolicy.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            shops.when(AgentShopGatewayRuntime::shop).thenReturn(gateway);
            attacks.when(() -> AgentAttackExecutionProvider.getEquippedWeaponType(bot)).thenReturn(WeaponType.SWORD1H);
            potions.when(() -> AgentPotionService.countPotions(bot)).thenReturn(new int[]{9999, 9999});
            runtime.when(() -> AgentShopRuntime.afterDelay(any(AgentRuntimeEntry.class), anyLong(), any(Runnable.class)))
                    .thenAnswer(invocation -> scheduled.add(invocation.getArgument(2)));
            trash.when(() -> AgentInventorySellTrashService.collectSellTrashEquips(
                    any(AgentRuntimeEntry.class), any(Character.class), any(InventoryGateway.class)))
                    .thenAnswer(collect -> carried.get() ? List.of(junk) : List.of());
            etc.when(() -> AgentQuestItemDispositionRuntime.collectShopSaleCandidates(any(), any(), any(), anyLong()))
                    .thenReturn(List.of());
            items.when(() -> AgentInventoryItemPolicy.hasItem(bot, junk)).thenAnswer(held -> carried.get());

            // a restock visit reaches the counter and opens its transaction, nothing to sell asked yet
            assertTrue(AgentShopService.requestVisitAtNpc(entry, bot, npc.getId()));
            AgentShopStateRuntime.setShopApproachDelayMs(entry, 0);
            assertTrue(AgentShopService.tickShopVisit(entry, bot, inventory));
            scheduled.remove(0).run();

            AgentShopService.requestSellTrashVisit(entry, bot, inventory);
            runtime.verify(() -> AgentShopRuntime.replyNow(entry, "i'll sell the junk after this, one sec."));

            for (int step = 0; step < 50 && !scheduled.isEmpty(); step++) {
                scheduled.remove(0).run();
            }
        }

        verify(gateway).sell(eq(bot), eq(shop), eq(InventoryType.EQUIP), anyShort(), eq((short) 1));
        assertFalse(AgentShopStateRuntime.shopVisitPending(entry), "the visit ended after the sale");
        assertFalse(AgentShopStateRuntime.shopSellTrashPending(entry));
    }

    @Test
    void shouldNotTriggerClawShopVisitWhenBestStarIsAboveThreshold() {
        Character bot = clawBotWithStars(800, 1000, 1000, 1000, 1000, 1000); // 5800 of the best star
        AgentRuntimeEntry entry = new AgentRuntimeEntry(bot, null, null);
        MapleMap map = bot.getMap();
        NPC npc = shopNpc(new Point(20, 0));
        Shop shop = mock(Shop.class);

        when(map.getMapObjectsInRange(any(Point.class), anyDouble(), any())).thenReturn(List.of(npc));
        when(shop.getItems()).thenReturn(List.of());

        InventoryGateway inventory = starStatsInventory();
        try (MockedStatic<AgentAttackExecutionProvider> attacks =
                     mockStatic(AgentAttackExecutionProvider.class, org.mockito.Mockito.CALLS_REAL_METHODS);
             MockedStatic<AgentPotionService> potions = mockStatic(AgentPotionService.class);
             MockedStatic<ShopFactory> shops = mockStatic(ShopFactory.class)) {
            ShopFactory factory = mock(ShopFactory.class);
            shops.when(ShopFactory::getInstance).thenReturn(factory);
            when(factory.getShopForNPC(npc.getId())).thenReturn(shop);
            attacks.when(() -> AgentAttackExecutionProvider.getEquippedWeaponType(bot)).thenReturn(WeaponType.CLAW);
            potions.when(() -> AgentPotionService.countPotions(bot)).thenReturn(new int[]{9999, 9999});

            AgentShopService.onMapChange(entry, bot, inventory);
        }

        assertFalse(AgentShopStateRuntime.shopVisitPending(entry));
    }

    @Test
    void shouldTriggerClawShopVisitWhenBestStarIsBelowThreshold() {
        Character bot = clawBotWithStars(800, 1000, 1000); // 2800 of the best star
        AgentRuntimeEntry entry = new AgentRuntimeEntry(bot, null, null);
        MapleMap map = bot.getMap();
        NPC npc = shopNpc(new Point(20, 0));
        Shop shop = mock(Shop.class);

        when(map.getMapObjectsInRange(any(Point.class), anyDouble(), any())).thenReturn(List.of(npc));
        when(shop.getItems()).thenReturn(List.of());

        InventoryGateway inventory = starStatsInventory();
        try (MockedStatic<AgentAttackExecutionProvider> attacks =
                     mockStatic(AgentAttackExecutionProvider.class, org.mockito.Mockito.CALLS_REAL_METHODS);
             MockedStatic<AgentPotionService> potions = mockStatic(AgentPotionService.class);
             MockedStatic<ShopFactory> shops = mockStatic(ShopFactory.class)) {
            ShopFactory factory = mock(ShopFactory.class);
            shops.when(ShopFactory::getInstance).thenReturn(factory);
            when(factory.getShopForNPC(npc.getId())).thenReturn(shop);
            attacks.when(() -> AgentAttackExecutionProvider.getEquippedWeaponType(bot)).thenReturn(WeaponType.CLAW);
            potions.when(() -> AgentPotionService.countPotions(bot)).thenReturn(new int[]{9999, 9999});

            AgentShopService.onMapChange(entry, bot, inventory);
        }

        assertTrue(AgentShopStateRuntime.shopVisitPending(entry));
    }

    @Test
    void aCompanionHeldBackForMesosStartsNoVisitOfItsOwnWhenItArrivesAtAShop() {
        // second review of the ask-owner change: a broke companion that followed its owner into a store started its
        // own visit, and the owner's gift during it could not be told from what it bought itself
        for (boolean held : new boolean[]{true, false}) {
            Character bot = clawBotWithStars(800, 1000, 1000); // 2800 of the best star: a visit would open
            AgentRuntimeEntry entry = new AgentRuntimeEntry(bot, null, null);
            MapleMap map = bot.getMap();
            NPC npc = shopNpc(new Point(20, 0));
            Shop shop = mock(Shop.class);
            when(map.getMapObjectsInRange(any(Point.class), anyDouble(), any())).thenReturn(List.of(npc));
            when(shop.getItems()).thenReturn(List.of());
            InventoryGateway inventory = starStatsInventory();
            try (MockedStatic<AgentAttackExecutionProvider> attacks =
                         mockStatic(AgentAttackExecutionProvider.class, org.mockito.Mockito.CALLS_REAL_METHODS);
                 MockedStatic<AgentPotionService> potions = mockStatic(AgentPotionService.class);
                 MockedStatic<ShopFactory> shops = mockStatic(ShopFactory.class);
                 MockedStatic<server.agents.capabilities.supplies.AgentSupplyReadiness> readiness = mockStatic(
                         server.agents.capabilities.supplies.AgentSupplyReadiness.class,
                         org.mockito.Mockito.CALLS_REAL_METHODS)) {
                ShopFactory factory = mock(ShopFactory.class);
                shops.when(ShopFactory::getInstance).thenReturn(factory);
                when(factory.getShopForNPC(npc.getId())).thenReturn(shop);
                attacks.when(() -> AgentAttackExecutionProvider.getEquippedWeaponType(bot)).thenReturn(WeaponType.CLAW);
                potions.when(() -> AgentPotionService.countPotions(bot)).thenReturn(new int[]{9999, 9999});
                readiness.when(() -> server.agents.capabilities.supplies.AgentSupplyReadiness.heldForMesos(
                        org.mockito.ArgumentMatchers.eq(entry), anyLong())).thenReturn(held);

                AgentShopService.onArrival(entry, bot, inventory);
            }

            assertEquals(!held, AgentShopStateRuntime.shopVisitPending(entry),
                    held ? "what it cannot pay for its readiness says" : "as on any map change");
        }
    }

    @Test
    void aShopStepLeftFromAnEarlierVisitDoesNotAbortTheNextVisit() {
        // Live 2026-09-21..10-06: a supply trip started (begin() re-runs onMapChange) while a transaction's
        // purchase step was scheduled. The step then ran against the new visit and aborted it with
        // "couldn't get to the shopkeeper, never mind" (sequenceActive=false, visitAgeMs 10-2000).
        Character bot = clawBotWithStars(800, 1000, 1000); // 2800 of the best star: a visit opens
        AgentRuntimeEntry entry = new AgentRuntimeEntry(bot, null, null);
        MapleMap map = bot.getMap();
        NPC npc = shopNpc(new Point(20, 0));
        Shop shop = mock(Shop.class);

        when(map.getMapObjectsInRange(any(Point.class), anyDouble(), any())).thenReturn(List.of(npc));
        when(shop.getItems()).thenReturn(List.of());

        InventoryGateway inventory = starStatsInventory();
        List<Runnable> scheduled = new ArrayList<>();
        try (MockedStatic<AgentAttackExecutionProvider> attacks =
                     mockStatic(AgentAttackExecutionProvider.class, org.mockito.Mockito.CALLS_REAL_METHODS);
             MockedStatic<AgentPotionService> potions = mockStatic(AgentPotionService.class);
             MockedStatic<ShopFactory> shops = mockStatic(ShopFactory.class);
             MockedStatic<AgentShopRuntime> runtime = mockStatic(AgentShopRuntime.class)) {
            ShopFactory factory = mock(ShopFactory.class);
            shops.when(ShopFactory::getInstance).thenReturn(factory);
            when(factory.getShopForNPC(npc.getId())).thenReturn(shop);
            attacks.when(() -> AgentAttackExecutionProvider.getEquippedWeaponType(bot)).thenReturn(WeaponType.CLAW);
            potions.when(() -> AgentPotionService.countPotions(bot)).thenReturn(new int[]{9999, 9999});
            runtime.when(() -> AgentShopRuntime.afterDelay(any(AgentRuntimeEntry.class), anyLong(), any(Runnable.class)))
                    .thenAnswer(invocation -> scheduled.add(invocation.getArgument(2)));

            // The first visit reaches the shopkeeper: its transaction opens and its first step is scheduled.
            AgentShopService.onMapChange(entry, bot, inventory);
            AgentShopStateRuntime.setShopApproachDelayMs(entry, 0);
            assertTrue(AgentShopService.tickShopVisit(entry, bot, inventory));
            assertTrue(AgentShopStateRuntime.shopSequenceActive(entry));
            assertEquals(1, scheduled.size());

            // Before that step comes due, the shops are scanned again: the first visit ends and a second opens.
            AgentShopService.onMapChange(entry, bot, inventory);
            assertTrue(AgentShopStateRuntime.shopVisitPending(entry));
            assertFalse(AgentShopStateRuntime.shopSequenceActive(entry));

            scheduled.get(0).run();
        }

        assertTrue(AgentShopStateRuntime.shopVisitPending(entry));
        assertEquals(AgentShopWorkflowPhase.APPROACHING, AgentShopStateRuntime.workflow(entry).phase());
    }

    @Test
    void shouldTriggerRechargeForDepletedBestStarMaskedByWeakerStars() {
        // Plenty of weak stars (id 2070000) plus a low stack of the BEST star (id 2070018).
        // Total (5800) is above the trigger, but the best star (800) is below it.
        Character bot = clawBotWithStarItems(new int[]{2070000, 2070000, 2070000, 2070000, 2070000},
                new int[]{1000, 1000, 1000, 1000, 1000});
        bot.getInventory(InventoryType.USE).addItem(Items.itemWithQuantity(2070018, 800));

        InventoryGateway inventory = starStatsInventory();
        assertTrue(entryWouldTriggerShopVisit(bot, WeaponType.CLAW, inventory));
        assertTrue(AgentShopService.shouldRechargeWhileShopping(bot, WeaponType.CLAW, inventory));
    }

    @Test
    void shouldNotRechargeWhenBestStarIsHealthyDespiteLowWeakerStars() {
        // Best star (2070018) is full and above threshold; a near-empty weak star must not trigger.
        Character bot = clawBotWithStarItems(new int[]{2070000, 2070018}, new int[]{50, 5000});

        InventoryGateway inventory = starStatsInventory();
        assertFalse(entryWouldTriggerShopVisit(bot, WeaponType.CLAW, inventory));
        assertFalse(AgentShopService.shouldRechargeWhileShopping(bot, WeaponType.CLAW, inventory));
    }

    @Test
    void shouldBuyArrowsWhileShoppingWhenBelowTargetButAboveTrigger() {
        Character bot = bowBotWithArrows(4500);

        assertFalse(entryWouldTriggerShopVisit(bot, WeaponType.BOW, inventoryGateway()));
        assertTrue(AgentShopService.shouldBuyFixedAmmoWhileShopping(bot, WeaponType.BOW));
    }

    @Test
    void shouldBuyInitialRechargeStackWhenClawHasNoThrowingStars() {
        Character bot = clawBotWithStars();

        assertTrue(AgentShopService.shouldBuySeedRechargeAmmoWhileShopping(
                bot, WeaponType.CLAW));

        bot.getInventory(InventoryType.USE).addItem(Items.itemWithQuantity(2070000, 1_000));
        assertFalse(AgentShopService.shouldBuySeedRechargeAmmoWhileShopping(
                bot, WeaponType.CLAW));
    }

    @Test
    void shouldTriggerSellTrashShopVisitEvenWhenNoResupplyIsNeeded() {
        Character bot = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(bot, null, null);
        NPC npc = shopNpc(new Point(20, 0));
        Shop shop = mock(Shop.class);

        when(bot.getMap()).thenReturn(map);
        when(bot.getPosition()).thenReturn(new Point(0, 0));
        when(bot.getInventory(InventoryType.USE)).thenReturn(new Inventory(bot, InventoryType.USE, (byte) 24));
        when(bot.getBuffedValue(any(BuffStat.class))).thenReturn(null);
        when(map.getMapObjectsInRange(any(Point.class), anyDouble(), any())).thenReturn(List.of(npc));
        when(shop.getItems()).thenReturn(List.of());

        try (MockedStatic<AgentAttackExecutionProvider> attacks =
                     mockStatic(AgentAttackExecutionProvider.class, org.mockito.Mockito.CALLS_REAL_METHODS);
             MockedStatic<AgentPotionService> potions = mockStatic(AgentPotionService.class);
             MockedStatic<ShopFactory> shops = mockStatic(ShopFactory.class);
             MockedStatic<AgentInventorySellTrashService> inventories = mockStatic(AgentInventorySellTrashService.class)) {
            ShopFactory factory = mock(ShopFactory.class);
            shops.when(ShopFactory::getInstance).thenReturn(factory);
            when(factory.getShopForNPC(npc.getId())).thenReturn(shop);
            attacks.when(() -> AgentAttackExecutionProvider.getEquippedWeaponType(bot)).thenReturn(WeaponType.CLAW);
            potions.when(() -> AgentPotionService.countPotions(bot)).thenReturn(new int[]{9999, 9999});
            inventories.when(() -> AgentInventorySellTrashService.collectSellTrashEquips(
                            any(AgentRuntimeEntry.class), any(Character.class), any(InventoryGateway.class)))
                    .thenReturn(List.of(mock(Item.class)));

            AgentShopService.requestSellTrashVisit(entry, bot, inventoryGateway());
        }

        assertTrue(AgentShopStateRuntime.shopVisitPending(entry));
        assertTrue(AgentShopStateRuntime.shopSellTrashPending(entry));
        assertEquals(new Point(20, 0), AgentShopStateRuntime.shopNpcPosition(entry));
    }

    private static Character clawBotWithStars(int... quantities) {
        int[] ids = new int[quantities.length];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = 2070000;
        }
        return clawBotWithStarItems(ids, quantities);
    }

    private static Character clawBotWithStarItems(int[] ids, int[] quantities) {
        Character bot = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        Inventory use = new Inventory(bot, InventoryType.USE, (byte) 24);
        for (int i = 0; i < ids.length; i++) {
            use.addItem(Items.itemWithQuantity(ids[i], quantities[i]));
        }
        when(bot.getMap()).thenReturn(map);
        when(bot.getPosition()).thenReturn(new Point(0, 0));
        when(bot.getInventory(InventoryType.USE)).thenReturn(use);
        when(bot.getBuffedValue(any(BuffStat.class))).thenReturn(null);
        return bot;
    }

    // Stub the ItemInformationProvider-backed gateway: star 2070018 is the strongest, all stacks
    // are well under slot-max so any partial stack counts as refillable.
    private static InventoryGateway starStatsInventory() {
        InventoryGateway inventory = inventoryGateway();
        when(inventory.getProjectileWeaponAttack(2070018)).thenReturn(50);
        when(inventory.getProjectileWeaponAttack(2070000)).thenReturn(10);
        when(inventory.getSlotMax(any(Character.class), anyInt())).thenReturn((short) 10000);
        return inventory;
    }

    private static InventoryGateway inventoryGateway() {
        return mock(InventoryGateway.class);
    }

    private static Character bowBotWithArrows(int quantity) {
        Character bot = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        Inventory use = new Inventory(bot, InventoryType.USE, (byte) 24);
        use.addItem(Items.itemWithQuantity(2060000, quantity));
        when(bot.getMap()).thenReturn(map);
        when(bot.getPosition()).thenReturn(new Point(0, 0));
        when(bot.getInventory(InventoryType.USE)).thenReturn(use);
        when(bot.getBuffedValue(any(BuffStat.class))).thenReturn(null);
        return bot;
    }

    private static boolean entryWouldTriggerShopVisit(Character bot, WeaponType weaponType, InventoryGateway inventory) {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(bot, null, null);
        MapleMap map = bot.getMap();
        NPC npc = shopNpc(new Point(20, 0));
        Shop shop = mock(Shop.class);

        when(map.getMapObjectsInRange(any(Point.class), anyDouble(), any())).thenReturn(List.of(npc));
        when(shop.getItems()).thenReturn(List.<server.ShopItem>of());

        try (MockedStatic<AgentAttackExecutionProvider> attacks =
                     mockStatic(AgentAttackExecutionProvider.class, org.mockito.Mockito.CALLS_REAL_METHODS);
             MockedStatic<AgentPotionService> potions = mockStatic(AgentPotionService.class);
             MockedStatic<ShopFactory> shops = mockStatic(ShopFactory.class)) {
            ShopFactory factory = mock(ShopFactory.class);
            shops.when(ShopFactory::getInstance).thenReturn(factory);
            when(factory.getShopForNPC(npc.getId())).thenReturn(shop);
            attacks.when(() -> AgentAttackExecutionProvider.getEquippedWeaponType(bot)).thenReturn(weaponType);
            potions.when(() -> AgentPotionService.countPotions(bot)).thenReturn(new int[]{9999, 9999});

            AgentShopService.onMapChange(entry, bot, inventory);
        }

        return AgentShopStateRuntime.shopVisitPending(entry);
    }

    private static NPC shopNpc(Point position) {
        NPC npc = mock(NPC.class);
        when(npc.hasShop()).thenReturn(true);
        when(npc.getId()).thenReturn(9010000);
        when(npc.getPosition()).thenReturn(new Point(position));
        return npc;
    }
}
