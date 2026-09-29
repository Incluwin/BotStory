package server.living.roster;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import server.agents.auth.AgentClaimGuard;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.bond.BondService;
import server.living.bond.ContractService;
import server.living.config.Content;
import server.living.config.Content.Contract;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.population.Citizen;
import server.living.population.FieldCatalog;
import server.living.population.Population;
import server.living.population.PopulationContent;
import server.living.population.PopulationContent.Field;
import server.living.population.PresenceScheduler;
import server.living.population.VoicePacks;
import server.living.resident.ResidentLooks;
import server.living.resident.ResidentRuntime;
import server.living.store.LivingStore.CharacterRow;
import server.living.store.LivingStore.PersonaState;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;
import static server.living.roster.LifecycleHarness.ANN;
import static server.living.roster.LifecycleHarness.HENESYS;
import static server.living.roster.LifecycleHarness.HENESYS_FIELD;
import static server.living.roster.LifecycleHarness.KERNING;

/**
 * Hired hands (docs/BOT_MANAGEMENT_PLAN.md P4, flows 9 and 10): a citizen's synthetic {@code hire:<characterId>} persona
 * comes from its own characters row and resolves through ensureCharacter without provisioning or a new look; a citizen
 * standing in its owner's party as their guest is adopted where it stands (no despawn, no party churn, the same seat);
 * an offline one is called from where it is; two owners hiring one citizen at once cannot both win; and a hire let go
 * (by its owner or its owner's deletion) goes back to the population.
 */
class HiredHandsTest {
    private static final int MIRA = 950;
    private static final String HIRE = "hire:950";
    private static final int BOB = 20;
    private static final int HUNTER = 310;
    private static final CharacterRow ROW = new CharacterRow("Mira", 1, 2, 21001, 31002, 30, HUNTER, KERNING);

    private final LifecycleHarness game = new LifecycleHarness();
    private final Population population = mock(Population.class);
    private final PresenceScheduler presence = mock(PresenceScheduler.class);
    private final Map<Integer, Citizen> citizens = new HashMap<>();
    /** "characterId keepLoaded" per hand-over the population was asked for. */
    private final List<String> handOvers = new ArrayList<>();
    private final int[] purse = {100_000};
    private final HiredHands hands = new HiredHands(game.world);
    private final Character mira = game.bot(MIRA, "Mira");
    private MockedStatic<Population> populations;

    @BeforeEach
    void wire() {
        populations = mockStatic(Population.class);
        populations.when(Population::get).thenReturn(population);
        when(population.citizen(anyInt())).thenAnswer(inv -> citizens.get((int) inv.getArgument(0)));
        doAnswer(inv -> citizens.put(((Citizen) inv.getArgument(0)).characterId(), inv.getArgument(0)))
                .when(population).save(any());
        when(population.presence()).thenReturn(presence);
        when(presence.handOver(anyInt(), anyBoolean(), anyString())).thenAnswer(inv -> {
            int id = inv.getArgument(0);
            citizens.computeIfPresent(id, (key, citizen) -> citizen.withFlag(Citizen.FLAG_HIRED));
            handOvers.add(id + " " + inv.getArgument(1));
            return game.loaded.contains(id);
        });
        FieldCatalog fields = mock(FieldCatalog.class);
        when(fields.homeTown(any(), any())).thenReturn(HENESYS);
        when(population.fields()).thenReturn(fields);
        PopulationContent places = mock(PopulationContent.class);
        when(places.field(anyInt())).thenAnswer(inv -> new Field(inv.getArgument(0), "somewhere", "", "victoria", false, 10, 20, 12));
        when(population.content()).thenReturn(places);
        VoicePacks voices = mock(VoicePacks.class);
        when(voices.render(any(), anyString(), anyLong())).thenAnswer(inv -> inv.getArgument(1));
        when(population.voices()).thenReturn(voices);
        citizens.put(MIRA, citizen(MIRA, "Mira"));
        when(mira.getLevel()).thenReturn(30);
        when(mira.getJob()).thenReturn(client.Job.HUNTER);

        when(game.store.store.characterRow(MIRA)).thenReturn(ROW);
        game.store.levels.put(MIRA, 30);
        game.store.jobs.put(MIRA, HUNTER);
        when(game.content.contract(LivingContent.HIRED_HAND_CONTRACT)).thenReturn(hiredHand());
        when(game.content.persona(HIRE)).thenAnswer(inv -> hands.persona(HIRE));
        when(game.world.hiredHands()).thenReturn(hands);
        when(game.world.bonds()).thenReturn(mock(BondService.class));
        when(game.world.contracts()).thenReturn(new ContractService(game.world));
        when(game.residents.ensureCharacter(argThat(persona -> persona != null && HIRE.equals(persona.id())))).thenReturn(MIRA);
        when(game.residents.characterIdOf(HIRE)).thenReturn(MIRA);
        when(game.residents.personaOfCharacterId(MIRA)).thenAnswer(inv -> game.roster().cachedOwnerOf(HIRE) != 0
                ? hands.persona(HIRE) : null);
        game.personaOfBot.put(MIRA, HIRE); // the harness's seat gate wants the hire's own seat
        when(game.ann.getMeso()).thenAnswer(inv -> purse[0]);
        doAnswer(inv -> {
            purse[0] += (int) inv.getArgument(0);
            return null;
        }).when(game.ann).gainMeso(anyInt(), anyBoolean());
        game.roster().installHiredHandRelease(hands::onReleased);
    }

