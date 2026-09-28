package server.living.roster;

import client.BotClient;
import client.Character;
import client.Client;
import client.Job;
import config.LivingWorldConfig;
import config.YamlConfig;
import net.server.Server;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import net.server.world.PartyEvents;
import net.server.world.PartySeatGate;
import net.server.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.TimerManager;
import server.agents.capabilities.party.AgentPartyLifecycleService;
import server.agents.capabilities.party.JoinOutcome;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestCandidateScope;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestLobbyProfile;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestLobbyRegistry;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestLobbyRuntime;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestLobbySession;
import server.agents.runtime.AgentInteractionRuntime;
import server.agents.runtime.AgentLifecycleService.AgentSpawnResult;
import server.agents.runtime.AgentRuntimeCleanupService;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.LivingWorld;
import server.living.arc.ArcService;
import server.living.config.Content.Persona;
import server.living.resident.ResidentRuntime;
import server.living.roster.CompanionSlots.JoinVerdict;
import server.living.roster.CompanionSlots.Kind;
import server.living.roster.CompanionSlots.Reservation;
import server.living.store.LivingStore;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Party.joinParty seat gate (docs/BOT_MANAGEMENT_PLAN.md P2a): a bot joining a party with a human holds a
 * CompanionSlots reservation charged to a human in it, or is exempt. The legitimate paths, each tested here:
 * <ul>
 *   <li>a roster load ({@code RosterService} call, recruit, arrival, login restore): TRAVEL, or the AWAY seat it kept;</li>
 *   <li>your own companion re-invited while out (router ACCEPT): a TRAVEL seat taken by the router;</li>
 *   <li>a citizen guest (router accept): GUEST, charged to the inviter (PartyInviteRoutingTest);</li>
 *   <li>bot-only parties: SocialFabric circles, citizen-led PQ lobbies, expedition overflow parties;</li>
 *   <li>Agents nobody has claimed: {@code @spawnbot}, {@code !skillprobe}, fixtures, SoloMapling bots;</li>
 *   <li>Agent PQ runs: a registered engagement ({@code !kpqtest}, the PQ test services) and a lobby waiter's
 *       DELAY_ACCEPT answer to a human leader.</li>
 * </ul>
 * Anything else is refused and logged as {@code companion.unseated_join}.
 */
class SeatGateTest {
    private static final int ANN = 10;
    private static final int BEN = 20;
    private static final int OLWEN_CHARACTER = 900;
    private static final int MIRA = 950;
    private static final Set<Integer> HUMANS = Set.of(ANN, BEN);

    // ---- the rule ----------------------------------------------------------------------

    @Test
    void aBotOnlyPartyNeedsNoSeat() {
        assertEquals(JoinVerdict.ADMIT_BOT_ONLY, CompanionSlots.decideJoin(false, true, null, Set.of(), () -> false));
    }

    @Test
    void anAgentNobodyClaimedNeedsNoSeat() {
        assertEquals(JoinVerdict.ADMIT_UNCLAIMED, CompanionSlots.decideJoin(true, false, null, HUMANS, () -> false));
    }

    @Test
    void everyKindOfReservationChargedToAHumanInThePartySeatsIt() {
        for (Kind kind : Kind.values()) {
            Reservation held = new Reservation(ANN, "olwen", kind, Long.MAX_VALUE, 1);
            assertEquals(JoinVerdict.ADMIT_SEATED, CompanionSlots.decideJoin(true, true, held, HUMANS,
                    () -> { throw new AssertionError("the PQ locks are not taken for a seated bot"); }), kind.name());
        }
    }

    @Test
    void aSeatChargedToSomeoneOutsideThePartyDoesNotCount() {
        Reservation held = new Reservation(30, "olwen", Kind.TRAVEL, Long.MAX_VALUE, 1);
        assertEquals(JoinVerdict.REFUSE_UNSEATED, CompanionSlots.decideJoin(true, true, held, HUMANS, () -> false));
    }

    @Test
    void anAgentPartyQuestRunIsExempt() {
        assertEquals(JoinVerdict.ADMIT_PQ, CompanionSlots.decideJoin(true, true, null, HUMANS, () -> true));
    }

    @Test
    void aClaimedBotWithNoSeatIsRefused() {
        assertFalse(CompanionSlots.decideJoin(true, true, null, HUMANS, () -> false).admits());
    }

    // ---- Party.joinParty asks the installed gate ------------------------------------------

