package server.living.population.needs;

import client.Character;
import client.Client;
import client.Job;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import config.PopulationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.economy.EconomyAtomicPersistence;
import server.economy.EconomyJournalTestSupport;
import server.economy.EconomyOperationKind;
import server.itcg.ItcgRecipes.Stack;
import server.living.population.ChatterEngine;
import server.living.population.Citizen;
import server.living.population.CitizenSession;
import server.living.population.CitizenSessions;
import server.living.population.Market;
import server.living.population.Population;
import server.living.population.PresenceScheduler;
import server.living.population.craft.CraftFakes;
import server.living.population.craft.Profession;
import server.living.population.craft.Professions;
import server.living.population.craft.RecipeBook;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Players' buy orders (docs/WORLD_SYSTEMS_PLAN.md N8.3) on real inventories and the real moves: the whole price is held
 * at the post, a citizen in town sells into an order that beats its reservation (and into no other), the goods come by
 * Duey once, what nobody fills comes back (in person or by Duey), and the meso of purses, held orders and packages adds up
 * after every step.
 */
class BuyOrdersTest {
    private static final int KIM = 5001;
    private static final int ANN = 10;
    private static final int ORE = 4010000;
    private static final long NOW = 1_800_000_000_000L;
    private static final long DAY = 86_400_000L;

    private final PopulationConfig config = new PopulationConfig();
    private final Population population = mock(Population.class);
    private final PresenceScheduler presence = mock(PresenceScheduler.class);
    private final Market market = mock(Market.class);
    private final NeedLedger needs = mock(NeedLedger.class);
    private final Connection con = mock(Connection.class);
    private final AtomicInteger annMeso = new AtomicInteger(100_000);
    private final AtomicInteger kimMeso = new AtomicInteger(1_000);
    private final Character ann = character(ANN, "Ann", annMeso);
    private final Character kim = character(KIM, "Kim", kimMeso);
    private final MemoryStore store = new MemoryStore();
    private final RecipeBook book = new RecipeBook(List.of(new RecipeBook.Recipe("r4011000", Profession.SMITH, 4011000, 1, 0,
            List.of(new Stack(ORE, 10)), 300, 210, 10, "n")));
    private List<EconomyOperationKind> committed;
    private MockedStatic<InventoryManipulator> manipulator;
    private MockedStatic<AgentRuntimeIdentityRuntime> identity;
    private BuyOrders orders;
    private CitizenSession session;

    @BeforeEach
    void setUp() {
        config.BUY_ORDERS_ENABLED = true;
        when(population.config()).thenReturn(config);
        when(population.presence()).thenReturn(presence);
        when(population.market()).thenReturn(market);
        when(population.needs()).thenReturn(needs);
        when(needs.on()).thenReturn(true);
        ChatterEngine chatter = mock(ChatterEngine.class);
        when(population.chatter()).thenReturn(chatter);
        when(chatter.compose(any())).thenReturn("for your order");
        Professions professions = CraftFakes.professions(population, book);
        when(population.professions()).thenReturn(professions);
        when(market.price(ORE)).thenReturn(100L);
        when(market.npcPays(ORE, 1)).thenReturn(20L);
        orders = new BuyOrders(population, store, new BuyOrderHands(store, id -> true), id -> "Bronze Ore");
        session(Set.of());
        identity = mockStatic(AgentRuntimeIdentityRuntime.class);
        identity.when(() -> AgentRuntimeIdentityRuntime.bot(any())).thenReturn(kim);
        committed = EconomyJournalTestSupport.commitEnlistedAgainst(con);
        manipulator = mockStatic(InventoryManipulator.class);
        manipulator.when(() -> InventoryManipulator.removeFromSlot(any(), any(), anyShort(), anyShort(), anyBoolean()))
                .thenAnswer(call -> {
                    Client client = call.getArgument(0);
                    client.getPlayer().getInventory(call.getArgument(1)).removeItem(call.getArgument(2),
                            call.getArgument(3), false);
                    return null;
                });
        kim.getInventory(InventoryType.ETC).addItem(new Item(ORE, (short) 0, (short) 60));
    }

    @AfterEach
    void tearDown() {
        manipulator.close();
        identity.close();
        EconomyJournalTestSupport.reset();
    }

