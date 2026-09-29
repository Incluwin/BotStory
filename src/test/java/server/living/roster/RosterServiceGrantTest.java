package server.living.roster;

import client.Character;
import client.Client;
import client.inventory.manipulator.InventoryManipulator;
import config.LivingWorldConfig;
import net.packet.Packet;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import server.agents.runtime.AgentInteractionRuntime;
import server.agents.runtime.AgentRuntimeCleanupService;
import server.living.LivingWorld;
import server.living.bond.ContractService;
import server.living.config.Content;
import server.living.config.Content.Look;
import server.living.config.Content.Persona;
import server.living.population.FieldCatalog;
import server.living.population.Population;
import server.living.population.PopulationContent;
import server.living.resident.ResidentRuntime;
import server.living.resident.ResidentSpots;
import server.living.store.LivingStore;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.PersonaState;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import tools.PacketCreator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RosterService#grant}, the {@code !grantcompanion} write: a BENCHED row with no spawn and no
 * character change, the same ROSTER_MAX and one-owner-per-persona rules as a recruit, and every cache
 * (owner by persona, the owner set behind the operator grant) in step, both ways through release. The first grant or
 * recruit hands the owner the Adventurer's Roster with one whisper (plan flow 9 step 3); later ones hand over nothing,
 * and a login hands a roster owner without one another.
 */
class RosterServiceGrantTest {
    private static final int ALICE = 10;
    private static final int BOB = 20;
    private static final int LITH_HARBOR = 104000000;
    private static final Persona OLWEN = persona("olwen", "Olwen");
    private static final Persona CATO = persona("cato", "Cato");
    private static final int ROSTER_BOOK = 2430011;

    /** living_roster keyed by owner:persona. */
    private final Map<String, RosterEntry> rows = new LinkedHashMap<>();
    private final Map<String, PersonaState> personaStates = new HashMap<>();
    private final Map<String, Integer> worldFlags = new HashMap<>();
    private final List<String> events = new ArrayList<>();
    private final LivingWorld world = mock(LivingWorld.class);
    private final LivingStore store = mock(LivingStore.class);
    private final ResidentRuntime residents = mock(ResidentRuntime.class);
    private final LivingWorldConfig config = new LivingWorldConfig();
    private final Character alice = character(ALICE, "Alice");
    private final Character bob = character(BOB, "Bob");

    private MockedStatic<ResidentSpots> spots;
    private MockedStatic<AgentInteractionRuntime> interaction;
    private MockedStatic<AgentRuntimeCleanupService> cleanup;

    @BeforeEach
    void wireWorld() {
        when(store.rosterEntry(anyInt(), anyString())).thenAnswer(inv -> rows.get(key(inv.getArgument(0), inv.getArgument(1))));
        when(store.roster(anyInt())).thenAnswer(inv -> rows.values().stream()
                .filter(e -> e.characterId() == (int) inv.getArgument(0) && e.state() != RosterState.RELEASED).toList());
        when(store.rosterOfPersona(anyString())).thenAnswer(inv -> rows.values().stream()
                .filter(e -> e.personaId().equals(inv.getArgument(0)) && e.state() != RosterState.RELEASED)
                .findFirst().orElse(null));
        when(store.allActive()).thenReturn(List.of());
        when(store.allBenched()).thenAnswer(inv -> rows.values().stream()
                .filter(e -> e.state() == RosterState.BENCHED || e.state() == RosterState.AWAY).toList());
        doAnswer(inv -> {
            RosterEntry entry = inv.getArgument(0);
            rows.put(key(entry.characterId(), entry.personaId()), entry);
            return null;
        }).when(store).saveRoster(any());
        doAnswer(inv -> rows.remove(key(inv.getArgument(0), inv.getArgument(1))))
                .when(store).deleteRoster(anyInt(), anyString());
        when(store.personaState(anyString())).thenAnswer(inv -> personaStates.getOrDefault(inv.getArgument(0),
                new PersonaState(inv.getArgument(0), 0, null, 0, 0)));
        doAnswer(inv -> {
            PersonaState state = inv.getArgument(0);
            personaStates.put(state.personaId(), state);
            return null;
        }).when(store).savePersonaState(any());
        doAnswer(inv -> worldFlags.put(inv.getArgument(0), inv.getArgument(1)))
                .when(store).setWorldFlag(anyString(), anyInt());
        doAnswer(inv -> worldFlags.remove(inv.getArgument(0))).when(store).clearWorldFlag(anyString());
        doAnswer(inv -> events.add(inv.getArgument(0) + " " + inv.getArgument(1) + " " + inv.getArgument(2)))
                .when(store).event(anyInt(), anyString(), anyString(), any());

        when(world.store()).thenReturn(store);
        when(world.residents()).thenReturn(residents);
        when(world.config()).thenReturn(config);
        when(world.benchSessions()).thenReturn(mock(BenchSessions.class));
        when(world.benchDuty()).thenReturn(mock(BenchDutyDispatcher.class));
        when(world.contracts()).thenReturn(mock(ContractService.class));
        when(world.slots()).thenReturn(new CompanionSlots(world));

        spots = mockStatic(ResidentSpots.class);
        spots.when(() -> ResidentSpots.townMapId("lith-harbor")).thenReturn(LITH_HARBOR);
        interaction = mockStatic(AgentInteractionRuntime.class);
        cleanup = mockStatic(AgentRuntimeCleanupService.class);
    }

