package server.living.roster;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.partyquest.hpq.AgentHpqPaceProfile;
import server.agents.capabilities.partyquest.hpq.AgentHpqPopulationRuntime;
import server.agents.capabilities.partyquest.kpq.AgentKpqPaceProfile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * Each entrance builds its own quest's pace profile and reads its own quest's background flag: KPQ's entrance loads
 * AgentKpqPaceProfile and HPQ's AgentHpqPaceProfile, never the other's, and only HPQ's entrance stands aside while a
 * background population runs its quest (KPQ's never does). KpqEntrance and HpqEntrance wire these as method
 * references, where the other quest's would compile as well.
 */
class PqEntrancePaceWiringTest {
    @Test
    void kpqsEntranceBuildsKpqsPaceProfileOnly() {
        AgentKpqPaceProfile profile = mock(AgentKpqPaceProfile.class);
        try (MockedStatic<AgentKpqPaceProfile> kpq = mockStatic(AgentKpqPaceProfile.class);
             MockedStatic<AgentHpqPaceProfile> hpq = mockStatic(AgentHpqPaceProfile.class)) {
            kpq.when(() -> AgentKpqPaceProfile.load()).thenReturn(profile);

            assertSame(profile, PqEntrance.of("kpq").paceLoader().get());
            kpq.verify(() -> AgentKpqPaceProfile.load());
            kpq.verifyNoMoreInteractions();
            hpq.verifyNoInteractions();
        }
    }

    @Test
    void hpqsEntranceBuildsHpqsPaceProfileOnly() {
        AgentHpqPaceProfile profile = mock(AgentHpqPaceProfile.class);
        try (MockedStatic<AgentKpqPaceProfile> kpq = mockStatic(AgentKpqPaceProfile.class);
             MockedStatic<AgentHpqPaceProfile> hpq = mockStatic(AgentHpqPaceProfile.class)) {
            hpq.when(() -> AgentHpqPaceProfile.load()).thenReturn(profile);

            assertSame(profile, PqEntrance.of("hpq").paceLoader().get());
            hpq.verify(() -> AgentHpqPaceProfile.load());
            hpq.verifyNoMoreInteractions();
            kpq.verifyNoInteractions();
        }
    }

    @Test
    void onlyHpqsEntranceStandsAsideWhileItsBackgroundPopulationRuns() {
        try (MockedStatic<AgentHpqPopulationRuntime> population = mockStatic(AgentHpqPopulationRuntime.class)) {
            population.when(AgentHpqPopulationRuntime::backgroundEnabled).thenReturn(true);

            assertTrue(PqEntrance.of("hpq").backgroundRuns());
            assertFalse(PqEntrance.of("kpq").backgroundRuns(), "HPQ's background population is not KPQ's");

            population.when(AgentHpqPopulationRuntime::backgroundEnabled).thenReturn(false);

            assertFalse(PqEntrance.of("hpq").backgroundRuns());
        }
    }
}
