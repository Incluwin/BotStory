package server.living.roster;

import client.Job;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.pace.AgentPqMemberPower;
import server.agents.capabilities.partyquest.pace.AgentPqPaceCalibration;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static server.living.roster.LifecycleHarness.ANN;

/**
 * Lakelis's own choice weighed by the clear-time estimate (synthesis section 5), with the estimate injected: the
 * quickest lineup the owners' seats allow wins by its objective (the longest wait for one still on its way, plus the
 * median, plus the timer times the chance it runs out); lineups within a minute of it go by their fit without what
 * they hit for; each owner keeps the seats its turns give it; the leader's pick is never changed; more than 64
 * lineups, no estimate, or no lineup that can clear leave the choice as it was without an estimate.
 */
class LineupPlanPaceTest {
    private static final long TIMER_MS = 1_800_000L;
    private static final int BEN = 20;
    private static final int CAT = 30;

    /** The member sets the estimate was asked about, in order. */
    private final List<Set<Integer>> asked = new ArrayList<>();

    @Test
    void aGapBeyondTheTieWindowDecides() {
        List<LineupPlan.Candidate> party = annAndFour(0.0d);
        LineupPlan.Pace pace = pace(members -> members.contains(904) ? 900.0d : 1000.0d);

        LineupPlan plan = LineupPlan.build("kpq", ANN, 25, party, null, pace);

        assertEquals(List.of("Ann", "Mira", "Tobi", "Rook"), names(plan.lineup()),
                "Rook is four levels off Ann, and gets them through 100 s sooner");
        assertEquals(List.of("Pip"), names(plan.extras()), "a second magician adds no branch");
        assertEquals(900.0d, plan.clearTime().objectiveS(), 1e-9);
        assertEquals(900.0d, plan.clearTime().ownObjectiveS(), 1e-9, "her own choice");
        assertEquals(TIMER_MS, plan.clearTime().timerMs());
        assertEquals(4, asked.size(), "every lineup of three of the four");
        assertEquals(List.of("Ann", "Mira", "Tobi", "Pip"),
                names(LineupPlan.build("kpq", ANN, 25, party, null).lineup()), "without an estimate: level first");
    }

    @Test
    void withinTheTieWindowTheFitDecidesWithoutWhatTheyHitFor() {
        List<LineupPlan.Candidate> party = annAndFour(100.0d); // Rook hits hardest by far: today he goes in
        assertEquals(List.of("Ann", "Rook", "Mira", "Tobi"), names(LineupPlan.build("kpq", ANN, 25, party, null).lineup()));

        LineupPlan plan = LineupPlan.build("kpq", ANN, 25, party, null,
                pace(members -> members.contains(904) ? 960.0d : 1000.0d));

        assertEquals(List.of("Ann", "Mira", "Tobi", "Pip"), names(plan.lineup()),
                "40 s is inside the minute: the three at Ann's level fit best");
        assertEquals(1000.0d, plan.clearTime().objectiveS(), 1e-9);
    }

    @Test
    void eachOwnerKeepsTheSeatsItsTurnsGiveIt() {
        List<LineupPlan.Candidate> party = List.of(human(ANN, "Ann", Job.CLERIC), human(BEN, "Ben", Job.PAGE),
                ready(seat(901, "Ash", 25, Job.HUNTER, ANN)), ready(seat(902, "Bel", 25, Job.BANDIT, ANN)),
                ready(seat(903, "Cid", 25, Job.FP_WIZARD, ANN)), ready(seat(911, "Dov", 25, Job.FIGHTER, BEN)),
                ready(seat(912, "Eli", 22, Job.CRUSADER, BEN)));
        // two of Ann's would be quickest of all, but Ben's turn gives his companions a seat
        LineupPlan.Pace pace = pace(members -> members.containsAll(Set.of(901, 902)) ? 500.0d
                : members.containsAll(Set.of(903, 912)) ? 800.0d : 1000.0d);

        LineupPlan plan = LineupPlan.build("kpq", ANN, 25, party, null, pace);

        assertEquals(List.of("Ann", "Ben", "Cid", "Eli"), names(plan.lineup()));
        assertEquals(6, asked.size(), "3 of Ann's times 2 of Ben's");
        for (Set<Integer> members : asked) {
            assertEquals(1, members.stream().filter(id -> id >= 901 && id <= 903).count(), members.toString());
            assertEquals(1, members.stream().filter(id -> id >= 911).count(), members.toString());
        }
    }

