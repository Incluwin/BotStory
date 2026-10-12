package server.living.roster.ui;

import client.Character;
import client.Client;
import client.Job;
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
import server.living.bond.ContractService;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.resident.LivingTalk;
import server.living.resident.NativeTalkPage;
import server.living.resident.ResidentRuntime;
import server.living.roster.BenchLedger;
import server.living.roster.CompanionDirectory;
import server.living.roster.CompanionExecutor;
import server.living.roster.CompanionManager;
import server.living.roster.CompanionManager.NamedLineup;
import server.living.roster.CompanionPartySync;
import server.living.roster.CompanionSlots;
import server.living.roster.CompanionViews;
import server.living.roster.CrewRotation;
import server.living.roster.CrewSuggestions;
import server.living.roster.DirectExecutor;
import server.living.roster.HiredHands;
import server.living.roster.RosterResult;
import server.living.roster.RosterService;
import server.living.store.LivingStore;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;
import testutil.NativePages;
import testutil.NpcTalks;
import testutil.NpcTalks.Talk;
import testutil.Packets;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The native Crew window's pages (docs/BOT_MANAGEMENT_P10_PLAN.md, "Step 7: native pages"): the actions page lists
 * exactly what the NPC Crew window lists under its blocks for the same state, and each of the eight crew-wide functions
 * drawn natively reaches the same CompanionManager (or party sync) call as from the NPC window, with the same labels
 * and gates; an answer there is its pink line alone (never the NPC Crew window), Back and a stale click draw the actions
 * page again, and the bench warning of "Make room for a friend..." asks natively.
 */
class NativeCrewPagesTest {
    private static final int ANN = 10;
    private static final int ANT_TUNNEL = 105040300;
    private static final byte MENU = 4;

    private final LivingWorld world = mock(LivingWorld.class);
    private final LivingWorldConfig config = new LivingWorldConfig();
    private final LivingContent content = mock(LivingContent.class);
    private final RosterService roster = mock(RosterService.class);
    private final LivingStore store = mock(LivingStore.class);
    private final ResidentRuntime residents = mock(ResidentRuntime.class);
    private final CompanionDirectory directory = mock(CompanionDirectory.class);
    private final CompanionPartySync sync = mock(CompanionPartySync.class);
    private final CompanionSlots slots = mock(CompanionSlots.class);
    private final CompanionManager manager = mock(CompanionManager.class);
    private final BenchLedger ledger = mock(BenchLedger.class);
    private final Character owner = mock(Character.class);
    private final Client client = mock(Client.class);
    private final Character hadrikBot = mock(Character.class);
    private final List<RosterEntry> rows = new ArrayList<>();
    private final Map<String, Persona> personas = new HashMap<>();
    private final NativePages pages = new NativePages();

    private MockedStatic<LivingWorld> livingWorld;
    private CompanionViews views;
    private CompanionMenus menus;

