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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
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
    private LivingContent content;
    @Mock
    private ResidentRuntime residents;
    @Mock
    private LivingStore store;
    @Mock
    private Persona olwen;

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
        when(budget.usage(ANN)).thenReturn(new CompanionBudget.Usage(10, 1, 0, 0, 0));
        when(roster.freeSlots(chr)).thenReturn(4);
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
        when(roster.call(chr, olwen)).thenReturn("Olwen is on the way (1 min).");

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
    void anAwayCompanionKeepsItsReservedSeat() {
        offline("Olwen", new Relation.OwnCompanion(row(RosterState.AWAY)));
        when(roster.freeSlots(chr)).thenReturn(0);
        when(roster.call(chr, olwen)).thenReturn("Olwen joins your party.");

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
        when(roster.call(chr, olwen)).thenReturn("Olwen joins your party.");

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

    // ---- helpers -------------------------------------------------------------------------

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
