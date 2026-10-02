package server.agents.capabilities.partyquest.pace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.combat.data.AgentAttackDataProvider;
import server.agents.capabilities.partyquest.kpq.AgentKpqPaceProfile;
import server.life.LifeFactory;
import server.life.Monster;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * A pace profile is built once, on the thread that warms it (companion-ops), and only read afterwards: an NPC's lineup
 * on a leader's I/O thread asks {@code cached}, which is null while cold and never touches game data.
 */
class AgentPqPaceProfilesWarmTest {
    /** The shared registry is cold again after each test, so no later test depends on this one having run. */
    @AfterEach
    void coldAgain() {
        AgentPqPaceProfiles.resetForTest();
    }

    @Test
    void cachedIsNullBeforeWarmAndWarmBuildsOnceOnTheWarmingThread() throws Exception {
        AgentPqPaceProfiles.Registry registry = new AgentPqPaceProfiles.Registry();
        AtomicInteger loads = new AtomicInteger();
        List<String> loadThreads = new ArrayList<>();
        PaceStubProfile stub = PaceStubProfile.standard();
        registry.register("STUB", () -> {
            loads.incrementAndGet();
            loadThreads.add(Thread.currentThread().getName());
            return stub;
        });

        assertNull(registry.cached("stub"));
        assertNull(registry.cached(null));
        assertEquals(0, loads.get());

        ExecutorService companionOps = Executors.newSingleThreadExecutor(task -> new Thread(task, "companion-ops"));
        try {
            assertSame(stub, companionOps.submit(() -> registry.warm("stub")).get(30, TimeUnit.SECONDS));
            assertSame(stub, companionOps.submit(() -> registry.warm("stub")).get(30, TimeUnit.SECONDS));
        } finally {
            companionOps.shutdownNow();
        }
        assertSame(stub, registry.cached("stub"));
        assertSame(stub, registry.cached(" Stub "));
        assertEquals(1, loads.get());
        assertEquals(List.of("companion-ops"), loadThreads);
    }

    @Test
    void aProfileThatFailsOrIsUnknownStaysCold() {
        AgentPqPaceProfiles.Registry registry = new AgentPqPaceProfiles.Registry();
        registry.register("broken", () -> {
            throw new IllegalStateException("Map.wz has no life");
        });
        registry.register("cold", () -> PaceStubProfile.standard().cold());
        registry.register("linkage", () -> {
            throw new ExceptionInInitializerError("LifeFactory cannot initialise without its database");
        });

        assertNull(registry.warm("broken"));
        assertNull(registry.warm("cold"));
        assertNull(registry.warm("unknown"));
        assertNull(registry.warm("linkage"));
        assertNull(registry.cached("broken"));
        assertNull(registry.cached("cold"));
        assertNull(registry.cached("linkage"));
    }

    @Test
    void theQuestNeutralPackageNamesNoQuest() throws IOException {
        Pattern questPackage = Pattern.compile("server\\.agents\\.capabilities\\.partyquest\\.(?!pace\\.)[a-z]+\\.");
        List<String> naming = new ArrayList<>();
        try (Stream<Path> sources = Files.list(Path.of("src/main/java/server/agents/capabilities/partyquest/pace"))) {
            for (Path source : sources.toList()) {
                if (questPackage.matcher(Files.readString(source)).find()) naming.add(source.getFileName().toString());
            }
        }
        assertEquals(List.of(), naming, "a quest registers its own profile loader");
    }

    @Test
    void readingTheKpqProfileNeverLoadsMobsOrAttackDataOnTheCallingThread() throws Exception {
        AgentPqPaceProfiles.Registry registry = new AgentPqPaceProfiles.Registry();
        registry.register(AgentKpqPaceProfile.QUEST_KEY, AgentKpqPaceProfile::load);

        // Static mocks are thread-local: they count only the thread that opened them.
        try (MockedStatic<LifeFactory> life = mockStatic(LifeFactory.class);
             MockedStatic<AgentAttackDataProvider> attacks = mockStatic(AgentAttackDataProvider.class)) {
            assertNull(registry.cached("kpq"));
            life.verifyNoInteractions();
            attacks.verifyNoInteractions();
        }

        ExecutorService companionOps = Executors.newSingleThreadExecutor();
        AgentPqPaceProfile warmed;
        try {
            warmed = companionOps.submit(() -> {
                try (MockedStatic<LifeFactory> life = countingLifeFactory()) {
                    AgentPqPaceProfile profile = registry.warm("kpq");
                    // The warm-up loads each profile mob into LifeFactory here, on companion-ops.
                    for (int mobId : profile.mobIds()) life.verify(() -> LifeFactory.getMonster(mobId));
                    return profile;
                }
            }).get(120, TimeUnit.SECONDS);
        } finally {
            companionOps.shutdownNow();
        }
        assertNotNull(warmed);
        assertTrue(warmed.warmed());

        try (MockedStatic<LifeFactory> life = mockStatic(LifeFactory.class);
             MockedStatic<AgentAttackDataProvider> attacks = mockStatic(AgentAttackDataProvider.class)) {
            assertSame(warmed, registry.cached("kpq"));
            assertEquals(4, warmed.segments(4).stream().filter(segment -> segment instanceof AgentPqPaceSegment.Puzzle
                    || segment instanceof AgentPqPaceSegment.Quota).count());
            life.verifyNoInteractions();
            attacks.verifyNoInteractions();
        }
    }

    @Test
    void theSharedRegistryWarmsKpqFromTheGameDataOnceItsLoaderIsRegistered() {
        assertNull(AgentPqPaceProfiles.cached("kpq"));
        AgentPqPaceProfiles.register(AgentKpqPaceProfile.QUEST_KEY, AgentKpqPaceProfile::load);
        AgentPqPaceProfile kpq;
        try (MockedStatic<LifeFactory> ignored = countingLifeFactory()) {
            kpq = AgentPqPaceProfiles.warm("kpq");
        }

        assertNotNull(kpq);
        assertSame(kpq, AgentPqPaceProfiles.cached("KPQ"));
        assertSame(kpq, AgentPqPaceProfiles.warm("kpq"));
        assertNull(AgentPqPaceProfiles.cached("no-such-pq"));

        AgentPqPaceProfiles.resetForTest();
        assertNull(AgentPqPaceProfiles.cached("kpq"), "reset: cold again");
    }

    /** LifeFactory stubbed on this thread (its real loader reads drop data from the database, which tests lack). */
    private static MockedStatic<LifeFactory> countingLifeFactory() {
        MockedStatic<LifeFactory> life = mockStatic(LifeFactory.class);
        life.when(() -> LifeFactory.getMonster(anyInt())).thenAnswer(call -> mock(Monster.class));
        return life;
    }
}
