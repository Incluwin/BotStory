package server.living.roster;

import client.Character;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.integration.AgentCharacterGatewayRuntime;
import server.agents.integration.AgentIdentityGateway;
import server.agents.integration.AgentIdentityGatewayRuntime;
import server.agents.integration.AgentPersistenceGateway;
import server.agents.integration.AgentPersistenceGatewayRuntime;
import server.agents.integration.CharacterGateway;
import server.agents.registry.AgentResolvedCharacter;
import server.agents.runtime.AgentRuntimeRegistry;
import server.integration.AgentPresence;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.population.Citizen;
import server.living.population.Population;
import server.living.resident.ResidentRuntime;
import server.living.roster.CompanionDirectory.Relation;
import server.living.store.LivingStore;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** CompanionDirectory relations and the AgentClaimGuard rule it installs (docs/BOT_MANAGEMENT_PLAN.md P1). */
class CompanionDirectoryTest {
    private static final int ALICE = 10;
    private static final int BOB = 20;
    private static final int ADMIN = 30;
    private static final int OLWEN = 900;   // Alice's companion
    private static final int BRIN = 901;    // Bob's companion
    private static final int CATO = 902;    // an unrecruited Resident
    private static final int MIRA = 950;    // a citizen
    private static final int PROBE = 960;  // a plain Agent Alice leads
    private static final int IDLE = 961;   // a plain Agent nobody leads

    private final LivingWorld world = mock(LivingWorld.class);
    private final LivingStore store = mock(LivingStore.class);
    private final ResidentRuntime residents = mock(ResidentRuntime.class);
    private final BenchDutyDispatcher bench = mock(BenchDutyDispatcher.class);
    private final LivingContent content = mock(LivingContent.class);
    private final Population population = mock(Population.class);
    private final CompanionLeases leases = new CompanionLeases();
    private final RosterEntry olwenRow = row(ALICE, "olwen");
    private final Character alice = character(ALICE, "Alice");
    private final Character bob = character(BOB, "Bob");
    private final Character admin = character(ADMIN, "Admin");

    private MockedStatic<Population> populations;
    private MockedStatic<AgentRuntimeRegistry> registry;
    private String oldAdministrators;
    private CompanionDirectory directory;

    @BeforeEach
    void wireWorld() {
        when(store.allActive()).thenReturn(List.of(olwenRow, row(BOB, "brin")));
        when(store.rosterEntry(ALICE, "olwen")).thenReturn(olwenRow);
        persona(OLWEN, "olwen", "Olwen");
        persona(BRIN, "brin", "Brin");
        persona(CATO, "cato", "Cato");
        when(world.store()).thenReturn(store);
        when(world.residents()).thenReturn(residents);
        when(world.benchDuty()).thenReturn(bench);
        when(world.content()).thenReturn(content);
        when(world.leases()).thenReturn(leases);
        when(world.slots()).thenReturn(new CompanionSlots(world));
        RosterService roster = new RosterService(world);
        when(world.roster()).thenReturn(roster);
        Citizen mira = mock(Citizen.class);
        when(mira.characterId()).thenReturn(MIRA);
        when(population.isCitizen(MIRA)).thenReturn(true);
        when(population.citizenByName("Mira")).thenReturn(mira);
        populations = mockStatic(Population.class);
        populations.when(Population::get).thenReturn(population);
        registry = mockStatic(AgentRuntimeRegistry.class);
        registry.when(() -> AgentRuntimeRegistry.leaderIdForAgentCharacter(PROBE)).thenReturn(ALICE);
        registry.when(() -> AgentRuntimeRegistry.leaderIdForAgentCharacter(IDLE)).thenReturn(IDLE);
        Set<Integer> agents = Set.of(OLWEN, BRIN, CATO, MIRA, PROBE, IDLE);
        AgentPresence.install(chr -> agents.contains(chr.getId()));
        oldAdministrators = config.AgentYamlConfig.config.agent.AGENT_AUTHORITY_ADMINISTRATOR_NAMES;
        config.AgentYamlConfig.config.agent.AGENT_AUTHORITY_ADMINISTRATOR_NAMES = "Admin";
        directory = new CompanionDirectory(world);
    }

