package server.living.population;

import client.Character;
import config.PopulationConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.agents.runtime.AgentRuntimeEntry;
import server.life.NPC;
import server.living.population.CitizenSession.Activity;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Citizens go to sell when their equip bag fills (docs/ECONOMY_PLAN.md E3): a loaded citizen with few free equip slots
 * goes to the nearest potion shop at its plan and asks the shopkeeper for the sale on arrival, at most once each
 * SELL_TRIP_EVERY_HOURS; its trip ends when the sale does, or when it never began or ran too long.
 */
class CitizenSellTripsTest {
    private static final int ID = 900;
    private static final int SHOP_MAP = 100000102;
    private static final int SHOPKEEPER = 1012003;
    private static final long HOUR = 3_600_000L;

    private final PopulationConfig config = new PopulationConfig();
    private final Population population = mock(Population.class);
    private static final Point COUNTER = new Point(-297, -79);

    private final List<Integer> asked = new ArrayList<>();
    private final List<Point> walks = new ArrayList<>();
    private final Deque<Runnable> queue = new ArrayDeque<>();
    private final boolean[] visiting = {false};
    private final int[] free = {2};
    private final long[] clock = {0L};
    private final Point[] position = {new Point(300, 100)};
    private Citizen citizen;
    private CitizenSession session;
    private Character agent;
    private CitizenSellTrips trips;

    @BeforeEach
    void wire() {
        when(population.config()).thenReturn(config);
        citizen = citizen(Set.of());
        when(population.citizen(ID)).thenAnswer(inv -> citizen);
        session = new CitizenSession(citizen, 0L, 10 * HOUR, false);
        agent = mock(Character.class);
        when(agent.getName()).thenReturn("Riki");
        session.entry = new AgentRuntimeEntry(agent, agent, null);
        session.activity = Activity.TRAIN;
        trips = new CitizenSellTrips(population);
        trips.freeEquipSlots = who -> free[0];
        trips.shopFor = who -> new int[]{SHOP_MAP, SHOPKEEPER};
        trips.sale = (entry, who, npcId) -> asked.add(npcId);
        trips.visiting = entry -> visiting[0];
        trips.walk = (entry, point) -> walks.add(point);
        trips.clock = () -> clock[0];
        trips.later = (task, delayMs) -> queue.add(task);
        when(agent.getPosition()).thenAnswer(inv -> position[0]);
    }

    /** It arrives on the shop's map for its trip, planned at {@code now}. */
    private void arrive(long now) {
        clock[0] = now;
        session.sellTrip = trips.planned(session, now);
        session.activity = Activity.TOWN;
        session.virtual = false;
        assertTrue(trips.begin(session, map(SHOP_MAP)));
    }

    /** Runs the walk's checks, a second apart, while it has any. */
    private void runChecks(int seconds) {
        for (int i = 0; i < seconds && !queue.isEmpty(); i++) {
            clock[0] += CitizenSellTrips.CHECK_MS;
            queue.poll().run();
        }
    }

    @Test
    void aNearlyFullBagSendsItToTheNearestShopOnceInAWhile() {
        long now = 100 * HOUR;
        CitizenSellTrips.Trip trip = trips.planned(session, now);
        assertNotNull(trip);
        assertEquals(SHOP_MAP, trip.mapId());
        assertEquals(SHOPKEEPER, trip.npcId());
        assertNull(trips.planned(session, now + 5 * HOUR), "at most once each SELL_TRIP_EVERY_HOURS");
        assertNotNull(trips.planned(session, now + 6 * HOUR));
    }

