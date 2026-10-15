package server.agents.capabilities.shop;

import client.BuffStat;
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
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import server.Shop;
import server.ShopItem;
import server.StatEffect;
import server.agents.capabilities.combat.AgentCombatAmmoCounter;
import server.agents.capabilities.inventory.AgentUseItemClassificationPolicy;
import server.agents.capabilities.supplies.AgentPotionInventoryPolicy;
import server.agents.capabilities.supplies.AgentPotionService;
import server.agents.capabilities.supplies.AgentSupplyReadiness;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.integration.AgentShopGatewayRuntime;
import server.agents.integration.InventoryGateway;
import server.agents.integration.ShopGateway;
import server.agents.progression.AgentPotionSupplierCatalog;
import server.agents.runtime.AgentRuntimeEntry;
import testutil.Items;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * A readiness trip's ammo step at the counter (AgentShopService.buyReadinessAmmo, 2026-10-07): after the potions, a
 * companion short of its ammo standard refills the star or bullet stacks it owns and buys sets of the shop's cheapest
 * (or arrows) up to the trip's ammo target, at any supplier, never into the last free Use slot. On Victoria the old
 * top-up still runs first; elsewhere it does not, so a bow outside Victoria never buys toward 5000 arrows. The step is
 * logged, not added to the spoken "bought" line, and readinessAmmoPlan, what the trip's checks price, is exactly
 * what the counter does. Live: Kestrel reached LPQ stage 7 with no stars after spending 800 in one EPQ golem fight.
 */
class AgentShopServiceReadinessAmmoTest {
    private static final int RED = 2_000_000;
    private static final int WHITE = 2_000_002;
    private static final int BLUE = 2_000_003;
    private static final int SUBI = 2_070_000;
    private static final int ILBI = 2_070_006;
    private static final int BULLET = 2_330_000;
    private static final int ARROW = 2_060_000;
    /** Showa Town's only arrow for bows, at 40 mesos each. */
    private static final int SHOWA_ARROW = 2_060_003;
    private static final int KERNING_PHARMACY = 103_000_002;
    private static final int HENESYS_STORE = 100_000_102;
    /** Altaire Camp: Mayoren, the potion supplier of Ellin's EPQ desk. */
    private static final int MAYOREN = 300_000_000;
    private static final int SHOWA = 801_000_300;
    private static final int NEW_LEAF_CITY = 600_000_000;
    private static final AgentSupplyReadiness.Activity EPQ = AgentSupplyReadiness.Activity.partyQuest("epq");
    private static final AgentSupplyReadiness.Activity LPQ = AgentSupplyReadiness.Activity.partyQuest("lpq");
    private static final AgentSupplyReadiness.Activity KPQ = AgentSupplyReadiness.Activity.partyQuest("kpq");

    private final Map<Integer, StatEffect> effects = new HashMap<>();
    private final Map<Integer, Integer> prices = new HashMap<>();
    private final Map<Integer, Integer> stackSizes = new HashMap<>();
    private final Map<Integer, Double> unitPrices = new HashMap<>();
    private final List<String> transactions = new ArrayList<>();
    private final ShopGateway gateway = mock(ShopGateway.class);
    private final InventoryGateway inventory = mock(InventoryGateway.class);
    private final Map<BuffStat, Integer> buffs = new HashMap<>();
    private int purse = 300_000;
    private int ammoSpent;
    private int mapId = MAYOREN;
    private AgentSupplyReadiness.Activity trip;
    private Character bot;
    private Inventory use;
    private MockedStatic<AgentUseItemClassificationPolicy> classification;
    private MockedStatic<AgentPotionService> potions;
    private MockedStatic<AgentShopGatewayRuntime> shops;
    private MockedStatic<AgentInventoryGatewayRuntime> inventories;
    private MockedStatic<AgentShopRuntime> speech;
    private MockedStatic<AgentSupplyReadiness> readiness;

