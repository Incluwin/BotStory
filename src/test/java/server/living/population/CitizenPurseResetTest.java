package server.living.population;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decision D1 (docs/ECONOMY_PLAN.md section 7): a reset purse is a level-shaped draw, stable per character (a second
 * boot computes the same), with its median at PURSE_RESET_MEDIAN x expectedMeso and a lognormal spread, so a crowd of
 * Lv 70 citizens still has its poor and its rich.
 */
class CitizenPurseResetTest {
    @Test
    void thePurseIsStablePerCharacter() {
        assertEquals(CitizenPurseReset.purse(812, 70, 3.0d, 0.6d), CitizenPurseReset.purse(812, 70, 3.0d, 0.6d));
    }

    @Test
    void aLevelsPursesCentreOnThreeTimesItsExpectedMesoWithASpread() {
        List<Integer> purses = new ArrayList<>();
        for (int id = 1; id <= 2_001; id++) {
            purses.add(CitizenPurseReset.purse(id, 70, 3.0d, 0.6d));
        }
        purses.sort(null);
        double expected = Market.expectedMeso(70);
        double median = purses.get(purses.size() / 2);
        assertTrue(Math.abs(median / (3.0d * expected) - 1d) < 0.08, "median " + median + " vs " + 3 * expected);
        assertTrue(purses.get(purses.size() / 10) < 0.6d * median, "a poorer tenth");
        assertTrue(purses.get(purses.size() * 9 / 10) > 1.6d * median, "a richer tenth");
        assertTrue(purses.get(0) >= 0);
    }

    @Test
    void higherLevelsHoldMore() {
        long low = 0L;
        long high = 0L;
        for (int id = 1; id <= 500; id++) {
            low += CitizenPurseReset.purse(id, 30, 3.0d, 0.6d);
            high += CitizenPurseReset.purse(id, 90, 3.0d, 0.6d);
        }
        assertTrue(high > 4 * low, "Lv 90 purses " + high + " vs Lv 30 " + low);
    }
}
