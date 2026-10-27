package server.living.standing;

import config.LivingWorldConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.living.LivingWorld;
import server.living.bond.Friendship;
import server.living.population.Citizen;
import server.living.population.PersonMemories;
import server.living.population.PersonMemory;
import server.living.population.Population;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The hire side of standing (docs/BOT_CONCEPT_PLAN.md C1.3-C1.4): a FRIEND's fee discount, the Lv 7 wage discount, Lv 10
 * permanence, the warmth a hire let go keeps, and a hired citizen remembering its runs; none of it with the switch off.
 */
class HireStandingTest {
    private static final int ANN = 10;
    private static final int MIRA = 950;
    private static final String HIRE = "hire:950";

    private final LivingWorldConfig config = new LivingWorldConfig();
    private final LivingWorld world = mock(LivingWorld.class);
    private final Friendship friendship = mock(Friendship.class);
    private final Population population = mock(Population.class);
    private final PersonMemories memories = mock(PersonMemories.class);
    private MockedStatic<Population> populations;
    private MockedStatic<LivingWorld> worlds;

    @BeforeEach
    void wire() {
        config.HIRE_FRIENDSHIP_ENABLED = true;
        when(world.config()).thenReturn(config);
        when(world.friendship()).thenReturn(friendship);
        when(population.memories()).thenReturn(memories);
        populations = mockStatic(Population.class);
        populations.when(Population::get).thenReturn(population);
        worlds = mockStatic(LivingWorld.class);
        worlds.when(LivingWorld::get).thenReturn(world);
    }

    @AfterEach
    void close() {
        worlds.close();
        populations.close();
    }

    @Test
    void aFriendAsksLessAndNeverNothing() {
        assertEquals(22_500L, HireStanding.fee(world, 30_000L, 40), "25% off for a FRIEND");
        assertEquals(30_000L, HireStanding.fee(world, 30_000L, 39), "a REGULAR pays the full fee");
        config.HIRE_FRIEND_FEE_DISCOUNT_PERCENT = 90;
        assertEquals(3_000L, HireStanding.fee(world, 30_000L, 80), "the most it takes off is 90%");
        config.HIRE_FRIENDSHIP_ENABLED = false;
        assertEquals(30_000L, HireStanding.fee(world, 30_000L, 80));
    }

    @Test
    void aCloseFriendWorksForLess() {
        when(friendship.level(ANN, HIRE)).thenReturn(7);
        assertEquals(0.75d, HireStanding.wageFactor(world, ANN, HIRE));
        when(friendship.level(ANN, HIRE)).thenReturn(6);
        assertEquals(1.0d, HireStanding.wageFactor(world, ANN, HIRE));
        when(friendship.level(ANN, "olwen")).thenReturn(9);
        assertEquals(1.0d, HireStanding.wageFactor(world, ANN, "olwen"), "a Resident's wages are its own");
        when(friendship.level(ANN, HIRE)).thenReturn(10);
        config.HIRE_FRIENDSHIP_ENABLED = false;
        assertEquals(1.0d, HireStanding.wageFactor(world, ANN, HIRE));
    }

    @Test
    void aBestFriendIsPermanent() {
        when(friendship.level(ANN, HIRE)).thenReturn(10);
        assertTrue(HireStanding.permanent(world, ANN, HIRE));
        when(friendship.level(ANN, HIRE)).thenReturn(9);
        assertFalse(HireStanding.permanent(world, ANN, HIRE));
        when(friendship.level(ANN, "olwen")).thenReturn(10);
        assertFalse(HireStanding.permanent(world, ANN, "olwen"), "a Resident goes home by decision 18 as before");
        when(friendship.level(ANN, HIRE)).thenReturn(10);
        config.HIRE_FRIENDSHIP_ENABLED = false;
        assertFalse(HireStanding.permanent(world, ANN, HIRE));
    }

    @Test
    void aReleaseSetsWarmthFromTheFriendshipLevel() {
        when(friendship.level(ANN, HIRE)).thenReturn(4);
        HireStanding.released(world, ANN, HIRE);
        verify(memories).warmAtLeast(MIRA, ANN, 40);

        when(friendship.level(ANN, HIRE)).thenReturn(7);
        HireStanding.released(world, ANN, HIRE);
        verify(memories).warmAtLeast(MIRA, ANN, 60);
    }

    @Test
    void withTheSwitchOffAReleaseLeavesTheMemoryAlone() {
        config.HIRE_FRIENDSHIP_ENABLED = false;
        when(friendship.level(ANN, HIRE)).thenReturn(4);

        HireStanding.released(world, ANN, HIRE);

        verify(memories, never()).warmAtLeast(anyInt(), anyInt(), anyInt());
    }

    @Test
    void aHiredCitizenRemembersWhatItDidWithItsOwner() {
        Citizen hired = mock(Citizen.class);
        when(hired.hired()).thenReturn(true);
        when(population.citizen(MIRA)).thenReturn(hired);

        assertTrue(HireStanding.remember(MIRA, ANN, PersonMemory.Kind.PQ_CLEAR, 0, "kpq"));
        verify(memories).record(MIRA, ANN, PersonMemory.Kind.PQ_CLEAR, 0, "kpq", 1);

        when(hired.hired()).thenReturn(false);
        assertFalse(HireStanding.remember(MIRA, ANN, PersonMemory.Kind.FAMED, 0, ""), "a citizen with a session has its own hooks");
        when(population.citizen(4242)).thenReturn(null);
        assertFalse(HireStanding.remember(4242, ANN, PersonMemory.Kind.FAMED, 0, ""), "no citizen: a Resident");
        when(hired.hired()).thenReturn(true);
        config.HIRE_FRIENDSHIP_ENABLED = false;
        assertFalse(HireStanding.remember(MIRA, ANN, PersonMemory.Kind.FAMED, 0, ""));
        verify(memories, never()).record(MIRA, ANN, PersonMemory.Kind.FAMED, 0, "", 1);
    }
}