    @BeforeEach
    void anAssassinAtMayoren() {
        effects.put(RED, effect(50, 0));
        effects.put(WHITE, effect(300, 0));
        effects.put(BLUE, effect(0, 100));
        prices.putAll(Map.of(RED, 50, WHITE, 320, BLUE, 200, SUBI, 500, BULLET, 600, ARROW, 1, SHOWA_ARROW, 40));
        stackSizes.putAll(Map.of(RED, 100, WHITE, 100, BLUE, 100, SUBI, 500, ILBI, 800, BULLET, 800, ARROW, 2_000,
                SHOWA_ARROW, 2_000));
        unitPrices.putAll(Map.of(SUBI, 0.3d, ILBI, 0.8d, BULLET, 0.5d));
        when(inventory.getSlotMax(any(Character.class), anyInt()))
                .thenAnswer(inv -> (short) (int) stackSizes.getOrDefault((int) inv.getArgument(1), 100));
        when(inventory.getProjectileWeaponAttack(anyInt()))
                .thenAnswer(inv -> Map.of(SUBI, 15, ILBI, 27, BULLET, 10).getOrDefault((int) inv.getArgument(0), 0));

        bot = mock(Character.class);
        when(bot.getId()).thenReturn(88);
        when(bot.getName()).thenReturn("Kestrel");
        when(bot.getJob()).thenReturn(Job.ASSASSIN);
        when(bot.getCurrentMaxHp()).thenReturn(1_060);
        when(bot.getCurrentMaxMp()).thenReturn(500);
        when(bot.getMeso()).thenAnswer(inv -> purse);
        when(bot.getMapId()).thenAnswer(inv -> mapId);
        when(bot.getBuffedValue(any(BuffStat.class))).thenAnswer(inv -> buffs.get((BuffStat) inv.getArgument(0)));
        bag(24);

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
        readiness = mockStatic(AgentSupplyReadiness.class, Mockito.CALLS_REAL_METHODS);
        readiness.when(() -> AgentSupplyReadiness.standing(anyInt())).thenAnswer(inv -> trip);
        readiness.when(() -> AgentSupplyReadiness.tripActivity(any())).thenAnswer(inv -> trip);
        readiness.when(() -> AgentSupplyReadiness.shoppingActivity(any(), any())).thenAnswer(inv -> trip);

        when(gateway.buy(any(), any(), anyShort(), anyInt(), anyShort())).thenAnswer(inv -> {
            int itemId = inv.getArgument(3);
            short quantity = inv.getArgument(4);
            int perSlot = stackSizes.getOrDefault(itemId, 100);
            boolean set = ItemConstants.isRechargeable(itemId); // a set is one full stack, whatever the quantity
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
            if (ammo(itemId)) {
                ammoSpent += cost;
            }
            transactions.add((set ? "set" : Integer.toString(quantity)) + " x " + itemId);
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
        });
        when(gateway.recharge(any(), any(), anyShort(), anyInt())).thenAnswer(inv -> {
            Item stack = use.getItem((short) inv.getArgument(2));
            int reserve = inv.getArgument(3);
            int missing = stackSizes.get(stack.getItemId()) - stack.getQuantity();
            int price = (int) Math.ceil(unitPrices.get(stack.getItemId()) * missing);
            if (purse - price < reserve) {
                return Shop.TransactionResult.NOT_ENOUGH_MESO;
            }
            purse -= price;
            ammoSpent += price;
            stack.setQuantity((short) (stack.getQuantity() + missing));
            transactions.add("refill x " + stack.getItemId());
            return Shop.TransactionResult.SUCCESS;
        });
    }

    @AfterEach
    void close() {
        readiness.close();
        speech.close();
        inventories.close();
        shops.close();
        potions.close();
        classification.close();
    }

    @Test
    void theFixtureMapsAreWhereTheyClaimToBe() {
        assertTrue(AgentPotionSupplierCatalog.ammoTopUpAllowedAt(KERNING_PHARMACY));
        assertTrue(AgentPotionSupplierCatalog.ammoTopUpAllowedAt(HENESYS_STORE));
        assertFalse(AgentPotionSupplierCatalog.ammoTopUpAllowedAt(MAYOREN));
        assertFalse(AgentPotionSupplierCatalog.ammoTopUpAllowedAt(SHOWA));
        assertFalse(AgentPotionSupplierCatalog.ammoTopUpAllowedAt(NEW_LEAF_CITY));
    }

