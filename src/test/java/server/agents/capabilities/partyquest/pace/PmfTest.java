package server.agents.capabilities.partyquest.pace;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The exact tables the clear-time estimate draws a quest's random content from. */
class PmfTest {
    @Test
    void sumsIndependentDrawsExactlyCountingRepeatedValuesTwice() {
        Pmf two = Pmf.sumOfDraws(List.of(1, 2, 2), 2);

        assertEquals(2, two.min());
        assertEquals(4, two.max());
        assertEquals(1.0d / 9.0d, two.probability(2), 1e-12);
        assertEquals(4.0d / 9.0d, two.probability(3), 1e-12);
        assertEquals(4.0d / 9.0d, two.probability(4), 1e-12);
        assertEquals(2.0d * 5.0d / 3.0d, two.mean(), 1e-12);
        assertEquals(2.0d * (1.0d / 3.0d * 4.0d / 9.0d + 2.0d / 3.0d * 1.0d / 9.0d), two.variance(), 1e-12);
    }

    @Test
    void quantileIsTheSmallestValueWhoseCumulativeChanceReachesIt() {
        Pmf four = Pmf.uniform(1, 4);

        assertEquals(1, four.quantile(0.1d));
        assertEquals(2, four.quantile(0.5d));
        assertEquals(4, four.quantile(0.9d));
        assertEquals(2.5d, four.mean(), 1e-12);
    }

    @Test
    void zeroDrawsIsZeroAndBadMassIsRejected() {
        assertEquals(Pmf.point(0), Pmf.sumOfDraws(List.of(5, 6), 0));
        assertThrows(IllegalArgumentException.class, () -> new Pmf(0, new double[]{0.5d, 0.4d}));
        assertThrows(IllegalArgumentException.class, () -> new Pmf(0, new double[]{1.5d, -0.5d}));
        assertThrows(IllegalArgumentException.class, () -> Pmf.uniform(3, 2));
    }
}
