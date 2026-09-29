package server.living.roster;

import config.LivingWorldConfig;
import org.junit.jupiter.api.Test;
import server.living.LivingWorld;
import server.living.config.Content.BondStage;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.Assignment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The duty picker's projected hour (docs/BOT_MANAGEMENT_PLAN.md flow 2): the same priors the bench ledger credits with,
 * so TRAIN projects nothing once the companion keeps pace with its owner and catches up faster far behind, and resting
 * costs no upkeep.
 */
class BenchLedgerRatesTest {
    private final LivingWorld world = mock(LivingWorld.class);
    private final Persona persona = mock(Persona.class);
    private final BenchLedger ledger = new BenchLedger(world);

    BenchLedgerRatesTest() {
        when(world.config()).thenReturn(new LivingWorldConfig()); // efficiency 0.6, pace gap 3, max lag 8, catch-up x3
        when(persona.paceGap()).thenReturn(-1);
        when(persona.maxLag()).thenReturn(-1);
    }

    @Test
    void trainingProjectsAShareOfTheLevelAnHourAtThePriors() {
        // lv20: a level per 1.5 h at 0.6 efficiency is 40% an hour; 5 behind a lv25 owner is within the max lag
        BenchLedger.Rates rates = ledger.rates(persona, BondStage.MET, 20, 25, Assignment.TRAIN);

        assertEquals(40.0d, rates.expPercent(), 1e-9);
        assertEquals(600L, rates.upkeep());
        assertEquals(0L, rates.meso());
    }

    @Test
    void farBehindItsOwnerItCatchesUpFaster() {
        // 10 behind is 2 past the max lag: 1 + 2 x 0.5 = twice as fast
        assertEquals(80.0d, ledger.rates(persona, BondStage.MET, 20, 30, Assignment.TRAIN).expPercent(), 1e-9);
    }

    @Test
    void keepingPaceWithItsOwnerItTrainsNoFurther() {
        // MET: the pace gap of 3 holds it at lv27 for a lv30 owner; SWORN shrinks the gap to 0
        assertEquals(0.0d, ledger.rates(persona, BondStage.MET, 27, 30, Assignment.TRAIN).expPercent());
        assertEquals(0.0d, ledger.rates(persona, null, 27, 30, Assignment.TRAIN).expPercent(), "no bond reads as MET");
        assertEquals(0.0d, ledger.rates(persona, BondStage.SWORN, 30, 30, Assignment.TRAIN).expPercent());
    }

    @Test
    void restingCostsNoUpkeep() {
        BenchLedger.Rates rest = ledger.rates(persona, BondStage.MET, 20, 25, Assignment.REST);

        assertEquals(0L, rest.upkeep());
        assertEquals(0.0d, rest.expPercent());
    }
}