    @Test
    void aReadyCompanionBeatsOneOnTheWayWhenTheWaitOutweighsTheGain() {
        LineupPlan.Pace pace = pace(members -> members.contains(905) ? 900.0d : 1000.0d);

        LineupPlan far = LineupPlan.build("kpq", ANN, 25, withRin(300_000L), null, pace);

        assertEquals(List.of("Ann", "Mira", "Tobi", "Pip"), names(far.lineup()), "5 min away to save 100 s");
        assertTrue(far.onTheWay().isEmpty());
        assertTrue(far.startable());

        LineupPlan near = LineupPlan.build("kpq", ANN, 25, withRin(30_000L), null, pace);

        assertTrue(names(near.lineup()).contains("Rin"), "30 s away to save 100 s");
        assertEquals(List.of("Rin"), names(near.onTheWay()));
        assertEquals(930.0d, near.clearTime().objectiveS(), 1e-9, "the wait counts in the objective");
        assertEquals(930.0d, near.clearTime().timeS(), 1e-9, "and in the time");
        assertFalse(near.startable());
    }

    @Test
    void theLeadersPickIsNeverChangedAndCarriesHerOwnChoiceToCompare() {
        List<LineupPlan.Candidate> party = annAndFour(0.0d);
        LineupPlan.Pace pace = pace(members -> members.contains(904) ? 1300.0d : 1000.0d);

        LineupPlan plan = LineupPlan.build("kpq", ANN, 25, party, Set.of(902, 903, 904), pace);

        assertEquals(List.of("Ann", "Tobi", "Pip", "Rook"), names(plan.lineup()), "her pick stays hers");
        assertEquals(1300.0d, plan.clearTime().objectiveS(), 1e-9);
        assertEquals(1000.0d, plan.clearTime().ownObjectiveS(), 1e-9, "hers: the three without Rook");
        assertEquals(Set.of(902, 903, 904), plan.picked());
    }

    @Test
    void moreLineupsThanItWeighsKeepTheChoiceWithoutAnEstimate() {
        // nine of Ann's companions for three seats: C(9,3) = 84 lineups
        List<LineupPlan.Candidate> nine = annAnd(9);
        LineupPlan.Pace pace = pace(members -> members.containsAll(Set.of(907, 908, 909)) ? 500.0d : 1000.0d);

        LineupPlan plan = LineupPlan.build("kpq", ANN, 25, nine, null, pace);

        assertEquals(names(LineupPlan.build("kpq", ANN, 25, nine, null).lineup()), names(plan.lineup()));
        assertEquals(List.of(Set.of(ANN, 901, 902, 903)), asked, "only the choice it keeps is estimated");
        assertEquals(1000.0d, plan.clearTime().objectiveS(), 1e-9, "and shown with its time");

        asked.clear();
        LineupPlan eight = LineupPlan.build("kpq", ANN, 25, annAnd(8), null,
                pace(members -> members.containsAll(Set.of(906, 907, 908)) ? 500.0d : 1000.0d));

        assertEquals(56, asked.size(), "C(8,3) = 56 is within the 64 it weighs");
        assertEquals(Set.of(ANN, 906, 907, 908), eight.memberIds());
    }

