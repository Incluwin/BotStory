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
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import server.living.LivingWorld;
import server.living.bond.BondService;
import server.living.config.Content.BondStage;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.population.Population;
import server.living.population.ShippedFields;
import server.living.resident.LivingTalk;
import server.living.resident.ResidentRuntime;
import server.living.roster.BenchDutyChooser;
import server.living.roster.BenchDutyDispatcher;
import server.living.roster.BenchLedger;
import server.living.roster.BenchStallForm;
import server.living.roster.Commitments;
import server.living.roster.CompanionBossRuns;
import server.living.roster.CompanionBudget;
import server.living.roster.CompanionDirectory;
import server.living.roster.CompanionExecutor;
import server.living.roster.CompanionManager;
import server.living.roster.CompanionPartyQuestService;
import server.living.roster.CompanionRunRecorder;
import server.living.roster.CompanionSlots;
import server.living.roster.CompanionViews;
import server.living.roster.JobErrands;
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
import server.agents.capabilities.supplies.AgentSupplyReadiness;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import testutil.NpcTalks;
import testutil.NpcTalks.Talk;
import testutil.Packets;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    /** The monsters' level of a field, as the population's catalog knows it (none: 0). */
    private final Map<Integer, Integer> mobLevels = new HashMap<>();

    private MockedStatic<LivingWorld> livingWorld;
    /** Where a live slice's companion stands (a test says so; none: not loaded). */
    private MockedStatic<AgentRuntimeRegistry> registry;
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
        when(owner.getMap()).thenReturn(perion); // Ann stands beside Hadrik unless a test moves her
        when(hadrikBot.getHp()).thenReturn(812);
        when(hadrikBot.getCurrentMaxHp()).thenReturn(900);
        when(residents.characterIdOf("hadrik")).thenReturn(900);
        when(residents.characterIdOf("mira")).thenReturn(901);
        when(residents.characterIdOf("tobi")).thenReturn(902);
        when(store.characterLevelAndJob(901)).thenReturn(new int[] {28, Job.HUNTER.getId()});
        when(store.characterLevelAndJob(902)).thenReturn(new int[] {30, Job.CLERIC.getId()});
        livingWorld = mockStatic(LivingWorld.class);
        livingWorld.when(LivingWorld::get).thenReturn(world);
        registry = mockStatic(AgentRuntimeRegistry.class);
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
            public int mobLevel(int mapId) {
                return mobLevels.getOrDefault(mapId, 0);
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

            @Override
            public List<String> allPotions(Character bot) {
                return List.of();
            }
        };
    }

    @AfterEach
    void close() {
        LivingTalk.forget(owner);
        registry.close();
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
                "Let someone take your place...", "What you do at camp...", "Camp somewhere else...", "Fighting style...",
                "Supplies & loot...", "The whole crew...", "More..."), card.labels(), "The whole crew is on the first page");
        pick("More...");
        assertEquals(List.of("Your haul...", "Your wages...", "Let me see your gear", "Let's trade", "We should part ways",
                "Back to the top..."), lastTalk().labels());
    }

    @Test
    void bothCardsShowTheCompanionsPartyQuestRecordsUnderItsHeader() {
        rows.addAll(List.of(hadrikRow, miraRow));
        CompanionPartyQuestService quests = mock(CompanionPartyQuestService.class);
        CompanionRunRecorder records = mock(CompanionRunRecorder.class);
        when(world.partyQuests()).thenReturn(quests);
        when(quests.records()).thenReturn(records);
        when(records.recordLines(900)).thenReturn(List.of("KPQ: cleared 4 of 5", "HPQ: cleared 3 of 3"));
        when(records.recordLines(901)).thenReturn(List.of("KPQ: cleared 1 of 3"));

        menus.card(owner, hadrikRow);

        assertTrue(lastTalk().text().startsWith("#eHadrik#n, Lv31 Fighter - HP 812/900 - 38 Red Potions\r\n"
                + "KPQ: cleared 4 of 5\r\nHPQ: cleared 3 of 3\r\n\r\n#L"), lastTalk().text());
        LivingTalk.forget(owner);

        menus.card(owner, miraRow);

        String bench = lastTalk().text();
        assertTrue(bench.contains("\r\nKPQ: cleared 1 of 3\r\n") && bench.indexOf("KPQ") < bench.indexOf("#L"), bench);
    }

    @Test
    void oneOnItsOwnRestockTripSaysItIsAtTheStoreAndOneGoneForItsJobWhom() {
        rows.addAll(List.of(hadrikRow, miraRow));
        registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(900)).thenReturn(mock(AgentRuntimeEntry.class));
        try (MockedStatic<AgentSupplyReadiness> readiness = mockStatic(AgentSupplyReadiness.class)) {
            readiness.when(() -> AgentSupplyReadiness.status(any(), anyLong())).thenReturn(
                    new AgentSupplyReadiness.Status(AgentSupplyReadiness.StatusKind.RESTOCKING, 30_000L, 0));

            menus.card(owner, hadrikRow);
        }

        assertTrue(lastTalk().text().startsWith("#eHadrik#n, Lv31 Fighter - HP 812/900 - 38 Red Potions\r\n"
                + "at the store\r\n\r\n#L"), lastTalk().text());
        LivingTalk.forget(owner);

        JobErrands errands = mock(JobErrands.class);
        when(errands.visiting("mira")).thenReturn("Athena Pierce in Henesys");
        when(world.jobErrands()).thenReturn(errands);
        menus.card(owner, miraRow);

        assertTrue(lastTalk().text().contains("gone to see Athena Pierce in Henesys\r\n"), lastTalk().text());
        assertFalse(lastTalk().text().contains("train @"), lastTalk().text());
    }

    @Test
    void theGearAndTheTradeAreOfferedOnlyWithTheCompanionOnYourMap() {
        rows.addAll(List.of(hadrikRow, miraRow));
        when(owner.getMap()).thenReturn(mock(MapleMap.class)); // "Wait here", then Ann walked on

        menus.card(owner, hadrikRow);

        assertFalse(lastTalk().labels().contains("Let me see your gear"), lastTalk().labels().toString());
        pick("More...");
        assertEquals(List.of("Your haul...", "Your wages...", "We should part ways", "Back to the top..."),
                lastTalk().labels());
    }

    @Test
    void onlyOneBenchedOrAwayAndNotOnItsWayIsOfferedToTakeYourPlace() {
        RosterEntry travelling = row("tobi", RosterState.TRAVELLING, 0L);
        RosterEntry held = row("mira", RosterState.HOLD, 0L);
        rows.addAll(List.of(hadrikRow, travelling, held));

        menus.card(owner, hadrikRow);
        assertFalse(lastTalk().labels().contains("Let someone take your place..."), "nobody can take the seat at once");

        LivingTalk.forget(owner);
        RosterEntry away = row("mira", RosterState.AWAY, 0L);
        rows.set(2, away);
        menus.card(owner, hadrikRow);
        pick("Let someone take your place...");
        assertEquals(List.of("Mira, Lv28 Hunter - away: train @ Ant Tunnel I", "Back"), lastTalk().labels());
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
    void anOrderItCannotTakeSaysWhyOnTopOfItsCard() {
        rows.add(hadrikRow);
        when(manager.followMe(owner, hadrik)).thenReturn(new RosterResult.Failed("Hadrik is not out with you right now."));
        menus.card(owner, hadrikRow);

        pick("Stick with me");

        assertTrue(lastTalk().text().startsWith("Hadrik is not out with you right now.\r\n#eHadrik#n"), lastTalk().text());
        assertEquals(HADRIK_PORTRAIT, lastTalk().npcId());
    }

    @Test
    void anActionAnswersOnTheCardReadAfreshNotInAnExtraNotesWindow() {
        rows.addAll(List.of(hadrikRow, miraRow));
        when(roster.bench(owner, hadrik)).thenAnswer(inv -> {
            rows.set(0, row("hadrik", RosterState.BENCHED, 0L));
            return new RosterResult.Ok("Hadrik heads back to camp to train.");
        });
        menus.card(owner, hadrikRow);

        pick("Take a break at camp");

        Talk answer = lastTalk();
        assertEquals(HADRIK_PORTRAIT, answer.npcId(), "the companion's own card, not the player's notes");
        assertTrue(answer.text().startsWith("Hadrik heads back to camp to train.\r\n#eHadrik#n"), answer.text());
        assertEquals("Come join me", answer.labels().get(0), "the bench card: it is at camp now");
        assertTrue(talks().stream().noneMatch(talk -> talk.speaker() == CompanionMenus.NOTES_SPEAKER),
                "no extra OK window in the player's notes");
    }

    @Test
    void oneOnItsWayShowsTheClockAndTakesNoOrdersYet() {
        RosterEntry travelling = row("tobi", RosterState.TRAVELLING, 0L);
        rows.addAll(List.of(hadrikRow, travelling));
        when(roster.travelMsLeft(ANN, "tobi")).thenReturn(40_000L);

        menus.card(owner, travelling);

        Talk card = lastTalk();
        assertTrue(card.text().startsWith("#eTobi#n, Lv30 Cleric\r\non the way (0:40)\r\n"), card.text());
        assertEquals(List.of("Take a break at camp", "What you do at camp...", "Camp somewhere else...", "Fighting style...",
                "Supplies & loot...", "The whole crew...", "Your haul...", "Your wages...", "We should part ways"),
                card.labels());
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

            assertEquals(List.of("Come join me", "What you do at camp...", "Camp somewhere else...", "Fighting style...",
                    "Supplies & loot...", "Your haul...", "Your wages...", "We should part ways"), lastTalk().labels(),
                    state.name());
            LivingTalk.forget(owner);
        }
    }

    /**
     * 2026-10-15 (P20 run 3): a bench ally at its boss (CompanionBossRuns.allyHere) standing on its owner's map offers
     * "Let's trade" on its bench card, so its owner can hand it potions when it says on arrival it is short; the trade
     * goes to the manager. Not while it stands on another map (in the fight while its owner waits outside).
     */
    @Test
    void aBenchAllyAtItsBossOnTheOwnersMapOffersATrade() {
        rows.add(miraRow);
        CompanionBossRuns bossRuns = mock(CompanionBossRuns.class);
        when(world.bossRuns()).thenReturn(bossRuns);
        Character ally = mock(Character.class);
        when(bossRuns.allyHere("mira")).thenReturn(ally);
        when(ally.getMap()).thenReturn(mock(MapleMap.class)); // the fight

        menus.card(owner, miraRow);
        assertFalse(lastTalk().labels().contains("Let's trade"), lastTalk().labels().toString());
        LivingTalk.forget(owner);

        MapleMap room = owner.getMap();
        when(ally.getMap()).thenReturn(room);
        menus.card(owner, miraRow);
        List<String> labels = lastTalk().labels();
        assertEquals(List.of("Your wages...", "Let's trade", "We should part ways"),
                labels.subList(labels.indexOf("Your wages..."), labels.size()), labels.toString());

        pick("Let's trade");

        verify(manager).openTrade(eq(owner), eq(mira), anyLong());
    }

    // ---- tied to a run ---------------------------------------------------------------------

    @Test
    void whileTiedToARunBenchSwapErrandsAndCampWaitForIt() {
        rows.addAll(List.of(hadrikRow, miraRow));
        when(commitments.isCommitted(900)).thenReturn(true);

        menus.card(owner, hadrikRow);

        Talk card = lastTalk();
        assertEquals(List.of("Stick with me", "Wait here", "Hunt here while I'm busy", "Fighting style...",
                "Supplies & loot...", "The whole crew...", "Your haul...", "Your wages...", "Let me see your gear",
                "Let's trade", "More..."), card.labels(), "settings change no seat: they never wait for the run");
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

        assertEquals(List.of("Come join me", "Fighting style...", "Supplies & loot...", "Your haul...", "Your wages...",
                "We should part ways"), lastTalk().labels());
        assertTrue(lastTalk().text().contains("Swap in for (after the run)"), lastTalk().text());
    }

    @Test
    void aRoughCampIsMarkedInThePickerAndPickedAnywayTheCompanionSaysSo() {
        // found live 2026-09-30: a camp that killed its kind looked like any other in the picker
        rows.add(hadrikRow);
        fields.put(ANT_TUNNEL, 27);
        fields.put(ANT_TUNNEL_2, 30);
        when(roster.roughCampFor(900, ANT_TUNNEL_2)).thenReturn(true);
        when(roster.campDeathsPerHour(900, ANT_TUNNEL_2)).thenReturn(19.6d);
        menus.card(owner, hadrikRow);
        pick("Camp somewhere else...");

        assertTrue(lastTalk().labels().contains("Ant Tunnel II (Lv30 monsters) - rough for me (~20 deaths/h)"),
                lastTalk().labels().toString());
        assertTrue(lastTalk().labels().stream().anyMatch(label -> label.startsWith("Ant Tunnel I (Lv27 monsters)")
                && !label.contains("rough")), lastTalk().labels().toString());
        pick("Ant Tunnel II (Lv30 monsters) - rough for me (~20 deaths/h)");

        verify(manager).setCamp(owner, hadrik, ANT_TUNNEL_2);
        assertTrue(lastTalk().text().startsWith("Hadrik: ok, i'll camp at Ant Tunnel II, but it's rough for me.\r\n"),
                lastTalk().text());
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
        assertTrue(lastTalk().text().startsWith("Hadrik: after the run.\r\n#eHadrik#n"), lastTalk().text());
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
    void anotherCompanionsArrivalLeavesThisCardFreshButAChangeToThisOneMakesItStale() {
        // seen live: Garrick arrived (a write to his row) while Ilse's duty picker was open, and "Train" was refused
        CompanionViews views = cachedViews();
        rows.addAll(List.of(hadrikRow, miraRow));
        menus.card(owner, hadrikRow);

        views.invalidate(ANN, "mira"); // Mira's row changed: Hadrik's card is still right
        pick("Take a break at camp");
        verify(roster).bench(owner, hadrik);

        menus.card(owner, hadrikRow);
        views.invalidate(ANN, "hadrik"); // his own row changed: the click re-renders instead of acting
        pick("Take a break at camp");
        verify(roster, times(1)).bench(any(), any());
        assertTrue(lastTalk().text().startsWith(CompanionMenus.THINGS_CHANGED + "\r\n#eHadrik#n"), lastTalk().text());
    }

    @Test
    void aStaleClickOnOneReleasedMeanwhileActsOnNothing() {
        CompanionViews views = cachedViews();
        rows.addAll(List.of(hadrikRow, miraRow));
        menus.card(owner, hadrikRow);
        pick("More...");

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
        pick("More...");

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
                "Gather for your quests", "Sell the haul", "Run a stall", "Rest (no upkeep)", "Your call", "Back"),
                picker.labels());
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
        assertTrue(lastTalk().text().startsWith("Hadrik: ok, off to train at Ant Tunnel I. i'll keep my seat.\r\n#eHadrik#n"),
                lastTalk().text());
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
    void aRestIsSaidWhereItIsTakenInTheCampsTownNotOutOnTheField() {
        // found live 2026-09-29: "Garrick: ok, i'll rest at The Burnt Land I." while he rested in Perion
        rows.add(miraRow);
        RosterEntry resting = new RosterEntry(ANN, "mira", RosterState.BENCHED, Assignment.REST, 0, 0, ANT_TUNNEL, 0L, 0L,
                0L, 0L, 0L, 0L, 0, null);
        when(roster.entry(ANN, "mira")).thenReturn(miraRow);
        when(roster.describeAssignment(resting)).thenReturn("rest");
        doAnswer(invocation -> {
            when(roster.entry(ANN, "mira")).thenReturn(resting);
            return new RosterResult.Ok("");
        }).when(manager).assign(owner, mira, Assignment.REST, 0, 0);
        BenchDutyDispatcher duty = dispatcher();
        BenchDutyDispatcher.Form rest = mock(BenchDutyDispatcher.Form.class);
        when(duty.form(Assignment.REST)).thenReturn(rest);
        when(rest.mapFor(eq(resting), eq(mira), anyInt())).thenReturn(HENESYS);
        menus.card(owner, miraRow);
        pick("What you do at camp...");

        pick("Rest (no upkeep)");

        assertTrue(lastTalk().text().startsWith("Mira: ok, i'll rest at Henesys.\r\n#eMira#n"), lastTalk().text());
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
        assertEquals(List.of("#i4000021# #t4000021# (~26 an hour) - you need 30", "#i4000022# #t4000022# - you need 12",
                "Back"), items.labels());
        pick("#i4000022# #t4000022# - you need 12");

        Talk box = lastTalk();
        assertEquals(NUMBER, box.msgType());
        assertEquals("how many #t4000022#? (0: as many as i can)", box.text());
        assertEquals(List.of(12, 0, 9_999), numberBounds(lastTalkPacket()), "the quest's need is where the box starts");
        answer(NUMBER, 1, 200);

        verify(manager).assign(owner, mira, Assignment.FARM_ITEM, SNAIL_SHELL, 200);
        assertTrue(lastTalk().text().startsWith("Mira: ok, i'll train at Ant Tunnel I.\r\n#eMira#n"), lastTalk().text());
    }

    @Test
    void itemRatesReadAsAPlayerWouldSayThem() {
        assertEquals("rare", CompanionCardScreens.itemRate(0.04d));
        assertEquals("~2.6 an hour", CompanionCardScreens.itemRate(2.6d));
        assertEquals("~40 an hour", CompanionCardScreens.itemRate(39.6d));
    }

    @Test
    void endChatOnTheCountBoxEndsTheChat() {
        rows.add(miraRow);
        drops.put(STOLEN_FENCE, 400_000);
        menus.card(owner, miraRow);
        pick("What you do at camp...");
        pick("Farm an item...");
        pick(lastTalk().labels().get(0));

        answer(NUMBER, 0, null);

        assertFalse(LivingTalk.active(client));
        verify(manager, never()).assign(any(), any(), any(), anyInt(), anyInt());
    }

    // ---- P5: learned rates, "Your call" ----------------------------------------------------------

    @Test
    void thePickerReadsTheRatesLearnedAtItsCampAndSaysHowManyHuntsTaughtThem() {
        rows.add(hadrikRow);
        when(bench.rates(any(), any(), anyInt(), anyInt(), anyInt(), any(), anyInt())).thenAnswer(inv ->
                switch ((Assignment) inv.getArgument(5)) {
                    case TRAIN -> new BenchLedger.Rates(1.43d, 0L, 610L, 7.2d); // upkeep capped by what the camp pays
                    case FARM_MESO -> new BenchLedger.Rates(0.0d, 4_070L, 610L, 7.2d);
                    case REST -> new BenchLedger.Rates(0.0d, 0L, 0L, 7.2d);
                    default -> new BenchLedger.Rates(0.0d, 0L, 610L, 7.2d);
                });
        menus.card(owner, hadrikRow);

        pick("What you do at camp...");

        Talk picker = lastTalk();
        assertTrue(picker.text().startsWith("what should i go do? i'll keep my seat.\r\nnow: train @ Ant Tunnel I\r\n"
                + "upkeep: 610 meso an hour, none while i rest\r\n(7 hunts measured at Ant Tunnel I)\r\n"), picker.text());
        assertEquals(List.of("Train (~1.4% EXP/h)", "Farm meso (~3.5k meso/h after upkeep)", "Farm an item...",
                "Gather for your quests", "Sell the haul", "Run a stall", "Rest (no upkeep)", "Your call", "Back"),
                picker.labels());
        verify(bench).rates(hadrik, null, 31, Job.FIGHTER.getId(), 0, Assignment.TRAIN, ANT_TUNNEL); // its class line, its camp
    }

    @Test
    void theCampPickerShowsEachFieldsHourForItsDuty() {
        rows.add(hadrikRow);
        fields.put(ANT_TUNNEL, 27);
        fields.put(ANT_TUNNEL_2, 30);
        when(owner.getMapId()).thenReturn(HENESYS);
        when(bench.rates(any(), any(), anyInt(), anyInt(), anyInt(), any(), anyInt())).thenAnswer(inv ->
                new BenchLedger.Rates((int) inv.getArgument(6) == ANT_TUNNEL ? 1.23d : 2.61d, 0L, 930L));
        menus.card(owner, hadrikRow);

        pick("Camp somewhere else...");

        assertEquals(List.of("Ant Tunnel I (Lv27 monsters, ~1.2% EXP/h) - my camp now", "Ant Tunnel II (Lv30 monsters, ~2.6% EXP/h)",
                "Right here (Henesys)", "Back"), lastTalk().labels());
        BenchLedger.Rates farm = new BenchLedger.Rates(0.0d, 4_100L, 900L);
        assertEquals(", ~3.2k meso/h after upkeep", CompanionCardScreens.campRate(Assignment.FARM_MESO, farm));
        assertEquals("Farm meso (~3.2k meso/h after upkeep)", CompanionCardScreens.dutyLabel(Assignment.FARM_MESO, farm),
                "one camp, one duty: the two pickers show one number");
        assertEquals("", CompanionCardScreens.campRate(Assignment.FARM_ITEM, new BenchLedger.Rates(0.0d, 4_100L, 900L)));
        assertEquals("", CompanionCardScreens.campRate(Assignment.TRAIN, null));
    }

    @Test
    void yourCallLetsTheCompanionPickItsCampAndDutyAndSayWhy() {
        rows.add(hadrikRow);
        BenchLedger ledger = new BenchLedger(world); // the ledger's priors: nothing was measured yet
        when(world.bench()).thenReturn(ledger);
        when(world.routineDate()).thenReturn(LocalDate.of(2026, 9, 29));
        when(owner.getLevel()).thenReturn(40); // Hadrik (Lv31) is behind: he trains
        mobLevels.put(ANT_TUNNEL, 20); // his camp's monsters are far below him
        fields.put(ANT_TUNNEL_2, 30);
        fields.put(HILL, 12);
        RosterEntry away = new RosterEntry(ANN, "hadrik", RosterState.AWAY, Assignment.TRAIN, 0, 0, ANT_TUNNEL_2, 0L, 0L, 0L,
                0L, 0L, 0L, 0, null);
        when(roster.entry(ANN, "hadrik")).thenReturn(hadrikRow, away);
        BenchDutyDispatcher duty = dispatcher();
        menus.card(owner, hadrikRow);
        pick("What you do at camp...");

        pick("Your call");

        InOrder order = inOrder(manager, duty);
        order.verify(manager).setCamp(owner, hadrik, ANT_TUNNEL_2);
        order.verify(manager).assign(owner, hadrik, Assignment.TRAIN, 0, 0);
        order.verify(duty).setYourCall(ANN, "hadrik", true); // a standing intent: it picks again before each slice
        assertTrue(lastTalk().text().startsWith("Hadrik: going to Ant Tunnel II. good exp for me right now. i'll keep my seat."
                + "\r\n#eHadrik#n"), lastTalk().text());
        verify(store).event(eq(ANN), eq("hadrik"), eq("bench.your_call"), any());
    }

    @Test
    void yourCallWithNoFieldForItsLevelAsksForACampAndChangesNothing() {
        RosterEntry inTown = new RosterEntry(ANN, "mira", RosterState.BENCHED, Assignment.REST, 0, 0, HENESYS, 0L, 0L,
                0L, 0L, 0L, 0L, 0, null);
        rows.add(inTown);
        when(roster.entry(ANN, "mira")).thenReturn(inTown);
        menus.card(owner, inTown);
        pick("What you do at camp...");

        pick("Your call");

        verify(manager, never()).assign(any(), any(), any(), anyInt(), anyInt());
        verify(manager, never()).setCamp(any(), any(), anyInt());
        assertTrue(lastTalk().text().startsWith("Mira: i don't know a good spot for my level. pick a camp for me?\r\n"),
                lastTalk().text());
    }

    @Test
    void aCampPastTheCastleGatesIsNeitherWhereItsFieldDutiesRunNorAYourCallCandidate() {
        // user decision 2026-10-10 (FieldCatalog.closed): the card reads the population's catalog as the bench does
        int skyscraper6 = 106021300;
        RosterEntry castle = new RosterEntry(ANN, "mira", RosterState.BENCHED, Assignment.TRAIN, 0, 0, skyscraper6, 0L, 0L,
                0L, 0L, 0L, 0L, 0, null);
        rows.add(castle);
        when(roster.entry(ANN, "mira")).thenReturn(castle);
        menus.cards().catalog = new CompanionCardScreens.GameCatalog(world);
        List<Integer> offered = new ArrayList<>();
        Population population = ShippedFields.population();
        try (MockedStatic<Population> populations = mockStatic(Population.class);
             MockedConstruction<BenchDutyChooser> choosers = mockConstruction(BenchDutyChooser.class, (chooser, context) ->
                     when(chooser.choose(any(), any(), any(), anyInt(), anyInt(), anyInt(), any())).thenAnswer(inv -> {
                         List<BenchDutyChooser.Camp> camps = inv.getArgument(6);
                         camps.forEach(camp -> offered.add(camp.mapId()));
                         return null;
                     }))) {
            populations.when(Population::get).thenReturn(population);
            menus.card(owner, castle);
            pick("What you do at camp...");

            ArgumentCaptor<Integer> at = ArgumentCaptor.forClass(Integer.class);
            verify(bench, atLeastOnce()).rates(eq(mira), any(), anyInt(), anyInt(), anyInt(), eq(Assignment.TRAIN), at.capture());
            assertTrue(at.getAllValues().stream().noneMatch(ShippedFields.PAST_THE_CASTLE_GATES::contains),
                    "its field duties' hour is read at " + at.getAllValues());

            pick("Your call");

            assertEquals(1, choosers.constructed().size());
            assertFalse(offered.isEmpty());
            assertTrue(offered.stream().noneMatch(ShippedFields.PAST_THE_CASTLE_GATES::contains), "offered " + offered);
        }
        verify(manager, never()).setCamp(any(), any(), eq(skyscraper6));
    }

    // ---- P5: the bench card's live state -----------------------------------------------------------

    @Test
    void aFarmerOnALiveSliceReadsLiveAndOneTheLedgerKeepsReadsOutOfSight() {
        BenchDutyDispatcher duty = dispatcher();
        RosterEntry farming = farmingFences();
        cachedViews();
        BenchDutyDispatcher.Slice slice = slice(CompanionBudget.Kind.BENCH_LIVE, Assignment.FARM_ITEM, ANT_TUNNEL);
        when(duty.slice("mira")).thenReturn(slice);

        menus.card(owner, farming);
        assertTrue(lastTalk().text().startsWith("#eMira#n, Lv28 Hunter\r\ntrain @ Ant Tunnel I\r\n143/200 item 4000021 (live)\r\n"),
                lastTalk().text());
        LivingTalk.forget(owner);

        when(duty.slice("mira")).thenReturn(null);
        menus.card(owner, farming);
        assertTrue(lastTalk().text().contains("\r\n143/200 item 4000021 (out of sight)\r\n"), lastTalk().text());
    }

    @Test
    void aTraineeWithNoCountShowsItOnItsDutyLine() {
        dispatcher();
        rows.add(miraRow);

        menus.card(owner, miraRow);

        assertTrue(lastTalk().text().startsWith("#eMira#n, Lv28 Hunter\r\ntrain @ Ant Tunnel I (out of sight)\r\n"),
                lastTalk().text());
    }

    @Test
    void aStallAndATownRestSayWhereTheyAre() {
        BenchDutyDispatcher duty = dispatcher();
        rows.add(miraRow);
        BenchDutyDispatcher.Slice stall = slice(CompanionBudget.Kind.COMMERCE, Assignment.STALL, 910000003);
        when(duty.slice("mira")).thenReturn(stall);

        menus.card(owner, miraRow);
        assertTrue(lastTalk().text().startsWith("#eMira#n, Lv28 Hunter\r\ntrain @ Ant Tunnel I\r\nstall open in FM room 3\r\n"),
                lastTalk().text());
        LivingTalk.forget(owner);

        BenchDutyDispatcher.Slice rest = slice(CompanionBudget.Kind.BENCH_TOWN, Assignment.REST, HENESYS);
        when(duty.slice("mira")).thenReturn(rest);
        menus.card(owner, miraRow);
        assertTrue(lastTalk().text().contains("\r\nresting in Henesys\r\n"), lastTalk().text());

        assertEquals("selling the haul in FM room 12", CompanionCardScreens.liveLine(CompanionBudget.Kind.COMMERCE,
                Assignment.SELL, 910000012, true, id -> "?"));
        assertEquals("stall open in the Free Market", CompanionCardScreens.liveLine(CompanionBudget.Kind.COMMERCE,
                Assignment.STALL, 910000000, true, id -> "?"));
        assertEquals("(live)", CompanionCardScreens.liveLine(CompanionBudget.Kind.BENCH_LIVE, Assignment.GATHER, ANT_TUNNEL,
                true, id -> "?"));
    }

    @Test
    void aGathererOffItsCampSaysWhereItGathersAndOneAtItsCampReadsLive() {
        BenchDutyDispatcher duty = dispatcher();
        rows.add(miraRow);
        BenchDutyDispatcher.Slice away = slice(CompanionBudget.Kind.BENCH_LIVE, Assignment.GATHER, ANT_TUNNEL_2);
        when(duty.slice("mira")).thenReturn(away); // gone to the best field for what its owner's quests need

        menus.card(owner, miraRow);
        assertTrue(lastTalk().text().startsWith("#eMira#n, Lv28 Hunter\r\ntrain @ Ant Tunnel I\r\ngathering at Ant Tunnel II\r\n"),
                lastTalk().text());
        LivingTalk.forget(owner);

        BenchDutyDispatcher.Slice atCamp = slice(CompanionBudget.Kind.BENCH_LIVE, Assignment.GATHER, ANT_TUNNEL);
        when(duty.slice("mira")).thenReturn(atCamp);
        menus.card(owner, miraRow);
        assertTrue(lastTalk().text().startsWith("#eMira#n, Lv28 Hunter\r\ntrain @ Ant Tunnel I (live)\r\n"), lastTalk().text());
    }

    @Test
    void aStallReadsOpenOnlyOnceItsFormHasTheShopOpen() {
        BenchDutyDispatcher duty = dispatcher();
        rows.add(miraRow);
        BenchStallForm stalls = mock(BenchStallForm.class); // the STALL and SELL form LivingWorld registers (P5 duties)
        when(duty.form(Assignment.STALL)).thenReturn(stalls);
        BenchDutyDispatcher.Slice stall = slice(CompanionBudget.Kind.COMMERCE, Assignment.STALL, 910000003);
        when(duty.slice("mira")).thenReturn(stall);

        menus.card(owner, miraRow); // still walking to its spot in the room
        assertTrue(lastTalk().text().contains("\r\nsetting up a stall in FM room 3\r\n"), lastTalk().text());
        LivingTalk.forget(owner);

        when(stalls.open("mira")).thenReturn(true);
        menus.card(owner, miraRow);
        assertTrue(lastTalk().text().contains("\r\nstall open in FM room 3\r\n"), lastTalk().text());
        assertEquals("setting up a stall in FM room 12", CompanionCardScreens.liveLine(CompanionBudget.Kind.COMMERCE,
                Assignment.SELL, 910000012, false, id -> "?"));
    }

    @Test
    void afterARoughHuntTheCardSaysSoOnALineOfItsOwnAndNoMarkerIsAdded() {
        BenchDutyDispatcher duty = dispatcher();
        when(duty.note("mira")).thenReturn(BenchDutyDispatcher.ROUGH_HUNT);
        rows.add(miraRow);

        menus.card(owner, miraRow);

        // its duty line stays whole ("@ camp" right after the duty), and the rest is its own line under it
        assertTrue(lastTalk().text().startsWith("#eMira#n, Lv28 Hunter\r\ntrain @ Ant Tunnel I\r\nresting after a rough hunt\r\n"),
                lastTalk().text());
        assertFalse(lastTalk().text().contains("(out of sight)"), lastTalk().text());
    }

    @Test
    void aSliceNotWhereItWorksReadsRestockingOrOutOfSightNeverLive() {
        BenchDutyDispatcher duty = dispatcher();
        rows.add(miraRow);
        BenchDutyDispatcher.Slice slice = slice(CompanionBudget.Kind.BENCH_LIVE, Assignment.TRAIN, ANT_TUNNEL);
        when(duty.slice("mira")).thenReturn(slice);
        standsAt(901, HENESYS); // it died and revived in town: its slice has not ended yet

        menus.card(owner, miraRow);
        assertTrue(lastTalk().text().startsWith("#eMira#n, Lv28 Hunter\r\ntrain @ Ant Tunnel I (out of sight)\r\n"),
                lastTalk().text());
        LivingTalk.forget(owner);

        when(slice.resupplying()).thenReturn(true); // out of supplies: its own shop visit in town
        menus.card(owner, miraRow);
        assertTrue(lastTalk().text().contains("\r\nrestocking in Henesys\r\n"), lastTalk().text());
        assertFalse(lastTalk().text().contains("(live)"), lastTalk().text());
        LivingTalk.forget(owner);

        RosterEntry resting = new RosterEntry(ANN, "mira", RosterState.BENCHED, Assignment.REST, 0, 0, ANT_TUNNEL, 0L, 0L, 0L,
                0L, 0L, 0L, 0, null);
        rows.set(rows.indexOf(miraRow), resting);
        when(roster.describeAssignment(resting)).thenReturn("rest");
        BenchDutyDispatcher.Slice inTown = slice(CompanionBudget.Kind.BENCH_TOWN, Assignment.REST, HENESYS);
        when(duty.slice("mira")).thenReturn(inTown);
        menus.card(owner, resting);
        assertTrue(lastTalk().text().contains("\r\nrest\r\nresting in Henesys\r\n"), "no camp on a duty done in town: "
                + lastTalk().text());
    }

    @Test
    void anActiveOrParkedCompanionShowsNoBenchState() {
        dispatcher();
        rows.addAll(List.of(hadrikRow, row("tobi", RosterState.PARKED, 0L)));

        menus.card(owner, hadrikRow);
        assertFalse(lastTalk().text().contains("(out of sight)"), lastTalk().text());
        LivingTalk.forget(owner);
        menus.card(owner, rows.get(1));
        assertFalse(lastTalk().text().contains("(out of sight)"), lastTalk().text());
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
        assertTrue(lastTalk().text().startsWith("Hadrik: ok, i'll camp at The Hill East of Henesys.\r\n#eHadrik#n"),
                lastTalk().text());
    }

    // ---- haul, gear, trade ------------------------------------------------------------------

    @Test
    void theHaulListsWhatItCarriesAndHandsItOver() {
        RosterEntry carrying = haulRow();
        rows.add(carrying);
        when(roster.handOver(owner, hadrik)).thenReturn("Hadrik hands you 3,120 meso and 2 stacks.");
        menus.card(owner, carrying);

        pick("Your haul...");

        Talk haul = lastTalk();
        assertEquals("my haul:\r\n3,120 meso\r\n#t4000021# x12\r\n#t1302000# x1\r\n\r\n", haul.text().substring(0,
                haul.text().indexOf("#L")));
        assertEquals(List.of("Hand it over", "Send it by Duey", "Bank the meso", "Wear your best from the haul", "Back"),
                haul.labels());
        pick("Hand it over");

        verify(roster).handOver(owner, hadrik);
        assertTrue(lastTalk().text().startsWith("Hadrik hands you 3,120 meso and 2 stacks.\r\n#eHadrik#n"), lastTalk().text());
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
        assertTrue(lastTalk().text().startsWith("Hadrik puts on Sword from the haul.\r\n#eHadrik#n"), lastTalk().text());
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
        pick("More...");
        int talks = talks().size();
        long clicked = System.currentTimeMillis();

        pick("Let's trade");

        ArgumentCaptor<Long> openBy = ArgumentCaptor.forClass(Long.class);
        verify(manager).openTrade(any(), any(), openBy.capture());
        assertTrue(openBy.getValue() >= clicked + CompanionMenus.LATE_OUTCOME_MS, "never later than the click allows");
        assertTrue(openBy.getValue() <= System.currentTimeMillis() + CompanionMenus.LATE_OUTCOME_MS);
        assertEquals(talks, talks().size(), "the trade window is the answer");
    }

    @Test
    void aTradeThatCannotOpenSaysWhy() {
        rows.add(hadrikRow);
        when(manager.openTrade(any(), any(), anyLong())).thenReturn(new RosterResult.Failed("Hadrik is too far away to trade."));
        menus.card(owner, hadrikRow);
        pick("More...");

        pick("Let's trade");

        assertTrue(lastTalk().text().startsWith("Hadrik is too far away to trade.\r\n#eHadrik#n"), lastTalk().text());
    }

    // ---- the whole crew -----------------------------------------------------------------------

    @Test
    void theWholeCrewIsTheCrewWindowInTheCompanionsVoiceWithBackToItsCard() {
        rows.add(hadrikRow);
        CompanionDirectory directory = mock(CompanionDirectory.class);
        when(world.directory()).thenReturn(directory);
        when(directory.resolveName(owner, "Hadrik")).thenReturn(new CompanionDirectory.Relation.OwnCompanion(hadrikRow));
        menus.card(owner, hadrikRow);

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
        when(bench.upkeepPerHour(anyInt(), anyInt(), anyInt())).thenReturn(930.0d); // Lv31, 30 a level: under the camp's cap
        when(owner.getMeso()).thenReturn(10_000);
        when(roster.payWages(owner, hadrik, 2_790L)).thenReturn("Hadrik is paid up for ~3 h.");
        menus.card(owner, hadrikRow);
        pick("More...");

        pick("Your wages...");

        Talk wages = lastTalk();
        assertTrue(wages.text().startsWith("i'm working off the haul.\r\nmy upkeep is 930 meso an hour;"), wages.text());
        assertEquals(List.of("Pay your wages...", "Back"), wages.labels());
        pick("Pay your wages...");
        assertEquals(NUMBER, lastTalk().msgType());
        assertEquals(List.of(2, 1, 10), numberBounds(lastTalkPacket()), "no more hours than Ann can pay for");
        answer(NUMBER, 1, 3);

        verify(roster).payWages(owner, hadrik, 2_790L);
        assertTrue(lastTalk().text().startsWith("Hadrik is paid up for ~3 h.\r\n#eHadrik#n"), lastTalk().text());
    }

    @Test
    void wagesSheCannotPayForOfferNoBox() {
        rows.add(hadrikRow);
        when(bench.upkeepPerHour(anyInt(), anyInt(), anyInt())).thenReturn(930.0d);
        when(owner.getMeso()).thenReturn(100);
        menus.card(owner, hadrikRow);
        pick("More...");

        pick("Your wages...");

        assertEquals(List.of("Back"), lastTalk().labels());
        assertTrue(lastTalk().text().contains("(you need 930 meso to pay an hour)"), lastTalk().text());
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
        when(manager.release(owner, hadrik)).thenAnswer(inv -> {
            rows.remove(carrying); // parted ways: no card to return to
            return new RosterResult.Ok("Hadrik hands you 3,120 meso and 2 stacks. Hadrik heads home to Lith Harbor.");
        });
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
        assertEquals("Hadrik hands you 3,120 meso and 2 stacks. Hadrik heads home to Lith Harbor.", lastTalk().text());
        assertEquals(CompanionMenus.NOTES_SPEAKER, lastTalk().speaker(), "the notes: there is no card to return to");
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

    /** Rates at the priors for Hadrik (Lv31) at his camp: 1.23% EXP an hour training, 5,030 meso an hour farming, 930 upkeep. */
    private void rates() {
        when(bench.rates(any(), any(), anyInt(), anyInt(), anyInt(), any(), anyInt())).thenAnswer(inv ->
                switch ((Assignment) inv.getArgument(5)) {
                    case TRAIN -> new BenchLedger.Rates(1.23d, 0L, 930L);
                    case FARM_MESO -> new BenchLedger.Rates(0.0d, 5_030L, 930L);
                    case REST -> new BenchLedger.Rates(0.0d, 0L, 0L);
                    default -> new BenchLedger.Rates(0.0d, 0L, 930L);
                });
    }

    /** The live bench, with no slice and no rest unless a test says so. */
    private BenchDutyDispatcher dispatcher() {
        BenchDutyDispatcher duty = mock(BenchDutyDispatcher.class);
        when(duty.note(any())).thenReturn("");
        when(world.benchDuty()).thenReturn(duty);
        return duty;
    }

    /** Mira's live slice of {@code pool} at {@code mapId}, Mira standing there. */
    private BenchDutyDispatcher.Slice slice(CompanionBudget.Kind pool, Assignment duty, int mapId) {
        BenchDutyDispatcher.Slice slice = mock(BenchDutyDispatcher.Slice.class);
        when(slice.pool()).thenReturn(pool);
        when(slice.duty()).thenReturn(duty);
        when(slice.mapId()).thenReturn(mapId);
        when(slice.characterId()).thenReturn(901);
        standsAt(901, mapId);
        return slice;
    }

    /** The loaded character {@code characterId} stands on {@code mapId}. */
    private void standsAt(int characterId, int mapId) {
        Character bot = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(mapId);
        when(bot.getMap()).thenReturn(map);
        AgentRuntimeEntry entry = new AgentRuntimeEntry(bot, null, null);
        registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(characterId)).thenReturn(entry);
    }

    /** Mira on the bench farming 200 Stolen Fences at Ant Tunnel I, 143 in her haul. */
    private RosterEntry farmingFences() {
        RosterEntry farming = new RosterEntry(ANN, "mira", RosterState.BENCHED, Assignment.FARM_ITEM, STOLEN_FENCE, 200, ANT_TUNNEL,
                0L, 0L, 0L, 0L, 0L, 0L, 0, null);
        rows.add(farming);
        when(store.haul(ANN, "mira")).thenReturn(List.of(new HaulItem(STOLEN_FENCE, 143)));
        return farming;
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

    @Test
    void aHiresCardShowsItsFriendshipWithItsOwner() {
        Persona pip = persona("hire:950", "Pip");
        BondService bonds = mock(BondService.class);
        when(world.bonds()).thenReturn(bonds);
        server.living.bond.Friendship friendship = new server.living.bond.Friendship(world); // reads the config first
        when(world.friendship()).thenReturn(friendship);
        when(bonds.peek(ANN, "hire:950")).thenReturn(new Bond(ANN, "hire:950", BondStage.TRUSTED, 75, new LinkedHashSet<>(),
                ContractState.NONE, 0L, 0L, 0, 0L, 0, 0L, 0L));
        when(bonds.peek(ANN, "hadrik")).thenReturn(new Bond(ANN, "hadrik", BondStage.TRUSTED, 75, new LinkedHashSet<>(),
                ContractState.ACTIVE, 0L, 0L, 0, 0L, 0, 0L, 0L));

        assertEquals("", menus.cards().friendship(owner, pip), "HIRE_FRIENDSHIP_ENABLED off: no friendship kept");
        world.config().HIRE_FRIENDSHIP_ENABLED = true; // docs/BOT_CONCEPT_PLAN.md C1.4

        assertEquals("Friendship Lv 4 Friend (35 points to Lv 5)\r\n", menus.cards().friendship(owner, pip));
        assertEquals("", menus.cards().friendship(owner, hadrik), "a Resident's own window shows its friendship");
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
