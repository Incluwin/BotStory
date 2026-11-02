package server.living.population.casino;

import org.junit.jupiter.api.Test;
import server.living.population.casino.BlackjackGame.Phase;
import server.living.population.casino.BlackjackGame.Result;
import server.living.population.casino.BlackjackGame.Seat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The blackjack rounds a citizen dealer runs (docs/SOLOMAPLING_PORT.md): the hole card SoloMapling never dealt is dealt
 * face down and turned over only on the dealer's turn, the dealer peeks for a natural, naturals pay 3 to 2, busts lose
 * at once, the dealer draws to 17, and a seat that picks its bet up before it is settled is sent away.
 */
class BlackjackGameTest {
    private static final Map<Integer, Integer> ONE_CHIP = Map.of(Chips.STUMP, 1);

    /** Records what the dealer does; chips are down for every seat in {@link #betting}. */
    private static final class FakeTable implements BlackjackGame.Table {
        final List<String> events = new ArrayList<>();
        final List<String> said = new ArrayList<>();
        final Map<Integer, Map<Integer, Integer>> betting = new HashMap<>();
        final Set<Integer> gone = new HashSet<>();
        final Map<Integer, Result> results = new HashMap<>();
        final Map<Integer, Long> paid = new HashMap<>();
        final List<String> onTable = new ArrayList<>();
        final List<List<String>> rounds = new ArrayList<>();
        /** Seats that hit whenever it is their turn (citizens answer through decide). */
        final Set<Integer> alwaysHit = new HashSet<>();
        BlackjackGame game;

        @Override
        public void say(String trigger, Map<String, Object> args) {
            said.add(trigger);
        }

        @Override
        public void deal(Seat seat, int index, String card, boolean faceDown) {
            events.add("deal " + (seat == null ? "dealer" : seat.name) + " " + card + (faceDown ? " down" : ""));
            onTable.add(card);
        }

        @Override
        public void reveal(String card) {
            events.add("reveal " + card);
        }

        @Override
        public Map<Integer, Integer> takeBet(Seat seat) {
            Map<Integer, Integer> down = betting.getOrDefault(seat.id, Map.of());
            if (!down.isEmpty()) {
                events.add("take " + seat.name);
            }
            return down;
        }

        @Override
        public void pay(Seat seat, long mesos) {
            paid.merge(seat.id, mesos, Long::sum);
        }

        @Override
        public void clearCards() {
            rounds.add(new ArrayList<>(onTable));
            onTable.clear();
        }

        @Override
        public void turn(Seat seat, int total, boolean soft, int dealerUp) {
            events.add("turn " + seat.name + " " + total);
            if (alwaysHit.contains(seat.id)) {
                game.decide(seat.id, true);
            }
        }

        @Override
        public boolean present(Seat seat) {
            return !gone.contains(seat.id);
        }

        @Override
        public void left(Seat seat, String reason) {
            events.add("left " + seat.name + " " + reason);
        }

        @Override
        public void settled(Seat seat, Result result, double reward) {
            results.put(seat.id, result);
        }
    }

    private final FakeTable table = new FakeTable();
    private final BlackjackGame game = new BlackjackGame(table, new Random(7));
    private long now;

    {
        table.game = game;
    }

    /** Runs ticks one second apart until the round is back to WAITING (or a limit). */
    private void playRound() {
        tick(); // WAITING -> BETTING
        now += BlackjackGame.BET_WINDOW_MS;
        for (int i = 0; i < 200; i++) {
            tick();
            if (game.phase() == Phase.WAITING) {
                now += BlackjackGame.ROUND_PAUSE_MS;
                return;
            }
        }
        throw new AssertionError("round never ended: " + game.phase());
    }

    private void tick() {
        game.tick(now);
        now += 1_000;
    }

    private void seat(int id, String name) {
        assertTrue(game.join(id, name, true));
        table.betting.put(id, ONE_CHIP);
    }

    /** Ticks through betting and the deal, to the first turn (or the end of the round). */
    private void dealTo(Phase stop) {
        tick();
        now += BlackjackGame.BET_WINDOW_MS;
        for (int i = 0; i < 20 && game.phase() != stop && !(i > 0 && game.phase() == Phase.WAITING); i++) {
            tick();
        }
    }

