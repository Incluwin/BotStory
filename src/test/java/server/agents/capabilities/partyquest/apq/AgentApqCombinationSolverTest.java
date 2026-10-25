package server.agents.capabilities.partyquest.apq;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rope and platform deductions against every combination Amos can draw. */
class AgentApqCombinationSolverTest {

    @Test
    void everyRopeCombinationFallsWithinAmossSevenTries() {
        List<int[]> combinations = AgentApqCombinationSolver.ropes().candidates();
        assertEquals(21, combinations.size(), "five bodies on three ropes");
        int worst = 0;
        int total = 0;
        for (int[] answer : combinations) {
            int tries = solve(AgentApqCombinationSolver.ropes(), answer);
            worst = Math.max(worst, tries);
            total += tries;
        }
        assertTrue(worst <= AgentApqDefinition.ROPE_MISSES_PER_DRAW, "worst " + worst + " tries");
        assertTrue(total / 21.0d < 4.0d, "average " + total / 21.0d + " tries");
    }

    @Test
    void everyPlatformCombinationFallsWithinSevenTries() {
        List<int[]> combinations = AgentApqCombinationSolver.platforms().candidates();
        assertEquals(126, combinations.size(), "five of nine platforms");
        int worst = 0;
        int total = 0;
        for (int[] answer : combinations) {
            int tries = solve(AgentApqCombinationSolver.platforms(), answer);
            worst = Math.max(worst, tries);
            total += tries;
        }
        assertTrue(worst <= 7, "worst " + worst + " tries");
        assertTrue(total / 126.0d < 5.5d, "average " + total / 126.0d + " tries");
    }

    @Test
    void amosCountsRopesThatWeighTheSameAndSlimesCountBodiesRight() {
        assertEquals(0, AgentApqCombinationSolver.sameCounts(new int[] {2, 2, 1}, new int[] {1, 1, 3}));
        assertEquals(1, AgentApqCombinationSolver.sameCounts(new int[] {2, 2, 1}, new int[] {2, 0, 3}));
        assertEquals(3, AgentApqCombinationSolver.sameCounts(new int[] {2, 2, 1}, new int[] {2, 2, 1}));
        assertEquals(2, AgentApqCombinationSolver.bodiesRight(new int[] {1, 1, 1, 1, 1, 0, 0, 0, 0},
                new int[] {1, 0, 0, 0, 1, 1, 1, 1, 0}));
    }

    @Test
    void anAnswerNoCandidateGivesStartsOver() {
        AgentApqCombinationSolver solver = AgentApqCombinationSolver.ropes();
        assertTrue(solver.observe(new int[] {5, 0, 0}, 0));
        int left = solver.remaining();
        assertTrue(left < 21);
        // a right try never answers "one rope" for itself: no candidate draws it, so the solver starts over
        assertFalse(solver.observe(new int[] {5, 0, 0}, 3));
        assertEquals(21, solver.remaining());
    }

    @Test
    void theNextTryMovesTheFewestBodiesAmongEqualChoices() {
        AgentApqCombinationSolver solver = AgentApqCombinationSolver.platforms();
        int[] standing = {1, 1, 1, 1, 1, 0, 0, 0, 0};
        int[] first = solver.next(standing);
        int[] again = AgentApqCombinationSolver.platforms().next(standing);
        assertArrayEquals(first, again, "the choice depends only on the answers and the bodies");
        assertEquals(AgentApqDefinition.PUZZLE_BODIES, Arrays.stream(first).sum());
    }

    /** Tries until Amos accepts, as the run does: next(), Amos's answer, observe(). */
    private static int solve(AgentApqCombinationSolver solver, int[] answer) {
        int[] standing = null;
        for (int tries = 1; tries <= 20; tries++) {
            int[] tried = solver.next(standing);
            if (Arrays.equals(tried, answer)) {
                return tries;
            }
            boolean ropes = tried.length == 3;
            int said = ropes ? AgentApqCombinationSolver.sameCounts(tried, answer)
                    : AgentApqCombinationSolver.bodiesRight(tried, answer);
            assertTrue(solver.observe(tried, said), "the answer leaves the true combination");
            standing = tried;
        }
        throw new AssertionError("no solve within 20 tries for " + Arrays.toString(answer));
    }
}
