package server.living.roster;

import client.Character;
import client.Client;
import client.Job;
import net.server.world.Party;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scripting.event.EventManager;
import scripting.npc.NPCConversationManager;
import server.agents.capabilities.combat.data.AgentAttackDataProvider;
import server.agents.capabilities.contracts.AgentResourceCategory;
import server.agents.capabilities.partyquest.hpq.AgentHpqSessionRegistry;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfiles;
import server.agents.capabilities.supplies.AgentSupplyProcurementState;
import server.agents.integration.AgentDialogueTransportRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.life.LifeFactory;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;

/**
 * Companion runs at Tory (P6b HPQ step 5): Ann leads her companions at Henesys Park, and the same service that serves
 * Lakelis serves Tory by HPQ's entrance. The kill switch lists hpq apart; each key answers at its own desk (her pink
 * text names her); HPQ's admission runs on companion-ops, never on Ann's I/O thread, and the start is HenesysPQ's,
 * back on it; the extras wait on the park floor, off Tory's ledge; a companion off the park on its restock trip is on
 * its way; and a Tory click never spends the greeting Lakelis keeps for a leader back from a KPQ re-roll. After the
 * off-I/O admission, every way the Yes ends (a run, a refusal, a hand-back that throws) lets Ann's next Yes through,
 * an admitted session that cannot start is stopped and its extras called back, and whatever Tory answers is hers: her
 * refusals, 1012112.js's start texts, the lineup again for a companion still finishing something, her generic line.
 */
class CompanionPartyQuestServiceHpqTest {
    private static final int HENESYS_SHOP = 100_000_102;

    private final CompanionPqFixture fx = new CompanionPqFixture();
    private final LifecycleHarness game = fx.game;
    /** One runtime entry per loaded companion for the whole test (the harness hands out a new one per lookup). */
    private final Map<Integer, AgentRuntimeEntry> entries = new HashMap<>();
    /** What Tory's desk was asked to show ("proposal hpq", "outcome ..."). */
    private final List<String> tory = new ArrayList<>();
    private String toryTopLine;