    @AfterEach
    void restore() {
        config.AgentYamlConfig.config.agent.AGENT_AUTHORITY_ADMINISTRATOR_NAMES = oldAdministrators;
        AgentPresence.install(null);
        registry.close();
        populations.close();
    }

    // ---- mayControl matrix ---------------------------------------------------------------

    @Test
    void ownersControlTheirCompanionsAndNobodyElseDoes() {
        assertTrue(directory.mayControl(alice, OLWEN));
        assertFalse(directory.mayControl(bob, OLWEN));
        assertFalse(directory.mayControl(alice, BRIN));
        assertTrue(directory.mayControl(bob, BRIN));
    }

    @Test
    void aBenchLiveCompanionIsLeftToItsBenchSessionEvenByItsOwner() {
        when(bench.live("olwen")).thenReturn(true);

        assertFalse(directory.mayControl(alice, OLWEN));
        assertTrue(directory.mayControl(admin, OLWEN));
    }

    @Test
    void residentsAndCitizensTakeOrdersFromNoPlayer() {
        assertFalse(directory.mayControl(alice, CATO));
        assertFalse(directory.mayControl(alice, MIRA));
        assertTrue(directory.mayControl(admin, CATO));
        assertTrue(directory.mayControl(admin, MIRA));
    }

    @Test
    void aPlainAgentAnswersOnlyToItsRuntimeLeader() {
        assertTrue(directory.mayControl(alice, PROBE));
        assertFalse(directory.mayControl(bob, PROBE));
        assertFalse(directory.mayControl(alice, IDLE));
        assertTrue(directory.mayControl(admin, IDLE));
    }

    @Test
    void nobodyIsNeverInControl() {
        assertFalse(directory.mayControl(null, PROBE));
    }

    // ---- mayLoad: no administrator override for a claimed character -----------------------

    @Test
    void anAdministratorLoadsNoCompanionResidentOrCitizen() {
        assertFalse(directory.mayLoad(admin, OLWEN));
        assertFalse(directory.mayLoad(admin, CATO));
        assertFalse(directory.mayLoad(admin, MIRA));
        assertTrue(directory.mayLoad(admin, IDLE));
    }

    @Test
    void anOwnerLoadsTheirCompanionOnlyThroughTheRosterWhichHoldsItsLease() {
        assertFalse(directory.mayLoad(alice, OLWEN)); // an @spawnbot of it: the roster did not take the lease

        leases.tryAcquire(OLWEN, CompanionLeases.Kind.COMPANION, ALICE);

        assertTrue(directory.mayLoad(alice, OLWEN));
        assertFalse(directory.mayLoad(bob, OLWEN));
        when(bench.live("olwen")).thenReturn(true);
        assertFalse(directory.mayLoad(alice, OLWEN));
    }

    @Test
    void mayLoadStaysCompanionOnlyForAnExpeditionLease() {
        // P7 Part B: a bench ally on its trip to a boss run holds an EXPEDITION lease; it loads only on its arrival,
        // never through a spawn or an invite (the party-invite gate asks mayLoad), while its owner still controls it
        leases.tryAcquire(OLWEN, CompanionLeases.Kind.EXPEDITION, ALICE);

        assertFalse(directory.mayLoad(alice, OLWEN));
        assertFalse(directory.mayLoad(bob, OLWEN));
        assertFalse(directory.mayLoad(admin, OLWEN));
        assertTrue(directory.mayControl(alice, OLWEN), "the boss run adopts it for its owner");
    }

    @Test
    void aPlainAgentLoadsForWhoeverMayControlIt() {
        assertTrue(directory.mayLoad(alice, PROBE));
        assertFalse(directory.mayLoad(bob, PROBE));
        assertFalse(directory.mayLoad(null, PROBE));
    }

