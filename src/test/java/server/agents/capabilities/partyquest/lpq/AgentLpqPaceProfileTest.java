package server.agents.capabilities.partyquest.lpq;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.agents.capabilities.partyquest.pace.AgentPqMemberPower;
import server.agents.capabilities.partyquest.pace.AgentPqPaceCalibration;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimate;
import server.agents.capabilities.partyquest.pace.AgentPqPaceEstimator;
import server.agents.capabilities.partyquest.pace.AgentPqPaceProfile;
import server.agents.capabilities.partyquest.pace.AgentPqPaceSegment;
import server.life.LifeFactory;
import server.life.Monster;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * P6g LPQ C4.1: LPQ as the clear-time estimate sees a companion run. Each pass stage is the companions' collection up
 * to the leader's feet, then his hand-in; stage 6 is his climb, stage 8 his checks over the 126 five-of-nine
 * combinations (his own moves count only at five, when he holds a box), stage 9 the Ratz, Alishar (a clear the leader
 * stays out of) and his key. The same keys for five and six; priors from agent-engine.yaml, Alishar's HP from
 * LifeFactory.
 */
class AgentLpqPaceProfileTest {
    private static final List<String> KEYS = List.of("yes", "s1.collect", "s1.handin", "s2.collect", "s2.handin",
            "s3.collect", "s3.handin", "s4.rooms", "s4.handin", "s5.rooms", "s5.handin", "s6.climb", "s7.fight",
            "s7.handin", "s8.entry", "s8.checks", "s9.ratz", "s9.boss", "s9.key");
    private static final int SHADOW_EYE = 9_300_008;
    private static final int DARK_EYE = 9_300_014;
    private static final int ROMBARD = 9_300_010;
    private static final int ALISHAR = 9_300_012;
    private static final long ALISHAR_HP = 125_000L;

    @Test
    void theSegmentsAreTheSameKeysForFiveAndSix() {
        AgentLpqPaceProfile profile = AgentLpqPaceProfile.load(ALISHAR_HP);

        assertEquals("lpq", profile.questKey());
        assertTrue(profile.warmed());
        assertEquals(2_700_000L, profile.timerMs());
        assertEquals(5, profile.minSize());
        assertEquals(6, profile.maxSize());
        assertEquals(1, profile.epoch());
        assertEquals(List.of(SHADOW_EYE, DARK_EYE, ROMBARD, ALISHAR), profile.mobIds());
        assertEquals(ALISHAR, profile.dangerMobId());
        assertEquals(KEYS, profile.segmentKeys(5));
        assertEquals(KEYS, profile.segmentKeys(6));
        assertThrows(IllegalArgumentException.class, () -> profile.segments(4));
        assertThrows(IllegalArgumentException.class, () -> profile.segments(7));
        for (int size = 5; size <= 6; size++) {
            List<AgentPqPaceSegment> segments = profile.segments(size);
            assertEquals(new AgentPqPaceSegment.Fixed("s1.collect", AgentPqPaceSegment.Side.COMPANION, "s1.collect",
                    true), segments.get(1), "the collection ends at his feet");
            assertEquals(new AgentPqPaceSegment.Fixed("s1.handin", AgentPqPaceSegment.Side.LEADER, "handin", false),
                    segments.get(2));
            assertEquals(new AgentPqPaceSegment.Fixed("s6.climb", AgentPqPaceSegment.Side.LEADER, "s6.climb", false),
                    segments.get(11), "the climb is his");
            AgentPqPaceSegment.Puzzle checks = (AgentPqPaceSegment.Puzzle) segments.get(15);
            assertEquals(126, checks.combinations());
            assertEquals("puzzle.check", checks.checkTerm());
            assertEquals("puzzle.move", checks.moveTerm());
            AgentPqPaceSegment.Clear boss = (AgentPqPaceSegment.Clear) segments.get(17);
            assertEquals(Map.of(ALISHAR, 1), boss.counts());
            assertEquals(Map.of(ALISHAR, 125_000), boss.hp());
            assertEquals(false, boss.leaderFights(), "S9 keeps him out of Alishar's reach");
            assertEquals(Map.of(2, ALISHAR, 3, ALISHAR), boss.assigner().assign(Map.of(
                    2, Map.of(ALISHAR, 10.0d), 3, Map.of(ALISHAR, 20.0d))));
        }
        AgentPqPaceProfile.Thresholds thresholds = profile.thresholds();
        assertEquals(3.0d, thresholds.fragileTouches(), 1e-12);
        assertEquals(0.6d, thresholds.weakHitChance(), 1e-12);
        assertEquals(Map.of(), thresholds.slowMs());
    }

