package server.living.roster;

import config.LivingWorldConfig;
import org.junit.jupiter.api.Test;
import server.living.LivingWorld;
import server.living.config.Content.BondStage;
import server.living.config.Content.Persona;
import server.living.resident.ResidentRuntime;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * "Your call" (docs/BOT_MANAGEMENT_PLAN.md P5, "BenchYieldModel / BenchDutyChooser", decision 13 a): a bandit over duty x
 * camp scored on what BenchYieldModel measured, shrunk toward FieldCatalog priors. On synthetic camps it tries each,
 * settles on the best (its regret grows slower and slower), and still tries a camp nobody hunted yet; it trains while
 * the companion is behind, farms meso once it keeps pace, passes over a camp that keeps killing it, and says why.
 */
class BenchDutyChooserTest {
    private static final int ANN = 10;
    private static final int BOWMAN = 300;
    private static final int LEVEL = 20;
    private static final Map<Integer, String> PLACES = Map.of(101, "Ant Tunnel I", 102, "Pig Beach", 103, "The Hill East of Henesys",
            104, "Sleepywood Swamp", 105, "Another Place");

    private final LivingWorldConfig config = new LivingWorldConfig();
    private final FakeLivingStore store = new FakeLivingStore();
    private final LivingWorld world = mock(LivingWorld.class);
    private final Persona mira = mock(Persona.class);
    private final BenchYieldModel yields;
    private final BenchLedger ledger;
    private final BenchDutyChooser chooser;
    private final RosterEntry row = new RosterEntry(ANN, "mira", RosterState.BENCHED, Assignment.TRAIN, 0, 0, 101, 0L, 0L, 0L,
            1_000L, 1_000L, 1_000L, 0, null);

    BenchDutyChooserTest() {
        when(world.config()).thenReturn(config); // explore 0.3, one death an hour costs half an hour's score
        when(world.store()).thenReturn(store.store);
        when(world.routineDate()).thenReturn(LocalDate.of(2026, 9, 29));
        when(mira.id()).thenReturn("mira");
        when(mira.paceGap()).thenReturn(-1); // pace gap 3, max lag 8
        when(mira.maxLag()).thenReturn(-1);
        yields = new BenchYieldModel(world);
        ledger = new BenchLedger(world);
        when(world.benchYields()).thenReturn(yields);
        when(world.bench()).thenReturn(ledger);
        chooser = new BenchDutyChooser(world);
    }

    // ---- the bandit on synthetic camps -----------------------------------------------------------

    @Test
    void itTriesEveryCampThenSettlesOnTheBestAndItsRegretFlattens() {
        // true EXP an hour at four camps, as a share of the ledger's rate at Lv20; a Lv25 owner: Mira trains (5 behind)
        Map<Integer, Double> truth = new LinkedHashMap<>(Map.of(101, 0.6d, 102, 0.9d, 103, 1.4d, 104, 1.0d));
        List<BenchDutyChooser.Camp> camps = camps(truth.keySet());
        Random noise = new Random(7);
        Set<Integer> tried = new HashSet<>();
        double best = 1.4d;
        double firstHalf = 0.0d;
        double secondHalf = 0.0d;
        int bestLate = 0;
        int rounds = 300;
        for (int round = 0; round < rounds; round++) {
            BenchDutyChooser.Pick pick = chooser.choose(row, mira, BondStage.MET, LEVEL, BOWMAN, 25, camps);
            assertEquals(Assignment.TRAIN, pick.duty(), "behind its owner it trains: " + pick);
            int camp = pick.campMapId();
            tried.add(camp);
            hunt(camp, truth.get(camp), 0.02d, 0, noise);
            double regret = best - truth.get(camp);
            if (round < rounds / 2) {
                firstHalf += regret;
            } else {
                secondHalf += regret;
            }
            if (round >= rounds - 100 && camp == 103) {
                bestLate++;
            }
        }
        assertEquals(truth.keySet(), tried, "every camp was tried");
        assertTrue(bestLate >= 90, "the best camp in " + bestLate + " of the last 100 picks");
        assertTrue(secondHalf < firstHalf / 2.0d, "regret flattens: " + firstHalf + " then " + secondHalf);
        assertTrue(firstHalf + secondHalf < 0.1d * rounds, "total regret " + (firstHalf + secondHalf) + " over " + rounds);
    }

