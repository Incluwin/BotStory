package server.living.roster;

import client.Character;
import client.Job;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.WeaponType;
import constants.inventory.ItemConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;
import server.Shop;
import server.ShopItem;
import server.StatEffect;
import server.agents.capabilities.combat.AgentCombatAmmoCounter;
import server.agents.capabilities.inventory.AgentUseItemClassificationPolicy;
import server.agents.capabilities.shop.AgentShopProvisioning;
import server.agents.capabilities.supplies.AgentPotionInventoryPolicy;
import server.agents.capabilities.supplies.AgentPotionService;
import server.agents.capabilities.supplies.AgentSupplyReadiness;
import server.agents.capabilities.supplies.AgentSupplyRecoveryPolicy;
import server.agents.capabilities.supplies.AgentWalletReserve;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.integration.AgentShopGatewayRuntime;
import server.agents.integration.InventoryGateway;
import server.agents.integration.ShopGateway;
import server.living.roster.CompanionBossAllies.State;
import testutil.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static server.living.roster.BossAllyHarness.SLEEPYWOOD_CAMP;
import static server.living.roster.BossAllyHarness.isLine;

/**
 * A bench ally's provisioning on arrival (P7 Part B, R5; CompanionBossAllies.arrive). Allies come down by recall and
 * never pass Ant Tunnel Park, so an ally short of the Easy Balrog standard (its potions, and the projectiles for its
 * weapon) buys what it lacks before it says it is here: from its own purse less its wallet reserve, at the 24 Hr Mobile
 * Store's (1061001) prices and with the picks a readiness trip's counter makes there (the same shop code path, with no
 * walk), on toward BUY_TO_PERCENT of the standard. Still short, it says the lowpots line, and a low-ammo line when its
 * projectiles fall short. A stocked ally buys nothing; with COMPANION_BOSS_ALLY_TYPES not listing the boss nothing is
 * bought and it says what it said before. The store's stock here is its potions and projectiles as
 * 102-shopitems-data.sql lists them (its food is left out); the shop gateway, item effects and stack sizes are stubs.
 */
class CompanionBossAlliesProvisionTest {
    private static final int ORRIN = 911;
    /** Ant Tunnel Park's 24 Hr Mobile Store. */
    private static final int STORE = 1_061_001;
    private static final int UNAGI = 2_022_003;
    private static final int PURE_WATER = 2_022_000;
    private static final int BULLET = 2_330_000;
    private static final int SUBI = 2_070_000;
    private static final int BRONZE_BOLT = 2_061_001;
    private static final int BRONZE_ARROW = 2_060_001;
    private static final int BOLT = 2_061_000;
    private static final int ARROW = 2_060_000;
    private static final int SWORD = 1_302_020;
    /** The store's items in its slot order and prices (102-shopitems-data.sql:636-646). */
    private static final List<int[]> STOCK = List.of(new int[]{BULLET, 600}, new int[]{SUBI, 500},
            new int[]{BRONZE_BOLT, 2_800}, new int[]{BRONZE_ARROW, 2_800}, new int[]{BOLT, 2},
            new int[]{ARROW, 2}, new int[]{PURE_WATER, 1_650}, new int[]{UNAGI, 1_100});
    private static final Map<Integer, Integer> STACKS = Map.of(UNAGI, 100, PURE_WATER, 100, BULLET, 800,
            SUBI, 800, BOLT, 2_000, ARROW, 2_000, BRONZE_BOLT, 2_000, BRONZE_ARROW, 2_000);
    private static final Map<WeaponType, Integer> WEAPONS = Map.of(WeaponType.SWORD1H, SWORD,
            WeaponType.BOW, 1_452_002, WeaponType.CROSSBOW, 1_462_001, WeaponType.CLAW, 1_472_000,
            WeaponType.GUN, 1_492_000);
    private static final AgentSupplyReadiness.Activity EASY_BALROG =
            AgentSupplyReadiness.Activity.partyQuest("easy_balrog");
    /** Glibber on Ice Valley II, Zakum's approach store (P8). */
    private static final int GLIBBER = 2_030_009;
    private static final int ORANGE_POTION = 2_000_001;
    private static final int WHITE_POTION = 2_000_002;
    private static final int BLUE_POTION = 2_000_003;
    private static final int MANA_ELIXIR = 2_000_006;
    /**
     * Glibber's projectiles and potions in his slot order and prices (102-shopitems-data.sql:1106-1134), his food
     * and dews left out.
     */
    private static final List<int[]> GLIBBERS_STOCK = List.of(new int[]{BULLET, 600}, new int[]{SUBI, 500},
            new int[]{BOLT, 1}, new int[]{ARROW, 1}, new int[]{PURE_WATER, 1_716}, new int[]{UNAGI, 1_144},
            new int[]{MANA_ELIXIR, 644}, new int[]{BLUE_POTION, 208}, new int[]{WHITE_POTION, 332},
            new int[]{ORANGE_POTION, 166});