    @Test
    void aTenSlotBagAtMayorenGetsItsPotionsFirstAndStarsOnlyInTheSlotsLeftOver() {
        trip = EPQ;
        bag(10);

        AgentShopPurchaseSequence<AgentRuntimeEntry> visit = visit(List.of(RED, WHITE, BLUE, SUBI), WeaponType.CLAW);

        assertArrayEquals(new int[]{350, 350}, AgentPotionService.countPotions(bot), transactions.toString());
        assertEquals(500, stars(), "one set in the one slot the potions left beside the run's");
        assertEquals(1, use.getNumFreeSlot(), "the run's slot stays free");
        assertEquals("set x " + SUBI, transactions.getLast(), "potions first, then stars");
        assertEquals(1, transactions.stream().filter(line -> line.endsWith(" x " + SUBI)).count());
        assertNull(visit.firstShortfall(), "no shop line with counts and an item name; the trip's own lines tell it");
    }

    @Test
    void aReadinessTripRefillsItsOwnStacksBeforeBuyingSets() {
        trip = EPQ;
        carryPotions(350, 350);
        use.addItem(Items.itemWithQuantity(ILBI, 300)); // a star this shop does not sell

        visit(List.of(RED, WHITE, BLUE, SUBI), WeaponType.CLAW);

        assertEquals(List.of("refill x " + ILBI, "set x " + SUBI, "set x " + SUBI,
                "set x " + SUBI, "set x " + SUBI, "set x " + SUBI), transactions,
                "the refill and two sets reach the standard; three more sets are its headroom");
        assertEquals(3_300, stars());
    }

    @Test
    void aReadinessTripOutsideVictoriaRunsNoTopUpToFiveThousandArrows() {
        trip = LPQ;
        mapId = SHOWA;
        hunter();
        carryPotions(80, 100);
        use.addItem(Items.itemWithQuantity(ARROW, 100));

        visit(List.of(RED, WHITE, BLUE, SHOWA_ARROW), WeaponType.BOW);

        assertEquals(3_200, arrows(), transactions.toString());
        assertEquals(List.of("1000 x " + SHOWA_ARROW, "500 x " + SHOWA_ARROW, "1000 x " + SHOWA_ARROW,
                        "600 x " + SHOWA_ARROW), transactions,
                "to the standard at 40 mesos each, then its headroom (twice the standard), never toward 5000");
    }

    @Test
    void anOrdinaryVisitOutsideVictoriaStillBuysNoAmmo() {
        trip = null;
        mapId = SHOWA;
        hunter();
        carryPotions(80, 100);
        use.addItem(Items.itemWithQuantity(ARROW, 100));

        visit(List.of(RED, WHITE, BLUE, SHOWA_ARROW), WeaponType.BOW);

        assertEquals(100, arrows());
        assertTrue(transactions.stream().noneMatch(line -> line.endsWith(" x " + SHOWA_ARROW)), transactions.toString());
    }

    @Test
    void aVictoriaReadinessTripKeepsTheTopUpBeforeThePotions() {
        trip = KPQ;
        mapId = HENESYS_STORE;
        hunter();
        use.addItem(Items.itemWithQuantity(ARROW, 100));

        visit(List.of(RED, WHITE, BLUE, ARROW), WeaponType.BOW);

        assertEquals(5_000, arrows(), "the Victoria top-up, as before");
        assertEquals(List.of("1000 x " + ARROW, "1000 x " + ARROW, "1000 x " + ARROW, "1000 x " + ARROW,
                "900 x " + ARROW), transactions.subList(0, 5), "arrows before the potions");
        assertTrue(transactions.subList(5, transactions.size()).stream().noneMatch(line -> line.endsWith(" x " + ARROW)),
                "the readiness step finds 5000 above its target");
        int[] after = AgentPotionService.countPotions(bot);
        assertTrue(after[0] >= 20 && after[1] >= 25, transactions.toString());
    }

