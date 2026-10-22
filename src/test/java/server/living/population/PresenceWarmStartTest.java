package server.living.population;

import client.Character;
import config.LivingWorldConfig;
import config.PopulationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.integration.cosmic.CosmicAgentOfflineLoader;
import server.agents.runtime.AgentInteractionRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.living.LivingWorld;
import server.living.config.ContentLoader;
import server.living.config.LivingContent;
import server.living.resident.ResidentSpots;
import server.living.roster.RosterService;
import server.maps.MapleMap;

import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * Boot warm start. Live 2026-10-21, a restart at 06:44 came back with 1 citizen online at 06:49 where 76 had been at
 * 06:35, and the towns took three hours to fill again (101 at 08:14, 224 at 09:44): the sweep logs offline citizens in
 * at the curve's steady-state rate, which only keeps a full server full. The first sweep now replays the last day of
 * sweeps in memory and logs in who would be online, a few dozen loads a sweep, each with the time it has left.
 */
class PresenceWarmStartTest {
    private static final int TOWN = 104000000;
    private static final int[] FLAT = flat(10);

    private final PopulationConfig config = new PopulationConfig();
    // the replay asks for the config and content about a million times: a recording mock would hold every call
    private final Population population = mock(Population.class, withSettings().stubOnly());
    private final PopulationContent content = mock(PopulationContent.class, withSettings().stubOnly());
    private final LivingWorld world = mock(LivingWorld.class);
    private final Map<Integer, Citizen> registry = new LinkedHashMap<>();
    private final List<Integer> loads = new ArrayList<>();
    private MockedStatic<CosmicAgentOfflineLoader> loader;
    private MockedStatic<AgentInteractionRuntime> runtime;
    private MockedStatic<ResidentSpots> spots;
    private MockedStatic<AgentMovementCommandRuntime> movement;

    @BeforeEach
    void wire() {
        when(population.config()).thenReturn(config);
        when(population.content()).thenReturn(content);
        when(population.world()).thenReturn(world);
        when(world.config()).thenReturn(new LivingWorldConfig());
        when(population.churn()).thenReturn(mock(Churn.class));
        when(population.chatter()).thenReturn(mock(ChatterEngine.class));
        when(population.social()).thenReturn(mock(SocialFabric.class));
        when(population.market()).thenReturn(mock(Market.class));
        when(population.ledger()).thenReturn(mock(ProgressionLedger.class));
        FieldCatalog fields = mock(FieldCatalog.class);
        when(fields.homeTown(any(), any())).thenReturn(TOWN);
        when(population.fields()).thenReturn(fields);
        CitizenStore store = mock(CitizenStore.class);
        when(store.characterProgress(anyInt())).thenReturn(new int[]{30, 0});
        when(population.store()).thenReturn(store);
        MapleMap town = mock(MapleMap.class);
        when(town.getId()).thenReturn(TOWN);
        when(population.map(anyInt(), anyInt())).thenReturn(town);
        when(population.citizens()).thenAnswer(inv -> List.copyOf(registry.values()));
        when(population.citizen(anyInt())).thenAnswer(inv -> registry.get((int) inv.getArgument(0)));
        doAnswer(inv -> {
            Citizen citizen = inv.getArgument(0);
            registry.put(citizen.characterId(), citizen);
            return null;
        }).when(population).save(any());
        loader = mockStatic(CosmicAgentOfflineLoader.class);
        loader.when(() -> CosmicAgentOfflineLoader.loadOfflineAgent(anyInt(), anyInt(), anyInt(), any(), any()))
                .thenAnswer(inv -> {
                    loads.add(inv.getArgument(0));
                    return mock(Character.class);
                });
        runtime = mockStatic(AgentInteractionRuntime.class);
        runtime.when(() -> AgentInteractionRuntime.registerDirectorIdleAgent(any()))
                .thenAnswer(inv -> new AgentRuntimeEntry(inv.getArgument(0), null, null));
        spots = mockStatic(ResidentSpots.class);
        movement = mockStatic(AgentMovementCommandRuntime.class);
    }

    @AfterEach
    void close() {
        movement.close();
        spots.close();
        runtime.close();
        loader.close();
    }