    @Test
    void aCampNobodyHuntedYetGetsItsTryThenTheBestIsBack() {
        Map<Integer, Double> truth = new LinkedHashMap<>(Map.of(101, 0.6d, 102, 1.3d));
        Random noise = new Random(11);
        for (int round = 0; round < 60; round++) {
            BenchDutyChooser.Pick pick = chooser.choose(row, mira, BondStage.MET, LEVEL, BOWMAN, 25, camps(truth.keySet()));
            hunt(pick.campMapId(), truth.get(pick.campMapId()), 0.02d, 0, noise);
        }
        List<BenchDutyChooser.Camp> withNew = camps(List.of(101, 102, 105));

        BenchDutyChooser.Pick explore = chooser.choose(row, mira, BondStage.MET, LEVEL, BOWMAN, 25, withNew);

        assertEquals(105, explore.campMapId(), explore.toString());
        assertEquals(BenchDutyChooser.Why.NEW_CAMP, explore.why());
        assertEquals("going to Another Place. never hunted there, worth a look.", BenchDutyChooser.line(explore, PLACES::get));
        hunt(105, 0.4d, 0.02d, 0, noise); // a poor camp
        hunt(105, 0.4d, 0.02d, 0, noise);
        assertEquals(102, chooser.choose(row, mira, BondStage.MET, LEVEL, BOWMAN, 25, withNew).campMapId(),
                "measured poor, it goes back to the best");
    }

    @Test
    void aCampItsDutysFormWouldNotTakeItLiveAtIsNeverTriedForItsOwnSake() {
        // Mira trusts Ann: the ledger trains her to one level under her owner, but the field form (BenchSessions, what
        // LivingWorld's dispatcher runs TRAIN with) takes a trainee live only 3+ levels behind; only a live hunt measures
        RosterEntry capped = new RosterEntry(ANN, "mira", RosterState.BENCHED, Assignment.TRAIN, 0, 0, 101, 0L, 0L, 0L,
                1_000L, 1_000L, 1_000L, config.BENCH_FARM_HOURS_PER_DAY * 3600 + 60, "2026-09-29"); // no farming today
        for (int slice = 0; slice < 20; slice++) {
            yields.record(key(101), sample(1.3d, 0.02d, 0));
        }
        List<BenchDutyChooser.Camp> camps = camps(List.of(101, 105));
        assertEquals(BenchDutyChooser.Why.NEW_CAMP, chooser.choose(capped, mira, BondStage.TRUSTED, LEVEL, BOWMAN, 22, camps).why(),
                "with no dispatcher (a test's world) every arm is tried as if live");
        ResidentRuntime residents = mock(ResidentRuntime.class);
        MapleMap field = mock(MapleMap.class); // every camp here is a field
        when(world.residents()).thenReturn(residents);
        when(residents.characterIdOf("mira")).thenReturn(950);
        when(residents.map(anyInt())).thenReturn(field);
        when(world.benchDuty()).thenReturn(new BenchDutyDispatcher(world, new BenchSessions(world)));
        store.levels.put(ANN, 22);
        store.levels.put(950, LEVEL);

        BenchDutyChooser.Pick kept = chooser.choose(capped, mira, BondStage.TRUSTED, LEVEL, BOWMAN, 22, camps);

        assertEquals(101, kept.campMapId(), "two behind, the field form keeps it in the ledger: no try at a camp for its sake");
        assertEquals(BenchDutyChooser.Why.GOOD_EXP, kept.why());
        assertEquals(0.0d, kept.bonus(), 1e-9);
        store.levels.put(ANN, 25); // five behind: the field form takes it live, so the camp nobody hunted gets its try
        BenchDutyChooser.Pick tried = chooser.choose(capped, mira, BondStage.TRUSTED, LEVEL, BOWMAN, 25, camps);
        assertEquals(105, tried.campMapId(), tried.toString());
        assertEquals(BenchDutyChooser.Why.NEW_CAMP, tried.why());
    }

    // ---- priors, duties and reasons ---------------------------------------------------------------