    @Test
    void theHoleCardIsDealtFaceDownAndTurnedOverOnlyOnTheDealersTurn() {
        seat(1, "Ann");
        game.stack(List.of("TH", "9S", "7D", "8C")); // Ann 17; dealer 9 up, 8 in the hole
        dealTo(Phase.PLAYER_TURN);
        assertEquals(List.of("take Ann", "deal Ann TH", "deal dealer 9S", "deal Ann 7D", "deal dealer 8C down", "turn Ann 17"),
                table.events);
        assertTrue(table.said.contains("casino.bj.dealer.up"));
        game.heard(1, "stand");
        for (int i = 0; i < 10 && game.phase() != Phase.WAITING; i++) {
            tick();
        }
        int reveal = table.events.indexOf("reveal 8C");
        assertTrue(reveal > table.events.indexOf("turn Ann 17"), "the hole card is turned over after the players' turns");
        assertEquals(Result.PUSH, table.results.get(1), "17 against 17");
        assertEquals(250_000L, table.paid.get(1), "a push hands the stake back");
    }

    @Test
    void theDealerPeeksUnderAnAceAndEndsTheRoundOnANatural() {
        seat(1, "Ann");
        game.stack(List.of("TH", "AS", "9D", "KC")); // Ann 19; dealer A up, K in the hole
        dealTo(Phase.SETTLE);
        assertTrue(table.events.contains("reveal KC"));
        assertTrue(table.said.contains("casino.bj.dealer.natural"));
        assertTrue(table.events.stream().noneMatch(e -> e.startsWith("turn")), "nobody plays against a natural");
        tick();
        assertEquals(Result.LOSE, table.results.get(1));
        assertFalse(table.paid.containsKey(1), "the dealer keeps the bet it took in");
    }

    @Test
    void aNaturalPaysThreeToTwo() {
        seat(1, "Ann");
        game.stack(List.of("AH", "9S", "KD", "7C")); // Ann blackjack; dealer 16
        playRound();
        assertEquals(Result.BLACKJACK, table.results.get(1));
        assertEquals(250_000L + 380_000L, table.paid.get(1), "the stake back and 250k at 3:2, rounded up to the 10k chip");
        assertTrue(table.said.contains("casino.bj.natural"));
    }

    @Test
    void aBustLosesAtOnceAndTheDealerStillPlaysForTheOthers() {
        seat(1, "Ann");
        seat(2, "Bo");
        // deal order Ann, Bo, dealer, Ann, Bo, dealer; then Ann's hit, then the dealer's draw
        game.stack(List.of("TH", "TD", "6S", "6H", "8C", "TC", "KS", "5D"));
        dealTo(Phase.PLAYER_TURN);
        game.heard(1, "hit me");
        tick();
        assertTrue(table.said.contains("casino.bj.bust"));
        assertEquals(Result.LOSE, table.results.get(1), "settled at the bust, before the dealer plays");
        assertFalse(table.results.containsKey(2));
        game.heard(2, "stand");
        for (int i = 0; i < 10 && game.phase() != Phase.WAITING; i++) {
            tick();
        }
        assertTrue(table.events.contains("deal dealer 5D"), "the dealer draws on 16 while Bo stands");
        assertEquals(Result.LOSE, table.results.get(2), "18 against the dealer's 21");
    }

    @Test
    void theDealerDrawsToSeventeenAndStandsThere() {
        seat(1, "Ann");
        game.stack(List.of("TH", "6S", "8D", "TC", "AD", "5H")); // Ann 18; dealer 16, draws A = 17, stops
        dealTo(Phase.PLAYER_TURN);
        game.heard(1, "stand");
        for (int i = 0; i < 10 && game.phase() != Phase.WAITING; i++) {
            tick();
        }
        assertTrue(table.events.contains("deal dealer AD"));
        assertFalse(table.events.contains("deal dealer 5H"), "a dealer at 17 stands");
        assertEquals(Result.WIN, table.results.get(1));
        assertEquals(500_000L, table.paid.get(1), "the stake back and as much again");
    }

    @Test
    void aSilentPlayerIsAskedOnceThenStood() {
        seat(1, "Ann");
        game.stack(List.of("TH", "9S", "7D", "8C"));
        dealTo(Phase.PLAYER_TURN);
        now += BlackjackGame.TURN_MS;
        tick();
        assertTrue(table.said.contains("casino.bj.nag"));
        assertFalse(table.said.contains("casino.bj.autostand"));
        now += BlackjackGame.TURN_MS;
        tick();
        assertTrue(table.said.contains("casino.bj.autostand"));
        assertEquals(Phase.DEALER_TURN, game.phase());
    }

