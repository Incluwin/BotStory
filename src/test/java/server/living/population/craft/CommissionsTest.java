package server.living.population.craft;

import client.Character;
import client.Client;
import client.Job;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import config.PopulationConfig;
import constants.inventory.ItemConstants;
import net.server.PlayerStorage;
import net.server.world.World;
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
import server.living.population.FieldCatalog;
import server.living.population.PersonMemories;
import server.living.population.Population;
import server.living.population.PresenceScheduler;
import server.living.population.needs.NeedLedger;
import server.living.population.paper.PaperDesk;
import server.maps.MapleMap;
import tools.Pair;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A commission from order to delivery (docs/WORLD_SYSTEMS_PLAN.md N8.2), on real inventories, the real moves and a store
 * kept in memory: the materials and the fee are held, the crafter works while it is online and goes on where it stopped,
 * what it made reaches the customer once (in person, else by Duey with a note), a waiting order gives everything back,
 * and one nobody finishes is refunded. After every step the meso of purses, held fees and packages adds up to what there
 * was, and no item is in two places or none.
 */
class CommissionsTest {
    private static final int SMITH = 5001;
    private static final int ANN = 10;
    private static final int BO = 11;
    private static final int ORE = 4010000;
    private static final int PLATE = 4011000;
    private static final int CAP = 1002001;
    private static final int HELMET = 1002042;
    private static final int SCROLL = 2040001;
    private static final long NOW = 1_800_000_000_000L;
    private static final long MINUTE = 60_000L;
    private static final long START_MESO = 100_000L + 1_000L + 100_000L;

    private final PopulationConfig config = new PopulationConfig();
    private final Population population = mock(Population.class);
    private final PresenceScheduler presence = mock(PresenceScheduler.class);
    private final PersonMemories memories = mock(PersonMemories.class);
    private final ChatterEngine chatter = mock(ChatterEngine.class);
    private final FieldCatalog fields = mock(FieldCatalog.class);
    private final NeedLedger needs = mock(NeedLedger.class);
    private final PlayerStorage players = mock(PlayerStorage.class);
    private final MapleMap henesys = mock(MapleMap.class);
    private final AtomicInteger annMeso = new AtomicInteger(100_000);
    private final AtomicInteger boMeso = new AtomicInteger(100_000);
    private final AtomicInteger smithMeso = new AtomicInteger(1_000);
    private final Character ann = character(ANN, "Ann", annMeso);
    private final Character bo = character(BO, "Bo", boMeso);
    private final Character smithChr = character(SMITH, "Kim", smithMeso);
    private final MemoryStore store = new MemoryStore();
    private final RecipeBook book = new RecipeBook(List.of(
            new RecipeBook.Recipe("r4011000", Profession.SMITH, PLATE, 1, 0, List.of(new Stack(ORE, 10)), 300, 210, 10, "n"),
            new RecipeBook.Recipe("r1002042", Profession.SMITH, HELMET, 1, 15,
                    List.of(new Stack(CAP, 1), new Stack(PLATE, 1)), 500, 350, 45, "n")));
    private final Connection con = mock(Connection.class);
    private Professions professions;
    private Commissions desk;
    private CitizenSession session;
    private List<EconomyOperationKind> committed;
    private MockedStatic<InventoryManipulator> manipulator;
    private MockedStatic<AgentRuntimeIdentityRuntime> identity;
    private Citizen smith = ProfessionsTest.citizen(SMITH, Set.of(Profession.SMITH.flag()));

