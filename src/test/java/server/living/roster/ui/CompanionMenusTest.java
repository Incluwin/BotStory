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
import org.mockito.Mockito;
import server.living.LivingWorld;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.resident.LivingTalk;
import server.living.resident.ResidentRuntime;
import server.living.roster.CompanionDirectory;
import server.living.roster.CompanionDirectory.Relation;
import server.living.roster.CompanionExecutor;
import server.living.roster.CompanionPartySync;
import server.living.roster.CompanionSlots;
import server.living.roster.DirectExecutor;
import server.living.roster.RosterResult;
import server.living.roster.RosterService;
import server.living.store.LivingStore;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;
import testutil.NpcTalks;
import testutil.NpcTalks.Talk;
import testutil.Packets;
import tools.PacketCreator;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Crew window v0 screens (docs/BOT_MANAGEMENT_PLAN.md P1.5): the options a card offers per state and roster, the
 * swap pickers, and that bench, call and swap go to {@link RosterService} for the owner's own companions only. An
 * outcome is the player's notes, a full party asks whom to take over from, the last one out is never benched into a
 * spot only typing gets back from, and nothing is read while a window awaits its reply. P2a: the actions run on
 * companion-ops and are answered on the owner's own packet thread (pink text when a window cannot open by then or
 * the action took long), a full party offers "Make room for a friend...", and a friend's share is not called full.
 */
class CompanionMenusTest {
    private static final int ANN = 10;
    private static final int HADRIK_PORTRAIT = 9970001;
    private static final int ROSTER_BOOK = 2430011;
    private static final byte MENU = 4;
    private static final byte SAY = 0;
    /** A benched companion's card with someone out to swap with (P3). */
    private static final List<String> BENCH_CARD = List.of("Come join me", "Swap in for...", "What you do at camp...",
            "Camp somewhere else...", "Your haul...", "Your wages...", "We should part ways", "Never mind");

