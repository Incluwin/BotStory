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
import java.util.HashSet;
import java.util.List;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * A supply trip sells the Agent's trash before it buys (AgentSupplyProcurementRuntime sets sellTrashPending), one sale
 * every SELL_TRASH_STEP_DELAY_MS. Live 2026-10-04, bots carrying a dozen ETC stacks and a tab of trash equips spent most
 * of SHOP_SEQUENCE_TIMEOUT_MS selling and gave up mid-purchase ("took too long at the shop, giving up"), which the
 * supply trip counts as a failed shop visit. The visit is replayed on a virtual clock with every purchase step at
 * SHOP_STEP_DELAY_MAX_MS, ticking the visit before each scheduled step the way the procurement loop does.
 *
 * <p>The same visit checks what the Agent says about the sale: the ETC step once added the pieces it sold to the
 * equip count, so 48 trash equips and twelve 100-piece stacks came out as "sold 1248 trash equips".
 */
class AgentShopServiceSaleBudgetTest {
    private static final int NPC_ID = 2_020_001;
    private static final int EL_NATH_POTION_SHOP_MAP = 211_000_102;
    private static final int RED = 2_000_000;
    private static final int RED_PRICE = 50;
    private static final int FIRST_ETC = 4_000_000;
    private static final int TRASH_SWORD = 1_302_000;
    private static final int TRASH_SHIELD = 1_092_000;
    private static final int APPROACH_TICK_MS = 100;

    private record Step(long atMs, long order, Runnable action) {}

    private final Point npcPosition = new Point(100, 50);
    private final ShopGateway gateway = mock(ShopGateway.class);
    private final InventoryGateway inventoryGateway = mock(InventoryGateway.class);
    private final PriorityQueue<Step> steps = new PriorityQueue<>(
            Comparator.comparingLong(Step::atMs).thenComparingLong(Step::order));
    private final List<String> spoken = new ArrayList<>();
    private final List<String> bought = new ArrayList<>();
    /** Item ids the shopkeeper refuses to buy back. */
    private final Set<Integer> unsellable = new HashSet<>();
    private final StatEffect redEffect = mock(StatEffect.class);
    private long now;
    private long order;
    /** How long past its due time each scheduled step runs (an unwatched Agent's mailbox). */
    private long lateMs;
    /** The scheduled steps that run before the rest never come. */
    private int stallAfterSteps = Integer.MAX_VALUE;
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
            if (unsellable.contains(bot.getInventory(type).getItem(slot).getItemId())) {
                return Shop.TransactionResult.INVALID;
            }
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
        AgentShopService.stepClock = () -> now;
    }

    @AfterEach
    void close() {
        AgentShopService.stepClock = System::currentTimeMillis;
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

    /**
     * Live 10-29 (250 Agents): an unwatched citizen's sell trip sold one piece every ~1.5 s, its steps running on its
     * mailbox well past their 0.5 s, so 72 sold pieces outran the budget of 1 s a planned sale and the purchases after
     * them gave up ("took too long at the shop", 23 trips). The time a step runs late is the scheduler's, not the
     * visit's.
     */
    @Test
    void aFullBagSoldAtAnUnwatchedAgentsPaceStillFinishesItsPurchases() {
        carry(0, 90);
        lateMs = 1_000L;

        supplyTrip();

        assertFalse(spoken.contains(AgentDialogueCatalog.shopSequenceTimeoutReply()), () -> "spoke " + spoken);
        assertEquals(AgentShopWorkflowPhase.COMPLETED, AgentShopStateRuntime.workflow(entry).phase());
        assertTrue(equips.list().isEmpty(), "every trash equip was sold");
        assertFalse(bought.isEmpty(), "the purchase steps ran after the sale");
    }

    @Test
    void aSaleWhoseStepsStopComingStillTimesOut() {
        carry(0, 10);
        stallAfterSteps = 4;

        supplyTrip();
        now += 200_000L;
        assertTrue(AgentShopService.visitOutOfTime(entry, now), "outran its own budget: its next tick ends it");
        AgentShopService.tickShopVisit(entry, bot, inventoryGateway, now);

        assertTrue(spoken.contains(AgentDialogueCatalog.shopSequenceTimeoutReply()), () -> "spoke " + spoken);
    }

    /**
     * A citizen's sell trip waited 5 minutes at most for its sale (CitizenSellTrips.VISIT_TIMEOUT_MS) and then went on
     * with its day mid-sale; it now waits as long as the visit's own budget allows (visitOutOfTime), and a full bag sold
     * at a slow scheduler's pace runs past those 5 minutes within that budget.
     */
    @Test
    void aFullBagSoldPastFiveMinutesAtASlowPaceIsNeverOutOfItsOwnTime() {
        carry(0, 90);
        lateMs = 3_000L;
        long startedAt = System.currentTimeMillis();
        now = startedAt;
        AgentShopStateRuntime.setShopSellTrashPending(entry, true);
        assertTrue(AgentShopService.requestVisitAtNpc(entry, bot, NPC_ID, 0));
        boolean everOutOfTime = false;
        while (AgentShopStateRuntime.shopVisitPending(entry) && !AgentShopStateRuntime.shopSequenceActive(entry)) {
            AgentShopService.tickShopVisit(entry, bot, inventoryGateway, now);
            now += APPROACH_TICK_MS;
        }
        while (!steps.isEmpty()) {
            Step step = steps.poll();
            now = Math.max(now, step.atMs() + lateMs);
            everOutOfTime |= AgentShopService.visitOutOfTime(entry, now);
            AgentShopService.tickShopVisit(entry, bot, inventoryGateway, now);
            step.action().run();
        }

        assertTrue(now - startedAt > 5 * 60_000L, "the sale ran " + (now - startedAt) + " ms");
        assertFalse(everOutOfTime, "within its own budget all along");
        assertTrue(equips.list().isEmpty(), "every piece sold");
        assertEquals(AgentShopWorkflowPhase.COMPLETED, AgentShopStateRuntime.workflow(entry).phase());
    }

    @Test
    void etcPiecesSoldAreNotCountedAsTrashEquips() {
        carry(12, 48);

        supplyTrip();

        assertEquals(List.of("sold 48 trash equips and 12 stacks of etc"), saleLines());
    }

    @Test
    void onlyEtcSoldIsToldAsStacksOfEtc() {
        carry(3, 0);

        supplyTrip();

        assertEquals(List.of("sold 3 stacks of etc"), saleLines());
    }

    @Test
    void unsoldEquipsAndEtcStacksAreCountedApart() {
        carry(3, 4);
        equips.addItem(Equip.restored(TRASH_SHIELD, (short) 0));
        unsellable.add(TRASH_SHIELD);
        unsellable.add(FIRST_ETC);
        unsellable.add(FIRST_ETC + 1);

        supplyTrip();

        assertEquals(List.of(
                "sold 4 trash equips and 1 stack of etc",
                "unable to sell 1 trash equip and 2 stacks of etc, tell me to drop them if you want them gone"),
                saleLines());
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

    /** What the Agent said about the sale itself. */
    private List<String> saleLines() {
        return spoken.stream()
                .filter(line -> line.startsWith("sold ") || line.startsWith("unable to sell "))
                .toList();
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
        int ran = 0;
        while (!steps.isEmpty() && ran++ < stallAfterSteps) {
            Step step = steps.poll();
            now = Math.max(now, step.atMs() + lateMs);
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