    @Test
    void aRefusedBotDoesNotJoin() {
        withWorld((world, party) -> {
            Character bot = character(OLWEN_CHARACTER, "Olwen", new BotClient(0, 1), world);
            List<PartyEvents.PartyChangeEvent> events = new ArrayList<>();
            PartyEvents.install(events::add);
            PartySeatGate.install((joining, target) -> false);

            assertFalse(Party.joinParty(bot, party.getId(), false));

            assertEquals(1, party.getMembers().size());
            assertTrue(events.isEmpty());
        });
    }

    @Test
    void anAdmittedBotJoins() {
        withWorld((world, party) -> {
            Character bot = character(OLWEN_CHARACTER, "Olwen", new BotClient(0, 1), world);
            PartySeatGate.install((joining, target) -> joining == bot && target == party);

            assertTrue(Party.joinParty(bot, party.getId(), false));

            assertEquals(2, party.getMembers().size());
        });
    }

    @Test
    void aHumanNeverAsksTheGate() {
        withWorld((world, party) -> {
            Character ben = character(BEN, "Ben", new Client(Client.Type.CHANNEL, -1, "test", null, 0, 1), world);
            PartySeatGate.install((joining, target) -> {
                throw new AssertionError("the gate is for bots");
            });

            assertTrue(Party.joinParty(ben, party.getId(), false));
        });
    }

    @Test
    void withNoGateOrAFailingOneEveryJoinGoesThroughAsBefore() {
        withWorld((world, party) -> {
            Character bot = character(OLWEN_CHARACTER, "Olwen", new BotClient(0, 1), world);
            assertTrue(Party.joinParty(bot, party.getId(), false));

            Character second = character(901, "Brin", new BotClient(0, 1), world);
            PartySeatGate.install((joining, target) -> {
                throw new IllegalStateException("gate bug");
            });
            assertTrue(Party.joinParty(second, party.getId(), false));
        });
    }

    // ---- the living gate ------------------------------------------------------------------

    private final LivingWorld living = mock(LivingWorld.class);
    private final CompanionDirectory directory = mock(CompanionDirectory.class);
    private final ResidentRuntime residents = mock(ResidentRuntime.class);
    private final RosterService roster = mock(RosterService.class);
    private final LivingStore store = mock(LivingStore.class);
    private final CompanionExecutor executor = new CompanionExecutor();
    private CompanionSlots slots;

    @BeforeEach
    void wireLiving() {
        when(living.directory()).thenReturn(directory);
        when(living.residents()).thenReturn(residents);
        when(living.roster()).thenReturn(roster);
        when(living.store()).thenReturn(store);
        when(living.executor()).thenReturn(executor);
        Persona olwen = mock(Persona.class);
        when(olwen.id()).thenReturn("olwen");
        when(residents.personaOfCharacterId(OLWEN_CHARACTER)).thenReturn(olwen);
        when(directory.isClaimed(OLWEN_CHARACTER)).thenReturn(true);
        when(directory.isClaimed(MIRA)).thenReturn(true);
        when(directory.ownerOfCharacter(OLWEN_CHARACTER)).thenReturn(ANN);
        slots = new CompanionSlots(living);
    }

    @AfterEach
    void stopExecutor() {
        executor.shutdown();
        PartySeatGate.install(null);
        PartyEvents.install(null);
    }

    @Test
    void aCompanionOnATravelSeatJoinsItsOwnersParty() {
        slots.reserve(ANN, "olwen", Kind.TRAVEL, Long.MAX_VALUE);

        assertTrue(slots.admitsJoin(bot(OLWEN_CHARACTER), party(member(ANN, false))));
    }

    @Test
    void aCompanionOnItsKeptAwaySeatJoins() {
        slots.reserve(ANN, "olwen", Kind.AWAY, Long.MAX_VALUE);

        assertTrue(slots.admitsJoin(bot(OLWEN_CHARACTER), party(member(ANN, false), member(BEN, false))));
    }

    @Test
    void anUnseatedCompanionIsRefusedAndLogged() {
        assertFalse(slots.admitsJoin(bot(OLWEN_CHARACTER), party(member(ANN, false))));

        verify(store, timeout(5_000)).event(eq(ANN), eq("olwen"), eq("companion.unseated_join"), anyString());
    }

    @Test
    void aCompanionSeatedForAnotherPartyIsRefused() {
        slots.reserve(ANN, "olwen", Kind.TRAVEL, Long.MAX_VALUE);

        assertFalse(slots.admitsJoin(bot(OLWEN_CHARACTER), party(member(BEN, false))));
    }

    @Test
    void aCitizenGuestJoinsOnItsHostsGuestSeat() {
        Party party = party(member(ANN, false));
        assertFalse(slots.admitsJoin(bot(MIRA), party));

        slots.reserve(ANN, CompanionSlots.characterKey(MIRA), Kind.GUEST, Long.MAX_VALUE);
        assertTrue(slots.admitsJoin(bot(MIRA), party));
    }