    @AfterEach
    void closeStatics() {
        cleanup.close();
        interaction.close();
        spots.close();
    }

    @Test
    void grantBenchesThePersonaAtItsHomeCampWithoutSpawningOrTouchingItsCharacter() {
        RosterService roster = new RosterService(world);

        assertEquals("Olwen joins Alice's roster, benched at map " + LITH_HARBOR + ".", roster.grant(alice, OLWEN));

        RosterEntry row = rows.get(key(ALICE, "olwen"));
        assertEquals(RosterState.BENCHED, row.state());
        assertEquals(Assignment.TRAIN, row.assignment());
        assertEquals(LITH_HARBOR, row.campMapId());
        assertEquals(0L, row.accruedExp());
        assertEquals(List.of(ALICE + " olwen roster.grant"), events);
        // the Resident hands the character over; nothing provisions, reshapes or spawns it
        verify(residents).releaseForRecruit("olwen");
        verify(residents, never()).ensureCharacter(any());
        interaction.verifyNoInteractions();
        cleanup.verifyNoInteractions();
        verify(world, never()).bench();
    }

    @Test
    void aNewCompanionCampsAtTheTopFieldForItsLevelNearItsHomeTown() {
        Population population = mock(Population.class);
        FieldCatalog fields = mock(FieldCatalog.class);
        when(population.fields()).thenReturn(fields);
        int portRoad = 104010000;
        when(fields.fieldsFor(1, LITH_HARBOR, 1)).thenReturn(List.of(new PopulationContent.Field(portRoad, "Port Road", "",
                "victoria", false, 10, 3, 6)));
        RosterService roster = new RosterService(world);

        try (MockedStatic<Population> populations = mockStatic(Population.class)) {
            populations.when(Population::get).thenReturn(population);
            assertEquals("Olwen joins Alice's roster, benched at map " + portRoad + ".", roster.grant(alice, OLWEN));
        }

        // it trains in a field from the start: the card never shows "train @" a town it cannot train in
        assertEquals(portRoad, rows.get(key(ALICE, "olwen")).campMapId());
        assertEquals(Assignment.TRAIN, rows.get(key(ALICE, "olwen")).assignment());
    }

    @Test
    void grantKeepsEveryOwnershipCacheInStep() {
        RosterService roster = new RosterService(world);

        roster.grant(alice, OLWEN);

        assertEquals(ALICE, roster.cachedOwnerOf("olwen"));
        assertTrue(roster.isOwned("olwen"));
        assertTrue(roster.hasCompanions(alice)); // the operator grant installed by LivingWorld
        assertEquals(ALICE, personaStates.get("olwen").employedBy());
        assertEquals(ALICE, worldFlags.get("persona.olwen.employed"));
        // a restart rebuilds the same caches from the row
        assertEquals(ALICE, new RosterService(world).cachedOwnerOf("olwen"));
    }