    @AfterEach
    void close() {
        AgentClaimGuard.install(null);
        populations.close();
        game.close();
    }

    // ---- the synthetic persona ----------------------------------------------------------------

    @Test
    void aHiresPersonaIsItsOwnCharactersRowAndNeverContent() {
        Persona persona = hands.persona(HIRE);

        assertEquals("Mira", persona.name());
        assertEquals(new Content.Look("female", 2, 21001, 31002), persona.look());
        assertEquals(30, persona.level().min());
        assertEquals("HUNTER", persona.job());
        assertEquals(LivingContent.HIRED_HAND_CONTRACT, persona.contract());
        assertEquals(-1, persona.benchToleranceDays(), "a hire never walks out of the bench");
        assertEquals(0, persona.portraitNpcId(), "no rendered portrait: the Bulletin Board speaks for it");
        assertFalse(persona.recruitable());
        assertNull(hands.persona("hire:nobody"));
        assertNull(hands.persona("olwen"));

        LivingContent content = LivingContent.empty();
        LivingContent.installHireResolver(hands::persona);
        try {
            assertEquals(persona, content.persona(HIRE), "LivingContent.persona falls back to the installed resolver");
            assertTrue(content.personas().isEmpty(), "hire ids never enter content.personas()");
        } finally {
            LivingContent.installHireResolver(null);
        }
        assertNull(content.persona(HIRE), "without a resolver a hire id is nobody");
    }

    @Test
    void aHireResolvesThroughEnsureCharacterWithoutProvisioningOrANewLook() {
        try (MockedConstruction<ResidentLooks> looks = mockConstruction(ResidentLooks.class)) {
            ResidentRuntime residents = new ResidentRuntime(game.world);
            Persona persona = hands.persona(HIRE);

            assertEquals(MIRA, residents.ensureCharacter(persona));
            assertEquals(MIRA, residents.characterIdOf(HIRE));

            verifyNoInteractions(looks.constructed().get(0)); // no look is written for her
            verify(game.store.store, never()).savePersonaState(any()); // nothing provisioned
            assertNull(residents.personaOfCharacterId(MIRA), "nobody employs her yet: a citizen, no persona");
        }
    }

    // ---- adoption in place ---------------------------------------------------------------------

    @Test
    void adoptingALoadedGuestNeitherDespawnsItNorMovesItsPartySeat() {
        miraIsAnnsGuest();
        int freeBefore = game.slots.free(game.ann);

        HiredHands.Outcome outcome = hands.hire(game.ann, MIRA, hands.terms(MIRA));

        assertTrue(outcome.hired(), outcome.toString());
        assertEquals("deal. let's go.", outcome.line());
        assertEquals(RosterState.ACTIVE, game.store.row(ANN, HIRE).state());
        assertEquals(List.of("stop Mira", "register Mira", "follow Mira"), game.calls,
                "no despawn, no spawn, no leave or join: only the runtime moves to Ann");
        assertTrue(game.members.contains(MIRA), "still in the party");
        assertEquals(List.of(), game.partySizes, "no party churn at all");
        assertNull(game.slots.reservation(CompanionSlots.characterKey(MIRA)), "her guest seat is her companion seat now");
        assertEquals(freeBefore, game.slots.free(game.ann));
        assertEquals(ANN, AgentRuntimeRegistry.leaderIdForAgentCharacter(MIRA));
        assertEquals(new CompanionLeases.Lease(CompanionLeases.Kind.COMPANION, ANN), game.leases.holder(MIRA));
        assertEquals(List.of(MIRA + " true"), handOvers, "her population life ends without an unload");
        verify(presence, never()).logout(any(), anyString());
        assertTrue(citizens.get(MIRA).hired());
        assertEquals(new PersonaState(HIRE, MIRA, null, 0, ANN), game.store.personaStates.get(HIRE));
        assertEquals(70_000, purse[0], "Lv30 x 1,000 meso");
        assertEquals(1, game.store.events(HiredHands.EVENT_ACCEPT).size());
    }

