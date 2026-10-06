package server.living.population;

import client.Character;
import client.Job;
import config.PopulationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.living.LivingWorld;
import server.living.config.LivingContent;
import server.living.population.PopulationContent.Career;
import server.living.resident.ResidentShaper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/**
 * The catch-up when a citizen loads (2026-10-06): the first and second job come here as before; the third and fourth
 * only off-screen for a backlog citizen (or with job errands off), else they are errands. With nothing to level or
 * advance it still repairs the growth and skills of levels the ledger wrote alone. At rollout every citizen already
 * old enough for its next job is flagged once.
 */
class ProgressionLedgerJobTest {
    private static final int ID = 700;

    private final PopulationConfig config = new PopulationConfig();
    private final Population population = mock(Population.class);
    private final CitizenStore store = mock(CitizenStore.class);
    private final Map<Integer, Citizen> registry = new LinkedHashMap<>();
    private final List<Citizen> saved = new ArrayList<>();
    private MockedStatic<ResidentShaper> shaper;
    private MockedStatic<CitizenGear> gear;
    private Job held;
    private Job afterShaping;

    @BeforeEach
    void wire() {
        PopulationContent content = mock(PopulationContent.class);
        when(content.career("warrior-fighter")).thenReturn(new Career("warrior-fighter", "FIGHTER", 1, 75, List.of()));
        LivingWorld world = mock(LivingWorld.class);
        when(world.content()).thenReturn(mock(LivingContent.class));
        when(population.config()).thenReturn(config);
        when(population.content()).thenReturn(content);
        when(population.store()).thenReturn(store);
        when(population.world()).thenReturn(world);
        when(population.citizens()).thenAnswer(inv -> List.copyOf(registry.values()));
        when(population.citizen(anyInt())).thenAnswer(inv -> registry.get((int) inv.getArgument(0)));
        when(population.update(anyInt(), any())).thenAnswer(inv -> {
            Citizen current = registry.get((int) inv.getArgument(0));
            Citizen next = current == null ? null : inv.<UnaryOperator<Citizen>>getArgument(1).apply(current);
            if (next != null && next != current) {
                registry.put(next.characterId(), next);
                saved.add(next);
            }
            return next == null ? current : next;
        });
        shaper = mockStatic(ResidentShaper.class);
        shaper.when(() -> ResidentShaper.job(anyString())).thenCallRealMethod();
        shaper.when(() -> ResidentShaper.shapeTo(any(), any(), anyInt(), any())).thenAnswer(inv -> {
            held = afterShaping; // what the real shaping's advanceIfLegal would leave it at
            return null;
        });
        gear = mockStatic(CitizenGear.class);
    }

    @AfterEach
    void close() {
        shaper.close();
        gear.close();
    }

    @Test
    void aBacklogCitizenTakesItsOverdueThirdJobOffScreenOnceAndTheFlagIsGone() {
        Citizen citizen = put(Set.of(Citizen.FLAG_JOB_BACKLOG));
        Character agent = agent(Job.FIGHTER, 75);
        afterShaping = Job.CRUSADER;

        Job advanced = new ProgressionLedger(population).catchUp(agent, citizen);

        shaper.verify(() -> ResidentShaper.shapeTo(eq(agent), eq(Job.HERO), eq(75), isNull()));
        assertEquals(Job.CRUSADER, advanced, "the off-screen third job is reported for its login line");
        assertFalse(registry.get(ID).has(Citizen.FLAG_JOB_BACKLOG));
    }

    @Test
    void aCitizenWhoReachedItsThirdJobLaterIsNotReshapedButRepairedItGoesOnAnErrand() {
        Citizen citizen = put(Set.of());
        Character agent = agent(Job.FIGHTER, 75);

        Job advanced = new ProgressionLedger(population).catchUp(agent, citizen);

        shaper.verify(() -> ResidentShaper.shapeTo(any(), any(), anyInt(), any()), never());
        shaper.verify(() -> ResidentShaper.repair(eq(agent), isNull()));
        assertNull(advanced);
        assertEquals(Job.FIGHTER, held);
        assertTrue(saved.isEmpty(), "no flag change for a citizen that was never a backlog one");
    }

