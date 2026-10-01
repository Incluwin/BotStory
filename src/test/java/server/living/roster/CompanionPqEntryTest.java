package server.living.roster;

import client.Character;
import client.Client;
import client.Job;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import net.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.npc.NPCConversationManager;
import server.agents.capabilities.dialogue.AgentDialogueCatalog;
import server.agents.capabilities.partyquest.kpq.AgentKpqSession;
import server.agents.integration.AgentDialogueTransportRuntime;
import server.living.config.Content.Persona;
import server.living.store.LivingStore.RosterState;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.KERNING;

/**
 * Yes at Lakelis (P6, flow 3): the companions left out step out of the party beside Lakelis first (a HOLD, saying so
 * aloud), then, back on Ann's I/O thread, the lineup is admitted, marked a companion run with guidance, committed
 * with its extras, and the event started, in that order and in one step. A refused admission is answered in
 * Lakelis's words and calls the extras back; so does a party that changed meanwhile, without admitting anyone. One that
 * refuses a companion still finishing something shows the lineup again at once, for a Yes that goes in; a Yes while it
 * is at it still, under half a minute later, is answered the same at once and steps nobody out. A lineup
 * of humans only takes the stock start. A second Yes while the first is carried out only hears "hold on"; one that
 * threw is over all the same. A Yes on her own choice is planned again by her own choice. A companion the lineup leaves
 * waiting for mana potions asks for some in party chat, once, as the lineup is shown.
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
    void anOwnExtraAwayFromTheEntranceWaitsWhereItIsAndTellsItsOwner() {
        game.bot(casse).changeMap(game.map(LifecycleHarness.HENESYS), new Point(300, 200)); // told to grind there
        fx.ready();

        fx.yes();

        assertEquals(RosterState.HOLD, game.row(casse).state());
        assertEquals(LifecycleHarness.HENESYS, game.row(casse).holdMapId(), "where it is, not beside Lakelis");
        assertTrue(game.calls.stream().noneMatch(call -> call.startsWith("walk Casse")), game.calls.toString());
        fx.dialogue.verify(() -> AgentDialogueTransportRuntime.sayMapNow(eq(game.bot(casse)), anyString()), never());
        verify(game.residents).whisper(casse, game.ann, "i'll wait out here till you're done.");
        fx.dialogue.verify(() -> AgentDialogueTransportRuntime.sayMapNow(game.bot(hadrik),
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
        fx.doors.refusal = "Tobi is not owned by this KPQ lobby";
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
    void aCompanionStillFinishingSomethingIsAnsweredWithTheLineupAgainAndTheNextYesGoesIn() {
        fx.doors.refusal = "Tobi could not leave its current activity"; // live: HappyWolf at 19:56, Rurik at 19:58
        fx.ready();

        assertNull(fx.yes());

        assertEquals(List.of("still finishing: Give Tobi a sec, they're finishing something."),
                game.calls.stream().filter(call -> call.startsWith("still") || call.startsWith("answer")).toList());
        assertEquals(List.of(ANN, 900, 901, 902), List.copyOf(fx.shownAgain.memberIds()), "the same lineup");
        assertTrue(fx.shownAgain.startable(), "Yes is one click away");
        assertEquals(RosterState.ACTIVE, game.row(casse).state(), "called back meanwhile");

        fx.doors.refusal = null; // its step is done
        fx.service.start(game.ann, fx.shownAgain);

        assertEquals(2, game.calls.stream().filter(call -> call.startsWith("admit")).count());
        assertTrue(game.calls.contains("start"));
        assertTrue(fx.service.runForSession(fx.doors.session.sessionId()).started);
    }

    @Test
    void aCompanionStillFinishingSomethingIsNeverWaitedFor() {
        fx.doors.refusal = "Tobi could not leave its current activity";
        fx.ready();
        List<Runnable> ops = fx.holdCompanionOps();
        fx.io = new ArrayList<>();

        fx.yes(); // Ann's click returns at once: the extras step out on companion-ops
        CompanionPqFixture.drain(ops);
        CompanionPqFixture.drain(fx.io); // admission, refused, and the answer: one pass on Ann's I/O thread
        CompanionPqFixture.drain(ops); // the extras come back

        assertEquals(1, game.calls.stream().filter(call -> call.startsWith("admit")).count(), "no retry loop");
        assertTrue(game.calls.contains("still finishing: Give Tobi a sec, they're finishing something."));
        assertEquals(List.of(), game.timers, "no timer to try again");
        assertEquals(List.of(), fx.io, "nothing left to run on Ann's I/O thread");
        assertEquals(List.of(), ops);
        assertEquals(List.of("Casse", "Hadrik"), fx.shownAgain.extras().stream().map(LineupPlan.Seat::name).toList(),
                "the lineup Ann said Yes to, though its extras were still out of the party as it was answered");
        assertEquals(List.of("low on potions", "Lv32, too seasoned for this"),
                fx.shownAgain.waiting().stream().map(LineupPlan.Waiting::reason).toList());
    }

    @Test
    void aYesWhileItIsStillAtItIsAnsweredAtOnceAndTheExtrasStayPut() {
        fx.doors.refusal = "Tobi could not leave its current activity";
        fx.ready();
        fx.yes();
        fx.doors.finishing.add(901); // Tobi's step is not done yet
        game.calls.clear();
        fx.dialogue.clearInvocations();

        fx.service.start(game.ann, fx.shownAgain); // Ann clicks Yes again a few seconds later

        assertEquals(List.of("still finishing: Give Tobi a sec, they're finishing something."), game.calls,
                "nobody steps out and back, and admission is not asked again");
        assertEquals(RosterState.ACTIVE, game.row(casse).state());
        fx.dialogue.verify(() -> AgentDialogueTransportRuntime.sayMapNow(any(), anyString()), never());

        fx.doors.finishing.clear(); // done
        fx.doors.refusal = null;
        fx.service.start(game.ann, fx.shownAgain);

        assertTrue(game.calls.contains("start"), game.calls.toString());
    }

    @Test
    void aYesHalfAMinuteLaterOrWithoutItAsksAdmissionAgain() {
        fx.doors.refusal = "Tobi could not leave its current activity";
        fx.ready();
        fx.doors.finishing.add(901); // something keeps it busy all along
        fx.yes();
        long now = System.currentTimeMillis();

        assertEquals("Give Tobi a sec, they're finishing something.",
                fx.service.stillFinishing(ANN, fx.shownAgain, now));
        assertNull(fx.service.stillFinishing(ANN, fx.service.plan(game.ann, "kpq", Set.of(900, 902)), now),
                "a lineup without Tobi");
        fx.yes(); // refused again, and noted again
        assertNull(fx.service.stillFinishing(ANN, fx.shownAgain, now + CompanionPartyQuestService.FINISHING_MS + 1_000L));
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
    void aYesThatThrowsLetsTheNextYesThrough() {
        fx.ready();
        LineupPlan seen = fx.service.plan(game.ann, "kpq");
        Inventory use = game.bots.get(901).getInventory(InventoryType.USE);
        when(use.getNextFreeSlot()).thenThrow(new IllegalStateException("Tobi's bags are being saved"));

        assertThrows(IllegalStateException.class, () -> fx.service.start(game.ann, seen)); // LivingTalk logs it

        doReturn((short) 0).when(use).getNextFreeSlot();
        fx.service.start(game.ann, seen);
        assertFalse(game.calls.contains("answer Hold on, they're lining up."), "the Yes that threw is over");
        assertTrue(game.calls.contains("admit [Ann, Mira, Tobi, Pip]"));
    }

    @Test
    void aHoldThatThrowsCallsTheOthersBackAndLetsTheNextYesThrough() {
        fx.ready();
        List<Runnable> ops = fx.holdCompanionOps();
        fx.dialogue.when(() -> AgentDialogueTransportRuntime.sayMapNow(eq(game.bot(hadrik)), anyString()))
                .thenThrow(new IllegalStateException("map chat is down"));
        fx.yes();

        assertThrows(IllegalStateException.class, () -> CompanionPqFixture.drain(ops)); // companion-ops logs it

        assertTrue(game.calls.contains("answer " + LakelisLines.GENERIC));
        assertFalse(game.calls.stream().anyMatch(call -> call.startsWith("admit")));
        CompanionPqFixture.drain(ops);
        assertEquals(RosterState.ACTIVE, game.row(casse).state(), "the one that stepped out comes back");
        assertEquals(RosterState.ACTIVE, game.row(hadrik).state());
        game.calls.clear();
        fx.yes();
        assertFalse(game.calls.contains("answer Hold on, they're lining up."), "the Yes that threw is over");
    }

    @Test
    void aCompanionShortOfManaAsksForSomeOnceAsTheLineupIsShown() {
        fx.manaPotions.put(901, 0); // Tobi, a Page: live, no MP_POTION supplier inside the run
        fx.ready();
        game.config.COMPANION_PQ_ENABLED_KEYS = "kpq";
        for (int id : List.of(900, 901, 902, 903, 904)) {
            when(game.world.directory().ownerOfCharacter(id)).thenReturn(ANN);
        }
        Client client = game.ann.getClient();
        when(client.onIoThread()).thenReturn(true); // the click on Lakelis, on its own thread
        when(client.getCM()).thenReturn(mock(NPCConversationManager.class));
        List<Runnable> ops = fx.holdCompanionOps();

        assertTrue(fx.service.offerAtDesk(game.ann, "kpq"));
        assertTrue(fx.service.offerAtDesk(game.ann, "kpq")); // and once more a moment later

        assertEquals(List.of("proposal", "proposal"), game.calls);
        assertEquals(List.of(), game.partyChat, "said on companion-ops, not on Ann's I/O thread");
        CompanionPqFixture.drain(ops);
        assertEquals(1, game.partyChat.size(), "asked once: " + game.partyChat);
        String asked = game.partyChat.getFirst();
        assertTrue(asked.startsWith("Tobi: ")
                && AgentDialogueCatalog.potRequestMpReplies().contains(asked.substring("Tobi: ".length())), asked);
    }

    @Test
    void herOwnChoiceIsPlannedAgainByHerOwnChoice() {
        fx.potions.put(903, 50); // Casse carries potions this time: four can go and Pip is left over
        fx.ready();
        LineupPlan seen = fx.service.plan(game.ann, "kpq");
        assertEquals(Set.of(ANN, 900, 901, 903), seen.memberIds());
        Inventory miras = game.bots.get(900).getInventory(InventoryType.USE);
        when(miras.getNextFreeSlot()).thenReturn((short) -1); // Mira's bags filled since

        fx.service.start(game.ann, seen);

        assertEquals(List.of("things changed"), game.calls);
        assertEquals(Set.of(ANN, 901, 902, 903), fx.shownAgain.memberIds(), "Pip takes the seat, as a new one would");
        assertTrue(fx.shownAgain.startable());
        assertNull(fx.shownAgain.picked(), "still her own choice");
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