    @Test
    void theClaimGuardDecidesTheAdoptionAndARefusalChangesNothing() {
        miraIsAnnsGuest();
        List<Integer> asked = new ArrayList<>();
        AgentClaimGuard.install(new AgentClaimGuard.Rule() {
            @Override
            public boolean mayControl(Character actor, int agentCharacterId) {
                return true;
            }

            @Override
            public boolean mayLoad(Character actor, int agentCharacterId) {
                asked.add(agentCharacterId);
                return false;
            }

            @Override
            public boolean isClaimed(int agentCharacterId) {
                return true;
            }
        });

        HiredHands.Outcome outcome = hands.hire(game.ann, MIRA, hands.terms(MIRA));

        assertEquals(HiredHands.Result.BUSY, outcome.result());
        assertEquals(List.of(MIRA), asked);
        assertNull(game.store.row(ANN, HIRE));
        assertFalse(game.roster().isOwned(HIRE));
        assertNull(game.leases.holder(MIRA));
        assertEquals(List.of(), handOvers, "the population never let go of her");
        assertFalse(citizens.get(MIRA).hired());
        assertEquals(List.of(), game.calls);
        assertTrue(game.slots.reservation(CompanionSlots.characterKey(MIRA)) != null, "still Ann's guest");
        assertEquals(100_000, purse[0], "the fee went back");
    }

    @Test
    void aCitizenLedByAnyoneButThePopulationIsNotTaken() {
        miraIsAnnsGuest();
        game.registry.when(() -> AgentRuntimeRegistry.leaderIdForAgentCharacter(MIRA)).thenReturn(4242);

        HiredHands.Outcome outcome = hands.hire(game.ann, MIRA, hands.terms(MIRA));

        assertEquals(HiredHands.Result.BUSY, outcome.result());
        assertNull(game.store.row(ANN, HIRE));
        assertEquals(List.of(), game.calls);
        assertEquals(100_000, purse[0]);
    }

    // ---- the call path --------------------------------------------------------------------------

    @Test
    void anOfflineCitizenIsCalledFromWhereItIsAndTheLookColumnsNeverChange() throws Exception {
        List<String> sql = new ArrayList<>();
        try (MockedConstruction<ResidentLooks> looks = mockConstruction(ResidentLooks.class,
                withSettings().defaultAnswer(CALLS_REAL_METHODS));
             MockedStatic<DatabaseConnection> db = mockStatic(DatabaseConnection.class)) {
            db.when(DatabaseConnection::getConnection).thenAnswer(inv -> {
                Connection connection = mock(Connection.class);
                when(connection.prepareStatement(anyString())).thenAnswer(prepare -> {
                    sql.add(prepare.getArgument(0));
                    return mock(PreparedStatement.class);
                });
                return connection;
            });
            ResidentRuntime residents = new ResidentRuntime(game.world);
            when(game.residents.ensureCharacter(argThat(persona -> persona != null && HIRE.equals(persona.id()))))
                    .thenAnswer(inv -> residents.ensureCharacter(inv.getArgument(0)));

            HiredHands.Outcome hired = hands.hire(game.ann, MIRA, hands.terms(MIRA));
            assertTrue(hired.hired(), hired.toString());
            assertEquals(List.of(MIRA + " false"), handOvers);
            RosterEntry row = game.store.row(ANN, HIRE);
            assertEquals(RosterState.TRAVELLING, row.state(), "join-then-travel from Kerning, where she logged off");
            assertEquals(KERNING, row.campMapId());
            assertTrue(game.calls.get(0).startsWith("spawn Mira at " + KERNING), game.calls.toString());
            assertTrue(game.calls.contains("join Mira"));
            game.runTimers();
            assertEquals(RosterState.ACTIVE, game.row(hands.persona(HIRE)).state());

            assertTrue(game.roster().bench(game.ann, hands.persona(HIRE)).ok());
            game.runTimers();
            assertEquals(RosterState.BENCHED, game.row(hands.persona(HIRE)).state());
            assertTrue(game.calls.contains("remove Mira"), game.calls.toString());
            assertTrue(game.roster().call(game.ann, hands.persona(HIRE)).ok());
            game.runTimers();
            assertEquals(RosterState.ACTIVE, game.row(hands.persona(HIRE)).state());

            assertTrue(sql.stream().noneMatch(statement -> statement.toUpperCase().startsWith("UPDATE CHARACTERS")),
                    "a hire never rewrites its characters row's look: " + sql);
            assertEquals("Mira", hands.persona(HIRE).name());
            assertEquals(new Content.Look("female", 2, 21001, 31002), hands.persona(HIRE).look());
        }
    }