    @Test
    void withNothingMeasuredTheCampWhoseMonstersFitItsLevelWins() {
        List<BenchDutyChooser.Camp> camps = List.of(new BenchDutyChooser.Camp(101, 10), new BenchDutyChooser.Camp(102, 20),
                new BenchDutyChooser.Camp(103, 26));

        BenchDutyChooser.Pick pick = chooser.choose(row, mira, BondStage.MET, LEVEL, BOWMAN, 25, camps);

        assertEquals(new BenchDutyChooser.Pick(Assignment.TRAIN, 102, BenchDutyChooser.Why.GOOD_EXP, 0, 1.0d - 0.05d * 0.5d, 0.0d, 0.0d),
                pick, "Lv20 monsters for a Lv20: no fit lost, only the prior's rare death");
        assertEquals("going to Pig Beach. good exp for me right now.", BenchDutyChooser.line(pick, PLACES::get),
                "a place keeps its own name");
    }

    @Test
    void itCampsWhereItsOwnerTrainsBestUntilWhatItMeasuresThereSaysOtherwise() {
        // F2.4: the owner's best map at its level band joins the candidates with a 15% higher prior
        server.living.habits.OwnerHabitBook habits = mock(server.living.habits.OwnerHabitBook.class);
        when(world.ownerHabits()).thenReturn(habits);
        when(habits.trainingMaps(ANN, LEVEL)).thenReturn(List.of(105));
        List<BenchDutyChooser.Camp> camps = camps(List.of(101, 102));

        BenchDutyChooser.Pick pick = chooser.choose(row, mira, BondStage.MET, LEVEL, BOWMAN, 25, camps);
        assertEquals(105, pick.campMapId(), "where its owner trains, never hunted by it yet");
        assertEquals(1.15d - 0.05d * 0.5d, pick.score(), 1e-9, "the prior 15% higher; only its rare death off");

        for (int slice = 0; slice < 20; slice++) {
            yields.record(key(105), sample(0.6d, 0.02d, 0)); // it measured there: poor for it
            yields.record(key(101), sample(1.0d, 0.02d, 0));
            yields.record(key(102), sample(1.0d, 0.02d, 0));
        }
        assertTrue(chooser.choose(row, mira, BondStage.MET, LEVEL, BOWMAN, 25, camps).campMapId() != 105,
                "measured values still win");
    }

    @Test
    void keepingPaceWithItsOwnerItFarmsMeso() {
        BenchDutyChooser.Pick pick = chooser.choose(row, mira, BondStage.MET, LEVEL, BOWMAN, 22, camps(List.of(101, 102)));

        assertEquals(Assignment.FARM_MESO, pick.duty());
        assertEquals(BenchDutyChooser.Why.KEEPING_PACE, pick.why());
        assertEquals("farming meso at Ant Tunnel I. i'm keeping up with you, so coin it is.",
                BenchDutyChooser.line(pick, PLACES::get));
    }

    @Test
    void itPassesOverACampThatKeepsKillingIt() {
        for (int slice = 0; slice < 20; slice++) {
            yields.record(key(101), sample(1.6d, 0.02d, 2)); // rich, but two deaths a slice
            yields.record(key(102), sample(1.1d, 0.02d, 0));
        }

        BenchDutyChooser.Pick pick = chooser.choose(row, mira, BondStage.MET, LEVEL, BOWMAN, 25, camps(List.of(101, 102)));

        assertEquals(102, pick.campMapId());
        assertEquals(BenchDutyChooser.Why.SAFER, pick.why());
        assertEquals("going to Pig Beach. Ant Tunnel I was too rough on me.", BenchDutyChooser.line(pick, PLACES::get),
                "it hunted there: it was rough on it");
    }

    @Test
    void aCampOnlyThePriorCallsRoughLooksRoughItWasNeverThere() {
        // nothing measured: Ant Tunnel I's monsters are a level above Mira (more EXP, more deaths by the prior), Pig
        // Beach's three below
        List<BenchDutyChooser.Camp> camps = List.of(new BenchDutyChooser.Camp(101, LEVEL + 1), new BenchDutyChooser.Camp(102, LEVEL - 3));

        BenchDutyChooser.Pick pick = chooser.choose(row, mira, BondStage.MET, LEVEL, BOWMAN, 25, camps);

        assertEquals(102, pick.campMapId(), pick.toString());
        assertEquals(BenchDutyChooser.Why.SAFER, pick.why());
        assertEquals(0.0d, pick.roughSamples(), 1e-9);
        assertEquals("going to Pig Beach. Ant Tunnel I looks too rough for me.", BenchDutyChooser.line(pick, PLACES::get),
                "never hunted there: it never says the camp was rough on it");
    }

