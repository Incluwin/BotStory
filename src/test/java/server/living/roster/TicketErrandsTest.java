package server.living.roster;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.agents.runtime.AgentInteractionRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.life.NPC;
import server.living.config.Content.Persona;
import server.living.roster.TicketErrands.Progress;
import server.living.roster.TicketErrands.Step;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.LITH;
import static server.living.roster.TicketErrands.AMOS_THE_STRONG_NPC_ID;
import static server.living.roster.TicketErrands.PURPLEWOOD_FOREST_2_MAP_ID;
import static server.living.roster.TicketErrands.TRAINING_GROUND_MAP_ID;

/**
 * Companions earn their own Entrance Ticket to the Amorian Challenge (2026-10-25, docs/APQ_GPQ_COMPANION_PLAN.md A2):
 * asked by its owner, a companion hunts ten Lip Lock Keys in Purplewood Forest 2, live when it is loaded and on the
 * ledger at its own measured rate in between, trades them with Amos the Strong and comes back with the ticket.
 */
class TicketErrandsTest {
    private static final int THANE = 905;
    private static final Progress FRESH = new Progress(0, false);

    private final LifecycleHarness game = new LifecycleHarness();
    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final AtomicReference<Progress> bag = new AtomicReference<>(FRESH);
    private final List<String> acts = new ArrayList<>();
    private final List<Integer> credits = new ArrayList<>();
    private final List<Integer> hunts = new ArrayList<>();
    private TicketErrands errands;
    private Persona thane;

    @BeforeEach
    void thaneOutWithAnn() {
        thane = game.companionAs("FIGHTER", "thane", "Thane", THANE, RosterState.ACTIVE, LITH);
        game.outWithAnn(thane);
        game.roster();
        game.store.levels.put(THANE, 45);
        Character bot = game.bot(thane);
        when(bot.getHp()).thenReturn(2000);
        when(bot.getCurrentMaxHp()).thenReturn(2000);
        errands = new TicketErrands(game.world, clock::get);
        errands.goesAlong = (runtime, ownerId) -> true; // following Ann
        errands.progress = character -> bag.get();
        errands.hands = new TicketErrands.Hands() {
            @Override
            public boolean trade(AgentRuntimeEntry runtime) {
                acts.add("trade");
                Progress p = bag.get();
                if (p.keys() >= 10 && !p.ticket()) {
                    bag.set(new Progress(p.keys() - 10, true)); // as 9201043.js does it
                }
                return bag.get().ticket();
            }

            @Override
            public void credit(AgentRuntimeEntry runtime, int keys) {
                credits.add(keys);
                Progress p = bag.get();
                bag.set(new Progress(p.keys() + keys, p.ticket()));
            }

            @Override
            public boolean hunt(AgentRuntimeEntry runtime, Character agent, String requestId, int mapId, long exitAtMs) {
                hunts.add(mapId);
                return true;
            }
        };
        game.interaction.when(() -> AgentInteractionRuntime.registerDirectorIdleAgent(any()))
                .thenAnswer(inv -> new AgentRuntimeEntry(inv.getArgument(0), inv.getArgument(0), null));
    }

    @AfterEach
    void close() {
        game.close();
    }

    @Test
    void theStepIsReadFromTheBag() {
        assertEquals(Step.HUNT_KEYS, TicketErrands.next(FRESH));
        assertEquals(Step.HUNT_KEYS, TicketErrands.next(new Progress(9, false)));
        assertEquals(Step.TRADE, TicketErrands.next(new Progress(10, false)));
        assertEquals(Step.TRADE, TicketErrands.next(new Progress(23, false)));
        assertEquals(Step.DONE, TicketErrands.next(new Progress(0, true)));
        assertEquals(Step.DONE, TicketErrands.next(new Progress(12, true)), "a ticket is a ticket");
    }

    // ---- the owner's ask ----------------------------------------------------------------------------------

    @Test
    void askedItSaysSoWalksOutKeepingItsSeatAndHeadsForPurplewood() {
        String said = errands.ask(game.ann, thane);

        assertEquals("Thane leaves to earn an Entrance Ticket, keeping the party slot.", said);
        verify(game.residents).reply(thane, game.ann, TicketErrandLines.GO.get(ANN % TicketErrandLines.GO.size()));
        assertEquals(RosterState.AWAY, game.row(thane).state());
        assertEquals(CompanionSlots.Kind.AWAY, game.slots.reservation("thane").kind(), "its seat is kept");
        assertTrue(game.store.flags.containsKey("companion.ticket-errand.10.thane"));
        assertTrue(errands.underway("thane"));
        assertEquals("hunting Lip Lock Keys in Purplewood", errands.doing("thane"));

        RosterEntry away = game.row(thane);
        assertSame(errands.hunt, errands.formFor(away));
        assertEquals(0, errands.hunt.mapFor(away, thane, THANE), "on its way");
        clock.addAndGet(roadTo(PURPLEWOOD_FOREST_2_MAP_ID));
        assertEquals(PURPLEWOOD_FOREST_2_MAP_ID, errands.hunt.mapFor(away, thane, THANE), "in Purplewood");
        assertEquals(0, errands.talk.mapFor(away, thane, THANE), "a hunt, not a talk");
    }