    private final BossAllyHarness h = new BossAllyHarness();
    private final Map<Integer, Integer> prices = new HashMap<>();
    private final Map<Integer, StatEffect> effects = new HashMap<>();
    /** Each purchase, as "<quantity or set> x <item>". */
    private final List<String> transactions = new ArrayList<>();
    /** How many map-chat lines were said when each purchase was made. */
    private final List<Integer> linesAtPurchase = new ArrayList<>();
    /** The NPCs whose shop was looked up. */
    private final List<Integer> asked = new ArrayList<>();
    private final ShopGateway gateway = mock(ShopGateway.class);
    private final InventoryGateway inventory = mock(InventoryGateway.class);
    private final List<MockedStatic<?>> statics = new ArrayList<>();
    private Character orrin;
    private Inventory use;
    private int weaponId = SWORD;
    private int purse = 1_000_000;

    @BeforeEach
    void orrinIsOnHisWayDownAndTheStoreIsStocked() {
        h.benched("orrin", "Orrin", ORRIN, SLEEPYWOOD_CAMP);
        h.game.roster();
        h.allies.send(h.game.ann, "easy_balrog", List.of("orrin"), h.now);
        assertEquals(State.TRAVELLING, h.allies.trip("orrin").state);
        h.ports.liveStore = true;
        AgentWalletReserve.install(null); // an ally leads itself: it keeps the journey reserve

        orrin = h.game.bots.get(ORRIN);
        use = new Inventory(orrin, InventoryType.USE, (byte) 24);
        Inventory equipped = mock(Inventory.class);
        when(equipped.getItem((short) -11)).thenAnswer(inv -> new Item(weaponId, (short) -11, (short) 1));
        when(orrin.getInventory(InventoryType.USE)).thenAnswer(inv -> use);
        when(orrin.getInventory(InventoryType.EQUIPPED)).thenReturn(equipped);
        when(orrin.getMeso()).thenAnswer(inv -> purse);
        when(orrin.getCurrentMaxHp()).thenReturn(4_000);
        when(orrin.getCurrentMaxMp()).thenReturn(1_500);

        effects.put(UNAGI, effect(1_000, 0));
        effects.put(PURE_WATER, effect(0, 800));
        Shop store = mock(Shop.class);
        List<ShopItem> items = new ArrayList<>();
        for (int[] row : STOCK) {
            ShopItem item = mock(ShopItem.class);
            when(item.getItemId()).thenReturn(row[0]);
            when(item.getPrice()).thenReturn(row[1]);
            items.add(item);
            prices.put(row[0], row[1]);
        }
        when(store.getItems()).thenReturn(items);
        when(gateway.findForNpc(anyInt())).thenAnswer(inv -> {
            int npcId = inv.getArgument(0);
            asked.add(npcId);
            return npcId == STORE ? store : null;
        });
        when(gateway.buy(any(), any(), anyShort(), anyInt(), anyShort())).thenAnswer(inv -> buy(inv.getArgument(3),
                inv.getArgument(4)));
        when(inventory.getSlotMax(any(Character.class), anyInt()))
                .thenAnswer(inv -> (short) (int) STACKS.getOrDefault((int) inv.getArgument(1), 100));
        when(inventory.getWeaponType(anyInt())).thenAnswer(inv -> {
            int itemId = inv.getArgument(0);
            return WEAPONS.entrySet().stream().filter(entry -> entry.getValue() == itemId).map(Map.Entry::getKey)
                    .findFirst().orElse(WeaponType.NOT_A_WEAPON);
        });

        MockedStatic<AgentShopGatewayRuntime> shops = open(AgentShopGatewayRuntime.class);
        shops.when(AgentShopGatewayRuntime::shop).thenReturn(gateway);
        MockedStatic<AgentInventoryGatewayRuntime> inventories = open(AgentInventoryGatewayRuntime.class);
        inventories.when(AgentInventoryGatewayRuntime::inventory).thenReturn(inventory);
        MockedStatic<AgentUseItemClassificationPolicy> classification = open(AgentUseItemClassificationPolicy.class);
        classification.when(() -> AgentUseItemClassificationPolicy.isRecoveryPotion(anyInt()))
                .thenAnswer(inv -> effects.containsKey((int) inv.getArgument(0)));
        classification.when(() -> AgentUseItemClassificationPolicy.isRecoveryPotion(any(StatEffect.class)))
                .thenAnswer(inv -> inv.getArgument(0) != null);
        classification.when(() -> AgentUseItemClassificationPolicy.itemEffect(anyInt()))
                .thenAnswer(inv -> effects.get((int) inv.getArgument(0)));
        MockedStatic<AgentPotionService> potions = open(AgentPotionService.class);
        potions.when(() -> AgentPotionService.countPotions(orrin))
                .thenAnswer(inv -> AgentPotionInventoryPolicy.countRecoveryPotions(use.list(), effects::get));
    }

