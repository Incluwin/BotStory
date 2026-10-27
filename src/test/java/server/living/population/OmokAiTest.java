package server.living.population;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The citizens' omok player: it beats a random mover, and at low strength a plain threat-line player (finish a five,
 * block a five, else build the longest line) beats it some of the time; the strength knob is what makes the difference.
 */
class OmokAiTest {
    private static final int GAMES = 40;

    /** Who moves next: the board and the stone to place, the cell back. */
    private interface Mover {
        int move(int[] board, int stone);
    }

    @Test
    void itFinishesItsFiveAndAStrongPlayerBlocksTheOthers() {
        int[] board = new int[OmokAi.CELLS];
        for (int x = 3; x < 7; x++) {
            board[7 * OmokAi.SIZE + x] = 1;
        }
        int win = OmokAi.choose(board, 1, 0.0d, new Random(1L));
        assertTrue(win == 7 * OmokAi.SIZE + 2 || win == 7 * OmokAi.SIZE + 7, "plays the fifth stone");

        board[7 * OmokAi.SIZE + 2] = 2; // one end blocked: the other end is the only block
        for (long seed = 0; seed < 20; seed++) {
            assertEquals(7 * OmokAi.SIZE + 7, OmokAi.choose(board, 2, 1.0d, new Random(seed)));
        }
    }

    @Test
    void itBeatsARandomMover() {
        Random random = new Random(11L);
        Mover ai = (board, stone) -> OmokAi.choose(board, stone, 0.4d, random);
        Mover randomMover = (board, stone) -> randomCell(board, random);
        int wins = 0;
        for (int game = 0; game < GAMES; game++) {
            boolean aiFirst = game % 2 == 0;
            int winner = play(aiFirst ? ai : randomMover, aiFirst ? randomMover : ai);
            if (winner == (aiFirst ? 1 : 2)) {
                wins++;
            }
        }
        assertTrue(wins >= GAMES - 2, "won " + wins + " of " + GAMES);
    }

    @Test
    void aWeakPlayerLosesSometimesToAThreatLinePlayerAndAStrongOneLessOften() {
        int weakLosses = losses(0.05d, new Random(21L));
        int strongLosses = losses(0.95d, new Random(21L));
        assertTrue(weakLosses > 0, "the weak player never lost");
        assertTrue(strongLosses < weakLosses, "strong lost " + strongLosses + ", weak " + weakLosses);
    }

    private static int losses(double strength, Random random) {
        Mover ai = (board, stone) -> OmokAi.choose(board, stone, strength, random);
        Mover threatLine = OmokAiTest::threatLine;
        int losses = 0;
        for (int game = 0; game < GAMES; game++) {
            boolean aiFirst = game % 2 == 0;
            int winner = play(aiFirst ? ai : threatLine, aiFirst ? threatLine : ai);
            if (winner == (aiFirst ? 2 : 1)) {
                losses++;
            }
        }
        return losses;
    }

    /** Plays a game to the end: the winner's stone, or 0 for a full board. */
    private static int play(Mover first, Mover second) {
        int[] board = new int[OmokAi.CELLS];
        for (int turn = 0; turn < OmokAi.CELLS; turn++) {
            int stone = turn % 2 == 0 ? 1 : 2;
            int cell = (stone == 1 ? first : second).move(board.clone(), stone);
            assertEquals(0, board[cell], "plays an empty cell");
            board[cell] = stone;
            if (OmokAi.five(board, cell, stone)) {
                return stone;
            }
        }
        return 0;
    }

    /** Finishes a five, else blocks the other's five, else plays where its own lines grow most. */
    private static int threatLine(int[] board, int stone) {
        List<Integer> candidates = OmokAi.candidates(board);
        if (candidates.isEmpty()) {
            return 7 * OmokAi.SIZE + 7;
        }
        for (int side : new int[]{stone, 3 - stone}) {
            for (int cell : candidates) {
                board[cell] = side;
                boolean five = OmokAi.five(board, cell, side);
                board[cell] = 0;
                if (five) {
                    return cell;
                }
            }
        }
        int best = candidates.get(0);
        for (int cell : candidates) {
            if (OmokAi.value(board, cell, stone) > OmokAi.value(board, best, stone)) {
                best = cell;
            }
        }
        return best;
    }

    private static int randomCell(int[] board, Random random) {
        List<Integer> empty = new ArrayList<>();
        for (int cell = 0; cell < OmokAi.CELLS; cell++) {
            if (board[cell] == 0) {
                empty.add(cell);
            }
        }
        return empty.get(random.nextInt(empty.size()));
    }
}