    private final LivingWorld world = mock(LivingWorld.class);
    private final LivingContent content = mock(LivingContent.class);
    private final RosterService roster = mock(RosterService.class);
    private final LivingStore store = mock(LivingStore.class);
    private final ResidentRuntime residents = mock(ResidentRuntime.class);
    private final CompanionDirectory directory = mock(CompanionDirectory.class);
    private final CompanionPartySync sync = mock(CompanionPartySync.class);
    private final CompanionSlots slots = mock(CompanionSlots.class);
    private final Character owner = mock(Character.class);
    private final Client client = mock(Client.class);
    private final Persona hadrik = persona("hadrik", "Hadrik");
    private final Persona mira = persona("mira", "Mira");
    private final Persona tobi = persona("tobi", "Tobi");
    private final RosterEntry hadrikRow = row("hadrik", RosterState.ACTIVE, 0L);
    private final RosterEntry tobiRow = row("tobi", RosterState.ACTIVE, 0L);
    private final RosterEntry miraRow = row("mira", RosterState.BENCHED, 4_872L);
    private final Character hadrikBot = mock(Character.class);
    private final List<RosterEntry> rows = new ArrayList<>();

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
            ((Runnable) inv.getArgument(0)).run(); // the owner's packet thread, at once unless a test queues it
            return null;
        }).when(client).runOnIoThread(any());
        when(owner.haveItem(ROSTER_BOOK)).thenReturn(true);
        when(world.content()).thenReturn(content);
        when(world.roster()).thenReturn(roster);
        when(world.store()).thenReturn(store);
        when(world.residents()).thenReturn(residents);
        when(world.directory()).thenReturn(directory);
        when(world.config()).thenReturn(new LivingWorldConfig());
        CompanionExecutor ops = DirectExecutor.inline();
        when(world.executor()).thenReturn(ops);
        when(world.partySync()).thenReturn(sync);
        when(world.slots()).thenReturn(slots);
        // Ann's own party unless a test says otherwise: the seats she cannot fill are taken
        when(slots.occupied(owner)).thenAnswer(inv -> RosterService.PARTY_CAP - roster.freeSlots(owner));
        when(content.portraitNpcId("hadrik")).thenReturn(HADRIK_PORTRAIT);
        when(roster.roster(owner)).thenReturn(rows);
        when(roster.describeAssignment(any())).thenReturn("train");
        when(roster.companion(hadrikRow)).thenReturn(hadrikBot);
        when(roster.entry(ANN, "hadrik")).thenReturn(hadrikRow);
        when(roster.freeSlots(owner)).thenReturn(3);
        MapleMap perion = mock(MapleMap.class);
        when(perion.getMapName()).thenReturn("Perion");
        when(hadrikBot.getLevel()).thenReturn(31);
        when(hadrikBot.getJob()).thenReturn(Job.FIGHTER);
        when(hadrikBot.getMap()).thenReturn(perion);
        when(hadrikBot.getHp()).thenReturn(812);
        when(hadrikBot.getCurrentMaxHp()).thenReturn(900);
        when(residents.characterIdOf("mira")).thenReturn(901);
        when(residents.characterIdOf("tobi")).thenReturn(902);
        when(store.characterLevelAndJob(901)).thenReturn(new int[] {28, Job.HUNTER.getId()});
        when(store.characterLevelAndJob(902)).thenReturn(new int[] {30, Job.CLERIC.getId()});
        when(roster.bench(any(), any())).thenReturn(new RosterResult.Ok("Hadrik heads back to camp to train."));
        when(roster.call(any(), any())).thenReturn(new RosterResult.Ok("Mira is on the way (1m)."));
        when(roster.swap(any(), any(), any())).thenReturn(new RosterResult.Ok("swapped."));
        // the stock seat rule under this mock: an AWAY companion kept its seat, anyone else needs a free one
        when(roster.canSeat(any(), any())).thenAnswer(inv -> ((RosterEntry) inv.getArgument(1)).state() == RosterState.AWAY
                || roster.freeSlots(inv.getArgument(0)) > 0);
        livingWorld = mockStatic(LivingWorld.class);
        livingWorld.when(LivingWorld::get).thenReturn(world);
        menus = new CompanionMenus(world, mapId -> "Ant Tunnel");
    }

    @AfterEach
    void close() {
        LivingTalk.forget(owner);
        livingWorld.close();
    }

    // ---- the card ------------------------------------------------------------------------

    @Test
    void theCardOfALoadedCompanionWithSomeoneOnTheBench() {
        rows.addAll(List.of(hadrikRow, miraRow));

        menus.card(owner, hadrikRow);

        Talk card = lastTalk();
        assertEquals(HADRIK_PORTRAIT, card.npcId());
        assertEquals(0, card.speaker());
        assertEquals(MENU, card.msgType());
        assertTrue(card.text().startsWith("#eHadrik#n, Lv31 Fighter - HP 812/900"), card.text());
        assertEquals(List.of("Stick with me", "Wait here", "Hunt here while I'm busy", "Take a break at camp",
                "Let someone take your place...", "What you do at camp...", "Camp somewhere else...", "Your haul...",
                "Your wages...", "Let me see your gear", "More..."), card.labels());
        pick("More...");
        assertEquals(List.of("Let's trade", "The whole crew...", "We should part ways", "Never mind", "More..."),
                lastTalk().labels()); // "More..." wraps round to the first page
    }

    @Test
    void theLastOneOutIsNeverBenchedWithoutTheRosterToCallItBack() {
        rows.addAll(List.of(hadrikRow, miraRow));
        when(owner.haveItem(ROSTER_BOOK)).thenReturn(false);

        menus.card(owner, hadrikRow);

        assertFalse(lastTalk().labels().contains("Take a break at camp"), lastTalk().text());
        assertFalse(lastTalk().labels().contains("What you do at camp..."), "nor sent off on an errand");
        assertTrue(lastTalk().labels().contains("Let someone take your place..."), lastTalk().text());
    }

    @Test
    void withTwoOutEitherMayTakeABreakEvenWithoutTheRoster() {
        rows.addAll(List.of(hadrikRow, tobiRow));
        when(owner.haveItem(ROSTER_BOOK)).thenReturn(false);

        menus.card(owner, hadrikRow);

        assertTrue(lastTalk().labels().contains("Take a break at camp"), lastTalk().text());
    }

    @Test
    void noSwapWithAnEmptyBenchAndNoGearWhenNotLoaded() {
        rows.add(tobiRow);

        menus.card(owner, tobiRow);

        assertEquals(List.of("Take a break at camp", "What you do at camp...", "Camp somewhere else...", "Your haul...",
                "Your wages...", "The whole crew...", "We should part ways", "Never mind"), lastTalk().labels());
    }

    @Test
    void aCompanionWithNoPortraitOfItsOwnSpeaksFromTheBulletinBoard() {
        rows.add(tobiRow);

        menus.card(owner, tobiRow);

        assertEquals(CompanionMenus.BOARD_NPC, lastTalk().npcId());
    }

    @Test
    void takeABreakBenchesThroughTheRosterAndTellsTheOutcome() {
        rows.add(hadrikRow);
        menus.card(owner, hadrikRow);

        pick("Take a break at camp");

        verify(roster).bench(owner, hadrik);
        Talk outcome = lastTalk();
        assertEquals(SAY, outcome.msgType());
        // the result is the player's notes, not the companion talking about itself under its own portrait
        assertEquals(CompanionMenus.BOARD_NPC, outcome.npcId());
        assertEquals(CompanionMenus.NOTES_SPEAKER, outcome.speaker());
        assertEquals("Hadrik heads back to camp to train.", outcome.text());
        answer(SAY, 1, null);
        assertFalse(LivingTalk.active(client));
    }

    @Test
    void letSomeoneTakeYourPlacePicksFromTheBenchAndSwapsOutForIn() {
        rows.addAll(List.of(hadrikRow, miraRow));
        menus.card(owner, hadrikRow);

        pick("Let someone take your place...");
        Talk picker = lastTalk();
        assertEquals(HADRIK_PORTRAIT, picker.npcId());
        assertTrue(picker.text().startsWith("who takes my place?\r\n"), picker.text());
        assertEquals(List.of("Mira, Lv28 Hunter - train @ Ant Tunnel - 10% EXP banked", "Back"), picker.labels());
        pick("Mira, Lv28 Hunter - train @ Ant Tunnel - 10% EXP banked");

        verify(roster).swap(owner, hadrik, mira);
        assertEquals("swapped.", lastTalk().text());
        assertEquals(CompanionMenus.NOTES_SPEAKER, lastTalk().speaker());
    }

    @Test
    void letMeSeeYourGearResendsTheStockCharacterInfo() {
        rows.add(hadrikRow);
        menus.card(owner, hadrikRow);
        Packet charInfo = mock(Packet.class);

        try (MockedStatic<PacketCreator> packets = mockStatic(PacketCreator.class, Mockito.CALLS_REAL_METHODS)) {
            packets.when(() -> PacketCreator.charInfo(hadrikBot)).thenReturn(charInfo);
            pick("Let me see your gear");
        }

        verify(hadrikBot).exportExcludedItems(client);
        verify(owner).sendPacket(charInfo);
        assertFalse(LivingTalk.active(client));
    }

    @Test
    void theGearOfACompanionBenchedWhileTheCardWasOpenIsNotShown() {
        rows.add(hadrikRow);
        menus.card(owner, hadrikRow);
        when(roster.entry(ANN, "hadrik")).thenReturn(row("hadrik", RosterState.BENCHED, 0L)); // benched from chat meanwhile

        pick("Let me see your gear");

        verify(hadrikBot, never()).exportExcludedItems(any());
        assertEquals("Hadrik is not out with you right now.", lastTalk().text());
    }

    // ---- the bench card ------------------------------------------------------------------

    @Test
    void aBenchedCompanionsCardCallsOrSwapsIn() {
        rows.addAll(List.of(hadrikRow, miraRow));

        menus.card(owner, miraRow);

        Talk card = lastTalk();
        assertTrue(card.text().startsWith("#eMira#n, Lv28 Hunter\r\ntrain @ Ant Tunnel - 10% EXP banked"), card.text());
        assertEquals(BENCH_CARD, card.labels());
        pick("Come join me");
        verify(roster).call(owner, mira);
        assertEquals("Mira is on the way (1m).", lastTalk().text());
        assertEquals(CompanionMenus.NOTES_SPEAKER, lastTalk().speaker());
    }

    @Test
    void comeJoinMeWithThePartyFullAsksWhomToTakeOverFromInsteadOfCalling() {
        rows.addAll(List.of(hadrikRow, tobiRow, miraRow));
        when(roster.freeSlots(owner)).thenReturn(0);
        menus.card(owner, miraRow);

        pick("Come join me");

        Talk picker = lastTalk();
        assertTrue(picker.text().startsWith("your party's full. who should i take over from?\r\n"), picker.text());
        assertEquals(List.of("Hadrik, Lv31 Fighter - Perion - HP 90%", "Tobi, Lv30 Cleric - on the way", "Back"),
                picker.labels());
        verify(roster, never()).call(any(), any());
        pick("Hadrik, Lv31 Fighter - Perion - HP 90%");
        verify(roster).swap(owner, hadrik, mira);
    }

    @Test
    void comeJoinMeInASharedPartyDoesNotCallAFriendsShareFull() {
        rows.addAll(List.of(hadrikRow, tobiRow, miraRow));
        when(roster.freeSlots(owner)).thenReturn(0);
        when(slots.occupied(owner)).thenReturn(5); // Ben's share has a seat he is not using
        menus.card(owner, miraRow);

        pick("Come join me");

        assertTrue(lastTalk().text().startsWith("the other seats are your friend's. who should i take over from?\r\n"),
                lastTalk().text());
        verify(roster, never()).call(any(), any());
    }

    @Test
    void comeJoinMeUnderTheLeaderCapSaysItIsTheLeadersDoingAndOffersTheSwap() {
        rows.addAll(List.of(hadrikRow, miraRow));
        when(roster.freeSlots(owner)).thenReturn(0);
        when(slots.occupied(owner)).thenReturn(4); // the leader expelled one of Ann's two: its seat stays empty
        when(slots.leaderCapped(owner)).thenReturn(true);
        menus.card(owner, miraRow);

        pick("Come join me");

        Talk picker = lastTalk();
        assertTrue(picker.text().startsWith("the leader sent one of us out. who should i take over from?\r\n"),
                picker.text());
        assertEquals(List.of("Hadrik, Lv31 Fighter - Perion - HP 90%", "Back"), picker.labels());
        verify(roster, never()).call(any(), any());
    }

    @Test
    void comeJoinMeUnderTheLeaderCapWithNobodyOutStaysAtCampInItsOwnWords() {
        rows.add(miraRow);
        when(roster.freeSlots(owner)).thenReturn(0);
        when(slots.occupied(owner)).thenReturn(3); // the leader left Ann none of her seats
        when(slots.leaderCapped(owner)).thenReturn(true);
        menus.card(owner, miraRow);

        pick("Come join me");

        assertEquals(RosterService.LEADER_CAP_LINE, lastTalk().text());
        assertNotEquals(CompanionMenus.NOTES_SPEAKER, lastTalk().speaker(), "Mira says it, not the player's notes");
        verify(roster, never()).call(any(), any());
    }

    @Test
    void anAwayCompanionKeptItsSeatSoItIsCalledEvenWithThePartyFull() {
        RosterEntry away = row("mira", RosterState.AWAY, 0L);
        rows.addAll(List.of(hadrikRow, away));
        when(roster.freeSlots(owner)).thenReturn(0);
        menus.card(owner, away);

        pick("Come join me");

        verify(roster).call(owner, mira);
    }

    @Test
    void aCompanionAlreadyOnTheWayShowsItsEtaAndOffersNothingToRepeat() {
        rows.addAll(List.of(hadrikRow, miraRow));
        when(roster.travelMsLeft(ANN, "mira")).thenReturn(40_000L);

        menus.crew(owner);
        assertEquals("Mira, Lv28 Hunter - on the way (~40 s)", lastTalk().labels().get(1));

        LivingTalk.forget(owner); // the Crew window closed
        menus.card(owner, miraRow);
        Talk card = lastTalk();
        assertTrue(card.text().startsWith("#eMira#n, Lv28 Hunter\r\non the way (~40 s)"), card.text());
        assertFalse(card.labels().contains("Come join me"), card.text());
        assertFalse(card.labels().contains("Swap in for..."), card.text());
    }

    @Test
    void swapInForPicksTheOutgoingCompanion() {
        rows.addAll(List.of(hadrikRow, tobiRow, miraRow));
        menus.card(owner, miraRow);

        pick("Swap in for...");
        Talk picker = lastTalk();
        assertEquals(List.of("Hadrik, Lv31 Fighter - Perion - HP 90%", "Tobi, Lv30 Cleric - on the way", "Back"),
                picker.labels());
        pick("Tobi, Lv30 Cleric - on the way");

        verify(roster).swap(owner, tobi, mira);
    }

    @Test
    void noSwapInWhenNobodyIsOut() {
        rows.add(miraRow);

        menus.card(owner, miraRow);

        assertEquals(List.of("Come join me", "What you do at camp...", "Camp somewhere else...", "Your haul...",
                "Your wages...", "We should part ways", "Never mind"), lastTalk().labels());
    }

    // ---- the Crew window -----------------------------------------------------------------

    @Test
    void theCrewWindowIsYourNotesWithThePartyThenTheBench() {
        rows.addAll(List.of(hadrikRow, miraRow));
        Party party = mock(Party.class);
        when(party.getMembers()).thenReturn(List.of(mock(PartyCharacter.class), mock(PartyCharacter.class)));
        when(owner.getParty()).thenReturn(party);
        when(roster.freeSlots(owner)).thenReturn(4);

        menus.crew(owner);

        Talk crew = lastTalk();
        assertEquals(CompanionMenus.NOTES_SPEAKER, crew.speaker());
        assertEquals(CompanionMenus.BOARD_NPC, crew.npcId());
        assertTrue(crew.text().startsWith("#eParty 2/6#n\r\n#L"), crew.text());
        assertTrue(crew.text().contains("#eBench (1 of 9)#n"), crew.text()); // one of the ten roster places is out
        assertEquals(List.of("Hadrik, Lv31 Fighter - Perion - HP 90%",
                "Mira, Lv28 Hunter - train @ Ant Tunnel - 10% EXP banked"), crew.labels());
    }

    @Test
    void aCrewRowOpensThatCompanionsCardAfterTheDirectoryConfirmsItIsStillYours() {
        rows.addAll(List.of(hadrikRow, miraRow));
        when(directory.resolveName(owner, "Mira")).thenReturn(new Relation.OwnCompanion(miraRow));
        menus.crew(owner);

        pick("Mira, Lv28 Hunter - train @ Ant Tunnel - 10% EXP banked");

        assertEquals(BENCH_CARD, lastTalk().labels());
    }

    @Test
    void aRowNoLongerYoursActsOnNothing() {
        rows.addAll(List.of(hadrikRow, miraRow));
        when(directory.resolveName(owner, "Mira")).thenReturn(new Relation.OthersCompanion(20, "mira"));
        menus.crew(owner);

        pick("Mira, Lv28 Hunter - train @ Ant Tunnel - 10% EXP banked");

        assertEquals("Mira is not in your roster.", lastTalk().text());
        assertEquals(CompanionMenus.NOTES_SPEAKER, lastTalk().speaker());
        verify(roster, never()).call(any(), any());
        verify(roster, never()).bench(any(), any());
        verify(roster, never()).swap(any(), any(), any());
    }

    @Test
    void theCrewWindowOpensWithEveryCompanionBenched() {
        rows.add(miraRow);

        menus.rosterBook(owner);

        Talk crew = lastTalk();
        assertTrue(crew.text().contains("(nobody out with me)"), crew.text());
        assertEquals(List.of("Mira, Lv28 Hunter - train @ Ant Tunnel - 10% EXP banked"), crew.labels());
    }

    @Test
    void bankedExpPastTheNextLevelReadsReadyToLevelNeverOverAHundredPercent() {
        rows.add(row("mira", RosterState.BENCHED, 50_000_000L));

        menus.rosterBook(owner);

        assertEquals(List.of("Mira, Lv28 Hunter - train @ Ant Tunnel - ready to level"), lastTalk().labels());
    }

    @Test
    void theRosterBookWithNoRosterIsAShortPinkLine() {
        menus.rosterBook(owner);

        verify(owner).dropMessage(5, "Nobody travels with you yet.");
        ArgumentCaptor<Packet> sent = ArgumentCaptor.forClass(Packet.class);
        verify(owner).sendPacket(sent.capture());
        assertTrue(NpcTalks.isEnableActions(sent.getValue()));
        verify(client, never()).sendPacket(any());
    }

    @Test
    void aLongCrewPagesWithMore() {
        for (int index = 0; index < 12; index++) {
            String id = "bench" + index;
            persona(id, "Bench" + index);
            rows.add(row(id, RosterState.BENCHED, 0L));
        }
        when(residents.characterIdOf(anyString())).thenReturn(0);

        menus.crew(owner);

        List<String> labels = lastTalk().labels();
        assertEquals(11, labels.size()); // ten rows, then "More..."
        assertEquals("Bench0 - train @ Ant Tunnel", labels.get(0)); // never loaded yet: no "Lv0"
        assertEquals("More...", labels.get(10));
    }

    @Test
    void anAwayCompanionIsOnTheBenchMarkedAway() {
        rows.add(row("mira", RosterState.AWAY, 0L));

        menus.crew(owner);

        assertEquals(List.of("Mira, Lv28 Hunter - away: train @ Ant Tunnel"), lastTalk().labels());
        verify(store, never()).characterLevel(anyInt());
    }

    @Test
    void thePartyCountIncludesTheSeatAnAwayCompanionKeeps() {
        rows.addAll(List.of(hadrikRow, row("mira", RosterState.AWAY, 0L)));
        when(slots.occupied(owner)).thenReturn(3); // Ann, Hadrik and the seat Mira keeps

        menus.crew(owner);

        assertTrue(lastTalk().text().startsWith("#eParty 3/6#n"), lastTalk().text());
    }

    @Test
    void thePartyCountIsTheSeatsTakenNotTheSeatsLeftToYou() {
        rows.addAll(List.of(hadrikRow, tobiRow));
        when(roster.freeSlots(owner)).thenReturn(0); // Ann's share is used
        when(slots.occupied(owner)).thenReturn(5); // Ann, two of hers, Ben and one of his

        menus.crew(owner);

        assertTrue(lastTalk().text().startsWith("#eParty 5/6#n"), lastTalk().text());
    }

    // ---- no work while a window is up ------------------------------------------------------

    @Test
    void whileAWindowAwaitsItsReplyNothingIsReadAndNoWindowIsSent() {
        rows.addAll(List.of(hadrikRow, miraRow));
        LivingTalk.open(owner, HADRIK_PORTRAIT).ok("still reading this").show();
        clearInvocations(client, roster, store, residents);

        menus.rosterBook(owner);
        menus.card(owner, hadrikRow);
        menus.card(owner, miraRow);

        verify(roster, never()).roster(any());
        verify(roster, never()).companion(any());
        verify(store, never()).characterLevelAndJob(anyInt());
        verify(client, never()).sendPacket(any());
        ArgumentCaptor<Packet> sent = ArgumentCaptor.forClass(Packet.class);
        verify(owner, Mockito.times(3)).sendPacket(sent.capture());
        assertTrue(sent.getAllValues().stream().allMatch(NpcTalks::isEnableActions));
    }

    @Test
    void aHeldDoubleClickInside500MsIsRefusedBeforeAnyRowIsRead() {
        rows.addAll(List.of(hadrikRow, miraRow));
        when(client.canClickNPC()).thenReturn(false);

        menus.rosterBook(owner);
        menus.card(owner, hadrikRow);

        verify(roster, never()).roster(any());
        verify(client, never()).sendPacket(any());
    }

    // ---- on companion-ops (P2a) -----------------------------------------------------------

    @Test
    void anActionRunsOnCompanionOpsAfterTheClickReleasedTheClient() {
        List<Runnable> queue = DirectExecutor.queue();
        CompanionExecutor queued = DirectExecutor.queued(queue);
        when(world.executor()).thenReturn(queued);
        rows.add(hadrikRow);
        menus.card(owner, hadrikRow);

        pick("Take a break at camp");

        verify(roster, never()).bench(any(), any()); // not on the packet thread
        assertFalse(LivingTalk.active(client));
        assertEquals(1, queue.size());
        queue.get(0).run();
        verify(roster).bench(owner, hadrik);
        assertEquals("Hadrik heads back to camp to train.", lastTalk().text());
    }

    @Test
    void anOutcomeReadyWhileAnotherWindowWaitsIsPinkTextInstead() {
        List<Runnable> queue = DirectExecutor.queue();
        CompanionExecutor queued = DirectExecutor.queued(queue);
        when(world.executor()).thenReturn(queued);
        rows.add(hadrikRow);
        menus.card(owner, hadrikRow);
        pick("Take a break at camp");
        LivingTalk.open(owner, 9000000).ok("a stock window the player opened meanwhile").show();
        clearInvocations(client);

        queue.get(0).run();

        verify(owner).dropMessage(5, "Hadrik heads back to camp to train."); // a note, not the companion's voice
        verify(residents, never()).whisper(any(), any(), anyString());
        verify(client, never()).sendPacket(any()); // nothing over the window awaiting its reply
    }

    @Test
    void theOutcomeIsAnsweredOnTheOwnersPacketThreadNotOnCompanionOps() {
        List<Runnable> queue = DirectExecutor.queue();
        CompanionExecutor queued = DirectExecutor.queued(queue);
        when(world.executor()).thenReturn(queued);
        List<Runnable> io = new ArrayList<>();
        doAnswer(inv -> io.add(inv.getArgument(0))).when(client).runOnIoThread(any());
        rows.add(hadrikRow);
        menus.card(owner, hadrikRow);
        pick("Take a break at camp");
        clearInvocations(client);

        queue.get(0).run(); // companion-ops

        verify(roster).bench(owner, hadrik);
        verify(client, never()).sendPacket(any()); // no window from companion-ops
        assertEquals(1, io.size());
        io.get(0).run();
        assertEquals("Hadrik heads back to camp to train.", lastTalk().text());
    }

    @Test
    void anActionThatTookLongAnswersInPinkTextInsteadOfPoppingAWindowUp() {
        List<Runnable> queue = DirectExecutor.queue();
        CompanionExecutor queued = DirectExecutor.queued(queue);
        when(world.executor()).thenReturn(queued);
        when(roster.bench(any(), any())).thenAnswer(inv -> {
            Thread.sleep(CompanionMenus.LATE_OUTCOME_MS + 50L); // a slow load or the audit ahead of it
            return new RosterResult.Ok("Hadrik heads back to camp to train.");
        });
        rows.add(hadrikRow);
        menus.card(owner, hadrikRow);
        pick("Take a break at camp");
        clearInvocations(client);

        queue.get(0).run();

        verify(owner).dropMessage(5, "Hadrik heads back to camp to train.");
        verify(client, never()).sendPacket(any());
    }

    @Test
    void aFullPartyOffersToMakeRoomForAFriend() {
        rows.addAll(List.of(hadrikRow, tobiRow));
        when(roster.companion(tobiRow)).thenReturn(null);
        fullParty(6);
        when(sync.makeRoom(ANN, "tobi")).thenReturn(true);

        menus.crew(owner);
        pick("Make room for a friend...");

        Talk picker = lastTalk();
        assertEquals(CompanionMenus.NOTES_SPEAKER, picker.speaker());
        assertTrue(picker.text().startsWith("Who steps out for your friend?\r\n"), picker.text());
        assertEquals(List.of("Hadrik, Lv31 Fighter - Perion - HP 90%", "Tobi, Lv30 Cleric - on the way", "Never mind"),
                picker.labels());
        pick("Tobi, Lv30 Cleric - on the way");

        verify(sync).makeRoom(ANN, "tobi");
        assertEquals("Tobi steps out to make room for your friend.", lastTalk().text());
    }

    @Test
    void aPartyWithRoomHasNoMakeRoom() {
        rows.addAll(List.of(hadrikRow, tobiRow));
        fullParty(5);

        menus.crew(owner);

        assertFalse(lastTalk().labels().contains("Make room for a friend..."));
    }

    // ---- helpers -------------------------------------------------------------------------

    private void fullParty(int members) {
        Party party = mock(Party.class);
        List<PartyCharacter> list = new ArrayList<>();
        for (int i = 0; i < members; i++) {
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

    private List<Packet> sent() {
        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(client, atLeastOnce()).sendPacket(captor.capture());
        return captor.getAllValues();
    }

    private Talk lastTalk() {
        List<Talk> talks = NpcTalks.talks(sent());
        return talks.get(talks.size() - 1);
    }

    private Persona persona(String id, String name) {
        Persona persona = mock(Persona.class);
        when(persona.id()).thenReturn(id);
        when(persona.name()).thenReturn(name);
        when(content.persona(id)).thenReturn(persona);
        return persona;
    }

    private static RosterEntry row(String personaId, RosterState state, long bankedExp) {
        return new RosterEntry(ANN, personaId, state, Assignment.TRAIN, 0, 0, 105040300, bankedExp, 0L, 0L, 0L, 0L, 0L, 0, null);
    }
}
