package server.agents.capabilities.shop;

import client.BuffStat;
import client.Character;
import client.Job;
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
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.integration.AgentShopGatewayRuntime;
import server.agents.integration.InventoryGateway;
import server.agents.integration.ShopGateway;
import server.agents.runtime.AgentRuntimeEntry;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * A potion restock at a full Use tab (2026-10-13): before it buys, the visit sells a stack of ammo for a weapon the
 * Agent does not wield for each Use slot its potions need, and nothing else. Live: the Lv67 Spearman Demon744 carried 8
 * Unagi (Perion sells none) in the last free slot of a Use tab full of looted arrows and scrolls, so every visit to
 * Perion's potion shop (NPC 1021100) was refused the White Potions (NO_SPACE), logged "HP_POTION 8 -> 8", and the trip
 * was given up and taken again every ten minutes while its potions ran out.
 */
class AgentShopServiceUseRoomTest {
    private static final int RED = 2_000_000;
    private static final int ORANGE_POTION = 2_000_001;
    private static final int WHITE = 2_000_002;
    private static final int BLUE = 2_000_003;
    private static final int MANA_ELIXIR = 2_000_006;
    private static final int UNAGI = 2_022_003;
    private static final int ARROW = 2_060_000;
    private static final int CROSSBOW_ARROW = 2_061_000;
    private static final int SUBI = 2_070_000;
    private static final int HELMET_SCROLL = 2_040_001;
    private static final int GLOVE_SCROLL = 2_040_804;
    private static final int PERION_POTION_SHOP = 102_000_002;
    /** Perion's potions and arrows (102-shopitems-data.sql, NPC 1021100), its food, pills and scrolls left out. */
    private static final List<Integer> PERION = List.of(RED, ORANGE_POTION, WHITE, BLUE, MANA_ELIXIR, ARROW,
            CROSSBOW_ARROW);

    private final Map<Integer, StatEffect> effects = new HashMap<>();
    private final Map<Integer, Integer> prices = new HashMap<>();
    private final Map<Integer, Integer> stackSizes = new HashMap<>();
    private final List<String> transactions = new ArrayList<>();
    private final ShopGateway gateway = mock(ShopGateway.class);
    private final InventoryGateway inventory = mock(InventoryGateway.class);
    private int purse = 23_389_064;
    private Character bot;
    private Inventory use;
    private MockedStatic<AgentUseItemClassificationPolicy> classification;
    private MockedStatic<AgentPotionService> potions;
    private MockedStatic<AgentShopGatewayRuntime> shops;
    private MockedStatic<AgentInventoryGatewayRuntime> inventories;
    private MockedStatic<AgentShopRuntime> speech;

    @BeforeEach
    void demon744AtPerionsPotionShop() {
        effects.put(RED, effect(50, 0));
        effects.put(ORANGE_POTION, effect(150, 0));
        effects.put(WHITE, effect(300, 0));
        effects.put(BLUE, effect(0, 100));
        effects.put(MANA_ELIXIR, effect(0, 300));
        effects.put(UNAGI, effect(1_000, 0));
        prices.putAll(Map.of(RED, 50, ORANGE_POTION, 160, WHITE, 320, BLUE, 200, MANA_ELIXIR, 620, ARROW, 1,
                CROSSBOW_ARROW, 1));
        stackSizes.putAll(Map.of(ARROW, 2_000, CROSSBOW_ARROW, 2_000, SUBI, 500));
        when(inventory.getSlotMax(any(Character.class), anyInt()))
                .thenAnswer(inv -> (short) (int) stackSizes.getOrDefault((int) inv.getArgument(1), 100));

        bot = mock(Character.class);
        when(bot.getId()).thenReturn(527);
        when(bot.getName()).thenReturn("Demon744");
        when(bot.getJob()).thenReturn(Job.SPEARMAN);
        when(bot.getCurrentMaxHp()).thenReturn(6_096); // its reserve target: 10 bars, 60960 HP
        when(bot.getCurrentMaxMp()).thenReturn(600);
        when(bot.getMeso()).thenAnswer(inv -> purse);
        when(bot.getMapId()).thenReturn(PERION_POTION_SHOP);
        when(bot.getBuffedValue(any(BuffStat.class))).thenReturn(null);

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
        inventories = mockStatic(AgentInventoryGatewayRuntime.class);
        inventories.when(AgentInventoryGatewayRuntime::inventory).thenReturn(inventory);
        speech = mockStatic(AgentShopRuntime.class);

        // as the live shop: a purchase fills the stacks of it carried, then takes free slots, or is refused whole
        when(gateway.buy(any(), any(), anyShort(), anyInt(), anyShort())).thenAnswer(inv -> {
            int itemId = inv.getArgument(3);
            int quantity = (short) inv.getArgument(4);
            int perSlot = stackSizes.getOrDefault(itemId, 100);
            int room = use.getNumFreeSlot() * perSlot;
            for (Item stack : use.listById(itemId)) {
                room += perSlot - stack.getQuantity();
            }
            if (quantity > room) {
                return Shop.TransactionResult.NO_SPACE;
            }
            purse -= prices.get(itemId) * quantity;
            transactions.add(quantity + " x " + itemId);
            int left = quantity;
            for (Item stack : use.listById(itemId)) {
                int onto = Math.min(left, perSlot - stack.getQuantity());
                stack.setQuantity((short) (stack.getQuantity() + onto));
                left -= onto;
            }
            for (; left > 0; left -= perSlot) {
                use.addItem(stack(itemId, Math.min(left, perSlot)));
            }
            return Shop.TransactionResult.SUCCESS;
        });
        when(gateway.sell(any(), any(), any(), anyShort(), anyShort())).thenAnswer(inv -> {
            short slot = inv.getArgument(3);
            short quantity = inv.getArgument(4);
            Item stack = use.getItem(slot);
            transactions.add("sold " + quantity + " x " + stack.getItemId());
            use.removeItem(slot, quantity, false);
            return Shop.TransactionResult.SUCCESS;
        });
    }

