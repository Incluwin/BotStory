package server.agents.diagnostics.arena;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.combat.learning.AgentCombatPolicy;
import server.agents.capabilities.combat.learning.AgentCombatPolicyTable;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decision 13 a: what bench grinders learned out of sight reaches live play only through the arena. {@code
 * !combatarena <agent> policy=candidate} measures the candidate against live play in the policy=learn pairs and adopts
 * it only when it is ahead by a standard error of the paired difference at least (never on noise alone); otherwise the
 * policy file keeps what it had.
 */
class CandidateAdoptionTest {
    private final AgentCombatPolicyTable candidate = candidate();
    private final AgentCombatPolicy.Scenario current = new AgentCombatPolicy.Scenario(true, 900, 850, 400,
            Map.of("1 target", Map.of(0, new AgentCombatPolicyTable.Cell(40, 700, true, 700, 1))));

    @Test
    void aCandidateAheadByItsStandardErrorIsAdopted() {
        for (double difference : new double[] {25, 5}) { // well ahead, and ahead by exactly its standard error
            AgentCombatArenaService.Evaluation evaluation = new AgentCombatArenaService.Evaluation(
                    new ArenaOptimizer.PairedComparison(1000 + difference, 1000, difference, 5), false);

            AgentCombatPolicy.Scenario kept = AgentCombatArenaService.afterCandidateCheck(current, candidate, evaluation, 120);

            assertTrue(kept.adopted());
            assertEquals(candidate.snapshot(), kept.table(), "the candidate's own table, as learned");
            assertEquals(1000 + difference, kept.learnedDps(), 1e-9);
            assertEquals(1000, kept.priorDps(), 1e-9, "measured against live play");
            assertEquals(120, kept.decisions());
        }
    }

    @Test
    void aCandidateWithinTheNoiseOfLivePlayIsNotAdopted() {
        for (double difference : new double[] {0, 3, 4.99}) { // a tie, or ahead by less than the noise of the pairs
            AgentCombatArenaService.Evaluation noise = new AgentCombatArenaService.Evaluation(
                    new ArenaOptimizer.PairedComparison(1000 + difference, 1000, difference, 5), false);

            assertFalse(noise.candidateAdoptable(), "ahead by " + difference + " +/- 5");
            assertSame(current, AgentCombatArenaService.afterCandidateCheck(current, candidate, noise, 120));
        }
        assertTrue(new AgentCombatArenaService.Evaluation(new ArenaOptimizer.PairedComparison(1000, 1000, 0, 5), false)
                .adoptable(), "policy=learn still adopts what is not worse than the prior scorer");
    }

    @Test
    void aWorseCandidateLeavesLivePlayWithWhatItHad() {
        AgentCombatArenaService.Evaluation worse = new AgentCombatArenaService.Evaluation(
                new ArenaOptimizer.PairedComparison(950, 1000, -50, 5), false);

        assertSame(current, AgentCombatArenaService.afterCandidateCheck(current, candidate, worse, 120));
        assertNull(AgentCombatArenaService.afterCandidateCheck(null, candidate, worse, 120),
                "nothing adopted before: still nothing");
    }

    @Test
    void aReadingBoundedByTheDummyNeverAdopts() {
        AgentCombatArenaService.Evaluation capped = new AgentCombatArenaService.Evaluation(
                new ArenaOptimizer.PairedComparison(2000, 1000, 1000, 5), true);

        assertFalse(capped.adoptable());
        assertSame(current, AgentCombatArenaService.afterCandidateCheck(current, candidate, capped, 120));
    }

    @Test
    void policyCandidateIsItsOwnArenaRun() {
        ArenaOptions check = ArenaOptions.parse(new String[]{"a", "job=hero", "policy=candidate", "scenario=single"});
        assertTrue(check.candidate());
        assertFalse(check.learn(), "it learns nothing itself");
        assertFalse(ArenaOptions.parse(new String[]{"a", "policy=learn"}).candidate());
        assertThrows(IllegalArgumentException.class,
                () -> ArenaOptions.parse(new String[]{"a", "policy=candidate", "learn=300"}));
        assertThrows(IllegalArgumentException.class,
                () -> ArenaOptions.parse(new String[]{"a", "policy=candidate", "priors=off"}),
                "a candidate is checked under the priors live play uses");
    }

    private static AgentCombatPolicyTable candidate() {
        AgentCombatPolicyTable table = new AgentCombatPolicyTable();
        table.update("1 target", 1121008, 1500, 0.05, 0, 1500, 1);
        return table;
    }
}
