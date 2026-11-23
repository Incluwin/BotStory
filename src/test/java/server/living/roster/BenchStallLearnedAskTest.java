package server.living.roster;

import org.junit.jupiter.api.Test;
import server.living.population.PriceLearning;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * A live bench STALL priced by learned starting asks (docs/WORLD_SYSTEMS_PLAN.md I2.2): a slice that ran its whole
 * length or sold out rewards each item's arm per bundle listed and sold; one cut short teaches nothing.
 */
class BenchStallLearnedAskTest {
    private static final int ORE = 4010000;
    private static final int HAT = 1002094;

    private static BenchStallForm.Stall stall() {
        BenchStallForm.Stall stall = new BenchStallForm.Stall();
        stall.stocked.put(ORE, 300);
        stall.perBundle.put(ORE, 100);
        stall.sold.put(ORE, 100);
        stall.stocked.put(HAT, 1);
        stall.perBundle.put(HAT, 1);
        stall.learned.put(ORE, new double[]{3, 1.0d});
        stall.learned.put(HAT, new double[]{8, 1.25d});
        return stall;
    }

    @Test
    void aFullSliceRewardsEachLearnedLinePerBundle() {
        PriceLearning learner = mock(PriceLearning.class);
        BenchStallForm.settleLearned(stall(), BenchStallForm.FULL_SLICE, learner);
        verify(learner).benchSettled(ORE, 3, 1.0d, 3, 1);
        verify(learner).benchSettled(HAT, 8, 1.25d, 1, 0);
    }

    @Test
    void aSoldOutStallCountsAndACutShortOneDoesNot() {
        PriceLearning soldOut = mock(PriceLearning.class);
        BenchStallForm.settleLearned(stall(), BenchStallForm.SOLD_OUT, soldOut);
        verify(soldOut).benchSettled(ORE, 3, 1.0d, 3, 1);
        PriceLearning called = mock(PriceLearning.class);
        BenchStallForm.settleLearned(stall(), "called back", called);
        verify(called, never()).benchSettled(anyInt(), anyInt(), anyDouble(), anyInt(), anyInt());
        BenchStallForm.settleLearned(new BenchStallForm.Stall(), BenchStallForm.FULL_SLICE, called);
        verify(called, never()).benchSettled(anyInt(), anyInt(), anyDouble(), anyInt(), anyInt());
        BenchStallForm.settleLearned(stall(), BenchStallForm.FULL_SLICE, null); // no learner: nothing, nothing thrown
    }
}
