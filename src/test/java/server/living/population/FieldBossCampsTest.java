package server.living.population;

import client.Character;
import config.PopulationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.supplies.AgentPotionService;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.life.Monster;
import server.living.LivingWorld;
import server.living.population.records.ClearLog;
import server.living.population.records.FieldBosses;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Citizens at field-boss spawns (docs/WORLD_SYSTEMS_PLAN.md N6.3): off, no citizen hits a field boss; a camper hits one
 * only with a human on the map, above its potion floor, and only when citizens started first or the human who did said
 * yes to "want a hand?"; campers who started first offer a human a seat once; with nobody watching they never hit it
 * and a roll settles it, a win booked like any kill and a loss costing nothing (no deaths).
 */
class FieldBossCampsTest {
    private static final int STUMPY_MOB = 3220000;
    private static final int STUMPY_MAP = 101030404;
    private static final int KIM = 5001;
    private static final int LU = 5002;
    private static final int ANN = 10;

    private final PopulationConfig config = new PopulationConfig();
    private final Population population = mock(Population.class);
    private final PresenceScheduler presence = mock(PresenceScheduler.class);
    private final ChatterEngine chatter = mock(ChatterEngine.class);
    private final ClearLog clears = mock(ClearLog.class);
    private final MapleMap map = mock(MapleMap.class);
    private final Monster stumpy = mock(Monster.class);
    private final Character kim = mock(Character.class);
    private final Character lu = mock(Character.class);
    private final Character ann = mock(Character.class);
    private final List<Character> players = new ArrayList<>();
    private final FieldBosses bosses = new FieldBosses(List.of(new FieldBosses.Boss("field.stumpy", "Stumpy",
            List.of(STUMPY_MOB), List.of(STUMPY_MAP), 35, 30, 60, "script", List.of("stumpy"))));
    private final Random dice = mock(Random.class);
    private MockedStatic<AgentPotionService> potions;
    private MockedStatic<AgentRuntimeIdentityRuntime> identity;
    private CitizenSession kimSession;
    private CitizenSession luSession;
    private FieldBossCamps camps;

