package server.living.population.needs;

import client.Character;
import client.Client;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import constants.inventory.ItemConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.economy.EconomyJournalTestSupport;
import server.economy.EconomyOperationKind;
import server.living.population.Citizen;
import server.living.population.Population;
import tools.Pair;

import java.sql.Connection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The needs board's hands in the game (docs/BOT_CONCEPT_PLAN.md F1), on real inventories: a hand-over in person moves the
 * potions out of the player's bag into the citizen's own and the offer from its purse to the player's, as one
 * NEED_FULFILLED economy transaction that also stores the request; a citizen who cannot pay moves nothing; at a board
 * for a citizen who is away the items wait in its escrow and its stored purse pays, in the same commit; its next load
 * takes the escrow into its bag; and only tradeable items, equips base or better, count as carried.
 */
class LiveNeedHandsTest {
    private static final int KIM = 5001;
    private static final int ANN = 10;
    private static final int WHITE = 2000002;
    private static final int QUEST_ITEM = 4031013;
    private static final int GARNIER = 1472030;
    private static final long NOW = 1_800_000_000_000L;

    private final Population population = mock(Population.class);
    private final NeedStore store = mock(NeedStore.class);
    private final Connection con = mock(Connection.class);
    private final AtomicInteger annMeso = new AtomicInteger(1_000);
    private final AtomicInteger kimMeso = new AtomicInteger(2_000_000);
    private final Character ann = character(ANN, "Ann", annMeso);
    private final Character kim = character(KIM, "Kim", kimMeso);
    private final LiveNeedHands hands = new LiveNeedHands(population, store, new Catalog());
    private List<EconomyOperationKind> committed;
    private MockedStatic<InventoryManipulator> manipulator;

    @BeforeEach
    void setUp() {
        when(population.citizen(KIM)).thenReturn(new Citizen(KIM, "Kim", "evening", 0, 0, "victoria", "hunter",
                "relaxed-v1", "default", 1.0d, 0, 1, new LinkedHashSet<>(), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, ""));
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
        manipulator.when(() -> InventoryManipulator.checkSpace(any(), anyInt(), anyInt(), anyString())).thenReturn(true);
        ann.getInventory(InventoryType.USE).addItem(new Item(WHITE, (short) 0, (short) 120));
    }

    @AfterEach
    void tearDown() {
        manipulator.close();
        EconomyJournalTestSupport.reset();
    }

    private static Character character(int id, String name, AtomicInteger meso) {
        Character chr = mock(Character.class);
        Client client = mock(Client.class);
        when(client.getPlayer()).thenReturn(chr);
        when(chr.getClient()).thenReturn(client);
        when(chr.getId()).thenReturn(id);
        when(chr.getName()).thenReturn(name);
        when(chr.getMeso()).thenAnswer(call -> meso.get());
        when(chr.canHoldMeso(anyInt())).thenReturn(true);
        doAnswer(call -> meso.addAndGet(call.getArgument(0))).when(chr).gainMeso(anyInt(), anyBoolean());
        doAnswer(call -> meso.addAndGet(call.getArgument(0))).when(chr)
                .gainMeso(anyInt(), anyBoolean(), anyBoolean(), anyBoolean());
        for (InventoryType type : List.of(InventoryType.EQUIP, InventoryType.USE, InventoryType.ETC)) {
            when(chr.getInventory(type)).thenReturn(new Inventory(chr, type, (byte) 24));
        }
        return chr;
    }

    private static Need whites() {
        return new Need(7, KIM, NeedKind.SUPPLY, WHITE, 100, 0, 345L, "easy_balrog", 100000000, Need.State.OPEN, 0, 0L, 0,
                true, NOW, NOW + 86_400_000L, 0L);
    }

    @Test
    void inPersonThePotionsGoIntoTheCitizensBagAndItsMesosToThePlayerInOneCommit() throws Exception {
        Need need = whites();
        Need after = need.withDelivery(ANN, 40, false, false, NOW);
        assertEquals(120, hands.carried(ann, need));

        assertNull(hands.transfer(ann, kim, need, after, 40, 40 * 345L));

        assertEquals(80, ann.getInventory(InventoryType.USE).countById(WHITE));
        assertEquals(40, kim.getInventory(InventoryType.USE).countById(WHITE), "into its own bag, not destroyed");
        assertEquals(1_000 + 13_800, annMeso.get());
        assertEquals(2_000_000 - 13_800, kimMeso.get());
        assertEquals(List.of(EconomyOperationKind.NEED_FULFILLED), committed);
        verify(store).update(con, after);
    }

