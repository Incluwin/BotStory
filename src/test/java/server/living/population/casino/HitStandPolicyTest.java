package server.living.population.casino;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A citizen's hit-or-stand choice at the blackjack table: it starts from SoloMapling's casual rule and learns from the
 * hands it plays, so a citizen that keeps busting on 16 against a 6 learns to stand there.
 */
class HitStandPolicyTest {
    @Test
    void aNewCitizenPlaysSoloMaplingsCasualRule() {
        assertEquals(1, HitStandPolicy.priorHitChance(9));
        assertEquals(0.5, HitStandPolicy.priorHitChance(12), 1e-9);
        assertEquals(0.1, HitStandPolicy.priorHitChance(16), 1e-9);
        assertEquals(0.1, HitStandPolicy.priorHitChance(17), 1e-9);
        assertEquals(0, HitStandPolicy.priorHitChance(18));
        HitStandPolicy fresh = new HitStandPolicy(0.3, new Random(1));
        assertTrue(fresh.value(10, false, 6, true) > fresh.value(10, false, 6, false), "it hits a 10");
        assertTrue(fresh.value(19, false, 6, true) < fresh.value(19, false, 6, false), "it stands on 19");
    }

    @Test
    void itNeverHitsTwentyOne() {
        HitStandPolicy policy = new HitStandPolicy(0.3, new Random(1));
        for (int i = 0; i < 100; i++) {
            assertFalse(policy.hit(21, false, 10));
        }
    }

    @Test
    void handsItLosesByHittingTeachItToStand() {
        Random random = new Random(5);
        HitStandPolicy policy = new HitStandPolicy(0.3, random);
        double before = policy.value(14, false, 6, true) - policy.value(14, false, 6, false);
        for (int hand = 0; hand < 300; hand++) {
            boolean hit = policy.hit(14, false, 6);
            policy.settle(hit ? -1 : 0.3); // hitting 14 into a 6 busts here; standing wins a little on average
        }
        double after = policy.value(14, false, 6, true) - policy.value(14, false, 6, false);
        assertTrue(after < before, "the hit lost value against the stand: " + before + " -> " + after);
        HitStandPolicy unrelated = new HitStandPolicy(0.3, new Random(5));
        assertEquals(unrelated.value(14, false, 10, true), policy.value(14, false, 10, true), 1e-9,
                "what it learned against a 6 says nothing about a 10");
    }

    @Test
    void aSettleCreditsEveryChoiceOfTheHand() {
        HitStandPolicy policy = new HitStandPolicy(0.3, new Random(2));
        double hitBefore = policy.value(8, false, 9, true);
        double standBefore = policy.value(8, false, 9, false);
        boolean first = policy.hit(8, false, 9);
        policy.hit(13, false, 9);
        policy.settle(1.5);
        assertTrue(policy.value(8, false, 9, first) > (first ? hitBefore : standBefore),
                "the first choice of a won hand gains value too, not only the last");
        double settled = policy.value(8, false, 9, first);
        policy.settle(-1);
        assertEquals(settled, policy.value(8, false, 9, first), 1e-9, "a settle forgets the hand it credited");
    }
}
