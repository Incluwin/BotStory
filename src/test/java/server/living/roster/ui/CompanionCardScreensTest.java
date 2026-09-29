package server.living.roster.ui;

import client.Character;
import client.Client;
import client.Job;
import config.LivingWorldConfig;
import net.packet.Packet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import server.living.LivingWorld;
import server.living.bond.BondService;
import server.living.config.Content.BondStage;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.resident.LivingTalk;
import server.living.resident.ResidentRuntime;
import server.living.roster.BenchLedger;
import server.living.roster.Commitments;
import server.living.roster.CompanionDirectory;
import server.living.roster.CompanionExecutor;
import server.living.roster.CompanionManager;
import server.living.roster.CompanionSlots;
import server.living.roster.CompanionViews;
import server.living.roster.DirectExecutor;
import server.living.roster.RosterResult;
import server.living.roster.RosterService;
import server.living.store.LivingStore;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.Bond;
import server.living.store.LivingStore.ContractState;
import server.living.store.LivingStore.HaulItem;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;
import testutil.NpcTalks;
import testutil.NpcTalks.Talk;
import testutil.Packets;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The P3 companion card (docs/BOT_MANAGEMENT_PLAN.md "Player experience > Companion card", flows 1, 2 and 10): the options
 * per seat state (out with you, on its way, waiting at a run's entrance, benched, away, parked), the header with the
 * potions it really carries, orders the companion answers in its own words, the duty picker with the bench priors'
 * rates (an errand for one out with you, FARM_ITEM's item and count box), the camp picker, the haul (hand over, wear the
 * best), the wages box, the stock trade, and parting ways behind a Yes/No. While it is tied to a run, bench, swap,
 * errands and its camp read "after the run"; a click after the owner's rows changed re-renders the card; a companion
 * no longer in the roster is never acted on.
 */
class CompanionCardScreensTest {
    private static final int ANN = 10;
    private static final int HADRIK_PORTRAIT = 9970001;
    private static final int ROSTER_BOOK = 2430011;
    private static final int ANT_TUNNEL = 105040300;
    private static final int ANT_TUNNEL_2 = 105040301;
    private static final int HILL = 100010000;
    private static final int HENESYS = 100000000;
    private static final int STOLEN_FENCE = 4000021;
    private static final int SNAIL_SHELL = 4000022;
    private static final byte MENU = 4;
    private static final byte YES_NO = 1;
    private static final byte NUMBER = 3;

    private final LivingWorld world = mock(LivingWorld.class);
    private final LivingContent content = mock(LivingContent.class);
    private final RosterService roster = mock(RosterService.class);
    private final LivingStore store = mock(LivingStore.class);
    private final ResidentRuntime residents = mock(ResidentRuntime.class);
    private final CompanionSlots slots = mock(CompanionSlots.class);
    private final CompanionManager manager = mock(CompanionManager.class, invocation -> new RosterResult.Ok(""));
    private final BenchLedger bench = mock(BenchLedger.class);
    private final Commitments commitments = mock(Commitments.class);
    private final Character owner = mock(Character.class);
    private final Client client = mock(Client.class);
    private final Persona hadrik = persona("hadrik", "Hadrik");
    private final Persona mira = persona("mira", "Mira");
    private final Persona tobi = persona("tobi", "Tobi");
    private final RosterEntry hadrikRow = row("hadrik", RosterState.ACTIVE, 0L);
    private final RosterEntry miraRow = row("mira", RosterState.BENCHED, 0L);
    private final Character hadrikBot = mock(Character.class);
    private final List<RosterEntry> rows = new ArrayList<>();
    private final Map<Integer, String> places = Map.of(ANT_TUNNEL, "Ant Tunnel I", ANT_TUNNEL_2, "Ant Tunnel II",
            HILL, "The Hill East of Henesys", HENESYS, "Henesys");
    private final Map<Integer, Integer> fields = new LinkedHashMap<>();
    private final Set<Integer> fieldMaps = new HashSet<>(Set.of(ANT_TUNNEL, ANT_TUNNEL_2, HILL));
    private final Map<Integer, Integer> drops = new LinkedHashMap<>();
    private final Map<Integer, Integer> needs = new LinkedHashMap<>();

    private MockedStatic<LivingWorld> livingWorld;
    private CompanionMenus menus;

    @BeforeEach
    void wire() {
        when(owner.getId()).thenReturn(ANN);
        when(owner.getName()).thenReturn("Ann");
        when(owner.getClient()).thenReturn(client);
        when(owner.haveItem(ROSTER_BOOK)).thenReturn(true);
        when(client.getPlayer()).thenReturn(owner);
        when(client.canClickNPC()).thenReturn(true);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run(); // the owner's packet thread, at once
            return null;
        }).when(client).runOnIoThread(any());
        when(world.content()).thenReturn(content);
        when(world.roster()).thenReturn(roster);
        when(world.store()).thenReturn(store);
        when(world.residents()).thenReturn(residents);
        when(world.config()).thenReturn(new LivingWorldConfig());
        CompanionExecutor ops = DirectExecutor.inline();
        when(world.executor()).thenReturn(ops);
        when(world.slots()).thenReturn(slots);
        when(world.manager()).thenReturn(manager);
        when(world.bench()).thenReturn(bench);
        when(world.commitments()).thenReturn(commitments);
        when(slots.occupied(owner)).thenReturn(3);
        when(content.portraitNpcId("hadrik")).thenReturn(HADRIK_PORTRAIT);
        when(roster.roster(owner)).thenReturn(rows);
        when(roster.describeAssignment(any())).thenReturn("train");
        when(roster.companion(hadrikRow)).thenReturn(hadrikBot);
        when(roster.freeSlots(owner)).thenReturn(3);
        when(roster.canSeat(any(), any())).thenReturn(true);
        when(roster.call(any(), any())).thenReturn(new RosterResult.Ok("Tobi is on the way (1m)."));
        when(roster.bench(any(), any())).thenReturn(new RosterResult.Ok("Hadrik heads back to camp to train."));
        MapleMap perion = mock(MapleMap.class);
        when(perion.getMapName()).thenReturn("Perion");
        when(hadrikBot.getLevel()).thenReturn(31);
        when(hadrikBot.getJob()).thenReturn(Job.FIGHTER);
        when(hadrikBot.getMap()).thenReturn(perion);
        when(hadrikBot.getHp()).thenReturn(812);
        when(hadrikBot.getCurrentMaxHp()).thenReturn(900);
        when(residents.characterIdOf("hadrik")).thenReturn(900);
        when(residents.characterIdOf("mira")).thenReturn(901);
        when(residents.characterIdOf("tobi")).thenReturn(902);
        when(store.characterLevelAndJob(901)).thenReturn(new int[] {28, Job.HUNTER.getId()});
        when(store.characterLevelAndJob(902)).thenReturn(new int[] {30, Job.CLERIC.getId()});
        livingWorld = mockStatic(LivingWorld.class);
        livingWorld.when(LivingWorld::get).thenReturn(world);
        menus = new CompanionMenus(world, mapId -> places.getOrDefault(mapId, "map " + mapId));
        menus.cards().catalog = new CompanionCardScreens.Catalog() {
            @Override
            public Map<Integer, Integer> fields(int level, int nearMapId) {
                return fields;
            }

            @Override
            public boolean isField(int mapId) {
                return fieldMaps.contains(mapId);
            }

            @Override
            public Map<Integer, Integer> drops(int mapId) {
                return drops;
            }

            @Override
            public Map<Integer, Integer> questNeeds(Character player) {
                return needs;
            }

            @Override
            public String potions(Character bot) {
                return bot == hadrikBot ? "38 Red Potions" : "";
            }
        };
    }

    @AfterEach
    void close() {
        LivingTalk.forget(owner);
        livingWorld.close();
    }

    // ---- the card per state ----------------------------------------------------------------

    @Test
    void theCardOfOneOutWithYouShowsItsRealPotionsUnderItsOwnPortrait() {
        rows.addAll(List.of(hadrikRow, miraRow));

        menus.card(owner, hadrikRow);

        Talk card = lastTalk();
        assertEquals(HADRIK_PORTRAIT, card.npcId());
        assertEquals(MENU, card.msgType());
        assertTrue(card.text().startsWith("#eHadrik#n, Lv31 Fighter - HP 812/900 - 38 Red Potions\r\n\r\n#L"), card.text());
        assertEquals(List.of("Stick with me", "Wait here", "Hunt here while I'm busy", "Take a break at camp",
                "Let someone take your place...", "What you do at camp...", "Camp somewhere else...", "Your haul...",
                "Your wages...", "Let me see your gear", "More..."), card.labels());
    }

    @Test
    void ordersGoToTheManagerAndTheCompanionAnswersInItsOwnWords() {
        rows.add(hadrikRow);
        for (String order : List.of("Stick with me", "Wait here", "Hunt here while I'm busy")) {
            menus.card(owner, hadrikRow);
            int talks = talks().size();

            pick(order);

            assertEquals(talks, talks().size(), order + ": no notes over the companion's own reply");
            assertFalse(LivingTalk.active(client));
        }
        verify(manager).followMe(owner, hadrik);
        verify(manager).waitHere(owner, hadrik);
        verify(manager).huntHere(owner, hadrik);
    }

    @Test
    void anOrderDoneWhileAnotherWindowWaitsSaysNothingWhenTheCompanionAnswered() {
        List<Runnable> queue = DirectExecutor.queue();
        CompanionExecutor queued = DirectExecutor.queued(queue);
        when(world.executor()).thenReturn(queued);
        rows.add(hadrikRow);
        menus.card(owner, hadrikRow);
        pick("Stick with me");
        LivingTalk.open(owner, 9000000).ok("a stock window the player opened meanwhile").show();
        int talks = talks().size();

        queue.get(0).run();

        verify(manager).followMe(owner, hadrik);
        verify(owner, never()).dropMessage(anyInt(), any());
        assertEquals(talks, talks().size(), "nothing over the window awaiting its reply");
    }

    @Test
    void anOrderItCannotTakeSaysWhyInTheNotes() {
        rows.add(hadrikRow);
        when(manager.followMe(owner, hadrik)).thenReturn(new RosterResult.Failed("Hadrik is not out with you right now."));
        menus.card(owner, hadrikRow);

        pick("Stick with me");

        assertEquals("Hadrik is not out with you right now.", lastTalk().text());
        assertEquals(CompanionMenus.NOTES_SPEAKER, lastTalk().speaker());
    }

    @Test
    void oneOnItsWayShowsTheClockAndTakesNoOrdersYet() {
        RosterEntry travelling = row("tobi", RosterState.TRAVELLING, 0L);
        rows.addAll(List.of(hadrikRow, travelling));
        when(roster.travelMsLeft(ANN, "tobi")).thenReturn(40_000L);

        menus.card(owner, travelling);

        Talk card = lastTalk();
        assertTrue(card.text().startsWith("#eTobi#n, Lv30 Cleric\r\non the way (0:40)\r\n"), card.text());
        assertEquals(List.of("Take a break at camp", "What you do at camp...", "Camp somewhere else...", "Your haul...",
                "Your wages...", "The whole crew...", "We should part ways", "Never mind"), card.labels());
    }

    @Test
    void oneWaitingAtARunsEntranceCanBeAskedToCome() {
        RosterEntry held = row("tobi", RosterState.HOLD, 0L);
        rows.addAll(List.of(hadrikRow, held));
        menus.card(owner, held);
        assertTrue(lastTalk().text().contains("waiting at the entrance"), lastTalk().text());

        pick("Come join me");

        verify(roster).call(owner, tobi);
    }

    @Test
    void theBenchCardIsTheSameForBenchedAwayAndParked() {
        for (RosterState state : List.of(RosterState.BENCHED, RosterState.AWAY, RosterState.PARKED)) {
            rows.clear();
            RosterEntry entry = row("mira", state, 0L);
            rows.add(entry);

            menus.card(owner, entry);

            assertEquals(List.of("Come join me", "What you do at camp...", "Camp somewhere else...", "Your haul...",
                    "Your wages...", "We should part ways", "Never mind"), lastTalk().labels(), state.name());
            LivingTalk.forget(owner);
        }
    }

    // ---- tied to a run ---------------------------------------------------------------------

    @Test
    void whileTiedToARunBenchSwapErrandsAndCampWaitForIt() {
        rows.addAll(List.of(hadrikRow, miraRow));
        when(commitments.isCommitted(900)).thenReturn(true);

        menus.card(owner, hadrikRow);

        Talk card = lastTalk();
        assertEquals(List.of("Stick with me", "Wait here", "Hunt here while I'm busy", "Your haul...", "Your wages...",
                "Let me see your gear", "Let's trade", "The whole crew...", "We should part ways", "Never mind"),
                card.labels());
        for (String waits : List.of("Take a break at camp", "Let someone take your place", "What you do at camp",
                "Camp somewhere else")) {
            assertTrue(card.text().contains("\r\n" + waits + " (after the run)\r\n"), card.text());
        }
        assertTrue(card.text().contains("in a run with you"), card.text());
    }

    @Test
    void onTheBenchWhileTiedToARunItCanStillBeCalledButNotSwappedOrSentOff() {
        rows.addAll(List.of(hadrikRow, miraRow));
        when(commitments.isCommitted(901)).thenReturn(true);

        menus.card(owner, miraRow);

        assertEquals(List.of("Come join me", "Your haul...", "Your wages...", "We should part ways", "Never mind"),
                lastTalk().labels());
        assertTrue(lastTalk().text().contains("Swap in for (after the run)"), lastTalk().text());
    }

    @Test
    void aCampChangeClickedAfterTheRunBeganAnswersAfterTheRun() {
        rows.add(hadrikRow);
        fields.put(ANT_TUNNEL_2, 30);
        menus.card(owner, hadrikRow);
        pick("Camp somewhere else...");
        when(commitments.isCommitted(900)).thenReturn(true); // the run began while the picker was open

        pick("Ant Tunnel II (Lv30 monsters)");

        verify(manager, never()).setCamp(any(), any(), anyInt());
        assertEquals("Hadrik: after the run.", lastTalk().text());
    }

    // ---- a stale click ---------------------------------------------------------------------

    @Test
    void aClickAfterTheOwnersRowsChangedReRendersTheCardInsteadOfActing() {
        CompanionViews views = cachedViews();
        rows.addAll(List.of(hadrikRow, miraRow));
        menus.card(owner, hadrikRow);

        views.invalidate(ANN); // a roster write or a party change touched Ann's rows meanwhile
        pick("Take a break at camp");

        verify(roster, never()).bench(any(), any());
        Talk again = lastTalk();
        assertTrue(again.text().startsWith(CompanionMenus.THINGS_CHANGED + "\r\n#eHadrik#n"), again.text());
        pick("Take a break at camp"); // the re-rendered card acts
        verify(roster).bench(owner, hadrik);
    }

    @Test
    void aStaleClickOnOneReleasedMeanwhileActsOnNothing() {
        CompanionViews views = cachedViews();
        rows.addAll(List.of(hadrikRow, miraRow));
        menus.card(owner, hadrikRow);

        rows.remove(hadrikRow);
        views.invalidate(ANN);
        pick("Your haul...");

        assertEquals("Hadrik is not in your roster.", lastTalk().text());
        verify(roster, never()).handOver(any(), any());
    }

    @Test
    void aPageOfOneNoLongerInYourRosterIsNeverOpened() {
        rows.add(hadrikRow);
        menus.card(owner, hadrikRow);

        rows.clear(); // released from chat while the card was open (no cache to stamp against)
        pick("Your wages...");

        assertEquals("Hadrik is not in your roster.", lastTalk().text());
        verify(roster, never()).payWages(any(), any(), anyLong());
    }

    // ---- what it does at camp ---------------------------------------------------------------

    @Test
    void theDutyPickerShowsTheProjectedRatesOfTheBenchPriors() {
        rows.add(hadrikRow);
        rates();
        menus.card(owner, hadrikRow);

        pick("What you do at camp...");

        Talk picker = lastTalk();
        assertEquals(HADRIK_PORTRAIT, picker.npcId());
        assertTrue(picker.text().startsWith("what should i go do? i'll keep my seat.\r\nnow: train @ Ant Tunnel I\r\n"
                + "upkeep: 930 meso an hour, none while i rest\r\n"), picker.text());
        assertEquals(List.of("Train (~1.2% EXP/h)", "Farm meso (~4.1k meso/h after upkeep)", "Farm an item...",
                "Gather for your quests", "Sell the haul", "Run a stall", "Rest (no upkeep)", "Back"), picker.labels());
        pick("Back");
        assertTrue(lastTalk().text().startsWith("#eHadrik#n"), "Back is the card again");
    }

    @Test
    void aDutyForOneOutWithYouSendsItOffAndItKeepsTheSeat() {
        rows.add(hadrikRow);
        rates();
        RosterEntry away = row("hadrik", RosterState.AWAY, 0L);
        when(roster.entry(ANN, "hadrik")).thenReturn(hadrikRow, away);
        menus.card(owner, hadrikRow);
        pick("What you do at camp...");

        pick("Train (~1.2% EXP/h)");

        verify(manager).assign(owner, hadrik, Assignment.TRAIN, 0, 0);
        verify(manager, never()).setCamp(any(), any(), anyInt());
        assertEquals("Hadrik leaves to train @ Ant Tunnel I and keeps the seat.", lastTalk().text());
    }

    @Test
    void aFieldDutyWithATownForACampAsksWhereFirstThenSetsBoth() {
        RosterEntry inTown = new RosterEntry(ANN, "mira", RosterState.BENCHED, Assignment.REST, 0, 0, HENESYS, 0L, 0L,
                0L, 0L, 0L, 0L, 0, null);
        rows.add(inTown);
        fields.put(ANT_TUNNEL, 27);
        fields.put(ANT_TUNNEL_2, 30);
        when(owner.getMapId()).thenReturn(HENESYS); // in town: no "Right here" for a field duty
        when(roster.entry(ANN, "mira")).thenReturn(inTown);
        menus.card(owner, inTown);
        pick("What you do at camp...");

        pick("Farm meso");

        Talk where = lastTalk();
        assertTrue(where.text().startsWith("i can't do that in town. where should i go?"), where.text());
        assertEquals(List.of("Ant Tunnel I (Lv27 monsters)", "Ant Tunnel II (Lv30 monsters)", "Back"), where.labels());
        pick("Ant Tunnel II (Lv30 monsters)");

        InOrder order = inOrder(manager);
        order.verify(manager).setCamp(owner, mira, ANT_TUNNEL_2);
        order.verify(manager).assign(owner, mira, Assignment.FARM_MESO, 0, 0);
    }

    @Test
    void farmItemPicksFromTheCampsDropsAndTheQuestNeedsThenTakesACount() {
        rows.add(miraRow);
        when(roster.entry(ANN, "mira")).thenReturn(miraRow);
        drops.put(STOLEN_FENCE, 400_000);
        needs.put(STOLEN_FENCE, 30);
        needs.put(SNAIL_SHELL, 12);
        when(bench.itemsPerHour(400_000)).thenReturn(26.4d);
        menus.card(owner, miraRow);
        pick("What you do at camp...");

        pick("Farm an item...");

        Talk items = lastTalk();
        assertTrue(items.text().startsWith("what should i farm at Ant Tunnel I?"), items.text());
        assertTrue(items.text().contains("#eFor your quests:#n"), items.text());
        assertEquals(List.of("#i4000021# #t4000021# (~26.4/h) - you need 30", "#i4000022# #t4000022# - you need 12", "Back"),
                items.labels());
        pick("#i4000022# #t4000022# - you need 12");

        Talk box = lastTalk();
        assertEquals(NUMBER, box.msgType());
        assertEquals("how many #t4000022#? (0: as many as i can)", box.text());
        assertEquals(List.of(12, 0, 9_999), numberBounds(lastTalkPacket()), "the quest's need is where the box starts");
        answer(NUMBER, 1, 200);

        verify(manager).assign(owner, mira, Assignment.FARM_ITEM, SNAIL_SHELL, 200);
        assertEquals("Mira will train @ Ant Tunnel I.", lastTalk().text());
    }

    @Test
    void cancellingTheCountGoesBackToTheItems() {
        rows.add(miraRow);
        drops.put(STOLEN_FENCE, 400_000);
        menus.card(owner, miraRow);
        pick("What you do at camp...");
        pick("Farm an item...");
        pick(lastTalk().labels().get(0));

        answer(NUMBER, 0, null);

        assertEquals(MENU, lastTalk().msgType());
        assertTrue(lastTalk().text().startsWith("what should i farm at Ant Tunnel I?"), lastTalk().text());
        verify(manager, never()).assign(any(), any(), any(), anyInt(), anyInt());
    }

    // ---- where it camps ---------------------------------------------------------------------

    @Test
    void theCampPickerOffersFittingFieldsAndRightHere() {
        rows.add(hadrikRow);
        fields.put(ANT_TUNNEL, 27);
        fields.put(ANT_TUNNEL_2, 30);
        when(owner.getMapId()).thenReturn(HILL);
        menus.card(owner, hadrikRow);

        pick("Camp somewhere else...");

        Talk picker = lastTalk();
        assertTrue(picker.text().startsWith("where should i camp? now: Ant Tunnel I"), picker.text());
        assertEquals(List.of("Ant Tunnel I (Lv27 monsters) - my camp now", "Ant Tunnel II (Lv30 monsters)",
                "Right here (The Hill East of Henesys)", "Back"), picker.labels());
        pick("Right here (The Hill East of Henesys)");

        verify(manager).setCamp(owner, hadrik, HILL);
        verify(manager, never()).assign(any(), any(), any(), anyInt(), anyInt());
        assertEquals("Hadrik will camp at The Hill East of Henesys.", lastTalk().text());
    }

    // ---- haul, gear, trade ------------------------------------------------------------------

    @Test
    void theHaulListsWhatItCarriesAndHandsItOver() {
        RosterEntry carrying = haulRow();
        rows.add(carrying);
        when(roster.handOver(owner, hadrik)).thenReturn("Hadrik hands over 2 item stack(s) and 3120 meso.");
        menus.card(owner, carrying);

        pick("Your haul...");

        Talk haul = lastTalk();
        assertEquals("my haul:\r\n3,120 meso\r\n#t4000021# x12\r\n#t1302000# x1\r\n\r\n", haul.text().substring(0,
                haul.text().indexOf("#L")));
        assertEquals(List.of("Hand it over", "Send it by Duey", "Bank the meso", "Wear your best from the haul", "Back"),
                haul.labels());
        pick("Hand it over");

        verify(roster).handOver(owner, hadrik);
        assertEquals("Hadrik hands over 2 item stack(s) and 3120 meso.", lastTalk().text());
    }

    @Test
    void wearYourBestFromTheHaulGoesThroughTheManager() {
        RosterEntry carrying = haulRow();
        rows.add(carrying);
        when(manager.wearBestFromHaul(owner, hadrik)).thenReturn(new RosterResult.Ok("Hadrik puts on Sword from the haul."));
        menus.card(owner, carrying);
        pick("Your haul...");

        pick("Wear your best from the haul");

        verify(manager).wearBestFromHaul(owner, hadrik);
        assertEquals("Hadrik puts on Sword from the haul.", lastTalk().text());
    }

    @Test
    void aBenchedCompanionsHaulCannotBeWornFromCamp() {
        RosterEntry benched = new RosterEntry(ANN, "mira", RosterState.BENCHED, Assignment.TRAIN, 0, 0, ANT_TUNNEL, 0L, 0L,
                0L, 0L, 0L, 0L, 0, null);
        rows.add(benched);
        when(store.haul(ANN, "mira")).thenReturn(List.of(new HaulItem(1302000, 1)));
        menus.card(owner, benched);

        pick("Your haul...");

        assertEquals(List.of("Hand it over", "Send it by Duey", "Back"), lastTalk().labels());
    }

    @Test
    void letsTradeOpensTheStockTradeWindowWithNoNotesOverIt() {
        rows.add(hadrikRow);
        menus.card(owner, hadrikRow);
        int talks = talks().size();

        pick("Let's trade");

        verify(manager).openTrade(owner, hadrik);
        assertEquals(talks, talks().size(), "the trade window is the answer");
    }

    @Test
    void aTradeThatCannotOpenSaysWhy() {
        rows.add(hadrikRow);
        when(manager.openTrade(owner, hadrik)).thenReturn(new RosterResult.Failed("Hadrik is too far away to trade."));
        menus.card(owner, hadrikRow);

        pick("Let's trade");

        assertEquals("Hadrik is too far away to trade.", lastTalk().text());
    }

    // ---- the whole crew -----------------------------------------------------------------------

    @Test
    void theWholeCrewIsTheCrewWindowInTheCompanionsVoiceWithBackToItsCard() {
        rows.add(hadrikRow);
        CompanionDirectory directory = mock(CompanionDirectory.class);
        when(world.directory()).thenReturn(directory);
        when(directory.resolveName(owner, "Hadrik")).thenReturn(new CompanionDirectory.Relation.OwnCompanion(hadrikRow));
        menus.card(owner, hadrikRow);
        pick("More...");

        pick("The whole crew...");

        Talk crew = lastTalk();
        assertEquals(HADRIK_PORTRAIT, crew.npcId());
        assertTrue(crew.text().startsWith("#eParty 3/6#n"), crew.text());
        pick("Back");
        assertTrue(lastTalk().text().startsWith("#eHadrik#n"), lastTalk().text());
    }

    // ---- wages ------------------------------------------------------------------------------

    @Test
    void wagesShowTheStatusAndPayHoursAheadInTheNumberBox() {
        rows.add(hadrikRow);
        when(owner.getMeso()).thenReturn(10_000);
        when(roster.payWages(owner, hadrik, 2_790L)).thenReturn("Hadrik is paid up for ~3 h.");
        menus.card(owner, hadrikRow);

        pick("Your wages...");

        Talk wages = lastTalk();
        assertTrue(wages.text().startsWith("i'm working off the haul.\r\nmy upkeep is 930 meso an hour;"), wages.text());
        assertEquals(List.of("Pay your wages...", "Back"), wages.labels());
        pick("Pay your wages...");
        assertEquals(NUMBER, lastTalk().msgType());
        assertEquals(List.of(2, 1, 10), numberBounds(lastTalkPacket()), "no more hours than Ann can pay for");
        answer(NUMBER, 1, 3);

        verify(roster).payWages(owner, hadrik, 2_790L);
        assertEquals("Hadrik is paid up for ~3 h.", lastTalk().text());
    }

    @Test
    void wagesSheCannotPayForOfferNoBox() {
        rows.add(hadrikRow);
        when(owner.getMeso()).thenReturn(100);
        menus.card(owner, hadrikRow);

        pick("Your wages...");

        assertEquals(List.of("Back"), lastTalk().labels());
        assertTrue(lastTalk().text().contains("(an hour is 930 meso)"), lastTalk().text());
    }

    // ---- parting ways (flow 10) -------------------------------------------------------------

    @Test
    void partingWaysAsksYesNoWithTheBondAndTheHaulThenReleases() {
        RosterEntry carrying = haulRow();
        rows.add(carrying);
        BondService bonds = mock(BondService.class);
        when(world.bonds()).thenReturn(bonds);
        when(bonds.peek(ANN, "hadrik")).thenReturn(new Bond(ANN, "hadrik", BondStage.TRUSTED, 100, new LinkedHashSet<>(),
                ContractState.ACTIVE, 1_000L, 0L, 0, 0L, 60, 0L, 1_000L));
        when(manager.release(owner, hadrik)).thenReturn(new RosterResult.Ok("Hadrik hands over 2 item stack(s). Hadrik leaves."));
        menus.card(owner, carrying);
        pick("More...");

        pick("We should part ways");

        Talk confirm = lastTalk();
        assertEquals(YES_NO, confirm.msgType());
        assertEquals(CompanionMenus.BOARD_NPC, confirm.npcId());
        assertEquals(CompanionMenus.NOTES_SPEAKER, confirm.speaker());
        assertEquals("Part ways with Hadrik for good?\r\n\r\nBond: Trusted\r\nHaul: 3,120 meso, 2 stacks, handed over first"
                + " (Duey takes what doesn't fit)", confirm.text());
        verify(manager, never()).release(any(), any());
        answer(YES_NO, 1, null);

        verify(manager).release(owner, hadrik);
        assertEquals("Hadrik hands over 2 item stack(s). Hadrik leaves.", lastTalk().text());
    }

    @Test
    void noKeepsTheCompanion() {
        rows.add(hadrikRow);
        menus.card(owner, hadrikRow);
        pick("More...");
        pick("We should part ways");

        answer(YES_NO, 0, null);

        verify(manager, never()).release(any(), any());
        assertFalse(LivingTalk.active(client));
    }

    // ---- helpers -------------------------------------------------------------------------

    /** Rates at the priors for Hadrik (Lv31): 1.23% EXP an hour training, 4,930 meso an hour farming, 930 upkeep. */
    private void rates() {
        when(bench.rates(any(), any(), anyInt(), anyInt(), any())).thenAnswer(inv -> switch ((Assignment) inv.getArgument(4)) {
            case TRAIN -> new BenchLedger.Rates(1.23d, 0L, 930L);
            case FARM_MESO -> new BenchLedger.Rates(0.0d, 5_030L, 930L);
            case REST -> new BenchLedger.Rates(0.0d, 0L, 0L);
            default -> new BenchLedger.Rates(0.0d, 0L, 930L);
        });
    }

    /** Hadrik out with Ann carrying 3,120 meso, 12 Stolen Fences and a sword. */
    private RosterEntry haulRow() {
        RosterEntry carrying = new RosterEntry(ANN, "hadrik", RosterState.ACTIVE, Assignment.TRAIN, 0, 0, ANT_TUNNEL, 0L,
                3_120L, 0L, 0L, 0L, 0L, 0, null);
        when(roster.companion(carrying)).thenReturn(hadrikBot);
        when(store.haul(ANN, "hadrik")).thenReturn(List.of(new HaulItem(STOLEN_FENCE, 12), new HaulItem(1302000, 1)));
        return carrying;
    }

    private CompanionViews cachedViews() {
        CompanionViews views = new CompanionViews(world, mapId -> places.getOrDefault(mapId, "map " + mapId),
                itemId -> "item " + itemId);
        when(world.views()).thenReturn(views);
        when(store.roster(ANN)).thenAnswer(inv -> List.copyOf(rows));
        return views;
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

    private List<Talk> talks() {
        return NpcTalks.talks(sent());
    }

    private Talk lastTalk() {
        List<Talk> talks = talks();
        return talks.get(talks.size() - 1);
    }

    private Packet lastTalkPacket() {
        List<Packet> talks = sent().stream().filter(NpcTalks::isTalk).toList();
        return talks.get(talks.size() - 1);
    }

    /** A number box's {default, min, max}, after its text. */
    private static List<Integer> numberBounds(Packet packet) {
        byte[] bytes = packet.getBytes();
        int length = (bytes[9] & 0xFF) | (bytes[10] & 0xFF) << 8;
        ByteBuffer tail = ByteBuffer.wrap(bytes, 11 + length, bytes.length - 11 - length).order(ByteOrder.LITTLE_ENDIAN);
        return List.of(tail.getInt(), tail.getInt(), tail.getInt());
    }

    private Persona persona(String id, String name) {
        Persona persona = mock(Persona.class);
        when(persona.id()).thenReturn(id);
        when(persona.name()).thenReturn(name);
        when(content.persona(id)).thenReturn(persona);
        return persona;
    }

    private static RosterEntry row(String personaId, RosterState state, long haulMeso) {
        return new RosterEntry(ANN, personaId, state, Assignment.TRAIN, 0, 0, ANT_TUNNEL, 0L, haulMeso, 0L, 0L, 0L, 0L, 0,
                null);
    }
}