    @BeforeEach
    void annAtToryWithHerCompanions() {
        fx.atEntrance("hpq");
        fx.companion("mira", "Mira", 900, 22, Job.HUNTER);
        fx.companion("tobi", "Tobi", 901, 23, Job.PAGE);
        fx.companion("pip", "Pip", 902, 24, Job.FP_WIZARD);
        game.registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(anyInt())).thenAnswer(inv -> {
            int id = inv.getArgument(0);
            return game.loaded.contains(id)
                    ? entries.computeIfAbsent(id, key -> new AgentRuntimeEntry(game.bots.get(key), game.ann, null))
                    : null;
        });
    }

    @AfterEach
    void close() {
        fx.close();
    }

    // ---- the switch and the desks ----------------------------------------------------------

    @Test
    void theKillSwitchListsHpqApartAndEachKeyAnswersAtItsOwnDesk() {
        fx.ready();
        installTory();
        enableDesk("kpq");

        assertFalse(fx.service.offerAtDesk(game.ann, "hpq"), "hpq not listed: Tory is stock");
        assertEquals(List.of(), tory);
        assertFalse(game.calls.contains("proposal"));

        game.config.COMPANION_PQ_ENABLED_KEYS = "kpq,hpq";
        assertTrue(fx.service.offerAtDesk(game.ann, "hpq"));

        assertEquals(List.of("proposal hpq"), tory, "Tory's desk, with an HPQ lineup");
        assertFalse(game.calls.contains("proposal"), "never Lakelis's");
    }

    @Test
    void withoutHerDeskToryAnswersInPinkTextInHerOwnName() {
        fx.ready();
        fx.io = new ArrayList<>(); // Ann's first Yes is still being carried out
        LineupPlan plan = fx.service.plan(game.ann, "hpq");
        fx.service.start(game.ann, plan);

        fx.service.start(game.ann, plan);

        verify(game.ann).dropMessage(5, "Tory: Hold on, they're lining up.");
        assertFalse(game.calls.stream().anyMatch(call -> call.startsWith("answer")), "Lakelis's desk is not hers");
    }

    // ---- Yes at Tory -----------------------------------------------------------------------

    @Test
    void hpqIsAdmittedOnCompanionOpsAndStartedOnHerIoThreadWithTheExtraOnTheParkFloor() {
        fx.companion("casse", "Casse", 903, 12, Job.BANDIT); // the fourth companion waits outside
        fx.ready();
        List<Runnable> ops = fx.holdCompanionOps();

        fx.service.start(game.ann, fx.service.plan(game.ann, "hpq"));
        assertEquals(List.of(), steps(), "the extras step out on companion-ops first");
        CompanionPqFixture.drain(ops);

        assertEquals(List.of("walk Casse to 1554,574", "admit [Ann, Tobi, Mira, Pip]", "start hpq"), steps(),
                "off Tory's ledge, then admitted, then started");
        assertFalse(fx.doors.admittedOnIo, "HPQ's admission never runs on Ann's I/O thread");
        assertTrue(fx.doors.startedOnIo, "HenesysPQ's start is the click's, on her I/O thread");
        CompanionRun run = fx.service.runForSession(fx.doors.hpqSession.sessionId());
        assertEquals("hpq", run.pqKey);
        assertEquals("pq:hpq:" + fx.doors.hpqSession.sessionId(), run.key);
        assertTrue(run.started);
        assertEquals(4, fx.doors.committedAtStart, "three companions and the extra, before the start");
        assertTrue(fx.doors.hpqSession.memberCoordinationChatEnabled(), "COMPANION_PQ_GUIDANCE on");
        assertEquals(new PqEntrance.LiveSession(fx.doors.hpqSession.sessionId(), ANN),
                CompanionPartyQuestService.liveSession(run));
        assertFalse(game.calls.stream().anyMatch(call -> call.startsWith("answer")), "a start that went in says nothing");
    }

    @Test
    void aLeaderWhoLogsOffWhileHpqIsAdmittedHasItsSessionStoppedAndTheExtrasCalledBack() {
        Persona casse = fx.companion("casse", "Casse", 903, 12, Job.BANDIT);
        fx.ready();
        List<Runnable> ops = fx.holdCompanionOps();
        fx.service.start(game.ann, fx.service.plan(game.ann, "hpq"));
        ops.removeFirst().run(); // Casse steps out; Ann's I/O thread hands HPQ's admission to companion-ops
        assertEquals(RosterState.HOLD, game.row(casse).state());

        when(game.ann.getClient()).thenReturn(null); // Ann logs off meanwhile
        CompanionPqFixture.drain(ops);

        assertTrue(game.calls.contains("stop 901: companion HPQ leader logged off"), game.calls.toString());
        assertFalse(game.calls.contains("start hpq"));
        assertNull(fx.service.runForSession(fx.doors.hpqSession.sessionId()), "no run of it");
        assertEquals(RosterState.ACTIVE, game.row(casse).state(), "Casse is called back");
    }

    // ---- after HPQ's admission: Ann's next Yes, a hand-back that throws, Tory's own refusals ---------------

    @Test
    void afterAnHpqRunEndsAnnsNextYesAtToryGoesThrough() {
        fx.ready();
        installTory();
        assertTrue(fx.yes().started);

        fx.sessionEnds(true);
        CompanionRun next = fx.yes();

        assertFalse(tory.contains("outcome " + DeskLines.HOLD_ON), "the first Yes is over: " + tory);
        assertEquals(2, admits(), game.calls.toString());
        assertTrue(next != null && next.started, "the second run went up the hill");
    }

    @Test
    void aRefusedHpqAdmissionLetsAnnsNextYesThrough() {
        fx.ready();
        installTory();
        List<Runnable> ops = fx.holdCompanionOps();
        fx.doors.refusal = "Every HPQ member must be on the same world and channel";
        fx.service.start(game.ann, fx.service.plan(game.ann, "hpq"));
        CompanionPqFixture.drain(ops);
        assertEquals(List.of("outcome " + DeskLines.THIS_CHANNEL), tory);

        fx.doors.refusal = null;
        fx.service.start(game.ann, fx.service.plan(game.ann, "hpq"));
        CompanionPqFixture.drain(ops);

        assertEquals(List.of("outcome " + DeskLines.THIS_CHANNEL), tory, "no 'Hold on' for the second Yes");
        assertEquals(2, admits(), game.calls.toString());
        assertTrue(game.calls.contains("start hpq"));
    }

    @Test
    void aHandBackThatThrowsStopsTheAdmittedSessionCallsTheExtraBackAndToryAnswers() {
        Persona casse = fx.companion("casse", "Casse", 903, 12, Job.BANDIT); // waits outside
        fx.ready();
        installTory();
        fx.doors.onStop = () -> AgentHpqSessionRegistry.remove(fx.doors.hpqSession); // the Agent layer's stop
        List<Runnable> ops = fx.holdCompanionOps();
        fx.service.start(game.ann, fx.service.plan(game.ann, "hpq"));
        ops.removeFirst().run(); // Casse steps out; Ann's I/O thread hands HPQ's admission to companion-ops
        assertEquals(RosterState.HOLD, game.row(casse).state());
        Client client = game.ann.getClient();
        doThrow(new IllegalStateException("Ann's channel is closing")).doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(client).runOnIoThread(any()); // the hand-back throws once; her answer still gets through

        assertThrows(IllegalStateException.class, () -> CompanionPqFixture.drain(ops)); // companion-ops logs it
        CompanionPqFixture.drain(ops);

        assertTrue(game.calls.contains("stop 901: companion HPQ start failed"), game.calls.toString());
        assertFalse(game.calls.contains("start hpq"));
        assertNull(fx.service.runForSession(fx.doors.hpqSession.sessionId()), "no run of it");
        assertEquals(RosterState.ACTIVE, game.row(casse).state(), "Casse is called back");
        assertEquals(List.of("outcome Something went wrong. Try again in a moment."), tory, "Tory's own words");

        fx.service.start(game.ann, fx.service.plan(game.ann, "hpq"));
        CompanionPqFixture.drain(ops);
        assertFalse(tory.contains("outcome " + DeskLines.HOLD_ON), "the Yes that threw is over: " + tory);
        assertEquals(2, admits(), game.calls.toString());
    }

    @Test
    void aHoldThatThrowsAtToryIsAnsweredInHerWords() {
        Persona casse = fx.companion("casse", "Casse", 903, 12, Job.BANDIT); // waits outside
        fx.ready();
        installTory();
        List<Runnable> ops = fx.holdCompanionOps();
        fx.dialogue.when(() -> AgentDialogueTransportRuntime.sayMapNow(eq(game.bot(casse)), anyString()))
                .thenThrow(new IllegalStateException("map chat is down"));
        fx.service.start(game.ann, fx.service.plan(game.ann, "hpq"));

        assertThrows(IllegalStateException.class, () -> CompanionPqFixture.drain(ops)); // companion-ops logs it

        assertEquals(List.of("outcome Something went wrong. Try again in a moment."), tory);
        assertEquals(0, admits());
    }

    @Test
    void aSessionReleasedBeforeHerStartIsAnsweredInHerWords() {
        fx.ready();
        installTory();
        fx.io = new ArrayList<>();
        fx.service.start(game.ann, fx.service.plan(game.ann, "hpq"));
        fx.io.removeFirst().run(); // the lineup checked on Ann's I/O thread; admitted on companion-ops (inline here)
        assertEquals(1, admits());

        AgentHpqSessionRegistry.remove(fx.doors.hpqSession); // released before the hand-back reached her I/O thread
        CompanionPqFixture.drain(fx.io);

        assertEquals(List.of("outcome Something went wrong. Try again in a moment."), tory);
        assertFalse(game.calls.contains("start hpq"));
        assertNull(fx.service.runForSession(fx.doors.hpqSession.sessionId()), "no run of it");
    }

    @Test
    void anHpqRefusalOffAnnsIoThreadIsSaidInToryWords() {
        fx.ready();
        installTory();
        fx.doors.refusal = "The current Henesys event accepts three to six members";

        fx.yes();

        assertEquals(List.of("outcome I can only send three to six of you up the hill."), tory);
        assertFalse(game.calls.contains("start hpq"));
    }

    @Test
    void aCompanionStillFinishingSomethingAtToryHasHerShowTheLineupAgain() {
        fx.ready();
        installTory();
        fx.doors.refusal = "Mira could not leave its current activity";

        fx.yes();

        assertEquals(List.of("still finishing hpq: Give Mira a sec, they're finishing something."), tory);
        assertFalse(game.calls.contains("start hpq"));
    }

    @Test
    void aFailedHenesysPqStartIsStoppedAndSaidInHerScriptsWords() {
        fx.ready();
        installTory();
        fx.doors.start = PqEntryPort.Start.NO_LOBBY;

        fx.yes();

        assertEquals(List.of("outcome " + ToryLines.NO_LOBBY), tory);
        assertTrue(game.calls.contains("stop 901: companion HPQ start failed"), game.calls.toString());
        assertNull(fx.service.runForSession(fx.doors.hpqSession.sessionId()), "no run of it");
    }

    @Test
    void everyEntrancesPaceDataIsWarmedAtBootSwitchedOnOrNot() {
        AgentAttackDataProvider timings = mock(AgentAttackDataProvider.class);
        try (MockedStatic<AgentPqPaceProfiles> profiles = mockStatic(AgentPqPaceProfiles.class);
             MockedStatic<LifeFactory> mobs = mockStatic(LifeFactory.class);
             MockedStatic<AgentAttackDataProvider> provider = mockStatic(AgentAttackDataProvider.class)) {
            provider.when(AgentAttackDataProvider::getInstance).thenReturn(timings);
            game.config.COMPANION_PQ_ENABLED_KEYS = "kpq"; // Tory not switched on yet

            fx.ready().warmPace();

            profiles.verify(() -> AgentPqPaceProfiles.register(eq("hpq"), any()));
            profiles.verify(() -> AgentPqPaceProfiles.warm("hpq"));
            for (int mobId : List.of(9_300_062, 9_300_063, 9_300_064)) {
                mobs.verify(() -> LifeFactory.getMonster(mobId));
            }
            verify(timings).getBodyActionId(anyString()); // once for every entrance
        }
    }

    @Test
    void toryStartsHenesysPqAndLakelisKerningPq() {
        Character leader = mock(Character.class);
        Client client = mock(Client.class);
        Party party = mock(Party.class);
        MapleMap park = mock(MapleMap.class);
        EventManager henesys = mock(EventManager.class);
        when(leader.getClient()).thenReturn(client);
        when(leader.getParty()).thenReturn(party);
        when(leader.getMap()).thenReturn(park);
        when(client.getEventManager("HenesysPQ")).thenReturn(henesys);
        when(henesys.getEligibleParty(party)).thenReturn(List.of(mock(net.server.world.PartyCharacter.class)));
        when(henesys.startInstance(party, park, 1)).thenReturn(true);

        assertEquals(PqEntryPort.Start.STARTED, PqEntryPort.STOCK.startInstance("hpq", leader));
        assertEquals(PqEntryPort.Start.NO_EVENT, PqEntryPort.STOCK.startInstance("kpq", leader), "no KerningPQ here");
        verify(client).getEventManager("KerningPQ");
    }

    // ---- who waits -------------------------------------------------------------------------

    @Test
    void aCompanionOffTheParkOnItsRestockTripIsOnItsWayNotWaiting() {
        fx.ready();
        Persona pip = game.personas.get("pip");
        game.bot(pip).changeMap(game.map(HENESYS_SHOP), new Point(-200, 150));
        assertEquals(List.of(new LineupPlan.Waiting(902, "Pip", "not in the park")),
                fx.service.plan(game.ann, "hpq").waiting());

        onTrip(902);
        LineupPlan plan = fx.service.plan(game.ann, "hpq");

        assertEquals(List.of(), plan.waiting());
        assertEquals(List.of(902), plan.onTheWay().stream().map(LineupPlan.Seat::characterId).toList());
        assertEquals(DeskLines.AT_THE_STORE, plan.onTheWay().getFirst().errand());
    }

    @Test
    void aCompanionAndALeaderAreWeighedByHpqsRulesInHerWords() {
        CompanionPqFixture.stand(game.bot(game.personas.get("mira")), 8, Job.BEGINNER);
        fx.ready();

        LineupPlan plan = fx.service.plan(game.ann, "hpq");
        assertEquals(List.of(new LineupPlan.Waiting(900, "Mira", "Lv8, not ready for this yet")), plan.waiting());
        assertNull(plan.blocker(), "Ann (Lv25) and two companions go up the hill");
        assertTrue(plan.startable());

        CompanionPqFixture.stand(game.ann, 9, Job.BEGINNER);
        assertEquals("You need to be at least level 10 for the hill.", fx.service.plan(game.ann, "hpq").blocker());
    }

    // ---- Lakelis's greeting ----------------------------------------------------------------

    @Test
    void aToryClickNeverSpendsTheGreetingLakelisKeepsAfterAKpqReroll() {
        fx.atEntrance("kpq");
        fx.ready();
        installTory();
        fx.yes();
        fx.sessionRerolled(); // Ann left stage 1 of KPQ to run it again
        enableDesk("kpq,hpq");

        fx.atEntrance("hpq");
        assertTrue(fx.service.offerAtDesk(game.ann, "hpq"));
        assertEquals(List.of("proposal hpq"), tory);
        assertNull(toryTopLine, "Tory has no word about KPQ's re-roll");

        fx.atEntrance("kpq");
        assertTrue(fx.service.offerAtDesk(game.ann, "kpq"));
        assertEquals(LakelisLines.BACK_ALREADY, fx.topLine, "still Lakelis's to say");
    }

    // ---- fixture ---------------------------------------------------------------------------

    /** The walks, admission and start the Yes went through, in order. */
    private List<String> steps() {
        return game.calls.stream().filter(call -> call.startsWith("walk") || call.startsWith("admit")
                || call.startsWith("start")).toList();
    }

    /** Tory's desk: it notes each window it is asked for. */
    private void installTory() {
        fx.service.installDesk("hpq", new CompanionPartyQuestService.Desk() {
            @Override
            public void proposal(Character leader, LineupPlan plan, String topLine) {
                tory.add("proposal " + plan.pqKey());
                toryTopLine = topLine;
            }

            @Override
            public void thingsChanged(Character leader, LineupPlan plan) {
                tory.add("things changed " + plan.pqKey());
            }

            @Override
            public void outcome(Character leader, String line, long clickedAtMs) {
                tory.add("outcome " + line);
            }

            @Override
            public void stillFinishing(Character leader, LineupPlan plan, String line, long clickedAtMs) {
                tory.add("still finishing " + plan.pqKey() + ": " + line);
            }
        });
    }

    /** How many times HPQ's admission was asked to admit a lineup. */
    private long admits() {
        return game.calls.stream().filter(call -> call.startsWith("admit")).count();
    }

    /** The entry NPCs of {@code keys} answer Ann's click with the lineup (her party's companions are hers). */
    private void enableDesk(String keys) {
        game.config.COMPANION_PQ_ENABLED_KEYS = keys;
        for (int id : List.of(900, 901, 902, 903)) {
            when(game.world.directory().ownerOfCharacter(id)).thenReturn(ANN);
        }
        Client client = game.ann.getClient();
        when(client.onIoThread()).thenReturn(true);
        when(client.getCM()).thenReturn(mock(NPCConversationManager.class));
    }

    /** The companion set off on a readiness trip to the Henesys shop (one hop). */
    private void onTrip(int characterId) {
        AgentRuntimeEntry entry = AgentRuntimeRegistry.findByAgentCharacterId(characterId);
        entry.capabilityStates().require(AgentSupplyProcurementState.STATE_KEY).start(
                "readiness:HP_POTION:1000", "maintenance:resupply:readiness:HP_POTION:1000",
                AgentResourceCategory.HP_POTION, HENESYS_SHOP, 1_012_004, LifecycleHarness.HENESYS,
                AgentSupplyProcurementState.Phase.TRAVEL_TO_SUPPLIER);
    }
}
