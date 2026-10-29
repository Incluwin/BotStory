package server.living.population;

import client.Character;
import client.Job;
import config.PopulationConfig;
import net.server.channel.Channel;
import net.server.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.economy.integration.cosmic.AgentFreeMarketBuyerService;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.knowledge.KnowledgeTestKit;
import server.living.population.CitizenSession.Activity;
import server.maps.MapManager;
import server.maps.MapleMap;
import server.maps.PlayerShop;

import java.awt.Point;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A citizen on a gear errand at the market goes to the stall that sells a piece it can buy (docs/ECONOMY_PLAN.md E3):
 * in another Free Market room it goes there, in its own it walks beside the stall, and it gives up on a stall it cannot
 * reach. Its own room's stalls come first; never its own stall, a closed one, one over its offer or purse.
 */
class MarketErrandStallTest {
    private static final int CITIZEN = 4242;
    private static final int ANN = 10;
    private static final int HERE = 910000003;
    private static final int OTHER = 910000001;
    private static final int MAPLE_SWORD = 1302020; // reqLevel 35, worn
    private static final int RED_KATANA = 1302012;  // reqLevel 70, the errand's upgrade

    private final PopulationConfig config = new PopulationConfig();
    private final Population population = mock(Population.class);
    private final CitizenStore store = mock(CitizenStore.class);
    private final PresenceScheduler presence = mock(PresenceScheduler.class);
    private final MapManager maps = mock(MapManager.class);
    private final Map<Integer, List<PlayerShop>> rooms = new HashMap<>();
    private final Character agent = mock(Character.class);
    private CitizenSession session;
    private MockedStatic<AgentRuntimeIdentityRuntime> identity;
    private MockedStatic<AgentMovementCommandRuntime> movement;
    private MockedStatic<Market> names;
    private MockedConstruction<AgentFreeMarketBuyerService> buyers;
    private AutoCloseable seed;

