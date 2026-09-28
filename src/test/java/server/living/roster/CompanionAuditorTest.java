package server.living.roster;

import client.Character;
import net.server.world.CauseScope;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import net.server.world.PartyOperation;
import net.server.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;
import server.agents.capabilities.party.AgentPartyLifecycleService;
import server.agents.capabilities.party.JoinOutcome;
import server.agents.runtime.AgentInteractionRuntime;
import server.agents.runtime.AgentLifecycleService.AgentSpawnResult;
import server.agents.runtime.AgentRuntimeCleanupService;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.LivingWorld;
import server.living.arc.ArcService;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.resident.ResidentRuntime;
import server.living.roster.CompanionAuditor.Finding;
import server.living.roster.CompanionAuditor.Repair;
import server.living.roster.CompanionDriftReport.AgentRef;
import server.living.roster.CompanionDriftReport.Code;
import server.living.roster.CompanionDriftReport.Companion;
import server.living.roster.CompanionDriftReport.Live;
import server.living.roster.CompanionDriftReport.Member;
import server.living.roster.CompanionDriftReport.Owner;
import server.living.roster.CompanionDriftReport.Row;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CompanionAuditor (docs/BOT_MANAGEMENT_PLAN.md P2a): drift is acted on only when seen on two runs in a row, is
 * repaired toward the roster row (never toward the party), gets one repair and one "did not hold" per episode, and
 * the roster's repairs never write the row.
 */
class CompanionAuditorTest {
    private static final int ALICE = 10;
    private static final int PARTY = 50;
    private static final int OLWEN_CHARACTER = 900;
    private static final Persona OLWEN = CompanionChatVerbsTest.persona("olwen", "Olwen");

    private final LivingWorld world = mock(LivingWorld.class);
    private final RosterService roster = mock(RosterService.class);
    private final FakeLivingStore rows = new FakeLivingStore();
    private final LivingContent content = mock(LivingContent.class);
    private final Character alice = character(ALICE, "Alice");
    private long now = 1_000_000L;
    private CompanionAuditor auditor;

    @BeforeEach
    void wireWorld() {
        when(world.roster()).thenReturn(roster);
        when(world.store()).thenReturn(rows.store);
        when(world.content()).thenReturn(content);
        when(content.persona("olwen")).thenReturn(OLWEN);
        when(roster.onlineCharacter(ALICE)).thenReturn(alice);
        auditor = new CompanionAuditor(world, () -> now);
    }

    // ---- detection -----------------------------------------------------------------------