    @AfterEach
    void close() {
        for (int i = statics.size() - 1; i >= 0; i--) {
            statics.get(i).close();
        }
        h.close();
    }

    @Test
    void aShortAllyBuysTowardTheStandardAtTheStoresPricesAndPaysBeforeItSaysItIsHere() {
        carry(10, 10);
        AgentSupplyReadiness.Standard standard = AgentSupplyReadiness.standard(EASY_BALROG);

        h.game.runDueTimers();

        int[] after = potions();
        assertTrue(after[0] >= standard.hp() && after[1] >= standard.mp(), after[0] + "/" + after[1]);
        assertTrue(after[0] <= AgentSupplyReadiness.buyTarget(EASY_BALROG, orrin, true)
                && after[1] <= AgentSupplyReadiness.buyTarget(EASY_BALROG, orrin, false), after[0] + "/" + after[1]);
        assertTrue(after[0] > standard.hp() || after[1] > standard.mp(),
                "on past the standard toward BUY_TO_PERCENT of it: " + transactions);
        assertEquals(1_000_000 - (after[0] - 10) * 1_100 - (after[1] - 10) * 1_650, purse,
                "it paid the store's prices from its own purse");
        assertEquals(List.of(STORE), asked, "the 24 Hr Mobile Store on the way down");
        assertTrue(transactions.stream().allMatch(line -> line.endsWith(" x " + UNAGI)
                || line.endsWith(" x " + PURE_WATER)), "the picks for its bars: " + transactions);
        assertTrue(linesAtPurchase.stream().allMatch(lines -> lines == 0), "bought before its first line");
        assertEquals(List.of("provision Orrin easy_balrog at " + STORE), h.ports.provisions);
        assertEquals(1, h.mapChat.size(), "stocked now: only that it is here " + h.mapChat);
        assertTrue(isLine(BossRunLines.ALLY_HERE, null, said(0)), h.mapChat.toString());
    }

    @Test
    void anAllyThatCannotAffordTheStandardBuysWhatItCanKeepsItsReserveAndSaysItIsLowOnPots() {
        carry(10, 10);
        int reserve = AgentSupplyRecoveryPolicy.minimumWalletReserve(orrin);
        assertTrue(reserve > 0, "the fixture: an ally keeps a journey reserve");
        purse = reserve + 30_000;
        AgentSupplyReadiness.Standard standard = AgentSupplyReadiness.standard(EASY_BALROG);

        h.game.runDueTimers();

        assertFalse(transactions.isEmpty(), "it buys what it can");
        assertTrue(purse >= reserve, "never into its reserve: " + purse);
        assertTrue(purse - reserve < 1_100, "spent all it could: " + purse + " " + transactions);
        int[] after = potions();
        assertTrue(after[0] < standard.hp() || after[1] < standard.mp(), after[0] + "/" + after[1]);
        assertEquals(2, h.mapChat.size(), h.mapChat.toString());
        assertTrue(isLine(BossRunLines.ALLY_HERE, null, said(0)), h.mapChat.toString());
        assertTrue(isLine(BossRunLines.ALLY_LOW_POTS, null, said(1)), h.mapChat.toString());
    }

    @Test
    void aStockedAllyBuysNothing() {
        AgentSupplyReadiness.Standard standard = AgentSupplyReadiness.standard(EASY_BALROG);
        carry(standard.hp(), standard.mp());

        h.game.runDueTimers();

        assertEquals(List.of(), transactions);
        assertEquals(1_000_000, purse);
        assertEquals(1, h.mapChat.size(), h.mapChat.toString());
        assertTrue(isLine(BossRunLines.ALLY_HERE, null, said(0)), h.mapChat.toString());
    }