    @Test
    void aCampThatKeepsKillingItIsWorseThanRestingAndGetsNoTryForItsOwnSake() {
        for (int slice = 0; slice < 3; slice++) {
            yields.record(key(101), sample(1.0d, 0.02d, 2)); // an hour's EXP, but eight deaths an hour
        }

        BenchDutyChooser.Pick pick = chooser.choose(row, mira, BondStage.MET, LEVEL, BOWMAN, 25, camps(List.of(101)));

        // before, a score clamped to 0 plus its exploration bonus sent it back to die: resting is better than that
        assertEquals(Assignment.REST, pick.duty(), pick.toString());
        assertEquals(BenchDutyChooser.Why.NOTHING_TO_DO, pick.why());

        List<BenchDutyChooser.Camp> withNew = camps(List.of(101, 105));
        BenchDutyChooser.Pick explore = chooser.choose(row, mira, BondStage.MET, LEVEL, BOWMAN, 25, withNew);
        assertEquals(105, explore.campMapId(), "a camp nobody hunted yet still gets its try: " + explore);
    }

    @Test
    void withNothingToTrainForAndTheFarmingCapReachedItRests() {
        RosterEntry capped = new RosterEntry(ANN, "mira", RosterState.BENCHED, Assignment.FARM_MESO, 0, 0, 101, 0L, 0L, 0L,
                1_000L, 1_000L, 1_000L, config.BENCH_FARM_HOURS_PER_DAY * 3600 + 60, "2026-09-29"); // past the cap

        BenchDutyChooser.Pick pick = chooser.choose(capped, mira, BondStage.MET, LEVEL, BOWMAN, 22, camps(List.of(101)));

        assertEquals(Assignment.REST, pick.duty());
        assertEquals(0, pick.campMapId(), "it stays where it camps");
        assertEquals("i'll rest up for now. nothing out there's worth it today.", BenchDutyChooser.line(pick, PLACES::get));
    }

    @Test
    void withNoCampToChooseFromThereIsNoPick() {
        assertNull(chooser.choose(row, mira, BondStage.MET, LEVEL, BOWMAN, 25, List.of()));
        assertNull(chooser.choose(row, mira, BondStage.MET, LEVEL, BOWMAN, 25, List.of(new BenchDutyChooser.Camp(0, 20))));
    }

    @Test
    void thePriorFitsMonstersNearItsLevel() {
        assertEquals(1.0d, BenchDutyChooser.fit(20, 20), 1e-9);
        assertEquals(1.0d, BenchDutyChooser.fit(0, 20), 1e-9, "unknown monsters: no loss");
        assertEquals(0.75d, BenchDutyChooser.fit(15, 20), 1e-9);
        assertEquals(0.76d, BenchDutyChooser.fit(23, 20), 1e-9, "stronger ones cost more per level");
        assertEquals(0.3d, BenchDutyChooser.fit(1, 40), 1e-9);
        assertEquals(0.05d, BenchDutyChooser.deathPrior(18, 20), 1e-9);
        assertEquals(0.8d, BenchDutyChooser.deathPrior(23, 20), 1e-9);
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static List<BenchDutyChooser.Camp> camps(Iterable<Integer> mapIds) {
        List<BenchDutyChooser.Camp> out = new ArrayList<>();
        for (int mapId : mapIds) {
            out.add(new BenchDutyChooser.Camp(mapId, 0)); // the monsters' level unknown: the prior fits every camp alike
        }
        return out;
    }

    private static BenchYieldModel.Key key(int camp) {
        return BenchYieldModel.Key.of(BOWMAN, LEVEL, camp);
    }

    /** One full live slice (15 minutes) at {@code camp}: EXP at {@code exp} x the prior (with 20% noise), meso likewise. */
    private void hunt(int camp, double exp, double meso, int deaths, Random noise) {
        double jitter = 1.0d + 0.2d * noise.nextGaussian();
        yields.record(key(camp), new BenchYieldModel.Sample(0.25d, Math.max(0L, Math.round(ledger.expPrior(LEVEL) * 0.25d * exp * jitter)),
                Math.round(ledger.mesoPrior(LEVEL) * 0.25d * meso), 0L, deaths));
    }

    private BenchYieldModel.Sample sample(double exp, double meso, int deaths) {
        return new BenchYieldModel.Sample(0.25d, Math.round(ledger.expPrior(LEVEL) * 0.25d * exp),
                Math.round(ledger.mesoPrior(LEVEL) * 0.25d * meso), 0L, deaths);
    }
}
