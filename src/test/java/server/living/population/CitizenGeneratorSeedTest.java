package server.living.population;

import client.Character;
import client.Client;
import client.Job;
import client.creator.BotCreator;
import config.LivingWorldConfig;
import config.PopulationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.integration.AgentAccountResolution;
import server.agents.integration.AgentBackingAccountSecurityRuntime;
import server.agents.integration.AgentClientGateway;
import server.agents.integration.AgentClientGatewayRuntime;
import server.agents.integration.AgentIdentityGateway;
import server.agents.integration.AgentIdentityGatewayRuntime;
import server.agents.integration.AgentPersistenceGateway;
import server.agents.integration.AgentPersistenceGatewayRuntime;
import server.living.LivingWorld;
import server.living.config.ContentLoader;
import server.living.population.PopulationContent.Region;
import server.living.resident.ResidentRuntime;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * {@link CitizenGenerator#seed} end to end through birth, with account and character creation stubbed (no database): the
 * saved citizen is homed in the requested region at a life-curve level inside its band and a batch does not retire on
 * one day; an unknown region creates nothing; untargeted births draw exactly what they drew before seeding existed.
 */
class CitizenGeneratorSeedTest {
    private static final PopulationContent CONTENT = PopulationLoader.load(Path.of("scripts/config/population"),
            ContentLoader.load(Path.of("scripts/config/living-world")));

    private final PopulationConfig config = new PopulationConfig();
    private final LivingWorldConfig worldConfig = new LivingWorldConfig();
    private final Population population = mock(Population.class);
    private final List<Citizen> saved = new ArrayList<>();
    private final AtomicInteger characters = new AtomicInteger(5000);
    private final List<Object[]> created = new ArrayList<>();
    private final List<MockedStatic<?>> statics = new ArrayList<>();

    @BeforeEach
    void wire() throws Exception {
        config.LIFE_PACE = 0.8d;
        config.SEED_BACKDATE_DAYS = 180;
        when(population.config()).thenReturn(config);
        when(population.content()).thenReturn(CONTENT);
        when(population.names()).thenReturn(new NameGenerator(CONTENT.names().patterns(), CONTENT.names().lists(),
                CONTENT.names().start(), CONTENT.names().middle(), CONTENT.names().end()));
        when(population.store()).thenReturn(mock(CitizenStore.class));
        when(population.channels()).thenReturn(List.of(1, 2, 3));
        LivingWorld world = mock(LivingWorld.class);
        when(world.config()).thenReturn(worldConfig);
        when(world.residents()).thenReturn(mock(ResidentRuntime.class)); // no map: shaping is skipped
        when(population.world()).thenReturn(world);
        doAnswer(inv -> saved.add(inv.getArgument(0))).when(population).save(any());
        AgentPersistenceGateway persistence = mock(AgentPersistenceGateway.class);
        when(persistence.resolveOrCreateAgentAccount(anyString())).thenAnswer(inv -> AgentAccountResolution.created(characters.get()));
        AgentClientGateway clients = mock(AgentClientGateway.class);
        when(clients.createHeadlessClient(anyInt(), anyInt())).thenAnswer(inv -> mock(Client.class));
        MockedStatic<AgentPersistenceGatewayRuntime> persistenceRuntime = track(mockStatic(AgentPersistenceGatewayRuntime.class));
        persistenceRuntime.when(AgentPersistenceGatewayRuntime::persistence).thenReturn(persistence);
        MockedStatic<AgentBackingAccountSecurityRuntime> security = track(mockStatic(AgentBackingAccountSecurityRuntime.class));
        security.when(() -> AgentBackingAccountSecurityRuntime.lockInteractiveLogin(anyInt())).thenReturn(true);
        MockedStatic<AgentClientGatewayRuntime> clientRuntime = track(mockStatic(AgentClientGatewayRuntime.class));
        clientRuntime.when(AgentClientGatewayRuntime::clients).thenReturn(clients);
        MockedStatic<AgentIdentityGatewayRuntime> identities = track(mockStatic(AgentIdentityGatewayRuntime.class));
        identities.when(AgentIdentityGatewayRuntime::identities).thenReturn(mock(AgentIdentityGateway.class));
        MockedStatic<Character> names = track(mockStatic(Character.class));
        names.when(() -> Character.canCreateChar(anyString())).thenReturn(true);
        MockedStatic<BotCreator> creator = track(mockStatic(BotCreator.class));
        creator.when(() -> BotCreator.createCharacter(any(), anyString(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(),
                anyInt(), anyInt(), any(Job.class))).thenAnswer(inv -> {
                    created.add(inv.getArguments());
                    return characters.incrementAndGet();
                });
    }

    @AfterEach
    void close() {
        statics.forEach(MockedStatic::close);
    }

    @Test
    void aSeedIsHomedInTheRequestedRegionAtALevelInsideItsBand() {
        CitizenGenerator generator = new CitizenGenerator(population);
        for (Region region : CONTENT.regions().values()) {
            if (!CitizenGenerator.reachable(region, CONTENT.careers().values(), config.SEED_BACKDATE_DAYS, config.LIFE_PACE)) {
                continue;
            }
            for (int i = 0; i < 10; i++) {
                Citizen citizen = generator.seed(region.id());
                assertNotNull(citizen, region.id());
                assertEquals(region.id(), citizen.homeRegion());
                assertEquals(citizen, saved.get(saved.size() - 1));
                int level = LifeCurve.levelTarget(citizen.ageDays(System.currentTimeMillis()), citizen.pace(),
                        CONTENT.career(citizen.career()).levelCap(), config.LIFE_PACE);
                assertTrue(region.fits(level), region.id() + " got lv" + level);
                assertTrue(citizen.retiresAtMs() > System.currentTimeMillis(), "a seed is not born retired");
            }
        }
        Citizen newcomer = generator.birth(null, 0);
        String home = switch (CitizenLines.line(CONTENT.career(newcomer.career()))) {
            case CitizenLines.CYGNUS -> "ereve";
            case CitizenLines.ARAN -> "rien";
            default -> "victoria";
        };
        assertEquals(home, newcomer.homeRegion(), "an untargeted lv1 newcomer still picks its region by level and line");
    }

    @Test
    void aBatchOfSeedsRetiresGraduallyNotAllAMonthFromNow() {
        CitizenGenerator generator = new CitizenGenerator(population);
        List<Citizen> seeds = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            seeds.add(generator.seed("leafre"));
        }
        long now = System.currentTimeMillis();
        int lastMonth = 0;
        for (Citizen seed : seeds) {
            long remaining = (seed.retiresAtMs() - seed.joinedAtMs()) / 86_400_000L - seed.ageDays(now);
            assertTrue(remaining >= 30 && remaining <= config.LIFE_DAYS_MAX, "remaining life " + remaining + " days");
            lastMonth += remaining == 30 ? 1 : 0;
        }
        assertTrue(lastMonth <= 3, lastMonth + " of 60 leafre seeds retire exactly 30 days from now"); // clamped: about 20
    }

    /** Draws captured from the generator before seeding (251fada32), regions as f2cb3bcc9's regions.yaml assigns them; an edit to the shipped population content changes these too. */
    @Test
    void untargetedBirthsDrawWhatTheyDrewBeforeSeeding() {
        worldConfig.CYGNUS_ARAN_CAREERS_ENABLED = false; // switch off: the Explorers' careers only, drawn as before them
        CitizenGenerator generator = new CitizenGenerator(population);
        List<String> births = new ArrayList<>();
        for (int backdate : new int[] {0, 0, 7, 30, 45, 90, 120, 150, 180, 2, 60, 175}) {
            Citizen c = generator.birth(null, backdate);
            births.add(String.format(Locale.ROOT, "%s %s %s %s %s %s %.12f %d %d %s life%d", c.name(), c.cohort(), c.homeRegion(),
                    c.career(), c.personality(), c.voice(), c.pace(), c.offsetMinutes(), c.channel(), c.flags(),
                    (c.retiresAtMs() - c.joinedAtMs()) / 86_400_000L));
        }
        Citizen c = generator.birth("nightowl", 100);
        births.add(c.name() + " " + c.cohort() + " " + c.career() + " " + c.homeRegion());
        assertEquals(List.of(
                "Angel266 evening-na victoria magician-cleric explorer-v1 proper-friendly 0.747257965099 14 2 [merchant] life214",
                "CrazySword afk-townie victoria magician-fire relaxed-v1 casual-typos 0.602931311366 59 2 [] life250",
                "BunnySin student-afternoon victoria magician-fire explorer-v1 proper-friendly 0.946713645082 -58 3 [pq-regular] life199",
                "RedOcean evening-na ludibrium warrior-fighter explorer-v1 proper-friendly 0.995720030007 -20 2 [recruiter] life141",
                "iKillKnight student-afternoon victoria warrior-spearman efficient-v1 terse-grinder 0.790507063009 -50 1 [] life215",
                "MushroomMage weekend-eu ariant warrior-page explorer-v1 proper-friendly 1.095557303940 -52 3 [] life120",
                "SilentWolf evening-na orbis warrior-fighter explorer-v1 proper-friendly 1.303065241400 51 2 [pq-regular] life188",
                "RedBunny evening-na malaysia bowman-hunter restless-v1 playful-caps 1.435022807511 -44 3 [] life368",
                "Rookun student-afternoon ludibrium magician-ice explorer-v1 proper-friendly 0.674953233423 49 1 [recruiter] life210",
                "SwiftRanger nightowl victoria pirate-gunslinger explorer-v1 proper-friendly 1.027130607235 9 3 [merchant] life106",
                "GrumpyCat evening-na orbis warrior-fighter restless-v1 playful-caps 1.400174025793 -58 2 [] life342",
                "xKeisx student-afternoon aquarium magician-fire explorer-v1 proper-friendly 0.661406023888 45 2 [recruiter] life390",
                "LittleAngel nightowl warrior-page orbis"), births);
    }

    /** A citizen starts in its own gender's clothes: gender digit ({@code id / 1000 % 10}) 0 male only, 1 female only, else both. */
    @Test
    void aCitizenStartsInItsOwnGendersClothes() {
        CitizenGenerator generator = new CitizenGenerator(population);
        Set<Integer> genders = new HashSet<>();
        for (int i = 0; i < 40; i++) {
            generator.birth(null, 0);
        }
        assertEquals(40, created.size());
        for (Object[] call : created) {
            int gender = (Integer) call[5];
            genders.add(gender);
            for (int slot = 6; slot < 10; slot++) {
                int itemId = (Integer) call[slot];
                int digit = itemId / 1000 % 10;
                assertTrue(digit > 1 || digit == gender, call[1] + " (gender " + gender + ") starts in " + itemId);
            }
        }
        assertEquals(Set.of(0, 1), genders, "40 births cover both genders");
    }



    /**
     * Cygnus Knights and Arans (CYGNUS_ARAN_CAREERS_ENABLED, on as shipped): about a quarter of births, Aran 10% and each
     * Knight 3%; made a Noblesse or a Legend (the Legend in the Aran's clothes and Basic Polearm); below its first job
     * at home in Ereve or Rien, which take no one else. With the switch off none is born.
     */
    @Test
    void aQuarterOfNewCitizensAreKnightsOrAransAndTheYoungOnesLiveInEreveAndRien() {
        CitizenGenerator generator = new CitizenGenerator(population);
        int knights = 0;
        int arans = 0;
        int births = 1200;
        for (int i = 0; i < births; i++) {
            Citizen c = generator.birth(null, i % 181);
            Object[] call = created.get(created.size() - 1);
            String line = CitizenLines.line(CONTENT.career(c.career()));
            int level = LifeCurve.levelTarget(c.ageDays(System.currentTimeMillis()), c.pace(),
                    CONTENT.career(c.career()).levelCap(), config.LIFE_PACE);
            switch (line) {
                case CitizenLines.CYGNUS -> {
                    knights++;
                    assertEquals(Job.NOBLESSE, call[10], c.career());
                    if (level < 10) {
                        assertEquals("ereve", c.homeRegion(), c.name() + " lv" + level);
                    }
                }
                case CitizenLines.ARAN -> {
                    arans++;
                    assertEquals(Job.LEGEND, call[10]);
                    assertEquals(List.of(BotCreator.ARAN_TOP, BotCreator.ARAN_BOTTOM, BotCreator.ARAN_SHOES,
                            BotCreator.BASIC_POLEARM), List.of(call[6], call[7], call[8], call[9]));
                    if (level < 10) {
                        assertEquals("rien", c.homeRegion(), c.name() + " lv" + level);
                    }
                }
                default -> {
                    assertEquals(Job.BEGINNER, call[10]);
                    assertTrue(!c.homeRegion().equals("ereve") && !c.homeRegion().equals("rien"),
                            c.name() + " (" + c.career() + ") lives in " + c.homeRegion());
                }
            }
        }
        assertEquals(0.15, knights / (double) births, 0.03, "five Knights at 3% each");
        assertEquals(0.10, arans / (double) births, 0.03, "Aran at 10%");

        worldConfig.CYGNUS_ARAN_CAREERS_ENABLED = false;
        for (int i = 0; i < 200; i++) {
            assertEquals(CitizenLines.EXPLORER, CitizenLines.line(CONTENT.career(generator.birth(null, i).career())));
        }
    }

    @Test
    void ereveSeedsAreKnightsAndAKnightSeededIntoVictoriaHasItsFirstJob() {
        CitizenGenerator generator = new CitizenGenerator(population);
        for (int i = 0; i < 40; i++) {
            Citizen ereve = generator.seed("ereve");
            assertEquals(CitizenLines.CYGNUS, CitizenLines.line(CONTENT.career(ereve.career())), ereve.career());
            Citizen rien = generator.seed("rien");
            assertEquals(CitizenLines.ARAN, CitizenLines.line(CONTENT.career(rien.career())));
        }
        for (int i = 0; i < 300; i++) {
            Citizen victoria = generator.seed("victoria");
            int level = LifeCurve.levelTarget(victoria.ageDays(System.currentTimeMillis()), victoria.pace(),
                    CONTENT.career(victoria.career()).levelCap(), config.LIFE_PACE);
            if (!CitizenLines.EXPLORER.equals(CitizenLines.line(CONTENT.career(victoria.career())))) {
                assertTrue(level >= 10, victoria.career() + " seeded into Victoria at lv" + level);
            }
        }
    }

    @Test
    void anUnknownRegionCreatesNothing() {
        assertNull(new CitizenGenerator(population).seed("atlantis"));
        assertEquals(5000, characters.get());
        assertTrue(saved.isEmpty());
    }

    private <T> MockedStatic<T> track(MockedStatic<T> mocked) {
        statics.add(mocked);
        return mocked;
    }
}
