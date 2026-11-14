package server.living.population.rivals;

import config.PvpConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RivalRewardsTest {
    private final PvpConfig config = new PvpConfig();

    private static RivalRewards.Fighter f(double edge, int level) {
        return new RivalRewards.Fighter(edge, level);
    }

    @Test
    void evenSidesAreEvenOddsAndALeadIsWorthWhatPracticeShowed() {
        assertEquals(0.5d, RivalRewards.winChance(List.of(f(1, 70)), List.of(f(1, 70)), config), 1e-9);
        // a 5-level lead at matched gear: pace ratio 1.05 / 0.95, practice won 57-60%
        assertEquals(0.599d, RivalRewards.winChance(List.of(f(1, 55)), List.of(f(1, 50)), config), 0.002d);
        // a 1.2 gear edge (G^gamma) at the same level
        assertEquals(0.675d, RivalRewards.winChance(List.of(f(1.2, 80)), List.of(f(1, 80)), config), 0.002d);
        // two fighters a side: the means count
        assertEquals(0.5d, RivalRewards.winChance(List.of(f(1.2, 80), f(1 / 1.2, 80)), List.of(f(1, 80)), config),
                1e-9);
    }

    @Test
    void theOddsScaleAWinBetweenThreeQuartersAndFiveQuarters() {
        assertEquals(1d, RivalRewards.oddsMultiplier(0.5d), 1e-9);
        assertEquals(0.75d, RivalRewards.oddsMultiplier(1d), 1e-9);
        assertEquals(1.25d, RivalRewards.oddsMultiplier(0d), 1e-9);
    }

    @Test
    void aWinPaysExpTrophiesStakeAndSometimesAScroll() {
        assertEquals(new RivalRewards.Plan(40_000, 3, 5_000, 2043001),
                RivalRewards.win(0.5d, true, 1_000d, 5_000, 2043001, 0.2d));
        assertEquals(new RivalRewards.Plan(20_000, 3, 5_000, 0),
                RivalRewards.win(0.5d, false, 1_000d, 5_000, 2043001, 0.3d));
        // the underdog's scroll chance is 25% x 1.25
        assertEquals(new RivalRewards.Plan(25_000, 3, 0, 2043001),
                RivalRewards.win(0d, false, 1_000d, 0, 2043001, 0.3d));
        // more than 85% likely: the Trophies alone
        assertEquals(new RivalRewards.Plan(0, 3, 0, 0), RivalRewards.win(0.9d, true, 1_000d, 5_000, 2043001, 0d));
    }

    @Test
    void aLossPaysATrophyAndExpOnlyForHalfTheirHp() {
        assertEquals(new RivalRewards.Plan(5_000, 1, 0, 0), RivalRewards.loss(true, 1_000d));
        assertEquals(new RivalRewards.Plan(0, 1, 0, 0), RivalRewards.loss(false, 1_000d));
    }

    @Test
    void theStakeIsThirtyMesoGrindMinutesAtTheOdds() {
        assertEquals(85_536L, RivalRewards.stake(0.5d, 2_851.2d));
        assertEquals(106_920L, RivalRewards.stake(0d, 2_851.2d));
        assertEquals(0L, RivalRewards.stake(0.9d, 2_851.2d));
    }

    @Test
    void eachWeaponGetsItsOwnSixtyPercentAttackScroll() {
        assertEquals(2043001, RivalRewards.attackScrollFor(1302000));
        assertEquals(2043701, RivalRewards.attackScrollFor(1372005));
        assertEquals(2044301, RivalRewards.attackScrollFor(1432000));
        assertEquals(2044901, RivalRewards.attackScrollFor(1492000));
        assertEquals(0, RivalRewards.attackScrollFor(1342000));
        assertEquals(0, RivalRewards.attackScrollFor(0));
    }

    @Test
    void theStakeComesEvenlyFromPursesAndNeverOverdrawsOne() {
        assertArrayEquals(new long[] {75, 75}, RivalRewards.shares(new long[] {100, 100}, 150));
        assertArrayEquals(new long[] {10, 290}, RivalRewards.shares(new long[] {10, 1_000}, 300));
        assertArrayEquals(new long[] {10, 20}, RivalRewards.shares(new long[] {10, 20}, 100));
        assertArrayEquals(new long[] {0, 0}, RivalRewards.shares(new long[] {10, 20}, 0));
        assertArrayEquals(new long[0], RivalRewards.shares(new long[0], 50));
    }
}
