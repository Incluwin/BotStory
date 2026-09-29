package server.living.roster.ui;

import client.Character;
import client.Client;
import config.LivingWorldConfig;
import net.packet.Packet;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import server.living.LivingWorld;
import server.living.arc.ArcService;
import server.living.bond.BondService;
import server.living.bond.ContractService;
import server.living.config.Content.BondStage;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.resident.LivingTalk;
import server.living.resident.ResidentRuntime;
import server.living.roster.CompanionDirectory;
import server.living.roster.CompanionExecutor;
import server.living.roster.CompanionPartySync;
import server.living.roster.CompanionSlots;
import server.living.roster.DirectExecutor;
import server.living.roster.RosterResult;
import server.living.roster.RosterService;
import server.living.store.LivingStore;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.Bond;
import server.living.store.LivingStore.ContractState;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import testutil.NpcTalks;
import testutil.NpcTalks.Talk;
import testutil.Packets;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The bench warning (docs/BOT_MANAGEMENT_PLAN.md, "Contract policy + deferral", decision 3): where ROSTER_CONTRACT_MODE
 * still lets a bench end a low-tolerance persona's contract (AUTHORED, or PERMANENT_AT_SWORN before SWORN), benching it
 * from the menus (a break, a swap, making room) asks Yes/No first in the player's notes; Yes benches, No does nothing.
 * The permanent default, a patient persona, or a SWORN bond under PERMANENT_AT_SWORN ask nothing. The Yes/No is only
 * ever the answer to the player's pick (P0 probe 6: never a second NPC_TALK while a window awaits its reply).
 */
class BenchWarningTest {
    private static final int ANN = 10;
    private static final byte MENU = 4;
    private static final byte YES_NO = 1;

    private final LivingWorld world = mock(LivingWorld.class);
    private final LivingWorldConfig config = new LivingWorldConfig();
    private final LivingContent content = mock(LivingContent.class);
    private final RosterService roster = mock(RosterService.class);
    private final LivingStore store = mock(LivingStore.class);
    private final ResidentRuntime residents = mock(ResidentRuntime.class);
    private final CompanionDirectory directory = mock(CompanionDirectory.class);
    private final CompanionPartySync sync = mock(CompanionPartySync.class);
    private final CompanionSlots slots = mock(CompanionSlots.class);
    private final BondService bonds = mock(BondService.class);
    private final Character owner = mock(Character.class);
    private final Client client = mock(Client.class);
    private final Persona dorran = persona("dorran", "Dorran", 0);
    private final Persona tobi = persona("tobi", "Tobi", -1);
    private final Persona mira = persona("mira", "Mira", 14);
    private final RosterEntry dorranRow = row("dorran", RosterState.ACTIVE);
    private final RosterEntry tobiRow = row("tobi", RosterState.ACTIVE);
    private final RosterEntry miraRow = row("mira", RosterState.BENCHED);
    private final List<RosterEntry> rows = new ArrayList<>();
    private final ContractService contracts = new ContractService(world);

    private MockedStatic<LivingWorld> livingWorld;
    private CompanionMenus menus;

    @BeforeEach
    void wire() {
        when(owner.getId()).thenReturn(ANN);
        when(owner.getName()).thenReturn("Ann");
        when(owner.getClient()).thenReturn(client);
        when(client.getPlayer()).thenReturn(owner);
        when(client.canClickNPC()).thenReturn(true);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(client).runOnIoThread(any());
        when(owner.haveItem(anyInt())).thenReturn(true);
        config.ROSTER_CONTRACT_MODE = "AUTHORED";
        when(world.config()).thenReturn(config);
        when(world.content()).thenReturn(content);
        when(world.roster()).thenReturn(roster);
        when(world.store()).thenReturn(store);
        when(world.residents()).thenReturn(residents);
        when(world.directory()).thenReturn(directory);
        CompanionExecutor ops = DirectExecutor.inline();
        when(world.executor()).thenReturn(ops);
        when(world.partySync()).thenReturn(sync);
        when(world.slots()).thenReturn(slots);
        when(world.bonds()).thenReturn(bonds);
        when(world.arcs()).thenReturn(mock(ArcService.class));
        when(world.contracts()).thenReturn(contracts);
        bond(BondStage.TRUSTED);
        when(roster.roster(owner)).thenReturn(rows);
        when(roster.describeAssignment(any())).thenReturn("train");
        when(roster.freeSlots(owner)).thenReturn(3);
        when(slots.occupied(owner)).thenReturn(3);
        when(roster.bench(any(), any())).thenReturn(new RosterResult.Ok("Dorran heads back to camp to train."));
        when(roster.swap(any(), any(), any())).thenReturn(new RosterResult.Ok("swapped."));
        when(sync.makeRoom(anyInt(), any())).thenReturn(true);
        livingWorld = mockStatic(LivingWorld.class);
        livingWorld.when(LivingWorld::get).thenReturn(world);
        menus = new CompanionMenus(world, mapId -> "Camp");
    }

