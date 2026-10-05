package server.agents.capabilities.shop;

import client.BuffStat;
import client.Character;
import client.inventory.Equip;
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
import server.agents.capabilities.dialogue.AgentDialogueCatalog;
import server.agents.capabilities.inventory.AgentInventorySellTrashService;
import server.agents.capabilities.inventory.AgentUseItemClassificationPolicy;
import server.agents.capabilities.inventory.demand.AgentEtcSaleCandidate;
import server.agents.capabilities.inventory.demand.AgentItemDispositionProposal;
import server.agents.capabilities.inventory.demand.AgentQuestItemDispositionRuntime;
import server.agents.capabilities.supplies.AgentPotionInventoryPolicy;
import server.agents.capabilities.supplies.AgentPotionService;
import server.agents.integration.AgentShopGatewayRuntime;
import server.agents.integration.InventoryGateway;
import server.agents.integration.ShopGateway;
import server.agents.runtime.AgentRuntimeEntry;
import server.life.NPC;
import server.maps.MapleMap;
import testutil.Items;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * A supply trip sells the Agent's trash before it buys (AgentSupplyProcurementRuntime sets sellTrashPending), one sale
 * every SELL_TRASH_STEP_DELAY_MS. Live 2026-10-04, bots carrying a dozen ETC stacks and a tab of trash equips spent most
 * of SHOP_SEQUENCE_TIMEOUT_MS selling and gave up mid-purchase ("took too long at the shop, giving up"), which the
 * supply trip counts as a failed shop visit. The visit is replayed on a virtual clock with every purchase step at
 * SHOP_STEP_DELAY_MAX_MS, ticking the visit before each scheduled step the way the procurement loop does.
 */
class AgentShopServiceSaleBudgetTest {
    private static final int NPC_ID = 2_020_001;
    private static final int EL_NATH_POTION_SHOP_MAP = 211_000_102;
    private static final int RED = 2_000_000;
    private static final int RED_PRICE = 50;
    private static final int FIRST_ETC = 4_000_000;
    private static final int TRASH_SWORD = 1_302_000;
    private static final int APPROACH_TICK_MS = 100;

    private record Step(long atMs, long order, Runnable action) {}

    private final Point npcPosition = new Point(100, 50);
    private final ShopGateway gateway = mock(ShopGateway.class);
    private final InventoryGateway inventoryGateway = mock(InventoryGateway.class);
    private final PriorityQueue<Step> steps = new PriorityQueue<>(
            Comparator.comparingLong(Step::atMs).thenComparingLong(Step::order));
    private final List<String> spoken = new ArrayList<>();
    private final List<String> bought = new ArrayList<>();
    private final StatEffect redEffect = mock(StatEffect.class);
    private long now;
    private long order;
    private int purse = 50_000;
    private Character bot;
    private AgentRuntimeEntry entry;
    private Inventory equips;
    private Inventory etc;
    private Inventory use;
    private MockedStatic<AgentShopRuntime> runtime;
    private MockedStatic<AgentShopGatewayRuntime> shops;
    private MockedStatic<AgentInventorySellTrashService> trash;
    private MockedStatic<AgentQuestItemDispositionRuntime> etcSales;
    private MockedStatic<AgentUseItemClassificationPolicy> classification;
    private MockedStatic<AgentPotionService> potions;

