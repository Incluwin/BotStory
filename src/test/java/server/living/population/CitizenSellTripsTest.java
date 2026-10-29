package server.living.population;

import client.Character;
import config.PopulationConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.agents.runtime.AgentRuntimeEntry;
import server.living.population.CitizenSession.Activity;
import server.maps.MapleMap;

import java.util.ArrayList;
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
    private final List<Integer> asked = new ArrayList<>();
    private final boolean[] visiting = {false};
    private final int[] free = {2};
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
        trips.sale = (entry, who, npcId, onAsked) -> {
            asked.add(npcId);
            onAsked.run();
        };
        trips.visiting = entry -> visiting[0];
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
    void onArrivalItAsksTheShopkeeperAndTheTripEndsWithTheSale() {
        session.sellTrip = trips.planned(session, 1_000L);
        assertFalse(trips.begin(session, map(100000000)), "not the shop's map");
        assertTrue(asked.isEmpty());

        assertTrue(trips.begin(session, map(SHOP_MAP)));
        assertEquals(List.of(SHOPKEEPER), asked);
        long askedAt = System.currentTimeMillis();
        visiting[0] = true;
        assertFalse(trips.finished(session, askedAt + 1_000L), "the visit is only starting");
        assertFalse(trips.finished(session, askedAt + 60_000L), "it is at the counter");
        visiting[0] = false;
        assertTrue(trips.finished(session, askedAt + 90_000L), "sold");
    }

    @Test
    void aTripThatNeverBeganOrNeverEndsIsOver() {
        session.sellTrip = trips.planned(session, 1_000L);
        assertFalse(trips.finished(session, 1_000L + CitizenSellTrips.TRIP_TIMEOUT_MS - 1));
        assertTrue(trips.finished(session, 1_000L + CitizenSellTrips.TRIP_TIMEOUT_MS), "it never got to the shop");

        session.sellTrip = trips.planned(session, 10 * HOUR);
        trips.begin(session, map(SHOP_MAP));
        visiting[0] = true;
        long askedAt = System.currentTimeMillis();
        assertTrue(trips.finished(session, askedAt + CitizenSellTrips.VISIT_TIMEOUT_MS), "a visit that never ends");
    }

    private static MapleMap map(int id) {
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(id);
        return map;
    }

    private static Citizen citizen(Set<String> flags) {
        return new Citizen(ID, "Riki", "evening", 0, 0, "victoria", "warrior-fighter", "relaxed-v1", "default", 1.0d, 0, 1,
                new LinkedHashSet<>(flags), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
    }
}
