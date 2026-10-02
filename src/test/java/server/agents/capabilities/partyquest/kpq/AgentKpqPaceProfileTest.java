package server.agents.capabilities.partyquest.kpq;

import org.junit.jupiter.api.Test;
import server.agents.capabilities.partyquest.pace.AgentPqPaceCalibration;
import server.agents.capabilities.partyquest.pace.AgentPqPaceSegment;
import server.agents.capabilities.partyquest.pace.Pmf;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KPQ's pace profile reads its content where it lives: the coupon answers from AgentKpqDefinition (Cloto's table), the
 * puzzle combinations from AgentKpqCombinationOrder, stage 5's mobs from the Map.wz census of 103000804 and the timer
 * from the catalog. The span keys pinned here are the contract a run's pace report must produce.
 */
class AgentKpqPaceProfileTest {
    /** Map.wz and Mob.wz read directly (LifeFactory needs the database for drops, which tests do not have). */
    private static final AgentKpqPaceProfile PROFILE = AgentKpqPaceProfile.load(AgentKpqPaceProfile.Census.WZ);
    private static final List<String> SPAN_KEYS = List.of(
            "yes", "s1.open", "s1.grind", "s1.handin",
            "s2.entry", "s2.checks", "s2.accept", "s3.entry", "s3.checks", "s3.accept", "s4.entry", "s4.checks",
            "s5.move", "s5.descent", "s5.normals", "s5.boss", "s5.loot", "s5.carry", "s5.climb", "s5.talks");

    @Test
    void stageOneKillsAreTheSumOfTheNonLeadersCouponAnswers() {
        Pmf three = quota(3).kills();
        Pmf four = quota(4).kills();

        assertEquals(2.0d * 153.0d / 7.0d, three.mean(), 1e-9);
        assertEquals(28, three.quantile(0.1d));
        assertEquals(45, three.quantile(0.5d));
        assertEquals(60, three.quantile(0.9d));
        assertEquals(3.0d * 153.0d / 7.0d, four.mean(), 1e-9);
        assertEquals(43, four.quantile(0.1d));
        assertEquals(65, four.quantile(0.5d));
        assertEquals(85, four.quantile(0.9d));
        assertEquals(9_300_001, quota(3).mobId());
        assertEquals(1_000, quota(3).mobHp());
        assertEquals(0.5d, quota(3).leaderCredit(), 1e-12);
    }

    @Test
    void puzzlesTryEveryCombinationInOrderAndStageFourHasNoAcceptOfItsOwn() {
        int[] combinations = {4, 10, 20};
        double[] expectedChecks = {2.5d, 5.5d, 10.5d};
        for (int stage = 2; stage <= 4; stage++) {
            AgentPqPaceSegment.Puzzle puzzle = (AgentPqPaceSegment.Puzzle) segment(4,
                    AgentKpqPaceProfile.checksKey(stage));
            assertEquals(combinations[stage - 2], puzzle.combinations());
            assertEquals(expectedChecks[stage - 2], puzzle.checks().mean(), 1e-12);
            assertEquals(AgentKpqPaceProfile.PUZZLE_CHECK_TERM, puzzle.checkTerm());
        }

        List<String> topLevel = PROFILE.segments(4).stream().map(AgentPqPaceSegment::key).toList();
        assertFalse(topLevel.contains("s4.accept"));
        assertEquals(List.of("s4.checks", "s5.race", "s5.talks"), topLevel.subList(topLevel.size() - 3, topLevel.size()));
        AgentPqPaceSegment.Parallel race = (AgentPqPaceSegment.Parallel) segment(4, "s5.race");
        assertEquals(List.of(new AgentPqPaceSegment.Fixed("s5.climb", AgentPqPaceSegment.Side.LEADER)),
                race.branches().get(1));
    }

