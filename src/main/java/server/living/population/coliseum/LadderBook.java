package server.living.population.coliseum;

import server.pvp.Glicko2;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The ladder's ratings (docs/PVP_PLAN.md V4.2): one Glicko-2 rating a character a bracket, humans and citizens alike,
 * written through to {@code pvp_rating} and kept in memory once read. A rating not played for a while widens by the
 * days since its last match when it is next used (that widening is not written back until it plays).
 */
final class LadderBook {
    /** Every rating is this season's for now: no resets yet. */
    static final int SEASON = 1;
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    /**
     * A rating and its record; {@code updatedAtMs} is its last match (0: never played). {@code weekOf} is the week
     * ({@link #week}) of that match and {@code weekRating} the rating it went into that week's first match with, so
     * {@link #climbed} is how far it moved this week (V4.4's board).
     */
    record Row(int characterId, LadderRules.Bracket bracket, Glicko2.Rating rating, int wins, int losses,
               long updatedAtMs, int weekOf, double weekRating) {
        Row(int characterId, LadderRules.Bracket bracket, Glicko2.Rating rating, int wins, int losses,
            long updatedAtMs) {
            this(characterId, bracket, rating, wins, losses, updatedAtMs, 0, rating.rating());
        }

        static Row fresh(int characterId, LadderRules.Bracket bracket) {
            return new Row(characterId, bracket, Glicko2.Rating.initial(), 0, 0, 0L);
        }

        int played() {
            return wins + losses;
        }

        /** How far the rating moved in week {@code week}: 0 when it has not played that week. */
        int climbed(int week) {
            return weekOf == week ? (int) Math.round(rating.rating() - weekRating) : 0;
        }
    }

    /** The week of {@code ms} (UTC, a week starting Monday), counted from the epoch: the board's "this week". */
    static int week(long ms) {
        return (int) Math.floorDiv(Math.floorDiv(ms, DAY_MS) + 3, 7);
    }

    /** A row of the top list, with the name and level it is shown under. */
    record Ranked(Row row, String name, int level) {
    }

    /** Where ratings are kept; the live one is {@link LadderStore}. */
    interface Store {
        Optional<Row> load(int characterId, LadderRules.Bracket bracket, int season);

        void save(Row row, int season);

        /** The best rated with at least {@code minPlayed} matches, GMs left out, best first. */
        List<Ranked> top(LadderRules.Bracket bracket, int season, int minPlayed, int limit);

        /** Who climbed most in week {@code week} (played that week, GMs left out), most first; a rise only. */
        List<Ranked> climbers(LadderRules.Bracket bracket, int season, int week, int limit);

        /** {how many with {@code minPlayed} matches rate above {@code rating}, how many have them}, GMs left out. */
        int[] standing(LadderRules.Bracket bracket, int season, int minPlayed, double rating);
    }

    /** A match's effect on both sides' ratings. */
    record Change(Row before, Row after) {
        int delta() {
            return (int) Math.round(after.rating().rating() - before.rating().rating());
        }
    }

    private final Store store;
    private final Map<String, Row> cache = new ConcurrentHashMap<>();

    LadderBook(Store store) {
        this.store = store;
    }

    private static String key(int characterId, LadderRules.Bracket bracket) {
        return characterId + ":" + bracket;
    }

    /** The character's row in the bracket as stored (a fresh one when it never played there). */
    Row row(int characterId, LadderRules.Bracket bracket) {
        return cache.computeIfAbsent(key(characterId, bracket),
                k -> store.load(characterId, bracket, SEASON).orElse(Row.fresh(characterId, bracket)));
    }

    /** Its rating for a match at {@code now}: the stored one widened by the days since it last played. */
    Glicko2.Rating rating(int characterId, LadderRules.Bracket bracket, long now) {
        Row row = row(characterId, bracket);
        if (row.updatedAtMs() <= 0L) {
            return row.rating();
        }
        return Glicko2.idle(row.rating(), Math.max(0L, now - row.updatedAtMs()) / (double) DAY_MS);
    }

    /**
     * Books a match between {@code a} and {@code b} in {@code bracket}: {@code scoreA} 1 (a won), 0 (b won) or 0.5 (a
     * draw, which counts as neither a win nor a loss). Both rows are saved. Returns {a's change, b's change}.
     */
    synchronized Change[] record(int a, int b, LadderRules.Bracket bracket, double scoreA, long now) {
        Row rowA = row(a, bracket);
        Row rowB = row(b, bracket);
        Glicko2.Rating ratingA = rating(a, bracket, now);
        Glicko2.Rating ratingB = rating(b, bracket, now);
        Glicko2.Rating nextA = Glicko2.update(ratingA, ratingB, scoreA);
        Glicko2.Rating nextB = Glicko2.update(ratingB, ratingA, 1d - scoreA);
        int winA = scoreA > 0.5d ? 1 : 0;
        int winB = scoreA < 0.5d ? 1 : 0;
        int week = week(now);
        Row afterA = new Row(a, bracket, nextA, rowA.wins() + winA, rowA.losses() + winB, now, week,
                rowA.weekOf() == week ? rowA.weekRating() : rowA.rating().rating());
        Row afterB = new Row(b, bracket, nextB, rowB.wins() + winB, rowB.losses() + winA, now, week,
                rowB.weekOf() == week ? rowB.weekRating() : rowB.rating().rating());
        cache.put(key(a, bracket), afterA);
        cache.put(key(b, bracket), afterB);
        store.save(afterA, SEASON);
        store.save(afterB, SEASON);
        return new Change[] {new Change(rowA, afterA), new Change(rowB, afterB)};
    }

    /** A rating needs this many matches to be listed or ranked. */
    static final int LISTED_AFTER = 3;

    /** The bracket's top {@code limit} with at least {@link #LISTED_AFTER} matches (the board's page). */
    List<Ranked> top(LadderRules.Bracket bracket, int limit) {
        return store.top(bracket, SEASON, LISTED_AFTER, limit);
    }

    /** The bracket's biggest climbs of the week of {@code now}. */
    List<Ranked> climbers(LadderRules.Bracket bracket, int limit, long now) {
        return store.climbers(bracket, SEASON, week(now), limit);
    }

    /**
     * Where {@code characterId} stands in the bracket: its place (1 is the top) among the listed, and how many are
     * listed; its place is 0 while it has fewer than {@link #LISTED_AFTER} matches.
     */
    int[] standing(int characterId, LadderRules.Bracket bracket) {
        Row row = row(characterId, bracket);
        int[] counts = store.standing(bracket, SEASON, LISTED_AFTER, row.rating().rating());
        return new int[] {row.played() >= LISTED_AFTER ? counts[0] + 1 : 0, counts[1]};
    }

    /** Forgets what was read (a test, or a reset by hand). */
    void clearCache() {
        cache.clear();
    }
}
