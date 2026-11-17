package server.living.population.coliseum;

import org.junit.jupiter.api.Test;
import server.pvp.Glicko2;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColiseumBoardTest {
    private static final int WEEK = 2915;

    private static LadderBook.Ranked ranked(int id, String name, int level, double rating, int wins, int losses,
                                            int weekOf, double weekRating) {
        return new LadderBook.Ranked(new LadderBook.Row(id, LadderRules.Bracket.VETERAN,
                new Glicko2.Rating(rating, 80, 0.06), wins, losses, 1L, weekOf, weekRating), name, level);
    }

    @Test
    void theTopListShowsPlaceNameLevelRatingRecordAndThisWeeksMove() {
        String text = ColiseumBoard.topText(List.of(
                ranked(1, "Leaf749", 98, 1702.4, 12, 3, WEEK, 1658),
                ranked(2, "GrumpyDit", 95, 1650, 9, 5, WEEK - 1, 1600)), LadderRules.Bracket.VETERAN, WEEK);

        assertTrue(text.startsWith("The Veteran ladder (Lv 60-99):"), text);
        assertTrue(text.contains("1. #bLeaf749#k Lv 98  1702  (12-3)  +44\r\n"), text);
        assertTrue(text.contains("2. #bGrumpyDit#k Lv 95  1650  (9-5)\r\n"), "last week's move is not shown: " + text);
    }

    @Test
    void anEmptyBracketSaysSo() {
        assertTrue(ColiseumBoard.topText(List.of(), LadderRules.Bracket.CHAMPION, WEEK)
                .contains("Nobody has 3 matches in yet."));
        assertEquals("Nobody has climbed yet.\r\n", ColiseumBoard.climbText(List.of(), WEEK));
    }

    @Test
    void aClimbShowsTheRiseAndWhereItGot() {
        String text = ColiseumBoard.climbText(List.of(ranked(3, "Nolith", 99, 1611, 5, 1, WEEK, 1480)), WEEK);

        assertEquals("#bNolith#k +131 to 1611\r\n", text);
    }

    @Test
    void placesAndMovesReadAsWords() {
        assertEquals("1st", ColiseumBoard.ordinal(1));
        assertEquals("2nd", ColiseumBoard.ordinal(2));
        assertEquals("3rd", ColiseumBoard.ordinal(3));
        assertEquals("11th", ColiseumBoard.ordinal(11));
        assertEquals("12th", ColiseumBoard.ordinal(12));
        assertEquals("22nd", ColiseumBoard.ordinal(22));
        assertEquals("113th", ColiseumBoard.ordinal(113));
        assertEquals("+5", ColiseumBoard.signed(5));
        assertEquals("-12", ColiseumBoard.signed(-12));
    }

    @Test
    void noPageTextUsesASemicolon() {
        String text = ColiseumBoard.topText(List.of(ranked(1, "A", 70, 1500, 3, 0, WEEK, 1400)),
                LadderRules.Bracket.VETERAN, WEEK) + ColiseumBoard.climbText(List.of(), WEEK);

        assertFalse(text.contains(";"), "the client's NPC window cuts a line at a semicolon");
    }
}