    @Test
    void onlyALoadedCitizenOfItsOwnWithAFullishBagAndAShopToWalkTo() {
        free[0] = 9;
        assertNull(trips.planned(session, 0L), "9 free slots: room enough");
        free[0] = 8;
        citizen = citizen(Set.of(Citizen.FLAG_HIRED));
        assertNull(trips.planned(session, 0L), "a player's hired hand");
        citizen = citizen(Set.of());
        session.virtual = true;
        assertNull(trips.planned(session, 0L), "not loaded");
        session.virtual = false;
        trips.shopFor = who -> null;
        assertNull(trips.planned(session, 0L), "no shop it can walk to");
        trips.shopFor = who -> new int[]{SHOP_MAP, SHOPKEEPER};
        config.SELL_TRIPS_ENABLED = false;
        assertNull(trips.planned(session, 0L), "switched off");
        config.SELL_TRIPS_ENABLED = true;
        assertNotNull(trips.planned(session, 0L), "8 free slots");
    }

    @Test
    void itWalksUpToTheShopkeeperAsksForTheSaleThereAndTheTripEndsWithIt() {
        session.sellTrip = trips.planned(session, 1_000L);
        session.activity = Activity.TOWN;
        assertFalse(trips.begin(session, map(100000000)), "not the shop's map");
        assertTrue(walks.isEmpty());

        arrive(10 * HOUR);
        assertEquals(List.of(COUNTER), walks, "it walks to the shopkeeper");
        runChecks(5);
        assertTrue(asked.isEmpty(), "still on its way");
        position[0] = new Point(-280, -79);
        runChecks(1);
        assertEquals(List.of(SHOPKEEPER), asked, "at the counter it asks for the sale");
        assertTrue(queue.isEmpty());

        long askedAt = clock[0];
        visiting[0] = true;
        assertFalse(trips.finished(session, askedAt + 1_000L), "the visit is only starting");
        assertFalse(trips.finished(session, askedAt + 60_000L), "it is at the counter");
        visiting[0] = false;
        assertTrue(trips.finished(session, askedAt + 90_000L), "sold");
    }

    @Test
    void itAsksWhereverItStandsAfterAMinuteAndStopsWhenItMovedOn() {
        arrive(10 * HOUR);
        runChecks(59);
        assertTrue(asked.isEmpty());
        runChecks(1);
        assertEquals(List.of(SHOPKEEPER), asked, "walked a minute: it asks from where it stands");

        asked.clear();
        arrive(20 * HOUR);
        session.activity = Activity.TRAIN; // re-planned on the way
        runChecks(3);
        assertTrue(asked.isEmpty());
        assertTrue(queue.isEmpty(), "no more checks");
    }

    @Test
    void aTripThatNeverBeganOrNeverEndsIsOver() {
        session.sellTrip = trips.planned(session, 1_000L);
        assertFalse(trips.finished(session, 1_000L + CitizenSellTrips.TRIP_TIMEOUT_MS - 1));
        assertTrue(trips.finished(session, 1_000L + CitizenSellTrips.TRIP_TIMEOUT_MS), "it never got to the shop");

        arrive(10 * HOUR);
        position[0] = COUNTER;
        runChecks(1);
        visiting[0] = true;
        assertTrue(trips.finished(session, clock[0] + CitizenSellTrips.VISIT_TIMEOUT_MS), "a visit that never ends");

        arrive(20 * HOUR);
        visiting[0] = false;
        runChecks(1); // the shopkeeper refused it: no visit is under way
        assertTrue(trips.finished(session, clock[0] + CitizenSellTrips.VISIT_START_MS), "over in seconds");
    }

    private static MapleMap map(int id) {
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(id);
        NPC shopkeeper = mock(NPC.class);
        when(shopkeeper.getPosition()).thenReturn(COUNTER);
        when(map.getNPCById(SHOPKEEPER)).thenReturn(id == SHOP_MAP ? shopkeeper : null);
        return map;
    }

    private static Citizen citizen(Set<String> flags) {
        return new Citizen(ID, "Riki", "evening", 0, 0, "victoria", "warrior-fighter", "relaxed-v1", "default", 1.0d, 0, 1,
                new LinkedHashSet<>(flags), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
    }
}
