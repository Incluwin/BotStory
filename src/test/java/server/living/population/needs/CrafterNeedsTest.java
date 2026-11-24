package server.living.population.needs;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.economy.EconomyJournalTestSupport;
import server.economy.EconomyOperationKind;
import server.itcg.ItcgRecipes.Stack;
import server.living.population.ChatterEngine;
import server.living.population.Citizen;
import server.living.population.CitizenSession;
import server.living.population.CitizenSessions;
import server.living.population.Market;
import server.living.population.PersonMemories;
import server.living.population.Population;
import server.living.population.PresenceScheduler;
import server.living.population.craft.Commissions;
import server.living.population.craft.CraftFakes;
import server.living.population.craft.Profession;
import server.living.population.craft.Professions;
import server.living.population.craft.RecipeBook;
import tools.Pair;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Crafters on the needs board (docs/WORLD_SYSTEMS_PLAN.md N8.3 and N8.4): a smith posts a buy order for what a wanted
 * piece takes, within its purse share and never under the shop's price, and a human fills it; and a smith makes the piece
 * another citizen's GEAR request asks for out of its own bag, for its offer, loaded or away. The meso adds up each time.
 */
class CrafterNeedsTest {
    private static final int SMITH = 5001;
    private static final int BUYER = 5002;
    private static final int ANN = 10;
    private static final int PLATE = 4011002;
    private static final int HELMET = 1002042;
    private static final long NOW = 1_800_000_000_000L;
    private static final long HOUR = 3_600_000L;

    private final PopulationConfig config = new PopulationConfig();
    private final Population population = mock(Population.class);
    private final PresenceScheduler presence = mock(PresenceScheduler.class);
    private final Market market = mock(Market.class);
    private final AtomicInteger annMeso = new AtomicInteger(0);
    private final AtomicInteger smithMeso = new AtomicInteger(50_000);
    private final AtomicInteger buyerMeso = new AtomicInteger(200_000);
    private final Character ann = character(ANN, "Ann", annMeso);
    private final Character smithChr = character(SMITH, "Kim", smithMeso);
    private final Character buyerChr = character(BUYER, "Lu", buyerMeso);
    private final MemoryStore store = new MemoryStore();
    private final RecipeBook book = new RecipeBook(List.of(
            new RecipeBook.Recipe("r4011001", Profession.SMITH, 4011001, 1, 0, List.of(new Stack(4010001, 10)), 300, 210, 10, "n"),
            new RecipeBook.Recipe("r1002042", Profession.SMITH, HELMET, 1, 15, List.of(new Stack(PLATE, 1)), 500, 350, 45, "n")));
    private final Connection con = mock(Connection.class);
    private final Citizen smith = citizen(SMITH, "Kim", Set.of(Profession.SMITH.flag()));
    private final Citizen buyer = citizen(BUYER, "Lu", Set.of());
    private final CitizenSession smithSession = session(smith);
    private List<EconomyOperationKind> committed;
    private MockedStatic<InventoryManipulator> manipulator;
    private MockedStatic<AgentRuntimeIdentityRuntime> identity;
    private NeedLedger ledger;

