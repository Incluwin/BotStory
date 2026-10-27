package server.living.population;

import org.junit.jupiter.api.Test;
import server.living.population.PopulationContent.Field;
import server.living.roster.BenchYieldModel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Citizens learn where to train (docs/BOT_CONCEPT_PLAN.md F2.1, CitizenSpots): each fitting field is scored on the EXP
 * an hour citizens of its class line and band measured there, shrunk toward the fit prior, less its deaths; plus a UCB
 * bonus that shrinks as a field is measured; less a crowding term for the loaded citizens already there. The pick is a
 * weighted draw, exp((ucb - best) / temperature), never all to the best field.
 */
class CitizenSpotsTest {
    private static final int LEVEL = 25;
    private static final int WARRIOR = 110;
    private static final double BASE = 10_000.0d; // the ledger's EXP an hour at Lv25 here
    private static final double EXPLORE = 0.3d;
    private static final double CROWDING = 0.5d;
    private static final double PRIOR_SLICES = 1.0d;
    private static final double DEATH_COST = 0.5d;

    private final Field rich = field(101, LEVEL, 10);
    private final Field poor = field(102, LEVEL, 10);
    private final Field fresh = field(103, LEVEL, 10);
    private final BenchYieldModel yields = mock(BenchYieldModel.class);
    private final Map<Integer, Integer> occupancy = new HashMap<>();

    private static Field field(int mapId, int mobLevel, int capacity) {
        return new Field(mapId, "field " + mapId, "", "victoria", false, 10, mobLevel, capacity);
    }

    private void measured(Field field, double samples, double expPerHour, double deathsPerHour) {
        when(yields.citizenMeasured(BenchYieldModel.Key.of(WARRIOR, LEVEL, field.mapId())))
                .thenReturn(new BenchYieldModel.Rates(samples, expPerHour, 0.0d, 0.0d, deathsPerHour));
    }

    private List<CitizenSpots.Arm> arms(List<Field> fields) {
        return CitizenSpots.arms(fields, LEVEL, WARRIOR, yields, BASE, mapId -> occupancy.getOrDefault(mapId, 0),
                EXPLORE, CROWDING, PRIOR_SLICES, DEATH_COST);
    }

    @Test
    void theScoreIsTheMeasuredExpShrunkTowardThePriorLessItsDeaths() {
        when(yields.citizenMeasured(any())).thenReturn(null);
        measured(rich, 9.0d, 2.0d * BASE, 0.0d); // nine slices at twice the ledger's rate
        measured(poor, 1.0d, 0.2d * BASE, 1.0d); // one slice, a fifth of it, and a death

        List<CitizenSpots.Arm> arms = arms(List.of(rich, poor, fresh));

        assertEquals(BenchYieldModel.shrink(9.0d, 2.0d, 1.0d, PRIOR_SLICES)
                - DEATH_COST * BenchYieldModel.shrink(9.0d, 0.0d, 0.05d, PRIOR_SLICES), arms.get(0).score(), 1e-9);
        assertEquals(1.9d - DEATH_COST * 0.005d, arms.get(0).score(), 1e-9, "nine slices: 90% the measurement");
        assertEquals(0.6d - DEATH_COST * 0.525d, arms.get(1).score(), 1e-9, "one slice: halfway to the prior");
        assertEquals(1.0d - DEATH_COST * 0.05d, arms.get(2).score(), 1e-9, "nothing measured: the fit prior alone");
    }

    @Test
    void aFieldMeasuredLessGetsTheBiggerUcbBonus() {
        when(yields.citizenMeasured(any())).thenReturn(null);
        measured(rich, 9.0d, BASE, 0.0d);
        measured(poor, 1.0d, BASE, 0.0d);

        List<CitizenSpots.Arm> arms = arms(List.of(rich, poor, fresh));

        double total = 10.0d;
        assertEquals(EXPLORE * Math.sqrt(Math.log(1.0d + total) / 10.0d), arms.get(0).bonus(), 1e-9);
        assertEquals(EXPLORE * Math.sqrt(Math.log(1.0d + total) / 2.0d), arms.get(1).bonus(), 1e-9);
        assertEquals(EXPLORE * Math.sqrt(Math.log(1.0d + total)), arms.get(2).bonus(), 1e-9, "never measured: the most");
        assertTrue(arms.get(2).bonus() > arms.get(1).bonus() && arms.get(1).bonus() > arms.get(0).bonus());
    }

    @Test
    void fewerBotsAlreadyOnAMapScoreHigher() {
        when(yields.citizenMeasured(any())).thenReturn(null);
        occupancy.put(101, 8); // eight of ten places taken
        occupancy.put(102, 2);

        List<CitizenSpots.Arm> arms = arms(List.of(rich, poor, fresh));

        assertEquals(CROWDING * 0.8d, arms.get(0).crowd(), 1e-9);
        assertEquals(CROWDING * 0.2d, arms.get(1).crowd(), 1e-9);
        assertEquals(0.0d, arms.get(2).crowd(), 1e-9);
        assertTrue(arms.get(2).ucb() > arms.get(1).ucb() && arms.get(1).ucb() > arms.get(0).ucb(),
                "same fields, same measurements: the emptier one first");
    }

    @Test
    void thePickIsAWeightedDrawNeverAllToTheBest() {
        when(yields.citizenMeasured(any())).thenReturn(null);
        measured(rich, 20.0d, 1.4d * BASE, 0.0d);
        measured(poor, 20.0d, 1.2d * BASE, 0.0d);
        measured(fresh, 20.0d, 0.5d * BASE, 0.0d);
        List<CitizenSpots.Arm> arms = arms(List.of(rich, poor, fresh));
        double temperature = 0.15d;

        double[] weights = CitizenSpots.weights(arms, temperature);
        assertEquals(1.0d, weights[0], 1e-9, "the best weighs 1");
        assertEquals(Math.exp((arms.get(1).ucb() - arms.get(0).ucb()) / temperature), weights[1], 1e-9);

        Map<Integer, Integer> drawn = new HashMap<>();
        Random random = new Random(7);
        int draws = 4_000;
        for (int i = 0; i < draws; i++) {
            drawn.merge(CitizenSpots.draw(arms, temperature, random).mapId(), 1, Integer::sum);
        }
        double total = weights[0] + weights[1] + weights[2];
        assertEquals(weights[0] / total, drawn.get(101) / (double) draws, 0.03d);
        assertEquals(weights[1] / total, drawn.get(102) / (double) draws, 0.03d, "the second best still gets its share");
        assertTrue(drawn.getOrDefault(103, 0) < draws / 50, "a much worse field is all but never drawn");
        assertEquals(101, CitizenSpots.draw(arms, 0.0d, random).mapId(), "temperature 0: always the best");
    }
}
