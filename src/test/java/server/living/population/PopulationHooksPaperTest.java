package server.living.population;

import client.Character;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.integration.AgentPresence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** Who the town paper writes up from the level and job hooks (docs/WORLD_SYSTEMS_PLAN.md N1.2). */
class PopulationHooksPaperTest {
    @Test
    void playersAndCitizensButNotGmsOrCompanions() {
        Population population = mock(Population.class);
        Character player = mock(Character.class);
        Character gm = mock(Character.class);
        Character citizen = mock(Character.class);
        Character companion = mock(Character.class);
        when(gm.isGM()).thenReturn(true);
        when(citizen.getId()).thenReturn(5);
        when(companion.getId()).thenReturn(6);
        when(population.isCitizen(5)).thenReturn(true);
        try (MockedStatic<AgentPresence> agents = mockStatic(AgentPresence.class)) {
            agents.when(() -> AgentPresence.isAgent(citizen)).thenReturn(true);
            agents.when(() -> AgentPresence.isAgent(companion)).thenReturn(true);
            assertEquals(Boolean.TRUE, PopulationHooks.paperSubject(population, player));
            assertNull(PopulationHooks.paperSubject(population, gm));
            assertEquals(Boolean.FALSE, PopulationHooks.paperSubject(population, citizen));
            assertNull(PopulationHooks.paperSubject(population, companion));
        }
    }
}