    @Test
    void noEstimateKeepsTodaysChoiceExactly() {
        List<List<LineupPlan.Candidate>> parties = List.of(annAndFour(100.0d), annAndFour(0.0d), withRin(40_000L),
                annAnd(9), List.of(human(ANN, "Ann", Job.CLERIC), human(BEN, "Ben", Job.PAGE),
                        ready(seat(901, "Mira", 25, Job.HUNTER, ANN)), ready(seat(902, "Nell", 25, Job.BANDIT, ANN)),
                        ready(seat(911, "Tobi", 22, Job.FP_WIZARD, BEN))),
                List.of(human(ANN, "Ann", Job.CLERIC), ready(seat(901, "Mira", 24, Job.HUNTER, ANN)),
                        ready(seat(902, "Pip", 26, Job.FP_WIZARD, ANN)),
                        new LineupPlan.Candidate(seat(903, "Casse", 25, Job.BANDIT, ANN), LineupPlan.Fit.WAITS,
                                "bags full", false)));
        LineupPlan.Pace none = new LineupPlan.Pace(members -> null, TIMER_MS, companions -> 0.0d);
        for (List<LineupPlan.Candidate> party : parties) {
            for (Set<Integer> picked : new ArrayList<Set<Integer>>(java.util.Arrays.asList(null, Set.of(901, 902)))) {
                LineupPlan today = LineupPlan.build("kpq", ANN, 25, party, picked);

                assertEquals(today, LineupPlan.build("kpq", ANN, 25, party, picked, none));
                assertEquals(today, LineupPlan.build("kpq", ANN, 25, withoutPower(party), picked,
                        pace(members -> 900.0d)), "a member without power: no estimate");
                assertNull(today.clearTime());
            }
        }
    }

    @Test
    void aLineupThatCannotClearIsNeverPreferredAndWhenNoneCanItIsTheChoiceWithoutAnEstimate() {
        List<LineupPlan.Candidate> party = annAndFour(0.0d);
        LineupPlan.Pace someCannot = new LineupPlan.Pace(members -> remember(members,
                members.stream().anyMatch(member -> member.characterId() == 901)
                        ? cannotClear() : estimate(1000.0d, 0.0d)), TIMER_MS, companions -> 0.0d);

        LineupPlan plan = LineupPlan.build("kpq", ANN, 25, party, null, someCannot);

        assertEquals(List.of("Ann", "Tobi", "Pip", "Rook"), names(plan.lineup()), "Mira's lineups cannot clear");

        LineupPlan.Pace noneCan = new LineupPlan.Pace(members -> cannotClear(), TIMER_MS, companions -> 0.0d);
        LineupPlan stuck = LineupPlan.build("kpq", ANN, 25, party, null, noneCan);

        assertEquals(names(LineupPlan.build("kpq", ANN, 25, party, null).lineup()), names(stuck.lineup()));
        assertFalse(stuck.estimate().clearable(), "shown with its estimate: she says they would not make it");
        assertEquals(Double.POSITIVE_INFINITY, stuck.clearTime().objectiveS());
    }

    @Test
    void theClockCountsInTheObjective() {
        List<LineupPlan.Candidate> party = annAndFour(0.0d);
        // with Rook: 1000 s at the median but a coin flip on the timer, 1000 + 1800 x 0.5; without: 1200 s, no risk
        LineupPlan.Pace pace = new LineupPlan.Pace(members -> remember(members,
                members.stream().anyMatch(member -> member.characterId() == 904)
                        ? estimate(1000.0d, 0.5d) : estimate(1200.0d, 0.0d)), TIMER_MS, companions -> 0.0d);

        LineupPlan plan = LineupPlan.build("kpq", ANN, 25, party, null, pace);

        assertFalse(plan.memberIds().contains(904));
        assertEquals(1200.0d, plan.clearTime().objectiveS(), 1e-9);
        assertEquals(1200.0d, plan.clearTime().timeS(), 1e-9);

        LineupPlan pick = LineupPlan.build("kpq", ANN, 25, party, Set.of(901, 902, 904), pace);

        assertEquals(1900.0d, pick.clearTime().objectiveS(), 1e-9, "the clock's risk weighs");
        assertEquals(1000.0d, pick.clearTime().timeS(), 1e-9, "but the time is the median");
        assertEquals(1200.0d, pick.clearTime().ownObjectiveS(), 1e-9);
        assertEquals(1200.0d, pick.clearTime().ownTimeS(), 1e-9);
    }