    @Test
    void driftSeenOnceIsOnlyWatched() {
        assertEquals(List.of(), review(notLoaded()));

        CompanionAuditor.Status status = auditor.status();
        assertEquals(1, status.driftNow());
        assertEquals(Map.of(Code.ACTIVE_NOT_LOADED, 1), status.driftByCode());
        assertEquals(1, status.open().size());
        assertEquals(1, status.open().get(0).sightings());
        assertFalse(status.open().get(0).repaired());
        assertEquals(0, status.confirmed());
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"ACTIVE, RESEAT", "BENCHED, UNSEAT", "AWAY, UNSEAT", "TRAVELLING, NONE", "HOLD, NONE", "PARKED, NONE"})
    void driftSeenTwiceIsRepairedTowardTheRosterRow(RosterState state, Repair repair) {
        // P2b: a TRAVELLING companion in the party is legal; one that lost its party is drift, reported only
        CompanionDriftReport report = state == RosterState.ACTIVE ? notLoaded()
                : state == RosterState.TRAVELLING ? outOfTheParty(state) : stillInParty(state);
        review(report);

        List<Finding> findings = review(report);

        assertEquals(1, findings.size());
        assertEquals(repair, findings.get(0).repair(), "the row's state decides, whatever the party shows");
        assertFalse(findings.get(0).stuck());
        assertEquals(1, auditor.status().confirmed());
        assertEquals(repair == Repair.NONE ? 0 : 1, auditor.status().repairs());
    }

    @Test
    void aRepairThatDidNotHoldIsReportedOnceAndNeverRetried() {
        review(notLoaded());
        assertEquals(Repair.RESEAT, review(notLoaded()).get(0).repair());

        List<Finding> third = review(notLoaded());
        assertEquals(1, third.size());
        assertTrue(third.get(0).stuck());
        assertEquals(Repair.NONE, third.get(0).repair());
        for (int run = 0; run < 5; run++) {
            assertEquals(List.of(), review(notLoaded()), "no repair loop");
        }

        CompanionAuditor.Status status = auditor.status();
        assertEquals(1, status.repairs());
        assertEquals(1, status.stuck());
        assertTrue(status.open().get(0).stuck());
    }

    @Test
    void aCleanRunEndsTheEpisodeAndANewDriftStartsOver() {
        review(notLoaded());
        review(notLoaded());
        review(clean());

        assertTrue(auditor.status().open().isEmpty());
        assertEquals(List.of(), review(notLoaded()), "a new episode is watched first again");
        assertEquals(Repair.RESEAT, review(notLoaded()).get(0).repair());
        assertEquals(2, auditor.status().repairs());
    }

    @Test
    void informationalDriftIsNeverActedOn() {
        CompanionDriftReport unrostered = CompanionDriftReport.of(owner(PARTY, 202), 4, List.of(),
                List.of(new AgentRef(202, "Spawned")));

        review(unrostered);
        assertEquals(List.of(), review(unrostered));
        assertTrue(auditor.status().open().isEmpty());
        assertEquals(0, auditor.status().driftNow());
    }

    @Test
    void aVerbInFlightIsNeitherDriftNorClean() {
        review(notLoaded());
        when(roster.inFlight(ALICE, "olwen")).thenReturn(true);

        assertEquals(List.of(), review(notLoaded()), "a call under way is not repaired");
        assertEquals(1, auditor.status().open().get(0).sightings(), "nor counted");

        when(roster.inFlight(ALICE, "olwen")).thenReturn(false);
        assertEquals(Repair.RESEAT, review(notLoaded()).get(0).repair());
    }

    @Test
    void anOwnerInAnInstanceIsDeferredAndAnOwnerWhoLeftIsForgotten() {
        review(notLoaded());

        assertEquals(List.of(), auditor.review(List.of(), Set.of(ALICE), now));
        assertEquals(1, auditor.status().open().size(), "deferred: the episode waits");
        assertEquals(1, auditor.status().ownersDeferred());

        auditor.review(List.of(), Set.of(), now);
        assertTrue(auditor.status().open().isEmpty(), "logged off");
    }

    // ---- apply ---------------------------------------------------------------------------

    @Test
    void aRepairIsLoggedAsDriftAndRunsUnderTheAuditCause() {
        List<String> causes = new ArrayList<>();
        doAnswer(inv -> causes.add("reseat " + CauseScope.current())).when(roster).reseat(alice, OLWEN);
        doAnswer(inv -> causes.add("unseat " + CauseScope.current())).when(roster).unseat(alice, OLWEN);

        auditor.apply(new Finding(ALICE, "olwen", List.of(Code.ACTIVE_NOT_LOADED), "not loaded", Repair.RESEAT, false));
        auditor.apply(new Finding(ALICE, "olwen", List.of(Code.IN_PARTY_NOT_ACTIVE), "in party", Repair.UNSEAT, false));

        assertEquals(List.of("reseat companion.audit", "unseat companion.audit"), causes);
        assertEquals(null, CauseScope.current(), "the cause token ends with the repair");
        List<String> drift = rows.events("companion.drift");
        assertEquals(2, drift.size());
        assertTrue(drift.get(0).startsWith("10 olwen companion.drift [ACTIVE_NOT_LOADED] not loaded; repair RESEAT"), drift.get(0));
    }

    @Test
    void aStuckOrUnrepairableFindingIsOnlyLogged() {
        auditor.apply(new Finding(ALICE, "olwen", List.of(Code.ACTIVE_NOT_LOADED), "not loaded", Repair.NONE, true));
        auditor.apply(new Finding(ALICE, "olwen", List.of(Code.IN_PARTY_NOT_ACTIVE), "parked", Repair.NONE, false));

        verify(roster, never()).reseat(any(), any());
        verify(roster, never()).unseat(any(), any());
        assertTrue(rows.events("companion.drift").get(0).endsWith("the repair did not hold"));
    }

    @Test
    void aRepairForAnOwnerWhoLeftDoesNothing() {
        when(roster.onlineCharacter(ALICE)).thenReturn(null);

        auditor.apply(new Finding(ALICE, "olwen", List.of(Code.ACTIVE_NOT_LOADED), "not loaded", Repair.RESEAT, false));

        verify(roster, never()).reseat(any(), any());
        assertEquals(1, rows.events("companion.drift").size());
    }

    // ---- the roster's repairs ------------------------------------------------------------

    @Nested
    class RosterRepairs {
        private final ResidentRuntime residents = mock(ResidentRuntime.class);
        private final Character olwenBot = character(OLWEN_CHARACTER, "Olwen");
        private MockedStatic<AgentRuntimeRegistry> registry;
        private MockedStatic<AgentRuntimeCleanupService> cleanup;
        private MockedStatic<AgentInteractionRuntime> interaction;
        private MockedStatic<AgentPartyLifecycleService> party;
        private MockedStatic<Party> parties;
        private RosterService real;

        @BeforeEach
        void realRoster() {
            when(world.residents()).thenReturn(residents);
            when(residents.ensureCharacter(OLWEN)).thenReturn(OLWEN_CHARACTER);
            when(residents.characterIdOf("olwen")).thenReturn(OLWEN_CHARACTER);
            when(world.config()).thenReturn(new config.LivingWorldConfig());
            when(world.slots()).thenReturn(new CompanionSlots(world));
            when(world.leases()).thenReturn(new CompanionLeases());
            when(world.benchSessions()).thenReturn(mock(BenchSessions.class));
            when(world.bench()).thenReturn(mock(BenchLedger.class));
            when(world.arcs()).thenReturn(mock(ArcService.class));
            CompanionBudget budget = mock(CompanionBudget.class);
            when(budget.admit(any(), any(Character.class))).thenReturn(CompanionBudget.Admission.ALLOW);
            when(world.budget()).thenReturn(budget);
            registry = mockStatic(AgentRuntimeRegistry.class);
            cleanup = mockStatic(AgentRuntimeCleanupService.class);
            interaction = mockStatic(AgentInteractionRuntime.class);
            party = mockStatic(AgentPartyLifecycleService.class);
            parties = mockStatic(Party.class);
            interaction.when(() -> AgentInteractionRuntime.spawnAgentForLeader(alice, "Olwen"))
                    .thenReturn(AgentSpawnResult.ok(olwenBot, true));
            party.when(() -> AgentPartyLifecycleService.joinAgentToLeaderParty(alice, olwenBot)).thenReturn(JoinOutcome.JOINED);
        }

        @AfterEach
        void closeStatics() {
            parties.close();
            party.close();
            interaction.close();
            cleanup.close();
            registry.close();
        }

        @Test
        void reseatLetsGoAndSeatsTheActiveCompanionAgainInTheOwnersParty() {
            rows.seed(row(RosterState.ACTIVE));
            real = new RosterService(world);

            real.reseat(alice, OLWEN);

            cleanup.verify(() -> AgentRuntimeCleanupService.removeAgentByCharacterId(OLWEN_CHARACTER));
            interaction.verify(() -> AgentInteractionRuntime.spawnAgentForLeader(alice, "Olwen"));
            party.verify(() -> AgentPartyLifecycleService.joinAgentToLeaderParty(alice, olwenBot));
            assertEquals(RosterState.ACTIVE, rows.row(ALICE, "olwen").state(), "the row is the truth: it stays ACTIVE");
            assertTrue(rows.saved.stream().allMatch(saved -> saved.state() == RosterState.ACTIVE), "never benched toward the party");
        }

        @Test
        void reseatRejoinsInPlaceACompanionStandingWithItsOwnerOutsideTheParty() {
            rows.seed(row(RosterState.ACTIVE));
            real = new RosterService(world);
            loaded(ALICE);
            MapleMap field = mock(MapleMap.class);
            when(alice.getMap()).thenReturn(field);
            when(olwenBot.getMap()).thenReturn(field);
            client.Client aliceClient = mock(client.Client.class);
            when(aliceClient.getChannel()).thenReturn(1);
            client.BotClient olwenClient = mock(client.BotClient.class);
            when(olwenClient.getChannel()).thenReturn(1);
            when(alice.getClient()).thenReturn(aliceClient);
            when(olwenBot.getClient()).thenReturn(olwenClient);
            Party ownerParty = party(PARTY);
            PartyCharacter aliceMember = mock(PartyCharacter.class);
            when(aliceMember.getId()).thenReturn(ALICE);
            when(aliceMember.isOnline()).thenReturn(true);
            when(ownerParty.getMembers()).thenReturn(List.of(aliceMember));
            when(alice.getParty()).thenReturn(ownerParty);
            party.when(() -> AgentPartyLifecycleService.joinAgentToLeaderParty(alice, olwenBot)).thenAnswer(inv -> {
                assertEquals(CompanionSlots.Kind.TRAVEL, world.slots().reservation("olwen").kind()); // the seat gate's seat
                return JoinOutcome.JOINED;
            });

            real.reseat(alice, OLWEN);

            party.verify(() -> AgentPartyLifecycleService.joinAgentToLeaderParty(alice, olwenBot));
            cleanup.verifyNoInteractions(); // never unloaded in front of its owner: no pop-out
            interaction.verifyNoInteractions(); // and never loaded again at a portal
            assertEquals(List.of(), rows.saved, "the row is never written");
        }

        @Test
        void reseatAndUnseatLeaveRowsOfOtherStatesAlone() {
            rows.seed(row(RosterState.BENCHED));
            real = new RosterService(world);
            real.reseat(alice, OLWEN);

            rows.seed(row(RosterState.ACTIVE));
            real.unseat(alice, OLWEN);

            cleanup.verifyNoInteractions();
            interaction.verifyNoInteractions();
            party.verifyNoInteractions();
            assertEquals(List.of(), rows.saved);
        }

        @Test
        void unseatUnloadsABenchedCompanionThatStillFollowsItsOwner() {
            rows.seed(row(RosterState.BENCHED));
            real = new RosterService(world);
            loaded(ALICE);

            real.unseat(alice, OLWEN);

            party.verify(() -> AgentPartyLifecycleService.leaveAgentParty(olwenBot));
            cleanup.verify(() -> AgentRuntimeCleanupService.removeAgentByCharacterId(OLWEN_CHARACTER));
            assertEquals(RosterState.BENCHED, rows.row(ALICE, "olwen").state());
            assertEquals(List.of(), rows.saved, "the row is never written");
        }

        @Test
        void unseatOnlyTakesABenchSliceOutOfTheOwnersParty() {
            rows.seed(row(RosterState.AWAY));
            real = new RosterService(world);
            loaded(OLWEN_CHARACTER); // a live bench slice leads itself
            Party ownerParty = party(PARTY);
            when(alice.getParty()).thenReturn(ownerParty);
            when(olwenBot.getParty()).thenReturn(ownerParty);

            real.unseat(alice, OLWEN);

            party.verify(() -> AgentPartyLifecycleService.leaveAgentParty(olwenBot));
            cleanup.verifyNoInteractions();
            assertEquals(List.of(), rows.saved);
        }

        @Test
        void unseatExpelsAnOfflineMember() {
            rows.seed(row(RosterState.BENCHED));
            real = new RosterService(world);
            Party ownerParty = party(PARTY);
            PartyCharacter offline = mock(PartyCharacter.class);
            when(offline.isOnline()).thenReturn(false);
            when(ownerParty.getMemberById(OLWEN_CHARACTER)).thenReturn(offline);
            when(alice.getParty()).thenReturn(ownerParty);
            World channelWorld = mock(World.class);
            when(alice.getWorldServer()).thenReturn(channelWorld);

            real.unseat(alice, OLWEN);

            verify(channelWorld).updateParty(PARTY, PartyOperation.EXPEL, offline);
            assertEquals(List.of(), rows.saved);
        }

        /** Olwen's character is loaded under an Agent runtime led by {@code leaderId}. */
        private void loaded(int leaderId) {
            AgentRuntimeEntry runtime = new AgentRuntimeEntry(olwenBot, alice, null);
            registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(OLWEN_CHARACTER)).thenReturn(runtime);
            registry.when(() -> AgentRuntimeRegistry.leaderIdForAgentCharacter(OLWEN_CHARACTER)).thenReturn(leaderId);
        }
    }

    // ---- helpers -------------------------------------------------------------------------

    private List<Finding> review(CompanionDriftReport report) {
        now += CompanionAuditor.INTERVAL_MS;
        return auditor.review(List.of(report), Set.of(), now);
    }

    private static CompanionDriftReport notLoaded() {
        return CompanionDriftReport.of(owner(PARTY), 4,
                List.of(new Companion(rowOf(RosterState.ACTIVE), Live.unloaded(-1))), List.of());
    }

    private static CompanionDriftReport stillInParty(RosterState state) {
        return CompanionDriftReport.of(owner(PARTY, OLWEN_CHARACTER), 4,
                List.of(new Companion(rowOf(state), new Live(true, 1, 100000000, 500, 800, PARTY, ALICE))), List.of());
    }

    private static CompanionDriftReport outOfTheParty(RosterState state) {
        return CompanionDriftReport.of(owner(PARTY), 4,
                List.of(new Companion(rowOf(state), new Live(true, 1, 104000000, 500, 800, 0, ALICE))), List.of());
    }

    private static CompanionDriftReport clean() {
        return CompanionDriftReport.of(owner(PARTY, OLWEN_CHARACTER), 4,
                List.of(new Companion(rowOf(RosterState.ACTIVE), new Live(true, 1, 100000000, 500, 800, PARTY, ALICE))), List.of());
    }

    private static Owner owner(int partyId, int... bots) {
        List<Member> members = new ArrayList<>();
        members.add(new Member(ALICE, "Alice", false));
        for (int bot : bots) {
            members.add(new Member(bot, "bot" + bot, true));
        }
        return new Owner(ALICE, "Alice", 1, 100000000, partyId, members);
    }

    private static Row rowOf(RosterState state) {
        return new Row("olwen", "Olwen", state, Assignment.TRAIN, 104000000, OLWEN_CHARACTER);
    }

    private static RosterEntry row(RosterState state) {
        return new RosterEntry(ALICE, "olwen", state, Assignment.TRAIN, 0, 0, 104000000, 0L, 0L, 0L, 1L, 1L, 1L, 0, null);
    }

    private static Party party(int id) {
        Party party = mock(Party.class);
        when(party.getId()).thenReturn(id);
        when(party.getMembers()).thenReturn(List.of());
        return party;
    }

    private static Character character(int id, String name) {
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(id);
        when(character.getName()).thenReturn(name);
        when(character.isLoggedin()).thenReturn(true);
        return character;
    }
}
