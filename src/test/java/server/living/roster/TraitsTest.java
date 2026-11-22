package server.living.roster;

import org.junit.jupiter.api.Test;
import server.living.roster.Traits.History;
import server.living.roster.Traits.Kind;
import server.living.roster.Traits.Run;
import server.living.roster.Traits.Trait;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Traits.of (docs/WORLD_SYSTEMS_PLAN.md N9.1): each trait's gain bar, the lower bar it is kept at, and what takes it
 * away; at most three, the held ones first; the living_bond flag each is kept as.
 */
class TraitsTest {
    private static final long NOW = 400L * Traits.DAY_MS;
    private static final long TRADER_BAR = 3_000_000L;
    private static final Trait WARY_ZAKUM = new Trait(Kind.WARY, "zakum", Traits.DIED);
    private static final Trait VETERAN_KPQ = new Trait(Kind.VETERAN, "kpq", null);

    /** Runs, newest first, built oldest first: {@link #add} puts each one ahead of the ones before it. */
    private final List<Run> runs = new ArrayList<>();

    private void add(String activity, boolean cleared, int deaths, int closeCalls, long daysAgo) {
        runs.addFirst(new Run(activity, cleared, deaths, closeCalls, NOW - daysAgo * Traits.DAY_MS));
    }

    private void clean(String activity, int count, long daysAgo) {
        for (int i = 0; i < count; i++) {
            add(activity, true, 0, 0, daysAgo);
        }
    }

    private List<Trait> of(List<Trait> held) {
        return of(held, 0L, 0L);
    }

    private List<Trait> of(List<Trait> held, long benchedSinceMs, long soldMeso) {
        return Traits.of(new History(List.copyOf(runs), benchedSinceMs, soldMeso, TRADER_BAR, NOW), held);
    }

    // ---- WARY ---------------------------------------------------------------------------------

    @Test
    void aDeathAtABossMakesItWaryOfThatBossAlone() {
        clean("kpq", 2, 3);
        add("zakum", false, 1, 0, 1);

        assertEquals(List.of(WARY_ZAKUM), of(List.of()));
    }

    @Test
    void twoCloseCallsMakeItWaryButOneDoesNot() {
        add("horntail", true, 0, 1, 2);
        assertEquals(List.of(), of(List.of()));

        add("horntail", true, 0, 1, 1);
        assertEquals(List.of(new Trait(Kind.WARY, "horntail", Traits.CLOSE)), of(List.of()));
    }

    @Test
    void waryIsKeptThroughTwoCleanRunsThereAndGoesAfterTheThird() {
        add("zakum", false, 1, 0, 5);
        clean("zakum", 2, 4);
        assertEquals(List.of(WARY_ZAKUM), of(List.of(WARY_ZAKUM)));

        clean("zakum", 1, 3);
        assertEquals(List.of(), of(List.of(WARY_ZAKUM)));
    }

    @Test
    void cleanRunsElsewhereDoNotCureWariness() {
        add("zakum", false, 1, 0, 5);
        clean("kpq", 6, 4);

        assertEquals(List.of(WARY_ZAKUM), of(List.of(WARY_ZAKUM)));
    }

    @Test
    void aDeathPastTheLastFiveRunsThereGainsNothing() {
        add("zakum", false, 1, 0, 50);
        clean("zakum", 4, 40);
        add("zakum", true, 0, 1, 1); // one close call: not clean, but not two

        assertEquals(List.of(), of(List.of()));
    }

    // ---- VETERAN ------------------------------------------------------------------------------

    @Test
    void tenClearsMakeAVeteranNineDoNot() {
        clean("kpq", 9, 5);
        assertEquals(List.of(), of(List.of()));

        clean("kpq", 1, 1);
        assertEquals(List.of(VETERAN_KPQ), of(List.of()));
    }

