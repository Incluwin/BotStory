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