    @BeforeEach
    void setUp() {
        config.PROFESSIONS_ENABLED = true;
        config.COMMISSIONS_ENABLED = true;
        World world = mock(World.class);
        when(world.getPlayerStorage()).thenReturn(players);
        when(players.getCharacterById(ANN)).thenReturn(ann);
        when(population.gameWorld()).thenReturn(world);
        when(population.config()).thenReturn(config);
        when(population.presence()).thenReturn(presence);
        when(population.memories()).thenReturn(memories);
        when(population.chatter()).thenReturn(chatter);
        when(population.fields()).thenReturn(fields);
        when(population.needs()).thenReturn(needs);
        when(population.citizen(SMITH)).thenAnswer(call -> smith);
        when(fields.kind(anyInt())).thenReturn("town");
        when(chatter.compose(any())).thenReturn("your order, as you wanted it");
        professions = new Professions(population, book);
        desk = desk();
        session = CitizenSessions.loaded(smith, 0L, NOW * 2);
        session.entry = mock(AgentRuntimeEntry.class);
        session.mapId = 100000000;
        identity = mockStatic(AgentRuntimeIdentityRuntime.class);
        identity.when(() -> AgentRuntimeIdentityRuntime.bot(any())).thenReturn(smithChr);
        committed = EconomyJournalTestSupport.commitEnlistedAgainst(con);
        manipulator = mockStatic(InventoryManipulator.class); // the bag moves without packets
        manipulator.when(() -> InventoryManipulator.removeFromSlot(any(), any(), anyShort(), anyShort(), anyBoolean()))
                .thenAnswer(call -> {
                    Client client = call.getArgument(0);
                    client.getPlayer().getInventory(call.getArgument(1)).removeItem(call.getArgument(2),
                            call.getArgument(3), false);
                    return null;
                });
        manipulator.when(() -> InventoryManipulator.addFromDrop(any(), any(), anyBoolean())).thenAnswer(call -> {
            Client client = call.getArgument(0);
            Item item = call.getArgument(1);
            return client.getPlayer().getInventory(ItemConstants.getInventoryType(item.getItemId())).addItem(item) >= 0;
        });
        ann.getInventory(InventoryType.ETC).addItem(new Item(ORE, (short) 0, (short) 50));
        bo.getInventory(InventoryType.ETC).addItem(new Item(ORE, (short) 0, (short) 50));
    }

    @AfterEach
    void tearDown() {
        manipulator.close();
        identity.close();
        EconomyJournalTestSupport.reset();
        PaperDesk.install(null);
    }

    private Commissions desk() {
        return new Commissions(population, professions, store, new CommissionHands(store, new Catalog()), id -> "item " + id);
    }

    private Character character(int id, String name, AtomicInteger meso) {
        Character chr = mock(Character.class);
        Client client = mock(Client.class);
        when(client.getPlayer()).thenReturn(chr);
        when(chr.getClient()).thenReturn(client);
        when(chr.getId()).thenReturn(id);
        when(chr.getName()).thenReturn(name);
        when(chr.getJob()).thenReturn(Job.BEGINNER);
        when(chr.getMapId()).thenReturn(100000000);
        when(chr.getMap()).thenReturn(henesys);
        when(chr.getMeso()).thenAnswer(call -> meso.get());
        when(chr.canHoldMeso(anyInt())).thenReturn(true);
        doAnswer(call -> meso.addAndGet(call.getArgument(0))).when(chr).gainMeso(anyInt(), anyBoolean());
        doAnswer(call -> meso.addAndGet(call.getArgument(0))).when(chr)
                .gainMeso(anyInt(), anyBoolean(), anyBoolean(), anyBoolean());
        for (InventoryType type : List.of(InventoryType.EQUIP, InventoryType.USE, InventoryType.SETUP, InventoryType.ETC)) {
            when(chr.getInventory(type)).thenReturn(new Inventory(chr, type, (byte) 24));
        }
        doAnswer(call -> { // a rollback puts the bag back
            when(chr.getInventory(call.getArgument(0))).thenReturn(call.getArgument(1));
            return null;
        }).when(chr).setInventory(any(), any());
        return chr;
    }

    private void online() {
        when(presence.session(SMITH)).thenReturn(session);
    }

    private void away() {
        when(presence.session(SMITH)).thenReturn(null);
    }

    private int ores(Character chr) {
        return chr.getInventory(InventoryType.ETC).countById(ORE);
    }

    /** Purses, held fees and packages: nothing made, nothing lost. */
    private void mesoAddsUp() {
        long held = store.rows.values().stream().filter(order -> order.state().open()).mapToLong(Commission::fee).sum();
        long mailed = store.mail.stream().mapToLong(Mail::meso).sum();
        assertEquals(START_MESO, annMeso.get() + boMeso.get() + smithMeso.get() + held + mailed);
    }

    private Commission ordered(Character customer, int quantity, long nowMs) {
        Commissions.Ordered ordered = desk.order(customer, SMITH, "r4011000", quantity, nowMs);
        assertNull(ordered.refused());
        mesoAddsUp();
        return ordered.order();
    }

    /** Runs the minute lane for {@code minutes}; the last time it ran. */
    private long run(long nowMs, int minutes) {
        long at = nowMs;
        for (int i = 0; i < minutes; i++) {
            at += MINUTE;
            desk.minute(at);
            mesoAddsUp();
        }
        return at;
    }

    // ---- order -----------------------------------------------------------------------------------

