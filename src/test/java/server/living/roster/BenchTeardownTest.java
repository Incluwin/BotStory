package server.living.roster;

import client.Character;
import client.Client;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.townlife.AgentTownLifeCheckpointRuntime;
import server.agents.runtime.AgentMailboxRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.activity.AgentActivityBootstrap;
import server.agents.runtime.field.AgentFieldCheckpointRuntime;
import server.agents.runtime.field.AgentFieldVisitLeaseRuntime;
import server.agents.runtime.townlife.AgentTownLifeVisitLeaseRuntime;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static server.living.roster.BenchHarness.ANN;
import static server.living.roster.BenchHarness.TOWN;

/**
 * A slice's end is exception-safe (docs/BOT_MANAGEMENT_PLAN.md P5 "Living bench", "CompanionLeases +
 * AgentAvailabilityGuard"): each tidy-up step runs on its own, a companion still online after a failed unload keeps its
 * lease and its pool slot (nothing may load a second copy of it) and is unloaded again next tick, one slice failing
 * never stops the others, a form that began and then failed is finished and handed back like any end, and the
 * hand-back runs on the Agent's own mailbox (a mailbox that never runs it still leaves no checkpoint to restore).
 */
class BenchTeardownTest {
    private final BenchHarness game = new BenchHarness();

    @AfterEach
    void close() {
        game.close();
    }

    @Test
    void anUnloadThatFailsKeepsTheLeaseAndTheSlotAndIsTriedAgainEachTick() {
        game.companion(ANN, "olwen", "Olwen", 901, Assignment.TRAIN);
        game.duty.tick();
        Client client = game.bots.get(901).getClient();
        doThrow(new IllegalStateException("socket already closing")).when(client).forceDisconnect();

        game.duty.endFor("olwen", "called by owner");

        assertEquals(new CompanionLeases.Lease(CompanionLeases.Kind.BENCH_LIVE, ANN), game.leases.holder(901),
                "still online: its lease stays, so no second copy of it can load");
        assertTrue(game.duty.live("olwen"), "and it keeps its pool slot");
        assertEquals(1, game.duty.liveCountFor(ANN, CompanionBudget.Kind.BENCH_LIVE));
        assertEquals(List.of(), game.store.events("bench.live-end"));

        game.duty.tick(); // still failing: tried again, and never loaded a second time meanwhile
        assertTrue(game.duty.live("olwen"));
        assertEquals(1, game.calls.stream().filter(call -> call.equals("load Olwen at " + BenchHarness.FIELD + " ch1")).count(),
                game.calls.toString());

        doNothing().when(client).forceDisconnect();
        game.store.seed(game.row(ANN, "olwen").with(RosterState.ACTIVE, game.now)); // its owner's call goes on
        game.duty.tick();

        assertFalse(game.duty.live("olwen"));
        assertNull(game.leases.holder(901));
        assertEquals(List.of(ANN + " olwen bench.live-end called by owner"), game.store.events("bench.live-end"),
                "one end, once it really unloaded");
    }

    @Test
    void aTidyUpStepThatThrowsSkipsNoneOfTheOthers() {
        game.companion(ANN, "fenn", "Fenn", 906, Assignment.REST);
        game.duty.register(Assignment.REST, new Throwing(true));
        game.duty.tick();
        assertTrue(game.duty.live("fenn"));

        game.duty.endFor("fenn", "called by owner");

        assertTrue(game.calls.containsAll(List.of("hand back Fenn", "unload Fenn")), game.calls.toString());
        assertFalse(game.duty.live("fenn"));
        assertNull(game.leases.holder(906), "its lease is released however its form's tidy-up went");
        assertEquals(List.of(ANN + " fenn bench.live-end called by owner"), game.store.events("bench.live-end"));
    }

    @Test
    void oneSliceFailingToUnloadNeverStopsTheOthersAtShutdown() {
        game.companion(ANN, "olwen", "Olwen", 901, Assignment.TRAIN);
        game.companion(ANN, "brin", "Brin", 902, Assignment.TRAIN);
        game.duty.tick();
        Client olwens = game.bots.get(901).getClient();
        doThrow(new IllegalStateException("socket")).when(olwens).forceDisconnect();

        game.duty.endAll("server shutdown");

        assertTrue(game.calls.containsAll(List.of("unload Olwen", "unload Brin")), game.calls.toString());
        assertFalse(game.duty.live("brin"), "the other one still ended");
        assertNull(game.leases.holder(902));
        assertTrue(game.duty.live("olwen"), "the one still online is kept for the next try");
    }

    @Test
    void aFormThatBeganAndThenFailedIsFinishedAndHandedBackBeforeItUnloads() {
        game.companion(ANN, "fenn", "Fenn", 906, Assignment.REST);
        game.duty.register(Assignment.REST, game.new FakeForm(CompanionBudget.Kind.BENCH_TOWN, TOWN));
        CompanionSettingsApplier settings = mock(CompanionSettingsApplier.class);
        doThrow(new IllegalStateException("settings row unreadable")).when(settings).bind(any(), anyInt(), anyString());
        org.mockito.Mockito.when(game.roster.settings()).thenReturn(settings);

        game.duty.tick();

        assertFalse(game.duty.live("fenn"));
        assertNull(game.leases.holder(906));
        List<String> order = List.of("begin Fenn", "finish fenn: begin failed", "hand back Fenn", "unload Fenn");
        assertTrue(game.calls.containsAll(order), game.calls.toString());
        for (int i = 1; i < order.size(); i++) {
            assertTrue(game.calls.indexOf(order.get(i - 1)) < game.calls.indexOf(order.get(i)), game.calls.toString());
        }
    }

