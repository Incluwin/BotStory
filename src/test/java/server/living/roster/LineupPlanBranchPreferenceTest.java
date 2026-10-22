package server.living.roster;

import client.Job;
import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.pace.AgentPqMemberPower;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wonky's own choice prefers all five job branches (OPQ plan, user decision O6): OrbisPQ.js blesses a party with a
 * warrior, a magician, a bowman, a thief and a pirate as it goes in. Without a clear time she swaps a companion for one
 * of the same owner's that completes the five; weighed, the quickest lineup with the five wins when it is at most three
 * minutes slower than the quickest of all. A lineup that cannot have the five is chosen as before and never refused;
 * the leader's pick is never changed for them; no entrance but OPQ's has a preference, so every other quest's choice is
 * as before.
 */
class LineupPlanBranchPreferenceTest {
    private static final int ANN = 10;
    private static final int BEN = 20;
    private static final long TIMER_MS = 2_700_000L;
    private static final int KAI = 901;
    private static final int MIRA = 902;
    private static final int PIP = 903;
    private static final int RIN = 904;
    private static final int TOBI = 905;
    private static final int BO = 906;
    private static final int DOV = 907;

    /** The member sets the estimate was asked about. */
    private final List<Set<Integer>> asked = new ArrayList<>();

    // ---- (a) a swap completes the five ---------------------------------------------------------

    @Test
    void withoutAClearTimeSheSwapsOneCompanionForTheFifthBranch() {
        LineupPlan lpq = LineupPlan.build("lpq", ANN, 60, annAndSix(BO, Job.BRAWLER), null); // the same seats, no preference

        assertEquals(List.of("Ann", "Kai", "Rin", "Tobi", "Mira", "Pip"), names(lpq.lineup()),
                "by level and new branches alone, Bo (eight levels off) stays out: no pirate");

        LineupPlan opq = LineupPlan.build("opq", ANN, 60, annAndSix(BO, Job.BRAWLER), null);

        assertEquals(List.of("Ann", "Kai", "Rin", "Tobi", "Mira", "Bo"), names(opq.lineup()),
                "Bo goes in for Pip, the second magician and the last one seated");
        assertEquals(List.of("Pip"), names(opq.extras()));
        assertTrue(OpqEntrance.ALL_FIVE_BRANCHES.metBy(opq.lineup()));
        assertNull(opq.blocker());
        assertTrue(opq.startable());
        assertNull(opq.clearTime());
    }

    @Test
    void weighedTheFiveWinWhenTheyAreAtMostThreeMinutesSlower() {
        LineupPlan plan = LineupPlan.build("opq", ANN, 60, annAndSix(BO, Job.BRAWLER), null,
                pace(members -> members.contains(BO) ? 1090.0d : 1000.0d));

        assertTrue(plan.memberIds().contains(BO), names(plan.lineup()).toString());
        assertTrue(OpqEntrance.ALL_FIVE_BRANCHES.metBy(plan.lineup()));
        assertEquals(1090.0d, plan.clearTime().objectiveS(), 1e-9, "shown with its own time, 90 s over the quickest");
        assertEquals(1090.0d, plan.clearTime().ownObjectiveS(), 1e-9);
        assertEquals(6, asked.size(), "every lineup of five of the six");

        asked.clear();
        LineupPlan edge = LineupPlan.build("opq", ANN, 60, annAndSix(BO, Job.BRAWLER), null,
                pace(members -> members.contains(BO) ? 1180.0d : 1000.0d));

        assertTrue(edge.memberIds().contains(BO), "three minutes over: still the five");
    }

    @Test
    void theClearTimeStillOrdersTheLineupsThatHaveTheFive() {
        // with Bo: 1050 s beside Pip, 1150 s without him; the quickest of all (without Bo) 1000 s
        LineupPlan plan = LineupPlan.build("opq", ANN, 60, annAndSix(BO, Job.BRAWLER), null,
                pace(members -> !members.contains(BO) ? 1000.0d : members.contains(PIP) ? 1050.0d : 1150.0d));

        assertEquals(List.of("Ann", "Kai", "Rin", "Tobi", "Pip", "Bo"), names(plan.lineup()),
                "the quicker of the five-branch lineups (Kai and Mira alike: by persona)");
        assertEquals(1050.0d, plan.clearTime().objectiveS(), 1e-9);
    }

    @Test
    void moreThanThreeMinutesSlowerTheQuickestStays() {
        LineupPlan plan = LineupPlan.build("opq", ANN, 60, annAndSix(BO, Job.BRAWLER), null,
                pace(members -> members.contains(BO) ? 1200.0d : 1000.0d));

        assertEquals(List.of("Ann", "Kai", "Rin", "Tobi", "Mira", "Pip"), names(plan.lineup()),
                "200 s is more than the blessing is worth");
        assertEquals(List.of("Bo"), names(plan.extras()));
        assertEquals(1000.0d, plan.clearTime().objectiveS(), 1e-9);
        assertNull(plan.blocker());
    }