    @ParameterizedTest
    @EnumSource(value = WeaponType.class, names = {"BOW", "CROSSBOW", "CLAW", "GUN"})
    void aRangedAllyBuysTheProjectilesForItsWeapon(WeaponType weapon) {
        ranged(weapon);
        AgentSupplyReadiness.Standard standard = AgentSupplyReadiness.standard(EASY_BALROG);
        carry(standard.hp(), standard.mp()); // its potions are fine: only its projectiles are short

        h.game.runDueTimers();

        int expected = switch (weapon) {
            case BOW -> ARROW; // the cheapest arrows for a bow, not the bronze ones
            case CROSSBOW -> BOLT;
            case CLAW -> SUBI;
            default -> BULLET;
        };
        assertTrue(transactions.stream().anyMatch(line -> line.endsWith(" x " + expected)), transactions.toString());
        assertTrue(AgentCombatAmmoCounter.countCarried(orrin, weapon) >= AgentSupplyReadiness.ammoStandard(EASY_BALROG),
                AgentCombatAmmoCounter.countCarried(orrin, weapon) + " " + transactions);
        assertEquals(1, h.mapChat.size(), h.mapChat.toString());
        assertTrue(isLine(BossRunLines.ALLY_HERE, null, said(0)), h.mapChat.toString());
    }

    @Test
    void aRangedAllyThatCannotAffordItsProjectilesSaysItWillMakeThemLast() {
        ranged(WeaponType.BOW);
        AgentSupplyReadiness.Standard standard = AgentSupplyReadiness.standard(EASY_BALROG);
        carry(standard.hp(), standard.mp());
        purse = AgentSupplyRecoveryPolicy.minimumWalletReserve(orrin); // nothing to spend

        h.game.runDueTimers();

        assertEquals(List.of(), transactions);
        assertEquals(2, h.mapChat.size(), h.mapChat.toString());
        assertTrue(isLine(BossRunLines.ALLY_HERE, null, said(0)), h.mapChat.toString());
        assertTrue(BossRunLines.GENERIC.get(BossRunLines.ALLY_LOW_AMMO).stream()
                .map(wording -> wording.replace("{ammo}", "arrows")).anyMatch(said(1)::equals), h.mapChat.toString());
    }

    @Test
    void withTheAllySwitchOffNothingIsBoughtAndItSaysWhatItSaidBefore() {
        ranged(WeaponType.BOW);
        carry(10, 10);
        h.game.config.COMPANION_BOSS_ALLY_TYPES = ""; // switched off while it was on its way

        h.game.runDueTimers();

        assertEquals(List.of(), h.ports.provisions);
        assertEquals(List.of(), asked);
        assertEquals(List.of(), transactions);
        assertEquals(1_000_000, purse);
        assertEquals(State.HERE, h.allies.trip("orrin").state, "it still came");
        assertEquals(2, h.mapChat.size(), "no low-ammo line: " + h.mapChat);
        assertTrue(isLine(BossRunLines.ALLY_HERE, null, said(0)), h.mapChat.toString());
        assertTrue(isLine(BossRunLines.ALLY_LOW_POTS, null, said(1)), h.mapChat.toString());
    }

