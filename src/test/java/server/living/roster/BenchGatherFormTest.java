package server.living.roster;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.agents.field.AgentFieldIntent;
import server.agents.runtime.AgentInteractionRuntime;
import server.agents.runtime.field.AgentFieldAdmissionMode;
import server.agents.runtime.field.AgentFieldSessionResult;
import server.agents.runtime.field.AgentFieldVisitLeaseRequest;
import server.agents.runtime.field.AgentFieldVisitLeaseRuntime;
import server.knowledge.Decisions;
import server.knowledge.FarmSites;
import server.knowledge.WorldKnowledge;
import server.life.SpawnPoint;
import server.living.config.Content.Quest;
import server.living.config.Content.Step;
import server.living.config.Content.StepType;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.QuestState;
import server.living.store.LivingStore.QuestStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static server.living.roster.BenchHarness.ANN;
import static server.living.roster.BenchHarness.FIELD;
import static server.living.roster.BenchHarness.TOWN;

/**
 * The GATHER form (docs/BOT_MANAGEMENT_PLAN.md P5 "Living bench", decision 12 a): a visible drop-pickup field lease at
 * the camp, on a BENCH_LIVE seat, aimed at the camp's mobs that drop what its owner's quests need; what it picks up is
 * harvested into the haul, and it ends with what the quests need, back in town, at its owner's level or the daily cap.
 */
class BenchGatherFormTest {
    private static final int SHELL = 4000019;
    private static final int OTHER_LOOT = 4000020;
    private static final int SNAIL = 100100;
    private static final int ELSEWHERE = 100200;
    /** Fields off its camp where shells drop: the nearer, a better one farther away, a third. */
    private static final int NEAR = BenchHarness.OTHER_FIELD;
    private static final int BEST = 104020000;
    private static final int THIRD = 104030000;

    private final BenchHarness game = new BenchHarness();
    private final List<AgentFieldVisitLeaseRequest> visits = new ArrayList<>();
    private final SpawnPoint snail = mock(SpawnPoint.class);
    private boolean visiting;

    @BeforeEach
    void setUp() {
        game.duty.register(Assignment.GATHER, new BenchGatherForm(game.world));
        game.statics(Decisions.class); // no server knowledge unless a test loads it (knows): its camp, as before
        game.statics(AgentInteractionRuntime.class).when(() -> AgentInteractionRuntime.registerDirectorIdleAgent(any()))
                .thenAnswer(inv -> {
                    Character agent = inv.getArgument(0);
                    game.calls.add("director-idle " + agent.getName());
                    return game.entry(agent.getId());
                });
        game.statics(AgentFieldVisitLeaseRuntime.class)
                .when(() -> AgentFieldVisitLeaseRuntime.start(any(), any(), any(), anyLong())).thenAnswer(inv -> {
                    visits.add(inv.getArgument(2));
                    visiting = true;
                    return new AgentFieldSessionResult(AgentFieldSessionResult.Status.STARTED, null, "");
                });
        game.statics(AgentFieldVisitLeaseRuntime.class).when(() -> AgentFieldVisitLeaseRuntime.active(any()))
                .thenAnswer(inv -> visiting);
        when(snail.getMonsterId()).thenReturn(SNAIL);
        when(game.map(FIELD).getMonsterSpawn()).thenReturn(List.of(snail));
        when(game.store.store.mobsDropping(SHELL)).thenReturn(List.of(new int[] {SNAIL, 400_000}, new int[] {ELSEWHERE, 600_000}));
    }

    @AfterEach
    void close() {
        game.close();
    }

    /** Ann's active quest (another persona's) needs {@code count} shells. */
    private void annNeedsShells(int count, String questPersona) {
        Step step = mock(Step.class);
        when(step.type()).thenReturn(StepType.COLLECT_ITEM);
        when(step.itemId()).thenReturn(SHELL);
        when(step.count()).thenReturn(count);
        Quest quest = mock(Quest.class);
        when(quest.persona()).thenReturn(questPersona);
        when(quest.step(0)).thenReturn(step);
        when(game.content.quest("shells")).thenReturn(quest);
        when(game.store.store.questStates(ANN, QuestStatus.ACTIVE)).thenReturn(List.of(
                new QuestState(ANN, "shells", 0, 0, QuestStatus.ACTIVE, 0L, 0L, 0)));
    }

