package server.living.audit;

import org.junit.jupiter.api.Test;
import server.living.audit.TellStats.Dist;
import server.living.audit.TellStats.Mode;
import server.living.audit.TellStats.PerBot;
import server.living.audit.TellStats.Percents;
import server.living.audit.TellStats.Spread;
import server.living.audit.TellStats.View;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The tell audit's arithmetic: spread, quantiles, per-bot medians, the commonest value's share and stacks. */
class TellStatsTest {
    @Test
    void theCoefficientOfVariationIsTheSpreadOverTheMean() {
        assertEquals(0.5, TellStats.cv(2, 1 + 3, 1 + 9), 1e-9, "1 and 3: mean 2, deviation 1");
        assertEquals(0, TellStats.cv(3, 300, 30_000), 1e-9, "three equal values do not vary");
        assertEquals(0, TellStats.cv(1, 5, 25), "one sample has no spread");
        assertEquals(0, TellStats.cv(2, 0, 0), "a zero mean has none either");
    }

    @Test
    void aDistributionKeepsCountMeanExtremesAndQuantiles() {
        Dist dist = new Dist();
        for (int value = 100; value >= 1; value--) {
            dist.add(value);
        }
        View view = dist.view();
        assertEquals(100, view.n());
        assertEquals(50.5, view.mean(), 1e-9);
        assertEquals(1, view.min());
        assertEquals(100, view.max());
        assertEquals(5, view.p5());
        assertEquals(50, view.p50());
        assertEquals(90, view.p90());
        assertEquals(View.EMPTY, new Dist().view());
    }

    @Test
    void quantilesComeFromTheRecentSamplesAndTheRestFromAll() {
        Dist dist = new Dist();
        dist.add(1);
        for (int i = 0; i < TellStats.RECENT; i++) {
            dist.add(500);
        }
        View view = dist.view();
        assertEquals(TellStats.RECENT + 1, view.n());
        assertEquals(1, view.min(), "the minimum covers every sample");
        assertEquals(500, view.p5(), "the first sample has left the recent window");
    }

    @Test
    void theMedianPerBotSpreadCountsOnlyBotsWithEnoughSamples() {
        PerBot perBot = new PerBot();
        for (int i = 0; i < 10; i++) {
            perBot.add(1, 300);                       // never varies: CV 0
            perBot.add(2, i % 2 == 0 ? 100 : 300);    // mean 200, deviation 100: CV 0.5
            perBot.add(3, i % 2 == 0 ? 200 : 600);    // mean 400, deviation 200: CV 0.5
        }
        perBot.add(4, 1);                             // one sample: left out
        Spread spread = perBot.medianCv(10);
        assertEquals(3, spread.bots());
        assertEquals(0.5, spread.medianCv(), 1e-9);
        assertTrue(Double.isNaN(new PerBot().medianCv(1).medianCv()), "no bots, no spread");
    }

    @Test
    void theModeIsTheCommonestPercentAndItsShare() {
        Percents percents = new Percents();
        for (int i = 0; i < 9; i++) {
            percents.add(70);
        }
        percents.add(65);
        Mode mode = percents.mode();
        assertEquals(10, mode.n());
        assertEquals(70, mode.value());
        assertEquals(0.9, mode.share(), 1e-9);

        Percents clamped = new Percents();
        clamped.add(140);
        clamped.add(-5);
        clamped.add(100);
        assertEquals(100, clamped.mode().value(), "values past 100 count at 100");
        assertEquals(0, new Percents().mode().share(), "empty");
    }

    @Test
    void threeIdleBotsWithin30PxStandInAStackTwoDoNot() {
        assertEquals(3, TellStats.stacked(new int[]{0, 10, 20}, new int[]{0, 0, 5}, 3, 30, 3));
        assertEquals(0, TellStats.stacked(new int[]{0, 10}, new int[]{0, 0}, 2, 30, 3), "two is a pair");
        assertEquals(0, TellStats.stacked(new int[]{0, 100, 200}, new int[]{0, 0, 0}, 3, 30, 3), "spread out");
        assertEquals(1, TellStats.stacked(new int[]{0, 25, 50}, new int[]{0, 0, 0}, 3, 30, 3),
                "in a row 25 px apart only the middle one has two others within 30 px");
        assertEquals(0, TellStats.stacked(new int[]{0, 30, 0}, new int[]{0, 0, 31}, 3, 30, 3),
                "30 px apart counts, 31 px and the diagonal do not");
    }

    @Test
    void quantilesAndMediansOnSmallInputs() {
        assertEquals(0, TellStats.quantile(new long[0], 0.5));
        assertEquals(7, TellStats.quantile(new long[]{7}, 0.05));
        assertEquals(2.5, TellStats.median(new double[]{4, 1, 3, 2}, 4), 1e-9);
        assertEquals(3, TellStats.median(new double[]{5, 3, 1, 99}, 3), 1e-9, "only the first count values");
        assertTrue(Double.isNaN(TellStats.median(new double[0], 0)));
    }
}
