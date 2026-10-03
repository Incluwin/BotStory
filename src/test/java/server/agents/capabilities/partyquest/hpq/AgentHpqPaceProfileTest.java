package server.agents.capabilities.partyquest.hpq;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import provider.Data;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * HPQ as the clear-time estimate sees it (plan step 4): Tory's Yes, the six seeds, the Moon Bunny's ten cakes and the
 * leader's hand-in at Growlie, the same four fixed segments for every party size. The bunny's prior is content: ten
 * cakes at the Moon Bunny's drop period over three (MapleMap's Moon Bunny timer), 200 s from its Mob.wz period.
 */
class AgentHpqPaceProfileTest {
    private static final List<String> SPAN_KEYS = List.of("yes", "seeds", "bunny", "handin");
    private static final int FLYEYE = 9_300_062;
    private static final int STIRGE = 9_300_063;
    private static final int GOBLIN_FIRE = 9_300_064;

    /** The Moon Bunny's drop period as LifeFactory reads it: Mob.wz info/dropItemPeriod, in tens of seconds. */
    private static int mobWzDropPeriodMs() {
        Data info = DataProviderFactory.getDataProvider(WZFiles.MOB).getData("9300061.img").getChildByPath("info");
        return DataTool.getIntConvert("dropItemPeriod", info, 0) * 10_000;
    }

    /** load() with LifeFactory stubbed on this thread (it needs the database), its bunny on the Mob.wz period. */
    private static AgentHpqPaceProfile loadFromLifeFactory(int bunnyDropPeriodMs, MockedStatic<LifeFactory> life) {
        life.when(() -> LifeFactory.getMonster(anyInt())).thenAnswer(call -> {
            Monster monster = mock(Monster.class);
            when(monster.getDropPeriodTime()).thenReturn(
                    (int) call.getArgument(0) == AgentHpqDefinition.MOON_BUNNY ? bunnyDropPeriodMs : 0);
            return monster;
        });
        return AgentHpqPaceProfile.load();
    }

    @Test
    void everyPartySizeRunsTheSameFourFixedSegments() {
        AgentHpqPaceProfile profile = AgentHpqPaceProfile.load(60_000L);

        assertEquals("hpq", profile.questKey());
        assertTrue(profile.warmed());
        assertEquals(600_000L, profile.timerMs());
        assertEquals(3, profile.minSize());
        assertEquals(6, profile.maxSize());
        assertEquals(1, profile.epoch());
        assertEquals(List.of(FLYEYE, STIRGE, GOBLIN_FIRE), profile.mobIds(), "stat proxies for 9300081-83");
        assertEquals(GOBLIN_FIRE, profile.dangerMobId());
        List<AgentPqPaceSegment> expected = List.of(
                new AgentPqPaceSegment.Fixed("yes", AgentPqPaceSegment.Side.COMPANION, "yes", false),
                new AgentPqPaceSegment.Fixed("seeds", AgentPqPaceSegment.Side.COMPANION, "seeds", false),
                new AgentPqPaceSegment.Fixed("bunny", AgentPqPaceSegment.Side.COMPANION, "bunny", false),
                new AgentPqPaceSegment.Fixed("handin", AgentPqPaceSegment.Side.LEADER, "handin", true));
        for (int size = 3; size <= 6; size++) {
            assertEquals(expected, profile.segments(size), "size " + size);
            assertEquals(SPAN_KEYS, profile.segmentKeys(size), "size " + size);
        }
        assertThrows(IllegalArgumentException.class, () -> profile.segments(2));
        assertThrows(IllegalArgumentException.class, () -> profile.segments(7));
        AgentPqPaceProfile.Thresholds thresholds = profile.thresholds();
        assertEquals(7.0d, thresholds.fragileTouches(), 1e-12);
        assertEquals(0.6d, thresholds.weakHitChance(), 1e-12);
        assertEquals(Map.of(), thresholds.slowMs());
    }

    @Test
    void theBunnysPriorIsTenCakesAtAThirdOfItsMobWzDropPeriod() {
        int periodMs = mobWzDropPeriodMs();
        assertEquals(60_000, periodMs, "Mob.wz 9300061 dropItemPeriod 6");
        AgentHpqPaceProfile profile;
        try (MockedStatic<LifeFactory> life = mockStatic(LifeFactory.class)) {
            profile = loadFromLifeFactory(periodMs, life);
            // The warm-up loads the Moon Bunny and every profile mob into LifeFactory, on its own thread.
            life.verify(() -> LifeFactory.getMonster(AgentHpqDefinition.MOON_BUNNY));
            for (int mobId : profile.mobIds()) life.verify(() -> LifeFactory.getMonster(mobId));
        }

        assertEquals(200_000.0d, profile.priors().get("bunny"), 1e-9);
        assertEquals(1_000.0d, profile.priors().get("yes"), 1e-12);
        assertEquals(150_000.0d, profile.priors().get("seeds"), 1e-12);
        assertEquals(40_000.0d, profile.priors().get("handin"), 1e-12);
        assertEquals(100_000.0d, AgentHpqPaceProfile.load(30_000L).priors().get("bunny"), 1e-9,
                "the period is read, not assumed");
    }