    /**
     * Server knowledge is loaded, and FarmSites rates the fields it can reach for shells from its camp as {@code sites}
     * (map id to what a round of kills there brings); {@code anyMonster}: every monster there drops them.
     */
    private WorldKnowledge knows(Map<Integer, Double> sites, boolean anyMonster) {
        WorldKnowledge k = mock(WorldKnowledge.class);
        game.statics(Decisions.class).when(Decisions::active).thenReturn(k);
        game.statics(FarmSites.class).when(() -> FarmSites.gatherSites(eq(k), eq(SHELL), anyInt(), eq(FIELD), eq(true)))
                .thenReturn(sites);
        game.statics(FarmSites.class).when(() -> FarmSites.anyMonsterDrops(k, SHELL, FIELD)).thenReturn(anyMonster);
        for (int mapId : sites.keySet()) {
            when(game.map(mapId).getMonsterSpawn()).thenReturn(List.of(snail));
        }
        return k;
    }

    @Test
    void withNoDropperAtItsCampItGathersAtTheBestFieldForItNotTheNearest() {
        annNeedsShells(10, "wren");
        game.companion(ANN, "dara", "Dara", 904, Assignment.GATHER);
        WorldKnowledge k = knows(Map.of(NEAR, 0.4d, BEST, 1.2d), false); // its camp is not among them: no dropper there
        when(k.distance(FIELD, NEAR)).thenReturn(2);
        when(k.distance(FIELD, BEST)).thenReturn(9);

        game.duty.tick();

        assertTrue(game.calls.contains("load Dara at " + BEST + " ch1"), "three times the shells a round: " + game.calls);
        assertEquals(BEST, visits.get(0).entryRequest().visit().mapId());
        assertEquals(Set.of(SNAIL), visits.get(0).entryRequest().visit().intent().requiredMobIds(), "the droppers there");
        assertEquals(List.of(ANN + " dara bench.live gather live at map " + BEST), game.store.events("bench.live"));

        game.duty.tick();
        assertTrue(game.duty.live("dara"), "where it works: it gathers on");
    }

    @Test
    void aDropperAtItsCampKeepsItThereAndATieGoesToTheNearerField() {
        annNeedsShells(10, "wren");
        game.companion(ANN, "dara", "Dara", 904, Assignment.GATHER);
        knows(Map.of(FIELD, 0.1d, BEST, 1.2d), false);

        game.duty.tick();
        assertTrue(game.calls.contains("load Dara at " + FIELD + " ch1"), "its owner's camp has shells: " + game.calls);
        game.duty.endFor("dara", "called by owner");
        game.calls.clear();

        WorldKnowledge k = knows(Map.of(NEAR, 1.2d, BEST, 1.2d), false);
        when(k.distance(FIELD, NEAR)).thenReturn(2);
        when(k.distance(FIELD, BEST)).thenReturn(9);
        game.now += BenchDutyDispatcher.TICK_MS;
        game.duty.tick();
        assertTrue(game.calls.contains("load Dara at " + NEAR + " ch1"), game.calls.toString());
    }

    @Test
    void aFieldItsKindOrItKeepsDyingAtIsPassedOver() {
        annNeedsShells(10, "wren");
        game.companion(ANN, "dara", "Dara", 904, Assignment.GATHER);
        knows(Map.of(BEST, 1.2d, NEAR, 0.8d, THIRD, 0.3d), false);
        when(game.roster.roughCampFor(904, BEST)).thenReturn(true); // its kind dies there (the yields measured it)
        String[] there = {"bench.live", "gather live at map " + NEAR};
        String[] died = {"bench.live-end", BenchDutyDispatcher.ROUGH_HUNT};
        game.duty.campHistory = (owner, persona) -> List.of(there, died, there, died); // two of its last slices there

        game.duty.tick();

        assertTrue(game.calls.contains("load Dara at " + THIRD + " ch1"), game.calls.toString());
    }

    @Test
    void withNowhereToGatherItStaysInTheLedgerUnlessAnyMonsterAtItsCampDropsIt() {
        annNeedsShells(10, "wren");
        game.companion(ANN, "dara", "Dara", 904, Assignment.GATHER);
        knows(Map.of(), false);

        game.duty.tick();
        assertFalse(game.duty.live("dara"), "nothing it could farm drops shells anywhere it can go");
        assertEquals(List.of(), game.calls);

        knows(Map.of(), true);
        game.now += BenchDutyDispatcher.TICK_MS;
        game.duty.tick();
        assertTrue(game.calls.contains("load Dara at " + FIELD + " ch1"), "a drop of every monster: its camp will do");
    }