    @Test
    void companionsResidentsAndCitizensAreClaimedPlainAgentsAreNot() {
        assertTrue(directory.isClaimed(OLWEN));
        assertTrue(directory.isClaimed(CATO));
        assertTrue(directory.isClaimed(MIRA));
        assertFalse(directory.isClaimed(PROBE));
    }

    // ---- relations -----------------------------------------------------------------------

    @Test
    void resolvesEveryKindOfCharacterFromTheViewersSide() {
        assertEquals(new Relation.OwnCompanion(olwenRow), directory.resolve(alice, character(OLWEN, "Olwen")));
        assertEquals(new Relation.OthersCompanion(ALICE, "olwen"), directory.resolve(bob, character(OLWEN, "Olwen")));
        assertEquals(new Relation.Resident("cato"), directory.resolve(alice, character(CATO, "Cato")));
        assertEquals(new Relation.Citizen(MIRA), directory.resolve(alice, character(MIRA, "Mira")));
        assertEquals(new Relation.PlainAgent(PROBE), directory.resolve(alice, character(PROBE, "Probe")));
        assertEquals(new Relation.Human(BOB), directory.resolve(alice, bob));
        assertSame(Relation.UNKNOWN, directory.resolve(alice, null));
    }

    @Test
    void resolvesPersonaAndCitizenNamesFromMemory() {
        assertEquals(new Relation.OwnCompanion(olwenRow), directory.resolveName(alice, "Olwen"));
        assertEquals(new Relation.OthersCompanion(BOB, "brin"), directory.resolveName(alice, "brin"));
        assertEquals(new Relation.Resident("cato"), directory.resolveName(bob, "Cato"));
        assertEquals(new Relation.Citizen(MIRA), directory.resolveName(bob, "Mira"));
        assertSame(Relation.UNKNOWN, directory.resolveName(bob, " "));
    }

    @Test
    void aHiredCitizenIsItsOwnersCompanionAndNobodyElses() {
        RosterEntry hire = row(ALICE, "hire:950");
        when(store.allActive()).thenReturn(List.of(olwenRow, row(BOB, "brin"), hire));
        when(store.rosterEntry(ALICE, "hire:950")).thenReturn(hire);
        RosterService roster = new RosterService(world); // as at boot, with Alice's hire:950 row
        when(world.roster()).thenReturn(roster);
        Character mira = character(MIRA, "Mira");

        assertEquals(new Relation.OwnCompanion(hire), directory.resolve(alice, mira));
        assertEquals(new Relation.OthersCompanion(ALICE, "hire:950"), directory.resolve(bob, mira));
        assertEquals(new Relation.OwnCompanion(hire), directory.resolveName(alice, "Mira"));
        assertEquals(ALICE, directory.ownerOfCharacter(MIRA));
        assertTrue(directory.mayControl(alice, MIRA));
        assertFalse(directory.mayControl(bob, MIRA));
        assertTrue(directory.isClaimed(MIRA));
        assertFalse(directory.mayLoad(alice, MIRA), "only through the roster, which holds its lease");
        leases.tryAcquire(MIRA, CompanionLeases.Kind.COMPANION, ALICE);
        assertTrue(directory.mayLoad(alice, MIRA));
        assertFalse(directory.mayLoad(bob, MIRA));
    }