    @AfterEach
    void close() {
        LivingTalk.forget(owner);
        livingWorld.close();
    }

    // ---- the menus -----------------------------------------------------------------------

    @Test
    void takingABreakAsksYesNoFirstWhereABenchCanEndTheContract() {
        rows.addAll(List.of(dorranRow, tobiRow));
        menus.card(owner, dorranRow);

        pick("Take a break at camp");

        Talk ask = lastTalk();
        assertEquals(YES_NO, ask.msgType());
        assertEquals(CompanionMenus.BOARD_NPC, ask.npcId());
        assertEquals(CompanionMenus.NOTES_SPEAKER, ask.speaker());
        assertEquals("Dorran won't sit at camp for long: after an hour on the bench, Dorran walks out for good.\r\n\r\n"
                + "Send Dorran to camp anyway?", ask.text());
        verify(roster, never()).bench(any(), any());

        answer(YES_NO, 1, null);

        verify(roster).bench(owner, dorran);
        assertEquals(MENU, lastTalk().msgType()); // the card again, the outcome on top
        assertTrue(lastTalk().text().startsWith("Dorran heads back to camp to train.\r\n"), lastTalk().text());
    }

    @Test
    void noBenchesNobodyAndGivesTheClientItsActionsBack() {
        rows.addAll(List.of(dorranRow, tobiRow));
        menus.card(owner, dorranRow);
        pick("Take a break at camp");
        clearInvocations(client);

        answer(YES_NO, 0, null);

        verify(roster, never()).bench(any(), any());
        assertFalse(LivingTalk.active(client));
        ArgumentCaptor<Packet> sent = ArgumentCaptor.forClass(Packet.class);
        verify(client).sendPacket(sent.capture());
        assertTrue(NpcTalks.isEnableActions(sent.getValue()));
    }

    @Test
    void thePermanentDefaultBenchesAtOnceWithoutAsking() {
        config.ROSTER_CONTRACT_MODE = "PERMANENT_ON_ROSTER";
        rows.addAll(List.of(dorranRow, tobiRow));
        menus.card(owner, dorranRow);

        pick("Take a break at camp");

        verify(roster).bench(owner, dorran);
        assertEquals(MENU, lastTalk().msgType()); // straight to the outcome, on the card
        assertFalse(talks().stream().anyMatch(talk -> talk.msgType() == YES_NO));
    }

    @Test
    void permanentAtSwornAsksOnlyBeforeTheBondIsSworn() {
        config.ROSTER_CONTRACT_MODE = "PERMANENT_AT_SWORN";
        rows.addAll(List.of(dorranRow, tobiRow));
        menus.card(owner, dorranRow);
        pick("Take a break at camp");
        assertEquals(YES_NO, lastTalk().msgType());
        answer(YES_NO, 0, null);

        bond(BondStage.SWORN);
        menus.card(owner, dorranRow);
        pick("Take a break at camp");

        verify(roster).bench(owner, dorran);
        assertEquals(MENU, lastTalk().msgType());
    }

    @Test
    void aPatientCompanionIsNeverAsked() {
        rows.addAll(List.of(tobiRow, dorranRow));
        menus.card(owner, tobiRow); // no bench tolerance at all

        pick("Take a break at camp");

        verify(roster).bench(owner, tobi);
        assertEquals(MENU, lastTalk().msgType());
    }

    @Test
    void aSwapAsksAboutTheOneThatStepsOut() {
        rows.addAll(List.of(dorranRow, miraRow));
        menus.card(owner, dorranRow);
        pick("Let someone take your place...");
        pick(lastTalk().labels().get(0)); // Mira

        assertEquals(YES_NO, lastTalk().msgType());
        assertTrue(lastTalk().text().startsWith("Dorran won't sit at camp for long"), lastTalk().text());
        verify(roster, never()).swap(any(), any(), any());
        answer(YES_NO, 1, null);
        verify(roster).swap(owner, dorran, mira);
    }

    @Test
    void swappingInFromTheBenchAsksAboutThePartyMemberItReplaces() {
        rows.addAll(List.of(dorranRow, miraRow));
        menus.card(owner, miraRow);
        pick("Swap in for...");
        pick(lastTalk().labels().get(0)); // Dorran

        assertEquals(YES_NO, lastTalk().msgType());
        assertTrue(lastTalk().text().startsWith("Dorran won't sit at camp for long"), lastTalk().text());
        answer(YES_NO, 1, null);
        verify(roster).swap(owner, dorran, mira);
    }