    @Test
    void theLastClearWithExactlyTheseCompanionsIsAskedForTheChosenOnes() {
        List<Set<Integer>> askedFor = new ArrayList<>();
        LineupPlan.Pace pace = new LineupPlan.Pace(members -> estimate(1000.0d, 0.0d), TIMER_MS, companions -> {
            askedFor.add(companions);
            return 960.0d;
        });

        LineupPlan plan = LineupPlan.build("kpq", ANN, 25, annAndFour(0.0d), null, pace);

        assertEquals(List.of(Set.of(901, 902, 903)), askedFor, "the companions only, once");
        assertEquals(960.0d, plan.clearTime().lastClearS(), 1e-9);
        assertNotNull(plan.estimate());
    }

    @Test
    void aLineupWithoutACompanionHasNoClearTime() {
        // three players and a companion that waits outside: the players go in on their own, and she says no time
        List<LineupPlan.Candidate> party = List.of(human(ANN, "Ann", Job.CLERIC), human(BEN, "Ben", Job.PAGE),
                human(CAT, "Cat", Job.HUNTER), new LineupPlan.Candidate(seat(901, "Mira", 25, Job.BANDIT, ANN),
                        LineupPlan.Fit.WAITS, "bags full", false, 0.0d, power(901, false)));

        LineupPlan plan = LineupPlan.build("kpq", ANN, 25, party, null, pace(members -> 1000.0d));

        assertEquals(List.of("Ann", "Ben", "Cat"), names(plan.lineup()));
        assertNull(plan.clearTime());
        assertEquals(List.of(), LakelisLines.clearTime(plan));
        assertEquals(List.of(), asked, "a lineup of players alone is never estimated");
        assertEquals(LineupPlan.build("kpq", ANN, 25, party, null), plan, "exactly as without an estimate");

        LineupPlan pickedNobody = LineupPlan.build("kpq", ANN, 25, party, Set.of(), pace(members -> 1000.0d));
        assertNull(pickedNobody.clearTime());
        assertEquals(List.of(), asked);
    }

    @Test
    void playersBesideTheLeaderCountInTheEstimateLikeCompanions() {
        // A real estimate of the short quest (PaceTestProfile): Ann leads and waits; Ben and Cat hit the Ligators
        // and King Slime for 500 a second; Mira, the one companion, never touches King Slime.
        AgentPqPaceCalibration prior = AgentPqPaceCalibration.prior(PaceTestProfile.warm());
        AgentPqPaceCalibration exact = new AgentPqPaceCalibration(prior.questKey(), prior.epoch(), prior.companion(),
                prior.leader(), 0.0d, 0, AgentPqPaceCalibration.Source.PRIOR, Map.of()); // no spread: the theory
        LineupPlan.Pace real = new LineupPlan.Pace(members -> remember(members,
                AgentPqPaceEstimator.estimate(PaceTestProfile.warm(), members, exact)), TIMER_MS, companions -> 0.0d);
        List<LineupPlan.Candidate> party = List.of(human(ANN, "Ann", Job.CLERIC),
                player(BEN, "Ben", Job.PAGE, 500.0d, 500.0d), player(CAT, "Cat", Job.HUNTER, 500.0d, 500.0d),
                new LineupPlan.Candidate(seat(901, "Mira", 25, Job.BANDIT, ANN), LineupPlan.Fit.READY, null, false,
                        0.0d, power(901, false, 500.0d, 0.0d)));

        LineupPlan plan = LineupPlan.build("kpq", ANN, 25, party, null, real);

        assertTrue(plan.estimate().clearable(), plan.estimate().toString());
        // a minute's opening, 100 Ligators at half a kill a second from each of the three, King Slime's 5000 HP at 1000
        assertEquals(60.0d + 100.0d / 1.5d + 5.0d, plan.estimate().meanS(), 1e-6);
        for (String line : LakelisLines.clearTime(plan)) {
            assertFalse(line.contains("King Slime") || line.contains("doors close") || line.contains("Mind the clock"),
                    line);
        }
    }