    @AfterEach
    void close() {
        speech.close();
        inventories.close();
        shops.close();
        potions.close();
        classification.close();
    }

    @Test
    void aSpearmanWhoseUseTabIsFullSellsOneStackOfArrowsAndBuysItsWhitePotions() {
        useTab(6, List.of(stack(UNAGI, 8), stack(BLUE, 100),
                stack(ARROW, 2_000), stack(CROSSBOW_ARROW, 2_000),
                stack(HELMET_SCROLL, 1), stack(GLOVE_SCROLL, 1)));

        visit(WeaponType.SPEAR_STAB);

        assertEquals(List.of("sold 2000 x " + ARROW, "100 x " + WHITE), transactions,
                "one slot for its HP potions: the first stack of arrows it cannot shoot, nothing more");
        assertEquals(108, AgentPotionService.countPotions(bot)[0]);
        assertEquals(List.of(UNAGI, BLUE, WHITE, CROSSBOW_ARROW, HELMET_SCROLL, GLOVE_SCROLL), itemIds(),
                "its crossbow arrows and its scrolls stay");
    }

    @Test
    void withAFreeUseSlotItSellsNoArrows() {
        useTab(7, List.of(stack(UNAGI, 8), stack(BLUE, 100),
                stack(ARROW, 2_000), stack(CROSSBOW_ARROW, 2_000),
                stack(HELMET_SCROLL, 1), stack(GLOVE_SCROLL, 1)));

        visit(WeaponType.SPEAR_STAB);

        assertEquals(List.of("100 x " + WHITE), transactions);
    }

    @Test
    void anAssassinNeverSellsItsOwnStarsForRoom() {
        when(bot.getJob()).thenReturn(Job.ASSASSIN);
        useTab(6, List.of(stack(UNAGI, 8), stack(BLUE, 100),
                stack(SUBI, 500), stack(SUBI, 500),
                stack(HELMET_SCROLL, 1), stack(GLOVE_SCROLL, 1)));

        visit(WeaponType.CLAW);

        assertEquals(List.of(), transactions, "nothing it does not use to sell: the shop refuses the potions as today");
    }

    private void useTab(int slots, List<Item> items) {
        use = new Inventory(bot, InventoryType.USE, (byte) slots);
        items.forEach(use::addItem);
        when(bot.getInventory(InventoryType.USE)).thenReturn(use);
    }

    /** A Use stack that is tradeable without asking the item data (ItemInformationProvider needs the database). */
    private static Item stack(int itemId, int quantity) {
        return new Item(itemId, (short) 0, (short) quantity) {
            @Override
            public boolean isUntradeable() {
                return false;
            }
        };
    }

    private List<Integer> itemIds() {
        List<Integer> ids = new ArrayList<>();
        for (short slot = 1; slot <= use.getSlotLimit(); slot++) {
            Item item = use.getItem(slot);
            if (item != null) {
                ids.add(item.getItemId());
            }
        }
        return ids;
    }

    private void visit(WeaponType wt) {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(bot, null, null);
        List<AgentShopPurchaseAction<AgentRuntimeEntry>> actions =
                AgentShopService.purchaseActions(entry, bot, inventory, wt);
        AgentShopPurchaseSequence<AgentRuntimeEntry> sequence = new AgentShopPurchaseSequence<>(entry, bot, inventory,
                new Point(), actions, new ArrayList<>(), null);
        Shop shop = shop(PERION);
        for (AgentShopPurchaseAction<AgentRuntimeEntry> action : actions) {
            sequence = action.run(sequence, shop);
        }
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
