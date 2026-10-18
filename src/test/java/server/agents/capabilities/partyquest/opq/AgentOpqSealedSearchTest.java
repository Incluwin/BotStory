package server.agents.capabilities.partyquest.opq;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-17, the sealed room's search (AgentOpqSealedSearch): ten splits of three players over three platforms; after a
 * wrong check Eak (2013001.js) says one platform is right or all are wrong, and the party keeps the splits that agree.
 * With nothing learned every draw is found within four checks (three on average); weighted by what runs solved, it
 * checks the splits Eak draws most first. The old left-major walk needed 6.9 checks on average for this server's draws.
 */
class AgentOpqSealedSearchTest {
    private static final ToLongFunction<AgentOpqSealedSearch.Split> UNIFORM = split -> 1L;
    /**
     * How often each split comes up per 36 draws in 2013001.js, whose Math.round draw favours 111, 201, 210 and 300
     * (each 1/6): the counts a server learns after many runs.
     */
    private static final Map<String, Integer> SOLVED = Map.of("003", 1, "012", 2, "021", 2, "030", 1, "102", 3,
            "111", 6, "120", 3, "201", 6, "210", 6, "300", 6);

    @AfterEach
    void restoreTheMemory() {
        AgentOpqSealedSplitMemory.install(null);
    }

    /** The checks the search needs for {@code drawn}, answering as Eak would. */
    private static int checksFor(AgentOpqSealedSearch.Split drawn, ToLongFunction<AgentOpqSealedSearch.Split> weight) {
        List<AgentOpqSealedSearch.Split> candidates = AgentOpqSealedSearch.SPLITS;
        for (int checks = 1; checks <= 10; checks++) {
            AgentOpqSealedSearch.Split guess = AgentOpqSealedSearch.next(candidates, weight);
            if (guess.equals(drawn)) return checks;
            candidates = AgentOpqSealedSearch.afterWrongCheck(candidates, guess, guess.matches(drawn) > 0);
        }
        throw new AssertionError("never found " + drawn.key());
    }

    @Test
    void thereAreTenSplitsOfThreePlayers() {
        assertEquals(10, AgentOpqSealedSearch.SPLITS.size());
        for (AgentOpqSealedSearch.Split split : AgentOpqSealedSearch.SPLITS) {
            assertEquals(3, split.left() + split.middle() + split.right(), split.key());
            assertEquals(split, AgentOpqSealedSearch.Split.parse(split.key()));
        }
        assertNull(AgentOpqSealedSearch.Split.parse("121"), "four players");
        assertNull(AgentOpqSealedSearch.Split.parse("x"));
    }

    @Test
    void withNothingLearnedEveryDrawIsFoundWithinFourChecks() {
        int total = 0;
        for (AgentOpqSealedSearch.Split drawn : AgentOpqSealedSearch.SPLITS) {
            int checks = checksFor(drawn, UNIFORM);
            assertTrue(checks <= 4, drawn.key() + " took " + checks);
            total += checks;
        }
        assertEquals(30, total, "three checks on average, against 5.5 for any fixed order");
    }

    @Test
    void whatRunsSolvedIsCheckedFirstAndCutsTheAverageForThisServersDraws() {
        ToLongFunction<AgentOpqSealedSearch.Split> learned = split -> 1L + SOLVED.get(split.key());
        double learnedAverage = 0.0d;
        double uniformAverage = 0.0d;
        for (AgentOpqSealedSearch.Split drawn : AgentOpqSealedSearch.SPLITS) {
            double share = SOLVED.get(drawn.key()) / 36.0d;
            learnedAverage += share * checksFor(drawn, learned);
            uniformAverage += share * checksFor(drawn, UNIFORM);
        }
        assertEquals("300", AgentOpqSealedSearch.next(AgentOpqSealedSearch.SPLITS, learned).key(),
                "one of the four splits drawn most");
        assertTrue(learnedAverage < 2.6d, "learned: " + learnedAverage);
        assertTrue(learnedAverage < uniformAverage, learnedAverage + " vs " + uniformAverage);
    }

