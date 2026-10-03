package server.agents.capabilities.partyquest.hpq;

import config.AgentYamlConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfile;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P6b HPQ step 1 seams a host builds on before HPQ's companion play lands: the pace profile it registers by key, and
 * whether background (population) HPQ is on, which decides whether Tory's desk stands aside.
 */
class AgentHpqPaceProfileSeamTest {
    private static final String ENABLED = "server.agents.capabilities.partyquest.hpq.AgentHpqPopulationRuntime.ENABLED";

    @AfterEach
    void tearDown() {
        AgentHpqPopulationRuntime.installHostManaged(null, null);
    }

    @Test
    void theProfileIsHpqsAndIsLoadedByKey() {
        Supplier<? extends AgentPqPaceProfile> loader = AgentHpqPaceProfile::load;
        AgentPqPaceProfile profile = loader.get();

        assertEquals("hpq", AgentHpqPaceProfile.QUEST_KEY);
        assertEquals(AgentHpqPaceProfile.QUEST_KEY, profile.questKey());
        assertEquals(600_000L, profile.timerMs());
        assertEquals(3, profile.minSize());
        assertEquals(6, profile.maxSize());
        // Its segments land with the pace step; until then it does not warm, so no HPQ estimate is drawn.
        assertFalse(profile.warmed());
    }

    @Test
    void backgroundHpqNeedsTheFeatureFlagAndAnEnabledPopulation() {
        String configured = AgentYamlConfig.config.tuning.get(ENABLED);
        try {
            AgentYamlConfig.config.tuning.put(ENABLED, "false");
            AgentHpqPopulationRuntime.installHostManaged(() -> true, java.util.Set::of);
            assertFalse(AgentHpqPopulationRuntime.backgroundEnabled(), "flag off");

            AgentYamlConfig.config.tuning.put(ENABLED, "true");
            assertTrue(AgentHpqPopulationRuntime.backgroundEnabled(), "flag on, host population on");

            AgentHpqPopulationRuntime.installHostManaged(() -> false, java.util.Set::of);
            // No unit test runs AgentHpqPopulationRuntime.start, so the Agent population source is off too.
            assertFalse(AgentHpqPopulationRuntime.backgroundEnabled(), "flag on, no population source");
        } finally {
            AgentYamlConfig.config.tuning.put(ENABLED, configured);
        }
    }
}