    @Test
    void theDealerTakesTheBetsInBeforeAnyCardShowsHowTheHandWillGo() {
        seat(1, "Ann");
        game.stack(List.of("TH", "9S", "6D", "8C", "KD")); // Ann 16 hits a K: a loss she might want to undo
        dealTo(Phase.PLAYER_TURN);
        assertTrue(table.events.indexOf("take Ann") < table.events.indexOf("deal Ann TH"),
                "the chips leave the floor when betting closes: " + table.events);
        assertEquals(1, table.events.stream().filter(e -> e.equals("take Ann")).count());
        game.heard(1, "hit");
        tick();
        assertEquals(Result.LOSE, table.results.get(1));
        assertFalse(table.paid.containsKey(1));
    }

    @Test
    void aTableThatClosesMidHandHandsEveryOpenBetBack() {
        seat(1, "Ann");
        seat(2, "Bo");
        game.stack(List.of("TH", "TD", "6S", "6H", "8C", "TC", "KS"));
        dealTo(Phase.PLAYER_TURN);
        game.heard(1, "hit"); // Ann busts: that hand is over before the close
        tick();
        game.close();
        assertFalse(table.paid.containsKey(1), "a hand already lost stays lost");
        assertEquals(250_000L, table.paid.get(2), "Bo's open bet is handed back");
        assertEquals(Result.PUSH, table.results.get(2));
        assertTrue(game.seats().isEmpty());
        assertEquals(Phase.WAITING, game.phase());
    }

    @Test
    void aSeatThatWalksOffMidHandForfeitsItsBet() {
        seat(1, "Ann");
        game.stack(List.of("TH", "9S", "9D", "8C"));
        dealTo(Phase.PLAYER_TURN);
        table.gone.add(1);
        tick();
        assertEquals(Result.LOSE, table.results.get(1));
        assertFalse(table.paid.containsKey(1));
        assertNull(game.seat(1));
        assertTrue(table.events.contains("left Ann null"));
    }

    @Test
    void aSeatThatNeverBetsIsAskedToLeaveAfterThreeRounds() {
        assertTrue(game.join(1, "Ann", true));
        for (int round = 0; round <= BlackjackGame.MAX_SKIPS; round++) {
            tick();
            now += BlackjackGame.BET_WINDOW_MS;
            tick();
            now += BlackjackGame.ROUND_PAUSE_MS;
        }
        assertTrue(table.events.contains("left Ann casino.bj.skipped"));
        assertNull(game.seat(1));
    }

    @Test
    void aFullTableTakesNoSixthSeat() {
        for (int id = 1; id <= BlackjackGame.MAX_SEATS; id++) {
            assertTrue(game.join(id, "P" + id, false));
        }
        assertFalse(game.join(99, "Late", true));
        assertFalse(game.join(1, "P1", false), "already seated");
    }

    @Test
    void fiveSeatsHittingEveryHandNeverRunTheDeckDryOrDealACardTwice() {
        for (int id = 1; id <= BlackjackGame.MAX_SEATS; id++) {
            seat(id, "P" + id);
            table.alwaysHit.add(id);
        }
        for (int round = 0; round < 60; round++) {
            playRound();
        }
        assertTrue(table.rounds.size() >= 60);
        for (List<String> round : table.rounds) {
            assertEquals(round.size(), new HashSet<>(round).size(), "a card twice in one round: " + round);
        }
    }

    @Test
    void outcomesFollowTheRules() {
        assertEquals(Result.LOSE, BlackjackGame.outcome(List.of("TH", "6S", "KD"), List.of("TC", "TS", "5D")),
                "a player bust loses even when the dealer busts too");
        assertEquals(Result.PUSH, BlackjackGame.outcome(List.of("AH", "KD"), List.of("AS", "QC")));
        assertEquals(Result.LOSE, BlackjackGame.outcome(List.of("7H", "7D", "7C"), List.of("AS", "QC")),
                "three-card 21 loses to a natural");
        assertEquals(Result.WIN, BlackjackGame.outcome(List.of("TH", "2D"), List.of("TC", "6S", "9D")));
        assertEquals(Result.PUSH, BlackjackGame.outcome(List.of("TH", "8D"), List.of("9C", "9S")));
    }
}
