// Pins behaviour as of 2026-09-27 for docs/BOT_MANAGEMENT_PLAN.md P1/P2. When the fix lands, flip the assertion and rename the test.
package server.living.roster;

import client.Character;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import net.server.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.party.AgentPartyLifecycleService;
import server.agents.capabilities.party.JoinOutcome;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.integration.AgentPartyMemberSnapshot;
import server.agents.integration.AgentPartySnapshot;
import server.agents.integration.PartyGateway;
import server.agents.integration.cosmic.CosmicPartyGateway;
import server.agents.runtime.AgentInteractionRuntime;
import server.agents.runtime.AgentLifecycleService.AgentSpawnResult;
import server.agents.runtime.AgentRuntimeCleanupService;
import server.agents.runtime.AgentRuntimeRegistry;
import server.integration.AgentPresence;
import server.living.LivingWorld;
import server.living.arc.ArcService;
import server.living.config.Content;
import server.living.config.Content.Look;
import server.living.config.Content.Persona;
import server.living.resident.ResidentRuntime;
import server.living.store.LivingStore;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Characterizes {@link RosterService} slot accounting and {@code spawnCompanion} (reached through the
 * public {@code call}/{@code bench}) against an in-memory stand-in for {@code living_roster}. The Agent
 * runtime statics are mocked; {@link BenchLedger} is the real class.
 */
class RosterServiceCharacterizationTest {
    private static final int ALICE = 10;
    private static final int BOB = 20;
    private static final int OLWEN_CHARACTER = 900;
    private static final Persona OLWEN = persona("olwen", "Olwen");

    /** living_roster keyed by owner:persona; saveRoster is an upsert (LivingStore.saveRoster:467-473). */
    private final Map<String, RosterEntry> rows = new LinkedHashMap<>();
    private final List<RosterEntry> saved = new ArrayList<>();
    private final LivingWorld world = mock(LivingWorld.class);
    private final CompanionLeases leases = new CompanionLeases();
    private final ResidentRuntime residents = mock(ResidentRuntime.class);
    private final Character alice = character(ALICE, "Alice");
    private final Character olwenBot = character(OLWEN_CHARACTER, "Olwen");

    private MockedStatic<AgentRuntimeRegistry> registry;
    private MockedStatic<AgentRuntimeCleanupService> cleanup;
    private MockedStatic<AgentInteractionRuntime> interaction;

    @BeforeEach
    void wireWorld() {
        LivingStore store = mock(LivingStore.class);
        when(store.rosterEntry(anyInt(), anyString())).thenAnswer(inv -> rows.get(key(inv.getArgument(0), inv.getArgument(1))));
        when(store.roster(anyInt())).thenAnswer(inv -> rows.values().stream()
                .filter(e -> e.characterId() == (int) inv.getArgument(0) && e.state() != RosterState.RELEASED).toList());
        when(store.allActive()).thenAnswer(inv -> rows.values().stream().filter(e -> e.state() == RosterState.ACTIVE).toList());
        when(store.allBenched()).thenAnswer(inv -> rows.values().stream()
                .filter(e -> e.state() == RosterState.BENCHED || e.state() == RosterState.AWAY).toList());
        doAnswer(inv -> {
            RosterEntry entry = inv.getArgument(0);
            rows.put(key(entry.characterId(), entry.personaId()), entry);
            saved.add(entry);
            return null;
        }).when(store).saveRoster(any());
        doAnswer(inv -> { // the ledger writes progress only: the row keeps its state (LivingStore.saveRosterProgress)
            RosterEntry progress = inv.getArgument(0);
            RosterEntry stored = rows.get(key(progress.characterId(), progress.personaId()));
            if (stored != null) {
                RosterEntry merged = new RosterEntry(progress.characterId(), progress.personaId(), stored.state(),
                        progress.assignment(), progress.assignmentItem(), progress.assignmentCount(), progress.campMapId(),
                        progress.accruedExp(), progress.haulMeso(), progress.wagesPaidUntilMs(), stored.recruitedAtMs(),
                        progress.benchedAtMs(), progress.lastLedgerAtMs(), progress.farmSecondsToday(), progress.farmDay());
                rows.put(key(merged.characterId(), merged.personaId()), merged);
                saved.add(merged);
            }
            return null;
        }).when(store).saveRosterProgress(any());

        when(residents.ensureCharacter(OLWEN)).thenReturn(OLWEN_CHARACTER);
        when(residents.characterIdOf(OLWEN.id())).thenReturn(OLWEN_CHARACTER);
        when(world.store()).thenReturn(store);
        when(world.residents()).thenReturn(residents);
        when(world.benchSessions()).thenReturn(mock(BenchSessions.class));
        when(world.arcs()).thenReturn(mock(ArcService.class));
        when(world.bench()).thenReturn(new BenchLedger(world));
        when(world.leases()).thenReturn(leases);
        when(world.slots()).thenReturn(new CompanionSlots(world));
        CompanionBudget budget = mock(CompanionBudget.class); // BudgetWiringTest covers the real pools
        when(budget.admit(any(), any(Character.class))).thenReturn(CompanionBudget.Admission.ALLOW);
        when(world.budget()).thenReturn(budget);

        registry = mockStatic(AgentRuntimeRegistry.class);
        cleanup = mockStatic(AgentRuntimeCleanupService.class);
        interaction = mockStatic(AgentInteractionRuntime.class);
        interaction.when(() -> AgentInteractionRuntime.spawnAgentForLeader(alice, "Olwen"))
                .thenReturn(AgentSpawnResult.ok(olwenBot, true));
    }