    @Test
    void withTenKeysAlreadyItGoesStraightToAmosTheStrong() {
        bag.set(new Progress(10, false));
        errands.ask(game.ann, thane);

        verify(game.residents).reply(thane, game.ann, TicketErrandLines.TRADE.get(ANN % TicketErrandLines.TRADE.size()));
        assertSame(errands.talk, errands.formFor(game.row(thane)));
        assertEquals("gone to see Amos the Strong", errands.doing("thane"));
    }

    @Test
    void belowTheChallengesLevelOrHoldingOneItSaysSoAndStays() {
        game.store.levels.put(THANE, 39);
        assertEquals("Thane needs Lv40 for the Amorian Challenge.", errands.ask(game.ann, thane));
        verify(game.residents).reply(thane, game.ann, TicketErrandLines.LOW.get(ANN % TicketErrandLines.LOW.size()));

        game.store.levels.put(THANE, 45);
        bag.set(new Progress(0, true));
        assertEquals("Thane already holds an Entrance Ticket.", errands.ask(game.ann, thane));

        assertEquals(RosterState.ACTIVE, game.row(thane).state());
        assertNull(errands.errand("thane"));
        assertFalse(game.store.flags.containsKey("companion.ticket-errand.10.thane"));
    }

    @Test
    void withTheKillSwitchOffNobodyGoesAndOneOutIsCalledBack() {
        errands.ask(game.ann, thane);
        game.runTimers();
        game.config.COMPANION_TICKET_ERRANDS_ENABLED = false;

        assertNull(errands.formFor(game.row(thane)));
        errands.sweep();
        game.runTimers();

        assertEquals(RosterState.ACTIVE, game.row(thane).state());
        assertFalse(game.store.flags.containsKey("companion.ticket-errand.10.thane"));
        assertTrue(errands.ask(game.ann, thane).contains("can't go"));
    }

    // ---- the hunt and the ledger ---------------------------------------------------------------------------

    @Test
    void aLiveHuntInPurplewoodMeasuresItsKeysAnHour() {
        BenchDutyDispatcher.Slice slice = inPurplewood();
        assertEquals(List.of(PURPLEWOOD_FOREST_2_MAP_ID), hunts);
        assertTrue(errands.errand("thane").live);

        clock.addAndGet(30 * 60_000L);
        bag.set(new Progress(3, false));
        assertNull(errands.hunt.check(slice, game.bot(thane), game.row(thane), clock.get()), "seven more to go");
        errands.hunt.finish(slice, game.bot(thane), "slice length");

        TicketErrands.Errand errand = errands.errand("thane");
        assertFalse(errand.live);
        assertEquals(6.0d, errand.perHour, 1e-9, "3 keys in half an hour");
        assertEquals(Step.HUNT_KEYS, errand.step);
        assertEquals(PURPLEWOOD_FOREST_2_MAP_ID, errands.hunt.mapFor(game.row(thane), thane, THANE));
    }

    @Test
    void tenKeysEndTheHuntAndSendItToAmosTheStrong() {
        BenchDutyDispatcher.Slice slice = inPurplewood();
        clock.addAndGet(40 * 60_000L);
        bag.set(new Progress(10, false));

        assertEquals("has its keys", errands.hunt.check(slice, game.bot(thane), game.row(thane), clock.get()));
        errands.hunt.finish(slice, game.bot(thane), "has its keys");

        TicketErrands.Errand errand = errands.errand("thane");
        assertEquals(Step.TRADE, errand.step);
        assertEquals(0, errands.talk.mapFor(game.row(thane), thane, THANE), "on its way");
        clock.addAndGet(RecallTravel.etaMs(PURPLEWOOD_FOREST_2_MAP_ID, TRAINING_GROUND_MAP_ID, game.config.RECALL_MAX_ETA_MS));
        assertEquals(TRAINING_GROUND_MAP_ID, errands.talk.mapFor(game.row(thane), thane, THANE));
    }

