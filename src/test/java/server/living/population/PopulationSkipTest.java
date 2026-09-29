package server.living.population;

import client.Character;
import config.PopulationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.townlife.AgentTownLifeRuntime;
import server.agents.runtime.AgentRuntimeCleanupService;
import server.agents.runtime.AgentRuntimeEntry;
import server.living.LivingWorld;
import server.living.population.CitizenStore.GuildMeta;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A hired citizen is its owner's companion (docs/BOT_MANAGEMENT_PLAN.md P4): the population leaves it alone. The
 * progression ledger neither raises nor reshapes it, Churn does not count it (so it backfills the town) or retire it,
 * the presence scheduler never logs it in (at boot or after) and drops a session it still had without logging it off,
 * and the social fabric keeps it out of circles and guilds. A hand-over ends its session without an unload.
 */
class PopulationSkipTest {
    private static final int HIRED = 950;
    private static final int FREE = 951;

    private final PopulationConfig config = new PopulationConfig();
    private final Population population = mock(Population.class);
    private final CitizenStore store = mock(CitizenStore.class);
    private final PresenceScheduler presence = mock(PresenceScheduler.class);
    private final Map<Integer, Citizen> registry = new LinkedHashMap<>();
    private final List<Citizen> saved = new ArrayList<>();
    private MockedStatic<DatabaseConnection> db;

    @BeforeEach
    void wire() {
        when(population.config()).thenReturn(config);
        when(population.store()).thenReturn(store);
        when(population.presence()).thenReturn(presence);
        when(population.content()).thenReturn(mock(PopulationContent.class));
        when(population.world()).thenReturn(mock(LivingWorld.class));
        when(population.citizens()).thenAnswer(inv -> List.copyOf(registry.values()));
        when(population.citizen(anyInt())).thenAnswer(inv -> registry.get((int) inv.getArgument(0)));
        doAnswer(inv -> {
            Citizen citizen = inv.getArgument(0);
            registry.put(citizen.characterId(), citizen);
            saved.add(citizen);
            return null;
        }).when(population).save(any());
        when(store.characterProgress(anyInt())).thenReturn(new int[] {5, 0, 0, 0, 0});
        db = mockStatic(DatabaseConnection.class); // the ledger's level bump
        db.when(DatabaseConnection::getConnection).thenAnswer(inv -> {
            Connection connection = mock(Connection.class);
            when(connection.prepareStatement(anyString())).thenReturn(mock(PreparedStatement.class));
            return connection;
        });
        put(citizen(HIRED, "Mira", Set.of(Citizen.FLAG_HIRED)));
        put(citizen(FREE, "Tobi", Set.of()));
    }

    @AfterEach
    void close() {
        db.close();
    }

    @Test
    void theProgressionLedgerNeitherRaisesNorReshapesAHiredCitizen() {
        ProgressionLedger ledger = new ProgressionLedger(population);

        ledger.tick();

        verify(store, never()).characterProgress(HIRED);
        verify(store, atLeastOnce()).characterProgress(FREE);
        assertTrue(saved.stream().noneMatch(citizen -> citizen.characterId() == HIRED), "no ledger write for the hire");
        clearInvocations(population.content());
        Character agent = mock(Character.class);
        ledger.catchUp(agent, registry.get(HIRED));
        verify(population.content(), never()).career(any());
        verify(agent, never()).gainMeso(anyInt(), anyBoolean());
    }

    @Test
    void churnDoesNotCountAHiredCitizenSoItBackfillsTheTown() {
        when(population.activeCount()).thenCallRealMethod();
        for (int id = 1; id <= 8; id++) {
            put(citizen(id, "c" + id, Set.of()));
        }
        CitizenGenerator generator = mock(CitizenGenerator.class);
        when(population.generator()).thenReturn(generator);
        when(generator.birth(isNull(), anyInt())).thenReturn(citizen(999, "Newborn", Set.of()));
        config.TARGET_CITIZENS = registry.size(); // 10 rows, one of them hired

        assertEquals(9, population.activeCount());
        new Churn(population).step(System.currentTimeMillis());

        verify(generator, times(1)).birth(isNull(), anyInt()); // the one seat the hire left
    }

    @Test
    void churnNeverRetiresAHiredHandMidJob() {
        long past = System.currentTimeMillis() - 1_000L;
        put(retiring(registry.get(HIRED), past));
        put(retiring(registry.get(FREE), past));
        when(population.social()).thenReturn(mock(SocialFabric.class));

        new Churn(population).tick();

        assertEquals(Citizen.State.ACTIVE, registry.get(HIRED).state());
        assertEquals(Citizen.State.RETIRED, registry.get(FREE).state());
    }

    @Test
    void thePresenceSchedulerNeverLogsInAHiredCitizen() {
        config.ONLINE_MAX = 0; // every login is virtual: no character is loaded here
        when(population.churn()).thenReturn(mock(Churn.class));
        PresenceScheduler scheduler = new PresenceScheduler(population);
        scheduler.forceOnline(10);

        scheduler.sweep();

        assertNotNull(scheduler.session(FREE));
        assertNull(scheduler.session(HIRED), "the roster owns the hire: not logged in, at boot or after");
    }