    @Test
    void withTheSwitchOffItGathersAtItsCamp() {
        game.config.BENCH_GATHER_AT_DROPPERS = false;
        annNeedsShells(10, "wren");
        game.companion(ANN, "dara", "Dara", 904, Assignment.GATHER);
        knows(Map.of(BEST, 1.2d), false);

        game.duty.tick();

        assertTrue(game.calls.contains("load Dara at " + FIELD + " ch1"), game.calls.toString());
    }

    @Test
    void itGathersAtItsCampOnAFieldSeatWithAVisitAimedAtTheDroppersThere() {
        annNeedsShells(10, "wren");
        game.companion(ANN, "dara", "Dara", 904, Assignment.GATHER);

        game.duty.tick();

        assertTrue(game.calls.containsAll(List.of("load Dara at " + FIELD + " ch1", "director-idle Dara")), game.calls.toString());
        assertFalse(game.calls.contains("grind Dara"), "a field visit, not the self-directed grind");
        assertEquals(1, visits.size());
        AgentFieldVisitLeaseRequest visit = visits.get(0);
        assertEquals(FIELD, visit.entryRequest().visit().mapId());
        assertEquals(AgentFieldAdmissionMode.CREATE_OR_JOIN, visit.admissionMode());
        AgentFieldIntent intent = visit.entryRequest().visit().intent();
        assertEquals(AgentFieldIntent.Type.FREE_GRIND, intent.type());
        assertEquals(Set.of(SNAIL), intent.requiredMobIds(), "only the dropper that lives at the camp: it fights only as needed");
        assertEquals(game.now + game.config.BENCH_LIVE_SLICE_MS, visit.exitAtMs(), "the lease carries the field slice's end");
        assertEquals(new CompanionLeases.Lease(CompanionLeases.Kind.BENCH_LIVE, ANN), game.leases.holder(904));
        assertEquals(1, game.duty.liveCountFor(ANN, CompanionBudget.Kind.BENCH_LIVE));

        game.duty.endFor("dara", "called by owner");

        assertFalse(game.duty.live("dara"));
        assertNull(game.leases.holder(904));
        assertTrue(game.calls.indexOf("hand back Dara") < game.calls.indexOf("unload Dara"), game.calls.toString());
        assertEquals(0, game.duty.liveCount(CompanionBudget.Kind.BENCH_LIVE));
    }

    @Test
    void itsLeaseStartsOnItsMailboxAndAGatherSliceMeasuresNoGrinderYield() {
        annNeedsShells(10, "wren");
        game.companion(ANN, "dara", "Dara", 904, Assignment.GATHER);
        game.duty.tick();
        game.statics(server.agents.runtime.AgentMailboxRuntime.class)
                .verify(() -> server.agents.runtime.AgentMailboxRuntime.dispatch(org.mockito.ArgumentMatchers.eq(game.entry(904)), any()));
        game.picksUpMeso(904, 300);

        game.now += game.config.BENCH_LIVE_SLICE_MS + 1L;
        game.duty.tick(); // its slice ends (and, still needed, the next one starts)

        assertEquals(List.of(ANN + " dara bench.live-end slice elapsed"), game.store.events("bench.live-end"));
        // it fought only what drops its items: a TRAIN or farming slice's rates at this camp are not its to set
        assertTrue(game.store.yields.isEmpty(), game.store.yields.toString());
    }

    @Test
    void whileWatchedItsVisitIsRenewedBeforeItRunsOut() {
        annNeedsShells(10, "wren");
        game.companion(ANN, "dara", "Dara", 904, Assignment.GATHER);
        game.duty.tick();
        when(game.map(FIELD).getAllPlayers()).thenReturn(List.of(mock(Character.class)));

        game.now += game.config.BENCH_LIVE_SLICE_MS - BenchDutyDispatcher.TICK_MS / 2; // it would end before the next look
        game.duty.tick();

        assertEquals(2, visits.size(), "renewed while it still runs");
        assertEquals(visits.get(0).entryRequest(), visits.get(1).entryRequest(), "the same field request: no break");
        assertEquals(game.now + game.config.BENCH_LIVE_SLICE_MS, visits.get(1).exitAtMs());
        assertTrue(game.duty.live("dara"));
    }

