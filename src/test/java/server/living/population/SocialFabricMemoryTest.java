package server.living.population;

import client.Character;
import config.PopulationConfig;
import net.server.world.Party;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.integration.AgentPresence;
import server.living.population.CitizenStore.Acquaintance;
import server.maps.MapleMap;

import java.util.LinkedHashSet;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The minute loop and the citizen's memory of people (docs/CITIZEN_PERSON_MEMORY_PLAN.md): each minute a citizen and a
 * player share a map is a sighting the memory hears before the shared minutes move {@code last_at} (so a reunion after
 * hours apart is seen as one), a minute in one party is a minute of their party stint, and a citizen that logs off or
 * retires takes its memories along.
 */
class SocialFabricMemoryTest {
    private static final int CITIZEN = 963;
    private static final int ANN = 10;
    private static final int KERNING = 103_000_000;

    private final Population population = mock(Population.class);
    private final PopulationConfig config = new PopulationConfig();
    private final CitizenStore store = mock(CitizenStore.class);
    private final PresenceScheduler presence = mock(PresenceScheduler.class);
    private final PersonMemories memories = mock(PersonMemories.class);
    private final Character agent = mock(Character.class);
    private final Character ann = mock(Character.class);
    private final MapleMap map = mock(MapleMap.class);
    private CitizenSession session;
    private MockedStatic<AgentRuntimeIdentityRuntime> identity;
    private MockedStatic<AgentPresence> presences;

    @BeforeEach
    void pipAndAnnOnKerning() {
        when(population.config()).thenReturn(config);
        when(population.store()).thenReturn(store);
        when(population.presence()).thenReturn(presence);
        when(population.memories()).thenReturn(memories);
        when(store.acquaintance(anyInt(), anyInt())).thenAnswer(call -> new Acquaintance(call.getArgument(0), call.getArgument(1),
                0, 0, false, false));
        Citizen citizen = new Citizen(CITIZEN, "Pip", "evening", 0, 0, "victoria", "hunter", "relaxed-v1", "default", 1.0d,
                0, 1, new LinkedHashSet<>(), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
        session = new CitizenSession(citizen, 0L, 3_600_000L, false);
        session.entry = mock(AgentRuntimeEntry.class);
        when(presence.sessions()).thenReturn(List.of(session));
        when(agent.getId()).thenReturn(CITIZEN);
        when(agent.getMap()).thenReturn(map);
        when(agent.getMapId()).thenReturn(KERNING);
        when(ann.getId()).thenReturn(ANN);
        when(ann.getName()).thenReturn("Ann");
        when(map.getAllPlayers()).thenReturn(List.of(agent, ann));
        identity = mockStatic(AgentRuntimeIdentityRuntime.class);
        identity.when(() -> AgentRuntimeIdentityRuntime.bot(any())).thenReturn(agent);
        presences = mockStatic(AgentPresence.class);
        presences.when(() -> AgentPresence.isAgent(agent)).thenReturn(true);
    }

    @AfterEach
    void close() {
        presences.close();
        identity.close();
    }

    @Test
    void theMemoryHearsTheSightingBeforeTheSharedMinutesMoveAndAPartyMinuteIsAStintMinute() {
        Party party = mock(Party.class);
        when(party.getId()).thenReturn(7);
        when(agent.getParty()).thenReturn(party);
        when(ann.getParty()).thenReturn(party);

        new SocialFabric(population).acquaintances(1_000_000L);

        InOrder order = inOrder(memories, store);
        order.verify(memories).seen(session, ann, 1_000_000L);
        order.verify(store).saveAcquaintance(new Acquaintance(CITIZEN, ANN, 1, 1, false, false));
        verify(memories).record(CITIZEN, ANN, PersonMemory.Kind.PARTY, KERNING, "", 1);
        verify(memories, never()).seen(eq(session), eq(agent), anyLong());
    }

    @Test
    void onTheSameMapWithoutAPartyItIsASightingOnlyOnceAMinute() {
        SocialFabric fabric = new SocialFabric(population);

        fabric.acquaintances(1_000_000L);
        fabric.acquaintances(1_030_000L);

        verify(memories, times(1)).seen(any(), any(), anyLong());
        verify(memories, never()).record(anyInt(), anyInt(), any(), anyInt(), anyString(), anyInt());
    }

    @Test
    void loggingOffTakesItsMemoriesAlongAndRetiringForgetsThem() {
        SocialFabric fabric = new SocialFabric(population);

        fabric.onLogout(session);
        fabric.onRetire(session.citizen);

        verify(memories).release(CITIZEN);
        verify(memories).forgetCitizen(CITIZEN);
    }
}
