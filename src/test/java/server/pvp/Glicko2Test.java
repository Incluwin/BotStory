package server.pvp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Glicko2Test {
    private static Glicko2.Rating r(double rating, double deviation) {
        return new Glicko2.Rating(rating, deviation, Glicko2.START_VOLATILITY);
    }

    @Test
    void glickmansWorkedExampleComesOutTheSame() {
        Glicko2.Rating player = r(1500, 200);
        Glicko2.Rating next = Glicko2.update(player, List.of(
                new Glicko2.Result(r(1400, 30), 1d),
                new Glicko2.Result(r(1550, 100), 0d),
                new Glicko2.Result(r(1700, 300), 0d)));
        assertEquals(1464.06, next.rating(), 0.05);
        assertEquals(151.52, next.deviation(), 0.05);
        assertEquals(0.05999, next.volatility(), 0.00001);
    }

    @Test
    void evenRatingsAreACoinFlipAndChancesAddUpToOne() {
        assertEquals(0.5, Glicko2.winChance(r(1500, 350), r(1500, 80)), 1e-9);
        Glicko2.Rating strong = r(1700, 60);
        Glicko2.Rating weak = r(1450, 120);
        double p = Glicko2.winChance(strong, weak);
        assertTrue(p > 0.75 && p < 0.95, "p=" + p);
        assertEquals(1d, p + Glicko2.winChance(weak, strong), 1e-9);
    }

    @Test
    void anUpsetMovesBothRatingsMoreThanAnExpectedWin() {
        Glicko2.Rating strong = r(1700, 80);
        Glicko2.Rating weak = r(1500, 80);
        double expectedGain = Glicko2.update(strong, weak, 1d).rating() - strong.rating();
        double upsetGain = Glicko2.update(weak, strong, 1d).rating() - weak.rating();
        assertTrue(expectedGain > 0 && upsetGain > expectedGain, expectedGain + " vs " + upsetGain);
        assertTrue(Glicko2.update(strong, weak, 0d).rating() < strong.rating());
    }

    @Test
    void aFreshRatingMovesFarAndSettlesAsItPlays() {
        Glicko2.Rating fresh = Glicko2.Rating.initial();
        Glicko2.Rating afterOne = Glicko2.update(fresh, r(1500, 80), 1d);
        assertTrue(afterOne.rating() - 1500 > 100, "first win moved " + (afterOne.rating() - 1500));
        assertTrue(afterOne.deviation() < fresh.deviation());

        Glicko2.Rating settled = afterOne;
        for (int i = 0; i < 30; i++) {
            settled = Glicko2.update(settled, r(settled.rating(), 80), i % 2 == 0 ? 1d : 0d);
        }
        assertTrue(settled.deviation() < 90, "deviation=" + settled.deviation());
        assertTrue(settled.deviation() >= Glicko2.MIN_DEVIATION);
    }

    @Test
    void idleDaysWidenTheDeviationUpToTheStart() {
        Glicko2.Rating settled = new Glicko2.Rating(1620, 60, 0.06);
        Glicko2.Rating week = Glicko2.idle(settled, 7);
        assertEquals(1620, week.rating(), 1e-9);
        assertTrue(week.deviation() > 60);
        assertEquals(Glicko2.START_DEVIATION, Glicko2.idle(settled, 1_000_000).deviation(), 1e-9);
        assertEquals(settled, Glicko2.idle(settled, 0));
    }
}