    @BeforeEach
    void anAgentAtElNathsPotionShopWithNoPotions() {
        bot = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        NPC npc = mock(NPC.class);
        when(npc.getId()).thenReturn(NPC_ID);
        when(npc.hasShop()).thenReturn(true);
        when(npc.getPosition()).thenReturn(npcPosition);
        when(map.getMapObjectsInRange(any(Point.class), anyDouble(), any())).thenReturn(List.of(npc));
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(EL_NATH_POTION_SHOP_MAP);
        when(bot.getPosition()).thenAnswer(inv -> new Point(npcPosition));
        when(bot.getName()).thenReturn("Yara");
        when(bot.getId()).thenReturn(15);
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

        ShopItem red = mock(ShopItem.class);
        when(red.getItemId()).thenReturn(RED);
        when(red.getPrice()).thenReturn(RED_PRICE);
        Shop shop = mock(Shop.class);
        when(shop.getItems()).thenReturn(List.of(red));
        shops = mockStatic(AgentShopGatewayRuntime.class);
        shops.when(AgentShopGatewayRuntime::shop).thenReturn(gateway);
        when(gateway.findForNpc(NPC_ID)).thenReturn(shop);
        when(gateway.sell(any(), any(), any(InventoryType.class), anyShort(), anyShort())).thenAnswer(inv -> {
            InventoryType type = inv.getArgument(2);
            short slot = inv.getArgument(3);
            short quantity = inv.getArgument(4);
            bot.getInventory(type).removeItem(slot, quantity, false);
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

        trash = mockStatic(AgentInventorySellTrashService.class);
        trash.when(() -> AgentInventorySellTrashService.collectSellTrashEquips(any(), any(), any()))
                .thenAnswer(inv -> List.copyOf(equips.list()));
        etcSales = mockStatic(AgentQuestItemDispositionRuntime.class);
        etcSales.when(() -> AgentQuestItemDispositionRuntime.collectShopSaleCandidates(any(), any(), any(), anyLong()))
                .thenAnswer(inv -> etc.list().stream().map(AgentShopServiceSaleBudgetTest::saleOf).toList());
    }

    @AfterEach
    void close() {
        etcSales.close();
        trash.close();
        runtime.close();
        potions.close();
        classification.close();
        shops.close();
    }

    @Test
    void aSupplyTripCarryingTwelveEtcStacksAndATabOfTrashStillFinishesItsPurchases() {
        carry(12, 48);

        supplyTrip();

        assertFalse(spoken.contains(AgentDialogueCatalog.shopSequenceTimeoutReply()), () -> "spoke " + spoken);
        assertEquals(AgentShopWorkflowPhase.COMPLETED, AgentShopStateRuntime.workflow(entry).phase());
        assertTrue(equips.list().isEmpty(), "every trash equip was sold");
        assertTrue(etc.list().isEmpty(), "every surplus ETC stack was sold");
        assertFalse(bought.isEmpty(), "the purchase steps ran after the sale");
    }

    /** {@code etcStacks} stacks of 100 sellable ETC and {@code trashEquips} trash equips. */
    private void carry(int etcStacks, int trashEquips) {
        for (int i = 0; i < etcStacks; i++) {
            etc.addItem(Items.itemWithQuantity(FIRST_ETC + i, 100));
        }
        for (int i = 0; i < trashEquips; i++) {
            equips.addItem(Equip.restored(TRASH_SWORD, (short) 0));
        }
    }

    /**
     * AgentSupplyProcurementRuntime's SHOPPING phase: sell first, ask for the supplier NPC, tick the visit until the
     * shopkeeper's transaction opens, then keep ticking while the scheduled shop steps run.
     */
    private void supplyTrip() {
        now = System.currentTimeMillis();
        AgentShopStateRuntime.setShopSellTrashPending(entry, true);
        assertTrue(AgentShopService.requestVisitAtNpc(entry, bot, NPC_ID, 0));
        while (AgentShopStateRuntime.shopVisitPending(entry) && !AgentShopStateRuntime.shopSequenceActive(entry)) {
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

    private static AgentEtcSaleCandidate saleOf(Item stack) {
        return new AgentEtcSaleCandidate(stack, stack.getQuantity(), new AgentItemDispositionProposal(
                stack.getItemId(), "loot", stack.getQuantity(), 0, stack.getQuantity(),
                AgentItemDispositionProposal.Disposition.SELL_SAFE_SURPLUS, 1, "", "", List.of()));
    }
}