    @Test
    void anOrderHoldsTheMaterialsAndTheFeeInOneCommit() {
        Commission order = ordered(ann, 2, NOW);
        assertEquals(30, ores(ann));
        assertEquals(100_000 - 420, annMeso.get());
        assertEquals(1_000, smithMeso.get(), "paid when it is done");
        assertEquals(20, store.items(order.id()).stream().mapToInt(pair -> pair.getLeft().getQuantity()).sum());
        assertEquals(Commission.State.HELD, order.state());
        assertEquals(NOW + 11 * MINUTE, order.dueAtMs(), "ten minutes, and one for the second plate");
        assertEquals(List.of(EconomyOperationKind.COMMISSION), committed);
    }

    @Test
    void anOrderIsRefusedWithoutTheMaterialsTheFeeRoomOrTheTrade() {
        assertEquals("You don't have the materials any more.", desk.order(ann, SMITH, "r4011000", 6, NOW).refused());
        annMeso.set(100);
        assertEquals("You can't pay the fee.", desk.order(ann, SMITH, "r4011000", 1, NOW).refused());
        annMeso.set(100_000);
        assertNotNull(desk.order(ann, SMITH, "r4011000", 101, NOW).refused());
        assertNotNull(desk.orderScroll(ann, SMITH, (short) 1, CAP, SCROLL, 10, NOW).refused(), "a smith does not scroll");
        assertEquals(50, ores(ann));
        assertTrue(store.rows.isEmpty());
        for (int i = 0; i < config.COMMISSIONS_PER_PLAYER; i++) {
            ordered(ann, 1, NOW);
        }
        assertEquals("You have 3 orders out already.", desk.order(ann, SMITH, "r4011000", 1, NOW).refused());
        for (int i = 3; i < config.COMMISSION_QUEUE_MAX; i++) {
            ordered(bo, 1, NOW);
        }
        assertEquals("They have all the work they can take right now.", desk.order(bo, SMITH, "r4011000", 1, NOW).refused());
    }

    @Test
    void offNothingIsOrderedOrWorkedOnAndTheOrdersKeep() {
        ordered(ann, 1, NOW);
        online();
        config.COMMISSIONS_ENABLED = false;
        assertNotNull(desk.order(ann, SMITH, "r4011000", 1, NOW).refused());
        run(NOW, 30);
        assertEquals(Commission.State.HELD, store.rows.get(1).state());
        config.COMMISSIONS_ENABLED = true;
        run(NOW + 30 * MINUTE, 12);
        assertEquals(Commission.State.DELIVERED, store.rows.get(1).state());
    }

    @Test
    void aFriendPaysLessAndNeverMoreThanTheStockFee() {
        assertEquals(210, desk.fee(book.recipe("r4011000"), 1, SMITH, ANN));
        when(memories.warmth(SMITH, ANN)).thenReturn(40);
        assertEquals(168, desk.fee(book.recipe("r4011000"), 1, SMITH, ANN));
        assertTrue(desk.fee(book.recipe("r4011000"), 10, SMITH, ANN) < 10 * 300);
    }

    // ---- work and delivery -----------------------------------------------------------------------

    @Test
    void theCrafterWorksAndHandsItOverInPerson() {
        Commission order = ordered(ann, 1, NOW);
        online();
        run(NOW, 1);
        assertEquals(Commission.State.WORKING, store.rows.get(order.id()).state());
        run(NOW + MINUTE, 9);
        assertEquals(0, ann.getInventory(InventoryType.ETC).countById(PLATE), "ten minutes of work");
        run(NOW + 10 * MINUTE, 1);

        assertEquals(1, ann.getInventory(InventoryType.ETC).countById(PLATE));
        assertEquals(1_000 + 210, smithMeso.get());
        assertTrue(store.items(order.id()).isEmpty(), "the materials are gone");
        assertTrue(store.mail.isEmpty());
        assertEquals(Commission.State.DELIVERED, store.rows.get(order.id()).state());
        assertTrue(desk.of(ANN).isEmpty());
        verify(chatter).speakTo(any());
        assertEquals(List.of(EconomyOperationKind.COMMISSION, EconomyOperationKind.COMMISSION), committed);
        run(NOW + 11 * MINUTE, 5);
        assertEquals(1, ann.getInventory(InventoryType.ETC).countById(PLATE), "once");
    }