    @Test
    void tenOldClearsWithNoneThisMonthGainNothing() {
        clean("kpq", 10, Traits.VETERAN_FRESH_DAYS + 1);

        assertEquals(List.of(), of(List.of()));
    }

    @Test
    void aVeteranKeepsItWithEightClearsAndLosesItAfterThreeMonthsAway() {
        clean("kpq", 8, Traits.VETERAN_STALE_DAYS);
        assertEquals(List.of(VETERAN_KPQ), of(List.of(VETERAN_KPQ)));
        assertEquals(List.of(), of(List.of()));

        runs.clear();
        clean("kpq", 12, Traits.VETERAN_STALE_DAYS + 1);
        assertEquals(List.of(), of(List.of(VETERAN_KPQ)));
    }

    @Test
    void sevenClearsLoseIt() {
        clean("kpq", 7, 1);

        assertEquals(List.of(), of(List.of(VETERAN_KPQ)));
    }

    // ---- STEADY -------------------------------------------------------------------------------

    @Test
    void twentyRunsWithoutADeathMakeItSteadyNineteenDoNot() {
        add("hpq", false, 1, 0, 30); // the death before the streak
        for (int i = 0; i < 19; i++) {
            add(i % 2 == 0 ? "kpq" : "hpq", false, 0, 0, 20);
        }
        assertEquals(List.of(), of(List.of()));

        add("kpq", false, 0, 0, 1);
        assertEquals(List.of(Trait.of(Kind.STEADY)), of(List.of()));
    }

    @Test
    void steadySurvivesOneDeathAndGoesAtTheSecond() {
        for (int i = 0; i < 20; i++) {
            add("lpq", false, 0, 0, 20);
        }
        add("lpq", false, 1, 0, 2);
        assertTrue(of(List.of(Trait.of(Kind.STEADY))).contains(Trait.of(Kind.STEADY)));
        assertTrue(of(List.of()).stream().noneMatch(t -> t.kind() == Kind.STEADY));

        add("lpq", false, 1, 0, 1);
        assertTrue(of(List.of(Trait.of(Kind.STEADY))).stream().noneMatch(t -> t.kind() == Kind.STEADY));
    }

    // ---- SHAKEN -------------------------------------------------------------------------------

    @Test
    void threeDeathsInAWeekShakeItTwoDoNot() {
        add("opq", false, 1, 0, 6);
        add("opq", false, 1, 0, 3);
        assertEquals(List.of(), of(List.of()).stream().filter(t -> t.kind() == Kind.SHAKEN).toList());

        add("kpq", false, 1, 0, 1);
        assertEquals(Kind.SHAKEN, of(List.of()).getFirst().kind());
    }

    @Test
    void threeDeathsOverMoreThanAWeekShakeNothing() {
        add("opq", false, 1, 0, 10);
        add("opq", false, 1, 0, 3);
        add("kpq", false, 1, 0, 1);

        assertTrue(of(List.of()).stream().noneMatch(t -> t.kind() == Kind.SHAKEN));
    }

    @Test
    void shakenGoesAfterThreeCleanRunsWhereverTheyAre() {
        add("opq", false, 3, 0, 2);
        clean("kpq", 2, 1);
        assertTrue(of(List.of(Trait.of(Kind.SHAKEN))).contains(Trait.of(Kind.SHAKEN)));

        clean("hpq", 1, 0);
        assertTrue(of(List.of(Trait.of(Kind.SHAKEN))).stream().noneMatch(t -> t.kind() == Kind.SHAKEN));
    }

    @Test
    void shakenAndSteadyNeverComeTogether() {
        for (int i = 0; i < 19; i++) {
            add("lpq", false, 0, 0, 30);
        }
        add("lpq", false, 1, 0, 1); // both keep bars pass: one death in twenty, no clean streak since

        List<Trait> traits = of(List.of(Trait.of(Kind.SHAKEN), Trait.of(Kind.STEADY)));
        assertTrue(traits.contains(Trait.of(Kind.SHAKEN)));
        assertTrue(traits.stream().noneMatch(t -> t.kind() == Kind.STEADY));
    }

