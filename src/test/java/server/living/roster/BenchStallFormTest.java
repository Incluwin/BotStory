package server.living.roster;

import client.Character;
import client.Client;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import constants.inventory.ItemConstants;
import net.server.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.runtime.AgentCapabilityInvocation;
import server.agents.capabilities.runtime.AgentCapabilityRuntime;
import server.agents.capabilities.shop.AgentFreeMarketStallCapability;
import server.agents.capabilities.shop.AgentFreeMarketStallService;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.runtime.AgentInteractionRuntime;
import server.living.config.Content.Persona;
import server.living.population.Market;
import server.living.population.Population;
import server.living.population.PopulationContent;
import server.living.quest.QuestLedger;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.PlayerShop;
import server.maps.PlayerShopEscrowStore;
import server.maps.reservation.FreeMarketCharacterSpaceCatalog;
import server.maps.reservation.FreeMarketStorePlacementService;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static server.living.roster.BenchHarness.ANN;

/**
 * The STALL and SELL form (docs/BOT_MANAGEMENT_PLAN.md P5 "Living bench", decision 12 a): a real Free Market shop
 * under the COMMERCE pool, stocked from the haul only (tradeable goods its owner's quests do not need), priced from the
 * market's index, titled in the companion's voice; the sales' meso goes to the haul and the unsold stock back to it
 * when the stall closes, so no unit or meso is duplicated or lost. The shop itself (the capability walking to a spot
 * and opening it, a player buying) is played by the test: listed units leave the bag for the shop's escrow, a sale
 * pays the owner's purse, and a close returns the escrow to the bag.
 */
class BenchStallFormTest {
    private static final int GWEN = 907;
    private static final int POTION = 2000002;
    private static final int SHELL = 4000019;
    private static final int SWORD = 1302000;
    private static final int QUEST_GOOD = 4000020;
    private static final int CASH_ONLY = 4000030;
    private static final int PERMIT = 5140000;

    private final BenchHarness game = new BenchHarness();
    private final Population population = mock(Population.class);
    private final Market market = mock(Market.class);
    /** The item facts: a slot holds 100, CASH_ONLY is unmerchable, two goods have names. */
    private final BenchStallForm.Goods goods = new BenchStallForm.Goods() {
        @Override
        public boolean restricted(int itemId) {
            return itemId == CASH_ONLY;
        }

        @Override
        public int slotMax(Character agent, int itemId) {
            return 100;
        }

        @Override
        public String name(int itemId) {
            return itemId == POTION ? "Orange Potion" : itemId == SWORD ? "Sword" : null;
        }
    };
    private final QuestLedger quests = mock(QuestLedger.class);
    private final PlayerShop shop = mock(PlayerShop.class);
    private final List<AgentCapabilityInvocation<?>> assigned = new ArrayList<>();
    /** item id -> first bag slot the test gives it (an equip's units take the slots after it). */
    private final Map<Integer, Short> slots = new HashMap<>();
    /** item id -> units in the shop's escrow. */
    private final Map<Integer, Integer> escrow = new HashMap<>();
    private final List<PlayerShop.SoldItem> sales = new ArrayList<>();
    private boolean populationUp = true;
    private boolean shopOpen;
    private PlayerShop playerShop;
    private Character gwen;

