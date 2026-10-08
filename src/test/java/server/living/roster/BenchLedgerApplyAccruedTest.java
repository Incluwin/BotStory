package server.living.roster;

import client.Character;
import config.LivingWorldConfig;
import org.junit.jupiter.api.Test;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.resident.ResidentRuntime;
import server.living.store.LivingStore;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BenchLedger.applyAccrued is the step every companion load runs (a roster call, a trip set off, a swap, a bench
 * slice): after its banked EXP the companion is prepared as a persona (ResidentRuntime.prepareCompanion), which shapes
 * one never shaped (a granted persona: Flint, live 2026-10-07) before its quest gear.
 */
class BenchLedgerApplyAccruedTest {
    private final LivingWorld world = mock(LivingWorld.class);
    private final LivingContent content = mock(LivingContent.class);
    private final ResidentRuntime residents = mock(ResidentRuntime.class);
    private final Persona flint = mock(Persona.class);
    private final Character bot = mock(Character.class);
    private final BenchLedger ledger = new BenchLedger(world);

    BenchLedgerApplyAccruedTest() {
        when(world.config()).thenReturn(new LivingWorldConfig());
        when(world.store()).thenReturn(mock(LivingStore.class));
        when(world.content()).thenReturn(content);
        when(world.residents()).thenReturn(residents);
        when(content.persona("flint")).thenReturn(flint);
    }

    @Test
    void aLoadedCompanionIsPreparedAsItsPersona() {
        ledger.applyAccrued(benched(), bot);

        verify(residents).prepareCompanion(bot, flint, true); // loading: shaped here when nothing ticks it yet
    }

    @Test
    void nothingIsPreparedWithoutACharacterOrPersona() {
        ledger.applyAccrued(benched(), null);
        when(content.persona("flint")).thenReturn(null);
        ledger.applyAccrued(benched(), bot);

        verify(residents, never()).prepareCompanion(any(), any(), anyBoolean());
    }

    private static RosterEntry benched() {
        return new RosterEntry(1008, "flint", RosterState.BENCHED, Assignment.TRAIN, 0, 0, 103000200, 0L, 0L, 0L, 0L, 0L,
                0L, 0, null);
    }
}