    @Test
    void theFirstSweepsLogInWhoTheReplayHasOnlineAtMostTenLoadsASweep() {
        allDay(30);
        config.ONLINE_MAX = 240;
        PresenceScheduler presence = new PresenceScheduler(population, new Random(3));

        presence.sweep();
        assertEquals(PresenceScheduler.WARM_START_LOADS_PER_SWEEP, loads.size(), "one sweep loads ten, not thirty");
        assertEquals(10, presence.loadedCount());
        assertEquals(20, presence.warmStartWaiting());

        presence.sweep();
        assertEquals(20, presence.loadedCount());
        presence.sweep();
        assertEquals(30, presence.loadedCount(), "three sweeps: everyone the replay had online");
        assertEquals(0, presence.warmStartWaiting());
        assertEquals(30, loads.size(), "each loaded once");
        assertEquals(0, presence.virtualCount());
    }

    @Test
    void pastOnlineMaxTheRestLogInVirtualWithoutWaiting() {
        allDay(30);
        config.ONLINE_MAX = 15;
        PresenceScheduler presence = new PresenceScheduler(population, new Random(3));

        presence.sweep();
        presence.sweep();

        assertEquals(15, presence.loadedCount(), "never more loaded than ONLINE_MAX");
        assertEquals(15, presence.virtualCount(), "the rest are online on the ledger, all in the second sweep");
        assertEquals(15, loads.size());
        assertEquals(0, presence.warmStartWaiting());
    }

    @Test
    void aHiredRosteredOrOnlineCitizenIsNeverLoggedInByTheWarmStart() {
        allDay(10);
        config.ONLINE_MAX = 0; // virtual logins: the warm start's choices without the loads
        registry.put(1, registry.get(1).withFlag(Citizen.FLAG_HIRED));
        RosterService roster = mock(RosterService.class);
        when(roster.cachedOwnerOf(anyString())).thenAnswer(inv -> (LivingContent.HIRE_PREFIX + 2).equals(inv.getArgument(0)) ? 77 : 0);
        when(world.roster()).thenReturn(roster);
        PresenceScheduler presence = new PresenceScheduler(population, new Random(3));
        CitizenSession already = presence.login(registry.get(3), System.currentTimeMillis(), true);

        presence.sweep();

        assertNull(presence.session(1), "a hired hand is the roster's");
        assertNull(presence.session(2), "and so is a citizen on a roster, flag or not");
        assertSame(already, presence.session(3), "an online citizen keeps the session it has");
        for (int id = 4; id <= 10; id++) {
            assertNotNull(presence.session(id), "citizen " + id);
        }
    }

    @Test
    void aCitizenHiredWhileItWaitsIsDropped() {
        allDay(20);
        config.ONLINE_MAX = 240;
        PresenceScheduler presence = new PresenceScheduler(population, new Random(3));
        presence.sweep();
        List<Integer> waiting = new ArrayList<>(registry.keySet());
        waiting.removeIf(id -> presence.session(id) != null);
        int hired = waiting.get(0);
        registry.put(hired, registry.get(hired).withFlag(Citizen.FLAG_HIRED));

        presence.sweep();

        assertNull(presence.session(hired));
        assertEquals(19, presence.loadedCount());
        assertEquals(0, presence.warmStartWaiting());
    }

    @Test
    void theWarmSessionsLogInWithTheTimeTheyHaveLeft() {
        when(content.cohort("steady")).thenReturn(cohort("steady", 25, 60, 120)); // about half online, 60-120 minute sessions
        config.ONLINE_MAX = 0;
        for (int id = 1; id <= 400; id++) {
            registry.put(id, citizen(id, "steady", 0, 0));
        }
        PresenceScheduler presence = new PresenceScheduler(population, new Random(5));
        long before = System.currentTimeMillis();

        presence.sweep();

        int online = presence.sessions().size();
        assertTrue(online >= 150 && online <= 300, "about half of 400 online at once, was " + online);
        int endingSoon = 0;
        for (CitizenSession session : presence.sessions()) {
            assertTrue(session.startedAtMs >= before, "online from the boot, not before it: its time online is booked from now");
            assertTrue(session.endsAtMs > before && session.endsAtMs <= before + 121 * 60_000L);
            if (session.endsAtMs < before + 60 * 60_000L) {
                endingSoon++; // a fresh login never ends this soon
            }
        }
        assertTrue(endingSoon >= online / 4, "part-way through their sessions, so they never all log off together: " + endingSoon);
    }