    @Test
    void whatItPicksUpGoesToTheHaulUntilTheQuestsHaveWhatTheyNeed() {
        annNeedsShells(10, "wren");
        game.companion(ANN, "dara", "Dara", 904, Assignment.GATHER);
        game.bags.get(904).put(2000000, 20); // its own potions: never picked up, never harvested
        game.duty.tick();

        game.picksUp(904, SHELL, 4);
        game.picksUp(904, OTHER_LOOT, 2);
        game.picksUpMeso(904, 300);
        game.duty.tick();

        assertEquals(4, game.haul(ANN, "dara").get(SHELL));
        assertEquals(2, game.haul(ANN, "dara").get(OTHER_LOOT), "kill loot is harvested as the field form does");
        assertEquals(300L, game.row(ANN, "dara").haulMeso());
        assertEquals(1_000, game.purses.get(904), "only the picked-up meso left its purse");
        assertEquals(20, game.bags.get(904).get(2000000));
        assertTrue(game.duty.live("dara"), "6 shells still to go");

        game.picksUp(904, SHELL, 6);
        game.duty.tick();

        assertEquals(10, game.haul(ANN, "dara").get(SHELL));
        assertFalse(game.duty.live("dara"));
        assertEquals(List.of(ANN + " dara bench.live-end gathered what your quests need"), game.store.events("bench.live-end"));

        game.duty.tick();
        assertFalse(game.duty.live("dara"), "nothing left to gather: the ledger keeps it");
    }

    @Test
    void withNothingToGatherForItStaysInTheLedger() {
        annNeedsShells(10, "dara"); // its own quest never counts from the bench
        game.companion(ANN, "dara", "Dara", 904, Assignment.GATHER);

        game.duty.tick();

        assertFalse(game.duty.live("dara"));
        assertEquals(List.of(), game.calls);
    }

    @Test
    void itEndsBackInTownAtItsOwnersLevelOrTheDailyCap() {
        annNeedsShells(10, "wren");
        game.companion(ANN, "dara", "Dara", 904, Assignment.GATHER);
        game.duty.tick();
        game.botMaps.put(904, game.map(TOWN));
        game.duty.tick(); // ends, and goes again from its camp
        assertTrue(game.duty.live("dara"));

        game.botLevels.put(904, 30);
        game.store.levels.put(904, 30); // saved at its level as it unloads
        game.duty.tick();
        assertFalse(game.duty.live("dara"));

        game.botLevels.put(904, 10);
        game.store.levels.put(904, 10);
        game.duty.tick();
        assertTrue(game.duty.live("dara"));
        when(game.ledger.withinDailyCap(any(), anyDouble())).thenReturn(false);
        game.duty.tick();

        assertEquals(List.of(ANN + " dara bench.live-end back in town",
                ANN + " dara bench.live-end would outlevel owner; back to the ledger",
                ANN + " dara bench.live-end daily farming cap reached"), game.store.events("bench.live-end"));
        assertFalse(game.duty.live("dara"));
    }

    @Test
    void aGathererTakesOneOfItsOwnersTwoFieldSeats() {
        annNeedsShells(10, "wren");
        game.companion(ANN, "brin", "Brin", 902, Assignment.TRAIN);
        game.companion(ANN, "dara", "Dara", 904, Assignment.GATHER);
        game.companion(ANN, "olwen", "Olwen", 901, Assignment.TRAIN);

        game.duty.tick();

        assertEquals(2, game.duty.liveCountFor(ANN, CompanionBudget.Kind.BENCH_LIVE));
        assertTrue(game.duty.live("brin") && game.duty.live("dara"), "by turn: brin, dara, then olwen");
        assertFalse(game.duty.live("olwen"));
    }

    @Test
    void aWatchedGathererGetsAnotherVisitWhenItsLeaseRunsOut() {
        annNeedsShells(10, "wren");
        game.companion(ANN, "dara", "Dara", 904, Assignment.GATHER);
        game.duty.tick();
        when(game.map(FIELD).getAllPlayers()).thenReturn(List.of(mock(Character.class))); // a player walks in

        game.now += game.config.BENCH_LIVE_SLICE_MS + 1;
        visiting = false; // the lease's exit came
        game.duty.tick();

        assertTrue(game.duty.live("dara"), "watched: it runs on");
        assertEquals(2, visits.size(), "a new visit at the same camp");
        assertEquals(game.now + game.config.BENCH_LIVE_SLICE_MS, visits.get(1).exitAtMs());

        when(game.map(FIELD).getAllPlayers()).thenReturn(List.of());
        game.now += game.config.BENCH_LIVE_SLICE_MS + 1;
        visiting = false;
        game.duty.tick();

        assertEquals(List.of(ANN + " dara bench.live-end slice elapsed"), game.store.events("bench.live-end"));
        assertEquals(2, game.calls.stream().filter(call -> call.startsWith("load Dara")).count(), "alone in its pool, it goes again");
        assertEquals(3, visits.size(), "unwatched and elapsed: no renewal; one visit per slice and one while watched");
    }
}