    @BeforeEach
    void setUp() {
        BenchStallForm stall = new BenchStallForm(game.world, goods);
        game.duty.register(Assignment.STALL, stall);
        game.duty.register(Assignment.SELL, stall);
        game.statics(AgentInteractionRuntime.class).when(() -> AgentInteractionRuntime.registerDirectorIdleAgent(any()))
                .thenAnswer(inv -> {
                    Character agent = inv.getArgument(0);
                    game.calls.add("director-idle " + agent.getName());
                    return game.entry(agent.getId());
                });
        game.statics(Population.class).when(Population::get).thenAnswer(inv -> populationUp ? population : null);
        PopulationContent content = mock(PopulationContent.class);
        when(content.permitItemId()).thenReturn(PERMIT);
        when(population.content()).thenReturn(content);
        when(population.market()).thenReturn(market);
        when(market.price(POTION)).thenReturn(50L);
        when(market.price(SHELL)).thenReturn(10L);
        when(market.price(SWORD)).thenReturn(2000L);
        when(game.world.quests()).thenReturn(quests);
        when(quests.isQuestItemFor(ANN, QUEST_GOOD)).thenReturn(true);
        game.statics(InventoryManipulator.class).when(() -> InventoryManipulator.addById(any(Client.class), anyInt(), anyShort()))
                .thenAnswer(inv -> {
                    Client client = inv.getArgument(0);
                    game.bags.get(client.getPlayer().getId()).merge(inv.getArgument(1), (int) inv.<Short>getArgument(2), Integer::sum);
                    return true;
                });
        game.statics(AgentCapabilityRuntime.class).when(() -> AgentCapabilityRuntime.assign(any(), any())).thenAnswer(inv -> {
            assigned.add(inv.getArgument(1));
            return true;
        });
        game.statics(AgentPrimitiveCapabilityGatewayRuntime.class);
        game.statics(PlayerShopEscrowStore.class);
        game.statics(FreeMarketStorePlacementService.class);
        when(shop.isOwner(any())).thenAnswer(inv -> inv.getArgument(0) == gwen);
        when(shop.isOpen()).thenAnswer(inv -> shopOpen);
        when(shop.getSold()).thenAnswer(inv -> List.copyOf(sales));
    }

    @AfterEach
    void close() {
        game.close();
    }

    private void gwen(Assignment duty, Map<Integer, Integer> haul) {
        game.companion(ANN, "gwen", "Gwen", GWEN, duty);
        gwen = game.bots.get(GWEN);
        haul.forEach((itemId, quantity) -> game.store.store.addHaul(ANN, "gwen", itemId, quantity));
        for (InventoryType type : List.of(InventoryType.USE, InventoryType.ETC, InventoryType.EQUIP)) {
            Inventory bag = gwen.getInventory(type);
            when(bag.listById(anyInt())).thenAnswer(inv -> {
                int itemId = inv.getArgument(0);
                int held = game.bags.get(GWEN).getOrDefault(itemId, 0);
                short first = slots.computeIfAbsent(itemId, id -> (short) (slots.size() * 10 + 1));
                List<Item> items = new ArrayList<>();
                if (type == InventoryType.EQUIP) {
                    for (int i = 0; i < held; i++) {
                        items.add(new Item(itemId, (short) (first + i), (short) 1));
                    }
                } else if (held > 0) {
                    items.add(new Item(itemId, first, (short) held));
                }
                return items;
            });
        }
        when(gwen.getPlayerShop()).thenAnswer(inv -> playerShop);
        doAnswer(inv -> { // the escrow comes back to its bag
            escrow.forEach((itemId, units) -> game.bags.get(GWEN).merge(itemId, units, Integer::sum));
            escrow.clear();
            shopOpen = false;
            playerShop = null;
            game.calls.add("close " + inv.getArgument(0));
            return null;
        }).when(gwen).closePlayerShop(anyString());
    }

