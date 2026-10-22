package server.agents.capabilities.partyquest.lmpq;

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
 * P6h Step 4: LMPQ as the clear-time estimate sees it, for three to six members: the farm (a Quota of 30-36 coupons
 * on box mob 9400211, so more seats farm faster) run alongside the leader's walk, then the hand-in at his feet, his
 * talk to Pietri and the claims; the maze's 15-minute timer from the catalog; HP read from Mob.wz; and a reference
 * crew's estimate at the priors, inside the timer with three seats and with six.
 *
 * <p>The reference crew (a human leader and companions at level 55) is written here: 1500 damage a second on the box
 * mobs, every attack landing, ten touches of Tauromacis to go down. At the priors six seats farm 33 coupons in about two
 * minutes and three in about five; the whole run's median stays well inside fifteen minutes either way.</p>
 */
class AgentLmpqPaceProfileTest {
    private static final AgentLmpqPaceProfile PROFILE = AgentLmpqPaceProfile.load(AgentLmpqPaceProfile.Census.WZ);
    private static final int BOX_MOB = 9_400_211;
    private static final int TAUROMACIS = 9_400_218;

    private static AgentPqMemberPower member(int id, double dpsScale, double touches, double hit) {
        double dps = 1500.0d * dpsScale;
        Map<Integer, Double> damage = Map.of(BOX_MOB, dps, 9_400_213, dps, 9_400_217, dps, TAUROMACIS, dps);
        Map<Integer, Double> hits = Map.of(BOX_MOB, hit, 9_400_213, 1.0d, 9_400_217, 1.0d, TAUROMACIS, 1.0d);
        return new AgentPqMemberPower(id, "m" + id, id == 1, id == 1, 55, 110, damage, hits, Map.of(), touches);
    }

    private static List<AgentPqMemberPower> crew(int seats, double dpsScale) {
        List<AgentPqMemberPower> crew = new ArrayList<>();
        for (int id = 1; id <= seats; id++) crew.add(member(id, dpsScale, 10.0d, 1.0d));
        return crew;
    }

    @Test
    void theSegmentsAndWhoseTimeEachIs() {
        assertEquals(Set.of("farm.seek", "farm.combat", "walk", "handin", "pietri", "claim"), PROFILE.terms());
        assertEquals(Set.of("walk", "pietri"), PROFILE.leaderTerms());
        for (int size = 3; size <= 6; size++) {
            assertEquals(List.of("farm", "walk", "handin", "pietri", "claim"), PROFILE.segmentKeys(size),
                    "size " + size);
        }
        assertThrows(IllegalArgumentException.class, () -> PROFILE.segments(2));
        assertThrows(IllegalArgumentException.class, () -> PROFILE.segments(7));
        AgentPqPaceSegment.Parallel maze = (AgentPqPaceSegment.Parallel) PROFILE.segments(6).getFirst();
        assertEquals("maze", maze.key());
        AgentPqPaceSegment.Quota farm = (AgentPqPaceSegment.Quota) maze.branches().get(0).getFirst();
        assertEquals(BOX_MOB, farm.mobId());
        assertEquals(4_800, farm.mobHp(), "Mob.wz 9400211");
        assertEquals(30, farm.kills().min());
        assertEquals(36, farm.kills().max());
        assertEquals(0.25d, farm.leaderCredit(), 1e-9, "he walks, and farms only on the way");
        AgentPqPaceSegment.Fixed handin = (AgentPqPaceSegment.Fixed) PROFILE.segments(6).get(1);
        assertTrue(handin.leaderBounded(), "the coupons end at his feet");
        assertEquals("lmpq", PROFILE.questKey());
        assertEquals(900_000L, PROFILE.timerMs(), "the catalog's 15 minutes (LudiMazePQ.js)");
        assertEquals(3, PROFILE.minSize());
        assertEquals(6, PROFILE.maxSize());
        assertEquals(List.of(BOX_MOB, 9_400_213, 9_400_217, TAUROMACIS), PROFILE.mobIds());
        assertEquals(TAUROMACIS, PROFILE.dangerMobId());
        assertEquals(PROFILE.terms(), PROFILE.priors().keySet(), "a prior for every term");
        assertTrue(PROFILE.warmed());
    }

