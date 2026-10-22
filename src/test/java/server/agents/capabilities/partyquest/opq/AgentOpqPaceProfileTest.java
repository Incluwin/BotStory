package server.agents.capabilities.partyquest.opq;

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
 * P6h OPQ step 4: OPQ as the clear-time estimate sees it, the same six segments for five or six members (the parts the
 * run's pace report cuts), the tower's 45-minute timer from the catalog, Papa Pixie's HP read from Mob.wz, and a
 * reference crew's estimate at the priors.
 *
 * <p>The reference crew (a human leader and five companions at level 55) is written here, per member and per mob: 1500
 * damage a second on Papa Pixie (and on the rooms' and the garden's mobs), every attack landing, ten touches of Papa
 * Pixie to go down. At the priors its garden takes about 300 s and the whole run's median about 1440 s (24 minutes),
 * well inside the timer.</p>
 */
class AgentOpqPaceProfileTest {
    private static final AgentOpqPaceProfile PROFILE = AgentOpqPaceProfile.load(AgentOpqPaceProfile.Census.WZ);
    private static final int LUNAR_PIXIE = 9_300_045;
    private static final int CELLION = 9_300_041;
    private static final int NEPENDEATH = 9_300_048;
    private static final int PAPA_PIXIE = 9_300_039;

    private static AgentPqMemberPower member(int id, double dpsScale, double touches, double hitOnPapaPixie) {
        double dps = 1500.0d * dpsScale;
        return new AgentPqMemberPower(id, "m" + id, id == 1, id == 1, 55, 110,
                Map.of(LUNAR_PIXIE, dps, CELLION, dps, NEPENDEATH, dps, PAPA_PIXIE, dps),
                Map.of(LUNAR_PIXIE, 1.0d, CELLION, 1.0d, NEPENDEATH, 1.0d, PAPA_PIXIE, hitOnPapaPixie),
                Map.of(), touches);
    }

    private static List<AgentPqMemberPower> crew(double dpsScale) {
        List<AgentPqMemberPower> crew = new ArrayList<>();
        for (int id = 1; id <= 6; id++) crew.add(member(id, dpsScale, 10.0d, 1.0d));
        return crew;
    }

    @Test
    void theSixSegmentsAndWhoseTimeEachIs() {
        assertEquals(Set.of("entrance", "rooms", "statue", "garden", "root", "claim"), PROFILE.terms());
        assertEquals(Set.of("entrance", "root"), PROFILE.leaderTerms());
        List<String> keys = List.of("entrance", "rooms", "statue", "garden", "root", "claim");
        for (int size = 5; size <= 6; size++) assertEquals(keys, PROFILE.segmentKeys(size), "size " + size);
        assertThrows(IllegalArgumentException.class, () -> PROFILE.segments(4));
        assertThrows(IllegalArgumentException.class, () -> PROFILE.segments(7));
        List<String> leaderBounded = new ArrayList<>();
        for (AgentPqPaceSegment segment : PROFILE.segments(6)) {
            if (segment instanceof AgentPqPaceSegment.Fixed fixed && fixed.leaderBounded()) leaderBounded.add(fixed.key());
        }
        assertEquals(List.of("rooms", "statue"), leaderBounded, "the rooms end at his clicks, the statue at his feet");
        assertEquals("opq", PROFILE.questKey());
        assertEquals(2_700_000L, PROFILE.timerMs(), "the catalog's 45 minutes (OrbisPQ.js)");
        assertEquals(5, PROFILE.minSize());
        assertEquals(6, PROFILE.maxSize());
        assertEquals(List.of(LUNAR_PIXIE, CELLION, NEPENDEATH, PAPA_PIXIE), PROFILE.mobIds());
        assertEquals(PAPA_PIXIE, PROFILE.dangerMobId());
        assertEquals(PROFILE.terms(), PROFILE.priors().keySet(), "a prior for every term");
        assertTrue(PROFILE.warmed());
    }

    @Test
    void theGardenIsPapaPixieAsMobWzHasHimAndEveryoneFightsHimTheLeaderIncluded() {
        AgentPqPaceSegment.Clear garden = (AgentPqPaceSegment.Clear) PROFILE.segments(5).get(3);

        assertEquals("garden", garden.key());
        assertEquals(Map.of(PAPA_PIXIE, 1), garden.counts());
        assertEquals(672_000, garden.hp().get(PAPA_PIXIE));
        assertTrue(garden.leaderFights());
        assertEquals(Map.of(1, PAPA_PIXIE, 2, PAPA_PIXIE), AgentOpqPaceProfile.everyoneOnPapaPixie(Map.of(
                1, Map.of(PAPA_PIXIE, 10.0d), 2, Map.of(PAPA_PIXIE, 1.0d, NEPENDEATH, 90.0d), 3, Map.of())));
    }

    @Test
    void theRunsPaceReportCutsExactlyTheProfilesSegments() {
        AgentOpqSession session = new AgentOpqSession(AgentOpqSession.Mode.HUMAN_ASSISTED, 7L, 1, 6, 1_000L);
        long at = 10_000L;
        for (AgentOpqSession.Phase phase : List.of(AgentOpqSession.Phase.ENTRANCE, AgentOpqSession.Phase.SPLIT_ROOMS,
                AgentOpqSession.Phase.RESTORING_STATUE, AgentOpqSession.Phase.GARDEN,
                AgentOpqSession.Phase.RESTORING_MINERVA, AgentOpqSession.Phase.CLAIMING_REWARD,
                AgentOpqSession.Phase.COMPLETED)) {
            session.transition(phase, at);
            at += 60_000L;
        }

        assertEquals(PROFILE.segmentKeys(6), AgentOpqPaceReport.spans(session, at).stream()
                .map(AgentOpqPaceReport.SpanReport::key).toList(), "so the calibration learns every span it records");
        assertEquals(AgentOpqPaceProfile.QUEST_KEY, AgentOpqPaceReport.QUEST_KEY);
    }

    @Test
    void theReferenceCrewFreesMinervaWellInsideTheTimer() {
        AgentPqPaceEstimate estimate = AgentPqPaceEstimator.estimate(PROFILE, crew(1.0d),
                AgentPqPaceCalibration.prior(PROFILE));

        assertTrue(estimate.clearable());
        assertEquals(300.0d, estimate.segmentMeanS().get("garden"), 300.0d * 0.15, "garden");
        assertTrue(estimate.p50S() >= 1_200.0d && estimate.p50S() <= 1_700.0d, "p50 " + estimate.p50S());
        assertTrue(estimate.overTimerChance() < 0.05d, "over the timer " + estimate.overTimerChance());
        assertEquals(List.of(), estimate.weaknesses());
    }

    @Test
    void theSameCrewAtATenthOfItsDamageIsNotDoneInsideTheTimer() {
        AgentPqPaceEstimate estimate = AgentPqPaceEstimator.estimate(PROFILE, crew(0.1d),
                AgentPqPaceCalibration.prior(PROFILE));

        assertTrue(estimate.overTimerChance() > 0.5d, "over the timer " + estimate.overTimerChance());
    }

    @Test
    void aFragileCompanionAndOneThatMissesPapaPixieAreNamed() {
        List<AgentPqMemberPower> crew = new ArrayList<>(crew(1.0d));
        crew.set(1, member(2, 1.0d, 4.9d, 1.0d));
        crew.set(2, member(3, 1.0d, 10.0d, 0.59d));

        AgentPqPaceEstimate estimate = AgentPqPaceEstimator.estimate(PROFILE, crew,
                AgentPqPaceCalibration.prior(PROFILE));

        assertTrue(estimate.weaknesses().contains(new AgentPqPaceEstimate.Weakness(2, PAPA_PIXIE, "garden",
                AgentPqPaceEstimate.Kind.FRAGILE)), estimate.weaknesses().toString());
        assertTrue(estimate.weaknesses().contains(new AgentPqPaceEstimate.Weakness(3, PAPA_PIXIE, "garden",
                AgentPqPaceEstimate.Kind.MISSES)), estimate.weaknesses().toString());
        assertFalse(estimate.weaknesses().stream().anyMatch(weakness -> weakness.characterId() == 4));
    }

    @Test
    void aCensusWithNoHpForAMobLeavesTheProfileUnbuilt() {
        assertThrows(IllegalStateException.class, () -> AgentOpqPaceProfile.load(mobId -> 0));
        assertThrows(IllegalStateException.class,
                () -> AgentOpqPaceProfile.load(mobId -> mobId == PAPA_PIXIE ? 0 : 7_300));
        assertEquals(672_000, AgentOpqPaceProfile.Census.WZ.hp(PAPA_PIXIE));
        assertEquals(0, AgentOpqPaceProfile.Census.WZ.hp(1_234_567), "no such mob");
    }
}