    /**
     * Before/after on the shipped cohorts, the live morning of 2026-10-21: 717 synthetic citizens (TARGET_CITIZENS)
     * born by share, offsets -60..59, in circles of 3-5. Replayed at the live sweep period, they match the live server:
     * 72 online at 06:35 (live 76), 566 sessions at 20:04 the evening before (live 581), and from a cold 06:44 boot 93,
     * 151, 218 online 90, 120, 180 minutes on (live 101, 153, 224). Without the warm start, ten minutes after the boot
     * a handful are online; with it, about as many as a server up all along has, from the first sweeps.
     */
    @Test
    void aRestartOnAWeekdayMorningComesBackAsFullAsTheServerWas() {
        PopulationContent shipped = PopulationLoader.load(Path.of("scripts/config/population"),
                ContentLoader.load(Path.of("scripts/config/living-world")));
        when(population.content()).thenReturn(shipped);
        config.PRESENCE_MULTIPLIER = 1.5d; // as config.yaml ships it
        List<Citizen> citizens = synthetic(shipped, 717, new Random(717));
        ZonedDateTime boot = ZonedDateTime.of(2026, 10, 21, 6, 44, 0, 0, ZoneId.of("America/New_York")); // a Wednesday
        long bootMs = boot.toInstant().toEpochMilli();
        PresenceScheduler presence = new PresenceScheduler(population, new Random(7));

        int upAllAlong = presence.replay(citizens, bootMs, boot, 48 * 3_600_000L).size();
        int warm = presence.replay(citizens, bootMs, boot).size();
        int coldTenMinutesOn = presence.replay(citizens, bootMs + 10 * 60_000L, boot.plusMinutes(10), 10 * 60_000L).size();

        assertTrue(upAllAlong >= 40, "the morning keeps the night's long sessions: " + upAllAlong);
        assertTrue(coldTenMinutesOn * 5 < upAllAlong, "before: " + coldTenMinutesOn + " of " + upAllAlong);
        assertTrue(Math.abs(warm - upAllAlong) * 3 <= upAllAlong, "after: " + warm + " of " + upAllAlong);
    }

    // ---- helpers ----------------------------------------------------------------------------------

    /** {@code count} citizens of a cohort that has everyone online all day, in sessions longer than the replay. */
    private void allDay(int count) {
        config.PRESENCE_MULTIPLIER = 10.0d;
        when(content.cohort("all-day")).thenReturn(cohort("all-day", 100, 2000, 2000));
        for (int id = 1; id <= count; id++) {
            registry.put(id, citizen(id, "all-day", 0, 0));
        }
    }

    private static PopulationContent.Cohort cohort(String id, int peakOnlinePercent, int sessionMin, int sessionMax) {
        return new PopulationContent.Cohort(id, ZoneId.of("UTC"), 100, peakOnlinePercent, FLAT, FLAT, sessionMin, sessionMax,
                Map.of("TOWN", 1));
    }

    /** {@code size} citizens born into the cohorts by share, offsets -60..59, in circles of 3-5 within a cohort. */
    private static List<Citizen> synthetic(PopulationContent content, int size, Random random) {
        List<PopulationContent.Cohort> cohorts = new ArrayList<>(content.cohorts().values());
        int shares = cohorts.stream().mapToInt(PopulationContent.Cohort::share).sum();
        List<List<Citizen>> born = new ArrayList<>();
        cohorts.forEach(cohort -> born.add(new ArrayList<>()));
        for (int id = 1; id <= size; id++) {
            int roll = random.nextInt(shares);
            int pick = 0;
            while ((roll -= cohorts.get(pick).share()) >= 0) {
                pick++;
            }
            born.get(pick).add(citizen(id, cohorts.get(pick).id(), random.nextInt(120) - 60, 0));
        }
        List<Citizen> citizens = new ArrayList<>();
        int circle = 0;
        for (List<Citizen> members : born) {
            int next = 0;
            while (members.size() - next >= 3) {
                int crew = Math.min(members.size() - next, 3 + random.nextInt(3));
                circle++;
                for (int i = 0; i < crew; i++, next++) {
                    citizens.add(members.get(next).withCircle(circle));
                }
            }
            citizens.addAll(members.subList(next, members.size()));
        }
        return citizens;
    }

    private static Citizen citizen(int id, String cohort, int offsetMinutes, int circleId) {
        return new Citizen(id, "Citizen" + id, cohort, circleId, 0, "victoria", "hunter", "relaxed-v1", "default", 1.0d,
                offsetMinutes, 1, new LinkedHashSet<>(), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
    }

    private static int[] flat(int weight) {
        int[] hours = new int[24];
        Arrays.fill(hours, weight);
        return hours;
    }
}