    @Test
    void grantRefusesAPersonaThatAlreadyHasAnotherOwner() {
        rows.put(key(BOB, "olwen"), row(BOB, "olwen"));
        RosterService roster = new RosterService(world);

        assertEquals("Olwen already travels with someone else.", roster.grant(alice, OLWEN));

        assertNull(rows.get(key(ALICE, "olwen")));
        assertEquals(BOB, roster.cachedOwnerOf("olwen"));
        assertFalse(roster.hasCompanions(alice));
        assertTrue(events.isEmpty());
        verify(residents, never()).releaseForRecruit(anyString());
    }

    @Test
    void grantingTheOwnersOwnCompanionAgainChangesNothing() {
        RosterService roster = new RosterService(world);
        roster.grant(alice, OLWEN);
        RosterEntry first = rows.get(key(ALICE, "olwen"));

        assertEquals("Olwen is already on Alice's roster.", roster.grant(alice, OLWEN));

        assertEquals(first, rows.get(key(ALICE, "olwen")));
        assertEquals(1, events.size());
    }

    @Test
    void grantRespectsRosterMax() {
        config.ROSTER_MAX = 2;
        rows.put(key(ALICE, "brin"), row(ALICE, "brin"));
        rows.put(key(ALICE, "cato"), row(ALICE, "cato"));
        RosterService roster = new RosterService(world);

        assertEquals("Alice's roster is full (2).", roster.grant(alice, OLWEN));

        assertNull(rows.get(key(ALICE, "olwen")));
        assertEquals(0, roster.cachedOwnerOf("olwen"));
        assertTrue(events.isEmpty());
    }

    @Test
    void releaseAfterAGrantClearsTheCachesAgain() {
        RosterService roster = new RosterService(world);
        roster.grant(alice, OLWEN);

        assertEquals("Olwen heads home to Lith Harbor.", roster.release(alice, OLWEN, "gm release"));

        assertNull(rows.get(key(ALICE, "olwen")));
        assertEquals(0, roster.cachedOwnerOf("olwen"));
        assertFalse(roster.hasCompanions(alice));
        assertEquals(0, personaStates.get("olwen").employedBy());
        assertFalse(worldFlags.containsKey("persona.olwen.employed"));
        assertEquals(ALICE + " olwen roster.release", events.get(events.size() - 1));
        // and the persona is free for someone else
        assertEquals("Olwen joins Bob's roster, benched at map " + LITH_HARBOR + ".", roster.grant(bob, OLWEN));
    }

    // ---- the Adventurer's Roster ---------------------------------------------------------------

    @Test
    void aGrantHandsTheOwnerTheAdventurersRosterWithTheStockItemGainLine() {
        Client client = carrying(alice, false);

        try (MockedStatic<InventoryManipulator> inventory = roomFor(client)) {
            new RosterService(world).grant(alice, OLWEN);
            inventory.verify(() -> InventoryManipulator.addById(client, ROSTER_BOOK, (short) 1));
        }

        ArgumentCaptor<Packet> sent = ArgumentCaptor.forClass(Packet.class);
        verify(alice).sendPacket(sent.capture());
        assertArrayEquals(PacketCreator.getShowItemGain(ROSTER_BOOK, (short) 1, true).getBytes(), sent.getValue().getBytes());
        verify(residents).whisper(OLWEN, alice, "double-click me or the book to manage the crew.");
    }

    @Test
    void laterGrantsAndRecruitsHandOverNoRosterAndSayNothingAboutIt() {
        rows.put(key(ALICE, "brin"), row(ALICE, "brin"));
        Client client = carrying(alice, false); // lost it: the next login hands over another
        partyFull(alice);
        RosterService roster = new RosterService(world);

        try (MockedStatic<InventoryManipulator> inventory = roomFor(client)) {
            roster.grant(alice, OLWEN);
            roster.recruit(alice, CATO);
            inventory.verify(() -> InventoryManipulator.addById(any(), anyInt(), anyShort()), never());
        }
        assertEquals(ALICE, roster.cachedOwnerOf("olwen"));
        assertEquals(ALICE, roster.cachedOwnerOf("cato"));
        verify(alice, never()).sendPacket(any());
        verify(residents, never()).whisper(any(), any(), anyString());
    }

    @Test
    void anOwnerWhoCarriesTheRosterGetsNoSecondOne() {
        Client client = carrying(alice, true);

        try (MockedStatic<InventoryManipulator> inventory = roomFor(client)) {
            new RosterService(world).grant(alice, OLWEN);
            inventory.verify(() -> InventoryManipulator.addById(any(), anyInt(), anyShort()), never());
        }
        verify(alice, never()).sendPacket(any());
    }