    @Test
    void aZakumAllyBuysFiveHundredAndFourHundredPotionsAndFourThousandBulletsAtGlibbersInOneGo() {
        // P8 (user decision 2026-10-08): the zakum standard is past the carry and per-visit limits; an ally that
        // never walks past Glibber reaches it from its own purse as a party companion's trip does at his counter
        AgentSupplyReadiness.Activity zakum = AgentSupplyReadiness.Activity.partyQuest("zakum");
        assertEquals(GLIBBER, BossEntrance.ZAKUM.approachStoreNpcId(), "the store an ally to Zakum buys at");
        Shop glibber = glibbersStore();
        ranged(WeaponType.GUN); // a Lv160 Corsair
        when(orrin.getCurrentMaxHp()).thenReturn(12_000);
        when(orrin.getCurrentMaxMp()).thenReturn(5_000);
        purse = 2_000_000;

        AgentShopProvisioning.Provisioned bought = CompanionBossAllies.Ports.LIVE.provision(orrin, "zakum",
                BossEntrance.ZAKUM.approachStoreNpcId());

        assertArrayEquals(new int[]{500, 400}, potions(), transactions.toString());
        assertEquals(500, bought.hpPotions());
        assertEquals(400, bought.mpPotions());
        int bullets = AgentCombatAmmoCounter.countCarried(orrin, WeaponType.GUN);
        assertTrue(bullets >= AgentSupplyReadiness.ammoStandard(zakum), bullets + " " + transactions);
        assertEquals(4_000, AgentSupplyReadiness.ammoStandard(zakum));
        assertTrue(AgentSupplyReadiness.shortfall(orrin, zakum).none(), "nothing short of the zakum standard");
        assertTrue(use.getNumFreeSlot() >= 1, "the run's slot stays free: " + use.getNumFreeSlot());
        assertEquals(List.of(GLIBBER), asked);
        assertTrue(transactions.stream().noneMatch(line -> line.endsWith(" x " + SUBI) || line.endsWith(" x " + BOLT)
                || line.endsWith(" x " + ARROW)), "bullets for a gun: " + transactions);
        assertEquals(2_000_000 - purse, bought.mesosBefore() - bought.mesosAfter(), "paid from its own purse");
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** Glibber's store behind the shop gateway (every other NPC: none), with what his potions restore. */
    private Shop glibbersStore() {
        effects.put(ORANGE_POTION, effect(150, 0));
        effects.put(WHITE_POTION, effect(300, 0));
        effects.put(BLUE_POTION, effect(0, 100));
        effects.put(MANA_ELIXIR, effect(0, 300));
        Shop store = mock(Shop.class);
        List<ShopItem> items = new ArrayList<>();
        for (int[] row : GLIBBERS_STOCK) {
            ShopItem item = mock(ShopItem.class);
            when(item.getItemId()).thenReturn(row[0]);
            when(item.getPrice()).thenReturn(row[1]);
            items.add(item);
            prices.put(row[0], row[1]);
        }
        when(store.getItems()).thenReturn(items);
        doAnswer(inv -> {
            int npcId = inv.getArgument(0);
            asked.add(npcId);
            return npcId == GLIBBER ? store : null;
        }).when(gateway).findForNpc(anyInt());
        return store;
    }

    /** The store's sale: a star or bullet set is one full stack; potions and arrows go onto stacks, then new ones. */
    private Shop.TransactionResult buy(int itemId, short quantity) {
        int perSlot = STACKS.getOrDefault(itemId, 100);
        boolean set = ItemConstants.isRechargeable(itemId);
        int count = set ? perSlot : quantity;
        int cost = set ? prices.get(itemId) : prices.get(itemId) * quantity;
        if (purse < cost) {
            return Shop.TransactionResult.NOT_ENOUGH_MESO;
        }
        int room = use.getNumFreeSlot() * perSlot;
        if (!set) {
            for (Item stack : use.listById(itemId)) {
                room += perSlot - stack.getQuantity();
            }
        }
        if (count > room || set && use.getNumFreeSlot() < 1) {
            return Shop.TransactionResult.NO_SPACE;
        }
        purse -= cost;
        transactions.add((set ? "set" : Integer.toString(quantity)) + " x " + itemId);
        linesAtPurchase.add(h.mapChat.size());
        int left = count;
        if (!set) {
            for (Item stack : use.listById(itemId)) {
                int onto = Math.min(left, perSlot - stack.getQuantity());
                stack.setQuantity((short) (stack.getQuantity() + onto));
                left -= onto;
            }
        }
        for (; left > 0; left -= perSlot) {
            use.addItem(Items.itemWithQuantity(itemId, Math.min(left, perSlot)));
        }
        return Shop.TransactionResult.SUCCESS;
    }

    /** Orrin carries {@code unagi} Unagi and {@code pureWater} Pure Water, in full stacks. */
    private void carry(int unagi, int pureWater) {
        stacks(UNAGI, unagi);
        stacks(PURE_WATER, pureWater);
    }

    private void stacks(int itemId, int count) {
        for (int left = count; left > 0; left -= STACKS.get(itemId)) {
            use.addItem(Items.itemWithQuantity(itemId, Math.min(left, STACKS.get(itemId))));
        }
    }

    /** Orrin is a third-job archer, thief or pirate with {@code weapon} and nothing to shoot. */
    private void ranged(WeaponType weapon) {
        weaponId = WEAPONS.get(weapon);
        when(orrin.getJob()).thenReturn(switch (weapon) {
            case BOW -> Job.RANGER;
            case CROSSBOW -> Job.SNIPER;
            case CLAW -> Job.HERMIT;
            default -> Job.OUTLAW;
        });
    }

    private int[] potions() {
        return AgentPotionService.countPotions(orrin);
    }

    /** Map-chat line {@code index} without "Orrin: ". */
    private String said(int index) {
        return h.mapChat.get(index).substring("Orrin: ".length());
    }

    private <T> MockedStatic<T> open(Class<T> type) {
        MockedStatic<T> mocked = mockStatic(type);
        statics.add(mocked);
        return mocked;
    }

    private static StatEffect effect(int hp, int mp) {
        StatEffect effect = mock(StatEffect.class);
        when(effect.getHp()).thenReturn((short) hp);
        when(effect.getMp()).thenReturn((short) mp);
        return effect;
    }
}