    @Test
    void aBowOnAReadinessTripBuysArrowsToTheTargetAndKeepsOneSlotFree() {
        trip = LPQ;
        hunter();
        bag(4);
        carryPotions(40, 50); // 2 free slots
        visit(List.of(RED, WHITE, BLUE, ARROW), WeaponType.BOW);
        assertEquals(2_000, arrows(), "the standard, then headroom up to the stack's room: " + transactions);
        assertEquals(1, use.getNumFreeSlot());

        buffs.put(BuffStat.SOULARROW, 1); // the fight never runs out under Soul Arrow, but the bag does
        transactions.clear();
        bag(4);
        carryPotions(40, 50);
        visit(List.of(RED, WHITE, BLUE, ARROW), WeaponType.BOW);
        assertEquals(2_000, arrows(), "by the bag count");
        assertEquals(1, use.getNumFreeSlot());

        buffs.clear();
        stackSizes.put(ARROW, 1_000);
        bag(4);
        carryPotions(40, 50);
        AgentShopPurchaseSequence<AgentRuntimeEntry> visit = visit(List.of(RED, WHITE, BLUE, ARROW), WeaponType.BOW);
        assertEquals(1_000, arrows(), "one stack beside the run's slot");
        assertEquals(1, use.getNumFreeSlot());
        assertNull(visit.firstShortfall(), "never \"bag's full\" for the slot it keeps free on purpose");
    }

    @Test
    void theLastFreeUseSlotIsNeverFilledWithStars() {
        trip = LPQ;
        bag(3);
        carryPotions(40, 50); // 1 free slot: the run's
        visit(List.of(RED, WHITE, BLUE, SUBI), WeaponType.CLAW);
        assertEquals(0, stars());
        assertEquals(1, use.getNumFreeSlot());
        assertTrue(transactions.stream().noneMatch(line -> line.endsWith(" x " + SUBI)), transactions.toString());

        transactions.clear();
        bag(4);
        carryPotions(40, 50); // 2 free slots
        visit(List.of(RED, WHITE, BLUE, SUBI), WeaponType.CLAW);
        assertEquals(500, stars());
        assertEquals(1, use.getNumFreeSlot());
    }

    @Test
    void aShopWithoutStarsGivesAnEmptyReportNotAnException() {
        trip = EPQ;
        mapId = NEW_LEAF_CITY;

        assertDoesNotThrow(() -> visit(List.of(RED, WHITE, BLUE), WeaponType.CLAW));

        assertArrayEquals(new int[]{350, 350}, AgentPotionService.countPotions(bot), "its potions are bought");
        assertEquals(0, stars());
        AgentShopBuyReport report = AgentShopService.buyReadinessAmmo(bot, shop(List.of(RED, WHITE, BLUE)),
                WeaponType.CLAW, inventory, 1_600, 0);
        assertEquals(0, report.quantity());
        assertFalse(report.hasShortfall(), "nothing to say about stars it could not find");
    }

    @Test
    void aStandardAtTheLowAmmoWarningIsBoughtPastIt() {
        trip = KPQ;
        mapId = KERNING_PHARMACY;
        carryPotions(20, 25);

        visit(List.of(RED, WHITE, BLUE, SUBI), WeaponType.CLAW);

        assertEquals(1_000, stars(), "Victoria's seed set, then one more: 500 would warn \"running low\" at once");
        assertEquals(2, transactions.stream().filter(line -> line.equals("set x " + SUBI)).count());
    }

    @Test
    void theReadinessAmmoIsLoggedNotSpoken() {
        trip = EPQ;
        carryPotions(350, 350);

        AgentShopPurchaseSequence<AgentRuntimeEntry> visit = visit(List.of(RED, WHITE, BLUE, SUBI), WeaponType.CLAW);

        assertEquals(3_500, stars(), "four sets to the standard, three more as headroom");
        assertEquals(List.of(), visit.bought(), "the spoken \"bought ...\" line never grows with it");
        assertEquals(3_500, visit.unspokenUnits(), "but the visit knows it bought something");
    }

    @Test
    void aBowOutsideVictoriaBuysPastItsStandardSoItsNextTownIsNoStoreTrip() {
        // review: at the default 500 standard a bow bought exactly to 501, so a Hunter at 480 walked to the shop for
        // 21 arrows and did it again after every hunt
        trip = KPQ;
        hunter();
        carryPotions(80, 100);
        use.addItem(Items.itemWithQuantity(ARROW, 480));

        visit(List.of(RED, WHITE, BLUE, ARROW), WeaponType.BOW);

        assertEquals(1_000, arrows(), "AMMO_BUY_TO_PERCENT 200 of the 500 standard: " + transactions);
    }

