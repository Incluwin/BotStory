package server.living.population;

import org.junit.jupiter.api.Test;
import server.living.population.PopulationContent.Region;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionTownWeightsTest {
    private static final int HENESYS = 100000000;
    private static final int ELLINIA = 101000000;
    private static final int KERNING = 103000000;

    @Test
    void uniformWhenNoWeightsAreGiven() {
        Region region = new Region("v", 1, 45, List.of(HENESYS, ELLINIA, KERNING), List.of(), List.of(), 910000000, 1);
        Map<Integer, Integer> counts = new HashMap<>();
        for (int seed = 0; seed < 3000; seed++) {
            counts.merge(region.townFor(seed), 1, Integer::sum);
        }
        assertEquals(1000, counts.get(HENESYS));
        assertEquals(1000, counts.get(ELLINIA));
        assertEquals(1000, counts.get(KERNING));
    }

    @Test
    void hubWeightsDrawProportionallyMoreCitizens() {
        Region region = new Region("v", 1, 45, List.of(HENESYS, ELLINIA, KERNING), List.of(6, 1, 3), List.of(), 910000000, 1);
        Map<Integer, Integer> counts = new HashMap<>();
        for (int seed = 0; seed < 10000; seed++) {
            counts.merge(region.townFor(seed), 1, Integer::sum);
        }
        assertEquals(6000, counts.get(HENESYS));
        assertEquals(1000, counts.get(ELLINIA));
        assertEquals(3000, counts.get(KERNING));
    }

    @Test
    void negativeSeedsStillLandOnATown() {
        Region region = new Region("v", 1, 45, List.of(HENESYS, KERNING), List.of(1, 1), List.of(), 910000000, 1);
        assertTrue(List.of(HENESYS, KERNING).contains(region.townFor(-12345)));
    }

    @Test
    void mismatchedWeightListIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new Region("v", 1, 45, List.of(HENESYS, KERNING), List.of(1), List.of(), 910000000, 1));
    }
}