    @Test
    void aCitizenWhoCannotPayMovesNothing() throws Exception {
        kimMeso.set(1_000);
        Need need = whites();

        assertEquals("Kim can't pay that right now.", hands.transfer(ann, kim, need, need, 40, 40 * 345L));

        assertEquals(120, ann.getInventory(InventoryType.USE).countById(WHITE));
        assertEquals(0, kim.getInventory(InventoryType.USE).countById(WHITE));
        assertEquals(1_000, annMeso.get());
        verify(store, never()).update(any(Connection.class), any());
    }

    @Test
    void atABoardItWaitsInEscrowAndItsStoredPursePaysInTheSameCommit() throws Exception {
        when(store.escrow(KIM)).thenReturn(List.of());
        Need need = whites();
        Need after = need.withDelivery(ANN, 100, true, true, NOW);

        assertNull(hands.transfer(ann, null, need, after, 100, 100 * 345L));

        assertEquals(20, ann.getInventory(InventoryType.USE).countById(WHITE));
        assertEquals(1_000 + 34_500, annMeso.get());
        verify(store).debit(con, KIM, 34_500L);
        verify(store).saveEscrow(eq(con), eq(KIM), argThat(items -> items.size() == 1
                && items.get(0).getLeft().getItemId() == WHITE && items.get(0).getLeft().getQuantity() == 100));
        verify(store).update(con, after);
        assertEquals(Need.State.ESCROW, after.state());
    }

    @Test
    void itsNextLoadTakesTheEscrowIntoItsBag() throws Exception {
        when(store.escrow(KIM)).thenReturn(List.of(new Pair<>(new Item(WHITE, (short) 0, (short) 100), InventoryType.USE)));
        Need escrowed = whites().withDelivery(ANN, 100, true, true, NOW);

        assertEquals(List.of(WHITE), hands.takeEscrow(kim, List.of(escrowed)));

        assertEquals(100, kim.getInventory(InventoryType.USE).countById(WHITE));
        verify(store).saveEscrow(con, KIM, List.of());
        verify(store).update(eq(con), argThat(need -> need.id() == 7 && need.state() == Need.State.MET));
    }

    @Test
    void onlyTradeableItemsAndEquipsBaseOrBetterCount() throws Exception {
        ann.getInventory(InventoryType.ETC).addItem(new Item(QUEST_ITEM, (short) 0, (short) 1));
        Need quest = new Need(8, KIM, NeedKind.SUPPLY, QUEST_ITEM, 1, 0, 10L, "", 0, Need.State.OPEN, 0, 0L, 0, true, NOW,
                NOW + 1L, 0L);
        assertEquals(0, hands.carried(ann, quest));

        Equip weak = mock(Equip.class);
        when(weak.getItemId()).thenReturn(GARNIER);
        when(weak.getQuantity()).thenReturn((short) 1);
        when(weak.getWatk()).thenReturn((short) 20);
        ann.getInventory(InventoryType.EQUIP).addItem(weak);
        Need garnier = new Need(9, KIM, NeedKind.GEAR, GARNIER, 1, 0, 900_000L, "weapon", 0, Need.State.OPEN, 0, 0L, 0,
                true, NOW, NOW + 1L, 0L);
        assertEquals(0, hands.carried(ann, garnier), "under Garnier's 25 attack");
        when(weak.getWatk()).thenReturn((short) 26);
        assertEquals(1, hands.carried(ann, garnier));
        assertTrue(committed.isEmpty(), "looking moves nothing");
        verify(store, never()).debit(any(), anyInt(), anyLong());
    }

    /** A quest item that cannot change hands, and Garnier's base of 25 weapon attack. */
    private static final class Catalog implements NeedLedger.Items {
        @Override
        public String name(int itemId) {
            return null;
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
            return itemId != QUEST_ITEM;
        }

        @Override
        public boolean baseOrBetter(int itemId, Map<String, Object> stats) {
            return itemId != GARNIER || NeedQuality.baseOrBetter(stats, Map.of("weaponAttack", 25));
        }
    }
}
