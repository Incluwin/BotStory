package server.agents.capabilities.combat.rotation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import server.agents.capabilities.combat.rotation.AgentCombatRotationProfile.Choice;
import server.agents.capabilities.combat.rotation.AgentCombatRotationProfile.Trial;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCombatRotationProfileTest {
    private static AgentCombatRotationProfile sample() {
        Choice single = new Choice(1121008, "Brandish", 1500, true, 0);
        Choice multi = new Choice(1111008, "Shout", 2100, true, 3);
        Choice boss = new Choice(1121008, "Brandish", 1400, false, 0);
        Trial trial = new Trial("single", 1121008, "Brandish", 1, true, 25, 37500, 1500, 30, 2, 800);
        return new AgentCombatRotationProfile(112, "HERO", 120, "profile", "2026-09-22T00:00:00Z",
                single, multi, boss, List.of(trial));
    }

    @Test
    void choicesFollowScenario() {
        AgentCombatRotationProfile profile = sample();
        assertEquals(1121008, profile.choiceFor(false, 1).skillId());
        assertEquals(1121008, profile.choiceFor(false, 2).skillId());
        assertEquals(1111008, profile.choiceFor(false, 3).skillId());
        assertEquals(1111008, profile.choiceFor(false, 6).skillId());
        assertFalse(profile.choiceFor(true, 6).useSpecialMoves());
        assertEquals(1121008, profile.choiceFor(true, 6).skillId());
    }

    @Test
    void multiWithoutThresholdNeverPreemptsSingle() {
        AgentCombatRotationProfile profile = new AgentCombatRotationProfile(112, "HERO", 120, "profile", "now",
                new Choice(1, "a", 10, true, 0), new Choice(2, "b", 20, true, 0), null, List.of());
        assertEquals(1, profile.choiceFor(false, 5).skillId());
        assertEquals(1, profile.choiceFor(true, 5).skillId());
    }

    @Test
    void roundTripsThroughJsonAndCachesByModification(@TempDir Path dir) throws Exception {
        AgentCombatRotationProfileRepository repository = new AgentCombatRotationProfileRepository(dir);
        assertTrue(repository.find(112).isEmpty());
        Path path = repository.save(sample());
        assertTrue(Files.isRegularFile(path));
        AgentCombatRotationProfile loaded = repository.find(112).orElseThrow();
        assertEquals("HERO", loaded.jobName());
        assertEquals(3, loaded.multi().aoeFromTargets());
        assertEquals(1, loaded.trials().size());
        assertEquals(37500, loaded.trials().get(0).damage());
        repository.delete(112);
        assertTrue(repository.find(112).isEmpty());
    }
}