    @Test
    void eaksAnswerKeepsOnlyTheSplitsThatAgreeWithIt() {
        AgentOpqSealedSearch.Split checked = new AgentOpqSealedSearch.Split(1, 1, 1);
        List<AgentOpqSealedSearch.Split> allWrong = AgentOpqSealedSearch.afterWrongCheck(
                AgentOpqSealedSearch.SPLITS, checked, false);
        assertEquals(List.of("003", "030", "300"), allWrong.stream().map(AgentOpqSealedSearch.Split::key).toList(),
                "no platform holds one player");
        List<AgentOpqSealedSearch.Split> oneRight = AgentOpqSealedSearch.afterWrongCheck(
                AgentOpqSealedSearch.SPLITS, checked, true);
        assertEquals(List.of("012", "021", "102", "120", "201", "210"),
                oneRight.stream().map(AgentOpqSealedSearch.Split::key).toList());
        assertEquals(9, AgentOpqSealedSearch.afterWrongCheck(AgentOpqSealedSearch.SPLITS, checked, null).size(),
                "an answer not heard only drops the split checked");
    }

    @Test
    void answersThatAgreeWithNoSplitStartTheSearchOver() {
        AgentOpqSealedSearch.Split checked = new AgentOpqSealedSearch.Split(3, 0, 0);
        List<AgentOpqSealedSearch.Split> left = List.of(new AgentOpqSealedSearch.Split(2, 1, 0));
        List<AgentOpqSealedSearch.Split> restarted = AgentOpqSealedSearch.afterWrongCheck(left, checked, false);
        assertEquals(9, restarted.size(), "a player Eak did not count: every split but the one checked");
        assertFalse(restarted.contains(checked));
    }

    @Test
    void theSessionAsksAgainOnceWhenEaksAnswerWasNotHeard() {
        AgentOpqSealedSplitMemory.install(new AgentOpqSealedSplitMemory(null));
        AgentOpqSession session = new AgentOpqSession(AgentOpqSession.Mode.AUTONOMOUS, 1L, 1, 1_000L);
        assertEquals("003", session.sealedGuess().key(), "nothing learned: the first of the best splits");

        assertEquals("unheard", session.advanceSealedAttempt(2_000L), "he only asked for exactly three players");
        assertEquals("003", session.sealedGuess().key(), "the same split once more");
        assertEquals(0, session.sealedAttempt());
        assertEquals("unheard-dropped", session.advanceSealedAttempt(3_000L));
        assertEquals(9, session.sealedCandidateCount());
        assertEquals(1, session.sealedAttempt());

        assertEquals("012", session.sealedGuess().key());
        session.recordSealedVerdict(true);
        assertEquals("one-right", session.advanceSealedAttempt(4_000L));
        assertEquals(2, session.sealedAttempt());
        assertEquals(5, session.sealedCandidateCount(), "021, 030, 102, 111 and 210 share a platform's count with 012");
        assertEquals("021", session.sealedGuess().key());
    }

    @Test
    void solvedSplitsAreKeptInTheirFileAndReadBack(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("agents").resolve("opq-sealed-splits.json");
        AgentOpqSealedSplitMemory memory = new AgentOpqSealedSplitMemory(file);
        AgentOpqSealedSearch.Split split = new AgentOpqSealedSearch.Split(2, 0, 1);
        memory.recordSolved(split);
        memory.recordSolved(split);

        assertTrue(Files.isRegularFile(file));
        AgentOpqSealedSplitMemory reread = new AgentOpqSealedSplitMemory(file);
        assertEquals(2, reread.solvedCount(split));
        assertEquals(3L, reread.weight(split), "one plus the solves");
        assertEquals(1L, reread.weight(new AgentOpqSealedSearch.Split(1, 1, 1)));
    }

    @Test
    void anUnreadableFileIsIgnoredAndUnknownSplitsAreDropped(@TempDir Path dir) throws Exception {
        Path broken = dir.resolve("broken.json");
        Files.writeString(broken, "{not json");
        assertEquals(1L, new AgentOpqSealedSplitMemory(broken).weight(new AgentOpqSealedSearch.Split(1, 1, 1)));

        Path odd = dir.resolve("odd.json");
        Files.writeString(odd, "{\"111\": 4, \"999\": 7, \"300\": -2}");
        AgentOpqSealedSplitMemory memory = new AgentOpqSealedSplitMemory(odd);
        assertEquals(4, memory.solvedCount(new AgentOpqSealedSearch.Split(1, 1, 1)));
        assertEquals(0, memory.solvedCount(new AgentOpqSealedSearch.Split(3, 0, 0)), "a negative count is dropped");
    }
}
