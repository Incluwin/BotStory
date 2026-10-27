package server.living.population;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A citizen's match-card memory knows only the cards turned face up: it pairs what it saw, guesses an unseen card
 * otherwise, forgets with time, and a good memory finds more pairs than a poor one.
 */
class MatchCardMemoryTest {

    @Test
    void itPairsTwoCardsItSawAndGuessesOnlyAmongCardsItHasNotSeen() {
        MatchCardMemory memory = new MatchCardMemory(12, 1.0d, 1_000.0d, new Random(4L));
        memory.saw(3, 5);
        memory.saw(9, 2);
        memory.saw(7, 5);

        int first = memory.first();
        assertTrue(first == 3 || first == 7, "turns one of the pair it saw");
        assertEquals(first == 3 ? 7 : 3, memory.second(first, 5));

        memory.paired(3, 7);
        for (int i = 0; i < 30; i++) {
            int guess = memory.first();
            assertNotEquals(3, guess);
            assertNotEquals(7, guess);
            assertNotEquals(9, guess, "a card it remembers without its pair is not a guess");
        }
    }

    @Test
    void aSecondCardWithNoRememberedPairIsAnotherUnseenCard() {
        MatchCardMemory memory = new MatchCardMemory(12, 1.0d, 1_000.0d, new Random(5L));
        memory.saw(0, 1);
        memory.saw(4, 3);
        for (int i = 0; i < 30; i++) {
            int second = memory.second(0, 1);
            assertTrue(second != 0 && second != 4, "neither the first card nor a card it knows is not its pair");
        }
    }

    @Test
    void withNoRecallItNeverPairsFromMemory() {
        MatchCardMemory memory = new MatchCardMemory(4, 0.0d, 1_000.0d, new Random(6L));
        memory.saw(0, 1);
        memory.saw(1, 1);
        int pairs = 0;
        for (int i = 0; i < 40; i++) {
            if (memory.second(0, 1) == 1) {
                pairs++;
            }
        }
        assertTrue(pairs < 40, "found its pair by memory every time");
    }

    @Test
    void aGoodMemoryFindsMorePairsThanAPoorOne() {
        Random deal = new Random(8L);
        int good = 0;
        int poor = 0;
        for (int game = 0; game < 30; game++) {
            List<Integer> deck = deck(10, deal);
            int[] found = play(deck, MatchCardMemory.forStrength(20, 0.95d, new Random(game)),
                    MatchCardMemory.forStrength(20, 0.05d, new Random(1_000 + game)), game % 2 == 0);
            good += found[0];
            poor += found[1];
        }
        assertTrue(good > poor, "good " + good + ", poor " + poor);
    }

    /** Two memories play a whole board, the matcher keeping its turn; the pairs each found. */
    private static int[] play(List<Integer> deck, MatchCardMemory a, MatchCardMemory b, boolean aFirst) {
        boolean[] gone = new boolean[deck.size()];
        int[] found = new int[2];
        int left = deck.size() / 2;
        boolean aTurn = aFirst;
        for (int turns = 0; left > 0 && turns < 1_000; turns++) {
            MatchCardMemory mover = aTurn ? a : b;
            int first = mover.first();
            int firstCard = deck.get(first);
            a.saw(first, firstCard);
            b.saw(first, firstCard);
            int second = mover.second(first, firstCard);
            assertTrue(!gone[first] && !gone[second] && first != second, "turns two cards still on the board");
            a.saw(second, deck.get(second));
            b.saw(second, deck.get(second));
            if (deck.get(second) == firstCard) {
                gone[first] = true;
                gone[second] = true;
                a.paired(first, second);
                b.paired(first, second);
                found[aTurn ? 0 : 1]++;
                left--;
            } else {
                aTurn = !aTurn;
            }
        }
        assertEquals(0, left, "the board was cleared");
        return found;
    }

    private static List<Integer> deck(int pairs, Random random) {
        List<Integer> deck = new ArrayList<>();
        for (int card = 0; card < pairs; card++) {
            deck.add(card);
            deck.add(card);
        }
        Collections.shuffle(deck, random);
        return deck;
    }
}
