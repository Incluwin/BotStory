package server.living.roster;

import client.Character;
import config.LivingWorldConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;
import server.agents.auth.AgentClaimGuard;
import server.agents.capabilities.dialogue.AgentPendingChatActionFlow;
import server.agents.commands.AgentReplyChannel;
import server.agents.runtime.AgentInteractionRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.LivingWorld;
import server.living.bond.ContractService;
import server.living.config.Content;
import server.living.config.Content.Look;
import server.living.config.Content.Persona;
import server.living.config.Content.Quest;
import server.living.config.Content.Step;
import server.living.config.LivingContent;
import server.living.resident.ResidentDialogue;
import server.living.resident.ResidentRuntime;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CompanionChatVerbs (docs/BOT_MANAGEMENT_PLAN.md P2a): the roster's chat commands and the Agent routes' dismiss and
 * confirmed logout all reach RosterService on companion-ops; "dismiss" and "park" bench; beginEscort takes a seat and
 * counts toward ROSTER_MAX or is refused in character.
 */
class CompanionChatVerbsTest {
    private static final int ALICE = 10;
    private static final int OLWEN_CHARACTER = 900;
    private static final int PROBE_CHARACTER = 960;
    private static final Persona OLWEN = persona("olwen", "Olwen");
    private static final Persona BRIN = persona("brin", "Brin");

    private final LivingWorld world = mock(LivingWorld.class);
    private final KeystoneErrands keystones = mock(KeystoneErrands.class);
    private final TicketErrands tickets = mock(TicketErrands.class);
    private final RosterService roster = mock(RosterService.class);
    private final ContractService contracts = mock(ContractService.class);
    private final ResidentRuntime residents = mock(ResidentRuntime.class);
    private final LivingContent content = mock(LivingContent.class);
    private final CompanionExecutor executor = new CompanionExecutor();
    private final Character alice = owner(ALICE, "Alice");
    /** "verb persona thread", in the order the verbs ran. */
    private final List<String> ran = new CopyOnWriteArrayList<>();
    private CompanionChatVerbs verbs;
    private ResidentDialogue dialogue;

    @BeforeEach
    void wireWorld() {
        when(world.roster()).thenReturn(roster);
        when(world.contracts()).thenReturn(contracts);
        when(world.residents()).thenReturn(residents);
        when(world.content()).thenReturn(content);
        when(world.executor()).thenReturn(executor);
        verbs = new CompanionChatVerbs(world);
        when(world.chatVerbs()).thenReturn(verbs);
        dialogue = new ResidentDialogue(world);

        for (Persona persona : List.of(OLWEN, BRIN)) {
            when(content.personaByName(persona.id())).thenReturn(persona); // chat is normalized to lower case
            when(content.personaByName(persona.name())).thenReturn(persona); // a whisper names the character
            when(content.persona(persona.id())).thenReturn(persona);
            when(roster.ownerOf(persona.id())).thenReturn(ALICE);
            when(roster.cachedOwnerOf(persona.id())).thenReturn(ALICE);
        }
        when(roster.entry(ALICE, "olwen")).thenReturn(row("olwen", RosterState.ACTIVE));
        when(roster.entry(ALICE, "brin")).thenReturn(row("brin", RosterState.BENCHED));
        when(roster.roster(alice)).thenReturn(List.of(row("olwen", RosterState.ACTIVE), row("brin", RosterState.BENCHED)));
        when(roster.hasCompanions(alice)).thenReturn(true);
        when(roster.onlineCharacter(ALICE)).thenReturn(alice);
        when(residents.personaOfCharacterId(OLWEN_CHARACTER)).thenReturn(OLWEN);

        when(roster.call(eq(alice), any())).thenAnswer(inv -> record("call", inv.getArgument(1)));
        when(roster.bench(eq(alice), any())).thenAnswer(inv -> record("bench", inv.getArgument(1)));
        when(roster.swap(eq(alice), any(), any())).thenAnswer(inv -> record("swap", inv.getArgument(1)));
        when(roster.setCamp(eq(alice), any(), anyInt())).thenAnswer(inv -> record("camp", inv.getArgument(1)));
        when(roster.assign(eq(alice), any(), any(), anyInt(), anyInt())).thenAnswer(inv -> record("assign", inv.getArgument(1)));
        when(roster.delegate(eq(alice), any(), any(), anyInt(), anyInt(), anyInt())).thenAnswer(inv -> record("delegate", inv.getArgument(1)));
        when(roster.haulSummary(eq(alice), any())).thenAnswer(inv -> List.of(line("haul", inv.getArgument(1))));
        when(roster.handOver(eq(alice), any())).thenAnswer(inv -> line("hand_over", inv.getArgument(1)));
        when(roster.sendIt(eq(alice), any())).thenAnswer(inv -> line("send", inv.getArgument(1)));
        when(roster.bankIt(eq(alice), any())).thenAnswer(inv -> line("bank", inv.getArgument(1)));
        when(roster.wagesStatus(eq(alice), any())).thenAnswer(inv -> line("wages", inv.getArgument(1)));
        when(roster.payWages(eq(alice), any(), anyLong())).thenAnswer(inv -> line("pay_wages", inv.getArgument(1)));
        when(roster.summary(alice)).thenAnswer(inv -> {
            ran.add("summary - " + Thread.currentThread().getName());
            return List.of("Roster 2/10");
        });
        when(contracts.dismiss(eq(alice), any())).thenAnswer(inv -> line("release", inv.getArgument(1)));
        when(world.keystoneErrands()).thenReturn(keystones);
        when(keystones.ask(eq(alice), any())).thenAnswer(inv -> line("keystone", inv.getArgument(1)));
        when(world.ticketErrands()).thenReturn(tickets);
        when(tickets.ask(eq(alice), any())).thenAnswer(inv -> line("ticket", inv.getArgument(1)));
    }

