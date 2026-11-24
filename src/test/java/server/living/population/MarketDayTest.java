package server.living.population;

import client.Character;
import client.Client;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import config.PopulationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.TimerManager;
import server.economy.EconomyMutation;
import server.economy.EconomyTransactionContext;
import server.economy.EconomyTransactionCoordinator;
import server.living.LivingWorld;
import server.living.LivingWorldHooks;
import server.living.population.CitizenSession.Activity;
import server.living.population.events.WorldEvents;
import server.living.population.events.WorldModifier;
import server.maps.MapleMap;
import server.maps.PlayerShop;
import server.maps.PlayerShopItem;

import java.awt.Point;
import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Market day in the market (docs/WORLD_SYSTEMS_PLAN.md N4.2): the stall cap and the asks while it runs, the prices it
 * never teaches, and a discounted sale that moves the buyer's meso to the seller and mints none.
 */
class MarketDayTest {
    private static final int SHELL = 4000019;
    private static final int SELLER = 10;

    private final Population population = mock(Population.class);
    private final CitizenStore store = mock(CitizenStore.class);
    private final PopulationConfig config = new PopulationConfig();
    private final WorldEvents events = mock(WorldEvents.class);
    private final MockedStatic<LivingWorld> living = mockStatic(LivingWorld.class);
    private final WorldModifier day = WorldModifier.NONE.with(
            WorldModifier.Effect.marketDay("m", 2, 0.85d, 3.0d, Long.MAX_VALUE));
    private Market market;

    @BeforeEach
    void wire() {
        when(population.config()).thenReturn(config);
        when(population.store()).thenReturn(store);
        when(store.prices()).thenReturn(List.<int[]>of(new int[] {SHELL, 100_000, 5}));
        PopulationContent content = mock(PopulationContent.class);
        when(content.basket()).thenReturn(List.of());
        when(population.content()).thenReturn(content);
        when(population.worldEvents()).thenReturn(events);
        when(events.modifier()).thenReturn(WorldModifier.NONE);
        market = new Market(population, itemId -> 1L);
    }

    @AfterEach
    void close() {
        living.close();
    }

    private double meanAsk() {
        long total = 0L;
        for (int i = 0; i < 2_000; i++) {
            total += market.ask(SHELL, 100_000L, (short) 1, (short) 1, null);
        }
        return total / 2_000.0d;
    }

    @Test
    void asksComeDownForTheDayOnly() {
        double usual = meanAsk();
        when(events.modifier()).thenReturn(day);
        double onTheDay = meanAsk();
        when(events.modifier()).thenReturn(day.without("m"));
        double after = meanAsk();
        assertEquals(105_000.0d, usual, 1_500.0d);
        assertEquals(0.85d, onTheDay / usual, 0.02d);
        assertEquals(1.0d, after / usual, 0.02d);
    }

    @Test
    void anAskIsNeverUnderWhatAnNpcPays() {
        Market dear = new Market(population, itemId -> 99_000L);
        when(events.modifier()).thenReturn(day);
        for (int i = 0; i < 200; i++) {
            assertTrue(dear.ask(SHELL, 100_000L, (short) 1, (short) 1, null) >= 99_000L);
        }
    }

    @Test
    void twiceAsManyStallsMayStand() throws Exception {
        TimerManager timers = mock(TimerManager.class);
        try (MockedStatic<TimerManager> manager = mockStatic(TimerManager.class)) {
            manager.when(TimerManager::getInstance).thenReturn(timers);
            Field open = Market.class.getDeclaredField("stallsOpen");
            open.setAccessible(true);
            open.set(market, config.MARKET_STALLS_MAX);
            market.onArrive(merchant());
            verify(timers, never()).schedule(any(), any(), anyLong());
            when(events.modifier()).thenReturn(day);
            market.onArrive(merchant());
            verify(timers, times(1)).schedule(any(), any(), anyLong());
            open.set(market, 2 * config.MARKET_STALLS_MAX);
            market.onArrive(merchant());
            verify(timers, times(1)).schedule(any(), any(), anyLong());
            when(events.modifier()).thenReturn(WorldModifier.NONE);
            open.set(market, config.MARKET_STALLS_MAX);
            market.onArrive(merchant());
            verify(timers, times(1)).schedule(any(), any(), anyLong());
        }
    }