    @Test
    void theHeadroomIsBoughtLastWithWhatIsLeftAndIsNoShortfall() {
        trip = LPQ;
        carryPotions(80, 100); // at the potion buy target: nothing past it to buy
        purse = 5 * 500 + 200; // the four sets of the standard and one more

        AgentShopPurchaseSequence<AgentRuntimeEntry> visit = visit(List.of(RED, WHITE, BLUE, SUBI), WeaponType.CLAW);

        assertEquals(2_500, stars(), transactions.toString());
        assertNull(visit.firstShortfall());
        assertFalse(visit.quietShortfall(), "falling short of the headroom is no shortfall at all");
    }

    @Test
    void anAmmoOnlyTripKeepsItsStarMoneyFromThePotionTopUp() {
        // review probe 1: potions at the LPQ standard (40/50) but under the buy target (80/100), no stars, and a
        // purse the trip's checks priced for the stars alone; the MP top-up used to spend it on 12 Blue first
        trip = LPQ;
        carryPotions(45, 55);
        Shop mayoren = shop(List.of(RED, WHITE, BLUE, SUBI));
        AgentShopService.ReadinessAmmoPlan plan = plan(mayoren, WeaponType.CLAW, false,
                AgentSupplyReadiness.ammoTarget(LPQ));
        purse = (int) plan.spend() + 500;

        AgentShopPurchaseSequence<AgentRuntimeEntry> visit = visit(mayoren, WeaponType.CLAW);

        assertTrue(stars() >= 1_600, stars() + " stars after " + transactions);
        assertNull(visit.firstShortfall(), "the extra Blue it could not afford past the standard is no news");
    }

    @Test
    void anAmmoOnlyTripThatCanAffordNoStarsSaysNoShopLine() {
        trip = LPQ;
        carryPotions(80, 100);
        purse = 100;

        AgentShopPurchaseSequence<AgentRuntimeEntry> visit = visit(List.of(RED, WHITE, BLUE, SUBI), WeaponType.CLAW);

        assertEquals(0, stars());
        assertNull(visit.firstShortfall(), "no \"could only afford 0 Subi\" line");
        assertTrue(visit.quietShortfall());
        assertNull(AgentShopService.closingLine(visit, true), "nor \"turned out I didn't need anything here\"");
    }

    @Test
    void aTripShortOfPotionsAndStarsBuysBothBeforeTheTopUp() {
        // review probe 2: the purse covers the potion standard (40 White, 50 Blue), 2,000 for stars and 3,000 more
        trip = LPQ;
        purse = 40 * 320 + 50 * 200 + 2_000 + 3_000;

        visit(List.of(RED, WHITE, BLUE, SUBI), WeaponType.CLAW);

        int[] potions = AgentPotionService.countPotions(bot);
        assertTrue(potions[0] >= 40 && potions[1] >= 50, java.util.Arrays.toString(potions) + " " + transactions);
        assertTrue(stars() >= 1_600, stars() + " stars after " + transactions);
    }

