package server.agents.capabilities.partyquest.hpq;

import config.AgentYamlConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfile;
import server.life.LifeFactory;
import server.life.Monster;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

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
        AgentPqPaceProfile profile;
        // LifeFactory stubbed on this thread (its real loader reads drop data from the database, which tests lack).
        try (MockedStatic<LifeFactory> life = mockStatic(LifeFactory.class)) {
            life.when(() -> LifeFactory.getMonster(anyInt())).thenAnswer(call -> {
                Monster monster = mock(Monster.class);
                when(monster.getDropPeriodTime()).thenReturn(60_000);
                return monster;
            });
            profile = loader.get();
        }

        assertEquals("hpq", AgentHpqPaceProfile.QUEST_KEY);
        assertEquals(AgentHpqPaceProfile.QUEST_KEY, profile.questKey());
        assertEquals(600_000L, profile.timerMs());
        assertEquals(3, profile.minSize());
        assertEquals(6, profile.maxSize());
        // Step 4 gave it its segments: it warms, so Tory's desk can quote an HPQ clear time.
        assertTrue(profile.warmed());
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