    @Test
    void threeOldDeathsBehindThreeCleanRunsShakeNothing() {
        add("opq", false, 3, 0, 3);
        clean("kpq", 3, 1);

        assertTrue(of(List.of()).stream().noneMatch(t -> t.kind() == Kind.SHAKEN));
    }

    // ---- HOMEBODY -----------------------------------------------------------------------------

    @Test
    void eighteenDaysOnTheBenchMakeAHomebodySeventeenDoNot() {
        assertEquals(List.of(), of(List.of(), NOW - 17 * Traits.DAY_MS, 0L));
        assertEquals(List.of(Trait.of(Kind.HOMEBODY)), of(List.of(), NOW - 18 * Traits.DAY_MS, 0L));
    }

    @Test
    void aHomebodyKeepsItUntilThreeRunsInAWeek() {
        clean("kpq", 2, 1);
        assertEquals(List.of(Trait.of(Kind.HOMEBODY)), of(List.of(Trait.of(Kind.HOMEBODY))));

        clean("kpq", 1, 0);
        assertEquals(List.of(), of(List.of(Trait.of(Kind.HOMEBODY))));
    }

    // ---- TRADER -------------------------------------------------------------------------------

    @Test
    void salesOverTheBarMakeATraderAndHalfTheBarKeepsIt() {
        assertEquals(List.of(), of(List.of(), 0L, TRADER_BAR - 1));
        assertEquals(List.of(Trait.of(Kind.TRADER)), of(List.of(), 0L, TRADER_BAR));

        assertEquals(List.of(Trait.of(Kind.TRADER)), of(List.of(Trait.of(Kind.TRADER)), 0L, TRADER_BAR / 2));
        assertEquals(List.of(), of(List.of(Trait.of(Kind.TRADER)), 0L, TRADER_BAR / 2 - 1));
    }

    // ---- the cap and the flags ----------------------------------------------------------------

    @Test
    void atMostThreeAndTheHeldOnesStayAheadOfNewOnes() {
        clean("kpq", 10, 2);
        add("zakum", false, 1, 0, 1);
        add("horntail", false, 1, 0, 1);
        List<Trait> held = List.of(Trait.of(Kind.TRADER));

        List<Trait> traits = of(held, NOW - 20 * Traits.DAY_MS, TRADER_BAR);

        assertEquals(3, traits.size());
        assertEquals(Trait.of(Kind.TRADER), traits.getFirst());
        assertEquals(List.of(new Trait(Kind.WARY, "horntail", Traits.DIED), WARY_ZAKUM), traits.subList(1, 3));
    }

    @Test
    void aKeptWaryKeepsTheCauseItCameFrom() {
        Trait close = new Trait(Kind.WARY, "zakum", Traits.CLOSE);
        add("zakum", false, 1, 0, 1);

        assertEquals(List.of(close), of(List.of(close)));
    }

    @Test
    void flagsRoundTrip() {
        for (Trait trait : List.of(WARY_ZAKUM, new Trait(Kind.WARY, "kpq", Traits.CLOSE), VETERAN_KPQ,
                Trait.of(Kind.STEADY), Trait.of(Kind.SHAKEN), Trait.of(Kind.HOMEBODY), Trait.of(Kind.TRADER))) {
            assertEquals(trait, Trait.parse(trait.flag()));
        }
        assertEquals("trait:wary:zakum:died", WARY_ZAKUM.flag());
        assertNull(Trait.parse("met.lakelis"));
        assertNull(Trait.parse("trait:veteran"));
        assertNull(Trait.parse("trait:steady:kpq"));
        assertNull(Trait.parse("trait:wary:zakum:bored"));
        assertEquals(List.of(VETERAN_KPQ), Traits.held(List.of("bond.x", VETERAN_KPQ.flag())));
    }
}
