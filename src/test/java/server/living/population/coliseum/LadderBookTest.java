package server.living.population.coliseum;

import config.PvpConfig;
import org.junit.jupiter.api.Test;
import server.pvp.DuelSim;
import server.pvp.Glicko2;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LadderBookTest {
    /** pvp_rating in memory. */
    static final class MemoryStore implements LadderBook.Store {
        final Map<String, LadderBook.Row> rows = new HashMap<>();
        int saves;

        @Override
        public Optional<LadderBook.Row> load(int characterId, LadderRules.Bracket bracket, int season) {
            return Optional.ofNullable(rows.get(characterId + ":" + bracket));
        }

        @Override
        public void save(LadderBook.Row row, int season) {
            saves++;
            rows.put(row.characterId() + ":" + row.bracket(), row);
        }

        @Override
        public List<LadderBook.Ranked> top(LadderRules.Bracket bracket, int season, int minPlayed, int limit) {
            List<LadderBook.Ranked> out = new ArrayList<>();
            rows.values().stream().filter(r -> r.bracket() == bracket && r.played() >= minPlayed)
                    .sorted(Comparator.comparingDouble((LadderBook.Row r) -> r.rating().rating()).reversed())
                    .limit(limit).forEach(r -> out.add(new LadderBook.Ranked(r, "C" + r.characterId(), 70)));
            return out;
        }

        @Override
        public List<LadderBook.Ranked> climbers(LadderRules.Bracket bracket, int season, int week, int limit) {
            List<LadderBook.Ranked> out = new ArrayList<>();
            rows.values().stream().filter(r -> r.bracket() == bracket && r.climbed(week) > 0)
                    .sorted(Comparator.comparingInt((LadderBook.Row r) -> r.climbed(week)).reversed())
                    .limit(limit).forEach(r -> out.add(new LadderBook.Ranked(r, "C" + r.characterId(), 70)));
            return out;
        }

        @Override
        public int[] standing(LadderRules.Bracket bracket, int season, int minPlayed, double rating) {
            int above = 0;
            int total = 0;
            for (LadderBook.Row r : rows.values()) {
                if (r.bracket() == bracket && r.played() >= minPlayed) {
                    total++;
                    above += r.rating().rating() > rating ? 1 : 0;
                }
            }
            return new int[] {above, total};
        }
    }

    @Test
    void aMatchMovesBothRatingsAndTheirRecordsAndIsSaved() {
        MemoryStore store = new MemoryStore();
        LadderBook book = new LadderBook(store);
        long now = 1_700_000_000_000L;

        LadderBook.Change[] changes = book.record(1, 2, LadderRules.Bracket.VETERAN, 1d, now);

        assertTrue(changes[0].delta() > 0 && changes[1].delta() < 0);
        assertEquals(changes[0].delta(), -changes[1].delta(), 1, "two fresh ratings move by the same amount");
        assertEquals(1, book.row(1, LadderRules.Bracket.VETERAN).wins());
        assertEquals(1, book.row(2, LadderRules.Bracket.VETERAN).losses());
        assertEquals(2, store.saves);
        assertEquals(now, store.rows.get("1:VETERAN").updatedAtMs());
    }

    @Test
    void aDrawIsNeitherAWinNorALoss() {
        MemoryStore store = new MemoryStore();
        LadderBook book = new LadderBook(store);
        book.record(1, 2, LadderRules.Bracket.ROOKIE, 0.5d, 1L);
        LadderBook.Row row = book.row(1, LadderRules.Bracket.ROOKIE);
        assertEquals(0, row.played());
        assertEquals(1500, row.rating().rating(), 1e-6);
    }

    @Test
    void aStoredRatingComesBackAndWidensWhileIdle() {
        MemoryStore store = new MemoryStore();
        long day = 24L * 60 * 60 * 1000;
        store.rows.put("7:CHAMPION", new LadderBook.Row(7, LadderRules.Bracket.CHAMPION,
                new Glicko2.Rating(1710, 60, 0.06), 12, 4, day));
        LadderBook book = new LadderBook(store);

        assertEquals(1710, book.rating(7, LadderRules.Bracket.CHAMPION, day).rating(), 1e-9);
        assertEquals(60, book.rating(7, LadderRules.Bracket.CHAMPION, day).deviation(), 1e-9);
        assertTrue(book.rating(7, LadderRules.Bracket.CHAMPION, 31 * day).deviation() > 60, "a month idle widens it");
        assertEquals(LadderBook.Row.fresh(8, LadderRules.Bracket.CHAMPION), book.row(8, LadderRules.Bracket.CHAMPION));
    }

    @Test
    void theTopListIsTheBestRatedWithThreeMatches() {
        MemoryStore store = new MemoryStore();
        LadderBook book = new LadderBook(store);
        for (int i = 0; i < 4; i++) {
            book.record(10, 11, LadderRules.Bracket.VETERAN, 1d, 1L + i);
        }
        book.record(12, 13, LadderRules.Bracket.VETERAN, 1d, 1L);

        List<LadderBook.Ranked> top = book.top(LadderRules.Bracket.VETERAN, 10);

        assertEquals(List.of(10, 11), top.stream().map(r -> r.row().characterId()).toList());
    }

    /** Citizens' own ladder: a best of three under the coliseum's round, decided by the stronger loadout. */
    @Test
    void theWeekStartsOnMondayInUtc() {
        long sunday = 1_763_251_200_000L; // 2025-11-16 00:00 UTC, a Sunday
        long monday = sunday + 24L * 60 * 60 * 1000;
        assertEquals(LadderBook.week(sunday - 1), LadderBook.week(sunday));
        assertEquals(LadderBook.week(sunday) + 1, LadderBook.week(monday));
        assertEquals(LadderBook.week(monday), LadderBook.week(monday + 6L * 24 * 60 * 60 * 1000 + 1));
    }

    @Test
    void theWeeksClimbCountsFromItsFirstMatchAndStartsOverNextWeek() {
        MemoryStore store = new MemoryStore();
        LadderBook book = new LadderBook(store);
        long monday = 1_763_337_600_000L; // 2025-11-17 00:00 UTC
        int week = LadderBook.week(monday);

        book.record(1, 2, LadderRules.Bracket.VETERAN, 1d, monday + 60_000L);
        book.record(1, 3, LadderRules.Bracket.VETERAN, 1d, monday + 120_000L);
        LadderBook.Row one = book.row(1, LadderRules.Bracket.VETERAN);

        assertEquals(1500d, one.weekRating(), 1e-9, "the week counts from the rating its first match began at");
        assertEquals((int) Math.round(one.rating().rating() - 1500d), one.climbed(week));
        assertTrue(one.climbed(week) > 0);
        assertEquals(0, one.climbed(week + 1), "a week it has not played shows no climb");
        assertEquals(List.of(1), book.climbers(LadderRules.Bracket.VETERAN, 5, monday + 180_000L).stream()
                .map(r -> r.row().characterId()).toList(), "only a rise is a climb");

        long nextWeek = monday + 7L * 24 * 60 * 60 * 1000;
        double before = one.rating().rating();
        book.record(1, 2, LadderRules.Bracket.VETERAN, 0d, nextWeek);
        LadderBook.Row next = book.row(1, LadderRules.Bracket.VETERAN);
        assertEquals(week + 1, next.weekOf());
        assertEquals(before, next.weekRating(), 1e-9, "the new week starts from last week's last rating");
        assertTrue(next.climbed(week + 1) < 0);
    }

    @Test
    void aStandingIsItsPlaceAmongTheListedAndZeroUntilItIsListed() {
        MemoryStore store = new MemoryStore();
        LadderBook book = new LadderBook(store);
        LadderRules.Bracket vet = LadderRules.Bracket.VETERAN;
        store.rows.put("1:VETERAN", new LadderBook.Row(1, vet, new Glicko2.Rating(1700, 80, 0.06), 6, 1, 1L));
        store.rows.put("2:VETERAN", new LadderBook.Row(2, vet, new Glicko2.Rating(1600, 80, 0.06), 4, 3, 1L));
        store.rows.put("3:VETERAN", new LadderBook.Row(3, vet, new Glicko2.Rating(1550, 80, 0.06), 3, 3, 1L));
        store.rows.put("4:VETERAN", new LadderBook.Row(4, vet, new Glicko2.Rating(1800, 200, 0.06), 1, 0, 1L));

        assertEquals(List.of(2, 3), List.of(book.standing(2, vet)[0], book.standing(2, vet)[1]),
                "second of the three with three matches");
        assertEquals(0, book.standing(4, vet)[0], "one match is not listed yet, however high");
        assertEquals(3, book.standing(4, vet)[1]);
        assertEquals(0, book.standing(9, vet)[0], "never played");
    }

    @Test
    void aSimulatedBestOfThreeGoesToTheStrongerLoadout() {
        PvpConfig config = new PvpConfig();
        int hRef = 4000;
        double rawDps = 8000;
        double coefficient = hRef / (config.PVP_TTK_SECONDS * rawDps);
        DuelSim.Fighter strong = new DuelSim.Fighter("s", 70, 112, false, hRef, 3000, rawDps, coefficient * 1.6, 200, 0,
                0, 0, 1d, 0, 1d, hRef);
        DuelSim.Fighter weak = new DuelSim.Fighter("w", 70, 112, false, hRef, 3000, rawDps, coefficient, 200, 0, 0, 0, 1d,
                0, 1d, hRef);
        Random random = new Random(5);
        int strongWins = 0;
        for (int i = 0; i < 200; i++) {
            if (Coliseum.simulatedMatch(strong, weak, config, random) == 0) {
                strongWins++;
            }
        }
        assertTrue(strongWins > 150, "the stronger loadout won " + strongWins + " of 200");
        assertEquals("3:9", Coliseum.pairKey(9, 3));
    }
}