    @Test
    void aCustomerElsewhereOrOfflineGetsItByDueyWithANote() {
        Commission order = ordered(ann, 2, NOW);
        when(players.getCharacterById(ANN)).thenReturn(null);
        online();
        run(NOW, 12);
        assertEquals(1, store.mail.size());
        Mail mail = store.mail.get(0);
        assertEquals(PLATE, mail.item().getItemId());
        assertEquals(2, mail.item().getQuantity());
        assertEquals(ANN, mail.receiver());
        assertEquals("Kim5001", mail.sender());
        assertEquals("your order, as you wanted it", mail.note());
        assertEquals(0L, mail.meso());
        assertEquals(0, ann.getInventory(InventoryType.ETC).countById(PLATE));
        assertEquals(Commission.State.DELIVERED, store.rows.get(order.id()).state());
        assertEquals(1_000 + 420, smithMeso.get());
    }

    @Test
    void aCustomerOnAnotherMapOrWithAFullBagGetsItByDueyToo() {
        ordered(ann, 1, NOW);
        when(ann.getMap()).thenReturn(mock(MapleMap.class));
        online();
        run(NOW, 12);
        assertEquals(1, store.mail.size());

        when(ann.getMap()).thenReturn(henesys);
        ordered(ann, 1, NOW + 20 * MINUTE);
        Inventory etc = ann.getInventory(InventoryType.ETC);
        while (etc.getNumFreeSlot() > 0) {
            etc.addItem(new Item(4000000, (short) 0, (short) 100));
        }
        run(NOW + 20 * MINUTE, 12);
        assertEquals(2, store.mail.size());
        assertEquals(0, etc.countById(PLATE));
    }

    @Test
    void theCrafterLoggingOffMidOrderGoesOnWhereItStoppedEvenAcrossARestart() {
        Commission order = ordered(ann, 1, NOW);
        online();
        long at = run(NOW, 6); // starts, then five minutes of work
        assertEquals(5 * MINUTE, store.rows.get(order.id()).workedMs());
        away();
        at = run(at, 600);
        assertEquals(5 * MINUTE, store.rows.get(order.id()).workedMs(), "ten hours away count for nothing");
        assertEquals(10, store.items(order.id()).get(0).getLeft().getQuantity(), "and lose nothing");

        desk = desk(); // a restart reads the open orders again
        online();
        run(at, 4);
        assertEquals(0, ann.getInventory(InventoryType.ETC).countById(PLATE));
        run(at + 4 * MINUTE, 1);
        assertEquals(1, ann.getInventory(InventoryType.ETC).countById(PLATE));
    }

    @Test
    void aDeliveryThatDoesNotCommitMovesNothingAndIsTriedAgain() {
        ordered(ann, 1, NOW);
        online();
        store.failing = true;
        run(NOW, 1); // the start is not written either: it waits
        assertEquals(Commission.State.HELD, store.rows.get(1).state());
        store.failing = false;
        long at = run(NOW + MINUTE, 10);
        store.failing = true;
        desk.minute(at + MINUTE);
        assertEquals(0, ann.getInventory(InventoryType.ETC).countById(PLATE));
        assertEquals(1_000, smithMeso.get());
        store.failing = false;
        run(at + MINUTE, 3);
        assertEquals(1, ann.getInventory(InventoryType.ETC).countById(PLATE));
    }

    @Test
    void peopleItKnowsComeFirst() {
        ordered(ann, 1, NOW);
        Commission second = ordered(bo, 1, NOW + 1);
        when(memories.warmth(SMITH, BO)).thenReturn(40);
        assertEquals(second.id(), desk.next(SMITH).id());
        when(memories.warmth(SMITH, ANN)).thenReturn(45);
        assertEquals(1, desk.next(SMITH).id(), "both friends: the older order");
        when(memories.warmth(SMITH, ANN)).thenReturn(15);
        assertEquals(10 * MINUTE + 10 * MINUTE, desk.waitMs(SMITH, BO, 10 * MINUTE), "a friend waits for friends only");
    }

    @Test
    void theTwoOrdersAreWorkedOneAfterTheOther() {
        ordered(ann, 1, NOW);
        ordered(bo, 1, NOW);
        when(players.getCharacterById(BO)).thenReturn(bo);
        online();
        run(NOW, 11);
        assertEquals(Commission.State.HELD, store.rows.get(2).state());
        run(NOW + 11 * MINUTE, 11);
        assertEquals(1, bo.getInventory(InventoryType.ETC).countById(PLATE));
        assertEquals(1_000 + 420, smithMeso.get());
    }