    @BeforeEach
    void brannocShopsForASword() throws Exception {
        seed = KnowledgeTestKit.publishSeed();
        when(population.config()).thenReturn(config);
        when(population.store()).thenReturn(store);
        when(population.presence()).thenReturn(presence);
        PopulationContent content = mock(PopulationContent.class);
        when(content.basket()).thenReturn(List.of());
        when(population.content()).thenReturn(content);
        when(store.prices()).thenReturn(List.<int[]>of(new int[]{RED_KATANA, 100_000, 3}));
        when(store.gearErrands()).thenReturn(Map.of(CITIZEN, Map.of("weapon",
                new GearErrands.Errand("weapon", "weapon", 35, 130, System.currentTimeMillis() + 3_600_000L))));
        World world = mock(World.class);
        Channel channel = mock(Channel.class);
        when(population.gameWorld()).thenReturn(world);
        when(world.getChannel(1)).thenReturn(channel);
        when(channel.getMapFactory()).thenReturn(maps);
        when(maps.isMapLoaded(anyInt())).thenAnswer(call -> rooms.containsKey((int) call.getArgument(0)));
        when(maps.getMap(anyInt())).thenAnswer(call -> {
            MapleMap map = mock(MapleMap.class);
            when(map.getAllPlayerShops()).thenReturn(rooms.getOrDefault((int) call.getArgument(0), List.of()));
            return map;
        });

        Citizen citizen = new Citizen(CITIZEN, "Brannoc", "evening", 0, 0, "victoria", "warrior-fighter", "relaxed-v1",
                "default", 1.0d, 0, 1, new LinkedHashSet<>(), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
        session = new CitizenSession(citizen, 0L, 3_600_000L, false);
        session.entry = mock(AgentRuntimeEntry.class);
        session.activity = Activity.MARKET;
        session.channel = 1;
        when(presence.sessions()).thenReturn(List.of(session));
        when(agent.getId()).thenReturn(CITIZEN);
        when(agent.getName()).thenReturn("Brannoc");
        when(agent.getMapId()).thenReturn(HERE);
        when(agent.getMeso()).thenReturn(1_000_000);
        when(agent.getLevel()).thenReturn(75);
        when(agent.getJob()).thenReturn(Job.FIGHTER);
        when(agent.getGender()).thenReturn(0);
        when(agent.getStr()).thenReturn(300);
        when(agent.getDex()).thenReturn(60);
        when(agent.getInt()).thenReturn(4);
        when(agent.getLuk()).thenReturn(4);
        identity = mockStatic(AgentRuntimeIdentityRuntime.class);
        identity.when(() -> AgentRuntimeIdentityRuntime.bot(any())).thenReturn(agent);
        movement = mockStatic(AgentMovementCommandRuntime.class);
        // item names are WZ data, which needs the database
        names = mockStatic(Market.class,
                call -> "itemName".equals(call.getMethod().getName()) ? "an item" : call.callRealMethod());
        buyers = mockConstruction(AgentFreeMarketBuyerService.class,
                (buyer, context) -> when(buyer.observeNearby(agent)).thenReturn(List.of())); // nothing within reach
    }

    @AfterEach
    void close() throws Exception {
        buyers.close();
        names.close();
        movement.close();
        identity.close();
        seed.close();
    }

    private static PlayerShop shop(int objectId, int ownerId, boolean open, int x, PlayerShop.ListingView... listings) {
        PlayerShop shop = mock(PlayerShop.class);
        when(shop.getObjectId()).thenReturn(objectId);
        when(shop.getOwnerId()).thenReturn(ownerId);
        when(shop.getOwnerName()).thenReturn("Ann");
        when(shop.isOpen()).thenReturn(open);
        when(shop.getPosition()).thenReturn(new Point(x, 150));
        when(shop.listingSnapshot()).thenReturn(List.of(listings));
        return shop;
    }

    private static PlayerShop.ListingView sword(int price) {
        return new PlayerShop.ListingView(0, RED_KATANA, (short) 1, (short) 1, price, "f", Map.of());
    }

    @Test
    void aStallInAnotherRoomSendsItThere() {
        rooms.put(OTHER, List.of(shop(77, ANN, true, 400, sword(120_000))));
        rooms.put(HERE, List.of());
        new Market(population, itemId -> 40_000L).tick();

        verify(presence).travel(eq(session), eq(Activity.MARKET), eq(OTHER), anyLong());
        movement.verifyNoInteractions();
    }

    @Test
    void inItsOwnRoomItWalksToTheStallAndGivesUpOnOneItCannotReach() {
        rooms.put(HERE, List.of(shop(77, ANN, true, 400, sword(120_000))));
        rooms.put(OTHER, List.of(shop(78, ANN, true, 400, sword(120_000))));
        Market market = new Market(population, itemId -> 40_000L);

        market.tick();
        verify(presence, never()).travel(any(), any(), anyInt(), anyLong());
        movement.verify(() -> AgentMovementCommandRuntime.moveTo(eq(session.entry), eq(new Point(400, 150)), eq(true)));
        assertEquals(new Market.ShoppingTrip(HERE, 77, 1, false), session.shopping, "its own room's stall first");

        market.tick();
        market.tick();
        market.tick(); // three ticks walking and it never got there
        assertTrue(session.shopping.abandoned());
        movement.verify(() -> AgentMovementCommandRuntime.moveTo(any(), any(), eq(true)), times(3));
        verify(presence, never()).travel(any(), any(), anyInt(), anyLong());

        market.tick(); // the stall it gave up on is skipped: the other room's
        verify(presence).travel(eq(session), eq(Activity.MARKET), eq(OTHER), anyLong());
    }

    @Test
    void onlyAPieceItCanBuyFromASellerItBuysFromAndNeverItsOwnStall() {
        Map<Integer, List<PlayerShop>> here = Map.of(HERE, List.of(
                shop(1, ANN, false, 0, sword(120_000)),     // closed
                shop(2, CITIZEN, true, 0, sword(120_000)),  // its own
                shop(3, ANN + 1, true, 0, sword(120_000)),  // a seller it does not buy from
                shop(4, ANN, true, 0, sword(140_000)),      // over its 130k offer
                shop(5, ANN, true, 0, sword(120_000))));
        assertEquals(5, Market.errandStall(HERE, here, view -> 130_000L, 1_000_000L, owner -> owner != ANN + 1, CITIZEN,
                null).objectId());
        assertNull(Market.errandStall(HERE, here, view -> 130_000L, 100_000L, owner -> true, CITIZEN, null),
                "it cannot pay 120k");
        assertNull(Market.errandStall(HERE, here, view -> 0L, 1_000_000L, owner -> true, CITIZEN, null),
                "no errand of its fits the piece");
        assertNull(Market.errandStall(HERE, Map.of(HERE, List.of(shop(5, ANN, true, 0, sword(120_000)))),
                view -> 130_000L, 1_000_000L, owner -> true, CITIZEN, new Market.ShoppingTrip(HERE, 5, 4, true)),
                "the stall it gave up on");
    }

    @Test
    void aTripCountsItsTriesAtOneStall() {
        Market.ErrandStall stall = new Market.ErrandStall(HERE, 77, new Point(), RED_KATANA, "Ann");
        Market.ShoppingTrip first = Market.nextTrip(null, stall);
        assertEquals(1, first.tries());
        Market.ShoppingTrip third = Market.nextTrip(Market.nextTrip(first, stall), stall);
        assertEquals(3, third.tries());
        assertFalse(third.abandoned());
        assertTrue(Market.nextTrip(third, stall).abandoned());
        assertEquals(1, Market.nextTrip(third, new Market.ErrandStall(HERE, 78, new Point(), RED_KATANA, "Ann")).tries(),
                "another stall is a fresh trip");
    }

    @Test
    void withoutAnErrandItBrowsesAsBefore() {
        when(store.gearErrands()).thenReturn(Map.of());
        rooms.put(OTHER, List.of(shop(77, ANN, true, 400, sword(120_000))));
        new Market(population, itemId -> 40_000L).tick();

        verify(presence, never()).travel(any(), any(), anyInt(), anyLong());
        movement.verifyNoInteractions();
        assertNull(session.shopping);
    }

    @Test
    void leavingTheRoomEndsTheTrip() {
        session.shopping = new Market.ShoppingTrip(HERE, 77, 2, false);
        new Market(population, itemId -> 40_000L).onLeave(session);
        assertNull(session.shopping);
    }
}