    @AfterEach
    void closeStatics() {
        interaction.close();
        cleanup.close();
        registry.close();
    }

    // ---- 1. stale-entry overwrite --------------------------------------------------------

    @Test
    void callSavesActiveOverTheZeroedAccruedExp() {
        seed(row(ALICE, "olwen", RosterState.BENCHED, 5_000L));
        RosterService roster = new RosterService(world);

        try (MockedStatic<AgentPartyLifecycleService> party = joined(); MockedStatic<Party> parties = mockStatic(Party.class)) {
            assertEquals("Olwen joins your party.", RosterVoice.line(OLWEN, roster.call(alice, OLWEN)));
        }

        verify(olwenBot, times(1)).gainExp(5_000, false, false);
        assertEquals(2, saved.size());
        // BenchLedger.applyAccrued writes the row's progress (never its state) with accruedExp 0 once the EXP is granted ...
        assertEquals(RosterState.BENCHED, saved.get(0).state());
        assertEquals(0L, saved.get(0).accruedExp());
        // ... and spawnCompanion re-reads that row before it saves ACTIVE, so the 5000 stays spent
        assertEquals(RosterState.ACTIVE, saved.get(1).state());
        assertEquals(0L, saved.get(1).accruedExp());
        assertEquals(0L, rows.get(key(ALICE, "olwen")).accruedExp());
    }

    @Test
    void bankedExpIsGrantedOnlyOnce() {
        seed(row(ALICE, "olwen", RosterState.BENCHED, 5_000L));
        RosterService roster = new RosterService(world);

        try (MockedStatic<AgentPartyLifecycleService> party = joined(); MockedStatic<Party> parties = mockStatic(Party.class)) {
            roster.call(alice, OLWEN);
            roster.bench(alice, OLWEN);
            roster.call(alice, OLWEN);
        }

        // no ledger tick ran between the calls, so there was nothing new to grant
        verify(olwenBot, times(1)).gainExp(5_000, false, false);
    }

    // ---- 2. a failed party join ----------------------------------------------------------

    @Test
    void awayCompanionStaysAwayWhenThePartyIsFull() {
        // AWAY skips the freeSlots gate in call (:230-231); owner.getMap() is null so the RecallTravel
        // branch (:239) is skipped and the companion spawns at once.
        seed(row(ALICE, "olwen", RosterState.AWAY, 0L));
        RosterService roster = new RosterService(world);
        World channelWorld = mock(World.class);
        Party full = mock(Party.class);
        List<PartyCharacter> six = Collections.nCopies(6, mock(PartyCharacter.class));
        when(full.getMembers()).thenReturn(six);
        when(channelWorld.getParty(77)).thenReturn(full);
        when(alice.getParty()).thenReturn(full);
        when(olwenBot.getWorldServer()).thenReturn(channelWorld);
        PartyGateway gateway = mock(PartyGateway.class);
        when(gateway.snapshot(alice)).thenReturn(new AgentPartySnapshot(77, sixMembers()));
        // the real join: Party.joinParty(agent, 77, true) returns false on size >= 6 (Party.java:373)
        when(gateway.joinAgentParty(olwenBot, 77))
                .thenAnswer(inv -> CosmicPartyGateway.INSTANCE.joinAgentParty(olwenBot, 77));

        String reply;
        try (MockedStatic<AgentPartyGatewayRuntime> gateways = mockStatic(AgentPartyGatewayRuntime.class)) {
            gateways.when(AgentPartyGatewayRuntime::party).thenReturn(gateway);
            reply = RosterVoice.line(OLWEN, roster.call(alice, OLWEN));
        }

        verify(gateway).joinAgentParty(olwenBot, 77);
        verify(olwenBot, never()).updatePartyMemberHP();
        // the join reports PARTY_FULL: the companion is despawned, the row keeps its AWAY slot, and it says why
        assertEquals("Olwen could not join right now.", reply); // the reason goes to the event log
        assertEquals(RosterState.AWAY, rows.get(key(ALICE, "olwen")).state());
        assertEquals(List.of(), saved);
        cleanup.verify(() -> AgentRuntimeCleanupService.removeAgentByCharacterId(OLWEN_CHARACTER));
        assertNull(leases.holder(OLWEN_CHARACTER));
        verify(residents).whisper(OLWEN, alice, "your party's full. i'll wait at camp.");
    }

    // ---- 3. a runtime held elsewhere -----------------------------------------------------