    @Test
    void theFirstHireHandsOverTheAdventurersRoster() {
        miraIsAnnsGuest();
        game.config.ROSTER_BOOK_ITEM_ID = 2430011;
        when(game.ann.haveItem(2430011)).thenReturn(false);
        try (MockedStatic<client.inventory.manipulator.InventoryManipulator> inventory =
                     mockStatic(client.inventory.manipulator.InventoryManipulator.class)) {
            inventory.when(() -> client.inventory.manipulator.InventoryManipulator.checkSpace(any(), eq(2430011), eq(1), eq("")))
                    .thenReturn(true);
            inventory.when(() -> client.inventory.manipulator.InventoryManipulator.addById(any(), eq(2430011), eq((short) 1)))
                    .thenReturn(true);

            assertTrue(hands.hire(game.ann, MIRA, hands.terms(MIRA)).hired());

            inventory.verify(() -> client.inventory.manipulator.InventoryManipulator.addById(any(), eq(2430011), eq((short) 1)));
        }
        verify(game.residents).whisper(argThat(persona -> HIRE.equals(persona.id())), eq(game.ann),
                eq("double-click me or the book to manage the crew."));
    }

    // ---- one citizen, one employer --------------------------------------------------------------

    @Test
    void twoOwnersHiringTheSameCitizenAtOnceCannotBothWin() throws Exception {
        miraIsAnnsGuest();
        Character bob = mock(Character.class);
        when(bob.getId()).thenReturn(BOB);
        when(bob.getName()).thenReturn("Bob");
        HiredHands.Terms terms = hands.terms(MIRA);
        AtomicReference<HiredHands.Outcome> bobs = new AtomicReference<>();
        // Ann's hire reads Mira's row; right then Bob's hire runs on another thread
        when(game.store.store.characterRow(MIRA)).thenAnswer(inv -> {
            if (bobs.get() == null) {
                Thread other = new Thread(() -> bobs.set(hands.hire(bob, MIRA, terms)));
                other.start();
                other.join();
            }
            return ROW;
        });

        HiredHands.Outcome anns = hands.hire(game.ann, MIRA, terms);

        assertTrue(anns.hired(), anns.toString());
        assertEquals(HiredHands.Result.TAKEN, bobs.get().result());
        assertEquals("sorry, someone else is hiring me right now.", bobs.get().line());
        HiredHands.Outcome later = hands.hire(bob, MIRA, terms);
        assertEquals(HiredHands.Result.TAKEN, later.result(), "once hired, nobody else can");
        assertEquals(ANN, game.roster().ownerOf(HIRE));
        assertEquals(1, game.store.events(HiredHands.EVENT_ACCEPT).size());
    }

    // ---- no double load on hire (DoubleLoadGuardTest's rule for hires) --------------------------

    @Test
    void doubleLoadOnHireALoadedGuestIsNeverLoadedASecondTime() {
        miraIsAnnsGuest();
        try (MockedStatic<server.agents.integration.cosmic.CosmicAgentOfflineLoader> loader =
                     mockStatic(server.agents.integration.cosmic.CosmicAgentOfflineLoader.class)) {
            assertTrue(hands.hire(game.ann, MIRA, hands.terms(MIRA)).hired());
            RosterResult again = game.roster().call(game.ann, hands.persona(HIRE));

            assertEquals("Mira is already with you.", RosterVoice.line(hands.persona(HIRE), again));
            loader.verifyNoInteractions();
        }
        assertTrue(game.calls.stream().noneMatch(call -> call.startsWith("spawn") || call.startsWith("remove")), game.calls.toString());
        assertEquals(1, game.calls.stream().filter(call -> call.equals("register Mira")).count(), "registered once, under Ann");
    }