    @Test
    void readinessAmmoPlanMatchesWhatTheCounterDoes() {
        // the plan prices the standard; the headroom step after it is off here (its own tests above)
        readiness.when(() -> AgentSupplyReadiness.ammoBuyTarget(any()))
                .thenAnswer(inv -> AgentSupplyReadiness.ammoTarget(inv.getArgument(0)));
        // a claw at a Victoria supplier: its partial stack refilled by the top-up, then one set to pass 500
        trip = KPQ;
        mapId = KERNING_PHARMACY;
        carryPotions(20, 25);
        use.addItem(Items.itemWithQuantity(SUBI, 200));
        Shop kerning = shop(List.of(RED, WHITE, BLUE, SUBI));
        AgentShopService.ReadinessAmmoPlan plan = plan(kerning, WeaponType.CLAW, true, 501);
        long victoria = AgentShopService.plannedAmmoSpend(bot, kerning, WeaponType.CLAW, inventory, unitPrices::get);
        assertCounterDoes(plan, victoria, kerning, WeaponType.CLAW);
        assertEquals(new AgentShopService.ReadinessAmmoPlan(1_000, 500L, 1), plan);

        // a claw at Mayoren: its Ilbi refilled, then two Subi sets
        reset(MAYOREN, EPQ);
        carryPotions(350, 350);
        use.addItem(Items.itemWithQuantity(ILBI, 300));
        Shop mayoren = shop(List.of(RED, WHITE, BLUE, SUBI, ARROW));
        plan = plan(mayoren, WeaponType.CLAW, false, 1_600);
        assertCounterDoes(plan, 0L, mayoren, WeaponType.CLAW);
        assertEquals(new AgentShopService.ReadinessAmmoPlan(1_800, 400L + 2 * 500L, 2), plan);

        // a bow at Mayoren: onto the stack it carries
        reset(MAYOREN, EPQ);
        hunter();
        carryPotions(350, 350);
        use.addItem(Items.itemWithQuantity(ARROW, 300));
        plan = plan(mayoren, WeaponType.BOW, false, 1_600);
        assertCounterDoes(plan, 0L, mayoren, WeaponType.BOW);
        assertEquals(new AgentShopService.ReadinessAmmoPlan(1_600, 1_300L, 0), plan);

        // a bow at Showa: another arrow, so a stack of its own
        reset(SHOWA, LPQ);
        hunter();
        carryPotions(80, 100);
        use.addItem(Items.itemWithQuantity(ARROW, 100));
        Shop showa = shop(List.of(RED, WHITE, BLUE, SHOWA_ARROW));
        plan = plan(showa, WeaponType.BOW, false, 1_600);
        assertCounterDoes(plan, 0L, showa, WeaponType.BOW);
        assertEquals(new AgentShopService.ReadinessAmmoPlan(1_600, 1_500L * 40, 1), plan);
    }

    @Test
    void readinessAmmoPlanCannotReachTheStandardAtAShopWithoutStars() {
        mapId = NEW_LEAF_CITY;
        use.addItem(Items.itemWithQuantity(ILBI, 300));

        AgentShopService.ReadinessAmmoPlan plan = plan(shop(List.of(RED, WHITE, BLUE)), WeaponType.CLAW, false, 1_600);

        assertEquals(new AgentShopService.ReadinessAmmoPlan(800, 400L, 0), plan, "its own stack's refill only");
    }

    // ---- seal cures (2026-10-14, P18: Horntail's Right Hand seals the whole altar) --------------------

    private static final int HOLY_WATER = 2_050_003;
    private static final AgentSupplyReadiness.Activity HORNTAIL_CURE =
            AgentSupplyReadiness.Activity.partyQuest("horntail_cure");

    @Test
    void aCureTripBuysHolyWaterUpToTheCureStandardBeforeAnyPotionPastItsStandard() {
        trip = HORNTAIL_CURE;
        prices.put(HOLY_WATER, 500);
        carryPotions(20, 25);
        int standard = AgentSupplyReadiness.cureStandard(HORNTAIL_CURE);
        purse = standard * 500 + 1_000; // the cures and hardly a potion more

        visit(List.of(WHITE, BLUE, HOLY_WATER), WeaponType.GENERAL1H_SWING);

        assertEquals(150, standard, "agent-engine.yaml CURE_STANDARDS horntail_cure");
        assertEquals(standard, AgentSupplyReadiness.carriedCures(bot), transactions.toString());
    }

    @Test
    void aCureTripNeverFillsTheLastFreeUseSlot() {
        trip = HORNTAIL_CURE;
        prices.put(HOLY_WATER, 500);
        bag(4);
        carryPotions(20, 25);

        visit(List.of(WHITE, BLUE, HOLY_WATER), WeaponType.GENERAL1H_SWING);

        assertEquals(100, AgentSupplyReadiness.carriedCures(bot), transactions.toString());
        assertEquals(1, use.getNumFreeSlot(), "the run's slot");
    }

    @Test
    void aTripForAnyOtherActivityBuysNoCures() {
        trip = LPQ;
        prices.put(HOLY_WATER, 500);
        carryPotions(80, 100);

        visit(List.of(WHITE, BLUE, HOLY_WATER), WeaponType.GENERAL1H_SWING);

        assertEquals(0, AgentSupplyReadiness.carriedCures(bot), transactions.toString());
    }

