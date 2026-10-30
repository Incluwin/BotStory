package server.agents.capabilities.shop;

import client.BuffStat;
import client.Character;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import server.Shop;
import server.ShopItem;
import server.StatEffect;
import server.agents.capabilities.dialogue.AgentDialogueCatalog;
import server.agents.capabilities.expedition.boss.AgentBossRunRegistry;
import server.agents.capabilities.expedition.boss.AgentBossRunSession;
import server.agents.capabilities.inventory.AgentInventorySellTrashService;
import server.agents.capabilities.inventory.AgentUseItemClassificationPolicy;
import server.agents.capabilities.inventory.demand.AgentQuestItemDispositionRuntime;
import server.agents.capabilities.movement.AgentMapStateRuntime;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.capabilities.supplies.AgentPotionInventoryPolicy;
import server.agents.capabilities.supplies.AgentPotionService;
import server.agents.capabilities.supplies.AgentSupplyReadiness;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.integration.AgentRelationshipRuntime;
import server.agents.integration.AgentShopGatewayRuntime;
import server.agents.integration.InventoryGateway;
import server.agents.integration.ShopGateway;
import server.agents.plans.AgentTask;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.activity.AgentActivityHostState;
import server.agents.runtime.activity.session.AgentActivityKind;
import server.life.NPC;
import server.maps.MapleMap;
import testutil.Items;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Companions sell their junk at every shop visit (decision 2026-10-10; the money stays in the companion's purse). A
 * companion the host names (AgentJunkSale.Rule) sells its junk gear quietly at every shop visit as its transaction
 * opens: a restock on arrival, a supply trip's visit (which keeps its own sale and lines), and a sale-only visit it
 * opens when its owner opens an NPC shop window on its map (once per map visit, that shopkeeper only). It stops
 * selling when its owner leaves the map, and gives up a sale-only visit it has not reached yet. "sell junk" during a
 * quiet sale is kept, sells what came meanwhile at the same counter, and is told in one line. Nobody else sells, and
 * nobody sells routinely in an event instance, a party quest or a boss run, or while held back for mesos.
 *
 * <p>The visit runs on a virtual clock, as in AgentShopServiceSaleBudgetTest: an Agent at El Nath's potion shop with
 * real Equip, ETC and Use tabs; the shopkeeper sells red potions and buys back everything but what a test refuses.</p>
 */
class AgentShopServiceJunkSaleTest {
    private static final int NPC_ID = 2_020_001;
    private static final int UNKNOWN_NPC = 9_999_999;
    private static final int EL_NATH_POTION_SHOP_MAP = 211_000_102;
    private static final int EL_NATH = 211_000_000;
    private static final int OWNER_ID = 4_200;
    private static final int RED = 2_000_000;
    private static final int RED_PRICE = 50;
    private static final int TRASH_SWORD = 1_302_000;
    private static final int APPROACH_TICK_MS = 100;

    private record Step(long atMs, long order, Runnable action) {}

    private final Point npcPosition = new Point(100, 50);
    private final Point botPosition = new Point(npcPosition);
    private final ShopGateway gateway = mock(ShopGateway.class);
    private final InventoryGateway inventoryGateway = mock(InventoryGateway.class);
    private final PriorityQueue<Step> steps = new PriorityQueue<>(
            Comparator.comparingLong(Step::atMs).thenComparingLong(Step::order));
    private final List<String> spoken = new ArrayList<>();
    private final List<String> replies = new ArrayList<>();
    private final List<String> bought = new ArrayList<>();
    /** Item ids the shopkeeper refuses to buy back. */
    private final Set<Integer> unsellable = new HashSet<>();
    /** Run after the n-th piece sold. */
    private final Map<Integer, Runnable> afterSale = new HashMap<>();
    private final StatEffect redEffect = mock(StatEffect.class);
    private long now;
    private long order;
    private int purse = 50_000;
    private int sales;
    private int ownerMapId = EL_NATH_POTION_SHOP_MAP;
    private boolean ruleSays = true;
    private Character bot;
    private Character owner;
    private Shop ownerShop;
    private AgentRuntimeEntry entry;
    private Inventory equips;
    private Inventory etc;
    private Inventory use;
    private MockedStatic<AgentShopRuntime> runtime;
    private MockedStatic<AgentShopGatewayRuntime> shops;
    private MockedStatic<AgentInventoryGatewayRuntime> inventories;
    private MockedStatic<AgentInventorySellTrashService> trash;
    private MockedStatic<AgentQuestItemDispositionRuntime> etcSales;
    private MockedStatic<AgentUseItemClassificationPolicy> classification;
    private MockedStatic<AgentPotionService> potions;

    @BeforeEach
    void aCompanionFollowingItsOwnerAtElNathsPotionShopWithNoPotions() {
        bot = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        NPC npc = mock(NPC.class);
        when(npc.getId()).thenReturn(NPC_ID);
        when(npc.hasShop()).thenReturn(true);
        when(npc.getPosition()).thenReturn(npcPosition);
        when(map.getMapObjectsInRange(any(Point.class), anyDouble(), any())).thenReturn(List.of(npc));
        when(map.getId()).thenReturn(EL_NATH_POTION_SHOP_MAP);
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(EL_NATH_POTION_SHOP_MAP);
        when(bot.getPosition()).thenAnswer(inv -> new Point(botPosition));
        when(bot.getName()).thenReturn("Hadrik");
        when(bot.getId()).thenReturn(15);
        when(bot.getHp()).thenReturn(1_000);
        when(bot.getCurrentMaxHp()).thenReturn(1_000);
        when(bot.getCurrentMaxMp()).thenReturn(400);
        when(bot.getMeso()).thenAnswer(inv -> purse);
        when(bot.getBuffedValue(any(BuffStat.class))).thenReturn(null);
        equips = new Inventory(bot, InventoryType.EQUIP, (byte) 96);
        etc = new Inventory(bot, InventoryType.ETC, (byte) 96);
        use = new Inventory(bot, InventoryType.USE, (byte) 24);
        when(bot.getInventory(InventoryType.EQUIP)).thenReturn(equips);
        when(bot.getInventory(InventoryType.ETC)).thenReturn(etc);
        when(bot.getInventory(InventoryType.USE)).thenReturn(use);
        entry = new AgentRuntimeEntry(bot, null, null);

        // its owner stands beside it in the store, with no shop window open yet
        owner = mock(Character.class);
        when(owner.getId()).thenReturn(OWNER_ID);
        when(owner.getMapId()).thenAnswer(inv -> ownerMapId);
        ownerShop = mock(Shop.class);
        when(ownerShop.getNpcId()).thenReturn(NPC_ID);
        AgentModeStateRuntime.startFollowing(entry, OWNER_ID);
        AgentRelationshipRuntime.setFollowTarget(entry, owner);
        AgentMapStateRuntime.setMapTracking(entry, EL_NATH_POTION_SHOP_MAP, Map.of());

        ShopItem red = mock(ShopItem.class);
        when(red.getItemId()).thenReturn(RED);
        when(red.getPrice()).thenReturn(RED_PRICE);
        Shop shop = mock(Shop.class);
        when(shop.getItems()).thenReturn(List.of(red));
        shops = mockStatic(AgentShopGatewayRuntime.class);
        shops.when(AgentShopGatewayRuntime::shop).thenReturn(gateway);
        inventories = mockStatic(AgentInventoryGatewayRuntime.class);
        inventories.when(AgentInventoryGatewayRuntime::inventory).thenReturn(inventoryGateway);
        when(gateway.findForNpc(NPC_ID)).thenReturn(shop);
        when(gateway.sell(any(), any(), any(InventoryType.class), anyShort(), anyShort())).thenAnswer(inv -> {
            InventoryType type = inv.getArgument(2);
            short slot = inv.getArgument(3);
            short quantity = inv.getArgument(4);
            if (unsellable.contains(bot.getInventory(type).getItem(slot).getItemId())) {
                return Shop.TransactionResult.INVALID;
            }
            bot.getInventory(type).removeItem(slot, quantity, false);
            purse += 1;
            Runnable then = afterSale.get(++sales);
            if (then != null) {
                then.run();
            }
            return Shop.TransactionResult.SUCCESS;
        });
        when(gateway.buy(any(), any(), anyShort(), anyInt(), anyShort())).thenAnswer(inv -> {
            int itemId = inv.getArgument(3);
            short quantity = inv.getArgument(4);
            purse -= RED_PRICE * quantity;
            use.addItem(Items.itemWithQuantity(itemId, quantity));
            bought.add(quantity + " x " + itemId);
            return Shop.TransactionResult.SUCCESS;
        });

        when(redEffect.getHp()).thenReturn((short) 50);
        classification = mockStatic(AgentUseItemClassificationPolicy.class);
        classification.when(() -> AgentUseItemClassificationPolicy.isRecoveryPotion(anyInt()))
                .thenAnswer(inv -> (int) inv.getArgument(0) == RED);
        classification.when(() -> AgentUseItemClassificationPolicy.isRecoveryPotion(any(StatEffect.class)))
                .thenAnswer(inv -> inv.getArgument(0) != null);
        classification.when(() -> AgentUseItemClassificationPolicy.itemEffect(anyInt()))
                .thenAnswer(inv -> (int) inv.getArgument(0) == RED ? redEffect : null);
        potions = mockStatic(AgentPotionService.class);
        potions.when(() -> AgentPotionService.countPotions(bot)).thenAnswer(inv ->
                AgentPotionInventoryPolicy.countRecoveryPotions(
                        use.list(), id -> id == RED ? redEffect : null));

        runtime = mockStatic(AgentShopRuntime.class);
        runtime.when(() -> AgentShopRuntime.randomDelayMs(anyInt(), anyInt()))
                .thenAnswer(inv -> (long) (int) inv.getArgument(1));
        runtime.when(() -> AgentShopRuntime.afterDelay(any(AgentRuntimeEntry.class), anyLong(), any(Runnable.class)))
                .thenAnswer(inv -> steps.add(new Step(now + (long) inv.getArgument(1), order++, inv.getArgument(2))));
        runtime.when(() -> AgentShopRuntime.sayMapNow(any(), anyString()))
                .thenAnswer(inv -> spoken.add(inv.getArgument(1)));
        runtime.when(() -> AgentShopRuntime.replyNow(any(), anyString()))
                .thenAnswer(inv -> replies.add(inv.getArgument(1)));

        trash = mockStatic(AgentInventorySellTrashService.class);
        trash.when(() -> AgentInventorySellTrashService.collectSellTrashEquips(any(), any(), any()))
                .thenAnswer(inv -> List.copyOf(equips.list()));
        etcSales = mockStatic(AgentQuestItemDispositionRuntime.class);
        etcSales.when(() -> AgentQuestItemDispositionRuntime.collectShopSaleCandidates(any(), any(), any(), anyLong()))
                .thenReturn(List.of());

        AgentJunkSale.install(id -> ruleSays);
        now = System.currentTimeMillis();
    }

    @AfterEach
    void close() {
        AgentJunkSale.install(null);
        etcSales.close();
        trash.close();
        runtime.close();
        potions.close();
        classification.close();
        inventories.close();
        shops.close();
    }

    // ---- every visit sells, quietly ----------------------------------------------------------------

    @Test
    void aCompanionsRestockOnArrivalSellsItsJunkGearFirstAndSaysNothing() {
        carryJunk(2);

        AgentShopService.onArrival(entry, bot, inventoryGateway);
        assertTrue(AgentShopStateRuntime.shopVisitPending(entry), "no potions: a restock visit opens");
        runVisit();

        InOrder counter = inOrder(gateway);
        counter.verify(gateway, times(2)).sell(eq(bot), any(), eq(InventoryType.EQUIP), anyShort(), eq((short) 1));
        counter.verify(gateway, org.mockito.Mockito.atLeastOnce()).buy(any(), any(), anyShort(), eq(RED), anyShort());
        assertTrue(equips.list().isEmpty(), "both pieces of junk were sold");
        assertEquals(List.of(), saleLines(), "a quiet sale");
        etcSales.verify(() -> AgentQuestItemDispositionRuntime.collectShopSaleCandidates(any(), any(), any(),
                anyLong()), never());
        assertEquals(AgentShopWorkflowPhase.COMPLETED, AgentShopStateRuntime.workflow(entry).phase());
    }

    @Test
    void anAgentTheHostDoesNotNameSellsNothing() {
        carryJunk(2);
        AgentJunkSale.install(null); // a citizen: no rule names it

        AgentShopService.onArrival(entry, bot, inventoryGateway);
        runVisit();

        verify(gateway, never()).sell(any(), any(), any(), anyShort(), anyShort());
        assertEquals(2, equips.list().size());
        assertFalse(bought.isEmpty(), "it restocked as today");
        assertEquals(AgentShopWorkflowPhase.COMPLETED, AgentShopStateRuntime.workflow(entry).phase());
    }

    @Test
    void aCompanionWithNoJunkBuysAsBeforeAndSaysNoJunkLine() {
        AgentShopService.onArrival(entry, bot, inventoryGateway);
        runVisit();

        assertFalse(spoken.contains(AgentDialogueCatalog.shopNoTrashEquipsReply()), () -> "spoke " + spoken);
        assertFalse(bought.isEmpty());
        assertEquals(AgentShopWorkflowPhase.COMPLETED, AgentShopStateRuntime.workflow(entry).phase());
    }

    @Test
    void aSupplyTripsVisitKeepsItsSaleLines() {
        carryJunk(1);

        AgentShopStateRuntime.setShopSellTrashPending(entry, true);
        assertTrue(AgentShopService.requestVisitAtNpc(entry, bot, NPC_ID, 0));
        runVisit();

        assertEquals(List.of("sold 1 trash equip"), saleLines());
        etcSales.verify(() -> AgentQuestItemDispositionRuntime.collectShopSaleCandidates(any(), any(), any(),
                anyLong()));
        assertFalse(bought.isEmpty());
    }

    @Test
    void aRoutineSaleThatCannotSellSaysNothingAndTheVisitGoesOn() {
        carryJunk(1);
        unsellable.add(TRASH_SWORD);

        AgentShopService.onArrival(entry, bot, inventoryGateway);
        runVisit();

        assertEquals(1, equips.list().size(), "the shopkeeper refused it");
        assertEquals(List.of(), saleLines());
        assertFalse(bought.isEmpty(), "the purchases ran");
        assertEquals(AgentShopWorkflowPhase.COMPLETED, AgentShopStateRuntime.workflow(entry).phase());
    }

    // ---- its owner opens a shop ---------------------------------------------------------------------

    @Test
    void theOwnerOpeningAShopSendsAFollowingCompanionToSellThere() {
        carryJunk(2);
        when(owner.getShop()).thenReturn(ownerShop);

        AgentShopService.tickOwnerShopping(entry, bot, now);

        assertTrue(AgentShopStateRuntime.shopVisitPending(entry));
        assertTrue(AgentShopStateRuntime.shopSaleOnly(entry));
        assertTrue(AgentShopStateRuntime.shopRoutineSale(entry));
        assertEquals(npcPosition, AgentShopStateRuntime.shopNpcPosition(entry));
        runVisit();

        for (String shopping : AgentDialogueCatalog.shoppingReplies()) {
            assertFalse(spoken.contains(shopping), () -> "spoke " + spoken);
        }
        assertTrue(equips.list().isEmpty(), "its junk was sold");
        verify(gateway, never()).buy(any(), any(), anyShort(), anyInt(), anyShort());
        assertFalse(spoken.contains(AgentDialogueCatalog.shopEmptyResupplyReply()), () -> "spoke " + spoken);
        assertEquals(List.of(), spoken, "nothing said");
        assertEquals(AgentShopWorkflowPhase.COMPLETED, AgentShopStateRuntime.workflow(entry).phase());
        assertFalse(AgentShopStateRuntime.shopVisitPending(entry));
    }

    @Test
    void noSaleVisitWhileTheOwnerHasNoShopOpenIsElsewhereIsChangingMapsOrTheMapIsNotTrackedYet() {
        carryJunk(2);

        AgentShopService.tickOwnerShopping(entry, bot, now);
        assertFalse(AgentShopStateRuntime.shopVisitPending(entry), "no shop window open");

        when(owner.getShop()).thenReturn(ownerShop);
        ownerMapId = EL_NATH;
        AgentShopService.tickOwnerShopping(entry, bot, now);
        assertFalse(AgentShopStateRuntime.shopVisitPending(entry), "its owner's shop is on another map");
        ownerMapId = EL_NATH_POTION_SHOP_MAP;

        when(owner.isChangingMaps()).thenReturn(true);
        AgentShopService.tickOwnerShopping(entry, bot, now);
        assertFalse(AgentShopStateRuntime.shopVisitPending(entry), "its owner is changing maps");
        when(owner.isChangingMaps()).thenReturn(false);

        AgentMapStateRuntime.setMapTracking(entry, EL_NATH, Map.of());
        AgentShopService.tickOwnerShopping(entry, bot, now);
        assertFalse(AgentShopStateRuntime.shopVisitPending(entry), "the arrival hook has not run on this map yet");
        AgentMapStateRuntime.setMapTracking(entry, EL_NATH_POTION_SHOP_MAP, Map.of());

        entry.scriptTaskQueueState().setActiveTask(AgentTask.stop());
        AgentShopService.tickOwnerShopping(entry, bot, now);
        assertFalse(AgentShopStateRuntime.shopVisitPending(entry), "a script task of its own runs");
        entry.scriptTaskQueueState().setActiveTask(null);

        AgentActivityHostState host = entry.capabilityStates().require(AgentActivityHostState.STATE_KEY);
        host.select("activity.party-quest", AgentActivityKind.PARTY_QUEST, now);
        AgentShopService.tickOwnerShopping(entry, bot, now);
        assertFalse(AgentShopStateRuntime.shopVisitPending(entry), "an Activity Host controller owns its foreground");
        host.clear(now);

        AgentShopService.tickOwnerShopping(entry, bot, now);
        assertTrue(AgentShopStateRuntime.shopVisitPending(entry), "all clear: it goes to sell");
    }

    @Test
    void oneLookPerMapVisit() {
        stockPotions();
        when(owner.getShop()).thenReturn(ownerShop);

        AgentShopService.tickOwnerShopping(entry, bot, now);
        assertFalse(AgentShopStateRuntime.shopVisitPending(entry), "nothing to sell");
        trash.verify(() -> AgentInventorySellTrashService.collectSellTrashEquips(any(), any(), any()), times(1));

        carryJunk(1);
        AgentShopService.tickOwnerShopping(entry, bot, now);
        assertFalse(AgentShopStateRuntime.shopVisitPending(entry), "one look per map visit");
        trash.verify(() -> AgentInventorySellTrashService.collectSellTrashEquips(any(), any(), any()), times(1));

        AgentShopService.onArrival(entry, bot, inventoryGateway); // it came onto the map again
        assertFalse(AgentShopStateRuntime.shopVisitPending(entry), "it needs no potions");
        AgentShopService.tickOwnerShopping(entry, bot, now);
        assertTrue(AgentShopStateRuntime.shopVisitPending(entry), "a new map visit: one more look");
        trash.verify(() -> AgentInventorySellTrashService.collectSellTrashEquips(any(), any(), any()), times(2));
    }

    @Test
    void aSaleOnlyVisitIsDroppedQuietlyWhenTheOwnerLeavesBeforeTheCounter() {
        carryJunk(2);
        botPosition.setLocation(-2_000, 50); // across the store
        when(owner.getShop()).thenReturn(ownerShop);
        AgentShopService.tickOwnerShopping(entry, bot, now);
        assertTrue(AgentShopStateRuntime.shopVisitPending(entry));

        ownerMapId = EL_NATH;
        assertFalse(AgentShopService.tickShopVisit(entry, bot, inventoryGateway, now + 10_000L));

        assertFalse(AgentShopStateRuntime.shopVisitPending(entry), "it gave up the visit");
        assertFalse(AgentShopStateRuntime.shopSaleOnly(entry));
        assertFalse(AgentShopStateRuntime.shopSellTrashPending(entry));
        assertEquals(List.of(), spoken);
        assertEquals(List.of(), replies);
        verify(gateway, never()).sell(any(), any(), any(), anyShort(), anyShort());
    }

    @Test
    void aRoutineSaleStopsWhenTheOwnerLeavesAndTheRestockStillBuys() {
        carryJunk(3);
        afterSale.put(1, () -> ownerMapId = EL_NATH);

        AgentShopService.onArrival(entry, bot, inventoryGateway);
        runVisit();

        verify(gateway, times(1)).sell(any(), any(), any(), anyShort(), anyShort());
        assertEquals(2, equips.list().size(), "the rest waits for the next shop");
        assertFalse(bought.isEmpty(), "the restock's buy step ran");
        assertEquals(List.of(), saleLines());
        assertFalse(spoken.contains(AgentDialogueCatalog.shopSellInterruptedReply()), () -> "spoke " + spoken);
        assertEquals(AgentShopWorkflowPhase.COMPLETED, AgentShopStateRuntime.workflow(entry).phase());
    }

    @Test
    void sellJunkAtTheCounterDuringARoutineSaleIsKeptAndNeverCut() {
        carryJunk(3);
        stockPotions();
        when(owner.getShop()).thenReturn(ownerShop);
        afterSale.put(1, () -> {
            AgentShopService.requestSellTrashVisit(entry, bot, inventoryGateway);
            ownerMapId = EL_NATH; // and its owner walks out
        });

        AgentShopService.tickOwnerShopping(entry, bot, now);
        runVisit();

        assertEquals(List.of(AgentDialogueCatalog.shopSellTrashAfterReply()), replies);
        assertTrue(equips.list().isEmpty(), "all three sold");
        assertEquals(List.of("sold 3 trash equips"), saleLines());
        assertFalse(spoken.contains(AgentDialogueCatalog.shopNoTrashEquipsReply()), () -> "spoke " + spoken);
        assertFalse(AgentShopStateRuntime.shopVisitPending(entry));
        assertFalse(AgentShopStateRuntime.shopSellTrashPending(entry));
    }

    @Test
    void aRoutineSaleThenSellJunkSellsWhatCameMeanwhileWithOneLine() {
        carryJunk(2);
        afterSale.put(1, () -> {
            equips.addItem(Equip.restored(TRASH_SWORD, (short) 0)); // its owner hands it one more piece
            AgentShopService.requestSellTrashVisit(entry, bot, inventoryGateway);
        });

        AgentShopService.onArrival(entry, bot, inventoryGateway);
        runVisit();

        verify(gateway, times(3)).sell(any(), any(), eq(InventoryType.EQUIP), anyShort(), eq((short) 1));
        assertTrue(equips.list().isEmpty(), "all three sold at the same counter");
        assertEquals(List.of("sold 3 trash equips"), saleLines());
        assertFalse(spoken.contains(AgentDialogueCatalog.shopNoTrashEquipsReply()), () -> "spoke " + spoken);
        assertFalse(bought.isEmpty(), "then it restocked");
        assertEquals(AgentShopWorkflowPhase.COMPLETED, AgentShopStateRuntime.workflow(entry).phase());
        assertFalse(AgentShopStateRuntime.shopSellTrashPending(entry));
    }

    @Test
    void sellJunkWhileASaleOnlyVisitWalksGetsTheJoinLine() {
        carryJunk(2);
        botPosition.setLocation(-2_000, 50);
        when(owner.getShop()).thenReturn(ownerShop);
        AgentShopService.tickOwnerShopping(entry, bot, now);
        assertTrue(AgentShopStateRuntime.shopRoutineSale(entry));

        AgentShopService.requestSellTrashVisit(entry, bot, inventoryGateway);

        assertEquals(List.of(AgentDialogueCatalog.shopSellTrashJoinReply()), replies);
        assertTrue(AgentShopStateRuntime.shopSaleAsked(entry), "the owner asked: the sale is announced");
        assertTrue(AgentShopStateRuntime.shopVisitPending(entry));
    }

    @Test
    void noRoutineSaleInAnEventInstanceAPartyQuestOrABossRun() {
        carryJunk(2);
        when(owner.getShop()).thenReturn(ownerShop);

        try (MockedStatic<AgentPartyQuestEngagementRegistry> pqs = mockStatic(AgentPartyQuestEngagementRegistry.class)) {
            pqs.when(() -> AgentPartyQuestEngagementRegistry.forMember(15))
                    .thenReturn(mock(AgentPartyQuestEngagement.class));
            AgentShopService.tickOwnerShopping(entry, bot, now);
            assertFalse(AgentShopStateRuntime.shopVisitPending(entry), "in a party quest");
        }
        AgentShopStateRuntime.setOwnerShopSaleMapId(entry, 0);

        try (MockedStatic<AgentBossRunRegistry> bosses = mockStatic(AgentBossRunRegistry.class)) {
            bosses.when(() -> AgentBossRunRegistry.forMember(15)).thenReturn(mock(AgentBossRunSession.class));
            AgentShopService.tickOwnerShopping(entry, bot, now);
            assertFalse(AgentShopStateRuntime.shopVisitPending(entry), "in a boss run");
        }
        AgentShopStateRuntime.setOwnerShopSaleMapId(entry, 0);

        when(bot.getEventInstance()).thenReturn(mock(EventInstanceManager.class));
        AgentShopService.tickOwnerShopping(entry, bot, now);
        assertFalse(AgentShopStateRuntime.shopVisitPending(entry), "in an event instance");

        // a restock inside the instance buys as today and sells nothing
        AgentShopService.onArrival(entry, bot, inventoryGateway);
        runVisit();
        verify(gateway, never()).sell(any(), any(), any(), anyShort(), anyShort());
        assertFalse(bought.isEmpty());
    }

    @Test
    void aCompanionHeldForMesosOpensNoVisitOnArrivalOrForItsOwnersShop() {
        carryJunk(2);
        when(owner.getShop()).thenReturn(ownerShop);
        boolean[] held = {true};
        try (MockedStatic<AgentSupplyReadiness> readiness =
                     mockStatic(AgentSupplyReadiness.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            readiness.when(() -> AgentSupplyReadiness.heldForMesos(eq(entry), anyLong())).thenAnswer(inv -> held[0]);

            AgentShopService.onArrival(entry, bot, inventoryGateway);
            assertFalse(AgentShopStateRuntime.shopVisitPending(entry), "no restock of its own");
            AgentShopService.tickOwnerShopping(entry, bot, now);
            assertFalse(AgentShopStateRuntime.shopVisitPending(entry), "no sale visit for its owner's shop");

            held[0] = false;
            AgentShopService.tickOwnerShopping(entry, bot, now);
            assertTrue(AgentShopStateRuntime.shopVisitPending(entry), "once it may buy again, it sells too");
        }
    }

    @Test
    void aSupplyTripReachingASaleOnlyVisitBuysThere() {
        carryJunk(1);
        botPosition.setLocation(-2_000, 50);
        when(owner.getShop()).thenReturn(ownerShop);
        AgentShopService.tickOwnerShopping(entry, bot, now);
        assertTrue(AgentShopStateRuntime.shopSaleOnly(entry));

        // its own supply trip begins and asks for the same shopkeeper
        AgentShopStateRuntime.setShopSellTrashPending(entry, true);
        assertTrue(AgentShopService.requestVisitAtNpc(entry, bot, NPC_ID, 0));
        assertFalse(AgentShopStateRuntime.shopSaleOnly(entry));
        assertTrue(AgentShopStateRuntime.shopSaleAsked(entry));

        botPosition.setLocation(npcPosition);
        runVisit();
        assertFalse(bought.isEmpty(), "its purchase steps ran");
        assertEquals(List.of("sold 1 trash equip"), saleLines());
    }

    @Test
    void requestSaleVisitRefusesWithoutJunkWithoutARuleOrWithAVisitPending() {
        assertFalse(AgentShopService.requestSaleVisit(entry, bot, NPC_ID, true), "no junk");

        carryJunk(1);
        AgentJunkSale.install(null);
        assertFalse(AgentShopService.requestSaleVisit(entry, bot, NPC_ID, true), "no rule");
        AgentJunkSale.install(id -> ruleSays);
        ruleSays = false;
        assertFalse(AgentShopService.requestSaleVisit(entry, bot, NPC_ID, true), "the rule does not name it");
        ruleSays = true;

        AgentShopService.onArrival(entry, bot, inventoryGateway); // a restock visit is on its way
        assertTrue(AgentShopStateRuntime.shopVisitPending(entry));
        assertFalse(AgentShopService.requestSaleVisit(entry, bot, NPC_ID, true), "a visit is pending");
        assertFalse(AgentShopStateRuntime.shopSaleOnly(entry), "the restock is left as it is");

        AgentShopStateRuntime.clearShopState(entry);
        assertTrue(AgentShopService.requestSaleVisit(entry, bot, NPC_ID, true));
        assertTrue(AgentShopStateRuntime.shopSaleOnly(entry));
        assertTrue(AgentShopStateRuntime.shopRoutineSale(entry));
    }

    @Test
    void theOwnerTriggerUsesOnlyTheOwnersShopkeeper() {
        carryJunk(1);
        Shop elsewhere = mock(Shop.class);
        when(elsewhere.getNpcId()).thenReturn(UNKNOWN_NPC); // a shopkeeper who does not stand on this map
        when(owner.getShop()).thenReturn(elsewhere);

        AgentShopService.tickOwnerShopping(entry, bot, now);
        assertFalse(AgentShopStateRuntime.shopVisitPending(entry), "never another shopkeeper across the map");

        // a bench sale slice may take any shopkeeper on its map
        assertTrue(AgentShopService.requestSaleVisit(entry, bot, UNKNOWN_NPC, true));
        assertEquals(npcPosition, AgentShopStateRuntime.shopNpcPosition(entry));
    }

    @Test
    void theAgentsShopPhaseLooksAtItsOwnersShopOnEveryTick() {
        // the owner trigger is run by the shop phase of every Agent tick (AgentLiveModeTickRuntime), visit or none
        carryJunk(1);
        botPosition.setLocation(-2_000, 50);
        AgentShopVisitTickService.Hooks idle = new AgentShopVisitTickService.Hooks(
                (visitEntry, visitAgent) -> false, (moveEntry, target, runAi) -> { }, (holdEntry, holdAgent) -> { });

        AgentShopVisitTickService.tickShopVisitIfPending(entry, bot, true, idle);
        assertFalse(AgentShopStateRuntime.shopVisitPending(entry), "no shop window open");

        when(owner.getShop()).thenReturn(ownerShop);
        AgentShopVisitTickService.Result result = AgentShopVisitTickService.tickShopVisitIfPending(entry, bot, true, idle);

        assertTrue(AgentShopStateRuntime.shopVisitPending(entry), "its owner opened the shop: it goes to sell");
        assertTrue(AgentShopStateRuntime.shopSaleOnly(entry));
        assertTrue(result.consumedTick(), "the visit walks it from this tick on");
    }

    @Test
    void aSaleOnlyVisitThatComesNoCloserGivesUpQuietly() {
        // the no-progress give-up (fix/economy 10-29) said "couldn't reach the shop" for a visit nobody asked for
        carryJunk(1);
        botPosition.setLocation(-2_000, 50);
        when(owner.getShop()).thenReturn(ownerShop);
        AgentShopService.tickOwnerShopping(entry, bot, now);
        AgentShopStateRuntime.setShopApproachDelayMs(entry, 0);
        AgentShopStateRuntime.extendShopVisit(entry, 600_000L); // a long walk: only the no-progress rule ends it

        for (long at = now; at <= now + 120_000L && AgentShopStateRuntime.shopVisitPending(entry); at += 1_000L) {
            AgentShopService.tickShopVisit(entry, bot, inventoryGateway, at); // it never comes any closer
        }

        assertFalse(AgentShopStateRuntime.shopVisitPending(entry), "the stuck walk was given up");
        assertEquals(List.of(), spoken);
    }

    // ---- harness ------------------------------------------------------------------------------------

    private void carryJunk(int pieces) {
        for (int i = 0; i < pieces; i++) {
            equips.addItem(Equip.restored(TRASH_SWORD, (short) 0));
        }
    }

    /** Enough red potions that it needs none (no restock visit on arrival). */
    private void stockPotions() {
        use.addItem(Items.itemWithQuantity(RED, 1_000));
    }

    /** What the Agent said about a sale itself. */
    private List<String> saleLines() {
        return spoken.stream()
                .filter(line -> line.startsWith("sold ") || line.startsWith("unable to sell ")
                        || line.equals(AgentDialogueCatalog.shopNoTrashEquipsReply()))
                .toList();
    }

    /**
     * The Agent's tick: the visit is ticked until the shopkeeper's transaction opens, then before each scheduled shop
     * step, as the movement loop does.
     */
    private void runVisit() {
        for (int tick = 0; tick < 1_000 && AgentShopStateRuntime.shopVisitPending(entry)
                && !AgentShopStateRuntime.shopSequenceActive(entry); tick++) {
            AgentShopService.tickShopVisit(entry, bot, inventoryGateway, now);
            now += APPROACH_TICK_MS;
        }
        while (!steps.isEmpty()) {
            Step step = steps.poll();
            now = Math.max(now, step.atMs());
            AgentShopService.tickShopVisit(entry, bot, inventoryGateway, now);
            step.action().run();
        }
    }
}