    @Test
    void betweenLiveSlicesTheLedgerCarriesTheHuntOnAtItsOwnRateIntoItsBag() {
        BenchDutyDispatcher.Slice slice = inPurplewood();
        clock.addAndGet(30 * 60_000L);
        bag.set(new Progress(3, false));
        errands.hunt.finish(slice, game.bot(thane), "slice length");

        clock.addAndGet(30 * 60_000L);
        errands.sweep();
        TicketErrands.Errand errand = errands.errand("thane");
        assertEquals(3.0d, errand.credit, 1e-9, "6 an hour, half an hour");
        assertEquals(Step.HUNT_KEYS, errand.step);
        clock.addAndGet(60 * 60_000L);
        errands.sweep();
        assertEquals(7.0d, errand.credit, 1e-9, "never past the ten");
        assertEquals(Step.TRADE, errand.step, "covered: off to Amos the Strong");

        clock.addAndGet(RecallTravel.etaMs(PURPLEWOOD_FOREST_2_MAP_ID, TRAINING_GROUND_MAP_ID, game.config.RECALL_MAX_ETA_MS));
        assertTrue(begin(errands.talk, TRAINING_GROUND_MAP_ID, AMOS_THE_STRONG_NPC_ID));
        assertEquals(List.of(7), credits, "into its bag as it loads");
        assertEquals(0.0d, errand.credit, 1e-9);
        game.runDueTimers(); // it walks up to him first
        clock.addAndGet(game.config.COMPANION_JOB_ERRAND_TALK_MS);
        game.runDueTimers();
        assertEquals(List.of("trade"), acts);
        assertTrue(bag.get().ticket());
    }

    @Test
    void aHuntItNeverMeasuredLiveGainsNothingOnTheLedger() {
        errands.ask(game.ann, thane);
        game.runTimers();
        clock.addAndGet(roadTo(PURPLEWOOD_FOREST_2_MAP_ID) + 5 * 3_600_000L);

        errands.sweep();

        TicketErrands.Errand errand = errands.errand("thane");
        assertEquals(0.0d, errand.credit, 1e-9, "no live rate, no credit: never quicker than a live hunt");
        assertEquals(Step.HUNT_KEYS, errand.step);
    }

    @Test
    void aShortLiveSliceMeasuresNothing() {
        BenchDutyDispatcher.Slice slice = inPurplewood();
        clock.addAndGet(20_000L);
        bag.set(new Progress(1, false));
        errands.hunt.finish(slice, game.bot(thane), "unloaded");

        assertEquals(0.0d, errands.errand("thane").perHour, 1e-9);
    }

    // ---- the trade and home again --------------------------------------------------------------------------

    @Test
    void atAmosTheStrongItTradesItsKeysAsAPlayerDoesAndComesBackToSayIt() {
        bag.set(new Progress(10, false));
        atAmos();
        game.runDueTimers();
        assertTrue(acts.isEmpty(), "it stands with him a moment first");
        clock.addAndGet(game.config.COMPANION_JOB_ERRAND_TALK_MS);
        game.runDueTimers();
        assertEquals(List.of("trade"), acts);
        game.runTimers(); // the trip back

        assertEquals(RosterState.ACTIVE, game.row(thane).state());
        verify(game.residents).reply(thane, game.ann, TicketErrandLines.BACK.get(ANN % TicketErrandLines.BACK.size()));
        assertNull(errands.errand("thane"));
        assertFalse(game.store.flags.containsKey("companion.ticket-errand.10.thane"));
        assertEquals(1, game.store.events("ticket.earned").size());
    }

    @Test
    void aRefusedTradeIsTriedAgainAndTheThirdRefusalEndsTheErrand() {
        bag.set(new Progress(10, false));
        errands.hands = new RefusingHands();
        errands.ask(game.ann, thane);
        game.runTimers();
        clock.addAndGet(roadTo(TRAINING_GROUND_MAP_ID));
        for (int attempt = 1; attempt <= TicketErrands.TRIES; attempt++) {
            assertTrue(begin(errands.talk, TRAINING_GROUND_MAP_ID, AMOS_THE_STRONG_NPC_ID), "attempt " + attempt);
            game.runDueTimers(); // it walks up to him first
            clock.addAndGet(game.config.COMPANION_JOB_ERRAND_TALK_MS);
            game.runDueTimers();
        }

        assertFalse(errands.underway("thane"));
        assertEquals(1, game.store.events("ticket.errand.end").size());
        errands.sweep();
        game.runTimers();
        assertEquals(RosterState.ACTIVE, game.row(thane).state(), "the sweep calls it back");
        verify(game.residents, never()).reply(thane, game.ann, TicketErrandLines.BACK.get(ANN % TicketErrandLines.BACK.size()));
    }