    @Test
    void eachOwnerKeepsItsSeatsWhenTheSwapIsInAnotherOwnersCompanions() {
        // Ann (Cleric) and Ben (Page) lead two each; only Ben has a pirate, Bo, eight levels off
        List<LineupPlan.Candidate> party = List.of(human(ANN, "Ann", Job.CLERIC), human(BEN, "Ben", Job.PAGE),
                ready(seat(KAI, "Kai", 60, Job.CROSSBOWMAN, ANN)), ready(seat(RIN, "Rin", 60, Job.ASSASSIN, ANN)),
                ready(seat(MIRA, "Mira", 60, Job.HUNTER, BEN)), ready(seat(DOV, "Dov", 60, Job.FIGHTER, BEN)),
                ready(seat(BO, "Bo", 52, Job.BRAWLER, BEN)));

        assertEquals(List.of("Ann", "Ben", "Kai", "Dov", "Rin", "Mira"),
                names(LineupPlan.build("lpq", ANN, 60, party, null).lineup()));

        LineupPlan plan = LineupPlan.build("opq", ANN, 60, party, null);

        assertEquals(List.of("Ann", "Ben", "Kai", "Dov", "Rin", "Bo"), names(plan.lineup()), "Bo for Mira, both Ben's");
        assertEquals(2, plan.lineup().stream().filter(seat -> seat.ownerId() == ANN).count());
        assertEquals(2, plan.lineup().stream().filter(seat -> seat.ownerId() == BEN).count());
    }

    // ---- (b) no swap completes the five: as before, never refused --------------------------------

    @Test
    void aBenchThatCannotCompleteTheFiveStillFillsSixSeatsAsBefore() {
        List<LineupPlan.Candidate> noPirate = annAndSix(DOV, Job.FIGHTER);
        LineupPlan lpq = LineupPlan.build("lpq", ANN, 60, noPirate, null);

        LineupPlan opq = LineupPlan.build("opq", ANN, 60, noPirate, null);

        assertEquals(List.of("Ann", "Kai", "Rin", "Tobi", "Mira", "Pip"), names(opq.lineup()));
        assertEquals(lpq.lineup(), opq.lineup());
        assertEquals(lpq.extras(), opq.extras());
        assertFalse(OpqEntrance.ALL_FIVE_BRANCHES.metBy(opq.lineup()));
        assertNull(opq.blocker(), "never refused for a missing branch");
        assertTrue(opq.startable());

        LineupPlan.Pace pace = pace(members -> members.contains(DOV) ? 900.0d : 1000.0d);
        LineupPlan weighedLpq = LineupPlan.build("lpq", ANN, 60, noPirate, null, pace);
        LineupPlan weighedOpq = LineupPlan.build("opq", ANN, 60, noPirate, null, pace);

        assertEquals(weighedLpq.lineup(), weighedOpq.lineup(), "the quickest, as before");
        assertEquals(weighedLpq.clearTime(), weighedOpq.clearTime());
        assertTrue(weighedOpq.memberIds().contains(DOV));
        assertNull(weighedOpq.blocker());
    }

    @Test
    void aPartyOfSixWithoutAPirateGoesInWhole() {
        List<LineupPlan.Candidate> party = List.of(human(ANN, "Ann", Job.CLERIC),
                ready(seat(KAI, "Kai", 60, Job.CROSSBOWMAN, ANN)), ready(seat(MIRA, "Mira", 60, Job.HUNTER, ANN)),
                ready(seat(PIP, "Pip", 60, Job.FP_WIZARD, ANN)), ready(seat(RIN, "Rin", 60, Job.ASSASSIN, ANN)),
                ready(seat(TOBI, "Tobi", 60, Job.PAGE, ANN)));

        LineupPlan plan = LineupPlan.build("opq", ANN, 60, party, null, pace(members -> 1000.0d));

        assertEquals(6, plan.lineup().size());
        assertNull(plan.blocker());
        assertTrue(plan.startable());
    }

    @Test
    void theLeadersPickWithoutTheFiveIsNeverChanged() {
        Set<Integer> picked = Set.of(KAI, MIRA, PIP, RIN, TOBI);

        LineupPlan plain = LineupPlan.build("opq", ANN, 60, annAndSix(BO, Job.BRAWLER), picked);

        assertEquals(Set.of(ANN, KAI, MIRA, PIP, RIN, TOBI), plain.memberIds(), "his pick stays his: no Bo");
        assertEquals(picked, plain.picked());
        assertNull(plain.blocker());
        assertTrue(plain.startable());

        LineupPlan weighed = LineupPlan.build("opq", ANN, 60, annAndSix(BO, Job.BRAWLER), picked,
                pace(members -> members.contains(BO) ? 1090.0d : 1000.0d));

        assertEquals(plain.lineup(), weighed.lineup());
        assertEquals(1000.0d, weighed.clearTime().objectiveS(), 1e-9);
        assertEquals(1090.0d, weighed.clearTime().ownObjectiveS(), 1e-9, "hers to compare with: the five");
        assertNull(weighed.blocker());
    }

    // ---- (c) only OPQ's entrance has a preference --------------------------------------------------