    /** The stall command the companion was sent with. */
    private AgentFreeMarketStallCapability.Command command() {
        try {
            Field field = AgentCapabilityInvocation.class.getDeclaredField("command");
            field.setAccessible(true);
            return (AgentFreeMarketStallCapability.Command) field.get(assigned.get(assigned.size() - 1));
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private int itemAt(InventoryType type, short slot) {
        for (Map.Entry<Integer, Short> entry : slots.entrySet()) {
            if (ItemConstants.getInventoryType(entry.getKey()) == type && slot >= entry.getValue() && slot < entry.getValue() + 10) {
                return entry.getKey();
            }
        }
        throw new AssertionError("nothing listed at " + type + " " + slot);
    }

    /** The capability reached its spot and opened the shop: every listed unit moves from the bag into the escrow. */
    private void opens() {
        for (AgentFreeMarketStallService.Listing listing : command().listings()) {
            int itemId = itemAt(listing.inventoryType(), listing.slot());
            int units = listing.perBundle() * listing.bundles();
            game.bags.get(GWEN).merge(itemId, -units, Integer::sum);
            escrow.merge(itemId, units, Integer::sum);
        }
        shopOpen = true;
        playerShop = shop;
    }

    /** A player buys {@code bundles} bundles of {@code units} each; the seller's proceeds reach its purse. */
    private void buys(int itemId, int bundles, int units, int proceeds) {
        escrow.merge(itemId, -bundles * units, Integer::sum);
        game.purses.merge(GWEN, proceeds, Integer::sum);
        sales.add(shop.new SoldItem("Anna", itemId, (short) bundles, proceeds));
        if (escrow.values().stream().allMatch(left -> left <= 0)) { // sold out: the shop closes itself
            shopOpen = false;
            playerShop = null;
        }
    }

    private int total(int itemId) {
        return game.haul(ANN, "gwen").getOrDefault(itemId, 0) + game.bags.get(GWEN).getOrDefault(itemId, 0)
                + escrow.getOrDefault(itemId, 0);
    }

    @Test
    void aStallIsStockedFromTheHaulSellsIntoItAndHandsTheRestBack() {
        gwen(Assignment.STALL, Map.of(POTION, 25, SHELL, 150, SWORD, 2, QUEST_GOOD, 5, CASH_ONLY, 3));

        game.duty.tick();

        int room = game.botMaps.get(GWEN).getId();
        assertTrue(FreeMarketCharacterSpaceCatalog.isRoom(room), "it loads in a Free Market room: " + room);
        assertTrue(game.calls.containsAll(List.of("load Gwen at " + room + " ch1", "director-idle Gwen")), game.calls.toString());
        assertEquals(new CompanionLeases.Lease(CompanionLeases.Kind.COMMERCE, ANN), game.leases.holder(GWEN));
        assertEquals(1, game.duty.liveCount(CompanionBudget.Kind.COMMERCE));
        assertEquals(0, game.duty.liveCountFor(ANN, CompanionBudget.Kind.BENCH_LIVE), "a stall takes no field seat");
        // the haul's goods reach its bag: a slot's worth each, and never the quest good or what no shop may sell
        assertEquals(Map.of(POTION, 0, SHELL, 50, SWORD, 0, QUEST_GOOD, 5, CASH_ONLY, 3), game.haul(ANN, "gwen"));
        assertEquals(25, game.bags.get(GWEN).get(POTION));
        assertEquals(100, game.bags.get(GWEN).get(SHELL));
        assertEquals(2, game.bags.get(GWEN).get(SWORD));
        assertEquals(1, game.bags.get(GWEN).get(PERMIT), "a shop permit, like a citizen's");
        AgentFreeMarketStallCapability.Command command = command();
        assertEquals(room, command.mapId());
        assertEquals(PERMIT, command.permitItemId());
        assertEquals("sword and more, fresh from camp", command.description(), "what it sells, in its voice");
        Map<Integer, List<Integer>> listed = new HashMap<>(); // item id -> [per bundle, bundles, bundle price] per listing
        for (AgentFreeMarketStallService.Listing listing : command.listings()) {
            listed.computeIfAbsent(itemAt(listing.inventoryType(), listing.slot()), id -> new ArrayList<>())
                    .addAll(List.of((int) listing.perBundle(), (int) listing.bundles(), listing.price()));
        }
        assertEquals(Map.of(POTION, List.of(10, 2, 500), SHELL, List.of(100, 1, 1000), SWORD, List.of(1, 1, 2000, 1, 1, 2000)), listed,
                "bundles at the market's index price");

        opens();
        game.duty.tick();
        buys(POTION, 1, 10, 480);
        buys(SWORD, 1, 1, 1_950);
        game.duty.tick();

        assertEquals(2_430L, game.row(ANN, "gwen").haulMeso(), "the sales' meso goes to the haul");
        assertEquals(1_000, game.purses.get(GWEN), "and leaves its purse");
        assertTrue(game.duty.live("gwen"));

        game.duty.endFor("gwen", "called by owner");

        assertTrue(game.calls.contains("close BENCH_SLICE_ENDED"), game.calls.toString());
        game.statics(AgentCapabilityRuntime.class).verify(() -> AgentCapabilityRuntime.cancelNow(any(), any(), anyLong()));
        game.statics(FreeMarketStorePlacementService.class).verify(() -> FreeMarketStorePlacementService.release(gwen));
        assertFalse(game.duty.live("gwen"));
        assertNull(game.leases.holder(GWEN));
        assertTrue(game.calls.contains("unload Gwen"));
        // nothing duplicated or lost: the unsold units are back in the haul, the sold ones are the buyers'
        assertEquals(Map.of(POTION, 15, SHELL, 150, SWORD, 1, QUEST_GOOD, 5, CASH_ONLY, 3), game.haul(ANN, "gwen"));
        for (int itemId : List.of(POTION, SHELL, SWORD)) {
            assertEquals(0, game.bags.get(GWEN).getOrDefault(itemId, 0), "nothing of the stall stays in its bag: " + itemId);
        }
        assertEquals(2_430L, game.row(ANN, "gwen").haulMeso());
        assertEquals(List.of(ANN + " gwen bench.live-end called by owner"), game.store.events("bench.live-end"));
    }

    @Test
    void aSoldOutStallEndsAndItsUnlistedUnitsGoBack() {
        gwen(Assignment.STALL, Map.of(POTION, 25));
        game.duty.tick();
        populationUp = false; // no second stall after this one, so the haul can be read
        opens();
        game.duty.tick();
        buys(POTION, 2, 10, 960);

        game.duty.tick();

        assertEquals(List.of(ANN + " gwen bench.live-end sold out"), game.store.events("bench.live-end"));
        assertEquals(960L, game.row(ANN, "gwen").haulMeso());
        assertEquals(5, game.haul(ANN, "gwen").get(POTION), "25 stocked, 20 listed and sold, 5 back");
        assertEquals(0, game.bags.get(GWEN).get(POTION));
        assertEquals(1_000, game.purses.get(GWEN));
    }

    @Test
    void aStallThatSoldOutBeforeItsFirstLookStillPaysTheHaul() {
        gwen(Assignment.STALL, Map.of(POTION, 20));
        World server = mock(World.class);
        when(gwen.getWorldServer()).thenReturn(server);
        when(server.getPlayerShop(GWEN)).thenReturn(shop); // a sold-out shop stays listed with the world
        game.duty.tick();
        populationUp = false;
        opens();
        buys(POTION, 2, 10, 960);

        game.duty.tick();

        assertEquals(List.of(ANN + " gwen bench.live-end sold out"), game.store.events("bench.live-end"));
        assertEquals(960L, game.row(ANN, "gwen").haulMeso());
        assertEquals(0, total(POTION), "all 20 sold");
        org.mockito.Mockito.verify(server).unregisterPlayerShop(shop);
    }

    @Test
    void aStallThatNeverOpensHandsEverythingBack() {
        gwen(Assignment.STALL, Map.of(POTION, 25, SHELL, 40));
        game.duty.tick();
        populationUp = false;

        game.duty.tick(); // its capability gave up without a shop

        assertEquals(List.of(ANN + " gwen bench.live-end could not open its stall"), game.store.events("bench.live-end"));
        assertEquals(Map.of(POTION, 25, SHELL, 40), game.haul(ANN, "gwen"));
        assertEquals(0, game.bags.get(GWEN).get(POTION) + game.bags.get(GWEN).get(SHELL));
    }

    @Test
    void aCallBeforeTheStallOpensCancelsItAndHandsTheStockBack() {
        gwen(Assignment.STALL, Map.of(SHELL, 40));
        game.duty.tick();

        game.duty.endFor("gwen", "called by owner");

        game.statics(AgentCapabilityRuntime.class).verify(() -> AgentCapabilityRuntime.cancelNow(any(), any(), anyLong()));
        assertFalse(game.calls.stream().anyMatch(call -> call.startsWith("close")), "no shop to close");
        assertEquals(Map.of(SHELL, 40), game.haul(ANN, "gwen"));
        assertEquals(0, game.bags.get(GWEN).get(SHELL));
    }

    @Test
    void sellPricesUnderTheIndexAndAPersonaNamesItsOwnStall() {
        gwen(Assignment.SELL, Map.of(POTION, 10));
        game.duty.tick();

        assertEquals("clearing my bag: orange potion", command().description());
        assertEquals(Math.round(50 * BenchStallForm.QUICK_SALE) * 10, command().listings().get(0).price(),
                "under the index, where browsing citizens buy");
        game.duty.endFor("gwen", "called by owner");

        Persona base = game.content.persona("gwen");
        when(game.content.persona("gwen")).thenReturn(new Persona(base.id(), base.name(), base.title(), base.job(), base.level(),
                base.strength(), base.loadout(), base.personality(), base.dialogueStyle(), base.homeTown(), base.routine(),
                base.recruitable(), base.contract(), base.questLines(), base.arc(), base.relationships(), base.rumors(),
                Map.of("stall", List.of("gwen's finds. fair and square.")), base.benchToleranceDays(), base.paceGap(),
                base.maxLag(), base.giftItemIds(), base.portraitNpcId(), base.look(), base.file()));
        game.store.seed(new RosterEntry(ANN, "gwen", RosterState.BENCHED, Assignment.STALL, 0, 0, BenchHarness.FIELD, 0L, 0L,
                0L, 1_000L, 1_000L, 1_000L, 0, null)); // its owner puts it on a stall instead
        game.duty.tick();

        assertEquals("gwen's finds. fair and square.", command().description());
        assertEquals(500, command().listings().get(0).price(), "a stall asks the index price");
    }

    @Test
    void withNothingItMaySellItStaysInTheLedger() {
        gwen(Assignment.STALL, Map.of(QUEST_GOOD, 5, CASH_ONLY, 3));

        game.duty.tick();

        assertFalse(game.duty.live("gwen"));
        assertEquals(List.of(), game.calls);
        assertEquals(Map.of(QUEST_GOOD, 5, CASH_ONLY, 3), game.haul(ANN, "gwen"));
    }

    @Test
    void stockAStallWasGoneWithComesBackToTheHaulAtItsNextStall() {
        gwen(Assignment.STALL, Map.of(POTION, 25));
        game.duty.tick();
        opens();
        game.duty.tick();
        // something else took the companion away mid-stall; its shop closed as it logged off
        game.loaded.remove(GWEN);
        escrow.forEach((itemId, units) -> game.bags.get(GWEN).merge(itemId, units, Integer::sum));
        escrow.clear();
        shopOpen = false;
        playerShop = null;
        assigned.clear();

        game.duty.tick(); // "gone"; and its next stall takes its stock back before stocking again

        assertEquals(List.of(ANN + " gwen bench.live-end gone"), game.store.events("bench.live-end"));
        assertTrue(game.duty.live("gwen"));
        assertEquals(25, total(POTION), "no potion duplicated or lost");
        assertEquals(0, game.haul(ANN, "gwen").get(POTION), "all 25 back in the haul, then stocked again");
        assertEquals(2, command().listings().get(0).bundles());
    }
}
