package server.living.imperfections;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Each bot's slips over the last hour (a sliding hour): at most {@code perHour} in all and at most each kind's own cap.
 * Memory only; a bot's book holds no more than its budget, and books gone quiet are dropped once there are many.
 */
final class SlipBudget {
    static final int PRUNE_OVER_BOOKS = 4096;

    private final Map<Integer, ArrayDeque<long[]>> books = new HashMap<>();

    /** True when bot {@code characterId} may slip ({@code kind}) at {@code nowMs}; a true is booked. */
    synchronized boolean spend(int characterId, Imperfections.Kind kind, long nowMs, int perHour) {
        ArrayDeque<long[]> book = books.computeIfAbsent(characterId, id -> new ArrayDeque<>());
        expire(book, nowMs);
        if (book.size() >= perHour) {
            return false;
        }
        int ofKind = 0;
        for (long[] slip : book) {
            if (slip[1] == kind.ordinal()) {
                ofKind++;
            }
        }
        if (ofKind >= kind.perHour) {
            return false;
        }
        book.addLast(new long[]{nowMs, kind.ordinal()});
        if (books.size() > PRUNE_OVER_BOOKS) {
            prune(nowMs);
        }
        return true;
    }

    /** Slips bot {@code characterId} has in its last hour. */
    synchronized int spent(int characterId, long nowMs) {
        ArrayDeque<long[]> book = books.get(characterId);
        if (book == null) {
            return 0;
        }
        expire(book, nowMs);
        return book.size();
    }

    private void prune(long nowMs) {
        for (Iterator<ArrayDeque<long[]>> it = books.values().iterator(); it.hasNext(); ) {
            ArrayDeque<long[]> book = it.next();
            expire(book, nowMs);
            if (book.isEmpty()) {
                it.remove();
            }
        }
    }

    private static void expire(ArrayDeque<long[]> book, long nowMs) {
        while (!book.isEmpty() && nowMs - book.peekFirst()[0] >= Imperfections.HOUR_MS) {
            book.pollFirst();
        }
    }
}
