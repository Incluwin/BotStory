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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A citizen's logout unloads it once (the unload saves it in full; no second save ahead of it). At server shutdown
 * the unloads run on several threads for a bounded time: a citizen still waiting at the end of it is left to its
 * channel's shutdown, and one that fails to unload never stops the others.
 */
class PresenceShutdownTest {
    private final PopulationConfig config = new PopulationConfig();
    private final Population population = mock(Population.class);
    private final Map<Integer, Citizen> registry = new LinkedHashMap<>();
    private final List<Integer> booked = new ArrayList<>();
    private MockedStatic<AgentRuntimeCleanupService> cleanup;
    private MockedStatic<AgentTownLifeRuntime> townLife;

    @BeforeEach
    void wire() {
        when(population.config()).thenReturn(config);
        when(population.content()).thenReturn(mock(PopulationContent.class));
        when(population.chatter()).thenReturn(mock(ChatterEngine.class));
        when(population.social()).thenReturn(mock(SocialFabric.class));
        when(population.market()).thenReturn(mock(Market.class));
        when(population.citizen(anyInt())).thenAnswer(inv -> registry.get((int) inv.getArgument(0)));
        doAnswer(inv -> {
            Citizen citizen = inv.getArgument(0);
            registry.put(citizen.characterId(), citizen);
            synchronized (booked) {
                booked.add(citizen.characterId());
            }
            return null;
        }).when(population).save(any());
        cleanup = mockStatic(AgentRuntimeCleanupService.class);
        townLife = mockStatic(AgentTownLifeRuntime.class);
    }

    @AfterEach
    void close() {
        townLife.close();
        cleanup.close();
    }

    @Test
    void aLogoutUnloadsTheCitizenOnceWithNoSaveAheadOfIt() {
        PresenceScheduler scheduler = new PresenceScheduler(population);
        Character agent = loaded(scheduler, 951, "Tobi");
        Client client = agent.getClient();

        scheduler.logout(scheduler.session(951), "session over");

        verify(client).forceDisconnect(); // Client.disconnectInternal saves it in full
        verify(agent, never()).saveCharToDB();
        assertNull(scheduler.session(951));
    }

    @Test
    void shutdownUnloadsTheCitizensOnSeveralThreadsAtOnce() throws Exception {
        config.SHUTDOWN_LOGOUT_THREADS = 3;
        PresenceScheduler scheduler = new PresenceScheduler(population);
        CountDownLatch together = new CountDownLatch(3);
        Set<String> threads = ConcurrentHashMap.newKeySet();
        AtomicInteger metTheOthers = new AtomicInteger();
        for (int id = 951; id <= 953; id++) {
            Client client = loaded(scheduler, id, "Citizen" + id).getClient();
            doAnswer(inv -> {
                threads.add(Thread.currentThread().getName());
                together.countDown();
                if (together.await(5, TimeUnit.SECONDS)) { // only true when all three unload at once
                    metTheOthers.incrementAndGet();
                }
                return null;
            }).when(client).forceDisconnect();
        }

        scheduler.logoutAll("server shutdown");

        assertEquals(3, metTheOthers.get(), "the three unloads overlapped");
        assertEquals(3, threads.size(), threads.toString());
        assertTrue(threads.stream().allMatch(name -> name.startsWith("citizen-logout-")), threads.toString());
        assertEquals(Set.of(951, 952, 953), Set.copyOf(booked.subList(booked.size() - 3, booked.size())),
                "then each one's time online is booked");
        for (int id = 951; id <= 953; id++) {
            assertNull(scheduler.session(id));
        }
    }

    @Test
    void theCitizensNotStartedWithinTheBudgetAreLeftToTheirChannel() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        List<Character> agents = List.of(agent("Slow"), agent("Waiting"), agent("AlsoWaiting"));
        Client slow = agents.get(0).getClient();
        doAnswer(inv -> {
            release.await(5, TimeUnit.SECONDS);
            return null;
        }).when(slow).forceDisconnect();

        long startedNs = System.nanoTime();
        int left = PresenceScheduler.unloadAll(agents, 1, 100L);
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs);
        release.countDown();

        assertEquals(2, left);
        assertTrue(tookMs < 3_000L, "it waited " + tookMs + " ms, not for the slow save");
        verify(agents.get(1).getClient(), never()).forceDisconnect();
        verify(agents.get(2).getClient(), never()).forceDisconnect();
    }

    @Test
    void oneCitizenFailingToUnloadNeverStopsTheOthers() {
        List<Character> agents = List.of(agent("Broken"), agent("Fine"));
        Client broken = agents.get(0).getClient();
        doThrow(new IllegalStateException("socket")).when(broken).forceDisconnect();

        assertEquals(0, PresenceScheduler.unloadAll(agents, 1, 5_000L));

        verify(agents.get(1).getClient()).forceDisconnect();
    }

    // ---- helpers ----------------------------------------------------------------------------------

    /** A citizen logged in, then made a loaded one (an agent on a runtime entry, with a client). */
    private Character loaded(PresenceScheduler scheduler, int characterId, String name) {
        Citizen citizen = new Citizen(characterId, name, "evening", 0, 0, "victoria", "hunter", "relaxed-v1", "default", 1.0d,
                0, 1, new LinkedHashSet<>(), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
        registry.put(characterId, citizen);
        CitizenSession session = scheduler.login(citizen, System.currentTimeMillis(), true);
        Character agent = agent(name);
        session.entry = new AgentRuntimeEntry(agent, agent, null);
        session.virtual = false;
        return agent;
    }

    private static Character agent(String name) {
        Character agent = mock(Character.class);
        Client client = mock(BotClient.class);
        when(agent.getName()).thenReturn(name);
        when(agent.getClient()).thenReturn(client);
        return agent;
    }
}