    @Test
    void aRecruitHandsOverTheRosterAndTheCompanionSaysHowToUseIt() {
        Client client = carrying(alice, false);
        partyFull(alice);

        try (MockedStatic<InventoryManipulator> inventory = roomFor(client)) {
            assertEquals("Olwen joins your roster but your party is full; call them when there is room.",
                    RosterVoice.line(OLWEN, new RosterService(world).recruit(alice, OLWEN)));
            inventory.verify(() -> InventoryManipulator.addById(client, ROSTER_BOOK, (short) 1));
        }
        verify(residents).whisper(OLWEN, alice, "double-click me or the book to manage the crew.");
    }

    @Test
    void aFullInventoryNeverStopsTheRecruitAndTheNextLoginTriesAgain() {
        Client client = carrying(alice, false);
        partyFull(alice);
        RosterService roster = new RosterService(world);

        try (MockedStatic<InventoryManipulator> inventory = mockStatic(InventoryManipulator.class)) {
            inventory.when(() -> InventoryManipulator.checkSpace(client, ROSTER_BOOK, 1, "")).thenReturn(false);
            assertEquals("Olwen joins your roster but your party is full; call them when there is room.", RosterVoice.line(OLWEN, roster.recruit(alice, OLWEN)));
            inventory.verify(() -> InventoryManipulator.addById(any(), anyInt(), anyShort()), never());
        }
        verify(residents, never()).whisper(any(), any(), anyString());

        try (MockedStatic<InventoryManipulator> inventory = roomFor(client)) {
            roster.onOwnerLogin(alice);
            inventory.verify(() -> InventoryManipulator.addById(client, ROSTER_BOOK, (short) 1));
        }
    }

    @Test
    void aLoginWithoutARosterOrWithTheItemOffGrantsNothing() {
        Client client = carrying(alice, false);
        RosterService roster = new RosterService(world);

        try (MockedStatic<InventoryManipulator> inventory = roomFor(client)) {
            roster.onOwnerLogin(alice); // nobody on the roster
            rows.put(key(ALICE, "olwen"), row(ALICE, "olwen"));
            config.ROSTER_BOOK_ITEM_ID = 0;
            roster.onOwnerLogin(alice);
            inventory.verify(() -> InventoryManipulator.addById(any(), anyInt(), anyShort()), never());
        }
    }

    private static Client carrying(Character owner, boolean book) {
        Client client = mock(Client.class);
        when(owner.getClient()).thenReturn(client);
        when(owner.haveItem(ROSTER_BOOK)).thenReturn(book);
        return client;
    }

    private static MockedStatic<InventoryManipulator> roomFor(Client client) {
        MockedStatic<InventoryManipulator> inventory = mockStatic(InventoryManipulator.class);
        inventory.when(() -> InventoryManipulator.checkSpace(client, ROSTER_BOOK, 1, "")).thenReturn(true);
        inventory.when(() -> InventoryManipulator.addById(client, ROSTER_BOOK, (short) 1)).thenReturn(true);
        return inventory;
    }

    private static void partyFull(Character owner) {
        Party party = mock(Party.class);
        when(party.getMembers()).thenReturn(Collections.nCopies(6, mock(PartyCharacter.class)));
        when(owner.getParty()).thenReturn(party);
    }

    private static String key(int ownerId, String personaId) {
        return ownerId + ":" + personaId;
    }

    private static RosterEntry row(int ownerId, String personaId) {
        return new RosterEntry(ownerId, personaId, RosterState.BENCHED, Assignment.TRAIN, 0, 0, LITH_HARBOR, 0L,
                0L, 0L, 1_000L, 1_000L, 1_000L, 0, null);
    }

    private static Character character(int id, String name) {
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(id);
        when(character.getName()).thenReturn(name);
        return character;
    }

    private static Persona persona(String id, String name) {
        return new Persona(id, name, "", "BEGINNER", new Content.LevelBand(1, 1), Content.Strength.ROOKIE, null,
                "relaxed-v1", null, "lith-harbor", List.of(), false, null, List.of(), null, List.of(), List.of(), Map.of(),
                -1, -1, -1, List.of(), 0, Look.NONE, "test");
    }
}
