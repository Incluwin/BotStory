package server.living.audit;

import org.junit.jupiter.api.Test;
import server.living.audit.TellBands.Band;
import server.living.audit.TellBands.Verdict;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Each band's verdicts: red past the plan's line, amber in the margin, no verdict without enough samples. */
class TellBandsTest {
    @Test
    void aMeasureWhereHigherLooksHumanIsRedUnderTheLineAndAmberInTheMargin() {
        Band band = TellBands.COMBAT_REACTION_CV;
        assertEquals(Verdict.RED, band.judge(0.08, 5));
        assertEquals(Verdict.RED, band.judge(0.2499, 5));
        assertEquals(Verdict.AMBER, band.judge(0.25, 5));
        assertEquals(Verdict.AMBER, band.judge(0.29, 5));
        assertEquals(Verdict.GREEN, band.judge(0.30, 5));
        assertEquals(Verdict.NO_DATA, band.judge(0.08, 2), "two bots are too few");
        assertEquals(Verdict.NO_DATA, band.judge(Double.NaN, 50));
    }

    @Test
    void aMeasureWhereLowerLooksHumanIsRedOverTheLine() {
        Band band = TellBands.POTION_POINT;
        assertEquals(Verdict.RED, band.judge(1.0, 100), "every drink at one HP point");
        assertEquals(Verdict.AMBER, band.judge(0.7, 100));
        assertEquals(Verdict.GREEN, band.judge(0.2, 100));
        assertEquals(Verdict.GREEN, band.judge(0.65, 100), "the margin's edge is still green");
        assertEquals(Verdict.NO_DATA, band.judge(1.0, 29));
    }

    @Test
    void thePlansLinesAreTheRedLines() {
        assertEquals(Verdict.RED, TellBands.REACTION_P5_MS.judge(149, 30), "faster than 150 ms");
        assertEquals(Verdict.GREEN, TellBands.REACTION_P5_MS.judge(400, 30));
        assertEquals(Verdict.RED, TellBands.ROUTE_REPEAT.judge(0.61, 10), "over 60% of repeat walks retraced");
        assertEquals(Verdict.RED, TellBands.BUFF_RECAST.judge(0.81, 30), "over 80% of recasts at one value");
        assertEquals(Verdict.RED, TellBands.ONLINE_SHARE.judge(21.0 / 24, 1), "online 21 h a day");
        assertEquals(Verdict.AMBER, TellBands.ONLINE_SHARE.judge(19.0 / 24, 1));
        assertEquals(Verdict.RED, TellBands.STACKING.judge(0.06, 100));
        assertEquals(Verdict.RED, TellBands.CHAT_REPEAT.judge(0.03, 50));
    }

    @Test
    void anyLongFieldAfkIsRed() {
        assertEquals(Verdict.GREEN, TellBands.FIELD_AFK.judge(0, 12));
        assertEquals(Verdict.RED, TellBands.FIELD_AFK.judge(1, 12));
        assertEquals(Verdict.NO_DATA, TellBands.FIELD_AFK.judge(0, 0), "no AFK seen yet");
    }

    @Test
    void verdictsHaveOneLetterForTheHourlyRow() {
        assertEquals('G', Verdict.GREEN.letter());
        assertEquals('A', Verdict.AMBER.letter());
        assertEquals('R', Verdict.RED.letter());
        assertEquals('-', Verdict.NO_DATA.letter());
        assertEquals('i', Verdict.INFO.letter());
    }
}