    @Test
    void resolvesOfflineNamesThroughTheCharacterTable() throws Exception {
        CharacterGateway characters = mock(CharacterGateway.class);
        AgentPersistenceGateway persistence = mock(AgentPersistenceGateway.class);
        AgentIdentityGateway identities = mock(AgentIdentityGateway.class);
        when(persistence.findCharacterByName("Idle")).thenReturn(new AgentResolvedCharacter(IDLE, "Idle", 1, null));
        when(persistence.findCharacterByName("Carol")).thenReturn(new AgentResolvedCharacter(40, "Carol", 2, null));
        when(identities.isActiveAgent(IDLE)).thenReturn(true);

        try (MockedStatic<AgentCharacterGatewayRuntime> characterRuntime = mockStatic(AgentCharacterGatewayRuntime.class);
             MockedStatic<AgentPersistenceGatewayRuntime> persistenceRuntime = mockStatic(AgentPersistenceGatewayRuntime.class);
             MockedStatic<AgentIdentityGatewayRuntime> identityRuntime = mockStatic(AgentIdentityGatewayRuntime.class)) {
            characterRuntime.when(AgentCharacterGatewayRuntime::characters).thenReturn(characters);
            persistenceRuntime.when(AgentPersistenceGatewayRuntime::persistence).thenReturn(persistence);
            identityRuntime.when(AgentIdentityGatewayRuntime::identities).thenReturn(identities);

            assertEquals(new Relation.PlainAgent(IDLE), directory.resolveName(alice, "Idle"));
            assertEquals(new Relation.Human(40), directory.resolveName(alice, "Carol"));
            assertSame(Relation.UNKNOWN, directory.resolveName(alice, "Nobody"));
        }
    }

    @Test
    void ownerOfCharacterReadsTheRosterCache() {
        assertEquals(ALICE, directory.ownerOfCharacter(OLWEN));
        assertEquals(BOB, directory.ownerOfCharacter(BRIN));
        assertEquals(0, directory.ownerOfCharacter(CATO));
        assertEquals(0, directory.ownerOfCharacter(PROBE));
    }

    // ---- own party -----------------------------------------------------------------------

    @Test
    void anOwnPartyHasOneHumanAndNoOtherPlayersCompanion() {
        assertTrue(directory.isCompanionParty(party(member(alice), member(character(OLWEN, "Olwen")),
                member(character(MIRA, "Mira")))));
        // Olwen logged off with Alice's party: an offline persona member still counts as an Agent
        assertTrue(directory.isCompanionParty(party(member(alice), offline(OLWEN))));

        assertFalse(directory.isCompanionParty(party(member(alice), member(bob))));
        assertFalse(directory.isCompanionParty(party(member(alice), member(character(BRIN, "Brin")))));
        assertFalse(directory.isCompanionParty(party(member(character(OLWEN, "Olwen")), member(character(PROBE, "Probe")))));
        assertFalse(directory.isCompanionParty(null));
    }

    // ---- helpers -------------------------------------------------------------------------

    private void persona(int characterId, String id, String name) {
        Persona persona = mock(Persona.class);
        when(persona.id()).thenReturn(id);
        when(persona.name()).thenReturn(name);
        when(residents.personaOfCharacterId(characterId)).thenReturn(persona);
        when(content.personaByName(name)).thenReturn(persona);
        when(content.personaByName(name.toLowerCase())).thenReturn(persona);
    }

    private static Party party(PartyCharacter... members) {
        Party party = mock(Party.class);
        when(party.getMembers()).thenReturn(List.of(members));
        return party;
    }

    private static PartyCharacter member(Character player) {
        PartyCharacter member = mock(PartyCharacter.class);
        int id = player.getId();
        when(member.getPlayer()).thenReturn(player);
        when(member.getId()).thenReturn(id);
        return member;
    }

    private static PartyCharacter offline(int characterId) {
        PartyCharacter member = mock(PartyCharacter.class);
        when(member.getId()).thenReturn(characterId);
        return member;
    }

    private static RosterEntry row(int ownerId, String personaId) {
        return new RosterEntry(ownerId, personaId, RosterState.ACTIVE, Assignment.TRAIN, 0, 0, 104000000, 0L,
                0L, 0L, 1_000L, 1_000L, 1_000L, 0, null);
    }

    private static Character character(int id, String name) {
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(id);
        when(character.getName()).thenReturn(name);
        return character;
    }
}