    @Test
    void anEquipOrderTakesThePlainestPieceAndGivesTheStockOne() {
        Equip scrolled = piece(CAP, 5, 12);
        Equip plain = piece(CAP, 0, 3);
        Inventory bag = ann.getInventory(InventoryType.EQUIP);
        bag.addItem(scrolled);
        bag.addItem(plain);
        ann.getInventory(InventoryType.ETC).addItem(new Item(PLATE, (short) 0, (short) 1));
        Commissions.Ordered ordered = desk.order(ann, SMITH, "r1002042", 1, NOW);
        assertNull(ordered.refused());
        assertEquals(List.of(scrolled), List.copyOf(bag.list()), "the scrolled one stays");
        assertEquals(45 * MINUTE, ordered.order().workMs());
    }

    // ---- cancel and refund ------------------------------------------------------------------------

    @Test
    void aCancelBeforeItStartsGivesEverythingBack() {
        Commission order = ordered(ann, 3, NOW);
        assertNull(desk.cancel(ann, order.id(), NOW + 1));
        assertEquals(50, ores(ann));
        assertEquals(100_000, annMeso.get());
        assertEquals(Commission.State.CANCELLED, store.rows.get(order.id()).state());
        assertTrue(store.items(order.id()).isEmpty());
        mesoAddsUp();
        assertEquals("That order is gone.", desk.cancel(ann, order.id(), NOW + 2), "once");
        assertEquals(50, ores(ann));
        online();
        run(NOW, 5);
        assertEquals(1_000, smithMeso.get());
    }

    @Test
    void aCancelNeedsRoomTheRightCustomerAndAnOrderNotStarted() {
        Commission order = ordered(ann, 1, NOW);
        assertEquals("That order is gone.", desk.cancel(bo, order.id(), NOW));
        Inventory etc = ann.getInventory(InventoryType.ETC);
        while (etc.getNumFreeSlot() > 0) {
            etc.addItem(new Item(4000000, (short) 0, (short) 100));
        }
        assertEquals("Make some room in your bag first.", desk.cancel(ann, order.id(), NOW));
        assertEquals(40, ores(ann));
        mesoAddsUp();
        online();
        run(NOW, 1);
        assertTrue(desk.cancel(ann, order.id(), NOW + 2 * MINUTE).contains("started"));
    }

    @Test
    void aCrafterGoneSendsEverythingBackByDuey() {
        Commission order = ordered(ann, 1, NOW);
        smith = smith.withFlag(Citizen.FLAG_HIRED);
        run(NOW, 1);
        assertEquals(Commission.State.REFUNDED, store.rows.get(order.id()).state());
        assertEquals(List.of(ORE), store.mail.stream().filter(mail -> mail.item() != null)
                .map(mail -> mail.item().getItemId()).toList());
        assertEquals(210L, store.mail.stream().mapToLong(Mail::meso).sum());
        assertTrue(store.items(order.id()).isEmpty());
        run(NOW + MINUTE, 3);
        assertEquals(2, store.mail.size(), "once");
    }

    @Test
    void anOrderItNeverGetsToGoesBackAfterTwoDays() {
        Commission order = ordered(ann, 1, NOW);
        desk.minute(NOW + 47 * 60 * MINUTE);
        assertEquals(Commission.State.HELD, store.rows.get(order.id()).state());
        desk.minute(NOW + 48 * 60 * MINUTE);
        assertEquals(Commission.State.REFUNDED, store.rows.get(order.id()).state());
        mesoAddsUp();
    }

    @Test
    void aRowEndedMeanwhileIsNeverPaidTwice() {
        Commission order = ordered(ann, 1, NOW);
        store.rows.put(order.id(), order.closed(Commission.State.CANCELLED, NOW)); // ended behind the desk's back
        long meso = annMeso.get();
        assertNotNull(desk.cancel(ann, order.id(), NOW));
        assertEquals(meso, annMeso.get());
        assertEquals(40, ores(ann));
    }

    // ---- scrolling --------------------------------------------------------------------------------

