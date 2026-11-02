package server.living.population.casino;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * One blackjack table's rounds, ported from SoloMapling's BlackjackDealerBot, BlackjackTable and BlackjackPlayer. The
 * game is plain state: {@link #tick} moves it one visible step (a card, a line, a payout), and everything it does in
 * the world goes through {@link Table}, so the rules are tested without a server.
 *
 * <p>A round: bets are the chips each seat drops in front of itself while the dealer calls for them; two cards to
 * every seat with a bet and two to the dealer, the second face down (SoloMapling never dealt it, see its TODO); the
 * dealer peeks under an ace or ten and ends the round at once on a natural; each seat hits or stands in turn, a human
 * by saying so, a citizen through its {@link HitStandPolicy}; the dealer turns the hole card over and draws to 17
 * (stands on all 17s); then winners are paid their stake and winnings in chips (a natural pays 3 to 2), a push gets its
 * stake back and a lost bet stays with the dealer. The dealer takes the bets in when betting closes, so a bet cannot be
 * picked back up once the cards show how it will go; a seat that walks off mid-hand forfeits it, and a table that
 * closes mid-hand hands every open bet back.
 */
final class BlackjackGame {
    static final int MAX_SEATS = 5;
    static final long BET_WINDOW_MS = 12_000;
    static final long TURN_MS = 15_000;
    static final long ROUND_PAUSE_MS = 4_000;
    /** Rounds in a row without a bet before a seat is asked to leave. */
    static final int MAX_SKIPS = 3;
    private static final int DEALER_STANDS = 17;

    enum Phase { WAITING, BETTING, DEALING, PLAYER_TURN, DEALER_TURN, SETTLE }

    enum Result { WIN, BLACKJACK, PUSH, LOSE }

    /** A player at the table: a human or a citizen. */
    static final class Seat {
        final int id;
        final String name;
        final boolean human;
        final List<String> hand = new ArrayList<>();
        /** The chips down this round (item id to count), empty when sitting it out. */
        final Map<Integer, Integer> bet = new LinkedHashMap<>();
        int skipped;
        boolean busted;
        boolean settled;
        /** A decision heard for the current turn: true hit, false stand, null none yet. */
        Boolean decision;

        Seat(int id, String name, boolean human) {
            this.id = id;
            this.name = name;
            this.human = human;
        }

        boolean inHand() {
            return !bet.isEmpty();
        }
    }

    /** What the dealer does in the world: the live table binds it to the map, tests to a fake. */
    interface Table {
        /** The dealer says a line ({@code trigger} casino.bj.*). */
        void say(String trigger, Map<String, Object> args);

        /** A card lands in front of a seat ({@code seat} null for the dealer), the {@code index}th of its hand. */
        void deal(Seat seat, int index, String card, boolean faceDown);

        /** The dealer's face-down card is turned over. */
        void reveal(String card);

        /**
         * The dealer takes in the chips this seat's character dropped in front of it as its bet: returns what it took, item
         * id to count; empty when none (or more than the table takes, which it leaves on the floor).
         */
        Map<Integer, Integer> takeBet(Seat seat);

        /** The dealer pays a seat {@code mesos} in chips: its stake back with any winnings. */
        void pay(Seat seat, long mesos);

        /** The dealer picks up every card on the table. */
        void clearCards();

        /** A seat's turn begins: a human gets the chat hint, a citizen thinks and answers through {@link #decide}. */
        void turn(Seat seat, int total, boolean soft, int dealerUp);

        /** Whether the seat's character is still at the table. */
        boolean present(Seat seat);

        /** A seat left or was sent away ({@code reason} a casino.bj.* trigger, null when it walked off). */
        void left(Seat seat, String reason);

        /** A seat's hand is settled ({@code reward} per chip: -1, 0, 1 or 1.5). */
        void settled(Seat seat, Result result, double reward);
    }

    private final Table table;
    private final Random random;
    private final List<Seat> seats = new ArrayList<>();
    private final List<String> dealer = new ArrayList<>();
    private final List<String> deck = new ArrayList<>();
    private final List<Seat> dealOrder = new ArrayList<>();
    private Phase phase = Phase.WAITING;
    private long phaseUntil;
    private int dealt;
    private int turnIndex;
    private boolean nagged;
    private boolean revealed;
    /** The seat whose turn it was left: the next turn starts on the next tick. */
    private boolean advance;

    BlackjackGame(Table table, Random random) {
        this.table = table;
        this.random = random;
    }

    Phase phase() {
        return phase;
    }

    List<Seat> seats() {
        return seats;
    }

    List<String> dealerHand() {
        return dealer;
    }

    Seat seat(int id) {
        for (Seat seat : seats) {
            if (seat.id == id) {
                return seat;
            }
        }
        return null;
    }

    /** For tests: these cards come off the deck next, in this order, ahead of the rest of a fresh shuffle. */
    void stack(List<String> top) {
        deck.clear();
        deck.addAll(top);
        List<String> rest = Cards.shuffledDeck(random);
        rest.removeAll(top);
        deck.addAll(rest);
    }

    /** Seats a player for the next round; false when the table is full or it is already seated. */
    boolean join(int id, String name, boolean human) {
        if (seats.size() >= MAX_SEATS || seat(id) != null) {
            return false;
        }
        seats.add(new Seat(id, name, human));
        return true;
    }

    /** A seat leaves; mid-hand its bet stays on the floor for the dealer. */
    void leave(int id) {
        Seat seat = seat(id);
        if (seat == null) {
            return;
        }
        if (seat.inHand() && !seat.settled && phase != Phase.WAITING && phase != Phase.BETTING) {
            settle(seat, Result.LOSE); // walking off mid-hand forfeits the bet the dealer holds
        }
        removeSeat(seat);
    }

    /** The table closes (its dealer goes): every bet not yet settled is handed back, and every seat leaves. */
    void close() {
        for (Seat seat : new ArrayList<>(seats)) {
            if (seat.inHand() && !seat.settled) {
                table.pay(seat, Chips.value(seat.bet));
                settle(seat, Result.PUSH);
            }
            removeSeat(seat);
        }
        dealer.clear();
        phase = Phase.WAITING;
    }

    /** What a seat said at the table: "hit" or "stand" on its own turn. */
    void heard(int id, String text) {
        Seat seat = seat(id);
        if (seat == null || phase != Phase.PLAYER_TURN || current() != seat) {
            return;
        }
        String said = text.toLowerCase();
        if (said.contains("stand") || said.contains("stay")) {
            seat.decision = false;
        } else if (said.contains("hit")) {
            seat.decision = true;
        }
    }

    /** A citizen's answer on its turn. */
    void decide(int id, boolean hit) {
        Seat seat = seat(id);
        if (seat != null && phase == Phase.PLAYER_TURN && current() == seat) {
            seat.decision = hit;
        }
    }

    /** One step of the round. */
    void tick(long now) {
        dropAbsent();
        switch (phase) {
            case WAITING -> waiting(now);
            case BETTING -> betting(now);
            case DEALING -> dealing(now);
            case PLAYER_TURN -> playerTurn(now);
            case DEALER_TURN -> dealerTurn();
            case SETTLE -> settleAll(now);
        }
    }

    private void waiting(long now) {
        if (seats.isEmpty() || now < phaseUntil) {
            return;
        }
        for (Seat seat : seats) {
            seat.hand.clear();
            seat.bet.clear();
            seat.busted = false;
            seat.settled = false;
            seat.decision = null;
        }
        dealer.clear();
        revealed = false;
        table.say("casino.bj.bets", Map.of());
        phase = Phase.BETTING;
        phaseUntil = now + BET_WINDOW_MS;
    }

    private void betting(long now) {
        if (now < phaseUntil) {
            return;
        }
        List<String> called = new ArrayList<>();
        for (Seat seat : new ArrayList<>(seats)) {
            Map<Integer, Integer> chips = table.takeBet(seat);
            if (chips.isEmpty()) {
                if (++seat.skipped > MAX_SKIPS) {
                    table.left(seat, "casino.bj.skipped");
                    removeSeat(seat);
                }
                continue;
            }
            seat.skipped = 0;
            seat.bet.putAll(chips);
            called.add(seat.name + " " + Chips.describe(chips));
        }
        if (called.isEmpty()) {
            table.say("casino.bj.nobets", Map.of());
            pause(now);
            return;
        }
        table.say("casino.bj.closed", Map.of("bets", String.join(", ", called)));
        if (deck.size() < 15) {
            deck.clear();
            deck.addAll(Cards.shuffledDeck(random));
        }
        dealOrder.clear();
        for (int round = 0; round < 2; round++) {
            for (Seat seat : seats) {
                if (seat.inHand()) {
                    dealOrder.add(seat);
                }
            }
            dealOrder.add(null);
        }
        dealt = 0;
        phase = Phase.DEALING;
    }

    private void dealing(long now) {
        if (dealt < dealOrder.size()) {
            Seat seat = dealOrder.get(dealt++);
            String card = draw();
            if (seat == null) {
                boolean hole = dealer.size() == 1;
                table.deal(null, dealer.size(), card, hole);
                dealer.add(card);
            } else {
                table.deal(seat, seat.hand.size(), card, false);
                seat.hand.add(card);
            }
            return;
        }
        int up = Cards.upValue(dealer.get(0));
        if ((up == 10 || up == 11) && Cards.blackjack(dealer)) {
            revealHole();
            table.say("casino.bj.dealer.natural", Map.of());
            phase = Phase.SETTLE;
            return;
        }
        table.say("casino.bj.dealer.up", Map.of("card", up));
        turnIndex = -1;
        nextTurn(now);
    }

    private void nextTurn(long now) {
        while (++turnIndex < seats.size()) {
            Seat seat = seats.get(turnIndex);
            if (!seat.inHand()) {
                continue;
            }
            if (Cards.blackjack(seat.hand)) {
                table.say("casino.bj.natural", Map.of("name", seat.name));
                continue;
            }
            startTurn(seat, now);
            return;
        }
        phase = Phase.DEALER_TURN;
    }

    private void startTurn(Seat seat, long now) {
        phase = Phase.PLAYER_TURN;
        seat.decision = null;
        nagged = false;
        phaseUntil = now + TURN_MS;
        int total = Cards.value(seat.hand);
        table.say("casino.bj.turn", Map.of("name", seat.name, "total", total));
        table.turn(seat, total, Cards.soft(seat.hand), Cards.upValue(dealer.get(0)));
    }

    private Seat current() {
        return turnIndex >= 0 && turnIndex < seats.size() ? seats.get(turnIndex) : null;
    }

    private void playerTurn(long now) {
        if (advance) {
            advance = false;
            nextTurn(now);
            return;
        }
        Seat seat = current();
        if (seat == null) {
            phase = Phase.DEALER_TURN;
            return;
        }
        if (seat.decision == null) {
            if (now < phaseUntil) {
                return;
            }
            if (!nagged) {
                nagged = true;
                phaseUntil = now + TURN_MS;
                table.say("casino.bj.nag", Map.of("name", seat.name));
                return;
            }
            seat.decision = false;
            table.say("casino.bj.autostand", Map.of("name", seat.name));
        }
        if (!seat.decision) {
            nextTurn(now);
            return;
        }
        String card = draw();
        table.deal(seat, seat.hand.size(), card, false);
        seat.hand.add(card);
        int total = Cards.value(seat.hand);
        if (total > 21) {
            seat.busted = true;
            table.say("casino.bj.bust", Map.of("name", seat.name, "total", total));
            settle(seat, Result.LOSE);
            nextTurn(now);
        } else if (total == 21) {
            table.say("casino.bj.twentyone", Map.of("name", seat.name));
            nextTurn(now);
        } else {
            startTurn(seat, now);
        }
    }

    private void dealerTurn() {
        if (!revealed) {
            revealHole();
            table.say("casino.bj.dealer.shows", Map.of("total", Cards.value(dealer)));
            return;
        }
        boolean anyStanding = false;
        for (Seat seat : seats) {
            anyStanding |= seat.inHand() && !seat.busted && !Cards.blackjack(seat.hand);
        }
        int total = Cards.value(dealer);
        if (anyStanding && total < DEALER_STANDS) {
            String card = draw();
            table.deal(null, dealer.size(), card, false);
            dealer.add(card);
            int now = Cards.value(dealer);
            table.say(now > 21 ? "casino.bj.dealer.bust" : "casino.bj.dealer.draws", Map.of("total", now));
            return;
        }
        phase = Phase.SETTLE;
    }

    /** The top card; an empty deck mid-round is reshuffled from the cards that are not on the table. */
    private String draw() {
        if (deck.isEmpty()) {
            deck.addAll(Cards.shuffledDeck(random));
            deck.removeAll(dealer);
            for (Seat seat : seats) {
                deck.removeAll(seat.hand);
            }
        }
        return deck.remove(0);
    }

    private void revealHole() {
        revealed = true;
        table.reveal(dealer.get(1));
    }

    private void settleAll(long now) {
        List<String> lines = new ArrayList<>();
        for (Seat seat : new ArrayList<>(seats)) {
            if (!seat.inHand() || seat.settled) {
                continue;
            }
            Result result = outcome(seat.hand, dealer);
            long stake = Chips.value(seat.bet);
            switch (result) {
                case WIN -> table.pay(seat, stake * 2);
                case BLACKJACK -> table.pay(seat, stake + Chips.threeToTwo(stake));
                case PUSH -> table.pay(seat, stake);
                case LOSE -> {
                } // the dealer keeps the bet it took in
            }
            lines.add(seat.name + " " + result.name().toLowerCase());
            settle(seat, result);
        }
        if (!lines.isEmpty()) {
            table.say("casino.bj.results", Map.of("results", String.join(", ", lines)));
        }
        table.clearCards();
        pause(now);
    }

    private void settle(Seat seat, Result result) {
        seat.settled = true;
        double reward = switch (result) {
            case WIN -> 1;
            case BLACKJACK -> 1.5;
            case PUSH -> 0;
            case LOSE -> -1;
        };
        table.settled(seat, result, reward);
    }

    private void pause(long now) {
        phase = Phase.WAITING;
        phaseUntil = now + ROUND_PAUSE_MS;
    }

    private void dropAbsent() {
        for (Seat seat : new ArrayList<>(seats)) {
            if (!table.present(seat)) {
                if (seat.inHand() && !seat.settled && phase != Phase.BETTING && phase != Phase.WAITING) {
                    settle(seat, Result.LOSE);
                }
                table.left(seat, null);
                removeSeat(seat);
            }
        }
    }

    private void removeSeat(Seat seat) {
        int index = seats.indexOf(seat);
        if (index < 0) {
            return;
        }
        seats.remove(index);
        for (int i = dealOrder.size() - 1; i >= 0; i--) {
            if (dealOrder.get(i) == seat) {
                dealOrder.remove(i);
                if (i < dealt) {
                    dealt--;
                }
            }
        }
        if (phase == Phase.PLAYER_TURN && index <= turnIndex) {
            if (index == turnIndex) {
                advance = true; // it was this seat's turn: the next seat's starts on the next tick
            }
            turnIndex--;
        }
    }

    /** The outcome of a seat's hand against the dealer's. */
    static Result outcome(List<String> hand, List<String> dealer) {
        int player = Cards.value(hand);
        int house = Cards.value(dealer);
        boolean natural = Cards.blackjack(hand);
        boolean houseNatural = Cards.blackjack(dealer);
        if (player > 21) {
            return Result.LOSE;
        }
        if (natural && houseNatural) {
            return Result.PUSH;
        }
        if (natural) {
            return Result.BLACKJACK;
        }
        if (houseNatural || (house <= 21 && house > player)) {
            return Result.LOSE;
        }
        return house > 21 || player > house ? Result.WIN : Result.PUSH;
    }
}