    @Test
    void aRuntimeHeldElsewhereIsRefusedNotRemoved() {
        seed(row(ALICE, "olwen", RosterState.BENCHED, 0L));
        RosterService roster = new RosterService(world);
        List<String> calls = new ArrayList<>();
        // whatever holds character 900 without a lease (a spawnbot, a party quest); it does not follow Alice
        registry.when(() -> AgentRuntimeRegistry.hasActiveAgentCharacterId(OLWEN_CHARACTER)).thenReturn(true);
        cleanup.when(() -> AgentRuntimeCleanupService.removeAgentByCharacterId(OLWEN_CHARACTER))
                .thenAnswer(inv -> calls.add("remove " + OLWEN_CHARACTER));
        interaction.when(() -> AgentInteractionRuntime.spawnAgentForLeader(alice, "Olwen")).thenAnswer(inv -> {
            calls.add("spawn Olwen");
            return AgentSpawnResult.ok(olwenBot, true);
        });

        String reply;
        try (MockedStatic<AgentPartyLifecycleService> party = joined()) {
            reply = RosterVoice.line(OLWEN, roster.call(alice, OLWEN));
        }

        // nothing is pulled out from under its holder and nothing is loaded; the companion asks for a moment
        assertEquals(List.of(), calls);
        assertEquals("Olwen could not join right now.", reply); // the reason goes to the event log
        assertEquals(RosterState.BENCHED, rows.get(key(ALICE, "olwen")).state());
        assertNull(leases.holder(OLWEN_CHARACTER));
        verify(residents).whisper(OLWEN, alice, "give me a moment. i'm in the middle of something.");
    }

    // ---- 4. freeSlots --------------------------------------------------------------------

    @Test
    void soloFreeSlotsCountOnlyOwnAwayCompanions() {
        seed(row(ALICE, "olwen", RosterState.AWAY, 0L));
        seed(row(ALICE, "brin", RosterState.AWAY, 0L));
        seed(row(ALICE, "cato", RosterState.BENCHED, 0L));
        // ACTIVE in the roster but in no party (a failed join, or a party that was left or disbanded)
        seed(row(ALICE, "dara", RosterState.ACTIVE, 0L));
        RosterService roster = new RosterService(world);

        // alone: 5 seats less the 2 AWAY companions' reservations; dara is not counted
        assertEquals(3, roster.freeSlots(alice));
    }

    @Test
    void humansInOnePartyGetAFairShareOfTheFreeSeats() {
        Character bob = character(BOB, "Bob");
        Character botInParty = character(901, "Brin");
        Party party = mock(Party.class);
        List<PartyCharacter> members = List.of(member(alice, false), member(bob, false), member(botInParty, true));
        when(party.getMembers()).thenReturn(members);
        when(alice.getParty()).thenReturn(party);
        when(bob.getParty()).thenReturn(party);
        seed(row(ALICE, "olwen", RosterState.AWAY, 0L));
        seed(row(ALICE, "dara", RosterState.ACTIVE, 0L)); // following Alice but not a party member
        RosterService roster = new RosterService(world);

        try (MockedStatic<AgentPresence> presence = mockStatic(AgentPresence.class)) {
            presence.when(() -> AgentPresence.isAgent(botInParty)).thenReturn(true);
            // P2a fair share (CompanionSlots): with 2 humans each gets floor(4/2) = 2 seats. Alice's AWAY companion
            // holds one of hers, Brin (nobody's companion) only occupies a seat, and dara, not a member, holds none
            assertEquals(1, roster.freeSlots(alice));
            assertEquals(2, roster.freeSlots(bob));
        }
    }

    // ---- helpers -------------------------------------------------------------------------

    /** The party join stubbed to succeed. */
    private MockedStatic<AgentPartyLifecycleService> joined() {
        MockedStatic<AgentPartyLifecycleService> party = mockStatic(AgentPartyLifecycleService.class);
        party.when(() -> AgentPartyLifecycleService.joinAgentToLeaderParty(alice, olwenBot)).thenReturn(JoinOutcome.JOINED);
        return party;
    }

    private void seed(RosterEntry entry) {
        rows.put(key(entry.characterId(), entry.personaId()), entry);
    }

    private static String key(int ownerId, String personaId) {
        return ownerId + ":" + personaId;
    }

    private static RosterEntry row(int ownerId, String personaId, RosterState state, long accruedExp) {
        return new RosterEntry(ownerId, personaId, state, Assignment.TRAIN, 0, 0, 104000000, accruedExp,
                0L, 0L, 1_000L, 1_000L, 1_000L, 0, null);
    }

    private static List<AgentPartyMemberSnapshot> sixMembers() {
        List<AgentPartyMemberSnapshot> members = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            members.add(new AgentPartyMemberSnapshot(ALICE + i, "member" + i, i == 0, 104000000));
        }
        return members;
    }

    private static PartyCharacter member(Character player, boolean bot) {
        PartyCharacter member = mock(PartyCharacter.class);
        int id = player.getId();
        when(member.getId()).thenReturn(id);
        when(member.getPlayer()).thenReturn(player);
        when(member.isOnline()).thenReturn(true);
        when(member.isBot()).thenReturn(bot);
        return member;
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