    @AfterEach
    void stop() {
        executor.shutdown();
    }

    // ---- the roster's chat commands ------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "roster,                     summary -",
            "call brin,                  call brin",
            "bench olwen,                bench olwen",
            "dismiss olwen,              bench olwen",
            "dismiss to camp olwen,      bench olwen",
            "olwen dismiss,              bench olwen",
            "park olwen,                 bench olwen",
            "olwen park,                 bench olwen",
            "park,                       bench olwen",
            "everyone park,              bench olwen",
            "everyone bench,             bench olwen",
            "call the usual,             call brin",
            "call everyone,              call brin",
            "call all,                   call brin",
            "call the crew,              call brin",
            "summon everybody,           call brin",
            "swap olwen for brin,        swap olwen",
            "release olwen,              release olwen",
            "olwen go home,              release olwen",
            "olwen camp here,            camp olwen",
            "olwen train,                assign olwen",
            "olwen go farm meso here,    delegate olwen",
            "olwen haul,                 haul olwen",
            "olwen hand it over,         hand_over olwen",
            "olwen send it,              send olwen",
            "olwen bank it,              bank olwen",
            "olwen wages,                wages olwen",
            "olwen pay wages 5k,         pay_wages olwen",
            "olwen get your keystone,    keystone olwen",
            "get a keystone olwen,       keystone olwen",
            "brin keystone,              keystone brin",
            "olwen get your ticket,      ticket olwen",
            "get an entrance ticket olwen, ticket olwen",
            "brin ticket,                ticket brin",
    })
    void everyRosterChatCommandRunsItsVerbOnCompanionOps(String said, String verb) throws Exception {
        assertTrue(dialogue.onMapChat(alice, said), "the roster takes '" + said + "'");
        drain();

        assertEquals(List.of(verb + " " + CompanionExecutor.THREAD_NAME), ran);
        verify(alice).dropMessage(anyInt(), anyString());
    }

    @Test
    void everyoneGetKeystonesAsksEachCompanionOutOrBenched() throws Exception {
        assertTrue(dialogue.onMapChat(alice, "everyone get keystones"));
        drain();

        assertEquals(List.of("keystone olwen " + CompanionExecutor.THREAD_NAME, "keystone brin " + CompanionExecutor.THREAD_NAME), ran);
    }

    @Test
    void everyoneGetTicketsAsksEachCompanionOutOrBenched() throws Exception {
        assertTrue(dialogue.onMapChat(alice, "everyone get tickets"));
        drain();

        assertEquals(List.of("ticket olwen " + CompanionExecutor.THREAD_NAME, "ticket brin " + CompanionExecutor.THREAD_NAME), ran);
    }

    @Test
    void aWhisperedDismissBenchesToo() throws Exception {
        assertTrue(dialogue.onWhisper(alice, "Olwen", "dismiss"));
        drain();

        assertEquals(List.of("bench olwen " + CompanionExecutor.THREAD_NAME), ran);
        verify(contracts, never()).dismiss(any(), any());
    }

    @Test
    void theChatThreadOnlyParsesTheVerb() throws Exception {
        // companion-ops is busy: the chat line is taken at once and the bench waits its turn
        CountDownLatch busy = new CountDownLatch(1);
        executor.submit("busy", () -> {
            try {
                busy.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });

        assertTrue(dialogue.onMapChat(alice, "bench olwen"));
        assertEquals(List.of(), ran, "nothing ran on the chat thread");

        busy.countDown();
        drain();
        assertEquals(List.of("bench olwen " + CompanionExecutor.THREAD_NAME), ran);
    }

    @Test
    void bareParkIsOnlyAVerbForSomeoneWithCompanions() {
        Character stranger = owner(30, "Bob");
        assertFalse(dialogue.onMapChat(stranger, "park"));
    }

    @Test
    void anOwnerWhoLoggedOffMeanwhileGetsNothing() throws Exception {
        when(alice.isLoggedin()).thenReturn(false);

        verbs.say(alice, "roster.bench", () -> RosterVoice.line(OLWEN, roster.bench(alice, OLWEN))).get(5, TimeUnit.SECONDS);

        assertEquals(List.of(), ran);
        verify(alice, never()).dropMessage(anyInt(), anyString());
    }

    // ---- untargeted "logout" + "yes" ------------------------------------------------------

    @Test
    void anUntargetedLogoutThenYesParksEveryoneOutAndNothingElseHearsTheYes() throws Exception {
        assertTrue(dialogue.onMapChat(alice, "logout"), "the roster asks, not each companion's Agent route");
        verify(residents).reply(eq(OLWEN), eq(alice), anyString());
        assertTrue(dialogue.onMapChat(alice, "yes"), "no Resident, citizen or Agent offer answers it first");
        drain();

        assertEquals(List.of("bench olwen " + CompanionExecutor.THREAD_NAME), ran);
        verify(alice).dropMessage(anyInt(), anyString());
    }

    @Test
    void theLogoutQuestionExpiresIsDroppedByOtherChatAndANewerLogoutAsksAgain() throws Exception {
        long[] now = {1_000_000L};
        verbs = new CompanionChatVerbs(world, () -> now[0]);
        when(world.chatVerbs()).thenReturn(verbs);

        assertTrue(dialogue.onMapChat(alice, "logout"));
        now[0] += CompanionChatVerbs.LOGOUT_CONFIRM_MS + 1;
        assertFalse(dialogue.onMapChat(alice, "yes"), "too late: an ordinary yes");

        assertTrue(dialogue.onMapChat(alice, "logout"));
        assertFalse(dialogue.onMapChat(alice, "one sec"));
        assertFalse(dialogue.onMapChat(alice, "yes"), "other chat dropped the question");

        assertTrue(dialogue.onMapChat(alice, "logout"));
        assertTrue(dialogue.onMapChat(alice, "logout"), "a newer logout asks again instead of cancelling");
        assertTrue(dialogue.onMapChat(alice, "yes"));
        drain();

        assertEquals(List.of("bench olwen " + CompanionExecutor.THREAD_NAME), ran);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"yeah", "ok", "Yes!", "'sure, go ahead'"})
    void aConfirmNormalizingWouldDropStillParksEveryoneOut(String confirm) throws Exception {
        assertTrue(dialogue.onMapChat(alice, "logout"));
        assertTrue(dialogue.onMapChat(alice, confirm), "the raw line is read: '" + confirm + "' is a yes");
        drain();

        assertEquals(List.of("bench olwen " + CompanionExecutor.THREAD_NAME), ran);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"no", "nah", "nvm", "yes nvm", "'sure, nvm'", "yes... actually no"})
    void aNoToTheLogoutIsAnsweredByTheCompanionThatAskedAndBenchesNobody(String answer) throws Exception {
        assertTrue(dialogue.onMapChat(alice, "logout"));
        assertTrue(dialogue.onMapChat(alice, answer), "a no is tested first and goes no further");
        assertFalse(dialogue.onMapChat(alice, "yes"), "the question is gone");
        drain();

        assertEquals(List.of(), ran);
        verify(residents).reply(OLWEN, alice, AgentPendingChatActionFlow.pendingActionCancelReply(false));
    }

    @Test
    void aConfirmedLogoutAlsoTurnsBackACompanionOnItsWay() throws Exception {
        when(roster.travelMsLeft(ALICE, "brin")).thenReturn(40_000L); // "call the usual" just now: benched, on its way

        assertTrue(dialogue.onMapChat(alice, "logout"));
        assertTrue(dialogue.onMapChat(alice, "yes"));
        drain();

        assertEquals(List.of("bench olwen " + CompanionExecutor.THREAD_NAME, "bench brin " + CompanionExecutor.THREAD_NAME),
                ran);
    }

    @Test
    void anOwnerWhoAlsoLeadsAPlainAgentHasTheLogoutAndItsYesGoStraightToTheAgentRoutes() {
        AgentRuntimeEntry probe = new AgentRuntimeEntry(owner(PROBE_CHARACTER, "Probe"), alice, null);
        CompanionDirectory directory = mock(CompanionDirectory.class); // claims nobody: a GM's own Agent
        when(world.directory()).thenReturn(directory);
        try (MockedStatic<AgentRuntimeRegistry> registry = mockStatic(AgentRuntimeRegistry.class);
             MockedStatic<AgentInteractionRuntime> agents = mockStatic(AgentInteractionRuntime.class)) {
            registry.when(() -> AgentRuntimeRegistry.entriesForLeader(ALICE)).thenReturn(List.of(probe));

            // every Agent asks for itself, as before; no Resident or citizen hears the "yes" first
            assertTrue(dialogue.onMapChat(alice, "logout"));
            assertTrue(dialogue.onMapChat(alice, "Yeah"));
            assertFalse(dialogue.onMapChat(alice, "yes"), "answered: an ordinary yes");

            agents.verify(() -> AgentInteractionRuntime.handleLeaderChat(alice, "logout", AgentReplyChannel.MAP));
            agents.verify(() -> AgentInteractionRuntime.handleLeaderChat(alice, "Yeah", AgentReplyChannel.MAP));
            agents.verify(() -> AgentInteractionRuntime.handleLeaderChat(any(), anyString(), any()), times(2));
        }
        verify(residents, never()).reply(any(), any(), anyString());
    }

    @Test
    void callTheUsualSaysTheLeaderCapOnce() throws Exception {
        when(roster.roster(alice)).thenReturn(List.of(row("olwen", RosterState.BENCHED), row("brin", RosterState.BENCHED)));
        when(roster.call(eq(alice), any())).thenAnswer(inv -> {
            record("call", inv.getArgument(1));
            return new RosterResult.LeaderCapped();
        });

        assertTrue(dialogue.onMapChat(alice, "call the usual"));
        drain();

        assertEquals(2, ran.size(), "each is still asked: one holding its own seat would come");
        verify(alice).dropMessage(5, RosterVoice.line(OLWEN, new RosterResult.LeaderCapped()));
        verify(alice).dropMessage(anyInt(), anyString());
    }

    // ---- the Agent routes' dismiss and confirmed logout ---------------------------------

    @Test
    void aRosteredCompanionsDismissOrLogoutIsTakenAndBenchedByItsOwner() throws Exception {
        for (AgentClaimGuard.Verb verb : AgentClaimGuard.Verb.values()) {
            assertTrue(verbs.take(alice, OLWEN_CHARACTER, verb), verb.name());
        }
        drain();

        assertEquals(List.of("bench olwen " + CompanionExecutor.THREAD_NAME, "bench olwen " + CompanionExecutor.THREAD_NAME), ran);
    }

    @Test
    void aPlainAgentOrAnUnownedResidentKeepsTheAgentRoute() throws Exception {
        assertFalse(verbs.take(alice, PROBE_CHARACTER, AgentClaimGuard.Verb.DISMISS), "no persona: a GM-spawned Agent");
        Persona resident = persona("hadrik", "Hadrik");
        when(residents.personaOfCharacterId(901)).thenReturn(resident);
        assertFalse(verbs.take(alice, 901, AgentClaimGuard.Verb.LOGOUT), "a Resident nobody owns");
        drain();

        assertEquals(List.of(), ran);
    }

    @Test
    void anOfflineOwnersCompanionIsStillNeverUnloadedByTheAgentRoute() throws Exception {
        when(roster.onlineCharacter(ALICE)).thenReturn(null);

        assertTrue(verbs.take(alice, OLWEN_CHARACTER, AgentClaimGuard.Verb.LOGOUT));
        drain();

        assertEquals(List.of(), ran);
    }

    // ---- beginEscort ---------------------------------------------------------------------

    @Nested
    class Escort {
        private final FakeLivingStore rows = new FakeLivingStore();
        private final LivingWorldConfig config = new LivingWorldConfig();
        private final Quest quest = mock(Quest.class);
        private RosterService real;

        @BeforeEach
        void realRoster() {
            when(world.store()).thenReturn(rows.store);
            when(world.config()).thenReturn(config);
            when(world.slots()).thenReturn(new CompanionSlots(world));
            when(world.benchSessions()).thenReturn(mock(BenchSessions.class));
            when(world.benchDuty()).thenReturn(mock(BenchDutyDispatcher.class));
            when(world.arcs()).thenReturn(mock(server.living.arc.ArcService.class));
            CompanionBudget budget = mock(CompanionBudget.class);
            when(budget.admit(any(), any(Character.class))).thenReturn(CompanionBudget.Admission.ALLOW);
            when(world.budget()).thenReturn(budget);
            when(quest.id()).thenReturn("lith-crates");
            when(quest.title()).thenReturn("Crates for the Dock");
            real = new RosterService(world);
            when(world.roster()).thenReturn(real);
        }

        @Test
        void anEscortTakesAnAwaySeatAndARosterRow() {
            RosterResult result = real.beginEscort(alice, OLWEN, quest, mock(Step.class));

            // the character cannot be loaded in a unit test, so the call fails after the row and the seat are in place
            assertInstanceOf(RosterResult.Failed.class, result);
            assertEquals(RosterState.AWAY, rows.row(ALICE, "olwen").state());
            CompanionSlots.Reservation seat = world.slots().reservation("olwen");
            assertEquals(ALICE, seat.ownerId());
            assertEquals(CompanionSlots.Kind.AWAY, seat.kind());
            assertEquals(1, real.roster(alice).size(), "it counts toward ROSTER_MAX");
            assertEquals(4, real.freeSlots(alice), "and holds one of the owner's five seats");
        }

        @Test
        void aFullRosterRefusesTheEscortInCharacter() {
            config.ROSTER_MAX = 1;
            rows.seed(new RosterEntry(ALICE, "brin", RosterState.BENCHED, Assignment.TRAIN, 0, 0, 104000000,
                    0L, 0L, 0L, 1L, 1L, 1L, 0, null));

            RosterResult result = real.beginEscort(alice, OLWEN, quest, mock(Step.class));

            assertEquals(new RosterResult.RosterFull(1), result);
            assertNull(rows.row(ALICE, "olwen"), "no row past ROSTER_MAX");
            assertNull(world.slots().reservation("olwen"));
            verify(residents).whisper(OLWEN, alice, "you've got a full crew already. i'll find my own way.");
            assertEquals(1, rows.events("roster.escort_refused").size());
        }

        @Test
        void aFullPartyRefusesTheEscortInCharacter() {
            for (int seat = 0; seat < 5; seat++) {
                world.slots().reserve(ALICE, "held" + seat, CompanionSlots.Kind.AWAY, Long.MAX_VALUE);
            }

            RosterResult result = real.beginEscort(alice, OLWEN, quest, mock(Step.class));

            assertEquals(new RosterResult.PartyFull(), result);
            assertNull(rows.row(ALICE, "olwen"));
            assertNull(world.slots().reservation("olwen"));
            verify(residents).whisper(OLWEN, alice, "your party's full. i'll find my own way.");
        }

        @Test
        void anOwnedPersonaIsNoEscort() {
            rows.seed(new RosterEntry(20, "olwen", RosterState.BENCHED, Assignment.TRAIN, 0, 0, 104000000,
                    0L, 0L, 0L, 1L, 1L, 1L, 0, null));

            assertNull(real.beginEscort(alice, OLWEN, quest, mock(Step.class)));
            assertEquals(20, real.ownerOf("olwen"));
            assertNull(world.slots().reservation("olwen"));
        }
    }

    // ---- helpers -------------------------------------------------------------------------

    private RosterResult record(String verb, Persona persona) {
        ran.add(verb + " " + persona.id() + " " + Thread.currentThread().getName());
        return new RosterResult.Ok(verb);
    }

    private String line(String verb, Persona persona) {
        ran.add(verb + " " + persona.id() + " " + Thread.currentThread().getName());
        return verb;
    }

    /** companion-ops is serial: once this runs, everything submitted before it has. */
    private void drain() throws Exception {
        executor.submit("drain", () -> { }).get(5, TimeUnit.SECONDS);
    }

    private static RosterEntry row(String personaId, RosterState state) {
        return new RosterEntry(ALICE, personaId, state, Assignment.TRAIN, 0, 0, 104000000, 0L, 0L, 0L, 1L, 1L, 1L, 0, null);
    }

    private static Character owner(int id, String name) {
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(id);
        when(character.getName()).thenReturn(name);
        when(character.isLoggedin()).thenReturn(true);
        when(character.getMapId()).thenReturn(104000000);
        return character;
    }

    static Persona persona(String id, String name) {
        return new Persona(id, name, "", "BEGINNER", new Content.LevelBand(1, 1), Content.Strength.ROOKIE, null,
                "relaxed-v1", null, "lith-harbor", List.of(), false, null, List.of(), null, List.of(), List.of(), Map.of(),
                -1, -1, -1, List.of(), 0, Look.NONE, "test");
    }
}
