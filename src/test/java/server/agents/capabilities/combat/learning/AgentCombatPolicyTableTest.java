package server.agents.capabilities.combat.learning;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCombatPolicyTableTest {
    @Test
    void firstVisitsAreAnExactAverageThenTheStepIsFloored() {
        AgentCombatPolicyTable table = new AgentCombatPolicyTable();
        table.update("s", 1, 100, 0.25, 0, 0, 1);
        table.update("s", 1, 200, 0.25, 0, 0, 1);
        assertEquals(150, table.cell("s", 1).value(), 1e-9);  // step 1/2
        table.update("s", 1, 300, 0.25, 0, 0, 1);
        assertEquals(200, table.cell("s", 1).value(), 1e-9);  // step 1/3
        table.update("s", 1, 400, 0.25, 0, 0, 1);
        assertEquals(250, table.cell("s", 1).value(), 1e-9);  // 1/4 == floor
        table.update("s", 1, 250, 0.25, 0, 0, 1);
        assertEquals(250, table.cell("s", 1).value(), 1e-9);  // floored 0.25, not 1/5
        assertEquals(5, table.visits("s", 1));
    }

    @Test
    void bestActionOnlyConsidersOfferedActionsWithEnoughVisits() {
        AgentCombatPolicyTable table = new AgentCombatPolicyTable();
        table.update("s", 1, 100, 0.05, 0, 0, 1);
        table.update("s", 1, 100, 0.05, 0, 0, 1);
        table.update("s", 2, 900, 0.05, 0, 0, 1);
        assertEquals(1, table.bestAction("s", List.of(1, 2), 2));
        assertEquals(2, table.bestAction("s", List.of(1, 2), 1));
        assertEquals(1, table.bestAction("s", List.of(1, 3), 1));
        assertNull(table.bestAction("other", List.of(1, 2), 1));
        assertTrue(Double.isNaN(table.bestValue("s", List.of(3), 1)));
    }

    @Test
    void aSavedPolicyReadsBackWithItsTables() throws Exception {
        Path directory = Files.createTempDirectory("combat-policy");
        AgentCombatPolicyRepository repository = new AgentCombatPolicyRepository(directory);
        AgentCombatPolicyTable table = new AgentCombatPolicyTable();
        table.update("1 target|orbs full", 1111003, 1234.5, 0.05, 0, 0, 1);
        repository.save(new AgentCombatPolicy(112, "HERO", 120, "profile", "now", 0.9, true,
                Map.of(1111003, "Panic"),
                Map.of("single", new AgentCombatPolicy.Scenario(true, 70000, 60000, 400, table.snapshot()))));

        AgentCombatPolicyRepository.Loaded loaded = repository.find(112).orElseThrow();
        assertTrue(loaded.policy().priorsRelaxed());
        assertTrue(loaded.policy().scenarios().get("single").adopted());
        assertEquals(1234.5, loaded.tables().get("single").cell("1 target|orbs full", 1111003).value(), 1e-9);
    }

    @Test
    void rewardsAreTheExpectationCalibratedByWhatActuallyLanded() {
        AgentCombatPolicyTable table = new AgentCombatPolicyTable();
        // The roll is noise: a high and a low roll around an accurate formula both credit about the expectation.
        double high = table.calibratedReward("s", 7, 1300, 1000, 5);
        double low = table.calibratedReward("s", 7, 700, 1000, 5);
        assertEquals(1000, (high + low) / 2, 60);
        assertTrue(Math.abs(high - low) < 100, "the damage roll must not swing the reward");
        // A skill the server caps at half of what the formula says converges to half.
        AgentCombatPolicyTable capped = new AgentCombatPolicyTable();
        double reward = 0;
        for (int i = 0; i < 200; i++) {
            reward = capped.calibratedReward("s", 8, 500, 1000, 5);
        }
        assertEquals(500, reward, 20);
        // Nothing expected (no estimate): the realized damage stands.
        assertEquals(321, capped.calibratedReward("s", 9, 321, 0, 5), 1e-9);
    }

    @Test
    void liveSituationsMapToTheScenarioTheArenaStaged() {
        assertEquals("boss", AgentCombatPolicy.scenarioFor(true, 5));
        assertEquals("multi", AgentCombatPolicy.scenarioFor(false, 2));
        assertEquals("single", AgentCombatPolicy.scenarioFor(false, 1));
    }
}