    private CitizenSession merchant() {
        CitizenSession session = mock(CitizenSession.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        Character agent = mock(Character.class);
        when(agent.getMapId()).thenReturn(910000003);
        org.mockito.Mockito.doReturn(agent).when(session).agent();
        session.activity = Activity.MARKET;
        session.citizen = new Citizen(7, "Mia", "evening", 0, 0, "victoria", "hunter", "relaxed-v1", "default", 1.0d, 0, 1,
                new LinkedHashSet<>(), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "").withFlag(Citizen.FLAG_MERCHANT);
        return session;
    }

    @Test
    void itsPricesTeachTheIndexNothingUntilItsStallsAreDown() {
        when(events.modifier()).thenReturn(WorldModifier.NONE.with(WorldModifier.Effect.holdPrices("m", Long.MAX_VALUE)));
        market.sold(SELLER, SHELL, 50_000);
        market.learnSales();
        market.unsold(SHELL, 50_000L);
        assertEquals(100_000L, market.price(SHELL));
        verify(store, never()).savePrice(anyInt(), anyLong(), anyInt());
        when(events.modifier()).thenReturn(WorldModifier.NONE);
        market.unsold(SHELL, 50_000L);
        assertTrue(market.price(SHELL) < 100_000L);
    }

    @Test
    void aDiscountedSaleMintsNoMeso() throws Exception {
        long[] purse = new long[2];
        when(events.modifier()).thenReturn(day);
        int price = (int) market.ask(SHELL, 100_000L, (short) 1, (short) 1, null);
        Character owner = character(purse, 0);
        Character buyer = character(purse, 1);
        when(buyer.getMeso()).thenReturn(1_000_000);
        Client client = mock(Client.class);
        when(client.getPlayer()).thenReturn(buyer);
        try (MockedStatic<EconomyTransactionCoordinator> coordinator = mockStatic(EconomyTransactionCoordinator.class, call -> {
            for (Object argument : call.getArguments()) { // runs the sale as the coordinator does, with no database
                if (argument instanceof EconomyMutation work) {
                    work.run(mock(EconomyTransactionContext.class));
                }
            }
            return null;
        }); MockedStatic<InventoryManipulator> bags = mockStatic(InventoryManipulator.class);
             MockedStatic<LivingWorldHooks> hooks = mockStatic(LivingWorldHooks.class)) {
            bags.when(() -> InventoryManipulator.checkSpace(any(), anyInt(), anyInt(), any())).thenReturn(true);
            bags.when(() -> InventoryManipulator.addFromDrop(any(), any(Item.class), anyBoolean())).thenReturn(true);
            PlayerShop shop = new PlayerShop(owner, "sale", 5140000);
            shop.addItem(new PlayerShopItem(new Item(SHELL, (short) 0, (short) 1), (short) 2, price));
            Field visitors = PlayerShop.class.getDeclaredField("visitors");
            visitors.setAccessible(true);
            ((Character[]) visitors.get(shop))[0] = buyer;
            assertTrue(shop.buy(client, 0, (short) 1));
        }
        assertEquals(-price, purse[1]);
        assertTrue(purse[0] > 0 && purse[0] + purse[1] <= 0, "the seller gets what the buyer paid, less the stock fee");
    }

    private static Character character(long[] purse, int who) {
        Character chr = mock(Character.class);
        when(chr.getPosition()).thenReturn(new Point(0, 0));
        when(chr.canHoldMeso(anyInt())).thenReturn(true);
        when(chr.getName()).thenReturn("P" + who);
        when(chr.getMap()).thenReturn(mock(MapleMap.class));
        org.mockito.Mockito.doAnswer(call -> {
            purse[who] += (int) call.getArgument(0);
            return null;
        }).when(chr).gainMeso(anyInt(), anyBoolean());
        return chr;
    }
}
