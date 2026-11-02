package server.living.population.casino;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Casino chips (the four stamps at 10k/50k/250k/1m) and cho-han dice (SoloMapling's DiceBot): change is made in the
 * fewest chips, a bet out of a purse never spends more than asked, and a dice bet pays evens on a match.
 */
class ChipsAndDiceTest {
    @Test
    void changeIsTheFewestChipsRoundedDownToTheSmallest() {
        assertEquals(Map.of(Chips.SLIME, 2, Chips.STUMP, 1, Chips.BLUE_SNAIL, 1, Chips.SNAIL, 2), Chips.change(2_324_999));
        assertEquals(Map.of(), Chips.change(9_999));
        assertEquals(2_320_000L, Chips.value(Chips.change(2_324_999)));
    }

    @Test
    void aBetFromAPurseUsesOnlyChipsItHoldsAndNeverMoreThanAsked() {
        Map<Integer, Integer> held = Map.of(Chips.SLIME, 1, Chips.SNAIL, 30);
        Map<Integer, Integer> bet = Chips.from(held, 600_000);
        assertEquals(Map.of(Chips.SNAIL, 30), bet, "no 1m chip for a 600k bet, and only the 10k chips it has");
        assertEquals(Map.of(), Chips.from(Map.of(Chips.SLIME, 3), 500_000));
    }

    @Test
    void naturalsRoundUpToTheSmallestChip() {
        assertEquals(20_000L, Chips.threeToTwo(10_000));
        assertEquals(1_500_000L, Chips.threeToTwo(1_000_000));
        assertEquals(380_000L, Chips.threeToTwo(250_000));
    }

    @Test
    void betsAreSaidAsPlayersSayThem() {
        assertEquals("1.5m", Chips.mesos(1_500_000));
        assertEquals("2m", Chips.mesos(2_000_000));
        assertEquals("250k", Chips.mesos(250_000));
        assertEquals("380k", Chips.describe(Map.of(Chips.STUMP, 1, Chips.BLUE_SNAIL, 2, Chips.SNAIL, 3)));
        assertFalse(Chips.isChip(4002004));
        assertEquals(0, Chips.value(4000000));
    }

    /** A dice table whose bettor has {@code down} chips on the floor; the host takes them in when a bet is called. */
    private static final class DiceTable implements ChoHanGame.Table {
        final List<String> said = new ArrayList<>();
        final Map<Integer, Map<Integer, Integer>> down = new HashMap<>();
        final List<Integer> taken = new ArrayList<>();
        final Map<Integer, Long> paid = new HashMap<>();
        final Map<Integer, Boolean> won = new HashMap<>();

        @Override
        public void say(String trigger, Map<String, Object> args) {
            said.add(trigger);
        }

        @Override
        public Map<Integer, Integer> takeBet(int bettorId) {
            Map<Integer, Integer> chips = down.getOrDefault(bettorId, Map.of());
            if (!chips.isEmpty()) {
                taken.add(bettorId);
            }
            return chips;
        }

        @Override
        public void pay(int bettorId, long mesos) {
            paid.merge(bettorId, mesos, Long::sum);
        }

        @Override
        public void settled(int bettorId, boolean won, long value) {
            this.won.put(bettorId, won);
        }
    }

    private static boolean roll(ChoHanGame game, long now) {
        game.tick(now + ChoHanGame.ROLL_MS);
        game.tick(now + ChoHanGame.ROLL_MS + ChoHanGame.SETTLE_MS);
        int[] dice = game.dice();
        return (dice[0] + dice[1]) % 2 == 0;
    }

    @Test
    void aMatchingCallPaysEvensAndAMissGoesToTheHost() {
        DiceTable table = new DiceTable();
        table.down.put(1, Map.of(Chips.STUMP, 2));
        ChoHanGame game = new ChoHanGame(table, new Random(3));
        int wins = 0;
        int losses = 0;
        for (int i = 0; i < 40; i++) {
            table.paid.clear();
            long now = i * 10_000L;
            assertTrue(game.call(1, "Ann", true, now));
            assertEquals(1, game.bettor());
            boolean even = roll(game, now);
            assertEquals(ChoHanGame.Phase.OPEN, game.phase());
            if (even) {
                wins++;
                assertEquals(1_000_000L, table.paid.get(1), "the 500k stake back and as much again");
                assertTrue(table.won.get(1));
            } else {
                losses++;
                assertFalse(table.paid.containsKey(1), "the host keeps a missed bet");
                assertFalse(table.won.get(1));
            }
        }
        assertTrue(wins > 5 && losses > 5, "both sides come up: " + wins + "/" + losses);
    }

    @Test
    void noChipsDownNoBetAndOneBetAtATime() {
        DiceTable table = new DiceTable();
        ChoHanGame game = new ChoHanGame(table, new Random(3));
        assertFalse(game.call(1, "Ann", true, 0));
        assertTrue(table.said.contains("casino.dice.nobet"));
        table.down.put(1, Map.of(Chips.SNAIL, 1));
        table.down.put(2, Map.of(Chips.SNAIL, 1));
        assertTrue(game.call(1, "Ann", false, 0));
        assertFalse(game.call(2, "Bo", true, 0), "the table holds one bet until it is settled");
    }

    @Test
    void theHostTakesTheChipsInWhenTheBetIsCalledAndHandsThemBackIfItCloses() {
        DiceTable table = new DiceTable();
        table.down.put(1, Map.of(Chips.SNAIL, 1));
        ChoHanGame game = new ChoHanGame(table, new Random(3));
        assertTrue(game.call(1, "Ann", true, 0));
        assertEquals(List.of(1), table.taken, "off the floor before the dice are rolled");
        game.close();
        assertEquals(10_000L, table.paid.get(1));
        assertEquals(ChoHanGame.Phase.OPEN, game.phase());
    }

    @Test
    void aBettorWhoWalksOffLeavesItsChipsToTheHost() {
        DiceTable table = new DiceTable();
        table.down.put(1, Map.of(Chips.SNAIL, 1));
        ChoHanGame game = new ChoHanGame(table, new Random(3));
        assertTrue(game.call(1, "Ann", true, 0));
        game.abandon(1);
        assertTrue(table.paid.isEmpty(), "walking off before the roll forfeits the bet");
        assertEquals(ChoHanGame.Phase.OPEN, game.phase());
    }
}
