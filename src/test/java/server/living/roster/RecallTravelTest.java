package server.living.roster;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecallTravelTest {
    @Test
    void sameMapIsInstantAndNeighboursAreOneHop() {
        assertEquals(0L, RecallTravel.etaMs(104000000, 104000000));
        assertEquals(1, RecallTravel.hops(104, 100), "Lith Harbor -> Henesys");
        assertEquals(2, RecallTravel.hops(104, 101), "Lith Harbor -> Henesys -> Ellinia");
        assertEquals(1, RecallTravel.hops(102, 103), "Perion -> Kerning City");
        assertEquals(2, RecallTravel.hops(105, 104), "Sleepywood -> Henesys -> Lith");
    }

    @Test
    void fieldsCostMoreThanTownsAndOffIslandIsFixed() {
        long toTown = RecallTravel.etaMs(104000000, 100000000);
        long toField = RecallTravel.etaMs(104000000, 104040000);
        assertEquals(60_000L, toTown, "20 s base + 40 s per hop");
        assertEquals(40_000L, toField, "same region field: base plus field extra");
        assertTrue(RecallTravel.etaMs(104000000, 211000000) >= 180_000L, "Orbis is off the island");
        assertEquals("~60 s", RecallTravel.describe(toTown));
        assertEquals("~2 min", RecallTravel.describe(100_000L));
        assertEquals("~40 s", RecallTravel.describe(toField));
    }
}