    @Test
    void theRunsPaceReportCutsExactlyTheProfilesSegments() {
        AgentLmpqSession session = new AgentLmpqSession(AgentLmpqSession.Mode.HUMAN_LEADER, 7L, 1, 3, 1_000L);
        long at = 10_000L;
        session.transition(AgentLmpqSession.Phase.FARMING, at);
        session.noteLeaderArrived(at + 30_000L);
        for (AgentLmpqSession.Phase phase : List.of(AgentLmpqSession.Phase.REGROUPING,
                AgentLmpqSession.Phase.CLEARING, AgentLmpqSession.Phase.REWARD)) {
            at += 60_000L;
            session.transition(phase, at);
        }
        session.complete(at + 60_000L);

        assertEquals(PROFILE.segmentKeys(3), AgentLmpqPaceReport.spans(session, at + 60_000L).stream()
                .map(AgentLmpqPaceReport.SpanReport::key).toList(), "so the calibration learns every span it records");
        assertEquals(AgentLmpqPaceProfile.QUEST_KEY, AgentLmpqPaceReport.QUEST_KEY);
    }

    @Test
    void theReferenceCrewClearsWellInsideTheTimerWithSixSeatsAndWithThree() {
        AgentPqPaceEstimate six = AgentPqPaceEstimator.estimate(PROFILE, crew(6, 1.0d),
                AgentPqPaceCalibration.prior(PROFILE));
        AgentPqPaceEstimate three = AgentPqPaceEstimator.estimate(PROFILE, crew(3, 1.0d),
                AgentPqPaceCalibration.prior(PROFILE));

        assertTrue(six.clearable() && three.clearable());
        assertTrue(six.p50S() < 900.0d && three.p50S() < 900.0d, six.p50S() + " / " + three.p50S());
        assertTrue(three.p50S() > six.p50S(), "more seats farm faster: " + six.p50S() + " / " + three.p50S());
        assertTrue(six.overTimerChance() < 0.05d && three.overTimerChance() < 0.05d,
                six.overTimerChance() + " / " + three.overTimerChance());
        assertEquals(List.of(), six.weaknesses());
    }

    @Test
    void theSameCrewAtATenthOfItsDamageIsSlower() {
        AgentPqPaceEstimate strong = AgentPqPaceEstimator.estimate(PROFILE, crew(3, 1.0d),
                AgentPqPaceCalibration.prior(PROFILE));
        AgentPqPaceEstimate weak = AgentPqPaceEstimator.estimate(PROFILE, crew(3, 0.1d),
                AgentPqPaceCalibration.prior(PROFILE));

        assertTrue(weak.p50S() > strong.p50S() + 60.0d, strong.p50S() + " / " + weak.p50S());
    }

    @Test
    void aFragileCompanionAndOneThatMissesTheBoxMobsAreNamed() {
        List<AgentPqMemberPower> crew = new ArrayList<>(crew(6, 1.0d));
        crew.set(1, member(2, 1.0d, 4.9d, 1.0d));
        crew.set(2, member(3, 1.0d, 10.0d, 0.59d));

        AgentPqPaceEstimate estimate = AgentPqPaceEstimator.estimate(PROFILE, crew,
                AgentPqPaceCalibration.prior(PROFILE));

        assertTrue(estimate.weaknesses().stream().anyMatch(weakness -> weakness.characterId() == 2
                && weakness.kind() == AgentPqPaceEstimate.Kind.FRAGILE), estimate.weaknesses().toString());
        assertTrue(estimate.weaknesses().stream().anyMatch(weakness -> weakness.characterId() == 3
                && weakness.kind() == AgentPqPaceEstimate.Kind.MISSES && weakness.mobId() == BOX_MOB),
                estimate.weaknesses().toString());
        assertFalse(estimate.weaknesses().stream().anyMatch(weakness -> weakness.characterId() == 4));
    }

    @Test
    void aCensusWithNoHpForAMobLeavesTheProfileUnbuilt() {
        assertThrows(IllegalStateException.class, () -> AgentLmpqPaceProfile.load(mobId -> 0));
        assertThrows(IllegalStateException.class,
                () -> AgentLmpqPaceProfile.load(mobId -> mobId == TAUROMACIS ? 0 : 4_800));
        assertEquals(16_000, AgentLmpqPaceProfile.Census.WZ.hp(TAUROMACIS));
        assertEquals(0, AgentLmpqPaceProfile.Census.WZ.hp(1_234_567), "no such mob");
    }
}