    private static Citizen citizen(int id, String name) {
        return new Citizen(id, name, "evening", 0, 0, "victoria", "hunter", "relaxed-v1", "default", 1.0d, 0, 1,
                new LinkedHashSet<>(), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
    }

    private CitizenSession camper(int id, String name, Character chr) {
        CitizenSession session = new CitizenSession(citizen(id, name), 0L, Long.MAX_VALUE, false);
        session.entry = mock(AgentRuntimeEntry.class);
        session.mapId = STUMPY_MAP;
        session.activity = CitizenSession.Activity.TRAIN;
        identity.when(() -> AgentRuntimeIdentityRuntime.bot(session.entry)).thenReturn(chr);
        when(chr.getId()).thenReturn(id);
        when(chr.getLevel()).thenReturn(40);
        when(chr.getMapId()).thenReturn(STUMPY_MAP);
        when(chr.getMap()).thenReturn(map);
        when(chr.isAlive()).thenReturn(true);
        when(chr.getJob()).thenReturn(client.Job.HUNTER);
        when(presence.session(id)).thenReturn(session);
        when(population.isCitizen(id)).thenReturn(true);
        potions.when(() -> AgentPotionService.countPotions(chr)).thenReturn(new int[] {200, 100});
        return session;
    }

    @BeforeEach
    void setUp() {
        config.CITIZEN_FIELD_BOSS_ENABLED = true;
        config.FIELD_BOSS_RECORDS_ENABLED = true;
        potions = org.mockito.Mockito.mockStatic(AgentPotionService.class);
        identity = org.mockito.Mockito.mockStatic(AgentRuntimeIdentityRuntime.class);
        when(population.config()).thenReturn(config);
        when(population.presence()).thenReturn(presence);
        when(population.chatter()).thenReturn(chatter);
        when(population.clears()).thenReturn(clears);
        LivingWorld world = mock(LivingWorld.class);
        server.living.roster.CompanionExecutor executor = mock(server.living.roster.CompanionExecutor.class);
        doAnswer(call -> {
            call.<Runnable>getArgument(1).run();
            return null;
        }).when(executor).submit(anyString(), any(Runnable.class));
        when(world.executor()).thenReturn(executor);
        when(population.world()).thenReturn(world);
        when(chatter.compose(any())).thenReturn("a line");
        when(map.getId()).thenReturn(STUMPY_MAP);
        when(map.getAllPlayers()).thenReturn(players);
        when(map.getAllMonsters()).thenReturn(List.of(stumpy));
        when(stumpy.getId()).thenReturn(STUMPY_MOB);
        when(stumpy.getObjectId()).thenReturn(77);
        when(stumpy.getMap()).thenReturn(map);
        when(stumpy.isAlive()).thenReturn(true);
        when(ann.getId()).thenReturn(ANN);
        when(ann.getName()).thenReturn("Ann");
        when(ann.getJob()).thenReturn(client.Job.BEGINNER);
        kimSession = camper(KIM, "Kim", kim);
        luSession = camper(LU, "Lu", lu);
        when(presence.sessions()).thenReturn(List.of(kimSession, luSession));
        camps = new FieldBossCamps(population, bosses, dice);
    }

    @AfterEach
    void close() {
        potions.close();
        identity.close();
    }

    @Test
    void offTheSwitchNoCitizenHitsAFieldBoss() {
        players.add(ann);
        config.CITIZEN_FIELD_BOSS_ENABLED = false;
        assertFalse(camps.allows(kim, stumpy));
    }

    @Test
    void campersStartOnABossNoHumanHitAndOfferAHumanWhoComesASeatOnce() {
        players.add(ann);
        assertTrue(camps.allows(kim, stumpy), "nobody hit it: citizens may start, a human is watching");
        assertTrue(camps.fight(map, stumpy).campersFirst);

        camps.minute(1_000L);
        camps.minute(61_000L);
        verify(chatter, times(1)).say(any(CitizenSession.class), eq("a line"));
        assertTrue(camps.allows(lu, stumpy), "they were here first");
    }

    @Test
    void aBossAHumanStartedOnIsLeftAloneUntilTheHumanSaysYes() {
        players.add(ann);
        when(stumpy.damageBy(ANN)).thenReturn(5_000L);
        when(stumpy.isAttackedBy(ann)).thenReturn(true);

        assertFalse(camps.allows(kim, stumpy));
        assertFalse(camps.allows(lu, stumpy));
        verify(chatter, times(1)).whisper(eq(kimSession), eq(ann), eq("a line"), eq(FieldBossCamps.TOPIC));

        assertFalse(camps.replied(luSession, ann, "yes"), "only the one who asked hears the answer");
        assertTrue(camps.replied(kimSession, ann, "nah im good"));
        assertFalse(camps.allows(kim, stumpy), "a no keeps them off");
        assertTrue(camps.replied(kimSession, ann, "ok sure"));
        assertTrue(camps.allows(kim, stumpy));
        assertTrue(camps.allows(lu, stumpy), "the party joins on the yes");
    }

    @Test
    void aCamperUnderItsPotionFloorStaysOutOfTheFight() {
        players.add(ann);
        potions.when(() -> AgentPotionService.countPotions(kim)).thenReturn(new int[] {49, 100});
        assertFalse(camps.allows(kim, stumpy));
        assertTrue(camps.allows(lu, stumpy));
    }

    @Test
    void unwatchedCampersNeverHitTheBossAndAWonRollBooksTheKillForAllOfThem() {
        assertFalse(camps.allows(kim, stumpy), "nobody on the map: the boss would not fight back");
        when(dice.nextDouble()).thenReturn(0.0d);

        camps.minute(1_000L);

        verify(map).killMonster(eq(stumpy), any(Character.class), eq(true), eq((short) 0));
        verify(clears).cleared(eq(List.of(kim, lu)), eq("field.stumpy"), eq("field:" + STUMPY_MAP), anyLong(), eq(1_000L));
        camps.minute(2_000L);
        verify(map, times(1)).killMonster(any(Monster.class), any(Character.class), eq(true), eq((short) 0));
    }

    @Test
    void aLostRollCostsNothingAndKillsNobody() {
        when(dice.nextDouble()).thenReturn(0.99d);
        camps.minute(1_000L);

        verify(map, never()).killMonster(any(Monster.class), any(Character.class), eq(true), eq((short) 0));
        verifyNoInteractions(clears);
        verify(kim, never()).updateHp(anyInt());
        verify(lu, never()).updateHp(anyInt());
    }

    @Test
    void theRollGrowsWithCampersAndLevelsAndStaysInItsBounds() {
        assertEquals(0.20d, FieldBossCamps.chance(1, 35, 35), 1e-9);
        assertEquals(0.20d + 0.12d * 2 + 0.02d * 5, FieldBossCamps.chance(3, 40, 35), 1e-9);
        assertEquals(0.05d, FieldBossCamps.chance(1, 10, 35), 1e-9);
        assertEquals(0.90d, FieldBossCamps.chance(6, 80, 35), 1e-9);
    }

    @Test
    void theTrainPickGoesOnlyToAFittingBossOnItsContinentWithMonstersToGrind() {
        when(dice.nextDouble()).thenReturn(0.0d);
        assertEquals(STUMPY_MAP, camps.pick(kimSession, 40, 100000000, id -> true, id -> false));
        assertEquals(0, camps.pick(kimSession, 70, 100000000, id -> true, id -> false), "out of its band");
        assertEquals(0, camps.pick(kimSession, 40, 200000000, id -> true, id -> false), "another continent");
        assertEquals(0, camps.pick(kimSession, 40, 100000000, id -> false, id -> false), "nothing else to grind");
        assertEquals(0, camps.pick(kimSession, 40, 100000000, id -> true, id -> true), "a map it avoids");
    }
}