    @Test
    void aGmSpawnedAgentNeedsNoSeat() {
        assertTrue(slots.admitsJoin(bot(960), party(member(ANN, false))));
        verify(store, never()).event(anyInt(), any(), anyString(), any());
    }

    @Test
    void citizensFormingTheirOwnPartyNeedNoSeat() {
        assertTrue(slots.admitsJoin(bot(MIRA), party(member(951, true))));
    }

    @Test
    void anAgentInARegisteredPartyQuestNeedsNoSeat() {
        try (MockedStatic<AgentPartyQuestEngagementRegistry> engagements = mockStatic(AgentPartyQuestEngagementRegistry.class)) {
            engagements.when(() -> AgentPartyQuestEngagementRegistry.forMember(MIRA))
                    .thenReturn(mock(AgentPartyQuestEngagement.class));

            assertTrue(slots.admitsJoin(bot(MIRA), party(member(ANN, false))));
        }
    }

    @Test
    void aLobbyWaiterAcceptingAHumanLeaderNeedsNoSeat() {
        PartyCharacter leader = member(ANN, false);
        Character ann = mock(Character.class);
        when(leader.getPlayer()).thenReturn(ann);
        Character mira = bot(MIRA);
        try (MockedStatic<AgentPartyQuestLobbyRuntime> lobby = mockStatic(AgentPartyQuestLobbyRuntime.class)) {
            lobby.when(() -> AgentPartyQuestLobbyRuntime.decidePartyInvite(mira, ann))
                    .thenReturn(AgentPartyQuestLobbyRuntime.InviteDecision.DELAY_ACCEPT);

            assertTrue(slots.admitsJoin(mira, party(leader)));
        }
    }

    @Test
    void aCitizenACoordinatorsLobbySendsInJoinsBeforeTheLobbyIndexesIt() {
        int recruiter = 951; // a citizen leading a KPQ lobby party a human has joined
        AgentPartyQuestLobbySession lobby = new AgentPartyQuestLobbySession("engagement-seat-gate",
                new AgentPartyQuestLobbyProfile("kpq", 1000, 9000, 21, 30, 4, -50, 50, List.of(), List.of(), List.of()),
                7L, recruiter, 4, AgentPartyQuestCandidateScope.ANY_ELIGIBLE_HUMAN, 1_000L);
        lobby.addMember(recruiter, AgentPartyQuestLobbySession.MemberType.AGENT,
                AgentPartyQuestLobbySession.MemberRole.RECRUITING_LEADER, 1_000L);
        AgentPartyQuestLobbyRegistry.register(lobby);
        try {
            Party party = party(member(recruiter, true), member(ANN, false));
            when(directory.isClaimed(recruiter)).thenReturn(true);

            // the directed join (AgentKpqLobbyAdmissionRuntime.join) joins first and indexes the member after
            assertNull(AgentPartyQuestLobbyRegistry.forMember(MIRA));
            assertTrue(slots.admitsJoin(bot(MIRA), party));
            // a roster companion is still nobody a lobby may send in
            assertFalse(slots.admitsJoin(bot(OLWEN_CHARACTER), party));
        } finally {
            AgentPartyQuestLobbyRegistry.remove(lobby);
        }
    }

    // ---- the roster's own loads hold a seat while they join -------------------------------

