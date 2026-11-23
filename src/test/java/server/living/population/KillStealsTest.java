package server.living.population;

import client.Character;
import config.PopulationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.integration.AgentPresence;
import server.life.Monster;
import server.maps.MapleMap;

import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kill-stealing (docs/WORLD_SYSTEMS_PLAN.md N2.4, decision D4): a human who finishes KS_KILLS mobs inside KS_WINDOW_MS
 * that one citizen, on the map first, had done KS_SHARE_PERCENT of leaves it one KS memory; ordinary side-by-side
 * training for half an hour leaves none; a citizen doing it to a human is only counted (the manners measurement).
 */
class KillStealsTest {
    private static final int CITIZEN = 963;
    private static final int HUMAN = 10;
    private static final int FIELD = 104_000_100;
    private static final long SECOND = 1000L;

    private final PopulationConfig config = new PopulationConfig();
    private final Population population = mock(Population.class);
    private final PersonMemories memories = mock(PersonMemories.class);
    private final MapleMap map = mock(MapleMap.class);
    private final Character human = mock(Character.class);
    private MockedStatic<AgentPresence> presences;
    private KillSteals steals;

    @BeforeEach
    void citizenFirstThenTheHuman() {
        config.KS_MEMORY_ENABLED = true;
        when(population.config()).thenReturn(config);
        when(population.memories()).thenReturn(memories);
        when(population.isCitizen(CITIZEN)).thenReturn(true);
        when(map.getId()).thenReturn(FIELD);
        when(map.getCharacterById(HUMAN)).thenReturn(human);
        presences = mockStatic(AgentPresence.class);
        presences.when(() -> AgentPresence.isAgent(human)).thenReturn(false);
        KillSteals.arrived(CITIZEN, FIELD, 1_000L);
        KillSteals.arrived(HUMAN, FIELD, 2_000L);
        steals = new KillSteals(population);
    }

    @AfterEach
    void close() {
        presences.close();
    }

    private static Monster mob(int killer, long citizenDamage, long humanDamage) {
        Monster monster = mock(Monster.class);
        when(monster.killerId()).thenReturn(killer);
        when(monster.damageBy(CITIZEN)).thenReturn(citizenDamage);
        when(monster.damageBy(HUMAN)).thenReturn(humanDamage);
        return monster;
    }

    private void kill(int killer, long citizenDamage, long humanDamage) {
        steals.killed(mob(killer, citizenDamage, humanDamage), Set.of(CITIZEN, HUMAN), map);
    }

    private void verifyKs(int times) {
        verify(memories, times(times)).record(eq(CITIZEN), eq(HUMAN), eq(PersonMemory.Kind.KS), eq(FIELD), anyString(), anyInt());
    }

    @Test
    void eightStealsInsideTheWindowMakeOneEpisodeAndSevenNone() {
        for (int i = 0; i < 7; i++) {
            kill(HUMAN, 700, 300);
        }
        verifyKs(0);
        kill(HUMAN, 700, 300);
        verifyKs(1);
        for (int i = 0; i < 7; i++) {
            kill(HUMAN, 700, 300);
        }
        verifyKs(1); // the count starts again after an episode
    }

    @Test
    void stealsSpreadPastTheWindowAreNothing() {
        long window = config.KS_WINDOW_MS;
        for (int i = 0; i < 20; i++) {
            steals.stolen(HUMAN, CITIZEN, FIELD, i * (window / 7 + SECOND), config);
        }
        verifyKs(0);
    }

    @Test
    void aFairShareAKillOfItsOwnOrArrivingFirstIsNoSteal() {
        for (int i = 0; i < 20; i++) {
            kill(HUMAN, 590, 410); // under 60 %
            kill(CITIZEN, 700, 300); // its own kill
            steals.killed(mob(HUMAN, 700, 0), Set.of(HUMAN), map); // one attacker: not read at all
        }
        KillSteals.arrived(HUMAN, FIELD, 500L); // the human was there first
        for (int i = 0; i < 20; i++) {
            kill(HUMAN, 900, 100);
        }
        verifyKs(0);
        assertTrue(steals.report().contains("humanSteals=0"), steals.report());
    }

    @Test
    void thirtyMinutesOfOrdinarySideBySideTrainingLeaveNoKsRow() {
        // each trains its own mobs (one attacker each: never read); now and then both hit one mob (an AoE, a mob
        // walking between them): about one shared mob a minute, the damage split at random, either landing the kill
        Random random = new Random(20261122L);
        long now = 0L;
        int shared = 0;
        int stealsCounted = 0;
        for (long t = 0; t < 30 * 60 * SECOND; t += 6 * SECOND) {
            steals.killed(mob(CITIZEN, 1000, 0), Set.of(CITIZEN), map, t);
            steals.killed(mob(HUMAN, 0, 1000), Set.of(HUMAN), map, t);
            if (random.nextInt(10) == 0) {
                shared++;
                long citizenPart = 100 + random.nextInt(800);
                int killer = random.nextBoolean() ? HUMAN : CITIZEN;
                if (killer == HUMAN && citizenPart >= 600) {
                    stealsCounted++;
                }
                Monster monster = mob(killer, citizenPart, 1000 - citizenPart);
                steals.killed(monster, Set.of(CITIZEN, HUMAN), map, t);
            }
            now = t;
        }
        assertTrue(shared >= 20, "the run had overlap to judge: " + shared);
        assertTrue(stealsCounted > 0, "and some of it looked like a steal: " + stealsCounted);
        verifyKs(0);
        verify(memories, never()).record(anyInt(), anyInt(), eq(PersonMemory.Kind.KS), anyInt(), anyString(), anyInt());
        assertEquals(29 * 60 * SECOND + 54 * SECOND, now);
    }

    @Test
    void aCitizenTakingAHumansKillIsCountedNeverRemembered() {
        KillSteals.arrived(HUMAN, FIELD, 500L);
        for (int i = 0; i < 10; i++) {
            kill(CITIZEN, 100, 900);
        }
        assertEquals(10, steals.citizenSteals());
        verify(memories, never()).record(anyInt(), anyInt(), any(), anyInt(), anyString(), anyInt());
    }

    @Test
    void offNothingIsRead() {
        config.KS_MEMORY_ENABLED = false;
        Monster monster = mob(HUMAN, 900, 100);
        for (int i = 0; i < 20; i++) {
            steals.killed(monster, Set.of(CITIZEN, HUMAN), map);
        }
        verify(monster, never()).damageBy(anyInt());
        verifyKs(0);
    }

    @Test
    void theEpisodeIsAGrudgeAndAColdGreeting() {
        long now = System.currentTimeMillis();
        PersonMemory memory = PersonMemory.none().with(PersonMemory.Kind.KS, FIELD, "", 1, now, config);
        assertTrue(memory.feltWarmth(now, config) <= PersonMemory.COLD, "a reserved or easygoing citizen refuses the invite");
        assertEquals("memory.greet.cool", memory.greeting(now, config).trigger());
        assertEquals("ks", Hearsay.deed(memory.episodes().get(0)));
        assertTrue(!Hearsay.good(PersonMemory.Kind.KS));
    }
}
