package server.living.population;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * How a citizen picks its next omok stone (docs/BOT_CONCEPT_PLAN.md F5a). Threat-based: it finishes a five when it
 * has one, blocks the other side's five, makes or stops an unstoppable double threat (an open four, a four-four), and
 * otherwise plays the cell that builds most of its own lines and breaks most of the other side's, counted over every
 * five-cell window through the cell. {@code strength} (0..1) is how often it notices a threat and how much its
 * judgement wanders: a weak player misses blocks and plays loose stones; a strong one rarely does.
 * The board is 15x15, cell {@code y * 15 + x}, 0 empty, else the stone type (1 or 2).
 */
final class OmokAi {
    static final int SIZE = 15;
    static final int CELLS = SIZE * SIZE;
    private static final int[][] DIRECTIONS = {{1, 0}, {0, 1}, {1, 1}, {1, -1}};
    /** A window's worth by how many of its five cells one side holds (none of the other side's). */
    private static final int[] WINDOW_SCORE = {0, 1, 12, 120, 1_500, 1_000_000};

    private OmokAi() {
    }

    /** The cell to play for {@code me}, or -1 when the board is full. */
    static int choose(int[] board, int me, double strength, Random random) {
        int other = 3 - me;
        List<Integer> candidates = candidates(board);
        if (candidates.isEmpty()) {
            return empty(board) ? center(random) : -1;
        }
        // a five of its own is never missed; the other side's five is missed now and then by a weak player
        Integer win = firstMaking(board, candidates, me);
        if (win != null) {
            return win;
        }
        Integer block = firstMaking(board, candidates, other);
        if (block != null && random.nextDouble() < 0.55d + 0.45d * strength) {
            return block;
        }
        if (block == null) {
            Integer doubleThreat = doubleThreat(board, candidates, me);
            if (doubleThreat != null && random.nextDouble() < 0.25d + 0.75d * strength) {
                return doubleThreat;
            }
            Integer stop = doubleThreat(board, candidates, other);
            if (stop != null && random.nextDouble() < 0.2d + 0.75d * strength) {
                return stop;
            }
        }
        // a weak player looks at part of the board, and its sense of a move's worth is noisy
        double attention = 0.45d + 0.55d * strength;
        double noise = (1.0d - strength) * 0.9d;
        int best = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int cell : candidates) {
            if (best >= 0 && random.nextDouble() > attention) {
                continue;
            }
            double score = value(board, cell, me) + 0.85d * value(board, cell, other);
            score *= Math.exp(noise * random.nextGaussian());
            if (score > bestScore) {
                bestScore = score;
                best = cell;
            }
        }
        return best;
    }

    /** What a stone of {@code side} on the empty {@code cell} is worth to that side: the windows through it. */
    static int value(int[] board, int cell, int side) {
        int x = cell % SIZE;
        int y = cell / SIZE;
        int total = 0;
        for (int[] d : DIRECTIONS) {
            for (int offset = -4; offset <= 0; offset++) {
                int sx = x + offset * d[0];
                int sy = y + offset * d[1];
                int ex = sx + 4 * d[0];
                int ey = sy + 4 * d[1];
                if (!inside(sx, sy) || !inside(ex, ey)) {
                    continue;
                }
                int own = 1; // the stone it would place
                boolean blocked = false;
                for (int i = 0; i < 5 && !blocked; i++) {
                    int at = (sy + i * d[1]) * SIZE + sx + i * d[0];
                    if (at == cell) {
                        continue;
                    }
                    if (board[at] == side) {
                        own++;
                    } else if (board[at] != 0) {
                        blocked = true;
                    }
                }
                if (!blocked) {
                    total += WINDOW_SCORE[Math.min(5, own)];
                }
            }
        }
        return total;
    }

    /** True when {@code side} has five in a row through {@code cell} (a stone of its own there). */
    static boolean five(int[] board, int cell, int side) {
        int x = cell % SIZE;
        int y = cell / SIZE;
        for (int[] d : DIRECTIONS) {
            int run = 1;
            for (int sign = -1; sign <= 1; sign += 2) {
                int cx = x + sign * d[0];
                int cy = y + sign * d[1];
                while (inside(cx, cy) && board[cy * SIZE + cx] == side) {
                    run++;
                    cx += sign * d[0];
                    cy += sign * d[1];
                }
            }
            if (run >= 5) {
                return true;
            }
        }
        return false;
    }

    /** Empty cells within two of a stone: where anyone would play. */
    static List<Integer> candidates(int[] board) {
        List<Integer> out = new ArrayList<>();
        for (int cell = 0; cell < CELLS; cell++) {
            if (board[cell] != 0) {
                continue;
            }
            int x = cell % SIZE;
            int y = cell / SIZE;
            boolean near = false;
            for (int dy = -2; dy <= 2 && !near; dy++) {
                for (int dx = -2; dx <= 2 && !near; dx++) {
                    near = inside(x + dx, y + dy) && board[(y + dy) * SIZE + x + dx] != 0;
                }
            }
            if (near) {
                out.add(cell);
            }
        }
        return out;
    }

    /** The first candidate where a stone of {@code side} makes five. */
    private static Integer firstMaking(int[] board, List<Integer> candidates, int side) {
        for (int cell : candidates) {
            board[cell] = side;
            boolean made = five(board, cell, side);
            board[cell] = 0;
            if (made) {
                return cell;
            }
        }
        return null;
    }

    /** A candidate after which {@code side} would have two different cells that make five: it cannot be stopped. */
    private static Integer doubleThreat(int[] board, List<Integer> candidates, int side) {
        for (int cell : candidates) {
            if (value(board, cell, side) < WINDOW_SCORE[4]) {
                continue; // no window reaches four through it
            }
            board[cell] = side;
            int threats = 0;
            for (int next : candidates(board)) {
                board[next] = side;
                if (five(board, next, side)) {
                    threats++;
                }
                board[next] = 0;
                if (threats >= 2) {
                    break;
                }
            }
            board[cell] = 0;
            if (threats >= 2) {
                return cell;
            }
        }
        return null;
    }

    private static boolean empty(int[] board) {
        for (int stone : board) {
            if (stone != 0) {
                return false;
            }
        }
        return true;
    }

    /** An opening stone near the middle, as most players start. */
    private static int center(Random random) {
        int x = 7 + random.nextInt(3) - 1;
        int y = 7 + random.nextInt(3) - 1;
        return y * SIZE + x;
    }

    private static boolean inside(int x, int y) {
        return x >= 0 && x < SIZE && y >= 0 && y < SIZE;
    }
}