    @Test
    void onlyWonkysEntrancePrefersBranches() {
        for (String key : PqEntrance.keys()) {
            if (!key.equals("opq")) {
                assertNull(PqEntrance.of(key).branchPreference(), key);
            }
        }
        PqEntrance.BranchPreference wonky = PqEntrance.of("opq").branchPreference();
        assertSame(OpqEntrance.ALL_FIVE_BRANCHES, wonky);
        assertEquals(Set.of(1, 2, 3, 4, 5), wonky.branches());
        assertEquals(180_000L, wonky.worthMs());

        LineupPlan epq = LineupPlan.build("epq", ANN, 60, annAndSix(BO, Job.BRAWLER), null); // six seats too
        assertEquals(LineupPlan.build("lpq", ANN, 60, annAndSix(BO, Job.BRAWLER), null).lineup(), epq.lineup());
        assertFalse(epq.memberIds().contains(BO));
    }

    @Test
    void theBranchesAreTheScriptsJobNiches() {
        // OrbisPQ.js isTeamAllJobs reads Job.getJobNiche: a Cygnus Knight or an Aran counts as its adventurer branch
        List<LineupPlan.Seat> knights = List.of(seat(1, "A", 60, Job.DAWNWARRIOR1, ANN), seat(2, "B", 60,
                Job.BLAZEWIZARD1, ANN), seat(3, "C", 60, Job.WINDARCHER1, ANN), seat(4, "D", 60, Job.NIGHTWALKER1, ANN),
                seat(5, "E", 60, Job.THUNDERBREAKER1, ANN));
        assertTrue(OpqEntrance.ALL_FIVE_BRANCHES.metBy(knights));
        assertEquals(0, OpqEntrance.ALL_FIVE_BRANCHES.missing(knights));

        List<LineupPlan.Seat> aran = new ArrayList<>(knights);
        aran.set(0, seat(1, "A", 60, Job.ARAN1, ANN));
        assertTrue(OpqEntrance.ALL_FIVE_BRANCHES.metBy(aran));

        List<LineupPlan.Seat> beginner = new ArrayList<>(knights);
        beginner.set(4, seat(5, "E", 60, Job.BEGINNER, ANN));
        assertEquals(1, OpqEntrance.ALL_FIVE_BRANCHES.missing(beginner), "a beginner has no branch");
    }

    // ---- helpers ---------------------------------------------------------------------------

    /**
     * Ann (Lv60 Cleric) and six of her companions: Kai (a second bowman), Mira, Pip (a second magician), Rin and Tobi
     * at her level, and {@code sixth} eight levels below her.
     */
    private static List<LineupPlan.Candidate> annAndSix(int sixth, Job sixthJob) {
        return List.of(human(ANN, "Ann", Job.CLERIC), ready(seat(KAI, "Kai", 60, Job.CROSSBOWMAN, ANN)),
                ready(seat(MIRA, "Mira", 60, Job.HUNTER, ANN)), ready(seat(PIP, "Pip", 60, Job.FP_WIZARD, ANN)),
                ready(seat(RIN, "Rin", 60, Job.ASSASSIN, ANN)), ready(seat(TOBI, "Tobi", 60, Job.PAGE, ANN)),
                ready(seat(sixth, sixth == BO ? "Bo" : "Dov", 52, sixthJob, ANN)));
    }

    /** The estimate of a member set: a 2 min range around {@code p50S(ids)}, never over the timer. */
    private LineupPlan.Pace pace(ToDoubleFunction<Set<Integer>> p50S) {
        return new LineupPlan.Pace(members -> {
            Set<Integer> ids = new HashSet<>();
            for (AgentPqMemberPower member : members) {
                ids.add(member.characterId());
            }
            asked.add(ids);
            return LineupPlanPaceTest.estimate(p50S.applyAsDouble(ids), 0.0d);
        }, TIMER_MS, companions -> 0.0d);
    }

    private static LineupPlan.Candidate human(int id, String name, Job job) {
        return new LineupPlan.Candidate(new LineupPlan.Seat(id, name, 60, job.getId(), "", true, 0, null, 200_080_101,
                true, 0L, 0, 0), LineupPlan.Fit.READY, null, false, 0.0d, power(id, true));
    }

    private static LineupPlan.Seat seat(int id, String name, int level, Job job, int owner) {
        return new LineupPlan.Seat(id, name, level, job.getId(), "", false, owner, name.toLowerCase(), 200_080_101,
                true, 0L, 0, 0);
    }

    private static LineupPlan.Candidate ready(LineupPlan.Seat seat) {
        return new LineupPlan.Candidate(seat, LineupPlan.Fit.READY, null, false, 0.0d, power(seat.characterId(), false));
    }

    private static AgentPqMemberPower power(int id, boolean human) {
        return new AgentPqMemberPower(id, "m" + id, human, id == ANN, 60, 0, Map.of(), Map.of(), Map.of(),
                Double.MAX_VALUE);
    }

    private static List<String> names(List<LineupPlan.Seat> seats) {
        return seats.stream().map(LineupPlan.Seat::name).toList();
    }
}