    @Test
    void theLeaderMovesOnlyWhenHeHoldsABox() {
        assertEquals(0.0d, AgentLpqPaceProfile.leaderMoveShare(6), 1e-12);
        double five = AgentLpqPaceProfile.leaderMoveShare(5);
        assertTrue(five > 0.0d && five < 1.0d, "share " + five);
        AgentLpqPaceProfile profile = AgentLpqPaceProfile.load(ALISHAR_HP);
        assertEquals(five, ((AgentPqPaceSegment.Puzzle) profile.segments(5).get(15)).leaderMoveShare(), 1e-12);
        assertEquals(0.0d, ((AgentPqPaceSegment.Puzzle) profile.segments(6).get(15)).leaderMoveShare(), 1e-12);
        // A hand replay: the leader (last of five) holds box 5, then box 5 leaves, then stays, then leaves again.
        List<List<Integer>> order = List.of(List.of(1, 2, 3, 4, 5), List.of(1, 2, 3, 4, 6), List.of(1, 2, 3, 5, 6),
                List.of(1, 2, 3, 4, 5));
        assertEquals(2.0d / 3.0d, AgentLpqPaceProfile.leaderMoveShare(order), 1e-12);
    }

    @Test
    void theTermsThePriorsAndTheLeadersTerms() {
        AgentLpqPaceProfile profile = AgentLpqPaceProfile.load(ALISHAR_HP);

        assertEquals(Set.of("yes", "s1.collect", "s2.collect", "s3.collect", "s4.rooms", "s5.rooms", "s7.fight",
                "handin", "s6.climb", "s8.entry", "puzzle.check", "puzzle.move", "s9.ratz", "s9.boss", "s9.key"),
                profile.terms());
        assertEquals(Set.of("handin", "s6.climb", "s8.entry", "puzzle.check", "puzzle.move", "s9.key"),
                profile.leaderTerms());
        assertEquals(profile.terms(), profile.priors().keySet());
        Map<String, Double> priors = profile.priors();
        assertEquals(2_000.0d, priors.get("yes"), 1e-12);
        assertEquals(100_000.0d, priors.get("s1.collect"), 1e-12);
        assertEquals(100_000.0d, priors.get("s2.collect"), 1e-12);
        assertEquals(160_000.0d, priors.get("s3.collect"), 1e-12);
        assertEquals(170_000.0d, priors.get("s4.rooms"), 1e-12);
        assertEquals(240_000.0d, priors.get("s5.rooms"), 1e-12);
        assertEquals(120_000.0d, priors.get("s7.fight"), 1e-12);
        assertEquals(25_000.0d, priors.get("handin"), 1e-12);
        assertEquals(150_000.0d, priors.get("s6.climb"), 1e-12);
        assertEquals(30_000.0d, priors.get("s8.entry"), 1e-12);
        assertEquals(10_000.0d, priors.get("puzzle.check"), 1e-12);
        assertEquals(4_000.0d, priors.get("puzzle.move"), 1e-12);
        assertEquals(15_000.0d, priors.get("s9.ratz"), 1e-12);
        assertEquals(0.5d, priors.get("s9.boss"), 1e-12);
        assertEquals(40_000.0d, priors.get("s9.key"), 1e-12);
        AgentPqPaceCalibration prior = AgentPqPaceCalibration.prior(profile);
        assertEquals(profile.leaderTerms(), prior.leader().keySet());
        assertEquals(profile.epoch(), prior.epoch());
    }