    @BeforeEach
    void setUp() {
        config.NEEDS_ENABLED = true;
        config.PROFESSIONS_ENABLED = true;
        config.BUY_ORDERS_ENABLED = true;
        config.BUY_ORDER_CHANCE = 1.0d;
        when(population.config()).thenReturn(config);
        when(population.presence()).thenReturn(presence);
        when(population.market()).thenReturn(market);
        when(population.chatter()).thenReturn(mock(ChatterEngine.class));
        when(population.memories()).thenReturn(mock(PersonMemories.class));
        when(population.citizen(SMITH)).thenReturn(smith);
        when(population.citizen(BUYER)).thenReturn(buyer);
        Professions professions = CraftFakes.professions(population, book);
        when(population.professions()).thenReturn(professions);
        Commissions commissions = mock(Commissions.class);
        when(commissions.hands()).thenReturn(CraftFakes.hands(id -> equip(id)));
        when(population.commissions()).thenReturn(commissions);
        when(market.price(anyInt())).thenReturn(1_000L);
        when(market.npcPays(anyInt(), anyInt())).thenReturn(100L);
        when(smithChr.getLevel()).thenReturn(30);
        Catalog items = new Catalog();
        ledger = new NeedLedger(population, store, new LiveNeedHands(population, store, items), items);
        ledger.start(NOW);
        identity = mockStatic(AgentRuntimeIdentityRuntime.class);
        identity.when(() -> AgentRuntimeIdentityRuntime.bot(any())).thenReturn(buyerChr);
        committed = EconomyJournalTestSupport.commitEnlistedAgainst(con);
        manipulator = mockStatic(InventoryManipulator.class);
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
        manipulator.when(() -> InventoryManipulator.checkSpace(any(), anyInt(), anyInt(), anyString())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        manipulator.close();
        identity.close();
        EconomyJournalTestSupport.reset();
        NeedOffers.install(null);
    }

    private static Citizen citizen(int id, String name, Set<String> flags) {
        return new Citizen(id, name, "evening", 0, 0, "victoria", "hunter", "relaxed-v1", "default", 1.0d, 0, 1,
                new LinkedHashSet<>(flags), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
    }

    private static CitizenSession session(Citizen citizen) {
        CitizenSession session = CitizenSessions.loaded(citizen, 0L, NOW * 2);
        session.entry = mock(AgentRuntimeEntry.class);
        return session;
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
        for (InventoryType type : List.of(InventoryType.EQUIP, InventoryType.EQUIPPED, InventoryType.USE, InventoryType.SETUP,
                InventoryType.ETC)) {
            when(chr.getInventory(type)).thenReturn(new Inventory(chr, type, (byte) 24));
        }
        doAnswer(call -> {
            when(chr.getInventory(call.getArgument(0))).thenReturn(call.getArgument(1));
            return null;
        }).when(chr).setInventory(any(), any());
        return chr;
    }

    private static Equip equip(int itemId) {
        Equip equip = mock(Equip.class);
        when(equip.getItemId()).thenReturn(itemId);
        when(equip.getQuantity()).thenReturn((short) 1);
        when(equip.getExpiration()).thenReturn(-1L);
        when(equip.getPetId()).thenReturn(-1);
        when(equip.copy()).thenReturn(equip);
        return equip;
    }

    private Need wantHelmet(long nowMs) {
        return ledger.post(BUYER, null, 0, new NeedDerivation.Proposal(NeedKind.GEAR, HELMET, 1, 30_000L, "cap"),
                nowMs + 48 * HOUR, nowMs);
    }

    private void buyOrder(long nowMs) {
        CrafterNeeds.derive(population, ledger, smithSession, smithChr, new Random(1), nowMs);
    }

    @Test
    void aSmithAsksForWhatAWantedPieceTakes() {
        wantHelmet(NOW);
        buyOrder(NOW);
        Need order = ledger.openBuys(SMITH).get(0);
        assertEquals(PLATE, order.itemId(), "the plate of the wanted helmet");
        assertEquals(900L, order.offer(), "0.9 x the index while prices are not learned");
        assertEquals(5, order.quantity(), "for five of them, and 5,000 of its 50,000 pays for that");
        buyOrder(NOW + HOUR);
        assertEquals(1, ledger.openBuys(SMITH).size(), "one at a time");
        assertEquals(0, committed.size());
    }

    @Test
    void neverUnderTheShopsPriceNorPastThePurseNorTheBoardsShare() {
        when(market.price(anyInt())).thenReturn(50L);
        assertEquals(100L, CrafterNeeds.unit(population, SMITH, PLATE));
        smithMeso.set(400); // 40 to spend: not one plate
        wantHelmet(NOW);
        buyOrder(NOW);
        assertTrue(ledger.openBuys(SMITH).isEmpty());
        smithMeso.set(50_000);
        config.BUY_ORDERS_OPEN_MAX = 0;
        buyOrder(NOW);
        assertTrue(ledger.openBuys(SMITH).isEmpty());
    }

    @Test
    void theLearnedShareIsAsked() {
        NeedOffers.install(new NeedOffers.Learner() {
            @Override
            public double premium(int citizenId, int itemId, double fixed) {
                return fixed;
            }

            @Override
            public double buyShare(int citizenId, int itemId, double fixed) {
                return 1.05d;
            }

            @Override
            public void closed(Need closed, long nowMs, boolean committedNow) {
            }
        });
        assertEquals(1_050L, CrafterNeeds.unit(population, SMITH, PLATE));
    }

    @Test
    void offOrOutOfTheTradeItComesDown() {
        wantHelmet(NOW);
        buyOrder(NOW);
        config.BUY_ORDERS_ENABLED = false;
        buyOrder(NOW + HOUR);
        assertTrue(ledger.openBuys(0).isEmpty());
    }

    @Test
    void aHumanFillsItAtABoardForTheCrafterInTown() {
        wantHelmet(NOW);
        buyOrder(NOW);
        Need order = ledger.openBuys(SMITH).get(0);
        ann.getInventory(InventoryType.ETC).addItem(new Item(PLATE, (short) 0, (short) 8));
        when(presence.session(SMITH)).thenReturn(smithSession);
        identity.when(() -> AgentRuntimeIdentityRuntime.bot(any())).thenReturn(smithChr);
        assertNull(ledger.handOver(ann, order.id(), 5, true, NOW + 1));
        assertEquals(3, ann.getInventory(InventoryType.ETC).countById(PLATE));
        assertEquals(5, smithChr.getInventory(InventoryType.ETC).countById(PLATE));
        assertEquals(4_500, annMeso.get());
        assertEquals(50_000 - 4_500, smithMeso.get());
        assertEquals(Need.State.MET, store.rows.get(order.id()).state());
        assertEquals(List.of(EconomyOperationKind.NEED_FULFILLED), committed);
    }

    // ---- a crafter makes what a citizen asks for ---------------------------------------------------

    @Test
    void aSmithMakesAWantedPieceForACitizenAbout() {
        Need want = wantHelmet(NOW);
        smithChr.getInventory(InventoryType.ETC).addItem(new Item(PLATE, (short) 0, (short) 2));
        CitizenSession buyerSession = session(buyer);
        when(presence.session(BUYER)).thenReturn(buyerSession);
        assertFalse(ledger.madeBy(smithSession, smithChr, NOW + 44 * 60_000L), "the work takes 45 minutes");
        assertTrue(ledger.madeBy(smithSession, smithChr, NOW + 45 * 60_000L));
        assertEquals(1, smithChr.getInventory(InventoryType.ETC).countById(PLATE));
        assertEquals(1, buyerChr.getInventory(InventoryType.EQUIP).countById(HELMET));
        assertEquals(50_000 + 30_000, smithMeso.get());
        assertEquals(200_000 - 30_000, buyerMeso.get());
        assertEquals(Need.State.MET, store.rows.get(want.id()).state());
        assertEquals(List.of(EconomyOperationKind.COMMISSION), committed);
        assertFalse(ledger.madeBy(smithSession, smithChr, NOW + HOUR), "once");
    }

    @Test
    void oneAwayFindsItInItsEscrowAndPaysFromItsStoredPurse() {
        Need want = wantHelmet(NOW);
        smithChr.getInventory(InventoryType.ETC).addItem(new Item(PLATE, (short) 0, (short) 2));
        store.purse = 200_000L;
        server.living.LivingWorld world = mock(server.living.LivingWorld.class);
        server.living.store.LivingStore living = mock(server.living.store.LivingStore.class);
        when(population.world()).thenReturn(world);
        when(world.store()).thenReturn(living);
        when(living.characterMeso(BUYER)).thenReturn(200_000L);
        assertTrue(ledger.madeBy(smithSession, smithChr, NOW + HOUR));
        assertEquals(1, store.escrowed.size());
        assertEquals(HELMET, store.escrowed.get(0).getLeft().getItemId());
        assertEquals(170_000L, store.purse);
        assertEquals(50_000 + 30_000, smithMeso.get());
        assertEquals(Need.State.ESCROW, store.rows.get(want.id()).state());
        assertTrue(ledger.escrowHolders().contains(BUYER));
    }

    @Test
    void noMaterialsNoPurseOrAPlayersHoldAndNothingIsMade() {
        Need want = wantHelmet(NOW);
        CitizenSession buyerSession = session(buyer);
        when(presence.session(BUYER)).thenReturn(buyerSession);
        assertFalse(ledger.madeBy(smithSession, smithChr, NOW + HOUR));
        smithChr.getInventory(InventoryType.ETC).addItem(new Item(PLATE, (short) 0, (short) 2));
        buyerMeso.set(100);
        assertFalse(ledger.madeBy(smithSession, smithChr, NOW + HOUR));
        buyerMeso.set(200_000);
        ledger.claim(ANN, want.id(), NOW + HOUR);
        assertFalse(ledger.madeBy(smithSession, smithChr, NOW + HOUR + 1));
        assertEquals(2, smithChr.getInventory(InventoryType.ETC).countById(PLATE));
        assertEquals(50_000, smithMeso.get());
    }

    private static final class MemoryStore extends NeedStore {
        final Map<Integer, Need> rows = new HashMap<>();
        List<Pair<Item, InventoryType>> escrowed = new ArrayList<>();
        long purse;

        @Override
        public List<Need> live(long nowMs) {
            return List.of();
        }

        @Override
        public List<Need> endedSince(long sinceMs) {
            return List.of();
        }

        @Override
        public Set<Integer> escrowHolders() {
            return Set.of();
        }

        @Override
        public int insert(Need need) {
            int id = rows.size() + 1;
            rows.put(id, need.withId(id));
            return id;
        }

        @Override
        public void update(Need need) {
            rows.put(need.id(), need);
        }

        @Override
        public void update(Connection connection, Need need) {
            rows.put(need.id(), need);
        }

        @Override
        public List<Pair<Item, InventoryType>> escrow(int citizenId) {
            return escrowed;
        }

        @Override
        public void saveEscrow(Connection connection, int citizenId, List<Pair<Item, InventoryType>> items) {
            escrowed = List.copyOf(items);
        }

        @Override
        public void debit(Connection connection, int characterId, long meso) {
            purse -= meso;
        }
    }

    private static final class Catalog implements NeedLedger.Items {
        @Override
        public String name(int itemId) {
            return "item " + itemId;
        }

        @Override
        public int successRate(int scrollId) {
            return 0;
        }

        @Override
        public boolean canApplyScroll(int scrollId, int equipId) {
            return false;
        }

        @Override
        public boolean tradeable(int itemId) {
            return true;
        }

        @Override
        public boolean baseOrBetter(int itemId, Map<String, Object> stats) {
            return true;
        }
    }
}