    @Test
    void calledBackByItsOwnerItsErrandIsOverAndItsBagKeepsItsKeys() {
        errands.ask(game.ann, thane);
        game.runTimers();
        game.roster().call(game.ann, thane);
        game.runTimers();
        assertEquals(RosterState.ACTIVE, game.row(thane).state());

        errands.sweep();

        assertNull(errands.errand("thane"));
        assertFalse(game.store.flags.containsKey("companion.ticket-errand.10.thane"));
    }

    @Test
    void aBenchedCompanionGoesBetweenItsDutiesAndBackToThem() {
        Persona orrin = game.companionAs("PAGE", "orrin", "Orrin", 906, RosterState.BENCHED, LITH);
        game.store.levels.put(906, 50);

        assertEquals("Orrin will go for an Entrance Ticket between bench duties.", errands.ask(game.ann, orrin));

        TicketErrands.Errand errand = errands.errand("orrin");
        assertFalse(errand.returns);
        assertSame(errands.hunt, errands.formFor(game.row(orrin)));
        assertEquals(RosterState.BENCHED, game.row(orrin).state(), "it stays on the bench");
        assertTrue(game.store.flags.containsKey("companion.ticket-errand.10.orrin"));
    }

    @Test
    void aRestartCarriesTheErrandsOnFromPurplewood() {
        game.store.flags.put("companion.ticket-errand.10.thane", ANN);
        game.store.flags.put("companion.ticket-errand.10.gone", ANN);
        game.store.seed(game.row(thane).with(RosterState.AWAY, 1_000L));

        errands.restore();

        assertFalse(game.store.flags.containsKey("companion.ticket-errand.10.gone"), "a row that is not away has none");
        TicketErrands.Errand errand = errands.errand("thane");
        assertTrue(errand.returns);
        assertEquals(PURPLEWOOD_FOREST_2_MAP_ID, errands.hunt.mapFor(game.row(thane), thane, THANE));
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    /** Thane left Ann with his ten keys, travelled to Amos' Training Ground and walks up to Amos the Strong now. */
    private BenchDutyDispatcher.Slice atAmos() {
        errands.ask(game.ann, thane);
        game.runTimers();
        clock.addAndGet(roadTo(TRAINING_GROUND_MAP_ID));
        assertTrue(begin(errands.talk, TRAINING_GROUND_MAP_ID, AMOS_THE_STRONG_NPC_ID));
        return new BenchDutyDispatcher.Slice(game.row(thane), THANE, errands.talk, TRAINING_GROUND_MAP_ID, clock.get());
    }

    /** Thane is loaded in Purplewood Forest 2 now, hunting: the slice. */
    private BenchDutyDispatcher.Slice inPurplewood() {
        errands.ask(game.ann, thane);
        game.runTimers();
        clock.addAndGet(roadTo(PURPLEWOOD_FOREST_2_MAP_ID));
        assertTrue(begin(errands.hunt, PURPLEWOOD_FOREST_2_MAP_ID, 0));
        return new BenchDutyDispatcher.Slice(game.row(thane), THANE, errands.hunt, PURPLEWOOD_FOREST_2_MAP_ID, clock.get());
    }

    /** A bench slice of {@code form} begun on {@code mapId}, with the NPC (if any) at (400, 150). */
    private boolean begin(BenchDutyDispatcher.Form form, int mapId, int npcId) {
        MapleMap map = game.map(mapId);
        if (npcId != 0) {
            NPC npc = mock(NPC.class);
            when(npc.getPosition()).thenReturn(new Point(400, 150));
            when(map.getNPCById(npcId)).thenReturn(npc);
        }
        Character bot = game.bot(thane);
        bot.changeMap(map, new Point(LifecycleHarness.portalX(mapId), 200));
        BenchDutyDispatcher.Slice slice = new BenchDutyDispatcher.Slice(game.row(thane), THANE, form, mapId, clock.get());
        return form.begin(slice, bot, clock.get());
    }

    private long roadTo(int mapId) {
        return RecallTravel.etaMs(LifecycleHarness.HENESYS_FIELD, mapId, game.config.RECALL_MAX_ETA_MS);
    }

    private final class RefusingHands implements TicketErrands.Hands {
        @Override
        public boolean trade(AgentRuntimeEntry runtime) {
            acts.add("trade");
            return false;
        }

        @Override
        public void credit(AgentRuntimeEntry runtime, int keys) {
        }

        @Override
        public boolean hunt(AgentRuntimeEntry runtime, Character agent, String requestId, int mapId, long exitAtMs) {
            return false;
        }
    }
}
