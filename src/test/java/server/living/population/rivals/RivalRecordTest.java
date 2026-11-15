package server.living.population.rivals;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * V3.4 (docs/PVP_PLAN.md): a human's head-to-head record against its rival crew, counted from its pvp_match rows, and
 * how the crew and the records page say it.
 */
class RivalRecordTest {
    private static final int ANN = 1;
    private static final int KIM = 5001;
    private static final int LU = 5002;

    private static RivalRecord.Match match(int winnerSide, int... foes) {
        List<RivalRecord.Fighter> fighters = new java.util.ArrayList<>();
        fighters.add(new RivalRecord.Fighter(ANN, 0, true, winnerSide == 1, 900, 1200));
        for (int foe : foes) {
            fighters.add(new RivalRecord.Fighter(foe, 1, false, winnerSide == 0, 600, 450));
        }
        return new RivalRecord.Match(0L, winnerSide, fighters);
    }

    @Test
    void theRecordCountsWinsLossesAndDrawsFromThePlayersSide() {
        RivalRecord.Tally tally = RivalRecord.overall(List.of(match(1, KIM), match(1, KIM, LU), match(0, LU),
                match(-1, KIM), match(1, LU)));

        assertEquals(new RivalRecord.Tally(1, 3, 1), tally);
        assertEquals(5, tally.played());
        assertEquals("1-3, 1 draw", tally.yours());
    }

    @Test
    void theCrewSaysTheScoreLeaderFirstAndWhoLeads() {
        assertEquals("ahead", new RivalRecord.Tally(1, 3, 0).standing());
        assertEquals("3-1", new RivalRecord.Tally(1, 3, 0).score());
        assertEquals("behind", new RivalRecord.Tally(2, 0, 0).standing());
        assertEquals("2-0", new RivalRecord.Tally(2, 0, 0).score());
        assertEquals("even", new RivalRecord.Tally(1, 1, 2).standing());
        assertEquals("1-1, 2 draws", new RivalRecord.Tally(1, 1, 2).yours());
        assertEquals(0, RivalRecord.overall(List.of()).played());
    }

    @Test
    void eachCrewMemberHasItsOwnHeadToHeadInFirstMetOrder() {
        Map<Integer, RivalRecord.Tally> foes = RivalRecord.perFoe(List.of(match(1, KIM), match(0, KIM, LU),
                match(1, LU)));

        assertEquals(List.of(KIM, LU), List.copyOf(foes.keySet()));
        assertEquals(new RivalRecord.Tally(1, 1, 0), foes.get(KIM));
        assertEquals(new RivalRecord.Tally(1, 1, 0), foes.get(LU));
    }

    @Test
    void theLettersSayTheDuelsFromTheCrewsSide() {
        assertEquals("duels are 2-0 to us now, that last one was fun :P. come get some",
                RivalCrews.duelNews(new long[] {1, 0, 2, 0}));
        assertEquals("u lead the duels 2-1 after that last one. not for long lol",
                RivalCrews.duelNews(new long[] {0, 2, 1, 0}));
        assertEquals("duels are 1-1. next one settles it", RivalCrews.duelNews(new long[] {-1, 1, 1, 1}));
    }
}
