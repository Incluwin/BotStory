package server.living.roster;

import client.Character;
import client.Job;
import net.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.kpq.AgentKpqSession;
import server.agents.integration.AgentDialogueTransportRuntime;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.KERNING;

/**
 * Yes at Lakelis (P6, flow 3): the companions left out step out of the party beside Lakelis first (a HOLD, saying so
 * aloud), then, back on Ann's I/O thread, the lineup is admitted, marked a companion run with guidance, committed
 * with its extras, and the event started, in that order and in one step. A refused admission is answered in
 * Lakelis's words and calls the extras back; so does a party that changed meanwhile, without admitting anyone. A lineup
 * of humans only takes the stock start. A second Yes while the first is carried out only hears "hold on".
 */
class CompanionPqEntryTest {
    private final CompanionPqFixture fx = new CompanionPqFixture();
    private final LifecycleHarness game = fx.game;
    private Persona casse;
    private Persona hadrik;

    @BeforeEach
    void annAtLakelisWithFiveCompanions() {
        fx.companion("mira", "Mira", 900, 24, Job.HUNTER);
        fx.companion("tobi", "Tobi", 901, 27, Job.PAGE);
        fx.companion("pip", "Pip", 902, 26, Job.FP_WIZARD);
        casse = fx.companion("casse", "Casse", 903, 25, Job.BANDIT);
        hadrik = fx.companion("hadrik", "Hadrik", 904, 32, Job.FIGHTER);
        fx.potions.put(903, 0); // live: Casse had 586 meso and no HP potion
    }

    @AfterEach
    void close() {
        fx.close();
    }

    @Test
    void extrasAreHeldBesideLakelisBeforeAdmit() {
        fx.ready();

        fx.yes();

        assertEquals(List.of(ANN, 900, 901, 902), fx.doors.partyAtAdmit, "admission sees the lineup only");
        assertEquals(RosterState.HOLD, game.row(casse).state());
        assertEquals(KERNING, game.row(casse).holdMapId());
        assertEquals(RosterState.HOLD, game.row(hadrik).state());
        List<String> steps = game.calls.stream().filter(call -> call.startsWith("walk") || call.startsWith("admit")
                || call.startsWith("start")).toList();
        assertEquals(List.of("walk Casse to 1440,120", "walk Hadrik to 1470,120", "admit [Ann, Mira, Tobi, Pip]", "start"),
                steps, "beside Lakelis, then admitted, then started");
        fx.dialogue.verify(() -> AgentDialogueTransportRuntime.sayMapNow(game.bot(casse),
                "i'll wait out here till you're done."));
    }

    @Test
    void adoptSetsBothFlagsAndCommitsCompanionsAndExtrasBeforeTheStart() {
        game.config.COMPANION_PQ_GUIDANCE = false;
        fx.ready();

        CompanionRun run = fx.yes();

        AgentKpqSession session = fx.doors.session;
        assertTrue(fx.doors.adoptedAtStart, "marked before the start");
        assertEquals(5, fx.doors.committedAtStart, "three companions and two extras, before the start");
        assertTrue(session.companionAdopted());
        assertFalse(session.memberCoordinationChatEnabled(), "COMPANION_PQ_GUIDANCE off");
        assertEquals("pq:kpq:" + session.sessionId(), run.key);
        for (int id : List.of(900, 901, 902, 903, 904)) {
            assertEquals(run.key, game.commitments.keyOf(id), "companion " + id);
        }
        assertFalse(game.commitments.isCommitted(ANN), "humans are never committed");
        assertTrue(run.started);
        assertFalse(game.calls.stream().anyMatch(call -> call.startsWith("answer")), "a start that went in says nothing");
    }

    @Test
    void guidanceFollowsTheSwitch() {
        fx.ready();

        fx.yes();

        assertTrue(fx.doors.session.memberCoordinationChatEnabled());
    }

    @Test
    void admitFailureSpeaksTheMappedLineAndRejoinsTheHolds() {
        fx.doors.refusal = "Tobi could not leave its current activity";
        fx.ready();

        CompanionRun run = fx.yes();

        assertNull(run);
        assertTrue(game.calls.contains("answer Tobi is busy with something. Give them a moment."));
        assertFalse(game.calls.contains("start"));
        for (int id : List.of(900, 901, 902, 903, 904)) {
            assertFalse(game.commitments.isCommitted(id), "nothing committed: " + id);
        }
        assertEquals(RosterState.ACTIVE, game.row(casse).state(), "called back");
        assertEquals(RosterState.ACTIVE, game.row(hadrik).state());
        assertTrue(game.members.containsAll(List.of(903, 904)));
    }