    @Test
    void insideTheTieWindowAndAtEqualFitAUsualCompanionGoesIn() {
        // Rook and Sol are both Lv25 fighters; only Sol has a place in Ann's usual lineup
        List<LineupPlan.Candidate> party = List.of(human(ANN, "Ann", Job.CLERIC),
                ready(seat(901, "Mira", 25, Job.HUNTER, ANN)), ready(seat(902, "Tobi", 25, Job.BANDIT, ANN)),
                ready(seat(903, "Rook", 25, Job.FIGHTER, ANN)),
                new LineupPlan.Candidate(seat(904, "Sol", 25, Job.FIGHTER, ANN), LineupPlan.Fit.READY, null, true,
                        0.0d, power(904, false)));

        LineupPlan plan = LineupPlan.build("kpq", ANN, 25, party, null,
                pace(members -> members.contains(903) ? 1000.0d : 1030.0d));

        assertEquals(Set.of(ANN, 901, 902, 904), plan.memberIds(), "30 s is inside the minute: the usual one goes");
        assertEquals(1030.0d, plan.clearTime().objectiveS(), 1e-9);
    }

    @Test
    void insideTheTieWindowAtEqualFitAndUsualPlacesThePersonasDecide() {
        // Zed comes first in the party, Abe's persona sorts first: Abe goes in
        List<LineupPlan.Candidate> party = List.of(human(ANN, "Ann", Job.CLERIC),
                ready(seat(901, "Mira", 25, Job.HUNTER, ANN)), ready(seat(902, "Tobi", 25, Job.BANDIT, ANN)),
                ready(seat(903, "Zed", 25, Job.FIGHTER, ANN)), ready(seat(904, "Abe", 25, Job.FIGHTER, ANN)));

        LineupPlan plan = LineupPlan.build("kpq", ANN, 25, party, null,
                pace(members -> members.contains(903) ? 1000.0d : 1030.0d));

        assertEquals(Set.of(ANN, 901, 902, 904), plan.memberIds());
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** The estimate of a member set: a 2 min range around {@code p50S(ids)}, never over the timer. */
    private LineupPlan.Pace pace(ToDoubleFunction<Set<Integer>> p50S) {
        Function<List<AgentPqMemberPower>, AgentPqPaceEstimate> estimator = members -> {
            Set<Integer> ids = ids(members);
            return remember(members, estimate(p50S.applyAsDouble(ids), 0.0d));
        };
        return new LineupPlan.Pace(estimator, TIMER_MS, companions -> 0.0d);
    }

    private AgentPqPaceEstimate remember(List<AgentPqMemberPower> members, AgentPqPaceEstimate estimate) {
        asked.add(ids(members));
        return estimate;
    }

    private static Set<Integer> ids(List<AgentPqMemberPower> members) {
        Set<Integer> ids = new HashSet<>();
        for (AgentPqMemberPower member : members) {
            ids.add(member.characterId());
        }
        return ids;
    }

    static AgentPqPaceEstimate estimate(double p50S, double overTimer) {
        return new AgentPqPaceEstimate(true, p50S - 60.0d, p50S, p50S + 60.0d, p50S, overTimer, Map.of(), Map.of(),
                List.of(), AgentPqPaceCalibration.Source.PRIOR, 0);
    }

    private static AgentPqPaceEstimate cannotClear() {
        return new AgentPqPaceEstimate(false, 3600.0d, 3600.0d, 3600.0d, 3600.0d, 1.0d, Map.of(), Map.of(),
                List.of(), AgentPqPaceCalibration.Source.PRIOR, 0);
    }

    /** Ann (Lv25 Cleric) and four of her companions: Mira, Pip and Tobi at her level, Rook four below. */
    private static List<LineupPlan.Candidate> annAndFour(double rookDps) {
        return List.of(human(ANN, "Ann", Job.CLERIC), ready(seat(901, "Mira", 25, Job.HUNTER, ANN), 1.0d),
                ready(seat(902, "Pip", 25, Job.FP_WIZARD, ANN), 1.0d), ready(seat(903, "Tobi", 25, Job.BANDIT, ANN), 1.0d),
                ready(seat(904, "Rook", 21, Job.FIGHTER, ANN), rookDps));
    }

    /** Ann and Mira, Pip and Tobi beside her, and Rin {@code etaMs} away. */
    private static List<LineupPlan.Candidate> withRin(long etaMs) {
        LineupPlan.Seat rin = new LineupPlan.Seat(905, "Rin", 25, Job.ASSASSIN.getId(), "", false, ANN, "rin", 104_000_000,
                false, etaMs, 0, 0);
        return List.of(human(ANN, "Ann", Job.CLERIC), ready(seat(901, "Mira", 25, Job.HUNTER, ANN)),
                ready(seat(902, "Pip", 25, Job.FP_WIZARD, ANN)), ready(seat(903, "Tobi", 24, Job.BANDIT, ANN)),
                new LineupPlan.Candidate(rin, LineupPlan.Fit.ON_THE_WAY, null, false, 0.0d, power(905, false)));
    }

    /** Ann and {@code count} of her companions, 901 at her level and each next one a level further off. */
    private static List<LineupPlan.Candidate> annAnd(int count) {
        List<LineupPlan.Candidate> party = new ArrayList<>();
        party.add(human(ANN, "Ann", Job.CLERIC));
        for (int i = 0; i < count; i++) {
            party.add(ready(seat(901 + i, "C" + i, 25 - i, Job.HUNTER, ANN)));
        }
        return party;
    }

    private static List<LineupPlan.Candidate> withoutPower(List<LineupPlan.Candidate> party) {
        List<LineupPlan.Candidate> cold = new ArrayList<>(party);
        LineupPlan.Candidate last = cold.removeLast();
        cold.add(new LineupPlan.Candidate(last.seat(), last.fit(), last.line(), last.usual(), last.dps(), null));
        cold.set(0, new LineupPlan.Candidate(cold.getFirst().seat(), cold.getFirst().fit(), cold.getFirst().line(),
                cold.getFirst().usual(), 0.0d, null));
        return cold;
    }

    private static LineupPlan.Candidate human(int id, String name, Job job) {
        return new LineupPlan.Candidate(new LineupPlan.Seat(id, name, 25, job.getId(), "", true, 0, null, 103_000_000,
                true, 0L, 0, 0), LineupPlan.Fit.READY, null, false, 0.0d, power(id, true));
    }

    private static LineupPlan.Seat seat(int id, String name, int level, Job job, int owner) {
        return new LineupPlan.Seat(id, name, level, job.getId(), "", false, owner, name.toLowerCase(), 103_000_000, true,
                0L, 0, 0);
    }

    private static LineupPlan.Candidate ready(LineupPlan.Seat seat) {
        return ready(seat, 0.0d);
    }

    private static LineupPlan.Candidate ready(LineupPlan.Seat seat, double dps) {
        return new LineupPlan.Candidate(seat, LineupPlan.Fit.READY, null, false, dps, power(seat.characterId(), false));
    }

    private static AgentPqMemberPower power(int id, boolean human) {
        return new AgentPqMemberPower(id, "m" + id, human, id == ANN, 25, 0, Map.of(), Map.of(), Map.of(),
                Double.MAX_VALUE);
    }

    /** A member hitting PaceTestProfile's Ligators and King Slime for these, every hit landing. */
    private static AgentPqMemberPower power(int id, boolean human, double ligator, double kingSlime) {
        return new AgentPqMemberPower(id, "m" + id, human, id == ANN, 25, 0,
                Map.of(PaceTestProfile.LIGATOR, ligator, PaceTestProfile.KING_SLIME, kingSlime),
                Map.of(PaceTestProfile.LIGATOR, 1.0d, PaceTestProfile.KING_SLIME, 1.0d), Map.of(), Double.MAX_VALUE);
    }

    /** A player beside the leader, hitting for these. */
    private static LineupPlan.Candidate player(int id, String name, Job job, double ligator, double kingSlime) {
        return new LineupPlan.Candidate(new LineupPlan.Seat(id, name, 25, job.getId(), "", true, 0, null, 103_000_000,
                true, 0L, 0, 0), LineupPlan.Fit.READY, null, false, 0.0d, power(id, true, ligator, kingSlime));
    }

    private static List<String> names(List<LineupPlan.Seat> seats) {
        return seats.stream().map(LineupPlan.Seat::name).toList();
    }
}