    @Test
    void aThirdJobCitizenKeepsItsJobAndIsOnlyRepaired() {
        Citizen citizen = put(Set.of());
        Character agent = agent(Job.CRUSADER, 85);

        assertNull(new ProgressionLedger(population).catchUp(agent, citizen));

        shaper.verify(() -> ResidentShaper.shapeTo(any(), any(), anyInt(), any()), never());
        shaper.verify(() -> ResidentShaper.repair(eq(agent), isNull()));
    }

    @Test
    void theSecondJobStillComesWithTheCatchUp() {
        Citizen citizen = put(Set.of());
        Character agent = agent(Job.WARRIOR, 35);
        afterShaping = Job.FIGHTER;

        assertNull(new ProgressionLedger(population).catchUp(agent, citizen));

        shaper.verify(() -> ResidentShaper.shapeTo(eq(agent), eq(Job.FIGHTER), eq(35), isNull()));
        assertEquals(Job.FIGHTER, held);
    }

    @Test
    void withJobErrandsOffTheThirdJobComesOffScreen() {
        config.JOB_ERRANDS_ENABLED = false;
        Citizen citizen = put(Set.of());
        Character agent = agent(Job.FIGHTER, 72);
        afterShaping = Job.CRUSADER;

        assertEquals(Job.CRUSADER, new ProgressionLedger(population).catchUp(agent, citizen));

        shaper.verify(() -> ResidentShaper.shapeTo(eq(agent), eq(Job.HERO), eq(72), isNull()));
    }

    @Test
    void theRolloutFlagsEveryActiveCitizenAlreadyOldEnoughForItsNextJob() {
        put(701, Set.of(), Citizen.State.ACTIVE);       // fighter lv72: overdue
        put(702, Set.of(), Citizen.State.ACTIVE);       // warrior lv71: overdue (its catch-up brings fighter first)
        put(703, Set.of(), Citizen.State.ACTIVE);       // fighter lv69: not yet
        put(704, Set.of(), Citizen.State.ACTIVE);       // crusader lv80: has it
        put(705, Set.of(Citizen.FLAG_HIRED), Citizen.State.ACTIVE); // a hire: its owner's
        put(706, Set.of(), Citizen.State.RETIRED);      // retired
        Map<Integer, int[]> levelsAndJobs = Map.of(701, new int[]{72, 110}, 702, new int[]{71, 100}, 703, new int[]{69, 110},
                704, new int[]{80, 111}, 705, new int[]{90, 110}, 706, new int[]{90, 110});
        when(store.levelsAndJobs()).thenReturn(levelsAndJobs);

        int marked = new ProgressionLedger(population).markJobBacklog();

        assertEquals(2, marked);
        assertTrue(registry.get(701).has(Citizen.FLAG_JOB_BACKLOG));
        assertTrue(registry.get(702).has(Citizen.FLAG_JOB_BACKLOG));
        for (int id : new int[]{703, 704, 705, 706}) {
            assertFalse(registry.get(id).has(Citizen.FLAG_JOB_BACKLOG), "citizen " + id);
        }
    }

    private Character agent(Job job, int level) {
        held = job;
        Character agent = mock(Character.class);
        when(agent.getLevel()).thenReturn(level);
        when(agent.getJob()).thenAnswer(inv -> held);
        when(agent.getMeso()).thenReturn(Integer.MAX_VALUE / 2);
        when(agent.getName()).thenReturn("Tobi");
        return agent;
    }

    private Citizen put(Set<String> flags) {
        return put(ID, flags, Citizen.State.ACTIVE);
    }

    private Citizen put(int id, Set<String> flags, Citizen.State state) {
        long now = System.currentTimeMillis(); // born today: the life-curve target is the level it has
        Citizen citizen = new Citizen(id, "Tobi" + id, "evening", 0, 0, "victoria", "warrior-fighter", "relaxed-v1", "default",
                1.0d, 0, 1, new LinkedHashSet<>(flags), state, now, 0L, 0L, 0L, 0L, 0, "");
        registry.put(id, citizen);
        return citizen;
    }
}