    private void session(Set<String> flags) {
        Citizen citizen = new Citizen(KIM, "Kim", "evening", 0, 0, "victoria", "hunter", "relaxed-v1", "default", 1.0d, 0, 1,
                new LinkedHashSet<>(flags), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
        session = CitizenSessions.loaded(citizen, 0L, NOW * 2);
        session.entry = mock(AgentRuntimeEntry.class);
        session.activity = CitizenSession.Activity.TOWN;
        when(presence.sessions()).thenReturn(List.of(session));
    }

    private static Character character(int id, String name, AtomicInteger meso) {
        Character chr = mock(Character.class);
        Client client = mock(Client.class);
        when(client.getPlayer()).thenReturn(chr);
        when(chr.getClient()).thenReturn(client);
        when(chr.getId()).thenReturn(id);
        when(chr.getName()).thenReturn(name);
        when(chr.getJob()).thenReturn(Job.BEGINNER);
        when(chr.getMeso()).thenAnswer(call -> meso.get());
        when(chr.canHoldMeso(anyInt())).thenReturn(true);
        doAnswer(call -> meso.addAndGet(call.getArgument(0))).when(chr).gainMeso(anyInt(), anyBoolean());
        doAnswer(call -> meso.addAndGet(call.getArgument(0))).when(chr)
                .gainMeso(anyInt(), anyBoolean(), anyBoolean(), anyBoolean());
        for (InventoryType type : List.of(InventoryType.EQUIP, InventoryType.USE, InventoryType.ETC)) {
            when(chr.getInventory(type)).thenReturn(new Inventory(chr, type, (byte) 24));
        }
        doAnswer(call -> {
            when(chr.getInventory(call.getArgument(0))).thenReturn(call.getArgument(1));
            return null;
        }).when(chr).setInventory(any(), any());
        return chr;
    }

    private void mesoAddsUp() {
        long held = store.rows.values().stream().filter(Need::isOpen).mapToLong(need -> need.pay(need.remaining())).sum();
        long mailed = store.mail.stream().mapToLong(Mail::meso).sum();
        assertEquals(101_000L, annMeso.get() + kimMeso.get() + held + mailed);
    }

    @Test
    void aPostHoldsTheWholePrice() {
        assertNull(orders.post(ann, ORE, 50, 95, NOW));
        assertEquals(100_000 - 4_750, annMeso.get());
        assertEquals(List.of(EconomyOperationKind.NEED_FULFILLED), committed);
        Need order = orders.of(ANN).get(0);
        assertEquals(ANN, order.posterId());
        assertEquals(0, order.citizenId());
        mesoAddsUp();
    }

    @Test
    void aPostIsRefusedOffTheCatalogueUnderTheShopOrPastThePurseAndTheCap() {
        assertNotNull(orders.post(ann, 4000000, 1, 95, NOW), "no recipe takes it");
        assertNotNull(orders.post(ann, ORE, 1, 19, NOW), "an NPC pays more");
        assertNotNull(orders.post(ann, ORE, 200, 600, NOW), "120,000 of 100,000");
        assertNotNull(orders.post(ann, ORE, 201, 95, NOW));
        for (int i = 0; i < config.BUY_ORDERS_PER_PLAYER; i++) {
            assertNull(orders.post(ann, ORE, 1, 95, NOW));
        }
        assertNotNull(orders.post(ann, ORE, 1, 95, NOW));
        mesoAddsUp();
        config.BUY_ORDERS_ENABLED = false;
        assertNotNull(orders.post(ann, ORE, 1, 95, NOW));
    }

    @Test
    void aCitizenInTownFillsItAndTheGoodsComeByDueyOnce() {
        orders.post(ann, ORE, 50, 95, NOW);
        orders.minute(NOW + 60_000L);
        assertEquals(10, kim.getInventory(InventoryType.ETC).countById(ORE));
        assertEquals(1_000 + 4_750, kimMeso.get());
        assertEquals(1, store.mail.size());
        Mail mail = store.mail.get(0);
        assertEquals(ORE, mail.item().getItemId());
        assertEquals(50, mail.item().getQuantity());
        assertEquals("Kim", mail.sender());
        assertEquals("for your order", mail.note());
        assertEquals(ANN, mail.receiver());
        assertEquals(Need.State.MET, store.rows.get(1).state());
        assertEquals(KIM, store.rows.get(1).fulfilledBy());
        assertTrue(orders.all().isEmpty());
        mesoAddsUp();
        orders.minute(NOW + 120_000L);
        assertEquals(10, kim.getInventory(InventoryType.ETC).countById(ORE));
    }

    @Test
    void aPartIsFilledAndTheRestComesBackWhenItIsTakenDown() {
        orders.post(ann, ORE, 100, 95, NOW);
        orders.minute(NOW);
        assertEquals(40, orders.of(ANN).get(0).remaining());
        mesoAddsUp();
        assertNull(orders.takeDown(ann, 1, NOW));
        assertEquals(100_000 - 60 * 95, annMeso.get());
        assertEquals(Need.State.GONE, store.rows.get(1).state());
        assertEquals("That order is gone.", orders.takeDown(ann, 1, NOW), "once");
        mesoAddsUp();
    }

    @Test
    void nobodySellsUnderTheirReservationOutInTheFieldOffAStallOrTheirOwnTradesGoods() {
        orders.post(ann, ORE, 50, 90, NOW); // exactly the 0.9 x index a stall asks at least
        orders.minute(NOW);
        assertEquals(60, kim.getInventory(InventoryType.ETC).countById(ORE));
        orders.takeDown(ann, 1, NOW);
        orders.post(ann, ORE, 50, 91, NOW);
        session.activity = CitizenSession.Activity.TRAIN;
        orders.minute(NOW);
        session.activity = CitizenSession.Activity.MARKET;
        session.stallOpen = true;
        orders.minute(NOW);
        session.stallOpen = false;
        assertEquals(60, kim.getInventory(InventoryType.ETC).countById(ORE));
        config.PROFESSIONS_ENABLED = true;
        session(Set.of(Profession.SMITH.flag()));
        orders.minute(NOW);
        assertEquals(60, kim.getInventory(InventoryType.ETC).countById(ORE), "a smith keeps its ore");
        session(Set.of());
        orders.minute(NOW);
        assertEquals(10, kim.getInventory(InventoryType.ETC).countById(ORE));
        mesoAddsUp();
    }

    @Test
    void theBestPayingOrderIsFilledFirst() {
        orders.post(ann, ORE, 40, 95, NOW);
        orders.post(ann, ORE, 40, 120, NOW);
        assertTrue(orders.sell(session, kim, NOW));
        assertEquals(Need.State.MET, store.rows.get(2).state());
        assertEquals(Need.State.OPEN, store.rows.get(1).state());
    }

    @Test
    void pastItsDeadlineWhatIsLeftGoesBackByDuey() {
        orders.post(ann, ORE, 100, 95, NOW);
        orders.minute(NOW);
        store.mail.clear();
        kim.getInventory(InventoryType.ETC).addItem(new Item(ORE, (short) 0, (short) 60));
        orders.minute(NOW + 7 * DAY);
        assertEquals(1, store.mail.size());
        assertEquals(40 * 95L, store.mail.get(0).meso());
        assertEquals(Need.State.EXPIRED, store.rows.get(1).state());
        orders.minute(NOW + 8 * DAY);
        assertEquals(1, store.mail.size());
        mesoAddsUp();
    }

    @Test
    void aSaleThatDoesNotCommitMovesNothing() {
        orders.post(ann, ORE, 50, 95, NOW);
        store.failing = true;
        assertFalse(orders.sell(session, kim, NOW));
        assertEquals(60, kim.getInventory(InventoryType.ETC).countById(ORE));
        assertEquals(1_000, kimMeso.get());
        store.failing = false;
        assertTrue(orders.sell(session, kim, NOW));
        mesoAddsUp();
    }

    @Test
    void aRestartReadsTheOpenOrdersAgain() {
        orders.post(ann, ORE, 100, 95, NOW);
        BuyOrders again = new BuyOrders(population, store, new BuyOrderHands(store, id -> true), id -> "Bronze Ore");
        again.minute(NOW);
        assertEquals(40, again.of(ANN).get(0).remaining());
        assertEquals(1_000 + 60 * 95, kimMeso.get());
    }

    record Mail(Item item, long meso, String note, String sender, int receiver) {
    }

    private static final class MemoryStore extends NeedStore {
        final Map<Integer, Need> rows = new HashMap<>();
        final List<Mail> mail = new ArrayList<>();
        boolean failing;

        @Override
        public List<Need> posted() {
            return rows.values().stream().filter(Need::isOpen).toList();
        }

        @Override
        public int insertPosted(Connection connection, Need order) {
            int id = rows.size() + 1;
            rows.put(id, order.withId(id));
            return id;
        }

        @Override
        public void updatePosted(Connection connection, Need before, Need after) throws SQLException {
            Need row = rows.get(before.id());
            if (failing || !row.isOpen() || row.delivered() != before.delivered()) {
                throw new SQLException("changed");
            }
            rows.put(after.id(), after);
        }

        @Override
        public void mail(Connection connection, Item item, int meso, String message, String sender, int receiverId) {
            mail.add(new Mail(item, meso, message, sender, receiverId));
        }

        @Override
        public boolean inTransaction(EconomyAtomicPersistence work) {
            try {
                work.persist(null);
                return true;
            } catch (SQLException failure) {
                return false;
            }
        }
    }
}
