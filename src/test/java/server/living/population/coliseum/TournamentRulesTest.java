package server.living.population.coliseum;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The weekly tournament's pure rules and its draw (docs/PVP_PLAN.md V5). */
class TournamentRulesTest {
    private static TournamentRules.Entrant e(int id, boolean human, double rating) {
        return new TournamentRules.Entrant(id, "n" + id, 70, human, rating);
    }

    @Test
    void playersTakeSeatsFirstAndTheBestRatedCitizensFillTheRestSeededByRating() {
        List<TournamentRules.Entrant> players = List.of(e(1, true, 1300));
        List<TournamentRules.Entrant> citizens = List.of(e(10, false, 1500), e(11, false, 2000), e(12, false, 1200),
                e(13, false, 1800));
        List<TournamentRules.Entrant> seated = TournamentRules.seat(players, citizens, 4);
        // the player keeps a seat though three citizens rate higher; 1200 is left out
        assertEquals(List.of(11, 13, 10, 1), seated.stream().map(TournamentRules.Entrant::characterId).toList());
    }

    @Test
    void moreSignUpsThanSeatsSeatsTheBestRatedPlayers() {
        List<TournamentRules.Entrant> players = List.of(e(1, true, 1100), e(2, true, 1900), e(3, true, 1500));
        List<TournamentRules.Entrant> seated = TournamentRules.seat(players, List.of(e(10, false, 2500)), 2);
        assertEquals(List.of(2, 3), seated.stream().map(TournamentRules.Entrant::characterId).toList());
    }

    @Test
    void theDrawKeepsTheTopTwoSeedsApartUntilTheFinal() {
        assertArrayEquals(new int[] {1, 8, 4, 5, 2, 7, 3, 6}, TournamentRules.order(8));
        assertArrayEquals(new int[] {1, 4, 2, 3}, TournamentRules.order(4));
        assertEquals(3, TournamentRules.rounds(8));
        assertEquals(1, TournamentRules.rounds(2));
        assertEquals("quarter-finals", TournamentRules.roundName(0, 3));
        assertEquals("semi-finals", TournamentRules.roundName(1, 3));
        assertEquals("final", TournamentRules.roundName(2, 3));
        assertEquals("semi-finals", TournamentRules.roundName(0, 2));
    }

    @Test
    void howFarAKnockedOutSeatGotDependsOnTheRoundsLeft() {
        assertEquals(0, TournamentRules.reachedWhenOut(0, 3));
        assertEquals(1, TournamentRules.reachedWhenOut(1, 3));
        assertEquals(2, TournamentRules.reachedWhenOut(2, 3));
        assertEquals(1, TournamentRules.reachedWhenOut(0, 2)); // a 4-seat draw opens with the semi-finals
        assertEquals(2, TournamentRules.reachedWhenOut(0, 1));
    }

    @Test
    void aDrawnBoutSendsTheBetterSeedOn() {
        assertEquals(1, TournamentRules.advancing(1, 1, 8));
        assertEquals(0, TournamentRules.advancing(-1, 3, 6));
        assertEquals(1, TournamentRules.advancing(-1, 7, 2));
    }

    @Test
    void prizesFollowThePlanAndTheHouseKeepsAQuarter() {
        long pool = 112_000;
        assertEquals(new TournamentRules.Prize(40, 56_000), TournamentRules.prize(TournamentRules.CHAMPION, pool));
        assertEquals(new TournamentRules.Prize(20, 28_000), TournamentRules.prize(2, pool));
        assertEquals(new TournamentRules.Prize(10, 0), TournamentRules.prize(1, pool));
        assertEquals(new TournamentRules.Prize(5, 0), TournamentRules.prize(0, pool));
        assertEquals(new TournamentRules.Prize(0, 0), TournamentRules.prize(TournamentRules.NONE, pool));
        assertEquals(16_000, TournamentRules.fee(80, 200));
    }