    @Test
    void stageFiveIsTheMapWzCensusFoughtByTheCompanionsAlone() {
        AgentPqPaceSegment.Clear normals = (AgentPqPaceSegment.Clear) segment(4, "s5.normals");
        AgentPqPaceSegment.Clear boss = (AgentPqPaceSegment.Clear) segment(4, "s5.boss");

        assertEquals(Map.of(9_300_000, 6, 9_300_002, 3), normals.counts());
        assertEquals(Map.of(9_300_003, 1), boss.counts());
        assertEquals(Map.of(9_300_000, 320, 9_300_002, 2_000, 9_300_003, 8_000), normals.hp());
        assertFalse(normals.leaderFights());
        assertFalse(boss.leaderFights());
        assertEquals(List.of(9_300_001, 9_300_000, 9_300_002, 9_300_003), PROFILE.mobIds());
        assertEquals(9_300_003, PROFILE.dangerMobId());
        assertEquals(1_800_000L, PROFILE.timerMs());
        assertEquals(3, PROFILE.minSize());
        assertEquals(4, PROFILE.maxSize());
        assertTrue(PROFILE.warmed());
    }

    @Test
    void bothPartySizesReportTheSameSpanKeys() {
        assertEquals(SPAN_KEYS, PROFILE.segmentKeys(3));
        assertEquals(SPAN_KEYS, PROFILE.segmentKeys(4));
        assertThrows(IllegalArgumentException.class, () -> PROFILE.segments(5));
    }

    @Test
    void theLeadersMoveShareIsReplayedFromTheCombinationOrder() {
        for (int stage = 2; stage <= 4; stage++) {
            List<List<Integer>> order = AgentKpqCombinationOrder.forPositionCount(
                    AgentKpqDefinition.combinationStage(stage).positions().size());
            int[] replayed = replayWithTheCoordinator(order);
            assertArrayEquals(replayed, AgentKpqPaceProfile.movesPerRank(order), "stage " + stage);
            assertEquals(order.size() - 1, replayed[0] + replayed[1] + replayed[2], "one mover per change");

            // The configured ratios are 1/1/1, so the leader's share is a third of the changes in a party of three.
            AgentPqPaceSegment.Puzzle three = (AgentPqPaceSegment.Puzzle) segment(3,
                    AgentKpqPaceProfile.checksKey(stage));
            AgentPqPaceSegment.Puzzle four = (AgentPqPaceSegment.Puzzle) segment(4,
                    AgentKpqPaceProfile.checksKey(stage));
            assertEquals(1.0d / 3.0d, three.leaderMoveShare(), 1e-12, "stage " + stage);
            assertEquals(0.0d, four.leaderMoveShare(), 1e-12, "stage " + stage);
            assertEquals(replayed[0] / (double) (order.size() - 1),
                    AgentKpqPaceProfile.leaderMoveShare(order, new double[]{1.0d, 0.0d, 0.0d}), 1e-12);
            assertEquals(replayed[2] / (double) (order.size() - 1),
                    AgentKpqPaceProfile.leaderMoveShare(order, new double[]{0.0d, 0.0d, 2.0d}), 1e-12);
        }
    }

    @Test
    void everyTermHasItsYamlPriorAndTheLeaderTermsAreTheHumansOwn() {
        assertEquals(PROFILE.terms(), PROFILE.priors().keySet());
        assertEquals(Set.of("s1.handin", "puzzle.entry", "puzzle.check", "puzzle.move", "puzzle.accept", "s5.climb",
                "s5.talks"), PROFILE.leaderTerms());
        assertEquals(0.60d, PROFILE.priors().get("s1.grind.combat"), 1e-12);
        assertEquals(15_800.0d, PROFILE.priors().get("s1.grind.seek"), 1e-12);
        assertEquals(0.19d, PROFILE.priors().get("s5.normals"), 1e-12);
        assertEquals(0.23d, PROFILE.priors().get("s5.boss"), 1e-12);
        assertEquals(30_000.0d, PROFILE.priors().get("s5.descent"), 1e-12);
        assertEquals(3_000.0d, PROFILE.priors().get("s5.loot"), 1e-12, "the coordinator's King Slime loot delay");
        AgentPqPaceCalibration prior = AgentPqPaceCalibration.prior(PROFILE);
        assertEquals(PROFILE.leaderTerms(), prior.leader().keySet());
        assertEquals(PROFILE.epoch(), prior.epoch());
    }

