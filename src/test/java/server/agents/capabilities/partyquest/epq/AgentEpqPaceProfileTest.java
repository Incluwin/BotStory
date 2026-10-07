package server.agents.capabilities.partyquest.epq;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.pace.AgentPqMemberPower;
import server.agents.capabilities.partyquest.pace.AgentPqPaceCalibration;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimator;
import server.agents.capabilities.partyquest.pace.AgentPqPaceSegment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Step 5.1: EPQ as the clear-time estimate sees it, the same ten segments for four to six members, its census read
 * from Map.wz and Mob.wz, and a reference crew's estimate in Step 0's range.
 *
 * <p>The reference crew (Step 0's level 48-50 party: a human leader and five companions) is written here, per member
 * and per mob: 1000 damage a second on a Poisoned Lord Tree, 700 / 650 / 550 on the Poison / Charged /
 * Super-Charged Poison Golem, every attack landing, ten touches of the Super-Charged Poison Golem to go down. At the
 * priors its trees take about 175 s (Step 0: 165 s), its golems about 405 s (Step 0: about 410 s), and the whole run's
 * median about 1250 s.</p>
 */
class AgentEpqPaceProfileTest {
    private static final AgentEpqPaceProfile PROFILE = AgentEpqPaceProfile.load(AgentEpqPaceProfile.Census.WZ);
    private static final int TREE = 9_300_172;
    private static final int GOLEM_1 = 9_300_180;
    private static final int GOLEM_2 = 9_300_181;
    private static final int GOLEM_3 = 9_300_182;

    private static AgentPqMemberPower member(int id, double dpsScale, double touches, double hitOnTheLastGolem) {
        return new AgentPqMemberPower(id, "m" + id, id == 1, id == 1, 49, 100,
                Map.of(TREE, 1000.0d * dpsScale, GOLEM_1, 700.0d * dpsScale, GOLEM_2, 650.0d * dpsScale,
                        GOLEM_3, 550.0d * dpsScale),
                Map.of(TREE, 1.0d, GOLEM_1, 1.0d, GOLEM_2, 1.0d, GOLEM_3, hitOnTheLastGolem),
                Map.of(), touches);
    }

    private static List<AgentPqMemberPower> crew(double dpsScale) {
        List<AgentPqMemberPower> crew = new ArrayList<>();
        for (int id = 1; id <= 6; id++) crew.add(member(id, dpsScale, 10.0d, 1.0d));
        return crew;
    }

    @Test
    void theTenSegmentsAndWhoseTimeEachIs() {
        assertEquals(Set.of("yes", "trees", "bottles", "maze", "marbles", "handin", "climb", "sprite", "altar",
                "golem"), PROFILE.terms());
        assertEquals(Set.of("handin", "sprite", "altar"), PROFILE.leaderTerms());
        List<String> keys = List.of("yes", "trees", "bottles", "maze", "marbles", "handin", "climb", "sprite", "altar",
                "golem");
        for (int size = 4; size <= 6; size++) assertEquals(keys, PROFILE.segmentKeys(size), "size " + size);
        assertThrows(IllegalArgumentException.class, () -> PROFILE.segments(3));
        assertThrows(IllegalArgumentException.class, () -> PROFILE.segments(7));
        List<String> leaderBounded = new ArrayList<>();
        for (AgentPqPaceSegment segment : PROFILE.segments(6)) {
            if (segment instanceof AgentPqPaceSegment.Fixed fixed && fixed.leaderBounded()) leaderBounded.add(fixed.key());
        }
        assertEquals(List.of("marbles", "climb"), leaderBounded);
        assertEquals(1_800_000L, PROFILE.timerMs());
        assertEquals(4, PROFILE.minSize());
        assertEquals(6, PROFILE.maxSize());
        assertEquals(List.of(TREE, GOLEM_1, GOLEM_2, GOLEM_3), PROFILE.mobIds());
        assertEquals(GOLEM_3, PROFILE.dangerMobId());
        assertTrue(PROFILE.warmed());
    }

    @Test
    void theFightsAreTheWzCensusAndEveryoneFightsThemTheLeaderIncluded() {
        AgentPqPaceSegment.Clear trees = (AgentPqPaceSegment.Clear) PROFILE.segments(4).get(1);
        AgentPqPaceSegment.Clear golem = (AgentPqPaceSegment.Clear) PROFILE.segments(4).get(9);

        assertEquals(Map.of(TREE, 50), trees.counts());
        assertEquals(6_100, trees.hp().get(TREE));
        assertEquals(Map.of(GOLEM_1, 1, GOLEM_2, 1, GOLEM_3, 1), golem.counts());
        assertEquals(63_000, golem.hp().get(GOLEM_1));
        assertEquals(83_000, golem.hp().get(GOLEM_2));
        assertEquals(113_500, golem.hp().get(GOLEM_3));
        assertTrue(trees.leaderFights());
        assertTrue(golem.leaderFights());
    }

    @Test
    void theGolemChainPutsEveryoneOnTheLowestLiveGolem() {
        assertEquals(Map.of(1, GOLEM_1, 2, GOLEM_1), AgentEpqPaceProfile.everyoneOnTheLowest(Map.of(
                1, Map.of(GOLEM_1, 10.0d, GOLEM_2, 90.0d, GOLEM_3, 5.0d),
                2, Map.of(GOLEM_3, 1.0d, GOLEM_1, 1.0d))));
        assertEquals(Map.of(1, GOLEM_2), AgentEpqPaceProfile.everyoneOnTheLowest(Map.of(
                1, Map.of(GOLEM_2, 10.0d, GOLEM_3, 90.0d), 2, Map.of())));
        assertEquals(Map.of(), AgentEpqPaceProfile.everyoneOnTheLowest(Map.of()));
    }

    @Test
    void theReferenceCrewClearsInStepZerosRange() {
        AgentPqPaceEstimate estimate = AgentPqPaceEstimator.estimate(PROFILE, crew(1.0d),
                AgentPqPaceCalibration.prior(PROFILE));

        assertTrue(estimate.clearable());
        assertTrue(estimate.p50S() >= 1_020.0d && estimate.p50S() <= 1_620.0d, "p50 " + estimate.p50S());
        assertEquals(165.0d, estimate.segmentMeanS().get("trees"), 165.0d * 0.15, "trees");
        assertEquals(410.0d, estimate.segmentMeanS().get("golem"), 410.0d * 0.15, "golem");
        assertEquals(List.of(), estimate.weaknesses());
    }

    @Test
    void theSameCrewAtATenthOfItsDamageIsNotDoneInsideTheTimer() {
        AgentPqPaceEstimate estimate = AgentPqPaceEstimator.estimate(PROFILE, crew(0.1d),
                AgentPqPaceCalibration.prior(PROFILE));

        assertTrue(estimate.overTimerChance() > 0.5d, "over the timer " + estimate.overTimerChance());
    }

    @Test
    void aFragileCompanionAndOneThatMissesTheLastGolemAreNamed() {
        List<AgentPqMemberPower> crew = new ArrayList<>(crew(1.0d));
        crew.set(1, member(2, 1.0d, 4.9d, 1.0d));
        crew.set(2, member(3, 1.0d, 10.0d, 0.59d));

        AgentPqPaceEstimate estimate = AgentPqPaceEstimator.estimate(PROFILE, crew,
                AgentPqPaceCalibration.prior(PROFILE));

        assertTrue(estimate.weaknesses().contains(new AgentPqPaceEstimate.Weakness(2, GOLEM_3, "golem",
                AgentPqPaceEstimate.Kind.FRAGILE)), estimate.weaknesses().toString());
        assertTrue(estimate.weaknesses().contains(new AgentPqPaceEstimate.Weakness(3, GOLEM_3, "golem",
                AgentPqPaceEstimate.Kind.MISSES)), estimate.weaknesses().toString());
        assertFalse(estimate.weaknesses().stream().anyMatch(weakness -> weakness.characterId() == 4));
    }

    @Test
    void aCensusWithNoTreesLeavesTheProfileUnbuilt() {
        AgentEpqPaceProfile.Census noTrees = new AgentEpqPaceProfile.Census() {
            @Override
            public Map<Integer, Integer> mobCounts(int mapId) {
                return Map.of();
            }

            @Override
            public MobFacts mob(int mobId) {
                return new MobFacts(1_000, false);
            }
        };
        assertThrows(IllegalStateException.class, () -> AgentEpqPaceProfile.load(noTrees));
    }
}
