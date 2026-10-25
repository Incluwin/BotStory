package server.agents.capabilities.expedition.gpq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 3's solver (GPQ plan G3): fed only the fountain's three counts after each wrong try, it finds every one of the
 * 256 hidden offerings within the fountain's seven tries per draw; the counts are 9040006.js's; a fresh solver has
 * seen no judgement, and a redraw keeps the judgements already seen.
 */
class AgentGpqVassalsTest {
    @Test
    void everyHiddenCombinationFallsWithinTheFountainsSevenTries() {
        int worst = 0;
        for (int combo = 0; combo < AgentGpqVassals.CODES; combo++) {
            AgentGpqVassals vassals = new AgentGpqVassals();
            int tries = 0;
            while (true) {
                int[] trying = vassals.trying();
                tries++;
                int tried = AgentGpqVassals.code(trying);
                if (tried == combo) {
                    break;
                }
                assertTrue(vassals.judged(tries, trying, AgentGpqVassals.verdict(tried, combo)));
                assertTrue(tries < 7, "combination " + combo + " still open after " + tries + " tries");
            }
            worst = Math.max(worst, tries);
        }
        assertTrue(worst <= 7, "worst " + worst);
    }

    @Test
    void theCountsAreTheFountainsOwn() {
        // 9040006.js on guess 0011 against combo 0101: two pleased; of the rest one 0 and one 1 each way, none unknown
        assertEquals(new AgentGpqVassals.Verdict(2, 2, 0), AgentGpqVassals.verdict(code(0, 0, 1, 1), code(0, 1, 0, 1)));
        // guess 2222 against 0123: one pleased, the three other 2s are offered more often than hidden
        assertEquals(new AgentGpqVassals.Verdict(1, 0, 3), AgentGpqVassals.verdict(code(2, 2, 2, 2), code(0, 1, 2, 3)));
        assertEquals(new AgentGpqVassals.Verdict(4, 0, 0), AgentGpqVassals.verdict(code(3, 1, 0, 2), code(3, 1, 0, 2)));
    }

    @Test
    void theEventsComboReadsThousandsFirstAndAResetReadsAsNone() {
        assertArrayEquals(new int[] {0, 0, 2, 3}, AgentGpqVassals.digits(AgentGpqVassals.comboOf("23")));
        assertArrayEquals(new int[] {3, 1, 0, 2}, AgentGpqVassals.digits(AgentGpqVassals.comboOf("3102")));
        assertEquals(-1, AgentGpqVassals.comboOf("reset"));
        assertEquals(-1, AgentGpqVassals.comboOf(null));
        assertEquals(-1, AgentGpqVassals.comboOf("4000"), "a kind the fountain never draws");
    }

    @Test
    void aFreshSolverHasSeenNoJudgementAndARedrawKeepsTheOnesSeen() {
        AgentGpqVassals vassals = new AgentGpqVassals();
        assertEquals(0, vassals.judgedSeen(), "the event's wrong effects start at 0: nothing to learn yet");
        assertFalse(vassals.judged(0, vassals.trying(), new AgentGpqVassals.Verdict(0, 0, 4)));

        vassals.judged(3, vassals.trying(), new AgentGpqVassals.Verdict(1, 1, 2));
        vassals.newDraw();
        assertEquals(3, vassals.judgedSeen());
        assertEquals(AgentGpqVassals.CODES, vassals.left(), "every combination possible again");
        assertEquals(1, vassals.draws());
    }

    private static int code(int a, int b, int c, int d) {
        return AgentGpqVassals.code(new int[] {a, b, c, d});
    }
}
