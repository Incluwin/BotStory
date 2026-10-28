package server.living.population;

import config.PopulationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.living.population.Market.StallWalk;
import server.maps.reservation.CharacterSpace;
import server.maps.reservation.CharacterSpaceOwner;
import server.maps.reservation.CharacterSpaceReservation;
import server.maps.reservation.CharacterSpaceReservationRuntime;
import server.maps.reservation.CharacterSpaceScope;
import server.maps.reservation.FreeMarketCharacterSpaceCatalog;

import java.awt.Point;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Citizen stalls in the Free Market rooms (live 10-28): a shop's sign is drawn over its owner, and citizens stood on the
 * arrival ledge above the exit portal, so every citizen stall of a room was stacked there and only the top one could be
 * clicked. A merchant now walks to its reserved stall spot before it opens, and a browser strolls from stall to stall on
 * every platform of the room, not only along the two-spot ledge it arrives on.
 */
class MarketStallSpotTest {
    private static final int ROOM = 910_000_001;
    /** Where citizens land in a Henesys-layout room: the spawn point drops them on the ledge x 682-938, y -146. */
    private static final Point ARRIVAL_LEDGE = new Point(828, -146);
    private static final int CITIZEN = 5001;

    @AfterEach
    void clearReservations() {
        CharacterSpaceReservationRuntime.clear();
    }

    @Test
    void itOpensOnlyOnItsSpotAndWalksAgainWhenItsMoveEndsShort() {
        Point spot = new Point(328, 34);
        long deadline = 45_000L;

        assertEquals(StallWalk.WAIT, Market.stallWalk(ARRIVAL_LEDGE, spot, true, 1_000L, deadline), "on its way");
        assertEquals(StallWalk.WALK_AGAIN, Market.stallWalk(new Point(600, 34), spot, false, 9_000L, deadline),
                "its move ended short of the spot");
        assertEquals(StallWalk.OPEN, Market.stallWalk(new Point(340, 34), spot, false, 12_000L, deadline));
        assertEquals(StallWalk.OPEN, Market.stallWalk(new Point(330, 34), spot, true, deadline, deadline),
                "on the spot at the deadline still opens");
        assertEquals(StallWalk.GIVE_UP, Market.stallWalk(ARRIVAL_LEDGE, spot, true, deadline, deadline));
        assertEquals(StallWalk.WAIT, Market.stallWalk(null, spot, true, 1_000L, deadline), "no position yet");
    }

    @Test
    void aBrowserLeavesTheArrivalLedgeForStallsOnOtherPlatforms() {
        List<CharacterSpace> spaces = FreeMarketCharacterSpaceCatalog.spaces(ROOM);
        Random random = new Random(7);
        Set<Integer> platforms = new LinkedHashSet<>();
        for (int stroll = 0; stroll < 200; stroll++) {
            Point to = Market.browseSpot(ROOM, ARRIVAL_LEDGE, random);
            CharacterSpace beside = spaces.stream()
                    .filter(space -> space.position().y == to.y)
                    .filter(space -> Math.abs(space.position().x - to.x) >= Market.BROWSE_BESIDE_MIN_PX
                            && Math.abs(space.position().x - to.x) < Market.BROWSE_BESIDE_MAX_PX)
                    .findFirst()
                    .orElse(null);
            assertTrue(beside != null, "it stands beside a stall spot: " + to);
            assertTrue(beside.position().distance(ARRIVAL_LEDGE) > Market.BROWSE_MIN_STEP_PX,
                    "never the spot it already stands at");
            platforms.add(to.y);
        }
        assertTrue(platforms.stream().anyMatch(y -> y != ARRIVAL_LEDGE.y),
                "some strolls leave the ledge: " + platforms);
    }

    @Test
    void outsideTheRoomsItStrollsAsInTown() {
        Random random = new Random(7);
        assertNull(Market.browseSpot(910_000_000, ARRIVAL_LEDGE, random), "the entrance is no room");
        assertNull(Market.browseSpot(100_000_000, ARRIVAL_LEDGE, random));
        assertNull(Market.browseSpot(ROOM, null, random));
    }

    @Test
    void movingOnWhileWalkingToItsStallLetsTheSpotGo() {
        Population population = mock(Population.class);
        CitizenStore store = mock(CitizenStore.class);
        when(population.config()).thenReturn(new PopulationConfig());
        when(population.store()).thenReturn(store);
        when(store.prices()).thenReturn(List.of());
        Market market = new Market(population);

        List<CharacterSpace> spaces = FreeMarketCharacterSpaceCatalog.spaces(ROOM);
        CharacterSpaceReservation spot = CharacterSpaceReservationRuntime.reserveExact(new CharacterSpaceScope(0, 1, ROOM),
                CharacterSpaceOwner.character(CITIZEN), spaces, spaces.get(20), 1).orElseThrow();
        CitizenSession session = new CitizenSession(citizen(), 0L, 60_000L, false);
        session.stallSpot = spot;

        market.onLeave(session);

        assertNull(session.stallSpot);
        assertTrue(CharacterSpaceReservationRuntime.reservation(CharacterSpaceOwner.character(CITIZEN)).isEmpty(),
                "the next merchant can take the spot");
        assertEquals(0, CharacterSpaceReservationRuntime.occupiedCount());
    }

    private static Citizen citizen() {
        return new Citizen(CITIZEN, "Mira", "evening", 0, 0, "victoria", "hunter", "relaxed-v1", "default", 1.0d, 0, 1,
                new LinkedHashSet<>(Set.of(Citizen.FLAG_MERCHANT)), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
    }
}
