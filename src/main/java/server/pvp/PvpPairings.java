package server.pvp;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToIntFunction;

/**
 * Who has fought whom, and when (the user's call 2026-11-13, "Vary practice pairings"; docs/PVP_PLAN.md V2). Check run
 * 26: a class pair's duels came from 10-40 distinct pairings, and one pairing ran 6-10 times in four hours (Keus233
 * against Soro 10 times, Soro won 9), because practice pairs each citizen with the nearest level on its map and the
 * same citizens stand on the same maps. So practice skips a partner it fought within PVP_PAIRING_REPEAT_MINUTES, and
 * the learned matchups count a pairing at most once in that time ({@link PvpMatchups}): a class pair then measures
 * its classes, not the two or three citizens who happened to meet most.
 *
 * <p>In memory only: a restart forgets it, which costs at most one early repeat per pairing.
 */
public final class PvpPairings {
    private static final PvpPairings PLAYED = new PvpPairings();
    /** Past this many pairings, ones older than {@link #FORGET_MS} are let go. */
    static final int PRUNE_AT = 4096;
    static final long FORGET_MS = 6 * 60 * 60_000L;

    private final Map<Long, Long> lastMs = new ConcurrentHashMap<>();

    /** The duels fought: every finished duel notes its opposing fighters ({@link DuelField}). */
    public static PvpPairings played() {
        return PLAYED;
    }

    static long key(int a, int b) {
        int low = Math.min(a, b);
        int high = Math.max(a, b);
        return ((long) low << 32) | (high & 0xffffffffL);
    }

    /** True when {@code a} and {@code b} were noted less than {@code windowMs} before {@code now}. */
    public boolean within(int a, int b, long now, long windowMs) {
        Long last = lastMs.get(key(a, b));
        return windowMs > 0 && last != null && now - last < windowMs;
    }

    public void note(int a, int b, long now) {
        lastMs.put(key(a, b), now);
        if (lastMs.size() > PRUNE_AT) {
            lastMs.values().removeIf(t -> now - t > FORGET_MS);
        }
    }

    /** Every pair of fighters on opposite sides of a finished duel. */
    public void noteDuel(List<DuelLog.Row> rows, long now) {
        for (int i = 0; i < rows.size(); i++) {
            for (int j = i + 1; j < rows.size(); j++) {
                if (rows.get(i).side() != rows.get(j).side()) {
                    note(rows.get(i).charId(), rows.get(j).charId(), now);
                }
            }
        }
    }

    /**
     * Practice's partner for {@code a} among {@code free}: the nearest level within {@code span} that has not fought
     * {@code a} within {@code windowMs}; null when none.
     */
    public <T> T partner(T a, List<T> free, ToIntFunction<T> level, ToIntFunction<T> id, int span, long now,
                         long windowMs) {
        T best = null;
        int ownLevel = level.applyAsInt(a);
        for (T other : free) {
            int gap = Math.abs(level.applyAsInt(other) - ownLevel);
            if (gap > span || within(id.applyAsInt(a), id.applyAsInt(other), now, windowMs)) {
                continue;
            }
            if (best == null || gap < Math.abs(level.applyAsInt(best) - ownLevel)) {
                best = other;
            }
        }
        return best;
    }

    int size() {
        return lastMs.size();
    }
}