    @Test
    void aSessionAHiredCitizenStillHadIsDroppedWithoutALogout() {
        when(population.churn()).thenReturn(mock(Churn.class));
        when(population.chatter()).thenReturn(mock(ChatterEngine.class));
        when(population.market()).thenReturn(mock(Market.class));
        when(population.ledger()).thenReturn(mock(ProgressionLedger.class));
        PresenceScheduler scheduler = new PresenceScheduler(population);
        put(registry.get(HIRED).withoutFlag(Citizen.FLAG_HIRED));
        CitizenSession session = scheduler.login(registry.get(HIRED), System.currentTimeMillis(), true);
        loaded(session);
        put(registry.get(HIRED).withFlag(Citizen.FLAG_HIRED));
        config.ONLINE_MAX = 0;

        try (MockedStatic<AgentRuntimeCleanupService> cleanup = mockStatic(AgentRuntimeCleanupService.class)) {
            scheduler.sweep();

            assertNull(scheduler.session(HIRED));
            cleanup.verifyNoInteractions(); // never unloaded, logged off or moved here
        }
    }

    @Test
    void aHandOverFlagsTheCitizenAndEndsItsSessionWithoutAnUnload() {
        when(population.market()).thenReturn(mock(Market.class));
        when(population.ledger()).thenReturn(mock(ProgressionLedger.class));
        PresenceScheduler scheduler = new PresenceScheduler(population);
        CitizenSession session = scheduler.login(registry.get(FREE), System.currentTimeMillis(), true);
        Character agent = loaded(session);

        try (MockedStatic<AgentRuntimeCleanupService> cleanup = mockStatic(AgentRuntimeCleanupService.class);
             MockedStatic<AgentTownLifeRuntime> townLife = mockStatic(AgentTownLifeRuntime.class)) {
            assertTrue(scheduler.handOver(FREE, true, "hired by Ann"));

            cleanup.verifyNoInteractions();
            verify(agent, never()).saveCharToDB();
            verify(population.market()).onLeave(session);
        }
        assertNull(scheduler.session(FREE));
        assertTrue(registry.get(FREE).hired());
        assertFalse(scheduler.handOver(HIRED, false, "hired by Ann"), "an offline citizen has no session to end");
        assertTrue(registry.get(HIRED).hired());
    }

    @Test
    void theSocialFabricKeepsHiredCitizensOutOfCirclesAndGuilds() {
        for (int id = 1; id <= 30; id++) {
            put(citizen(id, "hire" + id, Set.of(Citizen.FLAG_HIRED)));
        }
        // the citizens the population lives with already have a guild, so only guildless hires could be recruited
        put(registry.get(FREE).withGuild(5));
        put(citizen(FREE + 1, "Ilse", Set.of()).withGuild(5));
        put(citizen(FREE + 2, "Kettle", Set.of()).withGuild(5));
        when(store.guilds()).thenReturn(List.of(new GuildMeta(7, "open", 1, 200, "", "evening")));
        when(store.createCircle(anyString(), anyString())).thenReturn(3);
        config.GUILD_TARGET = 1;
        when(presence.sessions()).thenReturn(List.of());
        SocialFabric social = new SocialFabric(population);

        social.tick();

        verify(store).createCircle(anyString(), eq("evening"));
        List<Integer> circled = saved.stream().filter(citizen -> citizen.circleId() == 3).map(Citizen::characterId).toList();
        assertEquals(List.of(FREE, FREE + 1, FREE + 2), circled, "only citizens the population lives with");
        verify(store, never()).setGuild(anyInt(), eq(7), anyInt()); // guildless hires are never recruited
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private void put(Citizen citizen) {
        registry.put(citizen.characterId(), citizen);
    }

    /** Makes a virtual session a loaded one (an agent on a runtime entry). */
    private static Character loaded(CitizenSession session) {
        Character agent = mock(Character.class);
        when(agent.getName()).thenReturn(session.name());
        session.entry = new AgentRuntimeEntry(agent, agent, null);
        session.virtual = false;
        return agent;
    }

    private static Citizen citizen(int characterId, String name, Set<String> flags) {
        return new Citizen(characterId, name, "evening", 0, 0, "victoria", "hunter", "relaxed-v1", "default", 1.0d, 0, 1,
                new LinkedHashSet<>(flags), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
    }

    private static Citizen retiring(Citizen citizen, long retiresAtMs) {
        return new Citizen(citizen.characterId(), citizen.name(), citizen.cohort(), citizen.circleId(), citizen.guildId(),
                citizen.homeRegion(), citizen.career(), citizen.personality(), citizen.voice(), citizen.pace(),
                citizen.offsetMinutes(), citizen.channel(), citizen.flags(), citizen.state(), citizen.joinedAtMs(), retiresAtMs,
                citizen.lastOnlineAtMs(), citizen.lastLedgerAtMs(), citizen.accruedExp(), citizen.scheduledMinutes(),
                citizen.lifeSummary());
    }
}
