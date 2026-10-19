package server.agents.capabilities.partyquest.opq;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-18, the way up's lever search (AgentOpqLeverSearch): ten pairs of the five levers; after a wrong check Eak
 * (2013001.js) says one pushed lever is correct or both are wrong, and the party keeps the pairs that agree. Every
 * pair Eak can draw is found within four checks, never by reading his draw; the stock walk checked them in order (up
 * to ten).
 */
class AgentOpqLeverSearchTest {
    /** The checks the search needs for {@code drawn}, answering as Eak would. */
    private static int checksFor(AgentOpqLeverSearch.Pair drawn) {
        List<AgentOpqLeverSearch.Pair> candidates = AgentOpqLeverSearch.PAIRS;
        for (int checks = 1; checks <= 10; checks++) {
            AgentOpqLeverSearch.Pair guess = AgentOpqLeverSearch.next(candidates);
            if (guess.equals(drawn)) return checks;
            candidates = AgentOpqLeverSearch.afterWrongCheck(candidates, guess, guess.shared(drawn) > 0);
        }
        throw new AssertionError("never found " + drawn.key());
    }

    @Test
    void thereAreTenPairsWrittenAsEakWritesThem() {
        assertEquals(10, AgentOpqLeverSearch.PAIRS.size());
        assertEquals("11000", AgentOpqLeverSearch.PAIRS.get(0).key());
        for (AgentOpqLeverSearch.Pair pair : AgentOpqLeverSearch.PAIRS) {
            assertEquals(pair, AgentOpqLeverSearch.Pair.parse(pair.key()));
        }
        assertNull(AgentOpqLeverSearch.Pair.parse("11100"), "three pushed is no check");
        assertNull(AgentOpqLeverSearch.Pair.parse("10000"));
        assertNull(AgentOpqLeverSearch.Pair.parse("1100"));
        assertNull(AgentOpqLeverSearch.Pair.parse(null));
        assertThrows(IllegalArgumentException.class, () -> new AgentOpqLeverSearch.Pair(2, 2));
    }

    @Test
    void everyDrawIsFoundWithinFourChecks() {
        int total = 0;
        for (AgentOpqLeverSearch.Pair drawn : AgentOpqLeverSearch.PAIRS) {
            int checks = checksFor(drawn);
            assertTrue(checks <= 4, drawn.key() + " took " + checks);
            total += checks;
        }
        assertTrue(total <= 31, "average at most 3.1 checks, was " + total / 10.0);
    }

    @Test
    void anOffPlanFormationStillPrunesByWhatStood() {
        // The runner was still pulling levers when the leader clicked: 00110 stood, not the planned 11000.
        AgentOpqLeverSearch.Pair drawn = new AgentOpqLeverSearch.Pair(0, 2);
        AgentOpqLeverSearch.Pair stood = AgentOpqLeverSearch.Pair.parse("00110");
        List<AgentOpqLeverSearch.Pair> left = AgentOpqLeverSearch.afterWrongCheck(AgentOpqLeverSearch.PAIRS, stood,
                stood.shared(drawn) > 0);
        assertTrue(left.contains(drawn));
        assertTrue(left.stream().allMatch(pair -> pair.shared(stood) == 1));
        assertEquals(6, left.size());
    }

    @Test
    void anUnheardAnswerOnlyDropsTheCheckedPair() {
        AgentOpqLeverSearch.Pair checked = AgentOpqLeverSearch.PAIRS.get(0);
        List<AgentOpqLeverSearch.Pair> left = AgentOpqLeverSearch.afterWrongCheck(AgentOpqLeverSearch.PAIRS,
                checked, null);
        assertEquals(9, left.size());
        assertTrue(!left.contains(checked));
    }

    @Test
    void anAnswerNoPairAgreesWithStartsOverWithoutTheCheckedPair() {
        AgentOpqLeverSearch.Pair checked = new AgentOpqLeverSearch.Pair(3, 4);
        List<AgentOpqLeverSearch.Pair> only = List.of(checked, new AgentOpqLeverSearch.Pair(0, 1));
        List<AgentOpqLeverSearch.Pair> left = AgentOpqLeverSearch.afterWrongCheck(only, checked, true);
        assertEquals(9, left.size());
    }
}
