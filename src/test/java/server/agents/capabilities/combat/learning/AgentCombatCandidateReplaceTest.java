package server.agents.capabilities.combat.learning;

import client.Character;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

/** A table learned in the arena takes the place of one scenario of the job's candidate and leaves the others. */
class AgentCombatCandidateReplaceTest {
    @Test
    void aComparisonPlaysTheCandidateUnderTheLiveRules() {
        AgentCombatLearningState state = new AgentCombatLearningState();
        AgentCombatPolicyTable candidate = new AgentCombatPolicyTable();
        state.begin(AgentCombatLearner.Mode.LIVE, candidate, 0);
        assertSame(candidate, AgentCombatLearner.tableFor(state, AgentCombatLearner.Mode.LIVE, null, null, 1));

        state.end();
        Character bot = mock(Character.class); // no job: live play has no adopted table for it
        assertNull(AgentCombatLearner.tableFor(state, AgentCombatLearner.Mode.LIVE, bot, null, 1));
    }

    @Test
    void oneScenarioIsReplacedTheOthersStay(@TempDir Path folder) {
        AgentCombatCandidates candidates = new AgentCombatCandidates(folder);
        candidates.table(111).update("1 target", 5, 10, 0.05, 0, 10, 1);
        candidates.table(111).update("2-3 targets", 6, 10, 0.05, 0, 10, 1);
        AgentCombatPolicyTable learned = new AgentCombatPolicyTable();
        learned.update("1 target", 7, 20, 0.05, 0, 20, 1);
        learned.update("2-3 targets", 8, 20, 0.05, 0, 20, 1);

        candidates.replace(111, "single", learned);

        AgentCombatPolicyTable now = candidates.table(111);
        assertNull(now.cell("1 target", 5));
        assertEquals(20, now.cell("1 target", 7).value());
        assertEquals(10, now.cell("2-3 targets", 6).value(), "the pack's own table was not learned here");
        assertNull(now.cell("2-3 targets", 8));
        assertEquals(1, candidates.flush());
        assertEquals(1, new AgentCombatCandidates(folder).loadAll(), "and it is written like any candidate");
    }
}