    @Test
    void theHandBackRunsOnTheAgentsOwnMailbox() {
        game.companion(ANN, "olwen", "Olwen", 901, Assignment.TRAIN);
        game.duty.tick();
        AgentRuntimeEntry entry = game.entry(901);

        game.duty.endFor("olwen", "called by owner");

        game.statics(AgentMailboxRuntime.class).verify(() -> AgentMailboxRuntime.dispatch(eq(entry), any()));
        game.statics(AgentActivityBootstrap.class).verify(() -> AgentActivityBootstrap.requestPrimaryStop(eq(entry), any(), any(),
                anyString(), anyLong()), org.mockito.Mockito.atLeastOnce());
        assertTrue(game.calls.indexOf("hand back Olwen") < game.calls.indexOf("unload Olwen"), game.calls.toString());
    }

    @Test
    void aMailboxThatNeverRunsTheHandBackStillLeavesNoCheckpointToRestore() {
        game.companion(ANN, "olwen", "Olwen", 901, Assignment.TRAIN);
        game.duty.tick();
        Character olwen = game.bots.get(901);
        game.mailboxRuns = false; // closed as it is torn down: its tick never runs the hand-back

        game.duty.endFor("olwen", "called by owner");

        game.statics(AgentActivityBootstrap.class).verify(() -> AgentActivityBootstrap.requestPrimaryStop(any(), any(), any(),
                anyString(), anyLong()), never()); // never inline, from companion-ops
        // once it is out of the registry nothing ticks it: its checkpoints are cleared then
        assertTrue(game.calls.indexOf("unload Olwen") < game.calls.indexOf("hand back Olwen"), game.calls.toString());
        game.statics(AgentFieldVisitLeaseRuntime.class).verify(() -> AgentFieldVisitLeaseRuntime.clear(isNull(), eq(olwen)));
        game.statics(AgentTownLifeVisitLeaseRuntime.class).verify(() -> AgentTownLifeVisitLeaseRuntime.clear(isNull(), eq(olwen)));
        // the visits themselves too, not only their leases
        game.statics(AgentFieldCheckpointRuntime.class).verify(() -> AgentFieldCheckpointRuntime.delete(901));
        game.statics(AgentTownLifeCheckpointRuntime.class).verify(() -> AgentTownLifeCheckpointRuntime.delete(901));
        assertNull(game.leases.holder(901));
    }

    @Test
    void aRestSliceWhoseHandBackNeverRanLeavesNoTownVisitForTheCallsLoad() {
        // found live 2026-10-13: Garrick rested unwatched (abstract) in Perion, his owner called, the hand-back missed its
        // wait, the unload cleared only the visit's lease, and the call's load where he stood restored the bench TownLife
        // visit: abstract in the empty town on his way, and frozen beside his owner once he arrived
        game.companion(ANN, "fenn", "Fenn", 906, Assignment.REST);
        game.duty.register(Assignment.REST, game.new FakeForm(CompanionBudget.Kind.BENCH_TOWN, TOWN));
        game.duty.tick();
        game.statics(AgentTownLifeCheckpointRuntime.class).when(() -> AgentTownLifeCheckpointRuntime.delete(anyInt()))
                .thenAnswer(inv -> game.calls.add("forget town visit " + inv.getArgument(0)));
        game.mailboxRuns = false;

        game.duty.endFor("fenn", "called by owner");

        assertTrue(game.calls.contains("forget town visit 906"), game.calls.toString());
        assertTrue(game.calls.indexOf("unload Fenn") < game.calls.indexOf("forget town visit 906"),
                "once it is out of the registry, so no tick of its writes the visit again: " + game.calls);
        game.statics(AgentFieldCheckpointRuntime.class).verify(() -> AgentFieldCheckpointRuntime.delete(906));
        assertNull(game.leases.holder(906));
    }

    @Test
    void aSliceHandedBackStillLeavesNoVisitToRestore() {
        // its hunt is only asked to stop at its boundary: still draining when it unloads, and nothing ticks it after
        game.companion(ANN, "olwen", "Olwen", 901, Assignment.TRAIN);
        game.duty.tick();

        game.duty.endFor("olwen", "called by owner");

        game.statics(AgentFieldCheckpointRuntime.class).verify(() -> AgentFieldCheckpointRuntime.delete(901));
        game.statics(AgentTownLifeCheckpointRuntime.class).verify(() -> AgentTownLifeCheckpointRuntime.delete(901));
    }

    /** A town form whose begin works and whose finish throws (when {@code failFinish}). */
    private final class Throwing implements BenchDutyDispatcher.Form {
        private final boolean failFinish;

        Throwing(boolean failFinish) {
            this.failFinish = failFinish;
        }

        @Override
        public CompanionBudget.Kind pool() {
            return CompanionBudget.Kind.BENCH_TOWN;
        }

        @Override
        public int mapFor(RosterEntry entry, Persona persona, int characterId) {
            return TOWN;
        }

        @Override
        public boolean begin(BenchDutyDispatcher.Slice slice, Character agent, long nowMs) {
            return true;
        }

        @Override
        public String check(BenchDutyDispatcher.Slice slice, Character agent, RosterEntry entry, long nowMs) {
            return null;
        }

        @Override
        public void finish(BenchDutyDispatcher.Slice slice, Character agent, String reason) {
            if (failFinish) {
                throw new IllegalStateException("its last harvest failed");
            }
        }
    }
}