    /**
     * Six Lv50 members, the five companions each at 500 dps on Alishar: 2 + 890 + 6 x 25 + 150 + 30 + 62.5 x 10 + 15
     * + 100 (125,000 HP at half of 2,500 dps) + 40 s, about 33 minutes, inside the 45-minute timer.
     */
    @Test
    void aSixMemberLineupAtThePriorsClearsInsideTheTimer() {
        AgentLpqPaceProfile profile = AgentLpqPaceProfile.load(ALISHAR_HP);
        List<AgentPqMemberPower> six = new ArrayList<>();
        for (int id = 1; id <= 6; id++) six.add(member(id, id == 1));

        AgentPqPaceEstimate estimate = AgentPqPaceEstimator.estimate(profile, six,
                AgentPqPaceCalibration.prior(profile));

        double handSum = 2 + 890 + 6 * 25 + 150 + 30 + 62.5 * 10 + 15 + 100 + 40;
        assertTrue(estimate.clearable());
        assertTrue(estimate.p50S() < 2_700.0d, "p50 " + estimate.p50S());
        assertEquals(handSum, estimate.p50S(), 60.0d);
        assertEquals(100.0d, estimate.segmentMeanS().get("s9.boss"), 5.0d);
        assertEquals(List.of(), estimate.weaknesses());
        List<AgentPqMemberPower> five = six.subList(0, 5);
        assertTrue(AgentPqPaceEstimator.estimate(profile, five, AgentPqPaceCalibration.prior(profile)).clearable());
    }

    @Test
    void loadReadsAlisharFromLifeFactoryAndLoadsEveryMob() {
        try (MockedStatic<LifeFactory> life = mockStatic(LifeFactory.class)) {
            life.when(() -> LifeFactory.getMonster(anyInt())).thenAnswer(call -> {
                Monster monster = mock(Monster.class);
                when(monster.getMaxHp()).thenReturn((int) call.getArgument(0) == ALISHAR ? 100_000L : 7_000L);
                return monster;
            });
            AgentLpqPaceProfile profile = AgentLpqPaceProfile.load();

            assertEquals(Map.of(ALISHAR, 100_000),
                    ((AgentPqPaceSegment.Clear) profile.segments(6).get(17)).hp(), "the HP is read, not assumed");
            for (int mobId : profile.mobIds()) life.verify(() -> LifeFactory.getMonster(mobId), atLeastOnce());
        }
        try (MockedStatic<LifeFactory> life = mockStatic(LifeFactory.class)) {
            life.when(() -> LifeFactory.getMonster(anyInt())).thenAnswer(call -> {
                if ((int) call.getArgument(0) == ROMBARD) return null;
                Monster monster = mock(Monster.class);
                when(monster.getMaxHp()).thenReturn(125_000L);
                return monster;
            });
            assertThrows(IllegalStateException.class, AgentLpqPaceProfile::load);
        }
        assertThrows(IllegalStateException.class, () -> AgentLpqPaceProfile.load(0L));
    }

    private static AgentPqMemberPower member(int id, boolean leader) {
        return new AgentPqMemberPower(id, "m" + id, leader, leader, 50, 100,
                Map.of(SHADOW_EYE, 500.0d, DARK_EYE, 500.0d, ROMBARD, 500.0d, ALISHAR, 500.0d),
                Map.of(SHADOW_EYE, 0.95d, DARK_EYE, 0.95d, ROMBARD, 0.95d, ALISHAR, 0.9d),
                Map.of(SHADOW_EYE, 800.0d, DARK_EYE, 800.0d, ROMBARD, 800.0d, ALISHAR, 800.0d), 20.0d);
    }
}
