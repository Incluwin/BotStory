package server.living.roster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterState;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static server.living.roster.LifecycleHarness.KERNING;
import static server.living.roster.LifecycleHarness.LITH;

/**
 * A called companion comes back following its owner, never in a town or field visit a checkpoint kept from its bench
 * (a REST or field slice torn down without its hand-back, a restart mid-slice): its load restores such a visit where it
 * stands, so every companion load lets go of them first, once its live slice has ended. Found live 2026-10-13: Garrick,
 * called from a REST slice in Perion, came back in its bench TownLife visit, went abstract in the empty town on its way
 * and stood frozen beside its owner on arrival (outcome ledger never reconciled, mailbox never drained). A character
 * loaded for something else is refused as before and keeps its visits.
 */
class CallForgetsBenchVisitsTest {
    private static final int GARRICK = 835;

    private final LifecycleHarness game = new LifecycleHarness();
    private final Persona garrick = game.companion("garrick", "Garrick", GARRICK, RosterState.BENCHED, LITH);
    private BenchDutyDispatcher bench;

    @BeforeEach
    void annIsInKerningGarrickRestsInLith() {
        game.moveAnn(KERNING, 1500, 120);
        game.roster();
        bench = game.world.benchDuty();
    }

    @AfterEach
    void close() {
        game.close();
    }

    @Test
    void aCompanionCalledFromItsBenchLetsGoOfItsVisitsBeforeItLoads() {
        List<String> beforeLoad = new ArrayList<>();
        game.onSpawn = bot -> {
            InOrder order = inOrder(bench);
            order.verify(bench).endFor("garrick", "called by owner"); // its live slice ends (and unloads) first
            order.verify(bench).forgetVisits(GARRICK);
            beforeLoad.add(bot.getName());
        };

        assertTrue(game.roster().call(game.ann, garrick).ok());

        assertEquals(List.of("Garrick"), beforeLoad, "verified as it loaded, travelling from its camp");
        assertEquals(RosterState.TRAVELLING, game.row(garrick).state());
        game.runTimers();
        assertEquals(RosterState.ACTIVE, game.row(garrick).state());
    }

    @Test
    void aCharacterLoadedForSomethingElseIsRefusedAndKeepsItsVisits() {
        game.loaded.add(GARRICK); // a PQ lobby's, say
        game.registry.when(() -> AgentRuntimeRegistry.leaderIdForAgentCharacter(GARRICK)).thenReturn(4242);
        game.registry.when(() -> AgentRuntimeRegistry.hasActiveAgentCharacterId(GARRICK)).thenReturn(true);

        RosterResult result = game.roster().call(game.ann, garrick);

        assertEquals(RosterResult.Busy.class, result.getClass());
        verify(bench, never()).forgetVisits(anyInt());
    }
}