    @Test
    void partyChangedMeanwhileNeverAdmits() {
        fx.ready();
        fx.io = new ArrayList<>();
        fx.yes(); // the extras step out; the start waits for Ann's I/O thread
        game.members.remove((Integer) 902); // Pip left the party meanwhile

        CompanionPqFixture.drain(fx.io);

        assertFalse(game.calls.stream().anyMatch(call -> call.startsWith("admit")));
        assertTrue(game.calls.contains("answer Your party changed while I was getting things ready. Talk to me again."));
        assertEquals(RosterState.ACTIVE, game.row(casse).state(), "called back");
        assertEquals(RosterState.ACTIVE, game.row(hadrik).state());
    }

    @Test
    void aHoldThatFailsIsAnsweredOnAnnsIoThread() {
        fx.ready();
        List<Runnable> ops = fx.holdCompanionOps();
        fx.io = new ArrayList<>();
        fx.yes();
        game.loaded.remove(904); // Hadrik logged off before its turn to step out

        CompanionPqFixture.drain(ops);

        assertFalse(game.calls.stream().anyMatch(call -> call.startsWith("answer")), "not on companion-ops");
        CompanionPqFixture.drain(fx.io);
        assertTrue(game.calls.contains("answer Your party changed while I was getting things ready. Talk to me again."));
        assertFalse(game.calls.stream().anyMatch(call -> call.startsWith("admit")));
        CompanionPqFixture.drain(ops);
        assertEquals(RosterState.ACTIVE, game.row(casse).state(), "the one that stepped out comes back");
        game.calls.clear();
        fx.yes();
        assertFalse(game.calls.contains("answer Hold on, they're lining up."), "the Yes is over");
    }

    @Test
    void allHumanLineupTakesTheStockStart() {
        threeHumansAndNobodyToSendIn();

        CompanionRun run = fx.yes();

        assertNull(run, "no companion run");
        assertTrue(game.calls.contains("start"));
        assertFalse(game.calls.stream().anyMatch(call -> call.startsWith("admit")));
        assertEquals(Set.of(ANN, 20, 21), new HashSet<>(game.members), "the companions wait outside");
        assertEquals(RosterState.HOLD, game.row(casse).state(), "they rejoin as Ann comes out");
    }

    @Test
    void aStockStartThatFailsCallsTheExtrasBack() {
        threeHumansAndNobodyToSendIn();
        fx.doors.start = PqEntryPort.Start.NO_LOBBY;

        fx.yes();

        assertTrue(game.calls.contains("answer " + LakelisLines.NO_LOBBY));
        assertEquals(RosterState.ACTIVE, game.row(casse).state());
        assertEquals(Set.of(ANN, 20, 21, 900, 903, 904), new HashSet<>(game.members));
    }

    /** Ben and Cid join Ann; Mira, Casse and Hadrik stay in the party, and none of them can go in. */
    private void threeHumansAndNobodyToSendIn() {
        game.members.removeAll(List.of(901, 902)); // Tobi and Pip are not in this party
        human(20, "Ben");
        human(21, "Cid");
        fx.ready();
        fx.potions.put(900, 0);
    }

    @Test
    void doubleYesAnswersHoldOn() {
        fx.ready();
        fx.io = new ArrayList<>();
        fx.yes();

        fx.yes();

        assertTrue(game.calls.contains("answer Hold on, they're lining up."));
        CompanionPqFixture.drain(fx.io);
        assertEquals(1, game.calls.stream().filter(call -> call.startsWith("admit")).count());
    }

    @Test
    void aStaleYesShowsTheLineupAgain() {
        fx.ready();
        LineupPlan seen = fx.service.plan(game.ann, "kpq");
        game.members.remove((Integer) 902); // Pip left after the lineup was shown

        fx.service.start(game.ann, seen);

        assertEquals(List.of("things changed"), game.calls);
    }

    /** Another human of Ann's party, online beside her at Lakelis. */
    private Character human(int id, String name) {
        Character human = mock(Character.class);
        when(human.getId()).thenReturn(id);
        when(human.getName()).thenReturn(name);
        when(human.getMapId()).thenReturn(KERNING);
        when(human.getMap()).thenReturn(game.map(KERNING));
        CompanionPqFixture.stand(human, 25, Job.BANDIT);
        game.humans.add(id);
        game.members.add(id);
        when(Server.getInstance().getWorld(0).getPlayerStorage().getCharacterById(id)).thenReturn(human);
        return human;
    }
}
