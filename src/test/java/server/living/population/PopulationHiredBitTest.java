package server.living.population;

import config.LivingWorldConfig;
import config.PopulationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import server.living.LivingWorld;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

/**
 * The hired bit has one writer (docs/BOT_MANAGEMENT_PLAN.md P4, "Hired hand"): Population.setHired sets and clears it
 * on the current record, and no ordinary save flips it, not even one of a copy the ledger, the social fabric or churn
 * took before the hire (or the release); Population.update applies a change to the current record, never to a copy.
 * The table gets what the registry holds.
 */
class PopulationHiredBitTest {
    private static final int MIRA = 950;

    private final List<Citizen> written = new ArrayList<>();
    private MockedConstruction<CitizenStore> stores;
    private Population population;

    @BeforeEach
    void build() throws Exception {
        stores = mockConstruction(CitizenStore.class, (store, context) ->
                doAnswer(inv -> written.add(inv.getArgument(0))).when(store).save(any()));
        LivingWorld world = mock(LivingWorld.class);
        when(world.config()).thenReturn(new LivingWorldConfig());
        PopulationContent content = mock(PopulationContent.class);
        when(content.names()).thenReturn(new PopulationContent.Names(List.of(), Map.of(), List.of(), List.of(), List.of()));
        Constructor<Population> constructor = Population.class.getDeclaredConstructor(LivingWorld.class,
                PopulationConfig.class, PopulationContent.class);
        constructor.setAccessible(true);
        population = constructor.newInstance(world, new PopulationConfig(), content);
        population.save(citizen());
        written.clear();
    }

    @AfterEach
    void close() {
        stores.close();
    }

    @Test
    void aSaveOfACopyTakenBeforeTheHireKeepsTheHiredBit() {
        Citizen before = population.citizen(MIRA); // the ledger's list, made just before the hire

        population.setHired(MIRA, true); // the hand-over, on companion-ops
        population.save(before.withLedger(5_000L, 0L, 30)); // the ledger's late write of its copy

        Citizen now = population.citizen(MIRA);
        assertTrue(now.hired(), "the flag survives the stale write");
        assertEquals(5_000L, now.lastLedgerAtMs(), "the write itself lands");
        assertTrue(written.get(written.size() - 1).hired(), "and the table gets the flag too");
    }

    @Test
    void aSaveOfACopyTakenBeforeTheReleaseNeverFlagsItAgain() {
        population.setHired(MIRA, true);
        Citizen whileHired = population.citizen(MIRA);

        population.setHired(MIRA, false); // let go
        population.save(whileHired.withCircle(3));

        assertFalse(population.citizen(MIRA).hired(), "back in the population, for good");
        assertEquals(3, population.citizen(MIRA).circleId());
    }

    @Test
    void anUpdateAppliesToTheCurrentRecordAndKeepsTheBit() {
        population.setHired(MIRA, true);

        Citizen updated = population.update(MIRA, current -> current.withoutFlag(Citizen.FLAG_HIRED).withGuild(7));

        assertTrue(updated.hired(), "only setHired writes the bit");
        assertEquals(7, updated.guildId());
        assertSame(updated, population.citizen(MIRA));
        written.clear();
        assertSame(updated, population.update(MIRA, current -> current), "no change, no write");
        assertTrue(written.isEmpty());
        assertNull(population.update(4242, current -> current.withGuild(1)), "nobody to update");
    }

    @Test
    void setHiredWritesOnlyAChange() {
        population.setHired(MIRA, true);
        population.setHired(MIRA, true);

        assertEquals(1, written.size());
        assertNull(population.setHired(4242, true));
    }

    private static Citizen citizen() {
        return new Citizen(MIRA, "Mira", "evening", 0, 0, "victoria", "hunter", "relaxed-v1", "default", 1.0d, 0, 1,
                new LinkedHashSet<>(), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
    }
}
