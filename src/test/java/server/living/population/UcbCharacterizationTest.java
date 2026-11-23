package server.living.population;

import org.junit.jupiter.api.Test;
import server.living.population.PopulationContent.Field;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The shared UCB arithmetic (docs/WORLD_SYSTEMS_PLAN.md I2.1) changes neither old user: CitizenSpots' weighted draw and
 * weights and InviteBandit's optimistic rate equal, draw for draw on the same seed, the copies they carried before the
 * lift (kept here verbatim as the reference).
 */
class UcbCharacterizationTest {

    // ---- the pre-I2 code, verbatim -----------------------------------------------------------

    private static Field referenceDraw(List<CitizenSpots.Arm> arms, double temperature, Random random) {
        CitizenSpots.Arm best = null;
        for (CitizenSpots.Arm arm : arms) {
            if (best == null || arm.ucb() > best.ucb()) {
                best = arm;
            }
        }
        if (best == null || temperature <= 0.0d) {
            return best == null ? null : best.field();
        }
        double[] weights = referenceWeights(arms, temperature);
        double total = 0.0d;
        for (double weight : weights) {
            total += weight;
        }
        double roll = random.nextDouble() * total;
        for (int i = 0; i < arms.size(); i++) {
            roll -= weights[i];
            if (roll < 0.0d) {
                return arms.get(i).field();
            }
        }
        return best.field();
    }

    private static double[] referenceWeights(List<CitizenSpots.Arm> arms, double temperature) {
        double best = Double.NEGATIVE_INFINITY;
        for (CitizenSpots.Arm arm : arms) {
            best = Math.max(best, arm.ucb());
        }
        double[] out = new double[arms.size()];
        for (int i = 0; i < arms.size(); i++) {
            out[i] = Math.exp((arms.get(i).ucb() - best) / temperature);
        }
        return out;
    }

    private static double referenceOptimistic(int asked, int yes, int total, double explore) {
        double mean = asked == 0 ? 0.0d : yes / (double) asked;
        return mean + explore * Math.sqrt(Math.log(1.0d + total) / (1.0d + asked));
    }

    private static double referenceBonus(double explore, double total, double n) {
        return explore * Math.sqrt(Math.log(1.0d + total) / (1.0d + n));
    }

    // ---- the checks ------------------------------------------------------------------------

    private static List<CitizenSpots.Arm> randomArms(Random shape) {
        List<CitizenSpots.Arm> arms = new ArrayList<>();
        int count = shape.nextInt(6); // 0..5, an empty list included
        for (int i = 0; i < count; i++) {
            Field field = new Field(100 + i, "f" + i, "", "victoria", false, 10, 20, 10);
            double score = shape.nextInt(4) == 0 ? 0.5d : shape.nextDouble() * 2.0d; // ties now and then
            arms.add(new CitizenSpots.Arm(field, score, shape.nextDouble() * 0.3d, shape.nextDouble() * 0.5d));
        }
        return arms;
    }

    @Test
    void citizenSpotsDrawsExactlyAsBeforeOnTheSameSeed() {
        Random shape = new Random(7L);
        for (int trial = 0; trial < 500; trial++) {
            List<CitizenSpots.Arm> arms = randomArms(shape);
            double temperature = new double[]{0.0d, -1.0d, 0.05d, 0.2d, 1.0d}[trial % 5];
            long seed = shape.nextLong();
            Random before = new Random(seed);
            Random after = new Random(seed);
            for (int draw = 0; draw < 20; draw++) {
                assertEquals(referenceDraw(arms, temperature, before), CitizenSpots.draw(arms, temperature, after),
                        "trial " + trial + " draw " + draw);
            }
            assertEquals(before.nextLong(), after.nextLong(), "the same random numbers used");
            if (!arms.isEmpty() && temperature > 0.0d) {
                assertArrayEquals(referenceWeights(arms, temperature), CitizenSpots.weights(arms, temperature), 0.0d);
            }
        }
    }

    @Test
    void inviteBanditsOptimisticRateIsUnchanged() {
        Random shape = new Random(11L);
        for (int trial = 0; trial < 2_000; trial++) {
            int asked = shape.nextInt(50);
            int yes = asked == 0 ? 0 : shape.nextInt(asked + 1);
            int total = asked + shape.nextInt(200);
            double explore = shape.nextDouble();
            assertEquals(referenceOptimistic(asked, yes, total, explore),
                    InviteBandit.optimistic(asked, yes, total, explore), 0.0d);
        }
    }

    @Test
    void theSharedBonusIsTheOldFormula() {
        Random shape = new Random(13L);
        for (int trial = 0; trial < 2_000; trial++) {
            double total = shape.nextInt(500);
            double n = shape.nextDouble() * total;
            double explore = shape.nextDouble();
            assertEquals(referenceBonus(explore, total, n), Ucb.bonus(explore, total, n), 0.0d);
        }
    }
}
