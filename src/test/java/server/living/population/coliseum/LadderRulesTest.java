package server.living.population.coliseum;

import org.junit.jupiter.api.Test;
import server.pvp.Glicko2;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LadderRulesTest {
    private static LadderRules.Candidate c(int id, int level, double rating) {
        return new LadderRules.Candidate(id, level, new Glicko2.Rating(rating, 100, 0.06));
    }

    @Test
    void bracketsFollowTheLevelBands() {
        assertTrue(LadderRules.Bracket.of(29).isEmpty());
        assertEquals(LadderRules.Bracket.ROOKIE, LadderRules.Bracket.of(30).orElseThrow());
        assertEquals(LadderRules.Bracket.ROOKIE, LadderRules.Bracket.of(59).orElseThrow());
        assertEquals(LadderRules.Bracket.VETERAN, LadderRules.Bracket.of(60).orElseThrow());
        assertEquals(LadderRules.Bracket.CHAMPION, LadderRules.Bracket.of(100).orElseThrow());
        assertEquals("Lv 100+", LadderRules.Bracket.CHAMPION.range());
        assertEquals("Lv 60-99", LadderRules.Bracket.VETERAN.range());
    }

    @Test
    void theOpponentIsTheNearestRatingWithinFiveLevelsAndTheSameBracket() {
        Glicko2.Rating mine = new Glicko2.Rating(1600, 80, 0.06);
        List<LadderRules.Candidate> pool = List.of(
                c(1, 70, 1590),  // 8 levels away
                c(2, 63, 1400),
                c(3, 66, 1650),
                c(4, 59, 1601),  // Rookie bracket
                c(5, 67, 1560));
        assertEquals(5, LadderRules.pick(62, mine, pool).orElseThrow().characterId()); // 40 from 1600
        assertEquals(Optional.empty(), LadderRules.pick(25, mine, pool));
        assertEquals(Optional.empty(), LadderRules.pick(62, mine, List.of(c(1, 70, 1600))));
    }

    @Test
    void tiesGoToTheNearerLevelThenTheLowerId() {
        Glicko2.Rating mine = new Glicko2.Rating(1500, 80, 0.06);
        assertEquals(8, LadderRules.pick(80, mine, List.of(c(9, 84, 1520), c(8, 81, 1480))).orElseThrow()
                .characterId());
        assertEquals(3, LadderRules.pick(80, mine, List.of(c(7, 81, 1510), c(3, 79, 1490))).orElseThrow()
                .characterId());
    }

    @Test
    void aWinPaysTrophiesAndOddsScaledExpForTheFirstFiveWins() {
        LadderRules.Pay even = LadderRules.win(0.5, 0, 1000);
        assertEquals(3, even.trophies());
        assertEquals(6000, even.exp()); // 6 GMin x 1000 x 1.0
        assertEquals(7500, LadderRules.win(0d, 4, 1000).exp()); // beating a sure thing pays 1.25x
        assertEquals(0, LadderRules.win(0.5, 5, 1000).exp());
        assertEquals(3, LadderRules.win(0.5, 5, 1000).trophies());
        assertEquals(new LadderRules.Pay(0, 3), LadderRules.win(0.9, 0, 1000));
        assertEquals(new LadderRules.Pay(0, 1), LadderRules.loss());
        assertEquals(1200, LadderRules.fee(60));
    }

    @Test
    void bestOfThreeEndsAtTwoRoundsOrAfterThree() {
        LadderRules.Score s = LadderRules.Score.START.after(0);
        assertFalse(s.over());
        assertEquals(-1, s.winner());
        s = s.after(0);
        assertTrue(s.over());
        assertEquals(0, s.winner());
        assertEquals("2-0", s.toString());

        LadderRules.Score split = LadderRules.Score.START.after(1).after(0).after(1);
        assertTrue(split.over());
        assertEquals(1, split.winner());

        LadderRules.Score drawn = LadderRules.Score.START.after(0).after(1).after(-1);
        assertTrue(drawn.over());
        assertEquals(-1, drawn.winner());

        LadderRules.Score oneAndDraws = LadderRules.Score.START.after(-1).after(0).after(-1);
        assertTrue(oneAndDraws.over());
        assertEquals(0, oneAndDraws.winner());
    }
}