    @Test
    void makingRoomAsksAboutTheOneThatStepsOut() {
        rows.addAll(List.of(dorranRow, tobiRow));
        fullParty();
        menus.crew(owner);
        pick("Make room for a friend...");
        pick(lastTalk().labels().get(0)); // Dorran

        assertEquals(YES_NO, lastTalk().msgType());
        verify(sync, never()).makeRoom(anyInt(), any());
        answer(YES_NO, 1, null);
        verify(sync).makeRoom(ANN, "dorran");
    }

    @Test
    void neverASecondWindowWhileTheQuestionAwaitsItsAnswer() {
        rows.addAll(List.of(dorranRow, tobiRow));
        menus.card(owner, dorranRow);
        pick("Take a break at camp");
        int talksBefore = talks().size();
        clearInvocations(roster);

        menus.card(owner, dorranRow); // a double-click on the companion while the question is up
        menus.rosterBook(owner); // or on the Adventurer's Roster

        assertEquals(talksBefore, talks().size(), "no NPC_TALK over a window awaiting its reply");
        verify(roster, never()).roster(any());
        answer(YES_NO, 1, null); // the question still answers
        verify(roster).bench(owner, dorran);
    }

    // ---- what a bench risks, per mode ------------------------------------------------------

    @Test
    void theWalkOutRiskPerModeToleranceAndBond() {
        for (String mode : List.of("AUTHORED", "PERMANENT_ON_ROSTER", "PERMANENT_AT_SWORN")) {
            config.ROSTER_CONTRACT_MODE = mode;
            for (BondStage stage : List.of(BondStage.TRUSTED, BondStage.SWORN)) {
                bond(stage);
                boolean risk = mode.equals("AUTHORED") || mode.equals("PERMANENT_AT_SWORN") && stage != BondStage.SWORN;
                assertEquals(risk ? "an hour" : null, contracts.benchWalkOutAfter(ANN, dorran), mode + " " + stage);
                assertEquals(risk ? "a day" : null, contracts.benchWalkOutAfter(ANN, persona("jinx", "Jinx", 1)), mode);
                assertNull(contracts.benchWalkOutAfter(ANN, mira), "fourteen days is patient enough");
                assertNull(contracts.benchWalkOutAfter(ANN, tobi), "no tolerance: it never walks out");
                assertEquals(risk ? "an hour at camp with nothing to do and i walk." : null,
                        contracts.benchWarning(ANN, dorran), "the chat bench's in-character warning");
            }
        }
    }

    // ---- helpers -------------------------------------------------------------------------

    private void bond(BondStage stage) {
        when(bonds.peek(anyInt(), any())).thenReturn(new Bond(ANN, "dorran", stage, 100, new LinkedHashSet<>(),
                ContractState.ACTIVE, 1_000L, 0L, 0, 0L, 60, 0L, 1_000L));
    }

    private void fullParty() {
        Party party = mock(Party.class);
        List<PartyCharacter> list = new ArrayList<>();
        for (int i = 0; i < RosterService.PARTY_CAP; i++) {
            list.add(mock(PartyCharacter.class));
        }
        when(party.getMembers()).thenReturn(list);
        when(owner.getParty()).thenReturn(party);
    }

    private void pick(String label) {
        answer(MENU, 1, lastTalk().selection(label));
    }

    private void answer(byte lastMsg, int action, Integer selection) {
        assertTrue(LivingTalk.respond(client, lastMsg, (byte) action, Packets.buildInPacket(out -> {
            if (selection != null) {
                out.writeInt(selection);
            }
        })));
    }

    private List<Talk> talks() {
        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(client, atLeastOnce()).sendPacket(captor.capture());
        return NpcTalks.talks(captor.getAllValues());
    }

    private Talk lastTalk() {
        List<Talk> talks = talks();
        return talks.get(talks.size() - 1);
    }

    private Persona persona(String id, String name, int benchToleranceDays) {
        Persona persona = mock(Persona.class);
        when(persona.id()).thenReturn(id);
        when(persona.name()).thenReturn(name);
        when(persona.benchToleranceDays()).thenReturn(benchToleranceDays);
        when(content.persona(id)).thenReturn(persona);
        return persona;
    }

    private static RosterEntry row(String personaId, RosterState state) {
        return new RosterEntry(ANN, personaId, state, Assignment.TRAIN, 0, 0, 105040300, 0L, 0L, 0L, 0L, 0L, 0L, 0, null);
    }
}