    @Test
    void aMoonBunnyWithoutADropPeriodOrAMobLifeFactoryCannotLoadLeavesItUnbuilt() {
        assertThrows(IllegalStateException.class, () -> AgentHpqPaceProfile.load(0L));
        try (MockedStatic<LifeFactory> life = mockStatic(LifeFactory.class)) {
            assertThrows(IllegalStateException.class, () -> loadFromLifeFactory(0, life));
        }
        try (MockedStatic<LifeFactory> life = mockStatic(LifeFactory.class)) {
            life.when(() -> LifeFactory.getMonster(anyInt())).thenAnswer(call -> {
                if ((int) call.getArgument(0) == GOBLIN_FIRE) return null;
                Monster monster = mock(Monster.class);
                when(monster.getDropPeriodTime()).thenReturn(60_000);
                return monster;
            });
            assertThrows(IllegalStateException.class, AgentHpqPaceProfile::load);
        }
    }

    @Test
    void theTermsAreTheFourSegmentsAndOnlyTheHandInIsTheLeaders() {
        AgentHpqPaceProfile profile = AgentHpqPaceProfile.load(60_000L);

        assertEquals(Set.of("yes", "seeds", "bunny", "handin"), profile.terms());
        assertEquals(Set.of("handin"), profile.leaderTerms());
        assertEquals(profile.terms(), profile.priors().keySet());
        AgentPqPaceCalibration prior = AgentPqPaceCalibration.prior(profile);
        assertEquals(Set.of("handin"), prior.leader().keySet());
        assertEquals(Set.of("yes", "seeds", "bunny"), prior.companion().keySet());
        assertEquals(profile.epoch(), prior.epoch());
    }

    @Test
    void aFourMemberLineupAtThePriorsClearsInAboutSixAndAHalfMinutes() {
        AgentHpqPaceProfile profile = AgentHpqPaceProfile.load(60_000L);
        List<AgentPqMemberPower> four = List.of(member(1, true, true), member(2, false, false),
                member(3, false, false), member(4, false, false));

        AgentPqPaceEstimate estimate = AgentPqPaceEstimator.estimate(profile, four,
                AgentPqPaceCalibration.prior(profile));

        assertTrue(estimate.clearable());
        // 1 + 150 + 200 + 40 s, on the estimate's 5 s grid.
        assertEquals(391.0d, estimate.p50S(), 5.0d);
        assertEquals(391.0d, estimate.meanS(), 5.0d);
        assertTrue(estimate.overTimerChance() < 0.01d, "well inside the ten-minute timer");
        assertEquals(1.0d, estimate.segmentMeanS().get("yes"), 1e-9);
        assertEquals(200.0d, estimate.segmentMeanS().get("bunny"), 1e-9);
        assertEquals(List.of(), estimate.weaknesses());
        // Three to six members are all priced; the profile takes no other size.
        for (int size = 3; size <= 6; size++) {
            List<AgentPqMemberPower> lineup = new ArrayList<>();
            for (int id = 1; id <= size; id++) lineup.add(member(id, id == 1, id == 1));
            assertTrue(AgentPqPaceEstimator.estimate(profile, lineup, AgentPqPaceCalibration.prior(profile))
                    .clearable(), "size " + size);
        }
    }

    /**
     * A companion that goes down in fewer than seven Goblin Fire touches is fragile; the human leader is never named.
     * HPQ has no kill segment, so a member's hit chance names nothing (the estimator weighs it on quotas and clears).
     */
    @Test
    void aCompanionThatFallsToFewGoblinFireTouchesIsFragile() {
        AgentHpqPaceProfile profile = AgentHpqPaceProfile.load(60_000L);
        AgentPqMemberPower weak = new AgentPqMemberPower(2, "m2", false, false, 15, 100,
                Map.of(FLYEYE, 40.0d, STIRGE, 40.0d, GOBLIN_FIRE, 40.0d),
                Map.of(FLYEYE, 0.95d, STIRGE, 0.95d, GOBLIN_FIRE, 0.4d),
                Map.of(FLYEYE, 800.0d, STIRGE, 800.0d, GOBLIN_FIRE, 800.0d), 4.0d);
        AgentPqMemberPower fragileLeader = new AgentPqMemberPower(1, "m1", true, true, 15, 100,
                Map.of(), Map.of(), Map.of(), 2.0d);

        AgentPqPaceEstimate estimate = AgentPqPaceEstimator.estimate(profile,
                List.of(fragileLeader, weak, member(3, false, false)), AgentPqPaceCalibration.prior(profile));

        assertEquals(List.of(new AgentPqPaceEstimate.Weakness(2, GOBLIN_FIRE, "", AgentPqPaceEstimate.Kind.FRAGILE)),
                estimate.weaknesses());
    }

    private static AgentPqMemberPower member(int id, boolean human, boolean leader) {
        return new AgentPqMemberPower(id, "m" + id, human, leader, 30, 100,
                Map.of(FLYEYE, 120.0d, STIRGE, 120.0d, GOBLIN_FIRE, 100.0d),
                Map.of(FLYEYE, 0.95d, STIRGE, 0.95d, GOBLIN_FIRE, 0.9d),
                Map.of(FLYEYE, 800.0d, STIRGE, 800.0d, GOBLIN_FIRE, 800.0d), 20.0d);
    }
}
