package server.living.population;

import config.PopulationConfig;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * population.MAP_VISIBLE_MAX (docs/SCALE_PLATFORM_PLAN.md section 1): a citizen counts where it stands and where an
 * admitted move is taking it; admission is one count-and-reserve, so racing moves never both take the last slot.
 */
class MapVisibleCapTest {
    private static final long NOW = 1_000_000L;

    private final PopulationConfig config = new PopulationConfig();
    private final List<CitizenSession> sessions = new ArrayList<>();
    private final Map<CitizenSession, MapleMap> standing = new HashMap<>();
    private final MapVisibleCap cap = new MapVisibleCap(() -> config, () -> sessions, standing::get);
    private final MapleMap henesys = map(100_000_000);
    private final MapleMap ellinia = map(101_000_000);

    @Test
    void aMapAdmitsUpToItsCapCountingThoseStandingAndThoseOnTheirWay() {
        config.MAP_VISIBLE_MAX = 3;
        on(henesys, 1);
        on(ellinia, 2);
        CitizenSession walker = on(ellinia, 3);

        assertTrue(cap.admit(walker, henesys, NOW)); // 1 standing + its reservation
        assertEquals(2, cap.visible(henesys, 1, 0, NOW));
        assertTrue(cap.admit(on(ellinia, 4), henesys, NOW));
        assertFalse(cap.admit(on(ellinia, 5), henesys, NOW));
        assertTrue(cap.full(henesys, 1, 5, NOW));
        assertFalse(cap.full(map(102_000_000), 1, 5, NOW)); // per map instance: another map is empty
    }

    @Test
    void aCitizenIsNeverCountedTwiceOrAgainstItself() {
        config.MAP_VISIBLE_MAX = 2;
        CitizenSession here = on(henesys, 1);
        on(henesys, 2);

        assertTrue(cap.admit(here, henesys, NOW)); // it stands there already: no reservation, no double count
        assertFalse(cap.reserved(here));
        assertEquals(1, cap.visible(henesys, 1, here.characterId, NOW));
        assertEquals(2, cap.visible(henesys, 1, 0, NOW));
    }

    @Test
    void aSlotComesBackWhenTheMoveLandsIsDroppedOrLapses() {
        config.MAP_VISIBLE_MAX = 1;
        CitizenSession first = on(ellinia, 1);
        CitizenSession second = on(ellinia, 2);

        assertTrue(cap.admit(first, henesys, NOW));
        assertFalse(cap.admit(second, henesys, NOW));
        cap.release(first);
        assertTrue(cap.admit(second, henesys, NOW));
        assertFalse(cap.admit(first, henesys, NOW + 1_000L));
        assertTrue(cap.admit(first, henesys, NOW + MapVisibleCap.RESERVATION_MS)); // the second's lapsed unrefreshed
    }

    @Test
    void aWalkOnItsWayKeepsItsSlotByRefreshing() {
        config.MAP_VISIBLE_MAX = 1;
        CitizenSession walker = on(ellinia, 1);
        CitizenSession other = on(ellinia, 2);

        assertTrue(cap.admit(walker, henesys, NOW));
        cap.refresh(walker, NOW + MapVisibleCap.RESERVATION_MS - 1);
        assertTrue(cap.holds(walker, henesys, NOW + MapVisibleCap.RESERVATION_MS + 1));
        assertFalse(cap.admit(other, henesys, NOW + MapVisibleCap.RESERVATION_MS + 1));
    }

    @Test
    void aLoginReservesBeforeItsSessionIsInTheSchedulersMap() {
        config.MAP_VISIBLE_MAX = 1;
        CitizenSession login = session(1); // not in sessions yet, standing nowhere
        assertTrue(cap.admit(login, henesys, NOW));

        assertFalse(cap.admit(on(ellinia, 2), henesys, NOW));
    }

    @Test
    void racingMovesNeverBothTakeTheLastSlot() throws Exception {
        config.MAP_VISIBLE_MAX = 5;
        for (int id = 1; id <= 4; id++) {
            on(henesys, id);
        }
        List<CitizenSession> movers = new ArrayList<>();
        for (int id = 100; id < 164; id++) {
            movers.add(on(ellinia, id));
        }
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (CitizenSession mover : movers) {
                results.add(pool.submit(() -> {
                    start.await();
                    return cap.admit(mover, henesys, NOW);
                }));
            }
            start.countDown();
            int admitted = 0;
            for (Future<Boolean> result : results) {
                admitted += result.get(10, TimeUnit.SECONDS) ? 1 : 0;
            }
            assertEquals(1, admitted);
            assertEquals(5, cap.visible(henesys, 1, 0, NOW));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theKillSwitchAdmitsEverythingAndReservesNothing() {
        config.MAP_VISIBLE_MAX = 1;
        config.MAP_VISIBLE_CAP_ENABLED = false;
        on(henesys, 1);
        CitizenSession mover = on(ellinia, 2);

        assertTrue(cap.admit(mover, henesys, NOW));
        assertFalse(cap.reserved(mover));
        assertFalse(cap.full(henesys, 1, 2, NOW));
    }

    @Test
    void theBusiestMapsComeFirst() {
        on(henesys, 1);
        on(ellinia, 2);
        on(ellinia, 3);

        assertEquals(Map.of(101_000_000, 2, 100_000_000, 1), cap.busiest(3));
        assertEquals(List.of(101_000_000, 100_000_000), new ArrayList<>(cap.busiest(3).keySet()));
    }

    private CitizenSession on(MapleMap map, int id) {
        CitizenSession session = session(id);
        sessions.add(session);
        standing.put(session, map);
        return session;
    }

    private static CitizenSession session(int id) {
        Citizen citizen = new Citizen(id, "Citizen" + id, "evening", 0, 0, "victoria", "hunter", "relaxed-v1",
                "default", 1.0d, 0, 1, new LinkedHashSet<>(), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
        CitizenSession session = new CitizenSession(citizen, NOW, NOW + 3_600_000L, false);
        session.channel = 1;
        return session;
    }

    private static MapleMap map(int id) {
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(id);
        return map;
    }
}
