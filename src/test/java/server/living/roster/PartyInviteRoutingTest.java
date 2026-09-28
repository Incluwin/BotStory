package server.living.roster;

import client.BotClient;
import client.Character;
import client.Client;
import config.LivingWorldConfig;
import net.packet.InPacket;
import net.packet.Packet;
import net.server.PlayerStorage;
import net.server.channel.handlers.PartyOperationHandler;
import net.server.coordinator.world.InviteCoordinator;
import net.server.coordinator.world.InviteCoordinator.InviteResult;
import net.server.coordinator.world.InviteCoordinator.InviteResultType;
import net.server.coordinator.world.InviteCoordinator.InviteType;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import net.server.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import scripting.event.EventInstanceManager;
import server.TimerManager;
import server.agents.capabilities.partyquest.lobby.AgentPartyQuestLobbyRuntime;
import server.agents.runtime.AgentInteractionRuntime;
import server.agents.runtime.AgentLifecycleService.AgentSpawnResult;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.resident.ResidentRuntime;
import server.living.roster.CompanionDirectory.Relation;
import server.living.store.LivingStore;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import testutil.HandlerTest;
import testutil.Packets;
import tools.PacketCreator;

import java.lang.reflect.Constructor;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Party invite by name (op 4) through {@link PartyOperationHandler} and {@link CompanionPartyRouter}: target kind
 * x party state -> outcome and status code, and nothing is ever spawned or seated on a refusal
 * (docs/BOT_MANAGEMENT_PLAN.md P1).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PartyInviteRoutingTest extends HandlerTest {
    private static final int ANN = 10;
    private static final int PARTY_ID = 77;
    private static final int MIRA = 950;   // a citizen
    private static final int PROBE = 960;  // a plain Agent

    private final PartyOperationHandler handler = new PartyOperationHandler();
    private final LivingWorldConfig config = new LivingWorldConfig();

    @Mock
    private World world;
    @Mock
    private PlayerStorage storage;
    @Mock
    private Party party;
    @Mock
    private LivingWorld living;
    @Mock
    private CompanionDirectory directory;
    @Mock
    private RosterService roster;
    @Mock
    private CompanionBudget budget;
    @Mock
    private CompanionSlots slots;
    @Mock
    private LivingContent content;
    @Mock
    private ResidentRuntime residents;
    @Mock
    private LivingStore store;
    @Mock
    private Persona olwen;
    @Mock
    private CompanionPartySync sync;

    private MockedStatic<LivingWorld> livingWorld;
    private MockedStatic<AgentInteractionRuntime> interaction;
    private MockedStatic<InviteCoordinator> invites;
    private MockedStatic<Party> parties;
    private MockedStatic<TimerManager> timers;
    private final TimerManager timer = mock(TimerManager.class);

    @BeforeEach
    void wire() {
        when(client.getWorldServer()).thenReturn(world);
        when(world.getPlayerStorage()).thenReturn(storage);
        when(chr.getId()).thenReturn(ANN);
        when(chr.getName()).thenReturn("Ann");
        when(chr.getLevel()).thenReturn(30);
        when(chr.getParty()).thenReturn(party);
        when(party.getId()).thenReturn(PARTY_ID);
        when(party.getMembers()).thenReturn(List.of(mock(PartyCharacter.class)));
        when(living.directory()).thenReturn(directory);
        when(living.roster()).thenReturn(roster);
        when(living.budget()).thenReturn(budget);
        when(living.config()).thenReturn(config);
        when(living.content()).thenReturn(content);
        when(living.residents()).thenReturn(residents);
        when(living.store()).thenReturn(store);
        CompanionExecutor ops = DirectExecutor.inline(); // the roster's calls run on companion-ops
        when(living.executor()).thenReturn(ops);
        when(living.partySync()).thenReturn(sync);
        when(chr.getWorldServer()).thenReturn(world);
        when(budget.usage(ANN)).thenReturn(new CompanionBudget.Usage(10, 1, 0, 0, 0));
        when(roster.freeSlots(chr)).thenReturn(4);
        // the stock seat rule under this mock: an AWAY companion kept its seat, anyone else needs a free one
        when(roster.canSeat(any(), any())).thenAnswer(inv -> ((RosterEntry) inv.getArgument(1)).state() == RosterState.AWAY
                || roster.freeSlots(inv.getArgument(0)) > 0);
        when(living.slots()).thenReturn(slots);
        // a party of Ann and her own crew unless a test says otherwise: no seat left means 6/6
        when(slots.occupied(chr)).thenAnswer(inv -> CompanionSlots.PARTY_CAP - roster.freeSlots(chr));
        when(slots.keyOf(anyInt())).thenAnswer(inv -> CompanionSlots.characterKey(inv.getArgument(0)));
        when(slots.reserve(anyInt(), anyString(), any(), anyLong())).thenReturn(true);
        when(olwen.id()).thenReturn("olwen");
        when(olwen.name()).thenReturn("Olwen");
        when(content.persona("olwen")).thenReturn(olwen);
        livingWorld = mockStatic(LivingWorld.class);
        livingWorld.when(LivingWorld::get).thenReturn(living);
        interaction = mockStatic(AgentInteractionRuntime.class);
        invites = mockStatic(InviteCoordinator.class);
        parties = mockStatic(Party.class);
        timers = mockStatic(TimerManager.class);
        timers.when(TimerManager::getInstance).thenReturn(timer);
    }

    @AfterEach
    void close() {
        timers.close();
        parties.close();
        invites.close();
        interaction.close();
        livingWorld.close();
    }

    // ---- your own companion --------------------------------------------------------------

    @Test
    void yourBenchedCompanionIsCalledThroughTheRoster() {
        offline("Olwen", new Relation.OwnCompanion(row(RosterState.BENCHED)));
        when(roster.call(chr, olwen)).thenReturn(new RosterResult.Ok("Olwen is on the way (1 min)."));

        invite("Olwen");

        verify(roster).call(chr, olwen);
        // the companion whispers its ETA itself; the roster's summary line is not printed on top of it
        verify(chr, never()).dropMessage(anyInt(), anyString());
        nothingSpawnedOrSeated();
    }

    @Test
    void yourCompanionIntoAFullPartyIsRefusedWith17() {
        offline("Olwen", new Relation.OwnCompanion(row(RosterState.BENCHED)));
        when(roster.freeSlots(chr)).thenReturn(0);

        invite("Olwen");

        assertStatus(PacketCreator.partyStatusMessage(17));
        verify(roster, never()).call(any(), any());
        nothingSpawnedOrSeated();
    }

    @Test
    void yourCompanionPastYourShareOfASharedPartySaysTheOtherSeatsAreYourFriends() {
        offline("Olwen", new Relation.OwnCompanion(row(RosterState.BENCHED)));
        when(roster.freeSlots(chr)).thenReturn(0);
        when(slots.occupied(chr)).thenReturn(5); // 5/6: the open seat is Ben's share

        invite("Olwen");

        verify(residents).whisper(olwen, chr, CompanionPartyRouter.SHARE_USED_LINE);
        verify(chr, never()).sendPacket(any()); // not the stock "full" while the party window shows a seat
        verify(roster, never()).call(any(), any());
        nothingSpawnedOrSeated();
    }

    @Test
    void yourCompanionIntoASeatTheLeaderCapHoldsEmptySaysItIsTheLeadersDoing() {
        offline("Olwen", new Relation.OwnCompanion(row(RosterState.BENCHED)));
        when(roster.freeSlots(chr)).thenReturn(0);
        when(slots.occupied(chr)).thenReturn(4); // 4/6: the leader expelled one of Ann's
        when(slots.leaderCapped(chr)).thenReturn(true);

        invite("Olwen");

        verify(residents).whisper(olwen, chr, RosterService.LEADER_CAP_LINE);
        verify(residents, never()).whisper(olwen, chr, CompanionPartyRouter.SHARE_USED_LINE);
        verify(chr, never()).sendPacket(any()); // not the stock "full" while the party window shows a seat
        verify(slots, never()).reserve(anyInt(), anyString(), any(), anyLong());
        verify(roster, never()).call(any(), any());
        nothingSpawnedOrSeated();
    }

    @Test
    void anAwayCompanionKeepsItsReservedSeat() {
        offline("Olwen", new Relation.OwnCompanion(row(RosterState.AWAY)));
        when(roster.freeSlots(chr)).thenReturn(0);
        when(roster.call(chr, olwen)).thenReturn(new RosterResult.Ok("Olwen joins your party."));

        invite("Olwen");

        verify(roster).call(chr, olwen);
    }

    @Test
    void yourCompanionPastTheBudgetStaysAtCamp() {
        offline("Olwen", new Relation.OwnCompanion(row(RosterState.BENCHED)));
        when(budget.usage(ANN)).thenReturn(new CompanionBudget.Usage(10, config.COMPANION_PER_HUMAN, 0, 0, 0));

        invite("Olwen");

        verify(residents).whisper(olwen, chr, "it's too crowded out here right now. ask me again in a bit.");
        verify(store).event(eq(ANN), eq("olwen"), eq("companion.budget_denied"), anyString());
        verify(roster, never()).call(any(), any());
        nothingSpawnedOrSeated();
    }

    @Test
    void aPartylessOwnerGetsAPartyThatMayBeAStarterPartyForTheirOwnCompanion() {
        when(chr.getParty()).thenReturn(null);
        offline("Olwen", new Relation.OwnCompanion(row(RosterState.BENCHED)));
        parties.when(() -> Party.createParty(chr, false, true)).thenReturn(true);
        when(roster.call(chr, olwen)).thenReturn(new RosterResult.Ok("Olwen joins your party."));

        invite("Olwen");

        parties.verify(() -> Party.createParty(chr, false, true));
        verify(roster).call(chr, olwen);
    }

    @Test
    void yourCompanionAlreadyOutWithYouIsSeatedThroughTheStockInvite() {
        Character loaded = bot(900, "Olwen", 30);
        online("Olwen", loaded, new Relation.OwnCompanion(row(RosterState.ACTIVE)));
        invites.when(() -> InviteCoordinator.createInvite(InviteType.PARTY, chr, PARTY_ID, 900)).thenReturn(true);

        try (MockedStatic<AgentPartyQuestLobbyRuntime> lobby = mockStatic(AgentPartyQuestLobbyRuntime.class)) {
            invite("Olwen");

            lobby.verify(() -> AgentPartyQuestLobbyRuntime.decidePartyInvite(any(), any()), never());
        }
        parties.verify(() -> Party.joinParty(loaded, PARTY_ID, false));
        verify(roster, never()).call(any(), any());
    }

    @Test
    void yourCompanionOutWithYouButPartylessRejoinsOnASeatHeldForTheJoin() {
        Character loaded = bot(900, "Olwen", 30);
        online("Olwen", loaded, new Relation.OwnCompanion(row(RosterState.ACTIVE)));
        invites.when(() -> InviteCoordinator.createInvite(InviteType.PARTY, chr, PARTY_ID, 900)).thenReturn(true);

        invite("Olwen");

        verify(slots).reserve(eq(ANN), eq("olwen"), eq(CompanionSlots.Kind.TRAVEL), anyLong());
        parties.verify(() -> Party.joinParty(loaded, PARTY_ID, false));
    }

    @Test
    void yourCompanionOutWithYouGets17WhenYourSeatsAreTaken() {
        Character loaded = bot(900, "Olwen", 30);
        online("Olwen", loaded, new Relation.OwnCompanion(row(RosterState.ACTIVE)));
        when(roster.freeSlots(chr)).thenReturn(0);

        invite("Olwen");

        assertStatus(PacketCreator.partyStatusMessage(17));
        verify(slots, never()).reserve(anyInt(), anyString(), any(), anyLong());
        nothingSpawnedOrSeated();
    }

    // ---- everyone else's -----------------------------------------------------------------

    @Test
    void anotherPlayersCompanionIsDeniedWith23() {
        offline("Brin", new Relation.OthersCompanion(20, "brin"));

        invite("Brin");

        assertStatus(PacketCreator.partyStatusMessage(23, "Brin"));
        nothingSpawnedOrSeated();
    }

    @Test
    void anotherPlayersLoadedCompanionIsDeniedWith23() {
        Character brin = bot(901, "Brin", 30);
        online("Brin", brin, new Relation.OthersCompanion(20, "brin"));

        invite("Brin");

        assertStatus(PacketCreator.partyStatusMessage(23, "Brin"));
        verify(brin, never()).sendPacket(any());
        nothingSpawnedOrSeated();
    }

    @Test
    void aResidentIsDeniedWith23() {
        offline("Cato", new Relation.Resident("cato"));

        invite("Cato");

        assertStatus(PacketCreator.partyStatusMessage(23, "Cato"));
        nothingSpawnedOrSeated();
    }

    @Test
    void anAgentYouMayNotControlIsNotFound() {
        offline("Probe", new Relation.PlainAgent(PROBE));
        when(directory.mayControl(chr, PROBE)).thenReturn(false);

        invite("Probe");

        assertStatus(PacketCreator.partyStatusMessage(19));
        nothingSpawnedOrSeated();
    }

    @Test
    void anAdministratorStillLoadsAPlainAgentAfterTheBudgetCheck() {
        offline("Probe", new Relation.PlainAgent(PROBE));
        when(directory.mayControl(chr, PROBE)).thenReturn(true);
        when(budget.admit(CompanionBudget.Kind.COMPANION, chr)).thenReturn(CompanionBudget.Admission.ALLOW);
        interaction.when(() -> AgentInteractionRuntime.spawnAgentForLeader(chr, "Probe"))
                .thenReturn(AgentSpawnResult.fail("not loaded in this test"));

        invite("Probe");

        verify(budget).admit(CompanionBudget.Kind.COMPANION, chr);
        interaction.verify(() -> AgentInteractionRuntime.spawnAgentForLeader(chr, "Probe"));
    }

    @Test
    void anAdministratorPastTheBudgetLoadsNothing() {
        offline("Probe", new Relation.PlainAgent(PROBE));
        when(directory.mayControl(chr, PROBE)).thenReturn(true);
        when(budget.admit(CompanionBudget.Kind.COMPANION, chr)).thenReturn(CompanionBudget.Admission.deny("full"));

        invite("Probe");

        verify(chr).dropMessage(5, "No Agent can be loaded for you right now.");
        nothingSpawnedOrSeated();
    }

    @Test
    void anAgentTheClaimGuardDeniesGetsTheStockDenyNotAgentText() {
        Character probe = bot(PROBE, "Probe", 30);
        online("Probe", probe, new Relation.PlainAgent(PROBE));
        when(directory.mayControl(chr, PROBE)).thenReturn(true);
        invites.when(() -> InviteCoordinator.createInvite(InviteType.PARTY, chr, PARTY_ID, PROBE)).thenReturn(true);

        try (MockedStatic<AgentPartyQuestLobbyRuntime> lobby = mockStatic(AgentPartyQuestLobbyRuntime.class)) {
            lobby.when(() -> AgentPartyQuestLobbyRuntime.decidePartyInvite(probe, chr))
                    .thenReturn(AgentPartyQuestLobbyRuntime.InviteDecision.DENY);

            invite("Probe");
        }

        invites.verify(() -> InviteCoordinator.answerInvite(InviteType.PARTY, PROBE, PARTY_ID, false));
        parties.verify(() -> Party.joinParty(any(), anyInt(), anyBoolean()), never());
        ArgumentCaptor<Packet> sent = ArgumentCaptor.forClass(Packet.class);
        verify(client).sendPacket(sent.capture()); // the handler answers through the inviter's client
        assertArrayEquals(PacketCreator.partyStatusMessage(23, "Probe").getBytes(), sent.getValue().getBytes());
        verify(chr, never()).dropMessage(anyInt(), anyString());
    }

    @Test
    void aRoutingFailureFailsClosedWithNotFound() {
        when(storage.getCharacterByName("Olwen")).thenReturn(null);
        when(directory.resolveName(chr, "Olwen")).thenThrow(new IllegalStateException("store down"));

        invite("Olwen");

        assertStatus(PacketCreator.partyStatusMessage(19));
        nothingSpawnedOrSeated();
    }

    @Test
    void aCitizenNearYourLevelAcceptsAMomentLaterWithoutAskingTheLobby() {
        Character mira = bot(MIRA, "Mira", 30 + 5);
        online("Mira", mira, new Relation.Citizen(MIRA));
        invites.when(() -> InviteCoordinator.createInvite(InviteType.PARTY, chr, PARTY_ID, MIRA)).thenReturn(true);
        when(chr.isLoggedinWorld()).thenReturn(true);
        when(mira.isLoggedinWorld()).thenReturn(true);

        try (MockedStatic<AgentPartyQuestLobbyRuntime> lobby = mockStatic(AgentPartyQuestLobbyRuntime.class)) {
            invite("Mira");

            lobby.verify(() -> AgentPartyQuestLobbyRuntime.decidePartyInvite(any(), any()), never());
        }
        invites.verify(() -> InviteCoordinator.createInvite(InviteType.PARTY, chr, PARTY_ID, MIRA));
        parties.verify(() -> Party.joinParty(any(), anyInt(), anyBoolean()), never()); // not in the same tick

        answerLater();

        invites.verify(() -> InviteCoordinator.answerInvite(InviteType.PARTY, MIRA, PARTY_ID, true));
        parties.verify(() -> Party.joinParty(mira, PARTY_ID, false));
        verify(chr, never()).sendPacket(any());
    }

    @Test
    void aCitizensYesHoldsAGuestSeatChargedToTheInviterBeforeItJoins() {
        Character mira = bot(MIRA, "Mira", 30);
        online("Mira", mira, new Relation.Citizen(MIRA));
        invites.when(() -> InviteCoordinator.createInvite(InviteType.PARTY, chr, PARTY_ID, MIRA)).thenReturn(true);
        when(chr.isLoggedinWorld()).thenReturn(true);
        when(mira.isLoggedinWorld()).thenReturn(true);
        String guest = CompanionSlots.characterKey(MIRA);
        parties.when(() -> Party.joinParty(mira, PARTY_ID, false)).thenAnswer(inv -> {
            // the seat gate asks for the reservation inside the join, so it is taken first
            verify(slots).reserve(ANN, guest, CompanionSlots.Kind.GUEST, Long.MAX_VALUE);
            return true;
        });

        invite("Mira");
        answerLater();

        parties.verify(() -> Party.joinParty(mira, PARTY_ID, false));
        verify(slots, never()).release(anyString(), any());
    }

    @Test
    void aGuestSeatIsGivenBackWhenTheJoinFails() {
        Character mira = bot(MIRA, "Mira", 30);
        online("Mira", mira, new Relation.Citizen(MIRA));
        invites.when(() -> InviteCoordinator.createInvite(InviteType.PARTY, chr, PARTY_ID, MIRA)).thenReturn(true);
        when(chr.isLoggedinWorld()).thenReturn(true);
        when(mira.isLoggedinWorld()).thenReturn(true);

        invite("Mira");
        answerLater();

        verify(slots).release(CompanionSlots.characterKey(MIRA), CompanionSlots.Kind.GUEST);
    }

    @Test
    void aCitizenIsNotInvitedWithoutASeatForItsHost() {
        Character mira = bot(MIRA, "Mira", 30);
        online("Mira", mira, new Relation.Citizen(MIRA));
        when(roster.freeSlots(chr)).thenReturn(0);

        invite("Mira");

        assertStatus(PacketCreator.partyStatusMessage(17));
        nothingSpawnedOrSeated();
    }

    @Test
    void aCitizenPastYourShareOfASharedPartyDeclinesLikeAPlayer() {
        Character mira = bot(MIRA, "Mira", 30);
        online("Mira", mira, new Relation.Citizen(MIRA));
        when(roster.freeSlots(chr)).thenReturn(0);
        when(slots.occupied(chr)).thenReturn(4);

        invite("Mira");

        assertStatus(PacketCreator.partyStatusMessage(23, "Mira"));
        nothingSpawnedOrSeated();
    }

    @Test
    void aCitizensYesRunsOnCompanionOpsNotOnTheTimer() {
        Character mira = bot(MIRA, "Mira", 30);
        online("Mira", mira, new Relation.Citizen(MIRA));
        invites.when(() -> InviteCoordinator.createInvite(InviteType.PARTY, chr, PARTY_ID, MIRA)).thenReturn(true);
        when(chr.isLoggedinWorld()).thenReturn(true);
        when(mira.isLoggedinWorld()).thenReturn(true);
        List<Runnable> queue = DirectExecutor.queue();
        CompanionExecutor ops = DirectExecutor.queued(queue);
        when(living.executor()).thenReturn(ops);

        invite("Mira");
        answerLater(); // the timer only hands the answer over

        verify(slots, never()).reserve(anyInt(), anyString(), any(), anyLong());
        parties.verify(() -> Party.joinParty(any(), anyInt(), anyBoolean()), never());
        assertEquals(1, queue.size());
        queue.get(0).run();
        verify(slots).reserve(ANN, CompanionSlots.characterKey(MIRA), CompanionSlots.Kind.GUEST, Long.MAX_VALUE);
        parties.verify(() -> Party.joinParty(mira, PARTY_ID, false));
    }

    @Test
    void aCitizenWhoFoundAPartyMeanwhileDoesNotJoin() {
        Character mira = bot(MIRA, "Mira", 30);
        online("Mira", mira, new Relation.Citizen(MIRA));
        invites.when(() -> InviteCoordinator.createInvite(InviteType.PARTY, chr, PARTY_ID, MIRA)).thenReturn(true);
        when(chr.isLoggedinWorld()).thenReturn(true);
        when(mira.isLoggedinWorld()).thenReturn(true);

        invite("Mira");
        when(mira.getParty()).thenReturn(mock(Party.class));
        answerLater();

        invites.verify(() -> InviteCoordinator.answerInvite(InviteType.PARTY, MIRA, PARTY_ID, false));
        parties.verify(() -> Party.joinParty(any(), anyInt(), anyBoolean()), never());
    }

    @Test
    void aCitizenFarFromYourLevelDeniesAMomentLater() {
        Character mira = bot(MIRA, "Mira", 30 + 6);
        online("Mira", mira, new Relation.Citizen(MIRA));
        when(chr.isLoggedinWorld()).thenReturn(true);

        invite("Mira");
        verify(chr, never()).sendPacket(any());
        answerLater();

        assertStatus(PacketCreator.partyStatusMessage(23, "Mira"));
        nothingSpawnedOrSeated();
    }

    @Test
    void anOfflineCitizenIsNotFound() {
        offline("Mira", new Relation.Citizen(MIRA));

        invite("Mira");

        assertStatus(PacketCreator.partyStatusMessage(19));
        nothingSpawnedOrSeated();
    }

    @Test
    void anUnknownNameIsNotFoundWithoutASpawnAttempt() {
        offline("Nobody", Relation.UNKNOWN);

        invite("Nobody");

        assertStatus(PacketCreator.partyStatusMessage(19));
        nothingSpawnedOrSeated();
    }

    // ---- humans --------------------------------------------------------------------------

    @Test
    void aHumanGetsTheStockInvite() {
        Character ben = human(20, "Ben");
        online("Ben", ben, new Relation.Human(20));
        invites.when(() -> InviteCoordinator.createInvite(InviteType.PARTY, chr, PARTY_ID, 20)).thenReturn(true);

        invite("Ben");

        ArgumentCaptor<Packet> sent = ArgumentCaptor.forClass(Packet.class);
        verify(ben).sendPacket(sent.capture());
        assertArrayEquals(PacketCreator.partyInvite(chr).getBytes(), sent.getValue().getBytes());
        verify(chr, never()).sendPacket(any());
    }

    @Test
    void aStarterPartyTakesNoOtherHuman() {
        when(party.isStarterParty()).thenReturn(true);
        online("Ben", human(20, "Ben"), new Relation.Human(20));

        invite("Ben");

        assertStatus(PacketCreator.partyStatusMessage(10));
        nothingSpawnedOrSeated();
    }

    @Test
    void aStarterPartyTakesNoCitizen() {
        when(party.isStarterParty()).thenReturn(true);
        online("Mira", bot(MIRA, "Mira", 30), new Relation.Citizen(MIRA));

        invite("Mira");

        assertStatus(PacketCreator.partyStatusMessage(10));
        nothingSpawnedOrSeated();
    }

    // ---- humans alone with their companions: the merge (P0 probe 1) and make-room (probe 12) ------

    @Test
    void aHumanAloneWithTheirCompanionsGetsTheStockInviteInsteadOfAlreadyInAParty() {
        Character ben = human(20, "Ben");
        Party bens = partyOf(ben, 88, 3);
        when(directory.isCompanionParty(bens)).thenReturn(true);
        online("Ben", ben, new Relation.Human(20));
        invites.when(() -> InviteCoordinator.createInvite(InviteType.PARTY, chr, PARTY_ID, 20)).thenReturn(true);

        invite("Ben");

        ArgumentCaptor<Packet> sent = ArgumentCaptor.forClass(Packet.class);
        verify(ben).sendPacket(sent.capture());
        assertArrayEquals(PacketCreator.partyInvite(chr).getBytes(), sent.getValue().getBytes());
        verify(chr, never()).sendPacket(any());
        verify(client, never()).sendPacket(any());
    }

    @Test
    void aHumanRunningAnEventWithTheirCompanionsIsNotAskedToMerge() {
        Character ben = human(20, "Ben");
        Party bens = partyOf(ben, 88, 3);
        when(directory.isCompanionParty(bens)).thenReturn(true);
        PartyCharacter inTheRun = mock(PartyCharacter.class);
        Character benInside = human(20, "Ben");
        when(benInside.getEventInstance()).thenReturn(mock(EventInstanceManager.class));
        when(inTheRun.getPlayer()).thenReturn(benInside);
        when(bens.getPartyMembersOnline()).thenReturn(List.of(inTheRun));
        online("Ben", ben, new Relation.Human(20));

        invite("Ben");

        ArgumentCaptor<Packet> sent = ArgumentCaptor.forClass(Packet.class);
        verify(client).sendPacket(sent.capture()); // the stock "already in a party", as without companions
        assertArrayEquals(PacketCreator.partyStatusMessage(16).getBytes(), sent.getValue().getBytes());
        verify(ben, never()).sendPacket(any());
    }

    @Test
    void aHumanInAPartyWithAnotherHumanStillGetsAlreadyInAParty() {
        Character ben = human(20, "Ben");
        partyOf(ben, 88, 3); // shared: isCompanionParty is false
        online("Ben", ben, new Relation.Human(20));

        invite("Ben");

        ArgumentCaptor<Packet> sent = ArgumentCaptor.forClass(Packet.class);
        verify(client).sendPacket(sent.capture());
        assertArrayEquals(PacketCreator.partyStatusMessage(16).getBytes(), sent.getValue().getBytes());
        verify(ben, never()).sendPacket(any());
    }

    @Test
    void acceptingFromYourOwnCompanionPartyMergesOnCompanionOps() throws Exception {
        partyOf(null, 88, 3);
        when(directory.isCompanionParty(party)).thenReturn(true);

        accept(88);

        verify(sync).accept(ANN, 88);
        parties.verify(() -> Party.joinParty(any(), anyInt(), anyBoolean()), never());
    }

    @Test
    void acceptingFromAPartyWithOtherHumansIsTheStockRefusal() throws Exception {
        partyOf(null, 88, 3);

        accept(88);

        verify(sync, never()).accept(anyInt(), anyInt());
        parties.verify(() -> Party.joinParty(chr, 88, false)); // which answers "already in one"
    }

    @Test
    void aPartylessPlayerAcceptingIntoAFullPartyMakesRoomOnCompanionOps() throws Exception {
        when(chr.getParty()).thenReturn(null);
        partyOf(null, 88, 6);

        accept(88);

        verify(sync).accept(ANN, 88);
        parties.verify(() -> Party.joinParty(any(), anyInt(), anyBoolean()), never());
    }

    @Test
    void aHumanJoinsACitizenLedLobbyPartyTheStockWay() throws Exception {
        when(chr.getParty()).thenReturn(null);
        partyOf(null, 88, 4); // a PQ lobby a citizen leads, with room

        accept(88);

        parties.verify(() -> Party.joinParty(chr, 88, false));
        verify(sync, never()).accept(anyInt(), anyInt());
    }

    @Test
    void aCitizenBusyInAnEventInstanceIsTakingCareOfAnotherInvitation() {
        Character mira = bot(MIRA, "Mira", 30);
        when(mira.getEventInstance()).thenReturn(mock(EventInstanceManager.class));
        online("Mira", mira, new Relation.Citizen(MIRA));
        when(chr.isLoggedinWorld()).thenReturn(true);

        invite("Mira");
        verify(chr, never()).sendPacket(any());
        answerLater();

        assertStatus(PacketCreator.partyStatusMessage(22, "Mira"));
        nothingSpawnedOrSeated();
        verify(slots, never()).reserve(anyInt(), anyString(), any(), anyLong());
    }

    // ---- helpers -------------------------------------------------------------------------

    /** Op 3 for {@code partyId}, its invite answered as accepted. */
    private void accept(int partyId) throws ReflectiveOperationException {
        Constructor<InviteResult> result = InviteResult.class.getDeclaredConstructor(InviteResultType.class,
                Character.class, Object[].class);
        result.setAccessible(true);
        InviteResult accepted = result.newInstance(InviteResultType.ACCEPTED, null, new Object[0]);
        invites.when(() -> InviteCoordinator.answerInvite(InviteType.PARTY, ANN, partyId, true)).thenReturn(accepted);
        handler.handlePacket(Packets.buildInPacket(out -> {
            out.writeByte(3);
            out.writeInt(partyId);
        }), client);
    }

    /** A party {@code id} of {@code size} members that {@code member} is in (null: nobody given), known to the world. */
    private Party partyOf(Character member, int id, int size) {
        Party other = mock(Party.class);
        when(other.getId()).thenReturn(id);
        List<PartyCharacter> members = new java.util.ArrayList<>();
        for (int i = 0; i < size; i++) {
            members.add(mock(PartyCharacter.class));
        }
        when(other.getMembers()).thenReturn(members);
        when(world.getParty(id)).thenReturn(other);
        if (member != null) {
            when(member.getParty()).thenReturn(other);
        }
        return other;
    }

    private void invite(String name) {
        InPacket packet = Packets.buildInPacket(out -> {
            out.writeByte(4);
            out.writeString(name);
        });
        handler.handlePacket(packet, client);
    }

    /** Runs the citizen's answer, scheduled after the configured reply delay. */
    private void answerLater() {
        ArgumentCaptor<Runnable> answer = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Long> delay = ArgumentCaptor.forClass(Long.class);
        verify(timer).schedule(eq(TimerManager.SchedulerLane.LOW_PRIORITY), answer.capture(), delay.capture());
        assertTrue(delay.getValue() >= config.CITIZEN_INVITE_REPLY_MIN_MS
                && delay.getValue() <= config.CITIZEN_INVITE_REPLY_MAX_MS, "delay " + delay.getValue());
        answer.getValue().run();
    }

    private void offline(String name, Relation relation) {
        when(storage.getCharacterByName(name)).thenReturn(null);
        when(directory.resolveName(chr, name)).thenReturn(relation);
    }

    private void online(String name, Character target, Relation relation) {
        when(storage.getCharacterByName(name)).thenReturn(target);
        when(directory.resolve(chr, target)).thenReturn(relation);
    }

    private void assertStatus(Packet expected) {
        ArgumentCaptor<Packet> sent = ArgumentCaptor.forClass(Packet.class);
        verify(chr).sendPacket(sent.capture());
        assertArrayEquals(expected.getBytes(), sent.getValue().getBytes());
    }

    private void nothingSpawnedOrSeated() {
        interaction.verify(() -> AgentInteractionRuntime.spawnAgentForLeader(any(), anyString()), never());
        interaction.verify(() -> AgentInteractionRuntime.spawnAgentForLeader(any(), anyString(), anyBoolean()), never());
        invites.verify(() -> InviteCoordinator.createInvite(any(), any(), anyInt(), anyInt()), never());
        parties.verify(() -> Party.joinParty(any(), anyInt(), anyBoolean()), never());
    }

    private static Character bot(int id, String name, int level) {
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(id);
        when(bot.getName()).thenReturn(name);
        when(bot.getLevel()).thenReturn(level);
        when(bot.getClient()).thenReturn(mock(BotClient.class));
        return bot;
    }

    private static Character human(int id, String name) {
        Character human = mock(Character.class);
        when(human.getId()).thenReturn(id);
        when(human.getName()).thenReturn(name);
        when(human.getClient()).thenReturn(mock(Client.class));
        return human;
    }

    private static RosterEntry row(RosterState state) {
        return new RosterEntry(ANN, "olwen", state, Assignment.TRAIN, 0, 0, 104000000, 0L, 0L, 0L,
                1_000L, 1_000L, 1_000L, 0, null);
    }
}
