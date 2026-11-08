package server.living.population;

import client.BotClient;
import client.Character;
import client.Client;
import config.PopulationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.townlife.AgentTownLifeRuntime;
import server.agents.runtime.AgentRuntimeCleanupService;
import server.agents.runtime.AgentRuntimeEntry;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A citizen whose session runs out while it deals, hosts the bar or sells at a casino post stays on until the shift is
 * over (live 2026-11-08 bar hosts went offline 12 min into a 20-45 min shift), as one in a party quest does.
 */
class PresenceCasinoShiftTest {
    private final PopulationConfig config = new PopulationConfig();
    private final Population population = mock(Population.class);
    private final CitizenCasino casino = mock(CitizenCasino.class);
    private final Map<Integer, Citizen> registry = new LinkedHashMap<>();
    private MockedStatic<AgentRuntimeCleanupService> cleanup;
    private MockedStatic<AgentTownLifeRuntime> townLife;

    @BeforeEach
    void wire() {
        when(population.config()).thenReturn(config);
        when(population.content()).thenReturn(mock(PopulationContent.class));
        when(population.chatter()).thenReturn(mock(ChatterEngine.class));
        when(population.social()).thenReturn(mock(SocialFabric.class));
        when(population.market()).thenReturn(mock(Market.class));
        when(population.churn()).thenReturn(mock(Churn.class));
        when(population.casino()).thenReturn(casino);
        when(population.citizen(anyInt())).thenAnswer(inv -> registry.get((int) inv.getArgument(0)));
        cleanup = mockStatic(AgentRuntimeCleanupService.class);
        townLife = mockStatic(AgentTownLifeRuntime.class);
    }

    @AfterEach
    void close() {
        townLife.close();
        cleanup.close();
    }

    @Test
    void aSessionThatRunsOutMidShiftStaysUntilTheShiftIsOver() {
        PresenceScheduler scheduler = new PresenceScheduler(population);
        Character agent = loaded(scheduler, 961, "Neshao");
        CitizenSession session = scheduler.session(961);
        session.endsAtMs = System.currentTimeMillis() - 1;
        when(casino.holds(session)).thenReturn(true);

        scheduler.sweep();

        assertNotNull(scheduler.session(961), "still on shift");
        verify(agent.getClient(), never()).forceDisconnect();

        when(casino.holds(session)).thenReturn(false); // the shift is over
        scheduler.sweep();

        assertNull(scheduler.session(961), "logged off once the shift is over");
        verify(agent.getClient()).forceDisconnect();
    }

    @Test
    void aSessionThatRunsOutWithNoShiftLogsOff() {
        PresenceScheduler scheduler = new PresenceScheduler(population);
        loaded(scheduler, 962, "Tobi");
        scheduler.session(962).endsAtMs = System.currentTimeMillis() - 1;
        when(casino.holds(any())).thenReturn(false);

        scheduler.sweep();

        assertNull(scheduler.session(962));
    }

    private Character loaded(PresenceScheduler scheduler, int characterId, String name) {
        Citizen citizen = new Citizen(characterId, name, "evening", 0, 0, "victoria", "hunter", "relaxed-v1", "default", 1.0d,
                0, 1, new LinkedHashSet<>(), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
        registry.put(characterId, citizen);
        CitizenSession session = scheduler.login(citizen, System.currentTimeMillis(), true);
        Character agent = mock(Character.class);
        Client client = mock(BotClient.class);
        when(agent.getName()).thenReturn(name);
        when(agent.getClient()).thenReturn(client);
        session.entry = new AgentRuntimeEntry(agent, agent, null);
        session.virtual = false;
        return agent;
    }
}