    @Test
    void aCensusThatIsNotKpqsLeavesTheProfileUnbuilt() {
        AgentKpqPaceProfile.Census twoTypes = census(Map.of(9_300_001, 22, 9_300_000, 1),
                Map.of(9_300_000, 6, 9_300_003, 1));
        AgentKpqPaceProfile.Census noBoss = census(Map.of(9_300_001, 22), Map.of(9_300_000, 6, 9_300_002, 3));
        AgentKpqPaceProfile.Census stub = census(Map.of(9_300_001, 22), Map.of(9_300_000, 6, 9_300_003, 1));

        assertThrows(IllegalStateException.class, () -> AgentKpqPaceProfile.load(twoTypes));
        assertThrows(IllegalStateException.class, () -> AgentKpqPaceProfile.load(noBoss));
        AgentKpqPaceProfile fromStub = AgentKpqPaceProfile.load(stub);
        assertEquals(Map.of(9_300_000, 6), ((AgentPqPaceSegment.Clear) segmentOf(fromStub.segments(3), "s5.normals"))
                .counts());
    }

    /** Each change of combination through the coordinator's own formation assignment; moves counted per rank. */
    private static int[] replayWithTheCoordinator(List<List<Integer>> order) {
        List<AgentKpqMemberState> ranks = new ArrayList<>();
        for (int rank = 0; rank < 3; rank++) {
            AgentKpqMemberState member = new AgentKpqMemberState(100 + rank, AgentKpqMemberState.MemberType.AGENT,
                    rank + 1);
            member.setAssignedPosition(order.getFirst().get(rank));
            ranks.add(member);
        }
        int[] moves = new int[3];
        for (int attempt = 1; attempt < order.size(); attempt++) {
            for (AgentKpqMemberState mover : AgentKpqCoordinator.assignFormation(ranks, order.get(attempt), 7L,
                    attempt, 1_000L)) {
                moves[ranks.indexOf(mover)]++;
            }
            assertEquals(new TreeSet<>(order.get(attempt)),
                    new TreeSet<>(ranks.stream().map(AgentKpqMemberState::assignedPosition).toList()));
        }
        return moves;
    }

    private static AgentPqPaceSegment.Quota quota(int size) {
        return (AgentPqPaceSegment.Quota) segment(size, "s1.grind");
    }

    private static AgentPqPaceSegment segment(int size, String key) {
        return segmentOf(PROFILE.segments(size), key);
    }

    private static AgentPqPaceSegment segmentOf(List<AgentPqPaceSegment> segments, String key) {
        for (AgentPqPaceSegment segment : segments) {
            if (segment.key().equals(key)) return segment;
            if (segment instanceof AgentPqPaceSegment.Parallel parallel) {
                for (List<AgentPqPaceSegment> branch : parallel.branches()) {
                    for (AgentPqPaceSegment inner : branch) {
                        if (inner.key().equals(key)) return inner;
                    }
                }
            }
        }
        throw new AssertionError("no segment " + key);
    }

    private static AgentKpqPaceProfile.Census census(Map<Integer, Integer> stageOne, Map<Integer, Integer> stageFive) {
        return new AgentKpqPaceProfile.Census() {
            @Override
            public Map<Integer, Integer> mobCounts(int mapId) {
                return mapId == AgentKpqDefinition.STAGE_1_MAP ? stageOne : stageFive;
            }

            @Override
            public AgentKpqPaceProfile.MobFacts mob(int mobId) {
                return new AgentKpqPaceProfile.MobFacts(mobId == 9_300_003 ? 8_000 : 500, mobId == 9_300_003);
            }
        };
    }
}