    @Test
    void aScrollersOrderComesBackAsThePieceAfterItsScroll() {
        smith = ProfessionsTest.citizen(SMITH, Set.of(Profession.SCROLLER.flag()));
        Equip cap = piece(CAP, 0, 3);
        when(cap.getUpgradeSlots()).thenReturn((byte) 7);
        ann.getInventory(InventoryType.EQUIP).addItem(cap);
        ann.getInventory(InventoryType.USE).addItem(new Item(SCROLL, (short) 0, (short) 2));
        assertEquals(1, desk.hands().scrollPairs(ann).size());
        Commissions.Ordered ordered = desk.orderScroll(ann, SMITH, cap.getPosition(), CAP, SCROLL, 10, NOW);
        assertNull(ordered.refused());
        assertEquals(1_500L, ordered.order().fee());
        assertEquals(1, ann.getInventory(InventoryType.USE).countById(SCROLL), "one scroll goes in");
        assertEquals(0, ann.getInventory(InventoryType.EQUIP).countById(CAP));
        mesoAddsUp();
        online();
        run(NOW, 17);
        assertEquals(1, ann.getInventory(InventoryType.EQUIP).countById(CAP));
        assertEquals(1_000 + 1_500, smithMeso.get());
    }

    @Test
    void theMinuteAlsoLetsAnIdleCrafterMakeWhatACitizenAsksFor() {
        when(presence.sessions()).thenReturn(List.of(session));
        desk.minute(NOW);
        verify(needs).madeBy(eq(session), eq(smithChr), eq(NOW));
        ordered(ann, 1, NOW);
        desk.minute(NOW + MINUTE);
        verify(needs, times(1)).madeBy(any(), any(), org.mockito.ArgumentMatchers.anyLong());
    }

    /** A piece as a mock (Equip needs WZ), its copies pieces of their own. */
    private static Equip piece(int itemId, int level, int str) {
        Equip equip = mock(Equip.class);
        short[] position = {0};
        short[] quantity = {1};
        when(equip.getQuantity()).thenAnswer(call -> quantity[0]);
        doAnswer(call -> {
            quantity[0] = call.getArgument(0);
            return null;
        }).when(equip).setQuantity(anyShort());
        when(equip.getItemId()).thenReturn(itemId);
        when(equip.getLevel()).thenReturn((byte) level);
        when(equip.getStr()).thenReturn((short) str);
        when(equip.getExpiration()).thenReturn(-1L);
        when(equip.getPetId()).thenReturn(-1);
        when(equip.getItemType()).thenReturn((byte) 1);
        doAnswer(call -> {
            position[0] = call.getArgument(0);
            return null;
        }).when(equip).setPosition(anyShort());
        when(equip.getPosition()).thenAnswer(call -> position[0]);
        when(equip.copy()).thenAnswer(call -> {
            Equip copy = piece(itemId, level, str);
            copy.setPosition(position[0]);
            when(copy.getUpgradeSlots()).thenReturn((byte) 7);
            return copy;
        });
        return equip;
    }

    record Mail(Item item, long meso, String note, String sender, int receiver) {
    }

    private static final class MemoryStore extends CommissionStore {
        final Map<Integer, Commission> rows = new HashMap<>();
        final Map<Integer, List<Pair<Item, InventoryType>>> held = new HashMap<>();
        final List<Mail> mail = new ArrayList<>();
        boolean failing;

        @Override
        public List<Commission> open() {
            return rows.values().stream().filter(order -> order.state().open()).toList();
        }

        @Override
        public int insert(Connection connection, Commission order) {
            int id = rows.size() + 1;
            rows.put(id, order.withId(id));
            return id;
        }

        @Override
        public boolean update(Commission order) {
            try {
                update(null, order);
                return true;
            } catch (SQLException failure) {
                return false;
            }
        }

        @Override
        public void update(Connection connection, Commission order) throws SQLException {
            if (failing || !rows.get(order.id()).state().open()) {
                throw new SQLException("not open");
            }
            rows.put(order.id(), order);
        }

        @Override
        public List<Pair<Item, InventoryType>> items(int commissionId) {
            return held.getOrDefault(commissionId, List.of());
        }

        @Override
        public void saveItems(Connection connection, int commissionId, List<Pair<Item, InventoryType>> items) {
            held.put(commissionId, List.copyOf(items));
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

    private static final class Catalog implements CommissionHands.Items {
        @Override
        public boolean tradeable(int itemId) {
            return true;
        }

        @Override
        public Item make(int itemId, int count) {
            return itemId == HELMET ? piece(HELMET, 0, 1) : new Item(itemId, (short) 0, (short) count);
        }

        @Override
        public boolean scrolls(int scrollId, int equipId) {
            return scrollId == SCROLL && equipId == CAP;
        }

        @Override
        public Equip scrolled(Equip equip, int scrollId) {
            return equip;
        }
    }
}