    @Test
    void readinessCurePlanPricesTheCuresTheCounterBuysOrSaysTheShopSellsNone() {
        prices.put(HOLY_WATER, 500);

        AgentShopService.ReadinessCurePlan plan = AgentShopService.readinessCurePlan(bot,
                shop(List.of(WHITE, BLUE, HOLY_WATER)), HORNTAIL_CURE);
        assertEquals(new AgentShopService.ReadinessCurePlan(HOLY_WATER, 150, 75_000L, 2), plan);
        assertTrue(AgentShopService.readinessCurePlan(bot, shop(List.of(WHITE, BLUE)), HORNTAIL_CURE).unsold(),
                "Leafre's store sells none");
        assertEquals(AgentShopService.ReadinessCurePlan.NOTHING,
                AgentShopService.readinessCurePlan(bot, shop(List.of(WHITE, BLUE, HOLY_WATER)), LPQ));
    }

    // ---- fixture ---------------------------------------------------------------------------

    private AgentShopService.ReadinessAmmoPlan plan(Shop shop, WeaponType wt, boolean victoria, int target) {
        return AgentShopService.readinessAmmoPlan(bot, shop, wt, inventory, unitPrices::get, victoria, target);
    }

    /** The visit spends what the plan prices (with the Victoria top-up's own estimate), takes its slots, ends at it. */
    private void assertCounterDoes(AgentShopService.ReadinessAmmoPlan plan, long victoriaSpend, Shop shop,
                                   WeaponType wt) {
        int stacksBefore = ammoStacks(wt);
        ammoSpent = 0;
        visit(shop, wt);
        assertEquals(victoriaSpend + plan.spend(), ammoSpent, transactions.toString());
        assertEquals(plan.newStacks(), ammoStacks(wt) - stacksBefore, transactions.toString());
        assertEquals(plan.projected(), AgentCombatAmmoCounter.countCarried(bot, wt));
    }

    private void reset(int map, AgentSupplyReadiness.Activity activity) {
        mapId = map;
        trip = activity;
        transactions.clear();
        bag(24);
    }

    private AgentShopPurchaseSequence<AgentRuntimeEntry> visit(List<Integer> stock, WeaponType wt) {
        return visit(shop(stock), wt);
    }

    private AgentShopPurchaseSequence<AgentRuntimeEntry> visit(Shop shop, WeaponType wt) {
        AgentRuntimeEntry entry = new AgentRuntimeEntry(bot, null, null);
        List<AgentShopPurchaseAction<AgentRuntimeEntry>> actions =
                AgentShopService.purchaseActions(entry, bot, inventory, wt);
        AgentShopPurchaseSequence<AgentRuntimeEntry> sequence = new AgentShopPurchaseSequence<>(entry, bot, inventory,
                new Point(), actions, new ArrayList<>(), null);
        for (AgentShopPurchaseAction<AgentRuntimeEntry> action : actions) {
            sequence = action.run(sequence, shop);
        }
        return sequence;
    }

    private void hunter() {
        when(bot.getJob()).thenReturn(Job.HUNTER);
    }

    private void bag(int slots) {
        use = new Inventory(bot, InventoryType.USE, (byte) slots);
        when(bot.getInventory(InventoryType.USE)).thenReturn(use);
    }

    /** White and Blue potions in stacks of 100. */
    private void carryPotions(int white, int blue) {
        for (int left = white; left > 0; left -= 100) {
            use.addItem(Items.itemWithQuantity(WHITE, Math.min(100, left)));
        }
        for (int left = blue; left > 0; left -= 100) {
            use.addItem(Items.itemWithQuantity(BLUE, Math.min(100, left)));
        }
    }

    private int stars() {
        return AgentCombatAmmoCounter.countCarried(bot, WeaponType.CLAW);
    }

    private int arrows() {
        return AgentCombatAmmoCounter.countCarried(bot, WeaponType.BOW);
    }

    private int ammoStacks(WeaponType wt) {
        int stacks = 0;
        for (Item item : use.list()) {
            int id = item.getItemId();
            if (wt == WeaponType.CLAW ? ItemConstants.isThrowingStar(id) : ItemConstants.isArrowForBow(id)) {
                stacks++;
            }
        }
        return stacks;
    }

    private static boolean ammo(int itemId) {
        return ItemConstants.isThrowingStar(itemId) || ItemConstants.isBullet(itemId)
                || ItemConstants.isArrowForBow(itemId) || ItemConstants.isArrowForCrossBow(itemId);
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
