package server.living.population.casino;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Playing cards for the casino's blackjack tables, ported from SoloMapling's Deck, BlackjackRules and CardVisuals. A
 * card is its rank and suit ("TH" is the ten of hearts, "AS" the ace of spades). On the floor a card is a stock monster
 * card, one per rank, and a card dealt face down is the plain Monster Card (Etc 4030012), so no client file changes.
 */
final class Cards {
    private static final char[] SUITS = {'H', 'D', 'C', 'S'};
    private static final char[] RANKS = {'2', '3', '4', '5', '6', '7', '8', '9', 'T', 'J', 'Q', 'K', 'A'};
    /** Monster cards by rank 2..A: Red Snail, Slime, Orange Mushroom, ... Horned Tail, Pink Bean. */
    private static final int[] RANK_ITEMS = {2380004, 2380005, 2380007, 2380009, 2381007, 2381024, 2383012, 2383043,
            2388017, 2388022, 2388023, 2388024, 2388043};
    /** The back of a card: the generic Monster Card. */
    static final int CARD_BACK = 4030012;

    private Cards() {
    }

    /** One shuffled 52-card deck. */
    static List<String> shuffledDeck(Random random) {
        List<String> deck = new ArrayList<>(52);
        for (char suit : SUITS) {
            for (char rank : RANKS) {
                deck.add("" + rank + suit);
            }
        }
        Collections.shuffle(deck, random);
        return deck;
    }

    /** The best total of a hand: aces count 11 until the hand would bust, then 1. */
    static int value(List<String> hand) {
        int total = 0;
        int aces = 0;
        for (String card : hand) {
            char rank = card.charAt(0);
            switch (rank) {
                case 'A' -> {
                    aces++;
                    total += 11;
                }
                case 'K', 'Q', 'J', 'T' -> total += 10;
                default -> total += rank - '0';
            }
        }
        while (total > 21 && aces > 0) {
            total -= 10;
            aces--;
        }
        return total;
    }

    /** Whether a hand holds an ace still counted as 11. */
    static boolean soft(List<String> hand) {
        int hard = 0;
        boolean ace = false;
        for (String card : hand) {
            char rank = card.charAt(0);
            ace |= rank == 'A';
            hard += switch (rank) {
                case 'A' -> 1;
                case 'K', 'Q', 'J', 'T' -> 10;
                default -> rank - '0';
            };
        }
        return ace && hard + 10 <= 21;
    }

    /** A natural: two cards making 21. */
    static boolean blackjack(List<String> hand) {
        return hand.size() == 2 && value(hand) == 21;
    }

    /** The value a card shows face up, 2..11 (an ace shows 11). */
    static int upValue(String card) {
        return value(List.of(card));
    }

    /** The monster card that shows a card on the floor. */
    static int item(String card) {
        int index = new String(RANKS).indexOf(card.charAt(0));
        if (index < 0) {
            throw new IllegalArgumentException("not a card: " + card);
        }
        return RANK_ITEMS[index];
    }

    /** Every item a table may leave on the floor: the thirteen rank cards and the back. */
    static List<Integer> floorItems() {
        List<Integer> items = new ArrayList<>();
        for (int item : RANK_ITEMS) {
            items.add(item);
        }
        items.add(CARD_BACK);
        return items;
    }
}