    @Test
    void theRosterHoldsASeatWhileItsCompanionJoinsAndGivesItBackAfter() {
        Map<String, RosterEntry> rows = new LinkedHashMap<>();
        rows.put("olwen", new RosterEntry(ANN, "olwen", RosterState.BENCHED, Assignment.TRAIN, 0, 0, 104000000, 0L,
                0L, 0L, 1_000L, 1_000L, 1_000L, 0, null));
        when(store.rosterEntry(eq(ANN), anyString())).thenAnswer(inv -> rows.get(inv.getArgument(1)));
        doAnswer(inv -> {
            RosterEntry entry = inv.getArgument(0);
            rows.put(entry.personaId(), entry);
            return null;
        }).when(store).saveRoster(any());
        Persona olwen = residents.personaOfCharacterId(OLWEN_CHARACTER);
        when(olwen.name()).thenReturn("Olwen");
        when(residents.ensureCharacter(olwen)).thenReturn(OLWEN_CHARACTER);
        when(residents.characterIdOf("olwen")).thenReturn(OLWEN_CHARACTER);
        when(living.slots()).thenReturn(slots);
        when(living.config()).thenReturn(new LivingWorldConfig());
        when(living.benchSessions()).thenReturn(mock(BenchSessions.class));
        when(living.arcs()).thenReturn(mock(ArcService.class));
        when(living.bench()).thenReturn(mock(BenchLedger.class));
        when(living.leases()).thenReturn(new CompanionLeases());
        CompanionBudget budget = mock(CompanionBudget.class);
        when(budget.admit(any(), any(Character.class))).thenReturn(CompanionBudget.Admission.ALLOW);
        when(living.budget()).thenReturn(budget);
        Character ann = mock(Character.class);
        when(ann.getId()).thenReturn(ANN);
        when(ann.getName()).thenReturn("Ann");
        Character olwenBot = bot(OLWEN_CHARACTER);
        Party annsParty = party(member(ANN, false));
        RosterService realRoster = new RosterService(living);

        try (MockedStatic<AgentRuntimeRegistry> registry = mockStatic(AgentRuntimeRegistry.class);
             MockedStatic<AgentRuntimeCleanupService> cleanup = mockStatic(AgentRuntimeCleanupService.class);
             MockedStatic<AgentInteractionRuntime> interaction = mockStatic(AgentInteractionRuntime.class);
             MockedStatic<AgentPartyLifecycleService> lifecycle = mockStatic(AgentPartyLifecycleService.class);
             MockedStatic<Party> parties = mockStatic(Party.class)) {
            interaction.when(() -> AgentInteractionRuntime.spawnAgentForLeader(ann, "Olwen"))
                    .thenReturn(AgentSpawnResult.ok(olwenBot, true));
            lifecycle.when(() -> AgentPartyLifecycleService.joinAgentToLeaderParty(ann, olwenBot)).thenAnswer(inv -> {
                // the join runs through Party.joinParty, whose gate must see a seat for it
                assertEquals(Kind.TRAVEL, slots.reservation("olwen").kind());
                assertTrue(slots.admitsJoin(olwenBot, annsParty));
                return JoinOutcome.JOINED;
            });

            assertEquals("Olwen joins your party.", RosterVoice.line(olwen, realRoster.call(ann, olwen)));
        }

        assertNull(slots.reservation("olwen")); // a member holds its seat without one
        assertEquals(RosterState.ACTIVE, rows.get("olwen").state());
    }

    // ---- helpers -------------------------------------------------------------------------

    private interface WorldTest {
        void run(World world, Party party);
    }

    /** A real World and a party led by Ann; the gate and the listener are removed afterwards. */
    private static void withWorld(WorldTest test) {
        boolean families = YamlConfig.config.server.USE_FAMILY_SYSTEM;
        YamlConfig.config.server.USE_FAMILY_SYSTEM = false;
        try (MockedStatic<TimerManager> timers = mockStatic(TimerManager.class);
             MockedStatic<Server> servers = mockStatic(Server.class)) {
            timers.when(TimerManager::getInstance).thenReturn(mock(TimerManager.class));
            servers.when(Server::getInstance).thenReturn(mock(Server.class));
            World world = new World(0, 0, "", 1, 1, 1, 1, 1, 1, 1, 1f, 1);
            Character ann = character(ANN, "Ann", new Client(Client.Type.CHANNEL, -1, "test", null, 0, 1), world);
            test.run(world, world.createParty(new PartyCharacter(ann)));
        } finally {
            PartySeatGate.install(null);
            PartyEvents.install(null);
            YamlConfig.config.server.USE_FAMILY_SYSTEM = families;
        }
    }

    private static Character character(int id, String name, Client client, World world) {
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(id);
        when(character.getName()).thenReturn(name);
        when(character.getLevel()).thenReturn(30);
        when(character.getClient()).thenReturn(client);
        when(character.getJob()).thenReturn(Job.BEGINNER);
        when(character.getWorldServer()).thenReturn(world);
        when(character.getMap()).thenReturn(mock(MapleMap.class));
        return character;
    }

    private static Character bot(int id) {
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(id);
        when(bot.getName()).thenReturn("bot" + id);
        when(bot.getClient()).thenReturn(mock(BotClient.class));
        return bot;
    }

    private static PartyCharacter member(int id, boolean bot) {
        PartyCharacter member = mock(PartyCharacter.class);
        when(member.getId()).thenReturn(id);
        when(member.isBot()).thenReturn(bot);
        when(member.isOnline()).thenReturn(true);
        return member;
    }

    private static Party party(PartyCharacter leader, PartyCharacter... others) {
        Party party = new Party(77, leader);
        party.addMember(leader);
        for (PartyCharacter other : others) {
            party.addMember(other);
        }
        return party;
    }
}