    @Test
    void doubleLoadOnHireACitizenLeasedForSomethingElseIsRefusedNotBlinked() {
        miraIsAnnsGuest();
        game.leases.tryAcquire(MIRA, CompanionLeases.Kind.PQ_BACKGROUND, 0);

        HiredHands.Outcome outcome = hands.hire(game.ann, MIRA, hands.terms(MIRA));

        assertEquals(HiredHands.Result.BUSY, outcome.result());
        assertEquals(new CompanionLeases.Lease(CompanionLeases.Kind.PQ_BACKGROUND, 0), game.leases.holder(MIRA));
        assertEquals(List.of(), handOvers);
        assertEquals(List.of(), game.calls);
        assertNull(game.store.row(ANN, HIRE));
        assertEquals(100_000, purse[0]);
    }

    @Test
    void doubleLoadOnHireABenchedHireLoadedMeanwhileBySomeoneElseIsNeverPulledOut() {
        game.store.seed(new RosterEntry(ANN, HIRE, RosterState.BENCHED, server.living.store.LivingStore.Assignment.TRAIN, 0, 0,
                KERNING, 0L, 0L, 0L, 1_000L, 1_000L, 1_000L, 0, null));
        RosterService roster = game.roster();
        game.loaded.add(MIRA); // a PQ lobby's, say
        game.registry.when(() -> AgentRuntimeRegistry.leaderIdForAgentCharacter(MIRA)).thenReturn(4242);
        game.registry.when(() -> AgentRuntimeRegistry.hasActiveAgentCharacterId(MIRA)).thenReturn(true);

        RosterResult result = roster.call(game.ann, hands.persona(HIRE));

        assertEquals(RosterResult.Busy.class, result.getClass());
        assertEquals(List.of(), game.calls, "nothing removed, nothing spawned beside Ann");
        assertEquals(RosterState.BENCHED, game.store.row(ANN, HIRE).state());
        assertNull(game.leases.holder(MIRA));
    }

    // ---- parting ways ---------------------------------------------------------------------------

    @Test
    void aHireLetGoGoesBackToThePopulation() {
        miraIsAnnsGuest();
        assertTrue(hands.hire(game.ann, MIRA, hands.terms(MIRA)).hired());

        String line = game.world.contracts().dismiss(game.ann, hands.persona(HIRE));

        assertEquals("Mira heads home to Henesys.", line);
        assertNull(game.store.row(ANN, HIRE));
        assertFalse(game.roster().isOwned(HIRE));
        assertFalse(citizens.get(MIRA).hired(), "the population takes her back; Churn counts her again");
        assertEquals(0, game.store.personaStates.get(HIRE).employedBy());
        assertNull(game.residents.personaOfCharacterId(MIRA), "a citizen again, no persona");
        assertEquals(1, game.store.events("roster.release").size());
        assertEquals(List.of(), game.store.events("resident.released"));
    }

    @Test
    void anOwnerDeletedGivesTheirHiresBackWithoutAResidentRelease() {
        miraIsAnnsGuest();
        assertTrue(hands.hire(game.ann, MIRA, hands.terms(MIRA)).hired());
        game.annOnline = false;

        game.roster().ownerDeleted(ANN);

        assertNull(game.store.row(ANN, HIRE));
        assertFalse(citizens.get(MIRA).hired());
        assertTrue(game.calls.contains("remove Mira"), game.calls.toString());
        assertEquals(List.of(ANN + " " + HIRE + " roster.release owner deleted"), game.store.events("roster.release"));
        assertEquals(List.of(), game.store.events("resident.released"));
    }

    // ---- helpers ----------------------------------------------------------------------------------

    /** Mira stands beside Ann in her party, a guest charged to Ann, and the population runs her. */
    private void miraIsAnnsGuest() {
        game.standsBesideAnn(mira);
        game.members.add(MIRA);
        assertTrue(game.slots.reserve(ANN, CompanionSlots.characterKey(MIRA), CompanionSlots.Kind.GUEST, Long.MAX_VALUE));
        assertNotNull(game.roster());
    }

    static Citizen citizen(int characterId, String name) {
        return new Citizen(characterId, name, "evening", 0, 0, "victoria", "hunter", "relaxed-v1", "default", 1.0d, 0, 1,
                new LinkedHashSet<>(), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
    }

    static Contract hiredHand() {
        return new Contract(LivingContent.HIRED_HAND_CONTRACT,
                new Content.OfferWhen(Content.BondStage.NONE, List.of(), null, null),
                new Content.Price(1_000L, List.of(), null),
                new Content.Terms(0, 0, Content.LootShare.OWN_DROPS, List.of(Content.LeaveRule.DISMISS), 0, 0L, 10), "test");
    }
}