    @Test
    void theWeekRunsFromMondayAndSignUpClosesAtTheStart() {
        ZoneId zone = ZoneId.of("America/New_York");
        ZonedDateTime tuesday = ZonedDateTime.of(2026, 11, 17, 9, 30, 0, 0, zone);
        int week = TournamentRules.weekOf(tuesday);
        assertEquals(LocalDate.of(2026, 11, 16).toEpochDay(), week);
        ZonedDateTime start = TournamentRules.start(week, DayOfWeek.SATURDAY, 20, zone);
        assertEquals(ZonedDateTime.of(2026, 11, 21, 20, 0, 0, 0, zone), start);
        assertTrue(TournamentRules.signUpOpen(tuesday, week, DayOfWeek.SATURDAY, 20));
        assertFalse(TournamentRules.signUpOpen(start, week, DayOfWeek.SATURDAY, 20));
        assertEquals(week, TournamentRules.weekOf(ZonedDateTime.of(2026, 11, 22, 23, 59, 0, 0, zone)));
        assertEquals("Saturday 20:00", TournamentRules.when(DayOfWeek.SATURDAY, 20));
        assertEquals("Monday 08:00", TournamentRules.when(DayOfWeek.MONDAY, 8));
    }

    @Test
    void anEightSeatDrawCrownsAChampionAndRecordsHowFarEachGot() {
        TournamentDraw draw = new TournamentDraw(8, 8);
        assertEquals(4, draw.open().size());
        // quarter-finals: the better seed (side 0) wins every pair but the 4-5 one
        for (TournamentDraw.Pair pair : draw.open()) {
            draw.decide(pair.index(), pair.index() == 1 ? 1 : 0);
        }
        assertTrue(draw.next());
        List<TournamentDraw.Pair> semis = draw.open();
        assertEquals(2, semis.size());
        assertEquals(0, semis.get(0).a());
        assertEquals(4, semis.get(0).b()); // seed 5 came through the 4-5 pair
        draw.decide(0, 1);
        draw.decide(1, 0);
        assertTrue(draw.next());
        draw.decide(0, 0);
        assertFalse(draw.next());
        assertTrue(draw.over());
        assertEquals(4, draw.champion());
        assertEquals(1, draw.runnerUp());
        assertEquals(TournamentRules.CHAMPION, draw.reached(4));
        assertEquals(2, draw.reached(1));
        assertEquals(1, draw.reached(0));
        assertEquals(0, draw.reached(7));
    }

    @Test
    void emptySeatsAreByesAndTheSeedInThemGoesStraightThrough() {
        TournamentDraw draw = new TournamentDraw(8, 5); // seeds 6, 7, 8 empty
        List<TournamentDraw.Pair> open = draw.open();
        assertEquals(1, open.size()); // only 4 v 5 is fought
        assertEquals(3, open.get(0).a());
        assertEquals(4, open.get(0).b());
        assertEquals(4, draw.pairs().size());
        assertTrue(draw.pairs().get(0).bye());
        assertFalse(draw.roundDone());
        draw.decide(open.get(0).index(), 0);
        assertTrue(draw.next());
        assertEquals(2, draw.open().size()); // 1 v 4, 2 v 3
        assertEquals(TournamentRules.NONE, draw.reached(0)); // seed 1 is still in after its bye
        assertEquals(0, draw.reached(4)); // seed 5 lost in the quarter-finals
    }

    @Test
    void twoEntrantsMakeAFinalOnly() {
        TournamentDraw draw = new TournamentDraw(2, 2);
        assertEquals(1, draw.rounds());
        draw.decide(0, 1);
        assertFalse(draw.next());
        assertEquals(1, draw.champion());
        assertEquals(0, draw.runnerUp());
    }

    @Test
    void theLetterTellsAPlayerWhatTheyMissedAndWhetherThePrizeWaits() {
        Tournament.Letter paid = Tournament.letter("Crewfriend", List.of("VETERAN|1|Wavaki|10|0|1"));
        assertEquals("Tigun", paid.sender());
        assertEquals("Crewfriend, The Veteran tournament: you were a semi-finalist and Wavaki won it (10 Event Trophies)."
                + " Your prize is in your bags.", paid.text());

        // two weeks away no longer fit one note: the older is dropped, the newest and the tail stay
        Tournament.Letter owed = Tournament.letter("Crewfriend",
                List.of("VETERAN|1|Wavaki|10|0|1", "VETERAN|3|Crewfriend|40|56000|0"));
        assertEquals("Crewfriend, The Veteran tournament: you won it (40 Event Trophies and 56,000 mesos)."
                + " Your prize waits with me in Ariant.", owed.text());
        assertTrue(owed.text().length() <= Tournament.LETTER_MAX);

        assertNull(Tournament.letter("x", List.of("broken")));
    }
}