    @BeforeEach
    void wire() {
        when(owner.getId()).thenReturn(ANN);
        when(owner.getName()).thenReturn("Ann");
        when(owner.getClient()).thenReturn(client);
        when(client.getPlayer()).thenReturn(owner);
        when(client.canClickNPC()).thenReturn(true);
        when(client.onIoThread()).thenReturn(true);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run(); // the owner's packet thread, at once
            return null;
        }).when(client).runOnIoThread(any());
        when(world.content()).thenReturn(content);
        when(world.roster()).thenReturn(roster);
        when(world.store()).thenReturn(store);
        when(world.residents()).thenReturn(residents);
        when(world.directory()).thenReturn(directory);
        when(world.config()).thenReturn(config);
        CompanionExecutor ops = DirectExecutor.inline();
        when(world.executor()).thenReturn(ops);
        when(world.partySync()).thenReturn(sync);
        when(world.slots()).thenReturn(slots);
        when(world.manager()).thenReturn(manager);
        when(world.bench()).thenReturn(ledger);
        views = new CompanionViews(world, mapId -> "Ant Tunnel", itemId -> "Stolen Fence");
        when(world.views()).thenReturn(views);
        when(store.roster(ANN)).thenAnswer(inv -> List.copyOf(rows));
        when(roster.roster(owner)).thenReturn(rows);
        when(roster.describeAssignment(any())).thenReturn("train");
        when(slots.occupied(owner)).thenReturn(3);
        MapleMap perion = mock(MapleMap.class);
        when(perion.getMapName()).thenReturn("Perion");
        when(hadrikBot.getId()).thenReturn(900);
        when(hadrikBot.getName()).thenReturn("Hadrik");
        when(hadrikBot.getLevel()).thenReturn(31);
        when(hadrikBot.getJob()).thenReturn(Job.FIGHTER);
        when(hadrikBot.getMap()).thenReturn(perion);
        when(hadrikBot.getHp()).thenReturn(812);
        when(hadrikBot.getCurrentMaxHp()).thenReturn(900);
        when(manager.saveLineup(owner)).thenReturn(new RosterResult.Ok("saveLineup done."));
        when(manager.callUsual(owner)).thenReturn(new RosterResult.Ok("callUsual done."));
        when(manager.followAll(owner)).thenReturn(new RosterResult.Ok("followAll done."));
        livingWorld = mockStatic(LivingWorld.class);
        livingWorld.when(LivingWorld::get).thenReturn(world);
        menus = new CompanionMenus(world, mapId -> "Ant Tunnel");
        pages.install();
    }

    @AfterEach
    void close() {
        pages.uninstall();
        LivingTalk.forget(owner);
        LivingTalk.endNative(owner);
        livingWorld.close();
    }

    // ---- the actions page --------------------------------------------------------------------------

    @Test
    void theActionsPageListsExactlyWhatTheNpcCrewWindowListsUnderItsBlocks() {
        hadrik();
        add(row("tobi", RosterState.ACTIVE), "Tobi", 902, 30, Job.CLERIC);
        add(row("mira", RosterState.BENCHED), "Mira", 901, 28, Job.HUNTER);
        when(store.lineupSlots(ANN)).thenReturn(Map.of("mira", 1));
        offerEverything();

        List<String> expected = List.of("Call my usual lineup", "Remember this lineup", "Everyone: follow me",
                "Everyone: wait here", "Everyone: gather up", "Everyone: rebuff", "Formation...",
                "Everyone on the bench...", "Keep my crew fresh (off)", "Make room for a friend...",
                "Adventurers looking for a crew...", "My lineups...", "Who should come along?");
        assertEquals(expected, npcActions(3));
        assertEquals(expected, nativeActions(), "thirteen actions: the native page pages with More... too");
        assertEquals("What should my crew do?\r\n", pages.pages.get(pages.pages.size() - 2).text());
    }

    @Test
    void theActionsPageFollowsTheStateAsTheNpcCrewWindowDoes() {
        add(row("mira", RosterState.BENCHED), "Mira", 901, 28, Job.HUNTER);
        assertEquals(List.of("Everyone on the bench...", "My lineups..."), npcActions(1));
        assertEquals(npcActions(1), nativeActions());

        when(store.lineupSlots(ANN)).thenReturn(Map.of("mira", 1));
        views.invalidate(ANN);
        assertEquals(npcActions(1), nativeActions());
        assertTrue(nativeActions().contains("Call my usual lineup"));

        rows.clear();
        views.invalidate(ANN);
        assertEquals(List.of(), npcActions(0));
        menus.crews().nativeActions(owner);
        assertEquals(List.of(), pages.labels());
        assertEquals(List.of(NativeTalkPage.Entry.heading(CompanionCrewScreens.NOTHING_TO_DO)), pages.last().entries());
    }

    @Test
    void theActionsPageIsTheNotesMenuWithNoBackAndCloseReturnsToTheCrew() {
        hadrik();
        menus.crews().nativeActions(owner);

        NativeTalkPage page = pages.last();
        assertEquals(NativeTalkPage.Kind.MENU, page.kind());
        assertFalse(pages.labels().contains("Back"));
        pages.answer(owner, 255);

        assertEquals(List.of(page.stamp()), pages.closes);
        verify(client, never()).sendPacket(any()); // no NPC window, no enableActions
    }

    // ---- the eight functions -----------------------------------------------------------------------

    @Test
    void rememberThisLineupSavesItWithThePinkLineAndNeverOpensTheNpcCrewWindow() {
        hadrik();
        menus.crews().nativeActions(owner);
        int drawn = pages.pages.size();

        pages.pick(owner, "Remember this lineup");

        verify(manager).saveLineup(owner);
        verify(owner).dropMessage(5, "saveLineup done.");
        assertEquals(drawn, pages.pages.size(), "no page after the answer");
        assertEquals(List.of(pages.last().stamp()), pages.closes, "the page closed: the crew view again");
        assertNoNpcWindow();
    }

    @Test
    void callMyUsualLineupAndTheOrdersReachTheManager() {
        hadrik();
        add(row("mira", RosterState.BENCHED), "Mira", 901, 28, Job.HUNTER);
        when(store.lineupSlots(ANN)).thenReturn(Map.of("mira", 1));

        menus.crews().nativeActions(owner);
        pages.pick(owner, "Call my usual lineup");
        menus.crews().nativeActions(owner);
        pages.pick(owner, "Everyone: follow me");

        verify(manager).callUsual(owner);
        verify(owner).dropMessage(5, "callUsual done.");
        verify(manager).followAll(owner);
        verify(owner, never()).dropMessage(5, "followAll done."); // an order that went out: the companions answer
        assertNoNpcWindow();
    }

    @Test
    void formationIsTheNpcPickerNativelyAndAPickSetsIt() {
        hadrik();
        when(manager.setFormation(owner, "tight")).thenReturn(new RosterResult.Ok("You tell Hadrik: formation tight."));
        menus.crew(owner);
        pickNpc("Formation...");
        List<String> npc = lastTalk().labels();
        String npcText = lastTalk().text();
        LivingTalk.forget(owner);
        clearInvocations(client);

        menus.crews().nativeActions(owner);
        pages.pick(owner, "Formation...");

        assertEquals(npc, pages.labels());
        assertEquals(npcText.substring(0, npcText.indexOf("\r\n#L")), pages.last().text());
        pages.pick(owner, "Stay close");
        verify(manager).setFormation(owner, "tight");
        verify(owner, never()).dropMessage(anyInt(), anyString());
        assertNoNpcWindow();
    }

    @Test
    void everyoneOnTheBenchIsTheNpcDutyPickerNativelyAndAPickAssignsAll() {
        hadrik();
        add(row("mira", RosterState.BENCHED), "Mira", 901, 28, Job.HUNTER);
        when(ledger.rates(eq(personas.get("mira")), any(), eq(28), anyInt(), eq(Assignment.TRAIN)))
                .thenReturn(new BenchLedger.Rates(12.4d, 0L, 840L));
        when(manager.assignAll(owner, Assignment.REST, 0, 0)).thenReturn(new RosterResult.Ok("Mira will rest."));
        menus.crew(owner);
        pickNpc("Everyone on the bench...");
        List<String> npc = lastTalk().labels();
        LivingTalk.forget(owner);

        menus.crews().nativeActions(owner);
        pages.pick(owner, "Everyone on the bench...");

        assertEquals(npc, pages.labels());
        assertEquals("Train (~12% EXP/h)", pages.labels().get(0));
        pages.pick(owner, "Rest (no upkeep)");
        verify(manager).assignAll(owner, Assignment.REST, 0, 0);
        verify(owner).dropMessage(5, "Mira will rest.");
    }

    @Test
    void keepMyCrewFreshFlipsAsItsLabelOffers() {
        hadrik();
        boolean[] on = {false};
        CrewRotation rotation = mock(CrewRotation.class);
        when(world.crewRotation()).thenReturn(rotation);
        when(rotation.offered()).thenReturn(true);
        when(rotation.keepsFresh(ANN)).thenAnswer(inv -> on[0]);
        when(manager.setKeepCrewFresh(eq(owner), anyBoolean())).thenAnswer(inv -> {
            on[0] = inv.getArgument(1);
            return new RosterResult.Ok("Keep my crew fresh is " + (on[0] ? "on." : "off."));
        });

        menus.crews().nativeActions(owner);
        pages.pick(owner, "Keep my crew fresh (off)");
        verify(manager).setKeepCrewFresh(owner, true);
        verify(owner).dropMessage(5, "Keep my crew fresh is on.");

        menus.crews().nativeActions(owner);
        pages.pick(owner, "Keep my crew fresh (on)");
        verify(manager).setKeepCrewFresh(owner, false);
    }

    @Test
    void makeRoomAsksTheBenchWarningNativelyAndThePickStepsOut() {
        hadrik();
        add(row("tobi", RosterState.ACTIVE), "Tobi", 902, 30, Job.CLERIC);
        fullParty();
        ContractService contracts = mock(ContractService.class);
        when(contracts.benchWalkOutAfter(ANN, personas.get("hadrik"))).thenReturn("an hour");
        when(world.contracts()).thenReturn(contracts);
        when(sync.makeRoom(ANN, "hadrik")).thenReturn(true);
        when(sync.makeRoom(ANN, "tobi")).thenReturn(true);
        menus.crew(owner);
        pickNpc("Make room for a friend...");
        List<String> npc = lastTalk().labels();
        LivingTalk.forget(owner);
        clearInvocations(client);

        menus.crews().nativeActions(owner);
        pages.pick(owner, "Make room for a friend...");
        assertEquals(npc, pages.labels());
        pages.pick(owner, "Hadrik, Lv31 Fighter - Perion - HP 90%");

        NativeTalkPage ask = pages.last();
        assertEquals(NativeTalkPage.Kind.YES_NO, ask.kind());
        assertTrue(ask.text().startsWith("Hadrik won't sit at camp for long: after an hour on the bench"), ask.text());
        verify(sync, never()).makeRoom(anyInt(), anyString());
        pages.answer(owner, 1);

        verify(sync).makeRoom(ANN, "hadrik");
        verify(owner).dropMessage(5, "Hadrik steps out to make room for your friend.");
        assertNoNpcWindow();

        menus.crews().nativeActions(owner); // no contract: no question
        pages.pick(owner, "Make room for a friend...");
        pages.pick(owner, "Tobi, Lv30 Cleric - on the way");
        verify(sync).makeRoom(ANN, "tobi");
    }

    @Test
    void makeRoomsNoClosesWithoutSteppingOut() {
        hadrik();
        fullParty();
        ContractService contracts = mock(ContractService.class);
        when(contracts.benchWalkOutAfter(ANN, personas.get("hadrik"))).thenReturn("an hour");
        when(world.contracts()).thenReturn(contracts);
        menus.crews().nativeActions(owner);
        pages.pick(owner, "Make room for a friend...");
        pages.pick(owner, "Hadrik, Lv31 Fighter - Perion - HP 90%");

        pages.answer(owner, 0);

        verify(sync, never()).makeRoom(anyInt(), anyString());
        assertEquals(pages.last().stamp(), (int) pages.closes.get(pages.closes.size() - 1));
    }

    @Test
    void myLineupsCallsAndRemembersAsTheNpcPage() {
        hadrik();
        add(row("mira", RosterState.BENCHED), "Mira", 901, 28, Job.HUNTER);
        when(manager.namedLineups(owner)).thenReturn(Map.of(NamedLineup.HUNTING, List.of("Hadrik", "Mira")));
        when(manager.callNamed(owner, NamedLineup.HUNTING)).thenReturn(new RosterResult.Ok("Mira is on the way."));
        when(manager.saveNamed(owner, NamedLineup.PQ)).thenReturn(new RosterResult.Ok("Remembered as PQ."));
        menus.crew(owner);
        pickNpc("My lineups...");
        Talk npc = lastTalk();
        LivingTalk.forget(owner);
        clearInvocations(client);

        menus.crews().nativeActions(owner);
        pages.pick(owner, "My lineups...");

        assertEquals(npc.labels(), pages.labels());
        assertEquals(npc.text().substring(0, npc.text().indexOf("\r\n#L")), pages.last().text());
        pages.pick(owner, "Call the Hunting lineup");
        verify(manager).callNamed(owner, NamedLineup.HUNTING);
        verify(owner).dropMessage(5, "Mira is on the way.");

        menus.crews().nativeActions(owner);
        pages.pick(owner, "My lineups...");
        pages.pick(owner, "Remember this crew as PQ");
        verify(manager).saveNamed(owner, NamedLineup.PQ);
        verify(owner).dropMessage(5, "Remembered as PQ.");
        assertNoNpcWindow();
    }

    @Test
    void aPageThatReadsTheStoreFirstIsDrawnWhileAnNpcWindowWaits() {
        hadrik();
        when(manager.namedLineups(owner)).thenReturn(Map.of());
        LivingTalk.open(owner, 9000000).ok("a stock window the player opened").show();
        clearInvocations(client);

        menus.crews().nativeActions(owner);
        pages.pick(owner, "My lineups...");

        assertTrue(pages.last().text().startsWith("The lineups I keep:"), pages.last().text());
        verify(client, never()).sendPacket(any()); // nothing over the NPC window that waits
    }

    @Test
    void whoShouldComeAlongCallsTheSuggestedCrew() {
        hadrik();
        Persona mira = personas.get(add(row("mira", RosterState.BENCHED), "Mira", 901, 28, Job.HUNTER).personaId());
        config.COMPANION_PQ_ENABLED_KEYS = "hpq";
        when(manager.suggestCrews(owner)).thenReturn(List.of(new CrewSuggestions.Suggestion("hpq",
                List.of("you", "Hadrik", "Mira"), List.of(personas.get("hadrik"), mira), List.of(mira))));
        when(manager.callSuggested(owner, List.of(personas.get("hadrik"), mira)))
                .thenReturn(new RosterResult.Ok("Mira is on the way."));

        menus.crews().nativeActions(owner);
        pages.pick(owner, "Who should come along?");

        assertTrue(pages.last().text().startsWith("Who should come along?\r\n#eHPQ#n: you, Hadrik and Mira\r\n"),
                pages.last().text());
        assertEquals(List.of("Call Mira for HPQ", "Back"), pages.labels());
        pages.pick(owner, "Call Mira for HPQ");
        verify(manager).callSuggested(owner, List.of(personas.get("hadrik"), mira));
        verify(owner).dropMessage(5, "Mira is on the way.");
        assertNoNpcWindow();
    }

    // ---- Back and stale clicks ---------------------------------------------------------------------

    @Test
    void backFromAPageIsTheActionsPageAgain() {
        hadrik();
        menus.crews().nativeActions(owner);
        pages.pick(owner, "Formation...");

        pages.pick(owner, "Back");

        assertEquals("What should my crew do?\r\n", pages.last().text());
        assertTrue(pages.labels().contains("Formation..."));
        assertTrue(pages.closes.isEmpty(), "each page replaced the last");
        assertNoNpcWindow();
    }

    @Test
    void aClickAfterTheRowsChangedDrawsTheActionsPageWithTheNoteInsteadOfActing() {
        hadrik();
        menus.crews().nativeActions(owner);

        views.invalidate(ANN); // a roster write or a party change touched Ann's rows meanwhile
        pages.pick(owner, "Remember this lineup");

        verify(manager, never()).saveLineup(any());
        assertEquals(CompanionMenus.THINGS_CHANGED + "\r\n\r\nWhat should my crew do?\r\n", pages.last().text());
        pages.pick(owner, "Remember this lineup"); // the page drawn afresh acts
        verify(manager).saveLineup(owner);
    }

    @Test
    void aClientThatDrawsNoPagesGetsTheSamePagesAsNpcWindows() {
        hadrik();
        pages.draws = false; // a protocol-1 client

        menus.crews().nativeActions(owner);

        assertTrue(pages.pages.isEmpty());
        assertEquals(MENU, lastTalk().msgType());
        assertTrue(lastTalk().text().startsWith("What should my crew do?\r\n"), lastTalk().text());
        pickNpc("Formation...");
        assertTrue(lastTalk().labels().contains("Stay close"));
    }

    // ---- helpers -----------------------------------------------------------------------------------

    /** The NPC Crew window's actions (its labels after the {@code rowCount} rows, every page). */
    private List<String> npcActions(int rowCount) {
        LivingTalk.forget(owner);
        clearInvocations(client);
        menus.crew(owner);
        List<String> all = new ArrayList<>();
        for (int guard = 0; guard < 10; guard++) {
            List<String> labels = lastTalk().labels();
            labels.stream().filter(label -> !label.equals("More...") && !label.equals("Back to the top..."))
                    .forEach(all::add);
            if (!labels.contains("More...")) {
                break;
            }
            pickNpc("More...");
        }
        LivingTalk.forget(owner);
        return all.subList(rowCount, all.size());
    }

    /** The native actions page's options, every page. */
    private List<String> nativeActions() {
        menus.crews().nativeActions(owner);
        return pages.allLabels(owner);
    }

    /** Everything the Crew window can offer: rotation, a full party, the hire list and a party quest. */
    private void offerEverything() {
        CrewRotation rotation = mock(CrewRotation.class);
        when(world.crewRotation()).thenReturn(rotation);
        when(rotation.offered()).thenReturn(true);
        fullParty();
        when(world.hiredHands()).thenReturn(mock(HiredHands.class));
        config.COMPANION_PQ_ENABLED_KEYS = "kpq";
    }

    private void fullParty() {
        Party full = mock(Party.class);
        List<PartyCharacter> six = new ArrayList<>();
        for (int index = 0; index < RosterService.PARTY_CAP; index++) {
            six.add(mock(PartyCharacter.class));
        }
        when(full.getMembers()).thenReturn(six);
        when(owner.getParty()).thenReturn(full);
    }

    private void assertNoNpcWindow() {
        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(client, org.mockito.Mockito.atLeast(0)).sendPacket(captor.capture());
        assertTrue(NpcTalks.talks(captor.getAllValues()).isEmpty(), "an NPC window was sent");
    }

    /** Hadrik, out with Ann and loaded: Lv31 Fighter in Perion at 812/900 HP. */
    private RosterEntry hadrik() {
        RosterEntry entry = add(row("hadrik", RosterState.ACTIVE), "Hadrik", 900, 31, Job.FIGHTER);
        when(roster.companion(entry)).thenReturn(hadrikBot);
        return entry;
    }

    private RosterEntry add(RosterEntry entry, String name, int characterId, int level, Job job) {
        Persona persona = mock(Persona.class);
        when(persona.id()).thenReturn(entry.personaId());
        when(persona.name()).thenReturn(name);
        when(content.persona(entry.personaId())).thenReturn(persona);
        personas.put(entry.personaId(), persona);
        when(residents.characterIdOf(entry.personaId())).thenReturn(characterId);
        when(store.characterLevelAndJob(characterId)).thenReturn(new int[] {level, job.getId()});
        rows.add(entry);
        views.invalidate(ANN);
        return entry;
    }

    private void pickNpc(String label) {
        int selection = lastTalk().selection(label);
        assertTrue(LivingTalk.respond(client, MENU, (byte) 1, Packets.buildInPacket(out -> out.writeInt(selection))));
    }

    private Talk lastTalk() {
        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(client, org.mockito.Mockito.atLeastOnce()).sendPacket(captor.capture());
        List<Talk> talks = NpcTalks.talks(captor.getAllValues());
        return talks.get(talks.size() - 1);
    }

    private static RosterEntry row(String personaId, RosterState state) {
        return new RosterEntry(ANN, personaId, state, Assignment.TRAIN, 0, 0, ANT_TUNNEL, 0L, 0L, 0L, 0L, 0L, 0L, 0, null);
    }
}
